//! 居中弹层与确认框（§5.6、§9.5）：左标签右控件，取消/保存靠右，
//! 回车保存（输入框 PressEnter）、Esc 取消，校验出错就地显示且不清空已输入内容。

use gpui::{prelude::*, *};
use gpui_component::calendar::Date;

use super::{
    state::{Confirm, Dialog, SnowFold},
    widgets::{M, btn_primary, btn_secondary, btn_text, dialog_shell, field_row, t},
};
use anyhow::{Context as _, bail};
use snowfold_core::model::{CATEGORIES, SubscriptionInput, decimal, parse_money, today};
use snowfold_core::storage::Undo;
use std::time::Duration;

/// 周期选项（模块级常量，便于闭包捕获）。
const CYCLES: [(&str, &str); 4] = [
    ("weekly", "每周"),
    ("monthly", "每月"),
    ("quarterly", "每季"),
    ("yearly", "每年"),
];

impl SnowFold {
    pub(super) fn begin_dialog(
        &mut self,
        dialog: Dialog,
        window: &mut Window,
        cx: &mut gpui::Context<Self>,
    ) {
        if self.confirm.is_some() {
            return;
        }
        self.name_error = false;
        self.amount_error = None;
        self.date_error = false;
        self.category_menu_open = false;
        let (name, amount) = match dialog {
            Dialog::RenameAsset(id) => (
                self.data
                    .assets
                    .iter()
                    .find(|a| a.id == id)
                    .map(|a| a.name.clone())
                    .unwrap_or_default(),
                String::new(),
            ),
            Dialog::AdjustBalance(id) => (
                String::new(),
                self.data
                    .assets
                    .iter()
                    .find(|a| a.id == id)
                    .map(|a| decimal(a.amount_cents))
                    .unwrap_or_default(),
            ),
            Dialog::CorrectHistory(id) => (
                String::new(),
                self.data
                    .history
                    .iter()
                    .find(|h| h.id == id)
                    .map(|h| decimal(h.amount_cents))
                    .unwrap_or_default(),
            ),
            Dialog::EditSubscription(id) => self
                .data
                .subscriptions
                .iter()
                .find(|s| s.id == id)
                .map(|s| (s.name.clone(), decimal(s.amount_cents)))
                .unwrap_or_default(),
            _ => (String::new(), String::new()),
        };
        self.name.update(cx, |s, cx| s.set_value(name, window, cx));
        self.amount
            .update(cx, |s, cx| s.set_value(amount, window, cx));
        match dialog {
            Dialog::AddAsset => {
                self.new_category = if self.page == 1 {
                    self.selected
                        .and_then(|id| {
                            self.data.assets.iter().find(|a| a.id == id).map(|a| {
                                CATEGORIES
                                    .iter()
                                    .position(|(code, _)| *code == a.category_code)
                                    .unwrap_or(0)
                            })
                        })
                        .unwrap_or(0)
                } else {
                    0
                };
            }
            Dialog::NewSubscription => {
                self.currency = "CNY".into();
                self.cycle = "monthly".into();
                self.date
                    .update(cx, |d, cx| d.set_date(today(), window, cx));
            }
            Dialog::EditSubscription(id) => {
                if let Some(s) = self.data.subscriptions.iter().find(|s| s.id == id) {
                    self.currency = s.currency.clone();
                    self.cycle = s.cycle.clone();
                    let next = s.next_charge_on;
                    self.date.update(cx, |d, cx| d.set_date(next, window, cx));
                }
            }
            _ => {}
        }
        self.dialog = Some(dialog);
        self.modal_focus.focus(window);
        match dialog {
            Dialog::AdjustBalance(_) | Dialog::CorrectHistory(_) => {
                self.amount.update(cx, |s, cx| s.focus(window, cx))
            }
            _ => self.name.update(cx, |s, cx| s.focus(window, cx)),
        }
        cx.notify();
    }
    pub(super) fn cancel_dialog(&mut self, window: &mut Window, cx: &mut gpui::Context<Self>) {
        self.dialog = None;
        self.name_error = false;
        self.amount_error = None;
        self.date_error = false;
        self.category_menu_open = false;
        self.focus.focus(window);
        cx.notify();
    }
    fn dialog_date(&self, cx: &gpui::App) -> Option<chrono::NaiveDate> {
        match self.date.read(cx).date() {
            Date::Single(Some(date)) => Some(date),
            _ => None,
        }
    }
    pub(super) fn save_dialog(&mut self, window: &mut Window, cx: &mut gpui::Context<Self>) {
        let Some(dialog) = self.dialog else {
            return;
        };
        self.name_error = false;
        self.amount_error = None;
        self.date_error = false;
        let name = self.name.read(cx).value().trim().to_string();
        let amount_text = self.amount.read(cx).value().to_string();
        let picked_date = self.dialog_date(cx);
        // 字段级校验一次性标记全部出错字段（§9.5：错误文字写在出错字段下）。
        let needs_name = matches!(
            dialog,
            Dialog::AddAsset
                | Dialog::RenameAsset(_)
                | Dialog::NewSubscription
                | Dialog::EditSubscription(_)
        );
        let is_subscription = matches!(
            dialog,
            Dialog::NewSubscription | Dialog::EditSubscription(_)
        );
        let mut invalid = false;
        if needs_name && name.is_empty() {
            self.name_error = true;
            invalid = true;
        }
        let parsed = parse_money(&amount_text);
        if let Err(e) = &parsed {
            self.amount_error = Some(e.to_string());
            invalid = true;
        }
        if is_subscription && picked_date.is_none() {
            self.date_error = true;
            invalid = true;
        }
        if invalid {
            // 与保存失败一致：焦点回到名称输入，弹层保持打开。
            self.name.update(cx, |s, cx| s.focus(window, cx));
            cx.notify();
            return;
        }
        let amount = parsed.unwrap();
        let date = picked_date;
        let result = (|| -> Result<Option<Undo>> {
            let storage = self.storage.as_mut().context("数据库不可用")?;
            match dialog {
                Dialog::AddAsset => {
                    let code = CATEGORIES[self.new_category.min(4)].0;
                    let id = storage.create_asset(code, &name, amount)?;
                    self.highlight = Some(id);
                }
                Dialog::RenameAsset(id) => {
                    storage.rename_asset(id, &name)?;
                }
                Dialog::AdjustBalance(id) => {
                    return storage.change_balance(id, amount);
                }
                Dialog::CorrectHistory(id) => {
                    storage.correct_history(id, amount)?;
                }
                Dialog::NewSubscription | Dialog::EditSubscription(_) => {
                    let date = date.expect("日期缺失已在校验阶段拦截");
                    if let Dialog::EditSubscription(id) = dialog {
                        let mut s = self
                            .data
                            .subscriptions
                            .iter()
                            .find(|s| s.id == id)
                            .context("订阅不存在")?
                            .clone();
                        s.name = name;
                        s.amount_cents = amount;
                        s.currency = self.currency.clone();
                        s.cycle = self.cycle.clone();
                        s.next_charge_on = date;
                        storage.update_subscription(id, &SubscriptionInput::from(&s))?;
                    } else {
                        let s = SubscriptionInput {
                            name: name.clone(),
                            amount_cents: amount,
                            currency: self.currency.clone(),
                            cycle: self.cycle.clone(),
                            next_charge_on: date,
                            status: "active".into(),
                        };
                        let id = storage.create_subscription(&s)?;
                        self.highlight = Some(id);
                    }
                }
            }
            Ok(None)
        })();
        match result {
            Ok(undo) => {
                self.sync_edited();
                self.dialog = None;
                self.category_menu_open = false;
                if let Some(undo) = undo {
                    self.offer_undo(undo, "已记录本次变动", cx);
                }
                if let Err(e) = self.reload() {
                    self.show_error(format!("已保存，但读取失败：{e:#}"), cx);
                }
                if matches!(dialog, Dialog::AddAsset | Dialog::NewSubscription) {
                    self.focus.focus(window);
                    let highlight = self.highlight;
                    cx.spawn(async move |this, cx| {
                        cx.background_executor().timer(Duration::from_secs(3)).await;
                        let _ = this.update(cx, |this, cx| {
                            if this.highlight == highlight {
                                this.highlight = None;
                                cx.notify();
                            }
                        });
                    })
                    .detach();
                } else {
                    self.focus.focus(window);
                }
            }
            Err(e) => {
                // 字段级错误标在对应字段下；核心读写失败走全局横幅。
                if !self.name_error && self.amount_error.is_none() && !self.date_error {
                    self.show_error(e.to_string(), cx);
                }
                self.name.update(cx, |s, cx| s.focus(window, cx));
            }
        }
        cx.notify();
    }

