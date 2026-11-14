use super::{
    components::{Hint, Palette},
    state::{Confirm, SnowFold},
};
use gpui::{prelude::*, *};
use gpui_component::{
    Disableable, Icon,
    input::{Input, InputState},
    switch::Switch,
};
use snowfold_core::{
    CoreError, SnowFoldCore, SyncBackend, SyncConfig, SyncPolicy, SyncStatus, SyncTransport,
};
use std::{
    sync::Arc,
    time::{Duration, Instant},
};

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
    fn load(&mut self, config: &SyncConfig, window: &mut Window, cx: &mut App) {
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
    pub form: SyncForm,
    pub busy: bool,
    pub failure: Option<String>,
    foreground: bool,
    edited_at: Option<Instant>,
    pending_at: Option<Instant>,
    last_attempt: Instant,
    _activation: Option<gpui::Subscription>,
    pub transport: Arc<dyn SyncTransport>,
}
impl SyncUi {
    pub fn new(window: &mut Window, cx: &mut App) -> Self {
        Self {
            core: None,
            status: None,
            form: SyncForm::new(window, cx),
            busy: false,
            failure: None,
            foreground: window.is_window_active(),
            edited_at: None,
            pending_at: None,
            last_attempt: Instant::now(),
            _activation: None,
            transport: Arc::new(snowfold_core::sync::RustSyncTransport::default()),
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
                self.refresh_sync();
                self.reset_sync_interval();
                if let Some(config) = self
                    .sync
                    .status
                    .as_ref()
                    .and_then(|status| status.config.clone())
                {
                    self.sync.form.load(&config, window, cx);
                }
            }
            Err(error) => {
                self.error = Some(format!("操作失败：{error}"));
            }
        }
        self.sync._activation = Some(cx.observe_window_activation(window, |this, window, cx| {
            this.sync.foreground = window.is_window_active();
            if this.sync.foreground
                && this
                    .sync
                    .status
                    .as_ref()
                    .is_some_and(|s| s.policy.on_launch)
            {
                this.start_sync(false, cx);
            }
        }));
        cx.defer_in(window, |this, _, cx| {
            if this
                .sync
                .status
                .as_ref()
                .is_some_and(|s| s.policy.on_launch)
            {
                this.start_sync(false, cx);
            }
        });
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
    pub(super) fn refresh_sync(&mut self) {
        if let Some(core) = &self.sync.core {
            match core.sync_status() {
                Ok(status) => self.sync.status = Some(status),
                Err(error) => self.error = Some(sync_error(error).into()),
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
                this.refresh_sync();
                match result {
                    Ok(report) => {
                        if this.sync.failure.is_some() && this.error == this.sync.failure {
                            this.error = None;
                        }
                        this.sync.failure = None;
                        this.undo = None;
                        this.history = None;
                        this.popover = None;
                        if let Err(error) = this.reload() {
                            this.error = Some(format!("操作失败：{error}"));
                        }
                        this.editing = this.editing.filter(|editing| {
                            use super::state::Editing;
                            match editing {
                                Editing::AssetName(id) | Editing::AssetAmount(id) => {
                                    this.data.assets.iter().any(|a| a.id == *id)
                                }
                                Editing::History(id) => {
                                    this.data.history.iter().any(|h| h.id == *id)
                                }
                                Editing::SubscriptionName(id) | Editing::SubscriptionAmount(id) => {
                                    this.data.subscriptions.iter().any(|s| s.id == *id)
                                }
                                Editing::NewAsset(_) | Editing::NewSubscription => true,
                            }
                        });
                        this.highlight = this.highlight.filter(|id| {
                            this.data.assets.iter().any(|a| a.id == *id)
                                || this.data.subscriptions.iter().any(|s| s.id == *id)
                        });
                        if report.pending_local_changes {
                            this.sync.pending_at = Some(Instant::now());
                        }
                        if manual {
                            this.notice = Some(format!(
                                "同步完成 · 拉取 {} 条 · 上传 {} 条{}",
                                report.pulled_records,
                                report.pushed_records,
                                if report.pending_local_changes {
                                    "\n同步期间有新改动，下次同步时上传"
                                } else {
                                    ""
                                }
                            ));
                        }
                    }
                    Err(CoreError::Conflict) => {}
                    Err(error) => {
                        let message = sync_error(error).to_string();
                        this.sync.failure = Some(message.clone());
                        if manual {
                            this.error = Some(message);
                        }
                    }
                }
                cx.notify();
            });
        })
        .detach();
        cx.notify();
    }
    pub(super) fn save_sync_config(&mut self, window: &mut Window, cx: &mut gpui::Context<Self>) {
        if self.sync.busy {
            return;
        }
        let form = &self.sync.form;
        let s3 = form.backend == SyncBackend::S3;
        let config = SyncConfig {
            backend: form.backend,
            endpoint: form.endpoint.read(cx).value().trim().into(),
            bucket: if s3 {
                form.bucket.read(cx).value().trim().into()
            } else {
                String::new()
            },
            region: if s3 {
                form.region.read(cx).value().trim().into()
            } else {
                String::new()
            },
            prefix: form.prefix.read(cx).value().trim().into(),
            username: form.username.read(cx).value().to_string(),
            path_style: s3 && form.path_style,
        };
        let secret = form.secret.read(cx).value().to_string();
        self.sync.form.clear_secret(window, cx);
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
                self.error = None;
                self.notice = Some(notice.into());
                self.sync.failure = None;
            }
            Err(error) => self.error = Some(sync_error(error).into()),
        }
        self.refresh_sync();
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
            self.confirm = None;
            self.sync.edited_at = None;
            self.sync.pending_at = None;
        }
        self.sync_result(result, "同步配置已清除", cx);
    }
    fn set_sync_enabled(&mut self, enabled: bool, cx: &mut gpui::Context<Self>) {
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
    fn set_sync_policy(&mut self, policy: SyncPolicy, cx: &mut gpui::Context<Self>) {
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
            Ok(()) => self.error = None,
            Err(error) => self.error = Some(sync_error(error).into()),
        }
        self.refresh_sync();
        self.reset_sync_interval();
        cx.notify();
    }
    fn sync_time(&self) -> String {
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
    pub(super) fn sync_button(&self, p: Palette, cx: &mut gpui::Context<Self>) -> Div {
        let enabled = self.sync.status.as_ref().is_some_and(|s| s.enabled);
        let busy = self.sync.busy;
        let failed = self.sync.failure.is_some();
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
        let icon = Icon::default().path("icons/refresh-cw.svg").size(px(16.));
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
        div().when(enabled, |row| {
            row.child(
                div()
                    .debug_selector(move || {
                        if failed {
                            "sync-button-failed".into()
                        } else {
                            "sync-button".into()
                        }
                    })
                    .id("sync-now-top")
                    .tab_index(0)
                    .tab_stop(p.keyboard && !busy)
                    .relative()
                    .flex()
                    .items_center()
                    .justify_center()
                    .size(px(30.))
                    .rounded(px(8.))
                    .border_1()
                    .border_color(transparent_black())
                    .focus(move |s| s.border_color(p.accent))
                    .text_color(p.muted)
                    .when(!busy, |s| s.cursor_pointer().hover(move |s| s.bg(p.subtle)))
                    .tooltip(move |_, cx| {
                        cx.new(|_| Hint {
                            text: hint.clone().into(),
                            palette: p,
                        })
                        .into()
                    })
                    .on_click(cx.listener(|this, _, _, cx| this.start_sync(true, cx)))
                    .child(icon)
                    .when(failed, |s| {
                        s.child(
                            div()
                                .debug_selector(|| "sync-failure-dot".into())
                                .absolute()
                                .top(px(2.))
                                .right(px(2.))
                                .size(px(5.))
                                .rounded_full()
                                .bg(p.neg),
                        )
                    }),
            )
        })
    }
    pub(super) fn sync_settings(&self, p: Palette, cx: &mut gpui::Context<Self>) -> Div {
        let status = self.sync.status.as_ref();
        let configured = status.is_some_and(|s| s.config.is_some());
        let exported = status.is_some_and(|s| s.recovery_code_exported);
        let enabled = status.is_some_and(|s| s.enabled);
        let busy = self.sync.busy;
        let s3 = self.sync.form.backend == SyncBackend::S3;
        let note = |text: &'static str| {
            div()
                .text_size(px(11.5))
                .text_color(p.ink3)
                .line_height(px(18.))
                .child(text)
        };
        let field = |label, input: &Entity<InputState>| {
            p.field(
                label,
                Input::new(input)
                    .h(px(36.))
                    .w_full()
                    .rounded(px(9.))
                    .text_size(px(13.5))
                    .disabled(busy),
            )
        };
        let mut rows = vec![note(
            "数据先用本机同步密钥加密再上传，服务器只能看到密文。只在你打开应用或点「立即同步」时联网。",
        )];
        if !exported {
            rows.push(div().py(px(7.)).flex().flex_col().gap(px(8.)).child(note("开启同步前请先导出恢复码。换机或另一台设备要用同一个恢复码才能解密同步数据。"))
                .child(p.action_soft("sync-export-recovery", "导出恢复码").on_click(cx.listener(|this, _, _, cx| this.export_recovery_code(cx)))));
        }
        let mut backends = div()
            .flex()
            .gap(px(2.))
            .p(px(3.))
            .rounded(px(10.))
            .border_1()
            .border_color(p.line)
            .bg(p.subtle);
        for (backend, label) in [
            (SyncBackend::S3, "S3 兼容存储"),
            (SyncBackend::WebDav, "WebDAV"),
        ] {
            backends = backends.child(
                p.segment(label, label, self.sync.form.backend == backend)
                    .when(!busy, |s| {
                        s.on_click(cx.listener(move |this, _, window, cx| {
                            this.sync.form.backend = backend;
                            this.sync.form.clear_secret(window, cx);
                            cx.notify();
                        }))
                    }),
            );
        }
        let mut form = div()
            .flex()
            .flex_col()
            .gap(px(12.))
            .py(px(7.))
            .child(p.field("存储类型", backends))
            .child(field("服务地址", &self.sync.form.endpoint));
        if s3 {
            form = form.child(
                div()
                    .flex()
                    .gap(px(12.))
                    .child(field("存储桶", &self.sync.form.bucket))
                    .child(field("区域", &self.sync.form.region)),
            );
        }
        form = form.child(field("路径前缀（可选）", &self.sync.form.prefix));
        if s3 {
            form = form.child(
                p.sheet_row(
                    "使用路径风格地址",
                    "",
                    Switch::new("sync-path-style")
                        .checked(self.sync.form.path_style)
                        .disabled(busy)
                        .on_click(cx.listener(|this, value, _, cx| {
                            this.sync.form.path_style = *value;
                            cx.notify();
                        })),
                ),
            );
        }
        form = form
            .child(field(
                if s3 { "Access Key ID" } else { "用户名" },
                &self.sync.form.username,
            ))
            .child(field(
                if s3 { "Secret Access Key" } else { "密码" },
                &self.sync.form.secret,
            ));
        if configured {
            form = form.child(note(
                "密钥与密码加密保存在本机，不会显示；修改配置时需要重新输入。",
            ));
        }
        form = form.child(
            p.action_soft("sync-save-config", "保存配置")
                .when(busy, |s| s.opacity(0.5))
                .on_click(cx.listener(|this, _, window, cx| this.save_sync_config(window, cx))),
        );
        rows.push(form);
        if configured {
            rows.push(
                p.sheet_row(
                    "开启同步",
                    "",
                    Switch::new("sync-enable")
                        .checked(enabled)
                        .disabled(busy || !exported)
                        .on_click(
                            cx.listener(|this, value, _, cx| this.set_sync_enabled(*value, cx)),
                        ),
                ),
            );
            let policy = status.unwrap().policy.clone();
            let on_launch = policy.clone();
            let after_edit = policy.clone();
            let interval = policy.clone();
            let interval_label = match policy.interval_minutes {
                15 => "每 15 分钟",
                30 => "每 30 分钟",
                60 => "每小时",
                _ => "关闭",
            };
            rows.push(p.sheet_group(
                true,
                "同步策略",
                vec![
                        p.sheet_row(
                            "打开应用时同步",
                            "",
                            Switch::new("sync-on-launch")
                                .checked(policy.on_launch)
                                .disabled(busy)
                                .on_click(cx.listener(move |this, value, _, cx| {
                                    let mut policy = on_launch.clone();
                                    policy.on_launch = *value;
                                    this.set_sync_policy(policy, cx);
                                })),
                        ),
                        p.sheet_row(
                            "自动同步",
                            "",
                            p.field_select("sync-interval", interval_label)
                                .when(!busy, |s| {
                                    s.on_click(cx.listener(move |this, _, _, cx| {
                                        let mut policy = interval.clone();
                                        policy.interval_minutes = match policy.interval_minutes {
                                            0 => 15,
                                            15 => 30,
                                            30 => 60,
                                            _ => 0,
                                        };
                                        this.set_sync_policy(policy, cx);
                                    }))
                                }),
                        ),
                        p.sheet_row(
                            "修改后自动同步",
                            "",
                            Switch::new("sync-after-edit")
                                .checked(policy.after_edit)
                                .disabled(busy)
                                .on_click(cx.listener(move |this, value, _, cx| {
                                    let mut policy = after_edit.clone();
                                    policy.after_edit = *value;
                                    this.set_sync_policy(policy, cx);
                                })),
                        ),
                        note("自动同步只在应用打开时进行，关闭应用后不会在后台联网。"),
                    ],
            ));
            let state_text = if busy {
                "正在同步…".into()
            } else if status.unwrap().last_synced_at.is_some() {
                format!("上次同步 {}", self.sync_time())
            } else {
                "尚未同步".into()
            };
            rows.push(div().py(px(7.)).text_size(px(12.5)).child(state_text));
            if let Some(error) = &self.sync.failure {
                rows.push(
                    div()
                        .text_size(px(11.5))
                        .text_color(p.neg)
                        .child(error.clone()),
                );
            }
            rows.push(
                div()
                    .flex()
                    .items_center()
                    .gap(px(8.))
                    .py(px(7.))
                    .child(
                        p.action_soft("sync-now-settings", "立即同步")
                            .when(busy || !enabled, |s| s.opacity(0.5))
                            .on_click(cx.listener(|this, _, _, cx| this.start_sync(true, cx))),
                    )
                    .child(
                        p.action_danger("sync-clear-config", "清除配置")
                            .when(busy, |s| s.opacity(0.5))
                            .on_click(cx.listener(|this, _, window, cx| {
                                if !this.sync.busy {
                                    this.confirm = Some(Confirm::ClearSync);
                                    this.modal_focus.focus(window);
                                    cx.notify();
                                }
                            })),
                    ),
            );
        }
        rows.push(note(
            "在另一台设备上导入本机的恢复码，并填写相同的存储配置，即可同步同一份数据。",
        ));
        p.sheet_group(false, "同步", rows)
    }
}
