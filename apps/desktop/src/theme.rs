//! 设计令牌与文字排版（docs/design.md §1–§2）。
//!
//! 字体要点：Inter 与思源黑体都由 `add_fonts` 内置注册，DirectWrite 不会把
//! 内置字体放进回退链，含中文的静态文本必须按字符分段显式指定字体
//! （`text`），纯西文与金额走 Inter（金额开 `tnum`）。

use std::sync::Arc;

use gpui::{
    AnyElement, Font, FontFeatures, FontStyle, FontWeight, Hsla, IntoElement, ParentElement,
    SharedString, Styled, StyledText, TextRun, div, px, rgb, rgba,
};

pub(super) const FONT_LATIN: &str = "Inter";
pub(super) const FONT_CN: &str = "Noto Sans SC";

/// 金额等宽数字（Inter `tnum`）。
pub(super) fn tnum() -> FontFeatures {
    FontFeatures(Arc::new(vec![("tnum".into(), 1)]))
}

fn is_cjk(c: char) -> bool {
    matches!(
        c as u32,
        0x3000..=0x303F   // CJK 标点（，。「」…）
        | 0x3040..=0x30FF // 假名（容错）
        | 0x3400..=0x4DBF
        | 0x4E00..=0x9FFF
        | 0xF900..=0xFAFF // 汉字
        | 0xFF00..=0xFFEF // 全角形式
    )
}

pub(super) fn has_cjk(text: &str) -> bool {
    text.chars().any(is_cjk)
}

fn inter_font(weight: FontWeight, features: FontFeatures) -> Font {
    Font {
        family: FONT_LATIN.into(),
        features,
        fallbacks: None,
        weight,
        style: FontStyle::Normal,
    }
}

fn cn_font(weight: FontWeight) -> Font {
    Font {
        family: FONT_CN.into(),
        features: FontFeatures::default(),
        fallbacks: None,
        weight,
        style: FontStyle::Normal,
    }
}

/// 金额与数字字体：Inter + `tnum`。
pub(super) fn amount_font(weight: FontWeight) -> Font {
    inter_font(weight, tnum())
}

/// 金额与数字：Inter + `tnum`。金额串只来自核心 `format_money`/`money`。
pub(super) fn num(
    text: impl Into<SharedString>,
    color: impl Into<Hsla>,
    weight: FontWeight,
) -> AnyElement {
    let text: SharedString = text.into();
    div()
        .font(amount_font(weight))
        .text_color(color.into())
        .child(text)
        .into_any_element()
}

/// 中文收尾标点与前一个字形作为同一换行单元。
/// GPUI 0.2.2 的换行器按字形断行，不识别 Unicode WORD JOINER，
/// 因此用可换行 flex 装载不拆分的小段；英文单词保持完整。
fn punctuation_chunks(text: &str) -> Vec<String> {
    let mut chunks: Vec<String> = Vec::new();
    for c in text.chars() {
        let closing = "）】」』》、。，：；！？)%％".contains(c);
        let ascii_word = c.is_ascii_alphanumeric() || "+-_.".contains(c);
        if (closing
            || c == ' '
            || ascii_word
                && chunks.last().is_some_and(|s| {
                    s.chars()
                        .last()
                        .is_some_and(|p| p.is_ascii_alphanumeric() || "+-_.".contains(p))
                }))
            && let Some(last) = chunks.last_mut()
        {
            last.push(c);
        } else {
            chunks.push(c.to_string());
        }
    }
    chunks
}

/// 按字符分段生成 runs：CJK 段思源黑体，其余 Inter。
pub(super) fn mixed(text: impl Into<SharedString>, color: Hsla, weight: FontWeight) -> StyledText {
    let text = text.into();
    let mut runs: Vec<TextRun> = Vec::new();
    let mut start = 0usize;
    let mut cur_cjk: Option<bool> = None;
    for (index, c) in text.char_indices() {
        let cjk = is_cjk(c);
        if cur_cjk != Some(cjk) {
            if let Some(prev) = cur_cjk {
                runs.push(TextRun {
                    len: index - start,
                    font: if prev {
                        cn_font(weight)
                    } else {
                        inter_font(weight, FontFeatures::default())
                    },
                    color,
                    background_color: None,
                    underline: None,
                    strikethrough: None,
                });
                start = index;
            }
            cur_cjk = Some(cjk);
        }
    }
    if let Some(prev) = cur_cjk {
        runs.push(TextRun {
            len: text.len() - start,
            font: if prev {
                cn_font(weight)
            } else {
                inter_font(weight, FontFeatures::default())
            },
            color,
            background_color: None,
            underline: None,
            strikethrough: None,
        });
    }
    StyledText::new(text).with_runs(runs)
}

/// 静态文本：整段无中文时继承根部的 Inter；含中文按字符分段排。
/// 字号、行高由调用方在容器上设置。
pub(super) fn text(
    text: impl Into<SharedString>,
    color: impl Into<Hsla>,
    weight: FontWeight,
) -> AnyElement {
    let text: SharedString = text.into();
    let color = color.into();
    if has_cjk(&text)
        && text
            .chars()
            .any(|c| "）】」』》、。，：；！？)".contains(c))
    {
        return div()
            .flex()
            .flex_wrap()
            .min_w(px(0.))
            .children(
                punctuation_chunks(&text)
                    .into_iter()
                    .map(|chunk| div().whitespace_nowrap().child(mixed(chunk, color, weight))),
            )
            .into_any_element();
    }
    if !has_cjk(&text) {
        return div()
            .font_weight(weight)
            .text_color(color)
            .child(text)
            .into_any_element();
    }
    if text.chars().all(is_cjk) {
        return div()
            .font_family(FONT_CN)
            .font_weight(weight)
            .text_color(color)
            .child(text)
            .into_any_element();
    }
    div().child(mixed(text, color, weight)).into_any_element()
}

