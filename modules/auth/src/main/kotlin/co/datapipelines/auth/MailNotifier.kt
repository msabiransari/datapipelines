package co.datapipelines.auth

import org.slf4j.LoggerFactory
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executor

/**
 * What the auth flows ask for (auth.md §5A.8) — the hook points call THIS, never the transport:
 * a welcome at local-account creation, a reset mail at an admin reset, a new-user notice at
 * every user creation (local, or the OIDC callback's create branch).
 *
 * [NONE] is for auth-only test slices that construct a service directly; the application
 * always wires the real [MailNotifier], and the E2E proves it.
 */
interface MailNotices {
    fun welcome(
        user: User,
        oneTimePassword: String,
    )

    fun passwordReset(
        user: User,
        oneTimePassword: String,
        resetId: UUID,
    )

    fun newUser(
        user: User,
        createdBy: String,
        workspace: String?,
    )

    companion object {
        /** Sends nothing — test slices only; the wiring never chooses it. */
        val NONE: MailNotices =
            object : MailNotices {
                override fun welcome(
                    user: User,
                    oneTimePassword: String,
                ) = Unit

                override fun passwordReset(
                    user: User,
                    oneTimePassword: String,
                    resetId: UUID,
                ) = Unit

                override fun newUser(
                    user: User,
                    createdBy: String,
                    workspace: String?,
                ) = Unit
            }
    }
}

/**
 * The one dispatcher behind [MailNotices] (auth.md §5A.8):
 *
 * 1. **Render** the notice ([MailTemplates]) — the password is a template variable and
 *    nothing else here ever sees it again.
 * 2. **Claim before send** ([MailSendRepository.tryClaim]), ON THE CALLER'S THREAD: the
 *    message identity `(user, kind, act)` is inserted first; a second attempt — a retry, a
 *    double-submit, a second instance — finds the row and stops. The welcome mail carries a
 *    password and must never go twice. Claiming here rather than on the pool thread means
 *    the row rides the caller's transaction when there is one (a rolled-back creation claims
 *    nothing) and already exists — `PENDING` — when the admin screen renders the response.
 * 3. **After commit, off the request thread.** When a metadata transaction is open the send
 *    is registered as an `afterCommit` synchronization (the house pattern — `ConnectionLease`
 *    reads the same API); when none is, it is handed to the executor at once. Either way the
 *    request thread never waits on SMTP, and a send never fails a request. Today neither hook
 *    point runs inside a transaction (the create paths catch-and-reread a duplicate insert,
 *    which an aborted Postgres transaction would forbid), so the synchronization is the
 *    forward-compatible half and the direct hand-off the live one — the integration test
 *    proves both.
 * 4. **Send, then record**: the claim row gets `sent_at` + the Message-ID or the error, and
 *    an audit row (`mail.sent` / `mail.failed`) carries kind, recipients, act and the id or
 *    the error class — never the body. A row still `PENDING` long after its claim is a send
 *    the process never got to (it died between commit and send): visible on the admin
 *    screen, and the admin's answer is a reset, never a silent retry of a password mail.
 *
 * Mail OFF short-circuits before step 2: the [NoopMailSender] logs its one INFO line and
 * nothing is claimed or audited — an unconfigured deployment leaves no trace of notices it
 * could not send.
 *
 * The executor is bounded (two threads, a queue of 100, caller-runs on overflow — see
 * [MailConfiguration]); a task here catches everything, because an exception on a pool
 * thread is a line nobody reads.
 */
