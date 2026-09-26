package co.datapipelines.scheduler

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID

/**
 * **The crash windows** (scheduler design revision §2.1, §8): before the claim, after the claim but
 * before the launch, during the work, after the remote commit but before the bookkeeping — each
 * recovers conservatively and none repeats an effect. And the reconciler's R6 mapping: a lost
 * heartbeat (`instance_lost`) is `unknown` and BLOCKS; a later real terminal is an update about the
 * same run (A5).
 *
 * A "crash" is the state the crash leaves in the database, produced step by step — never a real
 * killed thread and never a race.
 */
class SchedulerRecoveryIntegrationTest {
    private lateinit var h: SchedulerHarness
    private lateinit var schedule: Schedule

    @BeforeEach
    fun setUp() {
        SchedulerTestDb.reset()
        h = SchedulerHarness()
        schedule = h.create()
        h.setTime(schedule.nextDueAt!!.plusSeconds(3))
        h.tick()
    }

    @Test
    fun `crash before the claim - the run is still queued, a revived delivery admits it once`() {
        // The first worker took capacity and prepared, then died: nothing durable changed.
        val run = onlyRun()
        h.executor.prepare(admissionOf(run))
        run.state shouldBe RunState.QUEUED

        val revived = SchedulerHarness(settable = h.settable, executor = h.executor)
        revived.worker.handle(run.id) shouldBe WorkerDecision.Done

        h.executor.startCount.get() shouldBe 1
        h.runs.find(run.id)!!.state shouldBe RunState.RUNNING
    }

    @Test
    fun `crash after the claim, before the launch - never launched again, unknown and the schedule blocked`() {
        val run = onlyRun()
        val executionId = UUID.randomUUID()
        // The claim committed (state `starting`, the reference recorded) and the worker died before start().
        h.transactions.execute {
            h.runs.claim(
                run.id,
                executionId,
                h.executor.prepare(admissionOf(run)).let {
                    (it as Preparation.Prepared).snapshot
                },
                "dead",
                h.clock.instant(),
            )
        }

        // The task's heartbeat stopped; db-scheduler revives it on another instance.
        val revived = SchedulerHarness(settable = h.settable, executor = h.executor)
        revived.worker.handle(run.id) shouldBe WorkerDecision.Done

        h.executor.startCount.get() shouldBe 0
        val after = h.runs.find(run.id)!!
        after.state shouldBe RunState.UNKNOWN
        after.reason shouldBe RunReasons.START_UNCONFIRMED
        after.executionId shouldBe executionId
        blockedReason() shouldBe BLOCKED_BY_UNKNOWN_RUN
    }

    @Test
    fun `crash after the claim - an execution that DID start is recorded running, not unknown`() {
        val run = onlyRun()
        val executionId = UUID.randomUUID()
        h.transactions.execute { h.runs.claim(run.id, executionId, prepared(run), "dead", h.clock.instant()) }
        h.executor.outcomes[executionId] = ExecutionOutcome.Running // the launch happened before the crash

        SchedulerHarness(settable = h.settable, executor = h.executor).worker.handle(run.id)

        h.executor.startCount.get() shouldBe 0
        h.runs.find(run.id)!!.state shouldBe RunState.RUNNING
        blockedReason() shouldBe null
    }

    @Test
    fun `the reconciler's backstop - a starting claim past the start grace with no execution becomes unknown`() {
        val run = onlyRun()
        val executionId = UUID.randomUUID()
        h.transactions.execute { h.runs.claim(run.id, executionId, prepared(run), "dead", h.clock.instant()) }

        h.reconciler.tick() shouldBe 0 // inside the grace: the claiming worker may still be launching
        h.setTime(
            h.clock
                .instant()
                .plus(h.properties.startGrace)
                .plusSeconds(1),
        )
        h.reconciler.tick() shouldBe 1

        h.runs.find(run.id)!!.state shouldBe RunState.UNKNOWN
        blockedReason() shouldBe BLOCKED_BY_UNKNOWN_RUN
    }

    @Test
    fun `during the work, heartbeat lost - instance_lost is unknown and blocks the schedule (A5, R6)`() {
        val run = startedRun()
        h.executor.outcomes[run.executionId!!] = ExecutionOutcome.Finished(RunState.UNKNOWN, "instance_lost")

        h.reconciler.tick() shouldBe 1

        val after = h.runs.find(run.id)!!
        after.state shouldBe RunState.UNKNOWN
        after.reason shouldBe "instance_lost"
        blockedReason() shouldBe BLOCKED_BY_UNKNOWN_RUN
        // A blocked schedule records nothing more, however long it stays blocked.
        h.setTime(h.clock.instant().plus(Duration.ofHours(3)))
        h.tick().total shouldBe 0
    }

