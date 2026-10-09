package co.datapipelines.browser

import com.microsoft.playwright.Page
import com.microsoft.playwright.options.LoadState
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.nio.file.Paths

/**
 * 106 §B, re-aimed by #398 — the width behaviour of the templates CATALOG and the template
 * WORKSPACE, at the seven widths the owner's machines and phones actually are: 390, 768,
 * 1100, 1440, 1920, 2560, 3491.
 *
 * ## What changed with #398, and where each moved claim lives now
 *
 * The two-pane explorer page and its detail pane are GONE (the sidebar tree is §3.4's, the
 * detail pane's capabilities are the workspace's tabs). The claims this class pinned on the
 * pane moved WITH their content, and the narrowings are disclosed here rather than passed
 * over:
 *
 *  - the detail's two-column geometry (`at 1440 the detail is two columns, at 1100 they
 *    stack`) has no surface left — the workspace's own width claims (the transform face's
 *    two-by-two above 1100, one column below) are [TemplateWorkspaceBrowserTest]'s, which
 *    did not exist when this case was written;
 *  - the page's tree drawer (below 1100) is the SIDEBAR's phone drawer now —
 *    [TemplateSidebarTreeBrowserTest] owns the drawer arm;
 *  - "a selection replaces all three regions" is a full navigation now (a leaf opens its
 *    workspace; the sidebar suite's leaf case covers it) and the selection's CLS budget is
 *    re-aimed at the surface that still swaps in page — the catalog's search re-fetch;
 *  - the long-line guard is re-aimed from the Overview's excerpt card to the workspace
 *    Source tab's read-only pane — the body's only rendering surface now — with its
 *    non-vacuity kept (the fixture must NOT fit, or a green run proves nothing).
 *
 * The pipelines-only cases (the readable version row, the description's paragraph break,
 * the Usage tab's schedules) were already the workspace's since #350 and are untouched by
 * this lane's fence.
 */
class ExplorerDetailBrowserTest : BrowserSuite() {
    private fun ready() {
        val user =
            seedLocalUser(
                uniqueEmail("det-" + generatedPassword("u").take(8)),
                generatedPassword("pw"),
                mustChange = false,
            )
        login(user.email, user.oneTimePassword)
        page.waitForURL("**/dashboard")
        createWorkspace("detws-" + generatedPassword("w").take(8).lowercase())
    }

    /**
     * A pipeline and a template, both in a folder — the sidebar tree's root renders folders
     * only (§4.1), so a flat row would give the tree nothing to open. The REST seeding is
     * cookie session plus the dp_csrf double-submit pair, in-page.
     */
    private fun seed() {
        page.navigate("$baseUrl/dashboard")
        postJson(
            "/api/v1/templates",
            """{"id":"test/detail_probe","type":"sql","dialect":"POSTGRES",""" +
                """"display_name":"detail_probe","description":"106 detail fixture","body":"SELECT 1"}""",
        )
        postJson(
            "/api/v1/templates",
            """{"id":"test/second_probe","type":"sql","dialect":"POSTGRES",""" +
                """"display_name":"second_probe","description":"a second leaf to select","body":"SELECT 2"}""",
        )
        postJson(
            "/api/v1/pipelines",
            """{"name":"test/detail_probe","display_name":"detail_probe",""" +
                // 138 §E: the sectioned shape the skill teaches — label, line, blank line, label…
                """"description":"Question\nThe 106 detail fixture — long enough to exercise the 78ch reading measure in the """ +
                """overview card without wrapping into the acting column beside it.\n\nWindow and door\nOne year, chosen by year.",""" +
                """"nodes":[{"id":"fq","type":"CALCULATOR","kind":"fiscal_quarter","context_key":"run_fiscal_quarter",""" +
                """"inputs":{"date":"${'$'}current_date","fiscal_start":"${'$'}org_fiscal_start_date"}}]}""",
        )
    }

