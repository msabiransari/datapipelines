package co.datapipelines.auth

import org.slf4j.LoggerFactory

/**
 * One outbound message as the port sees it (auth.md §5A.8): the kind, the recipients, the
 * subject, both bodies and any extra headers. From and Reply-To are the transport's own
 * configuration ([MailProperties]), not the message's.
 *
 * The body is the one place a one-time password may appear — never `toString` this into a
 * log line; the notifier logs kinds and domains, not messages.
 */
data class MailMessage(
    val kind: MailKind,
    val to: List<String>,
    val subject: String,
    val text: String,
    val html: String,
    val headers: Map<String, String> = emptyMap(),
) {
    override fun toString(): String = "MailMessage(kind=$kind, to=${to.map(::domainOf)}, subject=$subject, headers=${headers.keys})"

    companion object {
        /** The domain part of an address — what a log line may carry. */
        fun domainOf(address: String): String = address.substringAfterLast('@', missingDelimiterValue = "?")
    }
}

/** What the transport said. A [Failed] is returned, never thrown — the notifier records it. */
sealed interface SendOutcome {
    /** Accepted; [messageId] is the `Message-ID` the message went out under, when known. */
    data class Sent(
        val messageId: String?,
    ) : SendOutcome

    /** Refused or failed. The notifier stores the class + message, never the message body. */
    data class Failed(
        val error: Throwable,
    ) : SendOutcome

    /** Mail is off — nothing was attempted (see [NoopMailSender]). */
    data object Skipped : SendOutcome
}

/**
 * The outbound port (auth.md §5A.8). Two implementations: [SpringMailSender] over
 * `JavaMailSender` when mail is enabled, [NoopMailSender] otherwise — chosen once, at wiring
 * ([MailConfiguration]), from [MailProperties.enabled]. The bean graph is identical either way.
 */
interface MailSender {
    fun send(message: MailMessage): SendOutcome
}

/**
 * The port when mail is OFF: logs ONE line at INFO per skipped send — the kind and the
 * recipient DOMAIN only, never the address, never the body — and reports [SendOutcome.Skipped].
 * The line is `event=mail.skipped` (observability.md §3.4B).
 */
class NoopMailSender : MailSender {
    private val log = LoggerFactory.getLogger(NoopMailSender::class.java)

    override fun send(message: MailMessage): SendOutcome {
        log.info(
            "event=mail.skipped kind={} domain={} message=\"mail is not configured (datapipelines.mail.host + from); nothing sent\"",
            message.kind.wire,
            message.to
                .map(MailMessage::domainOf)
                .distinct()
                .joinToString(","),
        )
        return SendOutcome.Skipped
    }
}
