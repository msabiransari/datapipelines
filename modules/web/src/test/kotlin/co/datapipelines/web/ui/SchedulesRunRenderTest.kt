package co.datapipelines.web.ui

import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/**
 * #9 slice 2, A.4 — a run's dialog (`schedules/run.html`) by role: every member reads it (the run,
 * its frozen job, the Messages pane); the execution link and the unknown callout's "Open the
 * execution" are `canReadExecutions`' (a promoter reads the scheduler's trail only), and the
 * callout's Unblock is `canAuthor`'s — a reader's callout names who can unblock instead.
 */
class SchedulesRunRenderTest {
    @Test
    fun `an author's run dialog carries the execution links and the unblock verb`() {
        val dialog = SchedulesRender.skeleton(SchedulesRender.page(SchedulesRender.AUTHOR), "sch-tpl-run-dialog")
        dialog shouldContain "data-slot=\"messages\""
        dialog shouldContain "data-slot=\"execution\""
        dialog shouldContain "data-slot=\"unknown-execution\""
        dialog shouldContain "data-verb=\"schedule-unblock\""
    }

    @Test
    fun `a viewer's run dialog links the execution and names who can unblock`() {
        val dialog = SchedulesRender.skeleton(SchedulesRender.page(SchedulesRender.VIEWER), "sch-tpl-run-dialog")
        dialog shouldContain "data-slot=\"execution\""
        dialog shouldNotContain "data-verb=\"schedule-unblock\""
        dialog shouldContain "An author or a workspace admin can unblock it."
    }

    @Test
    fun `a promoter's run dialog has the trail but no execution link`() {
        val html = SchedulesRender.page(SchedulesRender.PROMOTER)
        val dialog = SchedulesRender.skeleton(html, "sch-tpl-run-dialog")
        dialog shouldContain "data-slot=\"messages\""
        dialog shouldNotContain "data-slot=\"execution\""
        dialog shouldNotContain "data-slot=\"unknown-execution\""
        html shouldContain "id=\"sch-tpl-message\""
    }
}
