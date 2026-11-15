//! 资产（§9.3，两栏）：左栏五个分类，右栏未选中时总资产概览、选中时条目详情。

use gpui::SharedString;
use gpui::{prelude::*, *};

use super::{
    overview::{category_sums, pct_text},
    state::{Dialog, SnowFold},
    widgets::{
        AmountLevel, M, SB, btn_primary, btn_secondary, btn_text, card, focus_amount, icon_block,
        sizes, t,
    },
};
use crate::theme::Palette;
use snowfold_core::model::{self, CATEGORIES, money};

/// RFC3339 → 「2026年11月14日 18:49」（解析失败原样返回）。
pub(super) fn cn_time(stamp: &str) -> String {
    use chrono::{Datelike, Timelike};
    match chrono::NaiveDateTime::parse_from_str(
        stamp.get(..19).unwrap_or(stamp),
        "%Y-%m-%dT%H:%M:%S",
    ) {
        Ok(t) => format!(
            "{}年{}月{}日 {:02}:{:02}",
            t.year(),
            t.month(),
            t.day(),
            t.hour(),
            t.minute()
        ),
        Err(_) => stamp.to_string(),
    }
}

impl SnowFold {
    pub(super) fn render_assets(&self, p: &Palette, cx: &mut gpui::Context<Self>) -> Div {
        if self.data.assets.is_empty() {
            return super::widgets::empty_state(
                "icons/line/wallet.svg",
                "还没有资产",
                "现金、房产、投资、借出和欠款都可以记",
                btn_primary("empty-add-asset", "添加资产", p).on_click(cx.listener(
                    |this, _, window, cx| this.begin_dialog(Dialog::AddAsset, window, cx),
                )),
                p,
            );
        }
        let (sums, counts, overflow, total, debt) = category_sums(&self.data);
        let _ = counts;
        let shown = |v: Option<i64>| {
            v.map(|v| money(v, "CNY"))
                .unwrap_or_else(|| "超出范围".into())
        };
        let left_width = self.last_content_width + 300. >= 1400.;
        let left = if left_width {
            440.
        } else {
            380_f32.min(self.last_content_width * 0.49)
        };
        let mut body = div().flex().gap(px(24.)).items_start().child(
            div()
                .w(px(left))
                .flex_none()
                .flex()
                .flex_col()
                .gap(px(24.))
                .child(self.asset_sections(p, &sums, overflow, cx)),
        );
        body = body.child(div().flex_1().min_w(px(0.)).flex().flex_col().child(
            match self.selected {
                Some(id) => self.asset_detail(p, id, cx),
                None => self.assets_summary(p, total, debt, overflow, &shown),
            },
        ));
        body
    }

    /// 左栏五个分区（间距 24）：分区头 + 卡片行；空分类用紧凑空态行。
    fn asset_sections(
        &self,
        p: &Palette,
        sums: &[i64; 5],
        overflow: bool,
        cx: &mut gpui::Context<Self>,
    ) -> Div {
        let mut sections = div().flex().flex_col().gap(px(24.));
        for (index, (code, label)) in model::CATEGORIES.iter().enumerate() {
            let assets: Vec<&model::Asset> = self
                .data
                .assets
                .iter()
                .filter(|a| a.category_code == *code)
                .collect();
            let sum = assets
                .iter()
                .try_fold(0i64, |v, a| v.checked_add(a.amount_cents));
            let header = div()
                .flex()
                .items_center()
                .gap(px(12.))
                .px(px(4.))
                .pb(px(8.))
                .pt(px(4.))
                .child(icon_block(index, p, false))
                .child(
                    div()
                        .text_size(px(sizes::SECTION))
                        .line_height(px(sizes::SECTION_LH))
                        .child(t(*label, p.text, SB)),
                )
                .child(t(
                    format!("{} 项", assets.len()),
                    p.text2,
                    FontWeight::NORMAL,
                ))
                .child(div().flex_1())
                .child(
                    div().text_size(px(sizes::BODY)).child(super::widgets::n(
                        sum.map(|v| money(if index == 4 { -v } else { v }, "CNY"))
                            .unwrap_or_else(|| "合计超出范围".into()),
                        if index == 4 { p.neg } else { p.text },
                        M,
                    )),
                );
            let mut list = card().bg(p.card);
            if assets.is_empty() {
                list = list.child(
                    div()
                        .flex()
                        .items_center()
                        .gap(px(12.))
                        .px(px(16.))
                        .py(px(12.))
                        .child(icon_block(index, p, false))
                        .child(div().flex_1().text_size(px(sizes::BODY)).child(t(
                            format!("暂无{}", CATEGORIES[index].1),
                            p.text2,
                            FontWeight::NORMAL,
                        )))
                        .child(
                            super::widgets::btn_soft(("add-empty", index), "＋ 添加", p).on_click(
                                cx.listener(move |this, _, window, cx| {
                                    this.new_category = index;
                                    this.begin_dialog(Dialog::AddAsset, window, cx);
                                }),
                            ),
                        ),
                );
            }
            for (row_index, a) in assets.iter().enumerate() {
                if row_index > 0 {
                    list = list
                        .child(super::widgets::row_separator(16., p))
                        .child(div().h(px(0.)).w_full());
                }
                list = list.child(self.asset_row(p, a, cx));
            }
            let section = div()
                .flex()
                .flex_col()
                .child(header)
                .when(overflow, |s| {
                    s.child(div().text_size(px(sizes::AUX)).child(t(
                        "金额合计超出整数范围，请检查资产数据",
                        p.neg,
                        FontWeight::NORMAL,
                    )))
                })
                .child(list);
            sections = sections.child(section);
            let _ = sums;
        }
        sections
    }

