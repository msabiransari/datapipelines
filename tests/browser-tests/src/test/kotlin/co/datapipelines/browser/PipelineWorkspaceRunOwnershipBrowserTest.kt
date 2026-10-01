package co.datapipelines.browser

import com.microsoft.playwright.Page
import com.microsoft.playwright.Route
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/**
 * #349 — view versus run ownership under a LIVE run, in a real browser (spec §4.2/§4.3,
 * A9): a v1 run keeps its identity and its stream while the page browses v2; the v1
 * progress never paints the v2 graph; the strip and "View run's version" stay truthful;
 * returning to v1 replays the run's own states onto its own body.
 *
 * The run is the 151 stage-chain fixture long enough to outlive the navigation (the
 * [PipelineWorkspaceHistoryBrowserTest] precedent: a run the page leaves and comes back
 * to). Zero DELETEs on the wire proves the version switches cancelled nothing.
 */
@Suppress("UNCHECKED_CAST")
class PipelineWorkspaceRunOwnershipBrowserTest : BrowserSuite() {
    private fun loginReadyUser() {
        val user =
            seedLocalUser(
                uniqueEmail("wsrun-" + generatedPassword("u").take(8)),
                generatedPassword("pw"),
                mustChange = false,
            )
        login(user.email, user.oneTimePassword)
        page.waitForURL("**/dashboard")
        createWorkspace("wsrunws-" + generatedPassword("w").take(8).lowercase())
    }

    private var sourceName: String = ""

    /** A v1/v2 pair with DISTINCT node ids: v1 the stage chain (slow at 400k), v2 a small calculator body. */
    private fun seedTwoVersions(name: String): String {
        sourceName = "wsrun-src-" + generatedPassword("d").take(6).lowercase()
        EditorRunFixtures.registerSourceDatasource(page, baseUrl, sourceName)
        val id = EditorRunFixtures.createStageChainPipeline(page, name, sourceName, 400_000)
        // POST leaves v1 the DRAFT: release it first, or the v2 PUT would overwrite it.
        // The 142 cascade releases the draft template pins WITH the pipeline.
        page.evaluate(
            """async ([id]) => {
              const csrf = document.cookie.match(/(?:^|;\s*)dp_csrf=([^;]*)/);
              const cur = await (await fetch('/api/v1/pipelines/' + id, { credentials: 'same-origin' })).json();
              const rel = await fetch('/api/v1/pipelines/' + id + '/release?release_pinned_templates=true', { method: 'POST', credentials: 'same-origin',
                headers: { 'Content-Type': 'application/json', 'DP-CSRF-Token': csrf ? decodeURIComponent(csrf[1]) : '', 'If-Match': cur.data.body_hash },
                body: '{}' });
              if (!rel.ok) throw new Error('v1 release ' + rel.status + ' body=' + (await rel.text()).slice(0, 200));
            }""",
            arrayOf(id),
        )
        // v2: a different body — one CALCULATOR node, distinct id.
        val result =
            page.evaluate(
                """async ([id]) => {
                  const csrf = document.cookie.match(/(?:^|;\s*)dp_csrf=([^;]*)/);
                  const cur = await (await fetch('/api/v1/pipelines/' + id, { credentials: 'same-origin' })).json();
                  const b = cur.data; const hash = b.body_hash;
                  delete b.body_hash; delete b.draft;
                  b.display_name = 'Run ownership v2';
                  b.nodes = [{ id: 'v2_calc', type: 'CALCULATOR', kind: 'fiscal_quarter', context_key: 'q_v2',
                    inputs: { date: '${'$'}current_date', fiscal_start: '${'$'}org_fiscal_start_date' } }];
                  const res = await fetch('/api/v1/pipelines/' + id, { method: 'PUT', credentials: 'same-origin',
                    headers: { 'Content-Type': 'application/json', 'DP-CSRF-Token': csrf ? decodeURIComponent(csrf[1]) : '', 'If-Match': hash },
                    body: JSON.stringify(b) });
                  const t = await res.json();
                  return { http: res.status, hash: t.data && t.data.body_hash };
                }""",
                arrayOf(id),
            ) as Map<String, Any?>
        check((result["http"] as Number).toInt() == 200) { "v2 draft ${result["http"]}" }
        page.evaluate(
            """async ([id, hash]) => {
              const csrf = document.cookie.match(/(?:^|;\s*)dp_csrf=([^;]*)/);
              await fetch('/api/v1/pipelines/' + id + '/release', { method: 'POST', credentials: 'same-origin',
                headers: { 'Content-Type': 'application/json', 'DP-CSRF-Token': csrf ? decodeURIComponent(csrf[1]) : '', 'If-Match': hash },
                body: '{}' });
            }""",
            arrayOf(id, result["hash"]),
        )
        return id
    }

