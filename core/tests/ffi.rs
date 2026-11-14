use snowfold_core::{
    CoreError, SnowFoldCore, SubscriptionGroup, SubscriptionInput, ThemePreference, backup::Backup,
    model::Id,
};
use std::sync::{Arc, Barrier};

fn open(directory: &tempfile::TempDir) -> Arc<SnowFoldCore> {
    SnowFoldCore::open(directory.path().to_string_lossy().into_owned()).unwrap()
}
fn subscription() -> SubscriptionInput {
    SubscriptionInput {
        name: "云存储".into(),
        amount_cents: 101,
        currency: "USD".into(),
        cycle: "monthly".into(),
        next_charge_on: "2024-01-31".into(),
        status: "active".into(),
    }
}

/// Loads the storage test fixture through the public JSON import path.
fn load_fixture(core: &SnowFoldCore) -> Result<(), CoreError> {
    let json =
        serde_json::to_string(&snowfold_core::storage::Storage::example_data().unwrap()).unwrap();
    core.import_json(json)
}

#[test]
fn asset_writes_validate_and_preserve_snapshot_undo_rules() -> anyhow::Result<()> {
    let directory = tempfile::tempdir()?;
    let core = open(&directory);
    let id = core.create_asset("cash".into(), "账户".into(), 0)?;
    let before = core.export_json()?;
    assert_eq!(
        core.create_asset("cash".into(), "错误".into(), -1),
        Err(CoreError::Validation)
    );
    assert_eq!(
        core.create_asset("cash".into(), " ".into(), 0),
        Err(CoreError::Validation)
    );
    assert_eq!(
        core.create_asset("unknown".into(), "账户".into(), 0),
        Err(CoreError::Validation)
    );
    assert_eq!(core.export_json()?, before);
    assert!(core.change_balance(id.clone(), 0)?.is_none());
    core.rename_asset(id.clone(), "新名称".into())?;
    let initial = core.data()?.history[0].id.clone();
    let undo = core.change_balance(id.clone(), 200)?.unwrap();
    core.correct_history(initial.clone(), 100)?;
    core.undo(undo.token.clone())?;
    assert_eq!(core.data()?.assets[0].amount_cents, 100);
    assert_eq!(core.undo(undo.token), Err(CoreError::Conflict));
    let undo = core.delete_asset(id.clone())?;
    assert!(core.data()?.history.is_empty());
    core.undo(undo.token)?;
    assert_eq!(core.data()?.assets[0].name, "新名称");
    assert_eq!(
        core.rename_asset(Id::new()?.to_string(), "缺失".into()),
        Err(CoreError::NotFound)
    );
    core.correct_history(initial.clone(), 300)?;
    assert_eq!(core.data()?.assets[0].amount_cents, 300);
    Ok(())
}

#[test]
fn subscriptions_share_calendar_groups_and_currency_totals() -> anyhow::Result<()> {
    let directory = tempfile::tempdir()?;
    let core = open(&directory);
    let id = core.create_subscription(subscription())?;
    assert_eq!(
        core.subscription_group(id.clone(), "2024-02-01".into())?,
        SubscriptionGroup::Overdue
    );
    assert_eq!(core.subscription_annual(id.clone())?, 1212);
    let overview = core.overview("2024-01-31".into())?;
    assert_eq!(overview.subscription_totals[1].due_this_month_cents, 101);
    assert_eq!(overview.subscription_totals[1].monthly_cents, 101);
    let renewed = core.renew_subscription(id.clone(), "2024-03-01".into())?;
    assert_eq!(
        (renewed.next_charge_on.as_str(), renewed.periods),
        ("2024-03-29", 2)
    );
    core.set_subscription_paused(id.clone(), true)?;
    assert_eq!(
        core.subscription_group(id.clone(), "2024-03-01".into())?,
        SubscriptionGroup::Paused
    );
    assert_eq!(
        core.renew_subscription(id.clone(), "2024-04-01".into())
            .unwrap_err(),
        CoreError::Conflict
    );
    assert_eq!(
        core.overview("2024-03-01".into())?.subscription_totals[1].annual_cents,
        0
    );
    core.set_subscription_paused(id.clone(), false)?;
    let before = core.export_json()?;
    let mut invalid = subscription();
    invalid.next_charge_on = "2024-02-30".into();
    assert_eq!(
        core.create_subscription(invalid),
        Err(CoreError::Validation)
    );
    assert_eq!(core.export_json()?, before);
    let undo = core.delete_subscription(id.clone())?;
    core.undo(undo.token)?;
    let restored = core.data()?.subscriptions.remove(0);
    assert_eq!(restored.id, id);
    assert_eq!(restored.amount_cents, 101);
    assert_ne!(
        serde_json::from_str::<Backup>(&core.export_json()?)?.subscriptions[0].revision_uid,
        serde_json::from_str::<Backup>(&before)?.subscriptions[0].revision_uid
    );
    Ok(())
}

