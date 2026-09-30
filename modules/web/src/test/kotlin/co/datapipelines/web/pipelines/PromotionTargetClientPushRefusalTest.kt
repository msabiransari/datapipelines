package co.datapipelines.web.pipelines

import co.datapipelines.auth.PromotionProperties
import co.datapipelines.web.api.ApiException
import com.sun.net.httpserver.HttpServer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress

/**
 * The sender's surface of the receiver's aggregate bound refusal (A2, L1c-c): the push client re-raises
 * the receiver's §4.2 error VERBATIM — the code, the message and the details — so a promoter's REST
 * promotion answer names the configured ceiling, the count that exceeded it and the config key, exactly
 * as the receiver answered. The target is a real JDK `HttpServer` stub answering the envelope the
 * receiver's `boundTransferArms` writes; [PromotionTargetClientCacheTest] is the harness mould.
 */
class PromotionTargetClientPushRefusalTest {
    private var server: HttpServer? = null

    @AfterEach
    fun stop() {
        server?.stop(0)
    }

    @Test
    fun `the sender re-raises the receiver's aggregate bound refusal verbatim - code, message, count, max, config key`() {
        val url = serving()

        val client =
            PromotionTargetClient(
                PromotionProperties(
                    target = PromotionProperties.Target(baseUrl = url, serverKey = "sender-side-secret"),
                    inventoryCacheTtlSeconds = 60,
                ),
                meterRegistry = SimpleMeterRegistry(),
                nowNanos = { 0L },
            )

        val refused =
            shouldThrow<ApiException> {
                client.push(
                    PromotionWire.Batch(
                        sourceEnv = "dev",
                        keyFingerprint = "fp",
                        workspace = "acme",
                        visualizations =
                            List(51) {
                                com.fasterxml.jackson.databind.node.JsonNodeFactory
                                    .instance
                                    .objectNode()
                            },
                    ),
                )
            }

        withClue("the code is the receiver's, verbatim") {
            refused.code shouldBe "visualization.validation.body_invalid"
        }
        withClue("the message names the ceiling and the key") {
            refused.message shouldContain "at most 50"
            refused.message shouldContain "max-visualizations-per-dashboard"
        }
        withClue("the details carry the bound's shape and the target: ${refused.details}") {
            refused.details["reason"] shouldBe "too_many"
            refused.details["path"] shouldBe "visualizations"
            refused.details["count"] shouldBe 51
            refused.details["max"] shouldBe 50
            refused.details["config_key"] shouldBe "datapipelines.visualization.max-visualizations-per-dashboard"
            refused.details["target"] shouldBe url
        }
    }

    /** A stub receiver refusing the push with the aggregate bound's exact refusal envelope (§4.2). */
    private fun serving(): String {
        val started = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        started.createContext("/api/v1/promotion/push") { exchange ->
            val body =
                """{"schema_version":1,"correlation_id":"c","error":{"code":"visualization.validation.body_invalid",
                   "message":"The batch's visualizations arm carries 51 entries; at most 50 (datapipelines.visualization.max-visualizations-per-dashboard).",
                   "details":{"reason":"too_many","path":"visualizations","count":51,"max":50,
                              "config_key":"datapipelines.visualization.max-visualizations-per-dashboard"}}}"""
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(400, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        started.start()
        server = started
        return "http://127.0.0.1:${started.address.port}"
    }
}
