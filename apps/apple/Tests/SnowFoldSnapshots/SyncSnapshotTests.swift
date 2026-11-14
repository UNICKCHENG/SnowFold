import XCTest
import UIKit

/// 新建独立验收账本，真实启动、点击与截图，不依赖用户数据或真实服务。
@MainActor
final class SyncSnapshotTests: XCTestCase {
    override func setUpWithError() throws { continueAfterFailure = false }

    private var isTablet: Bool { UIDevice.current.userInterfaceIdiom == .pad }
    private var output: URL {
        URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent("snowfold-m7-shots", isDirectory: true)
    }

    private func snap(_ name: String) throws {
        try FileManager.default.createDirectory(at: output, withIntermediateDirectories: true)
        try XCUIScreen.main.screenshot().pngRepresentation.write(to: output.appendingPathComponent(name + ".png"))
    }

    private func launch(_ backend: String, dark: Bool) -> XCUIApplication {
        if isTablet { XCUIDevice.shared.orientation = .landscapeLeft }
        let app = XCUIApplication()
        app.launchArguments = ["-SNOWFOLD_KEYCHAIN_ACCOUNT", "snowfold-apple-m7-shot-\(UUID().uuidString)",
                               "-SNOWFOLD_SYNC_ACCEPTANCE", backend,
                               "-AppleInterfaceStyle", dark ? "Dark" : "Light"]
        if dark { app.launchArguments.append("-SNOWFOLD_SYNC_DARK") }
        app.launch()
        XCTAssertTrue(app.buttons["sync-toolbar"].firstMatch.waitForExistence(timeout: 15))
        return app
    }

    private func reveal(_ element: XCUIElement, app: XCUIApplication) {
        var attempts = 0
        while !element.isHittable && attempts < 15 {
            let from = app.coordinate(withNormalizedOffset: CGVector(dx: 0.7, dy: 0.78))
            let to = app.coordinate(withNormalizedOffset: CGVector(dx: 0.7, dy: 0.32))
            from.press(forDuration: 0.03, thenDragTo: to)
            attempts += 1
        }
        XCTAssertTrue(element.isHittable)
    }

    private func positionSyncForm(_ app: XCUIApplication) {
        let endpoint = app.textFields["sync-endpoint"]
        reveal(endpoint, app: app)
        let target = isTablet ? 280.0 : 270.0
        let distance = max(0, endpoint.frame.minY - target)
        if distance > 20 {
            let start = app.coordinate(withNormalizedOffset: CGVector(dx: 0.7, dy: 0.8))
            start.press(forDuration: 0.1, thenDragTo: start.withOffset(CGVector(dx: 0, dy: -distance)),
                        withVelocity: .slow, thenHoldForDuration: 0.3)
        }
    }

    func testS3LightSettingsAndIdleFailureButtons() throws {
        let app = launch("s3", dark: false)
        let device = isTablet ? "ipad" : "iphone"
        try snap("\(device)-overview-idle-light")
        app.buttons["sync-toolbar"].firstMatch.tap()
        XCTAssertTrue(app.buttons.matching(identifier: "sync-toolbar").matching(NSPredicate(format: "label == %@", "上次同步失败，点击重试")).firstMatch.waitForExistence(timeout: 8))
        try snap("\(device)-overview-failed-light")
        if isTablet {
            for tab in ["assets", "subscriptions"] {
                app.descendants(matching: .any).matching(identifier: "nav-" + tab).firstMatch.tap()
                XCTAssertTrue(app.buttons["sync-toolbar"].firstMatch.waitForExistence(timeout: 5))
                try snap("ipad-\(tab)-toolbar-light")
            }
            app.descendants(matching: .any).matching(identifier: "nav-settings").firstMatch.tap()
        } else { app.buttons["设置"].firstMatch.tap() }
        positionSyncForm(app)
        try snap("\(device)-settings-s3-light")
        reveal(app.secureTextFields["sync-secret"].firstMatch, app: app)
        try snap("\(device)-settings-s3-credentials-light")
        let state = app.staticTexts["sync-status"]
        reveal(state, app: app)
        try snap("\(device)-settings-policy-light")
        XCTAssertTrue(app.secureTextFields["sync-secret"].firstMatch.value as? String != "capture-fixture")
        app.terminate()
    }

    func testWebDAVDarkSettings() throws {
        let app = launch("webdav", dark: true)
        let device = isTablet ? "ipad" : "iphone"
        if isTablet { app.descendants(matching: .any).matching(identifier: "nav-settings").firstMatch.tap() }
        else { app.buttons["设置"].firstMatch.tap() }
        positionSyncForm(app)
        try snap("\(device)-settings-webdav-dark")
        XCTAssertFalse(app.textFields["sync-bucket"].exists)
        app.terminate()
    }
}