#[test]
fn sealed_import_authentication_and_recovery_conflicts_never_replace_data() -> anyhow::Result<()> {
    let source_dir = tempfile::tempdir()?;
    let source = open(&source_dir);
    source.create_asset("cash".into(), "源账户".into(), 12345)?;
    let sealed = source.export_sealed()?;
    let recovery = source.export_recovery_code()?;
    let target_dir = tempfile::tempdir()?;
    let target = open(&target_dir);
    target.create_asset("cash".into(), "本机账户".into(), 99)?;
    let before = target.export_json()?;
    assert_eq!(
        target.import_sealed(sealed.clone()).unwrap_err(),
        CoreError::Authentication
    );
    assert_eq!(target.export_json()?, before);
    let info = target.inspect_recovery_code(recovery.clone())?;
    assert_eq!(
        target
            .import_recovery_code(recovery.clone(), Some("stale".into()))
            .unwrap_err(),
        CoreError::Conflict
    );
    assert_eq!(target.key_fingerprint()?, info.current_fingerprint);
    let replaced = target.import_recovery_code(recovery, info.current_fingerprint)?;
    assert!(replaced.previous_key_backed_up);
    assert!(target_dir.path().join("snowfold-sync.key.bak").is_file());
    assert_eq!(target.key_fingerprint()?, source.key_fingerprint()?);
    assert_eq!(target.inspect_sealed(sealed.clone())?.counts.assets, 1);
    let mut tampered = sealed.clone();
    let last = tampered.len() - 1;
    tampered[last] ^= 1;
    assert_eq!(
        target.import_sealed(tampered).unwrap_err(),
        CoreError::Authentication
    );
    assert_eq!(target.export_json()?, before);
    target.import_sealed(sealed.clone())?;
    assert_eq!(target.export_json()?, source.export_json()?);
    let before = target.export_json()?;
    let mut future = sealed;
    future[4] = 2;
    assert_eq!(
        target.import_sealed(future).unwrap_err(),
        CoreError::IncompatibleBackup
    );
    assert_eq!(
        target.import_sealed(vec![0; 10]).unwrap_err(),
        CoreError::InvalidBackup
    );
    assert_eq!(target.export_json()?, before);
    assert_eq!(
        target.inspect_recovery_code("broken".into()).unwrap_err(),
        CoreError::InvalidRecoveryCode
    );
    Ok(())
}

#[test]
fn baseline_f72e2f0_is_rejected_and_original_totals_are_preserved() -> anyhow::Result<()> {
    const JSON: &str = include_str!("fixtures/f72e2f0/backup.json");
    const KEY: &str = include_str!("fixtures/f72e2f0/snowfold-sync.key");
    const SEALED: &[u8] = include_bytes!("fixtures/f72e2f0/backup.sfseal");
    let old = tempfile::tempdir()?;
    let path = old.path().join("snowfold.sqlite3");
    std::fs::write(&path, include_bytes!("fixtures/f72e2f0/billfold.sqlite3"))?;
    let bytes = std::fs::read(&path)?;
    assert_eq!(
        SnowFoldCore::open(old.path().to_string_lossy().into()).err(),
        Some(CoreError::IncompatibleDatabase)
    );
    assert_eq!(std::fs::read(&path)?, bytes);
    let directory = tempfile::tempdir()?;
    let core = open(&directory);
    core.create_asset("cash".into(), "资产".into(), 23456)?;
    core.create_asset("debt".into(), "负债".into(), 456)?;
    let before = core.export_json()?;
    assert_eq!(
        core.import_json(JSON.into()),
        Err(CoreError::IncompatibleBackup)
    );
    core.import_recovery_code(KEY.into(), None)?;
    assert_eq!(
        core.import_sealed(SEALED.into()).unwrap_err(),
        CoreError::IncompatibleBackup
    );
    assert_eq!(core.export_json()?, before);
    let overview = core.overview("2026-11-14".into())?;
    assert_eq!(
        (
            overview.total_assets_cents,
            overview.total_debt_cents,
            overview.net_assets_cents
        ),
        (23456, 456, 23000)
    );
    Ok(())
}

