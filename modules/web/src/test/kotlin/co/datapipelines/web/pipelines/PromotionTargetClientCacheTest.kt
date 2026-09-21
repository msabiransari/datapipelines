package co.datapipelines.web.pipelines

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import co.datapipelines.auth.PromotionProperties
import co.datapipelines.pipeline.PipelineErrorCodes
import com.sun.net.httpserver.HttpServer
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger

/**
 * [PromotionTargetClient.cachedInventory] — the promoter lens's inventory read (178): one
 * target call per workspace per TTL window, an unreachable target remembered for the same
 * window (one probe, ONE WARN), the counter's three outcomes, [PromotionTargetClient.invalidate],
 * and `0` as "no cache".
 *
 * The target is a real JDK `HttpServer` on a loopback port counting its requests — the
 * request count is the fact every assertion here rests on — and the clock is injected so a
 * window "passes" without sleeping. The counter is a real [SimpleMeterRegistry]: a strict mock
 * would make a MISSING increment unobservable (MISTAKES.md), and the outcome tag set is the
 * closed set observability §4.1 documents.
 */
class PromotionTargetClientCacheTest {
    private val registry = SimpleMeterRegistry()
    private var now = 0L
    private val requests = AtomicInteger()
    private var server: HttpServer? = null
    private val warnings = ListAppender<ILoggingEvent>().also { it.start() }
    private val logger = LoggerFactory.getLogger(PromotionTargetClient::class.java) as Logger

    init {
        logger.addAppender(warnings)
    }

    @AfterEach
    fun stop() {
        server?.stop(0)
        logger.detachAppender(warnings)
    }

    @Test
    fun `one probe serves a window - hit after miss, and the window's end probes again`() {
        val client = client(serving(), ttlSeconds = 60)

        val first = client.cachedInventory("analytics")
        val second = client.cachedInventory("analytics")
        now += 59_000_000_000L
        val third = client.cachedInventory("analytics")
        now += 2_000_000_000L
        val fourth = client.cachedInventory("analytics")

        listOf(first, second, third, fourth).forEach { it.shouldBeInstanceOf<PromotionTargetClient.CachedInventory.Present>() }
        (first as PromotionTargetClient.CachedInventory.Present).inventory.deployment shouldBe "uat"
        withClue("two probes: the first read and the one past the window") { requests.get() shouldBe 2 }
        count("miss") shouldBe 2.0
        count("hit") shouldBe 2.0
        count("unreachable") shouldBe 0.0
    }

    @Test
    fun `the cache is per workspace, and invalidate drops one workspace's entry only`() {
        val client = client(serving(), ttlSeconds = 60)

        client.cachedInventory("analytics")
        client.cachedInventory("finance")
        client.cachedInventory("analytics")
        requests.get() shouldBe 2

        client.invalidate("analytics")
        client.cachedInventory("analytics")
        client.cachedInventory("finance")

        withClue("analytics probed again after the invalidation; finance still served from its window") {
            requests.get() shouldBe 3
        }
    }

    @Test
    fun `an unreachable target is remembered for the window - one probe, one WARN, then hits`() {
        val client = client(deadPort(), ttlSeconds = 60)

        val first = client.cachedInventory("analytics")
        val second = client.cachedInventory("analytics")
        val third = client.cachedInventory("analytics")

        first.shouldBeInstanceOf<PromotionTargetClient.CachedInventory.Unreachable>()
        first.code shouldBe PipelineErrorCodes.Versioning.PROMOTION_TARGET_UNREACHABLE
        first.reason shouldBe "ConnectException"
        second shouldBe first
        third shouldBe first
        count("unreachable") shouldBe 1.0
        count("hit") shouldBe 2.0
        val warns =
            warnings.list.filter {
                it.level == Level.WARN &&
                    it.formattedMessage.contains("event=pipeline.promotion.lens_unavailable")
            }
        withClue("the WARN is logged on the probe, so it is once per window by construction") { warns.size shouldBe 1 }
        warns.single().formattedMessage.contains("reason=ConnectException") shouldBe true
        warns.single().formattedMessage.contains("workspace=analytics") shouldBe true
        warns.single().formattedMessage.contains("sender-side-secret") shouldBe false

        now += 61_000_000_000L
        client.cachedInventory("analytics")
        withClue("the next window probes again and warns again") {
            count("unreachable") shouldBe 2.0
            warnings.list.count { it.formattedMessage.contains("lens_unavailable") } shouldBe 2
        }
    }

    @Test
    fun `no configured target is the unreachable outcome too, with its own reason`() {
        val client =
            PromotionTargetClient(
                PromotionProperties(target = PromotionProperties.Target(baseUrl = null), inventoryCacheTtlSeconds = 60),
                meterRegistry = registry,
                nowNanos = { now },
            )

        val outcome = client.cachedInventory("analytics")

        outcome.shouldBeInstanceOf<PromotionTargetClient.CachedInventory.Unreachable>()
        outcome.reason shouldBe "no_target_configured"
        count("unreachable") shouldBe 1.0
    }

    @Test
    fun `a TTL of zero disables the cache - every read probes and nothing is remembered`() {
        val client = client(serving(), ttlSeconds = 0)

        repeat(3) { client.cachedInventory("analytics") }

        requests.get() shouldBe 3
        count("miss") shouldBe 3.0
        count("hit") shouldBe 0.0
    }

    @Test
    fun `the fresh read the push path uses never touches the cache`() {
        val client = client(serving(), ttlSeconds = 60)

        client.inventory("analytics")
        client.inventory("analytics")
        client.cachedInventory("analytics")

        withClue("two fresh probes and one cached-path miss — the fresh reads did not seed the window") {
            requests.get() shouldBe 3
            count("miss") shouldBe 1.0
        }
    }

    private fun count(outcome: String): Double =
        registry
            .find(PromotionTargetClient.LENS_INVENTORY_METRIC)
            .tag("outcome", outcome)
            .counter()
            ?.count() ?: 0.0

    private fun client(
        baseUrl: String,
        ttlSeconds: Long,
    ): PromotionTargetClient =
        PromotionTargetClient(
            PromotionProperties(
                target = PromotionProperties.Target(baseUrl = baseUrl, serverKey = "sender-side-secret"),
                inventoryCacheTtlSeconds = ttlSeconds,
            ),
            meterRegistry = registry,
            nowNanos = { now },
        )

    /** A stub receiver answering §18.1's envelope for any workspace, counting requests. */
    private fun serving(): String {
        val started = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        started.createContext("/api/v1/promotion/inventory") { exchange ->
            requests.incrementAndGet()
            val body =
                """{"schema_version":1,"correlation_id":"c","data":{"deployment":"uat","authoring_enabled":false,""" +
                    """"workspace":"analytics","pipelines":[],"templates":[],"datasources":[]}}"""
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        started.start()
        server = started
        return "http://127.0.0.1:${started.address.port}"
    }

    /** A loopback port nothing listens on — the connection is refused, not timed out. */
    private fun deadPort(): String {
        val port = ServerSocket(0).use { it.localPort }
        return "http://127.0.0.1:$port"
    }
}
