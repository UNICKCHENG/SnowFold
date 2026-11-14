import SwiftUI
#if os(iOS)
import UIKit
#endif

// MARK: - 分栏动作桥（菜单命令 → 当前窗口）

/// 由 SplitRootView 注入的焦点动作；菜单命令通过它驱动当前窗口。
struct AppActions {
    let newMixed: () -> Void
    let newSubscription: () -> Void
    let undo: () -> Void
    let selectTab: (Router.Tab) -> Void
}

struct AppActionsKey: FocusedValueKey {
    typealias Value = AppActions
}

extension FocusedValues {
    var appActions: AppActions? {
        get { self[AppActionsKey.self] }
        set { self[AppActionsKey.self] = newValue }
    }
}

#if os(macOS)
struct AppCommands: Commands {
    @FocusedValue(\.appActions) private var actions: AppActions?

    var body: some Commands {
        CommandGroup(replacing: .newItem) {
            Button("新建资产") { actions?.newMixed() }
                .keyboardShortcut("n", modifiers: .command)
            Button(Copy.newSubscription) { actions?.newSubscription() }
                .keyboardShortcut("n", modifiers: [.command, .shift])
        }
        CommandMenu("前往") {
            Button("总览") { actions?.selectTab(.overview) }
                .keyboardShortcut("1", modifiers: .command)
            Button("资产与负债") { actions?.selectTab(.assets) }
                .keyboardShortcut("2", modifiers: .command)
            Button("订阅") { actions?.selectTab(.subscriptions) }
                .keyboardShortcut("3", modifiers: .command)
            Divider()
            Button("撤销最近的变动") { actions?.undo() }
                .keyboardShortcut("z", modifiers: .command)
        }
    }
}
#endif

// MARK: - 启动失败

/// 打不开数据库时的整页错误（不展示业务内容）。
struct StartupFailureView: View {
    let message: String

    var body: some View {
        VStack(spacing: 12) {
            Image(systemName: "internaldrive.badge.exclamationmark")
                .font(.system(size: 40, weight: .light))
                .foregroundStyle(Color.ink3)
            Text(message)
                .font(.callout)
                .multilineTextAlignment(.center)
                .textSelection(.enabled)
                .padding(.horizontal, 28)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .accessibilityElement(children: .combine)
    }
}

// MARK: - 设置入口

extension View {
    func settingsToolbar() -> some View { settingsToolbar { EmptyView() } }

    func settingsToolbar<Actions: View>(@ViewBuilder actions: () -> Actions) -> some View {
        toolbar {
            ToolbarItemGroup(placement: .primaryAction) {
                #if os(macOS)
                SettingsLink { Label(Copy.settingsTitle, systemImage: "slider.horizontal.3") }
                #else
                SettingsSheetButton()
                #endif
                actions()
            }
        }
    }
}

#if os(iOS)
struct SettingsSheetButton: View {
    @Environment(Router.self) private var router

    var body: some View {
        Button {
            if UIDevice.current.userInterfaceIdiom == .pad { router.tab = .settings }
            else { router.settingsPresented = true }
        } label: {
            Label(Copy.settingsTitle, systemImage: "slider.horizontal.3")
        }
    }
}
#endif

// MARK: - iPhone 根容器

struct RootView: View {
    @Environment(SnowFoldStore.self) private var store
    @Environment(Router.self) private var router
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        Group {
            switch store.state {
            case .opening:
                ProgressView()
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            case .failed(let message):
                StartupFailureView(message: message)
            case .ready:
                readyContent
            }
        }
        .task { await store.open() }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active {
                Task { await store.refreshIfReady() }
            }
        }
        .preferredColorScheme(store.theme.colorScheme)
    }

    @ViewBuilder
    private var readyContent: some View {
        #if os(iOS)
        if UIDevice.current.userInterfaceIdiom == .pad {
            SplitRootView()
        } else {
            tabs
        }
        #else
        SplitRootView()
        #endif
    }

    @ViewBuilder
    private var tabs: some View {
        TabView(selection: Binding(
            get: { router.tab },
            set: { router.tab = $0 }
        )) {
            NavigationStack { OverviewView() }
                .bannerOverlay()
                .tabItem { Label("总览", systemImage: "chart.pie.fill") }
                .tag(Router.Tab.overview)
                .accessibilityIdentifier("nav-overview")
            NavigationStack { AssetsView() }
                .bannerOverlay()
                .tabItem { Label("资产", systemImage: "creditcard.fill") }
                .tag(Router.Tab.assets)
                .accessibilityIdentifier("nav-assets")
            NavigationStack { SubscriptionsView() }
                .bannerOverlay()
                .tabItem { Label("订阅", systemImage: "calendar.badge.clock") }
                .tag(Router.Tab.subscriptions)
                .accessibilityIdentifier("nav-subscriptions")
        }
        .sheet(isPresented: Binding(
            get: { router.settingsPresented },
            set: { router.settingsPresented = $0 }
        )) {
            settingsSheet
        }
    }

    private var settingsSheet: some View {
        NavigationStack {
            SettingsView()
                .navigationTitle(Copy.settingsTitle)
                #if os(iOS)
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .confirmationAction) {
                        Button(Copy.done) { router.settingsPresented = false }
                    }
                }
                #endif
        }
        .presentationDetents([.large])
    }
}

