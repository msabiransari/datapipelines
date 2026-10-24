package co.datapipelines.scheduler

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * The sender's side of `schedule_notification_deliveries` (V51; metadata-db §4.40): claim one due
 * delivery, settle it, and recover claims a dead instance left behind. Over a BOUNDED template
 * ([SchedulerNotificationConfiguration] sets its `queryTimeout`): the sender's statements never
 * wait unbounded on a metadata database.
 *
 * A claim is ONE autocommit statement (`FOR UPDATE SKIP LOCKED`, then `sending_since` and
 * `attempts + 1`), so the send itself runs outside any transaction; a settle is conditional on the
 * claim's `sending_since`, so a claim another tick already recovered is never overwritten.
 */
class NotificationDeliveryRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) {
    /** Claims the oldest due delivery at [now]; null when none is due. */
    fun claimNext(now: Instant): ClaimedDelivery? =
        jdbc
            .query(
                """
                WITH next AS (
                    SELECT id FROM schedule_notification_deliveries
                     WHERE state = 'pending' AND sending_since IS NULL AND next_attempt_at <= :now
                     ORDER BY next_attempt_at, id
                     LIMIT 1
                       FOR UPDATE SKIP LOCKED
                ), claimed AS (
                    UPDATE schedule_notification_deliveries d
                       SET sending_since = :now, attempts = d.attempts + 1, updated_at = :now
                      FROM next
                     WHERE d.id = next.id
                    RETURNING d.id, d.event_id, d.recipient, d.attempts, d.message_id, d.sending_since
                )
                SELECT c.id, c.event_id, c.recipient, c.attempts, c.message_id, c.sending_since,
                       e.schedule_id, e.run_id, e.outcome, e.run_state, e.reason, e.occurred_at,
                       s.name, s.timezone, s.target_ref, r.scheduled_at
                  FROM claimed c
                  JOIN schedule_notification_events e ON e.id = c.event_id
                  JOIN schedules s ON s.id = e.schedule_id
                  JOIN schedule_runs r ON r.id = e.run_id
                """.trimIndent(),
                mapOf("now" to Timestamp.from(now)),
            ) { rs, _ -> claimOf(rs) }
            .singleOrNull()

    /**
     * Settles the claim [claim] into [state] — `pending` again with [nextAttemptAt], or a final
     * state — with [code] as the last error and [sentAt] when accepted. False when the row is no
     * longer this claim (recovered meanwhile): nothing is written then.
     */
    @Suppress("LongParameterList") // the settled row's columns, each one named
    fun settle(
        claim: ClaimedDelivery,
        state: DeliveryState,
        code: String?,
        sentAt: Instant?,
        nextAttemptAt: Instant?,
        now: Instant,
    ): Boolean =
        jdbc.update(
            """
            UPDATE schedule_notification_deliveries
               SET state = :state, sending_since = NULL, next_attempt_at = :nextAttemptAt,
                   last_error_code = :code, sent_at = COALESCE(:sentAt, sent_at), updated_at = :now
             WHERE id = :id AND state = 'pending' AND sending_since = :claimedAt
            """.trimIndent(),
            mapOf(
                "id" to claim.id,
                "state" to state.wire,
                "nextAttemptAt" to nextAttemptAt?.let(Timestamp::from),
                "code" to code,
                "sentAt" to sentAt?.let(Timestamp::from),
                "now" to Timestamp.from(now),
                "claimedAt" to Timestamp.from(claim.claimedAt),
            ),
        ) == 1

    /**
     * Claims older than [staleBefore] — sends a dead instance started and never settled — become
     * `uncertain` / `interrupted`: the message may have gone out, so they are never retried
     * automatically. Returns them with their run, for the trail.
     */
    fun recoverInterrupted(
        staleBefore: Instant,
        now: Instant,
    ): List<RecoveredDelivery> =
        jdbc.query(
            """
            WITH moved AS (
                UPDATE schedule_notification_deliveries
                   SET state = 'uncertain', sending_since = NULL, next_attempt_at = NULL,
                       last_error_code = :code, updated_at = :now
                 WHERE state = 'pending' AND sending_since < :staleBefore
                RETURNING id, event_id, recipient, attempts
            )
            SELECT m.id, m.event_id, m.recipient, m.attempts, e.schedule_id, e.run_id, e.outcome
              FROM moved m JOIN schedule_notification_events e ON e.id = m.event_id
            """.trimIndent(),
            mapOf(
                "code" to DeliveryCodes.INTERRUPTED,
                "now" to Timestamp.from(now),
                "staleBefore" to Timestamp.from(staleBefore),
            ),
        ) { rs, _ ->
            RecoveredDelivery(
                id = rs.getObject("id", UUID::class.java),
                eventId = rs.getObject("event_id", UUID::class.java),
                scheduleId = rs.getObject("schedule_id", UUID::class.java),
                runId = rs.getObject("run_id", UUID::class.java),
                outcome = NotificationOutcome.fromWire(rs.getString("outcome")),
                recipientDomain = rs.getString("recipient").substringAfterLast('@'),
                attempts = rs.getInt("attempts"),
            )
        }

    private fun claimOf(rs: ResultSet): ClaimedDelivery =
        ClaimedDelivery(
            id = rs.getObject("id", UUID::class.java),
            eventId = rs.getObject("event_id", UUID::class.java),
            recipient = rs.getString("recipient"),
            attempts = rs.getInt("attempts"),
            messageId = rs.getString("message_id"),
            claimedAt = rs.getTimestamp("sending_since").toInstant(),
            notice =
                NotificationNotice(
                    scheduleId = rs.getObject("schedule_id", UUID::class.java),
                    scheduleName = rs.getString("name"),
                    timezone = rs.getString("timezone"),
                    targetRef = rs.getString("target_ref"),
                    runId = rs.getObject("run_id", UUID::class.java),
                    outcome = NotificationOutcome.fromWire(rs.getString("outcome")),
                    runState = RunState.fromWire(rs.getString("run_state")),
                    reason = rs.getString("reason"),
                    occurredAt = rs.getTimestamp("occurred_at").toInstant(),
                    scheduledAt = rs.getTimestamp("scheduled_at")?.toInstant(),
                ),
        )
}

/**
 * One claimed delivery and what its notice is about. [claimedAt] is the stored `sending_since` (as
 * the database rounded it), which the settle is conditional on. [recipient] is personal data:
 * [toString] shows its domain only.
 */
data class ClaimedDelivery(
    val id: UUID,
    val eventId: UUID,
    val recipient: String,
    val attempts: Int,
    val messageId: String,
    val claimedAt: Instant,
    val notice: NotificationNotice,
) {
    val recipientDomain: String get() = recipient.substringAfterLast('@')

    override fun toString(): String =
        "ClaimedDelivery(id=$id, eventId=$eventId, domain=$recipientDomain, attempts=$attempts, outcome=${notice.outcome.wire})"
}

/** A claim recovered as `uncertain` / `interrupted` — no address, only its domain for the log line. */
data class RecoveredDelivery(
    val id: UUID,
    val eventId: UUID,
    val scheduleId: UUID,
    val runId: UUID,
    val outcome: NotificationOutcome,
    val recipientDomain: String,
    val attempts: Int,
)
