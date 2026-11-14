import XCTest
@testable import SnowFold
import Security

/// 真实钥匙串往返与系统失败分支，两端共用；不触碰 sync-key 条目。
final class KeychainKeyStoreTests: XCTestCase {
    private var account: String!
    private var store: KeychainKeyStore!

    override func setUpWithError() throws {
        account = "sync-key-test-\(UUID().uuidString)"
        store = KeychainKeyStore(account: account)
    }

    override func tearDownWithError() throws {
        try store.delete()
        store = nil
        account = nil
    }

    private var itemQuery: [String: Any] {
        var query = store.query(dataProtection: true)
        #if os(macOS)
        var probe = query
        probe[kSecReturnData as String] = true
        var result: CFTypeRef?
        if SecItemCopyMatching(probe as CFDictionary, &result) == errSecMissingEntitlement {
            query = store.query(dataProtection: false)
        }
        #endif
        return query
    }

    func testStoreLoadRoundtripAndDeviceOnlyAttributes() throws {
        let key = Data(0..<32)
        try store.store(key)
        XCTAssertEqual(try store.load(), key)

        var query = itemQuery
        query[kSecReturnAttributes as String] = true
        var result: CFTypeRef?
        XCTAssertEqual(SecItemCopyMatching(query as CFDictionary, &result), errSecSuccess)
        let attributes = try XCTUnwrap(result as? [String: Any])
        if query[kSecUseDataProtectionKeychain as String] as? Bool != false {
            XCTAssertEqual(attributes[kSecAttrAccessible as String] as? String,
                           kSecAttrAccessibleWhenUnlockedThisDeviceOnly as String)
            XCTAssertEqual(attributes[kSecAttrSynchronizable as String] as? Bool, false)
        } else {
            XCTAssertNotEqual(attributes[kSecAttrSynchronizable as String] as? Bool, true)
        }
    }

