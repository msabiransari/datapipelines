package co.datapipelines.web.ui

import io.kotest.assertions.withClue
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
        SchedulesRender.skeleton(html, "sch-tpl-run-row") shouldContain "data-slot=\"execution\""
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
