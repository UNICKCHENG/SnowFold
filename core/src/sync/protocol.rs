//! 后端请求与签名。传输宿主只发送已经构造好的请求。
use crate::{
    CoreError, SyncBackend, SyncConfig, SyncHeader, SyncRequest, SyncResponse, SyncTransport,
};
use base64::{Engine, engine::general_purpose::STANDARD};
use chrono::{DateTime, Utc};
use hmac::{Hmac, Mac};
use sha2::{Digest, Sha256};
use url::Url;
use zeroize::Zeroizing;

type Result<T> = std::result::Result<T, CoreError>;
pub const MAX_BYTES: usize = 32 * 1024 * 1024;

pub fn validate(config: &SyncConfig) -> Result<()> {
    let url = Url::parse(&config.endpoint).map_err(|_| CoreError::Validation)?;
    let loopback = matches!(url.host_str(), Some("localhost" | "127.0.0.1" | "[::1]"));
    if !(url.scheme() == "https" || (url.scheme() == "http" && loopback))
        || url
            .as_str()
            .split("://")
            .nth(1)
            .unwrap_or("")
            .split('/')
            .next()
            .unwrap_or("")
            .contains('@')
        || url.query().is_some()
        || url.fragment().is_some()
        || url.host_str().is_none()
        || config.username.is_empty()
        || config.username.contains(['\r', '\n', ':'])
        || config.endpoint.chars().any(char::is_control)
        || config.prefix.split('/').any(|s| s == "." || s == "..")
        || config.prefix.contains(['?', '#', '%', '\\'])
        || config.prefix.chars().any(char::is_control)
        || config.prefix.starts_with('/')
        || config.prefix.ends_with('/')
        || config.prefix.contains("//")
        || url.path().contains('%')
        || url.path().contains("//")
    {
        return Err(CoreError::Validation);
    }
    match config.backend {
        SyncBackend::S3 => {
            if config.bucket.is_empty()
                || config.bucket.len() > 63
                || !config
                    .bucket
                    .bytes()
                    .all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'-' || b == b'.')
                || config.bucket.starts_with(['.', '-'])
                || config.bucket.ends_with(['.', '-'])
                || config.region.is_empty()
                || !config
                    .region
                    .bytes()
                    .all(|b| b.is_ascii_alphanumeric() || b == b'-')
            {
                return Err(CoreError::Validation);
            }
        }
        SyncBackend::WebDav => {
            if !config.bucket.is_empty() || !config.region.is_empty() || config.path_style {
                return Err(CoreError::Validation);
            }
        }
    }
    Ok(())
}

fn encode(value: &str) -> String {
    let mut result = String::new();
    for b in value.bytes() {
        if b.is_ascii_alphanumeric() || b"-_.~".contains(&b) {
            result.push(b as char);
        } else {
            result.push_str(&format!("%{b:02X}"));
        }
    }
    result
}

pub fn object_url(config: &SyncConfig, name: &str) -> Result<Url> {
    validate(config)?;
    let mut url = Url::parse(&config.endpoint).map_err(|_| CoreError::Validation)?;
    let mut segments: Vec<String> = url
        .path()
        .split('/')
        .filter(|s| !s.is_empty())
        .map(encode)
        .collect();
    if config.backend == SyncBackend::S3 {
        if config.path_style {
            segments.push(encode(&config.bucket));
        } else {
            let host = format!(
                "{}.{}",
                config.bucket,
                url.host_str().ok_or(CoreError::Validation)?
            );
            url.set_host(Some(&host))
                .map_err(|_| CoreError::Validation)?;
        }
    }
    segments.extend(
        config
            .prefix
            .split('/')
            .filter(|s| !s.is_empty())
            .map(encode),
    );
    segments.push(encode(name));
    url.set_path(&format!("/{}", segments.join("/")));
    Ok(url)
}

fn hmac(key: &[u8], data: &[u8]) -> Zeroizing<Vec<u8>> {
    let mut mac = Hmac::<Sha256>::new_from_slice(key).expect("HMAC 接受任意长度密钥");
    mac.update(data);
    Zeroizing::new(mac.finalize().into_bytes().to_vec())
}

