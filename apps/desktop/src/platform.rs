//! Windows 窗口材质：Mica（DWMWA_SYSTEMBACKDROP_TYPE）与深色标题栏。
//!
//! Windows 11 build 22621+ 用 `DWMSBT_MAINWINDOW`；调用失败（Windows 10 或更旧）
//! 由调用方回退到不透明窗口底色（`backdrop_applied` 全局标记）。

#[cfg(windows)]
use gpui::{App, Window};

#[cfg(windows)]
#[link(name = "dwmapi")]
unsafe extern "system" {
    fn DwmSetWindowAttribute(
        hwnd: isize,
        attribute: u32,
        value: *const std::ffi::c_void,
        size: u32,
    ) -> std::ffi::c_int;
}

#[cfg(windows)]
const DWMWA_USE_IMMERSIVE_DARK_MODE: u32 = 20;
#[cfg(windows)]
const DWMWA_SYSTEMBACKDROP_TYPE: u32 = 38;
#[cfg(windows)]
const DWMSBT_MAINWINDOW: u32 = 2; // Mica

/// 全局标记：Mica 是否可用（决定窗口底色走半透明还是不透明）。
pub(super) struct Backdrop(pub(super) std::sync::atomic::AtomicBool);

impl gpui::Global for Backdrop {}

#[cfg(windows)]
pub(super) fn backdrop_global(cx: &mut App) {
    use std::sync::atomic::AtomicBool;
    cx.set_global(Backdrop(AtomicBool::new(false)));
}

#[cfg(not(windows))]
#[allow(dead_code)]
pub(super) fn backdrop_global(_cx: &mut App) {}

#[cfg(all(windows, not(test)))]
pub(super) fn backdrop_enabled(cx: &App) -> bool {
    cx.try_global::<Backdrop>()
        .map(|b| b.0.load(std::sync::atomic::Ordering::Relaxed))
        .unwrap_or(false)
}

#[cfg(any(not(windows), test))]
#[allow(dead_code)]
pub(super) fn backdrop_enabled(_cx: &App) -> bool {
    false
}

/// 窗口创建后调用：设置 Mica 与深浅色标记。只在 Windows 上生效。
#[cfg(all(windows, not(test)))]
pub(super) fn apply_backdrop(window: &Window, dark: bool, cx: &mut App) {
    use raw_window_handle::RawWindowHandle;

    let Ok(handle) = raw_window_handle::HasWindowHandle::window_handle(window) else {
        return;
    };
    let RawWindowHandle::Win32(win) = handle.as_raw() else {
        return;
    };
    let hwnd = win.hwnd.get();
    unsafe {
        let dark_value: i32 = i32::from(dark);
        DwmSetWindowAttribute(
            hwnd as isize,
            DWMWA_USE_IMMERSIVE_DARK_MODE,
            &dark_value as *const i32 as *const _,
            4,
        );
        let backdrop_value: u32 = DWMSBT_MAINWINDOW;
        let result = DwmSetWindowAttribute(
            hwnd as isize,
            DWMWA_SYSTEMBACKDROP_TYPE,
            &backdrop_value as *const u32 as *const _,
            4,
        );
        if result == 0
            && let Some(backdrop) = cx.try_global::<Backdrop>()
        {
            backdrop.0.store(true, std::sync::atomic::Ordering::Relaxed);
        }
    }
}

#[cfg(any(not(windows), test))]
#[allow(dead_code)]
pub(super) fn apply_backdrop(_window: &Window, _dark: bool, _cx: &mut App) {}