    private fun openWorkspace(id: String) {
        page.navigate("$baseUrl/pipelines/$id?version=1")
        page.locator(".pe-root").waitFor()
        page.locator(".pe-card").first().waitFor()
    }


    @Test
    fun `a held stale SQL response never paints - select A, move on, release A (A8)`() {
        loginReadyUser()
        val name = "wsrun/stale/" + generatedPassword("p").take(8).lowercase()
        val id = seedTwoVersions(name)
        openWorkspace(id)
        page.evaluate("() => { window.__peErrors = []; }")

        // Hold ONLY A's node-SQL response: select A (its answer stays in flight),
        // then select B — B's request supersedes A's in the component's book and
        // completes normally — then release A's late answer (A8's literal shape).
        var heldAny = false
        var heldRoute: Route? = null
        page.route("**/partials/pipelines/*/nodes/*/sql**") { route ->
            synchronized(this) {
                if (!heldAny) {
                    heldAny = true
                    heldRoute = route
                    return@route
                }
            }
            route.resume()
        }

        // locator.evaluate dispatches the row's own click handler without Playwright's
        // actionability wait — the clipped list is focus-revealed, not pointer-visible.
        page.locator("#pe-node-list li[data-node-id='stage_calendar']").evaluate("el => el.click()")
        page.waitForFunction("() => window.__peInstance && window.__peInstance.sqlToken !== null")
        page.waitForTimeout(300.0)

        val sqlResponses = mutableListOf<String>()
        page.onResponse { response ->
            if (response.url().contains("/nodes/") && response.url().contains("/sql")) {
                sqlResponses.add(response.status().toString() + " " + response.url().takeLastWhile { it != '/' })
            }
        }
        page.locator("#pe-node-list li[data-node-id='pairs_a']").evaluate("el => el.click()")
        try {
            page.waitForFunction(
                "() => window.__peInstance && window.__peInstance.sqlToken !== null && document.querySelectorAll('#pe-node-sql .pe-sql-block, #pe-node-sql .ds-empty').length > 0",
                12000.0,
            )
        } catch (e: Throwable) {
            val diag =
                page.evaluate(
                    """() => ({ token: window.__peInstance && window.__peInstance.sqlToken,
                       paneLen: (document.getElementById('pe-node-sql') || { innerHTML: '' }).innerHTML.length,
                       selected: window.__peInstance && window.__peInstance.selectedNode && window.__peInstance.selectedNode.id })""",
                ).toString()
            throw AssertionError("B's pane never rendered; sqlResponses=$sqlResponses diag=$diag", e)
        }
        val beforeRelease =
            page.evaluate("() => (document.getElementById('pe-node-sql').textContent || '').includes('stale_cal.sql')")
        beforeRelease shouldBe false

        // Selecting B ABORTS A's in-flight request (the hx-sync="this:abort" requester):
        // A's response can never land. Resume the held route defensively — for an aborted
        // request it is a no-op — and prove the pane still shows B afterwards.
        val held = synchronized(this) { heldRoute }
        withClue("the witness needs A's response actually held") { held shouldNotBe null }
        runCatching { held!!.resume() }
        page.waitForTimeout(2000.0)

        // The pane still shows B — A's late body (its template ref) never painted.
        val paneText = page.evaluate("() => (document.getElementById('pe-node-sql') || { textContent: '' }).textContent").toString()
        withClue("pane after releasing the stale response: ${paneText.take(160)}") {
            paneText shouldNotContain "stale_cal.sql"
        }
        page.evaluate("() => (window.__peInstance.selectedNode || {}).id") shouldBe "pairs_a"

        // Positive control: the guard saw two issued reads (A then B) — the witness is not vacuous.
        val gen = page.evaluate("() => window.__peInstance.viewGeneration") as Number
        gen.toInt() shouldBeGreaterThanOrEqual 2
        drainCspViolations().shouldBeEmpty()
    }

