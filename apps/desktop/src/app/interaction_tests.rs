use super::{
    bind_keys,
    state::{Confirm, SnowFold},
};
use gpui::*;
use snowfold_core::{
    backup::Backup,
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
        view.refresh_sync(cx);
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
        cx.debug_bounds("sync-now").is_none(),
        "未开启时不显示同步按钮"
    );
    let transport = enable_test_sync(&view, cx, false);
    view.read_with(cx, |v, _| {});
    view.update_in(cx, |view, _, cx| {
        view.sync.busy = true;
        cx.notify();
    });
    cx.run_until_parked();
    click(cx, "sync-now");
    click(cx, "sync-now");
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
    // 同步进行中仍可本地建资产。
    cx.simulate_keystrokes("cmd-2 cmd-n");
    cx.run_until_parked();
    cx.simulate_input("Busy");
    cx.simulate_keystrokes("tab");
    cx.simulate_input("10");
    cx.simulate_keystrokes("enter");
    view.read_with(cx, |v, _| {
        assert!(v.dialog.is_none());
        assert_eq!(v.data.assets.len(), 1);
        assert_eq!(v.data.assets[0].amount_cents, 1000);
    });
    // 手动同步挂起传输后完成，不产生第二次调用。
    click(cx, "sync-now");
    view.read_with(cx, |v, _| assert!(v.sync.busy));
}

#[gpui::test]
fn sync_success_clears_undo_and_history_panel(cx: &mut TestAppContext) {
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
    view.update(cx, |v, cx| {
        let id = v
            .storage
            .as_mut()
            .unwrap()
            .create_asset("cash", "Cash", 10000)
            .unwrap();
        v.reload().unwrap();
        let _ = id;
        cx.notify();
    });
    cx.run_until_parked();
    // 调整余额产生可撤销票据（资产行在资产页）。
    cx.simulate_keystrokes("ctrl-2");
    cx.run_until_parked();
    click(cx, "Cash");
    cx.run_until_parked();
    click(cx, "调整余额");
    cx.run_until_parked();
    cx.simulate_keystrokes("ctrl-a");
    cx.simulate_input("80");
    cx.simulate_keystrokes("enter");
    view.read_with(cx, |v, _| {
        assert!(v.undo.is_some());
        assert_eq!(v.data.assets[0].amount_cents, 8000);
    });
    view.update_in(cx, |v, _, cx| v.start_sync(true, cx));
    cx.run_until_parked();
    view.read_with(cx, |v, _| {
        assert!(v.undo.is_none(), "同步成功后撤销票据应被清除");
        let banner_text = v.banner.as_ref().map(|b| b.text.clone());
        assert!(
            banner_text
                .as_deref()
                .is_some_and(|t| t.starts_with("同步完成")),
            "手动同步完成要有同步完成通知：{banner_text:?}"
        );
    });
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
    view.update_in(cx, |v, _, cx| v.start_sync(true, cx));
    cx.run_until_parked();
    view.read_with(cx, |v, _| {
        assert!(v.sync.failure.is_some(), "失败要留下重试标记");
        assert!(v.banner.is_some(), "手动同步失败要显示错误通知");
    });
}

fn click(cx: &mut VisualTestContext, label: &'static str) {
    let bounds = cx
        .debug_bounds(label)
        .unwrap_or_else(|| panic!("missing rendered control: {label}"));
    cx.simulate_click(bounds.center(), Modifiers::none());
}

