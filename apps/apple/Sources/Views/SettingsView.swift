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

/// 设置：主题、数据（导入导出/清空）、加密备份与恢复码、关于。
struct SettingsView: View {
    @Environment(SnowFoldStore.self) private var store
    @Environment(\.dismiss) private var dismiss

    @State private var errorText: String?
    @State private var clearConfirm = false
    @State private var pendingJSONImport: JSONImport?
    @State private var pendingSealedImport: SealedImport?
    @State private var pendingKeyImport: KeyImport?

    @State private var showJSONImporter = false
    @State private var showSealedImporter = false
    @State private var showRecoveryImporter = false

    @State private var exportJSONText: String?
    @State private var exportSealedData: Data?
    @State private var exportRecoveryText: String?

    private struct JSONImport: Identifiable {
        let text: String
        let info: BackupInfo
        var id: String { "\(info.assets)-\(info.history)-\(info.subscriptions)" }
    }
    private struct SealedImport: Identifiable {
        let data: Data
        let info: SealedBackupInfo
        var id: String { info.deviceId + info.exportedAtDisplay }
    }
    private struct KeyImport: Identifiable {
        let contents: String
        let info: RecoveryCodeInfo
        var id: String { info.newFingerprint }
    }

    private static let sealedType = UTType(filenameExtension: "sfseal", conformingTo: .data) ?? .data

    var body: some View {
        Form {
            if let errorText {
                Section {
                    SheetErrorLine(message: errorText)
                }
            }
            appearanceSection
            backupSection
            keySection
            dataSection
            aboutSection
        }
        .accessibilityIdentifier("settings-form")
        .formStyle(.grouped)
        #if os(macOS)
        .frame(minWidth: 360, idealWidth: 520, maxWidth: 620)
        #endif
        .scrollContentBackground(.hidden)
        .background(Color.canvas)
        .navigationTitle(Copy.settingsTitle)
        .alert(Copy.clearConfirmationTitle, isPresented: $clearConfirm) {
            Button(Copy.cancel, role: .cancel) {}
            Button(Copy.confirmOverwrite, role: .destructive) {
                Task {
                    await store.clearAll()
                    errorText = nil
                }
            }
        } message: {
            Text(Copy.clearConfirmationBody)
        }
        .alert(Copy.importConfirmationTitle, isPresented: Binding(
            get: { pendingJSONImport != nil },
            set: { if !$0 { pendingJSONImport = nil } }
        )) {
            confirmButtonsForJSON
        } message: {
            if let pending = pendingJSONImport {
                Text(Copy.importConfirmationBody(
                    assets: pending.info.assets,
                    history: pending.info.history,
                    subscriptions: pending.info.subscriptions
                ))
            }
        }
        .alert(Copy.importConfirmationTitle, isPresented: Binding(
            get: { pendingSealedImport != nil },
            set: { if !$0 { pendingSealedImport = nil } }
        )) {
            confirmButtonsForSealed
        } message: {
            if let pending = pendingSealedImport {
                Text(Copy.sealedImportConfirmationBody(info: pending.info))
            }
        }
        .alert(Copy.replaceKeyConfirmationTitle, isPresented: Binding(
            get: { pendingKeyImport != nil },
            set: { if !$0 { pendingKeyImport = nil } }
        )) {
            confirmButtonsForKey
        } message: {
            if let pending = pendingKeyImport {
                Text(Copy.replaceKeyConfirmationBody(
                    current: pending.info.currentFingerprint,
                    new: pending.info.newFingerprint
                ))
            }
        }
        .fileImporter(isPresented: $showJSONImporter, allowedContentTypes: [.json, .data]) { result in
            handle(result) { url in
                let text = try readText(url)
                let info = try await store.inspectJSON(text)
                pendingJSONImport = JSONImport(text: text, info: info)
            }
        }
        .fileImporter(isPresented: $showSealedImporter, allowedContentTypes: [Self.sealedType, .data]) { result in
            handle(result) { url in
                let data = try readData(url)
                let info = try await store.inspectSealed(data)
                pendingSealedImport = SealedImport(data: data, info: info)
            }
        }
        .fileImporter(isPresented: $showRecoveryImporter, allowedContentTypes: [.text, .data]) { result in
            handle(result) { url in
                let contents = try readText(url)
                let info = try await store.inspectRecoveryCode(contents)
                pendingKeyImport = KeyImport(contents: contents, info: info)
            }
        }
        .fileExporter(
            isPresented: Binding(get: { exportJSONText != nil }, set: { if !$0 { exportJSONText = nil } }),
            document: exportJSONText.map { TextFileDocument(text: $0) },
            contentType: .json,
            defaultFilename: "snowfold-backup.json"
        ) { result in
            finishExport(result) { Copy.backupExported }
        }
        .fileExporter(
            isPresented: Binding(get: { exportSealedData != nil }, set: { if !$0 { exportSealedData = nil } }),
            document: exportSealedData.map { DataFileDocument(data: $0) },
            contentType: Self.sealedType,
            defaultFilename: "snowfold-backup.sfseal"
        ) { result in
            finishExport(result) {
                Copy.sealedExported(store.keyFingerprint ?? Copy.keyNotGenerated)
            }
        }
        .fileExporter(
            isPresented: Binding(get: { exportRecoveryText != nil }, set: { if !$0 { exportRecoveryText = nil } }),
            document: exportRecoveryText.map { TextFileDocument(text: $0) },
            contentType: .text,
            defaultFilename: "snowfold-recovery-code.txt"
        ) { result in
            finishExport(result) {
                Copy.recoveryExported(store.keyFingerprint ?? Copy.keyNotGenerated)
            }
        }
    }

