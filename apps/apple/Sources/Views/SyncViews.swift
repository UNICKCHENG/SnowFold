import SwiftUI

/// 凭证仅停留在安全输入框与本次提交闭包中，不进入 Store 或配置状态。
struct SyncSettingsSection: View {
    @Environment(SnowFoldStore.self) private var store
    let exportRecovery: () -> Void
    @State private var backend: SyncBackend = .s3
    @State private var endpoint = ""
    @State private var bucket = ""
    @State private var region = ""
    @State private var prefix = ""
    @State private var username = ""
    @State private var pathStyle = true
    @State private var secret = ""
    @State private var error: String?
    @State private var saving = false
    @State private var clearConfirmation = false

    var body: some View {
        Section {
            Text(Copy.syncHelp).font(.caption).foregroundStyle(Color.inkMuted)
                .fixedSize(horizontal: false, vertical: true)
            if store.syncStatus?.recoveryCodeExported != true {
                Text(Copy.syncRecoveryRequiredHelp).font(.caption).foregroundStyle(Color.inkMuted)
                Button(Copy.exportRecoveryButton, action: exportRecovery)
                    .disabled(store.isWorking || store.isSyncing)
            }
            Picker(Copy.syncBackendLabel, selection: $backend) {
                Text(Copy.syncBackendS3).tag(SyncBackend.s3)
                Text(Copy.syncBackendWebDAV).tag(SyncBackend.webDav)
            }
            .pickerStyle(.segmented)
            .accessibilityIdentifier("sync-backend")
            .onChange(of: backend) { _, _ in secret = "" }
            configFields
            if store.syncStatus?.config != nil {
                Text(Copy.syncSecretReenterHelp).font(.caption).foregroundStyle(Color.inkMuted)
                    .fixedSize(horizontal: false, vertical: true)
            }
            if let error { SheetErrorLine(message: error) }
            Button(Copy.syncSaveConfig) { save() }
                .disabled(saving || store.isSyncing)
                .accessibilityIdentifier("sync-save")
            if let status = store.syncStatus, status.config != nil {
                configuredControls(status)
            }
            Text(Copy.syncPairingHelp).font(.caption).foregroundStyle(Color.inkMuted)
                .fixedSize(horizontal: false, vertical: true)
        } header: {
            Text(Copy.settingsSyncHeader)
        }
        .task { loadConfig() }
        .onChange(of: store.syncStatus?.config) { _, _ in loadConfig() }
        .onDisappear { secret = "" }
        .alert(Copy.syncClearConfirmationTitle, isPresented: $clearConfirmation) {
            Button(Copy.cancel, role: .cancel) {}
            Button(Copy.syncClearConfig, role: .destructive) {
                perform { try await store.clearSyncConfig() }
            }
        } message: { Text(Copy.syncClearConfirmationBody) }
    }

    @ViewBuilder
    private var configFields: some View {
        field(Copy.syncEndpointLabel, text: $endpoint, id: "sync-endpoint")
        if backend == .s3 {
            field(Copy.syncBucketLabel, text: $bucket, id: "sync-bucket")
            field(Copy.syncRegionLabel, text: $region, id: "sync-region")
        }
        field(Copy.syncPrefixLabel, text: $prefix, id: "sync-prefix")
        if backend == .s3 {
            Toggle(Copy.syncPathStyleLabel, isOn: $pathStyle)
        }
        field(backend == .s3 ? Copy.syncAccessKeyLabel : Copy.syncUsernameLabel,
              text: $username, id: "sync-username")
        VStack(alignment: .leading, spacing: 6) {
            Text(backend == .s3 ? Copy.syncSecretKeyLabel : Copy.syncPasswordLabel).font(.subheadline)
            SecureField(backend == .s3 ? Copy.syncSecretKeyLabel : Copy.syncPasswordLabel, text: $secret)
                .textContentType(.password)
                .accessibilityIdentifier("sync-secret")
                #if os(iOS)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                #endif
        }
    }

