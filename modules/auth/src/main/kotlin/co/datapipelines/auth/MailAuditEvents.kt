package co.datapipelines.auth

/**
 * The mail audit event names ([Enums §15](../../../../../../../../docs/enums.md), auth.md §5A.8)
 * — the wire strings written to `audit_log.event`. `MailAuditEventsSpecDriftTest` fails if this
 * list and enums.md §15 ever disagree (the `SemanticsAuditEvents` guard applied here).
 *
 * `details` carry `kind`, `to`, `act_id`, and `message_id` on a send / `error` (class +
 * message) on a failure — NEVER the body, NEVER the one-time password (the redaction rule;
 * pinned by `MailNotifierIntegrationTest`).
 */
object MailAuditEvents {
    /** The transport accepted a notice. */
    const val SENT = "mail.sent"

    /** The transport refused or failed a notice; the claim row carries the same error. */
    const val FAILED = "mail.failed"

    /** Every registered name — the drift-test surface. */
    val ALL = listOf(SENT, FAILED)
}
