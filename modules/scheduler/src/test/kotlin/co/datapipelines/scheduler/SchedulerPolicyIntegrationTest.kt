package co.datapipelines.scheduler

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/**
 * **The §3 policies** (scheduler design revision §3, D-9.6, R4): the lateness window, skip versus
 * latest-only catch-up after an outage (one missed-range summary, never a replay loop), the overlap
 * guard, pause/resume without catch-up, capacity pressure retried inside the window and
 * `not_started` beyond it, and the shutdown gate that leaves a run queued, never lost.
 */
class SchedulerPolicyIntegrationTest {
    @BeforeEach
    fun clean() = SchedulerTestDb.reset()

    @Test
    fun `an on-time occurrence is queued as cron and enqueued`() {
        val h = SchedulerHarness()
        val schedule = h.create()
        h.setTime(schedule.nextDueAt!!.plusSeconds(30))

        h.tick().queued shouldBe 1

        val run = h.runsOf(schedule.id).single()
        run.origin shouldBe RunOrigin.CRON
        run.state shouldBe RunState.QUEUED
        run.scheduledAt shouldBe schedule.nextDueAt
        run.admitBy shouldBe schedule.nextDueAt.plus(h.properties.lateness)
        run.actorUserId shouldBe SchedulerTestDb.SYSTEM_ACTOR
        h.runTasks() shouldContainExactly listOf(run.id.toString())
        h.schedules.findLive(SchedulerTestDb.WORKSPACE, schedule.id)!!.nextDueAt shouldBe schedule.nextDueAt.plus(Duration.ofHours(1))
    }

    @Test
    fun `skip - a three-hour outage is ONE missed-range summary, nothing runs, no replay`() {
        val h = SchedulerHarness()
        val schedule = h.create()
        val first = schedule.nextDueAt!!
        h.setTime(first.plus(Duration.ofHours(3)).plus(Duration.ofMinutes(20)))

        val report = h.tick()

        // 13:00 … 16:00 fell due; the latest (16:00) is already past its 10-minute window, so every
        // one of the four is in the one summary row keyed by the first — none is fabricated as a run.
        report.queued shouldBe 0
        val run = h.runsOf(schedule.id).single()
        run.state shouldBe RunState.SKIPPED
        run.reason shouldBe RunReasons.MISSED
        run.scheduledAt shouldBe first
        val summary =
            h.runs
                .trail(run.id)
                .single()
                .details
        summary.path("missed_count").asInt() shouldBe 4
        summary.path("last_missed_at").asText() shouldBe first.plus(Duration.ofHours(3)).toString()
        h.runTasks() shouldHaveSize 0
    }

    @Test
    fun `skip - an outage whose latest occurrence is still inside the window runs it and summarizes the rest`() {
        val h = SchedulerHarness()
        val schedule = h.create()
        val first = schedule.nextDueAt!!
        h.setTime(first.plus(Duration.ofHours(3)).plus(Duration.ofMinutes(4)))

        h.tick().queued shouldBe 1

        val runs = h.runsOf(schedule.id).sortedBy { it.scheduledAt }
        runs.map { it.state } shouldContainExactly listOf(RunState.SKIPPED, RunState.QUEUED)
        h.runs
            .trail(runs[0].id)
            .single()
            .details
            .path("missed_count")
            .asInt() shouldBe 3
        runs[1].origin shouldBe RunOrigin.CRON
    }

    @Test
    fun `latest - the same outage catches up the latest occurrence once, the rest are summarized`() {
        val h = SchedulerHarness()
        val schedule = h.create(h.request(policy = "latest"))
        val first = schedule.nextDueAt!!
        val now = first.plus(Duration.ofHours(3)).plus(Duration.ofMinutes(20))
        h.setTime(now)

        h.tick().catchUp shouldBe 1

        val runs = h.runsOf(schedule.id).sortedBy { it.scheduledAt }
        runs shouldHaveSize 2
        runs[0].state shouldBe RunState.SKIPPED // 3 missed, summarized
        runs[1].origin shouldBe RunOrigin.CATCH_UP
        runs[1].state shouldBe RunState.QUEUED
        runs[1].scheduledAt shouldBe first.plus(Duration.ofHours(3)) // keeps the occurrence's own instant
        runs[1].admitBy shouldBe now.plus(h.properties.lateness) // its window opens when recorded
    }

