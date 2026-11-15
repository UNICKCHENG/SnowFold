package app.snowfold

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import app.snowfold.core.SnowFoldCore
import app.snowfold.core.SubscriptionInput
import app.snowfold.core.ThemePreference
import java.io.File
import java.time.LocalDate
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** 真核心 + 真 Activity；截图样例只调用公开核心接口，不直接写数据库。 */
class MobileDeliveryTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val args get() = InstrumentationRegistry.getArguments()
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var core: SnowFoldCore
    private lateinit var assetId: String
    private lateinit var subscriptionId: String
    private val longestName = "招商银行朝朝宝（活期理财）家庭备用金账户"

    @Before fun prepare() {
        core = SnowFoldCore.open(context.filesDir.absolutePath, app.snowfold.platform.AndroidSecureKeyStore(context))
        core.clearData()
        core.saveTheme(if (args.getString("captureTheme") == "dark") ThemePreference.DARK else ThemePreference.LIGHT)
        assetId = core.createAsset("cash", longestName, 1200000000L)
        core.changeBalance(assetId, 1234567890L)
        val initial = core.data().history.first { it.assetId == assetId }
        core.correctHistory(initial.id, 1180000000L)
        (1..30).forEach { index ->
            core.createAsset(listOf("cash", "fixed", "investment", "receivable", "debt")[index % 5],
                "家庭备用金账户 $index", 8234512L)
        }
        val today = LocalDate.now()
        val dates = listOf(today.minusDays(13), today.minusDays(6), today.plusDays(3), today.plusDays(7),
            today.plusMonths(1).withDayOfMonth(3), today.plusMonths(2), today.plusDays(9))
        dates.forEachIndexed { index, date ->
            val id = core.createSubscription(SubscriptionInput(
                name = listOf("Apple Music 家庭组", "iCloud+ 200GB", "GitHub Copilot", "视频会员家庭共享年度订阅", "物业管理费", "Notion Plus", "已暂停的健身课")[index],
                amountCents = if (index == 2) 1000L else 2100L,
                currency = if (index == 2) "USD" else "CNY", cycle = "monthly",
                nextChargeOn = date.toString(), status = if (index == 6) "paused" else "active"))
            if (index == 1) subscriptionId = id
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("nav-overview").fetchSemanticsNodes().isNotEmpty() }
    }

    @After fun cleanup() {
        scenario.close()
        core.destroy()
    }

    private fun text(resource: Int) = context.getString(resource)
    private fun nav(route: String) { compose.onNodeWithTag("nav-$route").performClick(); compose.waitForIdle() }
    private fun shot(name: String) {
        compose.waitForIdle()
        val prefix = args.getString("capturePrefix") ?: "supplement-light"
        val output = File(context.getExternalFilesDir(null)!!, "$prefix-$name.png")
        val image = instrumentation.uiAutomation.takeScreenshot()
        output.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }

    /** 每页连续滚动到末尾，包含长列表中的全部分组。 */
    private fun pages(tag: String, name: String) {
        val node = compose.onNodeWithTag(tag)
        var previous = -1f
        var unchanged = 0
        for (index in 0..24) {
            shot("$name-${index.toString().padStart(2, '0')}")
            val range = node.fetchSemanticsNode().config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)
            val current = range?.value?.invoke() ?: break
            if (current == previous) unchanged++ else unchanged = 0
            if (unchanged >= 2 || (range != null && current >= range.maxValue())) break
            previous = current
            val height = node.fetchSemanticsNode().boundsInRoot.height
            node.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, height * 0.75f) }
            compose.waitForIdle()
        }
    }

    @Test fun captureAllPages() {
        pages("overview-list", "01-overview")
        nav("assets")
        pages("assets-list", "02-assets")
        compose.onNodeWithTag("assets-list").performScrollToNode(hasTestTag("asset-group-0"))
        compose.onNodeWithTag("asset-$assetId").performClick()
        shot("03-asset-detail")
        val currentHistory = core.data().history.last { it.assetId == assetId }.id
        val detailScroll = if (compose.onAllNodesWithTag("asset-detail-scroll").fetchSemanticsNodes().isNotEmpty()) "asset-detail-scroll" else "assets-list"
        compose.onNodeWithTag(detailScroll).performScrollToNode(hasTestTag("history-correct-$currentHistory"))
        compose.onAllNodes(hasTestTag("history-correct-$currentHistory"), useUnmergedTree = true)[0].performClick()
        compose.onNodeWithTag("editor-sheet").assertIsDisplayed()
        shot("04-correction")
        Espresso.pressBack()
        nav("subscriptions")
        pages("subscriptions-list", "05-subscriptions")
        compose.onNodeWithTag("subscriptions-list").performScrollToNode(hasTestTag("subscription-group-0"))
        compose.onNodeWithTag("subscriptions-list").performScrollToNode(hasTestTag("subscription-$subscriptionId"))
        compose.onNodeWithTag("subscription-$subscriptionId").performClick()
        compose.onNodeWithTag("editor-sheet").assertIsDisplayed()
        shot("06-subscription-editor")
        Espresso.pressBack()
        nav("settings")
        pages("settings-list", "07-settings")
    }

    @Test fun deepLinksAndMenus() {
        // 总览构成行跳到资产页对应分区
        compose.onNodeWithTag("overview-list").performScrollToNode(hasTestTag("composition-row-2"))
        compose.onNodeWithTag("composition-row-2").performClick()
        compose.onNodeWithTag("asset-group-2").assertIsDisplayed()
        nav("overview")
        // 「全部订阅」仍从总览进入完整列表，并保留待确认分组。
        compose.onNodeWithText(text(R.string.all_subscriptions)).performScrollTo().performClick()
        compose.onNodeWithTag("subscription-group-0").assertIsDisplayed()
        // 已暂停分组照常展开，且没有额外的「即将扣费」分组
        compose.onNodeWithTag("subscriptions-list").performScrollToNode(hasTestTag("subscription-group-4"))
        compose.onNodeWithText("已暂停的健身课").assertExists()
        // 资产长按菜单：调整余额 / 重命名 / 删除
        nav("assets")
        compose.onNodeWithTag("assets-list").performScrollToNode(hasTestTag("asset-group-0"))
        compose.onNodeWithTag("asset-$assetId").performTouchInput { longClick() }
        compose.onNodeWithText(text(R.string.rename)).performClick()
        compose.onNodeWithTag("rename-name").assertTextContains(longestName)
        compose.onNodeWithTag("editor-cancel").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("editor-sheet").fetchSemanticsNodes().isEmpty() }
        nav("subscriptions")
        compose.onNodeWithTag("subscriptions-list").performScrollToNode(hasTestTag("subscription-$subscriptionId"))
        compose.onNodeWithTag("subscription-$subscriptionId").performTouchInput { longClick() }
        compose.onNodeWithText(text(R.string.menu_edit)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.pause)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.delete)).assertIsDisplayed()
        shot("09-subscription-menu")
    }

    @Test fun correctionFailureRetainsSheetAndSuccessKeepsHistoryCount() {
        nav("assets")
        compose.onNodeWithTag("asset-$assetId").performClick()
        val currentHistory = core.data().history.last { it.assetId == assetId }.id
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("history-correct-$currentHistory"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasTestTag("history-correct-$currentHistory"), useUnmergedTree = true)[0].performClick()
        val snapshots = core.data().history.size
        compose.onNodeWithTag("correct-amount").performTextReplacement("1..2")
        compose.onNodeWithTag("editor-save").performClick()
        compose.onNodeWithText(text(R.string.correct_history_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.error_validation), substring = true).assertExists()
        assertEquals(1234567890L, core.data().assets.first { it.id == assetId }.amountCents)
        shot("10-correction-error")
        compose.onNodeWithTag("correct-amount").performTextReplacement("12345678.91")
        compose.onNodeWithTag("editor-save").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("editor-sheet").fetchSemanticsNodes().isEmpty() }
        assertEquals(snapshots, core.data().history.size)
        assertEquals(1234567891L, core.data().assets.first { it.id == assetId }.amountCents)
        compose.onNode(hasText(longestName) and hasAnyAncestor(hasTestTag("asset-detail"))).assertExists()
    }

    @Test fun duplicateSaveAndTwoDeletesUndoOnlyLatest() {
        nav("assets")
        compose.onNodeWithTag("assets-add").performClick()
        compose.onNodeWithTag("editor-sheet").assertIsDisplayed()
        compose.onNodeWithTag("asset-name").performTextReplacement("连点只写一笔")
        compose.onNodeWithTag("asset-amount").performTextReplacement("12.34")
        compose.onNodeWithTag("editor-save").assertIsEnabled()
        shot("11a-before-double-save")
        compose.onNodeWithTag("editor-save").performSemanticsAction(SemanticsActions.OnClick) { click -> click(); click() }
        compose.waitUntil(5_000) { core.data().assets.count { it.name == "连点只写一笔" } == 1 }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("editor-sheet").fetchSemanticsNodes().isEmpty() }
        assertEquals(32, core.data().assets.size)
        val first = core.data().assets[1]
        val second = core.data().assets[2]
        for (asset in listOf(first, second)) {
            compose.onNodeWithTag("assets-list").performScrollToNode(hasTestTag("asset-${asset.id}"))
            compose.onNodeWithTag("asset-${asset.id}").performTouchInput { swipeLeft() }
            compose.waitUntil(5_000) { core.data().assets.none { it.id == asset.id } }
        }
        shot("11-undo-latest")
        compose.onNodeWithText(text(R.string.undo)).performClick()
        compose.waitUntil(5_000) { core.data().assets.any { it.id == second.id } }
        assertFalse(core.data().assets.any { it.id == first.id })
    }

    @Test fun undoBannerExpiresWithCoreTicketWithoutRestoringDeletedAsset() {
        nav("assets")
        compose.onNodeWithTag("asset-$assetId").performTouchInput { swipeLeft() }
        compose.waitUntil(5_000) { core.data().assets.none { it.id == assetId } }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("undo-banner").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("undo-banner").assertExists()
        compose.waitUntil(8_500) { compose.onAllNodesWithTag("undo-banner").fetchSemanticsNodes().isEmpty() }
        assertFalse(core.data().assets.any { it.id == assetId })
    }

    @Test fun draftSurvivesRecreation() {
        nav("subscriptions")
        compose.onNodeWithTag("subscriptions-list").performScrollToNode(hasTestTag("subscription-$subscriptionId"))
        compose.onNodeWithTag("subscription-$subscriptionId").performClick()
        compose.onNodeWithTag("editor-sheet").assertIsDisplayed()
        compose.onNodeWithTag("sub-name").performTextReplacement("重建后仍在的草稿")
        compose.onNodeWithTag("sub-amount").performTextReplacement("9876.54")
        Espresso.closeSoftKeyboard()
        // Physical rotation is driven outside instrumentation; recreate covers the same state path.
        scenario.recreate()
        compose.onNodeWithTag("sub-name").assertTextContains("重建后仍在的草稿")
        compose.onNodeWithTag("sub-amount").assertTextContains("9876.54")
        assertEquals("iCloud+ 200GB", core.data().subscriptions.first { it.id == subscriptionId }.name)
        shot("13-recreated-draft")
    }

    @Test fun foregroundRefreshClosesDeletedRecordAndRenewalHasNoUndo() {
        nav("subscriptions")
        compose.onNodeWithTag("subscriptions-list").performScrollToNode(hasTestTag("subscription-$subscriptionId"))
        compose.onNodeWithTag("subscription-$subscriptionId").performClick()
        compose.onNodeWithTag("editor-sheet").assertIsDisplayed()
        scenario.moveToState(Lifecycle.State.CREATED)
        core.deleteSubscription(subscriptionId)
        scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("editor-sheet").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText(text(R.string.error_not_found)).assertExists()
        compose.onNodeWithTag("subscriptions-list").performScrollToNode(hasTestTag("subscription-group-0"))
        compose.onAllNodesWithText(text(R.string.mark_renewed))[0].performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("已顺延 1 期", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("已顺延 1 期", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("undo-banner").assertDoesNotExist()
        shot("14-renewal-notice")
    }
}
