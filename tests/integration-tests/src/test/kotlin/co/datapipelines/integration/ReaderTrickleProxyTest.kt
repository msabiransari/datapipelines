package co.datapipelines.integration

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/** The pass-through fixture must carry an upstream EOF instead of turning it into a stalled HTTP request. */
class ReaderTrickleProxyTest {
    @Test
    fun `an upstream closing its response closes the downstream response too`() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { upstream ->
            val worker = Executors.newSingleThreadExecutor()
            try {
                val served = worker.submit { answerAndClose(upstream) }
                ReaderTrickleProxy(upstream.inetAddress.hostAddress, upstream.localPort).use { proxy ->
                    assertClosedResponse(proxy, served)
                }
            } finally {
                worker.shutdownNow()
                worker.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS) shouldBe true
            }
        }
    }

    private fun assertClosedResponse(
        proxy: ReaderTrickleProxy,
        served: Future<*>,
    ) {
        Socket("127.0.0.1", URI(proxy.endpoint).port).use { client ->
            client.soTimeout = READ_TIMEOUT_MILLIS
            client.getOutputStream().write("HEAD /metadata.json HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray())
            val response = client.getInputStream()
            String(response.readNBytes(RESPONSE.length), StandardCharsets.US_ASCII) shouldBe RESPONSE
            served.get(WAIT_SECONDS, TimeUnit.SECONDS)
            response.read() shouldBe -1
        }
    }

    private fun answerAndClose(upstream: ServerSocket) {
        upstream.accept().use { socket ->
            val input = socket.getInputStream().bufferedReader(StandardCharsets.US_ASCII)
            while (!input.readLine().isNullOrEmpty()) {
                // Consume the request head before answering.
            }
            socket.getOutputStream().write(RESPONSE.toByteArray(StandardCharsets.US_ASCII))
        }
    }

    companion object {
        private const val RESPONSE = "HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
        private const val READ_TIMEOUT_MILLIS = 2000
        private const val WAIT_SECONDS = 5L
    }
}
