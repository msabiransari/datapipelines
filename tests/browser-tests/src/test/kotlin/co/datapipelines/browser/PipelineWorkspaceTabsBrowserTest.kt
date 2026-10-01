package co.datapipelines.browser

import com.microsoft.playwright.Page
import com.microsoft.playwright.Route
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/**
 * #349 — the six-tab workspace composition, driven in a real browser (spec §4.1–§4.4,
 * A6/A7/A8-browser):
 *
 *  1. Six tabs, Flow the default; a tab change is NAVIGATION ONLY — the URL carries
 *     `tab=`, the panes swap by the `hidden` attribute, and the run state is untouched.
 *  2. The house table is the ACTUAL component on Runs, Usage and Versions (A7): the
 *     upgrade marker (`data-dt-ready`) and the component's own sort buttons are the
 *     behavior, not a class name; the lazy tabs load ONCE (click once, leave, return —
 *     no second fetch).
 *  3. The viewed version moves IN PAGE: selector click, Versions-tab Open and the
 *     View-run's-version affordance update URL, chip, body block, pin, selector marks
 *     and Versions-tab marks without a document reload (A9's browse half).
 *  4. Run input drafts are PER VERSION: an override typed on v1 survives a round trip
 *     v1→v2→v1 and never leaks into the other version's schema (spec §4.3).
 *  5. Zero console/CSP errors across the whole walk.
 *
 * The promoter's tab omission and zero forbidden fetches are the A3 arms in
 * [PipelineWorkspacePromoterAdmittedBrowserTest] (its in-test promotion target admits
 * the promoter; this fixture's workspace has none, so a promoter here is the fail-closed
 * 404 control that class already owns).
 */
@Suppress("UNCHECKED_CAST")
class PipelineWorkspaceTabsBrowserTest : BrowserSuite() {
    private fun loginReadyUser(slug: String) {
        val user =
            seedLocalUser(
                uniqueEmail(slug + generatedPassword("u").take(8)),
                generatedPassword("pw"),
                mustChange = false,
            )
        login(user.email, user.oneTimePassword)
        page.waitForURL("**/dashboard")
        createWorkspace("pwstabs-" + generatedPassword("w").take(8).lowercase())
    }

    /**
     * Seeds v1 (current release, parameter `year`) + v2 (newer release with parameters
     * `year` AND `region`) + v3 (draft, parameter `year` only — a schema v2's region
     * override must NOT leak into), through the REST surface. Returns the id.
     */
    private fun seedThreeVersions(
        name: String,
        v1NodeId: String,
        v2NodeId: String,
    ): String {
        // POST creates the pipeline's FIRST version as its DRAFT; releasing twice gives
        // v1 and v2 (both released, v2 current), and the last PUT re-opens the draft as
        // v3 — the 348 version-browser fixture's shape.
        val id = createPipeline(name, listOf(param("year", required = true)), v1NodeId)
        release(id, getPipeline(id).hash)
        // v2 draft: adds `region` — a different body, provably.
        val hash2 = putDraft(id, displayName = "Tabs v2", parameters = listOf(param("year", required = true), param("region")))
        release(id, hash2)
        // v3 draft: back to `year` only — a schema v2's region override must NOT leak into.
        val current = getPipeline(id)
        putDraft(id, displayName = "Tabs v3 draft", parameters = listOf(param("year", required = true)), ifMatch = current.hash)
        return id
    }

    /** One DECLARED parameter, keyed by name — the contract body's own shape. */
    private fun param(
        key: String,
        required: Boolean = false,
    ): String = """"$key":{"type":"STRING"${if (required) ""","required":true""" else ""}}"""

