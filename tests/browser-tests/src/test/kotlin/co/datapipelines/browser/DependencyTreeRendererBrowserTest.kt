package co.datapipelines.browser

import com.microsoft.playwright.Page
import com.microsoft.playwright.options.ReducedMotion
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.nio.file.Files
import java.nio.file.Paths

/**
 * #876 G6 §C — the ONE dependency renderer and its list alternative in a real browser over G4's real routes and
 * real authored objects (record §13): (a) both themes through the user's own theme select with zero console and CSP
 * errors, (b) the §5.4 worked graph — every object with a text status, a shared object drawn once and its dependents
 * highlighted, (c) the list's keyboard, (d) a self-import cycle, (e) the node limit, (f) the A26 race, (g) a 200-character
 * name, (h) the empty leaf, (i) the mount on a cold load, a boosted round trip and a history restore, (j) the
 * choose-a-version state, (k) the request shape, (l) the links, (m) every graph node's family glyph in both themes
 * and (n) the Graph/List choice kept per page.
 *
 * (e) runs against a LOW node limit set for this class's own application context ([LIMIT], the brief's first option):
 * the limit is real G4 behaviour, not a fulfilled payload, and the §5.4 graph's nine objects fit under it.
 */
@Suppress("LargeClass") // one class per renderer case list (§C); every case shares the authored §5.4 world
class DependencyTreeRendererBrowserTest : DependencyPaneBrowserSuite() {
    private val board = "#dp-pane-dependencies"

    private fun openBoardDependencies(world: WorkedGraph) {
        page.navigate("$baseUrl/dashboards/${world.dashboardId}?tab=dependencies")
        awaitSettled(board) shouldBe "ready"
    }

    /** Cytoscape's own registry on the canvas — the drawn graph, read without a test hook in product code. */
    @Suppress("UNCHECKED_CAST")
    private fun drawn(scope: String): Map<String, Any?> =
        page.evaluate(
            "s => { const c = document.querySelector(s + ' .dp-deps-canvas'); const cy = c && c._cyreg && c._cyreg.cy;" +
                " return cy ? { nodes: cy.nodes().length, edges: cy.edges().length," +
                " labels: cy.nodes().map(n => n.data('text')) } : null; }",
            scope,
        ) as Map<String, Any?>

    /** The user's own theme select (PipelineWorkspaceTabsBrowserTest's walk). */
    private fun useTheme(theme: String) {
        page.navigate("$baseUrl/settings")
        if (page.inputValue("#themeSelect") != theme) {
            page.waitForResponse("**/partials/profile/theme") { page.selectOption("#themeSelect", theme) }
        }
    }

    private fun shot(name: String) {
        Files.createDirectories(SHOTS)
        page.screenshot(Page.ScreenshotOptions().setPath(SHOTS.resolve("$name.png")).setFullPage(false))
    }

    @Test
    fun `(a) both themes through the theme select - graph and list, zero console and CSP errors`() {
        startTrace()
        val world = seedWorkedGraph(ready("depthm"))
        val errors = mutableListOf<String>()
        page.onConsoleMessage { if (it.type() == "error") errors += it.text() }
        val visited = mutableSetOf<String>()
        for (theme in listOf("dark", "light")) {
            useTheme(theme)
            openBoardDependencies(world)
            visited += page.evaluate("() => document.documentElement.getAttribute('data-theme')") as String
            page.locator("$board [data-dp-deps-view='graph']").click()
            page.waitForFunction("s => !!document.querySelector(s + ' .dp-deps-canvas canvas')", board)
            shot("renderer-graph-$theme")
            showList(board)
            shot("renderer-list-$theme")
            page.locator("$board [data-dp-deps-view='graph']").click()
        }
        withClue("both themes visited (non-vacuity)") { visited shouldBe setOf("dark", "light") }
        errors.shouldBeEmpty()
        drainCspViolations().filter { !it.contains(PLAYWRIGHT_SCREENSHOT_HASH) }.shouldBeEmpty()
    }

