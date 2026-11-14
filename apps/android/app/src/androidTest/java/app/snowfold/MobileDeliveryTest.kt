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

/** 真核心 + 真 Activity；截图样例也只调用公开核心接口，不直接写数据库。 */
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
        core = SnowFoldCore.open(context.filesDir.absolutePath)
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
    private fun field(resource: Int) = compose.onNode(hasSetTextAction() and hasText(text(resource)))
    private fun save() { compose.onNodeWithTag("editor-save").performClick(); compose.waitForIdle() }
    private fun shot(name: String) {
        compose.waitForIdle()
        val prefix = args.getString("capturePrefix") ?: "supplement-light"
        val output = File(context.filesDir, "$prefix-$name.png")
        val image = instrumentation.uiAutomation.takeScreenshot()
        output.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }

    /** 每页保留原始屏幕截图，连续滚动到末尾，包含长列表中的全部分组。 */
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
        compose.onNodeWithTag("assets-list").performScrollToIndex(0)
        compose.onNodeWithTag("asset-$assetId").performClick()
        shot("03-asset-detail")
        compose.onAllNodesWithText(text(R.string.correct_history))[0].performClick()
        shot("04-correction")
        Espresso.pressBack()
        nav("subscriptions")
        pages("subscriptions-list", "05-subscriptions")
        compose.onNodeWithTag("subscriptions-list").performScrollToIndex(0)
        compose.onNodeWithTag("subscriptions-list").performScrollToNode(hasTestTag("subscription-$subscriptionId"))
        compose.onNodeWithTag("subscription-$subscriptionId").performClick()
        shot("06-subscription-editor")
        // Phone editor is modal; on tablets the same form is embedded in the right pane.
        if (compose.onAllNodesWithTag("editor-sheet").fetchSemanticsNodes().isNotEmpty()) Espresso.pressBack()
        compose.onNodeWithContentDescription(text(R.string.cd_settings)).performClick()
        pages("settings-list", "07-settings")
    }

    @Test fun menusAndDeepLinksAndCollapsedTitles() {
        compose.onNodeWithTag("overview-list").performTouchInput { swipeUp() }
        shot("08-collapsed-title")
        compose.onNodeWithTag("legend-2").performScrollTo().performClick()
        compose.onNodeWithTag("asset-group-2").assertIsDisplayed()
        nav("overview")
        compose.onNodeWithTag("overdue-link").performScrollTo().performClick()
        compose.onNodeWithTag("subscription-group-0").assertIsDisplayed()
        // Paused is expanded and the final group exists without any extra upcoming group.
        compose.onNodeWithTag("subscriptions-list").performScrollToNode(hasTestTag("subscription-group-4"))
        compose.onNodeWithText("已暂停的健身课").assertExists()
        nav("assets")
        compose.onNodeWithTag("assets-list").performScrollToIndex(0)
        compose.onNodeWithTag("asset-$assetId").performTouchInput { longClick() }
        compose.onNodeWithText(text(R.string.menu_rename)).performClick()
        field(R.string.name_label).assertTextContains(longestName)
        Espresso.pressBack()
        nav("subscriptions")
        compose.onNodeWithTag("subscriptions-list").performScrollToNode(hasTestTag("subscription-$subscriptionId"))
        compose.onNodeWithTag("subscription-$subscriptionId").performTouchInput { longClick() }
        compose.onNodeWithText(text(R.string.menu_edit)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.pause)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.menu_delete)).assertIsDisplayed()
        shot("09-subscription-menu")
    }

    @Test fun correctionFailureRetainsSheetAndSuccessKeepsHistoryCount() {
        nav("assets")
        compose.onNodeWithTag("asset-$assetId").performClick()
        compose.onAllNodesWithText(text(R.string.correct_history))[0].performClick()
        val snapshots = core.data().history.size
        field(R.string.amount_label).performTextReplacement("1..2")
        save()
        compose.onNodeWithText(text(R.string.correction_sheet_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.error_validation), substring = true).assertExists()
        assertEquals(1234567890L, core.data().assets.first { it.id == assetId }.amountCents)
        shot("10-correction-error")
        field(R.string.amount_label).performTextReplacement("12345678.91")
        save()
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text(R.string.correction_sheet_title)).fetchSemanticsNodes().isEmpty() }
        assertEquals(snapshots, core.data().history.size)
        assertEquals(1234567891L, core.data().assets.first { it.id == assetId }.amountCents)
        compose.onNodeWithText(longestName).assertExists()
    }

    @Test fun duplicateSaveAndTwoDeletesUndoOnlyLatest() {
        nav("assets")
        compose.onNodeWithContentDescription(text(R.string.add)).performClick()
        compose.onNodeWithTag("sheet-toggle").performClick()
        field(R.string.name_label).performTextReplacement("连点只写一笔")
        field(R.string.amount_label).performTextReplacement("12.34")
        compose.onNodeWithTag("editor-save").assertIsEnabled()
        shot("11a-before-double-save")
        compose.onNodeWithTag("editor-save").performSemanticsAction(SemanticsActions.OnClick) { click -> click(); click() }
        compose.waitUntil(5_000) { core.data().assets.count { it.name == "连点只写一笔" } == 1 }
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

    @Test fun sheetDraftSurvivesHalfFullAndRecreation() {
        nav("subscriptions")
        compose.onNodeWithTag("subscriptions-list").performScrollToNode(hasTestTag("subscription-$subscriptionId"))
        compose.onNodeWithTag("subscription-$subscriptionId").performClick()
        compose.onNodeWithTag("sheet-toggle").performClick()
        field(R.string.name_label).performTextReplacement("重建后仍在的草稿")
        field(R.string.amount_label).performTextReplacement("9876.54")
        Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("sheet-toggle").performClick()
        compose.onNodeWithTag("sheet-toggle").performClick()
        field(R.string.name_label).assertTextContains("重建后仍在的草稿")
        // Physical rotation is driven outside instrumentation: API 37 can keep an obsolete
        // dialog root registered with Compose's idling resource after crossing the pane boundary.
        scenario.recreate()
        field(R.string.name_label).assertTextContains("重建后仍在的草稿")
        field(R.string.amount_label).assertTextContains("9876.54")
        assertEquals("iCloud+ 200GB", core.data().subscriptions.first { it.id == subscriptionId }.name)
        shot("13-recreated-draft")
    }

    @Test fun foregroundRefreshClosesDeletedRecordAndRenewalHasNoUndo() {
        nav("subscriptions")
        compose.onNodeWithTag("subscriptions-list").performScrollToNode(hasTestTag("subscription-$subscriptionId"))
        compose.onNodeWithTag("subscription-$subscriptionId").performClick()
        scenario.moveToState(Lifecycle.State.CREATED)
        core.deleteSubscription(subscriptionId)
        scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("editor-sheet").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText(text(R.string.error_not_found)).assertExists()
        compose.onNodeWithTag("subscriptions-list").performScrollToIndex(0)
        compose.onAllNodesWithText(text(R.string.mark_renewed))[0].performClick()
        compose.onNodeWithText("已顺延 1 期").assertExists()
        compose.onNodeWithTag("undo-banner").assertDoesNotExist()
        shot("14-renewal-notice")
    }
}
