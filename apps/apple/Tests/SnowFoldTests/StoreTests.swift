import XCTest
@testable import SnowFold
import SQLite3

/// 模型层测试：列表刷新、撤销、错误映射、跨实例过期编辑、备份导入导出。
@MainActor
final class StoreTests: XCTestCase {
    private var directory: URL!

    override func setUp() async throws {
        directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("snowfold-tests-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    }

    override func tearDown() async throws {
        try KeychainKeyStore(account: directory.lastPathComponent).delete()
        try? FileManager.default.removeItem(at: directory)
    }

    private func makeStore() -> SnowFoldStore {
        SnowFoldStore(dataDirectory: directory, keyStore: KeychainKeyStore(account: directory.lastPathComponent))
    }

    // MARK: 打开与刷新

    func testOpenEmptyShowsReadyWithZeroOverview() async throws {
        let store = makeStore()
        await store.open()
        XCTAssertEqual(store.state, .ready)
        XCTAssertTrue(store.ledger.assets.isEmpty)
        XCTAssertTrue(store.ledger.subscriptions.isEmpty)
        XCTAssertEqual(store.overview?.netAssetsCents, 0)
        XCTAssertEqual(store.groups.count, 0)
    }

    func testOpenIncompatibleDatabaseFailsWithMappedMessage() async throws {
        let dbPath = directory.appendingPathComponent("snowfold.sqlite3").path
        var handle: OpaquePointer?
        XCTAssertEqual(sqlite3_open(dbPath, &handle), SQLITE_OK)
        sqlite3_exec(handle, "PRAGMA user_version=9;", nil, nil, nil)
        sqlite3_close(handle)
        let store = makeStore()
        await store.open()
        guard case let .failed(message) = store.state else {
            return XCTFail("应进入 failed 状态，实际 \(store.state)")
        }
        XCTAssertTrue(message.contains("数据库版本不兼容"), message)
        XCTAssertNil(store.core)
    }

    // MARK: 资产增删改查与刷新

    func testCreateAssetRefreshesLedgerOverviewAndHighlight() async throws {
        let store = makeStore()
        await store.open()
        let id = try await store.createAsset(
            categoryCode: "cash",
            name: "招商银行朝朝宝（活期理财）家庭备用金账户",
            amountText: "12345678.90"
        )
        XCTAssertEqual(store.ledger.assets.count, 1)
        XCTAssertEqual(store.ledger.assets[0].amountCents, 1_234_567_890)
        XCTAssertEqual(store.overview?.totalAssetsCents, 1_234_567_890)
        XCTAssertEqual(store.highlightID, id)
    }

    func testInvalidAmountAndBlankNameMapToValidationCopy() async throws {
        let store = makeStore()
        await store.open()
        do {
            _ = try await store.createAsset(categoryCode: "cash", name: "测试", amountText: "12.345")
            XCTFail("应抛出 Validation")
        } catch {
            XCTAssertEqual(Copy.message(for: error), "请检查名称、金额、日期与选项")
        }
        do {
            _ = try await store.createAsset(categoryCode: "cash", name: "   ", amountText: "10")
            XCTFail("空白名称应抛出 Validation")
        } catch {
            XCTAssertEqual(Copy.message(for: error), "请检查名称、金额、日期与选项")
        }
        XCTAssertTrue(store.ledger.assets.isEmpty, "失败不写入")
        XCTAssertNil(store.notice, "编辑类失败由调用方就地展示")
    }

    func testChangeBalanceOffersUndoBannerAndUndoRestores() async throws {
        let store = makeStore()
        await store.open()
        let id = try await store.createAsset(categoryCode: "cash", name: "微信钱包", amountText: "100.00")
        try await store.changeBalance(id: id, amountText: "250.50")
        XCTAssertEqual(store.ledger.assets[0].amountCents, 25_050)
        XCTAssertEqual(store.ledger.history.count, 2, "变动应追加快照")
        let banner = try XCTUnwrap(store.undoState)
        XCTAssertEqual(banner.message, Copy.balanceRecorded)
        await store.undoNow()
        XCTAssertEqual(store.ledger.assets[0].amountCents, 10_000)
        XCTAssertEqual(store.ledger.history.count, 1)
        XCTAssertNil(store.undoState, "撤销成功后横幅撤下")
    }

    func testNewTicketReplacesOldAndOldTokenConflicts() async throws {
        let store = makeStore()
        await store.open()
        let id = try await store.createAsset(categoryCode: "fixed", name: "代步小车", amountText: "100")
        try await store.changeBalance(id: id, amountText: "200")
        let firstToken = try XCTUnwrap(store.undoState?.token)
        try await store.changeBalance(id: id, amountText: "300")
        let secondToken = try XCTUnwrap(store.undoState?.token)
        XCTAssertNotEqual(firstToken, secondToken, "新票据替换旧票据")
        do {
            try await store.write { try $0.undo(token: firstToken) }
            XCTFail("旧令牌应返回 Conflict")
        } catch {
            XCTAssertEqual(Copy.message(for: error), "数据已变化或正在使用，请刷新后重试")
        }
        XCTAssertEqual(store.ledger.assets[0].amountCents, 30_000, "失败的撤销不改变数据")
    }

    func testDeleteAssetUndoRestoresSameIdentity() async throws {
        let store = makeStore()
        await store.open()
        let id = try await store.createAsset(categoryCode: "receivable", name: "借给同事小王（装修周转）", amountText: "20000")
        await store.deleteAsset(id: id)
        XCTAssertTrue(store.ledger.assets.isEmpty)
        XCTAssertEqual(store.undoState?.message, Copy.assetDeleted)
        await store.undoNow()
        XCTAssertEqual(store.ledger.assets.first?.id, id, "撤销后 UUID 原样恢复")
        XCTAssertEqual(store.ledger.assets.first?.amountCents, 2_000_000)
    }

    /// 编辑 sheet 打开期间同一记录在别处被删除：保存返回 NotFound 并映射文案。
    func testStaleEditAfterExternalDeleteMapsNotFound() async throws {
        let store = makeStore()
        await store.open()
        let id = try await store.createAsset(categoryCode: "cash", name: "支付宝余额", amountText: "50")
        // 另一个实例删除同一目录里的记录（模拟桌面端同时打开）。
        let external = try SnowFoldCore.open(dataDir: directory.path, keyStore: KeychainKeyStore(account: directory.lastPathComponent))
        try external.deleteAsset(id: id)
        await store.refreshIfReady()
        XCTAssertTrue(store.ledger.assets.isEmpty, "后台回前台刷新应看到外部删除")
        do {
            try await store.renameAsset(id: id, name: "改名")
            XCTFail("应抛出 NotFound")
        } catch {
            XCTAssertEqual(Copy.message(for: error), "记录不存在，请刷新后重试")
        }
    }

    // MARK: 订阅

    func testSubscriptionGroupsFollowCoreSemantics() async throws {
        let store = makeStore()
        await store.open()
        let today = Fmt.today
        let yesterday = try XCTUnwrap(Fmt.date(fromDay: today)?.addingTimeInterval(-86_400))
        let nextMonth = try XCTUnwrap(Calendar.current.date(byAdding: .month, value: 1, to: Date()))
        let overdueID = try await store.createSubscription(SubscriptionInput(
            name: "iCloud 50GB", amountCents: 600, currency: "CNY", cycle: "monthly",
            nextChargeOn: Fmt.dayString(from: yesterday), status: "active"
        ))
        let nextMonthID = try await store.createSubscription(SubscriptionInput(
            name: "腾讯视频 VIP", amountCents: 2500, currency: "CNY", cycle: "quarterly",
            nextChargeOn: Fmt.dayString(from: nextMonth), status: "active"
        ))
        XCTAssertEqual(store.groups[overdueID], .overdue)
        XCTAssertEqual(store.groups[nextMonthID], .nextMonth)
        await store.setSubscriptionPaused(id: nextMonthID, paused: true)
        XCTAssertEqual(store.groups[nextMonthID], .paused)
    }

    func testRenewDueSubscriptionAdvancesOnePeriod() async throws {
        let store = makeStore()
        await store.open()
        let id = try await store.createSubscription(SubscriptionInput(
            name: "Netflix Premium", amountCents: 1599, currency: "USD", cycle: "monthly",
            nextChargeOn: Fmt.today, status: "active"
        ))
        await store.renewSubscription(id: id)
        XCTAssertEqual(store.notice, Copy.renewed(1))
        let record = try XCTUnwrap(store.ledger.subscriptions.first)
        XCTAssertGreaterThan(record.nextChargeOn, Fmt.today)
    }

    func testEditingSubscriptionUpdatesAllFields() async throws {
        let store = makeStore()
        await store.open()
        let id = try await store.createSubscription(SubscriptionInput(
            name: "健身房年卡", amountCents: 398_000, currency: "CNY", cycle: "yearly",
            nextChargeOn: "2027-01-15", status: "active"
        ))
        try await store.updateSubscription(id: id, SubscriptionInput(
            name: "健身房年卡（续费价）", amountCents: 368_000, currency: "CNY", cycle: "yearly",
            nextChargeOn: "2027-01-20", status: "paused"
        ))
        let record = store.ledger.subscriptions[0]
        XCTAssertEqual(record.name, "健身房年卡（续费价）")
        XCTAssertEqual(record.amountCents, 368_000)
        XCTAssertEqual(record.nextChargeOn, "2027-01-20")
        XCTAssertEqual(record.status, "paused")
        await store.deleteSubscription(id: id)
        XCTAssertEqual(store.undoState?.message, Copy.subscriptionDeleted)
        await store.undoNow()
        XCTAssertEqual(store.ledger.subscriptions.count, 1)
    }

    // MARK: 备份

    func testJSONExportImportRoundtripInvalidatesUndo() async throws {
        let store = makeStore()
        await store.open()
        _ = try await store.createAsset(categoryCode: "investment", name: "华泰证券 A 股账户", amountText: "156204.55")
        let json = try await store.exportJSON()
        let info = try await store.inspectJSON(json)
        XCTAssertEqual(info.assets, 1)
        XCTAssertEqual(info.history, 1)
        try await store.changeBalance(id: store.ledger.assets[0].id, amountText: "160000")
        try await store.clearAll()
        XCTAssertTrue(store.ledger.assets.isEmpty)
        XCTAssertNil(store.undoState, "清空后撤销令牌整体失效")
        try await store.importJSON(json)
        XCTAssertEqual(store.ledger.assets.count, 1)
        XCTAssertEqual(store.ledger.assets[0].name, "华泰证券 A 股账户")
    }

    func testImportInvalidJSONKeepsData() async throws {
        let store = makeStore()
        await store.open()
        _ = try await store.createAsset(categoryCode: "cash", name: "现金", amountText: "5")
        do {
            try await store.importJSON("{\"format_version\":99}")
            XCTFail("应拒绝未知版本")
        } catch {
            XCTAssertEqual(Copy.message(for: error), "不支持的备份版本或加密格式")
        }
        XCTAssertEqual(store.ledger.assets.count, 1, "导入失败原数据不变")
    }

    // MARK: 主题与密钥

    func testThemeRoundtripAcrossReopen() async throws {
        let store = makeStore()
        await store.open()
        await store.saveTheme(.dark)
        XCTAssertEqual(store.theme, .dark)
        let reopened = makeStore()
        await reopened.open()
        XCTAssertEqual(reopened.theme, .dark)
    }

    func testKeyFingerprintAndSealedRoundtrip() async throws {
        let store = makeStore()
        await store.open()
        XCTAssertNil(store.keyFingerprint, "密钥未生成前指纹为空")
        _ = try await store.createAsset(categoryCode: "cash", name: "零钱", amountText: "1.00")
        // 首次导出加密备份会自动生成密钥。
        _ = try await store.exportSealed()
        let fingerprint = try XCTUnwrap(store.keyFingerprint, "导出后应生成密钥指纹")
        let sealed = try await store.exportSealed()
        let info = try await store.inspectSealed(sealed)
        XCTAssertEqual(info.counts.assets, 1)
        try await store.clearAll()
        try await store.importSealed(sealed)
        XCTAssertEqual(store.ledger.assets.count, 1)
        let code = try await store.exportRecoveryCode()
        let codeInfo = try await store.inspectRecoveryCode(code)
        XCTAssertEqual(codeInfo.newFingerprint, fingerprint)
    }

    func testWrongPasswordSealedBytesFailAuthentication() async throws {
        let store = makeStore()
        await store.open()
        _ = try await store.exportSealed()
        var sealed = try await store.exportSealed()
        sealed[20] ^= 0xFF // 破坏密文
        do {
            _ = try await store.inspectSealed(sealed)
            XCTFail("篡改密文应认证失败")
        } catch {
            XCTAssertEqual(Copy.message(for: error), "无法解密：同步密钥不匹配或文件已损坏")
        }
    }

    // MARK: 显示格式

    func testMoneyAndDayFormattingMatchDesktop() {
        XCTAssertEqual(Fmt.money(1_234_567_890, "CNY"), "¥12,345,678.90")
        XCTAssertEqual(Fmt.money(-1234, "CNY"), "−¥12.34")
        XCTAssertEqual(Fmt.money(1599, "USD"), "$15.99")
        XCTAssertEqual(Fmt.decimal(1_234_567_890), "12345678.90")
        XCTAssertEqual(Fmt.heroSegments(1_234_567_890, "CNY").integer, "12,345,678")
        XCTAssertEqual(Fmt.heroSegments(-1234, "CNY").sign, "−")
    }

    func testDayAndTimestampFormatting() {
        XCTAssertEqual(Fmt.day("2026-11-14", relativeTo: "2026-11-14"), "11月14日")
        XCTAssertEqual(Fmt.day("2025-11-14", relativeTo: "2026-11-14"), "2025年11月14日")
        XCTAssertEqual(Fmt.chargeBadge(for: "2026-11-13", today: "2026-11-14").text, "逾期 1 天")
        XCTAssertEqual(Fmt.chargeBadge(for: "2026-11-14", today: "2026-11-14").text, "今天")
        XCTAssertEqual(Fmt.overdueCompact("2026-11-11", today: "2026-11-14"), "逾期3天")
        XCTAssertTrue(Fmt.timestamp("2026-11-14T05:00:00.123456789Z").contains("月"), "时间戳应转本地可读格式")
    }

    // MARK: 必测场景补充

    /// 快速连点保存只写一次：第二个并发写因 isWorking 被拒，账本只有一条。
    func testConcurrentSaveWritesOnce() async throws {
        let store = makeStore()
        await store.open()
        async let first: String = store.createAsset(categoryCode: "cash", name: "并发一", amountText: "1.00")
        async let second: String = store.createAsset(categoryCode: "cash", name: "并发二", amountText: "2.00")
        var rejectedDuplicate = false
        do {
            _ = try await (first, second)
        } catch CoreError.Conflict {
            rejectedDuplicate = true
            _ = try? await first
            _ = try? await second
        } catch {
            XCTFail("重复提交应返回 Conflict，实际为 \(error)")
        }
        XCTAssertTrue(rejectedDuplicate, "重叠的第二次保存必须被拒绝")
        XCTAssertEqual(store.ledger.assets.count, 1, "并发保存只能写入一条")
    }

    /// 相同金额的余额变动：核心返回 nil 票据，不出现撤销横幅。
    func testChangeBalanceSameAmountShowsNoUndo() async throws {
        let store = makeStore()
        await store.open()
        let id = try await store.createAsset(categoryCode: "cash", name: "不动", amountText: "10.00")
        try await store.changeBalance(id: id, amountText: "10.00")
        XCTAssertNil(store.undoState, "无变动不应出撤销票据")
        XCTAssertEqual(store.ledger.history.count, 1)
    }

    /// 暂停订阅续费：核心返回 Conflict，映射文案并提示，不改数据。
    func testRenewPausedSubscriptionConflicts() async throws {
        let store = makeStore()
        await store.open()
        let id = try await store.createSubscription(SubscriptionInput(
            name: "已暂停的服务", amountCents: 1_200, currency: "CNY", cycle: "monthly",
            nextChargeOn: Fmt.today, status: "paused"
        ))
        await store.renewSubscription(id: id)
        XCTAssertEqual(store.notice, Copy.message(for: CoreError.Conflict))
        let record = try XCTUnwrap(store.ledger.subscriptions.first)
        XCTAssertEqual(record.nextChargeOn, Fmt.today, "续费失败不顺延")
    }

    /// CoreError → 中文文案映射全表核对（docs/copy.md FFI 错误节）。
    func testCoreErrorMappingCoversAllVariants() {
        let expected: [CoreError: String] = [
            .Validation: "请检查名称、金额、日期与选项",
            .NotFound: "记录不存在，请刷新后重试",
            .Conflict: "数据已变化或正在使用，请刷新后重试",
            .Authentication: "无法解密：同步密钥不匹配或文件已损坏",
            .IncompatibleBackup: "不支持的备份版本或加密格式",
            .IncompatibleDatabase: "数据库版本不兼容，原文件未改动",
            .InvalidBackup: "备份内容不完整或不合法，原数据未改变",
            .InvalidRecoveryCode: "恢复码不合法，本机密钥未改变",
            .KeyUnavailable: "同步密钥不可用，请检查密钥文件或导入恢复码",
            .Io: "无法读写文件，请检查目录与权限",
            .Database: "无法读写本机数据库",
            .Overflow: "金额合计超出整数范围，请检查数据",
            .Internal: "核心状态不可用，请重新打开",
        ]
        for (error, message) in expected {
            XCTAssertEqual(Copy.message(for: error), message, "\(error) 映射不符")
        }
        XCTAssertEqual(expected.count, 13, "错误变体应全覆盖")
        XCTAssertEqual(Copy.message(for: NSError(domain: "test", code: 1)), Copy.message(for: .Internal), "非核心错误按 Internal 兜底")
    }

    /// 前台刷新必须看到同一目录的外部核心实例写入。
    func testRefreshIfReadyPicksUpExternalData() async throws {
        let store = makeStore()
        await store.open()
        let external = try SnowFoldCore.open(dataDir: directory.path, keyStore: KeychainKeyStore(account: directory.lastPathComponent))
        let id = try external.createAsset(categoryCode: "cash", assetName: "外部写入的资产", amountCents: 1234567890)
        XCTAssertTrue(store.ledger.assets.isEmpty)
        await store.refreshIfReady()
        XCTAssertEqual(store.ledger.assets.first?.id, id)
        XCTAssertEqual(store.overview?.totalAssetsCents, 1234567890)
    }

    func testNextChargeExcludesOverdueAndPaused() async throws {
        let store = makeStore()
        await store.open()
        for (name, date, status) in [("逾期", "2000-01-01", "active"), ("暂停", Fmt.today, "paused"),
                                     ("今天", Fmt.today, "active"), ("将来", "2099-01-01", "active")] {
            _ = try await store.createSubscription(SubscriptionInput(name: name, amountCents: 100,
                currency: "CNY", cycle: "monthly", nextChargeOn: date, status: status))
        }
        XCTAssertEqual(store.nextScheduledSubscription?.name, "今天")
    }

    func testDismissingUndoDoesNotRestoreDeletedData() async throws {
        let store = makeStore()
        await store.open()
        let id = try await store.createAsset(categoryCode: "cash", name: "关闭撤销提示", amountText: "12345678.90")
        await store.deleteAsset(id: id)
        XCTAssertNotNil(store.undoState)
        store.invalidateUndo()
        XCTAssertNil(store.undoState)
        XCTAssertTrue(store.ledger.assets.isEmpty)
        await store.refreshIfReady()
        XCTAssertTrue(store.ledger.assets.isEmpty, "关闭横幅只关闭提示，不执行撤销")
    }

}
