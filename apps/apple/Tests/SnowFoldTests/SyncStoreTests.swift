import XCTest
@testable import SnowFold

/// 带强 ETag 与条件写的内存服务，不解析密文，不接触真实网络。
final class MemorySyncTransport: SyncTransport, @unchecked Sendable {
    private let lock = NSLock()
    private var object: Data?
    private var version = 0
    private var requestCount = 0
    private var putCount = 0
    private var conflictOnce = false
    private var failure: UInt16?
    private var blockPut = false
    let putStarted = DispatchSemaphore(value: 0)
    let releasePut = DispatchSemaphore(value: 0)

    func waitForPausedPut() -> Bool { putStarted.wait(timeout: .now() + 3) == .success }

    var requests: Int { lock.withLock { requestCount } }
    var puts: Int { lock.withLock { putCount } }
    func injectConflict() { lock.withLock { conflictOnce = true } }
    func reject(_ status: UInt16?) { lock.withLock { failure = status } }
    func pauseNextPut() { lock.withLock { blockPut = true } }

    func send(request: SyncRequest) throws -> SyncResponse {
        let shouldBlock = lock.withLock {
            requestCount += 1
            if request.method == "PUT", blockPut { blockPut = false; return true }
            return false
        }
        if shouldBlock {
            putStarted.signal()
            guard releasePut.wait(timeout: .now() + 8) == .success else { throw CoreError.SyncNetwork }
        }
        return lock.withLock {
            func response(_ status: UInt16, body: Data = Data()) -> SyncResponse {
                SyncResponse(status: status, headers: object == nil ? [] : [SyncHeader(name: "ETag", value: "\"v\(version)\"")], body: body)
            }
            if let failure { return response(failure) }
            if request.method == "MKCOL" { return response(201) }
            if request.method == "GET" { return object.map { response(200, body: $0) } ?? response(404) }
            if request.method == "PUT" {
                putCount += 1
                if conflictOnce { conflictOnce = false; return response(412) }
                let match = request.headers.first { $0.name.lowercased() == "if-match" }?.value
                let none = request.headers.first { $0.name.lowercased() == "if-none-match" }?.value
                guard object == nil ? none == "*" : match == "\"v\(version)\"" else { return response(412) }
                object = request.body
                version += 1
                return response(200)
            }
            return response(405)
        }
    }
}

actor SyncTestClock {
    private var sleepers: [UUID: (TimeInterval, CheckedContinuation<Void, Error>)] = [:]
    func sleep(_ duration: TimeInterval) async throws {
        let id = UUID()
        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
                if Task.isCancelled { continuation.resume(throwing: CancellationError()) }
                else { sleepers[id] = (duration, continuation) }
            }
        } onCancel: {
            Task { await self.cancel(id) }
        }
    }
    private func cancel(_ id: UUID) { sleepers.removeValue(forKey: id)?.1.resume(throwing: CancellationError()) }
    func count() -> Int { sleepers.count }
    func advance() {
        let ready = sleepers.values
        sleepers.removeAll()
        for (_, continuation) in ready { continuation.resume() }
    }
}

@MainActor
final class SyncStoreTests: XCTestCase {
    private var directories: [URL] = []
    private var stores: [SnowFoldStore] = []
    private let scene = UUID()

    override func tearDown() async throws {
        for store in stores { await store.setSceneActive(scene, active: false) }
        stores.removeAll()
        for directory in directories {
            try KeychainKeyStore(account: directory.lastPathComponent).delete()
            try? FileManager.default.removeItem(at: directory)
        }
    }

    private func store(_ transport: MemorySyncTransport = MemorySyncTransport(), clock: SyncTestClock? = nil) async -> SnowFoldStore {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("snowfold-m7-test-\(UUID().uuidString)")
        directories.append(directory)
        let store = SnowFoldStore(dataDirectory: directory, keyStore: KeychainKeyStore(account: directory.lastPathComponent),
                                  syncTransport: transport, syncSleep: { seconds in
            if let clock { try await clock.sleep(seconds) }
            else { try await Task.sleep(for: .seconds(seconds)) }
        })
        stores.append(store)
        await store.open()
        return store
    }

    private func config(_ backend: SyncBackend) -> SyncConfig {
        SyncConfig(backend: backend, endpoint: "https://sync.example.com", bucket: backend == .s3 ? "snowfold" : "",
                   region: backend == .s3 ? "us-east-1" : "", prefix: "", username: "test-account", pathStyle: backend == .s3)
    }