    /// 弹层渲染（render.rs 调用）。
    pub(super) fn render_dialog(
        &self,
        window: &gpui::Window,
        p: &crate::theme::Palette,
        cx: &mut gpui::Context<Self>,
    ) -> AnyElement {
        let Some(dialog) = self.dialog else {
            return div().into_any_element();
        };
        let (title, body, mut edit_actions): (
            String,
            Vec<AnyElement>,
            Vec<(String, bool)>, // (label, is_delete)
        ) = match dialog {
            Dialog::AddAsset => {
                let mut body = vec![self.category_field(p, cx)];
                body.push(self.field_text("名称", self.name_error, p, window, cx));
                body.push(self.field_amount(
                    "金额",
                    self.amount_error.is_some(),
                    self.amount_error.clone(),
                    p,
                    window,
                    cx,
                ));
                ("添加资产".into(), body, vec![])
            }
            Dialog::RenameAsset(id) => {
                let asset = self.data.assets.iter().find(|a| a.id == id);
                let _ = asset;
                (
                    "重命名".into(),
                    vec![self.field_text("名称", self.name_error, p, window, cx)],
                    vec![],
                )
            }
            Dialog::AdjustBalance(id) => {
                let asset = self.data.assets.iter().find(|a| a.id == id);
                let name = asset.map(|a| a.name.clone()).unwrap_or_default();
                let current = asset
                    .map(|a| snowfold_core::model::money(a.amount_cents, "CNY"))
                    .unwrap_or_default();
                (
                    "调整余额".into(),
                    vec![
                        self.readonly_row("资产", name, p),
                        self.readonly_row("当前余额", current, p),
                        self.field_amount(
                            "新余额",
                            self.amount_error.is_some(),
                            self.amount_error.clone(),
                            p,
                            window,
                            cx,
                        ),
                    ],
                    vec![],
                )
            }
            Dialog::CorrectHistory(id) => {
                let history = self.data.history.iter().find(|h| h.id == id);
                let time = history
                    .map(|h| super::asset_page::cn_time(&h.recorded_at))
                    .unwrap_or_default();
                let original = history
                    .map(|h| snowfold_core::model::money(h.amount_cents, "CNY"))
                    .unwrap_or_default();
                (
                    "修正历史".into(),
                    vec![
                        self.readonly_row("记录时间", time, p),
                        self.readonly_row("原金额", original, p),
                        self.field_amount(
                            "修正为",
                            self.amount_error.is_some(),
                            self.amount_error.clone(),
                            p,
                            window,
                            cx,
                        ),
                    ],
                    vec![],
                )
            }
            Dialog::NewSubscription => (
                "新建订阅".into(),
                self.subscription_fields(window, &p, cx),
                vec![],
            ),
            Dialog::EditSubscription(id) => {
                if let Some(s) = self.data.subscriptions.iter().find(|s| s.id == id) {
                    let paused = s.status != "active";
                    (
                        "编辑订阅".into(),
                        self.subscription_fields(window, &p, cx),
                        vec![
                            ("删除订阅".into(), true),
                            (if paused { "恢复" } else { "暂停" }.into(), false),
                        ],
                    )
                } else {
                    (
                        "编辑订阅".into(),
                        self.subscription_fields(window, &p, cx),
                        vec![],
                    )
                }
            }
        };
        let help = if matches!(
            dialog,
            Dialog::NewSubscription | Dialog::EditSubscription(_)
        ) {
            Some("填写名称、金额并选择下次扣费日。到期确认续费后按周期顺延。")
        } else if matches!(dialog, Dialog::CorrectHistory(_)) {
            Some("修正不会新增快照")
        } else {
            None
        };
        let mut body_rows = Vec::new();
        for row in body {
            body_rows.push(row);
        }
        let shell = dialog_shell("dialog", 480., p)
            .track_focus(&self.modal_focus)
            .tab_index(0)
            .tab_stop(false)
            .on_mouse_down(MouseButton::Left, |_, _, cx| cx.stop_propagation())
            .child(
                div()
                    .px(px(24.))
                    .pt(px(20.))
                    .text_size(px(15.))
                    .line_height(px(20.))
                    .child(t(title, p.text, crate::app::widgets::SB)),
            )
            .child(
                div()
                    .id("dialog-body")
                    .flex()
                    .flex_col()
                    .gap(px(16.))
                    .px(px(24.))
                    .pt(px(16.))
                    .children(body_rows)
                    .children(help.map(|h| {
                        // 与控件列左对齐（标签 88 + 间距 16）。
                        div()
                            .pl(px(104.))
                            .text_size(px(11.))
                            .line_height(px(14.))
                            .child(t(h, p.text2, FontWeight::NORMAL))
                    })),
            )
            .child(
                div()
                    .flex()
                    .items_center()
                    .justify_between()
                    .px(px(24.))
                    .pt(px(8.))
                    .pb(px(20.))
                    .child(
                        div()
                            .flex()
                            .gap(px(4.))
                            .children(edit_actions.drain(..).map(|(label, is_delete)| {
                                btn_text(
                                    ElementId::from(SharedString::from(label.clone())),
                                    label.clone(),
                                    if is_delete { p.neg } else { p.text2 },
                                    p,
                                )
                                .on_click(cx.listener(move |this, _, window, cx| {
                                    if is_delete {
                                        if let Some(Dialog::EditSubscription(id)) = this.dialog {
                                            this.dialog = None;
                                            this.delete_subscription(id, window, cx);
                                        }
                                    } else if let Some(Dialog::EditSubscription(id)) = this.dialog {
                                        let paused = this
                                            .data
                                            .subscriptions
                                            .iter()
                                            .find(|s| s.id == id)
                                            .is_some_and(|s| s.status != "active");
                                        let new_status = if paused { "active" } else { "paused" };
                                        this.dialog = None;
                                        this.update_subscription(
                                            id,
                                            move |s| {
                                                s.status = new_status.into();
                                                Ok(())
                                            },
                                            window,
                                            cx,
                                        );
                                    }
                                }))
                                .into_any_element()
                            })),
                    )
                    .child(
                        div()
                            .flex()
                            .gap(px(8.))
                            .child(btn_secondary("cancel-dialog", "取消", p).on_click(
                                cx.listener(|this, _, window, cx| this.cancel_dialog(window, cx)),
                            ))
                            .child(btn_primary("save-dialog", "保存", p).on_click(
                                cx.listener(|this, _, window, cx| this.save_dialog(window, cx)),
                            )),
                    ),
            );
        div()
            .absolute()
            .inset_0()
            .bg(p.mask_dialog)
            .flex()
            .justify_center()
            .items_center()
            .on_mouse_down(
                MouseButton::Left,
                cx.listener(|this, _, window, cx| this.cancel_dialog(window, cx)),
            )
            .child(shell)
            .into_any_element()
    }