    @Test
    fun `(b) the worked graph - every object once with a text status, the shared library highlighted with both dependents`() {
        startTrace()
        val world = seedWorkedGraph(ready("depgraph"))
        openBoardDependencies(world)
        page.locator("$board [data-dp-deps-view='graph']").click()
        page.waitForFunction("s => { const c = document.querySelector(s + ' .dp-deps-canvas'); return c && c._cyreg; }", board)
        val graph = drawn(board)
        graph["nodes"] shouldBe WORKED_NODES
        graph["edges"] shouldBe WORKED_EDGES
        @Suppress("UNCHECKED_CAST")
        val labels = graph["labels"] as List<String>
        withClue("every graph label carries a second, text-status line: $labels") { labels.all { it.contains("\n") } shouldBe true }
        showList(board)
        val rows = listRows(board)
        rows shouldHaveSize WORKED_NODES
        withClue("every row has a status word: $rows") { rows.all { it.split("|")[2].isNotBlank() } shouldBe true }
        val common = rows.filter { it.startsWith(world.common + "|") }
        common shouldHaveSize 1
        common.single() shouldContain "of ${world.selector}"
        common.single() shouldContain "of ${world.query}"
        rows.filter { it.startsWith(world.datasource + "|") } shouldHaveSize 1
        assertReadableList()
        // The version cell: the name cell's own link would navigate.
        page.locator("$board tr.dp-deps-row:has(td.dp-deps-name [title='${world.common}']) td:nth-child(3)").click()
        page.locator("$board [data-dp-deps-view='graph']").click()
        page.locator("$board .dp-deps-panel").innerText() shouldContain "Used here by 2 objects"
        page.evaluate("s => document.querySelector(s + ' .dp-deps-canvas')._cyreg.cy.$('.dp-related').edges().length", board) shouldBe 2
    }

    /**
     * The worked graph's list is readable at 1440px (the #106 lesson, ui-screens §3.7): one-line kind and version
     * labels, no word ever split, and the tables fit the pane.
     */
    private fun assertReadableList() {
        // Readable width at 1440px (the #106 lesson, ui-screens §3.7): every kind label and version lays out on ONE line —
        // the first screenshot set "Dashboard" one syllable per line in an evenly split fixed-layout table.
        @Suppress("UNCHECKED_CAST")
        val lineCounts =
            page.evaluate(
                """s => [...document.querySelectorAll(s + ' tr.dp-deps-row')].map(tr => [tr.querySelector('td.dp-deps-kind svg + span'),
                  tr.querySelector('td:nth-child(3)')].map(e => { if (!e) return -1; const r = document.createRange();
                  r.selectNodeContents(e); return new Set([...r.getClientRects()].map(x => Math.round(x.top))).size; }))""",
                board,
            ) as List<List<Int>>
        withClue("kind and version line counts per row: $lineCounts") {
            lineCounts shouldHaveSize WORKED_NODES
            lineCounts.flatten().all { it == 1 } shouldBe true
        }
        // No word is ever split: in the name, the status badge and the relationship cell every line starts after a
        // break point (a space, "/", "-" or "·"), character by character. The second screenshot set the datasource's
        // badge as "Registere d datasour ce" — a lines-per-part count passed that (five lines, five parts).
        @Suppress("UNCHECKED_CAST")
        val words =
            page.evaluate(
                """s => { const cells = [...document.querySelectorAll(s + ' tr.dp-deps-row')].flatMap(tr => [
                  tr.querySelector('td.dp-deps-name [title]'), tr.querySelector('td:nth-child(4) .ds-badge'),
                  tr.querySelector('td:nth-child(5)')]).filter(Boolean); let breaks = 0; const split = [];
                  for (const e of cells) { let top = null, prev = ''; const w = document.createTreeWalker(e, NodeFilter.SHOW_TEXT);
                    for (let n = w.nextNode(); n; n = w.nextNode()) for (let i = 0; i < n.data.length; i++) {
                      const ch = n.data[i]; if (/\s/.test(ch)) { prev = ch; continue; }
                      const r = document.createRange(); r.setStart(n, i); r.setEnd(n, i + 1);
                      const t = Math.round(r.getBoundingClientRect().top);
                      if (top !== null && t > top) { breaks++; if (!/[\s\/\-\u00b7]/.test(prev)) split.push(e.textContent); }
                      top = t; prev = ch; } }
                  return { cells: cells.length, breaks: breaks, split: split }; }""",
                board,
            ) as Map<String, Any>
        withClue("name, badge and relationship cells: $words") {
            words["cells"] shouldBe 3 * WORKED_NODES
            (words["breaks"] as Int) shouldBeGreaterThan 0
            words["split"] shouldBe emptyList<String>()
        }
        // And the worked graph's list fits its pane at 1440px: no column (the vendored nowrap badge was one) widens a
        // table past its own box — an unframed .ds-table is its own scroller (data-table.css) — nor the list.
        page.evaluate(
            """s => { const l = document.querySelector(s + ' .dp-deps-list');
              return Math.max(...[l, ...l.querySelectorAll('table')].map(e => e.scrollWidth - e.clientWidth)); }""",
            board,
        ) shouldBe 0
    }