#[test]
fn json_failures_settings_examples_and_clear_keep_their_boundaries() -> anyhow::Result<()> {
    let directory = tempfile::tempdir()?;
    let core = open(&directory);
    load_fixture(&core)?;
    let data = core.data()?;
    assert_eq!(
        (
            data.assets.len(),
            data.history.len(),
            data.subscriptions.len()
        ),
        (11, 22, 9)
    );
    assert_eq!(
        core.overview("2026-11-14".into())?.net_assets_cents,
        78800000
    );
    let before = core.export_json()?;
    assert_eq!(core.import_json("{}".into()), Err(CoreError::InvalidBackup));
    let mut future: serde_json::Value = serde_json::from_str(&before)?;
    future["format_version"] = serde_json::json!(2);
    assert_eq!(
        core.import_json(future.to_string()),
        Err(CoreError::IncompatibleBackup)
    );
    assert_eq!(core.export_json()?, before);
    core.save_theme(ThemePreference::Dark)?;
    core.startup_backup()?;
    assert_eq!(
        std::fs::read_dir(directory.path().join("backups"))?.count(),
        1
    );
    let ticket = core.delete_asset(data.assets[0].id.clone())?;
    core.clear_data()?;
    assert_eq!(core.undo(ticket.token), Err(CoreError::Conflict));
    assert!(core.data()?.assets.is_empty());
    drop(core);
    assert_eq!(open(&directory).theme()?, ThemePreference::Dark);
    Ok(())
}

#[test]
fn concurrent_writes_and_failed_calls_release_the_serial_connection() -> anyhow::Result<()> {
    let directory = tempfile::tempdir()?;
    let core = open(&directory);
    let barrier = Arc::new(Barrier::new(8));
    let handles: Vec<_> = (0..8)
        .map(|index| {
            let core = core.clone();
            let barrier = barrier.clone();
            std::thread::spawn(move || {
                barrier.wait();
                assert_eq!(
                    core.create_asset("cash".into(), "invalid".into(), -1),
                    Err(CoreError::Validation)
                );
                core.create_asset("cash".into(), format!("账户{index}"), 101)
                    .unwrap()
            })
        })
        .collect();
    let ids: std::collections::HashSet<_> =
        handles.into_iter().map(|h| h.join().unwrap()).collect();
    assert_eq!(ids.len(), 8);
    let data = core.data()?;
    assert_eq!((data.assets.len(), data.history.len()), (8, 8));
    assert_eq!(core.overview("2026-11-14".into())?.total_assets_cents, 808);
    let exported: Backup = serde_json::from_str(&core.export_json()?)?;
    exported.validate()?;
    std::fs::write(directory.path().join("snowfold-sync.key"), "corrupt")?;
    assert_eq!(core.export_sealed().unwrap_err(), CoreError::KeyUnavailable);
    core.rename_asset(ids.iter().next().unwrap().clone(), "锁已释放".into())?;
    assert_eq!(
        std::fs::read_to_string(directory.path().join("snowfold-sync.key"))?,
        "corrupt"
    );
    Ok(())
}

#[test]
fn money_boundaries_and_aggregate_overflow_are_explicit() -> anyhow::Result<()> {
    use snowfold_core::{format_amount, format_money, parse_amount};
    assert_eq!(parse_amount("123.45".into())?, 12345);
    for invalid in ["", "1.001", "NaN", "-1", "92233720368547759"] {
        assert_eq!(parse_amount(invalid.into()), Err(CoreError::Validation));
    }
    assert_eq!(format_amount(0)?, "0.00");
    assert_eq!(format_money(-101, "USD".into())?, "−$1.01");
    let directory = tempfile::tempdir()?;
    let core = open(&directory);
    core.create_asset("cash".into(), "上限".into(), i64::MAX)?;
    core.create_asset("cash".into(), "一分".into(), 1)?;
    assert_eq!(
        core.overview("2026-11-14".into()).unwrap_err(),
        CoreError::Overflow
    );
    assert_eq!(
        core.overview("2026-1-1".into()).unwrap_err(),
        CoreError::Validation
    );
    assert_eq!(core.data()?.assets[0].amount_cents, i64::MAX);
    Ok(())
}

