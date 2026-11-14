use super::{
    bind_keys,
    state::{Confirm, Editing, SnowFold},
};
use gpui::*;
use snowfold_core::{
    backup::Backup,
    model::today,
    storage::{Storage, Theme},
};

use core::prelude::v1::test;
use std::sync::{
    Arc,
    atomic::{AtomicUsize, Ordering},
};

struct SyncTestTransport {
    calls: AtomicUsize,
    fail: bool,
}
impl snowfold_core::SyncTransport for SyncTestTransport {
    fn send(
        &self,
        request: snowfold_core::SyncRequest,
    ) -> Result<snowfold_core::SyncResponse, snowfold_core::CoreError> {
        self.calls.fetch_add(1, Ordering::SeqCst);
        if self.fail {
            return Err(snowfold_core::CoreError::SyncNetwork);
        }
        Ok(snowfold_core::SyncResponse {
            status: if request.method == "GET" { 404 } else { 201 },
            headers: vec![],
            body: vec![],
        })
    }
}
fn enable_test_sync(
    view: &Entity<SnowFold>,
    cx: &mut VisualTestContext,
    fail: bool,
) -> Arc<SyncTestTransport> {
    let transport = Arc::new(SyncTestTransport {
        calls: AtomicUsize::new(0),
        fail,
    });
    view.update_in(cx, |view, _, cx| {
        let core = view.sync.core.as_ref().unwrap();
        core.export_recovery_code().unwrap();
        core.configure_sync(
            snowfold_core::SyncConfig {
                backend: snowfold_core::SyncBackend::S3,
                endpoint: "http://127.0.0.1:1".into(),
                bucket: "snowfold-tests".into(),
                region: "us-east-1".into(),
                prefix: String::new(),
                username: "test-access".into(),
                path_style: true,
            },
            "test-secret".into(),
        )
        .unwrap();
        core.set_sync_policy(snowfold_core::SyncPolicy {
            on_launch: false,
            interval_minutes: 0,
            after_edit: false,
        })
        .unwrap();
        core.set_sync_enabled(true).unwrap();
        view.refresh_sync();
        view.sync.transport = transport.clone();
        cx.notify();
    });
    cx.run_until_parked();
    transport
}

#[gpui::test]
fn sync_button_ignores_repeated_clicks_while_busy(cx: &mut TestAppContext) {
    cx.update(|cx| {
        gpui_component::init(cx);
        bind_keys(cx);
    });
    let directory = tempfile::tempdir().unwrap();
    let storage = Storage::open(&directory.path().join("snowfold.sqlite3")).unwrap();
    let mut entity = None;
    let (_, cx) = cx.add_window_view(|window, cx| {
        let view = cx.new(|cx| SnowFold::load(Ok(storage), window, cx));
        entity = Some(view.clone());
        gpui_component::Root::new(view, window, cx)
    });
    let view = entity.unwrap();
    assert!(
        cx.debug_bounds("sync-button").is_none(),
        "未开启时不显示同步按钮"
    );
    let transport = enable_test_sync(&view, cx, false);
    view.update_in(cx, |view, _, cx| {
        view.sync.busy = true;
        cx.notify();
    });
    cx.run_until_parked();
    click(cx, "sync-button");
    click(cx, "sync-button");
    assert_eq!(
        transport.calls.load(Ordering::SeqCst),
        0,
        "同步中再点不会发起另一轮传输"
    );
    view.read_with(cx, |view, _| assert!(view.sync.busy));
}

#[gpui::test]
fn sync_allows_local_edit_while_busy(cx: &mut TestAppContext) {
    cx.update(|cx| {
        gpui_component::init(cx);
        bind_keys(cx);
    });
    let directory = tempfile::tempdir().unwrap();
    let storage = Storage::open(&directory.path().join("snowfold.sqlite3")).unwrap();
    let mut entity = None;
    let (_, cx) = cx.add_window_view(|window, cx| {
        let view = cx.new(|cx| SnowFold::load(Ok(storage), window, cx));
        entity = Some(view.clone());
        gpui_component::Root::new(view, window, cx)
    });
    let view = entity.unwrap();
    enable_test_sync(&view, cx, false);
    view.update_in(cx, |view, _, cx| {
        view.sync.busy = true;
        cx.notify();
    });
    cx.simulate_keystrokes("cmd-2 cmd-n");
    cx.simulate_input("同步中本机编辑");
    cx.simulate_keystrokes("tab");
    cx.simulate_input("123");
    cx.simulate_keystrokes("enter");
    view.read_with(cx, |view, _| {
        assert!(view.sync.busy);
        assert!(view.error.is_none(), "{:?}", view.error);
        assert_eq!(view.data.assets.len(), 1);
        assert_eq!(view.data.assets[0].amount_cents, 12300);
    });
}

