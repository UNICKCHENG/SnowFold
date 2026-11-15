use super::settings_window::SettingsWindow;
use anyhow::{Context as _, Result, bail};
use gpui::*;
use gpui_component::{
    date_picker::DatePickerState,
    input::{InputEvent, InputState},
};
use snowfold_core::backup::Backup;
use snowfold_core::model::{
    self, Id, Subscription as SubscriptionRecord, SubscriptionInput, today,
};
use snowfold_core::storage::{SealedInfo, Storage, Theme, Undo};
use std::time::{Duration, Instant};

/// 居中弹层（§5.6 / §9.5）。RenameAsset 是设计稿未画的重命名弹窗（补稿：与
/// 添加资产同形态，只有名称一个字段）。
#[derive(Clone, Copy, PartialEq)]
pub(super) enum Dialog {
    AddAsset,
    RenameAsset(Id),
    AdjustBalance(Id),
    CorrectHistory(Id),
    NewSubscription,
    EditSubscription(Id),
}

#[derive(Clone)]
pub(super) enum Confirm {
    Clear,
    ClearSync,
    Import(Backup),
    /// 已通过认证解密的加密备份：拿到用户确认后再替换数据。
    ImportSealed {
        bytes: Vec<u8>,
        info: SealedInfo,
    },
    /// 替换本机同步密钥：必须让用户看到新旧指纹，避免被诱导导入攻击者密钥。
    ReplaceKey {
        text: zeroize::Zeroizing<String>,
        new_fingerprint: String,
        old_fingerprint: Option<String>,
    },
}

/// 运行中反馈（§5.8）：成功通知 4 秒自动消失，错误手动关闭。
#[derive(Clone, Copy, PartialEq)]
pub(super) enum BannerKind {
    Info,
    Error,
}
#[derive(Clone)]
pub(super) struct Banner {
    pub(super) kind: BannerKind,
    pub(super) text: String,
    pub(super) at: Instant,
}

pub(crate) struct SnowFold {
    pub(super) storage: Option<Storage>,
    pub(super) data: model::Ledger,
    pub(super) theme: Theme,
    pub(super) page: usize,
    /// 启动失败（库打不开/启动备份失败）：内容区换错误空状态（§9.7）。
    pub(super) startup_error: Option<String>,
    /// 密钥不可用：总览顶部 warn 卡片（§9.7）。
    pub(super) key_unavailable: bool,
    pub(super) banner: Option<Banner>,
    /// 同步密钥指纹；`None` 表示本机还没有可用的同步密钥。
    pub(super) key_fingerprint: Option<String>,
    pub(super) sync: super::sync::SyncUi,
    pub(super) focus: FocusHandle,
    _appearance: gpui::Subscription,
    /// 资产页选中条目；`None` 时右栏是总资产概览（§9.3）。
    pub(super) selected: Option<Id>,
    pub(super) dialog: Option<Dialog>,
    pub(super) name: Entity<InputState>,
    pub(super) amount: Entity<InputState>,
    pub(super) date: Entity<DatePickerState>,
    pub(super) currency: String,
    pub(super) cycle: String,
    /// 「添加资产」弹层里选中的分类。
    pub(super) new_category: usize,
    /// 分类选择器浮层是否打开。
    pub(super) category_menu_open: bool,
    /// 弹层字段级错误：名称必填、金额文案（核心校验）、日期必选。
    pub(super) name_error: bool,
    pub(super) amount_error: Option<String>,
    /// 「请选择下次扣费日」字段级错误。
    pub(super) date_error: bool,
    pub(super) confirm: Option<Confirm>,
    pub(super) undo: Option<(Undo, Instant, String)>,
    pub(super) highlight: Option<Id>,
    pub(super) scroll: ScrollHandle,
    pub(super) categories: Vec<ScrollAnchor>,
    pub(super) new_item: ScrollAnchor,
    pub(super) modal_focus: FocusHandle,
    /// 输入框事件订阅（回车保存）。必须保活，丢弃即退订。
    _input_events: Vec<gpui::Subscription>,
    pub(super) settings_window: Option<AnyWindowHandle>,
    /// 确认框是否从设置窗口发起（决定遮罩画在哪个窗口）。
    pub(super) confirm_in_settings: bool,
    /// 渲染时写入的内容区高度（viewport − 工具栏 52），供「即将扣费」条数规则用。
    pub(super) last_content_height: f32,
    /// 渲染时写入的内容区宽度，供资产页两栏断点用。
    pub(super) last_content_width: f32,
    /// 渲染时写入的分类合计（右栏概览复用）。
    pub(super) sums_cached: [i64; 5],
}

