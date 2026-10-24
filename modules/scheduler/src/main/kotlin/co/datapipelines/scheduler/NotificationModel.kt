package co.datapipelines.scheduler

import java.time.Instant
import java.util.UUID

/**
 * What a notice says happened (`schedule_notification_events.outcome`, V51; enums.md §43), under the checkbox
 * ([event]) that selects it, with the subject the policy fixes (`docs/scheduler.md` §2.3, record
 * §6.1). *unknown* is never described as a stop; its later reconciled outcome is an update about
 * the same run under the unknown checkbox.
 */
enum class NotificationOutcome(
    val wire: String,
    val event: NotificationEvent,
    val subject: String,
) {
    STARTED("started", NotificationEvent.START, "Started"),
    COMPLETED("completed", NotificationEvent.SUCCESS, "Completed"),
    FAILED("failed", NotificationEvent.FAILURE, "Failed"),
    COULD_NOT_START("could_not_start", NotificationEvent.FAILURE, "Could not start"),
    CANCELLED("cancelled", NotificationEvent.FAILURE, "Stopped — cancelled"),
    ABORTED("aborted", NotificationEvent.FAILURE, "Stopped — instance shut down"),
    UNKNOWN("unknown", NotificationEvent.UNKNOWN, "Outcome unknown — action required"),
    UNKNOWN_UPDATE("unknown_update", NotificationEvent.UNKNOWN, "Update on an unknown run"),
    BLOCKED("blocked", NotificationEvent.BLOCKED, "Schedule blocked"),
    ;

    companion object {
        fun fromWire(value: String): NotificationOutcome = entries.first { it.wire == value }

        /**
         * The policy's mapping of one run transition (record §6.1; brief A.2): a later outcome of an
         * `unknown` run is [UNKNOWN_UPDATE] whatever it is; otherwise the target state decides. Null
         * — no notice — for queued/starting moves and for `skipped` (the ruling: no mail for skipped
         * ticks). Blocking is not a run transition: [BLOCKED] is written where the block happens.
         */
        fun of(
            to: RunState,
            kind: TrailKind,
        ): NotificationOutcome? =
            if (kind == TrailKind.UPDATED_AFTER_UNKNOWN) {
                UNKNOWN_UPDATE
            } else {
                when (to) {
                    RunState.RUNNING -> STARTED
                    RunState.SUCCEEDED -> COMPLETED
                    RunState.FAILED -> FAILED
                    RunState.NOT_STARTED -> COULD_NOT_START
                    RunState.CANCELLED -> CANCELLED
                    RunState.ABORTED -> ABORTED
                    RunState.UNKNOWN -> UNKNOWN
                    RunState.QUEUED, RunState.STARTING, RunState.SKIPPED -> null
                }
            }
    }
}

/** `schedule_notification_deliveries.state` (V51; enums.md §44). Never "delivered": accepted means the relay took it. */
enum class DeliveryState(
    val wire: String,
) {
    /** Queued, or due for another try after a send that provably never reached the relay. */
    PENDING("pending"),

    /** The SMTP relay accepted the message — not proof it reached an inbox. */
    ACCEPTED("accepted"),

    /** Never sent, or refused, or out of tries; [Delivery.lastErrorCode] says which. */
    FAILED("failed"),

    /** The send may have reached the relay (a connection lost after the data was sent). Never retried automatically. */
    UNCERTAIN("uncertain"),
    ;

    companion object {
        fun fromWire(value: String): DeliveryState = entries.first { it.wire == value }
    }
}

/**
 * The sanitized codes a delivery's `last_error_code` carries — no transport text, no address. A
 * delivery found with mail turned off between enqueue and send carries the [DeliveryOffReason]'s
 * wire value (`disabled`, `mail_unconfigured`, `no_allowlist`) instead.
 */
object DeliveryCodes {
    /** The recipient's domain is outside the allowlist (at enqueue, or re-checked at send). */
    const val DOMAIN_NOT_ALLOWED = "domain_not_allowed"

    /** More than `backlog-max` deliveries were pending; this one was never queued for sending. */
    const val BACKLOG_FULL = "backlog_full"

    /** The address fails strict RFC 822 parsing at send time. */
    const val INVALID_ADDRESS = "invalid_address"

    /** The relay could not be reached; nothing was sent. Retryable. */
    const val CONNECT_FAILED = "connect_failed"

    /** The relay answered 4xx (try later); nothing was accepted. Retryable. */
    const val SMTP_TRANSIENT = "smtp_transient"

    /** The relay refused the message (5xx). */
    const val SMTP_REFUSED = "smtp_refused"

    /** The relay refused our credentials. */
    const val AUTH_FAILED = "auth_failed"

    /** The connection failed after the message may have been submitted — the uncertain case. */
    const val IO_AFTER_CONNECT = "io_after_connect"

    /** A claimed send never reported back (the instance stopped mid-send, or the transport port threw) — may have been sent. */
    const val INTERRUPTED = "interrupted"

    /** The notice could not be rendered; nothing was sent. A defect, not a transport fault — never retried. */
    const val RENDER_FAILED = "render_failed"
}

/** One `schedule_notification_events` row. */
data class NotificationEventRow(
    val id: UUID,
    val scheduleId: UUID,
    val runId: UUID,
    val outcome: NotificationOutcome,
    val runState: RunState,
    val reason: String?,
    val occurredAt: Instant,
    val sequence: Int,
)

/** One `schedule_notification_deliveries` row. [recipient] is personal data: never logged, shown only to `schedule.update` holders. */
data class Delivery(
    val id: UUID,
    val eventId: UUID,
    val recipient: String,
    val state: DeliveryState,
    val attempts: Int,
    val nextAttemptAt: Instant?,
    val lastErrorCode: String?,
    val messageId: String,
    val sentAt: Instant?,
    val resendCount: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    /** The recipient's domain — the only part of the address a log line or a non-holder may see. */
    val recipientDomain: String get() = recipient.substringAfterLast('@')

    // A data class's generated toString would print the address; this one never does.
    override fun toString(): String =
        "Delivery(id=$id, eventId=$eventId, domain=$recipientDomain, state=${state.wire}, attempts=$attempts, " +
            "lastErrorCode=$lastErrorCode, resendCount=$resendCount)"
}