    private fun postJson(
        url: String,
        body: String,
    ) {
        val status =
            page.evaluate(
                """async (args) => {
                  const csrf = document.cookie.match(/(?:^|;\s*)dp_csrf=([^;]*)/);
                  const res = await fetch(args.url, {
                    method: 'POST', credentials: 'same-origin',
                    headers: {'Content-Type': 'application/json',
                              'DP-CSRF-Token': csrf ? decodeURIComponent(csrf[1]) : ''},
                    body: args.body,
                  });
                  return res.status;
                }""",
                mapOf("url" to url, "body" to body),
            )
        (status as Number).toInt() shouldBe 201
    }

    /** The probe template's WORKSPACE, reached the way a reader does — the catalog row. */
    private fun openTemplateWorkspace(
        query: String = "detail_probe",
        leaf: String = "detail_probe",
    ) {
        page.navigate("$baseUrl/templates?q=$query")
        page.waitForSelector("#template-list-wrapper a.tpl-result")
        page.locator("a.tpl-result", Page.LocatorOptions().setHasText(leaf)).first().click()
        page.waitForURL("**/templates/**")
        page.waitForSelector(".tw-root")
    }

    /**
     * #350: the probe pipeline's workspace, reached the same way.
     *
     * #460 correction: the row's click is a PREPARED BOOSTED swap, so `.pe-root` lands before
     * the editor's runtime has loaded its modules and activated the Alpine component. Its
     * `data-pe-runtime-epoch` is stamped only after `Alpine.initTree`, which is what binds the
     * tab buttons and their `@click` handlers; waiting for the root alone let a tab click arrive
     * first and be dropped (the version row never became visible, #469). This is a readiness
     * wait, not a sleep — the same observable the editor's own navigation witnesses use.
     */
    private fun openProbeWorkspace() {
        page.navigate("$baseUrl/pipelines?q=detail_probe")
        page.locator("#pipeline-list-wrapper a.tpl-result").first().click()
        page.waitForURL(PipelineWorkspaceUrl.PATTERN)
        page.waitForSelector(".pe-root")
        page.waitForSelector(".pe-root[data-pe-runtime-epoch]")
    }

    /** [openProbeWorkspace] then the Versions tab, with the row actually visible. */
    private fun openProbeVersions() {
        openProbeWorkspace()
        page.locator("#pe-tab-versions").click()
        page.locator("#pe-pane-versions tr[data-version-row]").first().waitFor()
    }

    private fun overflow(): Long =
        (page.evaluate("() => document.documentElement.scrollWidth - document.documentElement.clientWidth") as Number).toLong()

    /** The offenders, named — "the page overflows by 9px" is a fact nobody can act on. */
    private fun culprits(): String =
        page
            .evaluate(
                """
                () => Array.from(document.querySelectorAll('*'))
                  .filter(e => e.getBoundingClientRect().right > document.documentElement.clientWidth + 1)
                  .slice(0, 5)
                  .map(e => (e.tagName + (typeof e.className === 'string' && e.className.trim()
                    ? '.' + e.className.trim().split(/\s+/).join('.') : '')))
                  .join(' | ')
                """.trimIndent(),
            ).toString()

    // ------------------------------------------------------------------- §B

    @Test
    fun `no catalog width scrolls the document sideways, from 768 to a 3491px window`() {
        startTrace()
        ready()
        seed()

        val offenders = mutableListOf<String>()
        WIDTHS.filter { it.first >= SHELL_FLOOR }.forEach { (w, h) ->
            listOf("/pipelines", "/templates").forEach { route ->
                page.setViewportSize(w, h)
                page.navigate("$baseUrl$route")
                page.waitForLoadState(LoadState.NETWORKIDLE)
                // #398: both pages are flat catalogs at rest — every row is a full navigation,
                // so there is no selection to make and nothing to settle after.
                val extra = overflow()
                if (extra > 0) offenders += "$route at ${w}x$h overflows by ${extra}px — ${culprits()}"
            }
        }
        offenders shouldBe emptyList()
    }

