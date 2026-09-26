package co.datapipelines.scheduler

import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration

/**
 * **The reconciler** (R1, R6): maps an execution's terminal state onto its run, through the
 * executor's standardized outcome — never by reading a pipeline table or parsing an error code
 * (scheduler design revision §5.3). A JOB (`ArchitectureGuardTest`, B5). Liveness is NOT decided
 * here: the execution's own heartbeat and the stale-execution sweeper are the one clock
 * (`ABORTED` / `instance_lost` after three missed beats, A5), and the executor reports the result.
 *
 * Per watched run ([ScheduleRunRepository.watched]):
 * - `starting` — the execution exists: record `running` (or its terminal); it does not, and the
 *   claim is older than the start grace: `unknown` / `start_unconfirmed`, blocking (§2.1);
 * - `running` — a terminal maps by R6's table; an `unknown` mapping (the sweeper's instance_lost)
 *   blocks; a vanished record is `unknown` / `execution_missing`, blocking;
 * - `unknown` (watched for [UNKNOWN_WATCH]) — a LATER real terminal (a stalled worker overwrote
 *   `instance_lost`, A5) is recorded as an update about the same run; the schedule stays blocked
 *   until a person unblocks it.
 *
 * Every write is [RunLedger.move]'s conditional UPDATE, so a second reconciler (or the worker
 * recording its own `running`) racing this one writes nothing twice.
 */
class RunReconciler(
    private val runs: ScheduleRunRepository,
    private val executors: JobExecutors,
    private val ledger: RunLedger,
    private val clock: Clock,
    private val properties: SchedulerProperties,
) {
    /** One reconciler tick. Returns how many runs changed. */
    fun tick(): Int {
        val now = clock.instant()
        val watched = runs.watched(now.minus(UNKNOWN_WATCH), BATCH)
        var changed = 0
        watched.groupBy { it.workspaceId to it.executorId }.forEach { (key, group) ->
            val executor = executors.find(key.second) ?: return@forEach
            val outcomes = executor.inspect(key.first, group.mapNotNull { it.executionId })
            group.forEach { run ->
                val outcome = run.executionId?.let { outcomes[it] } ?: ExecutionOutcome.Absent
                if (reconcile(run, outcome)) changed++
            }
        }
        if (changed > 0) LOG.info("event=scheduler.reconciled changed={} watched={}", changed, watched.size)
        return changed
    }

    private fun reconcile(
        run: ScheduleRun,
        outcome: ExecutionOutcome,
    ): Boolean =
        when (run.state) {
            RunState.STARTING -> reconcileStarting(run, outcome)
            RunState.RUNNING -> reconcileRunning(run, outcome)
            RunState.UNKNOWN -> reconcileUnknown(run, outcome)
            else -> false
        }

    private fun reconcileStarting(
        run: ScheduleRun,
        outcome: ExecutionOutcome,
    ): Boolean {
        val details = RunLedger.details("execution_id" to run.executionId, "reconciled" to true)
        return when (outcome) {
            ExecutionOutcome.Running -> {
                ledger.move(run.id, setOf(RunState.STARTING), RunState.RUNNING, null, TrailKind.EXECUTION_STARTED, details)
            }

            is ExecutionOutcome.Finished -> {
                finish(run, setOf(RunState.STARTING), outcome)
            }

            ExecutionOutcome.Absent -> {
                val claimedAt = run.claimedAt ?: run.updatedAt
                if (Duration.between(claimedAt, clock.instant()) < properties.startGrace) return false
                ledger.move(
                    run.id,
                    setOf(RunState.STARTING),
                    RunState.UNKNOWN,
                    RunReasons.START_UNCONFIRMED,
                    TrailKind.UNKNOWN,
                    details,
                    block = true,
                    blockReason = BLOCKED_BY_UNKNOWN_RUN,
                )
            }
        }
    }

    private fun reconcileRunning(
        run: ScheduleRun,
        outcome: ExecutionOutcome,
    ): Boolean =
        when (outcome) {
            ExecutionOutcome.Running -> {
                false
            }

            is ExecutionOutcome.Finished -> {
                finish(run, setOf(RunState.RUNNING), outcome)
            }

            ExecutionOutcome.Absent -> {
                // Execution rows are never deleted (metadata-db §8.1 prunes only their events), so a
                // RUNNING run whose record vanished is evidence of something nobody can explain: blocked.
                ledger.move(
                    run.id,
                    setOf(RunState.RUNNING),
                    RunState.UNKNOWN,
                    EXECUTION_MISSING,
                    TrailKind.UNKNOWN,
                    RunLedger.details("execution_id" to run.executionId),
                    block = true,
                    blockReason = BLOCKED_BY_UNKNOWN_RUN,
                )
            }
        }

    /** A5: a real terminal arriving after `unknown` updates the SAME run; the block stays. */
    private fun reconcileUnknown(
        run: ScheduleRun,
        outcome: ExecutionOutcome,
    ): Boolean {
        if (outcome !is ExecutionOutcome.Finished || outcome.state == RunState.UNKNOWN) return false
        return ledger.move(
            run.id,
            setOf(RunState.UNKNOWN),
            outcome.state,
            outcome.reason,
            TrailKind.UPDATED_AFTER_UNKNOWN,
            RunLedger.details("execution_id" to run.executionId, "previous_reason" to run.reason),
        )
    }

    /** A terminal by R6's table; `unknown` blocks (record §7.1). */
    private fun finish(
        run: ScheduleRun,
        from: Set<RunState>,
        outcome: ExecutionOutcome.Finished,
    ): Boolean {
        val unknown = outcome.state == RunState.UNKNOWN
        return ledger.move(
            run.id,
            from,
            outcome.state,
            outcome.reason,
            if (unknown) TrailKind.UNKNOWN else TrailKind.FINISHED,
            RunLedger.details("execution_id" to run.executionId),
            block = unknown,
            blockReason = BLOCKED_BY_UNKNOWN_RUN,
        )
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(RunReconciler::class.java)

        /** Runs one tick examines; the rest wait for the next tick. */
        const val BATCH = 500

        /** How long an `unknown` run's execution is still watched for a later real terminal (record §2.1). */
        val UNKNOWN_WATCH: Duration = Duration.ofHours(24)

        /** `unknown` reason: a `running` run's execution record is gone (record §7.1). */
        const val EXECUTION_MISSING = "execution_missing"
    }
}
