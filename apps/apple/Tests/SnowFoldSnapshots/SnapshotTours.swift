import XCTest
import UIKit

/// 真实运行巡检；脚本只准备核心写出的数据，所有交互均通过应用 UI。
@MainActor
final class SnapshotTours: XCTestCase {
    private var mode: String { ProcessInfo.processInfo.environment["SHOT_MODE"] ?? "shot" }
    private var isTablet: Bool { UIDevice.current.userInterfaceIdiom == .pad }
    private let longestName = "招商银行朝朝宝（活期理财）家庭备用金账户"
    private var outputDirectory: URL {
        URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent("snowfold-shots", isDirectory: true)
    }

    override func setUpWithError() throws {
        try FileManager.default.createDirectory(at: outputDirectory, withIntermediateDirectories: true)
        continueAfterFailure = false
    }

    private func snap(_ name: String) {
        do {
            try XCUIScreen.main.screenshot().pngRepresentation
                .write(to: outputDirectory.appendingPathComponent("\(mode)-\(name).png"))
        } catch { XCTFail("截图写入失败：\(error)") }
    }

    private func launch() -> XCUIApplication {
        if isTablet { XCUIDevice.shared.orientation = .landscapeLeft }
        let app = XCUIApplication()
        app.launch()
        XCTAssertTrue(app.staticTexts["总览"].firstMatch.waitForExistence(timeout: 15))
        return app
    }

    private func navigate(_ tab: String, app: XCUIApplication) {
        let label = ["overview": "总览", "assets": "资产", "subscriptions": "订阅"][tab] ?? tab
        if !isTablet {
            let button = app.tabBars.buttons[label]
            XCTAssertTrue(button.waitForExistence(timeout: 5))
            button.tap()
        } else {
            let item = app.descendants(matching: .any).matching(identifier: "nav-" + tab).firstMatch
            XCTAssertTrue(item.waitForExistence(timeout: 5), "侧栏入口缺失：\(tab)")
            item.tap()
        }
    }

    private func element(containing text: String, app: XCUIApplication) -> XCUIElement {
        let predicate = NSPredicate(format: "label CONTAINS %@", text)
        let button = app.buttons.matching(predicate).firstMatch
        if button.exists { return button }
        return app.staticTexts.matching(predicate).firstMatch
    }

    private func reveal(_ element: XCUIElement, app: XCUIApplication, scrollID: String) {
        // List 的辅助功能容器可能覆盖整个懒加载内容，swipeUp 会选到视口外的起点。
        // 从应用可见区域发手势，和用户在屏幕内滑动一致。
        var count = 0
        while !element.isHittable && count < 28 {
            // iPad 三栏的列表位于中间列，右侧是内嵌表单；手势必须落在列表上。
            let x = isTablet ? 0.30 : 0.58
            let start = app.coordinate(withNormalizedOffset: CGVector(dx: x, dy: 0.78))
            let end = app.coordinate(withNormalizedOffset: CGVector(dx: x, dy: 0.28))
            start.press(forDuration: 0.03, thenDragTo: end)
            count += 1
        }
        if !element.isHittable { snap("failure-" + scrollID) }
        XCTAssertTrue(element.waitForExistence(timeout: 5), "未找到列表记录")
        XCTAssertTrue(element.isHittable, "记录未滚入可操作范围")
    }

    private func closeEditor(_ app: XCUIApplication) {
        let wasSheet = app.sheets.count > 0
        app.buttons["取消"].firstMatch.tap()
        if wasSheet || !isTablet {
            let gone = expectation(for: NSPredicate(format: "count == 0"), evaluatedWith: app.sheets)
            XCTAssertEqual(XCTWaiter.wait(for: [gone], timeout: 5), .completed)
        }
    }

    private func openSettings(_ app: XCUIApplication) {
        if isTablet { navigate("settings", app: app) }
        else { app.buttons["设置"].firstMatch.tap() }
        XCTAssertTrue(app.staticTexts["外观"].firstMatch.waitForExistence(timeout: 8))
        snap("settings")
        let key = app.staticTexts["同步密钥"].firstMatch
        reveal(key, app: app, scrollID: "settings-form")
        snap("settings-backup")
        if !isTablet { app.buttons["完成"].firstMatch.tap() }
    }

