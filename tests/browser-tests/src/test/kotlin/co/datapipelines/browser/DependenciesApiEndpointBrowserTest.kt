package co.datapipelines.browser

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.net.URI

/**
 * #876 G6 §E.1 (Dispatch decisions item 1) — the API details page's Dependencies card, bound to the SELECTED version
 * (record §13), never the current pointer. v1 is released pinning pipeline v1; v2 is a draft pinning pipeline v2.
 * Each version's page shows its own tree: the API node at that version with its lifecycle and the entity's
 * enabled/disabled (the provider's `operational.enabled`, read against the page's own API card), and its one
 * "Pipeline" edge to the exact pipeline version it pins. One tree request per first open, carrying the stored path and
 * that version; nothing before the open. Changing version is a page load (`?version=n`), so the pane never changes
 * version in place and the A26 abort case does not arise here.
 */
class DependenciesApiEndpointBrowserTest : DependencyPaneBrowserSuite() {
    private val scope = "[data-api-deps]"

    @Test
    fun `each version's page shows that version's tree, one request per open, nothing before it`() {
        startTrace()
        val root = ready("depapi")
        val fx = ApiVersionsFixtures(page)
        val pipeline = "$root/probe"
        val (pipelineId, p1Hash) = fx.seedReleasedPipeline(pipeline)
        fx.releaseNewPipelineVersion(pipelineId, pipeline, p1Hash)
        val endpointId = fx.createEndpoint(PATH, pipeline, pipelineVersion = 1)
        page.navigate("$baseUrl/api-console/endpoints/$endpointId")
        fx.openDialog("release", "endpoint-release")
        fx.submitAndWaitForPage("api_released")
        fx.openDialog("new-draft", "endpoint-draft")
        page.selectOption("[data-api-pipeline-version]", "2")
        fx.submitAndWaitForPage("api_draft_saved")

        val seen = mutableListOf<String>()
        countTreeRequests()
        for ((version, lifecycle, pinned) in listOf(Triple(2, "Draft", 2), Triple(1, "Released", 1))) {
            // One listener for the whole walk (a second countTreeRequests would record every request twice).
            treeRequests.clear()
            page.navigate("$baseUrl/api-console/endpoints/$endpointId?version=$version")
            page.locator(scope).waitFor()
            page.waitForTimeout(SETTLE_MS)
            withClue("version $version before the open") {
                page.locator(scope).getAttribute("open") shouldBe null
                paneState(scope) shouldBe "idle"
                treeRequests shouldHaveSize 0
            }
            val enabled = page.locator("[data-api-entity] [data-api-enabled]").innerText().trim()
            page.locator("$scope > summary").click()
            awaitSettled(scope) shouldBe "ready"
            withClue("version $version: ${treeRequests.toList()}") {
                treeRequests shouldHaveSize 1
                val url = URI(treeRequests[0])
                url.path shouldBe "${TREE_ROUTE}api_endpoint"
                url.rawQuery shouldBe "name=${PATH.replace("/", "%2F")}&version=$version"
            }
            showList(scope)
            val rows = listRows(scope).map { it.split('|').take(3).let { c -> listOf(c[0].removeSuffix(ROOT_MARK)) + c.drop(1) } }
            withClue("version $version rows: $rows") {
                rows shouldContainExactly
                    listOf(listOf(PATH, "v$version", "$lifecycle · $enabled"), listOf(pipeline, "v$pinned", "Released"))
            }
            edgeRows(scope) shouldContainExactly listOf("$PATH v$version|Pipeline|$pipeline v$pinned")
            page.locator("$scope > summary").click()
            page.locator("$scope > summary").click()
            page.waitForTimeout(SETTLE_MS)
            treeRequests shouldHaveSize 1
            seen += treeRequests[0]
        }
        seen.distinct() shouldHaveSize 2
        drainCspViolations() shouldBe emptyList()
    }

    private companion object {
        const val PATH = "/deps876/v1/probe"
        const val SETTLE_MS = 400.0

        /** The root row's name cell also carries the list's root mark. */
        const val ROOT_MARK = "This object"
    }
}
