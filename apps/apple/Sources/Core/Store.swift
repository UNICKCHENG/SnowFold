import Foundation
import Observation

/// 页面路由：iPhone 切换 TabView，Mac 切换侧边栏选择。
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
    /// 统一弹层入口：iOS 呈现为 sheet，macOS 呈现为居中弹层（见 Views/RootView.swift）。
    var modal: Modal?
    /// Mac 设置窗口当前页签（Cmd+, 独立窗口）。
    var settingsTab: MacSettingsTab = .general
}

/// 可撤销横幅：令牌来自核心，validForMs 内有效。
struct UndoState: Equatable {
    let token: String
    let message: String
    let deadline: Date
}

/// 共享模型层：唯一的核心调用入口。
/// 写操作在后台任务同步调用核心（核心内部 Mutex 串行），成功后统一刷新 data + overview。
@MainActor
@Observable
final class SnowFoldStore {
    enum LoadState: Equatable {
        case opening
        case ready
        case failed(String)
    }

    // MARK: 状态

    private(set) var state: LoadState = .opening
    private(set) var core: SnowFoldCore?
    private(set) var ledger = LedgerData(assets: [], history: [], subscriptions: [])
    private(set) var overview: Overview?
    private(set) var overviewError: String?
    /// 订阅 id → 核心分组（待确认/本月/下月/之后/已暂停）。
    private(set) var groups: [String: SubscriptionGroup] = [:]
    /// 订阅 id → 年化金额（核心 subscription_annual，供条形图）。
    private(set) var annuals: [String: Int64] = [:]
    private(set) var theme: ThemePreference = .system
    private(set) var keyFingerprint: String?
    /// 同步密钥不可用：总览顶部 warn 卡片（§9.7），导入恢复码后解除。
    private(set) var keyUnavailable = false
    private(set) var undoState: UndoState?
    private(set) var notice: String?
    /// 通知条按错误样式呈现（check / warning-triangle）。
    private(set) var noticeIsError = false
    private(set) var isWorking = false
    /// 新建记录后短暂高亮。
    private(set) var highlightID: String?

    private(set) var syncStatus: SyncStatus?
    private(set) var isSyncing = false
    private(set) var syncError: String?
    private(set) var syncGeneration = 0
    private let syncTransport: any SyncTransport
    private let syncSleep: @Sendable (TimeInterval) async throws -> Void
    private var activeScenes: Set<UUID> = []
    private var intervalTask: Task<Void, Never>?
    private var editSyncTask: Task<Void, Never>?
    private var intervalAnchor = Date.distantPast
    private var pendingLocalChanges = false
    private var isChangingSyncConfig = false

    let dataDirectory: URL
    private let keyStore: SecureKeyStore
    private var undoExpiryTask: Task<Void, Never>?
    private var highlightTask: Task<Void, Never>?
    /// 成功通知 4 秒自动消失（design.md §5.8）；错误通知手动关闭。
    private var noticeTask: Task<Void, Never>?

    init(dataDirectory: URL? = nil, keyStore: SecureKeyStore = KeychainKeyStore(),
         syncTransport: any SyncTransport = URLSessionSyncTransport(),
         syncSleep: @escaping @Sendable (TimeInterval) async throws -> Void = { try await Task.sleep(for: .seconds($0)) }) {
        self.syncTransport = syncTransport
        self.syncSleep = syncSleep
        self.dataDirectory = dataDirectory ?? Self.defaultDirectory()
        self.keyStore = keyStore
    }

    static func defaultDirectory() -> URL {
        #if os(macOS)
        if let custom = ProcessInfo.processInfo.environment["SNOWFOLD_DATA_DIR"], !custom.isEmpty {
            return URL(fileURLWithPath: custom, isDirectory: true)
        }
        #endif
        let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory
        return support.appendingPathComponent("SnowFold", isDirectory: true)
    }

    // MARK: 打开与刷新

    func open() async {
        guard core == nil else { return }
        do {
            let core = try SnowFoldCore.open(dataDir: dataDirectory.path, keyStore: keyStore)
            self.core = core
            state = .ready
            await refresh()
            await reloadSyncStatus()
            theme = (try? await background { try core.theme() }) ?? .system
            do {
                keyFingerprint = try await background { try core.keyFingerprint() }
                keyUnavailable = false
            } catch {
                // 只置位警示卡（总览页承载），不再重复弹通知。
                keyUnavailable = true
            }
            do {
                try await background { try core.startupBackup() }
            } catch {
                if notice == nil {
                    showErrorNotice(Copy.startupBackupFailed(Copy.userMessage(for: error)))
                }
            }
        } catch {
            state = .failed(Copy.localDataUnreadable(Copy.userMessage(for: error)))
        }
    }

