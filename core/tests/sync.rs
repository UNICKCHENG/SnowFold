use snowfold_core::{
    backup::Backup,
    sync::{merge, protocol},
    *,
};
use std::sync::{
    Arc, Barrier, Mutex,
    atomic::{AtomicUsize, Ordering},
};

type TestResult = anyhow::Result<()>;
fn open() -> (tempfile::TempDir, Arc<SnowFoldCore>) {
    let directory = tempfile::tempdir().unwrap();
    let core = SnowFoldCore::open(
        directory.path().to_string_lossy().into_owned(),
        Arc::new(keystore::FileKeyStore::new(directory.path())),
    )
    .unwrap();
    (directory, core)
}
fn config() -> SyncConfig {
    SyncConfig {
        backend: SyncBackend::S3,
        endpoint: "https://s3.example.com".into(),
        bucket: "snowfold-test".into(),
        region: "us-east-1".into(),
        prefix: "账本/空 格".into(),
        username: "test-access".into(),
        path_style: true,
    }
}
fn enabled(core: &SnowFoldCore) {
    core.export_recovery_code().unwrap();
    core.configure_sync(config(), "test-secret-never-plaintext".into())
        .unwrap();
    core.set_sync_enabled(true).unwrap();
}
fn paired() -> (
    tempfile::TempDir,
    Arc<SnowFoldCore>,
    tempfile::TempDir,
    Arc<SnowFoldCore>,
) {
    let (ad, a) = open();
    let (bd, b) = open();
    let recovery = a.export_recovery_code().unwrap();
    b.import_recovery_code(recovery, None).unwrap();
    enabled(&a);
    enabled(&b);
    (ad, a, bd, b)
}
fn backup(core: &SnowFoldCore) -> Backup {
    Backup::decode(&core.export_json().unwrap()).unwrap()
}
fn identical(a: &SnowFoldCore, b: &SnowFoldCore) {
    assert!(merge::equivalent(&backup(a), &backup(b)).unwrap());
}
#[derive(Default)]
struct Object {
    body: Option<Vec<u8>>,
    version: usize,
}
#[derive(Default)]
struct Remote {
    object: Mutex<Object>,
    calls: AtomicUsize,
    conflicts: AtomicUsize,
    forced_conflicts: AtomicUsize,
    barrier: Option<Barrier>,
    reads: AtomicUsize,
    before_put: Mutex<Option<Box<dyn FnOnce() + Send>>>,
}
impl Remote {
    fn bytes(&self) -> Vec<u8> {
        self.object.lock().unwrap().body.clone().unwrap()
    }
}
fn header(request: &SyncRequest, name: &str) -> Option<String> {
    request
        .headers
        .iter()
        .find(|h| h.name.eq_ignore_ascii_case(name))
        .map(|h| h.value.clone())
}
impl SyncTransport for Remote {
    fn send(&self, request: SyncRequest) -> Result<SyncResponse, CoreError> {
        self.calls.fetch_add(1, Ordering::SeqCst);
        match request.method.as_str() {
            "GET" => {
                let response = {
                    let object = self.object.lock().unwrap();
                    SyncResponse {
                        status: if object.body.is_some() { 200 } else { 404 },
                        headers: if object.body.is_some() {
                            vec![SyncHeader {
                                name: "ETag".into(),
                                value: format!("\"{}\"", object.version),
                            }]
                        } else {
                            vec![]
                        },
                        body: object.body.clone().unwrap_or_default(),
                    }
                };
                if let Some(barrier) = &self.barrier {
                    if self.reads.fetch_add(1, Ordering::SeqCst) < 2 {
                        barrier.wait();
                    }
                }
                Ok(response)
            }
            "PUT" => {
                let hook = self.before_put.lock().unwrap().take();
                if let Some(hook) = hook {
                    hook();
                }
                let mut object = self.object.lock().unwrap();
                let failed = self
                    .forced_conflicts
                    .fetch_update(Ordering::SeqCst, Ordering::SeqCst, |n| n.checked_sub(1))
                    .is_ok()
                    || match &object.body {
                        Some(_) => {
                            header(&request, "if-match") != Some(format!("\"{}\"", object.version))
                        }
                        None => header(&request, "if-none-match").as_deref() != Some("*"),
                    };
                if failed {
                    self.conflicts.fetch_add(1, Ordering::SeqCst);
                    return Ok(SyncResponse {
                        status: 412,
                        headers: vec![],
                        body: vec![],
                    });
                }
                object.body = Some(request.body);
                object.version += 1;
                Ok(SyncResponse {
                    status: 200,
                    headers: vec![],
                    body: vec![],
                })
            }
            _ => Err(CoreError::SyncNetwork),
        }
    }
}
#[test]
fn configuration_requires_recovery_export_and_credentials_are_encrypted() -> TestResult {
    let (directory, core) = open();
    let transport = Arc::new(Remote::default());
    assert!(matches!(
        core.sync_now(transport.clone()),
        Err(CoreError::SyncNotConfigured)
    ));
    assert_eq!(
        core.set_sync_enabled(true),
        Err(CoreError::SyncNotConfigured)
    );
    core.configure_sync(config(), "test-secret-never-plaintext".into())?;
    assert_eq!(
        core.set_sync_enabled(true),
        Err(CoreError::RecoveryCodeRequired)
    );
    assert!(matches!(
        core.sync_now(transport.clone()),
        Err(CoreError::SyncDisabled)
    ));
    assert_eq!(transport.calls.load(Ordering::SeqCst), 0);
    core.export_recovery_code()?;
    core.set_sync_enabled(true)?;
    let db = rusqlite::Connection::open(directory.path().join("snowfold.sqlite3"))?;
    let sealed: Vec<u8> = db.query_row("SELECT credential FROM sync_state", [], |r| r.get(0))?;
    assert!(sealed.starts_with(b"SFS1"));
    assert!(!String::from_utf8_lossy(&sealed).contains("test-secret-never-plaintext"));
    let raw = std::fs::read(directory.path().join("snowfold.sqlite3"))?;
    assert!(
        !raw.windows(b"test-secret-never-plaintext".len())
            .any(|v| v == b"test-secret-never-plaintext")
    );
    core.create_asset("cash".into(), "不可泄漏账户".into(), 12345)?;
    let report = core.sync_now(transport.clone())?;
    assert_eq!(report.pushed_records, 2);
    assert_eq!(report.pulled_records, 0);
    assert!(report.last_synced_at.ends_with('Z'));
    assert!(transport.bytes().starts_with(b"SFS1"));
    assert!(!String::from_utf8_lossy(&transport.bytes()).contains("不可泄漏账户"));
    let key = crypto::SyncKey::load(&directory.path().join("snowfold-sync.key"))?.unwrap();
    assert!(crypto::open(&transport.bytes(), &key, crypto::SNAPSHOT_AAD).is_err());
    assert!(core.sync_status()?.enabled);
    drop(core);
    let core = SnowFoldCore::open(
        directory.path().to_string_lossy().into_owned(),
        Arc::new(keystore::FileKeyStore::new(directory.path())),
    )?;
    assert!(core.sync_status()?.recovery_code_exported);
    assert!(core.sync_status()?.last_synced_at.is_some());
    core.clear_sync_config()?;
    assert!(core.sync_status()?.config.is_none());
    assert!(core.sync_status()?.recovery_code_exported);
    assert!(transport.bytes().starts_with(b"SFS1"));
    Ok(())
}
#[test]
fn concurrent_edits_history_branches_corrections_and_new_records_converge() -> TestResult {
    let (_ad, a, _bd, b) = paired();
    let remote = Arc::new(Remote::default());
    let id = a.create_asset("cash".into(), "原名".into(), 100)?;
    a.sync_now(remote.clone())?;
    b.sync_now(remote.clone())?;
    a.rename_asset(id.clone(), "甲".into())?;
    a.change_balance(id.clone(), 200)?;
    b.rename_asset(id.clone(), "乙".into())?;
    b.change_balance(id.clone(), 300)?;
    let original = a
        .data()?
        .history
        .iter()
        .find(|h| h.previous_id.is_none())
        .unwrap()
        .id
        .clone();
    a.correct_history(original.clone(), 110)?;
    b.correct_history(original.clone(), 120)?;
    a.create_asset("debt".into(), "甲新增".into(), 5)?;
    b.create_asset("fixed".into(), "乙新增".into(), 6)?;
    let ab = merge::merge(&backup(&a), &backup(&b))?;
    let ba = merge::merge(&backup(&b), &backup(&a))?;
    assert_eq!(serde_json::to_value(&ab)?, serde_json::to_value(&ba)?);
    assert_eq!(ab.assets.len(), 3);
    assert_eq!(ab.history.len(), 5);
    assert_eq!(ab.corrections.len(), 2);
    let aa = merge::merge(&ab, &ab)?;
    assert!(merge::equivalent(&aa, &ab)?);
    a.sync_now(remote.clone())?;
    b.sync_now(remote.clone())?;
    a.sync_now(remote.clone())?;
    identical(&a, &b);
    assert_eq!(a.history_corrections(original)?.len(), 2);
    // 新的本机修正从冲突收敛后的投影继续，不能丢掉两条不可变修正事实。
    a.correct_history(
        a.data()?
            .assets
            .iter()
            .find(|v| v.id == id)
            .unwrap()
            .current_history_id
            .clone(),
        400,
    )?;
    a.sync_now(remote.clone())?;
    b.sync_now(remote.clone())?;
    identical(&a, &b);
    Ok(())
}
#[test]
fn deletion_and_modification_use_timestamp_and_revision_ties_are_stable() -> TestResult {
    let (_ad, a, _bd, b) = paired();
    let id = a.create_asset("cash".into(), "原名".into(), 100)?;
    b.import_json(a.export_json()?)?;
    a.delete_asset(id.clone())?;
    b.rename_asset(id.clone(), "后修改".into())?;
    let deleted = backup(&a);
    let edited = backup(&b);
    assert_eq!(merge::merge(&deleted, &edited)?.assets.len(), 1);
    let mut deleted = deleted;
    deleted.tombstones[0].deleted_at = edited.assets[0].updated_at.clone();
    let merged = merge::merge(&edited, &deleted)?;
    assert!(merged.assets.is_empty());
    assert!(merged.history.is_empty());
    assert_eq!(merged.tombstones.len(), 1);
    let mut left = edited.clone();
    let mut right = edited;
    left.assets[0].revision_uid = "11111111-1111-4111-8111-111111111111".parse()?;
    right.assets[0].revision_uid = "22222222-2222-4222-8222-222222222222".parse()?;
    left.assets[0].name = "左".into();
    right.assets[0].name = "右".into();
    assert_eq!(merge::merge(&left, &right)?.assets[0].name, "右");
    assert!(merge::equivalent(
        &merge::merge(&left, &right)?,
        &merge::merge(&right, &left)?
    )?);
    Ok(())
}
#[test]
fn clock_rollback_keeps_new_local_writes_after_observed_remote_time() -> TestResult {
    let (_d, core) = open();
    let id = core.create_asset("cash".into(), "原".into(), 0)?;
    let mut data = backup(&core);
    data.assets[0].updated_at = "2090-01-01T00:00:00.000000000Z".into();
    core.import_json(serde_json::to_string(&data)?)?;
    core.rename_asset(id.clone(), "回拨后".into())?;
    assert_eq!(
        core.data()?.assets[0].updated_at,
        "2090-01-01T00:00:00.000000001Z"
    );
    core.change_balance(id.clone(), 1)?;
    core.delete_asset(id)?;
    assert!(backup(&core).tombstones[0].deleted_at > "2090-01-01T00:00:00.000000001Z".to_string());
    Ok(())
}
#[test]
fn two_simultaneous_creates_hit_412_then_converge_without_lost_update() -> TestResult {
    let (_ad, a, _bd, b) = paired();
    a.create_asset("cash".into(), "甲新增".into(), 1)?;
    b.create_asset("cash".into(), "乙新增".into(), 2)?;
    let remote = Arc::new(Remote {
        barrier: Some(Barrier::new(2)),
        ..Default::default()
    });
    let handles = [a.clone(), b.clone()]
        .into_iter()
        .map(|core| {
            let remote = remote.clone();
            std::thread::spawn(move || core.sync_now(remote))
        })
        .collect::<Vec<_>>();
    let reports = handles
        .into_iter()
        .map(|h| h.join().unwrap())
        .collect::<Result<Vec<_>, _>>()?;
    assert_eq!(remote.conflicts.load(Ordering::SeqCst), 1);
    assert_eq!(reports.iter().map(|r| r.retries).sum::<u32>(), 1);
    a.sync_now(remote.clone())?;
    b.sync_now(remote.clone())?;
    identical(&a, &b);
    assert_eq!(a.data()?.assets.len(), 2);
    Ok(())
}
#[test]
fn local_write_during_put_survives_and_configuration_or_key_replacement_cannot_race() -> TestResult
{
    let (_d, core) = open();
    enabled(&core);
    let remote = Arc::new(Remote::default());
    let weak = Arc::downgrade(&core);
    *remote.before_put.lock().unwrap() = Some(Box::new(move || {
        let core = weak.upgrade().unwrap();
        core.create_asset("cash".into(), "上传期间新增".into(), 10)
            .unwrap();
        assert_eq!(core.clear_sync_config(), Err(CoreError::Conflict));
        assert_eq!(
            core.configure_sync(config(), "another".into()),
            Err(CoreError::Conflict)
        );
        assert_eq!(
            core.import_json(core.export_json().unwrap()),
            Err(CoreError::Conflict)
        );
        let code = crypto::SyncKey::generate().unwrap().encode_file();
        assert!(matches!(
            core.import_recovery_code(code, core.key_fingerprint().unwrap()),
            Err(CoreError::Conflict)
        ));
    }));
    let report = core.sync_now(remote.clone())?;
    assert!(report.pending_local_changes);
    assert_eq!(core.data()?.assets.len(), 1);
    let report = core.sync_now(remote)?;
    assert!(!report.pending_local_changes);
    assert_eq!(report.pushed_records, 2);
    Ok(())
}
#[test]
fn repeated_conflicts_release_lock_and_leave_local_business_data_unchanged() -> TestResult {
    let (_d, core) = open();
    enabled(&core);
    core.create_asset("cash".into(), "本机".into(), 1)?;
    let before = core.export_json()?;
    let remote = Arc::new(Remote {
        forced_conflicts: AtomicUsize::new(4),
        ..Default::default()
    });
    assert!(matches!(
        core.sync_now(remote.clone()),
        Err(CoreError::SyncConflict)
    ));
    assert_eq!(remote.conflicts.load(Ordering::SeqCst), 4);
    assert_eq!(core.export_json()?, before);
    assert!(core.sync_status()?.last_synced_at.is_none());
    core.set_sync_enabled(false)?;
    core.set_sync_enabled(true)?;
    core.sync_now(remote)?;
    Ok(())
}
#[test]
fn wrong_key_device_pointed_at_same_object_never_puts_or_changes_local_data() -> TestResult {
    let (_ad, a) = open();
    let (_bd, b) = open();
    enabled(&a);
    enabled(&b);
    a.create_asset("cash".into(), "甲机密".into(), 1)?;
    b.create_asset("cash".into(), "乙机密".into(), 2)?;
    // 这个传输忽略派生路径，模拟旧钥与新钥被错误地指向同一远端对象。
    let remote = Arc::new(Remote::default());
    a.sync_now(remote.clone())?;
    let remote_before = remote.bytes();
    let local_before = b.export_json()?;
    let version = remote.object.lock().unwrap().version;
    assert!(matches!(
        b.sync_now(remote.clone()),
        Err(CoreError::SyncRemoteKeyMismatch)
    ));
    assert_eq!(remote.bytes(), remote_before);
    assert_eq!(b.export_json()?, local_before);
    assert_eq!(remote.object.lock().unwrap().version, version);
    let current = b.key_fingerprint()?;
    b.import_recovery_code(a.export_recovery_code()?, current)?;
    assert!(!b.sync_status()?.enabled);
    assert!(b.sync_status()?.config.is_none());
    assert!(!b.sync_status()?.recovery_code_exported);
    Ok(())
}
struct ResponseOnly {
    status: u16,
    etag: Option<&'static str>,
    body: Vec<u8>,
}
impl SyncTransport for ResponseOnly {
    fn send(&self, _: SyncRequest) -> Result<SyncResponse, CoreError> {
        Ok(SyncResponse {
            status: self.status,
            headers: self
                .etag
                .map(|v| {
                    vec![SyncHeader {
                        name: "etag".into(),
                        value: v.into(),
                    }]
                })
                .unwrap_or_default(),
            body: self.body.clone(),
        })
    }
}
#[test]
fn http_errors_missing_etags_redirects_and_tampering_fail_closed() -> TestResult {
    let (_d, core) = open();
    enabled(&core);
    let before = core.export_json()?;
    for (status, etag, expected) in [
        (401, None, CoreError::SyncAuthentication),
        (403, None, CoreError::SyncAuthentication),
        (302, None, CoreError::SyncNetwork),
        (500, None, CoreError::SyncNetwork),
        (200, None, CoreError::SyncInvalidRemote),
        (200, Some("W/\"a\""), CoreError::SyncInvalidRemote),
        (200, Some("\"a\""), CoreError::SyncInvalidRemote),
    ] {
        assert!(
            matches!(core.sync_now(Arc::new(ResponseOnly{status,etag,body:vec![]})),Err(e) if e==expected)
        );
        assert_eq!(core.export_json()?, before);
    }
    let remote = Arc::new(Remote::default());
    core.sync_now(remote.clone())?;
    remote.object.lock().unwrap().body.as_mut().unwrap()[20] ^= 1;
    assert!(matches!(
        core.sync_now(remote),
        Err(CoreError::SyncRemoteKeyMismatch)
    ));
    Ok(())
}
#[test]
fn invalid_configuration_and_sigv4_url_encoding_are_checked_at_boundary() -> TestResult {
    let mut cfg = config();
    let url = protocol::object_url(&cfg, "test.sfsync")?;
    assert!(
        url.as_str()
            .contains("%E8%B4%A6%E6%9C%AC/%E7%A9%BA%20%E6%A0%BC")
    );
    let request = protocol::request(
        &cfg,
        "s3-secret",
        "PUT",
        &url,
        Some(("if-match", "\"abc\"")),
        vec![1, 2, 3],
        "2025-01-01T00:00:00Z".parse()?,
    )?;
    let auth = header(&request, "authorization").unwrap();
    assert!(auth.contains(&format!(
        "Credential={}/20250101/us-east-1/s3/aws4_request",
        cfg.username
    )));
    assert!(auth.contains("content-type;host;if-match;x-amz-content-sha256;x-amz-date"));
    assert!(!auth.contains("s3-secret"));
    for endpoint in [
        "http://example.com",
        "https://user:secret@example.com",
        "https://example.com?secret=yes",
        "file:///tmp/a",
    ] {
        cfg.endpoint = endpoint.into();
        assert_eq!(protocol::validate(&cfg), Err(CoreError::Validation));
    }
    cfg = config();
    cfg.path_style = false;
    assert_eq!(
        protocol::object_url(&cfg, "a")?.host_str(),
        Some("snowfold-test.s3.example.com")
    );
    cfg = config();
    cfg.prefix = "../escape".into();
    assert_eq!(protocol::validate(&cfg), Err(CoreError::Validation));
    cfg = config();
    cfg.endpoint = "http://127.0.0.1:1234".into();
    protocol::validate(&cfg)?;
    Ok(())
}
#[test]
fn migration_from_v2_preserves_every_business_row_and_rolls_back_on_failure() -> TestResult {
    let (directory, core) = open();
    let path = directory.path().join("snowfold.sqlite3");
    core.import_json(serde_json::to_string(&storage::Storage::example_data()?)?)?;
    core.save_theme(ThemePreference::Light)?;
    let zero = core.create_asset("cash".into(), "零值与空前驱".into(), 0)?;
    core.create_asset("debt".into(), "整数边界".into(), i64::MAX)?;
    let ticket = core.change_balance(zero.clone(), 1)?.unwrap();
    core.undo(ticket.token)?;
    core.create_subscription(SubscriptionInput {
        name: "零值暂停".into(),
        amount_cents: 0,
        currency: "USD".into(),
        cycle: "yearly".into(),
        next_charge_on: "2096-02-29".into(),
        status: "paused".into(),
    })?;
    let removed = core.create_asset("fixed".into(), "删除墓碑".into(), 0)?;
    core.delete_asset(removed)?;
    let before = core.export_json()?;
    drop(core);
    {
        let db = rusqlite::Connection::open(&path)?;
        // 同步版本的唯一上行变更就是加表；回到基线 2 后再执行真实打开迁移。
        db.execute("DROP TABLE sync_state", [])?;
        db.pragma_update(None, "user_version", 2)?;
    }
    let core = SnowFoldCore::open(
        directory.path().to_string_lossy().into_owned(),
        Arc::new(keystore::FileKeyStore::new(directory.path())),
    )?;
    assert!(matches!(core.theme()?, ThemePreference::Light));
    assert_eq!(core.export_json()?, before);
    assert!(core.sync_status()?.config.is_none());
    let db = rusqlite::Connection::open(&path)?;
    assert_eq!(
        db.pragma_query_value(None, "user_version", |r| r.get::<_, i64>(0))?,
        3
    );
    assert!(db.execute("UPDATE sync_state SET enabled=1", []).is_err());
    assert!(
        db.execute("INSERT INTO sync_state(id) VALUES(2)", [])
            .is_err()
    );
    assert!(
        db.execute("UPDATE sync_state SET config_json='{}'", [])
            .is_err()
    );
    let failing = tempfile::tempdir()?;
    let path = failing.path().join("snowfold.sqlite3");
    let broken = rusqlite::Connection::open(&path)?;
    broken.execute_batch(include_str!("../src/schema.sql"))?;
    broken.execute("CREATE TABLE sync_state(existing TEXT)", [])?;
    broken.pragma_update(None, "user_version", 2)?;
    assert!(storage::Storage::open(&path).is_err());
    assert_eq!(
        broken.pragma_query_value(None, "user_version", |r| r.get::<_, i64>(0))?,
        2
    );
    assert_eq!(
        broken.query_row(
            "SELECT count(*) FROM pragma_table_info('sync_state')",
            [],
            |r| r.get::<_, i64>(0)
        )?,
        1
    );
    Ok(())
}

