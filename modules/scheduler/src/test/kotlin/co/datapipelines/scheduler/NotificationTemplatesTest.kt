package co.datapipelines.scheduler

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * #442b — the two notice families as they render: the policy's subject behind the schedule name with
 * every control character removed, the html part escaping every value (`th:text`), the deep link
 * built from the configured base URL, the schedule's wall clock beside UTC, and every outcome
 * rendering in the family it belongs to.
 */
class NotificationTemplatesTest {
    private val templates = NotificationTemplates()
    private val scheduleId = UUID.fromString("00000000-0000-0000-0000-00000000000a")
    private val runId = UUID.fromString("00000000-0000-0000-0000-00000000000b")

    private fun notice(
        outcome: NotificationOutcome = NotificationOutcome.FAILED,
        name: String = "reports/daily",
        target: String = "reports/daily_load",
        reason: String? = "execution_failed",
        timezone: String = "Europe/Berlin",
    ) = NotificationNotice(
        scheduleId = scheduleId,
        scheduleName = name,
        timezone = timezone,
        targetRef = target,
        runId = runId,
        outcome = outcome,
        runState = RunState.FAILED,
        reason = reason,
        occurredAt = Instant.parse("2026-10-24T21:14:05Z"),
        scheduledAt = Instant.parse("2026-10-24T21:00:00Z"),
    )

    @Test
    fun `the subject is the schedule name and the policy's subject, with CR, LF and every control character removed`() {
        templates.render(notice(), BASE).subject shouldBe "reports/daily — Failed"
        templates.render(notice(name = "a\r\nBcc: x@example.com\u0000b"), BASE).subject shouldBe "aBcc: x@example.comb — Failed"
    }

    @Test
    fun `every outcome renders its own headline in its family`() {
        NotificationOutcome.entries.forEach { outcome ->
            val rendered = templates.render(notice(outcome = outcome), BASE)
            rendered.subject shouldBe "reports/daily — ${outcome.subject}"
            rendered.text shouldContain outcome.subject
            rendered.html shouldContain outcome.subject
            val blockedFamily = "Blocked at" in rendered.text
            blockedFamily shouldBe (outcome == NotificationOutcome.BLOCKED)
        }
    }

    @Test
    fun `the html part escapes every value and the text part carries it verbatim`() {
        val rendered = templates.render(notice(target = "<script>alert(1)</script>", reason = "a&b"), BASE)

        rendered.html shouldNotContain "<script>"
        rendered.html shouldContain "&lt;script&gt;alert(1)&lt;/script&gt;"
        rendered.html shouldContain "a&amp;b"
        rendered.text shouldContain "Runs:          <script>alert(1)</script>"
    }

    @Test
    fun `the link is the configured base URL plus the run's deep link, and times read in the schedule's zone beside UTC`() {
        val rendered = templates.render(notice(), "https://dp.example.com/")

        rendered.text shouldContain "Open the run: https://dp.example.com/schedules?id=$scheduleId&run=$runId"
        rendered.html shouldContain "href=\"https://dp.example.com/schedules?id=$scheduleId&amp;run=$runId\""
        rendered.text shouldContain "When:          2026-10-24 23:14:05 Europe/Berlin (21:14:05 UTC)"
        rendered.text shouldContain "Scheduled for: 2026-10-24 23:00:00 Europe/Berlin (21:00:00 UTC)"
        rendered.text shouldContain "Reason:        execution_failed"
        templates.render(notice(timezone = "UTC", reason = null), BASE).text.let { text ->
            text shouldContain "When:          2026-10-24 21:14:05 UTC"
            text shouldNotContain "Reason:"
        }
    }

    private companion object {
        const val BASE = "https://dp.example.com"
    }
}
