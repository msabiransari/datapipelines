package co.datapipelines.auth

import co.datapipelines.auth.SharedPostgres.dataSource
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.util.UUID

/**
 * The `mail_sends` claim row (V27, metadata-db §4.19, auth.md §5A.8) against the real
 * migration: the claim is what makes "the welcome mail carrying a password never goes twice"
 * a database fact rather than a hope — a second attempt for the same (user, kind, act) finds
 * the row and does not send. The row proves an ATTEMPT, not a delivery; it is never cleaned up.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MailSendRepositoryIntegrationTest {
    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var users: UserRepository
    private lateinit var sends: MailSendRepository
    private lateinit var user: User

    @BeforeAll
    fun connect() {
        jdbc = NamedParameterJdbcTemplate(dataSource())
    }

    @BeforeEach
    fun setUp() {
        users = UserRepository(jdbc)
        sends = MailSendRepository(jdbc)
        jdbc.jdbcTemplate.execute("TRUNCATE users CASCADE")
        user = users.insert("ada@company.com", "Ada", null, "local", "ada@company.com", isAdmin = false)
    }

    @Test
    fun `the first claim for a user, kind and act wins and the second finds the row`() {
        val first = sends.tryClaim(user.id, MailKind.WELCOME, actId = user.id, recipient = "ada@company.com")
        val second = sends.tryClaim(user.id, MailKind.WELCOME, actId = user.id, recipient = "ada@company.com")

        first.shouldNotBeNull()
        second.shouldBeNull()
        val row = sends.find(user.id, MailKind.WELCOME, user.id).shouldNotBeNull()
        row.id shouldBe first
        row.recipient shouldBe "ada@company.com"
        row.status shouldBe MailSend.Status.PENDING
    }

    @Test
    fun `a different kind or a different act claims separately`() {
        sends.tryClaim(user.id, MailKind.WELCOME, actId = user.id, recipient = "ada@company.com").shouldNotBeNull()
        // The new-user notice for the same creation is its own message.
        sends.tryClaim(user.id, MailKind.NEW_USER, actId = user.id, recipient = "ops@company.com").shouldNotBeNull()
        // Two password resets are two acts — the second reset's mail must go.
        val firstReset = UUID.randomUUID()
        val secondReset = UUID.randomUUID()
        sends.tryClaim(user.id, MailKind.PASSWORD_RESET, actId = firstReset, recipient = "ada@company.com").shouldNotBeNull()
        sends.tryClaim(user.id, MailKind.PASSWORD_RESET, actId = secondReset, recipient = "ada@company.com").shouldNotBeNull()
        sends.tryClaim(user.id, MailKind.PASSWORD_RESET, actId = secondReset, recipient = "ada@company.com").shouldBeNull()
    }

    @Test
    fun `markSent and markFailed record the outcome the admin screen reads`() {
        val sent = sends.tryClaim(user.id, MailKind.WELCOME, actId = user.id, recipient = "ada@company.com").shouldNotBeNull()
        val failed = sends.tryClaim(user.id, MailKind.NEW_USER, actId = user.id, recipient = "ops@company.com").shouldNotBeNull()

        sends.markSent(sent, messageId = "<abc@dp>")
        sends.markFailed(failed, error = "MailSendException: Connection refused")

        val sentRow = sends.find(user.id, MailKind.WELCOME, user.id).shouldNotBeNull()
        sentRow.status shouldBe MailSend.Status.SENT
        sentRow.messageId shouldBe "<abc@dp>"
        sentRow.sentAt.shouldNotBeNull()
        val failedRow = sends.find(user.id, MailKind.NEW_USER, user.id).shouldNotBeNull()
        failedRow.status shouldBe MailSend.Status.FAILED
        failedRow.error.shouldNotBeNull() shouldContain "Connection refused"
        failedRow.sentAt.shouldBeNull()
    }

    @Test
    fun `latest returns the most recently claimed act of a kind - the reset the admin just made`() {
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        sends.tryClaim(user.id, MailKind.PASSWORD_RESET, actId = first, recipient = "ada@company.com")
        // Two autocommit claims; NOW() differs by at least the round trip between them.
        sends.tryClaim(user.id, MailKind.PASSWORD_RESET, actId = second, recipient = "ada@company.com")

        sends.latest(user.id, MailKind.PASSWORD_RESET).shouldNotBeNull().actId shouldBe second
        sends.latest(user.id, MailKind.WELCOME).shouldBeNull()
    }

    @Test
    fun `the database refuses a kind outside the closed list`() {
        val ex =
            shouldThrow<DataIntegrityViolationException> {
                jdbc.jdbcTemplate.update(
                    "INSERT INTO mail_sends (id, user_id, kind, act_id, recipient) VALUES (?, ?, 'newsletter', ?, 'x@y')",
                    UUID.randomUUID(),
                    user.id,
                    user.id,
                )
            }
        ex.message.shouldNotBeNull() shouldContain "chk_mail_sends_kind"
    }

    @Test
    fun `a row for an unknown user, kind or act is absent, not an error`() {
        sends.find(UUID.randomUUID(), MailKind.WELCOME, UUID.randomUUID()).shouldBeNull()
    }
}
