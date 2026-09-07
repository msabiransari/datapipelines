package co.datapipelines.web.ratelimit

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import co.datapipelines.web.config.RateLimitProperties
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import java.time.Instant
import java.util.UUID

/**
 * The fixed-window limiter (rest-api §12): both windows consumed, the binding window reported,
 * and — since 083's owner ruling — a Redis fault that fails **CLOSED** with its own reason and
 * exactly one WARN for the whole outage.
 */
class RedisRateLimiterTest {
    private val redis = mockk<StringRedisTemplate>()
    private val ops = mockk<ValueOperations<String, String>>()
    private val now = Instant.ofEpochSecond(1_000_000)

    private val appender = ListAppender<ILoggingEvent>()
    private lateinit var logger: Logger

    @BeforeEach
    fun captureLog() {
        logger = LoggerFactory.getLogger(RedisRateLimiter::class.java) as Logger
        appender.start()
        logger.addAppender(appender)
        logger.level = Level.DEBUG
    }

    @AfterEach
    fun releaseLog() {
        logger.detachAppender(appender)
        appender.stop()
    }

    private fun limiter(props: RateLimitProperties = RateLimitProperties()) = RedisRateLimiter(redis, props) { now }

    private fun givenCounters(
        second: Long,
        minute: Long,
    ) {
        every { redis.opsForValue() } returns ops
        every { ops.increment(match { it.contains(":s:") }) } returns second
        every { ops.increment(match { it.contains(":m:") }) } returns minute
        every { redis.expire(any<String>(), any<Long>(), any()) } returns true
    }

    private fun messagesAt(level: Level): List<String> = appender.list.filter { it.level == level }.map { it.formattedMessage }

    @Test
    fun `under both limits the request is allowed with remaining headroom`() {
        givenCounters(second = 13, minute = 87)
        val decision = limiter().consume(UUID.randomUUID())
        decision.allowed shouldBe true
        decision.limit shouldBe 100L
        decision.remaining shouldBe 87L
        decision.window shouldBe "s"
        // A counted decision never carries a reason — that is what tells a throttle from an outage.
        decision.reason.shouldBeNull()
        decision.unavailable shouldBe false
    }

    @Test
    fun `over the per-second limit rejects with a retry-after of the window remainder`() {
        givenCounters(second = 101, minute = 200)
        val decision = limiter().consume(UUID.randomUUID())
        decision.allowed shouldBe false
        decision.limit shouldBe 100L
        decision.remaining shouldBe 0L
        decision.retryAfterSeconds shouldBe 1L
        // A real throttle, so no reason: the filter must answer `rate_limit.exceeded`, not
        // `rate_limit.unavailable`. Asserting the null is what keeps the two refusals apart.
        decision.reason.shouldBeNull()
    }

    @Test
    fun `over the per-minute limit rejects even when the second window has headroom`() {
        givenCounters(second = 5, minute = 1001)
        val decision = limiter().consume(UUID.randomUUID())
        decision.allowed shouldBe false
        decision.limit shouldBe 1000L
        decision.window shouldBe "m"
        decision.reason.shouldBeNull()
    }

    @Test
    fun `a Redis fault fails CLOSED and names itself as the reason`() {
        every { redis.opsForValue() } returns ops
        every { ops.increment(any<String>()) } throws DataAccessResourceFailureException("redis gone")

        val decision = limiter().consume(UUID.randomUUID())

        // The owner's 2026-09-06 ruling: a request the limiter cannot judge is refused, not
        // admitted. Failing open would make "break the limiter's Redis" the cheapest way past
        // the limit.
        decision.allowed shouldBe false
        decision.unavailable shouldBe true
        decision.reason shouldBe "DataAccessResourceFailureException"
        decision.remaining shouldBe 0L
        decision.retryAfterSeconds shouldBe 1L
    }

    @Test
    fun `a null reply is a fault, not a count of zero`() {
        every { redis.opsForValue() } returns ops
        every { ops.increment(any<String>()) } returns null

        val decision = limiter().consume(UUID.randomUUID())

        decision.allowed shouldBe false
        decision.reason shouldBe "no reply"
    }

    @Test
    fun `an outage logs one WARN however many requests it refuses, and one INFO when it ends`() {
        every { redis.opsForValue() } returns ops
        every { ops.increment(any<String>()) } throws DataAccessResourceFailureException("redis gone")
        val limiter = limiter()

        repeat(50) { limiter.consume(UUID.randomUUID()).allowed shouldBe false }

        // A WARN per refused request would turn a Redis outage into a log outage at the full
        // request rate — the one line an operator needs, buried under thousands of copies.
        withClue("WARNs seen: ${messagesAt(Level.WARN)}") {
            messagesAt(Level.WARN).size shouldBe 1
        }
        messagesAt(Level.WARN).single() shouldBe
            "Rate limiter unavailable (Redis: DataAccessResourceFailureException); failing CLOSED — " +
            "requests are refused with rate_limit.unavailable until Redis answers again."
        messagesAt(Level.INFO).shouldBeEmpty()

        givenCounters(second = 1, minute = 1)
        limiter.consume(UUID.randomUUID()).allowed shouldBe true

        // The episode is closed exactly once, so the log carries the outage's shape, not its volume.
        messagesAt(Level.INFO).size shouldBe 1
        messagesAt(Level.WARN).size shouldBe 1
    }

    @Test
    fun `a second outage after a recovery warns again`() {
        // The back-off state must not latch: an operator has to see the SECOND outage too.
        every { redis.opsForValue() } returns ops
        every { ops.increment(any<String>()) } throws DataAccessResourceFailureException("redis gone")
        val limiter = limiter()
        limiter.consume(UUID.randomUUID())

        givenCounters(second = 1, minute = 1)
        limiter.consume(UUID.randomUUID())

        every { ops.increment(any<String>()) } throws DataAccessResourceFailureException("redis gone again")
        limiter.consume(UUID.randomUUID())

        messagesAt(Level.WARN).size shouldBe 2
    }
}
