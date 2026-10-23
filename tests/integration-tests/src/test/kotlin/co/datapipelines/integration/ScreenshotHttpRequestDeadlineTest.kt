package co.datapipelines.integration

import com.sun.net.httpserver.HttpServer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.net.http.HttpRequest
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.util.concurrent.Executors

/** The HTTP fixture's request budget is independent of its low-level wire-read timeout (#451). */
class ScreenshotHttpRequestDeadlineTest {
    @Test
    fun `the caller deadline bounds a delayed reply and a sufficient budget receives the real response`() {
        val executor = Executors.newCachedThreadPool()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = executor
        server.createContext("/upload") { exchange ->
            exchange.use {
                it.requestBody.readAllBytes()
                Thread.sleep(RESPONSE_DELAY_MILLIS)
                val response = """{"stored":true}""".toByteArray()
                it.responseHeaders.set("Content-Type", "application/json")
                try {
                    it.sendResponseHeaders(201, response.size.toLong())
                    it.responseBody.write(response)
                } catch (_: IOException) {
                    // The short-budget client's socket can already be closed. The long-budget caller must still receive 201.
                }
            }
        }
        server.start()
        try {
            shouldThrow<HttpTimeoutException> {
                ScreenshotUploadTransportTest.ScreenshotUploadTransport.post(
                    server.address.port,
                    "/upload",
                    HttpRequest.BodyPublishers.ofString("image"),
                    requestTimeout = Duration.ofMillis(SHORT_BUDGET_MILLIS),
                )
            }
            val accepted =
                ScreenshotUploadTransportTest.ScreenshotUploadTransport.post(
                    server.address.port,
                    "/upload",
                    HttpRequest.BodyPublishers.ofString("image"),
                    requestTimeout = Duration.ofSeconds(LONG_BUDGET_SECONDS),
                )
            accepted.statusCode shouldBe 201
            accepted.asString() shouldBe """{"stored":true}"""
        } finally {
            server.stop(0)
            executor.shutdownNow()
        }
    }

    private companion object {
        const val RESPONSE_DELAY_MILLIS = 750L
        const val SHORT_BUDGET_MILLIS = 200L
        const val LONG_BUDGET_SECONDS = 5L
    }
}