    fn field_text(
        &self,
        label: &'static str,
        error: bool,
        p: &crate::theme::Palette,
        window: &gpui::Window,
        cx: &mut gpui::Context<Self>,
    ) -> AnyElement {
        field_row(
            label,
            super::widgets::input_field(&self.name, error, p, window, cx),
            p,
        )
        .into_any_element()
    }

    fn field_amount(
        &self,
        label: &'static str,
        error: bool,
        error_text: Option<String>,
        p: &crate::theme::Palette,
        window: &gpui::Window,
        cx: &mut gpui::Context<Self>,
    ) -> AnyElement {
        let symbol = if self.currency == "USD" { "$" } else { "¥" };
        field_row(
            label,
            div()
                .child(super::widgets::amount_input(
                    &self.amount,
                    symbol,
                    error,
                    p,
                    window,
                    cx,
                ))
                .children(error_text.map(|e| {
                    div()
                        .mt(px(4.))
                        .text_size(px(11.))
                        .line_height(px(14.))
                        .child(t(e, p.neg, FontWeight::NORMAL))
                })),
            p,
        )
        .into_any_element()
    }

    fn readonly_row(
        &self,
        label: &'static str,
        value: String,
        p: &crate::theme::Palette,
    ) -> AnyElement {
        field_row(label, div().child(t(value, p.text, FontWeight::NORMAL)), p).into_any_element()
    }