pub fn request(
    config: &SyncConfig,
    secret: &str,
    method: &str,
    url: &Url,
    condition: Option<(&str, &str)>,
    body: Vec<u8>,
    at: DateTime<Utc>,
) -> Result<SyncRequest> {
    let mut headers = vec![SyncHeader {
        name: "content-type".into(),
        value: "application/octet-stream".into(),
    }];
    if let Some((name, value)) = condition {
        headers.push(SyncHeader {
            name: name.into(),
            value: value.into(),
        });
    }
    match config.backend {
        SyncBackend::WebDav => {
            let credential = Zeroizing::new(format!("{}:{}", config.username, secret));
            headers.push(SyncHeader {
                name: "authorization".into(),
                value: format!("Basic {}", STANDARD.encode(credential.as_bytes())),
            });
        }
        SyncBackend::S3 => {
            let host = match url.port() {
                Some(port) => format!("{}:{port}", url.host_str().ok_or(CoreError::Validation)?),
                None => url.host_str().ok_or(CoreError::Validation)?.to_owned(),
            };
            let timestamp = at.format("%Y%m%dT%H%M%SZ").to_string();
            let day = at.format("%Y%m%d").to_string();
            let payload_hash = hex::encode(Sha256::digest(&body));
            headers.extend([
                SyncHeader {
                    name: "host".into(),
                    value: host,
                },
                SyncHeader {
                    name: "x-amz-content-sha256".into(),
                    value: payload_hash.clone(),
                },
                SyncHeader {
                    name: "x-amz-date".into(),
                    value: timestamp.clone(),
                },
            ]);
            headers.sort_by(|a, b| a.name.cmp(&b.name));
            let signed = headers
                .iter()
                .map(|h| h.name.as_str())
                .collect::<Vec<_>>()
                .join(";");
            let canonical_headers = headers
                .iter()
                .map(|h| format!("{}:{}\n", h.name, h.value.trim()))
                .collect::<String>();
            let canonical = format!(
                "{method}\n{}\n\n{canonical_headers}\n{signed}\n{payload_hash}",
                url.path()
            );
            let scope = format!("{day}/{}/s3/aws4_request", config.region);
            let string_to_sign = format!(
                "AWS4-HMAC-SHA256\n{timestamp}\n{scope}\n{}",
                hex::encode(Sha256::digest(canonical.as_bytes()))
            );
            let initial = Zeroizing::new(format!("AWS4{secret}"));
            let date_key = hmac(initial.as_bytes(), day.as_bytes());
            let region_key = hmac(&date_key, config.region.as_bytes());
            let service_key = hmac(&region_key, b"s3");
            let signing_key = hmac(&service_key, b"aws4_request");
            let signature = hmac(&signing_key, string_to_sign.as_bytes());
            headers.push(SyncHeader {
                name: "authorization".into(),
                value: format!(
                    "AWS4-HMAC-SHA256 Credential={}/{scope}, SignedHeaders={signed}, Signature={}",
                    config.username,
                    hex::encode(&signature)
                ),
            });
        }
    }
    Ok(SyncRequest {
        method: method.into(),
        url: url.to_string(),
        headers,
        body,
    })
}

pub fn send(transport: &dyn SyncTransport, request: SyncRequest) -> Result<SyncResponse> {
    let response = transport
        .send(request)
        .map_err(|_| CoreError::SyncNetwork)?;
    if response.body.len() > MAX_BYTES
        || !(100..=599).contains(&response.status)
        || response
            .headers
            .iter()
            .any(|h| h.name.contains(['\r', '\n']) || h.value.contains(['\r', '\n']))
    {
        return Err(CoreError::SyncNetwork);
    }
    match response.status {
        401 | 403 => Err(CoreError::SyncAuthentication),
        300..=399 => Err(CoreError::SyncNetwork),
        _ => Ok(response),
    }
}

pub fn etag(response: &SyncResponse) -> Result<String> {
    let values: Vec<_> = response
        .headers
        .iter()
        .filter(|h| h.name.eq_ignore_ascii_case("etag"))
        .collect();
    let tag = values
        .first()
        .ok_or(CoreError::SyncInvalidRemote)?
        .value
        .clone();
    if values.len() != 1
        || tag.len() < 3
        || !tag.starts_with('"')
        || !tag.ends_with('"')
        || tag[1..tag.len() - 1]
            .bytes()
            .any(|b| b < 0x21 || b == b'"' || b == 0x7f)
    {
        return Err(CoreError::SyncInvalidRemote);
    }
    Ok(tag)
}

pub fn make_directories(
    config: &SyncConfig,
    secret: &str,
    url: &Url,
    transport: &dyn SyncTransport,
) -> Result<()> {
    let parts: Vec<_> = url.path().split('/').filter(|s| !s.is_empty()).collect();
    for end in 1..parts.len() {
        let mut directory = url.clone();
        directory.set_path(&format!("/{}/", parts[..end].join("/")));
        let response = send(
            transport,
            request(
                config,
                secret,
                "MKCOL",
                &directory,
                None,
                vec![],
                Utc::now(),
            )?,
        )?;
        if !matches!(response.status, 200 | 201 | 204 | 405) {
            return Err(CoreError::SyncNetwork);
        }
    }
    Ok(())
}

#[cfg(feature = "sync-http")]
pub struct RustSyncTransport {
    agent: ureq::Agent,
}
#[cfg(feature = "sync-http")]
impl Default for RustSyncTransport {
    fn default() -> Self {
        Self {
            agent: ureq::AgentBuilder::new()
                .redirects(0)
                .timeout_connect(std::time::Duration::from_secs(10))
                .timeout_read(std::time::Duration::from_secs(30))
                .timeout_write(std::time::Duration::from_secs(30))
                .build(),
        }
    }
}
#[cfg(feature = "sync-http")]
impl SyncTransport for RustSyncTransport {
    fn send(&self, request: SyncRequest) -> Result<SyncResponse> {
        use std::io::Read;
        let mut outgoing = self.agent.request(&request.method, &request.url);
        for h in &request.headers {
            outgoing = outgoing.set(&h.name, &h.value);
        }
        let result = if request.body.is_empty() {
            outgoing.call()
        } else {
            outgoing.send_bytes(&request.body)
        };
        let response = match result {
            Ok(response) | Err(ureq::Error::Status(_, response)) => response,
            Err(_) => return Err(CoreError::SyncNetwork),
        };
        let status = response.status();
        let headers = response
            .headers_names()
            .into_iter()
            .flat_map(|name| {
                response
                    .all(&name)
                    .into_iter()
                    .map(move |value| SyncHeader {
                        name: name.clone(),
                        value: value.into(),
                    })
            })
            .collect();
        let mut body = Vec::new();
        response
            .into_reader()
            .take(MAX_BYTES as u64 + 1)
            .read_to_end(&mut body)
            .map_err(|_| CoreError::SyncNetwork)?;
        if body.len() > MAX_BYTES {
            return Err(CoreError::SyncNetwork);
        }
        Ok(SyncResponse {
            status,
            headers,
            body,
        })
    }
}
