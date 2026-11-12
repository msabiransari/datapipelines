package co.datapipelines.browser

import co.datapipelines.browser.ScheduleFixtures.createSchedule
import co.datapipelines.browser.ScheduleFixtures.releasedPipeline
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.net.URI

/**
 * #876 G6 §D.1 — the schedule detail's Dependencies section: closed by default and silent until opened (zero tree
 * requests on the page's load), one request on the first open whose query is the schedule's id ALONE (no version, no
 * name, no revision — the browser witness of the selector table's schedule row), the schedule's own "Revision N" and
 * condition, and its one edge "Runs pipeline" to the exact pipeline version with that version's lifecycle text.
 * Selecting a second schedule (no page load: detail.js clones the skeleton and scans it) gives a closed section that
 * makes its own single request for its own id when opened.
 */
class DependenciesScheduleBrowserTest : DependencyPaneBrowserSuite() {
    private val scope = "#schedule-detail [data-sch-deps]"

    @Test
    fun `the section loads on first open with the id alone, shows what the schedule runs, and a second schedule loads its own`() {
        startTrace()
        val root = ready("depsched")
        val pipeline = "$root/jobs/rows"
        releasedPipeline(page, pipeline)
        val first = createSchedule(page, "$root/nightly/rows", pipeline)
        val second = createSchedule(page, "$root/nightly/other", pipeline)
        countTreeRequests()
        page.navigate("$baseUrl/schedules?id=$first")
        page.locator("#schedule-detail [data-schedule-detail]").waitFor()
        page.waitForTimeout(SETTLE_MS)

        // Closed and silent: the pane is bound (idle) and tells the reader how it loads; nothing was requested.
        page.locator(scope).getAttribute("open") shouldBe null
        paneState(scope) shouldBe "idle"
        page.locator("$scope [data-dp-deps-note]").textContent() shouldBe "Dependencies load when this section is opened."
        treeRequests shouldHaveSize 0

        page.locator("$scope > summary").click()
        awaitSettled(scope) shouldBe "ready"
        treeRequests shouldHaveSize 1
        withClue("the schedule request: ${treeRequests[0]}") {
            val url = URI(treeRequests[0])
            url.path shouldBe "${TREE_ROUTE}schedule"
            url.rawQuery shouldBe "id=$first"
        }
        showList(scope)
        val rows = listRows(scope)
        withClue("the rows: $rows") {
            rows shouldHaveSize 2
            rows.map { it.split('|').take(3).let { c -> listOf(c[0].removeSuffix(ROOT_MARK)) + c.drop(1) } } shouldContainExactly
                listOf(listOf("$root/nightly/rows", "Revision 1", "Enabled"), listOf(pipeline, "v1", "Released"))
        }
        edgeRows(scope) shouldContainExactly listOf("$root/nightly/rows Revision 1|Runs pipeline|$pipeline v1")

        // Closing and re-opening the same section is the same pane: no second request.
        page.locator("$scope > summary").click()
        page.locator("$scope > summary").click()
        page.waitForTimeout(SETTLE_MS)
        treeRequests shouldHaveSize 1

        // The second schedule, selected in the tree: a fresh closed section, its own single request when opened.
        page.locator("#schedule-tree-pane [data-leaf-name='$root/nightly/other']").click()
        page.waitForFunction(
            "id => { const d = document.querySelector('#schedule-detail [data-schedule-detail]');" +
                " return !!d && d.getAttribute('data-schedule-id') === id; }",
            second,
        )
        page.locator(scope).getAttribute("open") shouldBe null
        paneState(scope) shouldBe "idle"
        page.waitForTimeout(SETTLE_MS)
        treeRequests shouldHaveSize 1
        page.locator("$scope > summary").click()
        awaitSettled(scope) shouldBe "ready"
        treeRequests shouldHaveSize 2
        URI(treeRequests[1]).rawQuery shouldBe "id=$second"
        drainCspViolations() shouldBe emptyList()
    }

    private companion object {
        const val SETTLE_MS = 400.0

        /** The root row's name cell also carries the list's root mark. */
        const val ROOT_MARK = "This object"
    }
}
