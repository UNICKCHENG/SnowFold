//! 订阅（§9.4）：顶部统计、分组列表；行点击打开编辑弹层，悬停显示动作。

use gpui::{prelude::*, *};

use super::{
    overview::{cycle_label, pct_text, short_date},
    state::{Dialog, SnowFold},
    widgets::{M, SB, avatar, badge, card, group_head, sizes, t},
};
use crate::theme::Palette;
use gpui_component::calendar::Date;
use snowfold_core::model::{self, money, today};

impl SnowFold {
    pub(super) fn render_subscriptions(&self, p: &Palette, cx: &mut gpui::Context<Self>) -> Div {
        let active = self
            .data
            .subscriptions
            .iter()
            .filter(|s| s.status == "active")
            .count();
        let paused = self.data.subscriptions.len() - active;
        let mut body = div().flex().flex_col();
        if self.data.subscriptions.is_empty() {
            return body.child(self.subscriptions_empty(p, cx));
        }
        // 顶部统计（§9.4）：本月待扣（页标题焦点）+ 月均/年化（分区标题值）。
        body = body.child(
            div()
                .debug_selector(|| "stats-card".to_string())
                .flex()
                .items_end()
                .gap(px(40.))
                .child(
                    div()
                        .debug_selector(|| "stat0".to_string())
                        .flex()
                        .flex_col()
                        .gap(px(4.))
                        .child(t("本月待扣", p.text2, M))
                        .child(super::widgets::focus_amount(
                            &money(self.stats_value(0), "CNY"),
                            super::widgets::AmountLevel::Page,
                            false,
                            p,
                        ))
                        .children(self.usd_suffix(0, p)),
                )
                .child(self.stat_col("月均支出", 1, "monthly_spending_help", p))
                .child(self.stat_col("年化支出", 2, "annual_spending_help", p)),
        );
        body = body.child(
            div()
                .mt(px(12.))
                .text_size(px(sizes::AUX))
                .line_height(px(sizes::AUX_LH))
                .child(t(
                    if paused > 0 {
                        format!("{active} 个在订 · {paused} 个已暂停")
                    } else {
                        format!("{active} 个在订")
                    },
                    p.text2,
                    FontWeight::NORMAL,
                )),
        );
        // 分组：待确认（组头 neg）/本月/下月/之后/已暂停；组间 24。
        let labels = ["待确认", "本月", "下月", "之后", "已暂停"];
        let mut groups = div().flex().flex_col().gap(px(24.)).mt(px(16.));
        for (group, label) in labels.iter().enumerate() {
            let mut subscriptions: Vec<&model::Subscription> = self
                .data
                .subscriptions
                .iter()
                .filter(|s| s.group(today()) == group)
                .collect();
            if subscriptions.is_empty() {
                continue;
            }
            subscriptions.sort_by_key(|s| (s.next_charge_on, s.id));
            let mut list = card().bg(p.card);
            for (row_index, s) in subscriptions.iter().enumerate() {
                if row_index > 0 {
                    list = list
                        .child(super::widgets::row_separator(16., p))
                        .child(div().h(px(0.)).w_full());
                }
                list = list.child(self.subscription_row(p, s, group == 0, group == 4, cx));
            }
            groups = groups.child(
                div()
                    .flex()
                    .flex_col()
                    .child(group_head(label, subscriptions.len(), group == 0, p))
                    .child(list),
            );
        }
        body.child(groups)
    }

    fn subscriptions_empty(&self, p: &Palette, cx: &mut gpui::Context<Self>) -> Div {
        super::widgets::empty_state(
            "icons/line/calendar-repeat.svg",
            "还没有订阅",
            "记下常用服务，随时知道接下来要扣多少钱",
            new_subscription_button(
                p,
                cx.listener(|this, _, window, cx| {
                    this.begin_dialog(Dialog::NewSubscription, window, cx)
                }),
            ),
            p,
        )
    }

    fn stats_value(&self, index: usize) -> i64 {
        match model::totals(&self.data.subscriptions, today()) {
            Ok(stats) => stats[0][index],
            Err(_) => 0,
        }
    }

    fn usd_suffix(&self, index: usize, p: &Palette) -> Option<AnyElement> {
        let usd = match model::totals(&self.data.subscriptions, today()) {
            Ok(stats) => stats[1][index],
            Err(_) => 0,
        };
        if usd == 0 && index == 0 && self.stats_value(index) == 0 {
            return None;
        }
        Some(
            div()
                .mt(px(2.))
                .text_size(px(sizes::BODY))
                .child(super::widgets::n(
                    format!("+ {}", money(usd, "USD")),
                    p.text2,
                    FontWeight::NORMAL,
                ))
                .into_any_element(),
        )
    }

