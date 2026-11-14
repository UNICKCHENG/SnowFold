use super::{
    components::{Palette, hero_value, meta_item, short_date, today_cn},
    state::{Editing, SnowFold},
};
use crate::model::{self, CATEGORIES, money, today};
use gpui::{prelude::*, *};
use gpui_component::{Icon, IconName};

impl SnowFold {
    pub(super) fn render_overview(&self, p: Palette, cx: &mut gpui::Context<Self>) -> Div {
        let mut sums = [0i64; 5];
        let mut counts = [0; 5];
        let mut overflow = false;
        for a in &self.data.assets {
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
        let net = total.and_then(|v| v.checked_sub(debt));
        let weekday = ["日", "一", "二", "三", "四", "五", "六"]
            [chrono::Datelike::weekday(&today()).num_days_from_sunday() as usize];
        let mut body = div().flex().flex_col().child(
            div()
                .flex()
                .items_start()
                .justify_between()
                .child(p.head(
                    "总览".into(),
                    format!("{} 星期{} · 数据已保存在本机", today_cn(), weekday).into(),
                ))
                .child(self.sync_button(p, cx)),
        );
        if overflow || total.is_none() {
            body = body.child(
                p.panel()
                    .mb(px(24.))
                    .text_color(p.neg)
                    .child("金额合计超出整数范围，请检查资产数据"),
            );
        }
        let stats = model::totals(&self.data.subscriptions, today());
        let (monthly, due_note) = match &stats {
            Ok(s) => (
                format!("{} / {}", money(s[0][0], "CNY"), money(s[1][0], "USD")),
                String::new(),
            ),
            Err(e) => (format!("统计错误：{e}"), String::new()),
        };
        let _ = due_note;
        let ratio = total
            .filter(|v| *v > 0)
            .map(|v| format!("{:.1}%", debt as f64 / v as f64 * 100.))
            .unwrap_or_else(|| "—".into());
        let text_of = |v: Option<i64>| {
            v.map(|v| money(v, "CNY"))
                .unwrap_or_else(|| "超出范围".into())
        };
        let assets_text = text_of(total);
        let debt_text = money(debt, "CNY");
        let net_text = text_of(net);
        let active_subs = self
            .data
            .subscriptions
            .iter()
            .filter(|s| s.status == "active")
            .count();
        let overdue = self
            .data
            .subscriptions
            .iter()
            .filter(|s| s.status == "active" && s.next_charge_on < today())
            .count();
        let next_charge = self
            .data
            .subscriptions
            .iter()
            .filter(|s| s.status == "active")
            .map(|s| s.next_charge_on)
            .min();
        let side_note = if active_subs == 0 {
            "暂无扣费".to_string()
        } else if overdue > 0 {
            format!("{active_subs} 个订阅 · {overdue} 笔待确认")
        } else if let Some(next) = next_charge {
            format!("{active_subs} 个订阅 · 下一笔 {}", short_date(next))
        } else {
            format!("{active_subs} 个订阅")
        };
        let hero = div()
            .flex()
            .items_end()
            .justify_between()
            .gap(px(36.))
            .mb(px(44.))
            .child(
                div()
                    .flex()
                    .flex_col()
                    .min_w(px(0.))
                    .child(
                        div()
                            .text_size(px(12.))
                            .font_weight(FontWeight::MEDIUM)
                            .text_color(p.ink3)
                            .mb(px(10.))
                            .child("净资产"),
                    )
                    .child(hero_value(p, net_text, net.map(|v| v < 0).unwrap_or(false)))
                    .child(
                        div()
                            .flex()
                            .items_center()
                            .gap(px(22.))
                            .mt(px(16.))
                            .text_size(px(12.5))
                            .text_color(p.muted)
                            .child(meta_item(p, "总资产", assets_text, p.text))
                            .child(div().w(px(1.)).h(px(11.)).bg(p.line))
                            .child(meta_item(p, "总负债", debt_text.clone(), p.neg))
                            .child(div().w(px(1.)).h(px(11.)).bg(p.line))
                            .child(meta_item(p, "负债率", ratio, p.text)),
                    ),
            )
            .child(
                div()
                    .debug_selector(|| "monthly-summary".to_string())
                    .id("monthly-summary")
                    .tab_index(0)
                    .tab_stop(p.keyboard)
                    .flex()
                    .flex_col()
                    .items_end()
                    .gap(px(5.))
                    .pt(px(4.))
                    .pb(px(6.))
                    .pl(px(34.))
                    .border_l_1()
                    .border_color(p.line)
                    .border_color(transparent_black())
                    .focus(move |s| s.border_color(p.accent))
                    .cursor_pointer()
                    .on_click(cx.listener(|this, _, window, cx| this.select(2, window, cx)))
                    .child(
                        div()
                            .text_size(px(11.5))
                            .text_color(p.ink3)
                            .child("本月待扣"),
                    )
                    .child(
                        div()
                            .text_size(px(20.))
                            .font_weight(FontWeight::SEMIBOLD)
                            .child(monthly),
                    )
                    .child(
                        div()
                            .text_size(px(11.5))
                            .text_color(p.muted)
                            .child(side_note),
                    ),
            );
        body = body.child(hero);
        let mut bento = Vec::new();
        for i in 0..5 {
            let share = total
                .filter(|v| *v > 0)
                .map(|v| sums[i] as f64 / v as f64 * 100.)
                .unwrap_or(0.);
            let value = if i == 4 {
                debt_text.clone()
            } else {
                money(sums[i], "CNY")
            };
            let kinds = if i == 4 {
                total
                    .filter(|v| *v > 0)
                    .map(|v| debt as f64 / v as f64 * 100.)
                    .unwrap_or(0.)
            } else {
                share
            };
            bento.push(self.category_card(p, i, counts[i], value, kinds, share, cx));
        }
        let mut grid = div().flex().flex_col().gap(px(14.)).child(
            div()
                .flex()
                .gap(px(14.))
                .child(bento.remove(0))
                .child(bento.remove(0)),
        );
        let mut second = div().flex().gap(px(14.));
        for card in bento {
            second = second.child(card);
        }
        grid = grid.child(second);
        body = body.child(
            div()
                .flex()
                .flex_col()
                .child(self.section_row(p, "资产构成", "全部明细", "goto-assets", 1, cx))
                .child(grid),
        );
        let end = today()
            .checked_add_days(chrono::Days::new(30))
            .unwrap_or(today());
        let mut upcoming = self
            .data
            .subscriptions
            .iter()
            .filter(|s| s.status == "active" && s.next_charge_on <= end)
            .collect::<Vec<_>>();
        upcoming.sort_by_key(|s| (s.next_charge_on, s.id));
        let mut list = div()
            .rounded(px(18.))
            .border_1()
            .border_color(p.line)
            .bg(p.card)
            .overflow_hidden();
        if upcoming.is_empty() {
            list = list.child(
                div()
                    .px(px(18.))
                    .py(px(22.))
                    .text_size(px(12.5))
                    .text_color(p.ink3)
                    .child("未来 30 天没有待扣费用"),
            );
        }
        for (n, s) in upcoming.iter().take(5).enumerate() {
            let days = s.next_charge_on.signed_duration_since(today()).num_days();
            let (badge_text, badge_bg, badge_fg) = if days < 0 {
                (format!("逾期 {} 天", -days), p.neg_soft, p.neg)
            } else if days == 0 {
                ("今天".to_string(), p.neg_soft, p.neg)
            } else if days <= 7 {
                (format!("{days} 天后"), p.accent_soft, p.accent)
            } else {
                (format!("{days} 天后"), p.subtle, p.ink3)
            };
            let date = short_date(s.next_charge_on);
            list = list.child(
                div()
                    .debug_selector(move || "upcoming-row".to_string())
                    .id((ElementId::from("upcoming"), s.id.to_string()))
                    .tab_index(0)
                    .tab_stop(p.keyboard)
                    .flex()
                    .items_center()
                    .gap(px(14.))
                    .h(px(52.))
                    .px(px(18.))
                    .border_1()
                    .border_color(transparent_black())
                    .focus(move |s| s.border_color(p.accent))
                    .when(n > 0, |row| row.border_t_1().border_color(p.line))
                    .cursor_pointer()
                    .hover(move |row| row.bg(p.hover))
                    .on_click(cx.listener(|this, _, window, cx| this.select(2, window, cx)))
                    .child(
                        div()
                            .flex_none()
                            .w(px(74.))
                            .text_size(px(12.5))
                            .text_color(p.muted)
                            .child(date),
                    )
                    .child(
                        div()
                            .flex_1()
                            .min_w(px(0.))
                            .truncate()
                            .text_size(px(13.5))
                            .font_weight(FontWeight::MEDIUM)
                            .child(s.name.clone()),
                    )
                    .child(
                        div()
                            .flex_none()
                            .flex()
                            .items_center()
                            .h(px(21.))
                            .px(px(8.))
                            .rounded(px(99.))
                            .text_size(px(11.))
                            .font_weight(FontWeight::MEDIUM)
                            .bg(badge_bg)
                            .text_color(badge_fg)
                            .child(badge_text),
                    )
                    .child(
                        div()
                            .flex_none()
                            .w(px(82.))
                            .text_right()
                            .text_size(px(13.5))
                            .font_weight(FontWeight::MEDIUM)
                            .child(money(s.amount_cents, &s.currency)),
                    ),
            );
        }
        body.child(
            div()
                .flex()
                .flex_col()
                .mt(px(46.))
                .child(self.section_row(p, "即将扣费", "全部订阅", "goto-subs", 2, cx))
                .child(list),
        )
    }
    /// Section header: small uppercase label on the left, quiet link on the right.
    fn section_row(
        &self,
        p: Palette,
        title: &'static str,
        link: &'static str,
        id: &'static str,
        page: usize,
        cx: &mut gpui::Context<Self>,
    ) -> Stateful<Div> {
        div()
            .id((id, 0usize))
            .flex()
            .items_end()
            .justify_between()
            .gap(px(16.))
            .mb(px(14.))
            .child(
                div()
                    .text_size(px(12.))
                    .font_weight(FontWeight::SEMIBOLD)
                    .text_color(p.ink3)
                    .child(title),
            )
            .child(
                div()
                    .debug_selector(move || id.to_string())
                    .id(id)
                    .tab_index(0)
                    .tab_stop(p.keyboard)
                    .flex()
                    .items_center()
                    .gap(px(4.))
                    .text_size(px(12.5))
                    .text_color(p.muted)
                    .border_1()
                    .border_color(transparent_black())
                    .focus(move |s| s.border_color(p.accent))
                    .cursor_pointer()
                    .hover(move |s| s.text_color(p.text))
                    .on_click(cx.listener(move |this, _, window, cx| this.select(page, window, cx)))
                    .child(link)
                    .child(svg().path("icons/arrow-right.svg").w(px(12.)).h(px(12.))),
            )
    }
    /// One bento category card: icon chip, name, add action, amount and share bar.
    fn category_card(
        &self,
        p: Palette,
        i: usize,
        count: usize,
        value: String,
        bar: f64,
        share: f64,
        cx: &mut gpui::Context<Self>,
    ) -> Stateful<Div> {
        let (chip_bg, chip_fg) = p.category_tint(i);
        let liability = i == 4;
        div()
            .debug_selector(move || format!("overview-category-{i}"))
            .id(("overview-category", i))
            .tab_index(0)
            .tab_stop(p.keyboard)
            .flex()
            .flex_col()
            .flex_1()
            .min_w(px(0.))
            .min_h(px(124.))
            .px(px(18.))
            .pt(px(17.))
            .pb(px(16.))
            .rounded(px(18.))
            .border_1()
            .border_color(transparent_black())
            .focus(move |s| s.border_color(p.accent))
            .bg(p.card)
            .border_color(p.line)
            .hover(move |s| s.border_color(p.hairline_strong))
            .cursor_pointer()
            .on_click(cx.listener(move |this, _, window, cx| {
                this.select(1, window, cx);
                this.categories[i].scroll_to(window, cx);
            }))
            .child(
                div()
                    .flex()
                    .items_center()
                    .gap(px(9.))
                    .child(
                        div()
                            .flex_none()
                            .flex()
                            .items_center()
                            .justify_center()
                            .size(px(27.))
                            .rounded(px(8.))
                            .bg(chip_bg)
                            .child(
                                svg()
                                    .path(Self::category_icon(i))
                                    .w(px(15.))
                                    .h(px(15.))
                                    .text_color(chip_fg),
                            ),
                    )
                    .child(
                        div()
                            .text_size(px(13.))
                            .font_weight(FontWeight::MEDIUM)
                            .text_color(p.muted)
                            .child(CATEGORIES[i].1),
                    )
                    .child(div().flex_1())
                    .child(
                        div()
                            .debug_selector(move || format!("overview-add-{i}"))
                            .id(("overview-add", i))
                            .flex()
                            .items_center()
                            .justify_center()
                            .size(px(24.))
                            .rounded(px(7.))
                            .text_color(p.ink3)
                            .hover(move |s| s.bg(p.subtle).text_color(p.text))
                            .cursor_pointer()
                            .on_click(cx.listener(move |this, _, window, cx| {
                                cx.stop_propagation();
                                this.page = 1;
                                this.categories[i].scroll_to(window, cx);
                                this.begin(Editing::NewAsset(i), window, cx);
                            }))
                            .child(Icon::new(IconName::Plus).size(px(14.))),
                    ),
            )
            .child(
                div()
                    .mt(px(18.))
                    .text_size(px(25.))
                    .font_weight(FontWeight::SEMIBOLD)
                    .text_color(if liability { p.neg } else { p.text })
                    .child(value),
            )
            .child(
                div()
                    .flex()
                    .items_center()
                    .gap(px(10.))
                    .mt(px(14.))
                    .text_size(px(11.5))
                    .text_color(p.ink3)
                    .child(format!("{count} 项"))
                    .child(
                        div()
                            .flex_1()
                            .h(px(3.))
                            .rounded(px(99.))
                            .bg(p.line)
                            .overflow_hidden()
                            .child(
                                div()
                                    .h_full()
                                    .rounded(px(99.))
                                    .w(relative((bar / 100.).clamp(0., 1.) as f32))
                                    .bg(if liability {
                                        rgba(0xe58a7a8c)
                                    } else {
                                        rgba(0xf3f3ee52)
                                    }),
                            ),
                    )
                    .child(
                        div()
                            .flex_none()
                            .w(px(34.))
                            .text_right()
                            .child(format!("{share:.1}%")),
                    ),
            )
    }
}
