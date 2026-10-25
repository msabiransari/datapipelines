package co.datapipelines.web.ui

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/**
 * #9 slice 2, A.3 — the schedule form's skeleton (`schedules/form.html`): the author's only (the
 * whole template sits in the `canAuthor` guard — a reader's page has no form to open), the zones
 * the scheduler accepts grouped by region, the version fixed to `current` (slice 1 refuses a
 * number — scheduler.md §2.1), saved notification choices (#442a) with the mail-off note's reason (#442b), and no key (R2).
 */
class SchedulesFormRenderTest {
    @Test
    fun `the form is the author's - a reader's page has none`() {
        val author = SchedulesRender.page(SchedulesRender.AUTHOR)
        author shouldContain "id=\"sch-tpl-form\""
        author shouldContain "id=\"sch-tpl-param\""
        author shouldContain "data-verb=\"schedule-save\""
        listOf(SchedulesRender.VIEWER, SchedulesRender.PROMOTER).forEach { role ->
            val html = SchedulesRender.page(role)
            html shouldNotContain "id=\"sch-tpl-form\""
            html shouldNotContain "data-verb=\"schedule-save\""
        }
    }

    @Test
    fun `the form offers the server's zones by region and fixes the version to current`() {
        val form = SchedulesRender.skeleton(SchedulesRender.page(), "sch-tpl-form")
        form shouldContain "<optgroup label=\"America\">"
        form shouldContain "<option value=\"America/New_York\">America/New_York</option>"
        form shouldContain "<option value=\"UTC\">UTC</option>"
        (form.split("<option value=\"").size - 1 >= ScheduleTimezones.GROUPS.sumOf { it.zones.size }) shouldBe true
        // Slice 1 accepts `current` only: no numeric version control exists.
        form shouldNotContain "name=\"version\""
        // The five presets and the custom pattern, each writing the ONE cron field.
        listOf("hourly", "daily", "weekly", "monthly", "custom").forEach { p -> withClue(p) { form shouldContain "value=\"$p\"" } }
        form shouldContain "id=\"sch-f-cron\""
    }

    @Test
    fun `notifications have five choices defaults and an explicit mail off note`() {
        val form = SchedulesRender.skeleton(SchedulesRender.page(), "sch-tpl-form").lowercase()
        form shouldContain "id=\"sch-f-recipients\""
        form shouldContain "data-field-error=\"notifications\""
        form shouldContain "data-mail-off=\"disabled\""
        form shouldContain "scheduler mail is not enabled on this deployment"
        listOf("start", "success", "failure", "unknown", "blocked").forEach { event ->
            form shouldContain "name=\"notification_event\" value=\"$event\""
        }
        listOf("api key", "name=\"role\"").forEach { needle ->
            withClue(needle) { form shouldNotContain needle }
        }
    }

    @Test
    fun `the mail off note names its reason in plain words and is absent when delivery is on`() {
        fun form(delivery: Map<String, String>) = SchedulesRender.skeleton(SchedulesRender.page(delivery = delivery), "sch-tpl-form")
        val noAllowlist = form(mapOf("state" to "off", "reason" to "no_allowlist"))
        noAllowlist shouldContain "data-mail-off=\"no_allowlist\""
        noAllowlist shouldContain "Scheduler mail is off: no recipient domains are allowed on this development deployment"
        noAllowlist shouldNotContain "not enabled on this deployment"
        val unconfigured = form(mapOf("state" to "off", "reason" to "mail_unconfigured"))
        unconfigured shouldContain "Scheduler mail is off: this deployment has no mail server configured"
        val disabled = form(mapOf("state" to "off", "reason" to "disabled"))
        disabled shouldContain "Scheduler mail is not enabled on this deployment"
        val on = form(mapOf("state" to "on"))
        on shouldNotContain "data-mail-off"
        // Never described as delivery, on or off.
        listOf(noAllowlist, unconfigured, disabled, on).forEach { html -> html.lowercase() shouldNotContain "delivered" }
    }
}