    // MARK: 分区

    private var appearanceSection: some View {
        Section {
            VStack(alignment: .leading, spacing: 10) {
                Text("主题").font(.subheadline)
                ThemeSegmentedPicker()
            }
        } header: {
            Text(Copy.settingsThemeHeader)
        } footer: {
            Text(Copy.themeHelp)
        }
    }

    private var backupSection: some View {
        Section("备份") {
            settingsRow(title: "导出备份", help: Copy.exportBackupHelp) {
                Button(Copy.exportButton) { beginJSONExport() }.disabled(store.isWorking)
            }
            settingsRow(title: "导入备份", help: Copy.importBackupHelp) {
                Button(Copy.importButton) { showJSONImporter = true }.disabled(store.isWorking)
            }
            settingsRow(title: "导出加密备份", help: Copy.encryptedBackupHelp) {
                Button(Copy.exportSealedButton) { beginSealedExport() }.disabled(store.isWorking)
            }
            settingsRow(title: "导入加密备份", help: Copy.restoreEncryptedBackupHelp) {
                Button(Copy.importSealedButton) { showSealedImporter = true }.disabled(store.isWorking)
            }
        }
    }

    private var keySection: some View {
        Section("同步密钥") {
            VStack(alignment: .leading, spacing: 6) {
                Text(Copy.keyFingerprintLabel).font(.subheadline)
                Text(store.keyFingerprint ?? Copy.keyNotGenerated)
                    .font(.caption.monospaced())
                    .foregroundStyle(Color.inkMuted)
                    .textSelection(.enabled)
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityLabel("同步密钥指纹 " + (store.keyFingerprint ?? Copy.keyNotGenerated))
                Text(Copy.keyLocation)
                    .font(.caption).foregroundStyle(Color.inkMuted)
            }
            settingsRow(title: "导出恢复码", help: Copy.recoveryCodeHelp) {
                Button(Copy.exportRecoveryButton) { beginRecoveryExport() }.disabled(store.isWorking)
            }
            settingsRow(title: "导入恢复码", help: Copy.recoveryCodeHelp) {
                Button(Copy.importRecoveryButton) { showRecoveryImporter = true }.disabled(store.isWorking)
            }
        }
    }

    private var dataSection: some View {
        Section("数据") {
            VStack(alignment: .leading, spacing: 6) {
                Text("数据位置").font(.subheadline)
                Text(store.dataDirectory.path)
                    .font(.caption.monospaced()).foregroundStyle(Color.inkMuted)
                    .textSelection(.enabled).fixedSize(horizontal: false, vertical: true)
            }
            settingsRow(title: "清空全部数据", help: Copy.clearAllHelp) {
                Button(Copy.clearButton, role: .destructive) { clearConfirm = true }.disabled(store.isWorking)
            }
        }
    }

    private var aboutSection: some View {
        Section {
            Text(Copy.aboutOffline).font(.footnote).foregroundStyle(Color.inkMuted)
            Text(Copy.aboutVersion).font(.caption).foregroundStyle(Color.inkMuted)
        }
    }

    private func settingsRow<Control: View>(title: String, help: String, @ViewBuilder control: () -> Control) -> some View {
        ViewThatFits(in: .horizontal) {
            HStack(alignment: .firstTextBaseline, spacing: 12) {
                settingsExplanation(title, help: help)
                Spacer(minLength: 0)
                control().fixedSize()
            }
            VStack(alignment: .leading, spacing: 10) {
                settingsExplanation(title, help: help)
                control()
            }
        }
        .padding(.vertical, 4)
    }

