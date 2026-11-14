package co.datapipelines.lake.maintenance

import co.datapipelines.lake.LakeTestDb
import co.datapipelines.persistence.FailureShape
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineJson
import co.datapipelines.scheduler.SystemJobOutcome
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * **R10–R12 of #957** (dp-lake record §8a, §11, §11a call 34; acceptance E1 case (k), M2): every lease a run takes carries
 * the RUN's deadline, so nothing gives an expired run fresh authority, and nothing is concluded from an abandoned row.
 */
class MaintenanceDeadlineIntegrationTest {
    private val h = MaintenanceHarness()
    private val json = PipelineJson.objectMapper()

    @BeforeEach
    fun clean() = h.reset()

    @AfterEach
    fun close() = h.close()

    @Test
    fun `R10 - a write started after the run's deadline under a lease taken before it is refused by the fence with DP001`() {
        val table = h.table()
        val revisionsBefore = h.revisions(table)
        val refusal = AtomicReference<Throwable>()
        val hooks =
            object : MaintenanceRunHooks {
                // The lease is held (acquired with the run's deadline); the catalog write transaction has not started.
                override fun beforeWrite(run: MaintenanceRun) = h.awaitDbPast(run.deadline)

                override fun writeRefused(
                    run: MaintenanceRun,
                    failure: Throwable,
                ) = refusal.set(failure)
            }
        val part = MarkerPart("cleanup", 1)

        val outcome =
            h
                .worker(
                    parts = listOf(part),
                    hooks = hooks,
                    jobTimeout = Duration.ofMillis(R10_TIMEOUT_MILLIS),
                ).run(table.id.toString())

        outcome shouldBe SystemJobOutcome.Done
        val failure = requireNotNull(refusal.get()) { "the fence never refused the write" }
        println("(957 R10) refusal=${FailureShape.cause(failure)} sqlstate=${FailureShape.sqlState(failure)}")
        FailureShape.sqlState(failure) shouldBe "DP001"
        h.markers(table) shouldBe emptySet()
        h.revisions(table) shouldBe revisionsBefore
        val row = h.rows(table).single()
        row.state shouldBe "finished"
        parts(row) shouldBe listOf(Triple("cleanup", "deadline", true))
    }

    @Test
    fun `R11 - after a takeover the old run's next commit is refused lake maintenance deadline`() {
        val table = h.table()
        val old = h.store.admit(UUID.randomUUID(), table.id, MaintenanceRunKind.MAINTENANCE, Duration.ofMillis(SHORT_MILLIS), 0)
        old.shouldBeInstanceOf<Admission.Admitted>()
        h.awaitDbPast(old.deadline)
        val successor = h.store.admit(UUID.randomUUID(), table.id, MaintenanceRunKind.MAINTENANCE, Duration.ofHours(2), 0)
        successor.shouldBeInstanceOf<Admission.Admitted>().abandoned shouldBe listOf(old.runId)
        val oldRun = MaintenanceRunContext(h.leases, h.defs.catalogs, h.heartbeats).open(old.runId, table, old.deadline, emptyMap())

        val answer = oldRun.commit { it.updateProperties().set(MaintenanceHarness.MARKER + "late", old.runId.toString()) }

        answer shouldBe Guarded.Refused(MaintenanceOutcome.DEADLINE, PipelineErrorCodes.Lake.MAINTENANCE_DEADLINE)
        h.markers(table) shouldBe emptySet()
        h.leaseRows() shouldBe 0
    }

