mod app;
mod assets;
use snowfold_core::{crypto, model, storage};

use gpui::{
    App, AppContext, Application, Bounds, TitlebarOptions, WindowBounds, WindowOptions, px, size,
};

fn main() {
    Application::new()
        .with_assets(assets::AppAssets)
        .run(|cx: &mut App| {
            gpui_component::init(cx);
            app::bind_keys(cx);
            let bounds = Bounds::centered(None, size(px(1440.), px(900.)), cx);
            if let Err(error) = cx.open_window(
                WindowOptions {
                    window_bounds: Some(WindowBounds::Windowed(bounds)),
                    window_min_size: Some(size(px(960.), px(720.))),
                    titlebar: Some(TitlebarOptions {
                        title: Some("SnowFold".into()),
                        // GPUI 桌面端只发布 Windows 版（Mac 由 apps/apple 的 SwiftUI 应用提供）；
                        // Windows 隐藏标题栏需要自绘窗口控件，当前保留系统标题栏。
                        appears_transparent: false,
                        ..Default::default()
                    }),
                    ..Default::default()
                },
                |window, cx| {
                    let view = cx.new(|cx| app::SnowFold::new(window, cx));
                    cx.new(|cx| gpui_component::Root::new(view, window, cx))
                },
            ) {
                eprintln!("无法打开 SnowFold 窗口: {error:#}");
                cx.quit();
            }
            cx.on_window_closed(|cx| {
                if cx.windows().is_empty() {
                    cx.quit();
                }
            })
            .detach();
            cx.activate(true);
        });
}
