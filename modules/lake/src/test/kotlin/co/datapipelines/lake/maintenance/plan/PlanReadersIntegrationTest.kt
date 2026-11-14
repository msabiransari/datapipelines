package co.datapipelines.lake.maintenance.plan

import co.datapipelines.lake.maintenance.MaintenanceTable
import co.datapipelines.lake.maintenance.plan.PlanHarness.FileSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * #959 D2 over Postgres and MinIO: N3 (a read that never answers, M4: the planner's own readers, the abandoned-read
 * limit, the late result never written) and N3b (the two fences, each alone).
 */
class PlanReadersIntegrationTest {
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
        // the seed pass's readers are named apart (never counted as this test's) and closed after it
        h.readers("seed-${h.threadName}").use { seed -> h.pass(h.planner(readers = seed)) }
        tables.forEach { h.measurement(it)?.figures?.currentBytes shouldBe FILE_BYTES }
        return tables
    }

    /** The table the next page starts with (the oldest attempt), and the rest in page order. */
    private fun pageOrder(tables: List<MaintenanceTable>): List<MaintenanceTable> =
        h.repository.page(tables.size, emptySet()).map { target -> tables.single { it.id == target.tableId } }

    @Test
    fun `N3 - a stalled read is abandoned at the bound, a later table is measured meanwhile, the late result is never written`() {
        val (stuck, healthy) = pageOrder(measured("stuck", "healthy"))
        val good = h.measurement(stuck).shouldNotBeNull()
        val healthyBefore = h.measurement(healthy).shouldNotBeNull()
        val stall = h.control.stall(stuck.id)
        val readers = h.readers()
        val planner = h.planner(tableTimeout = bound, readers = readers)

        h.pass(planner)

        stall.entered.count shouldBe 0 // the read did reach the stalled stream
        stall.released.count shouldBe 1 // and it is still blocked
        val after = h.measurement(stuck).shouldNotBeNull()
        after.errorCode shouldBe PlanErrors.TIMEOUT
        after.attemptedAt.isAfter(good.attemptedAt) shouldBe true
        after.measuredAt shouldBe good.measuredAt
        after.figures shouldBe good.figures
        h
            .measurement(healthy)
            .shouldNotBeNull()
            .measuredAt!!
            .isAfter(healthyBefore.measuredAt) shouldBe true
        h.repository.lastPlan()!!.second.let {
            it.tablesMeasured shouldBe 1
            it.tablesNotMeasured shouldBe 1
        }
        readers.outstanding() shouldBe 1
        val threads = h.liveReaderThreads()
        threads shouldBe 2 // the abandoned reader and the live one

        // further passes with the read still blocked: no second read of the stuck table, no new reader thread
        val readsOfStuck = h.control.metadataReads.count { it == stuck.id }
        repeat(2) { h.pass(planner) }
        h.control.metadataReads.count { it == stuck.id } shouldBe readsOfStuck
        h.liveReaderThreads() shouldBe threads
        h.measurement(stuck)!!.errorCode shouldBe PlanErrors.TIMEOUT

        // release with NO successor attempt: the late result is not written; its client closes, its thread ends
        val closed = h.control.clientsClosed.get()
        h.control.release(stuck.id)
        h.await("the abandoned reader's thread ends") { h.liveReaderThreads() == 1 }
        h.control.clientsClosed.get() shouldBe closed + 1
        readers.outstanding() shouldBe 0
        val late = h.measurement(stuck).shouldNotBeNull()
        late.errorCode shouldBe PlanErrors.TIMEOUT
        late.measuredAt shouldBe good.measuredAt
        late.figures shouldBe good.figures

        // the next pass picks the table and its attempt publishes
        h.pass(planner)
        val republished = h.measurement(stuck).shouldNotBeNull()
        republished.errorCode.shouldBeNull()
        republished.measuredAt!!.isAfter(good.measuredAt) shouldBe true
        readers.close()
    }

    @Test
    fun `N3 - at the abandoned-read limit the pass measures nothing more and still enqueues from stored figures`() {
        val tables = pageOrder(measured("t1", "t2", "t3", "t4"))
        h.control.stall(tables[0].id)
        h.control.stall(tables[1].id)
        val attemptsBefore = tables.associate { it.id to h.measurement(it)!!.attemptedAt }

        h.pass(h.planner(tableTimeout = bound, maxAbandonedReads = 2, registered = setOf(MaintenancePartNames.CLEANUP)))

        h.measurement(tables[0])!!.errorCode shouldBe PlanErrors.TIMEOUT
        h.measurement(tables[1])!!.errorCode shouldBe PlanErrors.TIMEOUT
        // the page's remaining tables are neither attempted nor stamped
        h.measurement(tables[2])!!.attemptedAt shouldBe attemptsBefore.getValue(tables[2].id)
        h.measurement(tables[3])!!.attemptedAt shouldBe attemptsBefore.getValue(tables[3].id)
        val counts = h.repository.lastPlan()!!.second
        counts.tablesMeasured shouldBe 0
        counts.tablesNotMeasured shouldBe 4
        // every table has its first cleanup due: all four still enqueued, from the stored rows
        counts.tasksEnqueued shouldBe 4
        h.queue.queued.keys shouldBe tables.map { it.id.toString() }.toSet()
    }

    /** A seam that parks one side at its compare-and-set until the other side has passed (or reached) its own. */
    private class Race(
        private val readerFirst: Boolean,
    ) : PlanRaceHooks {
        val passAtBound = CountDownLatch(1)
        val firstDone = CountDownLatch(1)

        override fun beforeReaderSet(tableId: UUID) {
            // the read returns only once the pass is at its bound
            check(passAtBound.await(WAIT_SECONDS, TimeUnit.SECONDS)) { "the pass never reached its bound" }
            if (!readerFirst) check(firstDone.await(WAIT_SECONDS, TimeUnit.SECONDS)) { "the pass never set its state" }
        }

        override fun afterReaderSet(tableId: UUID) {
            if (readerFirst) firstDone.countDown()
        }

        override fun beforePassSet(tableId: UUID) {
            passAtBound.countDown()
            if (readerFirst) check(firstDone.await(WAIT_SECONDS, TimeUnit.SECONDS)) { "the reader never set its state" }
        }

        override fun afterPassSet(tableId: UUID) {
            if (!readerFirst) firstDone.countDown()
        }
    }

    @Test
    fun `N3b - the race, the reader first - exactly one of measured and timed out is recorded - measured`() {
        val table = h.table("race")
        h.append(table, FileSpec("2026-11-01", FILE_BYTES))

        h.pass(h.planner(tableTimeout = bound, hooks = Race(readerFirst = true)))

        val row = h.measurement(table).shouldNotBeNull()
        row.errorCode.shouldBeNull()
        row.figures!!.currentBytes shouldBe FILE_BYTES
        h.repository.lastPlan()!!.second.let {
            it.tablesMeasured shouldBe 1
            it.tablesNotMeasured shouldBe 0
        }
    }

    @Test
    fun `N3b - the race, the pass first - exactly one of measured and timed out is recorded - timed out`() {
        val table = h.table("race")
        h.append(table, FileSpec("2026-11-01", FILE_BYTES))
        val readers = h.readers()

        h.pass(h.planner(tableTimeout = bound, readers = readers, hooks = Race(readerFirst = false)))

        h.await("the losing reader ends") { readers.outstanding() == 0 }
        val row = h.measurement(table).shouldNotBeNull()
        row.errorCode shouldBe PlanErrors.TIMEOUT
        row.measuredAt.shouldBeNull()
        row.figures.shouldBeNull()
        h.repository.lastPlan()!!.second.let {
            it.tablesMeasured shouldBe 0
            it.tablesNotMeasured shouldBe 1
        }
    }

    @Test
    fun `N3b - the stamp - a row whose attempt stamp is newer than a publishing attempt's is not changed`() {
        val table = h.table("stamp")
        val mine = UUID.randomUUID()
        val profile = h.repository.startAttempt(table.id, mine).shouldNotBeNull()
        // another process attempted the table since
        val theirs = UUID.randomUUID()
        h.repository.startAttempt(table.id, theirs).shouldNotBeNull()
        val figures = TableFigures(1, 10, 1, 1, 0, 1, 0, 0, 0, 0)

        h.repository.publish(table.id, mine, figures, profile) shouldBe false
        h.repository.fail(table.id, mine, PlanErrors.TIMEOUT) shouldBe false

        val row = h.measurement(table).shouldNotBeNull()
        row.attemptId shouldBe theirs
        row.measuredAt.shouldBeNull()
        row.errorCode.shouldBeNull()
        h.named.jdbcTemplate.queryForObject(
            "SELECT count(*) FROM lake_storage_usage WHERE table_id = ?",
            Int::class.java,
            table.id,
        ) shouldBe 0
        // and the newer attempt still publishes
        h.repository.publish(table.id, theirs, figures, profile) shouldBe true
        h.measurement(table)!!.figures!!.currentBytes shouldBe 10
    }

    @Test
    fun `the readers - a read that ends in time leaves its reader for the next table`() {
        val tables = measured("r1", "r2", "r3")
        val readers = h.readers()
        h.pass(h.planner(readers = readers))
        h.control.clientsBuilt.get() shouldBe 2 // one for the seed pass, one for this pass's single reader
        h.liveReaderThreads() shouldBe 1
        tables.map { h.measurement(it)!!.errorCode } shouldContainExactly listOf(null, null, null)
        readers.close()
    }

    companion object {
        const val TIMEOUT_MILLIS = 400L
        const val FILE_BYTES = 4_096L
        const val WAIT_SECONDS = 20L
    }
}
