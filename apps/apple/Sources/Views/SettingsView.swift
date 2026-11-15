import SwiftUI
import UniformTypeIdentifiers
#if os(macOS)
import AppKit
#endif

// MARK: - 导出文档包装

struct TextFileDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.text] }
    var text: String

    init(text: String) {
        self.text = text
    }

    init(configuration: ReadConfiguration) throws {
        let data = configuration.file.regularFileContents ?? Data()
        text = String(data: data, encoding: .utf8) ?? ""
    }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        FileWrapper(regularFileWithContents: Data(text.utf8))
    }
}

struct DataFileDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.data] }
    var data: Data

    init(data: Data) {
        self.data = data
    }

    init(configuration: ReadConfiguration) throws {
        data = configuration.file.regularFileContents ?? Data()
    }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        FileWrapper(regularFileWithContents: data)
    }
}

// MARK: - 设置（iPhone/iPad 页面，mobile-layout.md §5）

struct SettingsView: View {
    var body: some View {
        #if os(macOS)
        MacSettingsWindowView()
        #else
        MobileSettingsView()
        #endif
    }
}

#if os(iOS)
struct MobileSettingsView: View {
    @Environment(SnowFoldStore.self) private var store
    @Environment(\.horizontalSizeClass) private var sizeClass
    @StateObject private var actions = SettingsActions()

    var body: some View {
        ScrollView {
            // iPad：内容区单列，最宽 720 居中（mobile-layout.md §8）。
            SettingsGroups(mode: .all, actions: actions)
                .frame(maxWidth: sizeClass == .regular ? 720 : nil)
                .frame(maxWidth: .infinity)
                .padding(.horizontal, 16)
                .padding(.top, 8)
                .padding(.bottom, 24)
        }
        .background(Color.bg)
        .navigationTitle(Copy.settingsTitle)
        .phoneInlineTitle()
        .settingsFlows(actions)
        .confirmationDialog(Copy.syncClearConfirmationTitle,
                            isPresented: $actions.syncClearConfirmation, titleVisibility: .visible) {
            Button(Copy.syncClearConfig, role: .destructive) { actions.confirmClearSyncConfig(store) }
            Button(Copy.cancel, role: .cancel) {}
        } message: {
            Text(Copy.syncClearConfirmationBody)
        }
    }
}
#endif

#if os(macOS)
/// Mac 设置窗口（§9.6）：宽 680；顶部 88 浮层工具栏，四个页签（图标 24 + 辅助文字，
/// 选中 brand-soft 底 + 实心图标 brand）；内容左右 32、上 24，分组间 24。
struct MacSettingsWindowView: View {
    @Environment(SnowFoldStore.self) private var store
    @Environment(Router.self) private var router
    @StateObject private var actions = SettingsActions()

