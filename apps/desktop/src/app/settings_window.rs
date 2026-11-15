//! 独立设置窗口（§9.6）：Ctrl+, 打开，宽 680；顶部 88 高浮层工具栏（标题居中 +
//! 四页签），分组卡片内容。页签：通用、数据、加密与同步、关于。

use gpui::{prelude::*, *};
use gpui_component::input::InputState;

use super::{
    state::{Confirm, SnowFold},
    widgets::{M, SB, btn_danger, btn_primary, btn_secondary, btn_text, card, sizes, switch, t},
};
use crate::theme::Palette;
use snowfold_core::storage::Theme;
use snowfold_core::{SyncBackend, SyncConfig};

/// 主界面状态快照：打开时构建、随主实体 notify 刷新；
/// 避免 render 在主实体 update 期间同步读它（会重入 panic）。
#[derive(Clone)]
pub(super) struct SettingsSnapshot {
    pub(super) confirm_open: bool,
    pub(super) theme: Theme,
    pub(super) fingerprint: Option<String>,
    pub(super) status: Option<snowfold_core::SyncStatus>,
    pub(super) busy: bool,
    pub(super) failure: Option<String>,
    pub(super) sync_time: String,
}

pub(super) struct SettingsWindow {
    app: gpui::WeakEntity<SnowFold>,
    tab: usize,
    focus: FocusHandle,
    /// 同步表单（输入实体属于本窗口；凭证不进主界面状态）。
    endpoint: Entity<InputState>,
    bucket: Entity<InputState>,
    region: Entity<InputState>,
    prefix: Entity<InputState>,
    username: Entity<InputState>,
    secret: Entity<InputState>,
    backend: SyncBackend,
    path_style: bool,
    /// 「修改」按钮展开存储表单。
    editing_config: bool,
    /// 确认框从设置窗口触发（在设置窗口里渲染遮罩）。
    confirm_here: bool,
    /// 主界面状态快照（打开时传入、notify 后刷新）。
    snapshot: SettingsSnapshot,
}

fn secret_input(window: &mut Window, cx: &mut App) -> Entity<InputState> {
    cx.new(|cx| {
        InputState::new(window, cx)
            .masked(true)
            .validate(|value, _| !value.contains(['\r', '\n']))
    })
}

impl SettingsWindow {
    pub(super) fn new(
        app: gpui::WeakEntity<SnowFold>,
        snapshot: SettingsSnapshot,
        window: &mut Window,
        cx: &mut gpui::Context<Self>,
    ) -> Self {
        let input = |masked: bool, window: &mut Window, cx: &mut gpui::Context<Self>| {
            cx.new(|cx| {
                InputState::new(window, cx)
                    .masked(masked)
                    .validate(|value, _| !value.contains(['\r', '\n']))
            })
        };
        let mut this = Self {
            app: app.clone(),
            tab: 0,
            focus: cx.focus_handle(),
            endpoint: input(false, window, cx),
            bucket: input(false, window, cx),
            region: input(false, window, cx),
            prefix: input(false, window, cx),
            username: input(false, window, cx),
            secret: input(true, window, cx),
            backend: SyncBackend::S3,
            path_style: true,
            editing_config: false,
            confirm_here: false,
            snapshot: snapshot.clone(),
        };
        // 已配置时把存储信息带进表单（修改时直接可编辑）。
        if let Some(config) = snapshot.status.as_ref().and_then(|st| st.config.clone()) {
            this.load_config(&config, window, cx);
        }
        // 主界面变化（同步状态、主题等）时刷新本窗口并更新快照。
        // 观察回调在主实体 update 结束后执行，读取安全。
        if let Some(entity) = app.upgrade() {
            cx.observe(&entity, |this, entity, cx| {
                this.snapshot = entity.read(cx).settings_snapshot();
                cx.notify();
            })
            .detach();
        }
        this.focus.focus(window);
        this
    }

    fn read_app<R>(&self, cx: &gpui::App, read: impl FnOnce(&SnowFold, &App) -> R) -> Option<R> {
        self.app.read_with(cx, read).ok()
    }

    fn load_config(
        &mut self,
        config: &SyncConfig,
        window: &mut Window,
        cx: &mut gpui::Context<Self>,
    ) {
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
    }