    func testLongTour() {
        let app = launch()
        XCTAssertTrue(app.buttons.matching(NSPredicate(format: "label CONTAINS '净资产'")).firstMatch.waitForExistence(timeout: 8))
        snap("overview")
        let overviewScroll = app.descendants(matching: .any).matching(identifier: "overview-scroll").firstMatch
        overviewScroll.swipeUp()
        snap("overview-composition")

        navigate("assets", app: app)
        let asset = element(containing: longestName, app: app)
        reveal(asset, app: app, scrollID: "assets-list")
        snap("assets")
        asset.tap()
        XCTAssertTrue(app.buttons["调整余额"].firstMatch.waitForExistence(timeout: 8))
        snap("asset-detail")
        if !isTablet { app.navigationBars.buttons.element(boundBy: 0).tap() }

        navigate("subscriptions", app: app)
        snap("statistics")
        let renew = app.buttons["✓ 已续费"].firstMatch
        reveal(renew, app: app, scrollID: "subscriptions-list")
        snap("subscriptions")
        renew.tap()
        let renewed = app.staticTexts.matching(NSPredicate(format: "label CONTAINS '已顺延'")).firstMatch
        XCTAssertTrue(renewed.waitForExistence(timeout: 8))
        snap("renew-notice")
        app.buttons["关闭"].firstMatch.tap()

        let music = app.buttons["subscription-editor-Apple Music 家庭版"]
        reveal(music, app: app, scrollID: "subscriptions-list")
        music.tap()
        XCTAssertTrue(app.textFields["名称"].firstMatch.waitForExistence(timeout: 8))
        snap("subscription-edit")
        closeEditor(app)

        let musicAgain = app.buttons["subscription-editor-Apple Music 家庭版"]
        reveal(musicAgain, app: app, scrollID: "subscriptions-list")
        musicAgain.swipeLeft()
        app.buttons["删除"].firstMatch.tap()
        let undo = app.buttons["撤销 · 6秒内"]
        XCTAssertTrue(undo.waitForExistence(timeout: 5))
        snap("undo-banner")
        // 从横幅正文开始向下拖；撤销按钮随之消失，列表中删除保持生效。
        let start = undo.coordinate(withNormalizedOffset: CGVector(dx: -0.9, dy: 0.5))
        start.press(forDuration: 0.05, thenDragTo: start.withOffset(CGVector(dx: 0, dy: 90)))
        let gone = expectation(for: NSPredicate(format: "exists == false"), evaluatedWith: undo)
        XCTAssertEqual(XCTWaiter.wait(for: [gone], timeout: 3), .completed)
        snap("undo-dismissed")

        app.buttons["new-subscription"].firstMatch.tap()
        XCTAssertTrue(app.textFields["名称"].firstMatch.waitForExistence(timeout: 8))
        snap("subscription-create")
        closeEditor(app)
        openSettings(app)
    }

    func testEmptyTour() {
        let app = launch()
        snap("empty-overview")
        navigate("assets", app: app)
        let add = app.buttons["add-empty-cash"]
        XCTAssertTrue(add.waitForExistence(timeout: 8))
        snap("empty-assets")
        add.tap()
        XCTAssertTrue(app.buttons["category-fixed"].waitForExistence(timeout: 8))
        app.buttons["category-fixed"].tap()
        snap("asset-create")
        let name = app.textFields["名称"].firstMatch
        name.tap()
        name.typeText("Regression Asset")
        let amount = app.textFields["0.00"].firstMatch
        amount.tap()
        amount.typeText("12.345")
        app.buttons["保存"].firstMatch.tap()
        let error = app.descendants(matching: .any).matching(identifier: "editor-error").firstMatch
        XCTAssertTrue(error.waitForExistence(timeout: 5))
        XCTAssertTrue(error.isHittable, "输入错误必须出现在可见区域")
        snap("asset-error")
        closeEditor(app)
        navigate("subscriptions", app: app)
        XCTAssertTrue(app.staticTexts["还没有订阅"].firstMatch.waitForExistence(timeout: 8))
        snap("empty-subscriptions")
        app.buttons["new-subscription"].firstMatch.tap()
        XCTAssertTrue(app.textFields["名称"].firstMatch.waitForExistence(timeout: 8))
        snap("subscription-create")
        closeEditor(app)
        openSettings(app)
    }

    /// 分类切换保存、编辑期间他处删除、前台返回刷新分别由 UI/模型共同验证。
    func testCategorySelectionAndForegroundRefresh() {
        let app = launch()
        navigate("assets", app: app)
        app.buttons["new-asset"].firstMatch.tap()
        app.buttons["category-investment"].tap()
        let name = app.textFields["名称"].firstMatch
        name.tap()
        name.typeText("Grid Selection Regression")
        let amount = app.textFields["0.00"].firstMatch
        amount.tap()
        amount.typeText("12345678.90")
        app.buttons["保存"].firstMatch.tap()
        let row = element(containing: "Grid Selection Regression", app: app)
        reveal(row, app: app, scrollID: "assets-list")
        XCTAssertTrue(row.exists)
        XCUIDevice.shared.press(.home)
        app.activate()
        XCTAssertTrue(row.waitForExistence(timeout: 8), "从后台回前台后记录应仍可见")
        snap("foreground-refreshed")
    }
}