/// 品牌色等纯色按透明度淡化（返回 Hsla 供描边、底色用）。
pub(super) fn fade(c: impl Into<Hsla>, alpha: f32) -> Hsla {
    let base: Hsla = c.into();
    base.alpha(alpha)
}

#[derive(Clone, Copy)]
pub(super) struct Palette {
    pub(super) dark: bool,
    pub(super) bg: gpui::Rgba,
    pub(super) card: gpui::Rgba,
    pub(super) sep: gpui::Rgba,
    pub(super) text: gpui::Rgba,
    pub(super) text2: gpui::Rgba,
    pub(super) brand: gpui::Rgba,
    pub(super) brand_soft: gpui::Rgba,
    pub(super) on_brand: gpui::Rgba,
    pub(super) neg: gpui::Rgba,
    pub(super) neg_soft: gpui::Rgba,
    pub(super) on_neg: gpui::Rgba,
    pub(super) warn: gpui::Rgba,
    pub(super) warn_soft: gpui::Rgba,
    pub(super) pos: gpui::Rgba,
    /// 浮层材质（侧边栏、横幅、工具栏圆钮）：84% 不透明。
    pub(super) material: gpui::Rgba,
    pub(super) material_edge: gpui::Rgba,
    pub(super) mask_dialog: gpui::Rgba,
    pub(super) mask_confirm: gpui::Rgba,
    /// Mica 未生效（Windows 10 或调用失败）时窗口底色不透明。
    pub(super) opaque_window: bool,
}

impl Palette {
    pub(super) fn new(dark: bool) -> Self {
        Self {
            dark,
            bg: rgb(if dark { 0x0E0E10 } else { 0xF4F4F6 }),
            card: rgb(if dark { 0x1C1C1F } else { 0xFFFFFF }),
            sep: rgb(if dark { 0x2F2F35 } else { 0xE2E2E7 }),
            text: rgb(if dark { 0xF2F2F5 } else { 0x1C1C1E }),
            text2: rgb(if dark { 0x9C9CA4 } else { 0x66666D }),
            brand: rgb(if dark { 0x3FC1CC } else { 0x076B75 }),
            brand_soft: rgb(if dark { 0x223A3E } else { 0xE1EDEE }),
            on_brand: rgb(if dark { 0x0E0E10 } else { 0xFFFFFF }),
            neg: rgb(if dark { 0xFF6A5E } else { 0xBF3228 }),
            neg_soft: rgb(if dark { 0x402829 } else { 0xF9EAEA }),
            on_neg: rgb(if dark { 0x0E0E10 } else { 0xFFFFFF }),
            warn: rgb(if dark { 0xF5B041 } else { 0x9A5B00 }),
            warn_soft: rgb(if dark { 0x3F3424 } else { 0xF3EBE0 }),
            pos: rgb(if dark { 0x4CC77A } else { 0x1F7A3E }),
            material: rgba(if dark { 0x242428D6 } else { 0xFAFAFCD6 }),
            material_edge: rgba(if dark { 0xFFFFFF1F } else { 0xFFFFFFE6 }),
            mask_dialog: rgba(if dark { 0x0000008F } else { 0x1C1C1E52 }),
            mask_confirm: rgba(if dark { 0x0000008F } else { 0x1C1C1E52 }),
            opaque_window: true,
        }
    }

    /// 分类识别色（图标块底、glyph）。
    pub(super) fn cat(&self, index: usize) -> (gpui::Rgba, gpui::Rgba) {
        if self.dark {
            match index {
                0 => (rgb(0x3F3525), rgb(0xE0A93F)),
                1 => (rgb(0x2D3B2A), rgb(0x7CC75B)),
                2 => (rgb(0x353247), rgb(0xA796FF)),
                3 => (rgb(0x283543), rgb(0x5FA8E8)),
                _ => (rgb(0x452A2A), rgb(0xFF6A5E)),
            }
        } else {
            match index {
                0 => (rgb(0xF2ECE0), rgb(0x946100)),
                1 => (rgb(0xE7EFE5), rgb(0x3B7D23)),
                2 => (rgb(0xEDEAF9), rgb(0x6A4FD0)),
                3 => (rgb(0xE4EEF6), rgb(0x1F6FB0)),
                _ => (rgb(0xF9EAEA), rgb(0xBF3228)),
            }
        }
    }

    /// 悬停叠加 4% 主文字色。
    pub(super) fn hover(&self) -> Hsla {
        fade(
            if self.dark {
                gpui::Rgba::from(rgb(0xF2F2F5))
            } else {
                gpui::Rgba::from(rgb(0x1C1C1E))
            },
            0.04,
        )
    }

    /// 按下叠加 8% 主文字色。
    pub(super) fn pressed(&self) -> Hsla {
        fade(
            if self.dark {
                gpui::Rgba::from(rgb(0xF2F2F5))
            } else {
                gpui::Rgba::from(rgb(0x1C1C1E))
            },
            0.08,
        )
    }
}

pub(super) fn f32_px(v: f32) -> gpui::Pixels {
    px(v)
}
