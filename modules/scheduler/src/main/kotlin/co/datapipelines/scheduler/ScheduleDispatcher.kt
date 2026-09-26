package co.datapipelines.scheduler

import com.github.kagkarlsson.scheduler.SchedulerClient
import com.github.kagkarlsson.scheduler.task.TaskDescriptor
import org.slf4j.LoggerFactory
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Where a recorded run is handed to db-scheduler: the one-time `schedule-run` task, instance id
 * and data both the run id (record §2.2). Nothing else ever enters `task_data`.
 */
fun interface RunQueue {
    /**
     * Enqueues [runId] to run at [at] — `scheduleIfNotExists`, so a second enqueue of one run is a
     * no-op. Called INSIDE the transaction that recorded the run (record §2.1, spike 1): the
     * starter's `TransactionAwareDataSourceProxy` makes the client's insert part of it.
     */
    fun enqueue(
        runId: UUID,
        at: Instant,
    )
}

/** [RunQueue] over db-scheduler's client and the run task's descriptor. */
class DbSchedulerRunQueue(
    private val client: SchedulerClient,
    private val task: TaskDescriptor<String>,
) : RunQueue {
    override fun enqueue(
        runId: UUID,
        at: Instant,
    ) {
        client.scheduleIfNotExists(task.instance(runId.toString()).data(runId.toString()).scheduledTo(at))
    }
}

/**
 * **The dispatcher** (R1): ONE recurring task, cluster-wide, and the only code that turns timing
 * into runs. A JOB — no transport names it (`ArchitectureGuardTest`, security-assurance B5).
 *
 * One [tick], in ONE `metadataTransactionManager` transaction:
 * 1. lock up to [BATCH] due schedules `FOR UPDATE SKIP LOCKED` (enabled, unblocked, live, in an
 *    active workspace — [ScheduleRepository.lockDue]);
 * 2. per schedule, find what fell due since `next_due_at` with the ONE occurrence function
 *    (record §3.2) and record it by the §3 policy — at most ONE run for the latest due occurrence
 *    and at most ONE missed-range summary for everything before it, however long the outage;
 * 3. enqueue each recorded `queued` run with `scheduleIfNotExists` in the same transaction;
 * 4. advance `next_due_at` to the first occurrence after now.
 *
 * Idempotent by construction: occurrence rows are `ON CONFLICT ON CONSTRAINT
 * uq_schedule_runs_occurrence DO NOTHING`, the enqueue is `IfNotExists`, and the advance is
 * computed from `now`, so a tick replayed after a crash, or run by a second instance that also
 * computed the same instant, records nothing twice (record §8, the two-instance proof).
 */
