import XCTest

/// 真应用验收：点击设置、读取真实提示、保留截图，不触发文件面板。
@MainActor
final class KeychainAcceptanceTests: XCTestCase {
    private func capture(_ name: String, app: XCUIApplication) {
        #if os(macOS)
        let screenshot = app.windows.firstMatch.screenshot()
        #else
        let screenshot = XCUIScreen.main.screenshot()
        #endif
        let attachment = XCTAttachment(screenshot: screenshot)
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    func testSettingsLocationAndActualLoadError() {
        continueAfterFailure = false
        let app = XCUIApplication()
        let account = "snowfold-keychain-ui-\(UUID().uuidString)"
        let baseArguments = ["-SNOWFOLD_KEYCHAIN_ACCOUNT", account]
        app.launchArguments = baseArguments
        app.launch()
        #if os(iOS)
        let settings = app.buttons["设置"].firstMatch
        XCTAssertTrue(settings.waitForExistence(timeout: 15))
        settings.tap()
        #else
        // Mac：设置在独立窗口（⌘,），经应用菜单打开。
        let settingsMenu = app.menuBarItems["SnowFold"].firstMatch
        XCTAssertTrue(settingsMenu.waitForExistence(timeout: 10))
        settingsMenu.tap()
        let settingsItem = app.menuItems.matching(
            NSPredicate(format: "label CONTAINS %@ OR label CONTAINS %@", "设置", "Settings")
        ).firstMatch
        XCTAssertTrue(settingsItem.waitForExistence(timeout: 5))
        settingsItem.tap()
        #endif
        let location = app.staticTexts["密钥存放在系统钥匙串"].firstMatch
        XCTAssertTrue(location.waitForExistence(timeout: 10))
        for _ in 0..<8 where !location.isHittable {
            #if os(macOS)
            app.swipeUp()
            #else
            app.swipeUp()
            #endif
        }
        XCTAssertTrue(location.isHittable)
        #if os(macOS)
        XCTAssertGreaterThanOrEqual(app.windows.firstMatch.frame.width, 600)
        capture("mac-settings-keychain", app: app)
        #else
        capture("iphone16e-settings-keychain", app: app)
        #endif
        app.terminate()

        app.launchArguments = baseArguments + ["-SNOWFOLD_KEYCHAIN_FAILURE"]
        app.launch()
        // UI 测试包不能引用宿主类型；文案取自 copy.md startup_key_unavailable 的稳定片段。
        let failure = app.staticTexts.matching(
            NSPredicate(format: "label CONTAINS %@", "请检查密钥文件或导入恢复码")
        ).firstMatch
        XCTAssertTrue(failure.waitForExistence(timeout: 15))
        XCTAssertTrue(failure.isHittable)
        #if os(macOS)
        capture("mac-keychain-load-error", app: app)
        #else
        capture("iphone16e-keychain-load-error", app: app)
        #endif
        app.terminate()
    }
}
