package app.snowfold

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import androidx.activity.compose.setContent
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * 设计稿对照截图（.fullstack/impl/android/capture/）：
 * 每条用例把应用摆到与设计稿相同的状态再整屏截图，命名与 screens.txt 对齐。
 * 造数只用核心公开 API；主题/浅深由 captureTheme 注入。
 */
class DesignCompareTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val args get() = InstrumentationRegistry.getArguments()
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var core: SnowFoldCore

    @Before fun prepare() {
        core = SnowFoldCore.open(context.filesDir.absolutePath, app.snowfold.platform.AndroidSecureKeyStore(context))
        core.clearData()
        core.clearSyncConfig()
        core.saveTheme(if (args.getString("captureTheme") == "dark") ThemePreference.DARK else ThemePreference.LIGHT)
    }

    @After fun cleanup() {
        if (::scenario.isInitialized) scenario.close()
        core.clearSyncConfig()
        core.destroy()
    }

    private val prefix get() = args.getString("capturePrefix") ?: "cmp-light"

    private fun shot(name: String) {
        compose.waitForIdle()
        Espresso.closeSoftKeyboard()
        instrumentation.waitForIdleSync()
        // 屏幕采集等待一次 250ms 页面过渡与 SurfaceFlinger 显示提交。
        Thread.sleep(300)
        val image = instrumentation.uiAutomation.takeScreenshot()
        File(context.getExternalFilesDir(null)!!, "$prefix-$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }

    private fun nav(route: String) {
        compose.onNodeWithTag("nav-$route").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasTestTag("nav-$route") and isSelected()).fetchSemanticsNodes().isNotEmpty()
        }
        if (route != "assets" || core.data().assets.isNotEmpty()) compose.onNodeWithTag("$route-list").assertExists()
        compose.waitForIdle()
    }

    private fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("nav-overview").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun field(tag: String, text: String) {
        compose.onNodeWithTag(tag).performTextReplacement(text)
    }

    // ── 数据 ──

    /** 设计稿默认数据（对齐 01-overlight 稿：五分类 + 7 订阅 + 2 逾期）。 */
    private fun seedDefault(sync: Boolean = true) {
        core.clearData()
        val bank = core.createAsset("cash", "招商银行 储蓄卡", 8645032L)
        repeat(4) { index -> core.changeBalance(bank, if (index % 2 == 0) 8385681L else 8645032L) }
        core.createAsset("cash", "微信零钱", 132050L)
        core.createAsset("cash", "支付宝余额宝", 2380000L)
        core.createAsset("fixed", "自住房 · 浦东", 420000000L)
        core.createAsset("fixed", "家用车", 13500000L)
        core.createAsset("investment", "沪深 300 指数基金", 15628044L)
        core.createAsset("investment", "美股账户", 9823015L)
        core.createAsset("receivable", "借给小王", 2000000L)
        core.createAsset("debt", "房贷 · 工商银行", 215000000L)
        core.createAsset("debt", "信用卡 · 招行", 862390L)
        val today = LocalDate.now()
        val subs = listOf(
            Triple("iCloud+ 200GB", 2100L, today.minusDays(3)),
            Triple("健身房年卡", 288000L, today.minusDays(5)),
            Triple("网易云音乐", 1500L, today),
            Triple("Netflix", 1549L, today.plusDays(6)),
            Triple("宽带 · 电信", 12900L, today.plusDays(14)),
            Triple("ChatGPT Plus", 2000L, today.plusDays(19)),
            Triple("车险 · 平安", 468000L, today.plusDays(107)),
            Triple("腾讯视频", 3000L, today.plusDays(24)),
        )
        subs.forEachIndexed { index, (name, cents, date) ->
            core.createSubscription(SubscriptionInput(
                name = name, amountCents = cents,
                currency = if (name == "Netflix" || name == "ChatGPT Plus") "USD" else "CNY",
                cycle = if (name == "健身房年卡" || name == "车险 · 平安") "yearly" else "monthly",
                nextChargeOn = date.toString(), status = if (name == "腾讯视频") "paused" else "active"))
        }
        if (sync) configureCaptureSync()
    }

    private fun configureCaptureSync() {
        core.exportRecoveryCode()
        core.configureSync(app.snowfold.core.SyncConfig(app.snowfold.core.SyncBackend.S3,
            "https://127.0.0.1:1", "snowfold", "auto", "family", "sample-user", true), "test-only")
        core.setSyncPolicy(app.snowfold.core.SyncPolicy(false, 0u, false))
        core.setSyncEnabled(true)
    }

    private fun seedLong() {
        core.clearData()
        val codes = listOf("cash", "fixed", "investment", "receivable", "debt")
        codes.forEachIndexed { category, code ->
            repeat(12) { index ->
                val amount = when {
                    code == "debt" -> if (index == 0) 123456789L else 1000000L + index * 734521L
                    code == "cash" && index == 0 -> 999999999999L
                    else -> 1234567L * (index + 1) + category * 98765L
                }
                core.createAsset(code,
                    if (index == 0) "招商银行朝朝宝活期理财家庭应急备用金账户专用户头" else
                        listOf("流动资金", "固定资产", "投资理财", "应收款", "负债")[category] + (index + 1).toString().padStart(2, '0') + " " +
                            listOf("工资卡", "定期存单三年期", "账户", "专户（家庭共用）")[index % 4],
                    amount)
            }
        }
        val today = LocalDate.now()
        val names = listOf("iCloud+ 2TB 家庭共享方案（含 Apple TV+ 与音乐）", "Adobe Creative Cloud 全家桶", "Netflix 高级版", "Spotify 家庭", "网易云音乐黑胶 SVIP", "ChatGPT Plus", "Notion 团队版", "GitHub Copilot", "1Password 家庭", "腾讯视频 VIP")
        // 与主控 ui-redesign/shared/design/mock/data.js 的分组、日期保持一致。
        val groups = listOf(0, 0, 1, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 4, 4, 4, 4, 2, 1, 3)
        repeat(30) { index ->
            val dayOffset = when (groups[index]) {
                0 -> -3 - index
                4 -> 5
                3 -> index % 9 + 60
                2 -> index % 9 + 20
                else -> index % 9
            }
            core.createSubscription(SubscriptionInput(
                name = names[index % 10] + if (index >= 10) " ${index / 10 + 1}" else "",
                amountCents = if (index == 0) 99999999L else if (index % 3 == 1) 9999L + index * 100 else 2500L + index * 731,
                currency = if (index % 3 == 1) "USD" else "CNY",
                cycle = listOf("monthly", "yearly", "quarterly", "weekly")[index % 4],
                nextChargeOn = today.plusDays(dayOffset.toLong()).toString(),
                status = if (index in 23..26) "paused" else "active"))
        }
        org.junit.Assert.assertEquals(60, core.data().assets.size)
        org.junit.Assert.assertEquals(30, core.data().subscriptions.size)
        configureCaptureSync()
    }

    private fun seedNegative() {
        seedDefault()
        core.createAsset("debt", "经营贷 · 建设银行", 379702540L)
    }

    // ── 用例 ──

    @Test fun capture01_overview() {
        seedDefault(); launch()
        if (context.resources.configuration.screenWidthDp >= 600) {
            // 平板工具栏的添加按钮必须能进入表单；副标题保留周期与日期。
            compose.onNodeWithTag("overview-toolbar-add").assertIsDisplayed().performClick()
            compose.onNodeWithTag("asset-name").assertIsDisplayed()
            compose.onNodeWithTag("editor-cancel").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("editor-sheet").fetchSemanticsNodes().isEmpty() }
            val gym = core.data().subscriptions.first { it.name == "健身房年卡" }
            val date = LocalDate.parse(gym.nextChargeOn)
            compose.onNodeWithText(context.getString(R.string.cycle_yearly) + " · " +
                context.getString(R.string.date_short, date.monthValue, date.dayOfMonth)).assertExists()
        }
        shot("01-overview")
    }

    @Test fun capture02_overview_empty() { launch(); shot("01-overview-empty") }

    @Test fun capture03_overview_long() { seedLong(); launch(); shot("01-overview-long") }

    @Test fun capture04_overview_negative() { seedNegative(); launch(); shot("01-overview-negative") }

    @Test fun capture05_assets() { seedDefault(); launch(); nav("assets"); shot("02-assets") }

    @Test fun capture06_assets_empty() { launch(); nav("assets"); shot("02-assets-empty") }

    @Test fun capture07_assets_long() { seedLong(); launch(); nav("assets"); shot("02-assets-long") }

    @Test fun capture08_asset_detail() {
        seedDefault(); launch(); nav("assets")
        val first = core.data().assets.first { it.categoryCode == "cash" }
        compose.onNodeWithTag("assets-list").performScrollToNode(hasTestTag("asset-${first.id}"))
        compose.onNodeWithTag("asset-${first.id}").performClick()
        if (context.resources.configuration.fontScale <= 1.3f) {
            val entry = core.data().history.first { it.assetId == first.id }
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            compose.onNodeWithTag("history-time-${entry.id}").performSemanticsAction(
                androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult,
            ) { it(layouts) }
            org.junit.Assert.assertEquals("默认字号的日期与时间应完整显示为一行", 1, layouts.single().lineCount)
            org.junit.Assert.assertFalse(layouts.single().hasVisualOverflow)
        }
        shot("02-asset-detail-history")
    }

    @Test fun capture09_add_asset() {
        seedDefault(); launch(); nav("assets")
        compose.onNodeWithTag("assets-add").performClick()
        field("asset-name", "招商银行 储蓄卡")
        field("asset-amount", "86450.32")
        shot("03-add-asset")
        field("asset-amount", "1..2")
        compose.onNodeWithTag("editor-save").performClick()
        compose.waitForIdle()
        shot("03-add-asset-error")
    }

    @Test fun capture10_adjust() {
        seedDefault(); launch(); nav("assets")
        val first = core.data().assets.first { it.categoryCode == "cash" }
        compose.onNodeWithTag("assets-list").performScrollToNode(hasTestTag("asset-${first.id}"))
        compose.onNodeWithTag("asset-${first.id}").performClick()
        compose.onNodeWithTag("asset-adjust").performClick()
        compose.onNodeWithTag("editor-sheet").assertIsDisplayed()
        field("adjust-amount", "90000.00")
        shot("03-adjust")
        field("adjust-amount", "1..2")
        compose.onNodeWithTag("editor-save").performClick()
        compose.waitForIdle()
        shot("03-adjust-error")
    }

    @Test fun capture11_correct() {
        seedDefault(); launch(); nav("assets")
        val first = core.data().assets.first { it.categoryCode == "cash" }
        compose.onNodeWithTag("assets-list").performScrollToNode(hasTestTag("asset-${first.id}"))
        compose.onNodeWithTag("asset-${first.id}").performClick()
        val hid = core.data().history.last { it.assetId == first.id }.id
        compose.onAllNodes(hasTestTag("history-correct-$hid"), useUnmergedTree = true)[0].performClick()
        compose.onNodeWithTag("editor-sheet").assertIsDisplayed()
        field("correct-amount", "80000.00")
        shot("03-correct")
        field("correct-amount", "1..2")
        compose.onNodeWithTag("editor-save").performClick()
        compose.waitForIdle()
        shot("03-correct-error")
    }

    @Test fun capture12_subscriptions() { seedDefault(); launch(); nav("subscriptions"); shot("04-subscriptions") }

    @Test fun capture13_subscriptions_empty() { launch(); nav("subscriptions"); shot("04-subscriptions-empty") }

    @Test fun capture14_subscriptions_long() { seedLong(); launch(); nav("subscriptions"); shot("04-subscriptions-long") }

    @Test fun capture15_subscriptions_swipe() {
        seedDefault(); launch(); nav("subscriptions")
        val target = core.data().subscriptions.first { it.status == "active" }
        compose.onNodeWithTag("subscriptions-list").performScrollToNode(hasTestTag("subscription-${target.id}"))
        compose.onNodeWithTag("subscription-${target.id}").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        shot("04-subscriptions-swipe")
    }

    @Test fun capture16_sub_new() {
        seedDefault(); launch(); nav("subscriptions")
        compose.onNodeWithTag("subscriptions-add").performClick()
        field("sub-name", "Netflix")
        field("sub-amount", "15.49")
        Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("sub-currency").performScrollTo().onChildAt(1).performClick()
        shot("05-sub-new")
        // 输入法完成动作验证缺失日期；与设计稿的日期错误状态一致。
        compose.onNodeWithTag("sub-amount").performImeAction()
        compose.onNodeWithText(context.getString(R.string.choose_next_charge_date)).assertExists()
        shot("05-sub-new-error")
        compose.onNodeWithTag("sub-date").performScrollTo().performClick()
        compose.onNodeWithText("20").performScrollTo().performClick()
        compose.onNodeWithTag("calendar-apply").performScrollTo().performClick()
        shot("05-sub-new-date")
    }

    @Test fun capture17_sub_edit() {
        seedDefault(); launch(); nav("subscriptions")
        val target = core.data().subscriptions.first { it.name == "Netflix" }
        compose.onNodeWithTag("subscriptions-list").performScrollToNode(hasTestTag("subscription-${target.id}"))
        compose.onNodeWithTag("subscription-${target.id}").performClick()
        shot("05-sub-edit")
    }

    @Test fun capture18_settings() {
        seedDefault()
        core.exportRecoveryCode()
        core.configureSync(app.snowfold.core.SyncConfig(
            app.snowfold.core.SyncBackend.S3, "https://127.0.0.1:1", "snowfold", "us-east-1", "family", "sample-user", true),
            "test-credential")
        core.setSyncPolicy(app.snowfold.core.SyncPolicy(false, 0u, false))
        core.setSyncEnabled(true)
        launch(); nav("settings")
        compose.onAllNodesWithText(context.getString(R.string.sync_help), useUnmergedTree = true).assertCountEquals(1)
        compose.onAllNodesWithText(context.getString(R.string.sync_interval_help), useUnmergedTree = true).assertCountEquals(1)
        shot("06-settings-full-sync-on")
    }

    @Test fun capture19_confirm_clear() {
        seedDefault(); launch(); nav("settings")
        compose.onNodeWithTag("settings-list").performScrollToNode(hasText(context.getString(app.snowfold.R.string.clear_all_title)))
        compose.onNodeWithText(context.getString(app.snowfold.R.string.clear_all_title)).performClick()
        compose.waitForIdle()
        shot("07-confirm-clear")
    }

    @Test fun capture20_toast_undo() {
        seedDefault(); launch(); nav("assets")
        val first = core.data().assets.first { it.categoryCode == "cash" }
        compose.onNodeWithTag("assets-list").performScrollToNode(hasTestTag("asset-${first.id}"))
        compose.onNodeWithTag("asset-${first.id}").performTouchInput { swipeLeft() }
        compose.waitUntil(5_000) { core.data().assets.none { it.id == first.id } }
        shot("08-toast-undo")
    }

    @Test fun capture21_toast_notice() {
        seedDefault(); launch(); nav("subscriptions")
        val overdue = core.data().subscriptions.filter { it.status == "active" && it.nextChargeOn < LocalDate.now().toString() }
        val target = overdue.firstOrNull() ?: core.data().subscriptions.first()
        val renew = compose.onNodeWithTag("renew-${target.id}")
        renew.performScrollTo()
        if (context.resources.configuration.screenWidthDp < 600) {
            val button = renew.fetchSemanticsNode().boundsInRoot
            val tabs = compose.onNodeWithTag("nav-subscriptions").fetchSemanticsNode().boundsInRoot
            val covered = button.bottom - tabs.top + 16 * context.resources.displayMetrics.density
            if (covered > 0f) compose.onNodeWithTag("subscriptions-list").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) { it(0f, covered) }
        }
        renew.performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("已顺延", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        shot("08-toast-notice")
    }
    private fun vm(): MainViewModel {
        lateinit var result: MainViewModel
        scenario.onActivity { result = androidx.lifecycle.ViewModelProvider(it)[MainViewModel::class.java] }
        return result
    }

    @Test fun capture22_settings_off() {
        seedDefault(sync = false); core.exportRecoveryCode(); launch(); nav("settings")
        compose.onAllNodesWithText(context.getString(R.string.sync_help), useUnmergedTree = true).assertCountEquals(1)
        compose.onNodeWithTag("sync-backend").assertExists()
        val backend = compose.onNodeWithTag("sync-backend").getUnclippedBoundsInRoot()
        val endpoint = compose.onNodeWithTag("sync-endpoint").getUnclippedBoundsInRoot()
        org.junit.Assert.assertTrue("存储类型应位于服务地址之前", backend.top < endpoint.top)
        shot("06-settings")
        compose.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("sync-section"))
        shot("06-settings-full-sync-off")
        compose.onNodeWithTag("sync-save").performScrollTo()
        if (context.resources.configuration.screenWidthDp < 600) {
            val save = compose.onNodeWithTag("sync-save").fetchSemanticsNode().boundsInRoot
            val tabs = compose.onNodeWithTag("nav-settings").fetchSemanticsNode().boundsInRoot
            val covered = save.bottom - tabs.top + 16 * context.resources.displayMetrics.density
            if (covered > 0f) compose.onNodeWithTag("settings-list").performSemanticsAction(
                androidx.compose.ui.semantics.SemanticsActions.ScrollBy,
            ) { it(0f, covered) }
        }
        shot("06-settings-full-sync-off-bottom")
    }

    @Test fun capture23_confirm_import() {
        seedDefault(); val json = core.exportJson(); launch(); nav("settings")
        val model = vm(); scenario.onActivity { model.prepareImportJson(json) }
        compose.waitUntil(5_000) { compose.onAllNodesWithText(context.getString(R.string.confirm_overwrite)).fetchSemanticsNodes().isNotEmpty() }
        shot("07-confirm-import")
    }

    @Test fun capture24_confirm_sealed() {
        seedDefault(); val bytes = core.exportSealed(); launch(); nav("settings")
        val model = vm(); scenario.onActivity { model.prepareImportSealed(bytes) }
        compose.waitUntil(5_000) { compose.onAllNodesWithText(context.getString(R.string.confirm_overwrite)).fetchSemanticsNodes().isNotEmpty() }
        shot("07-confirm-sealed")
    }

    @Test fun capture25_confirm_replacekey() {
        seedDefault()
        val temporary = File(context.cacheDir, "replace-key-fixture").apply { mkdirs() }
        val other = SnowFoldCore.open(temporary.absolutePath, object : app.snowfold.core.SecureKeyStore {
            private var key: ByteArray? = null
            override fun load() = key
            override fun store(value: ByteArray) { key = value }
            override fun delete() { key = null }
        })
        val recovery = other.exportRecoveryCode(); other.destroy(); temporary.deleteRecursively()
        launch(); nav("settings")
        val model = vm(); scenario.onActivity { model.prepareReplaceKey(recovery) }
        compose.waitUntil(5_000) { compose.onAllNodesWithText(context.getString(R.string.replace_key_confirmation_title)).fetchSemanticsNodes().isNotEmpty() }
        shot("07-confirm-replacekey")
    }

    @Test fun capture26_notice_error() {
        seedDefault(); launch()
        val model = vm(); scenario.onActivity { model.notifyImportReadFailed() }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("notice-banner").fetchSemanticsNodes().isNotEmpty() }
        shot("08-toast-error")
    }

    @Test fun capture27_persistence() {
        seedDefault(); launch()
        val before = core.exportJson()
        scenario.close(); scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("overview-net").fetchSemanticsNodes().isNotEmpty() }
        org.junit.Assert.assertEquals(before, core.exportJson())
        shot("restart-persisted")
    }

    @Test fun capture28_error_startup() {
        launch()
        scenario.onActivity { activity ->
            val model = MainViewModel(androidx.lifecycle.SavedStateHandle(), object : app.snowfold.data.CoreAccess {
                override suspend fun <T> call(block: (app.snowfold.data.LedgerGateway) -> T): T =
                    throw app.snowfold.core.CoreException.IncompatibleDatabase()
            })
            activity.setContent {
                app.snowfold.ui.theme.SnowFoldTheme(darkTheme = args.getString("captureTheme") == "dark") {
                    app.snowfold.ui.nav.AppRoot(model)
                }
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithText(context.getString(R.string.startup_error_title)).fetchSemanticsNodes().isNotEmpty() }
        if (context.resources.configuration.screenWidthDp >= 600) {
            listOf("overview", "assets", "subscriptions", "settings").forEach {
                compose.onNodeWithTag("nav-$it").assertIsNotEnabled()
            }
        } else {
            compose.onNodeWithTag("nav-overview").assertDoesNotExist()
        }
        shot("09-error-startup")
    }

    @Test fun capture29_error_key() {
        seedDefault(); launch()
        scenario.onActivity { activity ->
            val gateway = app.snowfold.data.RustLedgerGateway(core)
            val keyMissing = object : app.snowfold.data.LedgerGateway by gateway {
                override fun keyFingerprint(): String? = throw app.snowfold.core.CoreException.KeyUnavailable()
            }
            val model = MainViewModel(androidx.lifecycle.SavedStateHandle(), object : app.snowfold.data.CoreAccess {
                override suspend fun <T> call(block: (app.snowfold.data.LedgerGateway) -> T): T = block(keyMissing)
            })
            activity.setContent {
                app.snowfold.ui.theme.SnowFoldTheme(darkTheme = args.getString("captureTheme") == "dark") {
                    app.snowfold.ui.nav.AppRoot(model)
                }
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("key-card").fetchSemanticsNodes().isNotEmpty() }
        if (context.resources.configuration.fontScale <= 1.3f) {
            val title = compose.onNodeWithText(context.getString(R.string.key_unavailable_title)).fetchSemanticsNode().boundsInRoot
            val action = compose.onNodeWithTag("key-import").fetchSemanticsNode().boundsInRoot
            org.junit.Assert.assertTrue("导入按钮在说明右侧并与标题顶对齐", action.left >= title.right && action.top <= title.bottom)
        }
        shot("09-error-key")
    }

    @Test fun capture30_sync_states() {
        seedDefault(); core.exportRecoveryCode()
        core.configureSync(app.snowfold.core.SyncConfig(app.snowfold.core.SyncBackend.S3,
            "https://127.0.0.1:1", "snowfold", "auto", "", "test", true), "test-only")
        core.setSyncPolicy(app.snowfold.core.SyncPolicy(false, 0u, false)); core.setSyncEnabled(true)
        launch()
        val release = java.util.concurrent.CountDownLatch(1)
        try {
            scenario.onActivity { activity ->
                val gateway = app.snowfold.data.RustLedgerGateway(core)
                val model = MainViewModel(androidx.lifecycle.SavedStateHandle(), object : app.snowfold.data.CoreAccess {
                    override suspend fun <T> call(block: (app.snowfold.data.LedgerGateway) -> T): T =
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { block(gateway) }
                }, syncTransport = object : app.snowfold.core.SyncTransport {
                    override fun send(request: app.snowfold.core.SyncRequest): app.snowfold.core.SyncResponse {
                        release.await(10, java.util.concurrent.TimeUnit.SECONDS)
                        return app.snowfold.core.SyncResponse(500u, emptyList(), byteArrayOf())
                    }
                })
                activity.setContent {
                    app.snowfold.ui.theme.SnowFoldTheme(darkTheme = args.getString("captureTheme") == "dark") {
                        app.snowfold.ui.nav.AppRoot(model)
                    }
                }
            }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("sync-button").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("sync-button").performClick()
            compose.onNodeWithTag("sync-button").assertIsNotEnabled()
            shot("08-sync-syncing")
            release.countDown()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("sync-failed-dot", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            if (compose.onAllNodesWithContentDescription(context.getString(R.string.close)).fetchSemanticsNodes().isNotEmpty())
                compose.onNodeWithContentDescription(context.getString(R.string.close)).performClick()
            compose.onNodeWithTag("sync-failed-dot", useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithContentDescription(context.getString(R.string.sync_button_failed_a11y)).assertExists()
            shot("08-sync-failed")
            val dot = compose.onNodeWithTag("sync-failed-dot", useUnmergedTree = true).fetchSemanticsNode().boundsInWindow
            val pixels = instrumentation.uiAutomation.takeScreenshot()
            val center = pixels.getPixel(dot.center.x.toInt(), dot.center.y.toInt())
            pixels.recycle()
            val expected = if (args.getString("captureTheme") == "dark") 0xFFFF6A5E.toInt() else 0xFFBF3228.toInt()
            org.junit.Assert.assertEquals("失败红点中心像素应为 neg 色", expected, center)
        } finally { release.countDown() }
    }

}