    private func settingsExplanation(_ title: String, help: String) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(title).font(.subheadline)
            Text(help).font(.caption).foregroundStyle(Color.inkMuted)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    // MARK: 确认按钮

    @ViewBuilder
    private var confirmButtonsForJSON: some View {
        Button(Copy.cancel, role: .cancel) { pendingJSONImport = nil }
        Button(Copy.confirmOverwrite, role: .destructive) {
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
    }

    @ViewBuilder
    private var confirmButtonsForSealed: some View {
        Button(Copy.cancel, role: .cancel) { pendingSealedImport = nil }
        Button(Copy.confirmOverwrite, role: .destructive) {
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
    }

    @ViewBuilder
    private var confirmButtonsForKey: some View {
        Button(Copy.cancel, role: .cancel) { pendingKeyImport = nil }
        Button(Copy.confirmReplaceKey, role: .destructive) {
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
    }

    // MARK: 导出

    private func beginJSONExport() {
        Task {
            do {
                let text = try await store.exportJSON()
                #if os(macOS)
                try saveViaPanel(name: "snowfold-backup.json") { url in
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

    private func beginSealedExport() {
        Task {
            do {
                let data = try await store.exportSealed()
                #if os(macOS)
                try saveViaPanel(name: "snowfold-backup.sfseal") { url in
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

    private func beginRecoveryExport() {
        Task {
            do {
                let text = try await store.exportRecoveryCode()
                #if os(macOS)
                try saveViaPanel(name: "snowfold-recovery-code.txt") { url in
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

    private func finishExport(_ result: Result<URL, Error>, message: () -> String) {
        switch result {
        case .success:
            store.showNotice(message())
        case .failure(let error):
            errorText = Copy.exportFailed(error.localizedDescription)
        }
    }

    // MARK: 文件读取

    private func handle(_ result: Result<URL, Error>, work: @escaping (URL) async throws -> Void) {
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
    private func saveViaPanel(name: String, write: (URL) throws -> Void) throws {
        let panel = NSSavePanel()
        panel.nameFieldStringValue = name
        panel.canCreateDirectories = true
        guard panel.runModal() == .OK, let url = panel.url else { return } // 取消静默返回
        try validateExportTarget(url)
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        try write(url)
    }

    private func validateExportTarget(_ url: URL) throws {
        let dataDirectory = store.dataDirectory.standardizedFileURL.path
        let candidate = url.standardizedFileURL
        let protectedNames = ["snowfold.sqlite3", "snowfold-sync.key", "snowfold-sync.key.bak"]
        if candidate.deletingLastPathComponent().path == dataDirectory, protectedNames.contains(candidate.lastPathComponent) {
            throw CocoaError(.fileWriteFileExists, userInfo: [NSLocalizedDescriptionKey: Copy.exportTargetProtected])
        }
    }
    #endif
}

// MARK: - 主题分段选择（滑动高亮）

struct ThemeSegmentedPicker: View {
    @Environment(SnowFoldStore.self) private var store
    @Namespace private var selectionNamespace

    private let options: [(ThemePreference, String)] = [
        (.system, Copy.themeSystem),
        (.light, Copy.themeLight),
        (.dark, Copy.themeDark),
    ]

    var body: some View {
        HStack(spacing: 2) {
            ForEach(options, id: \.0) { theme, label in
                Button {
                    Task { await store.saveTheme(theme) }
                } label: {
                    // 桌面端 segment：激活段 active_tab 底 + ink 文字
                    Text(label)
                        .font(.caption.weight(store.theme == theme ? .medium : .regular))
                        .padding(.horizontal, 12)
                        .padding(.vertical, 5)
                        .background {
                            if store.theme == theme {
                                RoundedRectangle(cornerRadius: 7, style: .continuous)
                                    .fill(Color.activeTab)
                                    .matchedGeometryEffect(id: "theme-segment", in: selectionNamespace)
                            }
                        }
                        .foregroundStyle(store.theme == theme ? Color.ink : Color.inkMuted)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(store.theme == theme ? [.isSelected] : [])
            }
        }
        .padding(3)
        .background(Color.tabsBg, in: RoundedRectangle(cornerRadius: 9, style: .continuous))
        .animation(.spring(duration: 0.25), value: store.theme)
    }
}