    /// 后台回前台或数据可能被外部改动时调用。
    func refreshIfReady() async {
        guard core != nil, state == .ready else { return }
        await refresh()
    }

    func refresh() async {
        guard let core else { return }
        let on = Fmt.today
        do {
            let (data, groups, annuals) = try await background { () -> (LedgerData, [String: SubscriptionGroup], [String: Int64]) in
                let data = try core.data()
                var groups = [String: SubscriptionGroup]()
                var annuals = [String: Int64]()
                groups.reserveCapacity(data.subscriptions.count)
                for subscription in data.subscriptions {
                    groups[subscription.id] = (try? core.subscriptionGroup(id: subscription.id, on: on)) ?? .later
                    annuals[subscription.id] = (try? core.subscriptionAnnual(id: subscription.id)) ?? 0
                }
                return (data, groups, annuals)
            }
            ledger = data
            self.groups = groups
            self.annuals = annuals
        } catch {
            showErrorNotice(Copy.localDataUnreadable(Copy.userMessage(for: error)))
            return
        }
        do {
            overview = try await background { try core.overview(on: on) }
            overviewError = nil
        } catch {
            overview = nil
            overviewError = Copy.userMessage(for: error)
        }
    }

    /// 展示下一次将要扣费的服务；只筛选排序，不进行金额或扣费日计算。
    var nextScheduledSubscription: SubscriptionRecord? {
        ledger.subscriptions
            .filter { $0.status == "active" && $0.nextChargeOn >= Fmt.today }
            .min { ($0.nextChargeOn, $0.id) < ($1.nextChargeOn, $1.id) }
    }

    // MARK: 底层调用

    private func background<T: Sendable>(_ work: @escaping @Sendable () throws -> T) async throws -> T {
        try await Task.detached(priority: .userInitiated, operation: work).value
    }

    /// 一次写操作：后台调用核心，成功后刷新读模型（刷新失败不影响已写入的数据）。
    @discardableResult
    func write<T: Sendable>(_ work: @escaping @Sendable (SnowFoldCore) throws -> T) async throws -> T {
        guard let core else { throw CoreError.Internal }
        guard !isWorking else { throw CoreError.Conflict }
        isWorking = true
        defer { isWorking = false }
        let value = try await background { try work(core) }
        do {
            try await refreshOrThrow()
        } catch {
            showErrorNotice("\(Copy.savedButReloadFailed)：\(Copy.userMessage(for: error))")
        }
        scheduleAfterEdit()
        return value
    }

    private func refreshOrThrow() async throws {
        guard let core else { throw CoreError.Internal }
        let on = Fmt.today
        let (data, groups, annuals) = try await background { () -> (LedgerData, [String: SubscriptionGroup], [String: Int64]) in
            let data = try core.data()
            var groups = [String: SubscriptionGroup]()
            var annuals = [String: Int64]()
            for subscription in data.subscriptions {
                groups[subscription.id] = (try? core.subscriptionGroup(id: subscription.id, on: on)) ?? .later
                annuals[subscription.id] = (try? core.subscriptionAnnual(id: subscription.id)) ?? 0
            }
            return (data, groups, annuals)
        }
        ledger = data
        self.groups = groups
        self.annuals = annuals
        do {
            overview = try await background { try core.overview(on: on) }
            overviewError = nil
        } catch {
            overview = nil
            overviewError = Copy.userMessage(for: error)
        }
    }

    // MARK: 撤销

    func offerUndo(_ ticket: UndoTicket, _ message: String) {
        let deadline = Date().addingTimeInterval(Double(ticket.validForMs) / 1000.0)
        undoState = UndoState(token: ticket.token, message: message, deadline: deadline)
        undoExpiryTask?.cancel()
        undoExpiryTask = Task { [weak self] in
            let interval = deadline.timeIntervalSinceNow + 0.05
            if interval > 0 {
                try? await Task.sleep(for: .seconds(interval))
            }
            guard !Task.isCancelled else { return }
            self?.expireUndo()
        }
    }

    private func expireUndo() {
        guard let state = undoState, state.deadline <= Date() else { return }
        undoState = nil
    }

