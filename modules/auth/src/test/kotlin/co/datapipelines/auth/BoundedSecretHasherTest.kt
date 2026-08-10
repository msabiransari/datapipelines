package co.datapipelines.auth

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Security NEW-1: concurrent Argon2 work is bounded, so a flood on the unmetered
 * `/api` surface cannot drive unbounded 19 MiB native allocations into a container OOM.
 *
 * The delegate here is a fake that blocks on a latch rather than a real Argon2 call.
 * That is what makes the proof **deterministic** instead of a race: the test can hold
 * every permitted caller inside the hasher at once and observe the exact in-flight
 * count, with no sleeps and no dependence on how fast the machine hashes.
 */
class BoundedSecretHasherTest {
    /**
     * Records concurrency and parks inside the hasher until [gate] opens, so the test
     * controls exactly how many callers are in flight.
     */
    private class BlockingHasher(
        val gate: CountDownLatch,
        val admitted: CountDownLatch,
    ) : SecretHasher {
        val inFlight = AtomicInteger()
        val highWater = AtomicInteger()

        override fun hash(raw: String): String = raw

        override fun verify(
            encodedHash: String,
            raw: String,
        ): Boolean {
            val current = inFlight.incrementAndGet()
            highWater.accumulateAndGet(current, ::maxOf)
            admitted.countDown()
            try {
                check(gate.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "gate never opened" }
            } finally {
                inFlight.decrementAndGet()
            }
            return true
        }
    }

    @Test
    fun `no more than the permitted number of verifications run concurrently`() {
        val gate = CountDownLatch(1)
        val admitted = CountDownLatch(PERMITS)
        val delegate = BlockingHasher(gate, admitted)
        val hasher = BoundedSecretHasher(delegate, PERMITS)
        val pool = Executors.newFixedThreadPool(CALLERS)

        try {
            val results = (1..CALLERS).map { pool.submit<Boolean> { hasher.verify("hash", "dpk_X.secret") } }

            // Exactly PERMITS callers get in; the rest are parked on the semaphore
            // holding no native memory. Checked while they are all still inside.
            admitted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS) shouldBe true
            delegate.inFlight.get() shouldBe PERMITS
            hasher.availablePermits() shouldBe 0

            gate.countDown()
            results.forEach { it.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) shouldBe true }
        } finally {
            gate.countDown()
            pool.shutdownNow()
        }

        // Over the whole run — all CALLERS of them — the bound was never exceeded.
        delegate.highWater.get() shouldBe PERMITS
        delegate.inFlight.get() shouldBe 0
        hasher.availablePermits() shouldBe PERMITS
    }

    @Test
    fun `the bound is released even when the delegate throws`() {
        val boom =
            object : SecretHasher {
                override fun hash(raw: String): String = error("boom")

                override fun verify(
                    encodedHash: String,
                    raw: String,
                ): Boolean = error("boom")
            }
        val hasher = BoundedSecretHasher(boom, PERMITS)

        repeat(PERMITS + 1) { runCatching { hasher.verify("hash", "secret") } }

        // A leaked permit here would silently shrink the pool to zero and wedge every
        // future authentication — the failure mode a `finally`-less release produces.
        hasher.availablePermits() shouldBe PERMITS
    }

    @Test
    fun `verification outcomes pass through the bound unchanged`() {
        val hasher = BoundedSecretHasher(Argon2SecretHasher(), PERMITS)
        val encoded = hasher.hash("dpk_ABCDEFGHIJKL.secret")

        hasher.verify(encoded, "dpk_ABCDEFGHIJKL.secret") shouldBe true
        hasher.verify(encoded, "dpk_ABCDEFGHIJKL.wrong") shouldBe false
    }

    @Test
    fun `the wired SecretHasher bean is the bounded one, never the raw Argon2 hasher`() {
        // Argon2SecretHasher is deliberately not a @Component: if it were, injecting
        // SecretHasher could reach the unbounded native path and this fix would be moot.
        SecretHasherConfig().secretHasher().shouldBeInstanceOf<BoundedSecretHasher>()
    }

    private companion object {
        const val PERMITS = 4
        const val CALLERS = 16
        const val TIMEOUT_SECONDS = 10L
    }
}
