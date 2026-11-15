//! 内置资源：IconMCP 线条/实心图标与两款字体，全部编译进二进制（完全离线）。
//!
//! 界面只引用 `icons/line/*` 与 `icons/fill/*`；对 gpui-component 自带图标集的
//! 兜底仅为了其内部组件（日历浮层等）不至于缺资源，界面代码不再使用它们。

use gpui::{AssetSource, Result, SharedString};
use std::borrow::Cow;

pub struct AppAssets;

macro_rules! icons {
    ($($dir:literal / $name:literal),* $(,)?) => {
        [
            $((concat!("icons/", $dir, "/", $name, ".svg"),
               include_bytes!(concat!("../../../shared/icons/", $dir, "/", $name, ".svg"))),)*
        ]
    };
}

const LINE_ICONS: [(&str, &[u8]); 26] = icons!(
    "line" / "banknote",
    "line" / "calendar-repeat",
    "line" / "calendar",
    "line" / "check",
    "line" / "chevron-left",
    "line" / "chevron-right",
    "line" / "clock",
    "line" / "close",
    "line" / "credit-card",
    "line" / "database",
    "line" / "hand-coins",
    "line" / "home",
    "line" / "info",
    "line" / "key",
    "line" / "pause",
    "line" / "pie-chart",
    "line" / "play",
    "line" / "plus",
    "line" / "refresh",
    "line" / "settings",
    "line" / "shield-lock",
    "line" / "trash",
    "line" / "trending-up",
    "line" / "undo",
    "line" / "wallet",
    "line" / "warning-triangle",
);

const FILL_ICONS: [(&str, &[u8]); 26] = icons!(
    "fill" / "banknote",
    "fill" / "calendar-repeat",
    "fill" / "calendar",
    "fill" / "check",
    "fill" / "chevron-left",
    "fill" / "chevron-right",
    "fill" / "clock",
    "fill" / "close",
    "fill" / "credit-card",
    "fill" / "database",
    "fill" / "hand-coins",
    "fill" / "home",
    "fill" / "info",
    "fill" / "key",
    "fill" / "pause",
    "fill" / "pie-chart",
    "fill" / "play",
    "fill" / "plus",
    "fill" / "refresh",
    "fill" / "settings",
    "fill" / "shield-lock",
    "fill" / "trash",
    "fill" / "trending-up",
    "fill" / "undo",
    "fill" / "wallet",
    "fill" / "warning-triangle",
);

/// Inter 可变字体（西文与数字，shared/fonts 内置）。
pub(super) const INTER_TTF: &[u8] = include_bytes!("../../../shared/fonts/inter/InterVariable.ttf");
/// 思源黑体 SC 可变字体（中文，Google Fonts 版）。
pub(super) const NOTO_TTF: &[u8] = include_bytes!("../assets/fonts/NotoSansSC-Variable.ttf");

impl AssetSource for AppAssets {
    fn load(&self, path: &str) -> Result<Option<Cow<'static, [u8]>>> {
        if let Some((_, data)) = LINE_ICONS
            .iter()
            .chain(FILL_ICONS.iter())
            .find(|(n, _)| *n == path)
        {
            return Ok(Some(Cow::Borrowed(data)));
        }
        gpui_component_assets::Assets.load(path)
    }

    fn list(&self, path: &str) -> Result<Vec<SharedString>> {
        let mut names: Vec<SharedString> = LINE_ICONS
            .iter()
            .chain(FILL_ICONS.iter())
            .filter(|(name, _)| name.starts_with(path))
            .map(|(name, _)| (*name).into())
            .collect();
        names.extend(gpui_component_assets::Assets.list(path)?);
        Ok(names)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn line_and_fill_icons_are_embedded() {
        for (name, _) in LINE_ICONS.iter().chain(FILL_ICONS.iter()) {
            let data = AppAssets
                .load(name)
                .expect("asset source error")
                .expect("asset missing");
            assert!(
                data.starts_with(b"<svg"),
                "{name} should be an embedded svg"
            );
        }
    }

    #[test]
    fn fonts_are_present() {
        assert!(INTER_TTF.len() > 100_000);
        assert!(NOTO_TTF.len() > 1_000_000);
    }
}
