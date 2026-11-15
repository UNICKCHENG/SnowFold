use super::{state::SnowFold, widgets::Hint};
use crate::theme::{Palette, fade};
use gpui::{prelude::*, *};
use gpui_component::input::InputState;
use snowfold_core::{
    CoreError, SnowFoldCore, SyncBackend, SyncConfig, SyncPolicy, SyncStatus, SyncTransport,
};
use std::{
    sync::{Arc, atomic::AtomicBool},
    time::{Duration, Instant},
};

/// 设置窗口里的同步表单（输入实体属于设置窗口）。
pub(super) struct SyncForm {
    pub backend: SyncBackend,
    pub path_style: bool,
    pub endpoint: Entity<InputState>,
    pub bucket: Entity<InputState>,
    pub region: Entity<InputState>,
    pub prefix: Entity<InputState>,
    pub username: Entity<InputState>,
    pub secret: Entity<InputState>,
}
impl SyncForm {
    fn input(masked: bool, window: &mut Window, cx: &mut App) -> Entity<InputState> {
        cx.new(|cx| {
            InputState::new(window, cx)
                .masked(masked)
                .validate(|value, _| !value.contains(['\r', '\n']))
        })
    }
    pub fn new(window: &mut Window, cx: &mut App) -> Self {
        Self {
            backend: SyncBackend::S3,
            path_style: true,
            endpoint: Self::input(false, window, cx),
            bucket: Self::input(false, window, cx),
            region: Self::input(false, window, cx),
            prefix: Self::input(false, window, cx),
            username: Self::input(false, window, cx),
            secret: Self::input(true, window, cx),
        }
    }
    pub fn clear_secret(&mut self, window: &mut Window, cx: &mut App) {
        // 整个输入实体替换，连同编辑撤销历史一起释放，凭证不进入宿主状态副本。
        self.secret = Self::input(true, window, cx);
    }
    pub fn load_config(&mut self, config: &SyncConfig, window: &mut Window, cx: &mut App) {
        self.backend = config.backend;
        self.path_style = config.path_style;
        for (input, value) in [
            (&self.endpoint, &config.endpoint),
            (&self.bucket, &config.bucket),
            (&self.region, &config.region),
            (&self.prefix, &config.prefix),
            (&self.username, &config.username),
        ] {
            input.update(cx, |input, cx| input.set_value(value.clone(), window, cx));
        }
        self.clear_secret(window, cx);
    }
}

pub(super) struct SyncUi {
    pub core: Option<Arc<SnowFoldCore>>,
    pub status: Option<SyncStatus>,
    pub busy: bool,
    pub failure: Option<String>,
    foreground: bool,
    edited_at: Option<Instant>,
    pending_at: Option<Instant>,
    last_attempt: Instant,
    _activation: Option<gpui::Subscription>,
    pub transport: Arc<dyn SyncTransport>,
    /// 测试注入用：置 true 后 sync_tick 不再自动同步。
    pub suspend_policy: Arc<AtomicBool>,
}
impl SyncUi {
    pub fn new(window: &mut Window, cx: &mut App) -> Self {
        Self {
            core: None,
            status: None,
            busy: false,
            failure: None,
            foreground: window.is_window_active(),
            edited_at: None,
            pending_at: None,
            last_attempt: Instant::now(),
            _activation: None,
            transport: Arc::new(snowfold_core::sync::RustSyncTransport::default()),
            suspend_policy: Arc::new(AtomicBool::new(false)),
        }
    }
}

pub(super) fn sync_error(error: CoreError) -> &'static str {
    match error {
        CoreError::RecoveryCodeRequired => "请先导出恢复码再开启同步",
        CoreError::SyncNotConfigured => "尚未配置同步存储",
        CoreError::SyncDisabled => "同步未开启",
        CoreError::SyncNetwork => "无法连接同步服务，请检查网络与服务地址后重试",
        CoreError::SyncAuthentication => "同步服务拒绝访问，请检查账号、密钥与权限",
        CoreError::SyncConflict => "其他设备正在同步，请稍后重试",
        CoreError::SyncRemoteKeyMismatch => {
            "远端数据无法用本机同步密钥解密，本机与远端都未改动。请确认两台设备导入的是同一个恢复码"
        }
        CoreError::SyncInvalidRemote => "远端同步数据不完整或服务不支持条件写入，本机数据未改变",
        CoreError::SyncIncompatible => "远端数据来自更新版本的 SnowFold，请先升级",
        CoreError::Conflict => "正在同步或修改配置，请稍后再试",
        CoreError::KeyUnavailable => "同步密钥不可用，请检查密钥文件或导入恢复码",
        _ => "请检查服务地址（须为 https://）、存储桶、区域与凭证",
    }
}

