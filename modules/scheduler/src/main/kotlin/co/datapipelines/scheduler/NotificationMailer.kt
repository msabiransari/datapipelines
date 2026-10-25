package co.datapipelines.scheduler

/**
 * The scheduler's mail port (#442b; scheduler design revision §6.2): one rendered notice to ONE
 * recipient. The scheduler cannot see `auth`, so `web` implements this over auth's shared SMTP
 * transport — there is no second SMTP configuration. An implementation never throws for a send
 * that failed: it answers with the [MailOutcome] that says whether the relay could have accepted the
 * message, because that, and nothing else, decides whether a retry is safe.
 */
fun interface NotificationMailer {
    fun send(mail: NotificationMail): MailOutcome

    companion object {
        /** No composition root supplied a transport: nothing is attempted, every send is refused `mail_unconfigured`. */
        val UNAVAILABLE = NotificationMailer { MailOutcome.Refused(DeliveryOffReason.MAIL_UNCONFIGURED.wire) }
    }
}

/**
 * One message as the sender hands it to the port: exactly one [recipient] (never a list — one
 * message per recipient, so no address sees another), the subject, both bodies and the delivery's
 * stable [messageId], which a resend reuses. [toString] names neither the address nor the bodies.
 */
data class NotificationMail(
    val recipient: String,
    val subject: String,
    val text: String,
    val html: String,
    val messageId: String,
) {
    override fun toString(): String =
        "NotificationMail(domain=${recipient.substringAfterLast('@')}, subject=$subject, messageId=$messageId, " +
            "text=${text.length} chars, html=${html.length} chars)"
}

/**
 * What the transport said, reduced to the one question a retrying sender needs answered: could the
 * relay have accepted the message? Each failure carries a sanitized [DeliveryCodes] value — never
 * transport text, never an address.
 */
sealed interface MailOutcome {
    /** The relay accepted the message. Not proof of delivery to an inbox. */
    data object Accepted : MailOutcome

    /** Provably not accepted, and worth another try later (the relay was unreachable, said 4xx, or refused our login). */
    data class Retryable(
        val code: String,
    ) : MailOutcome

    /** Provably not accepted, and another try would say the same (5xx, an invalid address, mail off). */
    data class Refused(
        val code: String,
    ) : MailOutcome

    /** May have been accepted — the connection failed after the message went out. Never retried automatically. */
    data class Uncertain(
        val code: String,
    ) : MailOutcome
}
