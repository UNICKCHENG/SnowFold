package app.snowfold

import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.testTag
import app.snowfold.ui.components.glassPanel
import app.snowfold.ui.components.glassSource
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import app.snowfold.core.*
import app.snowfold.platform.AndroidSecureKeyStore
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** 核对真实页面的选中态、开关状态、唯一按钮名与扩展触控区。 */
class AccessibilityDeliveryTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var core: SnowFoldCore
    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before fun prepare() {
        core = SnowFoldCore.open(context.filesDir.absolutePath, AndroidSecureKeyStore(context))
        core.clearData(); core.clearSyncConfig()
        core.saveTheme(ThemePreference.LIGHT)
        core.createAsset("cash", "中文账户 Inter 123", 123456L)
        core.createSubscription(SubscriptionInput("中文服务 Inter 123", 2100L, "CNY", "monthly", java.time.LocalDate.now().plusDays(1).toString(), "active"))
        core.exportRecoveryCode()
        core.configureSync(SyncConfig(SyncBackend.S3, "https://127.0.0.1:1", "test", "auto", "", "test", true), "test-only")
        core.setSyncPolicy(SyncPolicy(false, 0u, false)); core.setSyncEnabled(true)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("nav-overview").fetchSemanticsNodes().isNotEmpty() }
    }

    @After fun cleanup() {
        scenario.close(); core.clearSyncConfig(); core.destroy()
    }

    @Test fun selectedStatesAndControlsExposeTheirRealTouchBounds() {
        compose.onNodeWithTag("nav-overview").assertIsSelected()
        compose.onNodeWithTag("nav-assets").performClick().assertIsSelected()
        compose.onNodeWithTag("nav-overview").assertIsNotSelected()
        compose.onNodeWithTag("assets-add").assertTouchWidthIsEqualTo(48.dp).assertTouchHeightIsEqualTo(48.dp)
        compose.onNodeWithTag("assets-add").performClick()
        compose.onNodeWithTag("editor-save").assertIsNotEnabled()
        compose.onNodeWithTag("editor-cancel").assertTouchWidthIsEqualTo(48.dp).assertTouchHeightIsEqualTo(48.dp)
        compose.onNodeWithTag("editor-cancel").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("editor-sheet").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("nav-settings").performClick()
        compose.onNodeWithText(context.getString(R.string.theme_light)).assertIsSelected().assertTouchHeightIsEqualTo(48.dp)
        compose.onNodeWithTag("sync-enable").performScrollTo().assertIsOn().assertTouchHeightIsEqualTo(48.dp)
        compose.onNodeWithTag("sync-enable").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        compose.waitUntil(5_000) { !core.syncStatus().enabled }
        compose.onNodeWithTag("sync-enable").assertIsOff()
    }

    @Test fun metricHelpNamesAreUniqueAndFingerprintHasCopyAction() {
        compose.onNodeWithTag("nav-subscriptions").performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.new_subscription_title)).assertExists()
        compose.onNodeWithContentDescription(context.getString(R.string.cd_monthly_info)).assertExists().assertTouchHeightIsEqualTo(48.dp)
        compose.onNodeWithContentDescription(context.getString(R.string.cd_annual_info)).assertExists()
        compose.onNodeWithTag("nav-settings").performClick()
        compose.onNodeWithTag("key-fingerprint").performScrollTo().assert(
            SemanticsMatcher("提供指纹复制读屏动作") {
                it.config.getOrElse(androidx.compose.ui.semantics.SemanticsActions.CustomActions) { emptyList() }
                    .any { action -> action.label == context.getString(R.string.cd_copy) }
            },
        )
    }

    @Test fun rowActionsWorkWithoutSwipeOrLongPress() {
        val assetId = core.data().assets.first().id
        compose.onNodeWithTag("nav-assets").performClick()
        val assetActions = compose.onNodeWithTag("asset-$assetId").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.CustomActions]
        compose.runOnIdle {
            org.junit.Assert.assertTrue(assetActions.first { it.label == context.getString(R.string.delete) }.action())
        }
        compose.waitUntil(5_000) { core.data().assets.none { it.id == assetId } }
        compose.onNodeWithText(context.getString(R.string.undo)).performClick()
        compose.waitUntil(5_000) { core.data().assets.any { it.id == assetId } }
        compose.onNodeWithTag("nav-subscriptions").performClick()
        val id = core.data().subscriptions.first().id
        val subscriptionActions = compose.onNodeWithTag("subscription-$id").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.CustomActions]
        compose.runOnIdle {
            org.junit.Assert.assertFalse(subscriptionActions.any { it.label == context.getString(R.string.mark_renewed) })
            org.junit.Assert.assertTrue(subscriptionActions.first { it.label == context.getString(R.string.pause) }.action())
        }
        compose.waitUntil(5_000) { core.data().subscriptions.first { it.id == id }.status == "paused" }
    }

    @Test fun errorNoticeRemainsUntilDismissed() {
        scenario.onActivity { androidx.lifecycle.ViewModelProvider(it)[MainViewModel::class.java].notifyImportReadFailed() }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("notice-banner").fetchSemanticsNodes().isNotEmpty() }
        Thread.sleep(4_200)
        compose.onNodeWithTag("notice-banner").assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.close)).performClick()
        compose.onNodeWithTag("notice-banner").assertDoesNotExist()
    }

    @Test fun glassReallyBlursThePixelsBehindIt() {
        org.junit.Assume.assumeTrue(android.os.Build.VERSION.SDK_INT >= 31)
        scenario.onActivity { activity ->
            activity.setContent {
                app.snowfold.ui.theme.SnowFoldTheme {
                    val source = app.snowfold.ui.components.rememberGlassSource()
                    var offset by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(androidx.compose.ui.unit.IntOffset.Zero) }
                    Box(Modifier.fillMaxSize()) {
                        Canvas(Modifier.fillMaxSize().glassSource(source)) {
                            val stripe = 4.dp.toPx()
                            for (index in 0..(size.width / stripe).toInt()) {
                                drawRect(if (index % 2 == 0) androidx.compose.ui.graphics.Color.Black else androidx.compose.ui.graphics.Color.White,
                                    topLeft = androidx.compose.ui.geometry.Offset(index * stripe, 0f),
                                    size = androidx.compose.ui.geometry.Size(stripe, size.height))
                            }
                        }
                        Box(Modifier.align(Alignment.Center).size(300.dp, 64.dp)
                            .onGloballyPositioned { offset = androidx.compose.ui.unit.IntOffset(it.positionInRoot().x.toInt(), it.positionInRoot().y.toInt()) }
                            .glassPanel(source, androidx.compose.foundation.shape.RoundedCornerShape(32.dp), offset, 32.dp)
                            .testTag("glass-proof"))
                    }
                }
            }
        }
        compose.waitForIdle()
        Thread.sleep(300)
        val bounds = compose.onNodeWithTag("glass-proof").fetchSemanticsNode().boundsInWindow
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val from = bounds.left.toInt() + bounds.width.toInt() / 4
        val until = bounds.right.toInt() - bounds.width.toInt() / 4
        val blurred = (from until until).map { android.graphics.Color.red(bitmap.getPixel(it, bounds.center.y.toInt())) }
        val original = (from until until).map { android.graphics.Color.red(bitmap.getPixel(it, bounds.top.toInt() - 30)) }
        val blurRange = blurred.max() - blurred.min()
        val originalRange = original.max() - original.min()
        InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply {
            putString("stream", "毛玻璃像素：原始极差=$originalRange，模糊后=$blurRange（要求 <12）\n")
        })
        java.io.File(context.getExternalFilesDir(null), "glass-effect-proof.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
        org.junit.Assert.assertTrue("背景条纹的原始对比应清晰：$originalRange", originalRange > 200)
        org.junit.Assert.assertTrue("真模糊应消除条纹，单通道极差为 $blurRange", blurRange < 12)
    }
}
