import XCTest

/// 审计专用（audit/apple-shots 分支）：Mac 弹层截图。
/// AcceptanceCapture 的 SNOWFOLD_SHOT 参数会让应用截完即退，拍不到交互中间态；
/// 这里用 XCUITest 打开「添加资产」与「订阅编辑」弹层后截应用窗口，
/// PNG 直写 MAC_SHOTS_DEST 目录（不经 xcresult 附件，便于归集）。
/// 数据目录经 SNOWFOLD_DATA_DIR 指到应用沙盒容器 tmp 下的 fixture（工作流负责放入）。
final class MacSheetTours: XCTestCase {
    private var destination: URL {
        if let path = ProcessInfo.processInfo.environment["MAC_SHOTS_DEST"], !path.isEmpty {
            return URL(fileURLWithPath: path, isDirectory: true)
        }
        return URL(fileURLWithPath: NSTemporaryDirectory(), isDirectory: true)
    }

    private var fixtureRoot: URL {
        let home = ProcessInfo.processInfo.environment["HOME"] ?? NSHomeDirectory()
        return URL(fileURLWithPath: home, isDirectory: true)
            .appendingPathComponent("Library/Containers/app.snowfold/Data/tmp", isDirectory: true)
    }

    override func setUpWithError() throws {
        try FileManager.default.createDirectory(at: destination, withIntermediateDirectories: true)
        continueAfterFailure = false
    }

    // MARK: 用例（浅深各一遍；每个用例独立启动应用，弹层互不干扰）

    func testAddAssetSheetLongLight() { shootAddAsset(fixture: "audit-long", look: "light", suffix: "") }
    func testAddAssetSheetLongDark() { shootAddAsset(fixture: "audit-long", look: "dark", suffix: "") }
    func testAddAssetSheetEmptyLight() { shootAddAsset(fixture: "audit-empty", look: "light", suffix: "-empty") }
    func testAddAssetSheetEmptyDark() { shootAddAsset(fixture: "audit-empty", look: "dark", suffix: "-empty") }
    func testSubscriptionEditSheetLight() { shootSubscriptionEdit(look: "light") }
    func testSubscriptionEditSheetDark() { shootSubscriptionEdit(look: "dark") }

    // MARK: 步骤

    private func setAppearance(_ look: String) {
        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/usr/bin/defaults")
        if look == "dark" {
            process.arguments = ["write", "-g", "AppleInterfaceStyle", "Dark"]
        } else {
            process.arguments = ["delete", "-g", "AppleInterfaceStyle"]
        }
        process.standardOutput = FileHandle.nullDevice
        process.standardError = FileHandle.nullDevice
        try? process.run()
        process.waitUntilExit()
    }

    private func launch(fixture: String) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchEnvironment["SNOWFOLD_DATA_DIR"] =
            fixtureRoot.appendingPathComponent(fixture, isDirectory: true).path
        app.launch()
        let sidebar = app.descendants(matching: .any).matching(identifier: "nav-assets").firstMatch
        XCTAssertTrue(sidebar.waitForExistence(timeout: 20), "侧栏未出现（数据目录：\(fixture)）")
        return app
    }

    private func snapWindow(_ app: XCUIApplication, _ name: String) {
        let window = app.windows.firstMatch
        let shot = window.exists ? window.screenshot() : XCUIScreen.main.screenshot()
        let url = destination.appendingPathComponent("\(name).png")
        do {
            try shot.pngRepresentation.write(to: url)
            print("已写入 \(url.path)")
        } catch {
            XCTFail("截图写入失败：\(error)")
        }
    }

    private func shootAddAsset(fixture: String, look: String, suffix: String) {
        setAppearance(look)
        let app = launch(fixture: fixture)
        let trigger = app.buttons["new-asset"].firstMatch
        XCTAssertTrue(trigger.waitForExistence(timeout: 8), "添加资产入口缺失")
        trigger.tap()
        // 分类卡出现即代表弹层渲染完成；等待失败也照拍现场，便于核对问题。
        _ = app.descendants(matching: .any).matching(identifier: "category-cash")
            .firstMatch.waitForExistence(timeout: 8)
        sleep(1)
        snapWindow(app, "mac-\(look)-sheet-add-asset\(suffix)")
    }

    private func shootSubscriptionEdit(look: String) {
        setAppearance(look)
        let app = launch(fixture: "audit-long")
        app.descendants(matching: .any).matching(identifier: "nav-subscriptions").firstMatch.tap()
        let row = app.descendants(matching: .any)
            .matching(identifier: "subscription-editor-iCloud 2TB（家庭共享）").firstMatch
        XCTAssertTrue(row.waitForExistence(timeout: 8), "订阅行缺失")
        row.tap()
        sleep(1)
        snapWindow(app, "mac-\(look)-sheet-subscription-edit")
    }
}
