//! 同步状态、加密与一次前台同步。TLS 传输仅在 sync-http feature 下编译。
pub mod merge;
pub mod protocol;
use crate::{
    CoreError, SyncBackend, SyncConfig, SyncReport, SyncStatus, SyncTransport,
    backup::Backup,
    crypto::{self, SyncKey},
    storage::Storage,
};
#[cfg(feature = "sync-http")]
pub use protocol::RustSyncTransport;
use rusqlite::params;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use zeroize::Zeroizing;

type Result<T> = std::result::Result<T, CoreError>;
const SYNC_AAD: &[u8] = b"snowfold/sync/json/v1";
const CREDENTIAL_AAD: &[u8] = b"snowfold/sync/credential/v1";

pub fn object_name(key: &SyncKey) -> String {
    let mut hash = Sha256::new();
    hash.update(b"snowfold/sync/object/v1");
    hash.update(key.as_bytes());
    format!("snowfold-{}.sfsync", hex::encode(hash.finalize()))
}
fn credential_aad(config: &SyncConfig) -> Result<Vec<u8>> {
    let mut aad = CREDENTIAL_AAD.to_vec();
    aad.extend(serde_json::to_vec(config).map_err(|_| CoreError::Internal)?);
    Ok(aad)
}
#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct Envelope {
    format: String,
    version: u32,
    data: Backup,
}
fn seal(data: &Backup, key: &SyncKey) -> Result<Vec<u8>> {
    let payload = Envelope {
        format: "snowfold.sync".into(),
        version: 1,
        data: data.clone(),
    };
    let json = Zeroizing::new(serde_json::to_vec(&payload).map_err(|_| CoreError::Internal)?);
    if json.len() > protocol::MAX_BYTES - 64 {
        return Err(CoreError::SyncInvalidRemote);
    }
    crypto::seal(&json, key, SYNC_AAD).map_err(|_| CoreError::Internal)
}
fn open(bytes: &[u8], key: &SyncKey) -> Result<Backup> {
    let json = Zeroizing::new(crypto::open(bytes, key, SYNC_AAD).map_err(|error| {
        match error.downcast_ref::<crypto::CryptoError>() {
            Some(crypto::CryptoError::Authentication) => CoreError::SyncRemoteKeyMismatch,
            Some(
                crypto::CryptoError::UnsupportedFormat(_)
                | crypto::CryptoError::UnsupportedAlgorithm(_),
            ) => CoreError::SyncIncompatible,
            _ => CoreError::SyncInvalidRemote,
        }
    })?);
    let value: serde_json::Value =
        serde_json::from_slice(&json).map_err(|_| CoreError::SyncInvalidRemote)?;
    if value.get("version").and_then(|v| v.as_u64()) != Some(1) {
        return Err(CoreError::SyncIncompatible);
    }
    let envelope: Envelope =
        serde_json::from_value(value).map_err(|_| CoreError::SyncInvalidRemote)?;
    if envelope.format != "snowfold.sync" {
        return Err(CoreError::SyncInvalidRemote);
    }
    envelope
        .data
        .validate()
        .map_err(|_| CoreError::SyncInvalidRemote)?;
    Ok(envelope.data)
}

impl Storage {
    pub(crate) fn sync_status(&self, fingerprint: Option<&str>) -> Result<SyncStatus> {
        let (config,enabled,recovery,last,policy) = self.connection.query_row(
            "SELECT config_json,enabled,recovery_fingerprint,last_synced_at,on_launch,interval_minutes,after_edit FROM sync_state WHERE id=1", [], |r| {
                Ok((r.get::<_,Option<String>>(0)?,r.get::<_,bool>(1)?,r.get::<_,Option<String>>(2)?,r.get::<_,Option<String>>(3)?,
                    crate::SyncPolicy {on_launch:r.get(4)?,interval_minutes:r.get(5)?,after_edit:r.get(6)?}))
            }).map_err(|_|CoreError::Database)?;
        let config = config
            .map(|v| serde_json::from_str(&v).map_err(|_| CoreError::Database))
            .transpose()?;
        Ok(SyncStatus {
            config,
            enabled,
            recovery_code_exported: fingerprint.is_some() && recovery.as_deref() == fingerprint,
            last_synced_at: last,
            policy,
        })
    }
    pub(crate) fn set_sync_policy(&self, policy: &crate::SyncPolicy) -> Result<()> {
        self.connection
            .execute(
                "UPDATE sync_state SET on_launch=?1,interval_minutes=?2,after_edit=?3 WHERE id=1",
                params![policy.on_launch, policy.interval_minutes, policy.after_edit],
            )
            .map_err(|_| CoreError::Database)?;
        Ok(())
    }
    pub(crate) fn mark_recovery_exported(&self, fingerprint: &str) -> Result<()> {
        self.connection
            .execute(
                "UPDATE sync_state SET recovery_fingerprint=?1 WHERE id=1",
                [fingerprint],
            )
            .map_err(|_| CoreError::Database)?;
        Ok(())
    }
    pub(crate) fn configure_sync(
        &self,
        config: &SyncConfig,
        secret: &str,
        key: &SyncKey,
    ) -> Result<()> {
        protocol::validate(config)?;
        if secret.is_empty() || secret.chars().any(char::is_control) {
            return Err(CoreError::Validation);
        }
        let sealed = crypto::seal(secret.as_bytes(), key, &credential_aad(config)?)
            .map_err(|_| CoreError::Internal)?;
        let json = serde_json::to_string(config).map_err(|_| CoreError::Internal)?;
        self.connection.execute("UPDATE sync_state SET config_json=?1,credential=?2,enabled=0,last_synced_at=NULL WHERE id=1",params![json,sealed]).map_err(|_|CoreError::Database)?;
        Ok(())
    }
    pub(crate) fn sync_secret(
        &self,
        config: &SyncConfig,
        key: &SyncKey,
    ) -> Result<Zeroizing<String>> {
        let sealed: Vec<u8> = self
            .connection
            .query_row("SELECT credential FROM sync_state WHERE id=1", [], |r| {
                r.get(0)
            })
            .map_err(|_| CoreError::Database)?;
        let plain = Zeroizing::new(
            crypto::open(&sealed, key, &credential_aad(config)?)
                .map_err(|_| CoreError::KeyUnavailable)?,
        );
        Ok(Zeroizing::new(
            String::from_utf8(plain.to_vec()).map_err(|_| CoreError::KeyUnavailable)?,
        ))
    }
    pub(crate) fn set_sync_enabled(&self, enabled: bool) -> Result<()> {
        self.connection
            .execute("UPDATE sync_state SET enabled=?1 WHERE id=1", [enabled])
            .map_err(|_| CoreError::Database)?;
        Ok(())
    }
    pub(crate) fn clear_sync_config(&self) -> Result<()> {
        self.connection.execute("UPDATE sync_state SET config_json=NULL,credential=NULL,enabled=0,last_synced_at=NULL,on_launch=1,interval_minutes=30,after_edit=1 WHERE id=1",[]).map_err(|_|CoreError::Database)?;
        Ok(())
    }
}

