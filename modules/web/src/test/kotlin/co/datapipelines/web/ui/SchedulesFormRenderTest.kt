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
 * number — scheduler.md §2.1), and nothing of the slices that are not this one (no recipients —
 * slice 4; no key — R2).
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
    fun `nothing from later slices - no recipients, no key`() {
        val form = SchedulesRender.skeleton(SchedulesRender.page(), "sch-tpl-form").lowercase()
        listOf("recipient", "e-mail list", "api key", "name=\"role\"").forEach { needle ->
            withClue(needle) { form shouldNotContain needle }
        }
    }
}