    private func enable(_ store: SnowFoldStore, backend: SyncBackend = .s3) async throws {
        _ = try await store.exportRecoveryCode()
        try await store.configureSync(config(backend), secret: "fixture-credential")
        try await store.setSyncPolicy(SyncPolicy(onLaunch: false, intervalMinutes: 0, afterEdit: false))
        try await store.setSyncEnabled(true)
        store.dismissNotice()
    }

    private func eventually(_ condition: @escaping @MainActor () -> Bool, file: StaticString = #filePath, line: UInt = #line) async {
        let deadline = Date().addingTimeInterval(3)
        while !condition() && Date() < deadline { try? await Task.sleep(for: .milliseconds(10)) }
        XCTAssertTrue(condition(), file: file, line: line)
    }

    func testDisabledAndUnconfiguredMakeZeroRequestsAndHideButton() async throws {
        let remote = MemorySyncTransport()
        let model = await store(remote)
        await model.setSceneActive(scene, active: true)
        await model.syncNow()
        _ = try await model.createAsset(categoryCode: "cash", name: "离线", amountText: "1")
        XCTAssertFalse(model.showsSyncButton)
        XCTAssertEqual(remote.requests, 0)
        try await enable(model)
        try await model.setSyncEnabled(false)
        await model.syncNow()
        XCTAssertEqual(remote.requests, 0)
        XCTAssertFalse(model.showsSyncButton)
    }

    func testRecoveryGateConfigurationPolicyAndClearPersist() async throws {
        let model = await store()
        try await model.configureSync(config(.webDav), secret: "fixture-credential")
        do { try await model.setSyncEnabled(true); XCTFail("未导出恢复码不能开启") }
        catch { XCTAssertEqual(error as? CoreError, .RecoveryCodeRequired) }
        _ = try await model.exportRecoveryCode()
        XCTAssertEqual(model.syncStatus?.recoveryCodeExported, true)
        try await model.setSyncEnabled(true)
        try await model.setSyncPolicy(SyncPolicy(onLaunch: false, intervalMinutes: 60, afterEdit: false))
        let reopened = SnowFoldStore(dataDirectory: model.dataDirectory,
                                    keyStore: KeychainKeyStore(account: model.dataDirectory.lastPathComponent))
        await reopened.open()
        XCTAssertEqual(reopened.syncStatus?.policy.intervalMinutes, 60)
        XCTAssertEqual(reopened.syncStatus?.enabled, true)
        try await model.clearSyncConfig()
        XCTAssertNil(model.syncStatus?.config)
        XCTAssertFalse(model.showsSyncButton)
        XCTAssertEqual(model.syncStatus?.policy.intervalMinutes, 30)
    }

    func testS3AndWebDAVRoundtripETagConflictRefreshAndUndo() async throws {
        for backend in [SyncBackend.s3, .webDav] {
            let remote = MemorySyncTransport()
            let first = await store(remote)
            try await enable(first, backend: backend)
            let id = try await first.createAsset(categoryCode: "cash", name: "跨端资产", amountText: "10")
            try await first.changeBalance(id: id, amountText: "20")
            let initialHistory = try XCTUnwrap(first.ledger.history.first)
            try await first.correctHistory(historyID: initialHistory.id, amountText: "15")
            let subscription = try await first.createSubscription(SubscriptionInput(
                name: "同步订阅", amountCents: 600, currency: "CNY", cycle: "monthly",
                nextChargeOn: Fmt.today, status: "active"))
            XCTAssertNotNil(first.undoState)
            let recovery = try await first.exportRecoveryCode()
            remote.injectConflict()
            await first.syncNow()
            XCTAssertNil(first.syncError)
            XCTAssertNil(first.undoState)
            XCTAssertEqual(remote.puts, 2, "412 由核心重试条件写")
            XCTAssertNotNil(first.syncStatus?.lastSyncedAt)
            let second = await store(remote)
            try await second.importRecoveryCode(recovery, expectedCurrentFingerprint: nil)
            try await enable(second, backend: backend)
            await second.syncNow()
            XCTAssertEqual(second.ledger.assets.first?.id, id)
            XCTAssertEqual(second.overview?.netAssetsCents, 2000)
            XCTAssertEqual(second.ledger.history.count, 2)
            let corrections = try await second.historyCorrections(historyID: initialHistory.id)
            XCTAssertEqual(corrections.count, 1)
            XCTAssertEqual(second.ledger.subscriptions.first?.id, subscription)
            XCTAssertNotNil(second.groups[subscription])
            XCTAssertEqual(second.annuals[subscription], 7200)
            XCTAssertEqual(second.syncGeneration, 1)
        }
    }