    @Test
    fun `(c) the list's keyboard - ArrowDown three times, Home, End, the focus ring visible`() {
        val world = seedWorkedGraph(ready("depkeys"))
        openBoardDependencies(world)
        showList(board)
        page.locator("$board tr.dp-deps-row").first().focus()
        repeat(3) { page.keyboard().press("ArrowDown") }
        focusedRow() shouldBe 3
        page.keyboard().press("Home")
        focusedRow() shouldBe 0
        page.keyboard().press("End")
        focusedRow() shouldBe WORKED_NODES - 1
        val outline = page.evaluate("() => getComputedStyle(document.activeElement).outlineStyle") as String
        withClue("the focused row's outline") { outline shouldNotBe "none" }
    }

    private fun focusedRow(): Int =
        (
            page.evaluate(
                "s => Array.from(document.querySelectorAll(s + ' tr.dp-deps-row')).indexOf(document.activeElement)",
                board,
            ) as Number
        ).toInt()

    @Test
    fun `(d) a planted self-import cycle shows the cycle text and no release order`() {
        val root = ready("depcycle")
        val loop = sqlTemplate("$root/loop.sql", "<#macro one>SELECT 1</#macro>", library = true)
        sql(
            "UPDATE template_versions SET imports_json = '[{\"id\":\"$loop\",\"version\":1,\"alias\":\"self\"}]'::jsonb " +
                "WHERE version = 1 AND template_id = (SELECT id FROM templates WHERE name = '$loop' AND workspace_id = '${workspaceId()}')",
        )
        page.navigate("$baseUrl/templates/$loop?tab=dependencies")
        awaitSettled("#tw-pane-dependencies") shouldBe "cycle"
        notes("#tw-pane-dependencies") shouldContainExactly listOf(CYCLE_NOTE)
        page
            .locator("#tw-pane-dependencies")
            .innerText()
            .lowercase()
            .contains("release order:") shouldBe false
    }

    @Test
    fun `(e) past the node limit the pane says so, draws at most the limit and never scrolls the page sideways`() {
        val root = ready("deplimit")
        val libraries = (1..LIMIT + 2).map { sqlTemplate("$root/lib$it.sql", "<#macro m$it>SELECT $it</#macro>", library = true) }
        val wide = sqlTemplate("$root/wide.sql", imports = libraries)
        page.navigate("$baseUrl/templates/$wide?tab=dependencies")
        awaitSettled("#tw-pane-dependencies") shouldBe "limit"
        notes("#tw-pane-dependencies").single() shouldStartWith "Limit reached — showing $LIMIT objects."
        // What the pane DRAWS is bounded by the limit in both views: the canvas's nodes, the list's object rows, and
        // the relationship rows (a fan from the root: one fewer than its objects).
        page.locator("#tw-pane-dependencies [data-dp-deps-view='graph']").click()
        page.waitForFunction(
            "s => { const c = document.querySelector(s + ' .dp-deps-canvas'); return c && c._cyreg; }",
            "#tw-pane-dependencies",
        )
        val graph = drawn("#tw-pane-dependencies")
        (graph["nodes"] as Number).toInt() shouldBeLessThanOrEqual LIMIT
        (graph["edges"] as Number).toInt() shouldBeLessThanOrEqual LIMIT - 1
        documentScrollsSideways() shouldBe false
        showList("#tw-pane-dependencies")
        listRows("#tw-pane-dependencies").size shouldBeLessThanOrEqual LIMIT
        page.locator("#tw-pane-dependencies [data-dp-deps-list='edges'] tbody tr").count() shouldBeLessThanOrEqual LIMIT - 1
        documentScrollsSideways() shouldBe false
    }

    private fun documentScrollsSideways(): Boolean =
        page.evaluate(
            "() => { const m = document.querySelector('.app-main');" +
                " return document.documentElement.scrollWidth > window.innerWidth || (m !== null && m.scrollWidth > m.clientWidth); }",
        ) as Boolean