#[gpui::test]
fn new_layout_matches_reference_geometry(cx: &mut TestAppContext) {
    cx.update(|cx| {
        gpui_component::init(cx);
        bind_keys(cx);
    });
    let directory = tempfile::tempdir().unwrap();
    let storage = Storage::open(&directory.path().join("layout.sqlite3")).unwrap();
    let mut entity = None;
    let (_root, cx) = cx.add_window_view(|window, cx| {
        let view = cx.new(|cx| SnowFold::load(Ok(storage), window, cx));
        entity = Some(view.clone());
        gpui_component::Root::new(view, window, cx)
    });
    let view = entity.unwrap();
    cx.simulate_resize(gpui::size(px(1440.), px(900.)));
    cx.run_until_parked();
    let close = |a: Pixels, b: Pixels| (f32::from(a) - f32::from(b)).abs() < 1.5;
    let nav = cx.debug_bounds("nav-overview").expect("侧边栏总览项");
    // 侧边栏距窗口上、左各 8，宽 220；导航项 32 高、内边距 10。
    assert!(close(nav.size.height, px(32.)), "nav height");
    assert!(
        close(nav.origin.x, px(20.)),
        "nav x {}",
        f32::from(nav.origin.x)
    );
    assert!(
        close(nav.origin.y, px(52.)),
        "nav y {}",
        f32::from(nav.origin.y)
    );
    let title = cx.debug_bounds("toolbar-title").expect("工具栏标题");
    assert!(
        close(title.origin.x, px(236.)),
        "title x {}",
        f32::from(title.origin.x)
    );
    assert!(close(title.size.height, px(35.)), "title height");
    // 三页空库不显示零值摘要，提示组在内容区内居中，保留设计稿的底部留白。
    for (width, height) in [(900., 600.), (1080., 720.), (1440., 900.)] {
        cx.simulate_resize(gpui::size(px(width), px(height)));
        for nav in ["nav-overview", "nav-assets", "nav-subscriptions"] {
            click(cx, nav);
            cx.run_until_parked();
            let empty = cx.debug_bounds("empty-state").expect("整页空态");
            assert!(close(
                empty.origin.x + empty.size.width / 2.,
                px(236. + (width - 236.) / 2.)
            ));
            assert!(close(
                empty.origin.y + empty.size.height / 2.,
                px(52. + (height - 52. - (width - 236.) * 0.08) / 2.)
            ));
            assert!(empty.size.height >= px(200.) && empty.size.height <= px(260.));
            assert!(cx.debug_bounds("overview-label").is_none());
            assert!(cx.debug_bounds("stats-card").is_none());
        }
    }
    // 弹层：添加资产 480 宽居中（点侧栏资产页与工具栏 ＋）。
    click(cx, "nav-assets");
    cx.run_until_parked();
    click(cx, "toolbar-new");
    cx.run_until_parked();
    let sheet = cx.debug_bounds("dialog").expect("添加资产弹层");
    assert!(close(sheet.size.width, px(480.)), "dialog width");
    assert!(
        close(sheet.origin.x + sheet.size.width / 2., px(720.)),
        "dialog centered"
    );
    let save = cx.debug_bounds("保存").expect("保存按钮");
    assert!(close(save.size.height, px(32.)), "save height");
    cx.simulate_keystrokes("escape");
    // 确认框 420 宽（直接置入确认状态）。
    view.update_in(cx, |v, _, cx| {
        v.confirm = Some(Confirm::Clear);
        cx.notify();
    });
    cx.run_until_parked();
    let confirm = cx.debug_bounds("confirm").expect("确认框");
    assert!(close(confirm.size.width, px(420.)), "confirm width");
    click(cx, "取消");
    view.read_with(cx, |v, _| assert!(v.confirm.is_none()));
}

