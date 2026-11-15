import SwiftUI
#if os(iOS)
import UIKit
#endif

// MARK: - 路由与弹层

/// 页面路由：iPhone 切换 TabView，iPad/Mac 切侧边栏选择；Mac 设置走独立窗口。
@MainActor
@Observable
final class Router {
    enum Tab: String, CaseIterable, Identifiable {
        case overview, assets, subscriptions, settings
        var id: String { rawValue }
    }

    var tab: Tab = .overview
    /// 从总览分类卡跳到资产页并定位到该分类。
    var focusAssetCategory: String?
    /// 统一弹层入口：iOS 呈现为 sheet，macOS 呈现为居中弹层。
    var modal: Modal?
    /// Mac 设置窗口当前页签（Cmd+, 独立窗口）。
    var settingsTab: MacSettingsTab = .general
}

/// Mac 设置窗口的四个页签（design.md §9.6）。
enum MacSettingsTab: String, CaseIterable, Identifiable {
    case general, data, seal, about
    var id: String { rawValue }

    var title: String {
        switch self {
        case .general: return Copy.settingsTabGeneral
        case .data: return Copy.settingsTabData
        case .seal: return Copy.settingsTabSecuritySync
        case .about: return Copy.settingsTabAbout
        }
    }

    var icon: String {
        switch self {
        case .general: return "ic-settings"
        case .data: return "ic-database"
        case .seal: return "ic-shield-lock"
        case .about: return "ic-info"
        }
    }
}

/// 全局弹层（添加资产、调整余额、修正历史、新建/编辑订阅）。
enum Modal: Identifiable {
    case newAsset(categoryCode: String)
    case renameAsset(AssetRecord)
    case adjustBalance(AssetRecord)
    case correctHistory(HistoryRecord, assetName: String)
    case newSubscription
    case editSubscription(SubscriptionRecord)

    var id: String {
        switch self {
        case .newAsset(let code): return "new-asset-\(code)"
        case .renameAsset(let asset): return "rename-\(asset.id)"
        case .adjustBalance(let asset): return "balance-\(asset.id)"
        case .correctHistory(let record, _): return "correct-\(record.id)"
        case .newSubscription: return "new-subscription"
        case .editSubscription(let record): return "edit-\(record.id)"
        }
    }
}

/// 可撤销横幅：令牌来自核心，validForMs 内有效。
struct UndoState: Equatable {
    let token: String
    let message: String
    let deadline: Date
}

// MARK: - 菜单命令桥

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
            Button(Copy.addAsset) { actions?.newMixed() }
                .keyboardShortcut("n", modifiers: .command)
            Button(Copy.newSubscription) { actions?.newSubscription() }
                .keyboardShortcut("n", modifiers: [.command, .shift])
        }
        CommandGroup(after: .sidebar) {
            Button(Copy.overviewTitle) { actions?.selectTab(.overview) }
                .keyboardShortcut("1", modifiers: .command)
            Button(Copy.assetsTitle) { actions?.selectTab(.assets) }
                .keyboardShortcut("2", modifiers: .command)
            Button(Copy.subscriptionsTitle) { actions?.selectTab(.subscriptions) }
                .keyboardShortcut("3", modifiers: .command)
            Divider()
            Button("撤销最近的变动") { actions?.undo() }
                .keyboardShortcut("z", modifiers: .command)
        }
    }
}
#endif

// MARK: - 根容器