    /// 在主界面实体上执行（无窗口上下文；焦点操作走 apply_confirm 等无窗口路径）。
    fn with_app<R>(
        &self,
        cx: &mut gpui::Context<Self>,
        f: impl FnOnce(&mut SnowFold, &mut gpui::Context<SnowFold>) -> R,
    ) -> Option<R> {
        self.app.update(cx, f).ok()
    }
}

impl gpui::Render for SettingsWindow {
    fn render(&mut self, window: &mut Window, cx: &mut gpui::Context<Self>) -> impl IntoElement {
        let dark = gpui_component::Theme::global(cx).mode == gpui_component::ThemeMode::Dark;
        let mut p = Palette::new(dark);
        p.opaque_window = !crate::platform::backdrop_enabled(cx);
        let tabs: [(&str, &str, &str); 4] = [
            ("icons/line/settings.svg", "icons/fill/settings.svg", "通用"),
            ("icons/line/database.svg", "icons/fill/database.svg", "数据"),
            (
                "icons/line/shield-lock.svg",
                "icons/fill/shield-lock.svg",
                "加密与同步",
            ),
            ("icons/line/info.svg", "icons/fill/info.svg", "关于"),
        ];
        let title = tabs[self.tab.min(3)].2;

        let mut tab_bar = div().flex().justify_center().gap(px(4.));
        for (i, (line, fill, label)) in tabs.iter().enumerate() {
            let active = i == self.tab;
            tab_bar = tab_bar.child(
                div()
                    .id(("settings-tab", i))
                    .tab_index(0)
                    .tab_stop(true)
                    .w(px(84.))
                    .flex()
                    .flex_col()
                    .items_center()
                    .gap(px(2.))
                    .py(px(8.))
                    .rounded(px(10.))
                    .cursor_pointer()
                    .when(active, |s| s.bg(p.brand_soft))
                    .hover(move |s| if active { s } else { s.bg(p.hover()) })
                    .focus(move |s| s.border_3().border_color(crate::theme::fade(p.brand, 0.4)))
                    .on_click(cx.listener(move |this, _, _, cx| {
                        this.tab = i;
                        cx.notify();
                    }))
                    .child(
                        svg()
                            .path(if active { *fill } else { *line })
                            .size(px(24.))
                            .text_color(if active { p.brand } else { p.text2 }),
                    )
                    .child(div().text_size(px(sizes::AUX)).child(t(
                        *label,
                        if active { p.brand } else { p.text2 },
                        M,
                    ))),
            );
        }

        let content = match self.tab {
            0 => self.tab_general(&p, cx),
            1 => self.tab_data(&p, cx),
            2 => self.tab_seal_sync(window, &p, cx),
            _ => self.tab_about(&p),
        };

        let mut root = div()
            .relative()
            .size_full()
            .flex()
            .flex_col()
            .overflow_hidden()
            .when(p.opaque_window, |s| s.bg(p.bg))
            .when(!p.opaque_window, |s| s.bg(crate::theme::fade(p.bg, 0.45)))
            .text_color(p.text)
            .text_size(px(13.))
            .font_family(crate::theme::FONT_LATIN)
            .track_focus(&self.focus)
            // —— 顶部 88 高浮层材质工具栏（Windows 无红绿灯；可拖动）——
            .child(
                div()
                    .window_control_area(WindowControlArea::Drag)
                    .on_mouse_down(MouseButton::Left, |_, window, _| window.start_window_move())
                    .flex_none()
                    .h(px(88.))
                    .bg(p.material)
                    .border_b_1()
                    .border_color(p.material_edge)
                    .child(
                        div().pt(px(14.)).flex().justify_center().child(
                            div()
                                .text_size(px(sizes::SECTION))
                                .line_height(px(sizes::SECTION_LH))
                                .child(t(title, p.text, SB)),
                        ),
                    )
                    .child(div().pt(px(2.)).child(tab_bar)),
            )
            .child(
                div()
                    .id("settings-content")
                    .flex_1()
                    .overflow_y_scroll()
                    .px(px(32.))
                    .pt(px(24.))
                    .pb(px(32.))
                    .child(content),
            );
        // 确认框从设置窗口发起时在本窗口渲染遮罩。
        if self.confirm_here && self.snapshot.confirm_open {
            root = root.child(self.render_confirm_here(&p, cx));
        }
        let _ = window;
        root
    }
}