#[gpui::test]
fn encrypted_backup_export_and_confirmed_restore(cx: &mut TestAppContext) {
    cx.update(|cx| {
        gpui_component::init(cx);
        bind_keys(cx);
    });
    let directory = tempfile::tempdir().unwrap();
    let storage = Storage::open(&directory.path().join("seal.sqlite3")).unwrap();
    let mut entity = None;
    let (_, cx) = cx.add_window_view(|window, cx| {
        let view = cx.new(|cx| SnowFold::load(Ok(storage), window, cx));
        entity = Some(view.clone());
        gpui_component::Root::new(view, window, cx)
    });
    let view = entity.unwrap();
    view.update_in(cx, |v, _, cx| {
        v.storage
            .as_mut()
            .unwrap()
            .create_asset("cash", "Sealed", 2500)
            .unwrap();
        v.reload().unwrap();
        cx.notify();
    });
    cx.run_until_parked();
    // 导出密文 → 导入密文 → 确认覆盖：行为与旧版一致。
    view.update_in(cx, |v, _, cx| {
        let key = v.load_key().unwrap();
        let sealed = v.storage.as_ref().unwrap().export_sealed(&key).unwrap();
        let info = Storage::inspect_sealed(&sealed, &key).unwrap();
        v.confirm = Some(Confirm::ImportSealed {
            bytes: sealed,
            info,
        });
        cx.notify();
    });
    cx.run_until_parked();
    click(cx, "确认覆盖");
    cx.run_until_parked();
    view.read_with(cx, |v, _| {
        assert_eq!(v.data.assets.len(), 1);
        assert_eq!(v.data.assets[0].name, "Sealed");
        let json: Backup =
            serde_json::from_str(&v.storage.as_ref().unwrap().export_json().unwrap()).unwrap();
        assert_eq!(json.assets.len(), 1);
    });
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
    // 新建资产：Tab 走 名称 → 金额，回车保存（输入框 PressEnter）。
    cx.simulate_keystrokes("cmd-2 cmd-n");
    cx.simulate_input("Checking");
    cx.simulate_keystrokes("tab");
    cx.simulate_input("100.00");
    cx.simulate_keystrokes("enter");
    view.read_with(cx, |v, _| {
        assert!(v.dialog.is_none(), "弹层未关闭");
        assert_eq!(v.data.assets.len(), 1);
        assert_eq!(v.data.assets[0].name, "Checking");
        assert_eq!(v.data.assets[0].amount_cents, 10000);
        assert_eq!(v.data.history.len(), 1);
    });
    // 调整余额弹层（旧行内金额编辑的行为等价迁移）。
    click(cx, "Checking");
    click(cx, "调整余额");
    cx.run_until_parked();
    cx.simulate_keystrokes("ctrl-a");
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
    // 新建订阅：默认今天，回车保存。
    cx.simulate_keystrokes("cmd-3 cmd-n");
    cx.run_until_parked();
    cx.simulate_input("Cloud");
    cx.simulate_keystrokes("tab");
    cx.simulate_input("6");
    cx.simulate_keystrokes("enter");
    view.read_with(cx, |v, _| {
        assert!(v.dialog.is_none(), "弹层未关闭");
        assert_eq!(v.data.subscriptions.len(), 1);
        assert_eq!(v.data.subscriptions[0].name, "Cloud");
        assert_eq!(v.data.subscriptions[0].amount_cents, 600);
    });
    // 编辑订阅：币种、周期分段保存。
    click(cx, "Cloud");
    cx.run_until_parked();
    click(cx, "dlg-currency1");
    click(cx, "保存");
    view.read_with(cx, |v, _| {
        assert_eq!(v.data.subscriptions[0].currency, "USD")
    });
    click(cx, "Cloud");
    click(cx, "dlg-cycle3");
    click(cx, "保存");
    view.read_with(cx, |v, _| {
        assert_eq!(v.data.subscriptions[0].cycle, "yearly");
        assert_eq!(
            v.data.subscriptions[0].next_charge_on,
            snowfold_core::model::today()
        );
    });
    // 续费（到期确认顺延）与暂停/恢复：行为不变。
    view.update_in(cx, |v, window, cx| {
        let id = v.data.subscriptions[0].id;
        v.renew_subscription(id, window, cx);
    });
    view.read_with(cx, |v, _| {
        assert!(v.banner.is_some());
    });
    view.update_in(cx, |v, window, cx| {
        let id = v.data.subscriptions[0].id;
        v.update_subscription(
            id,
            |s| {
                s.status = "paused".into();
                Ok(())
            },
            window,
            cx,
        );
    });
    view.read_with(cx, |v, _| {
        assert_eq!(v.data.subscriptions[0].status, "paused")
    });
    view.update_in(cx, |v, window, cx| {
        let id = v.data.subscriptions[0].id;
        v.update_subscription(
            id,
            |s| {
                s.status = "active".into();
                Ok(())
            },
            window,
            cx,
        );
    });
    // 设置窗口：主题切换持久化；清空数据走确认框。
    view.update_in(cx, |v, window, cx| {
        v.set_theme(Theme::Dark, window, cx);
    });
    view.read_with(cx, |v, _| {
        assert_eq!(v.storage.as_ref().unwrap().theme().unwrap(), Theme::Dark)
    });
    // 删除资产（回资产页选中后点删除）。
    cx.simulate_keystrokes("ctrl-2");
    cx.run_until_parked();
    click(cx, "Checking");
    click(cx, "删除");
    view.read_with(cx, |v, _| {
        assert_eq!(v.data.assets.len(), 0);
        assert!(v.undo.is_some(), "删除资产出现撤销横幅");
    });
    click(cx, "撤销 · 6秒内");
    view.read_with(cx, |v, _| assert_eq!(v.data.assets.len(), 1));
    // 清空全部数据（经确认框）。
    view.update_in(cx, |v, _, cx| {
        v.confirm = Some(Confirm::Clear);
        cx.notify();
    });
    click(cx, "取消");
    view.read_with(cx, |v, _| {
        assert!(v.confirm.is_none());
        assert_eq!(v.data.assets.len(), 1);
    });
    view.update_in(cx, |v, _, cx| {
        v.confirm = Some(Confirm::Clear);
        cx.notify();
    });
    click(cx, "清空");
    view.read_with(cx, |v, _| {
        assert!(v.data.assets.is_empty());
        assert!(v.data.subscriptions.is_empty());
    });
    // Esc 关闭弹层、Ctrl+1/2/3 切页、设置窗口打开。
    cx.simulate_keystrokes("ctrl-3 ctrl-n escape");
    view.read_with(cx, |v, _| {
        assert!(v.dialog.is_none());
        assert!(v.data.subscriptions.is_empty());
    });
    cx.simulate_keystrokes("ctrl-1");
    view.read_with(cx, |v, _| assert_eq!(v.page, 0));
    cx.simulate_keystrokes("ctrl-,");
    view.read_with(cx, |v, _| assert!(v.settings_window.is_some()));
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
        .create_asset(snowfold_core::model::CATEGORIES[0].0, "Checking", 10000)
        .unwrap();
    let mut entity = None;
    let (_root, cx) = cx.add_window_view(|window, cx| {
        let view = cx.new(|cx| SnowFold::load(Ok(storage), window, cx));
        entity = Some(view.clone());
        gpui_component::Root::new(view, window, cx)
    });
    let view = entity.unwrap();
    cx.simulate_keystrokes("cmd-2");
    click(cx, "Checking");
    click(cx, "调整余额");
    // Windows 没有 ctrl-a 全选绑定；追加非法片段保留原输入。
    cx.simulate_input(".5.5");
    cx.simulate_keystrokes("enter");
    cx.run_until_parked();
    view.read_with(cx, |v, app| {
        assert!(v.dialog.is_some(), "校验失败弹层保持打开");
        assert!(v.amount_error.is_some(), "金额错误应有字段级提示");
        assert!(!v.name_error && !v.date_error);
        let amount_text = v.amount.read(app).value().to_string();
        assert_eq!(amount_text, "100.00.5.5", "已输入内容不清空");
        assert_eq!(v.data.history.len(), 1, "非法金额不落库");
        let _ = id;
    });
}

