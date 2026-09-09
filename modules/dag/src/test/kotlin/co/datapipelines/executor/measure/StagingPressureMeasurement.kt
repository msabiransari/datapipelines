package co.datapipelines.executor.measure

import co.datapipelines.staging.H2StagingFactory
import co.datapipelines.staging.H2StagingProperties
import co.datapipelines.typesystem.Dialect
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

/**
 * 108 §3 — **what does one execution's staging actually cost, against the budget it is given?**
 *
 * `max-memory-mb` is a per-EXECUTION ceiling, and
 * `max-concurrent-executions-per-instance × max-memory-mb` is what the JVM can be asked for: with
 * the shipped defaults, 100 GB. Whether that matters depends on a number nobody had measured —
 * how much heap an execution staging a large table actually holds — so this measures it, and the
 * §C startup warning exists to say the arithmetic out loud rather than to guess at it.
 *
 * The reading is the same one `checkMemoryBudget` uses (post-GC used heap), because the point is
 * to compare like with like: if the guard's own metric says 400 MB for 2M rows, then the guard is
 * measuring the right thing and the ceiling means what it says.
 *
 * **RSS is deliberately NOT read here.** Process RSS includes the test JVM, the H2 driver, the
 * Gradle worker and every container client on the classpath; attributing it to "an execution
 * staging 2M rows" would be a number with the wrong name on it. `scripts/measure/03-pressure.sh`
 * samples the JVM's RSS from OUTSIDE with `ps` while this runs, which is the honest way to get it.
 *
 * Reports, never asserts — gated on `DP_MEASURE=1`.
 */
@EnabledIfEnvironmentVariable(named = "DP_MEASURE", matches = "1")
class StagingPressureMeasurement {
    @Test
    fun `heap held by one execution staging at the default budget`() {
        println("### One execution's staging footprint (the guard's own metric)")
        println("| rows | staged heap (MB) | max heap (MB) | budget (MB) |")
        println("|---|---|---|---|")
        ROW_COUNTS.forEach { rows ->
            val staging = H2StagingFactory(H2StagingProperties()).create(UUID.randomUUID())
            try {
                val baseline = usedHeapMb()
                source(rows).use { src ->
                    runBlocking { staging.stage(src.cursor, "m_pressure", Dialect.H2) }
                    val used = usedHeapMb()
                    println(
                        "| $rows | ${used - baseline} (abs $used) | ${Runtime.getRuntime().maxMemory() / MB} | " +
                            "${H2StagingProperties().maxMemoryMb} |",
                    )
                }
            } finally {
                staging.close()
            }
        }
        println()
        println("The `max_memory_mb` guard reads the JVM's used heap, not this execution's share of")
        println("it (staging §8.2's stated limit), so with concurrent executions every instance sees")
        println("the same number. The absolute column is what the guard would compare; the delta is")
        println("what this execution actually added.")
        println()
    }

    /**
     * The DuckDB half of §3 — the LAKE engine sharing the JVM's CPUs with the executor — is **not
     * measured here, and deliberately not guessed at**.
     *
     * A lake scan needs a DuckDB datasource with data on it; `dag`'s test classpath carries
     * neither the driver nor the fixture (they live in `datasources`, which lane 107 owns this
     * round). Reporting a number produced by anything less than a real lake read against real
     * Parquet would be a number about a stand-in.
     *
     * What the round hands 107 instead is the question, stated precisely, in the handback: measure
     * the 33-date `hvfhv_trips` query alone against the same query with two executions staging
     * concurrently, and set `properties.dialect.threads` from the answer — `max(2, cores/2)` is the
     * hypothesis, not the finding.
     */
    @Test
    fun `the lake contention measurement is not runnable from this module`() {
        println("### DuckDB / executor CPU contention")
        println()
        println("NOT MEASURED in this lane — the DuckDB driver and the lake fixtures live in")
        println("`modules/datasources`, which lane 107 owns this round. The measurement is stated")
        println("in the handback as an interface handed to 107, with the hypothesis it should test")
        println("(`threads` defaulting to `max(2, cores/2)`) marked as a hypothesis.")
        println()
    }

    private fun usedHeapMb(): Long {
        @Suppress("ExplicitGarbageCollectionCall")
        System.gc()
        val runtime = Runtime.getRuntime()
        return (runtime.totalMemory() - runtime.freeMemory()) / MB
    }

    private fun source(rows: Long): Source {
        val connection = DriverManager.getConnection("jdbc:h2:mem:pressure_${UUID.randomUUID()};MODE=PostgreSQL", "sa", "")
        val cursor =
            connection
                .createStatement()
                .executeQuery("SELECT x AS id, RPAD('a', 200, 'a') AS payload FROM SYSTEM_RANGE(1, $rows)")
        return Source(connection, cursor)
    }

    private class Source(
        private val connection: Connection,
        val cursor: java.sql.ResultSet,
    ) : AutoCloseable {
        override fun close() = connection.close()
    }

    private companion object {
        const val MB = 1024L * 1024L

        /** Up to 2M rows — the figure T188's pipeline scanned, and the one §C's warning is about. */
        val ROW_COUNTS = listOf(100_000L, 500_000L, 2_000_000L)
    }
}