    func testOverwriteKeepsOneItemAndLatestKey() throws {
        try store.store(Data(repeating: 1, count: 32))
        let replacement = Data(repeating: 2, count: 32)
        try store.store(replacement)
        XCTAssertEqual(try store.load(), replacement)

        var query = itemQuery
        query[kSecReturnAttributes as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitAll
        var result: CFTypeRef?
        XCTAssertEqual(SecItemCopyMatching(query as CFDictionary, &result), errSecSuccess)
        let items = try XCTUnwrap(result as? [[String: Any]])
        XCTAssertEqual(items.count, 1)
        if query[kSecUseDataProtectionKeychain as String] as? Bool != false {
            XCTAssertEqual(items[0][kSecAttrAccessible as String] as? String,
                           kSecAttrAccessibleWhenUnlockedThisDeviceOnly as String)
        }
    }

    func testDeleteThenLoadReturnsNilAndDeleteIsIdempotent() throws {
        try store.store(Data(repeating: 3, count: 32))
        try store.delete()
        XCTAssertNil(try store.load())
        try store.delete()
    }

    func testNeverStoredReturnsNil() throws {
        XCTAssertNil(try store.load())
    }

    func testCorruptStoredDataThrowsInsteadOfReturningNil() throws {
        for count in [0, 1, 31, 33] {
            try store.delete()
            var item = itemQuery
            item[kSecValueData as String] = Data(repeating: 4, count: count)
            if item[kSecUseDataProtectionKeychain as String] as? Bool != false {
                item[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
            }
            XCTAssertEqual(SecItemAdd(item as CFDictionary, nil), errSecSuccess)
            XCTAssertThrowsError(try store.load()) {
                XCTAssertEqual($0 as? KeychainKeyStore.Failure, .invalidData)
            }
        }
    }

    func testLoadSystemErrorsArePreserved() {
        for status in [errSecInteractionNotAllowed, errSecDecode, errSecAuthFailed, errSecNotAvailable] {
            let failing = KeychainKeyStore(operations: .init(copyMatching: { _, _ in status }))
            XCTAssertThrowsError(try failing.load()) {
                XCTAssertEqual($0 as? KeychainKeyStore.Failure, .system(status))
            }
        }
    }

    func testSuccessfulLoadWithoutDataThrows() {
        let failing = KeychainKeyStore(operations: .init(copyMatching: { _, _ in errSecSuccess }))
        XCTAssertThrowsError(try failing.load()) {
            XCTAssertEqual($0 as? KeychainKeyStore.Failure, .invalidData)
        }
    }

    func testSuccessfulLoadWithWrongTypeThrows() {
        let failing = KeychainKeyStore(operations: .init(copyMatching: { _, result in
            result?.pointee = "损坏的系统返回值" as CFString
            return errSecSuccess
        }))
        XCTAssertThrowsError(try failing.load()) {
            XCTAssertEqual($0 as? KeychainKeyStore.Failure, .invalidData)
        }
    }

    func testDefaultIdentityAndProtectionOnAllOperations() throws {
        let checkIdentity: @Sendable (CFDictionary) -> Void = { value in
            let query = value as NSDictionary
            XCTAssertEqual(query[kSecClass] as? String, kSecClassGenericPassword as String)
            XCTAssertEqual(query[kSecAttrService] as? String, "app.snowfold")
            XCTAssertEqual(query[kSecAttrAccount] as? String, "sync-key")
            XCTAssertEqual(query[kSecAttrSynchronizable] as? Bool, false)
            #if os(macOS)
            XCTAssertEqual(query[kSecUseDataProtectionKeychain] as? Bool, true)
            XCTAssertNotNil(query[kSecAttrAccessGroup], "主路径必须先由系统验证应用访问组")
            #endif
            XCTAssertNil(query[kSecAttrAccessControl])
        }
        let isolated = KeychainKeyStore(operations: .init(
            copyMatching: { query, _ in
                checkIdentity(query)
                return errSecItemNotFound
            },
            add: { query in
                checkIdentity(query)
                XCTAssertEqual((query as NSDictionary)[kSecAttrAccessible] as? String,
                               kSecAttrAccessibleWhenUnlockedThisDeviceOnly as String)
                return errSecSuccess
            },
            update: { query, attributes in
                checkIdentity(query)
                XCTAssertEqual((attributes as NSDictionary)[kSecAttrAccessible] as? String,
                               kSecAttrAccessibleWhenUnlockedThisDeviceOnly as String)
                return errSecItemNotFound
            },
            delete: { query in
                checkIdentity(query)
                return errSecSuccess
            }
        ))
        XCTAssertNil(try isolated.load())
        try isolated.store(Data(repeating: 5, count: 32))
        try isolated.delete()
    }

    func testUpdateErrorThrowsWithoutAddingOrDeleting() {
        let failing = KeychainKeyStore(operations: .init(
            add: { _ in XCTFail("更新报错不得改成新增"); return errSecSuccess },
            update: { _, _ in errSecInteractionNotAllowed },
            delete: { _ in XCTFail("更新报错不得删除旧密钥"); return errSecSuccess }
        ))
        XCTAssertThrowsError(try failing.store(Data(repeating: 6, count: 32))) {
            XCTAssertEqual($0 as? KeychainKeyStore.Failure, .system(errSecInteractionNotAllowed))
        }
    }

    func testAddErrorThrows() {
        let failing = KeychainKeyStore(operations: .init(
            add: { _ in errSecNotAvailable },
            update: { _, _ in errSecItemNotFound }
        ))
        XCTAssertThrowsError(try failing.store(Data(repeating: 7, count: 32))) {
            XCTAssertEqual($0 as? KeychainKeyStore.Failure, .system(errSecNotAvailable))
        }
    }

    func testDeleteErrorThrows() {
        let failing = KeychainKeyStore(operations: .init(delete: { _ in errSecInteractionNotAllowed }))
        XCTAssertThrowsError(try failing.delete()) {
            XCTAssertEqual($0 as? KeychainKeyStore.Failure, .system(errSecInteractionNotAllowed))
        }
    }

    #if os(macOS)
    func testMacMissingEntitlementFallsBackForLoad() throws {
        let key = Data(repeating: 9, count: 32)
        let isolated = KeychainKeyStore(operations: .init(copyMatching: { query, result in
            if (query as NSDictionary)[kSecUseDataProtectionKeychain] as? Bool == true {
                return errSecMissingEntitlement
            }
            XCTAssertEqual((query as NSDictionary)[kSecAttrSynchronizable] as? Bool, false)
            result?.pointee = key as CFData
            return errSecSuccess
        }))
        XCTAssertEqual(try isolated.load(), key)
    }

    func testMacMissingEntitlementDuringUpdateFallsBackForStore() throws {
        let isolated = KeychainKeyStore(operations: .init(
            add: { query in
                XCTAssertEqual((query as NSDictionary)[kSecUseDataProtectionKeychain] as? Bool, false)
                XCTAssertEqual((query as NSDictionary)[kSecAttrSynchronizable] as? Bool, false)
                XCTAssertNil((query as NSDictionary)[kSecAttrAccessible])
                return errSecSuccess
            },
            update: { query, attributes in
                if (query as NSDictionary)[kSecUseDataProtectionKeychain] as? Bool == true {
                    return errSecMissingEntitlement
                }
                XCTAssertNil((attributes as NSDictionary)[kSecAttrAccessible])
                return errSecItemNotFound
            }
        ))
        try isolated.store(Data(repeating: 10, count: 32))
    }

    func testMacMissingEntitlementDuringAddFallsBackForStore() throws {
        let isolated = KeychainKeyStore(operations: .init(
            add: { query in
                if (query as NSDictionary)[kSecUseDataProtectionKeychain] as? Bool == true {
                    return errSecMissingEntitlement
                }
                XCTAssertEqual((query as NSDictionary)[kSecAttrSynchronizable] as? Bool, false)
                XCTAssertNil((query as NSDictionary)[kSecAttrAccessible])
                return errSecSuccess
            },
            update: { _, _ in errSecItemNotFound }
        ))
        try isolated.store(Data(repeating: 11, count: 32))
    }

    func testMacMissingEntitlementFallsBackForDelete() throws {
        let isolated = KeychainKeyStore(operations: .init(delete: { query in
            if (query as NSDictionary)[kSecUseDataProtectionKeychain] as? Bool == true {
                return errSecMissingEntitlement
            }
            XCTAssertEqual((query as NSDictionary)[kSecAttrSynchronizable] as? Bool, false)
            return errSecSuccess
        }))
        try isolated.delete()
    }

    func testMacFallbackErrorsArePreserved() {
        for status in [errSecInteractionNotAllowed, errSecDecode, errSecAuthFailed,
                       errSecNotAvailable, errSecMissingEntitlement] {
            let failing = KeychainKeyStore(operations: .init(
                copyMatching: { query, _ in
                    (query as NSDictionary)[kSecUseDataProtectionKeychain] as? Bool == true
                        ? errSecMissingEntitlement : status
                },
                add: { query in
                    (query as NSDictionary)[kSecUseDataProtectionKeychain] as? Bool == true
                        ? errSecMissingEntitlement : status
                },
                update: { query, _ in
                    (query as NSDictionary)[kSecUseDataProtectionKeychain] as? Bool == true
                        ? errSecMissingEntitlement : errSecItemNotFound
                },
                delete: { query in
                    (query as NSDictionary)[kSecUseDataProtectionKeychain] as? Bool == true
                        ? errSecMissingEntitlement : status
                }
            ))
            XCTAssertThrowsError(try failing.load()) {
                XCTAssertEqual($0 as? KeychainKeyStore.Failure, .system(status))
            }
            XCTAssertThrowsError(try failing.store(Data(repeating: 12, count: 32))) {
                XCTAssertEqual($0 as? KeychainKeyStore.Failure, .system(status))
            }
            XCTAssertThrowsError(try failing.delete()) {
                XCTAssertEqual($0 as? KeychainKeyStore.Failure, .system(status))
            }
        }
    }

    func testMacOtherErrorsNeverUseLegacyKeychain() {
        for status in [errSecInteractionNotAllowed, errSecDecode, errSecAuthFailed, errSecNotAvailable] {
            let checkPrimary: @Sendable (CFDictionary) -> Void = { query in
                XCTAssertEqual((query as NSDictionary)[kSecUseDataProtectionKeychain] as? Bool, true,
                               "只有缺 entitlement 才允许回落")
            }
            let failing = KeychainKeyStore(operations: .init(
                copyMatching: { query, _ in checkPrimary(query); return status },
                add: { _ in XCTFail("更新系统错误不得新增"); return errSecSuccess },
                update: { query, _ in checkPrimary(query); return status },
                delete: { query in checkPrimary(query); return status }
            ))
            XCTAssertThrowsError(try failing.load()) {
                XCTAssertEqual($0 as? KeychainKeyStore.Failure, .system(status))
            }
            XCTAssertThrowsError(try failing.store(Data(repeating: 13, count: 32))) {
                XCTAssertEqual($0 as? KeychainKeyStore.Failure, .system(status))
            }
            XCTAssertThrowsError(try failing.delete()) {
                XCTAssertEqual($0 as? KeychainKeyStore.Failure, .system(status))
            }
        }
    }

    func testMacStoredItemUsesAvailableSystemKeychain() throws {
        try store.store(Data(repeating: 14, count: 32))
        var query = itemQuery
        if query[kSecUseDataProtectionKeychain as String] as? Bool == true {
            // 正式签名可用时，主路径应直接读回。
            XCTAssertEqual(try store.load(), Data(repeating: 14, count: 32))
            return
        }
        query[kSecReturnRef as String] = true
        var result: CFTypeRef?
        XCTAssertEqual(SecItemCopyMatching(query as CFDictionary, &result), errSecSuccess)
        let reference = try XCTUnwrap(result)
        XCTAssertEqual(CFGetTypeID(reference), SecKeychainItemGetTypeID())
        let item = reference as! SecKeychainItem
        var keychain: SecKeychain?
        XCTAssertEqual(SecKeychainItemCopyKeychain(item, &keychain), errSecSuccess)
        var path = [CChar](repeating: 0, count: 1024)
        var length = UInt32(path.count)
        XCTAssertEqual(SecKeychainGetPath(try XCTUnwrap(keychain), &length, &path), errSecSuccess)
        let keychainName = URL(fileURLWithPath: String(cString: path)).lastPathComponent
        XCTAssertEqual(keychainName, "login.keychain-db", "回落密钥必须进入登录钥匙串")
    }
    #else
    func testIOSMissingEntitlementIsThrown() {
        let failing = KeychainKeyStore(operations: .init(
            copyMatching: { _, _ in errSecMissingEntitlement },
            update: { _, _ in errSecMissingEntitlement },
            delete: { _ in errSecMissingEntitlement }
        ))
        XCTAssertThrowsError(try failing.load()) {
            XCTAssertEqual($0 as? KeychainKeyStore.Failure, .system(errSecMissingEntitlement))
        }
        XCTAssertThrowsError(try failing.store(Data(repeating: 15, count: 32))) {
            XCTAssertEqual($0 as? KeychainKeyStore.Failure, .system(errSecMissingEntitlement))
        }
        XCTAssertThrowsError(try failing.delete()) {
            XCTAssertEqual($0 as? KeychainKeyStore.Failure, .system(errSecMissingEntitlement))
        }
    }
    #endif

    func testInvalidStorePreservesExistingKey() throws {
        let key = Data(repeating: 8, count: 32)
        try store.store(key)
        for count in [0, 31, 33] {
            XCTAssertThrowsError(try store.store(Data(repeating: 9, count: count))) {
                XCTAssertEqual($0 as? KeychainKeyStore.Failure, .invalidData)
            }
            XCTAssertEqual(try store.load(), key)
        }
    }
}
