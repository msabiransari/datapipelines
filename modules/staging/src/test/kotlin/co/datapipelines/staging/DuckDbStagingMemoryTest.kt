package co.datapipelines.staging

import co.datapipelines.typesystem.ColumnSchema
import co.datapipelines.typesystem.Dialect
import co.datapipelines.typesystem.LogicalType
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.nio.file.Path
import java.sql.ResultSet
import java.util.concurrent.atomic.AtomicInteger

/**
 * The DuckDB memory bound (staging.md §8.5), the twin of [H2StagingMemoryTest]. On H2 the budget is
 * DP's reading of the JVM heap; on DuckDB it is the engine's `memory_limit`, so the trip happens
 * INSIDE the engine and the reading in the failure is the engine's own `used/limit` figure. The
 * limit bounds DuckDB's buffer manager only (dp-lake design §11) — not the JVM heap.
 *
 * Every database here reserves 1 GiB of scratch so the disk bound can never be what trips.
 *
 * Falsified at birth (handback): with [TINY_LIMIT] raised to 512 MiB, both refusal cases go red.
 */
class DuckDbStagingMemoryTest {
    @TempDir
    lateinit var scratch: Path

    private fun open(memoryLimitBytes: Long): DuckDbStaging =
        duckFactory(scratch, memoryLimitBytes = memoryLimitBytes).open(reservationBytes = 1024 * MIB)

    @Test
    fun `staging past a small budget fails on the engine's measured footprint`() {
        val staging = open(TINY_LIMIT)
        val payload = "a".repeat(PAYLOAD_CHARS)
        val rows = generateSequence(0) { it + 1 }.take(SOURCE_ROWS).map { listOf(it, "$it$payload") }

        val thrown =
            shouldThrow<StagingEngineMemoryLimitException> {
                val columns = listOf(ColumnSchema("id", LogicalType.INTEGER), ColumnSchema("payload", LogicalType.STRING))
                runBlocking { staging.stageRows("stg_big", columns, rows) }
            }

        println("memory evidence: limit=$TINY_LIMIT reading=${thrown.engineReading}")
        thrown.code shouldBe StagingErrorCodes.MEMORY_LIMIT_EXCEEDED
        thrown.details["memory_limit_bytes"] shouldBe TINY_LIMIT
        // The engine's own figure, e.g. "864.0 KiB/1.0 MiB" — not a JVM heap reading.
        thrown.engineReading shouldMatch ENGINE_READING
        staging.close()
    }

    @Test
    fun `the engine stops the drain mid-cursor, not after the whole source is read`() {
        val staging = open(TINY_LIMIT)
        val reads = AtomicInteger()
        SourceDb().use { src ->
            val rs = src.query("SELECT x AS id, RPAD('a', 800, 'a') AS payload FROM SYSTEM_RANGE(1, $SOURCE_ROWS)")
            shouldThrow<StagingEngineMemoryLimitException> { runBlocking { staging.stage(counting(rs, reads), "stg_mid", Dialect.H2) } }
        }
        (reads.get() > 0).shouldBeTrue()
        (reads.get() < SOURCE_ROWS).shouldBeTrue()
        // The partial table went with the failure.
        runBlocking { staging.stats() }.tableCount shouldBe 0
        staging.close()
    }

    @Test
    fun `an author statement past memory_limit fails as memory_limit_exceeded with the engine's reading`() {
        val staging = open(8 * MIB)

        val thrown =
            shouldThrow<StagingEngineMemoryLimitException> {
                runBlocking { staging.execute("CREATE TABLE agg AS SELECT string_agg(repeat('x', 1000), ',') AS s FROM range(100000)") }
            }

        thrown.engineReading shouldMatch ENGINE_READING
        staging.close()
    }

    @Test
    fun `a footprint within the limit stages cleanly and stats reports the engine's memory`() {
        val staging = open(256 * MIB)
        SourceDb().use { src ->
            val rs = src.query("SELECT x AS id FROM SYSTEM_RANGE(1, 100)")
            runBlocking { staging.stage(rs, "stg_small", Dialect.H2) }.rowsStaged shouldBe 100L
        }
        val stats = runBlocking { staging.stats() }
        stats.memoryUsedBytes shouldBeGreaterThan 0L
        stats.totalRows shouldBe 100L
        staging.close()
    }

    /** Counts every `next()` on [target]: how far the drain got before the engine refused. */
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
                } catch (e: InvocationTargetException) {
                    throw e.targetException
                }
            },
        ) as ResultSet

    private companion object {
        /** The smallest limit the properties admit; a ~1 MB batch cannot fit beside the engine's own blocks. */
        const val TINY_LIMIT = 1L shl 20

        const val SOURCE_ROWS = 100_000
        const val PAYLOAD_CHARS = 800

        /** The engine's `used/limit` pair as [DuckDbEngineFailures] extracts it from `(… used)`. */
        val ENGINE_READING = Regex("""[0-9.]+ [KMGT]?i?B/[0-9.]+ [KMGT]?i?B""")
    }
}
