use super::{
    components::{Hint, Palette},
    state::{Editing, Popover, SnowFold},
};
use crate::model::{self, SubscriptionInput, money, today};
use anyhow::{Context as _, bail};
use gpui::{prelude::*, *};
use gpui_component::{calendar::Date, date_picker::DatePicker};

impl SnowFold {
    fn open_popover(
        &mut self,
        popover: Popover,
        window: &mut Window,
        cx: &mut gpui::Context<Self>,
    ) {
        self.save_edit(window, cx);
        if self.editing.is_some() {
            return;
        }
        if let Popover::Date(id) = popover
            && let Some(s) = self.data.subscriptions.iter().find(|s| s.id == id)
        {
            self.date
                .update(cx, |d, cx| d.set_date(s.next_charge_on, window, cx));
        }
        self.popover = if self.popover == Some(popover) {
            None
        } else {
            Some(popover)
        };
        cx.notify();
    }
    pub(super) fn render_subscriptions(&self, p: Palette, cx: &mut gpui::Context<Self>) -> Div {
        let active = self
            .data
            .subscriptions
            .iter()
            .filter(|s| s.status == "active")
            .count();
        let paused = self.data.subscriptions.len() - active;
        let sub = if paused > 0 {
            format!("{active} 个在订 · {paused} 个已暂停")
        } else {
            format!("{active} 个在订")
        };
        let mut body = div().flex().flex_col().child(
            div()
                .flex()
                .justify_between()
                .items_end()
                .mb(px(34.))
                .child(p.head("订阅".into(), sub.into()))
                .child(
                    div()
                        .flex()
                        .items_center()
                        .gap(px(8.))
                        .child(
                            p.button("new-subscription", "＋ 新建订阅")
                                .bg(p.card)
                                .on_click(cx.listener(|this, _, window, cx| {
                                    this.begin(Editing::NewSubscription, window, cx)
                                })),
                        )
                        .child(self.sync_button(p, cx)),
                ),
        );
        match model::totals(&self.data.subscriptions, today()) {
            Ok(stats) => {
                // One card, four hairline-separated columns; the charge-cycle
                // caveats live in hover tooltips instead of sub-captions.
                let mut card = p
                    .list_card()
                    .flex_row()
                    .py(px(16.))
                    .px(px(4.))
                    .debug_selector(|| "stats-card".into());
                let hints = [
                    "在用订阅中，下次扣费日落在当前自然月的金额合计（含本月已过期、待确认的）",
                    "年化合计 ÷ 12 后舍入",
                    "按扣费周期折算为全年支出",
                ];
                for (i, label) in ["本月待扣", "月均支出", "年化支出"].iter().enumerate()
                {
                    let hint = hints[i];
                    card = card.child(
                        p.stat_col(
                            label,
                            format!(
                                "{} / {}",
                                money(stats[0][i], "CNY"),
                                money(stats[1][i], "USD")
                            )
                            .into(),
                            None,
                            false,
                        )
                        .debug_selector(move || ["stat0", "stat1", "stat2"][i].into())
                        .id(("stat-hint", i))
                        .tooltip(move |_, cx| {
                            cx.new(|_| Hint {
                                text: hint.into(),
                                palette: p,
                            })
                            .into()
                        }),
                    );
                }
                let next = self
                    .data
                    .subscriptions
                    .iter()
                    .filter(|s| s.status == "active" && s.next_charge_on >= today())
                    .min_by_key(|s| s.next_charge_on);
                card = card.child(
                    p.stat_col(
                        "下一个扣费",
                        next.map(|s| s.next_charge_on.to_string())
                            .unwrap_or_else(|| "—".into())
                            .into(),
                        Some(
                            next.map(|s| s.name.clone())
                                .unwrap_or_else(|| "暂无扣费".into())
                                .into(),
                        ),
                        true,
                    )
                    .debug_selector(|| "stat3".into()),
                );
                body = body.child(card.mb(px(34.)));
            }
            Err(e) => {
                body = body.child(
                    p.panel()
                        .text_color(rgb(0xae4a3c))
                        .child(format!("统计失败：{e:#}")),
                )
            }
        }
        if self.data.subscriptions.is_empty() {
            return body.child(
                p.panel()
                    .py_16()
                    .items_center()
                    .child("还没有订阅")
                    .child(
                        div()
                            .text_color(p.muted)
                            .child("记下常用服务，随时知道接下来要扣多少钱"),
                    )
                    .child(p.button("empty-new-subscription", "＋ 新建订阅").on_click(
                        cx.listener(|this, _, window, cx| {
                            this.begin(Editing::NewSubscription, window, cx)
                        }),
                    )),
            );
        }
        let mut first_group = true;
        for (group, label) in ["待确认", "本月", "下月", "之后", "已暂停"]
            .iter()
            .enumerate()
        {
            let mut subscriptions = self
                .data
                .subscriptions
                .iter()
                .filter(|s| s.group(today()) == group)
                .collect::<Vec<_>>();
            subscriptions.sort_by_key(|s| (s.next_charge_on, s.id));
            if subscriptions.is_empty() {
                continue;
            }
            let section = div()
                .flex()
                .flex_col()
                .when(!first_group, |section| section.mt(px(30.)))
                .child(p.group_head(label, subscriptions.len()));
            first_group = false;
            let mut list = p.list_card();
            for (row_index, s) in subscriptions.into_iter().enumerate() {
                let id = s.id;
                let mut row = div()
                    .flex()
                    .gap_2()
                    .items_center()
                    .min_h(px(64.))
                    .px(px(16.))
                    .id((ElementId::from("subscription-row"), id.to_string()))
                    .anchor_scroll((self.highlight == Some(id)).then(|| self.new_item.clone()))
                    .bg(if self.highlight == Some(id) {
                        p.canvas
                    } else {
                        p.card
                    })
                    .when(row_index > 0, |row| row.border_t_1().border_color(p.line))
                    .when(group == 4, |row| row.opacity(0.62));
                if group == 0 {
                    row = row.child(
                        div()
                            .flex_none()
                            .w(px(2.5))
                            .h(px(40.))
                            .rounded(px(2.))
                            .bg(p.neg),
                    );
                }
                // Reference avatar: 34px tile holding the service initial.
                let initial = s
                    .name
                    .chars()
                    .next()
                    .map(|c| c.to_uppercase().to_string())
                    .unwrap_or_else(|| "?".into());
                let (avatar_bg, avatar_fg, avatar_border) = if group == 0 {
                    (p.neg_soft, p.neg, p.neg_soft)
                } else {
                    (p.subtle, p.muted, p.line)
                };
                row = row.child(
                    div()
                        .flex_none()
                        .size(px(34.))
                        .flex()
                        .items_center()
                        .justify_center()
                        .rounded(px(10.))
                        .bg(avatar_bg)
                        .border_1()
                        .border_color(avatar_border)
                        .text_size(px(13.))
                        .font_weight(FontWeight::SEMIBOLD)
                        .text_color(avatar_fg)
                        .child(initial),
                );
                row = row.child(if self.editing == Some(Editing::SubscriptionName(id)) {
                    self.inline_input(false)
                } else {
                    div().w(px(180.)).overflow_hidden().child(
                        p.button(
                            (ElementId::from("sub-name"), id.to_string()),
                            s.name.clone(),
                        )
                        .text_color(p.text)
                        .on_click(cx.listener(
                            move |this, _, window, cx| {
                                this.begin(Editing::SubscriptionName(id), window, cx)
                            },
                        )),
                    )
                });
                row = row.child(if self.editing == Some(Editing::SubscriptionAmount(id)) {
                    self.inline_input(true)
                } else {
                    div().w(px(130.)).text_right().child(
                        p.button(
                            (ElementId::from("sub-amount"), id.to_string()),
                            money(s.amount_cents, &s.currency),
                        )
                        .text_color(p.text)
                        .on_click(cx.listener(
                            move |this, _, window, cx| {
                                this.begin(Editing::SubscriptionAmount(id), window, cx)
                            },
                        )),
                    )
                });
                row = row
                    .child(
                        div()
                            .relative()
                            .on_mouse_down(MouseButton::Left, |_, _, cx| cx.stop_propagation())
                            .child(
                                p.button(
                                    (ElementId::from("sub-currency"), id.to_string()),
                                    s.currency.clone(),
                                )
                                .on_click(cx.listener(
                                    move |this, _, window, cx| {
                                        this.open_popover(Popover::Currency(id), window, cx)
                                    },
                                )),
                            )
                            .when(self.popover == Some(Popover::Currency(id)), |row| {
                                let mut options = p
                                    .panel()
                                    .absolute()
                                    .top_full()
                                    .left_0()
                                    .w(px(130.))
                                    .shadow_lg()
                                    .on_mouse_down(MouseButton::Left, |_, _, cx| {
                                        cx.stop_propagation()
                                    });
                                for currency in ["CNY", "USD"] {
                                    options = options.child(
                                        p.button(
                                            (
                                                "currency-option",
                                                currency.len() + usize::from(currency == "USD"),
                                            ),
                                            currency,
                                        )
                                        .on_click(
                                            cx.listener(move |this, _, window, cx| {
                                                this.update_subscription(
                                                    id,
                                                    |s| {
                                                        s.currency = currency.into();
                                                        Ok(())
                                                    },
                                                    window,
                                                    cx,
                                                )
                                            }),
                                        ),
                                    );
                                }
                                row.child(deferred(options).with_priority(1))
                            }),
                    )
                    .child(
                        div()
                            .relative()
                            .on_mouse_down(MouseButton::Left, |_, _, cx| cx.stop_propagation())
                            .child(
                                p.button(
                                    (ElementId::from("sub-cycle"), id.to_string()),
                                    cycle_label(&s.cycle),
                                )
                                .text_color(p.muted)
                                .on_click(cx.listener(
                                    move |this, _, window, cx| {
                                        this.open_popover(Popover::Cycle(id), window, cx)
                                    },
                                )),
                            )
                            .when(self.popover == Some(Popover::Cycle(id)), |row| {
                                let mut options = p
                                    .panel()
                                    .absolute()
                                    .top_full()
                                    .left_0()
                                    .w(px(140.))
                                    .shadow_lg()
                                    .on_mouse_down(MouseButton::Left, |_, _, cx| {
                                        cx.stop_propagation()
                                    });
                                for (i, cycle) in ["weekly", "monthly", "quarterly", "yearly"]
                                    .into_iter()
                                    .enumerate()
                                {
                                    options = options.child(
                                        p.button(("cycle-option", i), cycle_label(cycle)).on_click(
                                            cx.listener(move |this, _, window, cx| {
                                                this.update_subscription(
                                                    id,
                                                    |s| {
                                                        s.cycle = cycle.into();
                                                        Ok(())
                                                    },
                                                    window,
                                                    cx,
                                                )
                                            }),
                                        ),
                                    );
                                }
                                row.child(deferred(options).with_priority(1))
                            }),
                    )
                    .child(
                        div()
                            .relative()
                            .on_mouse_down(MouseButton::Left, |_, _, cx| cx.stop_propagation())
                            .child(
                                p.button(
                                    (ElementId::from("sub-date"), id.to_string()),
                                    s.next_charge_on.to_string(),
                                )
                                .text_color(p.muted)
                                .on_click(cx.listener(
                                    move |this, _, window, cx| {
                                        this.open_popover(Popover::Date(id), window, cx)
                                    },
                                )),
                            )
                            .when(self.popover == Some(Popover::Date(id)), |row| {
                                row.child(
                                    deferred(
                                        p.panel()
                                            .absolute()
                                            .top_full()
                                            .right_0()
                                            .w(px(280.))
                                            .shadow_lg()
                                            .on_mouse_down(MouseButton::Left, |_, _, cx| {
                                                cx.stop_propagation()
                                            })
                                            .child(DatePicker::new(&self.date))
                                            .child(p.button("save-date", "应用日期").on_click(
                                                cx.listener(move |this, _, window, cx| {
                                                    let date = this.date.read(cx).date();
                                                    this.update_subscription(
                                                        id,
                                                        |s| {
                                                            let Date::Single(Some(date)) = date
                                                            else {
                                                                bail!("请选择日期")
                                                            };
                                                            s.next_charge_on = date;
                                                            Ok(())
                                                        },
                                                        window,
                                                        cx,
                                                    );
                                                }),
                                            )),
                                    )
                                    .with_priority(1),
                                )
                            }),
                    )
                    .child(div().flex_1());
                if group == 0 {
                    row = row.child(div().text_size(px(11.)).text_color(rgb(0xae4a3c)).child(
                        format!(
                            "逾期{}天",
                            today().signed_duration_since(s.next_charge_on).num_days()
                        ),
                    ));
                }
                if s.status == "active" {
                    row = row.child(
                        p.button((ElementId::from("renew-sub"), id.to_string()), "✓ 已续费")
                            .on_click(cx.listener(move |this, _, window, cx| {
                                let result = (|| {
                                    let mut s = this
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
                                    this.storage
                                        .as_mut()
                                        .context("数据库不可用")?
                                        .update_subscription(id, &SubscriptionInput::from(&s))?;
                                    this.notice = Some(format!("已顺延 {n} 期"));
                                    Ok(())
                                })();
                                this.finish(result, window, cx);
                            })),
                    );
                }
                row = row
                    .child(
                        p.button(
                            (ElementId::from("pause-sub"), id.to_string()),
                            if s.status == "active" {
                                "暂停"
                            } else {
                                "恢复"
                            },
                        )
                        .text_color(p.muted)
                        .on_click(cx.listener(
                            move |this, _, window, cx| {
                                this.update_subscription(
                                    id,
                                    |s| {
                                        s.status = if s.status == "active" {
                                            "paused"
                                        } else {
                                            "active"
                                        }
                                        .into();
                                        Ok(())
                                    },
                                    window,
                                    cx,
                                )
                            },
                        )),
                    )
                    .child(
                        p.button((ElementId::from("delete-sub"), id.to_string()), "×")
                            .text_color(p.muted)
                            .on_click(cx.listener(move |this, _, window, cx| {
                                this.delete_subscription(id, window, cx)
                            })),
                    );
                list = list.child(row);
            }
            body = body.child(section.child(list));
        }
        body
    }
}

pub(super) fn cycle_label(cycle: &str) -> &'static str {
    match cycle {
        "weekly" => "每周",
        "monthly" => "每月",
        "quarterly" => "每季",
        "yearly" => "每年",
        _ => "未知周期",
    }
}