class ScheduleDispatcher(
    private val schedules: ScheduleRepository,
    private val runs: ScheduleRunRepository,
    private val ledger: RunLedger,
    private val transactions: TransactionTemplate,
    private val clock: Clock,
    private val properties: SchedulerProperties,
    private val metrics: SchedulerMetrics,
    /** The system identity's user id (R2) — every run's `actor_user_id`. */
    private val systemActor: () -> UUID,
) {
    /** One dispatcher tick. Returns what it recorded, for the tests and the log. */
    fun tick(queue: RunQueue): DispatchReport {
        val now = clock.instant()
        val report =
            transactions.execute {
                val due = schedules.lockDue(now, BATCH)
                val recorded = DispatchReport()
                due.forEach { schedule -> dispatch(schedule, now, queue, recorded) }
                recorded
            } ?: DispatchReport()
        if (report.total > 0) {
            LOG.info(
                "event=scheduler.dispatched queued={} catch_up={} missed={} overlap={}",
                report.queued,
                report.catchUp,
                report.missed,
                report.overlap,
            )
        }
        return report
    }

    private fun dispatch(
        schedule: Schedule,
        now: Instant,
        queue: RunQueue,
        report: DispatchReport,
    ) {
        val due = schedule.nextDueAt ?: return
        val pattern = OccurrenceFunction.parse(schedule.cron)
        val zone = OccurrenceFunction.zone(schedule.timezone)
        // Only the window a run could still be admitted in matters for the LATEST occurrence:
        // anything older is part of the missed summary whatever the policy (record §3).
        val reach =
            if (schedule.missedRunPolicy == MissedRunPolicy.LATEST) {
                maxOf(properties.catchUpMaxAge, properties.lateness)
            } else {
                properties.lateness
            }
        val windowStart = maxOf(due, now.minus(reach))
        val latest = OccurrenceFunction.between(pattern, zone, windowStart, now, WINDOW_CAP).instants.lastOrNull()
        val missedUntil = latest ?: now.plusMillis(1)
        val missed = OccurrenceFunction.between(pattern, zone, due, missedUntil.minusMillis(1), MISSED_CAP)

        if (missed.instants.isNotEmpty()) recordMissed(schedule, missed, now, report)
        if (latest != null) recordLatest(schedule, latest, now, queue, report)
        schedules.advance(schedule.id, OccurrenceFunction.next(pattern, zone, now))
    }

    /** The latest due occurrence: queued, caught up, or recorded missed / overlap (record §3). */
    private fun recordLatest(
        schedule: Schedule,
        occurrence: Instant,
        now: Instant,
        queue: RunQueue,
        report: DispatchReport,
    ) {
        val onTime = !now.isAfter(occurrence.plus(properties.lateness))
        val origin = if (onTime) RunOrigin.CRON else RunOrigin.CATCH_UP
        val eligible = onTime || schedule.missedRunPolicy == MissedRunPolicy.LATEST
        val (state, reason) =
            when {
                !eligible -> RunState.SKIPPED to RunReasons.MISSED
                runs.hasActive(schedule.id) -> RunState.SKIPPED to RunReasons.OVERLAP
                else -> RunState.QUEUED to null
            }
        // A catch-up run's lateness window opens when it is recorded (record §3).
        val admitBy = (if (onTime) occurrence else now).plus(properties.lateness)
        val run = newRun(schedule, origin, occurrence, admitBy, now, state, reason)
        if (!runs.insertOccurrence(run)) return
        ledger.note(
            run.id,
            TrailKind.RECORDED,
            reason,
            RunLedger.details("origin" to origin.wire, "scheduled_at" to occurrence, "state" to state.wire),
        )
        when {
            state == RunState.QUEUED -> {
                queue.enqueue(run.id, now)
                if (origin == RunOrigin.CATCH_UP) report.catchUp++ else report.queued++
                metrics.occurrence(if (origin == RunOrigin.CATCH_UP) "catch_up" else "queued")
            }

            reason == RunReasons.OVERLAP -> {
                report.overlap++
                metrics.occurrence("overlap")
                metrics.runFinished(RunState.SKIPPED)
            }

            else -> {
                report.missed++
                metrics.occurrence("missed")
                metrics.runFinished(RunState.SKIPPED)
            }
        }
    }

    /** Everything due before the latest occurrence: ONE summary row, keyed by the first missed instant. */
    private fun recordMissed(
        schedule: Schedule,
        missed: Range,
        now: Instant,
        report: DispatchReport,
    ) {
        val first = missed.instants.first()
        val run = newRun(schedule, RunOrigin.CRON, first, first.plus(properties.lateness), now, RunState.SKIPPED, RunReasons.MISSED)
        if (!runs.insertOccurrence(run)) return
        ledger.note(
            run.id,
            TrailKind.RECORDED,
            RunReasons.MISSED,
            RunLedger.details(
                "origin" to RunOrigin.CRON.wire,
                "scheduled_at" to first,
                "state" to RunState.SKIPPED.wire,
                "missed_count" to missed.instants.size,
                "missed_count_is_lower_bound" to missed.truncated,
                "last_missed_at" to missed.instants.last(),
            ),
        )
        report.missed++
        metrics.occurrence("missed")
        metrics.runFinished(RunState.SKIPPED)
    }

    private fun newRun(
        schedule: Schedule,
        origin: RunOrigin,
        occurrence: Instant,
        admitBy: Instant,
        now: Instant,
        state: RunState,
        reason: String?,
    ) = NewRun(
        id = UUID.randomUUID(),
        scheduleId = schedule.id,
        workspaceId = schedule.workspaceId,
        origin = origin,
        scheduledAt = occurrence,
        referenceAt = occurrence,
        referenceTimezone = schedule.timezone,
        admitBy = admitBy,
        scheduleRevision = schedule.revision,
        executorId = schedule.executorId,
        payloadSchemaVersion = schedule.payloadSchemaVersion,
        payload = schedule.payload,
        parameters = schedule.parameters,
        actorUserId = systemActor(),
        requestedBy = null,
        state = state,
        reason = reason,
        createdAt = now,
    )

    companion object {
        private val LOG = LoggerFactory.getLogger(ScheduleDispatcher::class.java)

        /** Due schedules one tick locks; the next tick takes the rest. */
        const val BATCH = 100

        /**
         * Occurrences examined in the admission window: the widest window the properties admit is a
         * week (`MAX_CATCH_UP_SECONDS`) and a pattern passing the 60 s spacing floor matches at most
         * 1 440 times a day — 10 080, inside this cap, so the scan never truncates the LATEST.
         */
        const val WINDOW_CAP = 20_000

        /** Occurrences a missed summary counts before it reports a lower bound (record §3). */
        const val MISSED_CAP = 10_000
    }
}

/** What one dispatcher tick recorded. */
class DispatchReport {
    var queued: Int = 0
    var catchUp: Int = 0
    var missed: Int = 0
    var overlap: Int = 0

    val total: Int get() = queued + catchUp + missed + overlap
}
