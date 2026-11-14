package app.snowfold.platform

import android.security.keystore.StrongBoxUnavailableException
import java.io.File
import java.nio.file.Files
import java.security.GeneralSecurityException
import java.security.KeyStoreException
import java.security.ProviderException
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import javax.crypto.AEADBadTagException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AndroidSecureKeyStoreTest {
    @get:Rule val folder = TemporaryFolder()
    private val file get() = File(folder.root, "sync-key.bin")
    private val keys = TestWrappingKeys()
    private fun store() = WrappedKeyFile(file, keys)
    private val original = ByteArray(32) { it.toByte() }

    @Test fun neverSavedReturnsNullWithoutAccessingKeystore() {
        keys.failure = KeyStoreException("Keystore 不可用")
        assertNull(store().load())
        assertEquals(0, keys.creations)
    }

    @Test fun storedKeySurvivesANewStoreInstance() {
        store().store(original)
        assertArrayEquals(original, store().load())
        assertEquals(60L, file.length())
        assertFalse(file.readBytes().contentEquals(original))
        assertEquals(1, keys.creations)
    }

    @Test fun overwriteReturnsLatestKeyAndUsesANewIv() {
        store().store(original)
        val first = file.readBytes()
        store().store(original)
        assertFalse(first.copyOfRange(0, 12).contentEquals(file.readBytes().copyOfRange(0, 12)))
        val replacement = ByteArray(32) { (it + 70).toByte() }
        store().store(replacement)
        assertArrayEquals(replacement, store().load())
        assertEquals(1, keys.creations)
        assertEquals(listOf("sync-key.bin"), folder.root.list()!!.toList())
    }

    @Test fun deleteIsIdempotentAndLoadReturnsNull() {
        store().store(original)
        store().delete()
        store().delete()
        assertNull(store().load())
        assertFalse(file.exists())
    }

    @Test fun oneChangedCiphertextByteThrowsAuthenticationError() {
        store().store(original)
        val damaged = file.readBytes().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        file.writeBytes(damaged)
        assertThrows(AEADBadTagException::class.java) { store().load() }
        assertArrayEquals(damaged, file.readBytes())
        assertEquals(1, keys.creations)
    }

    @Test fun emptyTruncatedAndOversizedFilesThrowInsteadOfReturningNull() {
        listOf(0, 1, 12, 59, 61).forEach { size ->
            file.writeBytes(ByteArray(size))
            assertThrows(GeneralSecurityException::class.java) { store().load() }
        }
        assertEquals(0, keys.creations)
    }

    @Test fun missingWrappingKeyThrowsWithoutRegeneratingIt() {
        store().store(original)
        val encrypted = file.readBytes()
        keys.key = null
        assertThrows(KeyStoreException::class.java) { store().load() }
        assertArrayEquals(encrypted, file.readBytes())
        assertEquals(1, keys.creations)
    }

    @Test fun keystoreFailurePropagatesAndFailedOverwriteKeepsPreviousKey() {
        store().store(original)
        val encrypted = file.readBytes()
        val failure = KeyStoreException("Keystore 读取失败")
        keys.failure = failure
        assertSame(failure, assertThrows(KeyStoreException::class.java) { store().load() })
        assertSame(failure, assertThrows(KeyStoreException::class.java) { store().store(ByteArray(32)) })
        assertArrayEquals(encrypted, file.readBytes())
        keys.failure = null
        assertArrayEquals(original, store().load())
    }

    @Test fun invalidCoreKeyLengthsAreRejectedBeforeChangingStorage() {
        store().store(original)
        val encrypted = file.readBytes()
        listOf(0, 31, 33).forEach { size ->
            assertThrows(IllegalArgumentException::class.java) { store().store(ByteArray(size)) }
            assertArrayEquals(encrypted, file.readBytes())
        }
        assertArrayEquals(original, store().load())
    }

    @Test fun failedAtomicCommitPreservesDestinationAndCleansTemporaryFile() {
        assertTrue(file.mkdir())
        val marker = File(file, "keep").apply { writeText("保留") }
        assertThrows(java.io.IOException::class.java) { store().store(original) }
        assertEquals("保留", marker.readText())
        assertEquals(listOf("sync-key.bin"), folder.root.list()!!.toList())
    }

    @Test fun concurrentInstancesKeepOneWrappingKeyAndCompleteCiphertext() {
        val executor = Executors.newFixedThreadPool(2)
        try {
            val values = (1..12).map { value -> ByteArray(32) { value.toByte() } }
            executor.invokeAll(values.map { value -> Callable { store().store(value); store().load()!! } })
                .forEach { result -> assertTrue(values.any { it.contentEquals(result.get()) }) }
            assertTrue(values.any { it.contentEquals(store().load()) })
            assertEquals(1, keys.creations)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test fun strongBoxUnavailableFallsBackToOrdinaryKeystore() {
        val attempts = mutableListOf<Boolean>()
        val expected = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val actual = generateWithStrongBoxFallback { strongBox ->
            attempts += strongBox
            if (strongBox) throw StrongBoxUnavailableException("测试：StrongBox 不可用")
            expected
        }
        assertSame(expected, actual)
        assertEquals(listOf(true, false), attempts)
    }

    @Test fun strongBoxSuccessDoesNotFallBackAndOtherFailuresPropagate() {
        val expected = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val attempts = mutableListOf<Boolean>()
        assertSame(expected, generateWithStrongBoxFallback { attempts += it; expected })
        assertEquals(listOf(true), attempts)
        val failure = ProviderException("非 StrongBox 故障")
        assertSame(failure, assertThrows(ProviderException::class.java) {
            generateWithStrongBoxFallback { throw failure }
        })
    }

    @Test fun ordinaryKeystoreFailureAfterFallbackIsNotHidden() {
        val failure = ProviderException("普通 Keystore 也失败")
        val attempts = mutableListOf<Boolean>()
        assertSame(failure, assertThrows(ProviderException::class.java) {
            generateWithStrongBoxFallback { strongBox ->
                attempts += strongBox
                if (strongBox) throw StrongBoxUnavailableException("测试：StrongBox 不可用")
                throw failure
            }
        })
        assertEquals(listOf(true, false), attempts)
    }

    @Test fun foreignStoreClearsCallbackCopyAfterSuccess() {
        val callbackKey = original.copyOf()
        val adapter = AndroidSecureKeyStore(store())
        adapter.store(callbackKey)
        assertArrayEquals(ByteArray(32), callbackKey)
        assertArrayEquals(original, adapter.load())
    }

    @Test fun foreignFailureUsesExpectedCoreErrorAndClearsCallbackCopy() {
        store().store(original)
        val encrypted = file.readBytes()
        val failure = KeyStoreException("测试：系统密钥失效")
        keys.failure = failure
        val adapter = AndroidSecureKeyStore(store())
        val readError = assertThrows(app.snowfold.core.CoreException.KeyUnavailable::class.java) { adapter.load() }
        assertSame(failure, readError.cause)
        val callbackKey = original.copyOf()
        val writeError = assertThrows(app.snowfold.core.CoreException.KeyUnavailable::class.java) { adapter.store(callbackKey) }
        assertSame(failure, writeError.cause)
        assertArrayEquals(ByteArray(32), callbackKey)
        assertArrayEquals(encrypted, file.readBytes())
    }

    @Test fun foreignDeleteFailureUsesExpectedCoreError() {
        assertTrue(file.mkdir())
        val marker = File(file, "keep").apply { writeText("保留") }
        val error = assertThrows(app.snowfold.core.CoreException.KeyUnavailable::class.java) {
            AndroidSecureKeyStore(store()).delete()
        }
        assertTrue(error.cause is java.io.IOException)
        assertEquals("保留", marker.readText())
    }

    private class TestWrappingKeys : WrappingKeys {
        var key: SecretKey? = null
        var failure: GeneralSecurityException? = null
        var creations = 0
        override fun load(): SecretKey? {
            failure?.let { throw it }
            return key
        }
        override fun create(): SecretKey {
            creations++
            return KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().also { key = it }
        }
    }
}