impl SettingsWindow {
    fn tab_general(&self, p: &Palette, cx: &mut gpui::Context<Self>) -> Div {
        let theme_index = Some(self.snapshot.theme)
            .map(|theme| match theme {
                Theme::Light => 1usize,
                Theme::Dark => 2,
                Theme::System => 0,
            })
            .unwrap_or(0);
        let mut container = div()
            .w(px(240.))
            .flex()
            .p(px(2.))
            .rounded(px(10.))
            .border_1()
            .border_color(p.sep);
        for (i, label) in ["自动", "浅色", "深色"].iter().enumerate() {
            let active = i == theme_index;
            container = container.child(
                div()
                    .id(("theme-seg", i))
                    .tab_index(0)
                    .tab_stop(true)
                    .flex_1()
                    .h(px(24.))
                    .flex()
                    .items_center()
                    .justify_center()
                    .rounded(px(8.))
                    .text_size(px(sizes::BODY))
                    .cursor_pointer()
                    .when(active, |s| s.bg(p.brand))
                    .hover(move |s| if active { s } else { s.bg(p.hover()) })
                    .focus(move |s| s.border_3().border_color(crate::theme::fade(p.brand, 0.4)))
                    .on_click(cx.listener(move |this, _, window, cx| {
                        let theme = [Theme::System, Theme::Light, Theme::Dark][i];
                        let appearance = window.appearance();
                        this.with_app(cx, |app, cx| app.set_theme_with(theme, appearance, cx));
                        cx.notify();
                    }))
                    .child(if active {
                        t(*label, p.on_brand, M)
                    } else {
                        t(*label, p.text2, FontWeight::NORMAL)
                    }),
            );
        }
        div().flex().flex_col().gap(px(24.)).child(self.group(
            "外观",
            vec![self.row("主题", "「自动」会跟随系统的深浅色。", container, p)],
            p,
        ))
    }

    fn tab_data(&mut self, p: &Palette, cx: &mut gpui::Context<Self>) -> Div {
        div().flex().flex_col().gap(px(24.)).child(self.group(
            "数据",
            vec![
                self.row(
                    "导出备份",
                    "保存为 JSON 文件，可随时导入恢复。",
                    btn_secondary("settings-export", "导出", p).on_click(cx.listener(
                        |this, _, _, cx| {
                            this.with_app(cx, |app, cx| app.export(cx));
                        },
                    )),
                    p,
                ),
                self.row(
                    "导入备份",
                    "会覆盖当前所有记录，请先导出备份。",
                    btn_secondary("settings-import", "导入", p).on_click(cx.listener(
                        |this, _, _, cx| {
                            this.with_app(cx, |app, cx| {
                                app.import(cx);
                                app.confirm_in_settings = true;
                            });
                            this.confirm_here = true;
                            cx.notify();
                        },
                    )),
                    p,
                ),
                self.row(
                    "清空全部数据",
                    "删除本机上的全部资产与订阅记录。",
                    btn_danger("settings-clear", "清空", p).on_click(cx.listener(
                        |this, _, _, cx| {
                            this.with_app(cx, |app, cx| {
                                app.confirm = Some(Confirm::Clear);
                                app.confirm_in_settings = true;
                                cx.notify();
                            });
                            this.confirm_here = true;
                        },
                    )),
                    p,
                ),
            ],
            p,
        ))
    }

