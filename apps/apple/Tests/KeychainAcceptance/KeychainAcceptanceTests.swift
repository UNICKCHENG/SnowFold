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
        #if os(macOS)
        app.launchArguments = baseArguments + ["-SNOWFOLD_SHOT_TAB", "settings"]
        #else
        app.launchArguments = baseArguments
        #endif
        app.launch()
        #if os(iOS)
        let settings = app.buttons["设置"].firstMatch
        XCTAssertTrue(settings.waitForExistence(timeout: 15))
        settings.tap()
        #endif
        let location = app.staticTexts["密钥存放在系统钥匙串"].firstMatch
        XCTAssertTrue(location.waitForExistence(timeout: 10))
        for _ in 0..<8 where !location.isHittable {
            #if os(macOS)
            app.scrollViews["settings-form"].scroll(byDeltaX: 0, deltaY: -250)
            #else
            app.swipeUp()
            #endif
        }
        XCTAssertTrue(location.isHittable)
        #if os(macOS)
        XCTAssertGreaterThanOrEqual(app.windows.firstMatch.frame.width, 760)
        capture("mac-settings-keychain", app: app)
        #else
        capture("iphone16e-settings-keychain", app: app)
        #endif
        app.terminate()

        app.launchArguments = baseArguments + ["-SNOWFOLD_KEYCHAIN_FAILURE"]
        app.launch()
        let failure = app.staticTexts["本机密钥已失效，请用恢复码恢复"].firstMatch
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