    @Test
    fun `(f) A26 - a version switch while the first tree is held renders the SECOND version and nothing of the first`() {
        startTrace()
        val root = ready("deprace")
        val datasource = registerDatasource()
        val first = sqlTemplate("$root/first_only.sql")
        val second = sqlTemplate("$root/second_only.sql")
        val id = pipeline("$root/raced", first, datasource)
        val created = api("GET", "/api/v1/pipelines/$id", null)
        api("POST", "/api/v1/pipelines/$id/release?release_pinned_templates=true", "", created.data("body_hash"))
        redraftWithTemplate(id, second)
        val trace = mutableListOf<String>()
        val hold = holdTreeResponses()
        countTreeRequests()
        page.navigate("$baseUrl/pipelines/$id?version=2&tab=dependencies")
        page.waitForFunction("() => window.PEWorkspace && window.PEWorkspace.viewedVersion === 2")
        page.waitForCondition { hold.held.size == 1 }
        trace += "held #1 ${treeRequests.last()}"
        page.locator(".pe-versions a[data-version='1']").click()
        page.waitForFunction("() => window.PEWorkspace && window.PEWorkspace.viewedVersion === 1")
        page.waitForCondition { hold.held.size == 2 }
        trace += "held #2 ${treeRequests.last()}"
        // The CURRENT answer first, then the superseded one: a stale answer arriving LAST is the case a missing guard
        // would draw (released in the other order, the current answer simply overwrites it and nothing is proven).
        hold.release(1)
        awaitSettled("#pe-pane-dependencies") shouldBe "ready"
        trace += "released #2 (v1, current); pane state ready"
        runCatching { hold.release(0) }.onFailure { trace += "release #1: the page had aborted it (${it.javaClass.simpleName})" }
        page.waitForTimeout(STALE_SETTLE_MS)
        trace += "released #1 (v2, superseded) after the current one; pane state ${paneState("#pe-pane-dependencies")}"
        // One Graph/List pair: every render appends one, so a second pair is a drawn superseded answer (A26) or a
        // second binding of the same pane through the switch's teardown and rebind (mount.js's WeakSet).
        withClue("view buttons after the switch") { page.locator("#pe-pane-dependencies [data-dp-deps-view]").count() shouldBe 2 }
        showList("#pe-pane-dependencies")
        val text = page.locator("#pe-pane-dependencies").innerText()
        trace += "pane shows ${listOf(first, second).filter { text.contains(it) }}"
        Files.createDirectories(EVIDENCE)
        Files.write(EVIDENCE.resolve("race-trace.txt"), trace)
        treeRequests[0] shouldContain "version=2"
        treeRequests[1] shouldContain "version=1"
        paneState("#pe-pane-dependencies") shouldBe "ready"
        text shouldContain first
        withClue("the superseded (v2) payload's own object, anywhere in the pane") {
            (page.evaluate("() => document.getElementById('pe-pane-dependencies').innerHTML") as String).contains(second) shouldBe false
        }
    }

    /** A new DRAFT (v2) of pipeline [id] whose one node pins [template] — the GET body, re-pinned, PUT back. */
    private fun redraftWithTemplate(
        id: String,
        template: String,
    ) {
        val current = api("GET", "/api/v1/pipelines/$id", null)
        page.evaluate(
            """async (args) => {
              const csrf = document.cookie.match(/(?:^|;\s*)dp_csrf=([^;]*)/);
              const b = (await (await fetch('/api/v1/pipelines/' + args.id, { credentials: 'same-origin' })).json()).data;
              delete b.body_hash; delete b.draft;
              b.nodes[0].template = { id: args.template, version: 1 };
              const res = await fetch('/api/v1/pipelines/' + args.id, { method: 'PUT', credentials: 'same-origin',
                headers: { 'Content-Type': 'application/json', 'DP-CSRF-Token': csrf ? decodeURIComponent(csrf[1]) : '',
                  'If-Match': args.hash }, body: JSON.stringify(b) });
              if (!res.ok) throw new Error('redraft ' + res.status + ' ' + (await res.text()).slice(0, 300));
            }""",
            mapOf("id" to id, "template" to template, "hash" to current.data("body_hash")),
        )
    }

    @Test
    fun `(g) a 200-character name wraps in the list with its full name on title, and the page never scrolls sideways`() {
        val root = ready("deplong")
        val datasource = registerDatasource()
        val segment = "a".repeat(LONG_SEGMENT)
        // The name cap is 200 characters in all, 64 a segment: the last segment takes what the root and the two full
        // segments leave (three separators, the `.sql` suffix), so the fixture is exactly the cap.
        val last = "c".repeat(LONG_NAME - root.length - 2 * LONG_SEGMENT - SEPARATORS - ".sql".length)
        val long = sqlTemplate("$root/$segment/${"b".repeat(LONG_SEGMENT)}/$last.sql")
        withClue("the fixture name is exactly 200 characters") { long.length shouldBe LONG_NAME }
        val id = pipeline("$root/long_reader", long, datasource)
        page.navigate("$baseUrl/pipelines/$id?tab=dependencies")
        awaitSettled("#pe-pane-dependencies") shouldBe "ready"
        showList("#pe-pane-dependencies")
        page.locator("#pe-pane-dependencies td.dp-deps-name [title='$long']").count() shouldBe 1
        documentScrollsSideways() shouldBe false
    }