    fn tab_seal_sync(
        &mut self,
        window: &gpui::Window,
        p: &Palette,
        cx: &mut gpui::Context<Self>,
    ) -> Div {
        let Some((fingerprint, status, busy, failure)) = Some((
            self.snapshot.fingerprint.clone(),
            self.snapshot.status.clone(),
            self.snapshot.busy,
            self.snapshot.failure.clone(),
        )) else {
            return div();
        };
        let configured = status.as_ref().is_some_and(|s| s.config.is_some());
        let exported = status.as_ref().is_some_and(|s| s.recovery_code_exported);
        let enabled = status.as_ref().is_some_and(|s| s.enabled);
        let s3 = self.backend == SyncBackend::S3;

        let mut body = div().flex().flex_col().gap(px(24.));
        body = body.child(self.group(
            "加密备份",
            vec![
                self.row(
                    "加密备份",
                    "导出 .sfseal 密文，可安全上传网盘或发给家人。",
                    btn_secondary("settings-export-sealed", "导出密文", p).on_click(cx.listener(
                        |this, _, _, cx| {
                            this.with_app(cx, |app, cx| app.export_sealed(cx));
                        },
                    )),
                    p,
                ),
                self.row(
                    "恢复加密备份",
                    "导入密文并覆盖当前数据；需要本机同步密钥。",
                    btn_secondary("settings-import-sealed", "导入密文", p).on_click(cx.listener(
                        |this, _, _, cx| {
                            this.with_app(cx, |app, cx| {
                                app.import_sealed(cx);
                                app.confirm_in_settings = true;
                            });
                            this.confirm_here = true;
                            cx.notify();
                        },
                    )),
                    p,
                ),
                self.row(
                    "同步密钥指纹",
                    "",
                    crate::theme::text(
                        fingerprint.unwrap_or_else(|| "尚未生成".into()),
                        p.text2,
                        FontWeight::NORMAL,
                    ),
                    p,
                ),
                self.row(
                    "同步密钥恢复码",
                    "换机或重装后恢复加密备份；请离线保存，不要放云盘同步目录。",
                    div()
                        .flex()
                        .gap(px(8.))
                        .child(
                            btn_secondary("settings-export-recovery", "导出恢复码", p).on_click(
                                cx.listener(|this, _, _, cx| {
                                    this.with_app(cx, |app, cx| {
                                        app.export_recovery_code(cx)
                                    });
                                }),
                            ),
                        )
                        .child(
                            btn_secondary("settings-import-recovery", "导入恢复码", p).on_click(
                                cx.listener(|this, _, _, cx| {
                                    this.with_app(cx, |app, cx| {
                                        app.import_recovery_code(cx);
                                        app.confirm_in_settings = true;
                                    });
                                    this.confirm_here = true;
                                    cx.notify();
                                }),
                            ),
                        ),
                    p,
                ),
            ],
            p,
        ));

        // —— 同步 ——
        let mut sync_rows: Vec<gpui::Div> = Vec::new();
        if !exported {
            sync_rows.push(
                div()
                    .text_size(px(sizes::AUX))
                    .line_height(px(sizes::AUX_LH))
                    .child(t(
                        "开启同步前请先导出恢复码。换机或另一台设备要用同一个恢复码才能解密同步数据。",
                        p.text2,
                        FontWeight::NORMAL,
                    )),
            );
        }
        if !configured || self.editing_config {
            let field = |label: &'static str,
                         input: &Entity<InputState>,
                         p: &Palette,
                         window: &gpui::Window,
                         cx: &App|
             -> gpui::Div {
                super::widgets::field_row(
                    label,
                    super::widgets::input_field(input, false, p, window, cx),
                    p,
                )
            };
            let mut form = div().flex().flex_col().gap(px(16.)).py(px(8.));
            let mut backends = div()
                .flex()
                .p(px(2.))
                .rounded(px(10.))
                .border_1()
                .border_color(p.sep);
            for (i, (backend, label)) in [
                (SyncBackend::S3, "S3 兼容存储"),
                (SyncBackend::WebDav, "WebDAV"),
            ]
            .into_iter()
            .enumerate()
            {
                let active = self.backend == backend;
                backends = backends.child(
                    div()
                        .id(("sync-backend", i))
                        .tab_index(0)
                        .tab_stop(true)
                        .flex_1()
                        .h(px(24.))
                        .flex()
                        .items_center()
                        .justify_center()
                        .rounded(px(8.))
                        .text_size(px(sizes::BODY))
                        .cursor_pointer()
                        .when(active, |s| s.bg(p.brand))
                        .hover(move |s| if active { s } else { s.bg(p.hover()) })
                        .on_click(cx.listener(move |this, _, _, cx| {
                            this.backend = backend;
                            cx.notify();
                        }))
                        .child(if active {
                            t(label, p.on_brand, M)
                        } else {
                            t(label, p.text2, FontWeight::NORMAL)
                        }),
                );
            }
            form = form
                .child(super::widgets::field_row("存储类型", backends, p))
                .child(field("服务地址", &self.endpoint, p, window, cx));
            if s3 {
                form = form
                    .child(field("存储桶", &self.bucket, p, window, cx))
                    .child(field("区域", &self.region, p, window, cx));
            }
            form = form.child(field("路径前缀（可选）", &self.prefix, p, window, cx));
            form = form.child(field(
                if s3 { "Access Key ID" } else { "用户名" },
                &self.username,
                p,
                window,
                cx,
            ));
            form = form.child(field(
                if s3 { "Secret Access Key" } else { "密码" },
                &self.secret,
                p,
                window,
                cx,
            ));
            if s3 {
                form = form.child(self.row(
                    "使用路径风格地址",
                    "",
                    switch("sync-path-style", self.path_style, p).on_click(cx.listener(
                        |this, _, _, cx| {
                            this.path_style = !this.path_style;
                            cx.notify();
                        },
                    )),
                    p,
                ));
            }
            if configured {
                form = form.child(
                    div()
                        .text_size(px(sizes::AUX))
                        .line_height(px(sizes::AUX_LH))
                        .child(t(
                            "密钥与密码加密保存在本机，不会显示；修改配置时需要重新输入。",
                            p.text2,
                            FontWeight::NORMAL,
                        )),
                );
            }
            form = form.child(div().flex().justify_end().child(
                btn_primary("sync-save-config", "保存配置", p).on_click(cx.listener(
                    |this, _, window, cx| {
                        this.save_config(window, cx);
                    },
                )),
            ));
            sync_rows.push(div().py(px(8.)).child(form));
        } else {
            let sync_time = self.snapshot.sync_time.clone();
            let config = status.as_ref().and_then(|s| s.config.clone());
            sync_rows.push(
                self.row(
                    "开启同步",
                    format!("上次同步 {sync_time}"),
                    switch("sync-enable", enabled, p)
                        .when(busy || !exported, |s| s.opacity(0.5))
                        .on_click(cx.listener(move |this, _, _, cx| {
                            this.with_app(cx, |app, cx| {
                                app.set_sync_enabled(!enabled, cx);
                            });
                            cx.notify();
                        })),
                    p,
                ),
            );
            sync_rows.push(
                self.row(
                    "存储",
                    config
                        .map(|c| {
                            format!(
                                "{} · {}",
                                if c.backend == SyncBackend::S3 {
                                    "S3 兼容存储"
                                } else {
                                    "WebDAV"
                                },
                                c.endpoint
                            )
                        })
                        .unwrap_or_default(),
                    btn_secondary("sync-edit-config", "修改", p).on_click(cx.listener(
                        |this, _, _, cx| {
                            this.editing_config = true;
                            cx.notify();
                        },
                    )),
                    p,
                ),
            );
            sync_rows.push(
                div().flex().justify_end().py(px(8.)).child(
                    btn_secondary("sync-now-settings", "立即同步", p)
                        .when(busy || !enabled, |s| s.opacity(0.5))
                        .on_click(cx.listener(|this, _, _, cx| {
                            this.with_app(cx, |app, cx| app.start_sync(true, cx));
                            cx.notify();
                        })),
                ),
            );
        }
        body = body
            .child(self.group("同步", sync_rows, p))
            .child(
                div()
                    .text_size(px(sizes::AUX))
                    .line_height(px(sizes::AUX_LH))
                    .child(t(
                        "数据先用本机同步密钥加密再上传，服务器只能看到密文。只在你打开应用或点「立即同步」时联网。",
                        p.text2,
                        FontWeight::NORMAL,
                    )),
            );

        // —— 同步策略 ——
        let mut policy_rows: Vec<gpui::Div> = Vec::new();
        if let Some(policy) = status.as_ref().map(|s| s.policy.clone()) {
            policy_rows.push(
                self.row(
                    "打开应用时同步",
                    "",
                    switch("sync-on-launch", policy.on_launch, p)
                        .when(busy, |s| s.opacity(0.5))
                        .on_click(cx.listener({
                            let policy = policy.clone();
                            move |this, _, _, cx| {
                                this.with_app(cx, |app, cx| {
                                    let mut policy = policy.clone();
                                    policy.on_launch = !policy.on_launch;
                                    app.set_sync_policy(policy, cx);
                                });
                                cx.notify();
                            }
                        })),
                    p,
                ),
            );
            let interval_label = match policy.interval_minutes {
                15 => "每 15 分钟",
                30 => "每 30 分钟",
                60 => "每小时",
                _ => "关闭",
            };
            policy_rows.push(self.row(
                "自动同步",
                "",
                btn_secondary("sync-interval", interval_label, p).on_click(cx.listener({
                    let policy = policy.clone();
                    move |this, _, _, cx| {
                        this.with_app(cx, |app, cx| {
                            let mut policy = policy.clone();
                            policy.interval_minutes = match policy.interval_minutes {
                                0 => 15,
                                15 => 30,
                                30 => 60,
                                _ => 0,
                            };
                            app.set_sync_policy(policy, cx);
                        });
                        cx.notify();
                    }
                })),
                p,
            ));
            policy_rows.push(
                self.row(
                    "修改后自动同步",
                    "",
                    switch("sync-after-edit", policy.after_edit, p)
                        .when(busy, |s| s.opacity(0.5))
                        .on_click(cx.listener({
                            let policy = policy.clone();
                            move |this, _, _, cx| {
                                this.with_app(cx, |app, cx| {
                                    let mut policy = policy.clone();
                                    policy.after_edit = !policy.after_edit;
                                    app.set_sync_policy(policy, cx);
                                });
                                cx.notify();
                            }
                        })),
                    p,
                ),
            );
        }
        body = body.child(self.group("同步策略", policy_rows, p)).child(
            div()
                .text_size(px(sizes::AUX))
                .line_height(px(sizes::AUX_LH))
                .child(t(
                    "自动同步只在应用打开时进行，关闭应用后不会在后台联网。",
                    p.text2,
                    FontWeight::NORMAL,
                )),
        );
        if configured {
            body = body.child(
                btn_text("sync-clear-config", "清除配置", p.neg, p).on_click(cx.listener(
                    |this, _, _, cx| {
                        this.with_app(cx, |app, cx| {
                            app.confirm = Some(Confirm::ClearSync);
                            app.confirm_in_settings = true;
                            cx.notify();
                        });
                        this.confirm_here = true;
                    },
                )),
            );
        }
        if let Some(failure) = failure {
            body = body.child(div().text_size(px(sizes::AUX)).child(t(
                failure,
                p.neg,
                FontWeight::NORMAL,
            )));
        }
        body
    }

