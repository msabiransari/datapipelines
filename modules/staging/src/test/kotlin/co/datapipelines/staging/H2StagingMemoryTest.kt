package co.datapipelines.staging

import co.datapipelines.typesystem.Dialect
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.sql.ResultSet
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * The memory guard (§8.2): the budget decision is driven by a **measured** reading of used heap,
 * not an estimate. Staging a footprint past `max_memory_mb` fails the operation with
 * `pipeline.staging.memory_limit_exceeded`, and the exception carries the measured bytes — which
 * must exceed the budget.
 *
 * The budgets here are anchored to a live baseline rather than hard-coded small (see
 * [budgetMbAboveBaseline]): §8.2's reading is JVM-heap-wide, so a literal `maxMemoryMb = 1`
 * is below the process baseline and would fail every staging call on arrival — green for a
 * reason that has nothing to do with the rows staged.
 */
class H2StagingMemoryTest {
    @Test
    fun `staging past a small budget fails on the measured footprint`() {
        val budgetMb = budgetMbAboveBaseline(HEADROOM_MB)
        val staging = H2StagingFactory(H2StagingProperties(maxMemoryMb = budgetMb)).create(UUID.randomUUID())

        val thrown =
            SourceDb().use { src ->
                // ~100k rows × ~800-char payload ≈ +24 MB measured on the pinned driver — three
                // times the headroom, so the trip is the payload and not measurement jitter.
                val rs = src.query("SELECT x AS id, RPAD('a', 800, 'a') AS payload FROM SYSTEM_RANGE(1, 100000)")
                shouldThrow<StagingMemoryLimitException> { runBlocking { staging.stage(rs, "stg_big", Dialect.H2) } }
            }

        thrown.code shouldBe StagingErrorCodes.MEMORY_LIMIT_EXCEEDED
        thrown.maxMemoryMb shouldBe budgetMb
        // The measurement, not a row-count estimate, is what tripped the limit.
        thrown.memoryUsedBytes shouldBeGreaterThan budgetMb * 1024 * 1024

        staging.close()
    }

    /**
     * The budget stops the drain **while it is draining**, not after it has read everything
     * (108 §B).
     *
     * With `stage()` reading the source cursor outside the mutex, a check that only ran when the
     * whole cursor had been consumed would let a runaway node put its entire result in the heap
     * before anything looked — the guard would still fire, and would still be useless. So the
     * check runs on the first batch and then on a time throttle, and the observable that proves it
     * is how far the SOURCE cursor got: strictly fewer reads than the source has rows.
     *
     * Counting reads is the right observable because the alternatives are not available — the
     * partial table is rolled back by design, and the row count never reaches the caller.
     */
    @Test
    fun `the budget stops the drain mid-cursor, not after the whole source is read`() {
        val budgetMb = budgetMbAboveBaseline(HEADROOM_MB)
        val staging = H2StagingFactory(H2StagingProperties(maxMemoryMb = budgetMb)).create(UUID.randomUUID())

        val reads = AtomicInteger()
        SourceDb().use { src ->
            val rs = src.query("SELECT x AS id, RPAD('a', 800, 'a') AS payload FROM SYSTEM_RANGE(1, $SOURCE_ROWS)")
            shouldThrow<StagingMemoryLimitException> { runBlocking { staging.stage(counting(rs, reads), "stg_mid", Dialect.H2) } }
        }

        // Something was read — otherwise "fewer than all" is satisfied by a guard that fired
        // before the drain began, which is a different (and untested) claim.
        (reads.get() > 0).shouldBeTrue()
        (reads.get() < SOURCE_ROWS).shouldBeTrue()

        staging.close()
    }

    @Test
    fun `a footprint within budget stages cleanly and stats reports measured memory`() {
        val budgetMb = budgetMbAboveBaseline(WIDE_HEADROOM_MB)
        val staging = H2StagingFactory(H2StagingProperties(maxMemoryMb = budgetMb)).create(UUID.randomUUID())

        SourceDb().use { src ->
            val rs = src.query("SELECT x AS id FROM SYSTEM_RANGE(1, 100)")
            runBlocking { staging.stage(rs, "stg_small", Dialect.H2) }.rowsStaged shouldBe 100L
        }

        // The reading is a positive measurement, not a zeroed estimate.
        runBlocking { staging.stats() }.memoryUsedBytes shouldBeGreaterThan 0L

        staging.close()
    }

    /** Wraps [target] so every `next()` is counted — how far the drain actually got. */
    private fun counting(
        target: ResultSet,
        reads: AtomicInteger,
    ): ResultSet =
        Proxy.newProxyInstance(
            ResultSet::class.java.classLoader,
            arrayOf(ResultSet::class.java),
            InvocationHandler { _, method, args ->
                if (method.name == "next") reads.incrementAndGet()
                try {
                    method.invoke(target, *(args ?: emptyArray()))
                } catch (e: java.lang.reflect.InvocationTargetException) {
                    throw e.targetException
                }
            },
        ) as ResultSet

    private companion object {
        /** Big enough that a mid-drain stop is unambiguous at the default 1 000-row batch. */
        const val SOURCE_ROWS = 100000

        /** Enough for the staging machinery, well under the ~24 MB the trip case allocates. */
        const val HEADROOM_MB = 8L

        /** For the within-budget case: room the 100-row table cannot come close to filling. */
        const val WIDE_HEADROOM_MB = 64L
    }
}
