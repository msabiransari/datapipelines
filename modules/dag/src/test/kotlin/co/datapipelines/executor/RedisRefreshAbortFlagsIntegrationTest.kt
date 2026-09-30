package co.datapipelines.executor

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.longs.shouldBeInRange
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.UUID

/**
 * [RedisRefreshAbortFlags] against a real Redis (#10 L2, spec §8.4 / §18 premise 6): the key is the documented
 * `dp:refresh-abort:{id}`, it expires by itself, is per refresh (one refresh's flag is not another's), and `clear`
 * is the owner's cleanup. The abort itself — the refresh reading the flag and ending ABORTED — is `RefreshEngineTest`'s.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RedisRefreshAbortFlagsIntegrationTest {
    private val redis = RedisSupport.template()
    private val flags = RedisRefreshAbortFlags(redis)

    @BeforeEach
    fun setUp() {
        RedisSupport.flush(redis)
    }

    @Test
    fun `a request sets the documented key with the given expiry and reads back for that refresh only`() {
        val refresh = UUID.randomUUID()

        flags.isRequested(refresh).shouldBeFalse()
        flags.request(refresh, ttlSeconds = 60)

        flags.isRequested(refresh).shouldBeTrue()
        flags.isRequested(UUID.randomUUID()).shouldBeFalse()
        redis.hasKey("dp:refresh-abort:$refresh") shouldBe true
        redis.getExpire("dp:refresh-abort:$refresh") shouldBeInRange 1L..60L
    }

    @Test
    fun `clear removes the flag and is idempotent`() {
        val refresh = UUID.randomUUID()
        flags.request(refresh, ttlSeconds = 60)

        flags.clear(refresh)
        flags.clear(refresh)

        flags.isRequested(refresh).shouldBeFalse()
    }

    @Test
    fun `the flag is not the execution cancel flag - the two key spaces never overlap`() {
        val id = UUID.randomUUID()
        flags.request(id, ttlSeconds = 60)

        redis.hasKey("dp:cancel:$id") shouldBe false
    }
}
