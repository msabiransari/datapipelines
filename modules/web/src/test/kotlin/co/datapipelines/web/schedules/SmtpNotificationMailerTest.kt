package co.datapipelines.web.schedules

import co.datapipelines.auth.MailFailure
import co.datapipelines.auth.MailKind
import co.datapipelines.auth.MailMessage
import co.datapipelines.auth.MailNotifier
import co.datapipelines.auth.MailProperties
import co.datapipelines.auth.MailSender
import co.datapipelines.auth.SendOutcome
import co.datapipelines.scheduler.DeliveryCodes
import co.datapipelines.scheduler.MailOutcome
import co.datapipelines.scheduler.NotificationMail
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.mail.MailSendException

/**
 * #442b — the scheduler's mail port over auth's shared transport: one recipient per message, the
 * delivery's Message-ID and the configured stream header on it, the strict address check BEFORE the
 * transport sees anything, and auth's failure classes mapped to the scheduler's retry semantics.
 */
class SmtpNotificationMailerTest {
    private val transport = RecordingSender()
    private val mail = NotificationMail("ops@example.com", "reports/daily — Failed", "text", "<p>html</p>", "<d1@dp.example.com>")

    private fun mailer(stream: String? = null) =
        SmtpNotificationMailer(transport, MailProperties(host = "smtp.example.com", from = "dp@example.com", messageStream = stream))

    @Test
    fun `a send is one message to the one recipient, labelled, under the delivery's Message-ID and the stream header`() {
        mailer(stream = "outbound").send(mail) shouldBe MailOutcome.Accepted

        val message = transport.messages.single()
        message.kind shouldBe MailKind.SCHEDULE_NOTIFICATION
        message.to shouldBe listOf("ops@example.com")
        message.subject shouldBe mail.subject
        message.headers shouldBe mapOf("Message-ID" to "<d1@dp.example.com>", MailNotifier.STREAM_HEADER to "outbound")
    }

    @Test
    fun `no stream configured adds no stream header`() {
        mailer().send(mail)
        transport.messages.single().headers shouldBe mapOf("Message-ID" to "<d1@dp.example.com>")
    }

    @Test
    fun `an address the strict parser refuses never reaches the transport`() {
        listOf("ops@example.com, x@example.org", "Ops <ops@example.com>", "ops@example.com\r\nBcc: x@example.org").forEach { bad ->
            mailer().send(mail.copy(recipient = bad)) shouldBe MailOutcome.Refused(DeliveryCodes.INVALID_ADDRESS)
        }
        transport.messages.shouldBeEmpty()
    }

    @Test
    fun `mail off at the transport is refused mail_unconfigured, and a failure is classified, never thrown`() {
        transport.next = SendOutcome.Skipped
        mailer().send(mail) shouldBe MailOutcome.Refused("mail_unconfigured")
        transport.next = SendOutcome.Failed(MailSendException("dropped after DATA"))
        mailer().send(mail) shouldBe MailOutcome.Uncertain(DeliveryCodes.IO_AFTER_CONNECT)
    }

    @Test
    fun `only a send that provably never reached the relay is retryable`() {
        MailFailure.entries.associateWith(SmtpNotificationMailer::outcomeOf) shouldBe
            mapOf(
                MailFailure.CONNECT to MailOutcome.Retryable(DeliveryCodes.CONNECT_FAILED),
                MailFailure.AUTH to MailOutcome.Retryable(DeliveryCodes.AUTH_FAILED),
                MailFailure.TRANSIENT to MailOutcome.Retryable(DeliveryCodes.SMTP_TRANSIENT),
                MailFailure.REFUSED to MailOutcome.Refused(DeliveryCodes.SMTP_REFUSED),
                MailFailure.AMBIGUOUS to MailOutcome.Uncertain(DeliveryCodes.IO_AFTER_CONNECT),
            )
    }

    /** auth's port, recording: every message it is handed, answered with [next]. */
    private class RecordingSender : MailSender {
        val messages = mutableListOf<MailMessage>()
        var next: SendOutcome = SendOutcome.Sent(messageId = null)

        override fun send(message: MailMessage): SendOutcome {
            messages += message
            return next
        }
    }
}
