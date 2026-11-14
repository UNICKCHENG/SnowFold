import XCTest
@testable import SnowFold
import Security

/// 新内核 + 真实系统存储，覆盖事实板 §6；备份字节直接由核心生成。
@MainActor
final class SecureKeyMigrationTests: XCTestCase {
    private var directory: URL!
    private var keyStore: KeychainKeyStore!
    private let key = Data(0..<32)
    private var hex: String { key.map { String(format: "%02x", $0) }.joined() }

    override func setUp() async throws {
        directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("snowfold-migration-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        keyStore = KeychainKeyStore(account: directory.lastPathComponent)
    }

    override func tearDown() async throws {
        try keyStore.delete()
        try FileManager.default.removeItem(at: directory)
    }

    private var legacyURL: URL { directory.appendingPathComponent("snowfold-sync.key") }
    private var legacyBytes: Data { Data("snowfold-sync-key-v1\n\(hex)\n".utf8) }

    private func writeLegacy() throws {
        XCTAssertTrue(FileManager.default.createFile(atPath: legacyURL.path, contents: legacyBytes,
                                                     attributes: [.posixPermissions: 0o600]))
        XCTAssertEqual(try FileManager.default.attributesOfItem(atPath: legacyURL.path)[.posixPermissions] as? Int, 0o600)
    }

    private func open(_ storage: SecureKeyStore? = nil) throws -> SnowFoldCore {
        try SnowFoldCore.open(dataDir: directory.path, keyStore: storage ?? keyStore)
    }

    /// 按协调者批准的方法：预置 K 导出真实密文文件，再清空 store、重建旧文件。
    private func prepareBeforeMigration() throws -> (Data, String) {
        try writeLegacy()
        try keyStore.store(key)
        let before = try open()
        _ = try before.createAsset(categoryCode: "cash", assetName: "迁移前的真实备份", amountCents: 12345)
        let sealed = try before.exportSealed()
        let file = directory.appendingPathComponent("before-migration.bfseal")
        try sealed.write(to: file)
        let attachment = XCTAttachment(contentsOfFile: file)
        attachment.name = "before-migration.bfseal"
        attachment.lifetime = .keepAlways
        add(attachment)
        let recovery = try before.exportRecoveryCode()
        try keyStore.delete()
        try writeLegacy()
        return (try Data(contentsOf: file), recovery)
    }

    func testOldSealedFileImportsAfterMigration() throws {
        let (sealed, _) = try prepareBeforeMigration()
        let migrated = try open()
        _ = try migrated.keyFingerprint()
        XCTAssertFalse(FileManager.default.fileExists(atPath: legacyURL.path))
        try migrated.clearData()
        XCTAssertTrue(try migrated.data().assets.isEmpty)
        let imported = try migrated.importSealed(bytes: sealed)
        XCTAssertEqual(imported.counts.assets, 1)
        XCTAssertEqual(try migrated.data().assets.first?.name, "迁移前的真实备份")
        XCTAssertEqual(try keyStore.load(), key)
    }

    func testRecoveryCodeIsUnchangedCharacterForCharacter() throws {
        let (_, before) = try prepareBeforeMigration()
        let after = try open().exportRecoveryCode()
        XCTAssertEqual(after, before)
        XCTAssertEqual(after.split(separator: "\n").last.map(String.init), hex)
    }

    func testLegacyFileDisappearsOnlyAfterSuccessfulMigration() throws {
        try writeLegacy()
        let migrated = try open()
        XCTAssertNotNil(try migrated.keyFingerprint())
        XCTAssertEqual(try keyStore.load(), key)
        XCTAssertFalse(FileManager.default.fileExists(atPath: legacyURL.path))
    }

    func testTwoStartsKeepTheSameKey() async throws {
        try writeLegacy()
        let first = SnowFoldStore(dataDirectory: directory, keyStore: keyStore)
        await first.open()
        XCTAssertEqual(first.state, .ready)
        XCTAssertNil(first.notice)
        let before = try await first.exportRecoveryCode()
        let second = SnowFoldStore(dataDirectory: directory, keyStore: keyStore)
        await second.open()
        XCTAssertEqual(second.state, .ready)
        XCTAssertNil(second.notice)
        let after = try await second.exportRecoveryCode()
        XCTAssertEqual(after, before)
        XCTAssertEqual(try keyStore.load(), key)
        XCTAssertFalse(FileManager.default.fileExists(atPath: legacyURL.path))
    }

    func testStoreFailurePreservesLegacyFileAndKey() throws {
        try writeLegacy()
        let failing = KeychainKeyStore(account: directory.lastPathComponent, operations: .init(
            update: { _, _ in errSecInteractionNotAllowed }
        ))
        XCTAssertThrowsError(try open(failing).exportSealed()) {
            XCTAssertEqual($0 as? CoreError, .KeyUnavailable)
        }
        XCTAssertEqual(try Data(contentsOf: legacyURL), legacyBytes)
        XCTAssertNil(try keyStore.load())
        let recovered = try open().exportRecoveryCode()
        XCTAssertEqual(recovered.split(separator: "\n").last.map(String.init), hex)
        XCTAssertEqual(try keyStore.load(), key)
    }

    func testLoadFailureNeverGeneratesKeyAndShowsFrozenMessage() async throws {
        let failing = KeychainKeyStore(account: directory.lastPathComponent, operations: .init(
            copyMatching: { _, _ in errSecInteractionNotAllowed },
            add: { _ in XCTFail("load 报错不得生成密钥"); return errSecSuccess },
            update: { _, _ in XCTFail("load 报错不得写入密钥"); return errSecSuccess }
        ))
        XCTAssertThrowsError(try open(failing).exportSealed()) {
            XCTAssertEqual($0 as? CoreError, .KeyUnavailable)
            XCTAssertEqual(Copy.userMessage(for: $0), "本机密钥已失效，请用恢复码恢复")
        }
        let store = SnowFoldStore(dataDirectory: directory, keyStore: failing)
        await store.open()
        XCTAssertEqual(store.state, .ready)
        XCTAssertEqual(store.notice, "本机密钥已失效，请用恢复码恢复")
        XCTAssertNil(try keyStore.load())
        XCTAssertFalse(FileManager.default.fileExists(atPath: legacyURL.path))
    }

    func testRecoveryImportWritesOnlySystemStore() throws {
        let core = try open()
        _ = try core.importRecoveryCode(contents: String(decoding: legacyBytes, as: UTF8.self),
                                        expectedCurrentFingerprint: nil)
        XCTAssertEqual(try keyStore.load(), key)
        XCTAssertFalse(FileManager.default.fileExists(atPath: legacyURL.path))
        XCTAssertFalse(FileManager.default.fileExists(atPath: directory.appendingPathComponent("snowfold-sync.key.bak").path))
    }
}
