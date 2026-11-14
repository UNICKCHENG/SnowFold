use super::{
    components::Palette,
    state::{Editing, SnowFold},
};
use crate::{
    model::{CATEGORIES, SubscriptionInput, decimal, parse_money, today},
    storage::Undo,
};
use anyhow::{Context as _, Result, bail};
use gpui::{prelude::*, *};
use gpui_component::{calendar::Date, input::Input};
use std::time::Duration;

impl SnowFold {
    pub(super) fn begin(
        &mut self,
        editing: Editing,
        window: &mut Window,
        cx: &mut gpui::Context<Self>,
    ) {
        if self.editing.is_some() {
            self.save_edit(window, cx);
            if self.editing.is_some() {
                return;
            }
        }
        self.popover = None;
        self.error = None;
        let (name, amount) = match editing {
            Editing::AssetName(id) | Editing::AssetAmount(id) => self
                .data
                .assets
                .iter()
                .find(|a| a.id == id)
                .map(|a| (a.name.clone(), decimal(a.amount_cents)))
                .unwrap_or_default(),
            Editing::SubscriptionName(id) | Editing::SubscriptionAmount(id) => self
                .data
                .subscriptions
                .iter()
                .find(|s| s.id == id)
                .map(|s| (s.name.clone(), decimal(s.amount_cents)))
                .unwrap_or_default(),
            Editing::History(id) => (
                String::new(),
                self.data
                    .history
                    .iter()
                    .find(|h| h.id == id)
                    .map(|h| decimal(h.amount_cents))
                    .unwrap_or_default(),
            ),
            _ => (String::new(), String::new()),
        };
        self.name.update(cx, |s, cx| s.set_value(name, window, cx));
        self.amount
            .update(cx, |s, cx| s.set_value(amount, window, cx));
        if editing == Editing::NewSubscription {
            self.currency = "CNY".into();
            self.cycle = "monthly".into();
            self.date
                .update(cx, |s, cx| s.set_date(today(), window, cx));
        }
        self.editing = Some(editing);
        if matches!(
            editing,
            Editing::AssetAmount(_) | Editing::SubscriptionAmount(_) | Editing::History(_)
        ) {
            self.amount.update(cx, |s, cx| s.focus(window, cx));
        } else {
            self.name.update(cx, |s, cx| s.focus(window, cx));
        }
        cx.notify();
    }
    pub(super) fn save_edit(&mut self, window: &mut Window, cx: &mut gpui::Context<Self>) {
        let Some(edit) = self.editing else { return };
        let name = self.name.read(cx).value().to_string();
        let amount = self.amount.read(cx).value().to_string();
        let date = self.date.read(cx).date();
        let result = (|| -> Result<Option<Undo>> {
            let storage = self.storage.as_mut().context("数据库不可用")?;
            match edit {
                Editing::NewAsset(category) => {
                    let id = storage.create_asset(
                        CATEGORIES[category].0,
                        &name,
                        parse_money(&amount)?,
                    )?;
                    self.highlight = Some(id);
                }
                Editing::AssetName(id) => storage.rename_asset(id, &name)?,
                Editing::AssetAmount(id) => {
                    return storage.change_balance(id, parse_money(&amount)?);
                }
                Editing::History(id) => storage.correct_history(id, parse_money(&amount)?)?,
                Editing::NewSubscription => {
                    let Date::Single(Some(date)) = date else {
                        bail!("请选择下次扣费日")
                    };
                    let s = SubscriptionInput {
                        name: name.clone(),
                        amount_cents: parse_money(&amount)?,
                        currency: self.currency.clone(),
                        cycle: self.cycle.clone(),
                        next_charge_on: date,
                        status: "active".into(),
                    };
                    self.highlight = Some(storage.create_subscription(&s)?);
                }
                Editing::SubscriptionName(id) | Editing::SubscriptionAmount(id) => {
                    let mut s = self
                        .data
                        .subscriptions
                        .iter()
                        .find(|s| s.id == id)
                        .context("订阅不存在")?
                        .clone();
                    if matches!(edit, Editing::SubscriptionName(_)) {
                        s.name = name.clone();
                    } else {
                        s.amount_cents = parse_money(&amount)?;
                    }
                    storage.update_subscription(id, &SubscriptionInput::from(&s))?;
                }
            }
            Ok(None)
        })();
        match result {
            Ok(undo) => {
                self.editing = None;
                if let Some(undo) = undo {
                    self.offer_undo(undo, "已记录本次变动", cx);
                }
                if let Err(e) = self.reload() {
                    self.error = Some(format!("已保存，但读取失败：{e:#}"));
                } else {
                    self.error = None;
                }
                if matches!(edit, Editing::NewAsset(_) | Editing::NewSubscription) {
                    self.new_item.scroll_to(window, cx);
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
                }
                self.focus.focus(window);
            }
            Err(e) => self.error = Some(format!("未保存：{e:#}")),
        }
        cx.notify();
    }
    pub(super) fn inline_input(&self, amount: bool) -> Div {
        div()
            .w(px(match self.editing {
                Some(Editing::SubscriptionName(_)) => 180.,
                Some(Editing::SubscriptionAmount(_)) => 130.,
                _ => {
                    if amount {
                        160.
                    } else {
                        240.
                    }
                }
            }))
            .child(Input::new(if amount { &self.amount } else { &self.name }))
    }
    pub(super) fn edit_controls(&self, p: Palette, cx: &mut gpui::Context<Self>) -> Div {
        div()
            .flex()
            .gap_2()
            .child(
                p.button("save-edit", "完成")
                    .on_click(cx.listener(|this, _, window, cx| this.save_edit(window, cx))),
            )
            .child(
                p.button("cancel-edit", "取消")
                    .on_click(cx.listener(|this, _, window, cx| {
                        this.editing = None;
                        this.error = None;
                        this.focus.focus(window);
                        cx.notify();
                    })),
            )
    }
}