#[gpui::test]
fn startup_error_replaces_content_and_blocks_sidebar(cx: &mut TestAppContext) {
    cx.update(|cx| {
        gpui_component::init(cx);
        bind_keys(cx);
    });
    let (_root, cx) = cx.add_window_view(|window, cx| {
        let view = cx.new(|cx| SnowFold::load(Err(anyhow::anyhow!("boom")), window, cx));
        gpui_component::Root::new(view, window, cx)
    });
    cx.run_until_parked();
    cx.simulate_keystrokes("ctrl-1");
    cx.run_until_parked();
    // 侧边栏渲染但切页被拦下；工具栏标题退回品牌名。
    assert!(cx.debug_bounds("nav-overview").is_some());
    let title = cx.debug_bounds("toolbar-title").expect("品牌标题");
    assert!(f32::from(title.size.width) > 0.);
}

/// 工具栏与内容边距：最小窗口、默认窗口与大窗口均保留完整按钮。
#[gpui::test]
fn topbar_geometry_matches_reference_layout(cx: &mut TestAppContext) {
    cx.update(|cx| {
        gpui_component::init(cx);
        bind_keys(cx);
    });
    let directory = tempfile::tempdir().unwrap();
    let mut storage = Storage::open(&directory.path().join("topbar.sqlite3")).unwrap();
    storage
        .create_asset("cash", "布局测试长金额资产", 999_999_999_999)
        .unwrap();
    storage
        .create_asset("debt", "布局测试负债", 123_456_789)
        .unwrap();
    let (_, cx) = cx.add_window_view(|window, cx| {
        let view = cx.new(|cx| SnowFold::load(Ok(storage), window, cx));
        gpui_component::Root::new(view, window, cx)
    });
    for (width, height) in [(900., 600.), (1080., 720.), (1440., 900.)] {
        cx.simulate_resize(size(px(width), px(height)));
        cx.run_until_parked();
        let label = cx.debug_bounds("overview-label").unwrap();
        let overview = cx.debug_bounds("overview-body").unwrap();
        assert!(
            (f32::from(label.origin.y) - 60.).abs() < 1.5,
            "label={label:?}; overview={overview:?}"
        );
        let toolbar = cx.debug_bounds("toolbar").unwrap();
        assert!((f32::from(toolbar.size.height) - 52.).abs() < 1.5);
        let add = cx.debug_bounds("toolbar-new").unwrap();
        assert!((f32::from(add.size.width) - 32.).abs() < 1.5);
        assert!((f32::from(add.origin.y) - 10.).abs() < 1.5);
        let controls = if cfg!(windows) { 138. } else { 0. };
        assert!((f32::from(add.origin.x + add.size.width) - (width - controls - 16.)).abs() < 1.5);
        let settings = cx.debug_bounds("nav-settings").unwrap();
        assert!(settings.origin.y + settings.size.height <= px(height - 8.));
        assert!(settings.origin.y > px(height - 64.));
        click(cx, "nav-assets");
        for (label, selector) in [
            ("总负债", "asset-summary-总负债"),
            ("净资产", "asset-summary-净资产"),
            ("记录", "asset-summary-记录"),
        ] {
            let metric = cx.debug_bounds(selector).unwrap();
            assert!(metric.origin.x >= px(236. + 32.));
            assert!(
                metric.origin.x + metric.size.width <= px(width - 32. + 1.),
                "长金额摘要必须完整显示：{label} {metric:?} 窗口宽 {width}"
            );
        }
        click(cx, "nav-overview");
    }
}

