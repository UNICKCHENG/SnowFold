import SwiftUI

/// 同步设置段（设置页「加密与同步」/ 移动端「同步」组）。
/// 未配置＝卡片内表单（标签列 136 右对齐；移动标签宽 112 + 输入）；
/// 已配置＝开启同步（开关 + 上次同步）、存储（说明 + 修改）、立即同步、同步策略、清除配置。
struct SyncSection: View {
    @Environment(SnowFoldStore.self) private var store
    let exportRecovery: () -> Void
    var onClearConfig: (() -> Void)? = nil
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
    @State private var editingStorage = false

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            if store.syncStatus?.config == nil {
                unconfigured
            } else if let status = store.syncStatus, status.config != nil {
                configured(status)
            }
        }
        .task { loadConfig() }
        .onChange(of: store.syncStatus?.config) { _, _ in loadConfig() }
        .onDisappear { secret = "" }
    }

    // MARK: 未配置

    private var unconfigured: some View {
        SettingsGroup(title: Copy.settingsSyncHeader) {
            SettingsCard {
                VStack(alignment: .leading, spacing: 12) {
                    FormHelpLine(message: Copy.syncHelp)
                    if store.syncStatus?.recoveryCodeExported != true {
                        FormHelpLine(message: Copy.syncRecoveryRequiredHelp)
                        Button(Copy.exportRecoveryButton) { exportRecovery() }
                            .buttonStyle(SecondaryButtonStyle())
                            .disabled(store.isWorking || store.isSyncing)
                            .accessibilityIdentifier("sync-export-recovery")
                    }
                    configForm
                    if store.syncStatus?.config != nil {
                        FormHelpLine(message: Copy.syncSecretReenterHelp)
                    }
                    if let error {
                        FormErrorLine(message: error)
                    }
                    HStack {
                        Spacer(minLength: 0)
                        Button(Copy.syncSaveConfig) { save() }
                            .buttonStyle(PrimaryButtonStyle())
                            .disabled(saving || store.isSyncing)
                            .accessibilityIdentifier("sync-save")
                    }
                }
                .padding(16)
            }
            FormHelpLine(message: Copy.syncPairingHelp).padding(.leading, 4)
        }
    }

    /// 标签列 136 右对齐（桌面）/112（移动）+ 输入。
    private var labelWidth: CGFloat { Ds.isDesktop ? 136 : 112 }

    private var configForm: some View {
        VStack(alignment: .leading, spacing: 12) {
            FormRow(label: { dialogLabel(Copy.syncBackendLabel) }) {
                BrandSegmentedPicker(
                    options: [SyncBackend.s3, .webDav],
                    selection: $backend,
                    label: { $0 == .s3 ? Copy.syncBackendS3 : Copy.syncBackendWebDAV }
                )
                .onChange(of: backend) { _, _ in secret = "" }
                .accessibilityIdentifier("sync-backend")
                .frame(maxWidth: 300)
            }
            fieldRow(Copy.syncEndpointLabel, text: $endpoint, id: "sync-endpoint")
            if backend == .s3 {
                fieldRow(Copy.syncBucketLabel, text: $bucket, id: "sync-bucket")
                fieldRow(Copy.syncRegionLabel, text: $region, id: "sync-region")
            }
            fieldRow(Copy.syncPrefixLabel, text: $prefix, id: "sync-prefix")
            if backend == .s3 {
                FormRow(label: { dialogLabel(Copy.syncPathStyleLabel) }) {
                    Toggle("", isOn: $pathStyle)
                        .tint(.brand)
                        .labelsHidden()
                        .accessibilityLabel(Copy.syncPathStyleLabel)
                }
            }
            fieldRow(backend == .s3 ? Copy.syncAccessKeyLabel : Copy.syncUsernameLabel,
                     text: $username, id: "sync-username")
            FormRow(label: { dialogLabel(backend == .s3 ? Copy.syncSecretKeyLabel : Copy.syncPasswordLabel) }) {
                SecureField(backend == .s3 ? Copy.syncSecretKeyLabel : Copy.syncPasswordLabel, text: $secret)
                    .textContentType(.password)
                    .font(Ds.body())
                    .modifier(DSInputStyleUnfocused())
                    #if os(iOS)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    #endif
                    .accessibilityIdentifier("sync-secret")
            }
        }
    }

    /// 设置表单的标签（表单行内右对齐）。
    private func dialogLabel(_ text: String) -> some View {
        Text(text)
            .font(Ds.body())
            .foregroundStyle(Color.ink)
            .frame(width: labelWidth, alignment: Ds.isDesktop ? .trailing : .leading)
    }

    private func fieldRow(_ label: String, text: Binding<String>, id: String) -> some View {
        FormRow(label: { dialogLabel(label) }) {
            TextField(label, text: text)
                #if os(iOS)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                #endif
                .font(Ds.body())
                .modifier(DSInputStyleUnfocused())
                .accessibilityIdentifier(id)
                .frame(maxWidth: .infinity)
        }
    }

    // MARK: 已配置

    private func configured(_ status: SyncStatus) -> some View {
        VStack(alignment: .leading, spacing: 24) {
            // 开关与状态
            SettingsGroup(title: Copy.settingsSyncHeader) {
                SettingsCard {
                    VStack(spacing: 0) {
                        HStack(alignment: .center, spacing: 12) {
                            VStack(alignment: .leading, spacing: 4) {
                                Text(Copy.syncEnable)
                                    .font(Ds.body())
                                    .foregroundStyle(Color.ink)
                                TimelineView(.periodic(from: .now, by: 30)) { context in
                                    Text(store.isSyncing ? Copy.syncInProgress : status.lastSyncedAt.map {
                                        Copy.syncLastSynced(Fmt.syncTime($0, now: context.date))
                                    } ?? Copy.syncNeverSynced)
                                        .font(Ds.aux())
                                        .monospacedDigit()
                                        .foregroundStyle(Color.text2)
                                        .accessibilityIdentifier("sync-status")
                                }
                            }
                            Spacer(minLength: 12)
                            Toggle("", isOn: Binding(get: { status.enabled }, set: { value in
                                perform { try await store.setSyncEnabled(value) }
                            }))
                            .tint(.brand)
                            .labelsHidden()
                            .disabled(saving || store.isSyncing || !status.recoveryCodeExported)
                            .accessibilityLabel(Copy.syncEnable)
                            .accessibilityIdentifier("sync-enable")
                        }
                        .padding(.horizontal, 16)
                        .frame(minHeight: Ds.doubleRowHeight)
                        Rectangle().fill(Color.sep).frame(height: 1)
                        // 存储行：说明为存储类型与地址，按钮「修改」
                        HStack(alignment: .center, spacing: 12) {
                            VStack(alignment: .leading, spacing: 4) {
                                Text(Copy.syncStorageLabel)
                                    .font(Ds.body())
                                    .foregroundStyle(Color.ink)
                                Text(storageDescription(status.config))
                                    .font(Ds.aux())
                                    .foregroundStyle(Color.text2)
                                    .fixedSize(horizontal: false, vertical: true)
                            }
                            Spacer(minLength: 12)
                            Button(Copy.syncEditConfig) { withAnimation { editingStorage.toggle() } }
                                .buttonStyle(SecondaryButtonStyle())
                                .accessibilityIdentifier("sync-edit")
                        }
                        .padding(.horizontal, 16)
                        .frame(minHeight: Ds.doubleRowHeight)
                        if editingStorage {
                            Rectangle().fill(Color.sep).frame(height: 1)
                            configForm.padding(16)
                            if store.syncStatus?.config != nil {
                                FormHelpLine(message: Copy.syncSecretReenterHelp)
                                    .padding(.horizontal, 16)
                            }
                            HStack {
                                Spacer(minLength: 0)
                                Button(Copy.syncSaveConfig) { save() }
                                    .buttonStyle(PrimaryButtonStyle())
                                    .disabled(saving || store.isSyncing)
                                    .accessibilityIdentifier("sync-save")
                            }
                            .padding(.horizontal, 16)
                            .padding(.bottom, 16)
                        }
                        Rectangle().fill(Color.sep).frame(height: 1)
                        HStack(alignment: .center, spacing: 12) {
                            Button {
                                Task { await store.syncNow() }
                            } label: {
                                HStack(spacing: 6) {
                                    AppIcon(name: "ic-refresh", size: 16)
                                    Text(Copy.syncNow)
                                }
                            }
                            .buttonStyle(SecondaryButtonStyle())
                            .disabled(!status.enabled || store.isSyncing || saving)
                            .accessibilityIdentifier("sync-now")
                            if store.isSyncing {
                                AppIcon(name: "ic-refresh", size: 16)
                                    .foregroundStyle(Color.brand)
                            }
                            Spacer(minLength: 0)
                        }
                        .padding(.horizontal, 16)
                        .frame(minHeight: Ds.rowHeight)
                    }
                }
                if let message = store.syncError {
                    FormErrorLine(message: message)
                }
            }

            // 同步策略
            SettingsGroup(title: Copy.syncPolicyHeader) {
                SettingsCard {
                    VStack(spacing: 0) {
                        policyToggle(Copy.syncOnLaunchLabel, value: status.policy.onLaunch) { value in
                            var policy = status.policy
                            policy.onLaunch = value
                            perform { try await store.setSyncPolicy(policy) }
                        }
                        Rectangle().fill(Color.sep).frame(height: 1)
                        HStack(alignment: .center, spacing: 12) {
                            Text(Copy.syncIntervalLabel).font(Ds.body()).foregroundStyle(Color.ink)
                            Spacer(minLength: 12)
                            Picker(Copy.syncIntervalLabel, selection: Binding(get: { status.policy.intervalMinutes }, set: { value in
                                var policy = status.policy
                                policy.intervalMinutes = value
                                perform { try await store.setSyncPolicy(policy) }
                            })) {
                                Text(Copy.syncIntervalOff).tag(UInt32(0))
                                Text(Copy.syncInterval15m).tag(UInt32(15))
                                Text(Copy.syncInterval30m).tag(UInt32(30))
                                Text(Copy.syncInterval1h).tag(UInt32(60))
                            }
                            .pickerStyle(.menu)
                            .tint(.brand)
                            .disabled(saving || store.isSyncing)
                        }
                        .padding(.horizontal, 16)
                        .frame(minHeight: Ds.rowHeight)
                        Rectangle().fill(Color.sep).frame(height: 1)
                        policyToggle(Copy.syncAfterEditLabel, value: status.policy.afterEdit) { value in
                            var policy = status.policy
                            policy.afterEdit = value
                            perform { try await store.setSyncPolicy(policy) }
                        }
                    }
                }
                FormHelpLine(message: Copy.syncIntervalHelp).padding(.leading, 4)
            }

            HStack(spacing: 8) {
                Button(Copy.syncClearConfig) {
                    if let onClearConfig { onClearConfig() }
                }
                .buttonStyle(TextButtonStyle(danger: true))
                .disabled(store.isSyncing || saving)
                .accessibilityIdentifier("sync-clear")
                Spacer(minLength: 0)
            }
        }
    }

    private func storageDescription(_ config: SyncConfig?) -> String {
        guard let config else { return "" }
        let backendName = config.backend == .s3 ? Copy.syncBackendS3 : Copy.syncBackendWebDAV
        return "\(backendName) · \(config.endpoint)"
    }

    private func policyToggle(_ title: String, value: Bool, onChange: @escaping (Bool) -> Void) -> some View {
        HStack(alignment: .center, spacing: 12) {
            Text(title).font(Ds.body()).foregroundStyle(Color.ink)
            Spacer(minLength: 12)
            Toggle("", isOn: Binding(get: { value }, set: { onChange($0) }))
                .tint(.brand)
                .labelsHidden()
                .disabled(saving || store.isSyncing)
                .accessibilityLabel(title)
        }
        .padding(.horizontal, 16)
        .frame(minHeight: Ds.rowHeight)
    }

    // MARK: 提交

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
        endpoint = config.endpoint
        bucket = config.bucket
        region = config.region
        prefix = config.prefix
        username = config.username
        pathStyle = config.pathStyle
    }
}