#[gpui::test]
fn sync_success_clears_undo_and_history_panel(cx: &mut TestAppContext) {
    cx.update(|cx| {
        gpui_component::init(cx);
        bind_keys(cx);
    });
    let directory = tempfile::tempdir().unwrap();
    let mut storage = Storage::open(&directory.path().join("snowfold.sqlite3")).unwrap();
    let id = storage.create_asset("cash", "同步资产", 10000).unwrap();
    let undo = storage.change_balance(id, 20000).unwrap().unwrap();
    let mut entity = None;
    let (_, cx) = cx.add_window_view(|window, cx| {
        let view = cx.new(|cx| SnowFold::load(Ok(storage), window, cx));
        entity = Some(view.clone());
        gpui_component::Root::new(view, window, cx)
    });
    let view = entity.unwrap();
    enable_test_sync(&view, cx, false);
    view.update_in(cx, |view, _, cx| {
        view.offer_undo(undo, "已记录本次变动", cx);
        view.history = Some(id);
        cx.notify();
    });
    click(cx, "sync-button");
    cx.run_until_parked();
    view.read_with(cx, |view, _| {
        assert!(!view.sync.busy);
        assert!(view.sync.failure.is_none());
        assert!(view.sync.status.as_ref().unwrap().last_synced_at.is_some());
        assert!(view.undo.is_none());
        assert!(view.history.is_none());
        assert_eq!(view.data.assets[0].amount_cents, 20000);
    });
    // GPUI 0.2.2 的 Frame::clear 不清 debug_bounds，旧选择器不能用于断言控件消失。
    // 撤销显示条件与票据由上面的 undo.is_none() 断言验证。
}

