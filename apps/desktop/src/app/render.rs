use super::{
    actions::{Assets, Close, New, Overview, Settings, Subscriptions},
    components::Palette,
    state::{Confirm, Editing, SnowFold},
    subscriptions::cycle_label,
};
use crate::storage::Theme;
use gpui::{prelude::*, *};
use gpui_component::{Icon, IconName, ThemeMode, date_picker::DatePicker, input::Input};
use std::time::Duration;

impl Render for SnowFold {
    fn render(&mut self, window: &mut Window, cx: &mut gpui::Context<Self>) -> impl IntoElement {
        // 设置面板的可滚动内容区：随窗口高度伸缩，既能在最小 720 高窗口
        // 完整放下标题与底部按钮，也能在大窗口里少滚动。
        let settings_body_max = (f32::from(window.viewport_size().height) * 0.62).clamp(360., 780.);
        let mut p = Palette::new(gpui_component::Theme::global(cx).mode == ThemeMode::Dark);
        p.keyboard = !(self.settings
            || self.confirm.is_some()
            || self.editing == Some(Editing::NewSubscription));
        let mut tabs = div()
            .flex()
            .items_center()
            .gap(px(2.))
            .p(px(3.))
            .rounded(px(10.))
            .bg(p.tabs_bg);
        for (page, label) in ["总览", "资产", "订阅"].iter().enumerate() {
            let active = self.page == page;
            tabs = tabs.child(
                div()
                    .debug_selector(move || format!("tab{page}"))
                    .id(("tab", page))
                    .tab_index(0)
                    .tab_stop(p.keyboard)
                    .flex()
                    .items_center()
                    .justify_center()
                    .h(px(29.))
                    .min_w(px(64.))
                    .px(px(15.))
                    .rounded(px(7.))
                    .text_size(px(13.))
                    .font_weight(FontWeight::MEDIUM)
                    .text_color(if active { p.text } else { p.muted })
                    .when(active, |tab| tab.bg(p.active_tab))
                    .hover(move |tab| if active { tab } else { tab.text_color(p.text) })
                    .on_click(cx.listener(move |this, _, window, cx| this.select(page, window, cx)))
                    .child(*label),
            );
        }
        let settings_button = div()
            .debug_selector(|| "settings".to_string())
            .id("settings")
            .tab_index(0)
            .tab_stop(p.keyboard)
            .flex()
            .items_center()
            .justify_center()
            .size(px(30.))
            .rounded(px(8.))
            .border_1()
            .border_color(transparent_black())
            .focus(move |s| s.border_color(p.accent))
            .cursor_pointer()
            .hover(move |s| s.bg(p.subtle))
            .on_click(cx.listener(|this, _, window, cx| this.toggle_settings(window, cx)))
            .child(
                svg()
                    .path("icons/sliders.svg")
                    .w(px(16.))
                    .h(px(16.))
                    .text_color(p.muted),
            );
        let mut content = div()
            .flex()
            .flex_col()
            .w_full()
            .max_w(px(1040.))
            .mx_auto()
            .px(px(44.))
            .pt(px(40.))
            .pb(px(100.));
        if let Some(error) = &self.error {
            content = content.child(p.panel().text_color(rgb(0xae4a3c)).child(error.clone()));
        }
        if self.storage.is_some() {
            content = content.child(match self.page {
                0 => self.render_overview(p, cx),
                1 => self.render_assets(p, cx),
                _ => self.render_subscriptions(p, cx),
            });
        }
        let mut root = div()
            .relative()
            .size_full()
            .flex()
            .flex_col()
            .bg(p.canvas)
            .text_color(p.text)
            .text_size(px(14.))
            .track_focus(&self.focus)
            .on_mouse_down(
                MouseButton::Left,
                cx.listener(|this, _, _, cx| {
                    if this.popover.take().is_some() {
                        cx.notify();
                    }
                }),
            )
            .on_action(cx.listener(|this, _: &Overview, window, cx| this.select(0, window, cx)))
            .on_action(cx.listener(|this, _: &Assets, window, cx| this.select(1, window, cx)))
            .on_action(
                cx.listener(|this, _: &Subscriptions, window, cx| this.select(2, window, cx)),
            )
            .on_action(cx.listener(|this, _: &Close, window, cx| this.close(window, cx)))
            .on_action(cx.listener(|this, _: &Settings, window, cx| {
                this.toggle_settings(window, cx);
            }))
            .on_action(cx.listener(|this, _: &New, window, cx| this.new_current(window, cx)))
            .child(
                div()
                    .relative()
                    .h(px(56.))
                    .flex_none()
                    .child(
                        div()
                            .debug_selector(|| "brand".to_string())
                            .id("brand")
                            .absolute()
                            .left(px(88.))
                            .top(px(17.))
                            .flex()
                            .items_center()
                            .gap(px(8.))
                            .on_mouse_down(MouseButton::Left, |_, window, _| {
                                window.start_window_move()
                            })
                            .child(
                                Icon::default()
                                    .path("icons/mark.svg")
                                    .size(px(22.))
                                    .text_color(p.mark_b),
                            )
                            .child(
                                div()
                                    .text_size(px(13.))
                                    .font_weight(FontWeight::SEMIBOLD)
                                    .child("SnowFold"),
                            ),
                    )
                    .child(
                        div()
                            .absolute()
                            .left(px(0.))
                            .right(px(0.))
                            .top(px(10.5))
                            .flex()
                            .justify_center()
                            .child(tabs),
                    )
                    .child(
                        div()
                            .absolute()
                            .right(px(14.))
                            .top(px(13.))
                            .child(settings_button),
                    ),
            )
            .child(
                div()
                    .id("content")
                    .relative()
                    .flex_1()
                    .overflow_y_scroll()
                    .track_scroll(&self.scroll)
                    .on_scroll_wheel(cx.listener(|this, _, _, cx| {
                        if this.popover.take().is_some() {
                            cx.notify();
                        }
                    }))
                    .child(content),
            );
        if let Some((_, when, label)) = &self.undo {
            if when.elapsed() < Duration::from_secs(6) {
                root = root.child(
                    div()
                        .absolute()
                        .bottom(px(24.))
                        .left(px(40.))
                        .right(px(40.))
                        .flex()
                        .justify_center()
                        .child(
                            p.panel()
                                .flex_row()
                                .items_center()
                                .shadow_lg()
                                .child(label.clone())
                                .child(p.button("undo", "撤销 · 6秒内").on_click(
                                    cx.listener(|this, _, window, cx| this.undo(window, cx)),
                                )),
                        ),
                );
            }
        } else if let Some(notice) = &self.notice {
            root = root.child(
                div().absolute().bottom(px(24.)).right(px(40.)).child(
                    p.panel()
                        .flex_row()
                        .items_center()
                        .shadow_lg()
                        .child(notice.clone())
                        .child(p.button("dismiss-notice", "×").on_click(cx.listener(
                            |this, _, _, cx| {
                                this.notice = None;
                                cx.notify();
                            },
                        ))),
                ),
            );
        }
        p.keyboard = self.confirm.is_none();
        if self.settings {
            let q = Palette {
                keyboard: true,
                ..p
            };
            let mut themes = div()
                .flex()
                .gap(px(2.))
                .p(px(3.))
                .rounded(px(10.))
                .border_1()
                .border_color(p.line)
                .bg(p.subtle);
            for (theme, label) in [
                (Theme::System, "自动"),
                (Theme::Light, "浅色"),
                (Theme::Dark, "深色"),
            ] {
                themes = themes.child(
                    q.segment(theme.code(), label, self.theme == theme)
                        .on_click(cx.listener(move |this, _, window, cx| {
                            this.set_theme(theme, window, cx)
                        })),
                );
            }
            root = root.child(
                div()
                    .absolute()
                    .inset_0()
                    .bg(if p.dark {
                        rgba(0x00000080)
                    } else {
                        rgba(0x1c1c1947)
                    })
                    .flex()
                    .justify_center()
                    .items_center()
                    .on_mouse_down(
                        MouseButton::Left,
                        cx.listener(|this, _, window, cx| this.close(window, cx)),
                    )
                    .child(
                        div()
                            .debug_selector(|| "settings-sheet".to_string())
                            .w(px(472.))
                            .flex()
                            .flex_col()
                            .bg(p.card)
                            .border_1()
                            .border_color(p.line)
                            .rounded(px(22.))
                            .overflow_hidden()
                            .shadow_lg()
                            .track_focus(&self.modal_focus)
                            .tab_index(0)
                            .tab_stop(false)
                            .on_mouse_down(MouseButton::Left, |_, _, cx| cx.stop_propagation())
                            .child(
                                div()
                                    .flex()
                                    .items_center()
                                    .justify_between()
                                    .px(px(20.))
                                    .pt(px(18.))
                                    .pb(px(14.))
                                    .child(
                                        div()
                                            .text_size(px(15.))
                                            .font_weight(FontWeight::SEMIBOLD)
                                            .child("设置"),
                                    )
                                    .child(
                                        div()
                                            .debug_selector(|| "关闭".to_string())
                                            .id("close-settings")
                                            .tab_index(0)
                                            .tab_stop(q.keyboard)
                                            .flex()
                                            .items_center()
                                            .justify_center()
                                            .size(px(26.))
                                            .rounded(px(7.))
                                            .cursor_pointer()
                                            .hover(move |s| s.bg(p.subtle))
                                            .on_click(cx.listener(|this, _, window, cx| {
                                                this.close(window, cx)
                                            }))
                                            .child(
                                                Icon::new(IconName::Close)
                                                    .size_3p5()
                                                    .text_color(p.ink3),
                                            ),
                                    ),
                            )
                            .when_some(self.error.clone(), |sheet, e| {
                                sheet.child(
                                    div()
                                        .px(px(20.))
                                        .pb(px(8.))
                                        .text_size(px(12.))
                                        .text_color(p.neg)
                                        .child(e),
                                )
                            })
                            .child(
                                div()
                                    .id("settings-body")
                                    .flex()
                                    .flex_col()
                                    .px(px(20.))
                                    .pb(px(8.))
                                    // 内容多于最小窗口高度时滚动，保证 720 高窗口也
                                    // 能完整看到「完成」按钮和所有设置分组。
                                    .max_h(px(settings_body_max))
                                    .overflow_y_scroll()
                                    .child(q.sheet_group(
                                        true,
                                        "外观",
                                        vec![q.sheet_row(
                                            "主题",
                                            "跟随系统会自动匹配电脑的深浅色。",
                                            themes,
                                        )],
                                    ))
                                    .child(q.sheet_group(
                                        false,
                                        "数据",
                                        vec![
                                            q.sheet_row(
                                                "导出备份",
                                                "保存为 JSON 文件，可随时导入恢复。",
                                                q.action_soft("export", "导出").on_click(
                                                    cx.listener(|this, _, _, cx| this.export(cx)),
                                                ),
                                            ),
                                            q.sheet_row(
                                                "导入备份",
                                                "会覆盖当前所有记录，请先导出备份。",
                                                q.action_soft("import", "导入").on_click(
                                                    cx.listener(|this, _, _, cx| this.import(cx)),
                                                ),
                                            ),
                                            q.sheet_row(
                                                "清空全部数据",
                                                "删除本机上的全部资产与订阅记录。",
                                                q.action_danger("clear", "清空").on_click(
                                                    cx.listener(|this, _, window, cx| {
                                                        this.modal_focus.focus(window);
                                                        this.confirm = Some(Confirm::Clear);
                                                        cx.notify();
                                                    }),
                                                ),
                                            ),
                                        ],
                                    ))
                                    .child(q.sheet_group(
                                        false,
                                        "加密备份",
                                        vec![
                                            q.sheet_row(
                                                "加密备份",
                                                "导出 .sfseal 密文，可安全上传网盘或发给家人。",
                                                q.action_soft("export-sealed", "导出密文").on_click(
                                                    cx.listener(|this, _, _, cx| {
                                                        this.export_sealed(cx)
                                                    }),
                                                ),
                                            ),
                                            q.sheet_row(
                                                "恢复加密备份",
                                                "导入密文并覆盖当前数据；需要本机同步密钥。",
                                                q.action_soft("import-sealed", "导入密文").on_click(
                                                    cx.listener(|this, _, _, cx| {
                                                        this.import_sealed(cx)
                                                    }),
                                                ),
                                            ),
                                            #[cfg(windows)]
                                            q.sheet_line("密钥位置", "密钥由 Windows 账户凭据保护"),
                                            q.sheet_note(
                                                "同步密钥指纹",
                                                self.key_fingerprint
                                                    .clone()
                                                    .unwrap_or_else(|| "尚未生成".into()),
                                            ),
                                            q.sheet_row(
                                                "同步密钥恢复码",
                                                "换机或重装后恢复加密备份；请离线保存，不要放云盘同步目录。",
                                                div()
                                                    .flex()
                                                    .gap(px(8.))
                                                    .child(
                                                        q.action_soft(
                                                            "export-recovery",
                                                            "导出恢复码",
                                                        )
                                                        .on_click(cx.listener(
                                                            |this, _, _, cx| {
                                                                this.export_recovery_code(cx)
                                                            },
                                                        )),
                                                    )
                                                    .child(
                                                        q.action_soft(
                                                            "import-recovery",
                                                            "导入恢复码",
                                                        )
                                                        .on_click(cx.listener(
                                                            |this, _, _, cx| {
                                                                this.import_recovery_code(cx)
                                                            },
                                                        )),
                                                    ),
                                            ),
                                        ],
                                    ))
                                    .child(q.sheet_group(
                                        false,
                                        "关于",
                                        vec![q.sheet_line(
                                            "SnowFold · MVP 0.1",
                                            "离线运行 · 无网络请求",
                                        )],
                                    )),
                            )
                            .child(q.sheet_foot(vec![
                                q.action_primary("close-settings-done", "完成")
                                    .on_click(cx.listener(|this, _, window, cx| {
                                        this.close(window, cx)
                                    }))
                                    .into_any_element(),
                            ])),
                    ),
            );
        }
        p.keyboard = true;
        if self.editing == Some(Editing::NewSubscription) {
            let q = Palette {
                keyboard: true,
                ..p
            };
            root = root.child(
                div()
                    .absolute()
                    .inset_0()
                    .bg(if p.dark {
                        rgba(0x00000080)
                    } else {
                        rgba(0x1c1c1947)
                    })
                    .flex()
                    .justify_center()
                    .items_center()
                    .on_mouse_down(
                        MouseButton::Left,
                        cx.listener(|this, _, window, cx| this.close(window, cx)),
                    )
                    .child(
                        div()
                            .debug_selector(|| "subscription-sheet".to_string())
                            .w(px(472.))
                            .flex()
                            .flex_col()
                            .bg(p.card)
                            .border_1()
                            .border_color(p.line)
                            .rounded(px(22.))
                            .overflow_hidden()
                            .shadow_lg()
                            .on_mouse_down(MouseButton::Left, |_, _, cx| cx.stop_propagation())
                            .child(
                                div()
                                    .flex()
                                    .items_center()
                                    .justify_between()
                                    .px(px(20.))
                                    .pt(px(18.))
                                    .pb(px(14.))
                                    .child(
                                        div()
                                            .text_size(px(15.))
                                            .font_weight(FontWeight::SEMIBOLD)
                                            .child("新建订阅"),
                                    )
                                    .child(
                                        div()
                                            .debug_selector(|| "关闭".to_string())
                                            .id("close-subscription")
                                            .tab_index(0)
                                            .tab_stop(q.keyboard)
                                            .flex()
                                            .items_center()
                                            .justify_center()
                                            .size(px(26.))
                                            .rounded(px(7.))
                                            .cursor_pointer()
                                            .hover(move |s| s.bg(p.subtle))
                                            .on_click(cx.listener(|this, _, window, cx| {
                                                this.close(window, cx)
                                            }))
                                            .child(
                                                Icon::new(IconName::Close)
                                                    .size_3p5()
                                                    .text_color(p.ink3),
                                            ),
                                    ),
                            )
                            .when_some(self.error.clone(), |sheet, e| {
                                sheet.child(
                                    div()
                                        .px(px(20.))
                                        .pb(px(8.))
                                        .text_size(px(12.))
                                        .text_color(p.neg)
                                        .child(e),
                                )
                            })
                            .child(
                                div()
                                    .flex()
                                    .flex_col()
                                    .gap(px(14.))
                                    .px(px(20.))
                                    .pt(px(4.))
                                    .pb(px(18.))
                                    .child(q.field(
                                        "名称",
                                        Input::new(&self.name)
                                            .h(px(36.))
                                            .w_full()
                                            .rounded(px(9.))
                                            .text_size(px(13.5)),
                                    ))
                                    .child(
                                        div()
                                            .flex()
                                            .gap(px(12.))
                                            .child(q.field(
                                                "金额",
                                                Input::new(&self.amount)
                                                    .h(px(36.))
                                                    .w_full()
                                                    .rounded(px(9.))
                                                    .text_size(px(13.5)),
                                            ))
                                            .child(q.field(
                                                "币种",
                                                q.field_select(
                                                    "new-currency",
                                                    if self.currency == "CNY" {
                                                        "¥ 人民币"
                                                    } else {
                                                        "$ 美元"
                                                    },
                                                )
                                                .on_click(cx.listener(|this, _, _, cx| {
                                                    this.currency = if this.currency == "CNY" {
                                                        "USD".into()
                                                    } else {
                                                        "CNY".into()
                                                    };
                                                    cx.notify();
                                                })),
                                            )),
                                    )
                                    .child(
                                        div()
                                            .flex()
                                            .gap(px(12.))
                                            .child(q.field(
                                                "扣费周期",
                                                q.field_select("new-cycle", cycle_label(&self.cycle))
                                                    .on_click(cx.listener(|this, _, _, cx| {
                                                        let cycles =
                                                            ["weekly", "monthly", "quarterly", "yearly"];
                                                        let next = cycles
                                                            .iter()
                                                            .position(|c| *c == this.cycle)
                                                            .map(|i| cycles[(i + 1) % cycles.len()])
                                                            .unwrap_or("monthly");
                                                        this.cycle = next.into();
                                                        cx.notify();
                                                    })),
                                            ))
                                            .child(q.field(
                                                "下次扣费日",
                                                DatePicker::new(&self.date)
                                                    .h(px(36.))
                                                    .w_full()
                                                    .rounded(px(9.)),
                                            )),
                                    )
                                    .child(
                                        div()
                                            .text_size(px(11.5))
                                            .line_height(px(18.))
                                            .text_color(p.ink3)
                                            .child(
                                                "名称与金额必填。保存后按周期计算下次扣费；到期时进入「待确认」，点一下即可顺延。",
                                            ),
                                    ),
                            )
                            .child(q.sheet_foot(vec![
                                q.action("cancel-subscription", "取消")
                                    .on_click(cx.listener(|this, _, window, cx| {
                                        this.close(window, cx)
                                    }))
                                    .into_any_element(),
                                q.action_primary("save-subscription", "保存")
                                    .on_click(cx.listener(|this, _, window, cx| {
                                        this.save_edit(window, cx)
                                    }))
                                    .into_any_element(),
                            ])),
                    ),
            );
        }
        if let Some(confirm) = &self.confirm {
            let q = Palette {
                keyboard: true,
                ..p
            };
            let (title, description, confirm_label) = match confirm {
                Confirm::Clear => (
                    "清空全部数据？",
                    "将删除全部资产、历史与订阅，不能撤销。请先导出备份。".into(),
                    "确认覆盖",
                ),
                Confirm::Import(data) => (
                    "覆盖本机数据？",
                    format!(
                        "将导入 {} 项资产、{} 条历史、{} 项订阅，并覆盖现有数据。此操作不能撤销。",
                        data.assets.len(),
                        data.history.len(),
                        data.subscriptions.len()
                    ),
                    "确认覆盖",
                ),
                Confirm::ImportSealed { info, .. } => (
                    "覆盖本机数据？",
                    format!(
                        "加密备份导出于 {}（设备 {}），校验通过：{} 项资产、{} 条历史、{} 项订阅。\n确认后会覆盖现有数据，此操作不能撤销；如果导出时间比当前数据旧，请先取消并核对。",
                        info.exported_at,
                        info.device_id,
                        info.assets,
                        info.history,
                        info.subscriptions
                    ),
                    "确认覆盖",
                ),
                Confirm::ReplaceKey {
                    new_fingerprint,
                    old_fingerprint,
                    ..
                } => (
                    "替换本机同步密钥？",
                    format!(
                        "当前指纹 {}，新指纹 {}。\n{}只有确认这个恢复码来自你自己的设备时才继续。",
                        old_fingerprint.as_deref().unwrap_or("尚未生成"),
                        new_fingerprint,
                        // Windows 用 DPAPI 覆盖写入；Mac 开发版与测试的文件密钥仍留 .bak。
                        if cfg!(windows) {
                            "替换后旧密钥不会保留。请先导出当前恢复码，否则旧密钥导出的加密备份将无法在本机打开。"
                        } else {
                            "替换后，旧密钥导出的加密备份将无法在本机打开；旧密钥会备份为 snowfold-sync.key.bak。"
                        }
                    ),
                    "确认替换",
                ),
            };
            root = root.child(
                div()
                    .absolute()
                    .inset_0()
                    .bg(if p.dark {
                        rgba(0x00000099)
                    } else {
                        rgba(0x1c1c1952)
                    })
                    .flex()
                    .justify_center()
                    .items_center()
                    .child(
                        div()
                            .debug_selector(|| "confirm-sheet".to_string())
                            .w(px(400.))
                            .flex()
                            .flex_col()
                            .bg(p.card)
                            .border_1()
                            .border_color(p.line)
                            .rounded(px(18.))
                            .overflow_hidden()
                            .shadow_lg()
                            .track_focus(&self.modal_focus)
                            .tab_index(0)
                            .tab_stop(false)
                            .child(
                                div()
                                    .flex()
                                    .flex_col()
                                    .gap(px(8.))
                                    .px(px(20.))
                                    .pt(px(18.))
                                    .pb(px(18.))
                                    .child(
                                        div()
                                            .text_size(px(15.))
                                            .font_weight(FontWeight::SEMIBOLD)
                                            .child(title),
                                    )
                                    .child(
                                        div()
                                            .text_size(px(12.5))
                                            .line_height(px(19.))
                                            .text_color(p.muted)
                                            .child(description),
                                    )
                                    .when_some(self.error.clone(), |sheet, e| {
                                        sheet.child(
                                            div().text_size(px(12.)).text_color(p.neg).child(e),
                                        )
                                    }),
                            )
                            .child(q.sheet_foot(vec![
                                q.action("cancel-confirm", "取消")
                                    .on_click(cx.listener(|this, _, window, cx| {
                                        this.confirm = None;
                                        if this.settings {
                                            this.modal_focus.focus(window);
                                        } else {
                                            this.focus.focus(window);
                                        }
                                        cx.notify();
                                    }))
                                    .into_any_element(),
                                q.action_danger("apply-confirm", confirm_label)
                                    .on_click(cx.listener(|this, _, window, cx| {
                                        this.confirm_action(window, cx)
                                    }))
                                    .into_any_element(),
                            ])),
                    ),
            );
        }
        root
    }
}
