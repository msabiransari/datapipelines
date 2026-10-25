package co.datapipelines.scheduler

import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * The sender (#442b; record §6.1–§6.2): the `schedule-notifier` task's tick. It drains the outbox
 * the ledger writes, one delivery at a time, and never touches a run's state — "email failure never
 * changes the pipeline outcome".
 *
 * Each tick:
 * 1. recovers claims older than [staleAfter] (a dead instance's sends) as `uncertain` / `interrupted`;
 * 2. claims up to `batch-size` due deliveries, one autocommit claim each;
 * 3. per delivery, RE-CHECKS the deployment's state and the allowlist (either may have changed
 *    since enqueue — a delivery found with mail off or outside a narrowed allowlist is `failed` with
 *    that reason and nothing is sent), renders, and sends OUTSIDE any transaction;
 * 4. settles it, with its trail row (`notification_sent` / `_failed` / `_uncertain`) on the run, in
 *    one short transaction: accepted; a provably-unsent retryable failure back to `pending` after
 *    `backoffAfter(attempts)` while tries remain, else `failed`; a refusal `failed`; a possible send
 *    `uncertain`, never retried automatically.
 *
 * Log lines and trail details carry ids, the notice, the attempt count, the code and — in logs only
 * — the recipient's DOMAIN; never an address, a subject or a body.
 */
@Suppress("LongParameterList") // the sender's collaborators, each one a step of its KDoc
class ScheduleNotifier(
    private val deliveries: NotificationDeliveryRepository,
    private val runs: ScheduleRunRepository,
    private val policy: NotificationPolicy,
    private val notifications: SchedulerProperties.Notifications,
    private val mailer: NotificationMailer,
    private val templates: NotificationTemplates,
    private val transactions: TransactionTemplate,
    private val clock: Clock,
    private val metrics: SchedulerMetrics,
    private val staleAfter: Duration,
    private val worker: String,
) {
    /** One tick; returns what it settled, for the tests and the log line. */
    fun tick(): NotifierTick {
        val recovered = recover()
        var settled = 0
        while (settled < notifications.batchSize) {
            val claim = deliveries.claimNext(now()) ?: break
            deliver(claim)
            settled++
        }
        return NotifierTick(recovered, settled)
    }

    private fun recover(): Int {
        val now = now()
        val recovered =
            transactions
                .execute {
                    deliveries.recoverInterrupted(now.minus(staleAfter), now).onEach { delivery ->
                        runs.appendTrail(
                            delivery.runId,
                            TrailKind.NOTIFICATION_UNCERTAIN,
                            delivery.outcome.wire,
                            now,
                            worker,
                            RunLedger.details(
                                "delivery_id" to delivery.id,
                                "notification_id" to delivery.eventId,
                                "attempts" to delivery.attempts,
                                "code" to DeliveryCodes.INTERRUPTED,
                            ),
                        )
                    }
                }.orEmpty()
        recovered.forEach { delivery ->
            metrics.notificationSettled(DeliveryState.UNCERTAIN)
            LOG.warn(
                "event=scheduler.notification.uncertain delivery_id={} notification_id={} schedule_id={} run_id={} domain={} " +
                    "attempts={} code={} message=\"a send was claimed and never settled; it may have been sent and is not retried\"",
                delivery.id,
                delivery.eventId,
                delivery.scheduleId,
                delivery.runId,
                delivery.recipientDomain,
                delivery.attempts,
                DeliveryCodes.INTERRUPTED,
            )
        }
        return recovered.size
    }

    private fun deliver(claim: ClaimedDelivery) {
        val refusal =
            when {
                !policy.state.on -> policy.state.off?.wire
                !policy.admits(claim.recipient) -> DeliveryCodes.DOMAIN_NOT_ALLOWED
                else -> null
            }
        val outcome = refusal?.let { MailOutcome.Refused(it) } ?: send(claim)
        settle(claim, outcome)
    }

    /** Renders and sends; a defect on either side is settled, never left as a claim that looks like a lost instance. */
    private fun send(claim: ClaimedDelivery): MailOutcome {
        val baseUrl = policy.deployment.baseUrl ?: return MailOutcome.Refused(DeliveryOffReason.MAIL_UNCONFIGURED.wire)
        val rendered =
            try {
                templates.render(claim.notice, baseUrl)
            } catch (
                @Suppress("TooGenericExceptionCaught") e: RuntimeException,
            ) {
                LOG.error(
                    "event=scheduler.notification.render_failed delivery_id={} schedule_id={} run_id={} error={}",
                    claim.id,
                    claim.notice.scheduleId,
                    claim.notice.runId,
                    e.javaClass.simpleName,
                )
                return MailOutcome.Refused(DeliveryCodes.RENDER_FAILED)
            }
        val mail = NotificationMail(claim.recipient, rendered.subject, rendered.text, rendered.html, claim.messageId)
        return try {
            mailer.send(mail)
        } catch (
            @Suppress("TooGenericExceptionCaught") e: RuntimeException,
        ) {
            // The port promises not to throw; if it does, nobody can say whether the relay took it.
            LOG.error(
                "event=scheduler.notification.port_failed delivery_id={} schedule_id={} run_id={} error={}",
                claim.id,
                claim.notice.scheduleId,
                claim.notice.runId,
                e.javaClass.simpleName,
            )
            MailOutcome.Uncertain(DeliveryCodes.INTERRUPTED)
        }
    }

    private fun settle(
        claim: ClaimedDelivery,
        outcome: MailOutcome,
    ) {
        val now = now()
        val settlement = settlementOf(claim, outcome, now)
        val written =
            transactions.execute {
                deliveries
                    .settle(claim, settlement.state, settlement.code, settlement.sentAt, settlement.nextAttemptAt, now)
                    .also { written -> if (written && settlement.trail != null) trail(claim, settlement, now) }
            } == true
        if (!written) {
            LOG.warn(
                "event=scheduler.notification.settle_lost delivery_id={} schedule_id={} run_id={} " +
                    "message=\"the claim was recovered before it settled\"",
                claim.id,
                claim.notice.scheduleId,
                claim.notice.runId,
            )
            return
        }
        metrics.notificationSettled(settlement.state)
        val (level, line) =
            when (settlement.state) {
                DeliveryState.ACCEPTED -> Level.INFO to "event=scheduler.notification.sent $FIELDS"
                DeliveryState.PENDING -> Level.INFO to "event=scheduler.notification.retry $FIELDS"
                DeliveryState.FAILED -> Level.WARN to "event=scheduler.notification.failed $FIELDS"
                DeliveryState.UNCERTAIN -> Level.WARN to "event=scheduler.notification.uncertain $FIELDS"
            }
        LOG.atLevel(level).log(
            line,
            claim.id,
            claim.eventId,
            claim.notice.scheduleId,
            claim.notice.runId,
            claim.notice.outcome.wire,
            claim.recipientDomain,
            claim.attempts,
            settlement.code ?: "-",
        )
    }

    private fun settlementOf(
        claim: ClaimedDelivery,
        outcome: MailOutcome,
        now: Instant,
    ): Settlement =
        when (outcome) {
            MailOutcome.Accepted -> {
                Settlement(DeliveryState.ACCEPTED, null, sentAt = now, trail = TrailKind.NOTIFICATION_SENT)
            }

            is MailOutcome.Retryable -> {
                if (claim.attempts < notifications.maxAttempts) {
                    Settlement(DeliveryState.PENDING, outcome.code, nextAttemptAt = now.plus(notifications.backoffAfter(claim.attempts)))
                } else {
                    Settlement(DeliveryState.FAILED, outcome.code, trail = TrailKind.NOTIFICATION_FAILED)
                }
            }

            is MailOutcome.Refused -> {
                Settlement(DeliveryState.FAILED, outcome.code, trail = TrailKind.NOTIFICATION_FAILED)
            }

            is MailOutcome.Uncertain -> {
                Settlement(DeliveryState.UNCERTAIN, outcome.code, trail = TrailKind.NOTIFICATION_UNCERTAIN)
            }
        }

    private fun trail(
        claim: ClaimedDelivery,
        settlement: Settlement,
        now: Instant,
    ) {
        runs.appendTrail(
            claim.notice.runId,
            checkNotNull(settlement.trail),
            claim.notice.outcome.wire,
            now,
            worker,
            RunLedger.details(
                "delivery_id" to claim.id,
                "notification_id" to claim.eventId,
                "attempts" to claim.attempts,
                "code" to settlement.code,
            ),
        )
    }

    /** Microsecond precision — what a TIMESTAMPTZ stores — so a written instant reads back equal. */
    private fun now(): Instant = clock.instant().truncatedTo(ChronoUnit.MICROS)

    /** How one claim settles: the row's new state and columns, and the trail row a final state writes. */
    private data class Settlement(
        val state: DeliveryState,
        val code: String?,
        val sentAt: Instant? = null,
        val nextAttemptAt: Instant? = null,
        val trail: TrailKind? = null,
    )

    private companion object {
        val LOG = LoggerFactory.getLogger(ScheduleNotifier::class.java)

        /** The fields of every settle line (observability.md §3.4E): ids, the notice, the DOMAIN, the attempt count, the code. */
        const val FIELDS = "delivery_id={} notification_id={} schedule_id={} run_id={} notice={} domain={} attempts={} code={}"
    }
}

/** What one tick did: claims recovered as interrupted, deliveries settled. */
data class NotifierTick(
    val recovered: Int,
    val settled: Int,
)
