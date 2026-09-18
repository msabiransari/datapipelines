package co.datapipelines.auth

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import co.datapipelines.auth.SharedPostgres.dataSource
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import jakarta.mail.MessagingException
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import java.util.concurrent.Executor

/**
 * [MailNotifier] end to end against the real claim table (V27) with a REAL recording sender
 * and a real in-memory audit sink — never a strict mock: the contract is "you must send /
 * claim / audit", and a strict double passes precisely when the call is missing.
 *
 * The executor is DIRECT (runs the task on the calling thread) so every assertion is
 * deterministic; the production pool is wired in [MailConfiguration] and exercised by the
 * E2E. The after-commit half is proven with a real [TransactionTemplate] over the SAME
 * DataSource the repository writes through (so the claim rides the transaction, as it does in
 * the application): inside the transaction the claim exists and nothing has been sent; after
 * commit exactly one message has; after a rollback neither a message nor a claim row.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MailNotifierIntegrationTest {
    private lateinit var ds: javax.sql.DataSource
    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var users: UserRepository
    private lateinit var sends: MailSendRepository
    private lateinit var sender: RecordingMailSender
    private lateinit var audit: RecordingSink
    private lateinit var user: User
    private val direct = Executor { it.run() }

    @BeforeAll
    fun connect() {
        ds = dataSource()
        jdbc = NamedParameterJdbcTemplate(ds)
    }

    @BeforeEach
    fun setUp() {
        users = UserRepository(jdbc)
        sends = MailSendRepository(jdbc)
        sender = RecordingMailSender()
        audit = RecordingSink()
        jdbc.jdbcTemplate.execute("TRUNCATE users CASCADE")
        user = users.insert("ada@company.com", "Ada Lovelace", null, "local", "ada@company.com", isAdmin = false)
    }

    private fun notifier(
        properties: MailProperties = ON,
        mailSender: MailSender = sender,
    ) = MailNotifier(properties, AUTH, MailTemplates(), mailSender, sends, audit, direct)

    @Test
    fun `the welcome mail goes to the user with the password, the claim row records the send and the audit row carries no body`() {
        notifier().welcome(user, ONE_TIME)

        val message = sender.sent.single()
        message.kind shouldBe MailKind.WELCOME
        message.to shouldContainExactly listOf("ada@company.com")
        message.subject shouldBe "Your datapipelines account"
        message.text shouldContain ONE_TIME
        message.text shouldContain "https://dp.example.com/login"
        message.html shouldContain ONE_TIME
        message.headers shouldNotContainKey "X-PM-Message-Stream"

        val row = sends.find(user.id, MailKind.WELCOME, user.id).shouldNotBeNull()
        row.status shouldBe MailSend.Status.SENT
        row.messageId shouldBe "<recorded-1@dp>"

        val event = audit.rows.single()
        event.event shouldBe MailAuditEvents.SENT
        event.userId shouldBe user.id
        event.details["kind"] shouldBe "welcome"
        event.details["to"] shouldBe "ada@company.com"
        event.details["message_id"] shouldBe "<recorded-1@dp>"
        event.details.toString() shouldNotContain ONE_TIME
    }

    @Test
    fun `a second welcome for the same user finds the claim and never sends twice`() {
        notifier().welcome(user, ONE_TIME)
        notifier().welcome(user, "ANOTHER-ONE-TIME")

        sender.sent shouldHaveSize 1
        audit.rows shouldHaveSize 1
    }

    @Test
    fun `two resets are two acts and both mails go`() {
        notifier().passwordReset(user, ONE_TIME, resetId = UUID.randomUUID())
        notifier().passwordReset(user, "SECOND-ONE-TIME", resetId = UUID.randomUUID())

        sender.sent shouldHaveSize 2
        sender.sent.map { it.subject }.toSet() shouldBe setOf("Your datapipelines password was reset")
    }

    @Test
    fun `a failed send marks the claim failed, audits mail_failed with the error class and never throws`() {
        val failing = RecordingMailSender(fail = IllegalStateException("Connection refused: smtp:587"))

        notifier(mailSender = failing).welcome(user, ONE_TIME)

        val row = sends.find(user.id, MailKind.WELCOME, user.id).shouldNotBeNull()
        row.status shouldBe MailSend.Status.FAILED
        row.error.shouldNotBeNull() shouldContain "IllegalStateException: Connection refused"
        val event = audit.rows.single()
        event.event shouldBe MailAuditEvents.FAILED
        event.details["error"].toString() shouldContain "IllegalStateException"
        event.details.toString() shouldNotContain ONE_TIME
    }

    // ------------------ 158 (#121): the bounded connect-failure retry

    @Test
    fun `a connect failure is retried in place - one claim, the row ends sent, the attempts bounded`() {
        // #121's fix, falsifiable: a connect failure (the one class that cannot have delivered)
        // retries in place; the claim row is marked from the FINAL outcome only. On the pre-fix
        // code the first failure ended the row FAILED — this test is red there.
        val flaky =
            RecordingMailSender(
                failures =
                    mutableListOf(
                        MessagingException("Could not connect to SMTP host", java.net.ConnectException("Connection refused")),
                        MessagingException("Could not connect to SMTP host", java.net.SocketTimeoutException("connect timed out")),
                    ),
            )

        val lines = capturingLogs(MailNotifier::class.java) { notifier(mailSender = flaky).welcome(user, ONE_TIME) }

        flaky.attempts shouldBe 3
        val row = sends.find(user.id, MailKind.WELCOME, user.id).shouldNotBeNull()
        row.status shouldBe MailSend.Status.SENT
        row.messageId shouldBe "<recorded-1@dp>"
        audit.rows.single().event shouldBe MailAuditEvents.SENT
        lines.count { it.contains("event=mail.send_retry") } shouldBe 2
    }

    @Test
    fun `a connect failure that never recovers ends failed after the bounded attempts - one claim, one audit row`() {
        val down = RecordingMailSender(fail = MessagingException("Could not connect", java.net.ConnectException("Connection refused")))

        notifier(mailSender = down).welcome(user, ONE_TIME)

        down.attempts shouldBe 3
        val row = sends.find(user.id, MailKind.WELCOME, user.id).shouldNotBeNull()
        row.status shouldBe MailSend.Status.FAILED
        // The recorded error is the final attempt's wrapper text (class + message), as documented.
        row.error.shouldNotBeNull() shouldContain "MessagingException: Could not connect"
        audit.rows.single().event shouldBe MailAuditEvents.FAILED
    }

    @Test
    fun `a read timeout after connect is NOT retried - a password mail never risks a second copy`() {
        // The ambiguous class: the server may have accepted the message before the read died.
        // Recorded failed at once, one attempt — the admin's answer stays a reset (auth.md §5A.8).
        val ambiguous =
            RecordingMailSender(
                fail = MessagingException("Exception reading response", java.net.SocketTimeoutException("Read timed out")),
            )

        notifier(mailSender = ambiguous).welcome(user, ONE_TIME)

        ambiguous.attempts shouldBe 1
        sends.find(user.id, MailKind.WELCOME, user.id).shouldNotBeNull().status shouldBe MailSend.Status.FAILED
    }

    @Test
    fun `the new-user notice goes to every ops-to address, names the creator and the workspace, and carries no password`() {
        notifier(ON.copy(opsTo = "ops@company.com, sec@company.com")).newUser(user, createdBy = "admin@company.com", workspace = "acme")

        val message = sender.sent.single()
        message.kind shouldBe MailKind.NEW_USER
        message.to shouldContainExactly listOf("ops@company.com", "sec@company.com")
        message.subject shouldBe "New user: ada@company.com (local)"
        message.text shouldContain "admin@company.com"
        message.text shouldContain "acme"
        message.text shouldContain "https://dp.example.com/admin/users"
        message.text.lowercase() shouldNotContain "password"
        sends.find(user.id, MailKind.NEW_USER, user.id).shouldNotBeNull().recipient shouldBe "ops@company.com, sec@company.com"
    }

    @Test
    fun `no ops-to means no new-user notice, no claim and no audit`() {
        notifier(ON.copy(opsTo = null)).newUser(user, createdBy = "admin@company.com", workspace = null)

        sender.sent.shouldBeEmpty()
        sends.find(user.id, MailKind.NEW_USER, user.id).shouldBeNull()
        audit.rows.shouldBeEmpty()
    }

    @Test
    fun `the message-stream header rides every message when configured`() {
        notifier(ON.copy(messageStream = "outbound")).welcome(user, ONE_TIME)

        sender.sent.single().headers shouldContainKey "X-PM-Message-Stream"
        sender.sent.single().headers["X-PM-Message-Stream"] shouldBe "outbound"
    }

    @Test
    fun `mail off - the noop sender logs one INFO line with the kind and the domain only, no claim, no audit`() {
        val lines = capturingLogs(NoopMailSender::class.java) { notifier(OFF, NoopMailSender()).welcome(user, ONE_TIME) }

        sender.sent.shouldBeEmpty()
        sends.find(user.id, MailKind.WELCOME, user.id).shouldBeNull()
        audit.rows.shouldBeEmpty()
        lines shouldHaveSize 1
        lines.single() shouldContain "event=mail.skipped"
        lines.single() shouldContain "kind=welcome"
        lines.single() shouldContain "domain=company.com"
        lines.single() shouldNotContain "ada@"
        lines.single() shouldNotContain ONE_TIME
    }

    @Test
    fun `inside a transaction the send waits for the commit, and a rollback sends nothing`() {
        val tx = TransactionTemplate(DataSourceTransactionManager(ds))

        tx.executeWithoutResult {
            notifier().welcome(user, ONE_TIME)
            sender.sent.shouldBeEmpty()
            sends.find(user.id, MailKind.WELCOME, user.id).shouldNotBeNull().status shouldBe MailSend.Status.PENDING
        }
        sender.sent shouldHaveSize 1
        sends.find(user.id, MailKind.WELCOME, user.id).shouldNotBeNull().status shouldBe MailSend.Status.SENT

        val other = users.insert("bob@company.com", "Bob", null, "local", "bob@company.com", isAdmin = false)
        tx.executeWithoutResult { status ->
            notifier().welcome(other, ONE_TIME)
            status.setRollbackOnly()
        }
        sender.sent shouldHaveSize 1
        sends.find(other.id, MailKind.WELCOME, other.id).shouldBeNull()
    }

    @Test
    fun `the one-time password reaches the transport and nothing else - audit, log, claim row`() {
        val lines = capturingLogs(MailNotifier::class.java) { notifier().welcome(user, ONE_TIME) }

        // Positive control: the password DID reach the transport, so the negatives below mean something.
        sender.sent.single().text shouldContain ONE_TIME
        audit.rows
            .single()
            .details
            .toString() shouldNotContain ONE_TIME
        lines.forEach { it shouldNotContain ONE_TIME }
        val claimColumns =
            jdbc.jdbcTemplate.queryForList("SELECT row_to_json(m)::text FROM mail_sends m", String::class.java)
        claimColumns shouldHaveSize 1
        claimColumns.single() shouldNotContain ONE_TIME
    }

    private fun capturingLogs(
        of: Class<*>,
        block: () -> Unit,
    ): List<String> {
        val logger = LoggerFactory.getLogger(of) as ch.qos.logback.classic.Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        return try {
            block()
            appender.list.map { it.formattedMessage }
        } finally {
            logger.detachAppender(appender)
        }
    }

    /** A REAL in-memory sender: records what it was handed, answers a fixed Message-ID, or fails on request. */
    private class RecordingMailSender(
        private val fail: Exception? = null,
        private val failures: MutableList<Exception> = mutableListOf(),
    ) : MailSender {
        val sent = mutableListOf<MailMessage>()
        var attempts = 0

        override fun send(message: MailMessage): SendOutcome {
            attempts++
            // The scripted transient failures are consumed first; [fail] fails every call.
            if (failures.isNotEmpty()) return SendOutcome.Failed(failures.removeAt(0))
            fail?.let { return SendOutcome.Failed(it) }
            sent += message
            return SendOutcome.Sent(messageId = "<recorded-${sent.size}@dp>")
        }
    }

    private class RecordingSink : AuditEventSink {
        data class Row(
            val event: String,
            val userId: UUID?,
            val details: Map<String, Any?>,
        )

        val rows = mutableListOf<Row>()

        override fun log(
            event: String,
            userId: UUID?,
            keyId: String?,
            sourceIp: String?,
            userAgent: String?,
            details: Map<String, Any?>,
        ) {
            rows += Row(event, userId, details)
        }
    }

    private companion object {
        const val ONE_TIME = "ZQ7K-4HN2-PX9M"
        val ON = MailProperties(host = "smtp.example.com", from = "Data Pipelines <dp@example.com>", opsTo = "ops@company.com")
        val OFF = MailProperties()
        val AUTH = AuthProperties(baseUrl = "https://dp.example.com")
    }
}
