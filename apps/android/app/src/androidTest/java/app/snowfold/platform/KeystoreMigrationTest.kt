package app.snowfold.platform

import android.graphics.Bitmap
import android.system.Os
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import app.snowfold.MainActivity
import app.snowfold.R
import app.snowfold.core.CoreException
import app.snowfold.core.SecureKeyStore
import app.snowfold.core.SnowFoldCore
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** 当前内核 + 真实 Keystore；迁移前先保存密封字节，导入时只读该文件。 */
class KeystoreMigrationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val prefix get() = InstrumentationRegistry.getArguments().getString("capturePrefix") ?: "m6"
    private val directory get() = File(context.filesDir, "keystore-migration-case")
    private val legacy get() = File(directory, "snowfold-sync.key")
    private val encrypted get() = File(directory, "sync-key.bin")
    private val rootEncrypted get() = File(context.filesDir, "sync-key.bin")
    private val keyStore get() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private val key = ByteArray(32) { (it + 17).toByte() }
    private val hex get() = key.joinToString("") { "%02x".format(it.toInt() and 255) }
    private val legacyContents get() = "snowfold-sync-key-v1\n$hex\n"
    private var scenario: ActivityScenario<MainActivity>? = null

    private fun secureStore() = AndroidSecureKeyStore(WrappedKeyFile(encrypted, AndroidWrappingKeys()))
    private fun open(store: SecureKeyStore = secureStore()) = SnowFoldCore.open(directory.absolutePath, store)

    @Before fun prepare() {
        cleanStorage()
        assertTrue(directory.mkdirs())
    }

    @After fun cleanup() {
        scenario?.close()
        scenario = null
        cleanStorage()
    }

    private fun cleanStorage() {
        if (directory.exists()) assertTrue(directory.deleteRecursively())
        Files.deleteIfExists(rootEncrypted.toPath())
        keyStore.deleteEntry(WRAPPING_KEY_ALIAS)
    }

    private fun writeLegacy() {
        legacy.writeText(legacyContents)
        Os.chmod(legacy.absolutePath, 384)
        assertEquals(384, Os.stat(legacy.absolutePath).st_mode and 511)
    }

    private fun assertRecoveryValue(code: String) {
        assertEquals(hex, code.lineSequence().last { it.isNotBlank() })
        assertTrue(code.endsWith("snowfold-sync-key-v1\n$hex\n"))
        assertEquals(64, hex.length)
    }

    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun evidence(message: String) {
        File(context.filesDir, "$prefix-keystore-scenarios.txt").appendText("PASS $message\n")
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val image = instrumentation.uiAutomation.takeScreenshot()
        try {
            File(context.filesDir, "$prefix-$name.png").outputStream().use {
                assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            image.recycle()
        }
    }

    @Test fun preMigrationSealedFileImportsAfterLegacyKeyMigration() {
        writeLegacy()
        val store = secureStore()
        store.store(key.copyOf())
        val snapshot = File(context.filesDir, "$prefix-before-migration.bfseal")
        val (beforeCode, beforeJson) = open(store).use { core ->
            core.createAsset("cash", "迁移前真实密封资产", 1234567890L)
            val code = core.exportRecoveryCode()
            val json = core.exportJson()
            snapshot.writeBytes(core.exportSealed())
            code to json
        }
        val sealedDigest = digest(snapshot.readBytes())
        evidence("1a before-migration file=${snapshot.name} sha256=$sealedDigest; exportSealed used preloaded K")

        store.delete()
        writeLegacy()
        assertNull(store.load())
        open(store).use { core ->
            assertEquals(beforeCode, core.exportRecoveryCode())
            assertFalse(legacy.exists())
            core.clearData()
            assertTrue(core.data().assets.isEmpty())
            core.importSealed(snapshot.readBytes())
            assertEquals(beforeJson, core.exportJson())
            assertEquals(1234567890L, core.data().assets.single().amountCents)
        }
        assertEquals(sealedDigest, digest(snapshot.readBytes()))
        evidence("1 old backup imported after migration; same saved file sha256=$sealedDigest; data identical")
    }

    @Test fun recoveryCodeValueAndFormatRemainUnchanged() {
        val store = secureStore()
        store.store(key.copyOf())
        val before = open(store).use { it.exportRecoveryCode() }
        assertRecoveryValue(before)
        store.delete()
        writeLegacy()
        val after = open(store).use { it.exportRecoveryCode() }
        assertEquals(before, after)
        assertRecoveryValue(after)
        evidence("2 full recovery text identical before/after; 64 lowercase hex; sha256=${digest(after.toByteArray())}")
    }

    @Test fun migrationRemovesLegacyPlaintextOnlyAfterVerifiedReadback() {
        writeLegacy()
        val store = secureStore()
        val events = mutableListOf<String>()
        val observed = object : SecureKeyStore by store {
            override fun load(): ByteArray? {
                events += "load:${legacy.exists()}"
                return store.load()
            }
            override fun store(key: ByteArray) {
                events += "store:${legacy.exists()}"
                store.store(key)
            }
        }
        RandomAccessFile(legacy, "r").use { oldHandle ->
            open(observed).use { assertRecoveryValue(it.exportRecoveryCode()) }
            assertEquals(listOf("load:true", "store:true", "load:true"), events)
            assertFalse(legacy.exists())
            val overwritten = ByteArray(oldHandle.length().toInt())
            oldHandle.readFully(overwritten)
            assertTrue(overwritten.isNotEmpty())
            assertTrue(overwritten.all { it == 0.toByte() })
        }
        assertArrayEquals(key, store.load())
        assertFalse(File(directory, "snowfold-sync.key.bak").exists())
        evidence("3 old file overwritten then removed after readback; callback events=$events; no .bak")
    }

    @Test fun twoStartsKeepTheSameKeyWithoutErrors() {
        writeLegacy()
        val first = open().use { it.exportRecoveryCode() }
        val second = open().use { it.exportRecoveryCode() }
        assertEquals(first, second)
        assertRecoveryValue(second)
        assertFalse(legacy.exists())
        evidence("4 two independent core opens keep identical recovery text; no old plaintext")
    }

    @Test fun failedStoreKeepsLegacyKeyUsable() {
        writeLegacy()
        val store = secureStore()
        var writes = 0
        val rejecting = object : SecureKeyStore by store {
            override fun store(key: ByteArray) {
                writes++
                key.fill(0)
                throw CoreException.KeyUnavailable()
            }
        }
        open(rejecting).use { core ->
            assertThrows(CoreException.KeyUnavailable::class.java) { core.exportRecoveryCode() }
        }
        assertEquals(1, writes)
        assertEquals(legacyContents, legacy.readText())
        assertNull(store.load())
        assertFalse(encrypted.exists())
        open(store).use { core ->
            assertRecoveryValue(core.exportRecoveryCode())
            assertNotNull(core.inspectSealed(core.exportSealed()))
        }
        evidence("5 failed store preserves exact legacy bytes; retry uses K and sealed backup remains readable")
    }

    @Test fun loadFailureDoesNotGenerateAKeyAndShowsExactRecoveryGuidance() {
        val store = AndroidSecureKeyStore(context)
        store.store(key.copyOf())
        val ciphertext = rootEncrypted.readBytes()
        keyStore.deleteEntry(WRAPPING_KEY_ALIAS)
        val localError = assertThrows(CoreException.KeyUnavailable::class.java) { store.load() }
        assertTrue(localError.cause is KeyStoreException)
        SnowFoldCore.open(context.filesDir.absolutePath, store).use { core ->
            assertThrows(CoreException.KeyUnavailable::class.java) { core.exportRecoveryCode() }
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        val exact = "本机密钥已失效，请用恢复码恢复"
        compose.waitUntil(10_000) { compose.onAllNodesWithText(exact).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(exact).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.error_internal), substring = true).assertDoesNotExist()
        assertArrayEquals(ciphertext, rootEncrypted.readBytes())
        assertFalse(keyStore.containsAlias(WRAPPING_KEY_ALIAS))
        assertFalse(File(context.filesDir, "snowfold-sync.key").exists())
        screenshot("key-unavailable")
        evidence("6 real missing Keystore alias -> CoreException.KeyUnavailable across FFI -> exact UI '$exact'; ciphertext unchanged; no replacement key")
    }

    @Test fun failedReadbackKeepsLegacyUntilSuccessfulRestartCleanup() {
        writeLegacy()
        val store = secureStore()
        var written = false
        val interrupted = object : SecureKeyStore by store {
            override fun load(): ByteArray? {
                if (written) throw CoreException.KeyUnavailable()
                return store.load()
            }
            override fun store(key: ByteArray) {
                store.store(key)
                written = true
            }
        }
        open(interrupted).use { core ->
            assertThrows(CoreException.KeyUnavailable::class.java) { core.exportRecoveryCode() }
        }
        assertTrue(written)
        assertEquals(legacyContents, legacy.readText())
        assertArrayEquals(key, store.load())
        open(store).use { assertRecoveryValue(it.exportRecoveryCode()) }
        assertFalse(legacy.exists())
        evidence("extra readback failure retains old file and stored K; successful restart removes matching leftover")
    }

    @Test fun settingsShowsExactSystemKeystoreProtectionCopy() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("nav-overview").fetchSemanticsNodes().isNotEmpty() }
        if (compose.onAllNodesWithTag("nav-settings").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithTag("nav-settings").performClick()
        } else {
            compose.onNodeWithContentDescription(context.getString(R.string.cd_settings)).performClick()
        }
        compose.onNodeWithText("密钥由系统 Keystore 保护").performScrollTo().assertIsDisplayed()
        screenshot("settings-keystore")
        evidence("copy settings displays exact '密钥由系统 Keystore 保护'")
    }
}
