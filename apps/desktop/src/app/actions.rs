use gpui::*;

actions!(
    snowfold,
    [Overview, Assets, Subscriptions, Close, Settings, New, Quit]
);

pub(crate) fn bind_keys(cx: &mut App) {
    // 桌面发布 Windows：Ctrl 为主；保留 Cmd 以便本机开发。
    for modifier in ["cmd", "ctrl"] {
        cx.bind_keys([
            KeyBinding::new(&format!("{modifier}-1"), Overview, None),
            KeyBinding::new(&format!("{modifier}-2"), Assets, None),
            KeyBinding::new(&format!("{modifier}-3"), Subscriptions, None),
            KeyBinding::new(&format!("{modifier}-,"), Settings, None),
            KeyBinding::new(&format!("{modifier}-n"), New, None),
        ]);
    }
    cx.bind_keys([
        KeyBinding::new("escape", Close, None),
        KeyBinding::new("cmd-q", Quit, None),
    ]);
    cx.on_action(|_: &Quit, cx| cx.quit());
}
