package co.datapipelines.datasources

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/**
 * Redaction and truncation of error text (datasources.md §6.1: a `TestResult.error` or a
 * `ValidationError.message` never carries a password or the credential portion of a JDBC URL).
 *
 * Drivers and HikariCP routinely put the whole connection string — credentials included — into
 * their exception messages, and those messages flow straight into an API response, so the scrub
 * is the last line before the credential leaves the process.
 */
class ErrorTextTest {
    @Test
    fun `the literal password value is removed wherever the driver echoed it`() {
        val scrubbed = scrubSecrets("FATAL: password authentication failed for 'hunter2'", password = "hunter2")

        scrubbed shouldNotContain "hunter2"
        scrubbed shouldContain "***"
    }

    @Test
    fun `a credential-bearing JDBC URL loses its credential segment but keeps the host`() {
        val message = "Failed to connect to jdbc:postgresql://db.internal:5432/app?user=admin&password=hunter2&sslmode=require"

        val scrubbed = scrubSecrets(message, password = "hunter2")

        scrubbed shouldNotContain "hunter2"
        scrubbed shouldNotContain "admin"
        // The operator still learns which host and which TLS mode failed.
        scrubbed shouldContain "db.internal:5432"
        scrubbed shouldContain "sslmode=require"
    }

    @Test
    fun `a userinfo authority is redacted even when the password is not known here`() {
        val scrubbed = scrubSecrets("connect failed: jdbc:mysql://admin:hunter2@db.internal:3306/app")

        scrubbed shouldNotContain "hunter2"
        scrubbed shouldContain "db.internal:3306"
    }

    @Test
    fun `the MSSQL userName alias and the multi-factor password slots are redacted`() {
        val scrubbed = scrubSecrets("jdbc:sqlserver://h:1433;userName=sa;password2=hunter2;databaseName=app")

        scrubbed shouldNotContain "hunter2"
        scrubbed shouldNotContain "sa;"
        scrubbed shouldContain "databaseName=app"
    }

    @Test
    fun `reflected inbound values stay bounded at 64 chars`() {
        val long = "k".repeat(500)

        long.truncateForError().length shouldBe MAX_REFLECTED_CHARS + 1 // + the ellipsis marker
        "short".truncateForError() shouldBe "short"
    }

    @Test
    fun `server-produced messages are bounded at 512 chars`() {
        // A 64-char cap would cut the one sentence that says what went wrong; 512 keeps it.
        val long = "x".repeat(1000)
        long.scrubbedForError().length shouldBe MAX_SERVER_MESSAGE_CHARS + 1
    }

    @Test
    fun `scrubbing happens before truncation - the credential is redacted AND the tail is dropped`() {
        // NEW-2: the previous version of this case put the credential where BOTH orderings produced
        // a clean result, so it could not have failed if the order were reversed. Here the
        // credential sits INSIDE the first 512 chars: scrub-then-truncate redacts it, whereas
        // truncate-then-scrub would keep "hunter2" in the surviving prefix. Asserting the tail is
        // dropped as well proves the truncation still ran, so the test cannot pass by skipping it.
        val credentialAtStart = "password=hunter2 "
        val filler = "boom ".repeat(140) // 700 chars — pushes the tail past the 512 cap
        val tailMarker = "TAIL_MARKER_PAST_512"

        val scrubbed = (credentialAtStart + filler + tailMarker).scrubbedForError()

        scrubbed shouldNotContain "hunter2"
        scrubbed shouldContain "password=***"
        scrubbed shouldNotContain tailMarker
        scrubbed.length shouldBe MAX_SERVER_MESSAGE_CHARS + 1
    }

    @Test
    fun `DS-SEC-17 - compound credential keys are scrubbed, not only standalone ones`() {
        // The pre-fix regex anchored the key alternation with `\b`, so a key with a PREFIX
        // (`sslpassword`, `trustStorePassword`) had a word character before the alternation and
        // never matched — the credential was reflected back in the error message intact.
        listOf(
            "sslpassword=hunter2",
            "trustStorePassword=hunter2",
            "clientKeyPassword=hunter2",
            "keyStoreSecret=hunter2",
            "keyVaultProviderClientKey=hunter2",
            "trustCertificateKeyStorePassword=hunter2",
        ).forEach { assignment ->
            withClue("'$assignment' must be scrubbed") {
                val scrubbed = scrubSecrets("connect failed: jdbc:postgresql://db.internal:5432/app?$assignment")
                scrubbed shouldNotContain "hunter2"
                scrubbed shouldContain "***"
            }
        }
    }

    @Test
    fun `DS-SEC-17 - the widened key pattern still leaves non-credential text alone`() {
        // Over-redaction is the failure mode this fix could introduce: a scrub that ate the
        // diagnostic would defeat the purpose of returning a message at all (§6.1).
        val scrubbed =
            scrubSecrets("Failed to connect to jdbc:postgresql://db.internal:5432/app?sslmode=require&sslpassword=hunter2")

        scrubbed shouldNotContain "hunter2"
        scrubbed shouldContain "db.internal:5432"
        scrubbed shouldContain "sslmode=require"
        // `passwordCharacterEncoding` contains "password" but is not a secret — and it is not an
        // assignment this pattern should swallow whole.
        scrubSecrets("passwordCharacterEncoding=UTF-8") shouldContain "UTF-8"
    }
}
