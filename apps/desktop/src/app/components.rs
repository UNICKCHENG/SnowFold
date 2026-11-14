use super::state::SnowFold;
use crate::model::today;
use gpui::{prelude::*, *};
use gpui_component::{Icon, IconName};

#[derive(Clone, Copy)]
pub(super) struct Palette {
    pub(super) canvas: Rgba,
    pub(super) card: Rgba,
    pub(super) subtle: Rgba,
    pub(super) hover: Rgba,
    pub(super) text: Rgba,
    pub(super) muted: Rgba,
    pub(super) ink3: Rgba,
    pub(super) accent: Rgba,
    pub(super) pos: Rgba,
    pub(super) neg: Rgba,
    pub(super) line: Rgba,
    pub(super) hairline_strong: Rgba,
    pub(super) accent_soft: Rgba,
    pub(super) pos_soft: Rgba,
    pub(super) neg_soft: Rgba,
    pub(super) mark_b: Rgba,
    pub(super) tabs_bg: Rgba,
    pub(super) active_tab: Rgba,
    pub(super) dark: bool,
    pub(super) keyboard: bool,
}
/// `2026年11月13日` — the long form the reference layout uses in page subtitles.
pub(super) fn today_cn() -> String {
    use chrono::Datelike;
    let d = today();
    format!("{}年{}月{}日", d.year(), d.month(), d.day())
}
/// `11月17日`, or the full date when it falls outside the current year.
pub(super) fn short_date(date: chrono::NaiveDate) -> String {
    use chrono::Datelike;
    if date.year() == today().year() {
        format!("{}月{}日", date.month(), date.day())
    } else {
        format!("{}年{}月{}日", date.year(), date.month(), date.day())
    }
}
/// Hero amount: large integer, smaller currency symbol and decimals.
pub(super) fn hero_value(p: Palette, text: String, negative: bool) -> Div {
    let sym_len = text.chars().next().map(|c| c.len_utf8()).unwrap_or(0);
    let (sym, rest) = text.split_at(sym_len);
    let (int, dec) = match rest.find('.') {
        Some(i) => (&rest[..i], &rest[i..]),
        None => (rest, ""),
    };
    div()
        .flex()
        .items_end()
        .text_size(px(56.))
        .font_weight(FontWeight::SEMIBOLD)
        .text_color(if negative { p.neg } else { p.text })
        .child(
            div()
                .text_size(px(26.))
                .font_weight(FontWeight::MEDIUM)
                .text_color(p.muted)
                .mr(px(2.))
                .child(sym.to_string()),
        )
        .child(div().child(int.to_string()))
        .child(
            div()
                .text_size(px(29.))
                .font_weight(FontWeight::MEDIUM)
                .text_color(p.ink3)
                .child(dec.to_string()),
        )
}
/// `• 总资产 ¥1,420,400.00` meta entry under the hero amount.
pub(super) fn meta_item(p: Palette, label: &'static str, value: String, color: Rgba) -> Div {
    div()
        .flex()
        .items_center()
        .gap(px(7.))
        .child(div().flex_none().size(px(4.)).rounded(px(99.)).bg(p.ink3))
        .child(div().text_color(p.muted).child(label))
        .child(
            div()
                .font_weight(FontWeight::MEDIUM)
                .text_color(color)
                .child(value),
        )
}
impl Palette {
    pub(super) fn new(dark: bool) -> Self {
        Self {
            canvas: rgb(if dark { 0x0b0b0a } else { 0xf1f1ee }),
            card: rgb(if dark { 0x171716 } else { 0xffffff }),
            subtle: rgb(if dark { 0x1e1e1c } else { 0xf7f7f4 }),
            hover: rgb(if dark { 0x1c1c1a } else { 0xfbfbf9 }),
            text: rgb(if dark { 0xf3f3ee } else { 0x191917 }),
            muted: rgb(if dark { 0x9b9b93 } else { 0x6f6f68 }),
            ink3: rgb(if dark { 0x6c6c65 } else { 0x9d9d95 }),
            accent: rgb(if dark { 0x8098ff } else { 0x2b59e0 }),
            pos: rgb(if dark { 0x58c795 } else { 0x1c7a50 }),
            neg: rgb(if dark { 0xe58a7a } else { 0xae4a3c }),
            line: if dark {
                rgba(0xffffff15)
            } else {
                rgba(0x19191717)
            },
            hairline_strong: if dark {
                rgba(0xffffff29)
            } else {
                rgba(0x19191729)
            },
            accent_soft: if dark {
                rgba(0x8098ff26)
            } else {
                rgba(0x2b59e014)
            },
            pos_soft: if dark {
                rgba(0x58c79526)
            } else {
                rgba(0x1c7a5014)
            },
            neg_soft: if dark {
                rgba(0xe58a7a24)
            } else {
                rgba(0xae4a3c14)
            },
            mark_b: rgb(if dark { 0xc9c9bd } else { 0x4a4a3f }),
            tabs_bg: if dark {
                rgba(0xffffff0f)
            } else {
                rgba(0x1919170e)
            },
            active_tab: rgb(if dark { 0x2b2b28 } else { 0xffffff }),
            dark,
            keyboard: true,
        }
    }
    /// Category icon tint: `(chip background, glyph colour)`, matching the
    /// per-category colours of the design reference.
    pub(super) fn category_tint(self, index: usize) -> (Rgba, Rgba) {
        let dark = self.dark;
        match index {
            1 => (rgba(0x8c6d3f1a), rgb(0x8c6d3f)),
            2 => (self.pos_soft, self.pos),
            3 => (rgba(0x6d6d681a), self.muted),
            4 => (rgba(if dark { 0xe58a7a24 } else { 0xae4a3c17 }), self.neg),
            _ => (
                rgba(if dark { 0x8098ff24 } else { 0x2b59e017 }),
                self.accent,
            ),
        }
    }
    pub(super) fn panel(self) -> Div {
        div()
            .flex()
            .flex_col()
            .gap_3()
            .p_5()
            .rounded(px(18.))
            .bg(self.card)
    }
    pub(super) fn head(self, title: SharedString, sub: SharedString) -> Div {
        div()
            .flex()
            .flex_col()
            .gap_1()
            .child(
                div()
                    .text_size(px(25.))
                    .font_weight(FontWeight::SEMIBOLD)
                    .child(title),
            )
            .child(
                div()
                    .text_size(px(12.5))
                    .text_color(self.ink3)
                    .child(sub.to_string()),
            )
    }
    pub(super) fn sum_item(
        self,
        label: &'static str,
        value: SharedString,
        color: Rgba,
        last: bool,
    ) -> Div {
        div()
            .flex()
            .flex_col()
            .gap(px(6.))
            .when(!last, |item| {
                item.mr(px(34.))
                    .pr(px(34.))
                    .border_r_1()
                    .border_color(self.line)
            })
            .child(div().text_size(px(11.5)).text_color(self.ink3).child(label))
            .child(
                div()
                    .text_size(px(22.))
                    .font_weight(FontWeight::SEMIBOLD)
                    .text_color(color)
                    .child(value),
            )
    }
    /// One column of the statistics card: 11.5px ink-3 label over a 19px value,
    /// with an optional muted suffix (the reference puts the service name next
    /// to the next charge date). Hairline separators sit on the right edge.
    pub(super) fn stat_col(
        self,
        label: &'static str,
        value: SharedString,
        alt: Option<SharedString>,
        last: bool,
    ) -> Div {
        div()
            .flex()
            .flex_col()
            .flex_1()
            .px(px(18.))
            .py(px(2.))
            .when(!last, |col| col.border_r_1().border_color(self.line))
            .child(div().text_size(px(11.5)).text_color(self.ink3).child(label))
            .child(
                div()
                    .flex()
                    .items_baseline()
                    .gap(px(6.))
                    .mt(px(5.))
                    .child(
                        div()
                            .text_size(px(19.))
                            .font_weight(FontWeight::SEMIBOLD)
                            .text_color(self.text)
                            .child(value),
                    )
                    .children(alt.map(|alt| {
                        div()
                            .text_size(px(14.8))
                            .font_weight(FontWeight::MEDIUM)
                            .text_color(self.ink3)
                            .child(alt)
                    })),
            )
    }
    /// Section header that sits above a card, per the reference: 11.5px ink-3
    /// label plus a tabular count.
    pub(super) fn group_head(self, title: &'static str, count: usize) -> Div {
        div()
            .flex()
            .items_center()
            .gap(px(8.))
            .px(px(2.))
            .mb(px(10.))
            .child(
                div()
                    .text_size(px(11.5))
                    .font_weight(FontWeight::SEMIBOLD)
                    .text_color(self.ink3)
                    .child(title),
            )
            .child(
                div()
                    .text_size(px(11.))
                    .text_color(self.ink3)
                    .child(count.to_string()),
            )
    }
    /// Card that holds a list of rows separated by hairlines.
    pub(super) fn list_card(self) -> Div {
        div()
            .flex()
            .flex_col()
            .rounded(px(18.))
            .bg(self.card)
            .border_1()
            .border_color(self.line)
            .overflow_hidden()
    }
    /// Bare focusable control used for in-row text and icon actions: no
    /// visual chrome until it receives keyboard focus.
    /// 弹窗里的文字按钮基座；sheet 内部一律用键盘可达的 32px 控件。
    fn action_styled(
        self,
        id: impl Into<ElementId>,
        label: impl Into<SharedString>,
        text: Rgba,
        bg: Rgba,
        border: Rgba,
        hover_bg: Rgba,
        hover_text: Rgba,
    ) -> Stateful<Div> {
        let label = label.into();
        let text_id = label.clone();
        div()
            .debug_selector(move || text_id.to_string())
            .id(id)
            .tab_index(0)
            .tab_stop(self.keyboard)
            .h(px(32.))
            .px(px(13.))
            .flex()
            .items_center()
            .justify_center()
            .gap(px(6.))
            .rounded(px(9.))
            .text_size(px(13.))
            .font_weight(FontWeight::MEDIUM)
            .text_color(text)
            .bg(bg)
            .border_1()
            .border_color(border)
            .focus(move |s| s.border_color(self.accent))
            .cursor_pointer()
            .whitespace_nowrap()
            .hover(move |s| s.bg(hover_bg).text_color(hover_text))
            .child(label)
    }
    pub(super) fn action(
        self,
        id: impl Into<ElementId>,
        label: impl Into<SharedString>,
    ) -> Stateful<Div> {
        self.action_styled(
            id,
            label,
            self.muted,
            rgba(0x00000000),
            rgba(0x00000000),
            self.subtle,
            self.text,
        )
    }
    pub(super) fn action_primary(
        self,
        id: impl Into<ElementId>,
        label: impl Into<SharedString>,
    ) -> Stateful<Div> {
        self.action_styled(
            id,
            label,
            self.canvas,
            self.text,
            rgba(0x00000000),
            self.accent,
            self.canvas,
        )
    }
    pub(super) fn action_soft(
        self,
        id: impl Into<ElementId>,
        label: impl Into<SharedString>,
    ) -> Stateful<Div> {
        self.action_styled(
            id,
            label,
            self.text,
            self.subtle,
            self.line,
            self.hover,
            self.text,
        )
    }
    pub(super) fn action_danger(
        self,
        id: impl Into<ElementId>,
        label: impl Into<SharedString>,
    ) -> Stateful<Div> {
        self.action_styled(
            id,
            label,
            self.neg,
            self.neg_soft,
            rgba(0x00000000),
            self.neg_soft,
            self.neg,
        )
    }
    /// 设置页的分段选择器，尺寸与参考图一致（26px 高、7px 圆角）。
    pub(super) fn segment(
        self,
        id: impl Into<ElementId>,
        label: impl Into<SharedString>,
        active: bool,
    ) -> Stateful<Div> {
        let label = label.into();
        let text_id = label.clone();
        div()
            .debug_selector(move || text_id.to_string())
            .id(id)
            .tab_index(0)
            .tab_stop(self.keyboard)
            .h(px(26.))
            .px(px(12.))
            .flex()
            .items_center()
            .justify_center()
            .rounded(px(7.))
            .text_size(px(12.))
            .when(active, |s| {
                s.bg(self.card)
                    .text_color(self.text)
                    .font_weight(FontWeight::MEDIUM)
            })
            .when(!active, |s| s.text_color(self.muted))
            .cursor_pointer()
            .hover(move |s| s.text_color(self.text))
            .child(label)
    }
    /// 弹窗分组：上方 1px 分隔线，14px 上下内距，11.5px 分组标题。
    pub(super) fn sheet_group(self, first: bool, title: &'static str, rows: Vec<Div>) -> Div {
        let mut group = div().flex().flex_col().py(px(14.)).child(
            div()
                .mb(px(10.))
                .text_size(px(11.5))
                .font_weight(FontWeight::SEMIBOLD)
                .text_color(self.ink3)
                .child(title),
        );
        if !first {
            group = group.border_t_1().border_color(self.line);
        }
        for row in rows {
            group = group.child(row);
        }
        group
    }
    /// 设置行：左侧标签 + 说明，右侧控件。
    pub(super) fn sheet_row(
        self,
        label: &'static str,
        desc: &'static str,
        control: impl IntoElement,
    ) -> Div {
        div()
            .flex()
            .items_center()
            .justify_between()
            .gap(px(16.))
            .py(px(7.))
            .child(
                div()
                    .flex()
                    .flex_col()
                    .gap(px(2.))
                    .min_w(px(0.))
                    .child(div().text_size(px(13.)).child(label))
                    .child(
                        div()
                            .text_size(px(11.5))
                            .text_color(self.ink3)
                            .max_w(px(260.))
                            .child(desc),
                    ),
            )
            .child(control)
    }
    /// 关于行：左标题，右侧说明，无副标题。
    pub(super) fn sheet_line(self, label: &'static str, right: &'static str) -> Div {
        div()
            .flex()
            .items_center()
            .justify_between()
            .gap(px(16.))
            .py(px(7.))
            .child(div().text_size(px(13.)).child(label))
            .child(div().text_size(px(11.5)).text_color(self.ink3).child(right))
    }
    /// 只读说明行：右侧文本可以是运行时生成的内容（例如密钥指纹）。
    pub(super) fn sheet_note(self, label: &'static str, right: impl Into<SharedString>) -> Div {
        div()
            .flex()
            .items_center()
            .justify_between()
            .gap(px(16.))
            .py(px(7.))
            .child(div().text_size(px(13.)).child(label))
            .child(
                div()
                    .text_size(px(11.5))
                    .text_color(self.ink3)
                    .child(right.into()),
            )
    }
    /// 表单字段：11.5px 标签 + 6px 间距 + 控件。
    pub(super) fn field(self, label: &'static str, control: impl IntoElement) -> Div {
        div()
            .flex()
            .flex_col()
            .flex_1()
            .gap(px(6.))
            .min_w(px(0.))
            .child(div().text_size(px(11.5)).text_color(self.ink3).child(label))
            .child(control)
    }
    /// 36px 高、9px 圆角的下拉样式触发控件。
    pub(super) fn field_select(
        self,
        id: impl Into<ElementId>,
        value: impl Into<SharedString>,
    ) -> Stateful<Div> {
        let value = value.into();
        let text_id = value.clone();
        div()
            .debug_selector(move || text_id.to_string())
            .id(id)
            .tab_index(0)
            .tab_stop(self.keyboard)
            .h(px(36.))
            .w_full()
            .px(px(10.))
            .flex()
            .items_center()
            .justify_between()
            .rounded(px(9.))
            .border_1()
            .border_color(self.hairline_strong)
            .bg(self.card)
            .text_size(px(13.5))
            .text_color(self.text)
            .cursor_pointer()
            .focus(move |s| s.border_color(self.accent))
            .hover(move |s| s.bg(self.subtle))
            .child(value)
            .child(
                Icon::new(IconName::ChevronDown)
                    .size_3()
                    .text_color(self.ink3),
            )
    }
    /// 弹窗底部操作条：14/20/18 内距 + 上方分隔线 + 浅色底。
    pub(super) fn sheet_foot(self, buttons: Vec<AnyElement>) -> Div {
        let mut foot = div()
            .flex()
            .justify_end()
            .gap(px(8.))
            .px(px(20.))
            .pt(px(14.))
            .pb(px(18.))
            .border_t_1()
            .border_color(self.line)
            .bg(self.subtle);
        for button in buttons {
            foot = foot.child(button);
        }
        foot
    }
    pub(super) fn text_control(self, id: impl Into<ElementId>) -> Stateful<Div> {
        div()
            .id(id)
            .tab_index(0)
            .tab_stop(self.keyboard)
            .border_1()
            .border_color(transparent_black())
            .focus(move |s| s.border_color(self.accent))
            .rounded(px(6.))
            .cursor_pointer()
    }
    pub(super) fn button(
        self,
        id: impl Into<ElementId>,
        label: impl Into<SharedString>,
    ) -> Stateful<Div> {
        let label = label.into();
        let symbol = if label == "×" {
            Some((IconName::Close, String::new()))
        } else if let Some(text) = label.strip_prefix('＋') {
            Some((IconName::Plus, text.trim().to_string()))
        } else if let Some(text) = label.strip_prefix('◷') {
            Some((IconName::Undo2, format!("历史 {}", text.trim())))
        } else if let Some(text) = label.strip_prefix('✓') {
            Some((IconName::Check, text.trim().to_string()))
        } else if label == "设置" {
            Some((IconName::Settings, label.to_string()))
        } else {
            None
        };
        let content = if let Some((icon, text)) = symbol {
            div()
                .flex()
                .items_center()
                .justify_center()
                .gap_2()
                .child(Icon::new(icon).size_4())
                .when(!text.is_empty(), |row| row.child(text))
                .into_any_element()
        } else {
            label.clone().into_any_element()
        };
        div()
            .debug_selector(|| label.to_string())
            .id(id)
            .tab_index(0)
            .tab_stop(self.keyboard)
            .border_1()
            .border_color(transparent_black())
            .focus(move |s| s.border_color(self.accent))
            .px_3()
            .py_2()
            .rounded(px(9.))
            .text_color(self.accent)
            .cursor_pointer()
            .whitespace_nowrap()
            .hover(move |s| s.bg(self.canvas))
            .child(content)
    }
}
/// Small hover card used for the statistics caveats that the reference keeps
/// in `title` tooltips rather than in the page body.
pub(super) struct Hint {
    pub(super) text: SharedString,
    pub(super) palette: Palette,
}

impl Render for Hint {
    fn render(&mut self, _: &mut Window, _: &mut gpui::Context<Self>) -> impl IntoElement {
        let p = self.palette;
        div()
            .px(px(10.))
            .py(px(6.))
            .rounded(px(8.))
            .bg(p.card)
            .border_1()
            .border_color(p.line)
            .text_size(px(11.5))
            .text_color(p.text)
            .child(self.text.clone())
    }
}

impl SnowFold {
    /// Category icon path for the five fixed categories.
    pub(super) fn category_icon(index: usize) -> &'static str {
        [
            "icons/wallet.svg",
            "icons/house.svg",
            "icons/trending-up.svg",
            "icons/banknote.svg",
            "icons/credit-card.svg",
        ][index.min(4)]
    }
}