    @Test
    fun `(h) a template with no imports is the empty leaf - No dependencies`() {
        val root = ready("depleaf")
        val leaf = sqlTemplate("$root/leaf.sql")
        page.navigate("$baseUrl/templates/$leaf?tab=dependencies")
        awaitSettled("#tw-pane-dependencies") shouldBe "empty"
        notes("#tw-pane-dependencies") shouldContainExactly listOf("No dependencies")
    }

    @Test
    fun `(i) the mount - a cold load, a boosted round trip and a history restore each load once, an unopened pane never`() {
        startTrace()
        val root = ready("depmount")
        val formatter = jsonataTemplate("$root/formatter.jsonata")
        val a = visualization("$root/chart_a", formatter)
        val b = visualization("$root/chart_b", formatter)
        countTreeRequests()
        page.navigate("$baseUrl/visualizations/$a")
        page.waitForSelector("#viz-tab-dependencies")
        treeRequests.shouldBeEmpty()
        page.click("#viz-tab-dependencies")
        awaitSettled("#viz-pane-dependencies") shouldBe "ready"
        treeRequests shouldHaveSize 1
        follow("/visualizations/$b")
        page.waitForTimeout(SETTLE_MS)
        withClue("a pane that is never opened makes no request") { treeRequests shouldHaveSize 1 }
        page.click("#viz-tab-dependencies")
        awaitSettled("#viz-pane-dependencies") shouldBe "ready"
        treeRequests shouldHaveSize 2
        treeRequests[1] shouldContain "chart_b"
        page.goBack()
        page.waitForFunction("() => document.querySelector('#viz-pane-dependencies').hidden")
        withClue("Back across b's own tab entry replays the tab in page: $treeRequests") { treeRequests shouldHaveSize 2 }
        page.goBack()
        page.waitForURL("**/visualizations/$a**")
        page.waitForFunction("() => !document.querySelector('#viz-pane-dependencies').hidden")
        awaitSettled("#viz-pane-dependencies") shouldBe "ready"
        page.waitForTimeout(SETTLE_MS)
        withClue("the restored pane loaded exactly once: $treeRequests") { treeRequests shouldHaveSize 3 }
        treeRequests[2] shouldContain "chart_a"
    }

    /** A boosted navigation the user's own click makes (an htmx-processed link in main). */
    private fun follow(path: String) {
        page.evaluate(
            """path => { const a = document.createElement('a'); a.href = path; a.id = 'deps-leave';
              a.textContent = 'Go'; document.getElementById('app-main').append(a); htmx.process(a); }""",
            path,
        )
        page.click("#deps-leave")
        page.waitForURL("**$path")
    }

    /**
     * The brief's (j) premise — a workspace showing its Dependencies tab with no selected version — has no page: the
     * dashboard, visualization and template workspaces render their choose-a-version card INSTEAD of the tab strip,
     * and a stored state with no draft and no pointer never reaches it — the working-version read is null, the
     * family's 404 (the parameter-set model alike). Observed here, not inferred: every version of a dashboard
     * discarded (the state a release and a discard leave, D60's fallback to NULL; seeded in SQL because a sole draft's
     * purge takes the dashboard with it) answers 404 with no pane and no tree request. The pane's own half — a
     * version-less pane bound by the real mount on a live page says the choose-a-version sentence and sends nothing —
     * is the part a page could reach.
     */
    @Test
    fun `(j) with no version to show there is no request - a version-less pane says so, an all-discarded board is 404`() {
        val world = seedWorkedGraph(ready("depnover"))
        val live = visualization("${world.root}/live_chart", world.formatter)
        sql(
            "UPDATE dashboard_versions SET status = 'DISCARDED', released_at = NOW(), discarded_at = NOW() " +
                "WHERE dashboard_id = '${world.dashboardId}'",
        )
        sql("UPDATE dashboards SET current_version = NULL WHERE id = '${world.dashboardId}'")
        countTreeRequests()
        val discarded = page.navigate("$baseUrl/dashboards/${world.dashboardId}?tab=dependencies")
        withClue("an all-discarded dashboard's workspace (observed status)") { discarded?.status() shouldBe NOT_FOUND }
        page.locator("[data-dp-deps]").count() shouldBe 0
        page.navigate("$baseUrl/visualizations/$live")
        page.locator("#viz-tab-dependencies").waitFor()
        page.evaluate(
            """() => { const host = document.createElement('section'); host.id = 'deps-versionless';
              const pane = document.createElement('div'); pane.className = 'dp-deps';
              pane.setAttribute('data-dp-deps', ''); pane.setAttribute('data-family', 'dashboard');
              pane.setAttribute('data-name', 'test/versionless'); pane.setAttribute('data-dp-deps-state', 'idle');
              host.append(pane); document.getElementById('app-main').append(host); window.DependenciesMount.scan(host); }""",
        )
        awaitSettled("#deps-versionless") shouldBe "no-version"
        page.locator("#deps-versionless [data-dp-deps-note]").innerText() shouldBe "Select a version to see its dependencies."
        page.waitForTimeout(SETTLE_MS)
        treeRequests.shouldBeEmpty()
    }

