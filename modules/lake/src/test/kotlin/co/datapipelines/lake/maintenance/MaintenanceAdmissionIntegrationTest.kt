package co.datapipelines.lake.maintenance

import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.lake.LakeTestDb
import co.datapipelines.scheduler.SystemJobOutcome
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * **R5–R8 and R13c of #957** (dp-lake record §11a call 34; acceptance M1, M2): the run row is the exclusion. One
 * `running` row per table, whatever the instance that admitted it; an earlier run decides the admission in ONE
 * statement; no slot and a catalog not in `normal` mode write no row and ask for no lease; only the system identity with
 * no workspace is admitted.
 */
class MaintenanceAdmissionIntegrationTest {
    private val h = MaintenanceHarness()

    @BeforeEach
    fun clean() = h.reset()

    @AfterEach
    fun close() = h.close()

    @Test
    fun `R5 - two admissions at once for one table leave one running row, the loser answering the winner's deadline`() {
        val table = h.table()
        // The winner's admitting statement is held uncommitted on its own connection, so the loser's statement truly
        // overlaps it (forced, never raced): it must wait on the partial unique index and then insert nothing.
        val winnerConnection = LakeTestDb.connect().apply { autoCommit = false }
        try {
            val winnerStore = MaintenanceRunRepository(NamedParameterJdbcTemplate(SingleConnectionDataSource(winnerConnection, true)))
            val winner = winnerStore.admit(UUID.randomUUID(), table.id, MaintenanceRunKind.MAINTENANCE, Duration.ofHours(2), 0)
            winner.shouldBeInstanceOf<Admission.Admitted>()
            val loser =
                CompletableFuture.supplyAsync {
                    h.store.admit(UUID.randomUUID(), table.id, MaintenanceRunKind.MAINTENANCE, Duration.ofHours(2), 0)
                }
            val waited = h.awaitWaiter("Lock") || loser.isDone
            waited shouldBe true
            winnerConnection.commit()
            val answer = loser.get(MaintenanceHarness.WAIT_SECONDS, TimeUnit.SECONDS)
            println("(957 R5) winner=$winner loser=$answer")
            h.running(table) shouldBe 1
            answer.shouldBeInstanceOf<Admission.EarlierRunLive>().deadline shouldBe winner.deadline
            h.rows(table).map { it.id } shouldContainExactly listOf(winner.runId)
        } finally {
            winnerConnection.rollback()
            winnerConnection.close()
        }
    }

    @Test
    fun `R5 - the task's key is the table id - one task per table`() {
        val table = h.table()
        val worker = h.worker(parts = emptyList())
        worker.name shouldBe MaintenanceWorker.NAME
        worker.instances shouldBe "one instance per table"
        worker.run(table.id.toString()) shouldBe SystemJobOutcome.Done
        h.rows(table).single().state shouldBe MaintenanceRunState.FINISHED.wire
    }

    @Test
    fun `R6 - an earlier running row not past its deadline admits nothing and the task comes again at that deadline`() {
        val table = h.table()
        val earlier = h.store.admit(UUID.randomUUID(), table.id, MaintenanceRunKind.MAINTENANCE, Duration.ofHours(2), 0)
        earlier.shouldBeInstanceOf<Admission.Admitted>()
        val part = MarkerPart("cleanup", 1)

        val outcome = h.worker(parts = listOf(part)).run(table.id.toString())

        outcome shouldBe SystemJobOutcome.AgainAt(earlier.deadline)
        h.rows(table).map { it.id to it.state } shouldContainExactly listOf(earlier.runId to "running")
        part.started.get() shouldBe false
        h.leaseRows() shouldBe 0
    }

    @Test
    fun `R6 - past its deadline the old row is abandoned and the new run starts, both from one call`() {
        val table = h.table()
        val old = h.store.admit(UUID.randomUUID(), table.id, MaintenanceRunKind.MAINTENANCE, Duration.ofMillis(SHORT_MILLIS), 0)
        old.shouldBeInstanceOf<Admission.Admitted>()
        h.awaitDbPast(old.deadline)
        h.rows(table).single().state shouldBe "running" // nothing closes it but its successor's admission

        val next = h.store.admit(UUID.randomUUID(), table.id, MaintenanceRunKind.MAINTENANCE, Duration.ofHours(2), 0)

        next.shouldBeInstanceOf<Admission.Admitted>().abandoned shouldContainExactly listOf(old.runId)
        val rows = h.rows(table).associateBy { it.id }
        val abandoned = requireNotNull(rows[old.runId])
        abandoned.state shouldBe "abandoned"
        abandoned.errorCode shouldBe MaintenanceErrors.OUTCOME_NOT_CONFIRMED
        (abandoned.endedAt != null) shouldBe true
        requireNotNull(rows[next.runId]).state shouldBe "running"
        h.running(table) shouldBe 1
    }

