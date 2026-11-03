package co.datapipelines.staging

import co.datapipelines.typesystem.ColumnSchema
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.Dialect
import co.datapipelines.typesystem.LogicalType
import co.datapipelines.typesystem.StagingEngine
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * The DuckDB destruction guarantee (staging.md §3.4, §8.5), the twin of [H2StagingLifecycleTest]:
 * a DuckDB staging database is a FILE, so "destroyed" means the file, its WAL and the spill directory
 * are gone from disk — on success, on failure and on cancellation — and the reservation is released.
 * Each "gone" assertion is preceded by a "present" one on the same path, or the absence is vacuous.
 */
class DuckDbStagingLifecycleTest {
    @TempDir
    lateinit var scratch: Path

    @Test
    fun `while open the file exists and holds the staged table, after close every path is gone`() {
        val factory = duckFactory(scratch)
        val executionId = UUID.randomUUID()
        val staging = factory.create(executionId, StagingEngine.DUCKDB)

        SourceDb().use { src ->
            src.exec("CREATE TABLE t (id INTEGER)")
            src.exec("INSERT INTO t VALUES (1), (2), (3)")
            runBlocking { staging.stage(src.query("SELECT id FROM t"), "stg_t", Dialect.H2) }
        }
        staging.scalarText("SELECT count(*) FROM \"stg_t\"") shouldBe "3"
        pathsOf(scratch, executionId) shouldContain "exec_$executionId.duckdb"
        factory.budget.reservedBytes shouldBe factory.properties.defaultReservationBytes

        staging.close()

        pathsOf(scratch, executionId).shouldBeEmpty()
        factory.budget.reservedBytes shouldBe 0L
        // No new work after close: a lease is refused rather than reopening the database (§6).
        shouldThrow<IllegalStateException> { staging.scalarText("SELECT 1") }
    }

    @Test
    fun `a spill directory created by a sort is deleted with the database`() {
        val factory = duckFactory(scratch, memoryLimitBytes = 64 * MIB)
        // A reservation large enough that the table itself never trips the disk bound.
        val staging = factory.open(reservationBytes = 2048 * MIB)
        staging.authorExec("CREATE TABLE big AS SELECT range AS id, md5(range::VARCHAR) || repeat('x', 100) AS s FROM range(1000000)")
        // Hold a cursor over a sort larger than memory_limit open while the spill directory is read.
        val spillSeen =
            runBlocking {
                staging.withQuery("SELECT s FROM big ORDER BY s") { rs ->
                    rs.next()
                    Files.isDirectory(scratch.resolve("exec_${staging.executionId}.tmp"))
                }
            }
        spillSeen shouldBe true

        staging.close()

        pathsOf(scratch, staging.executionId).shouldBeEmpty()
    }

    @Test
    fun `the files are gone after close when a stage failed`() {
        val staging = duckFactory(scratch).open()
        // A failure raised AFTER the file exists: the value does not fit the declared DECIMAL(3, 2).
        shouldThrow<StagingValueOverflowException> {
            runBlocking {
                staging.stageRows(
                    "stg_fail",
                    listOf(ColumnSchema("n", LogicalType.DECIMAL, 3, 2)),
                    sequenceOf(listOf(BigDecimal("123.45"))),
                )
            }
        }
        pathsOf(scratch, staging.executionId) shouldContain "exec_${staging.executionId}.duckdb"

        staging.close()

        pathsOf(scratch, staging.executionId).shouldBeEmpty()
    }

    @Test
    fun `cancellation - a lease cancelled mid-block still leaves nothing behind once close runs`() {
        val factory = duckFactory(scratch)
        val staging = factory.open()
        runBlocking {
            val inside = CompletableDeferred<Unit>()
            val node =
                launch(Dispatchers.IO) {
                    staging.withConnection { c ->
                        c.createStatement().use { it.execute("CREATE TABLE \"stg_c\" AS SELECT 1 AS id") }
                        inside.complete(Unit)
                        delay(60_000)
                    }
                }
            inside.await()
            node.cancelAndJoin()
            // The executor's shape (PipelineExecutor's finally): close in NonCancellable.
            withContext(NonCancellable) { staging.close() }
        }
        pathsOf(scratch, staging.executionId).shouldBeEmpty()
        factory.budget.reservedBytes shouldBe 0L
    }

    @Test
    fun `close with a lease still out defers the deletion to that lease's return`() {
        val factory = duckFactory(scratch)
        val staging = factory.open()
        runBlocking {
            val inside = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val holder =
                async(Dispatchers.IO) {
                    staging.withConnection { c ->
                        inside.complete(Unit)
                        release.await()
                        c.createStatement().use { st -> st.executeQuery("SELECT 42").use { rs -> rs.next() } }
                    }
                }
            inside.await()
            staging.close()
            // close() did not wait, and did not pull the database from under the lease.
            pathsOf(scratch, staging.executionId) shouldContain "exec_${staging.executionId}.duckdb"
            factory.budget.reservedBytes shouldBe factory.properties.defaultReservationBytes
            release.complete(Unit)
            holder.await() shouldBe true
        }
        pathsOf(scratch, staging.executionId).shouldBeEmpty()
        factory.budget.reservedBytes shouldBe 0L
    }

    @Test
    fun `close is idempotent and releases the reservation exactly once`() {
        val factory = duckFactory(scratch)
        val first = factory.open()
        val second = factory.open()
        first.close()
        first.close()
        factory.budget.reservedBytes shouldBe second.reservationBytes
        second.close()
        factory.budget.reservedBytes shouldBe 0L
    }

    @Test
    fun `an unopenable scratch directory fails as creation_failed and releases the reservation`() {
        val notADirectory = Files.writeString(scratch.resolve("plain-file"), "x")
        val factory = duckFactory(notADirectory)
        val executionId = UUID.randomUUID()

        val thrown = shouldThrow<DatapipelinesException> { factory.create(executionId, StagingEngine.DUCKDB) }

        thrown.code shouldBe StagingErrorCodes.CREATION_FAILED
        thrown.details["execution_id"] shouldBe executionId.toString()
        factory.budget.reservedBytes shouldBe 0L
    }

    @Test
    fun `the DuckDB factory refuses an H2 request and the dispatching factory routes each engine`() {
        val duck = duckFactory(scratch)
        val refused = shouldThrow<DatapipelinesException> { duck.create(UUID.randomUUID(), StagingEngine.H2) }
        refused.code shouldBe StagingErrorCodes.ENGINE_UNAVAILABLE
        refused.details["engine"] shouldBe "H2"

        val dispatching = EngineDispatchingStagingFactory(H2StagingFactory(H2StagingProperties()), duck)
        dispatching.create(UUID.randomUUID(), StagingEngine.H2).use { it.shouldBeInstanceOf<H2Staging>() }
        dispatching.create(UUID.randomUUID(), StagingEngine.DUCKDB).use { it.shouldBeInstanceOf<DuckDbStaging>() }
        duck.budget.reservedBytes shouldBe 0L
    }

    @Test
    fun `stats count base tables only and read the engine's memory`() {
        val staging = duckFactory(scratch).open()
        staging.authorExec("CREATE TABLE a AS SELECT range AS id FROM range(100000)")
        staging.authorExec("CREATE VIEW v AS SELECT 1 AS x")
        staging.authorExec("CREATE TEMP TABLE tmp AS SELECT 1 AS x")

        val stats = runBlocking { staging.stats() }

        stats.tableCount shouldBe 1
        // The buffer manager holds the table it just wrote; a reading of 0 would mean the query read nothing.
        (stats.memoryUsedBytes > 0L) shouldBe true
        staging.close()
    }
}
