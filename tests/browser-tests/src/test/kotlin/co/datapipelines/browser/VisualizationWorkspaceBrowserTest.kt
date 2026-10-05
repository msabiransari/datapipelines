package co.datapipelines.browser

import com.microsoft.playwright.Page
import com.microsoft.playwright.options.RequestOptions
import com.microsoft.playwright.options.WaitForSelectorState
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.nio.file.Files
import java.nio.file.Paths

/**
 * #399 — the visualization WORKSPACE in a real browser on the real application (ui-screens.md §4.24): the Preview
 * tab renders the version's test-case FIXTURES one case at a time with no data request; the tab strip swaps panes in
 * page and carries `?tab=`; Evidence shows a run's screenshot only once one was stored; the Release dialog lists its
 * refusals before the button and its POST releases through the service; a viewer's and a promoter's pages issue no
 * lifecycle request; the 3d bundle; every tab ZERO-CSP in both themes (the suite's after-each), at three widths.
 *
 * #426 — the strip's tab switches ride the shared history helper (workspace/history.js, family `visualizations`):
 * Back/Forward re-select in page, a restored root wires once, and another family's entry runs none of this glue.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class VisualizationWorkspaceBrowserTest : VisualizationBrowserSuite() {
    @Test
    @Order(1)
    fun `Preview renders the fixtures one case at a time, the strip swaps panes in page, and no data request leaves`() {
        startTrace()
        page.setViewportSize(DESKTOP_W, DESKTOP_H)
        val root = ready("vws")
        val (id, _) = createVisualization("$root/charts/units")
        val requested = java.util.concurrent.CopyOnWriteArrayList<String>()
        page.onRequest { requested += it.method() + " " + it.url() }

        page.navigate("$baseUrl/visualizations/$id")
        // ONE Plotly bundle, the server's choice for the viewed version's traces.
        page.locator("script[data-dp-plotly-bundle]").count() shouldBe 1
        page.locator("script[data-dp-plotly-bundle]").getAttribute("data-dp-plotly-bundle") shouldBe "2d"
        page.locator("#viz-tab-preview").innerText() shouldContain "test fixtures"
        page.waitForSelector("#viz-pane-preview section[data-viz-case-index='0'][data-dp-ready='true']")
        val traces =
            page.evaluate(
                "() => { const g = document.querySelector(\"#viz-pane-preview section[data-viz-case-index='0'] .js-plotly-plot\");" +
                    " return g && g._fullData ? g._fullData.length : -1; }",
            ) as Number
        withClue("case 'two series' renders its two traces") { traces.toInt() shouldBe 2 }
        // One case at a time: the second is hidden and NOT mounted until it is selected.
        page.locator("#viz-pane-preview section[data-viz-case-index='1']").getAttribute("hidden") shouldBe "hidden"
        page.locator("#viz-pane-preview section[data-viz-case-index='1'][data-dp-ready]").count() shouldBe 0

        page.selectOption("[data-viz-case-select]", "1")
        page.waitForSelector("#viz-pane-preview section[data-viz-case-index='1'][data-dp-ready='true']")
        page.locator("#viz-pane-preview section[data-viz-case-index='0']").getAttribute("hidden") shouldBe "hidden"
        page.waitForFunction(
            "() => { const c = document.querySelector(\"#viz-pane-preview section[data-viz-case-index='1'] .dp-dashboard-status\");" +
                " return c && c.getAttribute('data-dp-state') === 'no-data'; }",
        )
        withClue("the fixture mode ran on the block alone: no request reached /api/v1 — $requested") {
            requested.filter { it.contains("/api/v1/") }.shouldBeEmpty()
        }

        // In page: the same document, each lazy pane loading once, the URL carrying the tab.
        page.evaluate("() => { window.__v399Marker = 1; }")
        page.click("#viz-tab-overview")
        page.waitForSelector("#viz-pane-overview:not([hidden]) dl, #viz-pane-overview:not([hidden]) .ds-card")
        page.url() shouldContain "tab=overview"
        page.click("#viz-tab-evidence")
        page.waitForSelector("#viz-pane-evidence:not([hidden]) [data-viz-runs-cap]")
        page.locator("#viz-pane-evidence [data-viz-runs-cap]").innerText() shouldContain "100"
        page.click("#viz-tab-used-by")
        page.waitForSelector("#viz-pane-used-by:not([hidden]) [data-viz-used-by-empty]")
        page.locator("#viz-pane-used-by").innerText() shouldContain "no dashboard pins this visualization"
        page.click("#viz-tab-versions")
        page.waitForSelector("#viz-pane-versions:not([hidden]) [data-version-row='1']")
        page.locator("#viz-pane-versions [data-viz-export]").getAttribute("href") shouldBe "/api/v1/visualizations/$id/export"
        page.evaluate("() => window.__v399Marker") shouldBe 1
        page.url() shouldContain "tab=versions"

        // The URL is the state: a reload re-selects Versions.
        page.reload()
        page.waitForSelector("#viz-pane-versions:not([hidden]) [data-version-row='1']")
        page.locator("#viz-pane-preview").getAttribute("hidden") shouldBe "hidden"
    }

    @Test
    @Order(2)
    @Suppress("LongMethod") // one walk on purpose: the run the Evidence tab reads is the run the release consumes
    fun `the Release dialog refuses before evidence, Evidence shows the screenshot only once stored, and the POST releases`() {
        startTrace()
        page.setViewportSize(DESKTOP_W, DESKTOP_H)
        val root = ready("vrel")
        val (id, hash) = createVisualization("$root/charts/units")

        // No run yet: the dialog lists the evidence refusal BEFORE any button, and offers no confirm.
        page.navigate("$baseUrl/visualizations/$id?tab=versions")
        page.waitForSelector("#viz-pane-versions:not([hidden]) [data-verb='visualization-release']")
        page.click("[data-verb='visualization-release']")
        page.waitForSelector("#dp-dialog [data-lifecycle-dialog='visualization-release']")
        page.locator("#dp-dialog [data-refusal-code]").count() shouldBeGreaterThan 0
        page.locator("#dp-dialog [data-verb='visualization-release-confirm']").count() shouldBe 0
        page.click("#dp-dialog [data-lifecycle-close]")
        page.waitForFunction("() => !document.querySelector('#dp-dialog .app-modal')")

        // A GREEN run, submitted with NO screenshot yet.
        val started = api("POST", "/api/v1/visualizations/$id/tests/sessions", "")
        val sessionId = started.at("data", "session_id") as String
        val runId = started.at("data", "run_id") as String
        val submitted =
            api(
                "POST",
                "/api/v1/visualizations/$id/tests/sessions/$sessionId/results",
                """{"cases":[{"name":"two series","verdict":"green"},{"name":"empty","verdict":"green"}],
                   "environment":{"browser":"chromium (playwright)","theme":"light","viewport":"1440x900"}}""",
            )
        submitted.at("data", "status") shouldBe "GREEN"
        val upload = submitted.at("data", "upload") as Map<*, *>

        // Observed event one: the run's detail says "no screenshot" and renders no image.
        page.navigate("$baseUrl/visualizations/$id?tab=evidence")
        page.waitForSelector("#viz-pane-evidence:not([hidden]) [data-run-row='$runId']")
        page.click("[data-run-row='$runId'] button")
        page.waitForSelector("#viz-evidence-run [data-viz-no-screenshot]")
        page.locator("#viz-evidence-run img").count() shouldBe 0

        // The upload, with the single-use capability alone (the agent's path).
        val image = page.screenshot()
        val stored =
            page.request().post(
                "$baseUrl${upload["url"]}?case=two%20series",
                RequestOptions
                    .create()
                    .setHeader(upload["header"] as String, upload["token"] as String)
                    .setHeader("Content-Type", "image/png")
                    .setData(image),
            )
        withClue(stored.text().take(EXCERPT)) { stored.status() shouldBe 201 }

        // Observed event two: the same run now shows the stored image through the existing route.
        page.reload()
        page.waitForSelector("#viz-pane-evidence:not([hidden]) [data-run-row='$runId']")
        page.click("[data-run-row='$runId'] button")
        page.waitForSelector("#viz-evidence-run img[data-viz-screenshot]")
        page.waitForFunction(
            "() => { const i = document.querySelector('#viz-evidence-run img[data-viz-screenshot]'); return i && i.naturalWidth > 0; }",
        )
        page.locator("#viz-evidence-run [data-viz-no-screenshot]").count() shouldBe 0

        // The dialog now admits the release; its POST (htmx, the page's CSRF header) releases and redirects.
        page.navigate("$baseUrl/visualizations/$id?tab=versions")
        page.waitForSelector("#viz-pane-versions:not([hidden]) [data-verb='visualization-release']")
        page.click("[data-verb='visualization-release']")
        page.waitForSelector("#dp-dialog [data-verb='visualization-release-confirm']")
        page.locator("#dp-dialog [data-refusal-code]").count() shouldBe 0
        page.click("#dp-dialog [data-verb='visualization-release-confirm']")
        page.waitForURL("**/visualizations/$id?tab=versions&ok=released")
        page.waitForSelector("#toast .ds-toast, #toast [role='status'], #toast [role='alert']")
        page.waitForSelector("#viz-pane-versions:not([hidden]) [data-version-row='1']")
        val record = api("GET", "/api/v1/visualizations/$id", null)
        withClue("the service released the exact draft ($hash)") { record.at("data", "current_version") shouldBe 1 }
        page.locator("[data-viz-viewed-label]").innerText() shouldContain "released"
    }

    @Test
    @Order(3)
    fun `a viewer reads every tab with no verb and no lifecycle request, and a promoter's fail-closed lens is the 404`() {
        startTrace()
        page.setViewportSize(DESKTOP_W, DESKTOP_H)
        val root = ready("vrole")
        val (id, _) = createVisualization("$root/charts/units")

        val viewer = memberSession("vview", "viewer")
        val vpage = viewer.page
        val lifecycle = mutableListOf<String>()
        vpage.onRequest { request ->
            if (request.method() == "POST" || request.url().contains("/lifecycle/")) lifecycle += request.method() + " " + request.url()
        }
        TABS.forEach { tab ->
            vpage.navigate("$baseUrl/visualizations/$id?tab=$tab")
            awaitPane(vpage, tab)
        }
        vpage.waitForSelector("#viz-pane-versions [data-version-row='1']")
        vpage.locator("#app-main [data-verb]").count() shouldBe 0
        withClue("the viewer's page issued no POST and no lifecycle read: $lifecycle") { lifecycle.shouldBeEmpty() }
        viewer.close()

        // No promotion target here: the promoter's lens FAILS CLOSED — the workspace is the family's 404.
        val promoter = memberSession("vpromo", "promoter")
        val ppage = promoter.page
        val posts = mutableListOf<String>()
        ppage.onRequest { request ->
            if (request.method() == "POST" || request.url().contains("/lifecycle/")) posts += request.method() + " " + request.url()
        }
        ppage.navigate("$baseUrl/visualizations/$id").status() shouldBe 404
        ppage.navigate("$baseUrl/visualizations/$id?version=1&tab=versions").status() shouldBe 404
        ppage.navigate("$baseUrl/visualizations")
        ppage.waitForSelector("#app-main [data-lens-unavailable]")
        ppage.locator("#app-main a.tpl-result").count() shouldBe 0
        ppage.locator("#app-main [data-verb]").count() shouldBe 0
        withClue("the promoter's page issued no POST and no lifecycle read: $posts") { posts.shouldBeEmpty() }
        promoter.close()
    }

    @Test
    @Order(4)
    fun `every tab is CSP-clean in both themes at three widths, and a 3d trace declares the 3d bundle`() {
        startTrace()
        page.setViewportSize(DESKTOP_W, DESKTOP_H)
        val root = ready("vshot")
        val (id, _) = createVisualization("$root/charts/units")
        val (surface, _) = createVisualization("$root/charts/hill", trace3d = true)

        for (theme in listOf("light", "dark")) {
            page.setViewportSize(DESKTOP_W, DESKTOP_H) // the theme toggle lives in the desktop top bar
            ensureTheme(theme)
            for ((w, h) in WIDTHS) {
                page.setViewportSize(w, h)
                TABS.forEach { tab -> checkTab(id, tab, "${w}x$h in $theme", "$theme-$w") }
            }
        }

        page.setViewportSize(DESKTOP_W, DESKTOP_H)
        page.navigate("$baseUrl/visualizations/$surface")
        page.locator("script[data-dp-plotly-bundle]").count() shouldBe 1
        page.locator("script[data-dp-plotly-bundle]").getAttribute("data-dp-plotly-bundle") shouldBe "3d"
        page.waitForSelector("#viz-pane-preview section[data-viz-case-index='0'][data-dp-ready='true']")
        shot("workspace-preview-3d")
        drainCspViolations().shouldBeEmpty()
        ensureTheme("light")
    }

    @Test
    @Order(5)
    fun `#426 - Back and Forward re-select in-page tab switches, and a boosted leave and Back wires once`() {
        startTrace()
        page.setViewportSize(DESKTOP_W, DESKTOP_H)
        val root = ready("vhist")
        val (id, _) = createVisualization("$root/charts/units")

        // A blocked inline style or script on any walk below fails the class's after-each (zero CSP):
        // the in-page walk runs light, the leave-and-restore dark.
        ensureTheme("light")
        page.navigate("$baseUrl/visualizations/$id?version=1")
        awaitPane(page, "preview")
        val length = historyLength()

        // Preview → Overview → Versions: two entries of the workspace's own (tab only).
        page.click("#viz-tab-overview")
        expectTab("overview")
        page.click("#viz-tab-versions")
        expectTab("versions")
        historyLength() shouldBe length + 2
        page.url() shouldContain "version=1"

        // Back ×2, Forward ×2 — IN PAGE: the same document, no entry minted.
        val marker = page.evaluate("() => (window.__p426Doc = Math.random().toString(36).slice(2))")
        for ((back, tab) in listOf(true to "overview", true to "preview", false to "overview", false to "versions")) {
            if (back) page.goBack() else page.goForward()
            expectTab(tab)
            historyLength() shouldBe length + 2
            page.evaluate("() => window.__p426Doc") shouldBe marker
        }
        historyStat("replayed.visualizations") shouldBe 4
        historyStat("listeners") shouldBe 1

        ensureTheme("dark")
        page.navigate("$baseUrl/visualizations/$id?version=1&tab=versions")
        awaitPane(page, "versions")
        // A boosted leave and Back: htmx restores the page; it is wired ONCE (one root marker,
        // one window listener) and its strip still switches and replays.
        page.click(".app-nav-link[data-nav-section='/templates']")
        page.waitForSelector("#template-list-wrapper") // #398: /templates is the flat catalog, no explorer pane
        page.goBack()
        page.waitForSelector("#viz-pane-versions:not([hidden])")
        page.waitForFunction("() => document.querySelectorAll('.dp-ws-root[data-dp-ws-wired=\"1\"]').length === 1")
        historyStat("listeners") shouldBe 1
        page.click("#viz-tab-overview")
        expectTab("overview")
        page.goBack()
        expectTab("versions")
        page.evaluate("() => document.querySelectorAll('.dp-ws-root').length") shouldBe 1
        ensureTheme("light")
    }

    @Test
    @Order(6)
    fun `#426 - another family's entry popping beside a live visualizations root runs no visualizations code`() {
        startTrace()
        page.setViewportSize(DESKTOP_W, DESKTOP_H)
        val root = ready("vfam")
        val (id, _) = createVisualization("$root/charts/units")

        // Every link ONTO a workspace is a full navigation (hx-boost="false"), so a `pipelines` entry never shares a
        // document with a visualizations root by the UI: the foreign entries are minted through the helper's OWN push,
        // and a stand-in answers for the pipelines (a family registers its replay the same way, history.js `listen`).
        ensureTheme("light")
        page.navigate("$baseUrl/visualizations/$id?tab=versions")
        awaitPane(page, "versions")
        val lazyPrefix = page.locator("#viz-pane-overview").getAttribute("data-lazy-url").substringBeforeLast("/")
        lazyPrefix shouldContain "/partials/visualizations/$id"
        page.evaluate(
            "() => { window.__p426Doc = 'one'; window.__p426Foreign = 0;" +
                " window.WorkspaceHistory.listen('pipelines', () => { window.__p426Foreign++; return true; }); }",
        )
        val requested = java.util.concurrent.CopyOnWriteArrayList<String>()
        page.onRequest { requested += it.method() + " " + it.url() }
        val length = historyLength()

        // The foreign switch: converts the arrival entry to a pipelines one and pushes the next (URL moves to Overview
        // while the strip, which no one told, stays on Versions — the state a per-root URL listener would "repair").
        page.evaluate("() => window.WorkspaceHistory.push('pipelines', { version: 1, tab: 'flow' }, { version: 1, tab: 'overview' })")
        page.url() shouldContain "tab=overview"
        historyLength() shouldBe length + 1
        for ((back, urlTab) in listOf(true to "versions", false to "overview")) {
            if (back) page.goBack() else page.goForward()
            page.waitForFunction("() => window.__p426Foreign >= ${if (back) 1 else 2}")
            page.url() shouldContain "tab=$urlTab"
            // The strip is the VISUALIZATIONS' and never moved: Versions, one pane, no pane loaded by this walk.
            page.locator("#viz-tab-versions").getAttribute("aria-selected") shouldBe "true"
            page.locator("#viz-pane-overview").getAttribute("hidden") shouldBe "hidden"
            page.evaluate("() => window.__p426Doc") shouldBe "one"
        }
        historyStat("replayed.pipelines") shouldBe 2
        historyStat("replayed.visualizations") shouldBe 0
        historyStat("listeners") shouldBe 1
        val leaked = requested.filter { it.contains(lazyPrefix) }
        withClue("a visualizations partial was requested by a foreign entry: $leaked") { leaked.shouldBeEmpty() }
    }

    private fun historyLength(): Int = (page.evaluate("() => history.length") as Number).toInt()

    @Test
    @Order(7)
    @Suppress("LongMethod") // two real cached-history walks share one document and the passive observation ledger
    fun `#444 - cached Back remounts Preview, switches real fixtures, and refits only live instances`() {
        startTrace()
        page.setViewportSize(DESKTOP_W, DESKTOP_H)
        val root = ready("vrestore")
        val (id, _) = createVisualization("$root/charts/units")
        observePreviewHistory()
        val errors = java.util.concurrent.CopyOnWriteArrayList<String>()
        val requested = java.util.concurrent.CopyOnWriteArrayList<String>()
        page.onConsoleMessage {
            val text = it.text()
            // BrowserSuite sets aside this exact empty CSSOM style hash too; all real refusals still fail.
            val emptyStyle = text.startsWith("Applying inline style") && text.contains("'sha256-47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU='")
            if (it.type() == "error" && !emptyStyle) errors += text
        }
        page.onPageError { errors += it }
        page.onRequest { requested += it.method() + " " + it.url() }
        page.navigate("$baseUrl/visualizations/$id")
        awaitPreviewResult("0")
        previewObservation("mounts.length") shouldBe 1
        previewObservation("selectors.length") shouldBe 1
        val documentMarker = page.evaluate("() => window.__v444.documentMarker")
        val swapListeners = previewObservation("swaps")
        swapListeners shouldBe 1
        val previewRequests = requested.count { it.contains("/partials/visualizations/$id/preview") }
        previewRequests shouldBe 1

        for (cycle in 1..2) {
            page.evaluate("() => { window.__v444.before = document.querySelector('#viz-preview-data'); }")
            page.click(".app-nav-link[data-nav-section='/templates']")
            page.waitForSelector("#template-list-wrapper")
            page.evaluate("() => window.__v444.documentMarker") shouldBe documentMarker
            withClue("the real boosted leave saved the wired Preview in htmx's cache") {
                page.evaluate(
                    "id => JSON.parse(sessionStorage.getItem('htmx-history-cache') || '[]').some(" +
                        "e => e.url.startsWith('/visualizations/' + id) && e.content.includes('data-viz-wired=\"1\"'))",
                    id,
                ) shouldBe true
            }
            page.goBack()
            page.waitForFunction("n => window.__v444.restores.length === n", cycle)
            page.waitForSelector(
                "#viz-pane-preview:not([hidden]) #viz-preview-data[data-viz-wired='1']",
                Page.WaitForSelectorOptions().setState(WaitForSelectorState.ATTACHED),
            )
            page.waitForFunction("() => document.querySelector('.viz-workspace .dp-ws-root').__dpWsWired === true")
            withClue("Back used a real htmx cache hit, restoring a new marker-carrying node in the same document") {
                page.evaluate(
                    "() => { const o = window.__v444, b = document.querySelector('#viz-preview-data');" +
                        " return o.hits.length === o.restores.length && o.misses === 0 && b !== o.before && !o.before.isConnected; }",
                ) shouldBe true
                page.evaluate("() => window.__v444.documentMarker") shouldBe documentMarker
            }
            // This assertion rejects a static ready marker copied from the cache: only real mounts are counted.
            withClue("post-Back selected case must mount afresh (cycle $cycle)") {
                previewObservation("mounts.length") shouldBe cycle * 2
            }
            previewObservation("selectors.length") shouldBe cycle + 1
            previewObservation("swaps") shouldBe swapListeners
            page.evaluate(
                "() => { const o = window.__v444, b = document.querySelector('#viz-preview-data'), s = window.VisualizationWorkspacePreview;" +
                    " const m = o.mounts.filter(m => m.block === b); return m.length === 1 && m[0].instance !== null && s.instances[m[0].index] === m[0].instance; }",
            ) shouldBe true
            awaitPreviewResult("0")
            page.selectOption("[data-viz-case-select]", "1")
            awaitPreviewResult("1")
            previewObservation("mounts.length") shouldBe cycle * 2 + 1
            page.selectOption("[data-viz-case-select]", "0")
            awaitPreviewResult("0")
            page.evaluate("() => { window.__v444.resizesBefore = window.__v444.mounts.reduce((n, m) => n + m.resizes, 0); }")
            page.click("#viz-tab-overview")
            awaitPane(page, "overview")
            page.click("#viz-tab-preview")
            awaitPreviewResult("0")
            page.evaluate(
                "() => { const o = window.__v444, b = document.querySelector('#viz-preview-data');" +
                    " return o.mounts.reduce((n, m) => n + m.resizes, 0) === o.resizesBefore + 1 &&" +
                    " o.mounts.filter(m => !m.section.isConnected).every(m => m.resizes === m.frozenResizes) &&" +
                    " o.mounts.filter(m => m.block === b && m.index === 0)[0].resizes > 0; }",
            ) shouldBe true
            previewObservation("mounts.length") shouldBe cycle * 2 + 1
            shot("444-preview-restored-$cycle")
            println(
                "444 cache cycle=$cycle hits=${previewObservation(
                    "hits.length",
                )} mounts=${previewObservation("mounts.length")} selectors=${previewObservation("selectors.length")} swaps=$swapListeners",
            )
        }
        requested.filter { it.contains("/api/v1/") }.shouldBeEmpty()
        requested.count { it.contains("/partials/visualizations/$id/preview") } shouldBe previewRequests
        requested.count { it.contains("/visualizations/$id") && !it.contains("/partials/") } shouldBe 1
        errors.shouldBeEmpty()
        drainCspViolations().shouldBeEmpty()
    }

    /** Observes real registrations, mounts and resizes; every wrapper delegates to the original implementation. */
    private fun observePreviewHistory() {
        page.addInitScript(
            """(() => {
              const o = window.__v444 = { documentMarker: Math.random(), mounts: [], selectors: [], swaps: 0, hits: [], restores: [], misses: 0 };
              const add = EventTarget.prototype.addEventListener;
              EventTarget.prototype.addEventListener = function(type, fn, options) {
                if (type === 'change' && this.matches && this.matches('[data-viz-case-select]')) o.selectors.push(this);
                if (type === 'htmx:afterSwap' && String(fn).includes('reg.handler(event)')) o.swaps++;
                return add.call(this, type, fn, options);
              };
              let mount;
              Object.defineProperty(window, 'DatapipelinesPreviewMount', { configurable: true,
                get: () => mount,
                set: real => { mount = function(...args) {
                  const instance = real.apply(this, args);
                  const section = args[3], block = section.closest('[data-dp-pane]').querySelector('#viz-preview-data');
                  const m = { instance, section, block, index: Number(section.dataset.vizCaseIndex), resizes: 0, frozenResizes: 0, ready: false };
                  o.mounts.push(m);
                  if (instance) {
                    instance.ready.then(() => { m.ready = true; });
                    const resize = instance.resize;
                    instance.resize = function(...a) { m.resizes++; return resize.apply(this, a); };
                  }
                  return instance;
                }; }
              });
              document.addEventListener('htmx:historyCacheHit', e => { o.hits.push(e.detail.path); });
              document.addEventListener('htmx:historyCacheMiss', () => { o.misses++; });
              document.addEventListener('htmx:historyRestore', e => { o.restores.push(e.detail.path); });
              document.addEventListener('htmx:beforeHistorySave', () => { o.mounts.forEach(m => { m.frozenResizes = m.resizes; }); });
            })();""",
        )
    }

    private fun previewObservation(path: String): Int =
        (page.evaluate("p => p.split('.').reduce((o, k) => o[k], window.__v444)", path) as Number).toInt()

    /** Fresh runtime readiness plus visible fixture output; snapshot attributes alone cannot satisfy this check. */
    private fun awaitPreviewResult(index: String) {
        page.waitForFunction(
            """i => {
              const s = document.querySelector('#viz-pane-preview section[data-viz-case-index="' + i + '"]');
              const o = window.__v444, b = document.querySelector('#viz-preview-data');
              if (!s || s.hidden || s.closest('[data-dp-pane]').hidden || !o.mounts.some(m => m.block === b && m.index === Number(i) && m.ready)) return false;
              if (i === '1') return s.querySelector('.dp-dashboard-status')?.getAttribute('data-dp-state') === 'no-data';
              const g = s.querySelector('.js-plotly-plot');
              return g && g._fullData && g._fullData.length === 2 && JSON.stringify(Array.from(g._fullData[0].y)) === '[3,5,2]' &&
                JSON.stringify(Array.from(g._fullData[1].y)) === '[4,4,4]';
            }""",
            index,
        )
    }

    private fun historyStat(path: String): Int =
        (
            page.evaluate(
                "p => p.split('.').reduce((o, k) => (o == null ? o : o[k]), window.WorkspaceHistory.stats()) || 0",
                path,
            ) as Number
        ).toInt()

    /**
     * One tab, asserted whole: the strip, the ONE visible pane, and the URL's `tab` — the arrival
     * entry keeps its own URL (no `tab=` when the page was entered on Preview).
     */
    private fun expectTab(tab: String) {
        page.waitForSelector("#viz-pane-$tab:not([hidden])")
        page.locator("#viz-tab-$tab").getAttribute("aria-selected") shouldBe "true"
        page.evaluate("() => document.querySelectorAll('.dp-ws-tab[aria-selected=\"true\"]').length") shouldBe 1
        page.evaluate("() => document.querySelectorAll('[data-dp-pane]:not([hidden])').length") shouldBe 1
        if (tab == "preview") page.url() shouldNotContain "tab=" else page.url() shouldContain "tab=$tab"
    }

    /** One tab opened by URL at the current viewport: rendered, no sideways scroll, zero CSP; two tabs are photographed. */
    private fun checkTab(
        id: String,
        tab: String,
        where: String,
        shotSuffix: String,
    ) {
        page.navigate("$baseUrl/visualizations/$id?tab=$tab")
        awaitPane(page, tab)
        if (tab == "preview") page.waitForSelector("#viz-pane-preview section[data-viz-case-index='0'][data-dp-ready='true']")
        page.waitForTimeout(SETTLE_MS)
        withClue("$tab at $where scrolls sideways") { documentOverflowsX() shouldBe false }
        withClue("CSP on $tab at $where") { drainCspViolations().shouldBeEmpty() }
        if (tab == "versions") {
            // #422 — relative in the cell, the absolute UTC stamp on `title`: never a raw ISO instant.
            val cells = page.locator("#viz-pane-versions td").allInnerTexts()
            withClue("versions cells at $where") { cells.shouldNotBeEmpty() }
            withClue("a raw ISO instant in the versions cells at $where: $cells") {
                cells.filter { ISO_INSTANT.containsMatchIn(it) }.shouldBeEmpty()
            }
            withClue("versions at $where carries no UTC title") {
                page.locator("#viz-pane-versions td span[title\$='UTC']").count() shouldBeGreaterThan 0
            }
        }
        if (tab == "preview" || tab == "versions") shot("workspace-$tab-$shotSuffix")
    }

    /** The tab's pane is visible and its lazy partial has replaced the loading note. */
    private fun awaitPane(
        target: Page,
        tab: String,
    ) {
        target.waitForSelector("#viz-pane-$tab:not([hidden])")
        target.waitForFunction("t => !document.querySelector('#viz-pane-' + t + ' > p.dp-tab-note')", tab)
    }

    private fun shot(name: String) {
        Files.createDirectories(SHOTS)
        page.screenshot(Page.ScreenshotOptions().setPath(SHOTS.resolve("$name.png")).setFullPage(false))
    }

    private companion object {
        const val DESKTOP_W = 1440
        const val DESKTOP_H = 900
        const val SETTLE_MS = 300.0
        val WIDTHS = listOf(1440 to 900, 1100 to 800, 390 to 844)
        val TABS = listOf("preview", "overview", "evidence", "used-by", "versions")
        val SHOTS = Paths.get("build", "reports", "399-visualizations")

        /** A raw ISO-8601 instant (`2026-10-01T09:00:00Z`) — the text #422 retired from the Versions cells. */
        val ISO_INSTANT = Regex("""\d{4}-\d\d-\d\dT\d\d:\d\d""")
    }
}
