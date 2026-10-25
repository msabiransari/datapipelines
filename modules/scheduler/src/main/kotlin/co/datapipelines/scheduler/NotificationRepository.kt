package co.datapipelines.scheduler

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * `schedule_notification_events` and `schedule_notification_deliveries` (V51; metadata-db §4.39,
 * §4.40): the outbox the ledger writes in a transition's transaction and the sender drains.
 *
 * Plain JDBC; the callers own the transactions (§8.5 rule 2). Every statement is parameterised;
 * an address is a bound value, never SQL text.
 */
class NotificationRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) {
    /**
     * The notification settings of [runId]'s schedule as they are NOW (the record: "the schedule's
     * event set at that moment"). Null for a run with no schedule, or whose schedule is deleted —
     * a deleted schedule notifies nobody.
     */
    fun settingsOfRun(runId: UUID): NotificationTarget? =
        jdbc
            .query(
                """
                SELECT s.id, s.notification_recipients, s.notification_events
                  FROM schedule_runs r JOIN schedules s ON s.id = r.schedule_id
                 WHERE r.id = :runId AND s.deleted_at IS NULL
                """.trimIndent(),
                mapOf("runId" to runId),
            ) { rs, _ ->
                NotificationTarget(
                    scheduleId = rs.getObject("id", UUID::class.java),
                    recipients = strings(rs, "notification_recipients"),
                    events = strings(rs, "notification_events").mapNotNull(NotificationEvent::fromWire).toSet(),
                )
            }.singleOrNull()

    /**
     * Inserts [event] — `ON CONFLICT ON CONSTRAINT uq_schedule_notification_events_run_outcome DO
     * NOTHING`: a run reaches each outcome once, so a replay writes nothing and returns false.
     */
    fun insertEvent(event: NotificationEventRow): Boolean =
        jdbc.update(
            """
            INSERT INTO schedule_notification_events
                (id, schedule_id, run_id, event, outcome, run_state, reason, occurred_at, sequence)
            VALUES (:id, :scheduleId, :runId, :event, :outcome, :runState, :reason, :occurredAt, :sequence)
            ON CONFLICT ON CONSTRAINT uq_schedule_notification_events_run_outcome DO NOTHING
            """.trimIndent(),
            mapOf(
                "id" to event.id,
                "scheduleId" to event.scheduleId,
                "runId" to event.runId,
                "event" to event.outcome.event.wire,
                "outcome" to event.outcome.wire,
                "runState" to event.runState.wire,
                "reason" to event.reason,
                "occurredAt" to Timestamp.from(event.occurredAt),
                "sequence" to event.sequence,
            ),
        ) == 1

    private val admissionJdbc =
        NamedParameterJdbcTemplate(
            JdbcTemplate(requireNotNull(jdbc.jdbcTemplate.dataSource)).apply { queryTimeout = ADMISSION_TIMEOUT_SECONDS },
        )

    /**
     * Serializes deployment-wide pending admission in the CALLER'S transaction (READ COMMITTED).
     * Lock order: enqueue's run row → advisory; resend's delivery row → advisory. The sender
     * claims delivery rows with SKIP LOCKED and never takes this advisory lock, so no cycle.
     * The dedicated statement timeout bounds contention without changing the shared template.
     */
    fun lockAdmission() {
        check(TransactionSynchronizationManager.isActualTransactionActive()) { "notification admission requires a transaction" }
        admissionJdbc.query(
            "SELECT pg_advisory_xact_lock(:key)",
            mapOf("key" to ADMISSION_LOCK_KEY),
        ) { _, _ -> Unit }
    }

    /** Deliveries waiting to be sent, deployment-wide — the backlog the cap is judged against. */
    fun pendingCount(): Int =
        jdbc.queryForObject(
            "SELECT count(*) FROM schedule_notification_deliveries WHERE state = 'pending'",
            emptyMap<String, Any>(),
            Int::class.java,
        ) ?: 0

    /** Writes [deliveries] as they were decided at enqueue: `pending` and due now, or settled `failed` with a code. */
    fun insertDeliveries(
        deliveries: List<NewDelivery>,
        now: Instant,
    ) {
        if (deliveries.isEmpty()) return
        val at = Timestamp.from(now)
        jdbc.batchUpdate(
            """
            INSERT INTO schedule_notification_deliveries
                (id, event_id, recipient, state, next_attempt_at, last_error_code, message_id, created_at, updated_at)
            VALUES (:id, :eventId, :recipient, :state, :nextAttemptAt, :code, :messageId, :now, :now)
            """.trimIndent(),
            deliveries
                .map { d ->
                    mapOf(
                        "id" to d.id,
                        "eventId" to d.eventId,
                        "recipient" to d.recipient,
                        "state" to (if (d.refusal == null) DeliveryState.PENDING else DeliveryState.FAILED).wire,
                        "nextAttemptAt" to (if (d.refusal == null) at else null),
                        "code" to d.refusal,
                        "messageId" to d.messageId,
                        "now" to at,
                    )
                }.toTypedArray(),
        )
    }

    /** The deliveries of [eventId], oldest first — a test's and the run detail's read. */
    fun deliveriesOf(eventId: UUID): List<Delivery> =
        jdbc.query(
            "SELECT * FROM schedule_notification_deliveries WHERE event_id = :eventId ORDER BY created_at, recipient",
            mapOf("eventId" to eventId),
            deliveryMapper,
        )

