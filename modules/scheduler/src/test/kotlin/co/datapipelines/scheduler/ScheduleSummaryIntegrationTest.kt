package co.datapipelines.scheduler

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * #584 — the Schedules home's read over the real V38 tables: [ScheduleRunRepository.latestStates]
 * answers each schedule's NEWEST run in one statement, inside the workspace only, and
 * [ScheduleService.summary] counts exactly what [ScheduleService.list] lists, lens included.
 */
class ScheduleSummaryIntegrationTest {
    private lateinit var h: SchedulerHarness

    @BeforeEach
    fun setUp() {
        SchedulerTestDb.reset()
        h = SchedulerHarness()
    }

    @Test
    fun `the latest run per schedule is the newest by created_at - a schedule with no run is absent`() {
        val a = h.create(h.request(name = "ops/a", payload = FakeExecutor.payload("a")))
        val b = h.create(h.request(name = "ops/b", payload = FakeExecutor.payload("b")))
        val none = h.create(h.request(name = "ops/c", payload = FakeExecutor.payload("c")))
        insertRun(a.id, RunState.FAILED, AT)
        insertRun(a.id, RunState.SUCCEEDED, AT.plusSeconds(60))
        insertRun(b.id, RunState.SUCCEEDED, AT)
        insertRun(b.id, RunState.SKIPPED, AT.plusSeconds(120))

        h.runs.latestStates(SchedulerTestDb.WORKSPACE, listOf(a.id, b.id, none.id)) shouldBe
            mapOf(a.id to RunState.SUCCEEDED, b.id to RunState.SKIPPED)
        h.runs.latestStates(SchedulerTestDb.OTHER_WORKSPACE, listOf(a.id, b.id)) shouldBe emptyMap()
        h.runs.latestStates(SchedulerTestDb.WORKSPACE, emptyList()) shouldBe emptyMap()
    }

    @Test
    fun `the summary counts what the list lists - the lens narrows both alike`() {
        val ok = h.create(h.request(name = "ops/ok", payload = FakeExecutor.payload("a")))
        val paused = h.create(h.request(name = "ops/paused", payload = FakeExecutor.payload("a")))
        val hidden = h.create(h.request(name = "ops/hidden", payload = FakeExecutor.payload("b")))
        h.service.pause(SchedulerTestDb.WORKSPACE, paused.id, SchedulerTestDb.CREATOR)
        insertRun(ok.id, RunState.SUCCEEDED, AT)
        insertRun(hidden.id, RunState.FAILED, AT)

        val everyone = h.service.summary(SchedulerTestDb.WORKSPACE, TargetViewer.EVERYONE)
        everyone.total shouldBe h.service.list(SchedulerTestDb.WORKSPACE, null, LIMIT, 0, TargetViewer.EVERYONE).size
        listOf(everyone.total, everyone.enabled, everyone.paused, everyone.blocked) shouldBe listOf(3, 2, 1, 0)
        everyone.lastRun.getValue(LastRunOutcome.FAILED) shouldBe 1
        // One cron for all three: one next_due_at, so the name breaks the tie; the paused one never qualifies.
        everyone.upNext.map { it.name } shouldContainExactly listOf("ops/hidden", "ops/ok")

        h.executor.lens = setOf("job:a")
        val narrowed = h.service.summary(SchedulerTestDb.WORKSPACE, NarrowedViewer)
        narrowed.total shouldBe h.service.list(SchedulerTestDb.WORKSPACE, null, LIMIT, 0, NarrowedViewer).size
        listOf(narrowed.total, narrowed.enabled, narrowed.paused) shouldBe listOf(2, 1, 1)
        narrowed.lastRun.getValue(LastRunOutcome.FAILED) shouldBe 0
        narrowed.upNext.map { it.name } shouldContainExactly listOf("ops/ok")
        (narrowed.recentlyChanged.map { it.name }.toSet()) shouldBe setOf("ops/ok", "ops/paused")
        h.service.summary(SchedulerTestDb.OTHER_WORKSPACE, TargetViewer.EVERYONE).total shouldBe 0
    }

    /** A terminal run row straight into `schedule_runs` — the summary reads states, not how they were reached. */
    private fun insertRun(
        scheduleId: UUID,
        state: RunState,
        at: Instant,
    ) {
        SchedulerTestDb.jdbc.update(
            """
            INSERT INTO schedule_runs (id, schedule_id, workspace_id, origin, scheduled_at, reference_at, reference_timezone,
                                       admit_by, executor_id, payload_schema_version, payload_json, actor_user_id, state,
                                       created_at, updated_at)
            VALUES (:id, :sid, :ws, 'cron', :at, :at, 'UTC', :at, :executor, 1, '{}'::jsonb, :actor, :state, :at, :at)
            """.trimIndent(),
            mapOf(
                "id" to UUID.randomUUID(),
                "sid" to scheduleId,
                "ws" to SchedulerTestDb.WORKSPACE,
                "at" to Timestamp.from(at),
                "executor" to FakeExecutor.ID,
                "actor" to SchedulerTestDb.SYSTEM_ACTOR,
                "state" to state.wire,
            ),
        )
    }

    private companion object {
        const val LIMIT = 1000
        val AT: Instant = Instant.parse("2026-09-25T11:00:00Z")
    }
}