    private func field(_ label: String, text: Binding<String>, id: String) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(label).font(.subheadline)
            TextField(label, text: text)
                .accessibilityIdentifier(id)
                #if os(iOS)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                #endif
        }
    }

    @ViewBuilder
    private func configuredControls(_ status: SyncStatus) -> some View {
        Toggle(Copy.syncEnable, isOn: Binding(get: { status.enabled }, set: { value in
            perform { try await store.setSyncEnabled(value) }
        }))
        .disabled(saving || store.isSyncing || !status.recoveryCodeExported)
        .accessibilityIdentifier("sync-enable")
        Text(Copy.syncPolicyHeader).font(.subheadline.weight(.semibold))
        Toggle(Copy.syncOnLaunchLabel, isOn: Binding(get: { status.policy.onLaunch }, set: { value in
            var policy = status.policy; policy.onLaunch = value
            perform { try await store.setSyncPolicy(policy) }
        })).disabled(saving || store.isSyncing)
        Picker(Copy.syncIntervalLabel, selection: Binding(get: { status.policy.intervalMinutes }, set: { value in
            var policy = status.policy; policy.intervalMinutes = value
            perform { try await store.setSyncPolicy(policy) }
        })) {
            Text(Copy.syncIntervalOff).tag(UInt32(0))
            Text(Copy.syncInterval15m).tag(UInt32(15))
            Text(Copy.syncInterval30m).tag(UInt32(30))
            Text(Copy.syncInterval1h).tag(UInt32(60))
        }.disabled(saving || store.isSyncing)
        Toggle(Copy.syncAfterEditLabel, isOn: Binding(get: { status.policy.afterEdit }, set: { value in
            var policy = status.policy; policy.afterEdit = value
            perform { try await store.setSyncPolicy(policy) }
        })).disabled(saving || store.isSyncing)
        Text(Copy.syncIntervalHelp).font(.caption).foregroundStyle(Color.inkMuted)
            .fixedSize(horizontal: false, vertical: true)
        TimelineView(.periodic(from: .now, by: 30)) { context in
            Text(store.isSyncing ? Copy.syncInProgress : status.lastSyncedAt.map {
                Copy.syncLastSynced(Fmt.syncTime($0, now: context.date))
            } ?? Copy.syncNeverSynced)
                .font(.subheadline).foregroundStyle(Color.inkMuted)
                .accessibilityIdentifier("sync-status")
        }
        if let message = store.syncError { SheetErrorLine(message: message) }
        Button(Copy.syncNow) { Task { await store.syncNow() } }
            .disabled(!status.enabled || store.isSyncing || saving)
        Button(Copy.syncClearConfig, role: .destructive) { clearConfirmation = true }
            .disabled(store.isSyncing || saving)
    }

    private func save() {
        let config = SyncConfig(backend: backend, endpoint: endpoint, bucket: backend == .s3 ? bucket : "",
                                region: backend == .s3 ? region : "", prefix: prefix, username: username,
                                pathStyle: backend == .s3 && pathStyle)
        let credential = secret
        secret = ""
        perform { try await store.configureSync(config, secret: credential) }
    }

    private func perform(_ operation: @escaping @MainActor () async throws -> Void) {
        guard !saving else { return }
        saving = true
        error = nil
        Task {
            defer { saving = false }
            do { try await operation() }
            catch { self.error = Copy.syncMessage(for: error, configuration: true) }
        }
    }

    private func loadConfig() {
        secret = ""
        guard let config = store.syncStatus?.config else {
            endpoint = ""; bucket = ""; region = ""; prefix = ""; username = ""
            return
        }
        backend = config.backend
        endpoint = config.endpoint; bucket = config.bucket; region = config.region
        prefix = config.prefix; username = config.username; pathStyle = config.pathStyle
    }
}

struct SyncToolbarButton: View {
    @Environment(SnowFoldStore.self) private var store
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        if store.showsSyncButton {
            Button { Task { await store.syncNow() } } label: {
                TimelineView(.animation(paused: !store.isSyncing || reduceMotion)) { context in
                    Image(systemName: "arrow.triangle.2.circlepath")
                        .rotationEffect(.degrees(store.isSyncing && !reduceMotion
                            ? context.date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: 1) * 360 : 0))
                        .overlay(alignment: .topTrailing) {
                            if store.syncError != nil {
                                Circle().fill(Color.negativeBrand).frame(width: 6, height: 6).offset(x: 3, y: -3)
                            }
                        }
                }
            }
            .disabled(store.isSyncing)
            .accessibilityLabel(store.isSyncing ? Copy.syncInProgress : store.syncError == nil ? Copy.syncButtonA11y : Copy.syncButtonFailedA11y)
            .accessibilityIdentifier("sync-toolbar")
            .help(store.syncStatus?.lastSyncedAt.map { Copy.syncButtonTooltip(Fmt.syncTime($0)) } ?? Copy.syncNeverSynced)

        }
    }
}

/// 窗口级宽度由 RootView 注入，避免分栏里的 compact 误判。
struct PageSyncToolbarEntry: View {
    @Environment(\.showsSettingsToolbar) private var compact
    var showOnPhone = false
    var body: some View {
        #if os(macOS)
        SyncToolbarButton()
        #else
        if showOnPhone || !compact { SyncToolbarButton() }
        #endif
    }
}
