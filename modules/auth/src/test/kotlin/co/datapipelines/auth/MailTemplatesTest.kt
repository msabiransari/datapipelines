package co.datapipelines.auth

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * The two notice families under `templates/mail/` (auth.md §5A.8), rendered by the real
 * engine in both parts: the welcome / reset mail carries the login URL, the login (the email),
 * the one-time password, the first-login sentence and the human to write to; the new-user
 * notice carries who, how, by whom, where and when — and NEVER a password.
 */
class MailTemplatesTest {
    private val templates = MailTemplates()

    private val welcome =
        templates.welcome(
            kind = MailKind.WELCOME,
            email = "ada@company.com",
            loginUrl = "https://dp.example.com/login",
            oneTimePassword = "ABCD-EFGH-JKLM",
            replyTo = "help@example.com",
        )

    @Test
    fun `the welcome mail carries the URL, the login, the password, the first-login sentence and the reply-to in both parts`() {
        welcome.subject shouldBe "Your datapipelines account"
        listOf(welcome.text, welcome.html).forEach { part ->
            part shouldContain "https://dp.example.com/login"
            part shouldContain "ada@company.com"
            part shouldContain "ABCD-EFGH-JKLM"
            part shouldContain "asks you to choose a new password"
            part shouldContain "help@example.com"
        }
        welcome.text shouldNotContain "<"
        welcome.html shouldStartWith "<!DOCTYPE html>"
    }

    @Test
    fun `the reset mail is the same family with its own subject and sentence`() {
        val reset =
            templates.welcome(
                kind = MailKind.PASSWORD_RESET,
                email = "ada@company.com",
                loginUrl = "https://dp.example.com/login",
                oneTimePassword = "ABCD-EFGH-JKLM",
                replyTo = "help@example.com",
            )

        reset.subject shouldBe "Your datapipelines password was reset"
        reset.text shouldContain "An administrator reset your password"
        reset.text shouldContain "ABCD-EFGH-JKLM"
        reset.html shouldContain "ABCD-EFGH-JKLM"
    }

    @Test
    fun `the new-user notice names who, how, by whom, where and when, links the admin screen and carries no password`() {
        val notice =
            templates.newUser(
                NewUserNotice(
                    email = "ada@company.com",
                    displayName = "Ada Lovelace",
                    provider = "local",
                    createdBy = "admin@company.com",
                    workspace = "acme",
                    at = Instant.parse("2026-09-14T10:15:30Z"),
                    adminUsersUrl = "https://dp.example.com/admin/users",
                ),
            )

        notice.subject shouldBe "New user: ada@company.com (local)"
        listOf(notice.text, notice.html).forEach { part ->
            part shouldContain "ada@company.com"
            part shouldContain "Ada Lovelace"
            part shouldContain "local"
            part shouldContain "admin@company.com"
            part shouldContain "acme"
            part shouldContain "2026-09-14T10:15:30Z"
            part shouldContain "https://dp.example.com/admin/users"
            part.lowercase() shouldNotContain "password"
        }
    }

    @Test
    fun `a social first login names the provider as the creator and no workspace as such`() {
        val notice =
            templates.newUser(
                NewUserNotice(
                    email = "bob@company.com",
                    displayName = null,
                    provider = "google",
                    createdBy = "self-service via google",
                    workspace = null,
                    at = Instant.parse("2026-09-14T10:15:30Z"),
                    adminUsersUrl = "https://dp.example.com/admin/users",
                ),
            )

        notice.subject shouldBe "New user: bob@company.com (google)"
        notice.text shouldContain "self-service via google"
        notice.text shouldContain "Workspace:  none"
        notice.text shouldNotContain "Name:"
    }

    @Test
    fun `the html part escapes what the text part carries verbatim`() {
        val notice =
            templates.newUser(
                NewUserNotice(
                    email = "eve@company.com",
                    displayName = "<script>alert(1)</script>",
                    provider = "local",
                    createdBy = "admin@company.com",
                    workspace = null,
                    at = Instant.EPOCH,
                    adminUsersUrl = "https://dp.example.com/admin/users",
                ),
            )

        notice.html shouldNotContain "<script>"
        notice.html shouldContain "&lt;script&gt;"
        notice.text shouldContain "<script>alert(1)</script>"
    }
}