fn read_object(
    config: &SyncConfig,
    secret: &str,
    url: &url::Url,
    transport: &dyn SyncTransport,
) -> Result<crate::SyncResponse> {
    let mut response = protocol::send(
        transport,
        protocol::request(config, secret, "GET", url, None, vec![], chrono::Utc::now())?,
    )?;
    if response.status == 200
        && response
            .headers
            .iter()
            .any(|h| h.name.eq_ignore_ascii_case("etag") && h.value.starts_with("W/"))
    {
        // Apache 在文件刚写入的一秒内给弱 ETag。只重读一次，绝不用弱 ETag 写入。
        std::thread::sleep(std::time::Duration::from_millis(1100));
        response = protocol::send(
            transport,
            protocol::request(config, secret, "GET", url, None, vec![], chrono::Utc::now())?,
        )?;
    }
    Ok(response)
}

/// 网络阶段不访问本机数据库。只有条件 PUT 成功才把合并结果交还调用者落库。
pub(crate) fn exchange(
    config: &SyncConfig,
    secret: &str,
    key: &SyncKey,
    local: &Backup,
    transport: &dyn SyncTransport,
) -> Result<(Backup, SyncReport)> {
    let url = protocol::object_url(config, &object_name(key))?;
    let mut directories_created = false;
    for retries in 0..=3 {
        let get = read_object(config, secret, &url, transport)?;
        let (merged, tag, pulled) = match get.status {
            200 => {
                let mut tag = protocol::etag(&get)?;
                let remote = match open(&get.body, key) {
                    Ok(data) => data,
                    Err(CoreError::SyncRemoteKeyMismatch) => {
                        // 部分 WebDAV 服务边写边读，先只读重试，绝不以认证失败的内容进行 PUT。
                        std::thread::sleep(std::time::Duration::from_millis(200));
                        let again = read_object(config, secret, &url, transport)?;
                        if again.status != 200 {
                            return Err(CoreError::SyncNetwork);
                        }
                        let next_tag = protocol::etag(&again)?;
                        let data = open(&again.body, key).map_err(|e| {
                            if e == CoreError::SyncRemoteKeyMismatch && next_tag != tag {
                                CoreError::SyncNetwork
                            } else {
                                e
                            }
                        })?;
                        tag = next_tag;
                        data
                    }
                    Err(e) => return Err(e),
                };
                let pulled = merge::count(&remote);
                (merge::merge(local, &remote)?, Some(tag), pulled)
            }
            404 => {
                if config.backend == SyncBackend::WebDav && !directories_created {
                    protocol::make_directories(config, secret, &url, transport)?;
                    directories_created = true;
                }
                (merge::merge(local, local)?, None, 0)
            }
            _ => return Err(CoreError::SyncNetwork),
        };
        let condition = match tag.as_deref() {
            Some(tag) => ("if-match", tag),
            None => ("if-none-match", "*"),
        };
        let sealed = seal(&merged, key)?;
        let mut response = protocol::send(
            transport,
            protocol::request(
                config,
                secret,
                "PUT",
                &url,
                Some(condition),
                sealed.clone(),
                chrono::Utc::now(),
            )?,
        )?;
        if matches!(response.status, 404 | 409)
            && config.backend == SyncBackend::WebDav
            && !directories_created
        {
            protocol::make_directories(config, secret, &url, transport)?;
            directories_created = true;
            response = protocol::send(
                transport,
                protocol::request(
                    config,
                    secret,
                    "PUT",
                    &url,
                    Some(condition),
                    sealed,
                    chrono::Utc::now(),
                )?,
            )?;
        }
        match response.status {
            200 | 201 | 204 => {
                let report = SyncReport {
                    pulled_records: pulled,
                    pushed_records: merge::count(&merged),
                    merged_records: merge::changed(local, &merged)?,
                    last_synced_at: crate::model::now(),
                    retries,
                    pending_local_changes: false,
                };
                return Ok((merged, report));
            }
            412 => continue,
            _ => return Err(CoreError::SyncNetwork),
        }
    }
    Err(CoreError::SyncConflict)
}