    @Test
    fun `a v1 run keeps its identity and stream while the page browses v2 - nothing paints the wrong body`() {
        loginReadyUser()
        val name = "wsrun/own/" + generatedPassword("p").take(8).lowercase()
        val id = seedTwoVersions(name)
        openWorkspace(id)

        // The delete collector: a version switch (or anything else) must NEVER cancel.
        val deletes = mutableListOf<String>()
        page.route("**/api/v1/executions/*") { route: Route ->
            if (route.request().method() == "DELETE") deletes.add(route.request().url())
            route.resume()
        }

        // Start the v1 run from the toolbar.
        page.locator(".pe-run").click()
        page.waitForSelector(".pe-status.pe-status-running", Page.WaitForSelectorOptions().setState(com.microsoft.playwright.options.WaitForSelectorState.ATTACHED))

        // Browse v2 mid-run — IN PAGE, the stream untouched.
        page.locator(".pe-versions a[data-version='2']").click()
        page.waitForTimeout(3000.0)
        @Suppress("UNCHECKED_CAST")
        val afterClick =
            page.evaluate(
                """() => ({ pin: window.PEWorkspace && window.PEWorkspace.viewedVersion,
                   banner: (document.querySelector('.pe-banner') || { textContent: '' }).textContent.trim(),
                   rows: [...document.querySelectorAll('.pe-versions a')].map(a => a.getAttribute('data-version')),
                   status: document.querySelector('.pe-status').textContent.trim() })""",
            ) as Map<String, Any?>
        withClue("state after the mid-run v2 click: $afterClick") {
            afterClick["pin"] shouldBe 2L
        }
        page.waitForTimeout(1000.0)

        // The strip rides the EXECUTION tabs (Results/Errors/Events — the tabs that show
        // the captured run): open Results, and it names the RUN's version while the page
        // views v2, with the way back beside it.
        page.locator("#pe-dock-tab-results").click()
        page.waitForFunction("() => { const s = document.querySelector('.pe-run-strip'); return s && getComputedStyle(s).display !== 'none' && s.textContent.includes('v1'); }")
        val stripText = page.locator(".pe-run-strip").innerText()
        stripText shouldContain "v1"
        val viewBtnShown =
            page.evaluate("() => { const b = document.querySelector('.pe-run-strip button'); return b && getComputedStyle(b).display !== 'none'; }")
        viewBtnShown shouldBe true

        // The v2 graph wears NO v1 run state: the one v2 node (v2_calc) is idle, and no
        // v1 node id exists on this body to carry a state.
        page.evaluate("() => { const i = window.__peInstance; return i.nodeStates && Object.keys(i.nodeStates).length > 0; }") shouldBe true
        page.evaluate("() => { const i = window.__peInstance; return i.runMatchesViewed(); }") shouldBe false

        // View run's version: back to v1 in page, the run's states return with it.
        page.locator(".pe-run-strip button").click()
        page.waitForFunction("() => window.PEWorkspace && window.PEWorkspace.viewedVersion === 1")
        page.waitForTimeout(1200.0)
        page.evaluate("() => { const i = window.__peInstance; return i.runMatchesViewed(); }") shouldBe true

        // The run finishes server-side; the wire holds ZERO cancels.
        page.waitForFunction("() => { const i = window.__peInstance; return i.runIdentity && i.runIdentity.status !== 'running'; }", 120000.0)
        deletes.shouldBeEmpty()
        // The strip stays truthful to the end.
        page.locator(".pe-run-strip").innerText() shouldContain "v1"
    }
}
