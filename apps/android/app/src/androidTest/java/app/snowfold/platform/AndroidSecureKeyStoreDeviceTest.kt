package app.snowfold.platform

import android.os.Build
import android.security.keystore.KeyInfo
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import java.security.KeyStoreException
import javax.crypto.AEADBadTagException
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** 只在本轮自建模拟器运行；使用真实应用私有文件与 AndroidKeyStore。 */
class AndroidSecureKeyStoreDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val file get() = File(context.filesDir, "sync-key.bin")
    private val keyStore get() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private val original = ByteArray(32) { (it + 10).toByte() }
    private fun store() = WrappedKeyFile(file, AndroidWrappingKeys())

    @Before fun prepare() { cleanStorage() }
    @After fun cleanup() { cleanStorage() }

    private fun cleanStorage() {
        java.nio.file.Files.deleteIfExists(file.toPath())
        keyStore.deleteEntry(WRAPPING_KEY_ALIAS)
    }

    @Test fun realKeystoreRoundTripUsesNonExportableUnauthenticatedAesKey() {
        assertNull(store().load())
        assertFalse(keyStore.containsAlias(WRAPPING_KEY_ALIAS))
        store().store(original)
        assertArrayEquals(original, store().load())
        assertEquals(60L, file.length())
        val wrappingKey = keyStore.getKey(WRAPPING_KEY_ALIAS, null) as SecretKey
        assertNull(wrappingKey.encoded)
        assertEquals("AES", wrappingKey.algorithm)
        val info = SecretKeyFactory.getInstance("AES", "AndroidKeyStore").getKeySpec(wrappingKey, KeyInfo::class.java) as KeyInfo
        assertEquals(256, info.keySize)
        assertFalse(info.isUserAuthenticationRequired)
        assertEquals(KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT, info.purposes)
        assertArrayEquals(arrayOf(KeyProperties.BLOCK_MODE_GCM), info.blockModes)
        assertArrayEquals(arrayOf(KeyProperties.ENCRYPTION_PADDING_NONE), info.encryptionPaddings)
    }

    @Test fun overwriteWithRealKeystoreReturnsLatestCoreKey() {
        store().store(original)
        val first = file.readBytes()
        val replacement = ByteArray(32) { (it + 90).toByte() }
        store().store(replacement)
        assertArrayEquals(replacement, store().load())
        assertFalse(first.copyOfRange(0, 12).contentEquals(file.readBytes().copyOfRange(0, 12)))
    }

    @Test fun deleteThenLoadReturnsNull() {
        store().store(original)
        store().delete()
        store().delete()
        assertNull(store().load())
        assertFalse(file.exists())
        assertTrue(keyStore.containsAlias(WRAPPING_KEY_ALIAS))
    }

    @Test fun changingOneCiphertextByteFailsAuthentication() {
        store().store(original)
        val damaged = file.readBytes().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        file.writeBytes(damaged)
        assertThrows(AEADBadTagException::class.java) { store().load() }
        assertArrayEquals(damaged, file.readBytes())
    }

    @Test fun removedWrappingKeyThrowsAndIsNeverRecreatedOnLoad() {
        store().store(original)
        val encrypted = file.readBytes()
        keyStore.deleteEntry(WRAPPING_KEY_ALIAS)
        assertThrows(KeyStoreException::class.java) { store().load() }
        assertFalse(keyStore.containsAlias(WRAPPING_KEY_ALIAS))
        assertArrayEquals(encrypted, file.readBytes())
    }

    @Test fun permanentlyInvalidatedKeyErrorPropagatesWithoutChangingCiphertext() {
        store().store(original)
        val encrypted = file.readBytes()
        val invalidated = KeyPermanentlyInvalidatedException()
        val broken = WrappedKeyFile(file, object : WrappingKeys {
            override fun load(): SecretKey = throw invalidated
            override fun create(): SecretKey = error("失效时不得生成替代密钥")
        })
        assertSame(invalidated, assertThrows(KeyPermanentlyInvalidatedException::class.java) { broken.load() })
        assertArrayEquals(encrypted, file.readBytes())
        assertArrayEquals(original, store().load())
    }

    @SdkSuppress(minSdkVersion = 28)
    @Test fun strongBoxUnavailableFallsBackToARealAndroidKeystoreKey() {
        val attempts = mutableListOf<Boolean>()
        val wrappingKeys = AndroidWrappingKeys { strongBox ->
            attempts += strongBox
            if (strongBox) throw StrongBoxUnavailableException("测试：StrongBox 不可用")
            generateAndroidWrappingKey(false)
        }
        WrappedKeyFile(file, wrappingKeys).store(original)
        assertEquals(listOf(true, false), attempts)
        assertArrayEquals(original, store().load())
        val wrappingKey = keyStore.getKey(WRAPPING_KEY_ALIAS, null) as SecretKey
        assertNull(wrappingKey.encoded)
        val info = SecretKeyFactory.getInstance("AES", "AndroidKeyStore").getKeySpec(wrappingKey, KeyInfo::class.java) as KeyInfo
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            assertNotEquals(KeyProperties.SECURITY_LEVEL_STRONGBOX, info.securityLevel)
        }
    }
}
