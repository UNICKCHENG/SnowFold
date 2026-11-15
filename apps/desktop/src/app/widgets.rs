//! 共用控件（docs/design.md §4–§5）。所有可点元素可 Tab 且有统一焦点环。

use crate::theme::{FONT_LATIN, fade};
use gpui::Focusable as _;
use gpui::prelude::*;
use gpui::{
    AnyElement, App, ElementId, Entity, FontWeight, Hsla, IntoElement, ParentElement, SharedString,
    Styled, div, px, rgba, svg,
};
use gpui_component::input::{Input, InputState};

use crate::theme::Palette;

pub(super) const M: FontWeight = FontWeight::MEDIUM;
pub(super) const SB: FontWeight = FontWeight::SEMIBOLD;

/// 字号（§2 桌面档）。
pub(crate) mod sizes {
    pub(crate) const HERO: f32 = 40.;
    pub(crate) const HERO_LH: f32 = 48.;
    pub(crate) const PAGE: f32 = 28.;
    pub(crate) const PAGE_LH: f32 = 34.;
    pub(crate) const SECTION: f32 = 15.;
    pub(crate) const SECTION_LH: f32 = 20.;
    pub(crate) const BODY: f32 = 13.;
    pub(crate) const BODY_LH: f32 = 18.;
    pub(crate) const AUX: f32 = 11.;
    pub(crate) const AUX_LH: f32 = 14.;
}

/// 静态文本（含中文自动分段思源黑体）。
pub(super) fn t(
    text: impl Into<SharedString>,
    color: impl Into<Hsla>,
    weight: FontWeight,
) -> AnyElement {
    crate::theme::text(text, color, weight)
}

/// 金额与数字（Inter + tnum）。
pub(super) fn n(
    text: impl Into<SharedString>,
    color: impl Into<Hsla>,
    weight: FontWeight,
) -> AnyElement {
    crate::theme::num(text, color, weight)
}

/// 颜色亮度增减（悬停 +8%、按下 −10%）。
fn lighten(c: gpui::Rgba, f: f32) -> Hsla {
    let mut h = Hsla::from(c);
    h.l = (h.l + f).clamp(0., 1.);
    h
}

fn darken(c: gpui::Rgba, f: f32) -> Hsla {
    lighten(c, -f)
}

/// 卡片：card 底、圆角 16、无描边无阴影。
pub(super) fn card() -> gpui::Div {
    div().flex().flex_col().rounded(px(16.))
}

/// 列表行分隔线（1px sep，从文字起始处缩进）。
pub(super) fn row_separator(indent: f32, p: &Palette) -> AnyElement {
    div()
        .ml(px(indent))
        .h(px(1.))
        .flex_none()
        .bg(p.sep)
        .into_any_element()
}

fn button_base(
    id: impl Into<ElementId>,
    label_for_debug: impl Into<SharedString>,
    height: f32,
) -> gpui::Stateful<gpui::Div> {
    let label_for_debug = label_for_debug.into();
    div()
        .debug_selector(move || label_for_debug.to_string())
        .id(id)
        .tab_index(0)
        .tab_stop(true)
        .h(px(height))
        .px(px(13.))
        .flex()
        .items_center()
        .justify_center()
        .gap(px(6.))
        .rounded(px(10.))
        .text_size(px(sizes::BODY))
        .cursor_pointer()
        .whitespace_nowrap()
}

/// 主要按钮（brand 底，on-brand 字）。
pub(super) fn btn_primary(
    id: impl Into<ElementId>,
    label: impl Into<SharedString>,
    p: &Palette,
) -> gpui::Stateful<gpui::Div> {
    let label = label.into();
    button_base(id, label.clone(), 32.)
        .bg(p.brand)
        .hover(move |s| s.bg(lighten(p.brand, 0.08)))
        .active(move |s| s.bg(darken(p.brand, 0.10)))
        .focus(move |s| s.border_3().border_color(fade(p.brand, 0.4)))
        .child(t(label, p.on_brand, M))
}

