package co.datapipelines.config

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/**
 * The §3.27 mail rules as data (137) — [MailRules], driven through [ConfigValidator.validate]
 * exactly as the boot does. One §7 area, one file (the posture / org / endpoints precedent).
 *
 * Every refusal names the field the operator has to touch; the baseline with mail OFF stays
 * green so an unconfigured deployment (every deployment before 137) is untouched.
 */
class ConfigValidatorMailTest {
    private fun mailOn() =
        ConfigSnapshots.valid().copy(
            mail =
                MailSnapshot(
                    authBaseUrl = "https://dp.example.com",
                    host = "smtp.postmarkapp.com",
                    port = "587",
                    username = "token",
                    passwordSet = true,
                    starttls = "true",
                    from = "Data Pipelines <dp@example.com>",
                    opsTo = "ops@example.com",
                ),
        )

    /** One mail field changed on the ON baseline. */
    private fun ConfigSnapshot.withMail(edit: MailSnapshot.() -> MailSnapshot) = copy(mail = mail.edit())

    @Test
    fun `mail off is the baseline and passes`() {
        ConfigValidator.validate(ConfigSnapshots.valid()).violations.shouldBeEmpty()
    }

    @Test
    fun `a fully configured mail block passes in both postures`() {
        ConfigValidator.validate(mailOn()).violations.shouldBeEmpty()
        ConfigValidator
            .validate(mailOn().copy(env = "prod", posture = "hardened", activeProfiles = setOf("hardened")))
            .violations
            .shouldBeEmpty()
    }

    @Test
    fun `a host without a from is refused, naming from`() {
        val report = ConfigValidator.validate(mailOn().withMail { copy(from = null) })

        report.violations.shouldHaveSize(1)
        report.violations.single().shouldContain("datapipelines.mail.from")
        report.violations.single().shouldContain("datapipelines.mail.host")
    }

    @Test
    fun `a from without a host is refused, naming host`() {
        // ops-to dropped too: with it set, the ops-to-without-host rule fires as well, correctly.
        val report = ConfigValidator.validate(mailOn().withMail { copy(host = null, opsTo = null) })

        report.violations.shouldHaveSize(1)
        report.violations.single().shouldContain("datapipelines.mail.host")
        report.violations.single().shouldContain("datapipelines.mail.from")
    }

    @Test
    fun `ops-to without a host is refused - a sink nobody can reach is a misconfiguration`() {
        val report = ConfigValidator.validate(ConfigSnapshots.valid().copy(mail = MailSnapshot(opsTo = "ops@example.com")))

        report.violations.shouldHaveSize(1)
        report.violations.single().shouldContain("datapipelines.mail.ops-to")
        report.violations.single().shouldContain("datapipelines.mail.host")
    }

    @Test
    fun `mail on without the auth base-url is refused - the welcome mail carries the login URL`() {
        val report = ConfigValidator.validate(mailOn().withMail { copy(authBaseUrl = null) })

        report.violations.shouldHaveSize(1)
        report.violations.single().shouldContain("datapipelines.auth.base-url")
        report.violations.single().shouldContain("datapipelines.mail.host")
    }

    @Test
    fun `a port that is not a positive integer is refused by name`() {
        listOf("smtp", "0", "70000").forEach { port ->
            val report = ConfigValidator.validate(mailOn().withMail { copy(port = port) })
            report.violations.single().shouldContain("datapipelines.mail.port")
        }
    }

    @Test
    fun `a from without an at-sign is refused by name`() {
        ConfigValidator
            .validate(mailOn().withMail { copy(from = "not-an-address") })
            .violations
            .single()
            .shouldContain("datapipelines.mail.from")
    }

    @Test
    fun `hardened refuses starttls false while development allows it`() {
        val plain = mailOn().withMail { copy(starttls = "false") }

        ConfigValidator.validate(plain).violations.shouldBeEmpty()
        val hardened = ConfigValidator.validate(plain.copy(env = "prod", posture = "hardened", activeProfiles = setOf("hardened")))
        hardened.violations.shouldHaveSize(1)
        hardened.violations.single().shouldContain("datapipelines.mail.starttls")
        hardened.violations.single().shouldContain("hardened")
    }

    @Test
    fun `hardened refuses a username without a password while development allows it`() {
        val noPassword = mailOn().withMail { copy(passwordSet = false) }

        ConfigValidator.validate(noPassword).violations.shouldBeEmpty()
        val hardened =
            ConfigValidator.validate(noPassword.copy(env = "prod", posture = "hardened", activeProfiles = setOf("hardened")))
        hardened.violations.shouldHaveSize(1)
        hardened.violations.single().shouldContain("datapipelines.mail.password")
        hardened.violations.single().shouldContain("datapipelines.mail.username")
    }

    @Test
    fun `the snapshot carries the password as presence only`() {
        mailOn().toString().shouldContain("passwordSet=true")
        mailOn().toString().shouldNotContain("password=")
    }
}