#[gpui::test]
fn sync_failure_shows_retry_red_dot(cx: &mut TestAppContext) {
    cx.update(|cx| {
        gpui_component::init(cx);
        bind_keys(cx);
    });
    let directory = tempfile::tempdir().unwrap();
    let storage = Storage::open(&directory.path().join("snowfold.sqlite3")).unwrap();
    let mut entity = None;
    let (_, cx) = cx.add_window_view(|window, cx| {
        let view = cx.new(|cx| SnowFold::load(Ok(storage), window, cx));
        entity = Some(view.clone());
        gpui_component::Root::new(view, window, cx)
    });
    let view = entity.unwrap();
    enable_test_sync(&view, cx, true);
    click(cx, "sync-button");
    cx.run_until_parked();
    view.read_with(cx, |view, _| {
        assert!(!view.sync.busy);
        assert_eq!(
            view.sync.failure.as_deref(),
            Some("无法连接同步服务，请检查网络与服务地址后重试")
        );
    });
    assert!(cx.debug_bounds("sync-button-failed").is_some());
    assert!(cx.debug_bounds("sync-failure-dot").is_some());
}
fn click(cx: &mut VisualTestContext, label: &'static str) {
    let bounds = cx
        .debug_bounds(label)
        .unwrap_or_else(|| panic!("missing rendered control: {label}"));
    cx.simulate_click(bounds.center(), Modifiers::none());
}
#[gpui::test]
fn topbar_geometry_matches_reference_layout(cx: &mut TestAppContext) {
    cx.update(|cx| {
        gpui_component::init(cx);
        bind_keys(cx);
    });
    let directory = tempfile::tempdir().unwrap();
    let storage = Storage::open(&directory.path().join("layout.sqlite3")).unwrap();
    let (_root, cx) = cx.add_window_view(|window, cx| {
        let view = cx.new(|cx| SnowFold::load(Ok(storage), window, cx));
        gpui_component::Root::new(view, window, cx)
    });
    cx.simulate_resize(gpui::size(px(1440.), px(900.)));
    let close = |a: Pixels, b: Pixels| (f32::from(a) - f32::from(b)).abs() < 1.5;
    let brand = cx.debug_bounds("brand").expect("brand rendered");
    assert!(
        close(brand.origin.x, px(88.)),
        "brand x {}",
        f32::from(brand.origin.x)
    );
    assert!(
        close(brand.origin.y, px(17.)),
        "brand y {}",
        f32::from(brand.origin.y)
    );
    let tab = cx.debug_bounds("tab0").expect("tab rendered");
    assert!(close(tab.size.height, px(29.)), "tab height");
    assert!(close(tab.size.width, px(64.)), "tab width");
    // 1440 wide window, 202 wide segmented control -> first tab starts at 622.
    assert!(
        close(tab.origin.x, px(622.)),
        "tab x {}",
        f32::from(tab.origin.x)
    );
    assert!(
        close(tab.origin.y, px(13.5)),
        "tab y {}",
        f32::from(tab.origin.y)
    );
    let settings = cx.debug_bounds("settings").expect("settings rendered");
    assert!(close(settings.size.width, px(30.)), "settings width");
    assert!(close(settings.size.height, px(30.)), "settings height");
    assert!(
        close(settings.origin.x + settings.size.width, px(1426.)),
        "settings right edge"
    );
    assert!(close(settings.origin.y, px(13.)), "settings y");
}
#[gpui::test]
fn modal_sheets_match_reference_geometry(cx: &mut TestAppContext) {
    cx.update(|cx| {
        gpui_component::init(cx);
        bind_keys(cx);
    });
    let directory = tempfile::tempdir().unwrap();
    let storage = Storage::open(&directory.path().join("modal.sqlite3")).unwrap();
    let (_root, cx) = cx.add_window_view(|window, cx| {
        let view = cx.new(|cx| SnowFold::load(Ok(storage), window, cx));
        gpui_component::Root::new(view, window, cx)
    });
    cx.simulate_resize(gpui::size(px(1440.), px(900.)));
    let close = |a: Pixels, b: Pixels| (f32::from(a) - f32::from(b)).abs() < 1.5;
    cx.simulate_keystrokes("cmd-,");
    cx.run_until_parked();
    let sheet = cx.debug_bounds("settings-sheet").expect("settings sheet");
    assert!(close(sheet.size.width, px(472.)), "sheet width");
    assert!(close(sheet.origin.x, px(484.)), "sheet x");
    // 最小窗口高度 720：设置面板必须完整放进去，不能被裁掉。
    assert!(
        sheet.size.height < px(700.),
        "settings sheet height {}",
        f32::from(sheet.size.height)
    );
    assert!(
        close(sheet.origin.y + sheet.size.height / 2., px(450.)),
        "sheet vertically centered"
    );
    // 最小窗口 960×720：设置面板仍要完整显示标题、内容区与底部按钮。
    cx.simulate_resize(gpui::size(px(960.), px(720.)));
    cx.run_until_parked();
    let sheet = cx
        .debug_bounds("settings-sheet")
        .expect("settings sheet at minimum size");
    assert!(
        sheet.size.height < px(720.),
        "settings sheet height at 960x720: {}",
        f32::from(sheet.size.height)
    );
    cx.simulate_resize(gpui::size(px(1440.), px(900.)));
    cx.run_until_parked();
    cx.simulate_keystrokes("cmd-,");
    cx.run_until_parked();
    cx.simulate_keystrokes("cmd-3");
    cx.simulate_keystrokes("cmd-n");
    cx.run_until_parked();
    let sheet = cx.debug_bounds("subscription-sheet").expect("editor sheet");
    assert!(close(sheet.size.width, px(472.)), "editor width");
    assert!(close(sheet.origin.x, px(484.)), "editor x");
    let currency = cx.debug_bounds("¥ 人民币").expect("currency trigger");
    assert!(close(currency.size.height, px(36.)), "currency height");
    let save = cx.debug_bounds("保存").expect("save button");
    assert!(close(save.size.height, px(32.)), "save height");
}
#[gpui::test]
fn subscription_stats_card_matches_reference_grid(cx: &mut TestAppContext) {
    cx.update(|cx| {
        gpui_component::init(cx);
        bind_keys(cx);
    });
    let directory = tempfile::tempdir().unwrap();
    let storage = Storage::open(&directory.path().join("stats.sqlite3")).unwrap();
    let (_root, cx) = cx.add_window_view(|window, cx| {
        let view = cx.new(|cx| SnowFold::load(Ok(storage), window, cx));
        gpui_component::Root::new(view, window, cx)
    });
    cx.simulate_keystrokes("cmd-3");
    cx.run_until_parked();
    let close = |a: Pixels, b: Pixels| (f32::from(a) - f32::from(b)).abs() < 1.5;
    let card = cx.debug_bounds("stats-card").expect("stats card");
    let mut columns = Vec::new();
    for selector in ["stat0", "stat1", "stat2", "stat3"] {
        columns.push(cx.debug_bounds(selector).expect("stat column"));
    }
    assert!(
        close(columns[0].origin.x, card.origin.x + px(4.)),
        "first column inset by the card padding"
    );
    assert!(
        close(
            columns[3].origin.x + columns[3].size.width,
            card.origin.x + card.size.width - px(4.)
        ),
        "last column flush with the card padding"
    );
    for pair in columns.windows(2) {
        assert!(
            close(pair[0].origin.x + pair[0].size.width, pair[1].origin.x),
            "columns sit flush against one another"
        );
        // Fractional layout rounding leaves at most ~1.5px between columns.
        let even = |a: Pixels, b: Pixels| (f32::from(a) - f32::from(b)).abs() < 2.1;
        assert!(
            even(pair[0].size.width, pair[1].size.width),
            "columns share one width: {} vs {}",
            f32::from(pair[0].size.width),
            f32::from(pair[1].size.width)
        );
    }
    // 16px padding plus the card's 1px border on each side wraps the columns.
    assert!(
        close(card.size.height - columns[0].size.height, px(16. * 2. + 2.)),
        "stats card padding: card {} vs column {}",
        f32::from(card.size.height),
        f32::from(columns[0].size.height)
    );
}
#[gpui::test]
fn encrypted_backup_export_and_confirmed_restore(cx: &mut TestAppContext) {
    cx.update(|cx| {
        gpui_component::init(cx);
        bind_keys(cx);
    });
    let directory = tempfile::tempdir().unwrap();
    let mut storage = Storage::open(&directory.path().join("sealed.sqlite3")).unwrap();
    storage
        .replace_data(&Storage::example_data().unwrap())
        .unwrap();
    let mut entity = None;
    let (_root, cx) = cx.add_window_view(|window, cx| {
        let view = cx.new(|cx| SnowFold::load(Ok(storage), window, cx));
        entity = Some(view.clone());
        gpui_component::Root::new(view, window, cx)
    });
    let view = entity.unwrap();
    cx.simulate_keystrokes("cmd-,");
    cx.run_until_parked();
    view.read_with(cx, |view, _| {
        assert!(view.key_fingerprint.is_some(), "启动时应生成同步密钥");
    });

    click(cx, "导出密文");
    assert!(cx.did_prompt_for_new_path(), "加密导出应打开保存对话框");
    let sealed_path = directory.path().join("backup.sfseal");
    cx.simulate_new_path_selection(|_| Some(sealed_path.clone()));
    cx.run_until_parked();
    let sealed = std::fs::read(&sealed_path).unwrap();
    assert!(sealed.starts_with(b"SFS1"), "导出文件必须是密封包");

    let key_path = crate::crypto::key_path_for(directory.path());
    let key = crate::crypto::SyncKey::load_or_create(&key_path).unwrap();
    let info = Storage::inspect_sealed(&sealed, &key).unwrap();
    assert_eq!(info.assets, 11);
    assert_eq!(info.history, 22);
    assert_eq!(info.subscriptions, 9);
    assert_eq!(info.device_id.len(), 16);
    chrono::NaiveDateTime::parse_from_str(&info.exported_at, "%Y-%m-%d %H:%M").unwrap();

    // 大窗口下设置面板不需要滚动，恢复码按钮可见；点击前先放大窗口。
    cx.simulate_resize(gpui::size(px(1440.), px(1200.)));
    cx.run_until_parked();
    click(cx, "导出恢复码");
    assert!(cx.did_prompt_for_new_path(), "导出恢复码应打开保存对话框");
    let recovery_path = directory.path().join("recovery.txt");
    cx.simulate_new_path_selection(|_| Some(recovery_path.clone()));
    cx.run_until_parked();
    let recovery = std::fs::read_to_string(&recovery_path).unwrap();
    assert_eq!(
        crate::crypto::SyncKey::decode_file(&recovery)
            .unwrap()
            .to_hex(),
        key.to_hex(),
        "恢复码文件必须能直接恢复成同一把密钥"
    );
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let mode = std::fs::metadata(&recovery_path)
            .unwrap()
            .permissions()
            .mode()
            & 0o777;
        assert_eq!(mode, 0o600, "恢复码是主密钥明文，必须是 0600");
    }

    // 加密备份只有在用户确认之后才覆盖数据。
    view.update_in(cx, |view, _window, cx| {
        view.confirm = Some(Confirm::ImportSealed {
            bytes: sealed.clone(),
            info: info.clone(),
        });
        cx.notify();
    });
    cx.run_until_parked();
    click(cx, "确认覆盖");
    cx.run_until_parked();
    view.read_with(cx, |view, _| {
        assert_eq!(view.data.assets.len(), 11);
        assert_eq!(view.data.history.len(), 22);
        assert_eq!(view.data.subscriptions.len(), 9);
    });

    // 替换同步密钥同样必须经过确认；旧密钥留 .bak，指纹随之更新。
    let replacement = crate::crypto::SyncKey::generate().unwrap();
    let new_fingerprint = replacement.fingerprint();
    view.update_in(cx, |view, _window, cx| {
        view.confirm = Some(Confirm::ReplaceKey {
            text: zeroize::Zeroizing::new(replacement.encode_file()),
            new_fingerprint: new_fingerprint.clone(),
            old_fingerprint: Some(key.fingerprint()),
        });
        cx.notify();
    });
    cx.run_until_parked();
    click(cx, "确认替换");
    cx.run_until_parked();
    assert_eq!(
        crate::crypto::SyncKey::load(&key_path)
            .unwrap()
            .unwrap()
            .to_hex(),
        replacement.to_hex()
    );
    assert_eq!(
        crate::crypto::SyncKey::load(&key_path.with_file_name("snowfold-sync.key.bak"))
            .unwrap()
            .unwrap()
            .to_hex(),
        key.to_hex(),
        "旧密钥必须保留在 .bak 里"
    );
    view.read_with(cx, |view, _| {
        assert_eq!(
            view.key_fingerprint.as_deref(),
            Some(new_fingerprint.as_str())
        );
    });
    // 确认页打开期间密钥被别人改过：指纹对不上就必须拒绝，不能覆盖。
    let stale = crate::crypto::SyncKey::generate().unwrap();
    view.update_in(cx, |view, _window, cx| {
        view.confirm = Some(Confirm::ReplaceKey {
            text: zeroize::Zeroizing::new(stale.encode_file()),
            new_fingerprint: stale.fingerprint(),
            old_fingerprint: Some("0000-0000-0000-0000".into()),
        });
        cx.notify();
    });
    cx.run_until_parked();
    click(cx, "确认替换");
    cx.run_until_parked();
    view.read_with(cx, |view, _| {
        assert!(view.error.is_some(), "指纹不一致时必须报错");
    });
    assert_eq!(
        crate::crypto::SyncKey::load(&key_path)
            .unwrap()
            .unwrap()
            .to_hex(),
        replacement.to_hex(),
        "指纹不一致时不能改动密钥文件"
    );
}
#[gpui::test]
fn native_input_navigation_crud_and_settings_events(cx: &mut TestAppContext) {
    cx.update(|cx| {
        gpui_component::init(cx);
        bind_keys(cx);
    });
    let directory = tempfile::tempdir().unwrap();
    let storage = Storage::open(&directory.path().join("ui.sqlite3")).unwrap();
    let mut entity = None;
    let (_root, cx) = cx.add_window_view(|window, cx| {
        let view = cx.new(|cx| SnowFold::load(Ok(storage), window, cx));
        entity = Some(view.clone());
        gpui_component::Root::new(view, window, cx)
    });
    let view = entity.unwrap();
    cx.simulate_keystrokes("cmd-2 cmd-n");
    cx.simulate_input("Checking");
    cx.simulate_keystrokes("tab");
    cx.simulate_input("100.00");
    cx.simulate_keystrokes("enter");
    view.read_with(cx, |v, _| {
        assert!(v.error.is_none(), "{:?}", v.error);
        assert_eq!(v.data.assets.len(), 1);
        assert_eq!(v.data.assets[0].name, "Checking");
        assert_eq!(v.data.assets[0].amount_cents, 10000);
        assert_eq!(v.data.history.len(), 1);
    });
    click(cx, "¥100.00");
    cx.simulate_keystrokes("secondary-a");
    cx.simulate_input("200");
    cx.simulate_keystrokes("enter");
    view.read_with(cx, |v, _| {
        assert_eq!(v.data.assets[0].amount_cents, 20000);
        assert_eq!(v.data.history.len(), 2);
        assert!(v.undo.is_some());
    });
    click(cx, "撤销 · 6秒内");
    view.read_with(cx, |v, _| {
        assert_eq!(v.data.assets[0].amount_cents, 10000);
        assert_eq!(v.data.history.len(), 1);
    });
    cx.simulate_keystrokes("cmd-3 cmd-n");
    view.read_with(cx, |v, _| {
        assert_eq!(v.editing, Some(Editing::NewSubscription))
    });
    cx.simulate_input("Cloud");
    cx.simulate_keystrokes("tab");
    cx.simulate_input("6");
    view.read_with(cx, |v, cx| {
        assert_eq!(v.name.read(cx).value().as_str(), "Cloud");
        assert_eq!(v.amount.read(cx).value().as_str(), "6");
    });
    cx.simulate_keystrokes("enter");
    view.read_with(cx, |v, _| {
        assert!(v.error.is_none(), "{:?}", v.error);
        assert_eq!(v.data.subscriptions.len(), 1);
        assert_eq!(v.data.subscriptions[0].name, "Cloud");
        assert_eq!(v.data.subscriptions[0].amount_cents, 600);
    });
    click(cx, "CNY");
    click(cx, "USD");
    view.read_with(cx, |v, _| {
        assert_eq!(v.data.subscriptions[0].currency, "USD")
    });
    click(cx, "每月");
    click(cx, "每年");
    view.read_with(cx, |v, _| {
        assert_eq!(v.data.subscriptions[0].cycle, "yearly");
        assert_eq!(v.data.subscriptions[0].next_charge_on, today());
    });
    click(cx, "暂停");
    view.read_with(cx, |v, _| {
        assert_eq!(v.data.subscriptions[0].status, "paused")
    });
    click(cx, "恢复");
    view.read_with(cx, |v, _| {
        assert_eq!(v.data.subscriptions[0].status, "active")
    });
    cx.simulate_keystrokes("cmd-,");
    click(cx, "深色");
    view.read_with(cx, |v, _| {
        assert_eq!(v.storage.as_ref().unwrap().theme().unwrap(), Theme::Dark)
    });
    click(cx, "导出");
    assert!(cx.did_prompt_for_new_path());
    let export_path = directory.path().join("roundtrip.json");
    cx.simulate_new_path_selection(|_| Some(export_path.clone()));
    cx.run_until_parked();
    let exported: Backup =
        serde_json::from_str(&std::fs::read_to_string(&export_path).unwrap()).unwrap();
    exported.validate().unwrap();
    assert_eq!(
        (
            exported.assets.len(),
            exported.history.len(),
            exported.subscriptions.len()
        ),
        (1, 1, 1)
    );
    click(cx, "清空");
    click(cx, "取消");
    view.read_with(cx, |v, _| {
        assert_eq!(v.data.assets.len(), 1);
        assert_eq!(v.data.subscriptions.len(), 1);
    });
    cx.simulate_keystrokes("escape cmd-1");
    view.read_with(cx, |v, _| assert_eq!(v.page, 0));
    cx.simulate_keystrokes("cmd-,");
    click(cx, "清空");
    click(cx, "确认覆盖");
    view.read_with(cx, |v, _| {
        assert!(v.data.assets.is_empty());
        assert!(v.data.subscriptions.is_empty());
    });
    cx.simulate_keystrokes("escape cmd-3 cmd-n escape");
    view.read_with(cx, |v, _| {
        assert!(v.editing.is_none());
        assert!(v.data.subscriptions.is_empty());
    });
    // Root focus traverses the same controls as a keyboard user; Enter invokes Div::on_click.
    cx.simulate_keystrokes("tab");
    cx.simulate_keystrokes("tab");
    cx.simulate_keystrokes("enter");
    // GPUI simulate_keystrokes only sends KeyDown; Div keyboard clicks use KeyUp.
    cx.simulate_event(KeyUpEvent {
        keystroke: Keystroke::parse("enter").unwrap(),
    });
    view.read_with(cx, |v, _| assert_eq!(v.page, 1));
    cx.simulate_keystrokes("cmd-, tab enter");
    cx.simulate_event(KeyUpEvent {
        keystroke: Keystroke::parse("enter").unwrap(),
    });
    view.read_with(cx, |v, _| assert!(!v.settings));
    cx.simulate_keystrokes("cmd-,");
    view.read_with(cx, |v, _| {
        v.data.validate().unwrap();
    });
    if let Some(output) = std::env::var_os("SNOWFOLD_ACCEPTANCE_EVIDENCE_DIR") {
        let output = std::path::PathBuf::from(output);
        std::fs::create_dir_all(&output).unwrap();
        view.read_with(cx, |v, _| {
            let storage = v.storage.as_ref().unwrap();
            std::fs::copy(&storage.path, output.join("interaction.sqlite3")).unwrap();
            std::fs::write(
                output.join("interaction.json"),
                storage.export_json().unwrap(),
            )
            .unwrap();
            let reopened = Storage::open(&output.join("interaction.sqlite3")).unwrap();
            assert_eq!(
                reopened.export_json().unwrap(),
                storage.export_json().unwrap()
            );
            assert_eq!(reopened.theme().unwrap(), Theme::Dark);
        });
        eprintln!(
            "GPUI event-created persistence evidence: {}",
            output.display()
        );
    }
}