    /**
     * At 390 the DOCUMENT overflows on every screen in the app, including `/dashboard`, which
     * has no catalog on it at all — the 106 measurement quoted in the original of this case.
     * The culprit is `HEADER.app-topbar` inside the shell's fixed grid (app.css and the shell
     * layout are 103's, not this lane's fence).
     *
     * What the templates surface CAN be held to at 390 is its own region: the catalog's list
     * does not overflow ITS box, and nothing inside it sticks out past it. The assertion goes
     * red the moment a badge row or a path that does not truncate widens the phone layout.
     */
    @Test
    fun `at 390 the catalog region fits its own box, whatever the shell around it does`() {
        startTrace()
        ready()
        seed()

        page.setViewportSize(390, 844)
        page.navigate("$baseUrl/templates")
        page.waitForLoadState(LoadState.NETWORKIDLE)

        val regionOverflow =
            (
                page.evaluate(
                    "() => { const p = document.querySelector('.app-catalog-list'); return p ? p.scrollWidth - p.clientWidth : 0; }",
                ) as Number
            ).toLong()
        withClue({ "the catalog region overflows by ${regionOverflow}px — ${insideCulprits(".app-catalog-list")}" }) {
            regionOverflow shouldBeLessThanOrEqual 1L
        }

        // Nothing inside the list sticks out of it: a path or a badge row that does is exactly
        // what "long text truncates inside its row, never the page" forbids.
        val stickingOut =
            page
                .evaluate(
                    """
                    () => {
                      const d = document.querySelector('.app-catalog-list');
                      const edge = d.getBoundingClientRect().right;
                      const clipped = (e) => {
                        let a = e.parentElement;
                        while (a && a !== d) {
                          const o = getComputedStyle(a).overflowX;
                          if ((o === 'auto' || o === 'scroll' || o === 'clip') && a.getBoundingClientRect().right <= edge + 1) return true;
                          a = a.parentElement;
                        }
                        return false;
                      };
                      return Array.from(d.querySelectorAll('*'))
                        .filter(e => e.getBoundingClientRect().right > edge + 1 && !clipped(e))
                        .slice(0, 5)
                        .map(e => (e.tagName + (typeof e.className === 'string' && e.className.trim()
                          ? '.' + e.className.trim().split(/\s+/).join('.') : '')))
                        .join(' | ');
                    }
                    """.trimIndent(),
                ).toString()
        stickingOut shouldBe ""
    }

    /**
     * #240's guard, re-aimed (#398) from the Overview's excerpt card to the workspace Source
     * tab's read-only pane — the body's only rendering surface now. A long line must scroll
     * INSIDE its pane and never size the layout: at every desktop width the pane's own box
     * stays put while its content scrolls (`scrollWidth > clientWidth` — the fixture must NOT
     * fit, or a green run proves nothing), and the document never grows sideways.
     */
    @Test
    fun `a long source line scrolls inside its pane - the body never sizes the layout`() {
        startTrace()
        ready()
        val slug = "longline" + generatedPassword("s").takeLast(6).lowercase()
        postJson(
            "/api/v1/templates",
            """{"id":"test/$slug/orders_sql","type":"sql","dialect":"POSTGRES","display_name":"orders_sql",""" +
                """"description":"#240 fixture","body":"$LONG_SQL_FIRST_LINE\nORDER BY order_id"}""",
        )
        // The read-only pane is the RELEASED view (the workspace's default): release v1 via
        // the in-page REST (the session's CSRF pair), hash from the create response.
        val createStatus =
            page.evaluate(
                """async () => {
                  const csrf = document.cookie.match(/(?:^|;\s*)dp_csrf=([^;]*)/);
                  const got = await fetch('/api/v1/templates?name=test/$slug/orders_sql', { credentials: 'same-origin' });
                  const body = await got.json();
                  const res = await fetch('/api/v1/templates/release', {
                    method: 'POST', credentials: 'same-origin',
                    headers: {'Content-Type': 'application/json', 'DP-CSRF-Token': csrf ? decodeURIComponent(csrf[1]) : '',
                              'If-Match': body.data.body_hash},
                    body: JSON.stringify({name: 'test/$slug/orders_sql'}),
                  });
                  return res.status;
                }""",
            )
        (createStatus as Number).toInt() shouldBe 200

        val offenders = mutableListOf<String>()
        page.navigate("$baseUrl/templates")
        listOf("light", "dark").forEach { theme ->
            ensureTheme(theme)
            listOf(1100, 1440, 1920).forEach { width -> offenders += longSourceOffenders(slug, width, theme) }
        }
        offenders shouldBe emptyList()
    }