    /// 月均/年化列：标签（辅助 text2）+ info 16（悬停口径说明）+ 值（分区标题）+ 美元行。
    fn stat_col(&self, label: &'static str, index: usize, help: &'static str, p: &Palette) -> Div {
        let dark = p.dark;
        let cny = self.stats_value(index);
        let usd = match model::totals(&self.data.subscriptions, today()) {
            Ok(stats) => stats[1][index],
            Err(_) => 0,
        };
        div()
            .debug_selector(move || format!("stat{index}"))
            .flex()
            .flex_col()
            .gap(px(4.))
            .child(
                div()
                    .flex()
                    .items_center()
                    .gap(px(4.))
                    .child(t(label, p.text2, FontWeight::NORMAL))
                    .child(
                        div()
                            .id(("stat-help", index))
                            .flex()
                            .items_center()
                            .cursor_default()
                            .child(
                                svg()
                                    .path("icons/line/info.svg")
                                    .size(px(16.))
                                    .text_color(p.text2),
                            )
                            .tooltip(move |_, cx| {
                                cx.new(|_| super::widgets::Hint {
                                    text: help.into(),
                                    dark,
                                })
                                .into()
                            }),
                    ),
            )
            .child(
                div()
                    .text_size(px(sizes::SECTION))
                    .line_height(px(sizes::SECTION_LH))
                    .child(super::widgets::n(money(cny, "CNY"), p.text, SB)),
            )
            .children(if usd != 0 {
                Some(
                    div()
                        .text_size(px(sizes::AUX))
                        .child(super::widgets::n(
                            format!("+ {}", money(usd, "USD")),
                            p.text2,
                            FontWeight::NORMAL,
                        ))
                        .into_any_element(),
                )
            } else {
                None
            })
    }

