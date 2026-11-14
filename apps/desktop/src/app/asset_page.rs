use super::{
    components::Palette,
    state::{Editing, SnowFold},
};
use crate::model::{self, CATEGORIES, money};
use gpui::{prelude::*, *};
use gpui_component::{Icon, IconName};

impl SnowFold {
    fn asset_row(
        &self,
        p: Palette,
        a: &model::Asset,
        cx: &mut gpui::Context<Self>,
    ) -> Stateful<Div> {
        let id = a.id;
        let open = self.history == Some(id);
        let history_count = self
            .data
            .history
            .iter()
            .filter(|h| h.asset_id == id)
            .count();
        let mut row = div()
            .flex()
            .items_center()
            .gap_3()
            .h(px(50.))
            .px_4()
            .border_t_1()
            .border_color(p.line)
            .id((ElementId::from("asset-row"), id.to_string()))
            .anchor_scroll((self.highlight == Some(id)).then(|| self.new_item.clone()))
            .bg(if open {
                p.subtle
            } else if self.highlight == Some(id) {
                p.hover
            } else {
                p.card
            })
            .hover(move |s| s.bg(p.hover));
        row = row.child(
            div()
                .flex_1()
                .min_w(px(0.))
                .overflow_hidden()
                .text_ellipsis()
                .child(if self.editing == Some(Editing::AssetName(id)) {
                    self.inline_input(false).into_any_element()
                } else {
                    p.text_control((ElementId::from("asset-name"), id.to_string()))
                        .debug_selector({
                            let name = a.name.clone();
                            move || name.to_string()
                        })
                        .px(px(4.))
                        .py(px(3.))
                        .text_size(px(13.5))
                        .child(a.name.clone())
                        .on_click(cx.listener(move |this, _, window, cx| {
                            this.begin(Editing::AssetName(id), window, cx)
                        }))
                        .into_any_element()
                }),
        );
        row = row.child(
            p.text_control((ElementId::from("asset-history"), id.to_string()))
                .flex()
                .items_center()
                .gap_1()
                .h(px(22.))
                .px(px(7.))
                .text_size(px(11.))
                .text_color(if open { p.text } else { p.ink3 })
                .when(open, |chip| chip.bg(p.subtle))
                .child(
                    svg()
                        .path("icons/clock.svg")
                        .w(px(12.))
                        .h(px(12.))
                        .text_color(if open { p.text } else { p.ink3 }),
                )
                .child(history_count.to_string())
                .on_click(cx.listener(move |this, _, _, cx| {
                    this.history = if this.history == Some(id) {
                        None
                    } else {
                        Some(id)
                    };
                    cx.notify();
                })),
        );
        row = row.child(div().flex_1().min_w(px(0.)));
        row = row.child(if self.editing == Some(Editing::AssetAmount(id)) {
            self.inline_input(true)
        } else {
            div().child(
                p.text_control((ElementId::from("asset-amount"), id.to_string()))
                    .debug_selector({
                        let label = money(a.amount_cents, "CNY");
                        move || label.clone()
                    })
                    .px(px(4.))
                    .py(px(3.))
                    .text_size(px(13.5))
                    .text_color(p.text)
                    .child(money(a.amount_cents, "CNY"))
                    .on_click(cx.listener(move |this, _, window, cx| {
                        this.begin(Editing::AssetAmount(id), window, cx)
                    })),
            )
        });
        row = row.child(
            p.text_control((ElementId::from("delete-asset"), id.to_string()))
                .debug_selector(|| "×".to_string())
                .flex()
                .items_center()
                .justify_center()
                .w(px(24.))
                .h(px(24.))
                .text_color(p.ink3)
                .hover(move |s| s.text_color(p.neg))
                .child(Icon::new(IconName::Close).size_3p5())
                .on_click(cx.listener(move |this, _, window, cx| {
                    cx.stop_propagation();
                    this.delete_asset(id, window, cx)
                })),
        );
        row
    }