    fn asset_row(
        &self,
        p: &Palette,
        a: &model::Asset,
        cx: &mut gpui::Context<Self>,
    ) -> Stateful<Div> {
        let asset_id = a.id;
        let selected = self.selected == Some(a.id);
        let highlight = self.highlight == Some(a.id);
        div()
            .debug_selector({
                let name = a.name.clone();
                move || name.to_string()
            })
            .id((ElementId::from("asset-row"), a.id.to_string()))
            .tab_index(0)
            .tab_stop(true)
            .when(highlight, |row| {
                row.anchor_scroll(Some(self.new_item.clone()))
            })
            .flex()
            .items_center()
            .gap(px(12.))
            .min_h(px(44.))
            .px(px(16.))
            .py(px(10.))
            .cursor_pointer()
            .when(selected, |s| s.bg(p.brand_soft))
            .when(!selected && highlight, |s| s.bg(p.hover()))
            .hover(move |s| if selected { s } else { s.bg(p.hover()) })
            .focus(move |s| s.border_3().border_color(crate::theme::fade(p.brand, 0.4)))
            .on_click(cx.listener(move |this, _, _, cx| {
                this.selected = Some(asset_id);
                cx.notify();
            }))
            .child(
                div()
                    .flex_1()
                    .min_w(px(0.))
                    .text_size(px(sizes::BODY))
                    .line_height(px(sizes::BODY_LH))
                    .child(t(
                        a.name.clone(),
                        if selected { p.brand } else { p.text },
                        if selected { M } else { FontWeight::NORMAL },
                    )),
            )
            .child(
                div()
                    .flex_none()
                    .text_size(px(sizes::BODY))
                    .child(super::widgets::n(
                        money(
                            if a.category_code == "debt" {
                                -a.amount_cents
                            } else {
                                a.amount_cents
                            },
                            "CNY",
                        ),
                        if selected { p.brand } else { p.text },
                        M,
                    )),
            )
    }