struct RootView: View {
    @Environment(SnowFoldStore.self) private var store
    @Environment(Router.self) private var router
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.horizontalSizeClass) private var sizeClass
    #if os(macOS)
    @Environment(\.openSettings) private var openSettings
    #endif
    @State private var sceneID = UUID()

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
        .task {
            await store.open()
            #if DEBUG
            await SyncAcceptance.prepareIfRequested(store)
            #endif
            await store.setSceneActive(sceneID, active: scenePhase == .active)
            await handleShotHooks()
        }
        .onChange(of: scenePhase) { _, phase in
            Task { await store.setSceneActive(sceneID, active: phase == .active) }
        }
        .onDisappear { Task { await store.setSceneActive(sceneID, active: false) } }
        .preferredColorScheme(store.theme.colorScheme)
    }

    @ViewBuilder
    private var readyContent: some View {
        #if os(iOS)
        if UIDevice.current.userInterfaceIdiom == .pad, sizeClass == .regular {
            SplitRootView()
        } else {
            PhoneRootView()
        }
        #else
        SplitRootView()
        #endif
    }

    /// 验收截图钩子（DEBUG 或显式 ACCEPTANCE 条件；见 AcceptanceCapture）。
    @MainActor
    private func handleShotHooks() async {
        #if (DEBUG || ACCEPTANCE) && os(macOS)
        guard store.state == .ready else { return }
        let arguments = CommandLine.arguments
        func value(_ flag: String) -> String? {
            guard let index = arguments.firstIndex(of: flag), index + 1 < arguments.count else { return nil }
            return arguments[index + 1]
        }
        if value("-SNOWFOLD_SHOT_DIALOG") == "asset" { router.modal = .newAsset(categoryCode: "cash") }
        if value("-SNOWFOLD_SHOT_DIALOG") == "adjust",
           let asset = store.ledger.assets.first { router.modal = .adjustBalance(asset) }
        if value("-SNOWFOLD_SHOT_DIALOG") == "correct",
           let record = store.ledger.history.first,
           let asset = store.ledger.assets.first(where: { $0.id == record.assetId }) {
            router.modal = .correctHistory(record, assetName: asset.name)
        }
        if value("-SNOWFOLD_SHOT_DIALOG") == "sub-new" { router.modal = .newSubscription }
        if value("-SNOWFOLD_SHOT_DIALOG") == "sub-edit",
           let record = store.ledger.subscriptions.first { router.modal = .editSubscription(record) }
        if arguments.contains("-SNOWFOLD_SHOT_NOTICE") {
            try? await Task.sleep(for: .seconds(0.3))
            store.showNotice(Copy.backupExported)
        }
        if arguments.contains("-SNOWFOLD_SHOT_UNDO"),
           let asset = store.ledger.assets.first {
            // 变动一次余额触发真实撤销横幅（临时数据目录，不影响任何真实库）。
            try? await store.changeBalance(id: asset.id, amountText: Fmt.decimal(asset.amountCents + 123_45))
        }
        if let tab = value("-SNOWFOLD_SHOT_OPEN_SETTINGS") {
            router.settingsTab = MacSettingsTab(rawValue: tab) ?? .general
            await Task.yield()
            openSettings()
        }
        #endif
    }
}

// MARK: - 启动失败（§9.7）

/// 打不开数据库时的整页错误：不展示业务内容、无按钮。
struct StartupFailureView: View {
    let message: String

    var body: some View {
        VStack(spacing: 0) {
            AppIcon(name: "ic-warning-triangle", size: 48)
                .foregroundStyle(Color.neg)
                .frame(width: 88, height: 88)
                .background(Color.negSoft, in: Circle())
                .padding(.bottom, 16)
            Text(Copy.startupErrorTitle)
                .font(Ds.sectionTitle())
                .foregroundStyle(Color.ink)
                .padding(.bottom, 8)
            Text(message)
                .font(Ds.body())
                .foregroundStyle(Color.text2)
                .multilineTextAlignment(.center)
                .frame(maxWidth: 280)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color.bg)
        .accessibilityElement(children: .combine)
    }
}

// MARK: - iPhone 根容器（docs/mobile-layout.md §1）

struct PhoneRootView: View {
    @Environment(SnowFoldStore.self) private var store
    @Environment(Router.self) private var router