/// 次要按钮（card 底 + 1px sep 内描边）。
pub(super) fn btn_secondary(
    id: impl Into<ElementId>,
    label: impl Into<SharedString>,
    p: &Palette,
) -> gpui::Stateful<gpui::Div> {
    let label = label.into();
    button_base(id, label.clone(), 32.)
        .bg(p.card)
        .border_1()
        .border_color(p.sep)
        .hover(move |s| s.border_color(p.text2))
        .focus(move |s| s.border_3().border_color(fade(p.brand, 0.4)))
        .child(t(label, p.text, M))
}

/// 危险按钮（neg 底）。
pub(super) fn btn_danger(
    id: impl Into<ElementId>,
    label: impl Into<SharedString>,
    p: &Palette,
) -> gpui::Stateful<gpui::Div> {
    let label = label.into();
    button_base(id, label.clone(), 32.)
        .bg(p.neg)
        .hover(move |s| s.bg(lighten(p.neg, 0.08)))
        .active(move |s| s.bg(darken(p.neg, 0.10)))
        .focus(move |s| s.border_3().border_color(fade(p.brand, 0.4)))
        .child(t(label, p.on_neg, M))
}

/// 文字按钮（透明底；危险操作传 neg）。
pub(super) fn btn_text(
    id: impl Into<ElementId>,
    label: impl Into<SharedString>,
    color: gpui::Rgba,
    p: &Palette,
) -> gpui::Stateful<gpui::Div> {
    let label = label.into();
    button_base(id, label.clone(), 32.)
        .hover(move |s| s.bg(p.hover()))
        .focus(move |s| s.border_3().border_color(fade(p.brand, 0.4)))
        .child(t(label, color, M))
}

/// 浅色按钮（brand-soft 底 + brand 字）：已续费、分类空态「添加」。
pub(super) fn btn_soft(
    id: impl Into<ElementId>,
    label: impl Into<SharedString>,
    p: &Palette,
) -> gpui::Stateful<gpui::Div> {
    let label = label.into();
    button_base(id, label.clone(), 28.)
        .px(px(10.))
        .bg(p.brand_soft)
        .hover(move |s| s.bg(lighten(p.brand_soft, 0.05)))
        .focus(move |s| s.border_3().border_color(fade(p.brand, 0.4)))
        .child(t(label, p.brand, M))
}

/// 工具栏的 32px 圆形图标按钮（浮层材质 + 0.5px 亮边）。
pub(super) fn circle_button(
    id: &'static str,
    icon: &'static str,
    icon_color: gpui::Rgba,
    p: &Palette,
) -> gpui::Stateful<gpui::Div> {
    let debug_id = id.to_string();
    div()
        .debug_selector(move || debug_id.clone())
        .id(id)
        .tab_index(0)
        .tab_stop(true)
        .flex()
        .items_center()
        .justify_center()
        .size(px(32.))
        .rounded_full()
        .bg(p.material)
        .border_1()
        .border_color(p.material_edge)
        .cursor_pointer()
        .hover(move |s| s.bg(fade(gpui::Rgba::from(p.text), 0.06)))
        .active(move |s| s.bg(fade(gpui::Rgba::from(p.text), 0.10)))
        .focus(move |s| s.border_3().border_color(fade(p.brand, 0.4)))
        .child(svg().path(icon).size(px(20.)).text_color(icon_color))
}

/// 分段控件（高 28：外框 bg + 1px sep、圆角 10、内边距 2、段宽均分；选中 brand 底）。
pub(super) fn segmented(
    id_prefix: &'static str,
    options: &[(&'static str, &'static str)],
    selected: usize,
    p: &Palette,
) -> gpui::Div {
    let mut container = div()
        .flex()
        .w_full()
        .p(px(2.))
        .rounded(px(10.))
        .border_1()
        .border_color(p.sep);
    for (i, (_value, label)) in options.iter().enumerate() {
        let active = i == selected;
        let debug = format!("{id_prefix}{i}");
        container = container.child(
            div()
                .debug_selector(move || debug.clone())
                .id((id_prefix, i))
                .tab_index(0)
                .tab_stop(true)
                .flex_1()
                .h(px(24.))
                .flex()
                .items_center()
                .justify_center()
                .rounded(px(8.))
                .text_size(px(sizes::BODY))
                .cursor_pointer()
                .when(active, |s| s.bg(p.brand))
                .hover(move |s| if active { s } else { s.bg(p.hover()) })
                .focus(move |s| s.border_3().border_color(fade(p.brand, 0.4)))
                .child(if active {
                    t(*label, p.on_brand, M)
                } else {
                    t(*label, p.text2, FontWeight::NORMAL)
                }),
        );
    }
    container
}