    /// 右栏未选中：总资产概览（页标题焦点）。
    fn assets_summary(
        &self,
        p: &Palette,
        total: Option<i64>,
        debt: i64,
        overflow: bool,
        shown: &dyn Fn(Option<i64>) -> String,
    ) -> Div {
        let mut body = div()
            .flex()
            .flex_col()
            .gap(px(16.))
            .child(t("总资产", p.text2, M))
            .child(focus_amount(&shown(total), AmountLevel::Page, false, p))
            .child(
                div()
                    .flex()
                    .flex_wrap()
                    .gap_x(px(32.))
                    .gap_y(px(16.))
                    .child(summary_metric("总负债", money(-debt, "CNY"), p.neg, p))
                    .child(summary_metric(
                        "净资产",
                        &total
                            .map(|v| v - debt)
                            .map(|v| money(v, "CNY"))
                            .unwrap_or_else(|| "超出范围".into()),
                        p.text,
                        p,
                    ))
                    .child(summary_metric(
                        "记录",
                        format!("{} 项", self.data.assets.len()),
                        p.text,
                        p,
                    )),
            );
        if overflow {
            body = body.child(div().text_size(px(sizes::AUX)).child(t(
                "金额合计超出整数范围，请检查资产数据",
                p.neg,
                FontWeight::NORMAL,
            )));
        }
        // 四个资产分类的构成卡（负债不计入）。
        let mut card = card().bg(p.card).mt(px(8.));
        for i in 0..4 {
            if i > 0 {
                card = card
                    .child(super::widgets::row_separator(60., p))
                    .child(div().h(px(0.)).w_full());
            }
            let ratio = total
                .filter(|v| *v > 0)
                .map(|v| self.sums_cached[i] as f64 / v as f64 * 100.)
                .unwrap_or(0.);
            let (bg, glyph) = p.cat(i);
            card = card.child(
                div()
                    .flex()
                    .flex_col()
                    .gap(px(6.))
                    .px(px(16.))
                    .py(px(12.))
                    .child(
                        div()
                            .flex()
                            .items_center()
                            .gap(px(12.))
                            .child(icon_block(i, p, false))
                            .child(t(model::CATEGORIES[i].1, p.text, M))
                            .child(div().flex_1())
                            .child(div().text_size(px(sizes::BODY)).child(super::widgets::n(
                                money(self.sums_cached[i], "CNY"),
                                p.text,
                                M,
                            ))),
                    )
                    .child(
                        div()
                            .flex()
                            .items_center()
                            .gap(px(12.))
                            .pl(px(40.))
                            .child(
                                div()
                                    .flex_1()
                                    .h(px(4.))
                                    .rounded_full()
                                    .bg(p.bg)
                                    .overflow_hidden()
                                    .child(
                                        div()
                                            .h_full()
                                            .rounded_full()
                                            .w(relative(if ratio > 0. {
                                                (ratio / 100.).clamp(0.005, 1.) as f32
                                            } else {
                                                0.
                                            }))
                                            .bg(glyph),
                                    ),
                            )
                            .child(
                                div()
                                    .w(px(44.))
                                    .flex_none()
                                    .text_right()
                                    .text_size(px(sizes::AUX))
                                    .child(t(pct_text(ratio), p.text2, FontWeight::NORMAL)),
                            ),
                    )
                    .when(i == 3, |row| {
                        let _ = bg;
                        row
                    }),
            );
        }
        body.child(card)
    }