    /// 横幅上的撤销按钮：过期或对象已变化时提示并撤下横幅。
    func undoNow() async {
        guard let state = undoState else { return }
        let token = state.token
        do {
            try await write { try $0.undo(token: token) }
            undoState = nil
        } catch {
            undoState = nil
            showErrorNotice(Copy.userMessage(for: error))
        }
    }

    /// 导入覆盖、清空等操作使撤销令牌整体失效。
    func invalidateUndo() {
        undoExpiryTask?.cancel()
        undoState = nil
    }

    func dismissNotice() {
        noticeTask?.cancel()
        noticeTask = nil
        notice = nil
    }

    /// 由编辑器等调用方设置全局通知（例如记录在别处被删除）。
    func showNotice(_ message: String, isError: Bool = false) {
        notice = message
        noticeIsError = isError
        noticeTask?.cancel()
        if !isError {
            let message = message
            noticeTask = Task { [weak self] in
                try? await Task.sleep(for: .seconds(4))
                guard !Task.isCancelled, let self, self.notice == message else { return }
                self.dismissNotice()
            }
        }
    }

    /// 错误通知：warning-triangle 样式，需手动关闭。
    func showErrorNotice(_ message: String) {
        showNotice(message, isError: true)
    }

    // MARK: 资产

    func parseCents(_ text: String) throws -> Int64 {
        try parseAmount(value: text)
    }

    @discardableResult
    func createAsset(categoryCode: String, name: String, amountText: String) async throws -> String {
        let cents = try parseCents(amountText)
        let id: String = try await write { try $0.createAsset(categoryCode: categoryCode, assetName: name, amountCents: cents) }
        flashHighlight(id)
        return id
    }

    func renameAsset(id: String, name: String) async throws {
        try await write { try $0.renameAsset(id: id, assetName: name) }
    }

    func changeBalance(id: String, amountText: String) async throws {
        let cents = try parseCents(amountText)
        if let ticket = try await write({ try $0.changeBalance(id: id, amountCents: cents) }) {
            offerUndo(ticket, Copy.balanceRecorded)
        }
    }

    func correctHistory(historyID: String, amountText: String) async throws {
        let cents = try parseCents(amountText)
        try await write { try $0.correctHistory(id: historyID, amountCents: cents) }
    }

    func deleteAsset(id: String) async {
        do {
            let ticket = try await write { try $0.deleteAsset(id: id) }
            offerUndo(ticket, Copy.assetDeleted)
        } catch {
            showErrorNotice(Copy.userMessage(for: error))
        }
    }

    func historyCorrections(historyID: String) async throws -> [HistoryCorrectionRecord] {
        guard let core else { throw CoreError.Internal }
        return try await background { try core.historyCorrections(id: historyID) }
    }

    // MARK: 订阅

    func createSubscription(_ input: SubscriptionInput) async throws -> String {
        let id: String = try await write { try $0.createSubscription(subscription: input) }
        flashHighlight(id)
        return id
    }

    func updateSubscription(id: String, _ input: SubscriptionInput) async throws {
        try await write { try $0.updateSubscription(id: id, subscription: input) }
    }

    func setSubscriptionPaused(id: String, paused: Bool) async {
        do {
            try await write { try $0.setSubscriptionPaused(id: id, isPaused: paused) }
        } catch {
            showErrorNotice(Copy.userMessage(for: error))
        }
    }

    /// 到期确认续费：按周期顺延。仅对 next_charge_on ≤ 今天的在订订阅展示入口。
    func renewSubscription(id: String) async {
        let on = Fmt.today
        do {
            let result = try await write { try $0.renewSubscription(id: id, on: on) }
            showNotice(Copy.renewed(result.periods))
        } catch {
            showErrorNotice(Copy.userMessage(for: error))
        }
    }

    func deleteSubscription(id: String) async {
        do {
            let ticket = try await write { try $0.deleteSubscription(id: id) }
            offerUndo(ticket, Copy.subscriptionDeleted)
        } catch {
            showErrorNotice(Copy.userMessage(for: error))
        }
    }

    // MARK: 主题

    func saveTheme(_ theme: ThemePreference) async {
        do {
            try await write { try $0.saveTheme(theme: theme) }
            self.theme = theme
        } catch {
            showErrorNotice("主题未保存：\(Copy.userMessage(for: error))")
        }
    }

    // MARK: 备份与密钥

    func exportJSON() async throws -> String {
        guard let core else { throw CoreError.Internal }
        return try await background { try core.exportJson() }
    }

