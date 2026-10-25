package co.datapipelines.web.schedules

import co.datapipelines.auth.MailAddresses
import co.datapipelines.auth.MailFailure
import co.datapipelines.auth.MailKind
import co.datapipelines.auth.MailMessage
import co.datapipelines.auth.MailNotifier
import co.datapipelines.auth.MailProperties
import co.datapipelines.auth.MailSender
import co.datapipelines.auth.SendOutcome
import co.datapipelines.scheduler.DeliveryCodes
import co.datapipelines.scheduler.DeliveryOffReason
import co.datapipelines.scheduler.MailOutcome
import co.datapipelines.scheduler.NotificationMail
import co.datapipelines.scheduler.NotificationMailer

/**
 * The scheduler's mail port over auth's ONE shared SMTP transport (#442b; scheduler design revision
 * §6.2 — in `web`, the composition root `app` compiles against). No second SMTP configuration: the
 * message goes through the same [MailSender] the account notices use, labelled
 * [MailKind.SCHEDULE_NOTIFICATION] (a label, never a `mail_sends` claim — the delivery row is the
 * identity), carrying the delivery's stable `Message-ID` and the configured message-stream header.
 *
 * The recipient is parsed STRICTLY first: a stored value that is not exactly one bare address never
 * reaches a header (`invalid_address`). The transport's failure is reduced to auth's [MailFailure]
 * — the only module that sees Jakarta Mail — and from there to the scheduler's sanitized codes.
 */
class SmtpNotificationMailer(
    private val sender: MailSender,
    private val properties: MailProperties,
) : NotificationMailer {
    override fun send(mail: NotificationMail): MailOutcome {
        if (!MailAddresses.isStrict(mail.recipient)) return MailOutcome.Refused(DeliveryCodes.INVALID_ADDRESS)
        val headers =
            buildMap {
                put(MESSAGE_ID, mail.messageId)
                properties.messageStream?.takeIf { it.isNotBlank() }?.let { put(MailNotifier.STREAM_HEADER, it) }
            }
        val message = MailMessage(MailKind.SCHEDULE_NOTIFICATION, listOf(mail.recipient), mail.subject, mail.text, mail.html, headers)
        return when (val outcome = sender.send(message)) {
            is SendOutcome.Sent -> MailOutcome.Accepted
            SendOutcome.Skipped -> MailOutcome.Refused(DeliveryOffReason.MAIL_UNCONFIGURED.wire)
            is SendOutcome.Failed -> outcomeOf(MailFailure.of(outcome.error))
        }
    }

    companion object {
        private const val MESSAGE_ID = "Message-ID"

        /** auth's classification → the scheduler's: only a send that provably never reached the relay is retryable. */
        fun outcomeOf(failure: MailFailure): MailOutcome =
            when (failure) {
                MailFailure.CONNECT -> MailOutcome.Retryable(DeliveryCodes.CONNECT_FAILED)
                MailFailure.AUTH -> MailOutcome.Retryable(DeliveryCodes.AUTH_FAILED)
                MailFailure.TRANSIENT -> MailOutcome.Retryable(DeliveryCodes.SMTP_TRANSIENT)
                MailFailure.REFUSED -> MailOutcome.Refused(DeliveryCodes.SMTP_REFUSED)
                MailFailure.AMBIGUOUS -> MailOutcome.Uncertain(DeliveryCodes.IO_AFTER_CONNECT)
            }
    }
}
