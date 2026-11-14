package app.snowfold.platform

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import androidx.annotation.RequiresApi
import app.snowfold.core.CoreException
import app.snowfold.core.SecureKeyStore
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.KeyStoreException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** UniFFI 只序列化 CoreException；系统存储失败统一交回密钥不可用。 */
class AndroidSecureKeyStore internal constructor(private val storage: WrappedKeyFile) : SecureKeyStore {
    constructor(context: Context) : this(WrappedKeyFile(File(context.filesDir, "sync-key.bin"), AndroidWrappingKeys()))

    override fun load(): ByteArray? = keyOperation { storage.load() }

    override fun store(key: ByteArray) {
        try {
            keyOperation { storage.store(key) }
        } finally {
            // UniFFI 传入的是内核副本，回调完成后由宿主清零。
            key.fill(0)
        }
    }

    override fun delete() = keyOperation { storage.delete() }

    private inline fun <T> keyOperation(block: () -> T): T = try {
        block()
    } catch (error: Exception) {
        throw CoreException.KeyUnavailable().apply { initCause(error) }
    }
}

/** 核心密钥可导出；只有包裹密钥留在系统 Keystore，私有文件仅保存 IV 与密文。 */
internal class WrappedKeyFile(
    private val keyFile: File,
    private val wrappingKeys: WrappingKeys,
) {
    fun load(): ByteArray? = synchronized(lock) {
        val wrapped = try {
            Files.readAllBytes(keyFile.toPath())
        } catch (_: NoSuchFileException) {
            return@synchronized null
        }
        if (wrapped.size != IV_BYTES + KEY_BYTES + TAG_BITS / 8) {
            throw GeneralSecurityException("密钥密文长度无效")
        }
        // 文件存在时缺失包裹密钥也是失效，绝不能创建新密钥或返回 null。
        val wrappingKey = wrappingKeys.load() ?: throw KeyStoreException("包裹密钥不存在")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, wrappingKey, GCMParameterSpec(TAG_BITS, wrapped.copyOfRange(0, IV_BYTES)))
        cipher.doFinal(wrapped, IV_BYTES, wrapped.size - IV_BYTES)
    }

    fun store(key: ByteArray): Unit = synchronized(lock) {
        require(key.size == KEY_BYTES) { "核心密钥必须为 32 字节" }
        val wrappingKey = wrappingKeys.load() ?: wrappingKeys.create()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey)
        val encrypted = cipher.doFinal(key)
        val iv = cipher.iv
        if (iv.size != IV_BYTES) throw GeneralSecurityException("密钥密文 IV 长度无效")

        // 临时文件同样只含密文；加密或提交失败时原文件保持完整。
        val temporary = Files.createTempFile(keyFile.toPath().parent, "sync-key-", ".tmp")
        try {
            FileOutputStream(temporary.toFile()).use { output ->
                output.write(iv)
                output.write(encrypted)
                output.fd.sync()
            }
            Files.move(temporary, keyFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    fun delete() {
        synchronized(lock) { Files.deleteIfExists(keyFile.toPath()) }
    }

    private companion object {
        const val KEY_BYTES = 32
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        val lock = Any()
    }
}

internal interface WrappingKeys {
    fun load(): SecretKey?
    fun create(): SecretKey
}

internal class AndroidWrappingKeys(
    private val generate: (Boolean) -> SecretKey = ::generateAndroidWrappingKey,
) : WrappingKeys {
    override fun load(): SecretKey? {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = keyStore.getKey(WRAPPING_KEY_ALIAS, null) ?: return null
        return key as? SecretKey ?: throw KeyStoreException("包裹密钥类型无效")
    }

    override fun create(): SecretKey = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        generateWithStrongBoxFallback(generate)
    } else {
        generate(false)
    }
}

internal const val WRAPPING_KEY_ALIAS = "snowfold-key-wrap"

@RequiresApi(Build.VERSION_CODES.P)
internal fun generateWithStrongBoxFallback(generate: (Boolean) -> SecretKey): SecretKey = try {
    generate(true)
} catch (_: StrongBoxUnavailableException) {
    generate(false)
}

internal fun generateAndroidWrappingKey(strongBox: Boolean): SecretKey {
    val parameters = KeyGenParameterSpec.Builder(
        WRAPPING_KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
    ).setKeySize(256)
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setRandomizedEncryptionRequired(true)
        .setUserAuthenticationRequired(false)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) parameters.setIsStrongBoxBacked(strongBox)
    return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
        init(parameters.build())
        generateKey()
    }
}
