package co.datapipelines.scheduler

import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID

/**
 * The outbox writer (record §6.1): called by [RunLedger] INSIDE the transaction that moves a run
 * (or blocks its schedule), after the trail row, so a notice exists exactly when its transition
 * does — both commit or neither. No event bus (none exists in this codebase), no second
 * transaction.
 *
 * Writes nothing when delivery is off (no phantom pending rows), when the schedule's event set at
 * this moment does not select the outcome, or when it has no recipients. Otherwise one event row,
 * one delivery row per recipient — `pending`, or `failed` at once with `domain_not_allowed` (outside
 * the allowlist) or `backlog_full` (over `backlog-max`), never silently dropped — and one
 * `notification_enqueued` trail row on the run. Logs carry ids, the event and counts; never an
 * address.
 */
class NotificationOutbox(
    private val repository: NotificationRepository,
    private val runs: ScheduleRunRepository,
    private val policy: NotificationPolicy,
    private val backlogMax: Int,
) {
    /**
     * Enqueues the notice for [runId] reaching [outcome] (state [runState] under [reason]) at
     * [at]; [sequence] is the trail seq of the transition. Returns the event id, or null when
     * nothing was written.
     */
    @Suppress("LongParameterList") // the transition's facts, each one a column of the event row
    fun enqueue(
        runId: UUID,
        outcome: NotificationOutcome,
        runState: RunState,
        reason: String?,
        at: Instant,
        sequence: Int,
        worker: String,
    ): UUID? {
        val target = selectingTarget(runId, outcome) ?: return null
        val event = NotificationEventRow(UUID.randomUUID(), target.scheduleId, runId, outcome, runState, reason, at, sequence)
        if (!repository.insertEvent(event)) return null

        var room = backlogMax - repository.pendingCount()
        val deliveries =
            target.recipients.map { recipient ->
                val id = UUID.randomUUID()
                val refusal =
                    when {
                        !policy.admits(recipient) -> DeliveryCodes.DOMAIN_NOT_ALLOWED
                        room <= 0 -> DeliveryCodes.BACKLOG_FULL
                        else -> null.also { room-- }
                    }
                NewDelivery(id, event.id, recipient, messageId(id), refusal)
            }
        repository.insertDeliveries(deliveries, at)
        val queued = deliveries.count { it.refusal == null }
        val refused = deliveries.groupingBy { it.refusal }.eachCount().filterKeys { it != null }
        runs.appendTrail(
            runId,
            TrailKind.NOTIFICATION_ENQUEUED,
            outcome.wire,
            at,
            worker,
            RunLedger.details(
                "notification_id" to event.id,
                "event" to outcome.event.wire,
                "outcome" to outcome.wire,
                "queued" to queued,
                "refused" to refused.values.sum(),
            ),
        )
        LOG.info(
            "event=scheduler.notification.enqueued schedule_id={} run_id={} notification_id={} notice={} outcome={} " +
                "queued={} refused={}",
            target.scheduleId,
            runId,
            event.id,
            outcome.event.wire,
            outcome.wire,
            queued,
            refused.entries.joinToString(",") { "${it.key}:${it.value}" }.ifEmpty { "0" },
        )
        if (DeliveryCodes.BACKLOG_FULL in refused) {
            LOG.warn(
                "event=scheduler.notification.backlog_full notification_id={} backlog_max={} " +
                    "message=\"deliveries were written failed/backlog_full; the sender is behind or mail is unreachable\"",
                event.id,
                backlogMax,
            )
        }
        return event.id
    }

    /** [runId]'s schedule settings when delivery is on and they select [outcome] for at least one recipient; else null. */
    private fun selectingTarget(
        runId: UUID,
        outcome: NotificationOutcome,
    ): NotificationTarget? =
        repository
            .takeIf { policy.state.on }
            ?.settingsOfRun(runId)
            ?.takeIf { outcome.event in it.events && it.recipients.isNotEmpty() }

    /** `<deliveryId@host-of-base-url>` — stable per delivery, so a resend carries the same Message-ID (record §6.1). */
    private fun messageId(deliveryId: UUID): String = "<$deliveryId@${policy.deployment.messageIdHost}>"

    private companion object {
        val LOG = LoggerFactory.getLogger(NotificationOutbox::class.java)
    }
}