    /// 右栏选中条目：详情 + 变动记录。
    fn asset_detail(&self, p: &Palette, id: model::Id, cx: &mut gpui::Context<Self>) -> Div {
        let Some(a) = self.data.assets.iter().find(|a| a.id == id) else {
            return div();
        };
        let category_index = model::CATEGORIES
            .iter()
            .position(|(code, _)| *code == a.category_code)
            .unwrap_or(0);
        let history: Vec<&model::History> = self
            .data
            .history
            .iter()
            .filter(|h| h.asset_id == id)
            .collect();
        let updated = a.updated_at.clone();
        let mut body = div()
            .flex()
            .flex_col()
            .gap(px(4.))
            .child(
                div()
                    .flex()
                    .items_center()
                    .gap(px(8.))
                    .child(
                        div()
                            .flex()
                            .items_center()
                            .justify_center()
                            .size(px(24.))
                            .rounded(px(8.))
                            .bg(p.cat(category_index).0)
                            .child(
                                svg()
                                    .path(super::widgets::category_icon(category_index, false))
                                    .size(px(16.))
                                    .text_color(p.cat(category_index).1),
                            ),
                    )
                    .child(div().text_size(px(sizes::AUX)).child(t(
                        model::CATEGORIES[category_index].1,
                        p.text2,
                        FontWeight::NORMAL,
                    ))),
            )
            .child(
                div()
                    .mt(px(4.))
                    .text_size(px(sizes::SECTION))
                    .line_height(px(sizes::SECTION_LH))
                    .child(t(a.name.clone(), p.text, SB)),
            )
            .child(
                focus_amount(
                    &money(
                        if a.category_code == "debt" {
                            -a.amount_cents
                        } else {
                            a.amount_cents
                        },
                        "CNY",
                    ),
                    AmountLevel::Page,
                    a.category_code == "debt",
                    p,
                )
                .mt(px(8.)),
            )
            .child(
                div()
                    .mt(px(8.))
                    .text_size(px(sizes::AUX))
                    .line_height(px(sizes::AUX_LH))
                    .child(t(
                        format!(
                            "更新于 {} · 历史 {} 条",
                            cn_time(&updated)
                                .split(' ')
                                .next()
                                .map(|s| s.to_string())
                                .unwrap_or_else(|| updated.clone()),
                            history.len()
                        ),
                        p.text2,
                        FontWeight::NORMAL,
                    )),
            )
            .child(
                div()
                    .flex()
                    .items_center()
                    .gap(px(8.))
                    .mt(px(16.))
                    .child(
                        btn_primary("adjust-balance", "调整余额", p).on_click(cx.listener(
                            move |this, _, window, cx| {
                                this.begin_dialog(Dialog::AdjustBalance(id), window, cx)
                            },
                        )),
                    )
                    .child(
                        btn_secondary("rename-asset", "重命名", p).on_click(cx.listener(
                            move |this, _, window, cx| {
                                this.begin_dialog(Dialog::RenameAsset(id), window, cx)
                            },
                        )),
                    )
                    .child(div().flex_1())
                    .child(
                        btn_text("delete-asset", "删除", p.neg, p).on_click(cx.listener(
                            move |this, _, window, cx| {
                                this.selected = None;
                                this.delete_asset(id, window, cx);
                            },
                        )),
                    ),
            );
        body = body.child(
            div()
                .mt(px(24.))
                .flex()
                .items_baseline()
                .gap(px(8.))
                .child(
                    div()
                        .text_size(px(sizes::SECTION))
                        .line_height(px(sizes::SECTION_LH))
                        .child(t("变动记录", p.text, SB)),
                )
                .child(div().text_size(px(sizes::AUX)).child(t(
                    "修正不会新增快照",
                    p.text2,
                    FontWeight::NORMAL,
                ))),
        );
        let mut card = card().bg(p.card).mt(px(12.));
        if history.is_empty() {
            card = card.child(
                div()
                    .px(px(16.))
                    .py(px(16.))
                    .text_size(px(sizes::BODY))
                    .child(t("还没有变动记录", p.text2, FontWeight::NORMAL)),
            );
        }
        for (position, h) in history.iter().enumerate().rev() {
            if position + 1 < history.len() {
                card = card
                    .child(super::widgets::row_separator(16., p))
                    .child(div().h(px(0.)).w_full());
            }
            let delta = position
                .checked_sub(1)
                .map(|i| h.amount_cents - history[i].amount_cents);
            let is_current = position + 1 == history.len();
            let hid = h.id;
            card = card.child(
                div()
                    .flex()
                    .items_center()
                    .gap(px(12.))
                    .px(px(16.))
                    .py(px(12.))
                    .min_h(px(56.))
                    .child(
                        div()
                            .flex()
                            .flex_col()
                            .gap(px(4.))
                            .min_w(px(0.))
                            .child(
                                div()
                                    .text_size(px(sizes::BODY))
                                    .line_height(px(sizes::BODY_LH))
                                    .child(t(cn_time(&h.recorded_at), p.text, FontWeight::NORMAL)),
                            )
                            .child(
                                div()
                                    .flex()
                                    .items_center()
                                    .gap(px(8.))
                                    .children(if is_current {
                                        Some(
                                            super::widgets::badge("当前", p.brand_soft, p.brand)
                                                .into_any_element(),
                                        )
                                    } else {
                                        None
                                    })
                                    .child(
                                        div().text_size(px(sizes::AUX)).child(t(
                                            delta
                                                .map(|v| {
                                                    format!(
                                                        "{}{}",
                                                        if v > 0 { "+" } else { "" },
                                                        money(v, "CNY")
                                                    )
                                                })
                                                .unwrap_or_else(|| "初始".into()),
                                            p.text2,
                                            FontWeight::NORMAL,
                                        )),
                                    ),
                            ),
                    )
                    .child(div().flex_1())
                    .child(div().text_size(px(sizes::BODY)).child(super::widgets::n(
                        money(h.amount_cents, "CNY"),
                        p.text,
                        FontWeight::NORMAL,
                    )))
                    .child(
                        btn_text(
                            (ElementId::from("correct-history"), hid.to_string()),
                            "修正",
                            p.brand,
                            p,
                        )
                        .on_click(cx.listener(
                            move |this, _, window, cx| {
                                this.begin_dialog(Dialog::CorrectHistory(hid), window, cx)
                            },
                        )),
                    ),
            );
        }
        body.child(card)
    }
}

fn summary_metric(
    label: &'static str,
    value: impl Into<SharedString>,
    color: gpui::Rgba,
    p: &Palette,
) -> Div {
    div()
        .debug_selector(move || format!("asset-summary-{label}"))
        .flex_none()
        .flex()
        .flex_col()
        .gap(px(4.))
        .child(t(label, p.text2, FontWeight::NORMAL))
        .child(super::widgets::n(value, color, M))
}