impl SnowFold {
    pub(crate) fn new(window: &mut Window, cx: &mut gpui::Context<Self>) -> Self {
        Self::load(Storage::open_default(), window, cx)
    }
    pub(super) fn load(
        storage: Result<Storage>,
        window: &mut Window,
        cx: &mut gpui::Context<Self>,
    ) -> Self {
        let focus = cx.focus_handle();
        focus.focus(window);
        // 单行 Enter 可能继续进入文本插入；在输入边界拒绝 CR/LF。
        let single_line =
            |text: &str, _: &mut gpui::Context<InputState>| !text.contains(['\r', '\n']);
        let name = cx.new(|cx| InputState::new(window, cx).validate(single_line));
        let amount = cx.new(|cx| {
            InputState::new(window, cx)
                .placeholder("0.00")
                .validate(single_line)
        });
        let date = cx.new(|cx| DatePickerState::new(window, cx));
        date.update(cx, |d, cx| d.set_date(today(), window, cx));
        let input_events: Vec<gpui::Subscription> = [name.clone(), amount.clone()]
            .into_iter()
            .map(|input| {
                cx.subscribe_in(&input, window, move |this, _, event, window, cx| {
                    if matches!(event, InputEvent::PressEnter { .. }) {
                        this.save_dialog(window, cx);
                    }
                })
            })
            .collect();
        let scroll = ScrollHandle::new();
        let mut view = Self {
            storage: None,
            data: model::Ledger::default(),
            theme: Theme::System,
            page: 0,
            startup_error: None,
            key_unavailable: false,
            banner: None,
            key_fingerprint: None,
            sync: super::sync::SyncUi::new(window, cx),
            focus,
            _appearance: cx.observe_window_appearance(window, |this, window, cx| {
                this.sync_theme(window, cx);
                cx.notify();
            }),
            selected: None,
            dialog: None,
            name,
            amount,
            date,
            currency: "CNY".into(),
            cycle: "monthly".into(),
            new_category: 0,
            category_menu_open: false,
            name_error: false,
            amount_error: None,
            date_error: false,
            confirm: None,
            undo: None,
            highlight: None,
            categories: (0..5)
                .map(|_| ScrollAnchor::for_handle(scroll.clone()))
                .collect(),
            new_item: ScrollAnchor::for_handle(scroll.clone()),
            scroll,
            modal_focus: cx.focus_handle(),
            _input_events: input_events,
            settings_window: None,
            confirm_in_settings: false,
            last_content_height: 668.,
            last_content_width: 800.,
            sums_cached: [0; 5],
        };
        match storage.and_then(|s| Ok((s.theme()?, s.data()?, s))) {
            Ok((theme, data, storage)) => {
                view.theme = theme;
                view.data = data;
                if let Err(e) = storage.startup_backup() {
                    view.startup_error = Some(format!(
                        "启动备份失败：{e:#}。原数据未改变，请检查数据目录权限。"
                    ));
                }
                view.storage = Some(storage);
                match view.load_key() {
                    Ok(key) => view.key_fingerprint = Some(key.fingerprint()),
                    Err(_) => view.key_unavailable = true,
                }
            }
            Err(e) => {
                view.startup_error = Some(format!(
                    "无法读取本机数据：{e:#}。请检查数据目录权限后重新打开。"
                ))
            }
        }
        if view.storage.is_some() {
            view.initialize_sync(window, cx);
        }
        view.sync_theme(window, cx);
        view
    }
    fn sync_theme(&self, window: &mut Window, cx: &mut App) {
        let dark = self.theme == Theme::Dark
            || (self.theme == Theme::System
                && matches!(
                    window.appearance(),
                    WindowAppearance::Dark | WindowAppearance::VibrantDark
                ));
        theme_state::apply(cx, dark);
    }
    pub(super) fn reload(&mut self) -> Result<()> {
        self.data = self.storage.as_ref().context("数据库不可用")?.data()?;
        Ok(())
    }
    pub(super) fn finish(
        &mut self,
        result: Result<()>,
        window: &mut Window,
        cx: &mut gpui::Context<Self>,
    ) {
        if result.is_ok() {
            self.sync_edited();
        }
        match result.and_then(|_| self.reload()) {
            Ok(()) => self.banner = None,
            Err(e)
                if e.downcast_ref::<snowfold_core::CoreError>()
                    == Some(&snowfold_core::CoreError::KeyUnavailable) =>
            {
                self.show_error(e.to_string(), cx);
            }
            Err(e) => self.show_error(format!("操作失败：{e:#}。请检查输入或数据目录后重试。"), cx),
        }
        if self.dialog.is_some() || self.confirm.is_some() {
            self.modal_focus.focus(window);
        } else {
            self.focus.focus(window);
        }
        cx.notify();
    }
    /// 成功通知（4 秒自动消失）。
    pub(super) fn notify_info(&mut self, text: impl Into<String>, cx: &mut gpui::Context<Self>) {
        self.banner = Some(Banner {
            kind: BannerKind::Info,
            text: text.into(),
            at: Instant::now(),
        });
        cx.spawn(async move |this, cx| {
            cx.background_executor().timer(Duration::from_secs(4)).await;
            let _ = this.update(cx, |this, cx| {
                if this
                    .banner
                    .as_ref()
                    .is_some_and(|b| b.kind == BannerKind::Info)
                {
                    this.banner = None;
                    cx.notify();
                }
            });
        })
        .detach();
    }
    /// 错误通知（手动关闭）。
    pub(super) fn show_error(&mut self, text: impl Into<String>, cx: &mut gpui::Context<Self>) {
        self.banner = Some(Banner {
            kind: BannerKind::Error,
            text: text.into(),
            at: Instant::now(),
        });
    }
    pub(super) fn select(
        &mut self,
        page: usize,
        window: &mut Window,
        cx: &mut gpui::Context<Self>,
    ) {
        if self.startup_error.is_some() || self.dialog.is_some() || self.confirm.is_some() {
            return;
        }
        if self.page == page {
            return;
        }
        self.page = page;
        self.selected = None;
        self.scroll.set_offset(point(px(0.), px(0.)));
        self.focus.focus(window);
        cx.notify();
    }
    /// 打开/聚焦设置窗口（Ctrl+,）。
    /// 设置窗口用的主界面状态快照（打开时与 notify 后构建）。
    pub(super) fn settings_snapshot(&self) -> super::settings_window::SettingsSnapshot {
        super::settings_window::SettingsSnapshot {
            confirm_open: self.confirm.is_some(),
            theme: self.theme,
            fingerprint: self.key_fingerprint.clone(),
            status: self.sync.status.clone(),
            busy: self.sync.busy,
            failure: self.sync.failure.clone(),
            sync_time: self.sync_time(),
        }
    }

