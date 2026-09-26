package co.datapipelines.scheduler

import org.slf4j.LoggerFactory
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * **The run worker** — the `schedule-run` one-time task's body (R1). A JOB: no transport names it
 * (`ArchitectureGuardTest`, B5). One delivery admits one `queued` run, in this order, and each
 * step is the reason the next one is safe (scheduler design revision §2, §2.1, §5.3):
 *
 * 1. **The gate** ([SchedulerAdmission]): closed during shutdown — the task is rescheduled and the
 *    run stays `queued`, never lost.
 * 2. **Policy under the run's lock**: a run whose schedule was deleted, blocked, or (for a
 *    non-manual run) paused since it was recorded is `skipped`; one past its `admit_by` is
 *    `skipped` / `missed`, or `not_started` / `capacity` when capacity is what kept it waiting.
 * 3. **Capacity BEFORE the claim** (R4, A6): no lease — a trail row, the attempt counted, the task
 *    rescheduled within the lateness window; beyond it, `not_started` / `capacity`.
 * 4. **Prepare** (the executor freezes its snapshot; executes nothing). A refusal is a definitive
 *    `not_started`, blocking when the executor asks.
 * 5. **The claim** — `queued → starting` with the minted execution reference and the snapshot, one
 *    conditional UPDATE: the only thing that makes a second launch impossible.
 * 6. **Start**, outside any transaction. Started → `running` (the lease is the execution's now);
 *    NotStarted → `not_started`; a throw → `unknown` / `start_failed`, blocking — the scheduler
 *    cannot know whether work began.
 *
 * A delivery that finds the run already `starting` is a REVIVED one (the previous worker's task
 * heartbeat stopped mid-launch): it launches nothing, asks the executor whether the reference
 * exists, and records `running` or `unknown` / `start_unconfirmed` (§2.1).
 */
class ScheduledRunWorker(
    private val runs: ScheduleRunRepository,
    private val schedules: ScheduleRepository,
    private val executors: JobExecutors,
    private val capacity: CapacityGate,
    private val admission: SchedulerAdmission,
    private val ledger: RunLedger,
    private val transactions: TransactionTemplate,
    private val clock: Clock,
    private val metrics: SchedulerMetrics,
) {
    /** Handles one delivery of [runId]; returns whether its task is done or must run again at an instant. */
    fun handle(runId: UUID): WorkerDecision =
        admission.admit { admitOrLaunch(runId) }
            // The gate is closed: shutdown is in progress. Leave the run queued and come back.
            ?: WorkerDecision.RetryAt(clock.instant().plus(SHUTDOWN_RETRY))

    private fun admitOrLaunch(runId: UUID): WorkerDecision {
        val run = runs.find(runId) ?: return WorkerDecision.Done
        return when (run.state) {
            RunState.QUEUED -> admit(run)
            RunState.STARTING -> recoverRevived(run)
            else -> WorkerDecision.Done // already decided by an earlier delivery
        }
    }

    // One early return per admission step, in the record's order (§2.1): policy, capacity, executor,
    // preparation, claim, start — a step that decides the run ends the delivery there.
    @Suppress("ReturnCount")
    private fun admit(run: ScheduleRun): WorkerDecision {
        policyRefusal(run)?.let { (state, reason) ->
            ledger.move(run.id, QUEUED, state, reason, if (state == RunState.SKIPPED) TrailKind.SKIPPED else TrailKind.NOT_STARTED)
            return WorkerDecision.Done
        }
        val lease = capacity.tryAcquire() ?: return waitForCapacity(run)
        var handedOver = false
        try {
            val executor =
                executors.find(run.executorId) ?: return refuse(run, EXECUTOR_UNAVAILABLE, block = true, "no executor '${run.executorId}'")
            when (val preparation = executor.prepare(admissionOf(run))) {
                is Preparation.Refused -> {
                    return refuse(run, preparation.reason, preparation.block, preparation.message)
                }

                is Preparation.Prepared -> {
                    val executionId = UUID.randomUUID()
                    if (!claim(run, executionId, preparation)) return WorkerDecision.Done
                    val outcome = startOrUnknown(executor, run, executionId, preparation, lease) ?: return WorkerDecision.Done
                    handedOver = outcome is StartOutcome.Started
                    record(run, outcome)
                }
            }
        } finally {
            if (!handedOver) lease.close()
        }
        return WorkerDecision.Done
    }

    /** Step 2 (record §3): what the run's schedule and window say, before any capacity is taken. */
    private fun policyRefusal(run: ScheduleRun): Pair<RunState, String>? {
        val schedule = run.scheduleId?.let(schedules::findAny)
        val now = clock.instant()
        return when {
            run.scheduleId != null && (schedule == null || schedule.deletedAt != null) -> RunState.SKIPPED to RunReasons.SCHEDULE_DELETED
            schedule?.blocked == true -> RunState.SKIPPED to RunReasons.SCHEDULE_BLOCKED
            schedule != null && !schedule.enabled && run.origin != RunOrigin.MANUAL -> RunState.SKIPPED to RunReasons.SCHEDULE_PAUSED
            now.isAfter(run.admitBy) && run.attempts > 0 -> RunState.NOT_STARTED to RunReasons.CAPACITY
            now.isAfter(run.admitBy) -> RunState.SKIPPED to RunReasons.MISSED
            else -> null
        }
    }

    /** Step 3's refusal: retry within the window (R4), recorded on the trail and counted. */
    private fun waitForCapacity(run: ScheduleRun): WorkerDecision {
        val now = clock.instant()
        val attempt =
            transactions.execute {
                val locked = runs.lock(run.id)
                if (locked?.state != RunState.QUEUED) return@execute null
                runs.countAttempt(run.id, now)
                ledger.note(run.id, TrailKind.CAPACITY_RETRY, RunReasons.CAPACITY, RunLedger.details("attempt" to locked.attempts + 1))
                locked.attempts + 1
            } ?: return WorkerDecision.Done
        metrics.capacityRetry()
        val next = now.plus(CAPACITY_RETRY)
        return when {
            !next.isAfter(run.admitBy) -> {
                WorkerDecision.RetryAt(next)
            }

            now.isBefore(run.admitBy) -> {
                WorkerDecision.RetryAt(run.admitBy)
            }

            // one last try at the window's end
            else -> {
                LOG.info("event=scheduler.run_not_started run_id={} reason=capacity attempts={}", run.id, attempt)
                ledger.move(run.id, QUEUED, RunState.NOT_STARTED, RunReasons.CAPACITY, TrailKind.NOT_STARTED)
                WorkerDecision.Done
            }
        }
    }

    /** Step 5 — the claim and its trail row, one transaction. False when another delivery claimed it first. */
    private fun claim(
        run: ScheduleRun,
        executionId: UUID,
        preparation: Preparation.Prepared,
    ): Boolean =
        transactions.execute {
            val claimed = runs.claim(run.id, executionId, preparation.snapshot, ledger.worker, clock.instant())
            if (claimed) {
                ledger.note(run.id, TrailKind.CLAIMED, null, RunLedger.details("execution_id" to executionId))
            }
            claimed
        } == true

    /** Step 6's launch; a throw is recorded `unknown` / `start_failed` (blocking) and yields null. */
    private fun startOrUnknown(
        executor: JobExecutor,
        run: ScheduleRun,
        executionId: UUID,
        preparation: Preparation.Prepared,
        lease: CapacityLease,
    ): StartOutcome? =
        try {
            executor.start(Launch(admissionOf(run), executionId, preparation.snapshot, lease))
        } catch (
            // The port's contract: a throw means "cannot know whether work began". Whatever the
            // exception, the answer is the same conservative one, so the catch is deliberately wide.
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            LOG.error("event=scheduler.start_failed run_id={} execution_id={} message=\"{}\"", run.id, executionId, e.message, e)
            ledger.move(
                run.id,
                STARTING,
                RunState.UNKNOWN,
                RunReasons.START_FAILED,
                TrailKind.UNKNOWN,
                RunLedger.details("execution_id" to executionId),
                block = true,
                blockReason = BLOCKED_BY_UNKNOWN_RUN,
            )
            null
        }

    private fun record(
        run: ScheduleRun,
        outcome: StartOutcome,
    ) {
        when (outcome) {
            is StartOutcome.Started -> {
                ledger.move(
                    run.id,
                    STARTING,
                    RunState.RUNNING,
                    null,
                    TrailKind.EXECUTION_STARTED,
                    RunLedger.details("execution_id" to outcome.executionId),
                )
            }

            is StartOutcome.NotStarted -> {
                ledger.move(
                    run.id,
                    STARTING,
                    RunState.NOT_STARTED,
                    outcome.reason,
                    TrailKind.NOT_STARTED,
                    RunLedger.details("message" to outcome.message.take(MAX_MESSAGE)),
                    block = outcome.block,
                )
            }
        }
    }

    /** A pre-claim refusal: `not_started`, blocking when asked (record §3.1 item 3). */
    private fun refuse(
        run: ScheduleRun,
        reason: String,
        block: Boolean,
        message: String,
    ): WorkerDecision {
        ledger.move(
            run.id,
            QUEUED,
            RunState.NOT_STARTED,
            reason,
            TrailKind.NOT_STARTED,
            RunLedger.details("message" to message.take(MAX_MESSAGE)),
            block = block,
        )
        return WorkerDecision.Done
    }

    /**
     * A revived delivery of a CLAIMED run (record §2.1): never launch again. The execution either
     * exists under the recorded reference — the start happened, record it — or it does not, and
     * the run is `unknown` / `start_unconfirmed`, blocking; the reconciler keeps watching it.
     */
    private fun recoverRevived(run: ScheduleRun): WorkerDecision {
        val executionId = run.executionId ?: return WorkerDecision.Done
        val executor = executors.find(run.executorId)
        val outcome = executor?.inspect(run.workspaceId, listOf(executionId))?.get(executionId) ?: ExecutionOutcome.Absent
        if (outcome == ExecutionOutcome.Absent) {
            ledger.move(
                run.id,
                STARTING,
                RunState.UNKNOWN,
                RunReasons.START_UNCONFIRMED,
                TrailKind.UNKNOWN,
                RunLedger.details("execution_id" to executionId, "revived" to true),
                block = true,
                blockReason = BLOCKED_BY_UNKNOWN_RUN,
            )
        } else {
            ledger.move(
                run.id,
                STARTING,
                RunState.RUNNING,
                null,
                TrailKind.EXECUTION_STARTED,
                RunLedger.details("execution_id" to executionId, "revived" to true),
            )
        }
        return WorkerDecision.Done
    }

    private fun admissionOf(run: ScheduleRun) =
        Admission(
            runId = run.id,
            workspaceId = run.workspaceId,
            scheduleId = run.scheduleId,
            origin = run.origin,
            scheduledAt = run.scheduledAt,
            referenceAt = run.referenceAt,
            referenceTimezone = run.referenceTimezone,
            payload = run.payload,
            parameters = run.parameters,
        )

    companion object {
        private val LOG = LoggerFactory.getLogger(ScheduledRunWorker::class.java)
        private val QUEUED = setOf(RunState.QUEUED)
        private val STARTING = setOf(RunState.STARTING)

        /** The capacity retry interval within the lateness window (R4). */
        val CAPACITY_RETRY: Duration = Duration.ofSeconds(30)

        /** How soon a delivery refused by the closed gate comes back (the next instance, or this one after restart). */
        val SHUTDOWN_RETRY: Duration = Duration.ofSeconds(10)

        /** A `not_started` reason when the run's executor is no longer registered (a removed executor; blocks). */
        const val EXECUTOR_UNAVAILABLE = "executor_unavailable"

        private const val MAX_MESSAGE = 300
    }
}

/** What a delivery of the run task decided. */
sealed interface WorkerDecision {
    /** The task is finished; db-scheduler removes it. */
    data object Done : WorkerDecision

    /** Deliver again at [at] — capacity or shutdown; the run is still `queued`. */
    data class RetryAt(
        val at: Instant,
    ) : WorkerDecision
}
