use crate::{
    model::{self, Id, Subscription as SubscriptionRecord, SubscriptionInput, today},
    storage::{SealedInfo, Storage, Theme, Undo},
};
use anyhow::{Context as _, Result, bail};
use gpui::*;
use gpui_component::{
    ThemeMode,
    date_picker::DatePickerState,
    input::{InputEvent, InputState},
};
use snowfold_core::backup::Backup;
use std::time::{Duration, Instant};

#[derive(Clone, Copy, Debug, PartialEq)]
pub(super) enum Editing {
    NewAsset(usize),
    AssetName(Id),
    AssetAmount(Id),
    History(Id),
    NewSubscription,
    SubscriptionName(Id),
    SubscriptionAmount(Id),
}
#[derive(Clone, Copy, PartialEq)]
pub(super) enum Popover {
    Currency(Id),
    Cycle(Id),
    Date(Id),
}
#[derive(Clone)]
pub(super) enum Confirm {
    Clear,
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

pub(crate) struct SnowFold {
    pub(super) storage: Option<Storage>,
    pub(super) data: model::Ledger,
    pub(super) theme: Theme,
    pub(super) page: usize,
    pub(super) settings: bool,
    pub(super) error: Option<String>,
    pub(super) notice: Option<String>,
    /// 同步密钥指纹；`None` 表示本机还没有可用的同步密钥。
    pub(super) key_fingerprint: Option<String>,
    pub(super) focus: FocusHandle,
    _appearance: gpui::Subscription,
    pub(super) editing: Option<Editing>,
    pub(super) name: Entity<InputState>,
    pub(super) amount: Entity<InputState>,
    pub(super) date: Entity<DatePickerState>,
    pub(super) currency: String,
    pub(super) cycle: String,
    _inputs: Vec<gpui::Subscription>,
    pub(super) history: Option<Id>,
    pub(super) popover: Option<Popover>,
    pub(super) confirm: Option<Confirm>,
    pub(super) undo: Option<(Undo, Instant, String)>,
    pub(super) highlight: Option<Id>,
    pub(super) scroll: ScrollHandle,
    pub(super) categories: Vec<ScrollAnchor>,
    pub(super) new_item: ScrollAnchor,
    pub(super) modal_focus: FocusHandle,
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
        let name = cx.new(|cx| InputState::new(window, cx).placeholder("名称"));
        let amount = cx.new(|cx| InputState::new(window, cx).placeholder("0.00"));
        let date = cx.new(|cx| DatePickerState::new(window, cx));
        date.update(cx, |d, cx| d.set_date(today(), window, cx));
        let input_events = [name.clone(), amount.clone()]
            .into_iter()
            .enumerate()
            .map(|(index, input)| {
                cx.subscribe_in(&input, window, move |this, _, event, window, cx| {
                    if matches!(event, InputEvent::PressEnter { .. }) {
                        this.save_edit(window, cx);
                    }
                    if matches!(event, InputEvent::Blur)
                        && matches!(
                            (this.editing, index),
                            (
                                Some(Editing::AssetName(_) | Editing::SubscriptionName(_)),
                                0
                            ) | (
                                Some(
                                    Editing::AssetAmount(_)
                                        | Editing::SubscriptionAmount(_)
                                        | Editing::History(_)
                                ),
                                1
                            )
                        )
                    {
                        this.save_edit(window, cx);
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
            settings: false,
            error: None,
            notice: None,
            key_fingerprint: None,
            focus,
            _appearance: cx.observe_window_appearance(window, |this, window, cx| {
                this.sync_theme(window, cx);
                cx.notify();
            }),
            editing: None,
            name,
            amount,
            date,
            currency: "CNY".into(),
            cycle: "monthly".into(),
            _inputs: input_events,
            history: None,
            popover: None,
            confirm: None,
            undo: None,
            highlight: None,
            categories: (0..5)
                .map(|_| ScrollAnchor::for_handle(scroll.clone()))
                .collect(),
            new_item: ScrollAnchor::for_handle(scroll.clone()),
            scroll,
            modal_focus: cx.focus_handle(),
        };
        match storage.and_then(|s| Ok((s.theme()?, s.data()?, s))) {
            Ok((theme, data, storage)) => {
                view.theme = theme;
                view.data = data;
                if let Err(e) = storage.startup_backup() {
                    view.error = Some(format!(
                        "启动备份失败：{e:#}。原数据未改变，请检查数据目录权限。"
                    ));
                }
                view.storage = Some(storage);
                match view.load_key() {
                    Ok(key) => view.key_fingerprint = Some(key.fingerprint()),
                    Err(error) if view.error.is_none() => {
                        view.error = Some(format!(
                            "同步密钥不可用：{error:#}。加密备份暂时无法导出或导入；可以在设置里导入恢复码，或处理密钥文件后重启。"
                        ));
                    }
                    Err(_) => {}
                }
            }
            Err(e) => {
                view.error = Some(format!(
                    "无法读取本机数据：{e:#}。请检查数据目录权限后重新打开。"
                ))
            }
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
        gpui_component::Theme::change(
            if dark {
                ThemeMode::Dark
            } else {
                ThemeMode::Light
            },
            Some(window),
            cx,
        );
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
        if self.settings {
            self.modal_focus.focus(window);
        } else {
            self.focus.focus(window);
        }
        match result.and_then(|_| self.reload()) {
            Ok(()) => self.error = None,
            Err(e) => self.error = Some(format!("操作失败：{e:#}。请检查输入或数据目录后重试。")),
        }
        cx.notify();
    }
    pub(super) fn select(
        &mut self,
        page: usize,
        window: &mut Window,
        cx: &mut gpui::Context<Self>,
    ) {
        if self.settings || self.confirm.is_some() || self.editing == Some(Editing::NewSubscription)
        {
            return;
        }
        self.save_edit(window, cx);
        if self.editing.is_some() {
            return;
        }
        self.page = page;
        self.settings = false;
        self.popover = None;
        self.scroll.set_offset(point(px(0.), px(0.)));
        self.focus.focus(window);
        cx.notify();
    }
    pub(super) fn close(&mut self, window: &mut Window, cx: &mut gpui::Context<Self>) {
        self.editing = None;
        self.popover = None;
        self.confirm = None;
        self.settings = false;
        if self.storage.is_some() {
            self.error = None;
        }
        self.focus.focus(window);
        cx.notify();
    }
    pub(super) fn toggle_settings(&mut self, window: &mut Window, cx: &mut gpui::Context<Self>) {
        if self.confirm.is_some() || self.editing == Some(Editing::NewSubscription) {
            return;
        }
        self.save_edit(window, cx);
        if self.editing.is_none() {
            self.settings = !self.settings;
            if self.settings {
                self.modal_focus.focus(window);
            } else {
                self.focus.focus(window);
            }
            cx.notify();
        }
    }
    pub(super) fn set_theme(
        &mut self,
        theme: Theme,
        window: &mut Window,
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
                self.error = None;
                self.sync_theme(window, cx);
            }
            Err(e) => self.error = Some(format!("主题未保存：{e:#}")),
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
    ) {
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
        if result.is_ok() {
            self.popover = None;
        }
        self.finish(result, window, cx);
    }
    pub(super) fn new_current(&mut self, window: &mut Window, cx: &mut gpui::Context<Self>) {
        if self.settings || self.confirm.is_some() {
            return;
        }
        if self.page == 2 {
            self.begin(Editing::NewSubscription, window, cx);
        } else {
            self.page = 1;
            self.begin(Editing::NewAsset(0), window, cx);
        }
    }
}