    @Test
    fun `(k) an artifact pane sends name and version, once`() {
        val world = seedWorkedGraph(ready("depshape"))
        countTreeRequests()
        openBoardDependencies(world)
        treeRequests shouldHaveSize 1
        val query = java.net.URI(treeRequests.single()).rawQuery
        query shouldBe "name=" + java.net.URLEncoder.encode(world.dashboard, Charsets.UTF_8) + "&version=1"
        // The loader calls the mount once per catalog node and a page may scan again: a second call over the same
        // elements binds nothing (mount.js's WeakSet) — no second request, the drawn pane untouched.
        page.evaluate("() => { window.DependenciesMount(document.getElementById('app-main')); window.DependenciesMount.scan(document); }")
        page.waitForTimeout(STALE_SETTLE_MS)
        withClue("requests after two more mount calls: $treeRequests") { treeRequests shouldHaveSize 1 }
        paneState(board) shouldBe "ready"
        page.locator("$board [data-dp-deps-view]").count() shouldBe 2
    }

    @Test
    fun `(l) each non-root link lands on that object at the node's version, the pointer untouched, the root unlinked`() {
        startTrace()
        val world = seedWorkedGraph(ready("deplinks"))
        val ws = workspaceId()
        val pointers = {
            POINTER_TABLES.map {
                scalar(
                    "SELECT coalesce(string_agg(name || '=' || coalesce(current_version::text, 'none'), ',' ORDER BY name), '') " +
                        "FROM $it WHERE workspace_id = '$ws'",
                )
            }
        }
        val before = pointers()
        openBoardDependencies(world)
        showList(board)
        page.locator("$board td.dp-deps-name:has(.dp-deps-root-mark) a").count() shouldBe 0
        for ((name, landed) in linkTargets(world)) {
            openBoardDependencies(world)
            showList(board)
            page.locator("$board td.dp-deps-name a[title='$name']").click()
            landed()
        }
        pointers() shouldBe before
    }

    /** Each linked node's name → the check that the page it opens shows that object at version 1. */
    private fun linkTargets(world: WorkedGraph): List<Pair<String, () -> Unit>> {
        fun paneShows(
            pane: String,
            name: String,
        ): () -> Unit =
            {
                page.waitForURL("**version=1**")
                withClue("a name-keyed path keeps its slashes: ${page.url()}") {
                    page.url().contains("%2F", ignoreCase = true) shouldBe false
                }
                page.waitForSelector("$pane [data-dp-deps]", Page.WaitForSelectorOptions().setState(ATTACHED))
                page.getAttribute("$pane [data-dp-deps]", "data-name") shouldBe name
                page.getAttribute("$pane [data-dp-deps]", "data-version") shouldBe "1"
            }
        return listOf(
            world.pipeline to paneShows("#pe-pane-dependencies", world.pipeline),
            world.chart to paneShows("#viz-pane-dependencies", world.chart),
            world.query to paneShows("#tw-pane-dependencies", world.query),
            world.set to {
                page.waitForURL("**/parameter-sets/${world.setId}?version=1")
                page.getAttribute("a[data-ps-tab='dependencies']", "href")!! shouldContain "version=1"
            },
            world.datasource to {
                page.waitForURL("**/datasources/${world.datasource}")
                page.locator("#app-main").innerText() shouldContain world.datasource
            },
        )
    }

    /**
     * The drawn graph's family glyphs, one entry per Cytoscape node: the node's kind word (its label's first part —
     * the oracle maps it, never the product's table), the glyph the layer drew for it, whether that glyph sits inside
     * the node's box and left of its label, the referenced symbol's drawn width (zero when the sprite has no such
     * symbol), its visibility, its colour and the node's fill.
     */
    @Suppress("UNCHECKED_CAST")
    private fun graphGlyphs(scope: String): List<Map<String, Any?>> = page.evaluate(GLYPHS_JS, scope) as List<Map<String, Any?>>