    fn tab_about(&self, p: &Palette) -> Div {
        div().flex().flex_col().gap(px(24.)).child(self.group(
            "关于",
            vec![self.row(
                "SnowFold · MVP 0.1",
                "默认离线运行 · 仅在开启同步后联网",
                div(),
                p,
            )],
            p,
        ))
    }

    fn save_config(&mut self, window: &mut Window, cx: &mut gpui::Context<Self>) {
        let s3 = self.backend == SyncBackend::S3;
        let read =
            |input: &Entity<InputState>, cx: &gpui::App| input.read(cx).value().trim().to_string();
        let config = SyncConfig {
            backend: self.backend,
            endpoint: read(&self.endpoint, cx),
            bucket: if s3 {
                read(&self.bucket, cx)
            } else {
                String::new()
            },
            region: if s3 {
                read(&self.region, cx)
            } else {
                String::new()
            },
            prefix: read(&self.prefix, cx),
            username: read(&self.username, cx),
            path_style: s3 && self.path_style,
        };
        let secret = self.secret.read(cx).value().to_string();
        // 凭证不进宿主状态副本：提交前替换输入实体，连同撤销历史一起释放。
        self.secret = secret_input(window, cx);
        self.with_app(cx, move |app, cx| {
            app.save_sync_config(config, secret, cx);
        });
        self.editing_config = false;
        cx.notify();
    }