#[test]
fn explicit_subscription_inputs_and_all_write_failures_keep_current_data() -> anyhow::Result<()> {
    let directory = tempfile::tempdir()?;
    let core = open(&directory);
    let id = core.create_subscription(subscription())?;
    let original = core.data()?.subscriptions.remove(0);
    let mut input = subscription();
    input.name = "更新".into();
    core.update_subscription(id.clone(), input.clone())?;
    let updated = core.data()?.subscriptions.remove(0);
    assert_eq!(updated.name, "更新");
    assert_eq!(updated.created_at, original.created_at);
    assert_ne!(updated.revision_uid, original.revision_uid);
    let before = core.export_json()?;
    input.amount_cents = -1;
    assert_eq!(
        core.update_subscription(id.clone(), input),
        Err(CoreError::Validation)
    );
    let missing = Id::new()?.to_string();
    assert_eq!(
        core.update_subscription(missing.clone(), subscription()),
        Err(CoreError::NotFound)
    );
    assert_eq!(
        core.set_subscription_paused(missing.clone(), true),
        Err(CoreError::NotFound)
    );
    assert_eq!(
        core.renew_subscription(missing.clone(), "2024-01-01".into())
            .unwrap_err(),
        CoreError::NotFound
    );
    assert_eq!(
        core.delete_subscription(missing.clone()).unwrap_err(),
        CoreError::NotFound
    );
    assert_eq!(core.delete_asset(missing).unwrap_err(), CoreError::NotFound);
    assert_eq!(
        core.rename_asset("0".into(), "name".into()),
        Err(CoreError::Validation)
    );
    assert_eq!(
        core.renew_subscription(id, "2024-01-01".into())
            .unwrap_err(),
        CoreError::Conflict
    );
    assert_eq!(core.export_json()?, before);
    let db = rusqlite::Connection::open(directory.path().join("snowfold.sqlite3"))?;
    db.execute_batch("CREATE TRIGGER fail_examples BEFORE INSERT ON subscriptions BEGIN SELECT RAISE(ABORT,'test'); END;")?;
    assert_eq!(load_fixture(&core), Err(CoreError::Conflict));
    assert_eq!(core.export_json()?, before);
    db.execute_batch("DROP TRIGGER fail_examples; CREATE TRIGGER fail_clear BEFORE DELETE ON subscriptions BEGIN SELECT RAISE(ABORT,'test'); END;")?;
    assert_eq!(core.clear_data(), Err(CoreError::Conflict));
    assert_eq!(core.export_json()?, before);
    db.execute_batch("DROP TRIGGER fail_clear;")?;
    core.clear_data()?;
    load_fixture(&core)?;
    Ok(())
}

#[test]
fn midway_create_delete_and_undo_failures_roll_back_and_allow_retry() -> anyhow::Result<()> {
    let directory = tempfile::tempdir()?;
    let core = open(&directory);
    let db = rusqlite::Connection::open(directory.path().join("snowfold.sqlite3"))?;
    let before = core.export_json()?;
    db.execute_batch("CREATE TRIGGER fail_history BEFORE INSERT ON asset_history BEGIN SELECT RAISE(ABORT,'test'); END;")?;
    assert_eq!(
        core.create_asset("cash".into(), "A".into(), 100),
        Err(CoreError::Conflict)
    );
    assert_eq!(core.export_json()?, before);
    db.execute_batch("DROP TRIGGER fail_history;")?;
    let id = core.create_asset("cash".into(), "A".into(), 100)?;
    let before = core.export_json()?;
    db.execute_batch("CREATE TRIGGER fail_delete BEFORE DELETE ON asset_items BEGIN SELECT RAISE(ABORT,'test'); END;")?;
    assert_eq!(
        core.delete_asset(id.clone()).unwrap_err(),
        CoreError::Conflict
    );
    assert_eq!(core.export_json()?, before);
    db.execute_batch("DROP TRIGGER fail_delete;")?;
    let ticket = core.delete_asset(id.clone())?;
    let deleted = core.export_json()?;
    db.execute_batch("CREATE TRIGGER fail_undo BEFORE INSERT ON asset_items BEGIN SELECT RAISE(ABORT,'test'); END;")?;
    assert_eq!(core.undo(ticket.token.clone()), Err(CoreError::Conflict));
    assert_eq!(core.export_json()?, deleted);
    db.execute_batch("DROP TRIGGER fail_undo;")?;
    core.undo(ticket.token)?;
    assert_eq!(core.data()?.assets[0].id, id);
    assert_eq!(core.data()?.assets[0].amount_cents, 100);
    Ok(())
}