    @Test
    fun `(m) every graph node shows its family's glyph beside its text, in both themes, zero console and CSP errors`() {
        startTrace()
        val world = seedWorkedGraph(ready("depglyph"))
        val errors = mutableListOf<String>()
        page.onConsoleMessage { if (it.type() == "error") errors += it.text() }
        val colours = mutableMapOf<String, Set<String>>()
        for (theme in listOf("dark", "light")) {
            useTheme(theme)
            openBoardDependencies(world)
            page.locator("$board [data-dp-deps-view='graph']").click()
            page.waitForFunction("s => { const c = document.querySelector(s + ' .dp-deps-canvas'); return c && c._cyreg; }", board)
            // A symbol the sprite lacks draws nothing: wait a bounded time for every glyph to draw, then assert.
            runCatching { page.waitForFunction(GLYPHS_DRAWN_JS, board, Page.WaitForFunctionOptions().setTimeout(GLYPH_WAIT_MS)) }
            val glyphs = graphGlyphs(board)
            withClue("$theme: one glyph per drawn node, its family's, drawn and visible inside the node beside its text: $glyphs") {
                glyphs shouldHaveSize WORKED_NODES
                glyphs.map { it["kind"] }.toSet() shouldBe FAMILY_GLYPHS.keys
                glyphs.map { it["glyph"] } shouldBe glyphs.map { FAMILY_GLYPHS[it["kind"]] }
                val misplaced =
                    glyphs.filterNot {
                        it["inside"] == true && it["beside"] == true && (it["width"] as Int) > 0 && it["visible"] == true &&
                            it["color"] != it["fill"]
                    }
                misplaced.shouldBeEmpty()
            }
            colours[theme] = glyphs.map { it["color"] as String }.toSet()
            shot("renderer-graph-glyphs-$theme")
        }
        withClue("the glyph colour follows the theme: $colours") { colours["dark"] shouldNotBe colours["light"] }
        errors.shouldBeEmpty()
        drainCspViolations().filter { !it.contains(PLAYWRIGHT_SCREENSHOT_HASH) }.shouldBeEmpty()
    }

    /** The view the pane under [scope] shows: the toggle button the pane marks pressed. */
    private fun activeView(scope: String): String =
        requireNotNull(page.getAttribute("$scope [data-dp-deps-view][aria-pressed='true']", "data-dp-deps-view")) {
            "no pressed view button under $scope"
        }

    private fun openPane(
        path: String,
        pane: String,
    ): String {
        page.navigate("$baseUrl$path")
        awaitSettled(pane) shouldBe "ready"
        return activeView(pane)
    }

    @Test
    fun `(n) the Graph or List choice is per page - independent, kept on reload, restore and query change, List under reduced motion`() {
        startTrace()
        val world = seedWorkedGraph(ready("depview"))
        val boardPath = "/dashboards/${world.dashboardId}"
        val pipelinePath = "/pipelines/${world.pipelineId}"
        val pipelinePane = "#pe-pane-dependencies"
        openPane("$boardPath?tab=dependencies", board) shouldBe "graph"
        page.locator("$board [data-dp-deps-view='list']").click()
        activeView(board) shouldBe "list"
        withClue("an unvisited page keeps its own default; the board's choice is not its") {
            openPane("$pipelinePath?tab=dependencies", pipelinePane) shouldBe "graph"
        }
        page.locator("$pipelinePane [data-dp-deps-view='graph']").click()
        withClue("the board kept its own choice") { openPane("$boardPath?tab=dependencies", board) shouldBe "list" }
        page.reload()
        awaitSettled(board) shouldBe "ready"
        withClue("a reload") { activeView(board) shouldBe "list" }
        withClue("a version and a tab change on the same page") {
            openPane("$boardPath?version=1&tab=dependencies", board) shouldBe "list"
            page.navigate("$baseUrl$boardPath?tab=board")
            openPane("$boardPath?tab=dependencies&version=1", board) shouldBe "list"
        }
        // A boosted step away and Back: the board comes back from the history cache, its pane bound afresh.
        follow(pipelinePath)
        page.goBack()
        page.waitForURL { it.contains(boardPath) && it.contains("tab=dependencies") }
        page.waitForFunction("s => !!document.querySelector(s + ' [data-dp-deps]')", board)
        awaitSettled(board) shouldBe "ready"
        withClue("a history restore") { activeView(board) shouldBe "list" }
        page.emulateMedia(Page.EmulateMediaOptions().setReducedMotion(ReducedMotion.REDUCE))
        withClue("an unvisited page under reduced motion") {
            openPane("/templates/${world.query}?tab=dependencies", "#tw-pane-dependencies") shouldBe "list"
        }
        withClue("a stored choice outranks the default") { openPane("$pipelinePath?tab=dependencies", pipelinePane) shouldBe "graph" }
        @Suppress("UNCHECKED_CAST")
        val stored = page.evaluate("() => JSON.parse(localStorage.getItem('dp.deps.views'))") as List<List<String>>
        withClue("one key: only the chosen pages' paths, each with the word chosen") {
            stored.sortedBy { it[0] } shouldBe listOf(listOf(boardPath, "list"), listOf(pipelinePath, "graph")).sortedBy { it[0] }
            page.evaluate("() => Object.keys(localStorage).filter(k => k.startsWith('dp.deps'))") shouldBe listOf("dp.deps.views")
        }
    }

