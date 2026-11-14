package app.snowfold.platform

import app.snowfold.core.CoreException
import app.snowfold.core.SyncRequest
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test

class AndroidSyncTransportTest {
    private fun request(url: String) = SyncRequest("GET", url, emptyList(), ByteArray(0))

    private fun server(status: Int, extra: String = "", pause: Long = 0, block: (String, AtomicInteger) -> Unit) {
        val socket = ServerSocket(0, 10, java.net.InetAddress.getByName("127.0.0.1"))
        val count = AtomicInteger()
        val worker = thread(isDaemon = true) {
            try {
                while (!socket.isClosed) socket.accept().use { client ->
                    count.incrementAndGet()
                    val reader = client.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) { }
                    if (pause > 0) Thread.sleep(pause)
                    client.getOutputStream().write(("HTTP/1.1 $status Test\r\nConnection: close\r\n" + extra +
                        "Content-Length: 0\r\n\r\n").toByteArray())
                }
            } catch (_: Exception) { }
        }
        try { block("http://127.0.0.1:${socket.localPort}/object", count) }
        finally { socket.close(); worker.join(1000) }
    }

    @Test fun returnsHttpErrorsWithoutConvertingThemToNetworkErrors() {
        for (status in listOf(401, 403, 404, 409, 412, 500)) server(status, "ETag: \"v1\"\r\n") { url, count ->
            val response = AndroidSyncTransport().send(request(url))
            assertEquals(status, response.status.toInt())
            assertEquals("\"v1\"", response.headers.first { it.name.equals("ETag", true) }.value)
            assertEquals(1, count.get())
        }
    }

    @Test fun refusesRedirectAndDoesNotSendASecondRequest() {
        server(302, "Location: /must-not-follow\r\n") { url, count ->
            assertEquals(302, AndroidSyncTransport().send(request(url)).status.toInt())
            assertEquals(1, count.get())
        }
    }

    @Test fun timeoutMapsToStableSyncNetworkWithoutOriginalDetails() {
        server(200, pause = 200) { url, _ ->
            try {
                AndroidSyncTransport(timeoutMillis = 40).send(request(url))
                fail("超时应失败")
            } catch (error: CoreException.SyncNetwork) {
                assertNull(error.cause)
            }
        }
    }

    @Test fun rejectsOversizedResponseBeforeReadingBody() {
        ServerSocket(0).use { server ->
            val worker = thread(isDaemon = true) {
                server.accept().use { client ->
                    val reader = client.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) { }
                    client.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 33554433\r\nConnection: close\r\n\r\n".toByteArray())
                }
            }
            assertThrows(CoreException.SyncNetwork::class.java) {
                AndroidSyncTransport().send(request("http://127.0.0.1:${server.localPort}/object"))
            }
            worker.join(1000)
        }
    }

    @Test fun rejectsInsecureRemoteAndUrlCredentialsQueriesOrFragments() {
        listOf("http://storage.example.com/object", "https://user:pass@example.com/object",
            "https://example.com/object?credential=value", "https://example.com/object#fragment").forEach { url ->
            assertThrows(CoreException.SyncNetwork::class.java) { AndroidSyncTransport().send(request(url)) }
        }
    }
}
