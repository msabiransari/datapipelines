package co.datapipelines.web.ui

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/**
 * #9 slice 2, A.2 — the selected schedule's skeleton (`schedules/detail.html`) by role: its verbs
 * (Edit, Pause, Resume, Unblock, Run now, Delete and the delete dialog's confirm) are `canAuthor`'s —
 * the five `schedule.*` write rows — and the run row's execution link is `canReadExecutions`'
 * (a viewer reads scheduled runs' executions, R3; a promoter does not). A reader's detail is
 * complete without them: the blocked callout names who can unblock instead.
 */
class SchedulesDetailRenderTest {
    private val verbs =
        listOf(
            "schedule-edit",
            "schedule-pause",
            "schedule-resume",
            "schedule-unblock",
            "schedule-run",
            "schedule-delete",
            "schedule-delete-confirm",
        )

    @Test
    fun `an author's detail carries every verb`() {
        val html = SchedulesRender.page(SchedulesRender.AUTHOR)
        verbs.forEach { verb -> withClue(verb) { html shouldContain "data-verb=\"$verb\"" } }
        SchedulesRender.skeleton(html, "sch-tpl-detail") shouldContain "Unblock checks the pipeline and the saved parameters again"
    }

    @Test
    fun `a viewer's detail has no verb, names who can unblock, and links the run's execution`() {
        val html = SchedulesRender.page(SchedulesRender.VIEWER)
        verbs.forEach { verb -> withClue(verb) { html shouldNotContain "data-verb=\"$verb\"" } }
        val detail = SchedulesRender.skeleton(html, "sch-tpl-detail")
        detail shouldContain "An author or a workspace admin can unblock it."
        detail shouldContain "data-slot=\"blocked\""
        detail shouldContain "<dt>Notifications</dt>"
        detail shouldContain "data-slot=\"notifications\""
        SchedulesRender.skeleton(html, "sch-tpl-run-row") shouldContain "data-slot=\"execution\""
    }

    /**
     * #876 G6 U2 — every role that opens the page gets the Dependencies section in the detail skeleton, closed, the
     * pane a schedule pane (detail.js fills `data-id` and `data-revision`; the skeleton carries neither, nor any name
     * or version), and the page lists the eight Dependencies catalog entries in its loader catalog.
     */
    @Test
    fun `every role's detail carries the closed Dependencies section and the page lists its catalog`() {
        for ((role, roles) in listOf("author" to SchedulesRender.AUTHOR, "viewer" to SchedulesRender.VIEWER, "promoter" to SchedulesRender.PROMOTER)) {
            val html = SchedulesRender.page(roles)
            val detail = SchedulesRender.skeleton(html, "sch-tpl-detail")
            withClue(role) {
                val section = detail.substringAfter("<details class=\"dp-deps-section\" data-sch-deps>").substringBefore("</details>")
                section shouldContain "<h3>"
                section shouldContain "Dependencies</h3>"
                section shouldContain "<div class=\"dp-deps\" data-dp-deps data-family=\"schedule\" data-dp-deps-state=\"idle\">"
                Regex("<details[^>]*data-sch-deps[^>]*\\bopen\\b").containsMatchIn(detail) shouldBe false
                section shouldNotContain "data-version"
                section shouldNotContain "data-name"
                val catalog = html.substringAfter("<template data-page-assets>").substringBefore("</template>")
                Regex("src=\"(/[^\"]+)\"").findAll(catalog).map { it.groupValues[1] }.toList() shouldBe DEPENDENCY_CATALOG
                catalog shouldContain "<script data-page-mount src=\"/js/dependencies/mount.js\">"
            }
        }
    }

    @Test
    fun `a promoter's detail has no verb and no execution link`() {
        val html = SchedulesRender.page(SchedulesRender.PROMOTER)
        verbs.forEach { verb -> withClue(verb) { html shouldNotContain "data-verb=\"$verb\"" } }
        val row = SchedulesRender.skeleton(html, "sch-tpl-run-row")
        row shouldContain "data-sch-action=\"open-run\""
        row shouldNotContain "data-slot=\"execution\""
    }
}