    /// 分类选择器：触发器 + 浮层菜单。
    fn category_field(
        &self,
        p: &crate::theme::Palette,
        cx: &mut gpui::Context<Self>,
    ) -> AnyElement {
        let index = self.new_category.min(4);
        let label = CATEGORIES[index].1;
        let trigger = div()
            .id("category-trigger")
            .tab_index(0)
            .tab_stop(true)
            .relative()
            .flex()
            .items_center()
            .justify_between()
            .h(px(32.))
            .w_full()
            .px(px(10.))
            .rounded(px(10.))
            .bg(p.card)
            .border_1()
            .border_color(p.sep)
            .text_size(px(13.))
            .cursor_pointer()
            .hover(move |s| s.border_color(p.text2))
            .focus(move |s| s.border_3().border_color(fade_brand(p)))
            .on_click(cx.listener(|this, _, _, cx| {
                this.category_menu_open = !this.category_menu_open;
                cx.notify();
            }))
            .child(
                div()
                    .flex()
                    .items_center()
                    .gap(px(8.))
                    .child(super::widgets::icon_block(index, p, false))
                    .child(t(label, p.text, FontWeight::NORMAL)),
            )
            .child(
                svg()
                    .path("icons/line/chevron-right.svg")
                    .size(px(16.))
                    .text_color(p.text2),
            );
        let mut field = div().relative().child(trigger);
        if self.category_menu_open {
            let mut menu = div()
                .absolute()
                .top_full()
                .left_0()
                .mt(px(4.))
                .w_full()
                .flex()
                .flex_col()
                .bg(p.card)
                .rounded(px(16.))
                .p(px(4.))
                .shadow(vec![super::widgets::floating_shadow(p.dark)])
                .on_mouse_down(MouseButton::Left, |_, _, cx| cx.stop_propagation());
            for (i, (_, name)) in CATEGORIES.iter().enumerate() {
                let selected = i == index;
                menu = menu.child(
                    div()
                        .id(("category-option", i))
                        .tab_index(0)
                        .tab_stop(true)
                        .flex()
                        .items_center()
                        .gap(px(8.))
                        .px(px(8.))
                        .py(px(6.))
                        .rounded(px(8.))
                        .cursor_pointer()
                        .when(selected, |s| s.bg(p.brand_soft))
                        .hover(move |s| s.bg(p.hover()))
                        .on_click(cx.listener(move |this, _, _, cx| {
                            this.new_category = i;
                            this.category_menu_open = false;
                            cx.notify();
                        }))
                        .child(super::widgets::icon_block(i, p, selected))
                        .child(t(
                            *name,
                            if selected { p.brand } else { p.text },
                            FontWeight::NORMAL,
                        )),
                );
            }
            field = field.child(deferred(menu).with_priority(1));
        }
        field_row("分类", field, p).into_any_element()
    }

