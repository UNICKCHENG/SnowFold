package app.snowfold

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.onChild
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import app.snowfold.core.formatMoney
import app.snowfold.ui.components.AmountText
import app.snowfold.ui.model.*
import app.snowfold.ui.overview.OverviewScreen
import app.snowfold.ui.theme.SnowFoldTheme
import app.snowfold.ui.theme.SnowFoldType
import java.io.File
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** 守住金额最后一位：检查真实 Android 排版结果，不只比 semantics 原文。 */
class AmountLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun fullAmountsFitAt360dpAnd200Percent() {
        val amounts = listOf("¥18,976,847.70", "¥12,345,678.90", "−¥12,345,678.90")
        compose.setContent {
            SnowFoldTheme {
                Column {
                    listOf(1f, 2f).forEach { scale ->
                        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale)) {
                            amounts.forEachIndexed { index, amount ->
                                AmountText(amount, SnowFoldType.hero,
                                    Modifier.width(296.dp).testTag("amount-$scale-$index"), decimalHierarchy = true)
                            }
                        }
                    }
                }
            }
        }
        listOf(1f, 2f).forEach { scale ->
            amounts.forEachIndexed { index, amount ->
                val results = mutableListOf<TextLayoutResult>()
                compose.onNodeWithTag("amount-$scale-$index").onChild().performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
                val layout = results.single()
                assertEquals(amount, layout.layoutInput.text.text.replace("\n", ""))
                assertFalse("amount clipped at $scale: $amount", layout.hasVisualOverflow)
                assertEquals(layout.layoutInput.text.length, layout.getLineEnd(layout.lineCount - 1))
                val last = layout.getBoundingBox(layout.layoutInput.text.lastIndex)
                assertTrue(last.right <= layout.size.width + 1)
            }
        }
    }

    @Test fun longestOverviewAt360dp() = overviewScreenshot(1f, "revision-longest-360.png")
    @Test fun longestOverviewAt200Percent() = overviewScreenshot(2f, "revision-longest-font200.png")

    /** 仅测试进程使用的核心写入样例，应用没有演示数据入口。 */
    @Test fun seedScreenshotLedger() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val core = app.snowfold.core.SnowFoldCore.open(context.filesDir.absolutePath)
        try {
            if (core.data().assets.isNotEmpty()) return
            core.createAsset("cash", "招商银行朝朝宝（活期理财）家庭备用金账户", 1234567890L)
            (1..30).forEach { index ->
                core.createAsset(listOf("cash", "fixed", "investment", "receivable", "debt")[index % 5],
                    listOf("工商银行工资卡", "家庭储蓄", "长期理财", "备用金", "住房贷款")[index % 5] + index,
                    8234512L)
            }
            val today = LocalDate.now()
            (0..11).forEach { index ->
                core.createSubscription(app.snowfold.core.SubscriptionInput(
                    name = listOf("Apple Music 家庭订阅", "Netflix 标准套餐", "GitHub Copilot", "iCloud+ 200G", "网易云黑胶 VIP", "健身私教课包月")[index % 6],
                    amountCents = if (index == 1) 1580L else if (index == 2) 1000L else 2100L,
                    currency = if (index == 1 || index == 2) "USD" else "CNY",
                    cycle = if (index % 4 == 0) "yearly" else "monthly",
                    nextChargeOn = today.plusDays((index - 3).toLong()).toString(),
                    status = if (index == 11) "paused" else "active",
                ))
            }
        } finally { core.destroy() }
    }

    private fun overviewScreenshot(scale: Float, filename: String) {
        val amount = formatMoney(1234567890L, "CNY")
        val ui = UiLedger(LocalDate.of(2026, 11, 14), emptyList(), emptyList(), emptyList(), 0,
            emptyList(), emptyList(), HeroUi(amount, false, amount, formatMoney(0L, "CNY"), "0.0%"),
            null, SubscriptionSummary.None, null, emptyList(), false)
        compose.setContent {
            SnowFoldTheme {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale)) {
                    OverviewScreen(ui, null, null, {}, {}, {}, expanded = false, modifier = Modifier.width(360.dp))
                }
            }
        }
        val results = mutableListOf<TextLayoutResult>()
        compose.onAllNodesWithText(amount.substringBefore("."), substring = true, useUnmergedTree = true)[0].performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, filename)
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val layout = results.single()
        assertFalse("${layout.layoutInput.text.text}, size=${layout.size}, lines=${layout.lineCount}, height=${layout.didOverflowHeight}, width=${layout.didOverflowWidth}, textHeight=${layout.multiParagraph.height}", layout.hasVisualOverflow)
    }
}