    /**
     * The deliveries of [runId], each with the notice it carries — the run detail's read (rest-api
     * §20.11), in transition then recipient order. Bounded by its own key: one event per outcome
     * of the run (`uq_schedule_notification_events_run_outcome`) times the recipient cap.
     */
    fun deliveriesOfRun(runId: UUID): List<RunDelivery> =
        jdbc.query(
            """
            SELECT d.*, e.outcome
              FROM schedule_notification_deliveries d JOIN schedule_notification_events e ON e.id = d.event_id
             WHERE e.run_id = :runId
             ORDER BY e.sequence, e.outcome, d.recipient
            """.trimIndent(),
            mapOf("runId" to runId),
            runDeliveryMapper,
        )

    /**
     * [deliveryId] if it belongs to [runId], row-locked for the resend that reads it (the caller's
     * transaction): the sender settles a claim under its own condition, and a failed or uncertain
     * row is never claimed, so the lock orders two resends of the same row, nothing else.
     */
    fun lockOfRun(
        runId: UUID,
        deliveryId: UUID,
    ): RunDelivery? =
        jdbc
            .query(
                """
                SELECT d.*, e.outcome
                  FROM schedule_notification_deliveries d JOIN schedule_notification_events e ON e.id = d.event_id
                 WHERE d.id = :id AND e.run_id = :runId
                   FOR UPDATE OF d
                """.trimIndent(),
                mapOf("id" to deliveryId, "runId" to runId),
                runDeliveryMapper,
            ).singleOrNull()

    /**
     * An explicit resend (#442b): a `failed` or `uncertain` delivery becomes `pending` and due at
     * [now], its attempts KEPT and `resend_count` + 1; its last code stays until the next attempt
     * settles. False when the row was in any other state.
     */
    fun requeue(
        deliveryId: UUID,
        now: Instant,
    ): Boolean =
        jdbc.update(
            """
            UPDATE schedule_notification_deliveries
               SET state = 'pending', next_attempt_at = :now, resend_count = resend_count + 1, updated_at = :now
             WHERE id = :id AND state IN ('failed', 'uncertain')
            """.trimIndent(),
            mapOf("id" to deliveryId, "now" to Timestamp.from(now)),
        ) == 1

    /** The events of [runId], in transition order. */
    fun eventsOfRun(runId: UUID): List<NotificationEventRow> =
        jdbc.query(
            "SELECT * FROM schedule_notification_events WHERE run_id = :runId ORDER BY sequence, outcome",
            mapOf("runId" to runId),
            eventMapper,
        )

    private val eventMapper =
        RowMapper { rs, _ ->
            NotificationEventRow(
                id = rs.getObject("id", UUID::class.java),
                scheduleId = rs.getObject("schedule_id", UUID::class.java),
                runId = rs.getObject("run_id", UUID::class.java),
                outcome = NotificationOutcome.fromWire(rs.getString("outcome")),
                runState = RunState.fromWire(rs.getString("run_state")),
                reason = rs.getString("reason"),
                occurredAt = rs.getTimestamp("occurred_at").toInstant(),
                sequence = rs.getInt("sequence"),
            )
        }

    internal val deliveryMapper =
        RowMapper { rs, _ ->
            Delivery(
                id = rs.getObject("id", UUID::class.java),
                eventId = rs.getObject("event_id", UUID::class.java),
                recipient = rs.getString("recipient"),
                state = DeliveryState.fromWire(rs.getString("state")),
                attempts = rs.getInt("attempts"),
                nextAttemptAt = rs.getTimestamp("next_attempt_at")?.toInstant(),
                lastErrorCode = rs.getString("last_error_code"),
                messageId = rs.getString("message_id"),
                sentAt = rs.getTimestamp("sent_at")?.toInstant(),
                resendCount = rs.getInt("resend_count"),
                createdAt = rs.getTimestamp("created_at").toInstant(),
                updatedAt = rs.getTimestamp("updated_at").toInstant(),
            )
        }

    private val runDeliveryMapper =
        RowMapper { rs, n ->
            RunDelivery(NotificationOutcome.fromWire(rs.getString("outcome")), checkNotNull(deliveryMapper.mapRow(rs, n)))
        }

    companion object {
        /** One key for the entire deployment's outbox (metadata-db §4.40), never derived from input. */
        const val ADMISSION_LOCK_KEY = 442_004_001L
        private const val ADMISSION_TIMEOUT_SECONDS = 5
    }

    private fun strings(
        rs: ResultSet,
        column: String,
    ): List<String> {
        val array = rs.getArray(column)
        return try {
            (array.array as Array<*>).map { it as String }
        } finally {
            array.free()
        }
    }
}

/** A schedule's notification settings, read at enqueue. [recipients] is personal data: never logged. */
data class NotificationTarget(
    val scheduleId: UUID,
    val recipients: List<String>,
    val events: Set<NotificationEvent>,
) {
    override fun toString(): String = "NotificationTarget(scheduleId=$scheduleId, recipients=${recipients.size}, events=$events)"
}

/** A delivery decided at enqueue: [refusal] null = pending, else the code it is written `failed` with. */
data class NewDelivery(
    val id: UUID,
    val eventId: UUID,
    val recipient: String,
    val messageId: String,
    val refusal: String?,
) {
    override fun toString(): String = "NewDelivery(id=$id, eventId=$eventId, refusal=$refusal)"
}
