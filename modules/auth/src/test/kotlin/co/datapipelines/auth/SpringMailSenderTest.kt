package co.datapipelines.auth

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import jakarta.mail.Multipart
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import org.junit.jupiter.api.Test
import org.springframework.mail.MailSendException
import org.springframework.mail.javamail.JavaMailSenderImpl

/**
 * [SpringMailSender] against a CAPTURING `JavaMailSenderImpl` — the real session, the real
 * MimeMessage assembly, only the transport hop replaced by a list: what goes on the wire is
 * exactly what is asserted here (From, Reply-To, To, Subject, both parts, the headers, the
 * Message-ID the outcome carries), and a transport failure is RETURNED, never thrown. The
 * session's own configuration ([SpringMailSender.javaMailSender]) is asserted property by
 * property — `starttls.required` is the one that makes "required, not opportunistic" true.
 */
class SpringMailSenderTest {
    private val properties =
        MailProperties(
            host = "smtp.example.com",
            port = 2525,
            username = "token",
            password = "secret",
            starttls = true,
            from = "Data Pipelines <dp@example.com>",
            replyTo = "help@example.com",
        )

    private val message =
        MailMessage(
            kind = MailKind.WELCOME,
            to = listOf("ada@company.com", "ops@company.com"),
            subject = "Your datapipelines account",
            text = "plain body",
            html = "<p>html body</p>",
            headers = mapOf(MailNotifier.STREAM_HEADER to "outbound"),
        )

    /** The real session and assembly; `send` captures instead of connecting. */
    private class CapturingJavaMailSender(
        private val fail: Boolean = false,
    ) : JavaMailSenderImpl() {
        val sent = mutableListOf<MimeMessage>()

        override fun send(mimeMessage: MimeMessage) {
            if (fail) throw MailSendException("Connection refused: smtp.example.com:2525")
            mimeMessage.saveChanges() // what the transport hop does before writing — assigns the Message-ID
            sent += mimeMessage
        }
    }

    @Test
    fun `the message on the wire carries from, reply-to, every recipient, the subject, both parts and the headers`() {
        val transport = CapturingJavaMailSender()

        val outcome = SpringMailSender(properties, transport).send(message)

        val mime = transport.sent.single()
        (mime.from.single() as InternetAddress).let {
            it.address shouldBe "dp@example.com"
            it.personal shouldBe "Data Pipelines"
        }
        (mime.replyTo.single() as InternetAddress).address shouldBe "help@example.com"
        mime.allRecipients.map { (it as InternetAddress).address } shouldContainExactly listOf("ada@company.com", "ops@company.com")
        mime.subject shouldBe "Your datapipelines account"
        mime.getHeader(MailNotifier.STREAM_HEADER).toList() shouldContainExactly listOf("outbound")
        part(mime.content as Multipart, "text/plain") shouldContain "plain body"
        part(mime.content as Multipart, "text/html") shouldContain "html body"
        outcome.shouldBeInstanceOf<SendOutcome.Sent>().messageId.shouldNotBeNull() shouldBe mime.messageID
    }

    /** The helper nests mixed → related → alternative; walk to the part of [mime] type. */
    private fun part(
        multipart: Multipart,
        mime: String,
    ): String {
        for (i in 0 until multipart.count) {
            val body = multipart.getBodyPart(i)
            if (body.isMimeType(mime)) return body.content.toString()
            (body.content as? Multipart)?.let { nested -> part(nested, mime).takeIf { it.isNotEmpty() }?.let { return it } }
        }
        return ""
    }

    @Test
    fun `a transport failure is returned as Failed, never thrown`() {
        val outcome = SpringMailSender(properties, CapturingJavaMailSender(fail = true)).send(message)

        val failed = outcome.shouldBeInstanceOf<SendOutcome.Failed>()
        failed.error.shouldBeInstanceOf<MailSendException>()
        failed.error.message.shouldNotBeNull() shouldContain "Connection refused"
    }

    @Test
    fun `the session is the properties - auth iff a username, starttls enabled AND required, bounded timeouts`() {
        val session = SpringMailSender.javaMailSender(properties)

        session.host shouldBe "smtp.example.com"
        session.port shouldBe 2525
        session.username shouldBe "token"
        session.password shouldBe "secret"
        session.javaMailProperties.getProperty("mail.smtp.auth") shouldBe "true"
        session.javaMailProperties.getProperty("mail.smtp.starttls.enable") shouldBe "true"
        session.javaMailProperties.getProperty("mail.smtp.starttls.required") shouldBe "true"
        session.javaMailProperties.getProperty("mail.smtp.connectiontimeout") shouldBe "10000"
        session.javaMailProperties.getProperty("mail.smtp.timeout") shouldBe "30000"
        session.javaMailProperties.getProperty("mail.smtp.writetimeout") shouldBe "30000"

        val relay = SpringMailSender.javaMailSender(properties.copy(username = "", password = "", starttls = false))
        relay.username shouldBe null
        relay.javaMailProperties.getProperty("mail.smtp.auth") shouldBe "false"
        relay.javaMailProperties.getProperty("mail.smtp.starttls.required") shouldBe "false"
    }
}