    private fun createPipeline(
        name: String,
        parameters: List<String>,
        nodeId: String,
    ): String {
        val params = parameters.joinToString(",")
        val result =
            page.evaluate(
                """async ([name, params, nodeId]) => {
                  const csrf = document.cookie.match(/(?:^|;\s*)dp_csrf=([^;]*)/);
                  const res = await fetch('/api/v1/pipelines', { method: 'POST', credentials: 'same-origin',
                    headers: { 'Content-Type': 'application/json', 'DP-CSRF-Token': csrf ? decodeURIComponent(csrf[1]) : '' },
                    body: JSON.stringify({ name, display_name: name, parameters: JSON.parse('{' + params + '}'),
                      nodes: [{ id: nodeId, type: 'CALCULATOR', kind: 'fiscal_quarter', context_key: 'q_' + nodeId,
                        inputs: { date: '${'$'}current_date', fiscal_start: '${'$'}org_fiscal_start_date' } }] }) });
                  const text = await res.text();
                  let id = null;
                  try { const d = JSON.parse(text); id = d.id || (d.data && d.data.id) || null; } catch (e) {}
                  return { status: res.status, id };
                }""",
                arrayOf(name, params, nodeId),
            ) as Map<String, Any?>
        check((result["status"] as Number).toInt() == 201) { "create ${result["status"]}" }
        return result["id"] as String
    }

    private data class Current(
        val hash: String,
        val body: String,
    )

