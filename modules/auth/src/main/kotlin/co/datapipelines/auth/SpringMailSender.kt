package co.datapipelines.auth

import jakarta.mail.internet.InternetAddress
import org.springframework.mail.MailException
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.JavaMailSenderImpl
import org.springframework.mail.javamail.MimeMessageHelper

/**
 * The production [MailSender]: a [JavaMailSender] built once from [MailProperties] (a
 * traditional SMTP submission: host, port, username, password, STARTTLS required when
 * `starttls` is true). Every message is multipart/alternative — the text part and the html
 * part — from `from`, with `Reply-To` set to the effective reply-to, plus the message's own
 * headers (the Postmark stream header when configured).
 *
 * Timeouts are bounded (connect 10 s, read/write 30 s): a hung SMTP server must not pin the
 * mail pool's two threads forever — the queue behind them would then overflow onto request
 * threads (caller-runs) and turn one dead relay into slow user creation everywhere.
 *
 * A [MailException] (and any runtime failure the transport raises) is RETURNED as
 * [SendOutcome.Failed], never thrown: the notifier records it on the claim row and in the
 * audit log, and the request that caused the send has long since returned.
 */
class SpringMailSender(
    private val properties: MailProperties,
    private val mailSender: JavaMailSender = javaMailSender(properties),
) : MailSender {
    override fun send(message: MailMessage): SendOutcome {
        val from = checkNotNull(properties.from) { "SpringMailSender wired without datapipelines.mail.from" }
        val replyTo = checkNotNull(properties.effectiveReplyTo()) { "SpringMailSender wired without a reply-to" }
        return try {
            val mime = mailSender.createMimeMessage()
            MimeMessageHelper(mime, true, Charsets.UTF_8.name()).apply {
                setFrom(InternetAddress(from))
                setReplyTo(InternetAddress(replyTo))
                setTo(message.to.map { InternetAddress(it) }.toTypedArray())
                // The subject carries an email and a provider name (the new-user notice). A line
                // break in either cannot start a second header: MimeMessage folds it on the way
                // out — pinned by SpringMailSenderTest rather than re-implemented here.
                setSubject(message.subject)
                setText(message.text, message.html)
                message.headers.forEach { (name, value) -> mime.setHeader(name, value) }
            }
            mailSender.send(mime)
            SendOutcome.Sent(messageId = mime.messageID)
        } catch (e: MailException) {
            SendOutcome.Failed(e)
        } catch (e: jakarta.mail.MessagingException) {
            SendOutcome.Failed(e)
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = "10000"
        private const val IO_TIMEOUT_MS = "30000"

        /** The Jakarta Mail session from the properties — one transport configuration, spelled once. */
        fun javaMailSender(properties: MailProperties): JavaMailSenderImpl =
            JavaMailSenderImpl().apply {
                host = checkNotNull(properties.host) { "SpringMailSender wired without datapipelines.mail.host" }
                port = properties.port
                username = properties.username?.takeIf { it.isNotBlank() }
                password = properties.password?.takeIf { it.isNotBlank() }
                defaultEncoding = Charsets.UTF_8.name()
                javaMailProperties.apply {
                    setProperty("mail.transport.protocol", "smtp")
                    setProperty("mail.smtp.auth", (!properties.username.isNullOrBlank()).toString())
                    // Required, not opportunistic: `enable` alone would fall back to plaintext on a
                    // server that does not advertise STARTTLS, and the body carries a password.
                    setProperty("mail.smtp.starttls.enable", properties.starttls.toString())
                    setProperty("mail.smtp.starttls.required", properties.starttls.toString())
                    setProperty("mail.smtp.connectiontimeout", CONNECT_TIMEOUT_MS)
                    setProperty("mail.smtp.timeout", IO_TIMEOUT_MS)
                    setProperty("mail.smtp.writetimeout", IO_TIMEOUT_MS)
                }
            }
    }
}