    func testSingleFlightWithTimerAndLocalEditDuringPut() async throws {
        let remote = MemorySyncTransport()
        let clock = SyncTestClock()
        let model = await store(remote, clock: clock)
        try await enable(model)
        try await model.setSyncPolicy(SyncPolicy(onLaunch: false, intervalMinutes: 15, afterEdit: true))
        await model.setSceneActive(scene, active: true)
        let id = try await model.createAsset(categoryCode: "cash", name: "网络期间编辑", amountText: "1")
        remote.pauseNextPut()
        let sync = Task { await model.syncNow() }
        let started = await Task.detached { remote.waitForPausedPut() }.value
        XCTAssertTrue(started)
        XCTAssertTrue(model.isSyncing)
        await model.syncNow()
        await model.syncNow(trigger: .interval)
        try await model.changeBalance(id: id, amountText: "2")
        await clock.advance()
        XCTAssertTrue(model.isSyncing)
        XCTAssertEqual(remote.requests, 2, "已有 GET 与暂停中的 PUT，其他触发不得发请求")
        remote.releasePut.signal()
        await sync.value
        XCTAssertEqual(model.ledger.assets.first?.amountCents, 200)
        XCTAssertNil(model.undoState)
        let before = remote.requests
        await eventually { !model.isSyncing }
        await Task.yield()
        await clock.advance()
        await eventually { remote.requests > before && !model.isSyncing }
        XCTAssertNil(model.syncError)
    }

    func testForegroundTriggerAndBackgroundStopsTimers() async throws {
        let remote = MemorySyncTransport()
        let clock = SyncTestClock()
        let model = await store(remote, clock: clock)
        try await enable(model)
        try await model.setSyncPolicy(SyncPolicy(onLaunch: true, intervalMinutes: 15, afterEdit: true))
        await model.setSceneActive(scene, active: true)
        XCTAssertGreaterThan(remote.requests, 0)
        let before = remote.requests
        await model.setSceneActive(scene, active: false)
        _ = try await model.createAsset(categoryCode: "cash", name: "后台编辑", amountText: "1")
        await clock.advance()
        XCTAssertEqual(remote.requests, before)
        await model.setSceneActive(scene, active: true)
        XCTAssertGreaterThan(remote.requests, before)
        XCTAssertNil(model.notice, "自动同步不弹成功通知")
        let reopened = SnowFoldStore(dataDirectory: model.dataDirectory,
                                    keyStore: KeychainKeyStore(account: model.dataDirectory.lastPathComponent),
                                    syncTransport: remote)
        stores.append(reopened)
        let beforeReopen = remote.requests
        await reopened.setSceneActive(scene, active: true)
        await reopened.open()
        await reopened.setSceneActive(scene, active: true)
        XCTAssertGreaterThan(remote.requests, beforeReopen, "场景先激活也不能漏掉启动同步")
    }

    func testAutomaticFailureOnlySetsStatusAndManualFailureNotifies() async throws {
        let remote = MemorySyncTransport()
        let model = await store(remote)
        try await enable(model)
        await model.setSceneActive(scene, active: true)
        remote.reject(403)
        await model.syncNow(trigger: .interval)
        XCTAssertEqual(model.syncError, Copy.message(for: .SyncAuthentication))
        XCTAssertNil(model.notice)
        await model.syncNow()
        XCTAssertEqual(model.notice, Copy.message(for: .SyncAuthentication))
        remote.reject(nil)
        await model.syncNow()
        XCTAssertNil(model.syncError)
    }

    func testSyncCopyAndRelativeTime() {
        XCTAssertEqual(Copy.syncMessage(for: CoreError.Validation, configuration: true), Copy.syncConfigInvalid)
        XCTAssertEqual(Copy.syncMessage(for: CoreError.Conflict), Copy.syncBusy)
        let now = Fmt.parseRFC3339("2026-11-14T12:00:00.000000000Z")!
        XCTAssertEqual(Fmt.syncTime("2026-11-14T11:59:30.000000000Z", now: now), "刚刚")
        XCTAssertEqual(Fmt.syncTime("2026-11-14T11:55:00.000000000Z", now: now), "5 分钟前")
    }
}