    /// 分组：组头（辅助中等 text2）+ 卡片内行。
    fn group(&self, label: &'static str, rows: Vec<gpui::Div>, p: &Palette) -> gpui::Div {
        let mut group = div()
            .flex()
            .flex_col()
            .gap(px(12.))
            .child(div().text_size(px(sizes::AUX)).child(t(label, p.text2, M)));
        let mut card = card().bg(p.card);
        for (i, row) in rows.into_iter().enumerate() {
            if i > 0 {
                card = card
                    .child(super::widgets::row_separator(16., p))
                    .child(div().h(px(0.)).w_full());
            }
            card = card.child(row);
        }
        group.child(card)
    }

    /// 设置行：标题正文 + 说明辅助 text2（最宽 360），右侧控件；行最小高 56。
    fn row(
        &self,
        title: &'static str,
        desc: impl Into<SharedString>,
        control: impl IntoElement,
        p: &Palette,
    ) -> gpui::Div {
        let desc = desc.into();
        div()
            .flex()
            .items_center()
            .gap(px(16.))
            .px(px(16.))
            .py(px(12.))
            .min_h(px(56.))
            .child(
                div()
                    .flex()
                    .flex_col()
                    .gap(px(2.))
                    .flex_1()
                    .min_w(px(0.))
                    .child(div().text_size(px(sizes::BODY)).child(t(
                        title,
                        p.text,
                        FontWeight::NORMAL,
                    )))
                    .when(!desc.is_empty(), |col| {
                        col.child(
                            div()
                                .max_w(px(360.))
                                .text_size(px(sizes::AUX))
                                .line_height(px(sizes::AUX_LH))
                                .child(t(desc, p.text2, FontWeight::NORMAL)),
                        )
                    }),
            )
            .child(control)
    }

