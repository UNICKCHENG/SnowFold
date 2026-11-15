package app.snowfold

import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Process
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import app.snowfold.core.SnowFoldCore
import app.snowfold.core.ThemePreference
import app.snowfold.platform.AndroidSecureKeyStore
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/**
 * 正式包进程重启证据：同签名的测试包分别运行 seed / verify，两次之间由 adb
 * force-stop、冷启动、再 force-stop。使用 Compose 事件与核心公开导出，不依赖
 * uiautomator dump。日常 debug 套件不运行本用例，脚本需显式传 releasePhase。
 */
class ReleasePersistenceTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun persistenceAcrossAppProcesses() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val phase = InstrumentationRegistry.getArguments().getString("releasePhase")
        assumeTrue("仅由正式包验收脚本显式运行", phase == "seed" || phase == "verify")
        assertEquals("必须验证未启用调试的正式包", 0, context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE)
        val directory = context.getExternalFilesDir(null)!!
        val beforeFile = File(directory, "release-proof-before.json")
        val name = "正式包 Restart 123"
        SnowFoldCore.open(context.filesDir.absolutePath, AndroidSecureKeyStore(context)).use { core ->
            if (phase == "seed") {
                core.clearData()
                core.clearSyncConfig()
                core.saveTheme(ThemePreference.LIGHT)
            } else {
                assertTrue("重启前的核心导出必须存在", beforeFile.exists())
                assertEquals("重启后的核心导出必须逐字节相同", beforeFile.readText(), core.exportJson())
                assertEquals(ThemePreference.DARK, core.theme())
            }
            ActivityScenario.launch(MainActivity::class.java).use {
                compose.waitUntil(20_000) { compose.onAllNodesWithTag("nav-overview").fetchSemanticsNodes().isNotEmpty() }
                if (phase == "seed") {
                    compose.onNodeWithTag("overview-add").performClick()
                    compose.onNodeWithTag("asset-name").performTextReplacement(name)
                    compose.onNodeWithTag("asset-amount").performTextReplacement("42.50")
                    compose.onNodeWithTag("editor-save").performClick()
                    compose.waitUntil(10_000) {
                        core.data().assets.any { asset -> asset.name == name && asset.amountCents == 4250L } &&
                            compose.onAllNodesWithTag("editor-sheet").fetchSemanticsNodes().isEmpty()
                    }
                    compose.onNodeWithTag("nav-settings").performClick()
                    compose.onNodeWithText(context.getString(R.string.theme_dark)).performClick()
                    compose.waitUntil(5_000) { core.theme() == ThemePreference.DARK }
                }
                compose.onNodeWithTag("nav-assets").performClick()
                compose.onNodeWithText(name).assertIsDisplayed()
                val asset = core.data().assets.single()
                assertEquals(name, asset.name)
                assertEquals(4250L, asset.amountCents)
                val exported = core.exportJson()
                if (phase == "seed") beforeFile.writeText(exported)
                else {
                    assertEquals(beforeFile.readText(), exported)
                    File(directory, "release-proof-after.json").writeText(exported)
                }
                compose.waitForIdle()
                instrumentation.waitForIdleSync()
                Thread.sleep(300)
                val image = instrumentation.uiAutomation.takeScreenshot()
                File(directory, "release-proof-$phase.png").outputStream().use { output ->
                    image.compress(Bitmap.CompressFormat.PNG, 100, output)
                }
                image.recycle()
                val digest = MessageDigest.getInstance("SHA-256").digest(exported.toByteArray()).joinToString("") { byte -> "%02x".format(byte) }
                instrumentation.sendStatus(0, Bundle().apply {
                    putString("stream", "RELEASE_PROOF phase=$phase debuggable=false pid=${Process.myPid()} export_sha256=$digest asset=${asset.id} amount_cents=${asset.amountCents} theme=${core.theme()}\n")
                })
            }
        }
    }
}
