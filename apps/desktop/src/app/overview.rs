//! 总览（§9.2）：净资产焦点 + 资产构成 / 即将扣费两列。

use gpui::SharedString;
use gpui::{prelude::*, *};

use super::{
    state::SnowFold,
    widgets::{
        AmountLevel, M, SB, avatar, badge, btn_primary, card, focus_amount, group_head, icon_block,
        sizes, t,
    },
};
use crate::theme::Palette;
use snowfold_core::model::{self, CATEGORIES, money, today};

pub(super) fn short_date(date: chrono::NaiveDate) -> String {
    use chrono::Datelike;
    if date.year() == today().year() {
        format!("{}月{}日", date.month(), date.day())
    } else {
        format!("{}年{}月{}日", date.year(), date.month(), date.day())
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

/// 占比显示：保留 1 位小数；非零但四舍五入为 0.0% 时显示「<0.1%」。
pub(super) fn pct_text(ratio: f64) -> String {
    if ratio > 0. && (ratio * 10.).round() < 1. {
        "<0.1%".into()
    } else {
        format!("{ratio:.1}%")
    }
}

impl SnowFold {
    pub(super) fn render_overview(&self, p: &Palette, cx: &mut gpui::Context<Self>) -> Div {
        if self.data.assets.is_empty() {
            return self.overview_empty(p, cx);
        }
        let (sums, counts, overflow, total, debt) = category_sums(&self.data);
        let _ = overflow;
        let net = total.and_then(|v| v.checked_sub(debt));
        let shown = |v: Option<i64>| {
            v.map(|v| money(v, "CNY"))
                .unwrap_or_else(|| "超出范围".into())
        };
        let negative = net.map(|v| v < 0).unwrap_or(false);

        let mut body = div()
            .debug_selector(|| "overview-body".to_string())
            .flex()
            .flex_col()
            // 焦点：净资产（大标题）。
            .child(
                div()
                    .flex()
                    .flex_col()
                    .child(
                        div()
                            .debug_selector(|| "overview-label".to_string())
                            .text_size(px(sizes::BODY))
                            .line_height(px(sizes::BODY_LH))
                            .child(t("净资产", p.text2, M)),
                    )
                    .child(focus_amount(&shown(net), AmountLevel::Hero, negative, p).mt(px(4.))),
            )
            .child(
                div()
                    .flex()
                    .items_start()
                    .gap(px(32.))
                    .mt(px(16.))
                    .child(metric("总资产", shown(total), p.text, p))
                    .child(metric("总负债", money(-debt, "CNY"), p.neg, p))
                    .child(metric(
                        "负债率",
                        &total
                            .filter(|v| *v > 0)
                            .map(|v| pct_text(debt as f64 / v as f64 * 100.))
                            .unwrap_or_else(|| "—".into()),
                        p.text,
                        p,
                    )),
            );

        body = body.child(
            div()
                .flex()
                .mt(px(32.))
                .gap(px(24.))
                .items_start()
                .child(self.composition_card(p, &sums, &counts, total, debt, cx))
                .child(self.upcoming_card(p, cx)),
        );
        body
    }

    fn overview_empty(&self, p: &Palette, cx: &mut gpui::Context<Self>) -> Div {
        super::widgets::empty_state(
            "icons/line/pie-chart.svg",
            "还没有记录",
            "记下第一笔资产，净资产会显示在这里",
            btn_primary("empty-add-asset", "添加资产", p).on_click(cx.listener(
                |this, _, window, cx| this.begin_dialog(super::state::Dialog::AddAsset, window, cx),
            )),
            p,
        )
    }

    /// 左列「资产构成」：五行双行宽松行（最小高 64，文字左缩进 56）。
    fn composition_card(
        &self,
        p: &Palette,
        sums: &[i64; 5],
        counts: &[usize; 5],
        total: Option<i64>,
        debt: i64,
        cx: &mut gpui::Context<Self>,
    ) -> Div {
        let mut card = card().bg(p.card).child(
            // 行与行之间 1px 分隔由行内控制；先放五行。
            div().h(px(0.)),
        );
        for i in 0..5 {
            let liability = i == 4;
            let value = if liability {
                money(-debt, "CNY")
            } else {
                money(sums[i], "CNY")
            };
            let ratio = match (liability, total) {
                (true, Some(t)) if t > 0 => debt as f64 / t as f64 * 100.,
                (false, Some(t)) if t > 0 => sums[i] as f64 / t as f64 * 100.,
                _ => 0.,
            };
            if i > 0 {
                card = card
                    .child(super::widgets::row_separator(56., p))
                    .child(div().h(px(0.)).w_full());
            }
            card = card.child(self.composition_row(p, i, counts[i], value, ratio, cx));
        }
        div()
            .flex_1()
            .min_w(px(0.))
            .flex()
            .flex_col()
            .child(section_head("资产构成", "全部明细", 1, p, cx))
            .child(card)
    }

    fn composition_row(
        &self,
        p: &Palette,
        index: usize,
        count: usize,
        value: impl Into<SharedString>,
        ratio: f64,
        cx: &mut gpui::Context<Self>,
    ) -> Stateful<Div> {
        let liability = index == 4;
        let fill = if liability { p.neg } else { p.cat(index).1 };
        let fill_width = if ratio > 0. {
            (ratio / 100.).clamp(0.005, 1.) as f32
        } else {
            0.
        };
        div()
            .debug_selector(move || format!("overview-category-{index}"))
            .id(("overview-category", index))
            .tab_index(0)
            .tab_stop(true)
            .flex()
            .items_center()
            .gap(px(12.))
            .min_h(px(64.))
            .px(px(16.))
            .py(px(12.))
            .cursor_pointer()
            .hover(move |s| s.bg(p.hover()))
            .focus(move |s| s.border_3().border_color(crate::theme::fade(p.brand, 0.4)))
            .on_click(cx.listener(move |this, _, window, cx| {
                this.select(1, window, cx);
                this.categories[index].scroll_to(window, cx);
            }))
            .child(icon_block(index, p, false))
            .child(
                div()
                    .flex()
                    .flex_col()
                    .gap(px(6.))
                    .flex_1()
                    .min_w(px(0.))
                    .child(
                        div()
                            .flex()
                            .items_baseline()
                            .gap(px(8.))
                            .child(t(CATEGORIES[index].1, p.text, M))
                            .child(t(format!("{count} 项"), p.text2, FontWeight::NORMAL))
                            .child(div().flex_1())
                            .child(div().text_size(px(sizes::BODY)).child(n_value(
                                value,
                                if liability { p.neg } else { p.text },
                                p,
                            ))),
                    )
                    .when(liability, |line| {
                        line.child(
                            div()
                                .text_size(px(sizes::AUX))
                                .line_height(px(sizes::AUX_LH))
                                .child(t("不计入资产构成", p.text2, FontWeight::NORMAL)),
                        )
                    })
                    .when(!liability, |line| {
                        line.child(
                            div()
                                .flex()
                                .items_center()
                                .gap(px(12.))
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
                                                .w(relative(fill_width))
                                                .bg(fill),
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
                    }),
            )
    }

    /// 右列「即将扣费」：双行宽松行；待确认在前，其后按日期。
    fn upcoming_card(&self, p: &Palette, cx: &mut gpui::Context<Self>) -> Div {
        let end = today()
            .checked_add_days(chrono::Days::new(30))
            .unwrap_or(today());
        let mut overdue_rows: Vec<&model::Subscription> = Vec::new();
        let mut upcoming_rows: Vec<&model::Subscription> = Vec::new();
        for s in &self.data.subscriptions {
            if s.status != "active" || s.next_charge_on > end {
                continue;
            }
            if s.next_charge_on < today() {
                overdue_rows.push(s);
            } else {
                upcoming_rows.push(s);
            }
        }
        overdue_rows.sort_by_key(|s| (s.next_charge_on, s.id));
        upcoming_rows.sort_by_key(|s| (s.next_charge_on, s.id));
        // 内容区（窗口减标题栏）高 ≥ 840 显示 8 条，否则 5 条。
        let limit = if f32::from(self.content_height()) >= 840. {
            8
        } else {
            5
        };
        let mut rows: Vec<&model::Subscription> = Vec::new();
        for s in &overdue_rows {
            rows.push(s);
        }
        for s in &upcoming_rows {
            rows.push(s);
        }
        rows.truncate(limit);
        let active = self
            .data
            .subscriptions
            .iter()
            .filter(|s| s.status == "active")
            .count();
        let overdue = overdue_rows.len();
        let summary = if active == 0 {
            String::new()
        } else if overdue > 0 {
            format!("{active} 个订阅 · {overdue} 笔待确认")
        } else {
            match upcoming_rows.first() {
                Some(next) => format!(
                    "{active} 个订阅 · 下一笔 {}",
                    short_date(next.next_charge_on)
                ),
                None => format!("{active} 个订阅"),
            }
        };
        let mut card = card().bg(p.card);
        if rows.is_empty() {
            card = card.child(
                div()
                    .px(px(16.))
                    .py(px(22.))
                    .text_size(px(sizes::BODY))
                    .child(t("未来 30 天没有扣费", p.text2, FontWeight::NORMAL)),
            );
        }
        for (i, s) in rows.iter().enumerate() {
            if i > 0 {
                card = card
                    .child(super::widgets::row_separator(60., p))
                    .child(div().h(px(0.)).w_full());
            }
            card = card.child(self.upcoming_row(p, s, cx));
        }
        if !summary.is_empty() {
            let neg = overdue > 0;
            card = card.child(
                div().px(px(16.)).py(px(12.)).child(
                    div()
                        .text_size(px(sizes::AUX))
                        .line_height(px(sizes::AUX_LH))
                        .child(t(
                            summary,
                            if neg { p.neg } else { p.text2 },
                            FontWeight::NORMAL,
                        )),
                ),
            );
        }
        div()
            .flex_1()
            .min_w(px(0.))
            .flex()
            .flex_col()
            .child(section_head("即将扣费", "全部订阅", 2, p, cx))
            .child(card)
    }

    fn upcoming_row(
        &self,
        p: &Palette,
        s: &model::Subscription,
        cx: &mut gpui::Context<Self>,
    ) -> Stateful<Div> {
        let days = s.next_charge_on.signed_duration_since(today()).num_days();
        let (badge_text, badge_bg, badge_fg) = if days < 0 {
            (format!("逾期 {} 天", -days), p.neg_soft, p.neg)
        } else if days == 0 {
            ("今天".to_string(), p.neg_soft, p.neg)
        } else if days <= 7 {
            (format!("{days} 天后"), p.warn_soft, p.warn)
        } else {
            (format!("{days} 天后"), p.card, p.text2)
        };
        let (avatar_bg, avatar_fg) = if days < 0 {
            (p.neg_soft, p.neg)
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
        div()
            .debug_selector(move || "upcoming-row".to_string())
            .id((ElementId::from("upcoming"), s.id.to_string()))
            .tab_index(0)
            .tab_stop(true)
            .flex()
            .items_center()
            .gap(px(12.))
            .min_h(px(64.))
            .px(px(16.))
            .py(px(12.))
            .cursor_pointer()
            .hover(move |row| row.bg(p.hover()))
            .focus(move |s| s.border_3().border_color(crate::theme::fade(p.brand, 0.4)))
            .on_click(cx.listener(|this, _, window, cx| this.select(2, window, cx)))
            .child(avatar(initial, avatar_bg, avatar_fg))
            .child(
                div()
                    .flex()
                    .flex_col()
                    .gap(px(2.))
                    .min_w(px(0.))
                    .child(
                        div()
                            .text_size(px(sizes::BODY))
                            .line_height(px(sizes::BODY_LH))
                            .child(t(s.name.clone(), p.text, M)),
                    )
                    .child(
                        div()
                            .text_size(px(sizes::AUX))
                            .line_height(px(sizes::AUX_LH))
                            .child(t(sub, p.text2, FontWeight::NORMAL)),
                    ),
            )
            .child(badge(badge_text, badge_bg, badge_fg))
            .child(div().flex_1())
            .child(div().flex_none().text_size(px(sizes::BODY)).child(n_value(
                money(s.amount_cents, &s.currency),
                p.text,
                p,
            )))
    }

    fn content_height(&self) -> gpui::Pixels {
        // 由 render 层写入的窗口高度（viewport - 52 工具栏）。
        gpui::px(self.last_content_height)
    }
}

fn n_value(text: impl Into<SharedString>, color: gpui::Rgba, _p: &Palette) -> AnyElement {
    super::widgets::n(text, color, M)
}

/// 分区头：分区标题（15 半粗）+ 右侧文字按钮 brand「全部明细 ›」。
fn section_head(
    title: &'static str,
    link: &'static str,
    page: usize,
    p: &Palette,
    cx: &mut gpui::Context<SnowFold>,
) -> Div {
    div()
        .flex()
        .items_center()
        .justify_between()
        .pb(px(12.))
        .child(
            div()
                .text_size(px(sizes::SECTION))
                .line_height(px(sizes::SECTION_LH))
                .child(t(title, p.text, SB)),
        )
        .child(
            div()
                .id(("section-link", page))
                .flex()
                .items_center()
                .gap(px(4.))
                .h(px(28.))
                .px(px(8.))
                .rounded(px(10.))
                .text_size(px(sizes::BODY))
                .cursor_pointer()
                .hover(move |s| s.bg(p.hover()))
                .focus(move |s| s.border_3().border_color(crate::theme::fade(p.brand, 0.4)))
                .on_click(cx.listener(move |this, _, window, cx| this.select(page, window, cx)))
                .child(t(link, p.brand, FontWeight::NORMAL))
                .child(
                    svg()
                        .path("icons/line/chevron-right.svg")
                        .size(px(16.))
                        .text_color(p.brand),
                ),
        )
}

fn metric(
    label: &'static str,
    value: impl Into<SharedString>,
    color: gpui::Rgba,
    p: &Palette,
) -> Div {
    div()
        .flex()
        .flex_col()
        .gap(px(4.))
        .child(t(label, p.text2, FontWeight::NORMAL))
        .child(super::widgets::n(value, color, M))
}

pub(super) fn category_sums(
    data: &model::Ledger,
) -> ([i64; 5], [usize; 5], bool, Option<i64>, i64) {
    let mut sums = [0i64; 5];
    let mut counts = [0usize; 5];
    let mut overflow = false;
    for a in &data.assets {
        if let Some(i) = CATEGORIES.iter().position(|(c, _)| *c == a.category_code) {
            match sums[i].checked_add(a.amount_cents) {
                Some(v) => sums[i] = v,
                None => overflow = true,
            }
            counts[i] += 1;
        }
    }
    let total = sums[..4]
        .iter()
        .try_fold(0i64, |sum, v| sum.checked_add(*v));
    let debt = sums[4];
    (sums, counts, overflow, total, debt)
}

/// 分组头（组头辅助中等 + 数量）。
pub(super) fn group_head_pub(label: &'static str, count: usize, neg: bool, p: &Palette) -> Div {
    group_head(label, count, neg, p)
}