    var body: some View {
        @Bindable var router = router
        VStack(spacing: 0) {
            header
            ScrollView {
                VStack(alignment: .leading, spacing: 24) {
                    switch router.settingsTab {
                    case .general:
                        SettingsGroups(mode: .general, actions: actions)
                    case .data:
                        SettingsGroups(mode: .data, actions: actions)
                    case .seal:
                        SettingsGroups(mode: .seal, actions: actions)
                    case .about:
                        SettingsGroups(mode: .about, actions: actions)
                    }
                }
                .padding(.horizontal, 32)
                .padding(.top, 24)
                .padding(.bottom, 32)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .frame(minWidth: 680, idealWidth: 680, maxWidth: 680, minHeight: 560)
        .background(Color.bg)
        .overlay { macConfirms }
        .settingsFlows(actions)
        .accessibilityIdentifier("settings-window")
    }

    /// Mac 确认框（§5.6 桌面确认框）：宽 420、左侧 40 圆形图标块、底栏取消+危险。
    @ViewBuilder
    private var macConfirms: some View {
        if actions.clearConfirm {
            MacConfirmDialog(
                icon: "ic-warning-triangle",
                title: Copy.clearConfirmationTitle,
                message: Copy.clearConfirmationBody,
                confirmTitle: Copy.clearConfirm,
                onCancel: { actions.clearConfirm = false },
                onConfirm: {
                    actions.clearConfirm = false
                    actions.confirmClear(store)
                }
            )
        }
        if let pending = actions.pendingJSONImport {
            MacConfirmDialog(
                icon: "ic-warning-triangle",
                title: Copy.importConfirmationTitle,
                message: Copy.importConfirmationBody(
                    assets: pending.info.assets,
                    history: pending.info.history,
                    subscriptions: pending.info.subscriptions
                ),
                confirmTitle: Copy.confirmOverwrite,
                onCancel: { actions.pendingJSONImport = nil },
                onConfirm: { actions.confirmJSONImport(store) }
            )
        }
        if let pending = actions.pendingSealedImport {
            MacConfirmDialog(
                icon: "ic-warning-triangle",
                title: Copy.importConfirmationTitle,
                message: Copy.sealedImportConfirmationBody(info: pending.info),
                confirmTitle: Copy.confirmOverwrite,
                onCancel: { actions.pendingSealedImport = nil },
                onConfirm: { actions.confirmSealedImport(store) }
            )
        }
        if let pending = actions.pendingKeyImport {
            MacConfirmDialog(
                icon: "ic-key",
                title: Copy.replaceKeyConfirmationTitle,
                message: Copy.replaceKeyConfirmationBody(
                    current: pending.info.currentFingerprint,
                    new: pending.info.newFingerprint
                ),
                confirmTitle: Copy.confirmReplaceKey,
                onCancel: { actions.pendingKeyImport = nil },
                onConfirm: { actions.confirmKeyImport(store) }
            )
        }
        if actions.syncClearConfirmation {
            MacConfirmDialog(
                icon: "ic-warning-triangle",
                title: Copy.syncClearConfirmationTitle,
                message: Copy.syncClearConfirmationBody,
                confirmTitle: Copy.syncClearConfig,
                onCancel: { actions.syncClearConfirmation = false },
                onConfirm: {
                    actions.syncClearConfirmation = false
                    actions.confirmClearSyncConfig(store)
                }
            )
        }
    }

    private var header: some View {
        @Bindable var router = router
        return VStack(spacing: 8) {
            Text(router.settingsTab.title)
                .font(Ds.sectionTitle())
                .foregroundStyle(Color.ink)
            HStack(spacing: 4) {
                ForEach(MacSettingsTab.allCases) { tab in
                    let selected = router.settingsTab == tab
                    Button {
                        router.settingsTab = tab
                    } label: {
                        VStack(spacing: 2) {
                            AppIcon(name: tab.icon, size: 24, fill: selected)
                                .foregroundStyle(selected ? Color.brand : Color.text2)
                            Text(tab.title)
                                .font(Ds.auxMedium())
                                .foregroundStyle(selected ? Color.brand : Color.text2)
                                .lineLimit(1)
                                .minimumScaleFactor(0.8)
                        }
                        .frame(width: 84, height: 52)
                        .background(
                            selected ? AnyShapeStyle(Color.brandSoft) : AnyShapeStyle(.clear),
                            in: RoundedRectangle(cornerRadius: 10, style: .continuous)
                        )
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(tab.title)
                    .accessibilityAddTraits(selected ? [.isSelected] : [])
                    .accessibilityIdentifier("settings-tab-" + tab.rawValue)
                }
            }
        }
        .frame(maxWidth: .infinity)
        .frame(height: 88)
        .glassEffect()
    }

    private var aboutGroup: some View {
        SettingsCard {
            VStack(alignment: .leading, spacing: 8) {
                Text(Copy.aboutVersion).font(Ds.body())
                Text(Copy.aboutOffline).font(Ds.aux()).foregroundStyle(Color.text2)
            }
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .frame(minHeight: Ds.doubleRowHeight)
        }
    }
}
#endif

// MARK: - 分组内容（移动五组 / Mac 按页签取子集）

/// 按宿主取子集：移动全量（外观/数据/加密备份/同步/关于），Mac 按页签。
struct SettingsGroups: View {
    enum Mode { case all, general, data, seal, about }

    @Environment(SnowFoldStore.self) private var store
    let mode: Mode
    @Environment(\.dynamicTypeSize) private var typeSize
    @ObservedObject var actions: SettingsActions

    var body: some View {
        VStack(alignment: .leading, spacing: 24) {
            switch mode {
            case .all:
                appearanceGroup
                dataGroup
                keyGroup
                SyncSection(exportRecovery: { actions.beginRecoveryExport(store) }, onClearConfig: { actions.syncClearConfirmation = true })
                aboutGroup
            case .general:
                appearanceGroup
            case .data:
                dataGroup
            case .seal:
                keyGroup
                SyncSection(exportRecovery: { actions.beginRecoveryExport(store) },
                            onClearConfig: { actions.syncClearConfirmation = true })
            case .about:
                aboutGroup
            }
            if let errorText = actions.errorText {
                FormErrorLine(message: errorText)
            }
        }
    }

    // MARK: 外观

    private var appearanceGroup: some View {
        SettingsGroup(title: Copy.settingsAppearanceHeader) {
            SettingsCard {
                VStack(alignment: .leading, spacing: 8) {
                    Text(Copy.themeHelp)
                        .font(Ds.aux())
                        .foregroundStyle(Color.text2)
                        .fixedSize(horizontal: false, vertical: true)
                    ThemeSegmentedPicker()
                        #if os(macOS)
                        .frame(width: 240)
                        #endif
                }
                .padding(16)
                .frame(maxWidth: .infinity, alignment: .leading)
                .frame(minHeight: Ds.doubleRowHeight)
            }
        }
    }

    // MARK: 数据（导出/导入/清空）

    private var dataGroup: some View {
        SettingsGroup(title: Copy.settingsDataHeader) {
            SettingsCard {
                VStack(spacing: 0) {
                    settingsRow(title: Copy.exportBackupTitle, help: Copy.exportBackupHelp) {
                        Button(Copy.exportButton) { actions.beginJSONExport(store) }
                            .buttonStyle(SecondaryButtonStyle())
                            .disabled(store.isWorking)
                            .accessibilityIdentifier("export-json")
                    }
                    rowSeparator
                    settingsRow(title: Copy.importBackupTitle, help: Copy.importBackupHelp) {
                        Button(Copy.importButton) { actions.showJSONImporter = true }
                            .buttonStyle(SecondaryButtonStyle())
                            .disabled(store.isWorking)
                            .accessibilityIdentifier("import-json")
                    }
                    rowSeparator
                    settingsRow(title: Copy.clearAllTitle, help: Copy.clearAllHelp, titleColor: .neg) {
                        Button(Copy.clearButton) { actions.clearConfirm = true }
                            .buttonStyle(DangerButtonStyle())
                            .disabled(store.isWorking)
                            .accessibilityIdentifier("clear-all")
                    }
                }
                .padding(.vertical, 8)
            }
        }
    }

    // MARK: 加密备份与密钥

    private var keyGroup: some View {
        SettingsGroup(title: Copy.settingsEncryptedHeader) {
            SettingsCard {
                VStack(spacing: 0) {
                    settingsRow(title: Copy.encryptedBackupTitle, help: Copy.encryptedBackupHelp) {
                        Button(Copy.exportSealedButton) { actions.beginSealedExport(store) }
                            .buttonStyle(SecondaryButtonStyle())
                            .disabled(store.isWorking)
                            .accessibilityIdentifier("export-sealed")
                    }
                    rowSeparator
                    settingsRow(title: Copy.restoreEncryptedBackupTitle, help: Copy.restoreEncryptedBackupHelp) {
                        Button(Copy.importSealedButton) { actions.showSealedImporter = true }
                            .buttonStyle(SecondaryButtonStyle())
                            .disabled(store.isWorking)
                            .accessibilityIdentifier("import-sealed")
                    }
                    rowSeparator
                    settingsRow(title: Copy.keyFingerprintLabel, help: Copy.keyLocation) {
                        Text(store.keyFingerprint ?? Copy.keyNotGenerated)
                            .font(Ds.aux().monospaced())
                            .foregroundStyle(Color.text2)
                            .textSelection(.enabled)
                            .fixedSize(horizontal: false, vertical: true)
                            .accessibilityLabel(Copy.keyFingerprintLabel + " " + (store.keyFingerprint ?? Copy.keyNotGenerated))
                            .accessibilityIdentifier("key-fingerprint")
                    }
                    rowSeparator
                    settingsRow(title: Copy.recoveryCodeTitle, help: Copy.recoveryCodeHelp) {
                        HStack(spacing: 8) {
                            Button(Copy.exportRecoveryButton) { actions.beginRecoveryExport(store) }
                                .buttonStyle(SecondaryButtonStyle())
                                .disabled(store.isWorking)
                                .accessibilityIdentifier("export-recovery")
                            Button(Copy.importRecoveryButton) { actions.showRecoveryImporter = true }
                                .buttonStyle(SecondaryButtonStyle())
                                .disabled(store.isWorking)
                                .accessibilityIdentifier("import-recovery")
                        }
                    }
                }
                .padding(.vertical, 8)
            }
        }
    }

    private var aboutGroup: some View {
        SettingsGroup(title: Copy.settingsAboutHeader) {
            SettingsCard {
                VStack(alignment: .leading, spacing: 8) {
                    Text(Copy.aboutVersion).font(Ds.body())
                    Text(Copy.aboutOffline).font(Ds.aux()).foregroundStyle(Color.text2)
                }
                .padding(16)
                .frame(maxWidth: .infinity, alignment: .leading)
                .frame(minHeight: Ds.doubleRowHeight)
            }
        }
    }

    private var rowSeparator: some View {
        Rectangle().fill(Color.sep).frame(height: 1)
    }

    /// 设置行：标题正文 + 说明辅助 text2（最宽 360），右侧控件；双行行最小高 56。
    private func settingsRow<Control: View>(
        title: String, help: String?, titleColor: Color = .ink,
        @ViewBuilder control: () -> Control
    ) -> some View {
        let layout = typeSize.isAccessibilitySize
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: 8))
            : AnyLayout(HStackLayout(alignment: .center, spacing: 12))
        return layout {
            VStack(alignment: .leading, spacing: 4) {
                Text(title)
                    .font(Ds.body())
                    .foregroundStyle(titleColor)
                if let help {
                    Text(help)
                        .font(Ds.aux())
                        .foregroundStyle(Color.text2)
                        .frame(maxWidth: 360, alignment: .leading)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            Spacer(minLength: 12)
            control()
        }
        .padding(.horizontal, 16)
        .frame(minHeight: Ds.doubleRowHeight)
    }
}

/// Mac 确认框（§5.6）：宽 420、内边距 24、左侧 40 圆形图标块（neg-soft 底 neg 图标）、
/// 右侧标题 + 正文；底栏右对齐取消（默认焦点）+ 危险按钮。
struct MacConfirmDialog: View {
    let icon: String
    let title: String
    let message: String
    let confirmTitle: String
    let onCancel: () -> Void
    let onConfirm: () -> Void

    var body: some View {
        ZStack {
            Color.black.opacity(0.32).ignoresSafeArea()
            HStack(alignment: .top, spacing: 16) {
                AppIcon(name: icon, size: 20)
                    .foregroundStyle(Color.neg)
                    .frame(width: 40, height: 40)
                    .background(Color.negSoft, in: Circle())
                VStack(alignment: .leading, spacing: 8) {
                    Text(title)
                        .font(Ds.sectionTitle())
                        .foregroundStyle(Color.ink)
                    Text(message)
                        .font(Ds.body())
                        .foregroundStyle(Color.text2)
                        .fixedSize(horizontal: false, vertical: true)
                    HStack(spacing: 8) {
                        Spacer(minLength: 8)
                        Button(Copy.cancel, action: onCancel)
                            .buttonStyle(SecondaryButtonStyle())
                            .keyboardShortcut(.cancelAction)
                            .accessibilityIdentifier("confirm-cancel")
                        Button(confirmTitle) { onConfirm() }
                            .buttonStyle(DangerButtonStyle())
                            .keyboardShortcut(.defaultAction)
                            .accessibilityIdentifier("confirm-danger")
                    }
                    .padding(.top, 12)
                }
            }
            .padding(24)
            .frame(width: 420)
            .background(Color.cardBg, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            .shadow(color: .black.opacity(0.14), radius: 32, y: 8)
            .padding(24)
        }
    }
}

// MARK: - 共享容器

struct SettingsGroup<Content: View>: View {
    let title: String
    @ViewBuilder var content: () -> Content

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(title)
                .font(Ds.auxMedium())
                .foregroundStyle(Color.text2)
                .padding(.leading, 4)
            content()
        }
    }
}

struct SettingsCard<Content: View>: View {
    @ViewBuilder var content: () -> Content

    var body: some View {
        VStack(alignment: .leading, spacing: 0) { content() }
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color.cardBg, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
    }
}

// MARK: - 状态与文件流（原行为保持不变）

@MainActor
final class SettingsActions: ObservableObject {
    @Published var errorText: String?
    @Published var clearConfirm = false
    @Published var pendingJSONImport: JSONImport?
    @Published var pendingSealedImport: SealedImport?
    @Published var pendingKeyImport: KeyImport?
    @Published var syncClearConfirmation = false

    @Published var showJSONImporter = false
    @Published var showSealedImporter = false
    @Published var showRecoveryImporter = false

    @Published var exportJSONText: String?
    @Published var exportSealedData: Data?
    @Published var exportRecoveryText: String?

    struct JSONImport: Identifiable {
        let text: String
        let info: BackupInfo
        var id: String { "\(info.assets)-\(info.history)-\(info.subscriptions)" }
    }
    struct SealedImport: Identifiable {
        let data: Data
        let info: SealedBackupInfo
        var id: String { info.deviceId + info.exportedAtDisplay }
    }
    struct KeyImport: Identifiable {
        let contents: String
        let info: RecoveryCodeInfo
        var id: String { info.newFingerprint }
    }

    static let sealedType = UTType(filenameExtension: "sfseal", conformingTo: .data) ?? .data

    // MARK: 导出

    func beginJSONExport(_ store: SnowFoldStore) {
        Task {
            do {
                let text = try await store.exportJSON()
                #if os(macOS)
                try saveViaPanel(store, name: "snowfold-backup.json") { url in
                    try Data(text.utf8).write(to: url)
                }
                store.showNotice(Copy.backupExported)
                #else
                exportJSONText = text
                #endif
                errorText = nil
            } catch {
                errorText = Copy.exportFailed(Copy.userMessage(for: error))
            }
        }
    }

    func beginSealedExport(_ store: SnowFoldStore) {
        Task {
            do {
                let data = try await store.exportSealed()
                #if os(macOS)
                try saveViaPanel(store, name: "snowfold-backup.sfseal") { url in
                    try data.write(to: url)
                }
                store.showNotice(Copy.sealedExported(store.keyFingerprint ?? Copy.keyNotGenerated))
                #else
                exportSealedData = data
                #endif
                errorText = nil
            } catch {
                errorText = Copy.sealedExportFailed(Copy.userMessage(for: error))
            }
        }
    }

    func beginRecoveryExport(_ store: SnowFoldStore) {
        Task {
            do {
                let text = try await store.exportRecoveryCode()
                #if os(macOS)
                try saveViaPanel(store, name: "snowfold-recovery-code.txt") { url in
                    try Data(text.utf8).write(to: url)
                }
                store.showNotice(Copy.recoveryExported(store.keyFingerprint ?? Copy.keyNotGenerated))
                #else
                exportRecoveryText = text
                #endif
                errorText = nil
            } catch {
                errorText = Copy.recoveryExportFailed(Copy.userMessage(for: error))
            }
        }
    }

    private func finishExport(_ result: Result<URL, Error>, store: SnowFoldStore, message: () -> String) {
        switch result {
        case .success:
            store.showNotice(message())
        case .failure(let error):
            errorText = Copy.exportFailed(error.localizedDescription)
        }
    }

    // MARK: 导入读取

    func handleJSONImport(_ result: Result<URL, Error>, store: SnowFoldStore) {
        handle(result, store: store) { [self] url in
            let text = try readText(url)
            let info = try await store.inspectJSON(text)
            pendingJSONImport = JSONImport(text: text, info: info)
        }
    }

    func handleSealedImport(_ result: Result<URL, Error>, store: SnowFoldStore) {
        handle(result, store: store) { [self] url in
            let data = try readData(url)
            let info = try await store.inspectSealed(data)
            pendingSealedImport = SealedImport(data: data, info: info)
        }
    }

    func handleKeyImport(_ result: Result<URL, Error>, store: SnowFoldStore) {
        handle(result, store: store) { [self] url in
            let contents = try readText(url)
            let info = try await store.inspectRecoveryCode(contents)
            pendingKeyImport = KeyImport(contents: contents, info: info)
        }
    }

    private func handle(_ result: Result<URL, Error>, store: SnowFoldStore,
                        work: @escaping @MainActor (URL) async throws -> Void) {
        switch result {
        case .success(let url):
            Task {
                do {
                    try await work(url)
                    errorText = nil
                } catch {
                    errorText = Copy.userMessage(for: error)
                }
            }
        case .failure:
            break // 用户取消，静默返回
        }
    }

    private func readText(_ url: URL) throws -> String {
        try withScopedAccess(url) {
            try String(contentsOf: url, encoding: .utf8)
        }
    }

    private func readData(_ url: URL) throws -> Data {
        try withScopedAccess(url) {
            try Data(contentsOf: url)
        }
    }

    private func withScopedAccess<T>(_ url: URL, _ work: () throws -> T) rethrows -> T {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        return try work()
    }

    #if os(macOS)
    /// macOS 导出经 NSSavePanel：可校验目标不覆盖自身数据文件。
    private func saveViaPanel(_ store: SnowFoldStore, name: String, write: (URL) throws -> Void) throws {
        let panel = NSSavePanel()
        panel.nameFieldStringValue = name
        panel.canCreateDirectories = true
        guard panel.runModal() == .OK, let url = panel.url else { return } // 取消静默返回
        try validateExportTarget(store, url: url)
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        try write(url)
    }

    private func validateExportTarget(_ store: SnowFoldStore, url: URL) throws {
        let dataDirectory = store.dataDirectory.standardizedFileURL.path
        let candidate = url.standardizedFileURL
        let protectedNames = ["snowfold.sqlite3", "snowfold-sync.key", "snowfold-sync.key.bak"]
        if candidate.deletingLastPathComponent().path == dataDirectory, protectedNames.contains(candidate.lastPathComponent) {
            throw CocoaError(.fileWriteFileExists, userInfo: [NSLocalizedDescriptionKey: Copy.exportTargetProtected])
        }
    }
    #endif

    // MARK: 确认后的提交

    func confirmJSONImport(_ store: SnowFoldStore) {
        guard let pending = pendingJSONImport else { return }
        pendingJSONImport = nil
        Task {
            do {
                try await store.importJSON(pending.text)
                errorText = nil
            } catch {
                errorText = Copy.importFailedUnchanged(Copy.userMessage(for: error))
            }
        }
    }

    func confirmSealedImport(_ store: SnowFoldStore) {
        guard let pending = pendingSealedImport else { return }
        pendingSealedImport = nil
        Task {
            do {
                try await store.importSealed(pending.data)
                errorText = nil
            } catch {
                errorText = Copy.sealedImportFailedUnchanged(Copy.userMessage(for: error))
            }
        }
    }

    func confirmKeyImport(_ store: SnowFoldStore) {
        guard let pending = pendingKeyImport else { return }
        pendingKeyImport = nil
        // 对话框打开期间密钥若已被改动，指纹对不上就拒绝（与桌面一致）。
        if store.keyFingerprint != nil, pending.info.currentFingerprint != nil,
           store.keyFingerprint != pending.info.currentFingerprint {
            errorText = Copy.keyChangedElsewhere
            return
        }
        Task {
            do {
                try await store.importRecoveryCode(
                    pending.contents,
                    expectedCurrentFingerprint: pending.info.currentFingerprint
                )
                errorText = nil
            } catch {
                errorText = Copy.recoveryImportFailedUnchanged(Copy.userMessage(for: error))
            }
        }
    }

    func confirmClearSyncConfig(_ store: SnowFoldStore) {
        Task {
            do {
                try await store.clearSyncConfig()
                errorText = nil
            } catch {
                errorText = Copy.syncMessage(for: error, configuration: true)
            }
        }
    }

    func confirmClear(_ store: SnowFoldStore) {
        Task {
            await store.clearAll()
            errorText = nil
        }
    }
}

// MARK: - 文件选择与确认挂载（两个宿主共用）

struct SettingsFlowsModifier: ViewModifier {
    @Environment(SnowFoldStore.self) private var store
    @ObservedObject var actions: SettingsActions

    func body(content: Content) -> some View {
        content
            .fileImporter(isPresented: $actions.showJSONImporter, allowedContentTypes: [.json, .data]) { result in
                actions.handleJSONImport(result, store: store)
            }
            .fileImporter(isPresented: $actions.showSealedImporter,
                          allowedContentTypes: [SettingsActions.sealedType, .data]) { result in
                actions.handleSealedImport(result, store: store)
            }
            .fileImporter(isPresented: $actions.showRecoveryImporter, allowedContentTypes: [.text, .data]) { result in
                actions.handleKeyImport(result, store: store)
            }
            .fileExporter(
                isPresented: Binding(get: { actions.exportJSONText != nil }, set: { if !$0 { actions.exportJSONText = nil } }),
                document: actions.exportJSONText.map { TextFileDocument(text: $0) },
                contentType: .json,
                defaultFilename: "snowfold-backup.json"
            ) { result in
                actions.finishExportManual(result, store: store, message: { Copy.backupExported })
            }
            .fileExporter(
                isPresented: Binding(get: { actions.exportSealedData != nil }, set: { if !$0 { actions.exportSealedData = nil } }),
                document: actions.exportSealedData.map { DataFileDocument(data: $0) },
                contentType: SettingsActions.sealedType,
                defaultFilename: "snowfold-backup.sfseal"
            ) { result in
                actions.finishExportManual(result, store: store) {
                    Copy.sealedExported(store.keyFingerprint ?? Copy.keyNotGenerated)
                }
            }
            .fileExporter(
                isPresented: Binding(get: { actions.exportRecoveryText != nil }, set: { if !$0 { actions.exportRecoveryText = nil } }),
                document: actions.exportRecoveryText.map { TextFileDocument(text: $0) },
                contentType: .text,
                defaultFilename: "snowfold-recovery-code.txt"
            ) { result in
                actions.finishExportManual(result, store: store) {
                    Copy.recoveryExported(store.keyFingerprint ?? Copy.keyNotGenerated)
                }
            }
            // 移动确认框：系统 alert（§5.6）；Mac 确认框在各自宿主内自绘。
            #if os(iOS)
            .confirmationDialog(Copy.clearConfirmationTitle, isPresented: $actions.clearConfirm, titleVisibility: .visible) {
                Button(Copy.clearConfirm, role: .destructive) { actions.confirmClear(store) }
                Button(Copy.cancel, role: .cancel) {}
            } message: {
                Text(Copy.clearConfirmationBody)
            }
            .alert(Copy.importConfirmationTitle, isPresented: Binding(
                get: { actions.pendingJSONImport != nil },
                set: { if !$0 { actions.pendingJSONImport = nil } }
            )) {
                confirmButtonsJSON
            } message: {
                if let pending = actions.pendingJSONImport {
                    Text(Copy.importConfirmationBody(
                        assets: pending.info.assets,
                        history: pending.info.history,
                        subscriptions: pending.info.subscriptions
                    ))
                }
            }
            .alert(Copy.importConfirmationTitle, isPresented: Binding(
                get: { actions.pendingSealedImport != nil },
                set: { if !$0 { actions.pendingSealedImport = nil } }
            )) {
                confirmButtonsSealed
            } message: {
                if let pending = actions.pendingSealedImport {
                    Text(Copy.sealedImportConfirmationBody(info: pending.info))
                }
            }
            .alert(Copy.replaceKeyConfirmationTitle, isPresented: Binding(
                get: { actions.pendingKeyImport != nil },
                set: { if !$0 { actions.pendingKeyImport = nil } }
            )) {
                confirmButtonsKey
            } message: {
                if let pending = actions.pendingKeyImport {
                    Text(Copy.replaceKeyConfirmationBody(
                        current: pending.info.currentFingerprint,
                        new: pending.info.newFingerprint
                    ))
                }
            }
            #endif
    }

    #if os(iOS)
    @ViewBuilder
    private var confirmButtonsJSON: some View {
        Button(Copy.cancel, role: .cancel) { actions.pendingJSONImport = nil }
        Button(Copy.confirmOverwrite, role: .destructive) { actions.confirmJSONImport(store) }
    }

    @ViewBuilder
    private var confirmButtonsSealed: some View {
        Button(Copy.cancel, role: .cancel) { actions.pendingSealedImport = nil }
        Button(Copy.confirmOverwrite, role: .destructive) { actions.confirmSealedImport(store) }
    }

    @ViewBuilder
    private var confirmButtonsKey: some View {
        Button(Copy.cancel, role: .cancel) { actions.pendingKeyImport = nil }
        Button(Copy.confirmReplaceKey, role: .destructive) { actions.confirmKeyImport(store) }
    }
    #endif
}

extension SettingsActions {
    func finishExportManual(_ result: Result<URL, Error>, store: SnowFoldStore, message: () -> String) {
        switch result {
        case .success:
            store.showNotice(message())
        case .failure(let error):
            errorText = Copy.exportFailed(error.localizedDescription)
        }
    }
}

// MARK: - 主题分段（brand 选中）

struct ThemeSegmentedPicker: View {
    @Environment(SnowFoldStore.self) private var store
    @State private var selection: ThemePreference = .system

    private var themeBinding: Binding<ThemePreference> {
        Binding<ThemePreference>(
            get: { store.theme },
            set: { newValue in
                selection = newValue
                Task { await store.saveTheme(newValue) }
            }
        )
    }

    var body: some View {
        BrandSegmentedPicker(
            options: [ThemePreference.system, ThemePreference.light, ThemePreference.dark],
            selection: themeBinding,
            label: { Copy.themeLabel($0) },
            identifier: { "theme-" + String(describing: $0) }
        )
        .accessibilityIdentifier("theme-picker")
    }
}

// MARK: - 各分组入口（合并 SettingsGroups 与文件流）

extension View {
    func settingsFlows(_ actions: SettingsActions) -> some View {
        modifier(SettingsFlowsModifier(actions: actions))
    }
}
