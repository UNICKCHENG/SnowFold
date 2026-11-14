import Foundation
import Security

/// 只保管 32 字节同步密钥，不负责生成、迁移或恢复。
final class KeychainKeyStore: SecureKeyStore {
    enum Failure: Error, Equatable {
        case system(OSStatus)
        case invalidData
    }

    /// 同步系统调用边界，便于验证系统锁定、解密失败与异常返回值。
    struct Operations: Sendable {
        var copyMatching: @Sendable (CFDictionary, UnsafeMutablePointer<CFTypeRef?>?) -> OSStatus = {
            SecItemCopyMatching($0, $1)
        }
        var add: @Sendable (CFDictionary) -> OSStatus = { SecItemAdd($0, nil) }
        var update: @Sendable (CFDictionary, CFDictionary) -> OSStatus = { SecItemUpdate($0, $1) }
        var delete: @Sendable (CFDictionary) -> OSStatus = { SecItemDelete($0) }
    }

    private let account: String
    private let operations: Operations

    // 测试用独立 account，避免读写应用的真实密钥。
    init(account: String = "sync-key", operations: Operations = Operations()) {
        self.account = account
        self.operations = operations
    }

    func query(dataProtection: Bool) -> [String: Any] {
        var query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: "app.snowfold",
            kSecAttrAccount as String: account,
            kSecAttrSynchronizable as String: false,
        ]
        #if os(macOS)
        query[kSecUseDataProtectionKeychain as String] = dataProtection
        if dataProtection {
            let identifier = SecTaskCreateFromSelf(nil).flatMap {
                SecTaskCopyValueForEntitlement($0, "com.apple.application-identifier" as CFString, nil) as? String
            }
            // 显式访问组让系统先检查权限，避免无权限查询被当成“条目不存在”。
            query[kSecAttrAccessGroup as String] = identifier ?? "app.snowfold"
        }
        #endif
        return query
    }

    private func withKeychain<Result>(_ operation: (Bool) throws -> Result) throws -> Result {
        #if os(macOS)
        do {
            return try operation(true)
        } catch Failure.system(let status) where status == errSecMissingEntitlement {
            // Mac 临时签名没有有效 team，仅此错误回落传统钥匙串；该路径不强制 ThisDeviceOnly。
            // 不缓存后端，正式签名具备权限后自动优先使用数据保护钥匙串。
            return try operation(false)
        }
        #else
        return try operation(true)
        #endif
    }

    func load() throws -> Data? {
        try withKeychain { dataProtection in
            var query = query(dataProtection: dataProtection)
            query[kSecReturnData as String] = true
            query[kSecMatchLimit as String] = kSecMatchLimitOne
            var result: CFTypeRef?
            let status = operations.copyMatching(query as CFDictionary, &result)
            if status == errSecItemNotFound { return nil }
            guard status == errSecSuccess else { throw Failure.system(status) }
            guard let data = result as? Data, data.count == 32 else { throw Failure.invalidData }
            return data
        }
    }

    func store(_ data: Data) throws {
        guard data.count == 32 else { throw Failure.invalidData }
        try withKeychain { dataProtection in
            var attributes: [String: Any] = [kSecValueData as String: data]
            if dataProtection {
                attributes[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
            }
            let query = query(dataProtection: dataProtection)
            let status = operations.update(query as CFDictionary, attributes as CFDictionary)
            if status == errSecSuccess { return }
            guard status == errSecItemNotFound else { throw Failure.system(status) }
            let item = query.merging(attributes) { _, value in value }
            let addStatus = operations.add(item as CFDictionary)
            guard addStatus == errSecSuccess else { throw Failure.system(addStatus) }
        }
    }

    func store(key: Data) throws {
        try store(key)
    }

    func delete() throws {
        try withKeychain { dataProtection in
            let status = operations.delete(query(dataProtection: dataProtection) as CFDictionary)
            guard status == errSecSuccess || status == errSecItemNotFound else {
                throw Failure.system(status)
            }
        }
    }
}
