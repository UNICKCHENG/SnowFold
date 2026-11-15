import XCTest
import UIKit

/// 真实运行巡检；脚本只准备核心写出的数据，所有交互均通过应用 UI。
/// 标识对照：TabView 与侧边栏行 nav-overview/nav-assets/nav-subscriptions/nav-settings；
/// 工具栏 new-asset/new-subscription/sync-toolbar；行 asset-row-<名>/subscription-editor-<名>；
/// 详情 adjust-balance/correct-<id>；sheet 头部按钮读屏名「取消」「保存」。
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
        XCTAssertTrue(app.descendants(matching: .any).matching(identifier: "nav-overview")
            .firstMatch.waitForExistence(timeout: 20), "导航入口缺失")
        return app
    }

    /// 每页一个焦点文字，用作到达断言（页标题会因页面而异，如「资产与负债」）。
    private func navigate(_ tab: String, app: XCUIApplication) {
        let marker = ["overview": "净资产", "assets": "总资产",
                      "subscriptions": "本月待扣", "settings": "外观"][tab] ?? tab
        let item = app.descendants(matching: .any).matching(identifier: "nav-" + tab).firstMatch
        XCTAssertTrue(item.waitForExistence(timeout: 8), "导航入口缺失：\(tab)")
        item.tap()
        XCTAssertTrue(element(containing: marker, app: app).waitForExistence(timeout: 8),
                      "页面焦点未出现：\(marker)")
    }

    private func element(containing text: String, app: XCUIApplication) -> XCUIElement {
        let predicate = NSPredicate(format: "label CONTAINS %@", text)
        let button = app.buttons.matching(predicate).firstMatch
        if button.exists { return button }
        return app.staticTexts.matching(predicate).firstMatch
    }

    private func reveal(_ element: XCUIElement, app: XCUIApplication, scrollID: String) {
        var count = 0
        while !element.isHittable && count < 28 {
            // 分栏列表位于左列，手势必须落在列表上；手机页从中部起滑。
            let x = isTablet ? 0.30 : 0.58
            let start = app.coordinate(withNormalizedOffset: CGVector(dx: x, dy: 0.72))
            let end = app.coordinate(withNormalizedOffset: CGVector(dx: x, dy: 0.25))
            start.press(forDuration: 0.03, thenDragTo: end)
            count += 1
        }
        XCTAssertTrue(element.waitForExistence(timeout: 5), "未找到列表记录：\(scrollID)")
        XCTAssertTrue(element.isHittable, "记录未滚入可操作范围：\(scrollID)")
    }

    /// sheet 头部的「保存」圆形按钮（读屏名）。
    private func saveButton(_ app: XCUIApplication) -> XCUIElement {
        app.buttons["保存"].firstMatch
    }

    private func cancelButton(_ app: XCUIApplication) -> XCUIElement {
        app.buttons["取消"].firstMatch
    }

    private func closeEditor(_ app: XCUIApplication) {
        let wasSheet = app.sheets.count > 0
        cancelButton(app).tap()
        if wasSheet || !isTablet {
            let gone = expectation(for: NSPredicate(format: "count == 0"), evaluatedWith: app.sheets)
            XCTAssertEqual(XCTWaiter.wait(for: [gone], timeout: 5), .completed)
        }
    }

    private func openSettings(_ app: XCUIApplication) {
        navigate("settings", app: app)
        XCTAssertTrue(app.staticTexts["外观"].firstMatch.waitForExistence(timeout: 8))
        snap("settings")
        let key = app.staticTexts["同步密钥指纹"].firstMatch
        if key.waitForExistence(timeout: 4) {
            reveal(key, app: app, scrollID: "settings-key")
        }
        snap("settings-key")
    }

    // MARK: 长数据巡游

    func testLongTour() {
        let app = launch()
        XCTAssertTrue(element(containing: "净资产", app: app).waitForExistence(timeout: 8))
        snap("overview")
        app.swipeUp()
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
        XCTAssertTrue(element(containing: "月均支出", app: app).waitForExistence(timeout: 8))
        snap("statistics")
        let renew = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "已续费")).firstMatch
        reveal(renew, app: app, scrollID: "subscriptions-list")
        snap("subscriptions")
        renew.tap()
        let renewed = app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "已顺延")).firstMatch
        XCTAssertTrue(renewed.waitForExistence(timeout: 8))
        snap("renew-notice")
        app.buttons["关闭"].firstMatch.tap()

        let music = app.descendants(matching: .any)
            .matching(identifier: "subscription-editor-Apple Music 家庭版").firstMatch
        reveal(music, app: app, scrollID: "subscriptions-list")
        music.tap()
        XCTAssertTrue(app.textFields["例如：Netflix"].firstMatch.waitForExistence(timeout: 8))
        snap("subscription-edit")
        closeEditor(app)

        app.buttons["new-subscription"].firstMatch.tap()
        XCTAssertTrue(app.textFields["例如：Netflix"].firstMatch.waitForExistence(timeout: 8))
        snap("subscription-create")
        closeEditor(app)
        openSettings(app)
    }

    // MARK: 空库巡游

    func testEmptyTour() {
        let app = launch()
        XCTAssertTrue(app.staticTexts["还没有记录"].firstMatch.waitForExistence(timeout: 8))
        snap("empty-overview")
        navigate("assets", app: app)
        let add = app.descendants(matching: .any).matching(identifier: "add-empty-cash").firstMatch
        XCTAssertTrue(add.waitForExistence(timeout: 8), "分类空状态「添加」缺失")
        snap("empty-assets")
        add.tap()
        let name = app.textFields["例如：招商银行 储蓄卡"].firstMatch
        XCTAssertTrue(name.waitForExistence(timeout: 8))
        name.tap()
        name.typeText("Regression Asset")
        let amount = app.textFields["0.00"].firstMatch
        amount.tap()
        amount.typeText("12.345")
        saveButton(app).tap()
        let error = app.descendants(matching: .any).matching(identifier: "editor-error").firstMatch
        XCTAssertTrue(error.waitForExistence(timeout: 5))
        XCTAssertTrue(error.isHittable, "输入错误必须出现在可见区域")
        snap("asset-error")
        closeEditor(app)
        navigate("subscriptions", app: app)
        XCTAssertTrue(app.staticTexts["还没有订阅"].firstMatch.waitForExistence(timeout: 8))
        snap("empty-subscriptions")
        app.buttons["new-subscription"].firstMatch.tap()
        XCTAssertTrue(app.textFields["例如：Netflix"].firstMatch.waitForExistence(timeout: 8))
        snap("subscription-create")
        closeEditor(app)
        openSettings(app)
    }

    // MARK: 分类切换与前台刷新

    /// 分类切换保存、编辑期间他处删除、前台返回刷新分别由 UI/模型共同验证。
    func testCategorySelectionAndForegroundRefresh() {
        let app = launch()
        navigate("assets", app: app)
        app.buttons["new-asset"].firstMatch.tap()
        // 分类是菜单选择器：先点开菜单，再选「投资理财」。
        let menu = element(containing: "流动资金", app: app)
        XCTAssertTrue(menu.waitForExistence(timeout: 8), "分类菜单缺失")
        menu.tap()
        let item = app.buttons["投资理财"].firstMatch
        XCTAssertTrue(item.waitForExistence(timeout: 5), "菜单项缺失")
        item.tap()
        let name = app.textFields["例如：招商银行 储蓄卡"].firstMatch
        name.tap()
        name.typeText("Grid Selection Regression")
        let amount = app.textFields["0.00"].firstMatch
        amount.tap()
        amount.typeText("12345678.90")
        saveButton(app).tap()
        let row = element(containing: "Grid Selection Regression", app: app)
        reveal(row, app: app, scrollID: "assets-list")
        XCTAssertTrue(row.exists)
        XCUIDevice.shared.press(.home)
        app.activate()
        XCTAssertTrue(row.waitForExistence(timeout: 8), "从后台回前台后记录应仍可见")
        snap("foreground-refreshed")
    }
}