    fn subscription_fields(
        &self,
        window: &gpui::Window,
        p: &crate::theme::Palette,
        cx: &mut gpui::Context<Self>,
    ) -> Vec<AnyElement> {
        let symbol = if self.currency == "USD" { "$" } else { "¥" };
        let currency_selected = if self.currency == "USD" { 1 } else { 0 };
        let cycle_selected = CYCLES
            .iter()
            .position(|(code, _)| *code == self.cycle)
            .unwrap_or(1);
        let date_error = self.date_error;
        vec![
            self.field_text("名称", self.name_error, p, window, cx),
            field_row(
                "金额",
                div()
                    .child(super::widgets::amount_input(
                        &self.amount,
                        symbol,
                        self.amount_error.is_some(),
                        p,
                        window,
                        cx,
                    ))
                    .children(self.amount_error.clone().map(|e| {
                        div()
                            .mt(px(4.))
                            .text_size(px(11.))
                            .line_height(px(14.))
                            .child(t(e, p.neg, FontWeight::NORMAL))
                    })),
                p,
            )
            .into_any_element(),
            field_row(
                "币种",
                segmented_listener(
                    "dlg-currency",
                    &[("CNY", "¥ 人民币"), ("USD", "$ 美元")],
                    currency_selected,
                    p,
                    cx.listener(|this, index: &usize, _, cx| {
                        this.currency = if *index == 1 {
                            "USD".into()
                        } else {
                            "CNY".into()
                        };
                        cx.notify();
                    }),
                ),
                p,
            )
            .into_any_element(),
            field_row(
                "扣费周期",
                segmented_listener(
                    "dlg-cycle",
                    &CYCLES,
                    cycle_selected,
                    p,
                    cx.listener(|this, index: &usize, _, cx| {
                        this.cycle = CYCLES[*index].0.into();
                        cx.notify();
                    }),
                ),
                p,
            )
            .into_any_element(),
            field_row(
                "下次扣费日",
                div()
                    .child(
                        div()
                            .rounded(px(11.))
                            .when(date_error, |s| s.border_1().border_color(p.neg))
                            .child(
                                gpui_component::date_picker::DatePicker::new(&self.date)
                                    .h(px(32.))
                                    .w_full()
                                    .rounded(px(10.)),
                            ),
                    )
                    .when(date_error, |s| {
                        s.child(
                            div()
                                .mt(px(4.))
                                .text_size(px(11.))
                                .line_height(px(14.))
                                .child(t("请选择下次扣费日", p.neg, FontWeight::NORMAL)),
                        )
                    }),
                p,
            )
            .into_any_element(),
        ]
    }
}