    @Test
    fun `R12 - a successor finds the old commit in flight - busy ends its run, and the old row stays abandoned`() {
        val table = h.table()
        val lock = LakeTestDb.connect()
        installHoldTrigger()
        try {
            hold(lock)
            // The OLD worker: its own slot and a granted scratch reservation; its commit passes the fence and is held.
            val oldSlots = SemaphoreMaintenanceSlots(1)
            val oldScratchClosed = AtomicInteger()
            val oldScratch = MaintenanceScratch { _ -> AutoCloseable { oldScratchClosed.incrementAndGet() } }
            val oldPart = MarkerPart("cleanup", 1, declaration = MaintenanceDeclaration(expectsDue = true, scratchBytes = SCRATCH))
            val oldWorker = h.worker(parts = listOf(oldPart), slots = oldSlots, scratch = oldScratch, jobTimeout = Duration.ofSeconds(2))
            val oldDone = CompletableFuture.supplyAsync { oldWorker.run(table.id.toString()) }
            h.awaitWaiter("Lock") shouldBe true
            val oldRow = h.rows(table).single()
            h.awaitDbPast(oldRow.deadline)

            // The successor: two due parts. The first's acquire meets the old commit's FOR SHARE on the lease row.
            val snapshot = AtomicReference<MaintenanceRunRow>()
            val first = MarkerPart("cleanup", 1, before = { snapshot.set(h.store.find(oldRow.id)) })
            val firstThenRelease =
                object : MaintenancePart by first {
                    override fun run(run: MaintenanceRun): PartResult = first.run(run).also { release(lock) }
                }
            val second = MarkerPart("rewrite", 2)
            val successor = h.worker(parts = listOf(firstThenRelease, second))

            successor.run(table.id.toString()) shouldBe SystemJobOutcome.Done

            first.started.get() shouldBe true
            second.started.get() shouldBe false
            val rows = h.rows(table).associateBy { it.id }
            val successorRow = rows.values.single { it.id != oldRow.id }
            successorRow.state shouldBe "finished"
            parts(successorRow) shouldBe listOf(Triple("cleanup", "busy", true), Triple("rewrite", "busy", false))

            // The OLD worker runs to the end of its finalization: its row is exactly the successor's takeover.
            oldDone.get(MaintenanceHarness.WAIT_SECONDS, TimeUnit.SECONDS) shouldBe SystemJobOutcome.Done
            val taken = requireNotNull(snapshot.get())
            taken.state shouldBe "abandoned"
            taken.errorCode shouldBe MaintenanceErrors.OUTCOME_NOT_CONFIRMED
            h.store.find(oldRow.id) shouldBe taken
            val oldAudit = h.audit.events.single { it.details["run_id"] == oldRow.id.toString() }
            oldAudit.details["state"] shouldBe "abandoned"
            oldAudit.details["outcome_confirmed"] shouldBe false
            oldScratchClosed.get() shouldBe 1
            oldSlots.tryAcquire() shouldBe true

            // A later run reads the landed state: the old commit did land, and nothing in the rows concluded it.
            val seen = AtomicReference<String>()
            val reader = MarkerPart("verify", 1, before = { seen.set(h.properties(table)[MaintenanceHarness.MARKER + "cleanup"]) })
            h.worker(parts = listOf(reader)).run(table.id.toString()) shouldBe SystemJobOutcome.Done
            seen.get() shouldBe oldRow.id.toString()
            h.markers(table) shouldBe setOf("cleanup", "verify")
        } finally {
            runCatching { release(lock) }
            lock.close()
            LakeTestDb.jdbc.execute("DROP TRIGGER IF EXISTS zz_test_957_hold ON iceberg_tables")
            LakeTestDb.jdbc.execute("DROP FUNCTION IF EXISTS test_957_hold()")
        }
    }

    /** The run row's parts as (name, outcome, attempted). */
    private fun parts(row: MaintenanceRunRow): List<Triple<String, String, Boolean>> =
        json.readTree(row.partsJson).map { Triple(it["name"].asText(), it["outcome"].asText(), it["attempted"].asBoolean()) }

    /**
     * An AFTER UPDATE trigger on the catalog row that waits for advisory lock 957: a commit that has passed the fence (the
     * BEFORE trigger, which took FOR SHARE on the lease row) is held inside its transaction until the test lets it go.
     */
    private fun installHoldTrigger() {
        LakeTestDb.jdbc.execute(
            "CREATE OR REPLACE FUNCTION test_957_hold() RETURNS trigger LANGUAGE plpgsql AS " +
                "\$\$ BEGIN PERFORM pg_advisory_xact_lock_shared($ADVISORY_KEY); RETURN NEW; END \$\$",
        )
        LakeTestDb.jdbc.execute(
            "CREATE TRIGGER zz_test_957_hold AFTER UPDATE ON iceberg_tables FOR EACH ROW EXECUTE FUNCTION test_957_hold()",
        )
    }

    private fun hold(connection: java.sql.Connection) {
        connection.createStatement().use { it.execute("SELECT pg_advisory_lock($ADVISORY_KEY)") }
    }

    private fun release(connection: java.sql.Connection) {
        connection.createStatement().use { it.execute("SELECT pg_advisory_unlock_all()") }
    }

    private companion object {
        const val R10_TIMEOUT_MILLIS = 1500L
        const val SHORT_MILLIS = 300L
        const val SCRATCH = 1024L
        const val ADVISORY_KEY = 957
    }
}
