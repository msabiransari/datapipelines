package co.datapipelines.auth

import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/**
 * The three notices the product sends (auth.md §5A.8) — the `mail_sends.kind` closed list
 * (`chk_mail_sends_kind`, V27) and the template family under `templates/mail/`.
 */
enum class MailKind(
    /** The stored value and the audit `kind`. */
    val wire: String,
) {
    /** To the user, at local-account creation: the login URL and the one-time password. */
    WELCOME("welcome"),

    /** To the user, at an admin reset: the same shape, a new one-time password. */
    PASSWORD_RESET("password_reset"),

    /** To sys-ops, at every new user (local or first social login). Never a password. */
    NEW_USER("new_user"),
}

/** One `mail_sends` row — the claim on a message identity and, later, its outcome. */
data class MailSend(
    val id: UUID,
    val userId: UUID,
    val kind: MailKind,
    val actId: UUID,
    val recipient: String,
    val claimedAt: Instant,
    val sentAt: Instant?,
    val messageId: String?,
    val error: String?,
) {
    enum class Status { PENDING, SENT, FAILED }

    /** Derived: claimed and neither stamp = the send has not returned yet. */
    val status: Status
        get() =
            when {
                sentAt != null -> Status.SENT
                error != null -> Status.FAILED
                else -> Status.PENDING
            }
}

/**
 * `mail_sends` (metadata-db §4.19, V27) — the claim row every notice passes through BEFORE the
 * transport is touched.
 *
 * ## Why a claim, and why before the send
 * The welcome mail carries a one-time password and must never go twice. The identity of a
 * message is `(user, kind, act)`; [tryClaim] inserts it with `ON CONFLICT DO NOTHING`, so
 * exactly one caller — the first, on whichever instance — gets an id back and sends. A retry,
 * a double-submit, or a second replica finds the row and stops. A claim is therefore an
 * ATTEMPT record, not a delivery: [markSent] and [markFailed] fill in what the transport
 * said afterwards, and nothing deletes the row — it is what the admin screen reads back
 * ("Emailed to … — sent" / "failed: …") and what an operator greps.
 *
 * `act_id` is the user's own id for the once-per-user kinds and a fresh id per reset for
 * [MailKind.PASSWORD_RESET] (two resets mint two credentials; both mails must go).
 */
class MailSendRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) {
    /** The claim: this caller's id when it won the row, `null` when the message is already claimed. */
    fun tryClaim(
        userId: UUID,
        kind: MailKind,
        actId: UUID,
        recipient: String,
    ): UUID? {
        val id = UUID.randomUUID()
        val claimed =
            jdbc.query(
                """
                INSERT INTO mail_sends (id, user_id, kind, act_id, recipient)
                VALUES (:id, :user_id, :kind, :act_id, :recipient)
                ON CONFLICT (user_id, kind, act_id) DO NOTHING
                RETURNING id
                """.trimIndent(),
                MapSqlParameterSource()
                    .addValue("id", id)
                    .addValue("user_id", userId)
                    .addValue("kind", kind.wire)
                    .addValue("act_id", actId)
                    .addValue("recipient", recipient),
            ) { rs, _ -> rs.getObject("id", UUID::class.java) }
        return claimed.firstOrNull()
    }

    /** The transport accepted the message; [messageId] is the `Message-ID` it went out under, when known. */
    fun markSent(
        id: UUID,
        messageId: String?,
    ) {
        jdbc.update(
            "UPDATE mail_sends SET sent_at = NOW(), message_id = :message_id WHERE id = :id",
            MapSqlParameterSource().addValue("id", id).addValue("message_id", messageId),
        )
    }

    /** The transport refused or failed; [error] is the exception's class and message — never a body. */
    fun markFailed(
        id: UUID,
        error: String,
    ) {
        jdbc.update(
            "UPDATE mail_sends SET error = :error WHERE id = :id",
            MapSqlParameterSource().addValue("id", id).addValue("error", error),
        )
    }

    /** The row for one message identity, or `null` when nothing has claimed it. */
    fun find(
        userId: UUID,
        kind: MailKind,
        actId: UUID,
    ): MailSend? =
        jdbc
            .query(
                """
                SELECT id, user_id, kind, act_id, recipient, claimed_at, sent_at, message_id, error
                FROM mail_sends
                WHERE user_id = :user_id AND kind = :kind AND act_id = :act_id
                """.trimIndent(),
                MapSqlParameterSource()
                    .addValue("user_id", userId)
                    .addValue("kind", kind.wire)
                    .addValue("act_id", actId),
                ROW_MAPPER,
            ).firstOrNull()

    /**
     * The most recently claimed message of [kind] for [userId] — the admin screen's read after
     * a reset, whose act id the screen does not otherwise hold. Ordered by claim time alone: two
     * resets of one user inside the same microsecond is not a shape an admin's clicks produce,
     * and a physical tie-breaker (`ctid`) would be worse — an UPDATE moves a tuple.
     */
    fun latest(
        userId: UUID,
        kind: MailKind,
    ): MailSend? =
        jdbc
            .query(
                """
                SELECT id, user_id, kind, act_id, recipient, claimed_at, sent_at, message_id, error
                FROM mail_sends
                WHERE user_id = :user_id AND kind = :kind
                ORDER BY claimed_at DESC
                LIMIT 1
                """.trimIndent(),
                MapSqlParameterSource().addValue("user_id", userId).addValue("kind", kind.wire),
                ROW_MAPPER,
            ).firstOrNull()

    private companion object {
        val ROW_MAPPER =
            RowMapper<MailSend> { rs: ResultSet, _: Int ->
                MailSend(
                    id = rs.getObject("id", UUID::class.java),
                    userId = rs.getObject("user_id", UUID::class.java),
                    kind = MailKind.entries.single { it.wire == rs.getString("kind") },
                    actId = rs.getObject("act_id", UUID::class.java),
                    recipient = rs.getString("recipient"),
                    claimedAt = rs.getObject("claimed_at", OffsetDateTime::class.java).toInstant(),
                    sentAt = rs.getObject("sent_at", OffsetDateTime::class.java)?.toInstant(),
                    messageId = rs.getString("message_id"),
                    error = rs.getString("error"),
                )
            }
    }
}
