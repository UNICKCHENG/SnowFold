#if DEBUG
import Foundation

/// 仅由验收参数启用，配置仍经过真实核心；传输固定拒绝，不访问外部服务。
final class CaptureSyncTransport: SyncTransport, @unchecked Sendable {
    func send(request: SyncRequest) throws -> SyncResponse {
        SyncResponse(status: 403, headers: [], body: Data())
    }
}

@MainActor
enum SyncAcceptance {
    private static var prepared = false

    static func prepareIfRequested(_ store: SnowFoldStore) async {
        let arguments = CommandLine.arguments
        guard !prepared, let index = arguments.firstIndex(of: "-SNOWFOLD_SYNC_ACCEPTANCE"),
              index + 1 < arguments.count else { return }
        prepared = true
        do {
            _ = try await store.createAsset(categoryCode: "cash", name: "家庭备用金", amountText: "12800")
            _ = try await store.exportRecoveryCode()
            let s3 = arguments[index + 1] == "s3"
            try await store.configureSync(SyncConfig(
                backend: s3 ? .s3 : .webDav, endpoint: "https://sync.example.com",
                bucket: s3 ? "snowfold" : "", region: s3 ? "us-east-1" : "", prefix: "family",
                username: "snowfold", pathStyle: s3), secret: "capture-fixture")
            try await store.setSyncPolicy(SyncPolicy(onLaunch: false, intervalMinutes: 30, afterEdit: true))
            await store.saveTheme(arguments.contains("-SNOWFOLD_SYNC_DARK") ? .dark : .light)
            try await store.setSyncEnabled(true)
            store.dismissNotice()
        } catch {
            store.showNotice(Copy.syncMessage(for: error))
        }
    }
}
#endif
