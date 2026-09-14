package co.datapipelines.auth

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/**
 * `datapipelines.mail` (configuration.md §3.27, auth.md §5A.8): enabled is DERIVED from
 * `host` + `from` — never a flag that can disagree with them — and the password never
 * reaches a `toString`, which is what a logged bean or a debugger's "print the config"
 * would otherwise carry.
 */
class MailPropertiesTest {
    @Test
    fun `mail is enabled exactly when host and from are both set`() {
        MailProperties().enabled.shouldBeFalse()
        MailProperties(host = "smtp.example.com").enabled.shouldBeFalse()
        MailProperties(from = "dp@example.com").enabled.shouldBeFalse()
        MailProperties(host = "  ", from = "dp@example.com").enabled.shouldBeFalse()
        MailProperties(host = "smtp.example.com", from = "dp@example.com").enabled.shouldBeTrue()
    }

    @Test
    fun `reply-to defaults to from and ops-to splits a comma list`() {
        val props = MailProperties(host = "h", from = "dp@example.com", opsTo = " ops@example.com, sec@example.com ,,")
        props.effectiveReplyTo() shouldBe "dp@example.com"
        props.opsRecipients() shouldContainExactly listOf("ops@example.com", "sec@example.com")
        MailProperties(from = "dp@example.com", replyTo = "help@example.com").effectiveReplyTo() shouldBe "help@example.com"
        MailProperties().opsRecipients() shouldContainExactly emptyList()
    }

    @Test
    fun `the from address may carry a display name and the bare address is derivable`() {
        MailProperties(from = "Data Pipelines <dp@example.com>").fromAddress() shouldBe "dp@example.com"
        MailProperties(from = "dp@example.com").fromAddress() shouldBe "dp@example.com"
    }

    @Test
    fun `toString never prints the password`() {
        val rendered =
            MailProperties(host = "smtp.example.com", username = "postmark-token", password = "hunter2-secret", from = "dp@example.com")
                .toString()
        rendered shouldContain "smtp.example.com"
        rendered shouldContain "postmark-token"
        rendered shouldNotContain "hunter2-secret"
        rendered shouldContain "password=<redacted>"
    }
}