    @Test
    fun `R7 - with one slot and one run going, a second table's task comes again 30 seconds ahead and writes no row`() {
        val first = h.table("orders")
        val second = h.table("events")
        val slots = SemaphoreMaintenanceSlots(1)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holding =
            MarkerPart("cleanup", 1, commits = false, before = {
                entered.countDown()
                release.await(MaintenanceHarness.WAIT_SECONDS, TimeUnit.SECONDS)
            })
        val going = CompletableFuture.supplyAsync { h.worker(parts = listOf(holding), slots = slots).run(first.id.toString()) }
        try {
            entered.await(MaintenanceHarness.WAIT_SECONDS, TimeUnit.SECONDS) shouldBe true
            val now = Instant.parse("2026-11-14T03:00:00Z")
            val other = MarkerPart("cleanup", 1)

            val outcome = h.worker(parts = listOf(other), slots = slots, clock = Clock.fixed(now, ZoneOffset.UTC)).run(second.id.toString())

            outcome shouldBe SystemJobOutcome.AgainAt(now.plusSeconds(30))
            h.rows(second).shouldBeEmpty()
            other.started.get() shouldBe false
        } finally {
            release.countDown()
        }
        going.get(MaintenanceHarness.WAIT_SECONDS, TimeUnit.SECONDS) shouldBe SystemJobOutcome.Done
        h.rows(first).single().state shouldBe "finished"
        slots.tryAcquire() shouldBe true // the first run released its slot
    }

    @Test
    fun `R8 - in restoring and in replaying no row is written and no lease is asked for`() {
        val table = h.table()
        try {
            for (mode in listOf("restoring", "replaying")) {
                LakeTestDb.jdbc.update("UPDATE lake_control SET mode = ?", mode)
                val trace = mutableListOf<String>()
                val outcome = h.worker(parts = listOf(MarkerPart("cleanup", 1, trace = trace))).run(table.id.toString())
                println("(957 R8) mode=$mode outcome=$outcome trace=$trace")
                outcome shouldBe SystemJobOutcome.Done
                trace.shouldBeEmpty() // not even a declaration
                h.rows(table).shouldBeEmpty()
                h.leaseRows() shouldBe 0
            }
        } finally {
            LakeTestDb.jdbc.update("UPDATE lake_control SET mode = 'normal'")
        }
    }

    @Test
    fun `R13c - with no workspace row in the database the system identity's run is admitted`() {
        val table = h.table()
        // V4 seeds the `default` workspace; it is taken out for this case and put back as it was.
        val members = LakeTestDb.jdbc.queryForList("SELECT workspace_id, user_id, role FROM workspace_members")
        val workspaces = LakeTestDb.jdbc.queryForList("SELECT id, name, display_name, is_personal, created_by FROM workspaces")
        LakeTestDb.jdbc.update("DELETE FROM workspace_members")
        LakeTestDb.jdbc.update("DELETE FROM workspaces")
        try {
            LakeTestDb.jdbc.queryForObject("SELECT count(*) FROM workspaces", Int::class.java) shouldBe 0
            val part = MarkerPart("cleanup", 1)

            h.worker(parts = listOf(part)).run(table.id.toString()) shouldBe SystemJobOutcome.Done

            h.rows(table).single().state shouldBe "finished"
            h.markers(table) shouldBe setOf("cleanup")
            val event = h.audit.events.single()
            event.event shouldBe LakeMaintenanceAuditEvents.RUN_FINISHED
            event.userId shouldBe h.system.id
        } finally {
            workspaces.forEach {
                LakeTestDb.jdbc.update(
                    "INSERT INTO workspaces (id, name, display_name, is_personal, created_by) VALUES (?, ?, ?, ?, ?)",
                    it["id"],
                    it["name"],
                    it["display_name"],
                    it["is_personal"],
                    it["created_by"],
                )
            }
            members.forEach {
                LakeTestDb.jdbc.update(
                    "INSERT INTO workspace_members (workspace_id, user_id, role) VALUES (?, ?, ?)",
                    it["workspace_id"],
                    it["user_id"],
                    it["role"],
                )
            }
        }
    }

    @Test
    fun `R13c - a super admin's session is refused before the slot is taken and writes no row`() {
        val table = h.table()
        val slots = RecordingSlots()
        val admin =
            AuthenticatedPrincipal(
                userId = UUID.randomUUID(),
                email = "admin@example.com",
                displayName = "Admin",
                authMethod = AuthMethod.OIDC,
                superAdmin = true,
            )
        val part = MarkerPart("cleanup", 1)

        h.worker(parts = listOf(part), slots = slots, principal = { admin }).run(table.id.toString()) shouldBe SystemJobOutcome.Done

        slots.acquired shouldBe 0
        h.rows(table).shouldBeEmpty()
        part.started.get() shouldBe false
        h.audit.events.shouldBeEmpty()
    }

    private class RecordingSlots : MaintenanceSlots {
        var acquired = 0

        override fun tryAcquire(): Boolean {
            acquired++
            return true
        }

        override fun release() = Unit
    }

    private companion object {
        const val SHORT_MILLIS = 300L
    }
}
