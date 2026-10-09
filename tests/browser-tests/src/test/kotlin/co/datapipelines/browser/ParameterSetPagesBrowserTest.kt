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
    fun `the catalog's search narrows by name, keeps q across the pager, and a miss offers the clear (#415)`() {
        startTrace()
        val root = ready("pssearch")
        (1..26).forEach { n ->
            createSet(setBody("$root/parameters/s${"%02d".format(n)}", "[${constants("kind", listOf("a"))}]"))
        }
        page.navigate("$baseUrl/parameter-sets")
        page.waitForSelector("#parameter-set-list-wrapper .tpl-result")

        // A one-hit search first - the narrowing is observable - then one term, 26 textual hits:
        // the pager's Next must ride the SAME query, or page two would lie.
        page.fill("#parameter-set-filter-q", "s26")
        page.waitForFunction("() => document.querySelectorAll('#parameter-set-list-wrapper .tpl-result').length === 1")
        page.fill("#parameter-set-filter-q", "/parameters/s")
        page.waitForFunction("() => document.querySelectorAll('#parameter-set-list-wrapper .tpl-result').length === 25")
        page.locator("#parameter-set-list-wrapper").innerText() shouldContain "Showing 25 of 26"
        page.locator("#parameter-set-list-wrapper button:has-text('Next')").click()
        page.waitForFunction("() => document.querySelectorAll('#parameter-set-list-wrapper .tpl-result').length === 1")
        page.locator("#parameter-set-list-wrapper .tpl-path").first().innerText() shouldBe "$root/parameters/s26"

        // A miss whose Clear button brings the catalog's first page back (25 rows — the page size).
        page.fill("#parameter-set-filter-q", "no_such_parameter_set")
        page.waitForSelector("#parameter-set-list-wrapper .ds-empty")
        page.locator("#parameter-set-list-wrapper").innerText() shouldContain "No parameter sets match your search"
        page.locator("#parameter-set-list-wrapper button:has-text('Clear search')").click()
        page.waitForFunction("() => document.querySelectorAll('#parameter-set-list-wrapper .tpl-result').length === 25")
        // The page's own box is the control: clearing by hand agrees with the button.
        page.fill("#parameter-set-filter-q", "")
        page.waitForFunction("() => document.querySelectorAll('#parameter-set-list-wrapper .tpl-result').length === 25")
    }

    @Test
    fun `the branch search expands matching paths and clearing closes the root (#415)`() {
        startTrace()
        val root = ready("psnavsearch")
        val (id, _) = createSet(setBody("$root/parameters/nav_search_me", "[${constants("kind", listOf("a"))}]"))
        page.navigate("$baseUrl/dashboard")
        page.waitForSelector("[data-nav-branch='parameter-sets']")
        page.click("[data-nav-branch='parameter-sets'] [data-nav-tree-toggle]")
        page.waitForSelector("#nav-tree-parameter-sets [aria-expanded] > .dp-tree-line button")

        // A non-empty query swaps the FLAT results into the SAME nav root (role=listbox), the
        // shared engine serving it with no JS change.
        page.fill("[data-nav-branch='parameter-sets'] input[type=search]", "nav_search_me")
        page.waitForSelector("#nav-tree-parameter-sets a.dp-tree-activate")
        page.locator("#nav-tree-parameter-sets a.dp-tree-activate").count() shouldBe 1
        page.locator("#nav-tree-parameter-sets [data-tree-key^='artifact:']").getAttribute("role") shouldBe "treeitem"
        // The listbox is the results list itself; its label names the presentation.
        page.locator("#nav-tree-parameter-sets [role=tree]").getAttribute("aria-label") shouldBe "parameter sets"

        // The search box's ArrowDown moves focus into the first result (the explorers' NAV keyboard).
        page.focus("[data-nav-branch='parameter-sets'] input[type=search]")
        page.keyboard().press("ArrowDown")
        page.evaluate("() => document.activeElement && document.activeElement.getAttribute('role')") shouldBe "treeitem"

        // Clearing the box returns the tree by construction: the dispatcher answers the empty
        // query with the root level again, and the set hides behind its folders as before.
        page.fill("[data-nav-branch='parameter-sets'] input[type=search]", "")
        page.waitForSelector("#nav-tree-parameter-sets [aria-expanded] > .dp-tree-line button")
        page.locator("#nav-tree-parameter-sets a.dp-tree-activate").count() shouldBe 0
        page.click("#nav-tree-parameter-sets [data-tree-key='folder:$root'] > .dp-tree-line button")
        page.waitForSelector("#nav-tree-parameter-sets [data-tree-key='folder:$root/parameters'] > .dp-tree-line button")
        page.click("#nav-tree-parameter-sets [data-tree-key='folder:$root/parameters'] > .dp-tree-line button")
        page.waitForSelector("#nav-tree-parameter-sets a.dp-tree-activate:has(span[title='$root/parameters/nav_search_me'])")
        page.click("#nav-tree-parameter-sets a.dp-tree-activate:has(span[title='$root/parameters/nav_search_me'])")
        page.waitForURL("**/parameter-sets/$id")
    }

    @Test
    fun `a promoter's search answers only her lens - the released set is a hit, the draft-only one is absent everywhere (#415)`() {
        startTrace()
        val root = ready("pspromsearch")
        val workspaceName = activeWorkspace()
        val (id, v1Hash) = createSet(setBody("$root/parameters/released_hit", "[${constants("kind", listOf("a"))}]"))
        release(id, v1Hash)
        val (draftOnly, _) = createSet(setBody("$root/parameters/draft_miss", "[${constants("kind", listOf("a"))}]"))

        val promoter = openPromoterSession(workspaceName)
        promoter.page.navigate("$baseUrl/dashboard")
        promoter.page.waitForSelector("[data-nav-branch='parameter-sets']")
        promoter.page.click("[data-nav-branch='parameter-sets'] [data-nav-tree-toggle]")
        promoter.page.waitForSelector("#nav-tree-parameter-sets [aria-expanded] > .dp-tree-line button")

        // One term that matches BOTH names; her search returns the released one only — the
        // draft-only set is filtered in the service, never hidden by CSS in the panel.
        promoter.page.fill("[data-nav-branch='parameter-sets'] input[type=search]", "_")
        promoter.page.waitForSelector("#nav-tree-parameter-sets a.dp-tree-activate")
        promoter.page.locator("#nav-tree-parameter-sets a.dp-tree-activate").count() shouldBe 1
        val branchPanel = promoter.page.locator("#nav-tree-parameter-sets").innerText()
        branchPanel.contains("draft_miss") shouldBe false

        // The catalog's search agrees with the branch's.
        promoter.page.navigate("$baseUrl/parameter-sets")
        promoter.page.waitForSelector("#parameter-set-list-wrapper .tpl-result")
        promoter.page.fill("#parameter-set-filter-q", "_")
        promoter.page.waitForFunction("() => document.querySelectorAll('#parameter-set-list-wrapper .tpl-result').length === 1")
        val catalogList = promoter.page.locator("#parameter-set-list-wrapper").innerText()
        catalogList shouldContain "Showing 1 of 1"
        catalogList.contains("draft_miss") shouldBe false
        promoter.close()
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
        page.waitForSelector("#nav-tree-parameter-sets [aria-expanded] > .dp-tree-line button")
        page.click("#nav-tree-parameter-sets [data-tree-key='folder:$root'] > .dp-tree-line button")
        page.click("#nav-tree-parameter-sets [data-tree-key='folder:$root/parameters'] > .dp-tree-line button")
        page.click("#nav-tree-parameter-sets a.dp-tree-activate:has(span[title='$root/parameters/nav_set'])")
        page.waitForURL("**/parameter-sets/$id")
        page.waitForSelector("[data-ps-viewed-label]")

        // A second workspace: the branch is the NEW workspace's - never the first one's cached level.
        createWorkspace("psother" + suffix())
        page.navigate("$baseUrl/dashboard")
        page.click("[data-nav-branch='parameter-sets'] [data-nav-tree-toggle]")
        page.waitForSelector("#nav-tree-parameter-sets .dp-tree-status")
        page.locator("#nav-tree-parameter-sets [aria-expanded] > .dp-tree-line button").count() shouldBe 0
    }

    @Test
    fun `a one-node graph is drawn at its natural size - the fit zoom is capped at 1`() {
        startTrace()
        val root = ready("psgz")
        val (id, hash) = createSet(setBody("$root/parameters/solo", "[${constants("kind", listOf("a"))}]"))
        release(id, hash)
        page.navigate("$baseUrl/parameter-sets/$id")
        page.waitForSelector("#ps-graph canvas")
        // The page's own graph module, on a throwaway host: a single node in a roomy container is what fit() magnifies.
        val zoom =
            page.evaluate(
                """() => {
                  const host = document.createElement('div');
                  host.style.width = '600px';
                  host.style.height = '400px';
                  document.body.appendChild(host);
                  const graph = window.PSGraph.create(host, { nodes: [{ id: 'solo', label: 'Solo', badge: 'input' }], edges: [] }, () => {});
                  const zoom = graph.cy.zoom();
                  graph.destroy();
                  host.remove();
                  return zoom;
                }""",
            ) as Number
        assertTrue(zoom.toDouble() <= 1.0, "a one-node graph was magnified to zoom $zoom")
    }

    @Test
    fun `zero CSP refusals across the catalog and a workspace - and light and dark screens of both`() {
        startTrace()
        val root = ready("psshot")
        val count = """{"name":"n","label":"Count","type":"INTEGER","kind":"INPUT","cardinality":"SINGLE","default_value":5}"""
        val kind = constants("kind", listOf("a", "b"))
        val level = constants("level", listOf("low", "high"))
        val shotParameters = "[$kind,$level,$count]"
        val (id, hash) = createSet(setBody("$root/parameters/shot", shotParameters))
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
