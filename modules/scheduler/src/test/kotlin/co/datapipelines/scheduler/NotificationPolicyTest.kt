package co.datapipelines.scheduler

import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test

/**
 * #442b A.1 — the owner's safety ruling (2026-10-03) as ONE decision ([NotificationPolicy]): the
 * computed delivery state and its three off reasons, the allowlist grammar, the save-time refusal
 * of a supplied recipient outside a non-empty allowlist, and the sender's configured bounds.
 */
class NotificationPolicyTest {
    private val mapper = ObjectMapper()
    private val mail = NotificationDeployment(mailConfigured = true, baseUrl = "https://dp.example.com", developmentPosture = false)

    private fun policy(
        enabled: Boolean = true,
        domains: List<String> = emptyList(),
        deployment: NotificationDeployment = mail,
    ) = NotificationPolicy(SchedulerProperties.Notifications(enabled = enabled, recipientDomains = domains), deployment)

    @Test
    fun `delivery is off unless enabled, needs the transport, and an empty allowlist sends nothing under development`() {
        policy(enabled = false).state.toWire() shouldBe mapOf("state" to "off", "reason" to "disabled")
        policy(deployment = mail.copy(mailConfigured = false)).state.toWire() shouldBe
            mapOf("state" to "off", "reason" to "mail_unconfigured")
        policy(deployment = mail.copy(developmentPosture = true)).state.toWire() shouldBe
            mapOf("state" to "off", "reason" to "no_allowlist")
        // A non-empty allowlist turns development on; hardened with an empty one is unrestricted (choice 1).
        policy(domains = listOf("example.com"), deployment = mail.copy(developmentPosture = true)).state.toWire() shouldBe
            mapOf("state" to "on")
        policy().state.toWire() shouldBe mapOf("state" to "on")
        // The flag outranks every other reason, and no deployment at all is off.
        policy(enabled = false, deployment = NotificationDeployment.UNKNOWN).state.off shouldBe DeliveryOffReason.DISABLED
        policy(deployment = NotificationDeployment.UNKNOWN).state.on shouldBe false
    }

    @Test
    fun `the allowlist admits exact domains and subdomain wildcards, case-insensitively`() {
        val p = policy(domains = listOf("Example.com", "*.corp.example.org"))
        p.admits("a@example.com") shouldBe true
        p.admits("a@EXAMPLE.COM") shouldBe true
        p.admits("a@sub.example.com") shouldBe false
        p.admits("a@team.corp.example.org") shouldBe true
        p.admits("a@corp.example.org") shouldBe false
        p.admits("a@evilcorp.example.org") shouldBe false
        p.admits("a@example.com.evil.net") shouldBe false
        p.admits("no-at-sign") shouldBe false
        policy().admits("anyone@anywhere.test") shouldBe true
        policy().refusesAtSave("anyone@anywhere.test") shouldBe false
    }

    @Test
    fun `a supplied recipient outside a non-empty allowlist is refused at save by index, without the address`() {
        val p = policy(domains = listOf("example.com"))
        val request = NotificationSettingsRequest(recipients = mapper.readTree("""["ok@example.com","x@example.org"]"""))
        val refused = shouldThrow<ScheduleException> { NotificationSettings.resolve(request, null, 20, p) }

        refused.code shouldBe ScheduleErrorCodes.NOTIFICATIONS_INVALID
        refused.details shouldBe mapOf("field" to "notifications.recipients[1]", "reason" to "domain_not_allowed")
        refused.message!!.contains("example.org") shouldBe false
        NotificationSettings.resolve(request.copy(recipients = mapper.readTree("""["ok@example.com"]""")), null, 20, p).recipients shouldBe
            listOf("ok@example.com")
        // Flag off: saving is still allowed — the settings are stored, the form says mail is off.
        NotificationSettings.resolve(request, null, 20, policy(enabled = false)).recipients.size shouldBe 2
    }

    @Test
    fun `stored recipients an edit leaves alone are not re-judged at save`() {
        val stored = mockk<Schedule> { every { notificationRecipients } returns listOf("old@example.org") }
        val edit = NotificationSettingsRequest(events = mapper.readTree("""["failure"]"""))

        val resolved = NotificationSettings.resolve(edit, stored, 20, policy(domains = listOf("example.com")))

        resolved.recipients shouldBe listOf("old@example.org")
    }

    @Test
    fun `the binding refuses a malformed allowlist entry and out-of-range sender bounds, naming the key`() {
        listOf("*.", "a@b.com", "*example.com", "ex ample.com", "-x.com").forEach { entry ->
            shouldThrow<IllegalArgumentException> { SchedulerProperties.Notifications(recipientDomains = listOf(entry)) }
                .message!! shouldContain "recipient-domains"
        }
        listOf(0, 11).forEach { shouldThrow<IllegalArgumentException> { SchedulerProperties.Notifications(maxAttempts = it) } }
        listOf(0, 201).forEach { shouldThrow<IllegalArgumentException> { SchedulerProperties.Notifications(batchSize = it) } }
        shouldThrow<IllegalArgumentException> { SchedulerProperties.Notifications(backlogMax = 0) }
        shouldThrow<IllegalArgumentException> { SchedulerProperties.Notifications(retryBackoffSeconds = emptyList()) }
        shouldThrow<IllegalArgumentException> { SchedulerProperties.Notifications(retryBackoffSeconds = listOf(0)) }
        shouldThrow<IllegalArgumentException> { SchedulerProperties.Notifications(retryBackoffSeconds = listOf(86_401)) }
    }

    @Test
    fun `the defaults are the documented ones and the backoff repeats its last entry`() {
        val n = SchedulerProperties.Notifications()
        listOf(n.enabled, n.recipientDomains, n.maxAttempts, n.retryBackoffSeconds, n.batchSize, n.backlogMax) shouldBe
            listOf(false, emptyList<String>(), 3, listOf(30L, 120L, 600L), 20, 1000)
        listOf(1, 2, 3, 4, 9).map { n.backoffAfter(it).seconds } shouldBe listOf(30L, 120L, 600L, 600L, 600L)
    }

    @Test
    fun `the Message-ID host is the base url's host, never a request's`() {
        mail.messageIdHost shouldBe "dp.example.com"
        mail.copy(baseUrl = "http://localhost:8080").messageIdHost shouldBe "localhost"
        NotificationDeployment.UNKNOWN.messageIdHost shouldBe "datapipelines.invalid"
    }
}