    @Test
    fun `latest never reaches past the catch-up max age`() {
        val h = SchedulerHarness(properties = SchedulerProperties(minIntervalSeconds = 60, catchUpMaxAgeSeconds = 3600))
        val schedule = h.create(h.request(policy = "latest", cron = "0 0 * * *"))
        h.setTime(schedule.nextDueAt!!.plus(Duration.ofHours(5)))

        h.tick().catchUp shouldBe 0

        h.runsOf(schedule.id).single().reason shouldBe RunReasons.MISSED
    }

    @Test
    fun `overlap - a due occurrence while a run is active is recorded skipped, and Run now answers 409`() {
        val h = SchedulerHarness()
        val schedule = h.create()
        h.setTime(schedule.nextDueAt!!.plusSeconds(5))
        h.tick()
        h.worker.handle(h.runsOf(schedule.id).single().id) // running now

        h.setTime(schedule.nextDueAt.plus(Duration.ofHours(1)).plusSeconds(5))
        h.tick().overlap shouldBe 1
        val runNow =
            shouldThrow<ScheduleException> { h.service.runNow(SchedulerTestDb.WORKSPACE, schedule.id, SchedulerTestDb.CREATOR, null) }

        runNow.code shouldBe ScheduleErrorCodes.RUN_OVERLAP
        h.runsOf(schedule.id).count { it.reason == RunReasons.OVERLAP } shouldBe 1
    }

    @Test
    fun `paused - nothing is recorded while paused, and resume does not catch up`() {
        val h = SchedulerHarness()
        val schedule = h.create()
        h.service.pause(SchedulerTestDb.WORKSPACE, schedule.id, SchedulerTestDb.CREATOR)
        h.setTime(schedule.nextDueAt!!.plus(Duration.ofHours(4)))
        h.tick().total shouldBe 0

        val resumed = h.service.resume(SchedulerTestDb.WORKSPACE, schedule.id, SchedulerTestDb.CREATOR)
        h.tick().total shouldBe 0

        resumed.nextDueAt!!.isAfter(h.clock.instant()) shouldBe true
        h.runsOf(schedule.id) shouldHaveSize 0
    }

    @Test
    fun `a run queued before a pause is skipped at admission - a manual run on a paused schedule is not`() {
        val h = SchedulerHarness()
        val schedule = h.create()
        h.setTime(schedule.nextDueAt!!.plusSeconds(5))
        h.tick()
        val queued = h.runsOf(schedule.id).single()
        h.service.pause(SchedulerTestDb.WORKSPACE, schedule.id, SchedulerTestDb.CREATOR)

        h.worker.handle(queued.id)
        h.runs.find(queued.id)!!.reason shouldBe RunReasons.SCHEDULE_PAUSED

        val manual = h.service.runNow(SchedulerTestDb.WORKSPACE, schedule.id, SchedulerTestDb.CREATOR, null).value
        h.worker.handle(manual.id)
        h.runs.find(manual.id)!!.state shouldBe RunState.RUNNING
        manual.requestedBy shouldBe SchedulerTestDb.CREATOR
        manual.actorUserId shouldBe SchedulerTestDb.SYSTEM_ACTOR
    }

    @Test
    fun `a deleted schedule's queued run is skipped - its runs and trail stay`() {
        val h = SchedulerHarness()
        val schedule = h.create()
        h.setTime(schedule.nextDueAt!!.plusSeconds(5))
        h.tick()
        val queued = h.runsOf(schedule.id).single()
        h.service.delete(SchedulerTestDb.WORKSPACE, schedule.id, SchedulerTestDb.CREATOR, schedule.revision)

        h.worker.handle(queued.id)

        h.runs.find(queued.id)!!.reason shouldBe RunReasons.SCHEDULE_DELETED
        h.runs.trail(queued.id) shouldHaveSize 2
        h.executor.startCount.get() shouldBe 0
    }