/// 带回调的分段控件（弹层里的币种/周期选择）。
fn segmented_listener(
    id: &'static str,
    options: &[(&'static str, &'static str)],
    selected: usize,
    p: &crate::theme::Palette,
    on_pick: impl Fn(&usize, &mut Window, &mut App) + 'static,
) -> gpui::Div {
    let on_pick = std::rc::Rc::new(on_pick);
    let mut container = div()
        .flex()
        .w_full()
        .p(px(2.))
        .rounded(px(10.))
        .border_1()
        .border_color(p.sep);
    for (i, (_value, label)) in options.iter().enumerate() {
        let active = i == selected;
        let debug = format!("{id}{i}");
        container = container.child(
            div()
                .debug_selector(move || debug.clone())
                .id((id, i))
                .tab_index(0)
                .tab_stop(true)
                .flex_1()
                .h(px(24.))
                .flex()
                .items_center()
                .justify_center()
                .rounded(px(8.))
                .text_size(px(13.))
                .cursor_pointer()
                .when(active, |s| s.bg(p.brand))
                .hover(move |s| if active { s } else { s.bg(p.hover()) })
                .focus(move |s| s.border_3().border_color(fade_brand(p)))
                .child(if active {
                    t(*label, p.on_brand, M)
                } else {
                    t(*label, p.text2, FontWeight::NORMAL)
                })
                .on_click({
                    let on_pick = on_pick.clone();
                    move |_, window, cx| on_pick(&i, window, cx)
                }),
        );
    }
    container
}

