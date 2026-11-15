//! 主窗口（§9.1）：透明标题栏 + 自绘系统样式窗口按钮（右上），左侧浮层侧边栏，
//! 工具栏与标题栏合一，内容区随页面切换。

use gpui::{prelude::*, *};
use gpui_component::ThemeMode;

use super::{
    actions::{Assets, Close, New, Overview, Settings, Subscriptions},
    dialogs,
    state::{BannerKind, SnowFold},
    widgets::{M, SB, btn_secondary, circle_button, t},
};
use crate::{
    platform,
    theme::{Palette, fade},
};

/// Windows 系统窗口按钮区宽（46×32 × 3）。
#[cfg(windows)]
const WINDOW_CONTROLS_WIDTH: f32 = 138.;
#[cfg(not(windows))]
const WINDOW_CONTROLS_WIDTH: f32 = 0.;

impl Render for SnowFold {
    fn render(&mut self, window: &mut Window, cx: &mut gpui::Context<Self>) -> impl IntoElement {
        let dark = gpui_component::Theme::global(cx).mode == ThemeMode::Dark;
        let mut p = Palette::new(dark);
        p.opaque_window = !platform::backdrop_enabled(cx);
        self.last_content_height = f32::from(window.viewport_size().height) - 52.;
        self.last_content_width = f32::from(window.viewport_size().width) - 236. - 64.;
        let (sums, _, _, _, _) = super::overview::category_sums(&self.data);
        self.sums_cached = sums;
        let failed_startup = self.startup_error.is_some();

        // —— 侧边栏（浮层材质，距上、左、下各 8，宽 220，圆角 18）——
        let sidebar = div()
            .absolute()
            .left(px(8.))
            .top(px(8.))
            .bottom(px(8.))
            .w(px(220.))
            .flex()
            .flex_col()
            .rounded(px(18.))
            .bg(p.material)
            .border_1()
            .border_color(p.material_edge)
            .when(failed_startup, |s| s.opacity(0.55))
            .child(
                // 顶部 44px 空（Windows 不画红绿灯）；按住可拖动窗口。
                div()
                    .h(px(44.))
                    .flex_none()
                    .on_mouse_down(MouseButton::Left, |_, window, _| window.start_window_move()),
            )
            .child(self.sidebar_items(&p, cx));

        // —— 工具栏（高 52，与标题栏合一；左标题、右同步/＋与窗口按钮）——
        let page_title: SharedString = if failed_startup {
            "SnowFold".into()
        } else {
            ["总览", "资产与负债", "订阅"][self.page.min(2)].into()
        };
        let mut right_buttons = div().flex().items_center().gap(px(8.));
        if !failed_startup {
            if self.sync.status.as_ref().is_some_and(|s| s.enabled) {
                right_buttons = right_buttons.child(self.sync_button(&p, cx));
            }
            let new_label = if self.page == 2 {
                "新建订阅"
            } else {
                "添加资产"
            };
            right_buttons = right_buttons.child(
                circle_button("toolbar-new", "icons/line/plus.svg", p.text, &p)
                    .map(|b| {
                        b.tooltip(move |_, cx| {
                            cx.new(|_| super::widgets::Hint {
                                text: new_label.into(),
                                dark,
                            })
                            .into()
                        })
                    })
                    .on_click(cx.listener(|this, _, window, cx| this.new_current(window, cx))),
            );
        }
        let toolbar = div()
            .debug_selector(|| "toolbar".to_string())
            .absolute()
            .left(px(0.))
            .top(px(0.))
            .right(px(0.))
            .h(px(52.))
            .flex_none()
            .child(
                // 拖动区：标题 + 空白（窗口按钮区除外）。
                div()
                    .window_control_area(WindowControlArea::Drag)
                    .absolute()
                    .left(px(236.))
                    .top(px(0.))
                    .bottom(px(0.))
                    // 避开右侧按钮区（＋/同步 72 宽 + 边距），否则系统拖动区会吞掉按钮点击。
                    .right(px(WINDOW_CONTROLS_WIDTH + 88.))
                    .on_mouse_down(MouseButton::Left, |_, window, _| window.start_window_move())
                    .child(
                        div()
                            .debug_selector(|| "toolbar-title".to_string())
                            .pl(px(24.))
                            .pt(px(15.))
                            .text_size(px(15.))
                            .line_height(px(20.))
                            .child(t(page_title, p.text, SB)),
                    ),
            )
            .child(
                div()
                    .absolute()
                    .right(px(WINDOW_CONTROLS_WIDTH + 16.))
                    .top(px(10.))
                    .child(right_buttons),
            )
            .when(cfg!(windows), |bar| {
                bar.child(
                    div()
                        .absolute()
                        .top(px(0.))
                        .right(px(0.))
                        .child(self.window_controls(&p)),
                )
            });

        // —— 内容区 ——
        let centered = failed_startup
            || if self.page == 2 {
                self.data.subscriptions.is_empty()
            } else {
                self.data.assets.is_empty()
            };
        let content = div()
            .id("content")
            .absolute()
            .left(px(236.))
            .right(px(0.))
            .top(px(52.))
            .bottom(px(0.))
            .overflow_y_scroll()
            .track_scroll(&self.scroll)
            .on_scroll_wheel(cx.listener(|this, _, _, cx| {
                if this.category_menu_open {
                    this.category_menu_open = false;
                    cx.notify();
                }
            }))
            .child({
                let mut inner = div().flex().flex_col();
                if let Some(error) = &self.startup_error {
                    inner = inner.child(self.render_startup_error(error, &p));
                } else if self.storage.is_some() {
                    inner = inner.child(match self.page {
                        0 => {
                            let mut body = div().flex().flex_col();
                            if self.key_unavailable {
                                body = body.child(self.key_unavailable_card(&p, cx).mb(px(24.)));
                            }
                            body = body.child(self.render_overview(&p, cx));
                            body
                        }
                        1 => self.render_assets(&p, cx),
                        _ => self.render_subscriptions(&p, cx),
                    });
                }
                div()
                    .px(px(32.))
                    .when(!centered, |s| s.pt(px(8.)).pb(px(48.)).min_h_full())
                    .when(centered, |s| {
                        // 设计稿整页空态：内容区内居中，底部留内容宽度的 8%。
                        s.h(px(self.last_content_height))
                            .pb(px((self.last_content_width + 64.) * 0.08))
                            .justify_center()
                    })
                    .flex()
                    .flex_col()
                    .child(inner)
            });

        let mut root = div()
            .relative()
            .size_full()
            .overflow_hidden()
            .when(p.opaque_window, |s| s.bg(p.bg))
            .when(!p.opaque_window, |s| s.bg(fade(p.bg, 0.45)))
            .text_color(p.text)
            .text_size(px(13.))
            .font_family(crate::theme::FONT_LATIN)
            .track_focus(&self.focus)
            .on_action(cx.listener(|this, _: &Overview, window, cx| this.select(0, window, cx)))
            .on_action(cx.listener(|this, _: &Assets, window, cx| this.select(1, window, cx)))
            .on_action(
                cx.listener(|this, _: &Subscriptions, window, cx| this.select(2, window, cx)),
            )
            .on_action(cx.listener(|this, _: &Close, window, cx| this.handle_escape(window, cx)))
            .on_action(
                cx.listener(|this, _: &Settings, window, cx| this.toggle_settings(window, cx)),
            )
            .on_action(cx.listener(|this, _: &New, window, cx| this.new_current(window, cx)))
            .child(sidebar)
            .child(toolbar)
            .child(content);

        // —— 浮层 ——
        if self.confirm.is_some() {
            root = root.child(dialogs::render_confirm(self, &p, cx));
        }
        if self.dialog.is_some() {
            root = root.child(self.render_dialog(window, &p, cx));
        }
        if let Some(banner) = self.render_banner(&p, cx) {
            root = root.child(banner);
        }
        if self.undo.is_some() {
            root = root.child(self.render_undo_banner(&p, cx));
        }
        root
    }
}