    @Test
    fun `capacity pressure - retried within the lateness window, counted, then not started beyond it (R4)`() {
        val h = SchedulerHarness(capacity = FakeCapacityGate(free = 0))
        val schedule = h.create()
        val due = schedule.nextDueAt!!
        h.setTime(due.plusSeconds(5))
        h.tick()
        val run = h.runsOf(schedule.id).single()

        val first = h.worker.handle(run.id)
        first.shouldBeInstanceOf<WorkerDecision.RetryAt>()
        first.at shouldBe due.plusSeconds(5).plus(ScheduledRunWorker.CAPACITY_RETRY)
        h.runs.find(run.id)!!.attempts shouldBe 1
        h.registry.counter(SchedulerMetrics.CAPACITY_RETRIES).count() shouldBe 1.0

        // The window closes with capacity still refused.
        h.setTime(run.admitBy.plusSeconds(1))
        h.worker.handle(run.id) shouldBe WorkerDecision.Done

        val after = h.runs.find(run.id)!!
        after.state shouldBe RunState.NOT_STARTED
        after.reason shouldBe RunReasons.CAPACITY
        h.executor.startCount.get() shouldBe 0
        h.runs.trail(run.id).map { it.kind } shouldContainExactly
            listOf(TrailKind.RECORDED, TrailKind.CAPACITY_RETRY, TrailKind.NOT_STARTED)
    }

    @Test
    fun `capacity freed inside the window - the retry launches`() {
        val gate = FakeCapacityGate(free = 0)
        val h = SchedulerHarness(capacity = gate)
        val schedule = h.create()
        h.setTime(schedule.nextDueAt!!.plusSeconds(5))
        h.tick()
        val run = h.runsOf(schedule.id).single()
        h.worker.handle(run.id)

        gate.free = 1
        h.setTime(h.clock.instant().plus(ScheduledRunWorker.CAPACITY_RETRY))
        h.worker.handle(run.id)

        h.runs.find(run.id)!!.state shouldBe RunState.RUNNING
        gate.held.get() shouldBe 1 // handed to the execution, not released by the scheduler
    }

    @Test
    fun `a run past its window with no capacity attempt is missed, not started`() {
        val h = SchedulerHarness()
        val schedule = h.create()
        h.setTime(schedule.nextDueAt!!.plusSeconds(5))
        h.tick()
        val run = h.runsOf(schedule.id).single()

        h.setTime(run.admitBy.plusSeconds(1)) // the task sat unpicked past the window (an outage)
        h.worker.handle(run.id)

        h.runs.find(run.id)!!.state shouldBe RunState.SKIPPED
        h.runs.find(run.id)!!.reason shouldBe RunReasons.MISSED
    }

    @Test
    fun `the shutdown gate - a closed gate reschedules the task and leaves the run queued, never lost`() {
        val h = SchedulerHarness()
        val schedule = h.create()
        h.setTime(schedule.nextDueAt!!.plusSeconds(5))
        h.tick()
        val run = h.runsOf(schedule.id).single()
        h.admission.stop()

        val decision = h.worker.handle(run.id)

        decision.shouldBeInstanceOf<WorkerDecision.RetryAt>()
        h.runs.find(run.id)!!.state shouldBe RunState.QUEUED
        h.executor.startCount.get() shouldBe 0
        h.capacity.held.get() shouldBe 0
    }

    @Test
    fun `a schedule in a deactivated workspace is not dispatched`() {
        val h = SchedulerHarness()
        val schedule = h.create()
        SchedulerTestDb.jdbc.update("UPDATE workspaces SET deactivated_at = now() WHERE id = :id", mapOf("id" to SchedulerTestDb.WORKSPACE))
        h.setTime(schedule.nextDueAt!!.plusSeconds(5))

        h.tick().total shouldBe 0
    }

    @Test
    fun `an edit of the cron recomputes next due from now - old-pattern occurrences are not missed`() {
        val h = SchedulerHarness()
        val schedule = h.create()
        h.setTime(schedule.nextDueAt!!.plus(Duration.ofHours(2)))
        val edited =
            h.service.update(
                SchedulerTestDb.WORKSPACE,
                schedule.id,
                SchedulerTestDb.CREATOR,
                schedule.revision,
                h.request(cron = "30 * * * *"),
            )

        edited.nextDueAt!!.isAfter(h.clock.instant()) shouldBe true
        h.tick().total shouldBe 0
        h.runsOf(schedule.id) shouldHaveSize 0
    }

    @Test
    fun `the admission gate is the ONLY thing that closed - reopening admits again`() {
        val h = SchedulerHarness()
        h.admission.isOpen shouldBe true
        h.admission.stop()
        h.admission.isOpen shouldBe false
        h.admission.start()
        h.admission.admit { 1 } shouldBe 1
        Instant.EPOCH shouldBe Instant.EPOCH
    }
}
