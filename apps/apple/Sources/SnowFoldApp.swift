import SwiftUI

@main
struct SnowFoldApp: App {
    /// 共享模型层在 App 层持有：窗口内容与 Settings 场景注入同一实例。
    @State private var store = SnowFoldStore()
    @State private var router = Router()

    init() {
        let initialRouter = Router()
        #if os(macOS)
        let arguments = CommandLine.arguments
        if let index = arguments.firstIndex(of: "-SNOWFOLD_SHOT_TAB"), index + 1 < arguments.count,
           let tab = Router.Tab(rawValue: arguments[index + 1]) { initialRouter.tab = tab }
        #endif
        _router = State(initialValue: initialRouter)
        #if os(macOS)
        AcceptanceCapture.runIfRequested()
        #endif
    }

    var body: some Scene {
        #if os(macOS)
        WindowGroup("SnowFold") {
            RootView()
                .frame(minWidth: 760, minHeight: 500)
        }
        .environment(store)
        .environment(router)
        .defaultSize(width: 1080, height: 720)
        .commands {
            AppCommands()
        }
        Settings {
            SettingsView()
                .environment(store)
                .environment(router)
                .preferredColorScheme(store.theme.colorScheme)
        }
        #else
        WindowGroup("SnowFold") {
            RootView()
        }
        .environment(store)
        .environment(router)
        #endif
    }
}
