package co.datapipelines.web.config

import co.datapipelines.datasources.DatasourceRegistry
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.data.redis.connection.Message
import org.springframework.data.redis.core.StringRedisTemplate
import java.nio.charset.StandardCharsets

/**
 * The two halves of §5.7's invalidation channel (050/R1), unit level: the publisher's payload
 * and failure policy, the subscriber's self-ignore and tolerance. The cross-instance behavior
 * those two produce together is the two-application-context E2E's job — nothing here proves
 * propagation.
 */
class DatasourceInvalidationChannelTest {
    private val mapper = jacksonObjectMapper()

    // ---------------------------------------------------------------------- publisher

    @Test
    fun `the publisher sends origin plus name on the shared channel`() {
        val redis = mockk<StringRedisTemplate>(relaxed = true)

        RedisPoolInvalidationPublisher(redis, mapper, instanceId = "A").publish("sales")

        verify {
            redis.convertAndSend(
                PoolInvalidationChannel.NAME,
                mapper.writeValueAsString(PoolInvalidationMessage(origin = "A", name = "sales")),
            )
        }
    }

    @Test
    fun `a Redis fault at publish is a WARN - the already-committed save never fails`() {
        val redis = mockk<StringRedisTemplate>()
        every { redis.convertAndSend(any<String>(), any<String>()) } throws DataAccessResourceFailureException("down")

        RedisPoolInvalidationPublisher(redis, mapper, instanceId = "A").publish("sales")

        // Reach here without exception = the contract held; nothing else to assert.
    }

    // ---------------------------------------------------------------------- subscriber

    private fun message(json: String): Message =
        mockk {
            every { body } returns json.toByteArray(StandardCharsets.UTF_8)
        }

    @Test
    fun `a foreign origin evicts the named pool`() {
        val registry = mockk<DatasourceRegistry>()
        every { registry.evictPool("sales") } returns true

        DatasourceInvalidationListener(registry, mapper, instanceId = "B")
            .onMessage(message("""{"origin":"A","name":"sales"}"""), null)

        verify(exactly = 1) { registry.evictPool("sales") }
    }

    @Test
    fun `the instance's own message is skipped - local eviction was synchronous`() {
        val registry = mockk<DatasourceRegistry>(relaxed = true)

        DatasourceInvalidationListener(registry, mapper, instanceId = "B")
            .onMessage(message("""{"origin":"B","name":"sales"}"""), null)

        verify(exactly = 0) { registry.evictPool(any()) }
    }

    @Test
    fun `a garbled payload is dropped without touching the subscription`() {
        val registry = mockk<DatasourceRegistry>(relaxed = true)

        DatasourceInvalidationListener(registry, mapper, instanceId = "B")
            .onMessage(message("not json at all"), null)

        verify(exactly = 0) { registry.evictPool(any()) }
    }

    @Test
    fun `unknown JSON fields do not break the parse - forwards-compat between versions`() {
        val registry = mockk<DatasourceRegistry>()
        every { registry.evictPool("sales") } returns false

        DatasourceInvalidationListener(registry, mapper, instanceId = "B")
            .onMessage(message("""{"origin":"A","name":"sales","futureField":1}"""), null)

        verify(exactly = 1) { registry.evictPool("sales") }
    }

    @Test
    fun `evicting no pool is silent - the foreign instance simply had none`() {
        val registry = mockk<DatasourceRegistry>()
        every { registry.evictPool("unused_here") } returns false

        // No exception, no state: the listener's only observable is the registry call, covered
        // above; this pins the no-op return path executes.
        DatasourceInvalidationListener(registry, mapper, instanceId = "B")
            .onMessage(message("""{"origin":"A","name":"unused_here"}"""), null)
    }
}
