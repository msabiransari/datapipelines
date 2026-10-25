package co.datapipelines.auth

import jakarta.mail.AuthenticationFailedException
import jakarta.mail.MessagingException
import jakarta.mail.SendFailedException
import jakarta.mail.internet.AddressException
import jakarta.mail.internet.InternetAddress
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException
import org.eclipse.angus.mail.smtp.SMTPSendFailedException
import org.eclipse.angus.mail.util.MailConnectException
import org.slf4j.LoggerFactory
import org.springframework.mail.MailAuthenticationException
import org.springframework.mail.MailSendException
import java.net.ConnectException
import java.net.UnknownHostException

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

/**
 * How a [SendOutcome.Failed] ended, as far as the relay's side can be known (#442b) — the one fact a
 * caller that retries needs: could the relay have ACCEPTED the message? Only the transport's own
 * module can tell, because only it sees Jakarta Mail; the scheduler's adapter maps these to its
 * delivery codes. Nothing here is ever logged with the exception's text.
 */
enum class MailFailure {
    /** The relay was never reached, or the session failed before any message (STARTTLS, greeting). Nothing was sent. */
    CONNECT,

    /** The relay refused the credentials. Nothing was sent. */
    AUTH,

    /** The relay answered 4xx — try later. It said it did not accept the message. */
    TRANSIENT,

    /** The relay answered 5xx, or an address was refused before sending. It did not accept the message. */
    REFUSED,

    /** Anything else after the session opened (a dropped connection, a timeout, a failed close): it may have been accepted. */
    AMBIGUOUS,
    ;

    companion object {
        /**
         * Classifies [error] as [SpringMailSender] returns it. Spring's `JavaMailSenderImpl` reports
         * the connect phase as a `MailSendException` whose every failed message maps to its CAUSE
         * (the one connect exception), a send failure as per-message exceptions with no cause, and a
         * failed close as a cause beside them — so the connect phase is recognised by that shape,
         * never by message text.
         */
        fun of(error: Throwable): MailFailure {
            val chain = chainOf(error)
            return when {
                chain.any { it is MailAuthenticationException || it is AuthenticationFailedException } -> AUTH
                chain.any { it is MailConnectException || it is ConnectException || it is UnknownHostException } -> CONNECT
                error is MailSendException && connectPhase(error) -> CONNECT
                else -> byReplyCode(chain) ?: if (chain.any(::refusedBeforeSending)) REFUSED else AMBIGUOUS
            }
        }

        /** The relay's own answer, when one is in [chain]: 4xx [TRANSIENT], 5xx [REFUSED]; null without one. */
        private fun byReplyCode(chain: List<Throwable>): MailFailure? =
            when (chain.firstNotNullOfOrNull(::replyCodeOf)) {
                null -> null
                in TRANSIENT_CODES -> TRANSIENT
                in PERMANENT_CODES -> REFUSED
                else -> null
            }

        /** An address Jakarta Mail refused before handing anything to the relay. */
        private fun refusedBeforeSending(error: Throwable): Boolean =
            error is AddressException || (error is SendFailedException && error !is SMTPSendFailedException)

        private val TRANSIENT_CODES = 400..499
        private val PERMANENT_CODES = 500..599

        private fun connectPhase(error: MailSendException): Boolean {
            val cause = error.cause ?: return false
            return error.failedMessages.isNotEmpty() && error.failedMessages.values.all { it === cause }
        }

        private fun replyCodeOf(error: Throwable): Int? =
            when (error) {
                is SMTPSendFailedException -> error.returnCode
                is SMTPAddressFailedException -> error.returnCode
                else -> null
            }

        /** [error], its causes, a Spring send failure's per-message exceptions and a Jakarta chain's next exceptions. */
        private fun chainOf(error: Throwable): List<Throwable> {
            val seen = LinkedHashSet<Throwable>()
            val pending = ArrayDeque(listOf(error))
            while (pending.isNotEmpty() && seen.size < MAX_CHAIN) {
                val next = pending.removeFirst()
                if (!seen.add(next)) continue
                next.cause?.let(pending::addLast)
                if (next is MailSendException) pending.addAll(next.failedMessages.values)
                if (next is MessagingException) next.nextException?.let(pending::addLast)
            }
            return seen.toList()
        }

        private const val MAX_CHAIN = 32
    }
}

/**
 * The strict RFC 822 address check a sender applies at SEND time (#442b, the brief's header-safety
 * rule): a stored address that Jakarta Mail's strict parser refuses — a second address, a display
 * name, a line break, a group — never reaches a header. True when [address] is exactly one bare,
 * strictly valid address.
 */
object MailAddresses {
    fun isStrict(address: String): Boolean =
        try {
            val parsed = InternetAddress(address, true)
            parsed.validate()
            parsed.personal == null && parsed.address == address && !parsed.isGroup
        } catch (_: AddressException) {
            false
        }
}