fn fade_brand(p: &crate::theme::Palette) -> gpui::Hsla {
    crate::theme::fade(p.brand, 0.4)
}

/// 确认框内容（图标、标题、正文、危险按钮文案），主窗口与设置窗口共用。
pub(super) fn confirm_info(confirm: &Confirm) -> (&'static str, String, String, String) {
    match confirm {
        Confirm::ClearSync => (
            "icons/line/warning-triangle.svg",
            "清除同步配置？".into(),
            "将删除本机保存的存储地址与凭证，并停止同步。远端数据、本机数据和同步密钥都不受影响。"
                .into(),
            "清除配置".into(),
        ),
        Confirm::Clear => (
            "icons/line/warning-triangle.svg",
            "清空全部数据？".into(),
            "将删除全部资产、历史与订阅，不能撤销。请先导出备份。".into(),
            "清空".into(),
        ),
        Confirm::Import(data) => (
            "icons/line/warning-triangle.svg",
            "覆盖本机数据？".into(),
            format!(
                "将导入 {} 项资产、{} 条历史、{} 项订阅，并覆盖现有数据。此操作不能撤销。",
                data.assets.len(),
                data.history.len(),
                data.subscriptions.len()
            ),
            "确认覆盖".into(),
        ),
        Confirm::ImportSealed { info, .. } => (
            "icons/line/warning-triangle.svg",
            "覆盖本机数据？".into(),
            format!(
                "加密备份导出于 {}（设备 {}），校验通过：{} 项资产、{} 条历史、{} 项订阅。\n确认后会覆盖现有数据，此操作不能撤销；如果导出时间比当前数据旧，请先取消并核对。",
                info.exported_at, info.device_id, info.assets, info.history, info.subscriptions
            ),
            "确认覆盖".into(),
        ),
        Confirm::ReplaceKey {
            new_fingerprint,
            old_fingerprint,
            ..
        } => (
            "icons/line/key.svg",
            "替换本机同步密钥？".into(),
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
            "确认替换".into(),
        ),
    }
}

/// 确认框（§5.6）：宽 420、左 40 圆形图标块、取消默认焦点、危险按钮靠右。
pub(super) fn render_confirm(
    this: &mut SnowFold,
    p: &crate::theme::Palette,
    cx: &mut gpui::Context<SnowFold>,
) -> AnyElement {
    use super::widgets::{btn_danger, btn_secondary, t};
    let Some(confirm) = this.confirm.clone() else {
        return div().into_any_element();
    };
    let (icon, title, body, danger_label) = confirm_info(&confirm);
    let shell =
        dialog_shell("confirm", 420., p)
            .track_focus(&this.modal_focus)
            .tab_index(0)
            .tab_stop(false)
            .on_mouse_down(MouseButton::Left, |_, _, cx| cx.stop_propagation())
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
                            .child(div().text_size(px(15.)).line_height(px(20.)).child(t(
                                title,
                                p.text,
                                crate::app::widgets::SB,
                            )))
                            .child(div().text_size(px(13.)).line_height(px(18.)).child(t(
                                body,
                                p.text2,
                                FontWeight::NORMAL,
                            ))),
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
                        // 「取消」为默认焦点。
                        btn_secondary("cancel-confirm", "取消", p).on_click(cx.listener(
                            |this, _, window, cx| {
                                this.close_confirm(window, cx);
                            },
                        )),
                    )
                    .child(btn_danger("apply-confirm", danger_label, p).on_click(
                        cx.listener(|this, _, window, cx| this.confirm_action(window, cx)),
                    )),
            );
    div()
        .absolute()
        .inset_0()
        .bg(p.mask_confirm)
        .flex()
        .justify_center()
        .items_center()
        .on_mouse_down(
            MouseButton::Left,
            cx.listener(|this, _, window, cx| this.close_confirm(window, cx)),
        )
        .child(shell)
        .into_any_element()
}