/// 开关：40×24，开 brand 底、关 sep 底，白色圆钮带阴影。
pub(super) fn switch(
    id: impl Into<ElementId>,
    checked: bool,
    p: &Palette,
) -> gpui::Stateful<gpui::Div> {
    div()
        .id(id)
        .tab_index(0)
        .tab_stop(true)
        .relative()
        .flex_none()
        .w(px(40.))
        .h(px(24.))
        .rounded_full()
        .cursor_pointer()
        .when(checked, |s| s.bg(p.brand))
        .when(!checked, |s| s.bg(p.sep))
        .focus(move |s| s.border_3().border_color(fade(p.brand, 0.4)))
        .child(
            div()
                .absolute()
                .top(px(2.))
                .left(px(if checked { 18. } else { 2. }))
                .size(px(20.))
                .rounded_full()
                .bg(p.card)
                .shadow(vec![gpui::BoxShadow {
                    color: rgba(0x00000040).into(),
                    offset: gpui::point(px(0.), px(1.)),
                    blur_radius: px(3.),
                    spread_radius: px(0.),
                }]),
        )
}

/// 分类图标块：28×28 圆角 10，图标 16（选中态用实心版）。
pub(super) fn icon_block(index: usize, p: &Palette, selected: bool) -> gpui::Div {
    let (bg, glyph) = p.cat(index);
    div()
        .flex_none()
        .flex()
        .items_center()
        .justify_center()
        .size(px(28.))
        .rounded(px(10.))
        .bg(bg)
        .child(
            svg()
                .path(category_icon(index, selected))
                .size(px(16.))
                .text_color(glyph),
        )
}

/// 五个固定分类的图标路径。
pub(super) fn category_icon(index: usize, fill: bool) -> &'static str {
    const LINE: [&str; 5] = [
        "icons/line/banknote.svg",
        "icons/line/home.svg",
        "icons/line/trending-up.svg",
        "icons/line/hand-coins.svg",
        "icons/line/credit-card.svg",
    ];
    const FILL: [&str; 5] = [
        "icons/fill/banknote.svg",
        "icons/fill/home.svg",
        "icons/fill/trending-up.svg",
        "icons/fill/hand-coins.svg",
        "icons/fill/credit-card.svg",
    ];
    if fill {
        FILL[index.min(4)]
    } else {
        LINE[index.min(4)]
    }
}

/// 订阅首字头像：32 圆形。
pub(super) fn avatar(
    initial: impl Into<SharedString>,
    bg: gpui::Rgba,
    fg: gpui::Rgba,
) -> gpui::Div {
    div()
        .flex_none()
        .flex()
        .items_center()
        .justify_center()
        .size(px(32.))
        .rounded_full()
        .bg(bg)
        .text_size(px(sizes::AUX + 2.))
        .child(t(initial.into(), fg, SB))
}

/// 徽标：高 20、左右 8、胶囊、辅助字号中等。
pub(super) fn badge(label: impl Into<SharedString>, bg: gpui::Rgba, fg: gpui::Rgba) -> gpui::Div {
    div()
        .flex_none()
        .flex()
        .items_center()
        .h(px(20.))
        .px(px(8.))
        .rounded_full()
        .bg(bg)
        .text_size(px(sizes::AUX))
        .child(t(label.into(), fg, M))
}

