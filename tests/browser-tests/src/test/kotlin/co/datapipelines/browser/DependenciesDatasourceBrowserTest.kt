package co.datapipelines.browser

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.net.URI

/**
 * #876 G6 §D.2 — the datasource detail's Dependencies section, after Learned facts: closed by default and silent
 * until opened, one request on the first open whose query is the datasource's name ALONE (the browser witness of the
 * selector table's datasource row), and the honest leaf — the datasource itself, "Unversioned", "Registered
 * datasource" with its stored access, no relationship, and exactly "No dependencies".
 */
class DependenciesDatasourceBrowserTest : DependencyPaneBrowserSuite() {
    private val scope = "[data-ds-deps]"

    @Test
    fun `the section loads on first open with the name alone and shows the leaf with No dependencies`() {
        startTrace()
        ready("depsrc")
        val name = registerDatasource()
        val access = if (scalar("SELECT is_readonly::text FROM datasources WHERE name = '$name'") == "true") "read-only" else "read-write"
        countTreeRequests()
        page.navigate("$baseUrl/datasources/$name")
        page.locator(scope).waitFor()
        page.waitForTimeout(SETTLE_MS)

        page.locator(scope).getAttribute("open") shouldBe null
        paneState(scope) shouldBe "idle"
        page.locator("$scope [data-dp-deps-note]").textContent() shouldBe "Dependencies load when this section is opened."
        treeRequests shouldHaveSize 0

        page.locator("$scope > summary").click()
        awaitSettled(scope) shouldBe "empty"
        treeRequests shouldHaveSize 1
        withClue("the datasource request: ${treeRequests[0]}") {
            val url = URI(treeRequests[0])
            url.path shouldBe "${TREE_ROUTE}datasource"
            url.rawQuery shouldBe "name=$name"
        }
        notes(scope) shouldContainExactly listOf("No dependencies")
        showList(scope)
        listRows(scope) shouldContainExactly listOf("${name}This object|Unversioned|Registered datasource · $access|Root")
        edgeRows(scope) shouldBe emptyList()

        page.locator("$scope > summary").click()
        page.locator("$scope > summary").click()
        page.waitForTimeout(SETTLE_MS)
        treeRequests shouldHaveSize 1
        drainCspViolations() shouldBe emptyList()
    }

    private companion object {
        const val SETTLE_MS = 400.0
    }
}