#[test]
fn quiescent_two_and_three_devices_converge_in_every_sync_order_without_losing_live_history()
-> TestResult {
    for devices in [2, 3] {
        let orders: Vec<Vec<usize>> = if devices == 2 {
            vec![vec![0, 1], vec![1, 0]]
        } else {
            vec![
                vec![0, 1, 2],
                vec![0, 2, 1],
                vec![1, 0, 2],
                vec![1, 2, 0],
                vec![2, 0, 1],
                vec![2, 1, 0],
            ]
        };
        for order in orders {
            let owners = (0..devices).map(|_| open()).collect::<Vec<_>>();
            let cores = owners.iter().map(|(_, c)| c.clone()).collect::<Vec<_>>();
            let main = cores[0].create_asset("cash".into(), "共同账户".into(), 100)?;
            let deleted = cores[0].create_asset("fixed".into(), "随后删除".into(), 0)?;
            let initial = cores[0].export_json()?;
            let recovery = cores[0].export_recovery_code()?;
            for (i, c) in cores.iter().enumerate() {
                if i > 0 {
                    c.import_recovery_code(recovery.clone(), None)?;
                    c.import_json(initial.clone())?;
                }
                enabled(c);
                c.change_balance(main.clone(), 200 + i as i64)?;
                c.rename_asset(main.clone(), format!("设备{i}"))?;
                c.create_asset("cash".into(), format!("独有资产{i}"), i as i64)?;
                c.create_subscription(SubscriptionInput {
                    name: format!("独有订阅{i}"),
                    amount_cents: i as i64,
                    currency: "CNY".into(),
                    cycle: "monthly".into(),
                    next_charge_on: "2027-01-01".into(),
                    status: "active".into(),
                })?;
            }
            cores[devices - 1].delete_asset(deleted)?;
            let remote = Arc::new(Remote::default());
            // 此后停止写入。首轮汇总、第二轮传播到所有设备。
            for _ in 0..2 {
                for &i in &order {
                    cores[i].sync_now(remote.clone())?;
                }
            }
            for c in &cores {
                identical(&cores[0], c);
                assert_eq!(c.data()?.assets.len(), devices + 1);
                assert_eq!(c.data()?.history.len(), 2 * devices + 1);
                assert_eq!(c.data()?.subscriptions.len(), devices);
            }
            let recovery_key = crypto::SyncKey::decode_file(&recovery)?;
            let plaintext = crypto::open(&remote.bytes(), &recovery_key, b"snowfold/sync/json/v1")?;
            let envelope: serde_json::Value = serde_json::from_slice(&plaintext)?;
            let remote_data = Backup::decode(&serde_json::to_string(&envelope["data"])?)?;
            assert!(merge::equivalent(&backup(&cores[0]), &remote_data)?);
            let stable = cores[0].export_json()?;
            for &i in order.iter().rev() {
                cores[i].sync_now(remote.clone())?;
            }
            assert_eq!(cores[0].export_json()?, stable);
        }
    }
    Ok(())
}
#[test]
fn undo_created_or_consumed_during_sync_never_survives_success_or_rewrites_merged_history()
-> TestResult {
    let (_d, core) = open();
    enabled(&core);
    let id = core.create_asset("cash".into(), "余额".into(), 100)?;
    let remote = Arc::new(Remote::default());
    let pending = Arc::new(Mutex::new(None));
    let out = pending.clone();
    let writer = core.clone();
    let asset = id.clone();
    *remote.before_put.lock().unwrap() = Some(Box::new(move || {
        *out.lock().unwrap() = Some(writer.change_balance(asset, 200).unwrap().unwrap());
    }));
    assert!(core.sync_now(remote.clone())?.pending_local_changes);
    let ticket = pending.lock().unwrap().take().unwrap();
    let after = core.export_json()?;
    assert_eq!(core.undo(ticket.token), Err(CoreError::Conflict));
    assert_eq!(core.export_json()?, after);
    assert_eq!(core.data()?.assets[0].amount_cents, 200);
    core.sync_now(remote.clone())?;
    let ticket = core.change_balance(id.clone(), 300)?.unwrap();
    let writer = core.clone();
    *remote.before_put.lock().unwrap() = Some(Box::new(move || writer.undo(ticket.token).unwrap()));
    assert!(core.sync_now(remote.clone())?.pending_local_changes);
    assert_eq!(core.data()?.assets[0].amount_cents, 200);
    assert_eq!(backup(&core).voided_history.len(), 1);
    core.sync_now(remote.clone())?;
    let before = core.export_json()?;
    core.sync_now(remote)?;
    assert_eq!(core.export_json()?, before);
    Ok(())
}
#[test]
fn known_limit_delete_then_resurrection_can_drop_an_offline_third_device_history() -> TestResult {
    let (_ad, a) = open();
    let (_bd, b) = open();
    let (_cd, c) = open();
    let id = a.create_asset("cash".into(), "原始".into(), 100)?;
    let initial = a.export_json()?;
    b.import_json(initial.clone())?;
    c.import_json(initial)?;
    a.change_balance(id.clone(), 200)?;
    b.delete_asset(id.clone())?;
    c.change_balance(id, 300)?;
    let mut a = backup(&a);
    let mut b = backup(&b);
    let mut c = backup(&c);
    a.assets[0].updated_at = "2028-01-01T00:00:01.000000000Z".into();
    b.tombstones[0].deleted_at = "2028-01-01T00:00:02.000000000Z".into();
    c.assets[0].updated_at = "2028-01-01T00:00:03.000000000Z".into();
    let deletion_first = merge::merge(&merge::merge(&a, &b)?, &c)?;
    let resurrection_first = merge::merge(&a, &merge::merge(&b, &c)?)?;
    assert_eq!(deletion_first.assets.len(), 1);
    assert_eq!(resurrection_first.assets.len(), 1);
    assert_eq!(deletion_first.assets[0].amount_cents, 300);
    assert_eq!(resurrection_first.assets[0].amount_cents, 300);
    assert_eq!(deletion_first.history.len(), 2);
    assert_eq!(resurrection_first.history.len(), 3);
    Ok(())
}

