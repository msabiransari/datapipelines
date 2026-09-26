package co.datapipelines.scheduler

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import org.slf4j.LoggerFactory
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.util.UUID

/**
 * The ONE place a run changes state (record §7.1, R6, R10): every transition is a conditional
 * UPDATE plus its trail row, in one transaction, and the run row's `state` + `reason` are exactly
 * the projection of the latest trail row. The dispatcher, the worker, the reconciler and the
 * management service all move runs through here, so a transition without its trail row cannot be
 * written.
 *
 * Blocking lives here too: a run that ends `unknown`, or a refusal the executor asks to block on,
 * blocks its schedule in the same transaction as the transition that caused it.
 */
class RunLedger(
    private val runs: ScheduleRunRepository,
    private val schedules: ScheduleRepository,
    private val transactions: TransactionTemplate,
    private val clock: Clock,
    private val metrics: SchedulerMetrics,
    /** This instance's worker name — the trail's `worker` column. */
    val worker: String,
) {
    /** Appends a trail row for [runId] that changes no state (a capacity retry, an unblock). */
    fun note(
        runId: UUID,
        kind: TrailKind,
        reason: String?,
        details: JsonNode = empty(),
    ) {
        runs.appendTrail(runId, kind, reason, clock.instant(), worker, details)
    }

    /**
     * Moves [runId] from one of [from] to [to] under [reason], appending [kind] — in its own
     * transaction. False when the run was no longer in [from] (another writer got there first);
     * nothing is written then. [block] blocks the run's schedule under [blockReason] in the same
     * transaction.
     */
    fun move(
        runId: UUID,
        from: Set<RunState>,
        to: RunState,
        reason: String?,
        kind: TrailKind,
        details: JsonNode = empty(),
        block: Boolean = false,
        blockReason: String? = reason,
    ): Boolean =
        transactions.execute {
            moveInTransaction(runId, from, to, reason, kind, details, block, blockReason)
        } == true

    /** [move] inside a transaction the caller already holds. */
    fun moveInTransaction(
        runId: UUID,
        from: Set<RunState>,
        to: RunState,
        reason: String?,
        kind: TrailKind,
        details: JsonNode = empty(),
        block: Boolean = false,
        blockReason: String? = reason,
    ): Boolean {
        val now = clock.instant()
        if (!runs.transition(runId, from, to, reason, now)) return false
        runs.appendTrail(runId, kind, reason, now, worker, details)
        if (!to.active) metrics.runFinished(to)
        if (block) blockScheduleOf(runId, blockReason ?: BLOCKED_BY_UNKNOWN_RUN)
        return true
    }

    /** Blocks the schedule [runId] belongs to (a manual or ad-hoc run's schedule too). Keeps an existing block. */
    private fun blockScheduleOf(
        runId: UUID,
        reason: String,
    ) {
        val scheduleId = runs.find(runId)?.scheduleId ?: return
        if (schedules.block(scheduleId, reason, runId, clock.instant())) {
            LOG.warn(
                "event=scheduler.schedule_blocked schedule_id={} run_id={} reason={} " +
                    "message=\"the schedule fires nothing until a person unblocks it\"",
                scheduleId,
                runId,
                reason,
            )
        }
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(RunLedger::class.java)

        /** An empty `details` object. */
        fun empty(): ObjectNode = JsonNodeFactory.instance.objectNode()

        /** A `details` object from [pairs]; null values are left out. */
        fun details(vararg pairs: Pair<String, Any?>): ObjectNode {
            val node = JsonNodeFactory.instance.objectNode()
            pairs.forEach { (key, value) ->
                when (value) {
                    null -> Unit
                    is Int -> node.put(key, value)
                    is Long -> node.put(key, value)
                    is Boolean -> node.put(key, value)
                    else -> node.put(key, value.toString())
                }
            }
            return node
        }
    }
}