    pub(super) fn toggle_settings(&mut self, window: &mut Window, cx: &mut gpui::Context<Self>) {
        if let Some(handle) = self.settings_window {
            if handle
                .update(cx, |_, window, _| window.activate_window())
                .is_ok()
            {
                return;
            }
            self.settings_window = None;
        }
        if self.startup_error.is_some() {
            return;
        }
        let app = cx.entity().downgrade();
        let bounds = Bounds::centered(None, size(px(680.), px(720.)), cx);
        let handle = cx
            .open_window(
                WindowOptions {
                    window_bounds: Some(WindowBounds::Windowed(bounds)),
                    window_min_size: Some(size(px(680.), px(560.))),
                    titlebar: Some(TitlebarOptions {
                        title: Some("SnowFold 设置".into()),
                        appears_transparent: true,
                        ..Default::default()
                    }),
                    ..Default::default()
                },
                |window, cx| {
                    #[cfg(windows)]
                    crate::platform::apply_backdrop(window, false, cx);
                    let snapshot = self.settings_snapshot();
                    let view = cx.new(|cx| SettingsWindow::new(app, snapshot, window, cx));
                    cx.new(|cx| gpui_component::Root::new(view, window, cx))
                },
            )
            .expect("打开设置窗口失败");
        handle
            .update(cx, |_, window, _| window.activate_window())
            .ok();
        self.settings_window = Some(handle.into());
        cx.notify();
    }
    pub(super) fn close_confirm(&mut self, window: &mut Window, cx: &mut gpui::Context<Self>) {
        self.confirm = None;
        self.modal_focus.focus(window);
        cx.notify();
    }
    pub(super) fn set_theme(
        &mut self,
        theme: Theme,
        window: &mut Window,
        cx: &mut gpui::Context<Self>,
    ) {
        self.set_theme_with(theme, window.appearance(), cx);
    }
    /// 无窗口上下文版本（设置窗口调用）：外观由调用方传入。
    pub(super) fn set_theme_with(
        &mut self,
        theme: Theme,
        appearance: WindowAppearance,
        cx: &mut gpui::Context<Self>,
    ) {
        let result = self
            .storage
            .as_ref()
            .context("数据库不可用")
            .and_then(|s| s.save_theme(theme));
        match result {
            Ok(()) => {
                self.theme = theme;
                let dark = theme == Theme::Dark
                    || (theme == Theme::System
                        && matches!(
                            appearance,
                            WindowAppearance::Dark | WindowAppearance::VibrantDark
                        ));
                theme_state::apply(cx, dark);
            }
            Err(e) => self.show_error(format!("主题未保存：{e:#}"), cx),
        }
        cx.notify();
    }
    pub(super) fn offer_undo(&mut self, undo: Undo, label: &str, cx: &mut gpui::Context<Self>) {
        let at = Instant::now();
        self.undo = Some((undo, at, label.into()));
        cx.spawn(async move |this, cx| {
            cx.background_executor().timer(Duration::from_secs(6)).await;
            let _ = this.update(cx, |this, cx| {
                if this.undo.as_ref().is_some_and(|(_, when, _)| *when == at) {
                    this.undo = None;
                    cx.notify();
                }
            });
        })
        .detach();
    }
    pub(super) fn undo(&mut self, window: &mut Window, cx: &mut gpui::Context<Self>) {
        let result = (|| {
            let (undo, when, _) = self.undo.as_ref().context("没有可撤销的操作")?;
            if when.elapsed() > Duration::from_secs(6) {
                bail!("撤销已过期");
            }
            self.storage.as_mut().context("数据库不可用")?.undo(undo)
        })();
        if result.is_ok() {
            self.undo = None;
        }
        self.finish(result, window, cx);
    }
    pub(super) fn delete_asset(
        &mut self,
        id: Id,
        window: &mut Window,
        cx: &mut gpui::Context<Self>,
    ) {
        match self
            .storage
            .as_mut()
            .context("数据库不可用")
            .and_then(|s| s.delete_asset(id))
        {
            Ok(undo) => {
                if self.selected == Some(id) {
                    self.selected = None;
                }
                self.offer_undo(undo, "已删除资产", cx);
                self.finish(Ok(()), window, cx);
            }
            Err(e) => self.finish(Err(e), window, cx),
        }
    }
    pub(super) fn delete_subscription(
        &mut self,
        id: Id,
        window: &mut Window,
        cx: &mut gpui::Context<Self>,
    ) {
        match self
            .storage
            .as_mut()
            .context("数据库不可用")
            .and_then(|s| s.delete_subscription(id))
        {
            Ok(undo) => {
                self.offer_undo(undo, "已删除订阅", cx);
                self.finish(Ok(()), window, cx);
            }
            Err(e) => self.finish(Err(e), window, cx),
        }
    }
    pub(super) fn update_subscription(
        &mut self,
        id: Id,
        change: impl FnOnce(&mut SubscriptionRecord) -> Result<()>,
        window: &mut Window,
        cx: &mut gpui::Context<Self>,
    ) -> Result<()> {
        let result = (|| {
            let mut s = self
                .data
                .subscriptions
                .iter()
                .find(|s| s.id == id)
                .context("订阅不存在")?
                .clone();
            change(&mut s)?;
            self.storage
                .as_mut()
                .context("数据库不可用")?
                .update_subscription(id, &SubscriptionInput::from(&s))?;
            Ok(())
        })();
        self.finish(result, window, cx);
        Ok(())
    }
    /// 到期确认续费（待确认行「已续费」）：按周期顺延并提示。
    pub(super) fn renew_subscription(
        &mut self,
        id: Id,
        window: &mut Window,
        cx: &mut gpui::Context<Self>,
    ) {
        let result = (|| {
            let mut s = self
                .data
                .subscriptions
                .iter()
                .find(|s| s.id == id)
                .context("订阅不存在")?
                .clone();
            let (date, n) = s.renew(today())?;
            if n == 0 {
                bail!("下次扣费日尚未到达，无需顺延");
            }
            s.next_charge_on = date;
            self.storage
                .as_mut()
                .context("数据库不可用")?
                .update_subscription(id, &SubscriptionInput::from(&s))?;
            Ok(n)
        })();
        match result {
            Ok(n) => {
                self.finish(Ok(()), window, cx);
                self.notify_info(format!("已顺延 {n} 期"), cx);
            }
            Err(e) => self.finish(Err(e), window, cx),
        }
    }
    /// 工具栏「＋」：总览/资产＝添加资产，订阅＝新建订阅。
    pub(super) fn new_current(&mut self, window: &mut Window, cx: &mut gpui::Context<Self>) {
        if self.startup_error.is_some() || self.confirm.is_some() {
            return;
        }
        if self.page == 2 {
            self.begin_dialog(Dialog::NewSubscription, window, cx);
        } else {
            self.page = 1;
            self.begin_dialog(Dialog::AddAsset, window, cx);
        }
    }
}

