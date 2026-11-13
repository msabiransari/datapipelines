package co.datapipelines.config

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * The §3.29 scheduler-notification startup rule (#442b) — [SchedulerNotificationRules], driven
 * through [ConfigValidator.validate] as the boot does: `enabled=true` refuses startup unless the
 * shared transport (host AND from) and `datapipelines.auth.base-url` are configured, naming the
 * keys; `false` (the default) is untouched by either.
 */
class ConfigValidatorSchedulerNotificationsTest {
    private val transport =
        MailSnapshot(authBaseUrl = "https://dp.example.com", host = "smtp.example.com", starttls = "true", from = "dp@example.com")

    private fun snapshot(mail: MailSnapshot) = ConfigSnapshots.valid().copy(mail = mail)

    @Test
    fun `enabled with the transport and base-url passes`() {
        ConfigValidator.validate(snapshot(transport.copy(schedulerNotificationsEnabled = "true"))).violations.shouldBeEmpty()
    }

    @Test
    fun `disabled or unset never needs mail`() {
        listOf(null, "false", "FALSE").forEach { flag ->
            ConfigValidator.validate(snapshot(MailSnapshot(schedulerNotificationsEnabled = flag))).violations.shouldBeEmpty()
        }
    }

    @Test
    fun `enabled with mail off is refused, naming the flag, host and from`() {
        val report =
            ConfigValidator.validate(
                snapshot(MailSnapshot(authBaseUrl = "https://dp.example.com", schedulerNotificationsEnabled = "true")),
            )

        report.violations.shouldHaveSize(1)
        val line = report.violations.single()
        line shouldContain "datapipelines.scheduler.notifications.enabled"
        line shouldContain "datapipelines.mail.host"
        line shouldContain "datapipelines.mail.from"
    }

    @Test
    fun `enabled with a half-configured transport is refused as mail off`() {
        // host without from: MailRules refuses the shape, and the scheduler flag cannot ride it.
        val report =
            ConfigValidator.validate(
                snapshot(transport.copy(from = null, schedulerNotificationsEnabled = "true")),
            )

        report.violations.filter { it.contains("datapipelines.scheduler.notifications.enabled") }.shouldHaveSize(1)
    }

    @Test
    fun `enabled without base-url is refused, naming base-url`() {
        // MailRules also refuses mail-on without base-url (the welcome mail's URL); both name it.
        val report =
            ConfigValidator.validate(
                snapshot(transport.copy(authBaseUrl = null, schedulerNotificationsEnabled = "true")),
            )

        val ours = report.violations.filter { it.contains("datapipelines.scheduler.notifications.enabled") }
        ours.shouldHaveSize(1)
        ours.single() shouldContain "datapipelines.auth.base-url"
    }
}
