import SwiftUI
#if DEBUG
import Security
#if os(macOS)
import AppKit
#endif
#endif

@main
struct SnowFoldApp: App {
    /// 共享模型层在 App 层持有：窗口内容与设置窗口注入同一实例。
    @State private var store = SnowFoldStore()
    @State private var router = Router()

    init() {
        let initialRouter = Router()
        #if DEBUG
        let captureArguments = CommandLine.arguments
        if let index = captureArguments.firstIndex(of: "-SNOWFOLD_KEYCHAIN_ACCOUNT"),
           index + 1 < captureArguments.count {
            let operations = captureArguments.contains("-SNOWFOLD_KEYCHAIN_FAILURE")
                ? KeychainKeyStore.Operations(copyMatching: { _, _ in errSecInteractionNotAllowed })
                : KeychainKeyStore.Operations()
            let transport: any SyncTransport = captureArguments.contains("-SNOWFOLD_SYNC_ACCEPTANCE")
                ? CaptureSyncTransport() : URLSessionSyncTransport()
            _store = State(initialValue: SnowFoldStore(
                dataDirectory: FileManager.default.temporaryDirectory
                    .appendingPathComponent(captureArguments[index + 1], isDirectory: true),
                keyStore: KeychainKeyStore(account: captureArguments[index + 1], operations: operations),
                syncTransport: transport
            ))
            #if os(macOS)
            // 仅验收启动：使用常规宽窗口，避免沿用之前的窗口恢复尺寸。
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
                if let window = NSApp.windows.first(where: { $0.canBecomeKey }) {
                    window.setContentSize(NSSize(width: 1080, height: 720))
                    window.center()
                    window.makeKeyAndOrderFront(nil)
                    NSApp.activate(ignoringOtherApps: true)
                }
            }
            #endif
        } else if ProcessInfo.processInfo.environment["XCTestConfigurationFilePath"] != nil {
            // XCTest 的宿主场景也隔离，不能触碰用户默认开发库。
            let account = "snowfold-keychain-host-\(UUID().uuidString)"
            _store = State(initialValue: SnowFoldStore(
                dataDirectory: FileManager.default.temporaryDirectory
                    .appendingPathComponent(account, isDirectory: true),
                keyStore: KeychainKeyStore(account: account)
            ))
        }
        #endif
        #if DEBUG || ACCEPTANCE
        let arguments = CommandLine.arguments
        if let index = arguments.firstIndex(of: "-SNOWFOLD_SHOT_TAB"), index + 1 < arguments.count,
           let tab = Router.Tab(rawValue: arguments[index + 1]) {
            initialRouter.tab = tab
        }
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
                .tint(.brand)
                .frame(minWidth: 900, minHeight: 600)
        }
        .windowResizability(.contentMinSize)
        .environment(store)
        .environment(router)
        .defaultSize(width: 1080, height: 720)
        .commands {
            AppCommands()
        }
        Settings {
            MacSettingsWindowView()
                .environment(store)
                .environment(router)
                .preferredColorScheme(store.theme.colorScheme)
                .tint(.brand)
        }
        #else
        WindowGroup("SnowFold") {
            RootView()
                .tint(.brand)
        }
        .environment(store)
        .environment(router)
        #endif
    }
}