    /** [slug]'s workspace at [width]: the source pane's containment, with the non-vacuity asserted. */
    private fun longSourceOffenders(
        slug: String,
        width: Int,
        theme: String,
    ): List<String> {
        page.setViewportSize(width, 900)
        openTemplateWorkspace(query = slug, leaf = "orders_sql")
        val where = "orders_sql at $width ($theme)"

        @Suppress("UNCHECKED_CAST")
        val edges =
            page.evaluate(
                """
                () => {
                  const pre = document.getElementById('versionBody');
                  if (!pre) return { missing: true };
                  // The pane WRAPS (`.te-readonly` is pre-wrap — the reading surface's own
                  // decision), so "never sizes the layout" is measured as: the pane's box stays
                  // inside the document while its longest line, measured WITHOUT wrapping,
                  // would have overflowed it (the non-vacuity half).
                  const probe = pre.cloneNode(true);
                  probe.style.whiteSpace = 'pre';
                  probe.style.wordBreak = 'normal';
                  probe.style.position = 'absolute';
                  probe.style.visibility = 'hidden';
                  document.body.appendChild(probe);
                  const unwrapped = probe.scrollWidth;
                  probe.remove();
                  return { missing: false, unwrapped, clientWidth: pre.clientWidth,
                           docOverflow: document.documentElement.scrollWidth - document.documentElement.clientWidth };
                }
                """.trimIndent(),
            ) as Map<String, Any?>
        check(edges["missing"] != true) { "$where: no read-only source pane on the workspace" }
        // Non-vacuity: the fixture's longest line must genuinely exceed the pane's width, or
        // a green containment assertion is a line that happened to fit.
        withClue({
            "$where: the line needs no more room than the pane " +
                "(${edges["unwrapped"]} <= ${edges["clientWidth"]}) — the fixture must not fit"
        }) {
            (edges["unwrapped"] as Number).toDouble() shouldBeGreaterThan (edges["clientWidth"] as Number).toDouble()
        }
        val offenders = mutableListOf<String>()
        if ((edges["docOverflow"] as Number).toLong() > 0) {
            offenders += "$where: the document grew sideways by ${edges["docOverflow"]}px — ${culprits()}"
        }
        shot("long-source-$width-$theme", fullPage = false)
        return offenders
    }

    @Test
    fun `the workspace tabs load once and swap - Runs arrives on the first click and not again`() {
        startTrace()
        ready()
        seed()

        page.setViewportSize(1440, 900)
        openTemplateWorkspace()

        // Versions is FIRST PAINT — server-rendered into the page (hidden: Source is the
        // default tab; the assertion is that it is IN the document, not a lazy load).
        page.waitForSelector(
            "#tw-pane-versions",
            Page.WaitForSelectorOptions().setState(com.microsoft.playwright.options.WaitForSelectorState.ATTACHED),
        )

        var runsRequests = 0
        page.onRequest { if (it.url().contains("/partials/templates/runs")) runsRequests++ }
        page.waitForResponse({ it.url().contains("/partials/templates/runs") }) {
            page.locator("#tw-tab-runs").click()
        }
        page.waitForFunction("() => !document.getElementById('tw-pane-runs').hidden")

        // A second click swaps back to a panel that is already loaded: no second request.
        page.locator("#tw-tab-source").click()
        page.waitForFunction("() => !document.getElementById('tw-pane-source').hidden")
        page.locator("#tw-tab-runs").click()
        page.waitForTimeout(200.0)
        runsRequests shouldBe 1

        // Overview is local: a swap with no request at all.
        page.locator("#tw-tab-overview").click()
        page.waitForFunction("() => !document.getElementById('tw-pane-overview').hidden")
        runsRequests shouldBe 1
    }