    var body: some View {
        @Bindable var router = router
        TabView(selection: $router.tab) {
            NavigationStack { OverviewView() }
                .bannerOverlay()
                .tabItem { tabLabel("ic-pie-chart", Copy.overviewTitle) }
                .tag(Router.Tab.overview)
                .accessibilityIdentifier("nav-overview")
            NavigationStack { AssetsView() }
                .bannerOverlay()
                .tabItem { tabLabel("ic-wallet", Copy.assetsTab) }
                .tag(Router.Tab.assets)
                .accessibilityIdentifier("nav-assets")
            NavigationStack { SubscriptionsView() }
                .bannerOverlay()
                .tabItem { tabLabel("ic-calendar-repeat", Copy.subscriptionsTitle) }
                .tag(Router.Tab.subscriptions)
                .accessibilityIdentifier("nav-subscriptions")
            NavigationStack { SettingsView() }
                .bannerOverlay()
                .tabItem { tabLabel("ic-settings", Copy.settingsTitle) }
                .tag(Router.Tab.settings)
                .accessibilityIdentifier("nav-settings")
        }
        .tint(.brand)
        .sheet(item: Binding(
            get: { router.modal },
            set: { router.modal = $0 }
        )) { modal in
            ModalSheet(modal: modal)
        }
    }

    private func tabLabel(_ icon: String, _ title: String) -> some View {
        Label {
            Text(title)
        } icon: {
            AppIcon(name: icon, size: 24)
        }
    }
}

/// iOS 弹层容器：mobile-layout.md §6 的底部 sheet。
struct ModalSheet: View {
    let modal: Modal

    var body: some View {
        switch modal {
        case .newAsset(let code):
            AssetEditorSheet(mode: .new(categoryCode: code))
        case .renameAsset(let asset):
            AssetEditorSheet(mode: .rename(asset))
        case .adjustBalance(let asset):
            AssetEditorSheet(mode: .balance(asset))
        case .correctHistory(let record, let name):
            HistoryCorrectionSheet(history: record, assetName: name)
        case .newSubscription:
            SubscriptionEditorSheet(mode: .new)
        case .editSubscription(let record):
            SubscriptionEditorSheet(mode: .edit(record))
        }
    }
}

/// 工具栏动作只在手机形态挂载（iPad/Mac 由分栏容器统一挂载，避免重复按钮）。
struct PhoneOnlyToolbar<Actions: View>: ViewModifier {
    @ViewBuilder var actions: () -> Actions

    func body(content: Content) -> some View {
        #if os(iOS)
        if UIDevice.current.userInterfaceIdiom == .pad {
            content
        } else {
            content.toolbar { ToolbarItem(placement: .topBarTrailing) { actions() } }
        }
        #else
        content
        #endif
    }
}

struct PhoneOnlyInlineTitle: ViewModifier {
    func body(content: Content) -> some View {
        #if os(iOS)
        if UIDevice.current.userInterfaceIdiom == .pad {
            content
        } else {
            content.navigationBarTitleDisplayMode(.inline)
        }
        #else
        content
        #endif
    }
}

extension View {
    func phoneToolbar<Actions: View>(@ViewBuilder actions: @escaping () -> Actions) -> some View {
        modifier(PhoneOnlyToolbar(actions: actions))
    }

    func phoneInlineTitle() -> some View {
        modifier(PhoneOnlyInlineTitle())
    }

    /// 撤销与通知横幅：浮在内容底部（iPhone 在标签栏上方，iPad/Mac 底部居中）。
    func bannerOverlay(centered: Bool = false) -> some View {
        safeAreaInset(edge: .bottom, spacing: 0) {
            BannerOverlay()
                .frame(maxWidth: centered ? 640 : .infinity)
                .frame(maxWidth: .infinity)
                .padding(.bottom, centered ? 24 : 8)
        }
    }
}

// MARK: - 分栏根容器（Mac 与 iPad 常规宽度共用，docs/mobile-layout.md §8）

struct SplitRootView: View {
    @Environment(SnowFoldStore.self) private var store
    @Environment(Router.self) private var router
    @Environment(\.colorScheme) private var colorScheme
    @State private var selectedAssetID: String?
    @State private var assetListScrollTo: String?