    @Test
    fun `after the remote commit, before the bookkeeping - a later real terminal updates the SAME run, the block stays`() {
        val run = startedRun()
        h.executor.outcomes[run.executionId!!] = ExecutionOutcome.Finished(RunState.UNKNOWN, "instance_lost")
        h.reconciler.tick()
        // The stalled worker was alive after all and overwrote instance_lost with SUCCESS (A5).
        h.executor.outcomes[run.executionId] = ExecutionOutcome.Finished(RunState.SUCCEEDED, null)

        h.reconciler.tick() shouldBe 1

        h.runs.find(run.id)!!.state shouldBe RunState.SUCCEEDED
        h.runs
            .trail(run.id)
            .map { it.kind }
            .takeLast(2) shouldContainExactly listOf(TrailKind.UNKNOWN, TrailKind.UPDATED_AFTER_UNKNOWN)
        blockedReason() shouldBe BLOCKED_BY_UNKNOWN_RUN // a person unblocks; nothing clears it silently
        h.executor.startCount.get() shouldBe 1 // and nothing ran twice
    }

    @Test
    fun `R6's table through the reconciler - succeeded, failed, cancelled and aborted are terminal and do not block`() {
        val outcomes =
            listOf(
                ExecutionOutcome.Finished(RunState.SUCCEEDED, null) to RunState.SUCCEEDED,
                ExecutionOutcome.Finished(RunState.FAILED, "execution_failed") to RunState.FAILED,
                ExecutionOutcome.Finished(RunState.CANCELLED, "cancelled") to RunState.CANCELLED,
                ExecutionOutcome.Finished(RunState.ABORTED, "shutdown") to RunState.ABORTED,
            )
        outcomes.forEach { (outcome, expected) ->
            SchedulerTestDb.reset()
            h = SchedulerHarness()
            schedule = h.create()
            h.setTime(schedule.nextDueAt!!.plusSeconds(3))
            h.tick()
            val run = startedRun()
            h.executor.outcomes[run.executionId!!] = outcome
            h.reconciler.tick() shouldBe 1
            h.runs.find(run.id)!!.state shouldBe expected
            blockedReason() shouldBe null
        }
    }

    @Test
    fun `a start that throws is unknown - the scheduler cannot know whether work began - and blocks`() {
        val run = onlyRun()
        h.executor.startOutcome = { error("the database connection dropped mid-launch") }

        h.worker.handle(run.id) shouldBe WorkerDecision.Done

        h.runs.find(run.id)!!.state shouldBe RunState.UNKNOWN
        h.runs.find(run.id)!!.reason shouldBe RunReasons.START_FAILED
        h.capacity.held.get() shouldBe 0 // the lease was not handed over, so it was released
        blockedReason() shouldBe BLOCKED_BY_UNKNOWN_RUN
    }

    @Test
    fun `a start that reports not-started is definitive - no block unless the executor asks, capacity released`() {
        val run = onlyRun()
        h.executor.startOutcome = { StartOutcome.NotStarted("record_unwritable", block = false, "no row") }

        h.worker.handle(run.id)

        val after = h.runs.find(run.id)!!
        after.state shouldBe RunState.NOT_STARTED
        after.reason shouldBe "record_unwritable"
        h.capacity.held.get() shouldBe 0
        blockedReason() shouldBe null
    }

    @Test
    fun `a run refused before launch has a trail and no execution - and blocks when the executor asks`() {
        val run = onlyRun()
        h.executor.refuseAtPrepare = Preparation.Refused("pointer_null", block = true, "the pipeline has no current version")

        h.worker.handle(run.id)

        val after = h.runs.find(run.id)!!
        after.state shouldBe RunState.NOT_STARTED
        after.executionId shouldBe null
        h.runs.trail(run.id).map { it.kind } shouldContainExactly listOf(TrailKind.RECORDED, TrailKind.NOT_STARTED)
        h.runs
            .trail(run.id)
            .last()
            .reason shouldBe "pointer_null"
        blockedReason() shouldBe "pointer_null"
        h.executor.startCount.get() shouldBe 0
    }

    @Test
    fun `the trail's seq is monotonic per run and the run row projects its latest row`() {
        val run = startedRun()
        h.executor.outcomes[run.executionId!!] = ExecutionOutcome.Finished(RunState.SUCCEEDED, null)
        h.reconciler.tick()
        val trail = h.runs.trail(run.id)
        trail.map { it.seq } shouldContainExactly (1..trail.size).toList()
        trail.last().kind shouldBe TrailKind.FINISHED
        h.runs.find(run.id)!!.state shouldBe RunState.SUCCEEDED
    }

    private fun onlyRun(): ScheduleRun = h.runsOf(schedule.id).single()

    private fun startedRun(): ScheduleRun {
        val run = onlyRun()
        h.worker.handle(run.id)
        return h.runs.find(run.id)!!.also { it.state shouldBe RunState.RUNNING }
    }

    private fun prepared(run: ScheduleRun) = (h.executor.prepare(admissionOf(run)) as Preparation.Prepared).snapshot

    private fun blockedReason(): String? = h.schedules.findLive(SchedulerTestDb.WORKSPACE, schedule.id)!!.blockedReason

    private fun admissionOf(run: ScheduleRun) =
        Admission(
            run.id,
            run.workspaceId,
            run.scheduleId,
            run.origin,
            run.scheduledAt,
            run.referenceAt,
            run.referenceTimezone,
            run.payload,
            run.parameters,
        )
}
