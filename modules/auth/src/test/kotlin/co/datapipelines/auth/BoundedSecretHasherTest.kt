package co.datapipelines.auth

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.support.StaticListableBeanFactory
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
        SecretHasherConfig().secretHasher(noRegistry()).shouldBeInstanceOf<BoundedSecretHasher>()
    }

    @Test
    fun `the bean registers its failure counter on the context's registry when one exists`() {
        val registry = SimpleMeterRegistry()
        val beans = StaticListableBeanFactory().apply { addBean("meterRegistry", registry) }

        SecretHasherConfig().secretHasher(beans.getBeanProvider(MeterRegistry::class.java))

        failures(registry, BoundedSecretHasher.CAUSE_MEMORY) shouldBe 0.0
    }

    /** A delegate whose verify fails natively with [nativeCode] — the stand-in for a real native fault. */
    private fun throwingDelegate(nativeCode: Int): SecretHasher =
        object : SecretHasher {
            override fun hash(raw: String): String = raw

            override fun verify(
                encodedHash: String,
                raw: String,
            ): Boolean = throw SecretVerificationUnavailableException(nativeCode, IllegalStateException("native ($nativeCode)"))
        }

    @Test
    fun `one throwing verify returns its permit`() {
        val hasher = BoundedSecretHasher(throwingDelegate(Argon2SecretHasher.MEMORY_ALLOCATION_ERROR), PERMITS)

        shouldThrow<SecretVerificationUnavailableException> { hasher.verify("hash", "secret") }

        hasher.availablePermits() shouldBe PERMITS
    }

    @Test
    fun `a native verification failure propagates, returns its permit and counts once under its cause`() {
        val cases =
            listOf(
                Argon2SecretHasher.MEMORY_ALLOCATION_ERROR to BoundedSecretHasher.CAUSE_MEMORY,
                Argon2SecretHasher.DECODING_FAILURE to BoundedSecretHasher.CAUSE_ENCODING,
                UNKNOWN_NATIVE_CODE to BoundedSecretHasher.CAUSE_OTHER,
            )
        cases.forEach { (code, cause) ->
            val registry = SimpleMeterRegistry()
            val hasher = BoundedSecretHasher(throwingDelegate(code), PERMITS, registry)
            // Eager: every series reads 0 before anything failed (a quiet series is not an absent one).
            listOf(BoundedSecretHasher.CAUSE_MEMORY, BoundedSecretHasher.CAUSE_ENCODING, BoundedSecretHasher.CAUSE_OTHER)
                .forEach { failures(registry, it) shouldBe 0.0 }

            val thrown = shouldThrow<SecretVerificationUnavailableException> { hasher.verify("hash", "secret") }

            thrown.nativeCode shouldBe code
            hasher.availablePermits() shouldBe PERMITS
            failures(registry, cause) shouldBe 1.0
            registry.find(BoundedSecretHasher.SECRET_VERIFICATION_FAILED_METRIC).counters().sumOf { it.count() } shouldBe 1.0
            // The raw code never becomes a tag value.
            registry
                .find(BoundedSecretHasher.SECRET_VERIFICATION_FAILED_METRIC)
                .counters()
                .map { it.id.getTag("cause") }
                .toSet() shouldBe
                setOf(
                    BoundedSecretHasher.CAUSE_MEMORY,
                    BoundedSecretHasher.CAUSE_ENCODING,
                    BoundedSecretHasher.CAUSE_OTHER,
                )
        }
    }

    @Test
    fun `a genuine mismatch counts nothing`() {
        val registry = SimpleMeterRegistry()
        val hasher = BoundedSecretHasher(Argon2SecretHasher(), PERMITS, registry)
        val encoded = hasher.hash("dpk_ABCDEFGHIJKL.secret")

        hasher.verify(encoded, "dpk_ABCDEFGHIJKL.wrong") shouldBe false

        registry.find(BoundedSecretHasher.SECRET_VERIFICATION_FAILED_METRIC).counters().sumOf { it.count() } shouldBe 0.0
    }

    private fun failures(
        registry: MeterRegistry,
        cause: String,
    ): Double =
        registry
            .get(BoundedSecretHasher.SECRET_VERIFICATION_FAILED_METRIC)
            .tag("cause", cause)
            .counter()
            .count()

    private fun noRegistry() = StaticListableBeanFactory().getBeanProvider(MeterRegistry::class.java)

    private companion object {
        const val PERMITS = 4
        const val CALLERS = 16
        const val TIMEOUT_SECONDS = 10L

        /** A native code no mapping names (argon2.h `ARGON2_THREAD_FAIL`) — must count as `other`. */
        const val UNKNOWN_NATIVE_CODE = -33
    }
}