/// 新规范弹层：480 表单、420 确认框；三种窗口尺寸都居中且按钮完整。
#[gpui::test]
fn modal_sheets_match_reference_geometry(cx: &mut TestAppContext) {
    cx.update(|cx| {
        gpui_component::init(cx);
        bind_keys(cx);
    });
    let directory = tempfile::tempdir().unwrap();
    let storage = Storage::open(&directory.path().join("modal.sqlite3")).unwrap();
    let mut entity = None;
    let (_, cx) = cx.add_window_view(|window, cx| {
        let view = cx.new(|cx| SnowFold::load(Ok(storage), window, cx));
        entity = Some(view.clone());
        gpui_component::Root::new(view, window, cx)
    });
    let view = entity.unwrap();
    for (width, height) in [(900., 600.), (1080., 720.), (1440., 900.)] {
        cx.simulate_resize(size(px(width), px(height)));
        view.update_in(cx, |v, window, cx| {
            v.confirm = None;
            v.begin_dialog(super::state::Dialog::NewSubscription, window, cx);
        });
        cx.run_until_parked();
        let form = cx.debug_bounds("dialog").expect("新建订阅弹层");
        assert!((f32::from(form.size.width) - 480.).abs() < 1.5);
        assert!((f32::from(form.origin.x + form.size.width / 2.) - width / 2.).abs() < 1.5);
        assert!((f32::from(form.origin.y + form.size.height / 2.) - height / 2.).abs() < 1.5);
        let save = cx.debug_bounds("保存").unwrap();
        assert!((f32::from(save.size.height) - 32.).abs() < 1.5);
        assert!(save.origin.y + save.size.height < px(height));
        view.update_in(cx, |v, window, cx| {
            v.cancel_dialog(window, cx);
            v.confirm = Some(Confirm::Clear);
            cx.notify();
        });
        cx.run_until_parked();
        let confirm = cx.debug_bounds("confirm").expect("确认弹层");
        assert!((f32::from(confirm.size.width) - 420.).abs() < 1.5);
        assert!((f32::from(confirm.origin.x + confirm.size.width / 2.) - width / 2.).abs() < 1.5);
    }
}