/// 分组头（卡片外，左缩进 4）：辅助中等 + 数量。
pub(super) fn group_head(label: &'static str, count: usize, neg: bool, p: &Palette) -> gpui::Div {
    let color = if neg { p.neg } else { p.text2 };
    div()
        .flex()
        .items_baseline()
        .gap(px(8.))
        .px(px(4.))
        .pt(px(4.))
        .pb(px(8.))
        .child(t(label, color, M))
        .child(t(count.to_string(), p.text2, FontWeight::NORMAL))
}

/// 输入框（gpui-component Input，外观走全局主题覆盖）：高 32、圆角 10。
/// 设计 §5.6 输入框：32 高、1px sep 描边、聚焦 3px brand-40%、错误 neg。
/// gpui-component 自带描边随系统主题色，这里统一接管（bordered(false)）。
pub(super) fn input_field(
    state: &Entity<InputState>,
    error: bool,
    p: &Palette,
    window: &gpui::Window,
    cx: &App,
) -> gpui::Div {
    let focused = state.read(cx).focus_handle(cx).is_focused(window);
    let field = Input::new(state)
        .h(px(32.))
        .w_full()
        .rounded(px(10.))
        .bordered(false)
        .focus_bordered(false);
    let mut wrap = div()
        .w_full()
        .rounded(px(10.))
        .border_1()
        .border_color(if error { p.neg } else { p.sep })
        .bg(p.card);
    if focused {
        // 出错字段聚焦时保留警示色，不被聚焦环盖住。
        wrap = wrap.border_3().border_color(if error {
            p.neg.into()
        } else {
            fade(p.brand, 0.4)
        });
    }
    wrap.child(field)
}

/// 带币种符前缀的金额输入（symbol 叠在输入框内左侧）。
pub(super) fn amount_input(
    state: &Entity<InputState>,
    symbol: &str,
    error: bool,
    p: &Palette,
    window: &gpui::Window,
    cx: &App,
) -> gpui::Div {
    let symbol = SharedString::from(symbol.to_string());
    let field = input_field(state, error, p, window, cx).pl(px(26.));
    div().relative().w_full().child(field).child(
        div()
            .absolute()
            .left(px(11.))
            .top(px(8.))
            .text_size(px(sizes::BODY))
            .child(n(symbol, p.text2, M)),
    )
}

/// 弹层表单行：标签列 88 右对齐，控件列自适应，行距 16。
pub(super) fn field_row(label: &'static str, control: impl IntoElement, p: &Palette) -> gpui::Div {
    div()
        .flex()
        .items_center()
        .gap(px(16.))
        .child(
            div()
                .w(px(88.))
                .flex_none()
                .text_size(px(sizes::BODY))
                .text_right()
                .child(t(label, p.text, FontWeight::NORMAL)),
        )
        .child(div().flex_1().min_w(px(0.)).child(control))
}

/// 浮层阴影（§4）：0 8px 32px；深色加深。
pub(super) fn floating_shadow(dark: bool) -> gpui::BoxShadow {
    gpui::BoxShadow {
        color: rgba(if dark { 0x00000080 } else { 0x00000024 }).into(),
        offset: gpui::point(px(0.), px(8.)),
        blur_radius: px(32.),
        spread_radius: px(0.),
    }
}

/// 弹层外壳：card 底、圆角 16、浮层阴影（深色加 1px sep 内描边）。
pub(super) fn dialog_shell(id: &'static str, width: f32, p: &Palette) -> gpui::Stateful<gpui::Div> {
    let debug_id = id.to_string();
    div()
        .debug_selector(move || debug_id.clone())
        .id(id)
        .w(px(width))
        .flex()
        .flex_col()
        .bg(p.card)
        .overflow_hidden()
        .when(p.dark, |s| s.border_1().border_color(p.sep))
        .shadow(vec![floating_shadow(p.dark)])
        .rounded(px(16.))
}

/// 空状态圆底：88 brand-soft + 48 线条图标 brand。
pub(super) fn empty_icon(icon: &'static str, p: &Palette) -> gpui::Div {
    div()
        .flex()
        .items_center()
        .justify_center()
        .size(px(88.))
        .rounded_full()
        .bg(p.brand_soft)
        .child(svg().path(icon).size(px(48.)).text_color(p.brand))
}

