package co.datapipelines.lake.maintenance.plan

import co.datapipelines.lake.maintenance.MaintenanceTable
import co.datapipelines.lake.maintenance.plan.MaintenancePartNames.COMPACTION
import co.datapipelines.lake.maintenance.plan.PlanHarness.FileSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

/**
 * #959 D2 over Postgres and MinIO, beside N3/N3b: the two failure boundaries of an attempt (round 2, the lander's R1 and
 * R2). N3c: an abandoned attempt whose `timeout` stamp fails stays retired and tracked. N3d: building the reader's
 * client is inside the attempt, failing at once or after the attempt was abandoned.
 */
class PlanReaderFailuresIntegrationTest {
    private val h = PlanHarness()

    @BeforeEach
    fun clean() = h.reset()

    @AfterEach
    fun close() = h.close()

    private val bound = Duration.ofMillis(TIMEOUT_MILLIS)

    /** Tables with one data file each, all measured once: every row holds good figures. */
    private fun measured(vararg names: String): List<MaintenanceTable> {
        val tables = names.map { h.table(it) }
        tables.forEach { h.append(it, FileSpec("2026-11-01", FILE_BYTES)) }
        h.readers("seed-${h.threadName}").use { seed -> h.pass(h.planner(readers = seed)) }
        tables.forEach { h.measurement(it)?.figures?.currentBytes shouldBe FILE_BYTES }
        return tables
    }

    private fun pageOrder(tables: List<MaintenanceTable>): List<MaintenanceTable> =
        h.repository.page(tables.size, emptySet()).map { target -> tables.single { it.id == target.tableId } }

