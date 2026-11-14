package app.snowfold.platform

import app.snowfold.core.CoreException
import app.snowfold.core.SyncHeader
import app.snowfold.core.SyncRequest
import app.snowfold.core.SyncResponse
import app.snowfold.core.SyncTransport
import java.net.URI
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** 仅同步使用；无日志拦截器、缓存或 Cookie，不重定向、不重试请求。 */
class AndroidSyncTransport(timeoutMillis: Int = 30_000) : SyncTransport {
    private val client = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .connectTimeout(timeoutMillis.toLong(), TimeUnit.MILLISECONDS)
        .readTimeout(timeoutMillis.toLong(), TimeUnit.MILLISECONDS)
        .writeTimeout(timeoutMillis.toLong(), TimeUnit.MILLISECONDS)
        .callTimeout(timeoutMillis.toLong(), TimeUnit.MILLISECONDS)
        .build()

    override fun send(request: SyncRequest): SyncResponse {
        try {
            val uri = URI(request.url)
            val loopback = uri.host?.lowercase() in setOf("localhost", "127.0.0.1", "[::1]", "::1")
            if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null ||
                (uri.scheme != "https" && !(uri.scheme == "http" && loopback))) {
                throw CoreException.SyncNetwork()
            }
            val body = if (request.body.isNotEmpty() || request.method in setOf("PUT", "POST", "PATCH")) {
                request.body.toRequestBody(null)
            } else null
            val outgoing = Request.Builder().url(request.url).method(request.method, body).apply {
                request.headers.forEach { header(it.name, it.value) }
            }.build()
            client.newCall(outgoing).execute().use { response ->
                val responseBody = response.body
                if (responseBody != null && responseBody.contentLength() > MAX_RESPONSE_BYTES) throw CoreException.SyncNetwork()
                val bytes = responseBody?.byteStream()?.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (output.size().toLong() + count > MAX_RESPONSE_BYTES) throw CoreException.SyncNetwork()
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                } ?: ByteArray(0)
                val headers = response.headers.map { SyncHeader(it.first, it.second) }
                return SyncResponse(response.code.toUShort(), headers, bytes)
            }
        } catch (_: Exception) {
            // IOException、超时与无效请求均不透传可能带凭证的原始异常。
            throw CoreException.SyncNetwork()
        }
    }

    private companion object {
        const val MAX_RESPONSE_BYTES = 32L * 1024 * 1024
    }
}