    companion object {
        /** The oracle: the rail's lucide glyph per kind word (layouts/default.html), the worked graph's six kinds. */
        val FAMILY_GLYPHS =
            mapOf(
                "Dashboard" to "boxes",
                "Parameter set" to "sliders-horizontal",
                "Pipeline" to "workflow",
                "Visualization" to "chart-line",
                "Template" to "code",
                "Datasource" to "database",
            )
        const val GLYPH_WAIT_MS = 5_000.0
        const val GLYPHS_DRAWN_JS =
            "s => { const l = document.querySelector(s + ' [data-dp-deps-glyphs]'); const u = l ? [...l.querySelectorAll('use')] : [];" +
                " return u.length > 0 && u.every(e => e.getBoundingClientRect().width > 0); }"
        const val GLYPHS_JS =
            """s => { const c = document.querySelector(s + ' .dp-deps-canvas'); const cy = c._cyreg.cy;
              const cr = c.getBoundingClientRect(); const ox = cr.left + c.clientLeft, oy = cr.top + c.clientTop;
              const layer = document.querySelector(s + ' [data-dp-deps-glyphs]'); const flat = v => String(v).replace(/\s/g, '');
              return cy.nodes().map(n => {
                const kind = n.data('text').split(' · ')[0];
                const g = layer ? [...layer.querySelectorAll('svg')].find(e => e.getAttribute('data-node') === n.id()) : null;
                if (!g) return { kind: kind, glyph: null };
                const b = n.renderedBoundingBox({ includeLabels: false, includeOverlays: false });
                const t = n.renderedBoundingBox({ includeNodes: false, includeLabels: true, includeOverlays: false });
                const r = g.getBoundingClientRect(); const cs = getComputedStyle(g); const use = g.querySelector('use');
                return { kind: kind, glyph: (use.getAttribute('href') || '').split('#')[1],
                  inside: r.left >= ox + b.x1 - 1 && r.right <= ox + b.x2 + 1 && r.top >= oy + b.y1 - 1 && r.bottom <= oy + b.y2 + 1,
                  beside: r.right <= ox + t.x1 + 1, width: Math.round(use.getBoundingClientRect().width),
                  visible: cs.visibility === 'visible' && cs.display !== 'none' && parseFloat(cs.opacity) === 1 && r.width > 0,
                  color: flat(cs.color), fill: flat(n.style('background-color')) }; }); }"""

        /** The node limit this class's application runs with (record §25.4's `max-versions`, lowered). */
        const val LIMIT = 12
        const val WORKED_NODES = 9
        const val WORKED_EDGES = 10
        const val LONG_SEGMENT = 64
        const val LONG_NAME = 200
        const val SEPARATORS = 3
        const val SETTLE_MS = 400.0
        const val PLAYWRIGHT_SCREENSHOT_HASH = "'sha256-47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU='"
        const val STALE_SETTLE_MS = 600.0
        const val NOT_FOUND = 404
        const val CYCLE_NOTE = "Cycle: these dependencies refer back to each other, so they have no release order."
        val ATTACHED = com.microsoft.playwright.options.WaitForSelectorState.ATTACHED
        val POINTER_TABLES = listOf("pipelines", "visualizations", "templates", "parameter_sets", "dashboards")
        val SHOTS = Paths.get("build", "reports", "876-dependencies")
        val EVIDENCE = SHOTS

        @JvmStatic
        @DynamicPropertySource
        fun lowNodeLimit(registry: DynamicPropertyRegistry) {
            registry.add("datapipelines.release.dependencies.max-versions") { LIMIT }
        }
    }
}