#[test]
fn remote_changes_again_during_412_retries_are_fetched_and_unioned_before_success() -> TestResult {
    let (_ad, a, _bd, b) = paired();
    let remote = Arc::new(Remote::default());
    a.create_asset("cash".into(), "最初".into(), 1)?;
    a.sync_now(remote.clone())?;
    b.sync_now(remote.clone())?;
    a.sync_now(remote.clone())?;
    let (_cd, c) = open();
    c.import_recovery_code(a.export_recovery_code()?, None)?;
    c.import_json(a.export_json()?)?;
    enabled(&c);
    let staging = Arc::new(Remote::default());
    c.create_asset("cash".into(), "第一次远端变更".into(), 2)?;
    c.sync_now(staging.clone())?;
    let first = staging.bytes();
    c.create_asset("cash".into(), "重试期间远端再变".into(), 3)?;
    c.sync_now(staging.clone())?;
    let second = staging.bytes();
    struct Churn {
        inner: Arc<Remote>,
        changes: Mutex<std::collections::VecDeque<Vec<u8>>>,
    }
    impl SyncTransport for Churn {
        fn send(&self, request: SyncRequest) -> Result<SyncResponse, CoreError> {
            if request.method == "PUT" {
                if let Some(body) = self.changes.lock().unwrap().pop_front() {
                    let mut object = self.inner.object.lock().unwrap();
                    object.body = Some(body);
                    object.version += 1;
                }
            }
            self.inner.send(request)
        }
    }
    a.create_asset("cash".into(), "本机待推送".into(), 4)?;
    let report = a.sync_now(Arc::new(Churn {
        inner: remote.clone(),
        changes: Mutex::new([first, second].into()),
    }))?;
    assert_eq!(report.retries, 2);
    assert_eq!(remote.conflicts.load(Ordering::SeqCst), 2);
    assert_eq!(a.data()?.assets.len(), 4);
    b.sync_now(remote.clone())?;
    a.sync_now(remote)?;
    identical(&a, &b);
    Ok(())
}
#[test]
fn failed_put_does_not_apply_valid_remote_merge_or_consume_local_undo() -> TestResult {
    let (_ad, a, _bd, b) = paired();
    let remote = Arc::new(Remote::default());
    a.create_asset("cash".into(), "远端独有".into(), 1)?;
    a.sync_now(remote.clone())?;
    let local = b.create_asset("cash".into(), "本机独有".into(), 2)?;
    let ticket = b.change_balance(local, 3)?.unwrap();
    let before = b.export_json()?;
    struct FailPut(Arc<Remote>);
    impl SyncTransport for FailPut {
        fn send(&self, request: SyncRequest) -> Result<SyncResponse, CoreError> {
            if request.method == "PUT" {
                return Ok(SyncResponse {
                    status: 503,
                    headers: vec![],
                    body: vec![],
                });
            }
            self.0.send(request)
        }
    }
    assert!(matches!(
        b.sync_now(Arc::new(FailPut(remote))),
        Err(CoreError::SyncNetwork)
    ));
    assert_eq!(b.export_json()?, before);
    assert!(b.sync_status()?.last_synced_at.is_none());
    b.undo(ticket.token)?;
    assert_eq!(b.data()?.assets[0].amount_cents, 2);
    Ok(())
}
#[test]
fn deleting_and_undoing_the_only_future_dated_asset_preserves_the_clock_high_water_mark()
-> TestResult {
    let (_d, c) = open();
    let id = c.create_asset("cash".into(), "时钟回拨".into(), 0)?;
    let mut original = backup(&c);
    original.assets[0].updated_at = "2090-01-01T00:00:00.000000000Z".into();
    c.import_json(serde_json::to_string(&original)?)?;
    let undo = c.delete_asset(id)?;
    let deleted_at = backup(&c).tombstones[0].deleted_at.clone();
    c.undo(undo.token)?;
    assert!(c.data()?.assets[0].updated_at > deleted_at);
    assert!(backup(&c).tombstones.is_empty());
    Ok(())
}
#[test]
fn concurrent_branches_keep_one_root_and_reject_disconnected_snapshot_instead_of_importing_it()
-> TestResult {
    let (_d, c) = open();
    c.create_asset("cash".into(), "唯一首条".into(), 1)?;
    let before = c.export_json()?;
    let mut disconnected = backup(&c);
    let mut extra = disconnected.history[0].clone();
    extra.id = model::Id::new()?;
    disconnected.history.push(extra);
    assert_eq!(
        c.import_json(serde_json::to_string(&disconnected)?),
        Err(CoreError::InvalidBackup)
    );
    assert_eq!(c.export_json()?, before);
    Ok(())
}

