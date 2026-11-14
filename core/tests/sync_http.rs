#![cfg(feature = "sync-http")]
use snowfold_core::{
    sync::{RustSyncTransport, merge, object_name, protocol},
    *,
};
use std::sync::{
    Arc, Barrier,
    atomic::{AtomicUsize, Ordering},
};

struct Observed {
    inner: RustSyncTransport,
    conflicts: AtomicUsize,
    first_reads: AtomicUsize,
    barrier: Option<Barrier>,
}
impl Observed {
    fn new(barrier: bool) -> Self {
        Self {
            inner: RustSyncTransport::default(),
            conflicts: AtomicUsize::new(0),
            first_reads: AtomicUsize::new(0),
            barrier: barrier.then(|| Barrier::new(2)),
        }
    }
}
impl SyncTransport for Observed {
    fn send(&self, request: SyncRequest) -> Result<SyncResponse, CoreError> {
        let method = request.method.clone();
        let conditional = request
            .headers
            .iter()
            .find(|h| h.name.starts_with("if-"))
            .map(|h| format!("{}={}", h.name, h.value))
            .unwrap_or_else(|| "none".into());
        let response = self.inner.send(request)?;
        println!(
            "HTTP {method} conditional={conditional} -> {} etag={}",
            response.status,
            response
                .headers
                .iter()
                .find(|h| h.name.eq_ignore_ascii_case("etag"))
                .map(|h| h.value.as_str())
                .unwrap_or("none")
        );
        if response.status >= 400 {
            let text = String::from_utf8_lossy(&response.body);
            if let Some(code) = text
                .split("<Code>")
                .nth(1)
                .and_then(|s| s.split("</Code>").next())
            {
                println!("S3 error code={code}");
            }
        }
        if response.status == 412 {
            self.conflicts.fetch_add(1, Ordering::SeqCst);
        }
        if method == "GET" {
            if let Some(barrier) = &self.barrier {
                if self.first_reads.fetch_add(1, Ordering::SeqCst) < 2 {
                    barrier.wait();
                }
            }
        }
        Ok(response)
    }
}
fn core() -> (tempfile::TempDir, Arc<SnowFoldCore>) {
    let d = tempfile::tempdir().unwrap();
    let c = SnowFoldCore::open(
        d.path().to_string_lossy().into_owned(),
        Arc::new(keystore::FileKeyStore::new(d.path())),
    )
    .unwrap();
    (d, c)
}
fn backup(c: &SnowFoldCore) -> backup::Backup {
    backup::Backup::decode(&c.export_json().unwrap()).unwrap()
}
fn enable(c: &SnowFoldCore, cfg: SyncConfig, secret: &str) {
    c.export_recovery_code().unwrap();
    c.configure_sync(cfg, secret.into()).unwrap();
    c.set_sync_enabled(true).unwrap();
}
fn exercise(backend: SyncBackend) -> anyhow::Result<()> {
    let (endpoint, username, secret) = match backend {
        SyncBackend::S3 => (
            std::env::var("SNOWFOLD_TEST_S3_ENDPOINT")?,
            std::env::var("SNOWFOLD_TEST_S3_ACCESS")?,
            zeroize::Zeroizing::new(std::env::var("SNOWFOLD_TEST_S3_SECRET")?),
        ),
        SyncBackend::WebDav => (
            std::env::var("SNOWFOLD_TEST_DAV_ENDPOINT")?,
            std::env::var("SNOWFOLD_TEST_DAV_USER")?,
            zeroize::Zeroizing::new(std::env::var("SNOWFOLD_TEST_DAV_SECRET")?),
        ),
    };
    let cfg = SyncConfig {
        backend,
        endpoint,
        bucket: if backend == SyncBackend::S3 {
            "snowfold-m7".into()
        } else {
            String::new()
        },
        region: if backend == SyncBackend::S3 {
            "us-east-1".into()
        } else {
            String::new()
        },
        prefix: format!("m7-{}/账本 空格", model::Id::new()?),
        username,
        path_style: backend == SyncBackend::S3,
    };
    let plain = Arc::new(Observed::new(false));
    if backend == SyncBackend::S3 {
        let bucket_url =
            format!("{}/{}", cfg.endpoint.trim_end_matches('/'), cfg.bucket).parse()?;
        let response = plain.send(protocol::request(
            &cfg,
            &secret,
            "PUT",
            &bucket_url,
            None,
            vec![],
            chrono::Utc::now(),
        )?)?;
        assert!(matches!(response.status, 200 | 201 | 409));
    }
    let (ad, a) = core();
    let (_bd, b) = core();
    b.import_recovery_code(a.export_recovery_code()?, None)?;
    enable(&a, cfg.clone(), &secret);
    enable(&b, cfg.clone(), &secret);
    let aid = a.create_asset("cash".into(), "S3-DAV 机密账户".into(), 100)?;
    let first = a.sync_now(plain.clone())?;
    assert_eq!(first.pushed_records, 2);
    assert_eq!(first.pulled_records, 0);
    b.sync_now(plain.clone())?;
    a.sync_now(plain.clone())?;
    assert!(merge::equivalent(&backup(&a), &backup(&b))?);
    let key = crypto::SyncKey::load(&ad.path().join("snowfold-sync.key"))?.unwrap();
    let url = protocol::object_url(&cfg, &object_name(&key))?;
    if backend == SyncBackend::WebDav {
        std::thread::sleep(std::time::Duration::from_millis(1100));
    }
    let old = plain.send(protocol::request(
        &cfg,
        &secret,
        "GET",
        &url,
        None,
        vec![],
        chrono::Utc::now(),
    )?)?;
    assert_eq!(old.status, 200);
    let old_tag = protocol::etag(&old)?;
    assert!(old.body.starts_with(b"SFS1"));
    assert!(!String::from_utf8_lossy(&old.body).contains("机密账户"));
    a.rename_asset(aid.clone(), "写入新版本".into())?;
    a.sync_now(plain.clone())?;
    let stale = plain.send(protocol::request(
        &cfg,
        &secret,
        "PUT",
        &url,
        Some(("if-match", &old_tag)),
        old.body,
        chrono::Utc::now(),
    )?)?;
    assert_eq!(stale.status, 412, "服务必须真实拒绝过期 ETag");
    let existing = plain.send(protocol::request(
        &cfg,
        &secret,
        "PUT",
        &url,
        Some(("if-none-match", "*")),
        vec![1, 2, 3],
        chrono::Utc::now(),
    )?)?;
    assert_eq!(existing.status, 412, "服务必须真实拒绝重复首建");
    b.sync_now(plain.clone())?;
    a.change_balance(aid.clone(), 200)?;
    b.change_balance(aid.clone(), 300)?;
    let a_new = a.create_asset("cash".into(), "并发甲新增".into(), 10)?;
    let b_new = b.create_asset("cash".into(), "并发乙新增".into(), 20)?;
    let concurrent = Arc::new(Observed::new(true));
    let handles = [a.clone(), b.clone()]
        .into_iter()
        .map(|c| {
            let t = concurrent.clone();
            std::thread::spawn(move || c.sync_now(t))
        })
        .collect::<Vec<_>>();
    let reports = handles
        .into_iter()
        .map(|h| h.join().unwrap())
        .collect::<Result<Vec<_>, _>>()?;
    if backend == SyncBackend::S3 {
        assert!(concurrent.conflicts.load(Ordering::SeqCst) >= 1);
        assert!(reports.iter().any(|r| r.retries >= 1));
    }
    assert!(a.data()?.assets.iter().any(|v| v.id == a_new));
    assert!(b.data()?.assets.iter().any(|v| v.id == b_new));
    // 非原子并发 PUT 后远端可能只剩甲；第一轮乙从本机补回更新，第二轮传播给甲。
    for round in 1..=2 {
        a.sync_now(plain.clone())?;
        b.sync_now(plain.clone())?;
        println!(
            "quiescent round={round} local_a={} local_b={}",
            a.data()?.assets.len(),
            b.data()?.assets.len()
        );
    }
    assert_eq!(a.data()?.assets.len(), 3);
    assert_eq!(a.data()?.history.len(), 5);
    assert!(merge::equivalent(&backup(&a), &backup(&b))?);
    // 同一路径放入另一把密钥的密文，核心必须在 GET 后停止，不能覆盖。
    let (_wrongd, wrong) = core();
    enable(&wrong, cfg.clone(), &secret);
    let wrong_fp = wrong.key_fingerprint()?;
    // 通过受控传输将 wrong 的派生路径映射为已有对象，真实服务器仍校验签名。
    struct SameObject {
        inner: RustSyncTransport,
        cfg: SyncConfig,
        secret: zeroize::Zeroizing<String>,
        url: url::Url,
        puts: AtomicUsize,
    }
    impl SyncTransport for SameObject {
        fn send(&self, r: SyncRequest) -> Result<SyncResponse, CoreError> {
            if r.method == "PUT" {
                self.puts.fetch_add(1, Ordering::SeqCst);
            }
            self.inner.send(protocol::request(
                &self.cfg,
                &self.secret,
                &r.method,
                &self.url,
                None,
                r.body,
                chrono::Utc::now(),
            )?)
        }
    }
    let mapped = Arc::new(SameObject {
        inner: RustSyncTransport::default(),
        cfg: cfg.clone(),
        secret: zeroize::Zeroizing::new(secret.to_string()),
        url: url.clone(),
        puts: AtomicUsize::new(0),
    });
    let local_before = wrong.export_json()?;
    assert!(matches!(
        wrong.sync_now(mapped.clone()),
        Err(CoreError::SyncRemoteKeyMismatch)
    ));
    assert_eq!(mapped.puts.load(Ordering::SeqCst), 0);
    assert_eq!(wrong.export_json()?, local_before);
    assert_eq!(wrong.key_fingerprint()?, wrong_fp);
    // 真实认证失败路径。错误凭证不进入日志。
    let rejected = plain.send(protocol::request(
        &cfg,
        "intentionally-invalid",
        "GET",
        &url,
        None,
        vec![],
        chrono::Utc::now(),
    )?)?;
    assert!(matches!(rejected.status, 401 | 403));
    let get = plain.send(protocol::request(
        &cfg,
        &secret,
        "GET",
        &url,
        None,
        vec![],
        chrono::Utc::now(),
    )?)?;
    assert_eq!(get.status, 200);
    println!(
        "PASS backend={backend:?} ciphertext_bytes={} assets={} history={} real_412={} retries={}",
        get.body.len(),
        a.data()?.assets.len(),
        a.data()?.history.len(),
        plain.conflicts.load(Ordering::SeqCst) + concurrent.conflicts.load(Ordering::SeqCst),
        reports.iter().map(|r| r.retries).sum::<u32>()
    );
    Ok(())
}
#[test]
#[ignore = "需要自建 S3 服务与环境变量；由 .fullstack 验收脚本显式运行"]
fn real_s3_sigv4_conditional_writes_and_encrypted_convergence() -> anyhow::Result<()> {
    exercise(SyncBackend::S3)
}
#[test]
#[ignore = "需要自建 WebDAV 服务与环境变量；由 .fullstack 验收脚本显式运行"]
fn real_webdav_basic_mkcol_conditional_writes_and_encrypted_convergence() -> anyhow::Result<()> {
    exercise(SyncBackend::WebDav)
}