    fn subscription_row(
        &self,
        p: &Palette,
        s: &model::Subscription,
        overdue_group: bool,
        paused: bool,
        cx: &mut gpui::Context<Self>,
    ) -> Stateful<Div> {
        let id = s.id;
        let days = s.next_charge_on.signed_duration_since(today()).num_days();
        let (badge_text, badge_bg, badge_fg) = if s.status != "active" {
            (String::new(), p.card, p.text2)
        } else if days < 0 {
            (format!("逾期 {} 天", -days), p.neg_soft, p.neg)
        } else if days == 0 {
            ("今天".to_string(), p.neg_soft, p.neg)
        } else if days <= 7 {
            (format!("{days} 天后"), p.warn_soft, p.warn)
        } else {
            (format!("{days} 天后"), p.card, p.text2)
        };
        let (avatar_bg, avatar_fg) = if overdue_group {
            (p.neg_soft, p.neg)
        } else if paused {
            (p.bg, p.text2)
        } else {
            (p.brand_soft, p.brand)
        };
        let initial = s
            .name
            .chars()
            .next()
            .map(|c| c.to_uppercase().to_string())
            .unwrap_or_else(|| "?".into());
        let sub = format!(
            "{} · {}",
            cycle_label(&s.cycle),
            short_date(s.next_charge_on)
        );
        let name_color = if paused { p.text2 } else { p.text };
        let amount_color = if paused { p.text2 } else { p.text };
        let row = div()
            .debug_selector({
                let name = s.name.clone();
                move || name.to_string()
            })
            .id((ElementId::from("subscription-row"), id.to_string()))
            .group("sub-row")
            .tab_index(0)
            .tab_stop(true)
            .flex()
            .items_center()
            .gap(px(12.))
            .min_h(px(56.))
            .px(px(16.))
            .py(px(12.))
            .cursor_pointer()
            .hover(move |row| row.bg(p.hover()))
            .focus(move |s| s.border_3().border_color(crate::theme::fade(p.brand, 0.4)))
            .on_click(cx.listener(move |this, _, window, cx| {
                this.begin_dialog(Dialog::EditSubscription(id), window, cx)
            }))
            .when(paused, |row| row.opacity(0.62))
            .child(avatar(initial, avatar_bg, avatar_fg))
            .child(
                div()
                    .flex()
                    .flex_col()
                    .gap(px(2.))
                    .flex_1()
                    .min_w(px(0.))
                    .child(
                        div()
                            .text_size(px(sizes::BODY))
                            .line_height(px(sizes::BODY_LH))
                            .child(t(s.name.clone(), name_color, M)),
                    )
                    .child(
                        div()
                            .text_size(px(sizes::AUX))
                            .line_height(px(sizes::AUX_LH))
                            .child(t(sub, p.text2, FontWeight::NORMAL)),
                    ),
            );
        let row = if !badge_text.is_empty() && s.status == "active" {
            row.child(badge(badge_text, badge_bg, badge_fg))
        } else {
            row
        };
        let row = row.child(div().flex_none().w(px(120.)).flex().justify_end().child(
            div().text_size(px(sizes::BODY)).child(super::widgets::n(
                money(s.amount_cents, &s.currency),
                amount_color,
                M,
            )),
        ));
        // 动作列（宽 112，右对齐）：只占一列，所有动作均装在列内。
        let mut actions = div()
            .w(px(112.))
            .flex_none()
            .flex()
            .justify_end()
            .items_center()
            .gap(px(8.));
        if overdue_group && s.status == "active" {
            actions = actions.child(
                super::widgets::btn_soft(
                    (ElementId::from("renew-sub"), id.to_string()),
                    "✓ 已续费",
                    p,
                )
                .on_click(
                    cx.listener(move |this, _, window, cx| this.renew_subscription(id, window, cx)),
                ),
            );
        } else if s.status == "active" {
            actions = actions
                .child(
                    div()
                        .id((ElementId::from("pause-sub"), id.to_string()))
                        .group_hover("sub-row", |s| s.visible())
                        .invisible()
                        .tab_index(0)
                        .tab_stop(true)
                        .flex()
                        .items_center()
                        .justify_center()
                        .size(px(24.))
                        .rounded(px(8.))
                        .cursor_pointer()
                        .hover(move |s| s.bg(p.hover()))
                        .focus(move |s| s.border_3().border_color(crate::theme::fade(p.brand, 0.4)))
                        .on_click(cx.listener(move |this, _, window, cx| {
                            this.update_subscription(
                                id,
                                |s| {
                                    s.status = "paused".into();
                                    Ok(())
                                },
                                window,
                                cx,
                            );
                        }))
                        .child(
                            svg()
                                .path("icons/line/pause.svg")
                                .size(px(16.))
                                .text_color(p.text2),
                        ),
                )
                .child(
                    div()
                        .id((ElementId::from("delete-sub"), id.to_string()))
                        .group_hover("sub-row", |s| s.visible())
                        .invisible()
                        .tab_index(0)
                        .tab_stop(true)
                        .flex()
                        .items_center()
                        .justify_center()
                        .size(px(24.))
                        .rounded(px(8.))
                        .cursor_pointer()
                        .hover(move |s| s.bg(p.hover()))
                        .focus(move |s| s.border_3().border_color(crate::theme::fade(p.brand, 0.4)))
                        .on_click(cx.listener(move |this, _, window, cx| {
                            this.delete_subscription(id, window, cx)
                        }))
                        .child(
                            svg()
                                .path("icons/line/trash.svg")
                                .size(px(16.))
                                .text_color(p.text2),
                        ),
                );
        } else {
            // 已暂停：悬停显示「恢复」与删除。
            actions = actions
                .child(
                    div()
                        .id((ElementId::from("resume-sub"), id.to_string()))
                        .group_hover("sub-row", |s| s.visible())
                        .invisible()
                        .tab_index(0)
                        .tab_stop(true)
                        .flex()
                        .items_center()
                        .justify_center()
                        .size(px(24.))
                        .rounded(px(8.))
                        .cursor_pointer()
                        .hover(move |s| s.bg(p.hover()))
                        .focus(move |s| s.border_3().border_color(crate::theme::fade(p.brand, 0.4)))
                        .on_click(cx.listener(move |this, _, window, cx| {
                            this.update_subscription(
                                id,
                                |s| {
                                    s.status = "active".into();
                                    Ok(())
                                },
                                window,
                                cx,
                            );
                        }))
                        .child(
                            svg()
                                .path("icons/line/play.svg")
                                .size(px(16.))
                                .text_color(p.text2),
                        ),
                )
                .child(
                    div()
                        .id((ElementId::from("delete-sub"), id.to_string()))
                        .group_hover("sub-row", |s| s.visible())
                        .invisible()
                        .tab_index(0)
                        .tab_stop(true)
                        .flex()
                        .items_center()
                        .justify_center()
                        .size(px(24.))
                        .rounded(px(8.))
                        .cursor_pointer()
                        .hover(move |s| s.bg(p.hover()))
                        .focus(move |s| s.border_3().border_color(crate::theme::fade(p.brand, 0.4)))
                        .on_click(cx.listener(move |this, _, window, cx| {
                            this.delete_subscription(id, window, cx)
                        }))
                        .child(
                            svg()
                                .path("icons/line/trash.svg")
                                .size(px(16.))
                                .text_color(p.text2),
                        ),
                );
        }
        let _ = pct_text;
        row.child(actions)
    }
}

/// 「＋ 新建订阅」按钮（浅色按钮带 plus 图标）。
pub(super) fn new_subscription_button(
    p: &Palette,
    on_click: impl Fn(&gpui::ClickEvent, &mut Window, &mut App) + 'static,
) -> gpui::Stateful<gpui::Div> {
    super::widgets::btn_soft("new-subscription", "＋ 新建订阅", p).on_click(on_click)
}

/// 行内日期弹层用的日期读取（保留给编辑弹层）。
pub(super) fn picked_date(
    state: &gpui::Entity<gpui_component::date_picker::DatePickerState>,
    cx: &gpui::App,
) -> Option<chrono::NaiveDate> {
    match state.read(cx).date() {
        Date::Single(Some(date)) => Some(date),
        _ => None,
    }
}
