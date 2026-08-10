package co.datapipelines.auth

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * The 60s liveness cache (auth.md §11.4, D13): read-through, TTL expiry, and immediate
 * local invalidation. The clock is injected so TTL is exercised without sleeping.
 */
class AuthCacheTest {
    private var nowNanos = 0L
    private val ttlSeconds = 60L
    private val cache = AuthCache(AuthProperties(apiKeys = AuthProperties.ApiKeys(cacheTtlSeconds = ttlSeconds))) { nowNanos }

    private val userId = UUID.randomUUID()

    private fun user(active: Boolean) = User(userId, "u@c.com", "U", null, "kc", "s", active, false, Instant.now(), Instant.now(), null)

    @Test
    fun `a value is loaded once and served from cache within the TTL`() {
        val loads = AtomicInteger()
        repeat(5) {
            cache.isUserActive(userId) {
                loads.incrementAndGet()
                user(active = true)
            } shouldBe true
        }
        loads.get() shouldBe 1
    }

    @Test
    fun `the value is reloaded after the TTL elapses`() {
        val loads = AtomicInteger()
        cache.isUserActive(userId) {
            loads.incrementAndGet()
            user(active = true)
        } shouldBe true
        nowNanos += (ttlSeconds + 1) * 1_000_000_000L
        cache.isUserActive(userId) {
            loads.incrementAndGet()
            user(active = false)
        } shouldBe false
        loads.get() shouldBe 2
    }

    @Test
    fun `local invalidation forces an immediate reload`() {
        val loads = AtomicInteger()
        cache.isUserActive(userId) {
            loads.incrementAndGet()
            user(active = true)
        } shouldBe true
        cache.invalidateUser(userId)
        cache.isUserActive(userId) {
            loads.incrementAndGet()
            user(active = false)
        } shouldBe false
        loads.get() shouldBe 2
    }

    @Test
    fun `a missing user is treated as inactive`() {
        cache.isUserActive(userId) { null } shouldBe false
    }

    /**
     * AUTH-SEC-4: the ceiling is the promise that a flood of distinct principals cannot
     * grow the map without limit. Nothing asserted it before — deleting the
     * `MAX_ENTRIES` guard in `admit` left every other test green.
     *
     * Clock is held still, so nothing expires and the sweep can free nothing: the only
     * thing that can stop growth here is the bound itself.
     */
    @Test
    fun `the cache stops admitting at its ceiling instead of growing`() {
        repeat(MAX_ENTRIES + OVERFLOW) {
            val id = UUID.randomUUID()
            cache.user(id) { user(active = true) }
        }

        cache.size() shouldBe MAX_ENTRIES
    }

    /** At the ceiling the cache still answers — it degrades to a direct read, never fails. */
    @Test
    fun `a saturated cache still serves loads, uncached`() {
        repeat(MAX_ENTRIES) { cache.user(UUID.randomUUID()) { user(active = true) } }
        val loads = AtomicInteger()

        repeat(3) {
            cache.isUserActive(userId) {
                loads.incrementAndGet()
                user(active = true)
            } shouldBe true
        }

        // Not admitted (the map is full), so every read goes to the loader — correct,
        // just not cached. The bound costs latency, never correctness.
        loads.get() shouldBe 3
        cache.size() shouldBe MAX_ENTRIES
    }

    private companion object {
        /** Mirrors `AuthCache.MAX_ENTRIES`. */
        const val MAX_ENTRIES = 10_000
        const val OVERFLOW = 1
    }
}
