package co.datapipelines.web.workspace

import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.dao.QueryTimeoutException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import java.time.Duration
import java.util.UUID

/**
 * [RedisLastUsedWorkspaceStore] — the keyspace contract and the fail-open posture. Last-used
 * is a convenience, never state of record: a Redis blip degrades a login to "first
 * membership", so BOTH failure paths are WARN-and-continue — a login must never fail because
 * a convenience key was unreadable.
 */
class RedisLastUsedWorkspaceStoreTest {
    private val redis = mockk<StringRedisTemplate>()
    private val values = mockk<ValueOperations<String, String>>()
    private val store = RedisLastUsedWorkspaceStore(redis)

    private val userId = UUID.randomUUID()

    init {
        every { redis.opsForValue() } returns values
    }

    @Test
    fun `lastUsed reads the dp keyspace key`() {
        every { values.get("dp:ws:last:$userId") } returns "acme"

        store.lastUsed(userId) shouldBe "acme"
    }

    @Test
    fun `recordUsed writes the name under the 30-day TTL`() {
        every { values.set(any(), any(), any<Duration>()) } returns Unit

        store.recordUsed(userId, "acme")

        verify {
            values.set("dp:ws:last:$userId", "acme", Duration.ofDays(30))
        }
    }

    @Test
    fun `a read failure fails OPEN - null, never a failed login`() {
        every { values.get(any()) } throws QueryTimeoutException("redis down")

        store.lastUsed(userId) shouldBe null
    }

    @Test
    fun `a write failure fails OPEN - the switch itself succeeded`() {
        every { values.set(any(), any(), any<Duration>()) } throws QueryTimeoutException("redis down")

        store.recordUsed(userId, "acme") // must not throw
    }
}
