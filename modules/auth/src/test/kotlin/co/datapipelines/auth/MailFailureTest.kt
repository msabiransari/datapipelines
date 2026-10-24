package co.datapipelines.auth

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import kotlin.concurrent.thread

/**
 * [MailFailure.of] over the REAL exception shapes [SpringMailSender] returns — never hand-built ones:
 * the real Jakarta session and Spring's `JavaMailSenderImpl` talk to a scripted SMTP stub on a local
 * socket (or to a closed port), so the classification is pinned to what the transport actually raises.
 * The question each case answers is the one a retrying caller needs (#442b): could the relay have
 * accepted the message?
 */
class MailFailureTest {
    private val stubs = mutableListOf<ScriptedSmtp>()

    @AfterEach
    fun stop() {
        stubs.forEach(ScriptedSmtp::close)
    }

    /** Starts a scripted relay, closed after the case. */
    private fun relay(stub: ScriptedSmtp): ScriptedSmtp = stub.also(stubs::add)

    @Test
    fun `nothing listening is CONNECT - nothing was sent`() {
        val port = ServerSocket(0).use { it.localPort } // bound, then released: nothing listens there now
        MailFailure.of(failureAgainst(port)) shouldBe MailFailure.CONNECT
    }

    @Test
    fun `a relay that closes before its greeting is CONNECT - the session never opened`() {
        val stub = relay(ScriptedSmtp(greeting = null))
        MailFailure.of(failureAgainst(stub.port)) shouldBe MailFailure.CONNECT
    }

    @Test
    fun `a 4xx at RCPT is TRANSIENT and a 5xx is REFUSED`() {
        MailFailure.of(failureAgainst(relay(ScriptedSmtp(rcpt = "450 4.2.0 mailbox busy")).port)) shouldBe MailFailure.TRANSIENT
        MailFailure.of(failureAgainst(relay(ScriptedSmtp(rcpt = "550 5.1.1 no such user")).port)) shouldBe MailFailure.REFUSED
    }

    @Test
    fun `a 4xx after the data is TRANSIENT and a 5xx is REFUSED - the relay said it did not take it`() {
        MailFailure.of(failureAgainst(relay(ScriptedSmtp(afterData = "451 4.3.0 try again later")).port)) shouldBe MailFailure.TRANSIENT
        MailFailure.of(failureAgainst(relay(ScriptedSmtp(afterData = "554 5.7.1 rejected")).port)) shouldBe MailFailure.REFUSED
    }

    @Test
    fun `a connection dropped after the body was sent is AMBIGUOUS - it may have been accepted`() {
        val stub = relay(ScriptedSmtp(afterData = null))
        MailFailure.of(failureAgainst(stub.port)) shouldBe MailFailure.AMBIGUOUS
        stub.sawBody.get() shouldBe true // non-vacuity: the body did go out before the drop
    }

    @Test
    fun `a relay refusing the credentials is AUTH`() {
        val stub = relay(ScriptedSmtp(auth = "535 5.7.8 authentication failed"))
        MailFailure.of(failureAgainst(stub.port, username = "token", password = "wrong")) shouldBe MailFailure.AUTH
    }

    @Test
    fun `the stub accepts a well-formed exchange - the scripts above fail only where they say`() {
        val stub = relay(ScriptedSmtp())
        send(stub.port).shouldBeInstanceOf<SendOutcome.Sent>()
    }

    @Test
    fun `strict address parsing admits one bare address and refuses everything a header could abuse`() {
        listOf("ops@example.com", "o'brien@example.com", "a{b}|c@example.org").forEach { MailAddresses.isStrict(it) shouldBe true }
        listOf(
            "ops@example.com, other@example.com",
            "Ops <ops@example.com>",
            "ops@example.com\r\nBcc: x@example.com",
            "group: ops@example.com;",
            "ops",
            "",
        ).forEach { MailAddresses.isStrict(it) shouldBe false }
    }

    private fun failureAgainst(
        port: Int,
        username: String? = null,
        password: String? = null,
    ): Throwable {
        val outcome = send(port, username, password)
        return outcome.shouldBeInstanceOf<SendOutcome.Failed>().error
    }

    private fun send(
        port: Int,
        username: String? = null,
        password: String? = null,
    ): SendOutcome =
        SpringMailSender(
            MailProperties(
                host = "127.0.0.1",
                port = port,
                username = username,
                password = password,
                starttls = false,
                from = "dp@example.com",
            ),
        ).send(
            MailMessage(MailKind.SCHEDULE_NOTIFICATION, listOf("ops@example.com"), "subject", "text", "<p>html</p>"),
        )

    /**
     * A one-connection SMTP server that answers each phase with its script; a null answer closes the
     * socket at that point. AUTH PLAIN is advertised only when [auth] is set.
     */
    private class ScriptedSmtp(
        private val greeting: String? = "220 stub ESMTP",
        private val auth: String? = null,
        private val rcpt: String = "250 2.1.5 ok",
        private val afterData: String? = "250 2.0.0 queued",
    ) : AutoCloseable {
        private val server = ServerSocket(0)
        val port: Int = server.localPort
        val sawBody =
            java.util.concurrent.atomic
                .AtomicBoolean(false)
        private val worker = thread(isDaemon = true, name = "scripted-smtp") { serve() }

        private fun serve() {
            try {
                server.accept().use(::converse)
            } catch (_: SocketException) {
                // closed by the test, or by the client mid-script — either way the client saw what it saw
            }
        }

        private fun converse(socket: Socket) {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.US_ASCII))
            val out = PrintWriter(socket.getOutputStream(), true)
            val reply = { line: String -> out.print("$line\r\n").also { out.flush() } }
            reply(greeting ?: return)
            while (true) {
                val verb = (reader.readLine() ?: return).substringBefore(' ').uppercase()
                val answer = answerTo(verb, reader, reply) ?: return
                answer.forEach(reply)
                if (verb == "QUIT") return
            }
        }

        /** The reply lines to [verb]; null closes the socket at this point of the script. */
        private fun answerTo(
            verb: String,
            reader: BufferedReader,
            reply: (String) -> Unit,
        ): List<String>? =
            when (verb) {
                "EHLO", "HELO" -> {
                    if (auth != null) listOf("250-stub", "250 AUTH PLAIN LOGIN") else listOf("250 stub")
                }

                "AUTH" -> {
                    listOf(auth ?: "503 no auth")
                }

                "MAIL" -> {
                    listOf("250 2.1.0 ok")
                }

                "RCPT" -> {
                    listOf(rcpt)
                }

                "DATA" -> {
                    reply("354 go ahead")
                    var body = reader.readLine()
                    while (body != null && body != ".") body = reader.readLine()
                    sawBody.set(true)
                    afterData?.let(::listOf)
                }

                "QUIT" -> {
                    listOf("221 bye")
                }

                else -> {
                    listOf("250 ok")
                }
            }

        override fun close() {
            server.close()
            worker.join(JOIN_MS)
        }

        private companion object {
            const val JOIN_MS = 2_000L
        }
    }
}