    var body: some View {
        @Bindable var router = router
        NavigationSplitView {
            sidebar
        } detail: {
            detail
        }
        .modifier(SplitModalPresenter())
        .onChange(of: store.syncGeneration) { _, _ in
            if let id = selectedAssetID, !store.ledger.assets.contains(where: { $0.id == id }) {
                selectedAssetID = nil
            }
        }
    }

    // MARK: 侧边栏（§9.1：220 宽浮层；设置固定左下角）

    private var sidebar: some View {
        VStack(spacing: 0) {
            #if os(iOS)
            Text("SnowFold")
                .font(Ds.sectionTitle())
                .foregroundStyle(Color.ink)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 12)
                .padding(.top, 8)
                .padding(.bottom, 12)
            #endif
            VStack(spacing: 2) {
                sidebarRow(.overview, icon: "ic-pie-chart", title: Copy.overviewTitle)
                sidebarRow(.assets, icon: "ic-wallet", title: Copy.assetsTitle)
                sidebarRow(.subscriptions, icon: "ic-calendar-repeat", title: Copy.subscriptionsTitle)
            }
            Spacer(minLength: 12)
            sidebarRow(.settings, icon: "ic-settings", title: Copy.settingsTitle)
                .padding(.bottom, 8)
        }
        .padding(.horizontal, 10)
        .padding(.top, 4)
        #if os(macOS)
        .frame(minWidth: 220, idealWidth: 220, maxWidth: 220)
        .navigationSplitViewColumnWidth(min: 220, ideal: 220, max: 220)
        #else
        .frame(minWidth: 260, idealWidth: 260, maxWidth: 260)
        .navigationSplitViewColumnWidth(min: 260, ideal: 260, max: 260)
        #endif
        .accessibilityElement(children: .contain)
    }

    /// 侧边栏行：行高 32（桌面）/44（iPad），图标 20/24 与文字间距 10/12；
    /// 选中 brand-soft 底、实心图标与文字 brand（中等）。
    private func sidebarRow(_ tab: Router.Tab, icon: String, title: String) -> some View {
        let selected = router.tab == tab
        return Button {
            withAnimation(.easeOut(duration: 0.15)) { router.tab = tab }
        } label: {
            HStack(spacing: Ds.isDesktop ? 10 : 12) {
                AppIcon(name: icon, size: Ds.isDesktop ? 20 : 24, fill: selected)
                    .foregroundStyle(selected ? Color.brand : Color.text2)
                Text(title)
                    .font(selected ? Ds.bodyMedium() : Ds.body())
                    .foregroundStyle(selected ? Color.brand : Color.ink)
                    .lineLimit(1)
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 10)
            .frame(minHeight: Ds.isDesktop ? 32 : 44, alignment: .leading)
            .background(
                selected ? AnyShapeStyle(Color.brandSoft) : AnyShapeStyle(.clear),
                in: RoundedRectangle(cornerRadius: 10, style: .continuous)
            )
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("nav-" + tab.rawValue)
        .accessibilityLabel(title)
        .accessibilityAddTraits(selected ? [.isSelected] : [])
    }

    // MARK: 详情列

    @ViewBuilder
    private var detail: some View {
        NavigationStack {
            switch router.tab {
            case .overview:
                OverviewView()
            case .assets:
                AssetsSplitView(selectedAssetID: $selectedAssetID, scrollTo: $assetListScrollTo)
            case .subscriptions:
                SubscriptionsView()
            case .settings:
                #if os(macOS)
                OverviewView() // Mac 设置走独立窗口（⌘,），侧栏无设置项
                #else
                SettingsView()
                #endif
            }
        }
        .bannerOverlay(centered: true)
        .toolbar { ToolbarItem(placement: .primaryAction) { PageToolbarActions() } }
    }
}

/// 资产页两栏（§9.3）：左列表 + 右概览/详情；未选中时右栏是总资产概览。
struct AssetsSplitView: View {
    @Environment(SnowFoldStore.self) private var store
    @Binding var selectedAssetID: String?
    @Binding var scrollTo: String?