#[test]
fn sync_policy_defaults_validation_configuration_reset_and_reopen_are_persistent() -> TestResult {
    let (directory, core) = open();
    assert_eq!(
        core.sync_status()?.policy,
        SyncPolicy {
            on_launch: true,
            interval_minutes: 30,
            after_edit: true
        }
    );
    assert!(core.key_fingerprint()?.is_none());
    for interval in [0, 15, 30, 60] {
        let chosen = SyncPolicy {
            on_launch: false,
            interval_minutes: interval,
            after_edit: false,
        };
        core.set_sync_policy(chosen.clone())?;
        assert_eq!(core.sync_status()?.policy, chosen);
    }
    let chosen = core.sync_status()?.policy;
    for interval in [1, 14, 16, 31, 59, 61, u32::MAX] {
        assert_eq!(
            core.set_sync_policy(SyncPolicy {
                on_launch: true,
                interval_minutes: interval,
                after_edit: true
            }),
            Err(CoreError::Validation)
        );
        assert_eq!(core.sync_status()?.policy, chosen);
    }
    core.configure_sync(config(), "policy-secret".into())?;
    assert_eq!(core.sync_status()?.policy, chosen);
    assert!(!core.export_json()?.contains("interval_minutes"));
    drop(core);
    let core = SnowFoldCore::open(
        directory.path().to_string_lossy().into_owned(),
        Arc::new(keystore::FileKeyStore::new(directory.path())),
    )?;
    assert_eq!(core.sync_status()?.policy, chosen);
    core.configure_sync(config(), "replacement-secret".into())?;
    assert_eq!(core.sync_status()?.policy, chosen);
    let db = rusqlite::Connection::open(directory.path().join("snowfold.sqlite3"))?;
    assert!(
        db.execute("UPDATE sync_state SET interval_minutes=1", [])
            .is_err()
    );
    assert!(db.execute("UPDATE sync_state SET on_launch=2", []).is_err());
    core.clear_sync_config()?;
    assert_eq!(core.sync_status()?.policy, SyncPolicy::default());
    assert!(core.sync_status()?.config.is_none());
    Ok(())
}
#[test]
fn rejecting_last_sync_timestamp_rolls_back_the_entire_local_merge_and_retains_undo() -> TestResult
{
    let (_ad, a, bd, b) = paired();
    let remote = Arc::new(Remote::default());
    a.create_asset("cash".into(), "远端待合并".into(), 1)?;
    a.sync_now(remote.clone())?;
    let local = b.create_asset("cash".into(), "本机".into(), 2)?;
    let ticket = b.change_balance(local, 3)?.unwrap();
    let before = b.export_json()?;
    let db = rusqlite::Connection::open(bd.path().join("snowfold.sqlite3"))?;
    db.execute_batch("CREATE TRIGGER reject_sync_timestamp BEFORE UPDATE OF last_synced_at ON sync_state WHEN NEW.last_synced_at IS NOT NULL BEGIN SELECT RAISE(ABORT,'reject sync timestamp'); END;")?;
    assert!(matches!(
        b.sync_now(remote.clone()),
        Err(CoreError::Conflict)
    ));
    assert_eq!(b.export_json()?, before);
    assert!(b.sync_status()?.last_synced_at.is_none());
    b.undo(ticket.token)?;
    assert_eq!(b.data()?.assets[0].amount_cents, 2);
    db.execute_batch("DROP TRIGGER reject_sync_timestamp;")?;
    b.sync_now(remote)?;
    assert_eq!(b.data()?.assets.len(), 2);
    assert!(b.sync_status()?.last_synced_at.is_some());
    Ok(())
}