/// 整页空态：图标、标题、说明与主按钮共用设计稿的间距。
pub(super) fn empty_state(
    icon: &'static str,
    title: &'static str,
    description: &'static str,
    button: impl IntoElement,
    p: &Palette,
) -> gpui::Div {
    div()
        .debug_selector(|| "empty-state".to_string())
        .flex()
        .flex_col()
        .items_center()
        .child(empty_icon(icon, p).mb(px(20.)))
        .child(
            div()
                .text_size(px(sizes::SECTION))
                .line_height(px(sizes::SECTION_LH))
                .child(t(title, p.text, SB)),
        )
        .child(
            div()
                .mt(px(8.))
                .max_w(px(280.))
                .text_center()
                .text_size(px(sizes::BODY))
                .line_height(px(sizes::BODY_LH))
                .child(t(description, p.text2, FontWeight::NORMAL)),
        )
        .child(div().mt(px(24.)).child(button))
}

/// 大数字焦点金额：币种符与小数段降一级、中等字重、小数 text2；
/// 负号与整数段同字号同基线、紧贴币种符之前；负数整串 neg。
pub(super) fn focus_amount(
    text: &str,
    level: AmountLevel,
    negative: bool,
    p: &Palette,
) -> gpui::Div {
    let (int_size, sub_size, lh) = match level {
        AmountLevel::Hero => (sizes::HERO, sizes::PAGE, sizes::HERO_LH),
        AmountLevel::Page => (sizes::PAGE, sizes::SECTION, sizes::PAGE_LH),
    };
    let color = if negative { p.neg } else { p.text };
    let sub_color = if negative { p.neg } else { p.text2 };
    let text = text.to_string();
    let (negative, sym, int, dec) = money_parts(&text, negative);
    let int = SharedString::from(int.to_string());
    let dec = SharedString::from(dec.to_string());
    div()
        .flex()
        .items_baseline()
        .line_height(px(lh))
        .when(negative, |row| {
            row.child(div().text_size(px(int_size)).child(n("−", color, SB)))
        })
        .child(div().text_size(px(sub_size)).child(n(sym, color, M)))
        .child(div().text_size(px(int_size)).child(n(int, color, SB)))
        .when(!dec.is_empty(), |row| {
            row.child(div().text_size(px(sub_size)).child(n(dec, sub_color, M)))
        })
}

#[derive(Clone, Copy, PartialEq)]
pub(super) enum AmountLevel {
    Hero,
    Page,
}

/// 拆 `format_money` 输出：`[−]¥1,234.56` → (符号, 整数, 小数)。
fn money_parts(text: &str, already_negative: bool) -> (bool, &'static str, &str, &str) {
    let negative = already_negative || text.starts_with('−');
    let rest = text.trim_start_matches('−');
    let (sym, rest) = if let Some(r) = rest.strip_prefix('¥') {
        ("¥", r)
    } else if let Some(r) = rest.strip_prefix('$') {
        ("$", r)
    } else {
        ("", rest)
    };
    let (int, dec) = match rest.find('.') {
        Some(i) => (&rest[..i], &rest[i..]),
        None => (rest, ""),
    };
    (negative, sym, int, dec)
}

/// 只读等宽值（指纹等）。
pub(super) fn mono(label: impl Into<SharedString>, p: &Palette) -> gpui::Div {
    div()
        .font_family(FONT_LATIN)
        .text_size(px(sizes::AUX + 2.))
        .child(t(label.into(), p.text2, FontWeight::NORMAL))
}

/// 统计口径说明悬浮卡。
pub(super) struct Hint {
    pub(super) text: SharedString,
    pub(super) dark: bool,
}

impl gpui::Render for Hint {
    fn render(&mut self, _: &mut gpui::Window, _: &mut gpui::Context<Self>) -> impl IntoElement {
        let p = Palette::new(self.dark);
        div()
            .px(px(10.))
            .py(px(6.))
            .rounded(px(10.))
            .bg(p.card)
            .text_size(px(sizes::AUX + 1.))
            .child(t(self.text.clone(), p.text2, FontWeight::NORMAL))
    }
}