impl SnowFold {
    pub(super) fn initialize_sync(&mut self, window: &mut Window, cx: &mut gpui::Context<Self>) {
        let result = (|| -> anyhow::Result<Arc<SnowFoldCore>> {
            let directory = self.data_directory()?;
            Ok(SnowFoldCore::open(
                directory.to_string_lossy().into_owned(),
                self.key_store()?,
            )?)
        })();
        match result {
            Ok(core) => {
                self.sync.core = Some(core);
                self.refresh_sync(cx);
                self.reset_sync_interval();
            }
            Err(error) => {
                self.show_error(format!("操作失败：{error}"), cx);
            }
        }
        self.sync._activation = Some(cx.observe_window_activation(window, |this, window, cx| {
            this.sync.foreground = window.is_window_active();
            if this.sync.foreground {
                this.maybe_sync_on_launch(cx);
            }
        }));
        cx.defer_in(window, |this, _, cx| this.maybe_sync_on_launch(cx));
        cx.spawn(async move |this, cx| {
            loop {
                cx.background_executor().timer(Duration::from_secs(1)).await;
                if this.update(cx, |this, cx| this.sync_tick(cx)).is_err() {
                    break;
                }
            }
        })
        .detach();
    }
    fn maybe_sync_on_launch(&mut self, cx: &mut gpui::Context<Self>) {
        if self
            .sync
            .status
            .as_ref()
            .is_some_and(|s| s.policy.on_launch)
            && !self
                .sync
                .suspend_policy
                .load(std::sync::atomic::Ordering::Relaxed)
        {
            self.start_sync(false, cx);
        }
    }
    pub(super) fn refresh_sync(&mut self, cx: &mut gpui::Context<Self>) {
        if let Some(core) = &self.sync.core {
            match core.sync_status() {
                Ok(status) => self.sync.status = Some(status),
                Err(error) => self.show_error(sync_error(error).to_string(), cx),
            }
        }
    }
    fn reset_sync_interval(&mut self) {
        let elapsed = self
            .sync
            .status
            .as_ref()
            .and_then(|s| {
                s.last_synced_at
                    .as_deref()
                    .and_then(|at| chrono::DateTime::parse_from_rfc3339(at).ok())
                    .map(|at| {
                        (chrono::Utc::now() - at.with_timezone(&chrono::Utc))
                            .num_seconds()
                            .max(0) as u64
                    })
                    .map(|seconds| {
                        Duration::from_secs(seconds.min(u64::from(s.policy.interval_minutes) * 60))
                    })
            })
            .unwrap_or_default();
        self.sync.last_attempt = Instant::now()
            .checked_sub(elapsed)
            .unwrap_or_else(Instant::now);
    }
    pub(super) fn sync_edited(&mut self) {
        self.sync.edited_at = Some(Instant::now());
    }
    fn sync_tick(&mut self, cx: &mut gpui::Context<Self>) {
        if self
            .sync
            .suspend_policy
            .load(std::sync::atomic::Ordering::Relaxed)
        {
            return;
        }
        if !self.sync.foreground || self.sync.busy {
            return;
        }
        let Some(status) = &self.sync.status else {
            return;
        };
        if !status.enabled || status.config.is_none() {
            return;
        }
        let after_edit = status.policy.after_edit
            && self
                .sync
                .edited_at
                .is_some_and(|at| at.elapsed() >= Duration::from_secs(30));
        let pending = self
            .sync
            .pending_at
            .is_some_and(|at| at.elapsed() >= Duration::from_secs(30));
        let interval = status.policy.interval_minutes;
        let since_sync = status
            .last_synced_at
            .as_deref()
            .and_then(|at| chrono::DateTime::parse_from_rfc3339(at).ok())
            .map(|at| (chrono::Utc::now() - at.with_timezone(&chrono::Utc)).num_seconds());
        let periodic = interval != 0
            && since_sync.is_none_or(|secs| secs >= i64::from(interval) * 60)
            && self.sync.last_attempt.elapsed() >= Duration::from_secs(u64::from(interval) * 60);
        if after_edit || pending || periodic {
            self.start_sync(false, cx);
        }
    }
    pub(super) fn start_sync(&mut self, manual: bool, cx: &mut gpui::Context<Self>) {
        if self.sync.busy
            || !self
                .sync
                .status
                .as_ref()
                .is_some_and(|s| s.enabled && s.config.is_some())
        {
            return;
        }
        let Some(core) = self.sync.core.clone() else {
            return;
        };
        self.sync.busy = true;
        self.sync.edited_at = None;
        self.sync.pending_at = None;
        self.sync.last_attempt = Instant::now();
        let transport = self.sync.transport.clone();
        let task = cx
            .background_executor()
            .spawn(async move { core.sync_now(transport) });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            let _ = this.update(cx, |this, cx| {
                this.sync.busy = false;
                this.refresh_sync(cx);
                match result {
                    Ok(report) => {
                        if this.sync.failure.is_some() && this.banner_text() == this.sync.failure {
                            this.banner = None;
                        }
                        this.sync.failure = None;
                        this.undo = None;
                        if let Err(error) = this.reload() {
                            this.show_error(format!("操作失败：{error}"), cx);
                        }
                        this.selected = this
                            .selected
                            .filter(|id| this.data.assets.iter().any(|a| a.id == *id));
                        if report.pending_local_changes {
                            this.sync.pending_at = Some(Instant::now());
                        }
                        if manual {
                            this.notify_info(
                                format!(
                                    "同步完成 · 拉取 {} 条 · 上传 {} 条{}",
                                    report.pulled_records,
                                    report.pushed_records,
                                    if report.pending_local_changes {
                                        "\n同步期间有新改动，下次同步时上传"
                                    } else {
                                        ""
                                    }
                                ),
                                cx,
                            );
                        }
                    }
                    Err(CoreError::Conflict) => {}
                    Err(error) => {
                        let message = sync_error(error).to_string();
                        this.sync.failure = Some(message.clone());
                        if manual {
                            this.show_error(message, cx);
                        }
                    }
                }
                cx.notify();
            });
        })
        .detach();
        cx.notify();
    }
    /// 按设置窗口提交的表单保存同步配置。
    pub(super) fn save_sync_config(
        &mut self,
        config: SyncConfig,
        secret: String,
        cx: &mut gpui::Context<Self>,
    ) {
        if self.sync.busy {
            return;
        }
        let result = self
            .sync
            .core
            .as_ref()
            .ok_or(CoreError::SyncNotConfigured)
            .and_then(|core| core.configure_sync(config, secret));
        self.sync_result(result, "同步配置已保存", cx);
    }
    fn sync_result(
        &mut self,
        result: Result<(), CoreError>,
        notice: &str,
        cx: &mut gpui::Context<Self>,
    ) {
        match result {
            Ok(()) => {
                self.banner = None;
                self.undo = None;
                self.sync.failure = None;
            }
            Err(error) => self.show_error(sync_error(error).to_string(), cx),
        }
        self.refresh_sync(cx);
        cx.notify();
    }
    pub(super) fn clear_sync(&mut self, cx: &mut gpui::Context<Self>) {
        let result = self
            .sync
            .core
            .as_ref()
            .ok_or(CoreError::SyncNotConfigured)
            .and_then(|core| core.clear_sync_config());
        if result.is_ok() {
            self.sync.edited_at = None;
            self.sync.pending_at = None;
        }
        self.sync_result(result, "同步配置已清除", cx);
    }
    pub(super) fn set_sync_enabled(&mut self, enabled: bool, cx: &mut gpui::Context<Self>) {
        if self.sync.busy {
            return;
        }
        let result = self
            .sync
            .core
            .as_ref()
            .ok_or(CoreError::SyncNotConfigured)
            .and_then(|core| core.set_sync_enabled(enabled));
        self.sync_result(
            result,
            if enabled {
                "同步已开启"
            } else {
                "同步已关闭"
            },
            cx,
        );
        self.reset_sync_interval();
    }
    pub(super) fn set_sync_policy(&mut self, policy: SyncPolicy, cx: &mut gpui::Context<Self>) {
        if self.sync.busy {
            return;
        }
        let result = self
            .sync
            .core
            .as_ref()
            .ok_or(CoreError::SyncNotConfigured)
            .and_then(|core| core.set_sync_policy(policy));
        match result {
            Ok(()) => self.banner = None,
            Err(error) => self.show_error(sync_error(error).to_string(), cx),
        }
        self.refresh_sync(cx);
        self.reset_sync_interval();
        cx.notify();
    }
    pub(super) fn sync_time(&self) -> String {
        self.sync
            .status
            .as_ref()
            .and_then(|s| s.last_synced_at.as_deref())
            .and_then(|at| chrono::DateTime::parse_from_rfc3339(at).ok())
            .map(|at| {
                let seconds = (chrono::Utc::now() - at.with_timezone(&chrono::Utc)).num_seconds();
                if seconds < 60 {
                    "刚刚".into()
                } else if seconds < 3600 {
                    format!("{} 分钟前", seconds / 60)
                } else {
                    at.with_timezone(&chrono::Local)
                        .format("%Y-%m-%d %H:%M")
                        .to_string()
                }
            })
            .unwrap_or_else(|| "尚未同步".into())
    }
    pub(super) fn banner_text(&self) -> Option<String> {
        self.banner.as_ref().map(|b| b.text.clone())
    }
    /// 工具栏圆形同步按钮（§5.9）：空闲/同步中（旋转）/失败（右上红点）。
    pub(super) fn sync_button(
        &self,
        p: &Palette,
        cx: &mut gpui::Context<Self>,
    ) -> gpui::Stateful<gpui::Div> {
        let busy = self.sync.busy;
        let failed = self.sync.failure.is_some();
        let dark = p.dark;
        let time = self.sync_time();
        let hint = if self
            .sync
            .status
            .as_ref()
            .is_some_and(|s| s.last_synced_at.is_some())
        {
            format!("立即同步 · 上次同步 {time}")
        } else {
            "立即同步 · 尚未同步".into()
        };
        let icon = gpui_component::Icon::default()
            .path("icons/line/refresh.svg")
            .size(px(20.))
            .text_color(if busy { p.brand } else { p.text });
        let icon = if busy {
            icon.with_animation(
                "sync-rotation",
                Animation::new(Duration::from_secs(1)).repeat(),
                |icon, delta| icon.transform(Transformation::rotate(percentage(delta))),
            )
            .into_any_element()
        } else {
            icon.into_any_element()
        };
        let button = div()
            .debug_selector(|| "sync-now".to_string())
            .id("sync-now")
            .tab_index(0)
            .tab_stop(true)
            .relative()
            .flex()
            .items_center()
            .justify_center()
            .size(px(32.))
            .rounded_full()
            .bg(p.material)
            .border_1()
            .border_color(p.material_edge)
            .when(!busy, |s| {
                s.cursor_pointer().hover(move |s| s.bg(p.hover()))
            })
            .focus(move |s| s.border_3().border_color(fade(p.brand, 0.4)))
            .tooltip(move |_, cx| {
                cx.new(|_| Hint {
                    text: hint.clone().into(),
                    dark,
                })
                .into()
            })
            .on_click(cx.listener(|this, _, _, cx| this.start_sync(true, cx)))
            .child(icon)
            .when(failed, |s| {
                s.child(
                    div()
                        .absolute()
                        .top(px(2.))
                        .right(px(2.))
                        .size(px(8.))
                        .rounded_full()
                        .border_2()
                        .border_color(p.card)
                        .bg(p.neg),
                )
            });
        button
    }
}
