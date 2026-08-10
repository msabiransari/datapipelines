package co.datapipelines.staging

import co.datapipelines.typesystem.Dialect
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.util.UUID

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

    private companion object {
        /** Enough for the staging machinery, well under the ~24 MB the trip case allocates. */
        const val HEADROOM_MB = 8L

        /** For the within-budget case: room the 100-row table cannot come close to filling. */
        const val WIDE_HEADROOM_MB = 64L
    }
}