    /// 确认框（从设置窗口发起）：与主窗口相同规格，主界面状态驱动。
    fn render_confirm_here(&mut self, p: &Palette, cx: &mut gpui::Context<Self>) -> AnyElement {
        let Some((icon, title, body, danger_label)) = self
            .read_app(cx, |app, _| {
                app.confirm.as_ref().map(super::dialogs::confirm_info)
            })
            .unwrap_or(None)
        else {
            return div().into_any_element();
        };
        div()
            .absolute()
            .inset_0()
            .bg(p.mask_confirm)
            .flex()
            .justify_center()
            .items_center()
            .child(
                super::widgets::dialog_shell("settings-confirm", 420., p)
                    .child(
                        div()
                            .flex()
                            .gap(px(16.))
                            .px(px(24.))
                            .pt(px(24.))
                            .child(
                                div()
                                    .flex_none()
                                    .flex()
                                    .items_center()
                                    .justify_center()
                                    .size(px(40.))
                                    .rounded_full()
                                    .bg(p.neg_soft)
                                    .child(svg().path(icon).size(px(20.)).text_color(p.neg)),
                            )
                            .child(
                                div()
                                    .flex()
                                    .flex_col()
                                    .gap(px(4.))
                                    .min_w(px(0.))
                                    .child(
                                        div()
                                            .text_size(px(sizes::SECTION))
                                            .line_height(px(sizes::SECTION_LH))
                                            .child(t(title, p.text, SB)),
                                    )
                                    .child(
                                        div()
                                            .text_size(px(sizes::BODY))
                                            .line_height(px(sizes::BODY_LH))
                                            .child(t(body, p.text2, FontWeight::NORMAL)),
                                    ),
                            ),
                    )
                    .child(
                        div()
                            .flex()
                            .justify_end()
                            .gap(px(8.))
                            .px(px(24.))
                            .pt(px(16.))
                            .pb(px(24.))
                            .child(
                                btn_secondary("settings-cancel-confirm", "取消", p).on_click(
                                    cx.listener(|this, _, _, cx| {
                                        this.with_app(cx, |app, cx| {
                                            app.confirm = None;
                                            app.confirm_in_settings = false;
                                            cx.notify();
                                        });
                                        this.confirm_here = false;
                                        cx.notify();
                                    }),
                                ),
                            )
                            .child(
                                btn_danger("settings-apply-confirm", danger_label, p).on_click(
                                    cx.listener(|this, _, _, cx| {
                                        this.with_app(cx, |app, cx| {
                                            if matches!(app.confirm, Some(Confirm::ClearSync)) {
                                                app.clear_sync(cx);
                                                app.confirm = None;
                                                app.confirm_in_settings = false;
                                                cx.notify();
                                            } else {
                                                app.apply_confirm(cx);
                                            }
                                        });
                                        this.confirm_here = false;
                                        cx.notify();
                                    }),
                                ),
                            ),
                    ),
            )
            .into_any_element()
    }
}