#[test]
fn separate_storage_write_after_successful_upload_is_preserved_and_reported_pending() -> TestResult
{
    let (directory, core) = open();
    enabled(&core);
    core.create_asset("cash".into(), "推送内容".into(), 1)?;
    struct WriteAfterUpload {
        inner: Remote,
        path: std::path::PathBuf,
        writes: AtomicUsize,
    }
    impl SyncTransport for WriteAfterUpload {
        fn send(&self, request: SyncRequest) -> Result<SyncResponse, CoreError> {
            let is_put = request.method == "PUT";
            let response = self.inner.send(request)?;
            if is_put && response.status == 200 && self.writes.fetch_add(1, Ordering::SeqCst) == 0 {
                let mut independent = storage::Storage::open(&self.path).unwrap();
                independent
                    .create_asset("cash", "推送成功后另一连接提交", 2)
                    .unwrap();
            }
            Ok(response)
        }
    }
    let transport = Arc::new(WriteAfterUpload {
        inner: Remote::default(),
        path: directory.path().join("snowfold.sqlite3"),
        writes: AtomicUsize::new(0),
    });
    let report = core.sync_now(transport.clone())?;
    assert!(report.pending_local_changes);
    assert_eq!(report.pushed_records, 2);
    assert_eq!(core.data()?.assets.len(), 2);
    assert_eq!(core.data()?.history.len(), 2);
    assert!(
        core.data()?
            .assets
            .iter()
            .any(|a| a.name == "推送成功后另一连接提交")
    );
    let next = core.sync_now(transport)?;
    assert!(!next.pending_local_changes);
    assert_eq!(next.pushed_records, 4);
    Ok(())
}
