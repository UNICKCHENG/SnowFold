package app.snowfold.platform

import android.content.Context
import android.net.Uri

/** SAF 读写：导出写入选定 Uri，导入一次性读取；失败返回 null 由调用方提示。 */
object SafIo {
    fun readText(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
    }.getOrNull()

    fun readBytes(context: Context, uri: Uri): ByteArray? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
    }.getOrNull()

    fun writeText(context: Context, uri: Uri, text: String): Boolean = writeBytes(context, uri, text.toByteArray(Charsets.UTF_8))

    fun writeBytes(context: Context, uri: Uri, bytes: ByteArray): Boolean = runCatching {
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } != null
    }.getOrDefault(false)
}