extension View {
    /// 撤销与通知横幅：挂在每个 tab 的 NavigationStack 上，浮在内容底部、标签栏之上。
    func bannerOverlay() -> some View {
        safeAreaInset(edge: .bottom, spacing: 0) {
            BannerOverlay()
        }
    }
}

// MARK: - 分栏根容器（Mac 与 iPad 共用，docs/mobile-layout.md §8）

struct SplitRootView: View {
    @Environment(SnowFoldStore.self) private var store
    @Environment(Router.self) private var router
    @Environment(\.scenePhase) private var scenePhase
    @State private var newAssetCategory: String?
    @State private var selectedAssetID: String?
    @State private var selectedSubscription: SubscriptionRecord?
    @State private var creatingSubscription = false

    private struct CategoryPick: Identifiable {
        let code: String
        var id: String { code }
    }

    var body: some View {
        splitContent
            #if os(macOS)
            .frame(minWidth: 760, minHeight: 500)
            #endif
            .focusedValue(\.appActions, AppActions(
                newMixed: {
                    if router.tab == .subscriptions { beginSubscription() }
                    else { newAssetCategory = "cash" }
                },
                newSubscription: { beginSubscription() },
                undo: { Task { await store.undoNow() } },
                selectTab: { router.tab = $0 }
            ))
            .sheet(item: Binding(
                get: { newAssetCategory.map(CategoryPick.init) },
                set: { newAssetCategory = $0?.code }
            )) { pick in AssetEditorSheet(mode: .new(categoryCode: pick.code)) }
            .task { await store.open() }
            .onChange(of: scenePhase) { _, phase in
                if phase == .active { Task { await store.refreshIfReady() } }
            }
            .preferredColorScheme(store.theme.colorScheme)
    }

    @ViewBuilder
    private var splitContent: some View {
        if router.tab == .assets || router.tab == .subscriptions {
            NavigationSplitView {
                sidebar
            } content: {
                NavigationStack { listContent }
                    .navigationSplitViewColumnWidth(min: 240, ideal: 320, max: 400)
            } detail: {
                NavigationStack { selectionDetail }
                    .bannerOverlay()
            }
            .navigationSplitViewStyle(.balanced)
        } else {
            NavigationSplitView {
                sidebar
            } detail: {
                NavigationStack {
                    if router.tab == .settings { SettingsView().navigationTitle(Copy.settingsTitle) }
                    else { OverviewView() }
                }
                .bannerOverlay()
            }
            .navigationSplitViewStyle(.balanced)
        }
    }

    private var sidebar: some View {
        List(selection: Binding<Router.Tab?>(
            get: { router.tab }, set: { if let tab = $0 { router.tab = tab } }
        )) {
            Label("总览", systemImage: "chart.pie.fill").tag(Router.Tab.overview)
                .accessibilityIdentifier("nav-overview")
            Label(Copy.assetsTitle, systemImage: "creditcard.fill").tag(Router.Tab.assets)
                .accessibilityIdentifier("nav-assets")
            Label(Copy.subscriptionsTitle, systemImage: "calendar.badge.clock").tag(Router.Tab.subscriptions)
                .accessibilityIdentifier("nav-subscriptions")
            Label(Copy.settingsTitle, systemImage: "slider.horizontal.3").tag(Router.Tab.settings)
                .accessibilityIdentifier("nav-settings")
        }
        .listStyle(.sidebar)
        .navigationTitle("SnowFold")
        .navigationSplitViewColumnWidth(min: 170, ideal: 190, max: 220)
    }

    @ViewBuilder
    private var listContent: some View {
        if router.tab == .assets {
            AssetsView(onSelect: { selectedAssetID = $0 }, selectedAssetID: selectedAssetID)
                .task { if selectedAssetID == nil { selectedAssetID = store.ledger.assets.first?.id } }
        } else {
            SubscriptionsView(onEdit: {
                creatingSubscription = false
                selectedSubscription = $0
            }, onNew: { beginSubscription() })
        }
    }

    @ViewBuilder
    private var selectionDetail: some View {
        if router.tab == .assets {
            if let id = selectedAssetID {
                AssetDetailView(assetID: id, onDelete: { selectedAssetID = nil })
                    .id(id)
            } else {
                EmptyStateView(icon: "wallet.pass", title: "选择一项资产", message: "查看余额、历史与修正记录")
            }
        } else if creatingSubscription {
            SubscriptionEditorSheet(mode: .new, embedded: true, onCompletion: endSubscription)
                .id("new-subscription")
        } else if let record = selectedSubscription {
            SubscriptionEditorSheet(mode: .edit(record), embedded: true, onCompletion: endSubscription)
                .id(record.id)
        } else {
            EmptyStateView(icon: "calendar", title: "选择一项订阅", message: "在这里查看和编辑扣费安排")
        }
    }

    private func beginSubscription() {
        router.tab = .subscriptions
        selectedSubscription = nil
        creatingSubscription = true
    }

    private func endSubscription() {
        creatingSubscription = false
        selectedSubscription = nil
    }
}
