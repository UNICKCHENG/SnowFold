mod app;
mod assets;
mod platform;
mod theme;

use gpui::{
    App, AppContext, Application, Bounds, TitlebarOptions, WindowBounds, WindowOptions, px, size,
};

fn main() {
    Application::new()
        .with_assets(assets::AppAssets)
        .run(|cx: &mut App| {
            if let Err(error) = cx.text_system().add_fonts(vec![
                std::borrow::Cow::Borrowed(assets::INTER_TTF),
                std::borrow::Cow::Borrowed(assets::NOTO_TTF),
            ]) {
                eprintln!("内置字体注册失败: {error:#}");
            }
            gpui_component::init(cx);
            app::bind_keys(cx);
            platform::backdrop_global(cx);
            let bounds = Bounds::centered(None, size(px(1080.), px(720.)), cx);
            if let Err(error) = cx.open_window(
                WindowOptions {
                    window_bounds: Some(WindowBounds::Windowed(bounds)),
                    window_min_size: Some(size(px(900.), px(600.))),
                    titlebar: Some(TitlebarOptions {
                        title: Some("SnowFold".into()),
                        // GPUI 桌面端只发布 Windows 版（Mac 由 apps/apple 提供）；
                        // 透明标题栏 + 自绘系统样式窗口按钮，工具栏与标题栏合一。
                        appears_transparent: true,
                        ..Default::default()
                    }),
                    ..Default::default()
                },
                |window, cx| {
                    #[cfg(windows)]
                    platform::apply_backdrop(window, false, cx);
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