    func inspectJSON(_ text: String) async throws -> BackupInfo {
        guard let core else { throw CoreError.Internal }
        return try await background { try core.inspectJson(json: text) }
    }

    /// 导入覆盖：单事务整库替换；撤销横幅随之失效。
    func importJSON(_ text: String) async throws {
        try await write { try $0.importJson(json: text) }
        invalidateUndo()
        showNotice(Copy.dataUpdated)
    }

    func exportSealed() async throws -> Data {
        guard let core else { throw CoreError.Internal }
        let data = try await background { try core.exportSealed() }
        keyFingerprint = (try? await background { try core.keyFingerprint() }) ?? keyFingerprint
        return data
    }

    func inspectSealed(_ data: Data) async throws -> SealedBackupInfo {
        guard let core else { throw CoreError.Internal }
        return try await background { try core.inspectSealed(bytes: data) }
    }

    func importSealed(_ data: Data) async throws {
        try await write { try $0.importSealed(bytes: data) }
        invalidateUndo()
        showNotice(Copy.dataUpdated)
        await reloadKeyFingerprint()
    }

    func exportRecoveryCode() async throws -> String {
        guard let core else { throw CoreError.Internal }
        let code = try await background { try core.exportRecoveryCode() }
        keyFingerprint = (try? await background { try core.keyFingerprint() }) ?? keyFingerprint
        await reloadSyncStatus()
        return code
    }

    func inspectRecoveryCode(_ contents: String) async throws -> RecoveryCodeInfo {
        guard let core else { throw CoreError.Internal }
        return try await background { try core.inspectRecoveryCode(contents: contents) }
    }

    /// 确认替换后调用；expectedCurrentFingerprint 保证对话框期间密钥未被他人改动。
    func importRecoveryCode(_ contents: String, expectedCurrentFingerprint: String?) async throws {
        guard let core else { throw CoreError.Internal }
        let result = try await background {
            try core.importRecoveryCode(contents: contents, expectedCurrentFingerprint: expectedCurrentFingerprint)
        }
        keyFingerprint = result.fingerprint
        await reloadSyncStatus()
        showNotice(result.previousKeyBackedUp ? Copy.keyReplaced(result.fingerprint) : Copy.keyImported(result.fingerprint))
    }

    /// 清空全部数据（确认后调用）。
    func clearAll() async {
        do {
            try await write { try $0.clearData() }
            invalidateUndo()
            showNotice(Copy.dataUpdated)
        } catch {
            showErrorNotice(Copy.userMessage(for: error))
        }
    }

    private func reloadKeyFingerprint() async {
        guard let core else { return }
        do {
            keyFingerprint = try await background { try core.keyFingerprint() }
            keyUnavailable = false
        } catch {
            keyUnavailable = true
        }
    }

    // MARK: 前台同步

    enum SyncTrigger { case manual, onLaunch, interval, afterEdit, pending }

    var showsSyncButton: Bool { syncStatus?.enabled == true }

    /// 多窗口共同计数，只有整个应用退到后台时停止调度。
    func setSceneActive(_ id: UUID, active: Bool) async {
        // 场景可先于数据库打开而激活，交给 open 完成后的调用再登记。
        guard !active || (core != nil && state == .ready) else { return }
        let wasActive = !activeScenes.isEmpty
        if active { activeScenes.insert(id) } else { activeScenes.remove(id) }
        let isActive = !activeScenes.isEmpty
        guard isActive != wasActive else { return }
        if !isActive {
            intervalTask?.cancel(); intervalTask = nil
            editSyncTask?.cancel(); editSyncTask = nil
            return
        }
        await refreshIfReady()
        await reloadSyncStatus()
        if syncStatus?.policy.onLaunch == true { await syncNow(trigger: .onLaunch) }
        if pendingLocalChanges { scheduleAfterEdit(pending: true) }
        scheduleInterval()
    }

    func reloadSyncStatus() async {
        guard let core else { return }
        do {
            syncStatus = try await background { try core.syncStatus() }
            if syncStatus?.enabled != true {
                intervalTask?.cancel(); intervalTask = nil
                editSyncTask?.cancel(); editSyncTask = nil
            }
        } catch {
            syncError = Copy.syncMessage(for: error)
        }
    }

    func configureSync(_ config: SyncConfig, secret: String) async throws {
        try await changeSyncConfiguration { try $0.configureSync(config: config, secret: secret) }
        syncError = nil
        pendingLocalChanges = false
        showNotice(Copy.syncConfigSaved)
    }

