package co.datapipelines.browser

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * #374 - the Parameter Sets pages in a real browser (ui-screens.md section 4.23): the resolve
 * matrix, the promoter's lens, the flat catalog's pager, the sidebar branch and the workspace
 * switch, the page's CSP posture and the light/dark handback screens. The live form (the
 * parameter-set evaluate rules) is [ParameterSetFormBrowserTest]; the shared fixtures, including
 * the loopback promotion target that makes the lens ADMIT released sets, are in
 * [ParameterSetBrowserSuite].
 */
class ParameterSetPagesBrowserTest : ParameterSetBrowserSuite() {
    private fun label(): String = page.locator("[data-ps-viewed-label]").innerText()

    @Test
    fun `the resolve matrix - current, an explicit draft, an unknown number never clamped, a draft-only set`() {
        startTrace()
        val root = ready("pspm")
        val (id, v1Hash) = createSet(setBody("$root/parameters/matrix", "[${constants("kind", listOf("a", "b"))}]"))
        val released = release(id, v1Hash)
        newDraft(id, released, setBody("$root/parameters/matrix", "[${constants("kind", listOf("a", "b", "c"))}]"))
        val (draftOnly, _) = createSet(setBody("$root/parameters/only_draft", "[${constants("kind", listOf("a"))}]"))

        // Unqualified: the lens-visible CURRENT - the release, not the newer draft.
        page.navigate("$baseUrl/parameter-sets/$id")
        page.waitForSelector("[data-ps-viewed-label]")
        label() shouldBe "v1 · released · current"
        // An explicit draft number: that exact draft, LABELLED as one.
        page.navigate("$baseUrl/parameter-sets/$id?version=2")
        page.waitForSelector("[data-ps-viewed-label]")
        label() shouldBe "v2 · draft"
        // An unknown number is the house 404 - never clamped to the head or the current.
        page.navigate("$baseUrl/parameter-sets/$id?version=99").status() shouldBe 404
        // A set with no release: the accessible draft, labelled.
        page.navigate("$baseUrl/parameter-sets/$draftOnly")
        page.waitForSelector("[data-ps-viewed-label]")
        label() shouldBe "v1 · draft"
    }

    @Test
    fun `the promoter's lens - released only, no control, zero evaluate requests, a draft is the house 404`() {
        startTrace()
        val root = ready("pspro")
        val workspaceName = activeWorkspace()
        val (id, v1Hash) = createSet(setBody("$root/parameters/matrix", "[${constants("kind", listOf("a", "b"))}]"))
        val released = release(id, v1Hash)
        newDraft(id, released, setBody("$root/parameters/matrix", "[${constants("kind", listOf("a", "b", "c"))}]"))
        val (draftOnly, _) = createSet(setBody("$root/parameters/only_draft", "[${constants("kind", listOf("a"))}]"))

        val promoter = openPromoterSession(workspaceName)
        val requests = mutableListOf<String>()
        promoter.page.onRequest { request -> requests += request.method() + " " + request.url() }

        promoter.page.navigate("$baseUrl/parameter-sets/$id")
        promoter.page.waitForSelector("[data-ps-viewed-label]")
        promoter.page.locator("[data-ps-viewed-label]").innerText() shouldBe "v1 · released · current"
        promoter.page.locator("[data-ps-read-only]").count() shouldBe 1
        promoter.page.locator("#ps-form-host").count() shouldBe 0
        // The graph still draws - the structure is readable; only the verb is withheld.
        promoter.page.waitForSelector("#ps-graph canvas")
        val shown = promoter.page.locator(".ps-versions").innerText()
        shown shouldContain "v1"
        assertTrue(!shown.contains("v2"), "the promoter's version list offered the draft: $shown")
        // The draft number and the draft-only set are the SAME 404 an absent one is.
        promoter.page.navigate("$baseUrl/parameter-sets/$id?version=2").status() shouldBe 404
        promoter.page.navigate("$baseUrl/parameter-sets/$draftOnly").status() shouldBe 404
        // The wire: no evaluate request, ever, from the promoter's page.
        requests.filter { it.contains("/evaluate") }.shouldBeEmpty()
        // The fixture's own non-vacuity: the production lens really probed the stub target, and
        // the admin of the same workspace reads what the promoter was refused.
        assertTrue(probeHits.get() >= 1, "the lens never probed the target - the fixture is vacuous")
        page.navigate("$baseUrl/parameter-sets/$draftOnly")
        page.waitForSelector("[data-ps-viewed-label]")
        promoter.close()
    }