    @Test
    fun `the URL carries the tab an in-page switch selects - replaceState, no pushed entry`() {
        // #398's state contract: a tab change is in-page navigation that rewrites ?tab= with
        // history.replaceState and pushes NO entry — the pipelines workspace's own contract
        // (#349 deviation 3). A version switch, by contrast, IS a navigation and carries the
        // current tab along.
        startTrace()
        ready()
        seed()

        page.setViewportSize(1440, 900)
        openTemplateWorkspace()
        page.locator("#tw-tab-versions").click()
        page.waitForFunction("() => !document.getElementById('tw-pane-versions').hidden")
        page.waitForFunction("() => new URLSearchParams(location.search).get('tab') === 'versions'")

        val versions = page.locator("#tw-pane-versions tr[data-version-row]").count()
        if (versions > 1) {
            page.locator(".tw-version-link").nth(1).click()
            page.waitForLoadState(LoadState.NETWORKIDLE)
            page.waitForSelector(".tw-root")
            // The navigation landed back on the tab the reader was reading.
            page.waitForFunction("() => !document.getElementById('tw-pane-versions').hidden")
        }
    }

    @Test
    fun `a selection in the catalog's search holds the layout-shift budget at every width`() {
        // The re-aim of the selection-CLS budget: the page surface that still swaps in place
        // is the catalog's search re-fetch (#398 — a leaf click is a full navigation).
        startTrace()
        ready()
        seed()

        WIDTHS.filter { it.first >= SHELL_FLOOR }.forEach { (w, h) ->
            page.setViewportSize(w, h)
            page.addInitScript(
                """
                window.__cls = 0;
                new PerformanceObserver((l) => { for (const e of l.getEntries())
                  if (!e.hadRecentInput) window.__cls += e.value; }).observe({type: 'layout-shift', buffered: true});
                """.trimIndent(),
            )
            page.navigate("$baseUrl/templates")
            page.waitForLoadState(LoadState.NETWORKIDLE)
            page.evaluate("() => { window.__cls = 0; }")
            page.fill("#template-filter-q", "detail_probe")
            page.waitForFunction(
                "() => document.querySelectorAll('#template-list-wrapper a.tpl-result').length > 0",
            )
            page.waitForLoadState(LoadState.NETWORKIDLE)
            val cls = (page.evaluate("() => window.__cls") as Number).toDouble()
            cls shouldBeLessThan CLS_BUDGET
        }
    }

    /**
     * The first 1440px screenshot of the original round showed a version row whose meta
     * column was about ten pixels wide: one character per line. The readable WIDTH is
     * asserted, not the presence — for the PIPELINES workspace's Versions tab (the same
     * house-table fragment #349 shares; #398 gives the templates family the same component,
     * whose own geometry TemplateWorkspaceBrowserTest measures).
     */
    @Test
    fun `a version row's meta column is readable, not one character per line`() {
        startTrace()
        ready()
        seed()

        listOf(1440, 1920, 2560).forEach { width ->
            page.setViewportSize(width, 900)
            openProbeVersions()

            @Suppress("UNCHECKED_CAST")
            val meta =
                page.evaluate(
                    """
                    () => {
                      const cells = [...document.querySelectorAll('#pe-pane-versions tr[data-version-row] td')];
                      const widest = cells.reduce((a, c) => c.getBoundingClientRect().width > a.getBoundingClientRect().width ? c : a, cells[0]);
                      const line = parseFloat(getComputedStyle(widest).lineHeight) || 16;
                      return { width: widest.getBoundingClientRect().width,
                               rowWidth: widest.closest('tr[data-version-row]').getBoundingClientRect().width,
                               lines: widest.getBoundingClientRect().height / line };
                    }
                    """.trimIndent(),
                ) as Map<String, Any?>

            withClue({ "at ${width}px the meta column is ${meta.d("width")}px of ${meta.d("rowWidth")}px" }) {
                meta.d("width") shouldBeGreaterThan META_MIN_WIDTH
            }
            withClue({ "at ${width}px the meta column sets on ${meta.d("lines")} lines" }) {
                meta.d("lines") shouldBeLessThan META_MAX_LINES
            }
        }
    }

