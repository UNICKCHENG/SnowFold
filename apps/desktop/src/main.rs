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
                        // macOS：红绿灯挪到左侧 18pt、距顶 21pt。
                        // 系统按钮高 14pt，21 + 7 = 28，与自绘 logo（17..39）、
                        // 分段导航（13.5..42.5）的中轴 28 完全对齐。
                        traffic_light_position: Some(gpui::point(px(18.), px(21.))),
                        // macOS：隐藏系统标题栏，消除内容上方的黑色边框；
                        // Windows 需要自绘窗口控件才能隐藏，当前保留系统标题栏。
                        #[cfg(target_os = "macos")]
                        appears_transparent: true,
                        #[cfg(not(target_os = "macos"))]
                        appears_transparent: false,
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