    /** Waits for a reader thread to end: by joining it, never by sleeping. */
    private fun ends(thread: Thread?) {
        val reader = thread.shouldNotBeNull()
        reader.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS))
        reader.isAlive shouldBe false
    }

    @Test
    fun `N3c - a failed timeout stamp leaves the abandoned reader counted, excluded and retired, and reaches the boundary as thrown`() {
        val (stuck, healthy) = pageOrder(measured("stuck", "healthy"))
        val good = h.measurement(stuck).shouldNotBeNull()
        val healthyBefore = h.measurement(healthy).shouldNotBeNull()
        val stall = h.control.stall(stuck.id)
        val jdbc = StampFailingJdbc(h.named).also { it.failNextTimeoutStamp(stuck.id) }
        val readers = h.readers()
        val planner = h.planner(tableTimeout = bound, maxAbandonedReads = 2, readers = readers, repository = PlanRepository(jdbc))

        // the stamp's own failure is what the scheduler boundary sees: not swallowed, not replaced
        shouldThrow<ExecutionException> { h.pass(planner) }.cause shouldBeSameInstanceAs jdbc.failure
        stall.entered.count shouldBe 0
        stall.released.count shouldBe 1 // the read is still blocked
        // (a) still counted, (b) its table excluded
        readers.outstanding() shouldBe 1
        readers.outstandingTables() shouldBe setOf(stuck.id)
        val abandoned = h.measurement(stuck).shouldNotBeNull()
        abandoned.attemptedAt.isAfter(good.attemptedAt) shouldBe true
        abandoned.errorCode.shouldBeNull() // the stamp never landed; the last good figures are kept
        abandoned.figures shouldBe good.figures

        // (c) a later pass neither reuses the reader nor attempts the table; (d) another table gets a fresh reader
        val readsOfStuck = h.control.metadataReads.count { it == stuck.id }
        val built = h.control.clientsBuilt.get()
        h.pass(planner)
        h.control.metadataReads.count { it == stuck.id } shouldBe readsOfStuck
        h.measurement(stuck)!!.attemptId shouldBe abandoned.attemptId
        h.control.clientsBuilt.get() shouldBe built + 1
        h.control.metadataReaders
            .last { it.first == healthy.id }
            .second shouldNotBe stall.thread
        val fresh = h.measurement(healthy).shouldNotBeNull()
        fresh.errorCode.shouldBeNull()
        fresh.measuredAt!!.isAfter(healthyBefore.measuredAt) shouldBe true
        h.repository.lastPlan()!!.second.let {
            it.tablesMeasured shouldBe 1
            it.tablesNotMeasured shouldBe 0
        }
        readers.outstanding() shouldBe 1

        // release: (e) the abandoned completion never publishes; its client closes, its thread ends, its slot frees
        val closed = h.control.clientsClosed.get()
        h.control.release(stuck.id)
        ends(stall.thread)
        h.control.clientsClosed.get() shouldBe closed + 1
        readers.outstanding() shouldBe 0
        readers.outstandingTables() shouldBe emptySet()
        val late = h.measurement(stuck).shouldNotBeNull()
        late.attemptId shouldBe abandoned.attemptId
        late.measuredAt shouldBe good.measuredAt
        late.figures shouldBe good.figures
        late.errorCode.shouldBeNull()

        // and the table is measured again on the next pass
        h.pass(planner)
        val republished = h.measurement(stuck).shouldNotBeNull()
        republished.errorCode.shouldBeNull()
        republished.measuredAt!!.isAfter(good.measuredAt) shouldBe true
        readers.close()
    }

    @Test
    fun `N3d - a client that cannot be built is a classified measurement failure - good figures kept, rewrite work held back until a measurement succeeds`() {
        val table = h.table("compactable")
        h.assign(table, "development")
        h.append(table, *Array(SMALL_FILES) { FileSpec("2026-11-01", MIB) })
        h.readers("seed-${h.threadName}").use { seed -> h.pass(h.planner(readers = seed)) }
        val good = h.measurement(table).shouldNotBeNull()
        good.errorCode.shouldBeNull()
        h.decide().getValue(table.id).let { (COMPACTION in it.due || COMPACTION in it.held) shouldBe true }

        val factory = ScriptedClientFactory(h::client)
        val readers = h.readers(ioFactory = factory)
        val planner = h.planner(readers = readers)
        factory.failNow()
        h.pass(planner)

        val failed = h.measurement(table).shouldNotBeNull()
        failed.errorCode shouldBe PlanErrors.codeOf(ScriptedClientFactory.ClientBuildFailure())
        failed.errorCode shouldBe "unclassified" // the scripted IllegalStateException is not a catalogued failure
        failed.attemptedAt.isAfter(good.attemptedAt) shouldBe true
        failed.measuredAt shouldBe good.measuredAt
        failed.figures shouldBe good.figures
        h.decide().getValue(table.id).let {
            (COMPACTION in it.due || COMPACTION in it.held) shouldBe false
            it.notDue[COMPACTION] shouldBe NotDueReason.NOT_MEASURED
        }
        h.repository.lastPlan()!!.second.tablesNotMeasured shouldBe 1
        readers.outstanding() shouldBe 0

        // the same reader builds its client on the next use; the measurement succeeds and the work is back
        h.pass(planner)
        val healed = h.measurement(table).shouldNotBeNull()
        healed.errorCode.shouldBeNull()
        healed.measuredAt!!.isAfter(good.measuredAt) shouldBe true
        h.decide().getValue(table.id).let { (COMPACTION in it.due || COMPACTION in it.held) shouldBe true }
        h.liveReaderThreads() shouldBe 1
        readers.close()
    }

    @Test
    fun `N3d - a client construction that fails after the attempt was abandoned ends its reader and frees its slot, every time`() {
        val (table) = measured("late")
        val factory = ScriptedClientFactory(h::client)
        val readers = h.readers(ioFactory = factory)
        // the limit is ONE: a slot that were never freed would stop every later measurement
        val planner = h.planner(tableTimeout = bound, maxAbandonedReads = 1, readers = readers)

        repeat(LATE_ROUNDS) {
            val construction = factory.failLate()
            h.pass(planner)
            construction.entered.count shouldBe 0 // the construction began and is still blocked
            readers.outstanding() shouldBe 1
            readers.outstandingTables() shouldBe setOf(table.id)
            h.measurement(table)!!.errorCode shouldBe PlanErrors.TIMEOUT

            construction.release() // the construction now throws, after its attempt was abandoned
            ends(construction.thread)
            readers.outstanding() shouldBe 0
            readers.outstandingTables() shouldBe emptySet()
            h.measurement(table)!!.errorCode shouldBe PlanErrors.TIMEOUT // the late failure writes nothing
        }

        // the table is selected again, on a fresh reader, within the limit of one
        val before = h.measurement(table).shouldNotBeNull()
        h.pass(planner)
        val measuredAgain = h.measurement(table).shouldNotBeNull()
        measuredAgain.errorCode.shouldBeNull()
        measuredAgain.measuredAt!!.isAfter(before.measuredAt) shouldBe true
        h.repository.lastPlan()!!.second.tablesMeasured shouldBe 1
        readers.close()
    }

    companion object {
        const val TIMEOUT_MILLIS = 400L
        const val FILE_BYTES = 4_096L
        const val MIB = 1_048_576L
        const val SMALL_FILES = 5
        const val LATE_ROUNDS = 3
        const val WAIT_SECONDS = 20L
    }
}