/// 无焦点态的设置表单输入框（聚焦环由系统焦点样式提供）。
struct DSInputStyleUnfocused: ViewModifier {
    func body(content: Content) -> some View {
        content
            .padding(.horizontal, 10)
            .frame(minHeight: Ds.fieldHeight)
            .background(Color.cardBg, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 10, style: .continuous)
                    .strokeBorder(Color.sep, lineWidth: 1)
            )
    }
}

// MARK: - 页面右上角同步按钮（§5.9）

struct SyncToolbarButton: View {
    @Environment(SnowFoldStore.self) private var store
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var rotation: Double = 0

    var body: some View {
        if store.showsSyncButton {
            Button {
                Task { await store.syncNow() }
            } label: {
                AppIcon(name: "ic-refresh", size: 18)
                    .foregroundStyle(foreground)
                    .overlay(alignment: .topTrailing) {
                        if store.syncError != nil {
                            Circle()
                                .fill(Color.neg)
                                .frame(width: 8, height: 8)
                                .overlay(Circle().strokeBorder(Color.cardBg, lineWidth: 2))
                                .offset(x: 4, y: -4)
                        }
                    }
                    .rotationEffect(.degrees(rotation))
            }
            #if os(macOS)
            .help(store.syncStatus?.lastSyncedAt.map { Copy.syncButtonTooltip(Fmt.syncTime($0)) } ?? Copy.syncNeverSynced)
            #endif
            .disabled(store.isSyncing)
            .accessibilityLabel(store.isSyncing ? Copy.syncInProgress
                                : store.syncError == nil ? Copy.syncButtonA11y : Copy.syncButtonFailedA11y)
            .accessibilityIdentifier("sync-toolbar")
            .onAppear { startRotation() }
            .onChange(of: store.isSyncing) { _, syncing in
                if !syncing { withAnimation(.none) { rotation = 0 } }
                startRotation()
            }
            .onChange(of: reduceMotion) { _, _ in startRotation() }
        }
    }

    private var foreground: Color {
        store.isSyncing ? Color.brand : Color.ink
    }

    /// 匀速 1 秒/圈；减少动态效果时不转（只换 brand 色）。
    private func startRotation() {
        guard store.isSyncing, !reduceMotion else { return }
        withAnimation(.linear(duration: 1).repeatForever(autoreverses: false)) {
            rotation = 360
        }
    }
}