/// 主题应用：设置 gpui-component 全局主题颜色为本设计令牌，并刷新所有窗口。
pub(super) mod theme_state {
    use super::*;

    pub(super) fn apply(cx: &mut App, dark: bool) {
        gpui_component::Theme::change(
            if dark {
                gpui_component::ThemeMode::Dark
            } else {
                gpui_component::ThemeMode::Light
            },
            None,
            cx,
        );
        let mut p = crate::theme::Palette::new(dark);
        p.opaque_window = !crate::platform::backdrop_enabled(cx);
        {
            let theme = cx.global_mut::<gpui_component::Theme>();
            theme.font_family = crate::theme::FONT_LATIN.into();
            theme.font_size = gpui::px(13.);
            theme.radius = gpui::px(10.);
            theme.transparent = gpui::transparent_black();
            let colors = &mut theme.colors;
            colors.background = p.bg.into();
            colors.foreground = p.text.into();
            colors.border = p.sep.into();
            colors.input = p.bg.into();
            colors.muted = p.brand_soft.into();
            colors.muted_foreground = p.text2.into();
            colors.selection = p.brand_soft.into();
            colors.caret = p.brand.into();
            colors.secondary = p.brand_soft.into();
            colors.secondary_hover = p.brand_soft.into();
            colors.secondary_foreground = p.text.into();
            colors.secondary_active = p.brand_soft.into();
            colors.link = p.brand.into();
            colors.link_hover = p.brand.into();
            colors.link_active = p.brand.into();
        }
        // 所有窗口刷新（设置窗口跟随主题）。
        for handle in cx.windows() {
            let _ = handle.update(cx, |_, window, _| window.refresh());
        }
    }
}