    fn asset_history_panel(
        &self,
        p: Palette,
        a: &model::Asset,
        cx: &mut gpui::Context<Self>,
    ) -> Div {
        let id = a.id;
        let history = self
            .data
            .history
            .iter()
            .filter(|h| h.asset_id == id)
            .collect::<Vec<_>>();
        let mut panel = div()
            .flex()
            .flex_col()
            .gap_2()
            .px_4()
            .pt_3()
            .pb_4()
            .border_t_1()
            .border_color(p.line)
            .bg(p.subtle)
            .child(
                div()
                    .text_size(px(11.5))
                    .text_color(p.ink3)
                    .child("变动记录 · 修正不会新增快照"),
            );
        for (position, h) in history.iter().enumerate().rev() {
            let hid = h.id;
            let delta = position
                .checked_sub(1)
                .map(|i| h.amount_cents - history[i].amount_cents);
            let label = if position + 1 == history.len() {
                "当前"
            } else {
                ""
            };
            let mut row = div()
                .flex()
                .items_center()
                .gap_3()
                .h(px(38.))
                .child(
                    div()
                        .w(px(220.))
                        .text_size(px(12.))
                        .text_color(p.muted)
                        .child(format!("{}  {}", h.recorded_at, label)),
                )
                .child(
                    div().flex_1().text_size(px(12.)).text_color(p.ink3).child(
                        delta
                            .map(|v| format!("{}{}", if v > 0 { "+" } else { "" }, money(v, "CNY")))
                            .unwrap_or_else(|| "初始".into()),
                    ),
                );
            row = row.child(if self.editing == Some(Editing::History(hid)) {
                self.inline_input(true)
            } else {
                div()
                    .text_size(px(13.))
                    .text_right()
                    .child(money(h.amount_cents, "CNY"))
            });
            row = row.child(
                p.button(
                    (ElementId::from("correct-history"), hid.to_string()),
                    if self.editing == Some(Editing::History(hid)) {
                        "完成"
                    } else {
                        "修正"
                    },
                )
                .text_size(px(12.))
                .on_click(cx.listener(move |this, _, window, cx| {
                    if this.editing == Some(Editing::History(hid)) {
                        this.save_edit(window, cx);
                    } else {
                        this.begin(Editing::History(hid), window, cx);
                    }
                })),
            );
            panel = panel.child(row);
        }
        panel
    }