    @Test
    fun `the flat catalog pages 25 at a time, and a boosted Next swaps inside the page`() {
        startTrace()
        val root = ready("pspage")
        (1..26).forEach { n ->
            createSet(setBody("$root/parameters/s${"%02d".format(n)}", "[${constants("kind", listOf("a"))}]"))
        }
        page.navigate("$baseUrl/parameter-sets")
        page.waitForSelector("#parameter-set-list-wrapper .tpl-result")
        page.locator("#parameter-set-list-wrapper .tpl-result").count() shouldBe 25
        page.locator("#parameter-set-list-wrapper").innerText() shouldContain "Showing 25 of 26"
        page.locator("#parameter-set-list-wrapper button:has-text('Next')").click()
        page.waitForFunction("() => document.querySelectorAll('#parameter-set-list-wrapper .tpl-result').length === 1")
        page.locator("#parameter-set-list-wrapper .tpl-path").first().innerText() shouldBe "$root/parameters/s26"
        page.locator("#parameter-set-list-wrapper button:has-text('Previous')").click()
        page.waitForFunction("() => document.querySelectorAll('#parameter-set-list-wrapper .tpl-result').length === 25")
    }

    @Test
    fun `the sidebar branch expands lazily to a leaf that opens the workspace, and a workspace switch shows the other workspace's sets`() {
        startTrace()
        val root = ready("pssb")
        val (id, _) = createSet(setBody("$root/parameters/nav_set", "[${constants("kind", listOf("a"))}]"))
        page.navigate("$baseUrl/dashboard")
        page.waitForSelector("[data-nav-branch='parameter-sets']")
        // The rail item carries the lucide sliders-horizontal glyph, vendored (never a custom SVG).
        page
            .locator("[data-nav-branch='parameter-sets'] a.app-nav-link use")
            .getAttribute("href") shouldContain "#sliders-horizontal"
        page.click("[data-nav-branch='parameter-sets'] [data-nav-tree-toggle]")
        page.waitForSelector("#nav-tree-parameter-sets .tpl-summary")
        page.click("#nav-tree-parameter-sets .tpl-summary:has(span[title='$root'])")
        page.click("#nav-tree-parameter-sets .tpl-summary:has(span[title='$root/parameters'])")
        page.click("#nav-tree-parameter-sets a.tpl-leaf:has(span[title='$root/parameters/nav_set'])")
        page.waitForURL("**/parameter-sets/$id")
        page.waitForSelector("[data-ps-viewed-label]")

        // A second workspace: the branch is the NEW workspace's - never the first one's cached level.
        createWorkspace("psother" + suffix())
        page.navigate("$baseUrl/dashboard")
        page.click("[data-nav-branch='parameter-sets'] [data-nav-tree-toggle]")
        page.waitForSelector("#nav-tree-parameter-sets .ds-empty")
        page.locator("#nav-tree-parameter-sets .tpl-summary").count() shouldBe 0
    }

    @Test
    fun `zero CSP refusals across the catalog and a workspace - and light and dark screens of both`() {
        startTrace()
        val root = ready("psshot")
        val (id, hash) = createSet(setBody("$root/parameters/shot", "[${constants("kind", listOf("a", "b"))}]"))
        release(id, hash)
        drainCspViolations()

        page.navigate("$baseUrl/parameter-sets")
        page.waitForSelector("#parameter-set-list-wrapper .tpl-result")
        ensureTheme("light")
        shot("parameter-sets-catalog-light")
        ensureTheme("dark")
        shot("parameter-sets-catalog-dark")
        ensureTheme("light")
        page.navigate("$baseUrl/parameter-sets/$id")
        page.waitForSelector("#ps-graph canvas")
        page.waitForSelector("#ps-form-host .dp-dashboard-parameter")
        shot("parameter-sets-workspace-light")
        ensureTheme("dark")
        shot("parameter-sets-workspace-dark")

        // The empty-element hash is Playwright's own screenshot path; the computed-style checks
        // cannot be fooled by it (the dashboards' pages state the same set-aside).
        drainCspViolations().filter { !it.contains("'sha256-47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU='") }.shouldBeEmpty()
    }
}
