package app.snowfold

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import app.snowfold.core.*
import app.snowfold.data.SerializedCoreAccess
import app.snowfold.platform.AndroidSecureKeyStore
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** 原生绑定 + 内存条件传输；不启动远端服务、不伪造核心合并规则。 */
class SyncDeliveryTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val args get() = InstrumentationRegistry.getArguments()

    private class MemoryTransport : SyncTransport {
        var bytes: ByteArray? = null
        var version = 0
        var conflictOnce = false
        var conflicts = 0
        var onPut: (() -> Unit)? = null
        val methods = mutableListOf<String>()
        override fun send(request: SyncRequest): SyncResponse {
            methods.add(request.method)
            fun response(code: Int, body: ByteArray = ByteArray(0)) = SyncResponse(code.toUShort(),
                if (bytes != null) listOf(SyncHeader("ETag", "\"$version\"")) else emptyList(), body)
            return when (request.method) {
                "MKCOL" -> response(201)
                "GET" -> bytes?.let { response(200, it) } ?: response(404)
                "PUT" -> {
                    onPut?.invoke(); onPut = null
                    if (conflictOnce) {
                        conflictOnce = false; conflicts++; response(412)
                    } else {
                        val condition = request.headers.first { it.name.equals(if (bytes == null) "If-None-Match" else "If-Match", true) }.value
                        assertEquals(if (bytes == null) "*" else "\"$version\"", condition)
                        bytes = request.body.copyOf(); version++; response(200)
                    }
                }
                else -> error("非契约请求方法")
            }
        }
    }

    private fun config(backend: SyncBackend) = SyncConfig(backend, "https://storage.example.com/dav/",
        if (backend == SyncBackend.S3) "snowfold" else "", if (backend == SyncBackend.S3) "us-east-1" else "",
        "family", "sample-user", backend == SyncBackend.S3)

    @Test fun transportSendsWebDavMkcolOverLoopback() {
        java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { server ->
            val method = java.util.concurrent.atomic.AtomicReference<String>()
            val worker = kotlin.concurrent.thread(isDaemon = true) {
                server.accept().use { client ->
                    val reader = client.getInputStream().bufferedReader()
                    method.set(reader.readLine().substringBefore(' '))
                    while (!reader.readLine().isNullOrEmpty()) { }
                    client.getOutputStream().write("HTTP/1.1 201 Created\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                }
            }
            val response = app.snowfold.platform.AndroidSyncTransport().send(
                SyncRequest("MKCOL", "http://127.0.0.1:${server.localPort}/dav/", emptyList(), ByteArray(0)))
            worker.join(2000)
            assertEquals(201, response.status.toInt())
            assertEquals("MKCOL", method.get())
        }
    }

    @Test fun s3AndWebDavUseGeneratedCallbackAndRetryConditionalConflict() {
        for (backend in listOf(SyncBackend.S3, SyncBackend.WEB_DAV)) {
            val root = File(context.cacheDir, "sync-${System.nanoTime()}").apply { mkdirs() }
            val store = AndroidSecureKeyStore(context)
            SnowFoldCore.open(File(root, "a").absolutePath, store).use { a ->
                SnowFoldCore.open(File(root, "b").absolutePath, store).use { b ->
                    val code = a.exportRecoveryCode()
                    b.importRecoveryCode(code, b.keyFingerprint()); b.exportRecoveryCode()
                    a.configureSync(config(backend), "test-credential"); a.setSyncEnabled(true)
                    b.configureSync(config(backend), "test-credential"); b.setSyncEnabled(true)
                    a.createAsset("cash", "同步资产", 12300)
                    val transport = MemoryTransport()
                    a.syncNow(transport)
                    transport.conflictOnce = true
                    val result = b.syncNow(transport)
                    assertEquals(1u, result.retries)
                    assertEquals(1, transport.conflicts)
                    assertEquals("同步资产", b.data().assets.single().name)
                    assertFalse(String(transport.bytes!!).contains("同步资产"))
                    if (backend == SyncBackend.WEB_DAV) assertTrue(transport.methods.contains("MKCOL"))
                }
            }
            root.deleteRecursively()
        }
    }

    @Test fun syncWorkerAllowsLocalWriteDuringNetworkAndReportsPendingChanges() = runBlocking {
        val root = File(context.cacheDir, "sync-concurrent-${System.nanoTime()}").apply { mkdirs() }
        val access = SerializedCoreAccess(root.absolutePath, AndroidSecureKeyStore(context))
        access.call { g ->
            g.exportRecoveryCode(); g.configureSync(config(SyncBackend.S3), "test-credential"); g.setSyncEnabled(true)
            g.createAsset("cash", "同步前", 100)
        }
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val transport = MemoryTransport().apply { onPut = { started.countDown(); assertTrue(release.await(10, TimeUnit.SECONDS)) } }
        val job = async(Dispatchers.Default) { access.sync(transport) }
        assertTrue(started.await(10, TimeUnit.SECONDS))
        try { withTimeout(3_000) { access.call { it.createAsset("cash", "传输中新增", 200) } } }
        finally { release.countDown() }
        assertTrue(job.await().pendingLocalChanges)
        assertEquals(2, access.call { it.data().assets.size })
        root.deleteRecursively()
        Unit
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val prefix = args.getString("capturePrefix") ?: "m7-phone-light"
        val image = instrumentation.uiAutomation.takeScreenshot()
        File(context.filesDir, "$prefix-$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }

    @Test fun captureSyncSettingsAndToolbarStates() {
        val core = SnowFoldCore.open(context.filesDir.absolutePath, AndroidSecureKeyStore(context))
        core.clearSyncConfig(); core.clearData(); core.exportRecoveryCode()
        core.saveTheme(if (args.getString("captureTheme") == "dark") ThemePreference.DARK else ThemePreference.LIGHT)
        core.createAsset("cash", "家庭备用金", 1288000)
        core.configureSync(config(SyncBackend.S3).copy(endpoint = "https://127.0.0.1:1"), "test-credential")
        core.setSyncPolicy(SyncPolicy(false, 0u, false)); core.setSyncEnabled(true)
        core.syncNow(MemoryTransport())
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("sync-button").fetchSemanticsNodes().isNotEmpty() }
            screenshot("overview-sync-idle")
            compose.onNodeWithTag("sync-button").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("sync-failed-dot", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            screenshot("overview-sync-failed")
            if (compose.onAllNodesWithTag("nav-settings").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithTag("nav-settings").performClick()
            else compose.onNodeWithContentDescription(context.getString(R.string.cd_settings)).performClick()
            compose.onNodeWithTag("sync-backend").performScrollTo()
            screenshot("settings-sync-s3")
            compose.onNodeWithTag("sync-secret").performScrollTo()
            screenshot("settings-sync-s3-credentials")
            compose.onNodeWithTag("sync-status").performScrollTo()
            screenshot("settings-sync-policy")
            compose.onNodeWithTag("sync-now").performScrollTo()
            screenshot("settings-sync-status")
            compose.onNodeWithTag("sync-backend").performScrollTo()
            compose.onNodeWithText(context.getString(R.string.sync_backend_webdav)).performClick()
            compose.onNodeWithTag("sync-secret").performScrollTo()
            screenshot("settings-sync-webdav")
            assertTrue(compose.onNodeWithTag("sync-secret").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text.isEmpty())
        } finally { scenario.close(); core.clearSyncConfig(); core.destroy() }
    }
}
