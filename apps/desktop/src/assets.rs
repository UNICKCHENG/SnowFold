use gpui::{AssetSource, Result, SharedString};
use std::borrow::Cow;

/// Embedded SVG icons for the app, layered on top of the icon set shipped by
/// `gpui-component-assets`. Everything is compiled into the binary so the
/// application stays fully offline.
pub struct AppAssets;

const LOCAL_ICONS: [(&str, &[u8]); 10] = [
    (
        "icons/wallet.svg",
        include_bytes!("../../../shared/icons/ui/wallet.svg"),
    ),
    (
        "icons/house.svg",
        include_bytes!("../../../shared/icons/ui/house.svg"),
    ),
    (
        "icons/trending-up.svg",
        include_bytes!("../../../shared/icons/ui/trending-up.svg"),
    ),
    (
        "icons/banknote.svg",
        include_bytes!("../../../shared/icons/ui/banknote.svg"),
    ),
    (
        "icons/credit-card.svg",
        include_bytes!("../../../shared/icons/ui/credit-card.svg"),
    ),
    (
        "icons/clock.svg",
        include_bytes!("../../../shared/icons/ui/clock.svg"),
    ),
    (
        "icons/sliders.svg",
        include_bytes!("../../../shared/icons/ui/sliders.svg"),
    ),
    (
        "icons/arrow-right.svg",
        include_bytes!("../../../shared/icons/ui/arrow-right.svg"),
    ),
    (
        "icons/mark.svg",
        include_bytes!("../../../shared/icons/ui/mark.svg"),
    ),
    (
        "icons/refresh-cw.svg",
        include_bytes!("../../../shared/icons/ui/refresh-cw.svg"),
    ),
];

impl AssetSource for AppAssets {
    fn load(&self, path: &str) -> Result<Option<Cow<'static, [u8]>>> {
        if let Some((_, data)) = LOCAL_ICONS.iter().find(|(name, _)| *name == path) {
            return Ok(Some(Cow::Borrowed(data)));
        }
        gpui_component_assets::Assets.load(path)
    }

    fn list(&self, path: &str) -> Result<Vec<SharedString>> {
        let mut names: Vec<SharedString> = LOCAL_ICONS
            .iter()
            .filter(|&(name, _)| name.starts_with(path))
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
    fn local_icon_assets_are_embedded_and_fall_back_to_component_set() {
        for (name, _) in LOCAL_ICONS {
            let data = AppAssets
                .load(name)
                .expect("asset source error")
                .expect("asset missing");
            assert!(
                data.starts_with(b"<svg"),
                "{name} should be an embedded svg"
            );
        }
        let delegated = AppAssets
            .load("icons/plus.svg")
            .expect("asset source error")
            .expect("component icon missing");
        assert!(delegated.starts_with(b"<svg"));
        let listed = AppAssets.list("icons/").expect("asset listing failed");
        assert!(
            listed
                .iter()
                .any(|name| name.as_ref() == "icons/wallet.svg")
        );
        assert!(listed.iter().any(|name| name.as_ref() == "icons/plus.svg"));
    }
}