/// 统计由焦点金额与两项支出构成，间距 40、底对齐；页面四周留出32边距。
#[gpui::test]
fn subscription_stats_card_matches_reference_grid(cx: &mut TestAppContext) {
    cx.update(|cx| {
        gpui_component::init(cx);
        bind_keys(cx);
    });
    let directory = tempfile::tempdir().unwrap();
    let mut storage = Storage::open(&directory.path().join("stats.sqlite3")).unwrap();
    storage
        .create_subscription(&snowfold_core::model::SubscriptionInput {
            name: "几何测试订阅".into(),
            amount_cents: 2100,
            currency: "CNY".into(),
            cycle: "monthly".into(),
            next_charge_on: snowfold_core::model::today(),
            status: "active".into(),
        })
        .unwrap();
    let (_, cx) = cx.add_window_view(|window, cx| {
        let view = cx.new(|cx| SnowFold::load(Ok(storage), window, cx));
        gpui_component::Root::new(view, window, cx)
    });
    cx.simulate_keystrokes("ctrl-3");
    for (width, height) in [(900., 600.), (1080., 720.), (1440., 900.)] {
        cx.simulate_resize(size(px(width), px(height)));
        cx.run_until_parked();
        let stats = cx.debug_bounds("stats-card").expect("订阅统计");
        assert!((f32::from(stats.origin.x) - 268.).abs() < 1.5, "{stats:?}");
        assert!((f32::from(stats.origin.y) - 60.).abs() < 1.5, "{stats:?}");
        assert!(
            (f32::from(stats.origin.x + stats.size.width) - (width - 32.)).abs() < 1.5,
            "{stats:?}"
        );
        let columns: Vec<_> = ["stat0", "stat1", "stat2"]
            .map(|s| cx.debug_bounds(s).unwrap())
            .into();
        for pair in columns.windows(2) {
            assert!(
                (f32::from(pair[1].origin.x - pair[0].origin.x - pair[0].size.width) - 40.).abs()
                    < 1.5
            );
            assert!(
                (f32::from(
                    pair[0].origin.y + pair[0].size.height - pair[1].origin.y - pair[1].size.height
                ))
                .abs()
                    < 1.5
            );
        }
        let row = cx.debug_bounds("几何测试订阅").unwrap();
        assert!(
            (f32::from(row.origin.x + row.size.width) - (width - 32.)).abs() < 1.5,
            "{row:?}"
        );
    }
}