    fn asset_section(
        &self,
        p: Palette,
        index: usize,
        code: &str,
        label: &'static str,
        cx: &mut gpui::Context<Self>,
    ) -> Div {
        let assets = self
            .data
            .assets
            .iter()
            .filter(|a| a.category_code == code)
            .collect::<Vec<_>>();
        let sum = assets
            .iter()
            .try_fold(0i64, |v, a| v.checked_add(a.amount_cents));
        let (chip_bg, chip_fg) = p.category_tint(index);
        let header = div()
            .flex()
            .items_center()
            .gap_2()
            .px(px(2.))
            .child(
                div()
                    .flex()
                    .items_center()
                    .justify_center()
                    .flex_none()
                    .w(px(27.))
                    .h(px(27.))
                    .rounded(px(8.))
                    .bg(chip_bg)
                    .child(
                        svg()
                            .path(Self::category_icon(index))
                            .w(px(15.))
                            .h(px(15.))
                            .text_color(chip_fg),
                    ),
            )
            .child(
                div()
                    .text_size(px(14.))
                    .font_weight(FontWeight::SEMIBOLD)
                    .child(label),
            )
            .child(
                div()
                    .text_size(px(11.5))
                    .text_color(p.ink3)
                    .child(format!("{} 项", assets.len())),
            )
            .child(div().flex_1())
            .child(
                div()
                    .text_size(px(15.))
                    .font_weight(FontWeight::SEMIBOLD)
                    .text_color(if index == 4 { p.neg } else { p.text })
                    .child(
                        sum.map(|v| money(v, "CNY"))
                            .unwrap_or_else(|| "合计超出范围".into()),
                    ),
            )
            .child(
                p.text_control(("add-asset", index))
                    .debug_selector(|| "＋ 添加".to_string())
                    .flex()
                    .items_center()
                    .gap_1()
                    .h(px(26.))
                    .pl(px(7.))
                    .pr(px(9.))
                    .text_size(px(12.5))
                    .text_color(p.ink3)
                    .hover(move |s| s.bg(p.subtle).text_color(p.text))
                    .child(Icon::new(IconName::Plus).size_3())
                    .child("添加")
                    .on_click(cx.listener(move |this, _, window, cx| {
                        this.begin(Editing::NewAsset(index), window, cx)
                    })),
            );
        let mut card = div()
            .flex()
            .flex_col()
            .rounded(px(18.))
            .border_1()
            .border_color(p.line)
            .bg(p.card)
            .overflow_hidden()
            .mt(px(11.));
        if self.editing == Some(Editing::NewAsset(index)) {
            card = card.child(
                div()
                    .flex()
                    .items_center()
                    .gap_2()
                    .px_4()
                    .py(px(10.))
                    .bg(p.subtle)
                    .border_b_1()
                    .border_color(p.line)
                    .child(self.inline_input(false))
                    .child(self.inline_input(true))
                    .child(self.edit_controls(p, cx)),
            );
        }
        if assets.is_empty() && self.editing != Some(Editing::NewAsset(index)) {
            card = card.child(
                div()
                    .py_5()
                    .px_4()
                    .text_center()
                    .text_size(px(12.5))
                    .text_color(p.ink3)
                    .child("还没有记录，点击右侧「添加」记下第一笔"),
            );
        }
        for a in assets {
            card = card.child(self.asset_row(p, a, cx));
            if self.history == Some(a.id) {
                card = card.child(self.asset_history_panel(p, a, cx));
            }
        }
        div().flex().flex_col().child(header).child(card)
    }

    pub(super) fn render_assets(&self, p: Palette, cx: &mut gpui::Context<Self>) -> Div {
        let mut sums = [0i64; 5];
        let mut overflow = false;
        for a in &self.data.assets {
            if let Some(i) = CATEGORIES.iter().position(|(c, _)| *c == a.category_code) {
                match sums[i].checked_add(a.amount_cents) {
                    Some(v) => sums[i] = v,
                    None => overflow = true,
                }
            }
        }
        let total = sums[..4]
            .iter()
            .try_fold(0i64, |sum, v| sum.checked_add(*v));
        let debt = sums[4];
        let net = total.and_then(|v| v.checked_sub(debt));
        let shown = |v: Option<i64>| {
            v.map(|v| money(v, "CNY"))
                .unwrap_or_else(|| "超出范围".to_string())
        };
        let mut sum_bar = div()
            .flex()
            .pt(px(2.))
            .pb(px(30.))
            .border_b_1()
            .border_color(p.line)
            .child(p.sum_item("总资产", shown(total).into(), p.text, false))
            .child(p.sum_item("总负债", money(debt, "CNY").into(), p.neg, false))
            .child(p.sum_item("净资产", shown(net).into(), p.text, true));
        if overflow || total.is_none() {
            sum_bar = sum_bar.child(
                div()
                    .flex_1()
                    .text_right()
                    .text_size(px(12.))
                    .text_color(p.neg)
                    .child("金额合计超出整数范围，请检查资产数据"),
            );
        }
        let mut sections = div().flex().flex_col().gap(px(34.));
        for (index, (code, label)) in CATEGORIES.iter().enumerate() {
            sections = sections.child(self.asset_section(p, index, code, label, cx));
        }
        div()
            .flex()
            .flex_col()
            .child(
                div()
                    .flex()
                    .items_start()
                    .justify_between()
                    .child(p.head(
                        "资产与负债".into(),
                        format!("{} 项记录 · 5 个固定分类", self.data.assets.len()).into(),
                    ))
                    .child(self.sync_button(p, cx)),
            )
            .child(div().h(px(34.)).flex_none())
            .child(sum_bar)
            .child(div().h(px(34.)).flex_none())
            .child(sections)
    }
}