impl SnowFold {
    fn handle_escape(&mut self, window: &mut Window, cx: &mut gpui::Context<Self>) {
        if self.category_menu_open {
            self.category_menu_open = false;
            cx.notify();
            return;
        }
        if self.dialog.is_some() {
            self.cancel_dialog(window, cx);
            return;
        }
        if self.confirm.is_some() {
            self.close_confirm(window, cx);
            return;
        }
    }

    /// 侧边栏导航项（§9.1）：行高 32、左右 10、图标 20 间距 10；设置固定左下。
    fn sidebar_items(&self, p: &Palette, cx: &mut gpui::Context<Self>) -> Div {
        let items: [(&str, &'static str, &'static str, &'static str, usize); 3] = [
            (
                "nav-overview",
                "icons/line/pie-chart.svg",
                "icons/fill/pie-chart.svg",
                "总览",
                0,
            ),
            (
                "nav-assets",
                "icons/line/wallet.svg",
                "icons/fill/wallet.svg",
                "资产",
                1,
            ),
            (
                "nav-subscriptions",
                "icons/line/calendar-repeat.svg",
                "icons/fill/calendar-repeat.svg",
                "订阅",
                2,
            ),
        ];
        let mut nav = div()
            .flex_1()
            .min_h(px(0.))
            .flex()
            .flex_col()
            .gap(px(2.))
            .px(px(12.));
        for (id, line, fill, label, page) in items {
            let active = !self.startup_error.is_some() && self.page == page;
            nav = nav.child(
                div()
                    .debug_selector(move || id.to_string())
                    .id(id)
                    .tab_index(0)
                    .tab_stop(true)
                    .flex()
                    .items_center()
                    .gap(px(10.))
                    .h(px(32.))
                    .px(px(10.))
                    .rounded(px(10.))
                    .text_size(px(13.))
                    .cursor_pointer()
                    .when(active, |s| s.bg(p.brand_soft))
                    .hover(move |s| if active { s } else { s.bg(p.hover()) })
                    .focus(move |s| s.border_3().border_color(fade(p.brand, 0.4)))
                    .on_click(cx.listener(move |this, _, window, cx| this.select(page, window, cx)))
                    .child(
                        svg()
                            .path(if active { fill } else { line })
                            .size(px(20.))
                            .text_color(if active { p.brand } else { p.text2 }),
                    )
                    .child(t(label, if active { p.brand } else { p.text }, M)),
            );
        }
        let settings_active = false;
        nav.child(div().flex_1()).child(
            div()
                .debug_selector(|| "nav-settings".to_string())
                .id("nav-settings")
                .tab_index(0)
                .tab_stop(true)
                .flex()
                .items_center()
                .gap(px(10.))
                .h(px(32.))
                .px(px(10.))
                .mb(px(12.))
                .rounded(px(10.))
                .text_size(px(13.))
                .cursor_pointer()
                .when(settings_active, |s| s.bg(p.brand_soft))
                .hover(move |s| s.bg(p.hover()))
                .focus(move |s| s.border_3().border_color(fade(p.brand, 0.4)))
                .on_click(cx.listener(|this, _, window, cx| this.toggle_settings(window, cx)))
                .child(
                    svg()
                        .path("icons/line/settings.svg")
                        .size(px(20.))
                        .text_color(p.text2),
                )
                .child(t("设置", p.text, M)),
        )
    }

    /// Windows 系统样式窗口按钮：46×32，最小化/最大化/关闭（右上，悬停 4% 主文字色，
    /// 关闭悬停 #C42B1C 白字）。系统命中区域使悬停预览与点击走系统行为。
    fn window_controls(&self, p: &Palette) -> Div {
        let hover = p.hover();
        let min = div()
            .id("win-min")
            .flex()
            .items_center()
            .justify_center()
            .w(px(46.))
            .h(px(32.))
            .window_control_area(WindowControlArea::Min)
            .cursor_pointer()
            .hover(move |s| s.bg(hover))
            .on_click(|_, window, _| window.minimize_window())
            .child(div().w(px(10.)).h(px(1.)).bg(p.text));
        let max = div()
            .id("win-max")
            .flex()
            .items_center()
            .justify_center()
            .w(px(46.))
            .h(px(32.))
            .window_control_area(WindowControlArea::Max)
            .cursor_pointer()
            .hover(move |s| s.bg(hover))
            .on_click(|_, window, _| window.zoom_window())
            .child(
                div()
                    .w(px(10.))
                    .h(px(10.))
                    .rounded(px(2.))
                    .border_1()
                    .border_color(p.text),
            );
        let close = div()
            .id("win-close")
            .flex()
            .items_center()
            .justify_center()
            .w(px(46.))
            .h(px(32.))
            .window_control_area(WindowControlArea::Close)
            .cursor_pointer()
            .hover(move |s| s.bg(rgb(0xC42B1C)).text_color(rgb(0xFFFFFF)))
            .on_click(|_, window, _| window.remove_window())
            .child(
                svg()
                    .path("icons/line/close.svg")
                    .size(px(10.))
                    .text_color(p.text),
            );
        div().flex().flex_none().child(min).child(max).child(close)
    }

    /// 启动失败错误页（§9.7）。
    fn render_startup_error(&self, error: &str, p: &Palette) -> Div {
        div()
            .flex()
            .flex_col()
            .items_center()
            .justify_center()
            .gap(px(16.))
            .child(
                div()
                    .flex()
                    .items_center()
                    .justify_center()
                    .size(px(88.))
                    .rounded_full()
                    .bg(p.neg_soft)
                    .child(
                        svg()
                            .path("icons/line/warning-triangle.svg")
                            .size(px(48.))
                            .text_color(p.neg),
                    ),
            )
            .child(div().text_size(px(15.)).line_height(px(20.)).child(t(
                "无法打开数据",
                p.text,
                SB,
            )))
            .child(
                div()
                    .max_w(px(420.))
                    .text_center()
                    .text_size(px(13.))
                    .line_height(px(18.))
                    .child(t(error.to_string(), p.text2, FontWeight::NORMAL)),
            )
    }

    /// 密钥不可用警告卡（§9.7）。
    fn key_unavailable_card(&self, p: &Palette, cx: &mut gpui::Context<Self>) -> Div {
        div()
            .flex()
            .items_center()
            .gap(px(12.))
            .px(px(16.))
            .py(px(12.))
            .rounded(px(16.))
            .bg(p.warn_soft)
            .child(
                svg()
                    .path("icons/line/key.svg")
                    .size(px(20.))
                    .text_color(p.warn),
            )
            .child(
                div()
                    .flex()
                    .flex_col()
                    .min_w(px(0.))
                    .child(t("同步密钥不可用", p.text, M))
                    .child(
                        div()
                            .text_size(px(11.))
                            .line_height(px(14.))
                            .child(t(
                                "请检查密钥文件或导入恢复码。加密备份暂时无法导出或导入；可以在设置里导入恢复码，或处理密钥文件后重启。",
                                p.text2,
                                FontWeight::NORMAL,
                            )),
                    ),
            )
            .child(div().flex_1())
            .child(
                btn_secondary("import-recovery-inline", "导入恢复码", p).on_click(cx.listener(
                    |this, _, _, cx| {
                        this.page = 0;
                        this.import_recovery_code(cx);
                    },
                )),
            )
    }

    /// 通知横幅（§5.8）：右下 24/40，成功 4 秒自动消失、错误手动关闭。
    fn render_banner(&self, p: &Palette, cx: &mut gpui::Context<Self>) -> Option<AnyElement> {
        let banner = self.banner.as_ref()?;
        let (icon, icon_color) = match banner.kind {
            BannerKind::Info => ("icons/line/check.svg", p.pos),
            BannerKind::Error => ("icons/line/warning-triangle.svg", p.neg),
        };
        Some(
            div()
                .absolute()
                .bottom(px(24.))
                .right(px(40.))
                .max_w(px(560.))
                .flex()
                .items_center()
                .gap(px(12.))
                .px(px(16.))
                .py(px(10.))
                .rounded(px(22.))
                .bg(p.material)
                .border_1()
                .border_color(p.material_edge)
                .shadow(vec![super::widgets::floating_shadow(p.dark)])
                .child(svg().path(icon).size(px(16.)).text_color(icon_color))
                .child(
                    div()
                        .min_w(px(0.))
                        .text_size(px(13.))
                        .line_height(px(18.))
                        .child(t(banner.text.clone(), p.text, FontWeight::NORMAL)),
                )
                .child(
                    div()
                        .id("dismiss-banner")
                        .tab_index(0)
                        .tab_stop(true)
                        .flex()
                        .items_center()
                        .justify_center()
                        .size(px(24.))
                        .rounded_full()
                        .cursor_pointer()
                        .hover(move |s| s.bg(p.hover()))
                        .on_click(cx.listener(|this, _, _, cx| {
                            this.banner = None;
                            cx.notify();
                        }))
                        .child(
                            svg()
                                .path("icons/line/close.svg")
                                .size(px(16.))
                                .text_color(p.text2),
                        ),
                )
                .into_any_element(),
        )
    }

    /// 撤销横幅（§5.8）：底部居中、6 秒内可撤销、不画倒计时条。
    fn render_undo_banner(&self, p: &Palette, cx: &mut gpui::Context<Self>) -> AnyElement {
        let Some((_, when, label)) = &self.undo else {
            return div().into_any_element();
        };
        if when.elapsed() > std::time::Duration::from_secs(6) {
            return div().into_any_element();
        }
        let label = label.clone();
        div()
            .absolute()
            .bottom(px(24.))
            .left(px(40.))
            .right(px(40.))
            .flex()
            .justify_center()
            .child(
                div()
                    .debug_selector(|| "undo-banner".to_string())
                    .flex()
                    .items_center()
                    .gap(px(12.))
                    .px(px(16.))
                    .py(px(10.))
                    .rounded(px(22.))
                    .bg(p.material)
                    .border_1()
                    .border_color(p.material_edge)
                    .shadow(vec![super::widgets::floating_shadow(p.dark)])
                    .child(
                        svg()
                            .path("icons/line/trash.svg")
                            .size(px(16.))
                            .text_color(p.text2),
                    )
                    .child(t(label, p.text, FontWeight::NORMAL))
                    .child(
                        div()
                            .debug_selector(|| "撤销 · 6秒内".to_string())
                            .id("undo-button")
                            .tab_index(0)
                            .tab_stop(true)
                            .flex()
                            .items_center()
                            .gap(px(6.))
                            .h(px(28.))
                            .px(px(8.))
                            .rounded(px(8.))
                            .cursor_pointer()
                            .text_size(px(13.))
                            .hover(move |s| s.bg(p.hover()))
                            .focus(move |s| s.border_3().border_color(fade(p.brand, 0.4)))
                            .on_click(cx.listener(|this, _, window, cx| this.undo(window, cx)))
                            .child(
                                svg()
                                    .path("icons/line/undo.svg")
                                    .size(px(16.))
                                    .text_color(p.brand),
                            )
                            .child(t("撤销 · 6秒内", p.brand, M)),
                    ),
            )
            .into_any_element()
    }
}