    /**
     * 138 §E.1 — the description's blank line is a paragraph break on screen. The assertion
     * is geometric: the second section's label sits at least two line-heights below the
     * first. The surface is the PIPELINES workspace's Overview (unchanged by this lane's
     * fence); the templates workspace's own Overview is [TemplateWorkspaceBrowserTest]'s.
     */
    @Test
    fun `a description's blank line renders as a paragraph break - two sections, two blocks`() {
        startTrace()
        ready()
        seed()
        page.setViewportSize(1440, 900)
        openProbeWorkspace()
        page.locator("#pe-tab-overview").click()
        page.waitForFunction(
            "() => (document.querySelector('#pe-pane-overview .tplx-measure') || {}).textContent?.includes('Window and door')",
        )

        @Suppress("UNCHECKED_CAST")
        val blocks =
            page.evaluate(
                """
                () => {
                  const p = document.querySelector('#pe-pane-overview .tplx-measure');
                  const text = p.firstChild;
                  const top = (label) => {
                    const r = document.createRange();
                    const at = text.data.indexOf(label);
                    r.setStart(text, at); r.setEnd(text, at + label.length);
                    return r.getBoundingClientRect().top;
                  };
                  return { whiteSpace: getComputedStyle(p).whiteSpace,
                           line: parseFloat(getComputedStyle(p).lineHeight) || 16,
                           gap: top('Window and door') - top('Question') };
                }
                """.trimIndent(),
            ) as Map<String, Any?>

        blocks["whiteSpace"] shouldBe "pre-line"
        withClue({ "the second label sits ${blocks.d("gap")}px below the first at a ${blocks.d("line")}px line" }) {
            blocks.d("gap") shouldBeGreaterThan 2.0 * blocks.d("line")
        }
    }

    @Test
    fun `the usage tab lists the schedules that run the pipeline (#259)`() {
        startTrace()
        val wsName = "detws-" + generatedPassword("w").take(8).lowercase()
        val email = uniqueEmail("det-" + generatedPassword("u").take(8))
        val user = seedLocalUser(email, generatedPassword("pw"), mustChange = false)
        login(user.email, user.oneTimePassword)
        page.waitForURL("**/dashboard")
        createWorkspace(wsName)
        seed()
        seedSchedule(wsName, email, "test/nightly-usage", "pipeline:test/detail_probe")

        page.setViewportSize(1440, 900)
        openProbeWorkspace()
        page.waitForResponse({ it.url().contains("/usage") }) {
            page.locator("#pe-tab-usage").click()
        }
        page.waitForFunction("() => !document.getElementById('pe-pane-usage').hidden")
        page.waitForFunction("() => (document.getElementById('pe-usage-body').innerText || '').includes('test/nightly-usage')")

        val usage = page.locator("#pe-usage-body").innerText()
        usage.lowercase().shouldContain("schedules running it")
        usage.shouldContain("test/nightly-usage")
        usage.lowercase().shouldContain("enabled")
    }