#[gpui::test]
fn invalid_amount_enter_preserves_single_line_input(cx: &mut TestAppContext) {
    cx.update(|cx| {
        gpui_component::init(cx);
        bind_keys(cx);
    });
    let directory = tempfile::tempdir().unwrap();
    let mut storage = Storage::open(&directory.path().join("single-line.sqlite3")).unwrap();
    let id = storage
        .create_asset(crate::model::CATEGORIES[0].0, "Checking", 10000)
        .unwrap();
    let mut entity = None;
    let (_root, cx) = cx.add_window_view(|window, cx| {
        let view = cx.new(|cx| SnowFold::load(Ok(storage), window, cx));
        entity = Some(view.clone());
        gpui_component::Root::new(view, window, cx)
    });
    let view = entity.unwrap();
    cx.simulate_keystrokes("cmd-2");
    click(cx, "¥100.00");
    // Windows 没有 cmd-a 全选绑定，追加 200 后会留下这个非法金额。
    cx.simulate_input("200");
    view.read_with(cx, |v, cx| {
        assert_eq!(v.amount.read(cx).value().as_str(), "100.00200");
    });
    cx.simulate_keystrokes("enter");
    view.read_with(cx, |v, cx| {
        assert!(v.error.is_some());
        assert_eq!(v.editing, Some(Editing::AssetAmount(id)));
        assert_eq!(v.data.assets[0].amount_cents, 10000);
        assert_eq!(v.amount.read(cx).value().as_str(), "100.00200");
    });
    cx.simulate_keystrokes("secondary-a");
    cx.simulate_input("200");
    cx.simulate_keystrokes("enter");
    view.read_with(cx, |v, cx| {
        assert!(v.error.is_none(), "{:?}", v.error);
        assert!(v.editing.is_none());
        assert_eq!(v.data.assets[0].amount_cents, 20000);
        assert_eq!(v.amount.read(cx).value().as_str(), "200");
    });
}