    func setSyncEnabled(_ enabled: Bool) async throws {
        try await changeSyncConfiguration { try $0.setSyncEnabled(enabled: enabled) }
        intervalAnchor = .distantPast
        showNotice(enabled ? Copy.syncEnabled : Copy.syncDisabled)
        scheduleInterval()
    }

    func setSyncPolicy(_ policy: SyncPolicy) async throws {
        try await changeSyncConfiguration { try $0.setSyncPolicy(policy: policy) }
        if !policy.afterEdit { editSyncTask?.cancel(); editSyncTask = nil }
        if pendingLocalChanges { scheduleAfterEdit(pending: true) }
        scheduleInterval()
    }

    func clearSyncConfig() async throws {
        try await changeSyncConfiguration { try $0.clearSyncConfig() }
        syncError = nil
        pendingLocalChanges = false
        showNotice(Copy.syncConfigCleared)
    }

    private func changeSyncConfiguration(_ work: @escaping @Sendable (SnowFoldCore) throws -> Void) async throws {
        guard let core else { throw CoreError.Internal }
        guard !isSyncing, !isChangingSyncConfig else { throw CoreError.Conflict }
        isChangingSyncConfig = true
        defer { isChangingSyncConfig = false }
        try await background { try work(core) }
        await reloadSyncStatus()
    }

    /// 与普通写操作分开单飞：等待网络时仍允许编辑本机记录。
    func syncNow(trigger: SyncTrigger = .manual) async {
        guard let core, syncStatus?.enabled == true, syncStatus?.config != nil,
              !isSyncing, !isChangingSyncConfig else { return }
        if trigger != .manual, activeScenes.isEmpty { return }
        isSyncing = true
        intervalTask?.cancel(); intervalTask = nil
        editSyncTask?.cancel(); editSyncTask = nil
        let transport = syncTransport
        defer {
            isSyncing = false
            scheduleInterval()
        }
        do {
            let report = try await background { try core.syncNow(transport: transport) }
            invalidateUndo()
            await refresh()
            syncGeneration += 1
            syncError = nil
            pendingLocalChanges = report.pendingLocalChanges
            await reloadSyncStatus()
            if trigger == .manual {
                showNotice(Copy.syncCompleted(report)
                    + (report.pendingLocalChanges ? " · " + Copy.syncPendingChanges : ""))
            }
            if report.pendingLocalChanges { scheduleAfterEdit(pending: true) }
        } catch {
            await reloadSyncStatus()
            if error as? CoreError != .Conflict {
                let message = Copy.syncMessage(for: error)
                syncError = message
                if trigger == .manual { showErrorNotice(message) }
            }
        }
        // 失败也等一个周期再重试，避免已到期的时间戳造成紧密重试。
        intervalAnchor = Date()
    }

    private func scheduleInterval() {
        intervalTask?.cancel(); intervalTask = nil
        guard !activeScenes.isEmpty, let status = syncStatus, status.enabled,
              status.policy.intervalMinutes > 0, !isSyncing else { return }
        let last = status.lastSyncedAt.flatMap(Fmt.parseRFC3339) ?? Date()
        let anchor = max(last, intervalAnchor)
        let delay = max(0.05, anchor.addingTimeInterval(Double(status.policy.intervalMinutes) * 60).timeIntervalSinceNow)
        let sleep = syncSleep
        intervalTask = Task { [weak self] in
            do { try await sleep(delay) } catch { return }
            guard !Task.isCancelled, let self else { return }
            self.intervalTask = nil
            await self.syncNow(trigger: .interval)
        }
    }

    private func scheduleAfterEdit(pending: Bool = false) {
        guard !activeScenes.isEmpty, syncStatus?.enabled == true,
              pending || syncStatus?.policy.afterEdit == true else { return }
        editSyncTask?.cancel()
        let sleep = syncSleep
        editSyncTask = Task { [weak self] in
            do { try await sleep(30) } catch { return }
            guard !Task.isCancelled, let self else { return }
            self.editSyncTask = nil
            await self.syncNow(trigger: pending ? .pending : .afterEdit)
        }
    }

    // MARK: 高亮

    private func flashHighlight(_ id: String) {
        highlightID = id
        highlightTask?.cancel()
        highlightTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(3))
            guard !Task.isCancelled else { return }
            if self?.highlightID == id {
                self?.highlightID = nil
            }
        }
    }
}
