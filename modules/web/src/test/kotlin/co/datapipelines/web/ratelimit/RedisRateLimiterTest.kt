package co.datapipelines.web.ratelimit

import co.datapipelines.web.config.RateLimitProperties
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import java.time.Instant
import java.util.UUID

/**
 * The fixed-window limiter (rest-api §12): both windows consumed, the binding window reported,
 * fail-open with a WARN on Redis faults.
 */
class RedisRateLimiterTest {
    private val redis = mockk<StringRedisTemplate>()
    private val ops = mockk<ValueOperations<String, String>>()
    private val now = Instant.ofEpochSecond(1_000_000)

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

    @Test
    fun `under both limits the request is allowed with remaining headroom`() {
        givenCounters(second = 13, minute = 87)
        val decision = limiter().consume(UUID.randomUUID())
        decision.allowed shouldBe true
        decision.limit shouldBe 100L
        decision.remaining shouldBe 87L
        decision.window shouldBe "s"
    }

    @Test
    fun `over the per-second limit rejects with a retry-after of the window remainder`() {
        givenCounters(second = 101, minute = 200)
        val decision = limiter().consume(UUID.randomUUID())
        decision.allowed shouldBe false
        decision.limit shouldBe 100L
        decision.remaining shouldBe 0L
        decision.retryAfterSeconds shouldBe 1L
    }

    @Test
    fun `over the per-minute limit rejects even when the second window has headroom`() {
        givenCounters(second = 5, minute = 1001)
        val decision = limiter().consume(UUID.randomUUID())
        decision.allowed shouldBe false
        decision.limit shouldBe 1000L
        decision.window shouldBe "m"
    }

    @Test
    fun `a Redis fault fails open rather than taking the API down with it`() {
        every { redis.opsForValue() } returns ops
        every { ops.increment(any<String>()) } throws DataAccessResourceFailureException("redis gone")
        val decision = limiter().consume(UUID.randomUUID())
        decision.allowed shouldBe true
    }
}