    var body: some View {
        #if os(macOS)
        HStack(alignment: .top, spacing: 24) {
            AssetsListView(selectedAssetID: $selectedAssetID, scrollTo: $scrollTo, selectionMode: true, showsSummary: false)
                .frame(width: 380)
            AssetsDetailPane(selectedAssetID: selectedAssetID)
        }
        .padding(.horizontal, 32)
        .padding(.top, 8)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        #else
        HStack(alignment: .top, spacing: 24) {
            AssetsListView(selectedAssetID: $selectedAssetID, scrollTo: $scrollTo, selectionMode: true, showsSummary: false)
                .frame(maxWidth: .infinity)
            AssetsDetailPane(selectedAssetID: selectedAssetID)
                .frame(maxWidth: .infinity)
        }
        .padding(.horizontal, 24)
        .padding(.top, 8)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        #endif
        // 自动选中首项：数据载入后且当前无选中（审计 #14 的时机缺陷在此修复）。
        .onChange(of: store.ledger) { _, ledger in
            if selectedAssetID == nil, let first = ledger.assets.first {
                selectedAssetID = first.id
            }
        }
        .task {
            if selectedAssetID == nil { selectedAssetID = store.ledger.assets.first?.id }
        }
    }
}

/// 页面工具栏动作：同步（开启后）与「＋」；Mac/iPad 由分栏容器挂载。
struct PageToolbarActions: View {
    @Environment(SnowFoldStore.self) private var store
    @Environment(Router.self) private var router

    var body: some View {
        HStack(spacing: 8) {
            SyncToolbarButton()
            if router.tab == .assets || router.tab == .overview {
                ToolbarPlusButton(icon: "ic-plus", label: Copy.addAsset, identifier: "new-asset") {
                    router.modal = .newAsset(categoryCode: router.focusAssetCategory ?? "cash")
                }
            }
            if router.tab == .subscriptions {
                ToolbarPlusButton(icon: "ic-plus", label: Copy.newSubscription, identifier: "new-subscription") {
                    router.modal = .newSubscription
                }
            }
        }
    }
}

struct ToolbarPlusButton: View {
    let icon: String
    let label: String
    let identifier: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            AppIcon(name: icon, size: 18)
        }
        .accessibilityLabel(label)
        .accessibilityIdentifier(identifier)
    }
}

// MARK: - macOS 居中弹层（§5.6 桌面弹层）

/// mac 主窗口内的弹层宿主：遮罩 + 居中卡片（宽 480、圆角 16、浮层阴影）。
struct SplitModalPresenter: ViewModifier {
    @Environment(Router.self) private var router
    @Environment(\.colorScheme) private var colorScheme

    func body(content: Content) -> some View {
        #if os(macOS)
        content
            .overlay {
                if let modal = router.modal {
                    ZStack {
                        (colorScheme == .dark ? Color.black.opacity(0.56) : Color.ink.opacity(0.32))
                            .ignoresSafeArea()
                            .transition(.opacity)
                        MacDialogHost(modal: modal)
                            .transition(.opacity.combined(with: .scale(scale: 0.98)))
                    }
                }
            }
            .animation(.easeOut(duration: 0.15), value: router.modal)
        #else
        content
            .sheet(item: Binding(
                get: { router.modal },
                set: { router.modal = $0 }
            )) { modal in
                ModalSheet(modal: modal)
            }
        #endif
    }
}

struct MacDialogHost: View {
    let modal: Modal

    var body: some View {
        switch modal {
        case .newAsset(let code):
            AssetEditorSheet(mode: .new(categoryCode: code))
        case .renameAsset(let asset):
            AssetEditorSheet(mode: .rename(asset))
        case .adjustBalance(let asset):
            AssetEditorSheet(mode: .balance(asset))
        case .correctHistory(let record, let name):
            HistoryCorrectionSheet(history: record, assetName: name)
        case .newSubscription:
            SubscriptionEditorSheet(mode: .new)
        case .editSubscription(let record):
            SubscriptionEditorSheet(mode: .edit(record))
        }
    }
}
