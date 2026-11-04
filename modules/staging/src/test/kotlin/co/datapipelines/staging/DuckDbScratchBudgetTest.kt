package co.datapipelines.staging

import co.datapipelines.typesystem.ColumnSchema
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.LogicalType
import co.datapipelines.typesystem.StagingEngine
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.SQLException
import java.util.UUID

/**
 * The scratch aggregate (lane #795 A3; staging.md §8.5): a database reserves its share BEFORE its file
 * exists, the share is released exactly once, and a database refuses to grow past it — inside the
 * engine for a spill, by DP's measurement after a write for the file itself.
 *
 * Falsified at birth (handback): with the refusal in [ScratchBudget.reserve] removed, the "second
 * database refuses" case goes red; the reserved bytes and the aggregate are printed for the record.
 */
class DuckDbScratchBudgetTest {
    @TempDir
    lateinit var scratch: Path

    @Test
    fun `the budget admits until full, refuses with the figures, and a release makes room`() {
        val budget = ScratchBudget(capacityBytes = 100)
        val first = budget.reserve(UUID.randomUUID(), 60)
        val second = budget.reserve(UUID.randomUUID(), 40)
        budget.reservedBytes shouldBe 100L

        val refused = shouldThrow<StagingScratchExhaustedException> { budget.reserve(UUID.randomUUID(), 1) }
        refused.code shouldBe StagingErrorCodes.SCRATCH_EXHAUSTED
        refused.details["requested_bytes"] shouldBe 1L
        refused.details["reserved_bytes"] shouldBe 100L
        refused.details["capacity_bytes"] shouldBe 100L

        first.release()
        first.release()
        first.close()
        budget.reservedBytes shouldBe 40L
        budget.reserve(UUID.randomUUID(), 60).release()
        second.close()
        budget.reservedBytes shouldBe 0L
    }

    @Test
    fun `a reservation near Long MAX refuses instead of overflowing into a fit`() {
        val budget = ScratchBudget(capacityBytes = Long.MAX_VALUE)
        budget.reserve(UUID.randomUUID(), Long.MAX_VALUE - 10)
        shouldThrow<StagingScratchExhaustedException> { budget.reserve(UUID.randomUUID(), 11) }
        shouldThrow<IllegalArgumentException> { budget.reserve(UUID.randomUUID(), 0) }
    }

    @Test
    fun `two databases that together exceed the aggregate - the second refuses before any file exists`() {
        val reservation = duckProps(scratch).defaultReservationBytes
        val factory = duckFactory(scratch, scratchCapacityBytes = reservation + reservation / 2)
        val first = factory.create(UUID.randomUUID(), StagingEngine.DUCKDB)
        val secondId = UUID.randomUUID()

        val refused = shouldThrow<StagingScratchExhaustedException> { factory.create(secondId, StagingEngine.DUCKDB) }

        println(
            "scratch evidence: reservation=$reservation reserved=${factory.budget.reservedBytes} " +
                "aggregate=${factory.budget.capacityBytes}",
        )
        refused.requestedBytes shouldBe reservation
        refused.reservedBytes shouldBe reservation
        pathsOf(scratch, secondId).shouldBeEmpty()
        factory.budget.reservedBytes shouldBe reservation

        // Release on close makes room: the same request now opens.
        first.close()
        factory.create(secondId, StagingEngine.DUCKDB).close()
        factory.budget.reservedBytes shouldBe 0L
    }

    @Test
    fun `a caller-supplied reservation is what the database reserves and caps its spill at`() {
        val factory = duckFactory(scratch)
        val staging = factory.open(reservationBytes = 300 * MIB)
        factory.budget.reservedBytes shouldBe 300 * MIB
        staging.scalarText("SELECT current_setting('max_temp_directory_size')") shouldBe "300.0 MiB"
        staging.close()
        factory.budget.reservedBytes shouldBe 0L
    }

    @Test
    fun `a crash between reserve and open releases the reservation`() {
        val props = duckProps(scratch)
        val budget = ScratchBudget(props.scratchCapacityBytes)
        val failingOpen = DuckDbStagingFactory(props, budget) { _, _ -> throw SQLException("simulated open failure") }
        val crashingOpen = DuckDbStagingFactory(props, budget) { _, _ -> throw IllegalStateException("simulated crash") }
        val id = UUID.randomUUID()

        shouldThrow<DatapipelinesException> { failingOpen.create(id, StagingEngine.DUCKDB) }.code shouldBe StagingErrorCodes.CREATION_FAILED
        budget.reservedBytes shouldBe 0L
        // Not a catalogued failure — it propagates as itself, and the finally still releases.
        shouldThrow<IllegalStateException> { crashingOpen.create(id, StagingEngine.DUCKDB) }
        budget.reservedBytes shouldBe 0L
        pathsOf(scratch, id).shouldBeEmpty()
    }

    @Test
    fun `a spill past the reservation fails inside the engine as scratch_limit_exceeded`() {
        val factory = duckFactory(scratch, memoryLimitBytes = 64 * MIB)
        val staging = factory.open(reservationBytes = 64 * MIB)
        // ~32 MiB on disk (inside the reservation), ~130 MB to sort: measured (probe 19), the sort's
        // spill passes a 64 MiB cap — and even a 91.5 MiB one — at a 64 MiB memory limit.
        staging.authorExec("CREATE TABLE big AS SELECT md5(range::VARCHAR) || repeat('x', 100) AS s FROM range(1000000)")
        val diskBefore = staging.diskBytes()

        val thrown =
            shouldThrow<StagingScratchLimitException> {
                runBlocking { staging.execute("CREATE TABLE sorted AS SELECT s FROM big ORDER BY s") }
            }

        println("spill evidence: file before sort=$diskBefore reservation=${staging.reservationBytes} reading=${thrown.engineReading}")
        thrown.code shouldBe StagingErrorCodes.SCRATCH_LIMIT_EXCEEDED
        thrown.details["reservation_bytes"] shouldBe 64 * MIB
        // The engine's own refusal, not DP's after-the-fact measurement.
        thrown.engineReading.shouldNotBeNull() shouldMatch Regex("""[0-9.]+ [KMGT]?i?B/[0-9.]+ [KMGT]?i?B""")
        staging.close()
        pathsOf(scratch, staging.executionId).shouldBeEmpty()
    }

    @Test
    fun `a database file that outgrows the reservation fails as scratch_limit_exceeded on DP's measurement`() {
        val staging = duckFactory(scratch).open(reservationBytes = 4 * MIB)

        val thrown =
            shouldThrow<StagingScratchLimitException> {
                runBlocking { staging.execute("CREATE TABLE grow AS SELECT md5(range::VARCHAR) AS s FROM range(1000000)") }
            }

        thrown.engineReading.shouldBeNull()
        thrown.measuredBytes.shouldNotBeNull() shouldBeGreaterThan 4 * MIB
        thrown.details["measured_bytes"] shouldBe thrown.measuredBytes
        staging.close()
    }

    @Test
    fun `a staged table that outgrows the reservation stops at the batch that crossed it`() {
        val staging = duckFactory(scratch).open(reservationBytes = 2 * MIB)
        val rows = generateSequence(0) { it + 1 }.take(1_000_000).map { listOf(UUID.randomUUID().toString()) }

        shouldThrow<StagingScratchLimitException> {
            runBlocking {
                staging.stageRows("stg_grow", listOf(ColumnSchema("s", LogicalType.STRING)), rows)
            }
        }
        // The partial table was rolled back with the failure.
        runBlocking { staging.stats() }.tableCount shouldBe 0
        staging.close()
    }
}