class MailNotifier(
    private val properties: MailProperties,
    private val authProperties: AuthProperties,
    private val templates: MailTemplates,
    private val sender: MailSender,
    private val sends: MailSendRepository,
    private val audit: AuditEventSink,
    private val executor: Executor,
) : MailNotices {
    private val log = LoggerFactory.getLogger(MailNotifier::class.java)

    override fun welcome(
        user: User,
        oneTimePassword: String,
    ) = dispatch(MailKind.WELCOME, user, actId = user.id, to = listOf(user.email)) {
        templates.welcome(MailKind.WELCOME, user.email, loginUrl(), oneTimePassword, replyTo())
    }

    override fun passwordReset(
        user: User,
        oneTimePassword: String,
        resetId: UUID,
    ) = dispatch(MailKind.PASSWORD_RESET, user, actId = resetId, to = listOf(user.email)) {
        templates.welcome(MailKind.PASSWORD_RESET, user.email, loginUrl(), oneTimePassword, replyTo())
    }

    override fun newUser(
        user: User,
        createdBy: String,
        workspace: String?,
    ) {
        val ops = properties.opsRecipients()
        if (ops.isEmpty()) return
        dispatch(MailKind.NEW_USER, user, actId = user.id, to = ops) {
            templates.newUser(
                NewUserNotice(
                    email = user.email,
                    // The email's local part is the placeholder every creation path stores when
                    // no name was given — not a name worth a line of its own.
                    displayName = user.displayName.takeIf { it.isNotBlank() && it != user.email.substringBefore('@') },
                    provider = user.provider,
                    createdBy = createdBy,
                    workspace = workspace,
                    at = Instant.now(),
                    adminUsersUrl = "${baseUrl()}/admin/users",
                ),
            )
        }
    }

    private fun dispatch(
        kind: MailKind,
        user: User,
        actId: UUID,
        to: List<String>,
        render: () -> RenderedMail,
    ) {
        val rendered = render()
        val message =
            MailMessage(
                kind = kind,
                to = to,
                subject = rendered.subject,
                text = rendered.text,
                html = rendered.html,
                headers =
                    properties.messageStream
                        ?.takeIf { it.isNotBlank() }
                        ?.let { mapOf(STREAM_HEADER to it) }
                        .orEmpty(),
            )
        if (!properties.enabled) {
            sender.send(message)
            return
        }
        val claim = sends.tryClaim(user.id, kind, actId, to.joinToString(", "))
        if (claim == null) {
            log.debug("event=mail.already_claimed kind={} user={} act={}", kind.wire, user.id, actId)
            return
        }
        afterCommitOrNow { deliver(claim, user.id, actId, message) }
    }

    /** The house pattern: inside a transaction, after its commit; otherwise now. */
    private fun afterCommitOrNow(task: () -> Unit) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                object : TransactionSynchronization {
                    override fun afterCommit() = executor.execute(task)
                },
            )
        } else {
            executor.execute(task)
        }
    }

    /** The pool task: send, then record on the claim. Catches everything — a pool thread has no reader. */
    @Suppress("TooGenericExceptionCaught")
    private fun deliver(
        claim: UUID,
        userId: UUID,
        actId: UUID,
        message: MailMessage,
    ) {
        try {
            record(claim, userId, actId, message, sender.send(message))
        } catch (e: Exception) {
            log.warn("event=mail.dispatch_failed kind={} user={} error={}", message.kind.wire, userId, e.toString())
        }
    }

    private fun record(
        claim: UUID,
        userId: UUID,
        actId: UUID,
        message: MailMessage,
        outcome: SendOutcome,
    ) {
        val base = mapOf("kind" to message.kind.wire, "to" to message.to.joinToString(", "), "act_id" to actId.toString())
        when (outcome) {
            is SendOutcome.Sent -> {
                sends.markSent(claim, outcome.messageId)
                audit.log(MailAuditEvents.SENT, userId = userId, details = base + ("message_id" to outcome.messageId))
                log.info(
                    "event=mail.accepted kind={} domain={} message_id={}",
                    message.kind.wire,
                    message.to
                        .map(MailMessage::domainOf)
                        .distinct()
                        .joinToString(","),
                    outcome.messageId,
                )
            }

            is SendOutcome.Failed -> {
                val error = "${outcome.error.javaClass.simpleName}: ${outcome.error.message}"
                sends.markFailed(claim, error)
                audit.log(MailAuditEvents.FAILED, userId = userId, details = base + ("error" to error))
                log.warn("event=mail.send_failed kind={} user={} error={}", message.kind.wire, userId, error)
            }

            SendOutcome.Skipped -> {
                // A claimed message the sender declined to attempt — only a NoopMailSender does
                // that, and it is never wired while mail is on. Recorded as failed so the row
                // is honest rather than pending forever.
                sends.markFailed(claim, "skipped")
            }
        }
    }

    private fun baseUrl(): String = authProperties.baseUrl?.trimEnd('/').orEmpty()

    private fun loginUrl(): String = "${baseUrl()}/login"

    private fun replyTo(): String = properties.effectiveReplyTo().orEmpty()

    companion object {
        /** Postmark's stream selector; the only vendor-shaped header the product knows. */
        const val STREAM_HEADER = "X-PM-Message-Stream"
    }
}