    /**
     * A live schedule of EXACTLY [workspaceName] whose target names [pipelineName] — seeded
     * straight into the shared Postgres with the same shape the scheduler's adapter writes
     * (`executor_id = 'pipeline'`, `target_ref = 'pipeline:<name>'`). The workspace and user
     * are matched by their full unique keys, never by a LIKE over the shared database.
     */
    private fun seedSchedule(
        workspaceName: String,
        userEmail: String,
        scheduleName: String,
        targetRef: String,
    ) {
        java.sql.DriverManager
            .getConnection(SharedBrowserE2e.jdbcUrl, SharedBrowserE2e.username, SharedBrowserE2e.password)
            .use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        INSERT INTO schedules (id, workspace_id, name, executor_id, payload_schema_version, payload_json,
                                               target_ref, cron, timezone, next_due_at, created_by, updated_by)
                        SELECT gen_random_uuid(), w.id, '$scheduleName', 'pipeline', 1,
                               jsonb_build_object('pipeline', right('$targetRef', - length('pipeline:'))),
                               '$targetRef', '0 30 2 * * *', 'UTC', NOW() + interval '1 day', u.id, u.id
                          FROM workspaces w
                          CROSS JOIN users u
                         WHERE w.name = '$workspaceName'
                           AND u.email = '$userEmail'
                        """.trimIndent(),
                    )
                }
            }
    }

    @Test
    fun `the workspace header's Release opens the 4_3d dialog - the plain confirm is gone`() {
        // SUPERSEDES 106's plain-confirm pin; re-aimed (#398) from the detail header to the
        // WORKSPACE header. A Release button renders only where a DRAFT exists, so the probe
        // is updated once — the PUT lands v2 DRAFT — before the walk.
        startTrace()
        ready()
        seed()
        // A draft over the seeded v1: the draft's write is the hash-preconditioned PUT (the
        // precondition is the working version's hash — for a draft-only template, its own).
        val putStatus =
            page.evaluate(
                """async () => {
                  const csrf = document.cookie.match(/(?:^|;\s*)dp_csrf=([^;]*)/);
                  const got = await fetch('/api/v1/templates?name=test/detail_probe', { credentials: 'same-origin' });
                  const body = await got.json();
                  const res = await fetch('/api/v1/templates', {
                    method: 'PUT', credentials: 'same-origin',
                    headers: {'Content-Type': 'application/json', 'DP-CSRF-Token': csrf ? decodeURIComponent(csrf[1]) : '',
                              'If-Match': body.data.body_hash},
                    body: JSON.stringify({id: 'test/detail_probe', type: 'sql', dialect: 'POSTGRES',
                                          display_name: 'detail_probe', description: '106 detail fixture',
                                          body: 'SELECT 1 -- drafted'}),
                  });
                  return res.status;
                }""",
            )
        (putStatus as Number).toInt() shouldBe 200

        page.setViewportSize(1440, 900)
        openTemplateWorkspace()

        val release =
            page.locator(
                ".tw-topbar button",
                com.microsoft.playwright.Page
                    .LocatorOptions()
                    .setHasText("Release v"),
            )
        release.isVisible shouldBe true
        page.waitForResponse({ it.url().contains("/lifecycle/release") }) { release.click() }
        page.locator("#tx-dialog [data-lifecycle-dialog='template-release']").waitFor()
        // No verb attributes anywhere on the page — the fetch path is gone.
        page.locator("#tx-dialog [data-verb-url]").count() shouldBe 0
        page.keyboard().press("Escape")
        page.locator("#tx-dialog [data-lifecycle-dialog]").count() shouldBe 0
    }

    // ---------------------------------------------------------- the evidence

    @Test
    fun `the seven widths, light and dark, photographed for the handback`() {
        startTrace()
        ready()
        seed()

        page.navigate("$baseUrl/templates")
        ensureTheme("light") // the deployment default is dark; the light walk asks for light
        walk("light")
        page.setViewportSize(1440, 900)
        page.navigate("$baseUrl/templates")
        page.waitForLoadState(LoadState.NETWORKIDLE)
        // The deployment default is dark: a blind toggle would flip to LIGHT and the wait
        // for dark would match only by racing the swap (it did, on laptops; not on CI).
        ensureTheme("dark")
        walk("dark")
    }

    private fun walk(mode: String) {
        WIDTHS.forEach { (w, h) ->
            listOf("pipelines", "templates").forEach { screen ->
                page.setViewportSize(w, h)
                page.navigate("$baseUrl/$screen")
                page.waitForLoadState(LoadState.NETWORKIDLE)
                // #398: both pages are catalogs at rest — photographed as they rest, the way
                // the pipelines one has been since #350.
                page.evaluate("() => window.scrollTo(0, 0)")
                shot("$screen-$w-$mode", fullPage = w >= 1100)
            }
        }
    }

    /** Which descendants of [selector] stick out past its right edge — named, not counted. */
    private fun insideCulprits(selector: String): String =
        page
            .evaluate(
                """
                (selector) => {
                  const root = document.querySelector(selector);
                  if (!root) return 'no ' + selector;
                  const edge = root.getBoundingClientRect().right;
                  return Array.from(root.querySelectorAll('*'))
                    .filter(e => e.getBoundingClientRect().right > edge + 1)
                    .slice(0, 6)
                    .map(e => {
                      const id = n => n.tagName + (n.id ? '#' + n.id : '')
                        + (typeof n.className === 'string' && n.className.trim()
                          ? '.' + n.className.trim().split(/\s+/).join('.') : '');
                      const chain = [];
                      for (let n = e; n && chain.length < 4; n = n.parentElement) chain.push(id(n));
                      const r = e.getBoundingClientRect();
                      return chain.join(' < ') + ' [' + Math.round(r.left) + '..' + Math.round(r.right)
                        + ' vs ' + Math.round(edge) + ']';
                    })
                    .join(' | ');
                }
                """.trimIndent(),
                selector,
            ).toString()

    private fun shotDir(): Path = Paths.get("build", "reports", "106-screenshots").also { it.toFile().mkdirs() }

    /**
     * A viewport shot below 1100px, a full-page shot above it.
     *
     * Playwright's full-page capture stitches scrolled bands and renders `position: fixed`
     * elements once — which is exactly what the drawer and its backdrop are, so a full-page
     * shot of the narrow layout shows a tree floating over content it is not over. The
     * viewport shot is what the phone actually looks like.
     */
    private fun shot(
        name: String,
        fullPage: Boolean,
    ) = page.screenshot(Page.ScreenshotOptions().setPath(shotDir().resolve("106-$name.png")).setFullPage(fullPage))

    private fun Map<String, Any?>.d(key: String) = (this[key] as Number).toDouble()

    private companion object {
        /** The owner's own widths: a phone, a tablet, the stacked breakpoint, and four desktops. */
        val WIDTHS =
            listOf(390 to 844, 768 to 1024, 1100 to 900, 1440 to 900, 1920 to 1080, 2560 to 1440, 3491 to 1440)

        /** Google's "good" CLS is 0.1; the prompt's budget for a SWAP is half of that. */
        const val CLS_BUDGET = 0.05

        /**
         * The narrowest width at which the app SHELL itself fits. Below it every screen
         * overflows, `/dashboard` included — see the 390 test's KDoc.
         */
        const val SHELL_FLOOR = 768

        // A meta column narrower than this cannot set "3 days ago - someone - 7 runs" at
        // all. The bug this guard exists for was a ~10px column setting ONE CHARACTER per
        // line; the floor stays an order of magnitude above it.
        const val META_MIN_WIDTH = 48.0

        /** ...and it must not need more than a few lines to do it. */
        const val META_MAX_LINES = 4.0

        /** #240's SQL fixture: a 126-character first line, wider than the source pane at every desktop width. */
        const val LONG_SQL_FIRST_LINE =
            "SELECT order_id, customer_id, amount_cents, currency, placed_at FROM orders WHERE customer_id IS NOT NULL AND amount_cents > 0"
    }
}