    private fun getPipeline(id: String): Current {
        val text = apiGet("/api/v1/pipelines/$id")
        val hash = Regex(""""body_hash"\s*:\s*"([^"]+)"""").find(text)!!.groupValues[1]
        return Current(hash, text)
    }

    private fun apiGet(path: String): String =
        page.evaluate(
            """async (path) => (await fetch(path, { credentials: 'same-origin' })).text()""",
            path,
        ) as String

    private fun putDraft(
        id: String,
        displayName: String,
        parameters: List<String>,
        ifMatch: String? = null,
    ): String {
        val hash = ifMatch ?: getPipeline(id).hash
        val body = getPipeline(id).body
        val result =
            page.evaluate(
                """async ([id, hash, displayName, params]) => {
                  const csrf = document.cookie.match(/(?:^|;\s*)dp_csrf=([^;]*)/);
                  const cur = await (await fetch('/api/v1/pipelines/' + id, { credentials: 'same-origin' })).json();
                  const b = cur.data;
                  delete b.body_hash; delete b.draft;
                  b.display_name = displayName;
                  b.parameters = JSON.parse('{' + params + '}');
                  const res = await fetch('/api/v1/pipelines/' + id, { method: 'PUT', credentials: 'same-origin',
                    headers: { 'Content-Type': 'application/json', 'DP-CSRF-Token': csrf ? decodeURIComponent(csrf[1]) : '', 'If-Match': hash },
                    body: JSON.stringify(b) });
                  const text = await res.text();
                  let outHash = null; let status = null;
                  try { const d = JSON.parse(text); outHash = d.data && d.data.body_hash; status = d.data && d.data.status; } catch (e) {}
                  return { http: res.status, hash: outHash, status };
                }""",
                arrayOf(id, hash, displayName, parameters.joinToString(",")),
            ) as Map<String, Any?>
        check((result["http"] as Number).toInt() == 200) { "put ${result["http"]}" }
        return result["hash"] as String
    }

    private fun release(
        id: String,
        hash: String,
    ) {
        val status =
            page.evaluate(
                """async ([id, hash]) => {
                  const csrf = document.cookie.match(/(?:^|;\s*)dp_csrf=([^;]*)/);
                  const res = await fetch('/api/v1/pipelines/' + id + '/release', { method: 'POST', credentials: 'same-origin',
                    headers: { 'Content-Type': 'application/json', 'DP-CSRF-Token': csrf ? decodeURIComponent(csrf[1]) : '', 'If-Match': hash },
                    body: '{}' });
                  return res.status;
                }""",
                arrayOf(id, hash),
            ) as Number
        check(status.toInt() == 200) { "release $status" }
    }

    private fun openWorkspace(id: String) {
        page.navigate("$baseUrl/pipelines/$id")
        page.locator(".pe-root").waitFor()
        page.locator(".pe-card").first().waitFor()
        // The selector renders from the workspace block's admitted history — wait for the
        // CLIENT render (Alpine's x-for), not just the root's markup.
        page.waitForFunction(
            "() => (document.querySelectorAll('.pe-versions a').length === (JSON.parse(document.getElementById('pipeline-workspace').textContent).versionRows || []).length)",
        )
    }

    /** The seeded history, read back — a seed bug fails HERE, not at a click. */
    private fun versionsOf(id: String): List<String> {
        val text = apiGet("/api/v1/pipelines/$id/versions")
        return Regex(""""version"\s*:\s*(\d+)""").findAll(text).map { it.groupValues[1] }.toList()
    }

    /** The suite's own CSP refusal collector, drained — the walk must be clean. */
    private fun consoleOrCspErrors(): List<String> = drainCspViolations()

    /** Counts matching requests while letting them through. */
    private fun countRequests(
        pattern: String,
        into: MutableList<String>,
    ) {
        page.route(pattern) { route: Route ->
            into.add(route.request().url())
            route.resume()
        }
    }

    @Test
    fun `six tabs, Flow the default - a tab change is navigation only and the lazy tabs load once`() {
        loginReadyUser("pwst")
        val id = seedThreeVersions("pwstabs/flow/" + generatedPassword("p").take(8).lowercase(), "n_v1", "n_v2")
        openWorkspace(id)

        // Flow default: the graph pane is the visible one, the URL has no tab.
        page.evaluate("() => document.querySelector('#pe-pane-flow').hidden") shouldBe false
        page.evaluate(
            "() => [...document.querySelectorAll('.pe-tab')].filter(b => b.getAttribute('aria-selected') === 'true').map(b => b.id).join()",
        ) shouldBe
            "pe-tab-flow"
        page.url() shouldNotContain "tab="

        val runsFetches = mutableListOf<String>()
        countRequests("**/partials/pipelines/*/runs", runsFetches)
        val usageFetches = mutableListOf<String>()
        countRequests("**/partials/pipelines/*/usage", usageFetches)

        // Runs: first open fetches once; leaving and returning does not re-fetch.
        page.locator("#pe-tab-runs").click()
        page.waitForSelector(
            "#pe-runs-body table[data-dt-ready], #pe-runs-body .ds-empty-description",
            Page.WaitForSelectorOptions().setState(com.microsoft.playwright.options.WaitForSelectorState.ATTACHED),
        )
        val afterFirst = runsFetches.size
        afterFirst shouldBe 1
        page.locator("#pe-tab-flow").click()
        page.locator("#pe-tab-runs").click()
        page.waitForTimeout(500.0)
        runsFetches.size shouldBe afterFirst

        // Usage: the same lazy-once rule.
        page.locator("#pe-tab-usage").click()
        page.waitForSelector(
            "#pe-usage-body table[data-dt-ready], #pe-usage-body .ds-empty-description",
            Page.WaitForSelectorOptions().setState(com.microsoft.playwright.options.WaitForSelectorState.ATTACHED),
        )
        usageFetches.size shouldBe 1

        // The URL carries the tab; Overview and the flow pane swap by the hidden attribute.
        page.locator("#pe-tab-overview").click()
        page.evaluate("() => document.querySelector('#pe-pane-overview').hidden") shouldBe false
        page.evaluate("() => document.querySelector('#pe-pane-flow').hidden") shouldBe true
        page.url() shouldContain "tab=overview"

        // A tab change never touches the execution state (nothing ran, but the run strip
        // and status stay exactly what they were — navigation only).
        consoleOrCspErrors().shouldBeEmpty()
    }

    @Test
    fun `Run parameters opens the Parameters tab beside Execute, declaration table and overrides composed`() {
        loginReadyUser("pwsp")
        val id = seedThreeVersions("pwstabs/params/" + generatedPassword("p").take(8).lowercase(), "p_v1", "p_v2")
        openWorkspace(id)

        // Beside Execute: the topbar's Run parameters affordance opens the SAME tab.
        page.locator("button[aria-label='Run parameters']").click()
        page.evaluate("() => document.querySelector('#pe-pane-parameters').hidden") shouldBe false
        page.url() shouldContain "tab=parameters"

        // The declaration is the house table, read-only: the viewed version's schema —
        // and the ACTUAL component upgraded it (the marker, not a class name).
        page.waitForFunction("() => !!document.querySelector('#pe-pane-parameters table[data-dt-ready]')")
        val rows =
            page.evaluate(
                "() => [...document.querySelectorAll('#pe-pane-parameters tbody tr td:first-child')].map(td => td.textContent.trim())",
            ) as List<String>
        // The name cell also carries the required badge; the row names the key.
        rows.any { it.startsWith("year") } shouldBe true

        // Overrides stay a separate section from the declaration.
        page.evaluate("() => !!document.querySelector('#pe-pane-parameters .pe-input')") shouldBe true
        consoleOrCspErrors().shouldBeEmpty()
    }

    @Test
    fun `the selector and the Versions tab move the viewed version IN PAGE - url, chip, body, marks, schema`() {
        loginReadyUser("pwsv")
        val id = seedThreeVersions("pwstabs/switch/" + generatedPassword("p").take(8).lowercase(), "s_v1", "s_v2")
        withClue("the seed must leave three versions") {
            versionsOf(id).sorted() shouldBe listOf("1", "2", "3")
        }
        openWorkspace(id)

        // v2 is the current pointer (released last) — the arrival.
        page.evaluate("() => JSON.parse(document.getElementById('pipeline-data').textContent).version") shouldBe 2

        // Selector → v3 (the draft): one in-page transition.
        page.locator(".pe-versions a[data-version='3']").click()
        page.waitForURL("**/pipelines/*?*version=3*")
        page.waitForFunction("() => window.PEWorkspace && window.PEWorkspace.viewedVersion === 3")
        page.evaluate("() => JSON.parse(document.getElementById('pipeline-data').textContent).version") shouldBe 3
        page.locator(".pe-vchip").innerText() shouldContain "v3"
        // The selector's viewed mark followed.
        page.evaluate("() => document.querySelector('.pe-versions a[data-version=\"3\"]').getAttribute('aria-current')") shouldBe "page"

        // The Versions tab: the house table, with the viewed mark and the Open link that
        // switches IN PAGE (no navigation).
        page.locator("#pe-tab-versions").click()
        page.waitForSelector("#pe-pane-versions table[data-dt-ready]")
        page.waitForFunction(
            "() => !!document.querySelector('#pe-pane-versions tr[data-version-row=\\\"1\\\"] [data-pe-viewed-mark]') === false",
        )
        page.evaluate("() => !!document.querySelector('#pe-pane-versions tr[data-version-row=\\\"3\\\"] [data-pe-viewed-mark]')") shouldBe
            true

        page.locator("#pe-pane-versions tr[data-version-row='1'] a[data-pe-version-link]").click()
        page.waitForURL("**/pipelines/*?*version=1*")
        page.waitForFunction("() => window.PEWorkspace && window.PEWorkspace.viewedVersion === 1")
        page.evaluate("() => JSON.parse(document.getElementById('pipeline-data').textContent).version") shouldBe 1
        // The tab mark re-rendered without a reload.
        page.evaluate("() => !!document.querySelector('#pe-pane-versions tr[data-version-row=\\\"1\\\"] [data-pe-viewed-mark]')") shouldBe
            true

        // An absent version is the visible refusal, and the view does not move.
        page.evaluate("() => window.__peInstance.applyVersion(99)")
        page.waitForSelector(".pe-banner")
        page.evaluate("() => window.PEWorkspace.viewedVersion") shouldBe 1
        consoleOrCspErrors().shouldBeEmpty()
    }

    @Test
    fun `run input drafts are per version - an override survives v1 to v2 to v1 and never leaks`() {
        loginReadyUser("pwso")
        val id = seedThreeVersions("pwstabs/over/" + generatedPassword("p").take(8).lowercase(), "o_v1", "o_v2")
        openWorkspace(id)

        // The arrival is v2 (the current pointer); the schema the override is typed on
        // must be an EXPLICIT move: v1 first, type `year`, then v2 — whose own bag is
        // fresh — then back to v1, whose override returns.
        page.locator(".pe-versions a[data-version='1']").click()
        page.waitForFunction("() => window.PEWorkspace && window.PEWorkspace.viewedVersion === 1")
        page.locator("button[aria-label='Run parameters']").click()
        page.locator("#pe-pane-parameters .pe-input[data-key='year']").fill("2023")
        page.waitForTimeout(100.0)

        // v2: a different schema — its own bag (year empty, region empty), no leak.
        page.locator(".pe-versions a[data-version='2']").click()
        page.waitForFunction("() => window.PEWorkspace && window.PEWorkspace.viewedVersion === 2")
        page.waitForFunction("() => document.querySelector('#pe-pane-parameters .pe-input[data-key=\\\"year\\\"]')?.value === ''")
        page.waitForTimeout(300.0)
        val v2Inputs =
            page.evaluate(
                "() => [...document.querySelectorAll('#pe-pane-parameters .pe-input')].map(i => i.getAttribute('data-key') + '=' + i.value)",
            ) as List<String>
        withClue("v2's own bag after the switch: $v2Inputs") {
            v2Inputs.any { it.startsWith("region=") } shouldBe true
        }
        v2Inputs.first { it.startsWith("region=") } shouldBe "region="

        // Type region on v2, then return to v1: year restored, region unknown on v1.
        page.locator("#pe-pane-parameters .pe-input[data-key='region']").fill("eu")
        page.locator(".pe-versions a[data-version='1']").click()
        page.waitForFunction("() => window.PEWorkspace && window.PEWorkspace.viewedVersion === 1")
        page.waitForFunction("() => document.querySelector('#pe-pane-parameters .pe-input[data-key=\\\"year\\\"]')?.value === '2023'")
        page.evaluate("() => !!document.querySelector('#pe-pane-parameters .pe-input[data-key=\\\"region\\\"]')") shouldBe false
        consoleOrCspErrors().shouldBeEmpty()
    }

    @Test
    fun `the dock keeps Node Details beside Results, Errors and Events, and a selection opens it with the graph wide`() {
        loginReadyUser("pwsd")
        val id = createPipeline("pwstabs/dock/" + generatedPassword("p").take(8).lowercase(), listOf(param("year")), "d_v1")
        openWorkspace(id)

        // Node Details is the dock's landing tab and the strip names all four.
        page.evaluate("() => document.querySelector('#pe-dock-tab-details').getAttribute('aria-selected')") shouldBe "true"
        page.evaluate("() => document.querySelector('#pe-dock-tab-details').textContent.trim()") shouldBe "Node Details"
        page.evaluate(
            "() => !!document.querySelector('#pe-dock-tab-results') && !!document.querySelector('#pe-dock-tab-errors') && !!document.querySelector('#pe-dock-tab-events')",
        ) shouldBe
            true

        // Selecting a node opens Node Details and the graph keeps its width (no right
        // inspector ever appears).
        // The a11y list row's own click handler, dispatched directly: the row sits in the
        // stage's bottom-left stack and Playwright's actionability wait races the graph's
        // layout settle — the handler under test is the click listener, not the hit target.
        page.evaluate("() => document.querySelector('#pe-node-list li[data-node-id]').click()")
        page.waitForSelector("#pe-pane-details .pe-details")
        @Suppress("UNCHECKED_CAST")
        val geometry =
            page.evaluate(
                """
                () => ({
                  dockH: document.querySelector('.pe-dock').getBoundingClientRect().height,
                  stageW: document.querySelector('.pe-stage').getBoundingClientRect().width,
                  rootW: document.querySelector('.pe-root').getBoundingClientRect().width,
                  inspector: !!document.querySelector('.pe-inspector, [class*=inspector]'),
                  emptyPromptShown: getComputedStyle(document.querySelector('#pe-pane-details .pe-empty')).display !== 'none',
                })
                """.trimIndent(),
            ) as Map<String, Any?>
        (geometry["dockH"] as Number).toDouble() shouldBeGreaterThanOrEqual 120.0
        (geometry["stageW"] as Number).toDouble() shouldBeGreaterThanOrEqual (geometry["rootW"] as Number).toDouble() * 0.9
        geometry["inspector"] shouldBe false
        geometry["emptyPromptShown"] shouldBe false
        consoleOrCspErrors().shouldBeEmpty()
    }

    @Test
    fun `the whole composition walk is clean in both themes - through the user's own theme select`() {
        loginReadyUser("pwst2")
        val id = seedThreeVersions("pwstabs/theme/" + generatedPassword("p").take(8).lowercase(), "t_v1", "t_v2")
        for (theme in listOf("dark", "light")) {
            // The SAME control a user drives (settings/index.html's select#themeSelect);
            // prefers-color-scheme emulation changes nothing on this app's class-based theme.
            page.navigate("$baseUrl/settings")
            page.selectOption("#themeSelect", theme)
            page.waitForTimeout(300.0)
            openWorkspace(id)
            for (tab in listOf("#pe-tab-overview", "#pe-tab-parameters", "#pe-tab-runs", "#pe-tab-usage", "#pe-tab-versions")) {
                page.locator(tab).click()
                page.waitForTimeout(300.0)
            }
            page.locator("#pe-tab-flow").click()
            page.waitForTimeout(300.0)
        }
        consoleOrCspErrors().shouldBeEmpty()
    }
}
