package co.datapipelines.browser

import com.microsoft.playwright.Request
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.function.Consumer

/**
 * #591 — a catalog's address bar always describes the list on screen, in real Chromium, for every
 * catalog that offers `lifecycle`. Each family's partial answers `HX-Replace-Url` with the URL of
 * the list it rendered (web `CatalogUrl`), so:
 *
 * - arriving at `?lifecycle=draft` and changing the select rewrites the URL; a reload renders the
 *   same list and the same select (the issue's acceptance case), and All leaves the bare path;
 * - the search box writes `?q=` after its 300 ms debounce, beside the lifecycle it carries;
 * - a change is ONE request — the partial — and nothing else;
 * - the URL is REPLACED, never pushed: Back from the catalog returns to the page before it, however
 *   many filter changes happened on it;
 * - Back from an object opened off a filtered list restores that list and those controls: the
 *   catalogs carry `hx-history="false"` (owner ruling 2026-10-21), so htmx never snapshots a catalog
 *   — a snapshot keeps the controls' rendered attributes, not their live values — and Back fetches
 *   the page at its URL, where the server renders both from the same parameters.
 *
 * Every case seeds one DRAFT object of its family in a fresh workspace: `draft` and All show it,
 * `released` shows the filtered empty state, so each step's list is distinguishable by its count.
 */
class CatalogUrlBrowserTest : VisualizationBrowserSuite() {
    /** One catalog: its page, its controls, its swap root, its partial, and how a draft of it is made. */
    enum class Family(
        val path: String,
        val qId: String,
        val lifecycleId: String,
        val rootId: String,
        val partial: String,
        val folder: String,
    ) {
        PIPELINES("/pipelines", "pipeline-filter-q", "pipeline-filter-lifecycle", "pipeline-list-wrapper", "/partials/pipelines", "flows"),
        TEMPLATES("/templates", "template-filter-q", "template-filter-lifecycle", "template-list-wrapper", "/partials/templates", "sql"),
        DASHBOARDS(
            "/dashboards",
            "dashboard-filter-q",
            "dashboard-filter-lifecycle",
            "dash-list-wrapper",
            "/partials/dashboards",
            "boards",
        ),
        VISUALIZATIONS(
            "/visualizations",
            "visualization-filter-q",
            "visualization-filter-lifecycle",
            "viz-list-wrapper",
            "/partials/visualizations",
            "charts",
        ),
        PARAMETER_SETS(
            "/parameter-sets",
            "parameter-set-filter-q",
            "parameter-set-filter-lifecycle",
            "parameter-set-list-wrapper",
            "/partials/parameter-sets/tree",
            "parameters",
        ),
    }

    /** A fresh workspace holding one draft of [family]; returns the draft's name. */
    private fun seedDraft(family: Family): String {
        val name = ready("cat591") + "/" + family.folder + "/one"
        val body =
            when (family) {
                Family.PIPELINES -> {
                    """{"name":"$name","display_name":"one","nodes":[{"id":"n1","type":"CALCULATOR","kind":"fiscal_quarter",""" +
                        """"context_key":"run_q_n1","inputs":{"date":"${'$'}current_date",""" +
                        """"fiscal_start":"${'$'}org_fiscal_start_date"}}]}"""
                }

                Family.TEMPLATES -> {
                    """{"id":"$name","type":"sql","dialect":"POSTGRES","display_name":"one",""" +
                        """"description":"#591 fixture","body":"SELECT 1"}"""
                }

                Family.DASHBOARDS -> {
                    """{"name":"$name","display_name":"one","visualizations":[],"layout":{"columns":12,"grid":[]}}"""
                }

                Family.VISUALIZATIONS -> {
                    null
                }

                Family.PARAMETER_SETS -> {
                    """{"name":"$name","display_name":"one","description":"#591 fixture","parameters":[""" +
                        """{"name":"kind","label":"Kind","type":"STRING","kind":"SELECT","cardinality":"SINGLE","required":true,""" +
                        """"source":{"constants":[{"value":"a","display_value":"A","is_default":true}]},""" +
                        """"presentation":{"control":"dropdown"}}]}"""
                }
            }
        if (body == null) createVisualization(name) else api("POST", "/api/v1" + family.path, body)
        return name
    }

    private fun url(
        family: Family,
        query: String = "",
    ) = baseUrl + family.path + query

    /** [request] must be a GET of [family]'s partial; its query, decoded. */
    private fun partialQuery(
        family: Family,
        request: String,
    ): Map<String, String> {
        request.substringBefore("?") shouldBe "GET ${family.partial}"
        return request.substringAfter("?", "").split("&").filter { it.isNotEmpty() }.associate {
            it.substringBefore("=") to URLDecoder.decode(it.substringAfter("=", ""), StandardCharsets.UTF_8)
        }
    }

    private fun rows(family: Family) = page.locator("#${family.rootId} a.tpl-result").count()

    private fun lifecycle(family: Family) = page.locator("#${family.lifecycleId}").inputValue()

    private fun search(family: Family) = page.locator("#${family.qId}").inputValue()

    private fun open(
        family: Family,
        query: String,
    ) {
        page.navigate(url(family, query))
        page.waitForSelector("#${family.rootId}")
        page.waitForFunction("() => !!window.htmx")
    }

    /**
     * Runs [change] and waits until the address bar reads [expected] and the swap has settled;
     * returns every request the page issued meanwhile. htmx replaces the URL inside the swap, so the
     * new list is in place when the URL reads [expected].
     */
    private fun changing(
        expected: String,
        change: () -> Unit,
    ): List<String> {
        val requests = mutableListOf<String>()
        // Only what the page REQUESTS: a static asset the swapped markup references (the icon
        // sprite a row's glyph uses) is a resource load the base page would make too, not a call.
        val record =
            Consumer<Request> {
                if (it.resourceType() == "xhr" || it.resourceType() == "fetch") {
                    requests += "${it.method()} ${it.url().removePrefix(baseUrl)}"
                }
            }
        page.evaluate("() => { window.__settled = 0; document.body.addEventListener('htmx:afterSettle', () => window.__settled++); }")
        page.onRequest(record)
        try {
            change()
            named(page, "the address bar reading $expected") { page.waitForURL({ it == expected }) }
            page.waitForFunction("() => window.__settled > 0")
        } finally {
            page.offRequest(record)
        }
        return requests
    }

    @ParameterizedTest
    @EnumSource(Family::class)
    fun `the lifecycle select rewrites the URL, and a reload renders the same list and select`(family: Family) {
        startTrace()
        seedDraft(family)
        open(family, "?lifecycle=draft")
        rows(family) shouldBe 1
        lifecycle(family) shouldBe "draft"

        val released = url(family, "?lifecycle=released")
        val requests = changing(released) { page.selectOption("#${family.lifecycleId}", "released") }
        withClue("one filter change is one request, the partial: $requests") {
            requests.size shouldBe 1
            partialQuery(family, requests.single())["lifecycle"] shouldBe "released"
        }
        rows(family) shouldBe 0

        page.reload()
        page.waitForSelector("#${family.rootId}")
        page.url() shouldBe released
        lifecycle(family) shouldBe "released"
        rows(family) shouldBe 0

        // All is the bare path: an unfiltered catalog has no query at all.
        changing(url(family)) { page.selectOption("#${family.lifecycleId}", "") }
        rows(family) shouldBe 1
        page.reload()
        page.waitForSelector("#${family.rootId}")
        page.url() shouldBe url(family)
        lifecycle(family) shouldBe ""
        rows(family) shouldBe 1
    }

    @ParameterizedTest
    @EnumSource(Family::class)
    fun `the search box writes q beside the lifecycle after its debounce, and a reload keeps both`(family: Family) {
        startTrace()
        seedDraft(family)
        open(family, "?lifecycle=draft")

        val none = url(family, "?q=zz-none-591&lifecycle=draft")
        val requests = changing(none) { page.fill("#${family.qId}", "zz-none-591") }
        withClue("one debounced search is one request, the partial: $requests") {
            requests.size shouldBe 1
            partialQuery(family, requests.single()).filterKeys { it == "q" || it == "lifecycle" } shouldBe
                mapOf("q" to "zz-none-591", "lifecycle" to "draft")
        }
        rows(family) shouldBe 0

        page.reload()
        page.waitForSelector("#${family.rootId}")
        page.url() shouldBe none
        search(family) shouldBe "zz-none-591"
        lifecycle(family) shouldBe "draft"
        rows(family) shouldBe 0

        changing(url(family, "?q=one&lifecycle=draft")) { page.fill("#${family.qId}", "one") }
        rows(family) shouldBe 1
    }

    @ParameterizedTest
    @EnumSource(Family::class)
    fun `filter changes replace the history entry - Back leaves the catalog for the page before it`(family: Family) {
        startTrace()
        seedDraft(family)
        page.navigate("$baseUrl/dashboard")
        open(family, "?lifecycle=draft")
        val before = (page.evaluate("() => history.length") as Number).toInt()

        changing(url(family, "?lifecycle=released")) { page.selectOption("#${family.lifecycleId}", "released") }
        changing(url(family)) { page.selectOption("#${family.lifecycleId}", "") }
        changing(url(family, "?q=one")) { page.fill("#${family.qId}", "one") }
        (page.evaluate("() => history.length") as Number).toInt() shouldBe before

        page.goBack()
        named(page, "Back to the dashboard") { page.waitForURL("$baseUrl/dashboard") }
    }

    @ParameterizedTest
    @EnumSource(Family::class)
    fun `Back from an object opened off a filtered list restores that list and its controls`(family: Family) {
        startTrace()
        val name = seedDraft(family)
        open(family, "?lifecycle=released")
        rows(family) shouldBe 0

        val filtered = url(family, "?q=one")
        changing(url(family)) { page.selectOption("#${family.lifecycleId}", "") }
        changing(filtered) { page.fill("#${family.qId}", "one") }
        rows(family) shouldBe 1

        page.locator("#${family.rootId} a.tpl-result").click()
        named(page, "leaving the catalog for $name") { page.waitForURL({ URI(it).path != family.path }) }

        page.goBack()
        named(page, "Back to $filtered") { page.waitForURL({ it == filtered }) }
        page.waitForSelector("#${family.rootId}")
        rows(family) shouldBe 1
        search(family) shouldBe "one"
        lifecycle(family) shouldBe ""
    }

    /**
     * A.3 of the brief: htmx 2.0.10 snapshots the page for history before it replaces the URL
     * (`if (historyUpdate.type) saveCurrentPageToHistory()`), which would run the shell's
     * `htmx:beforeHistorySave` cleanups on the LIVE catalog at every filter change — the trap
     * shell.js's header note 9 records. Measured before the catalogs opted out (lane 591): one
     * save per change, nothing outside the list changed. Since `hx-history="false"` (owner ruling
     * 2026-10-21) the save does not run at all; this pins both — zero saves, and no inline style
     * or width outside the swapped list differs (the indicator's width animates, so only its
     * style attribute is compared).
     */
    @ParameterizedTest
    @EnumSource(Family::class)
    fun `a filter change runs no history save and leaves the live page outside the list untouched`(family: Family) {
        startTrace()
        seedDraft(family)
        open(family, "?lifecycle=draft")
        page.evaluate("() => { window.__saves = 0; document.body.addEventListener('htmx:beforeHistorySave', () => window.__saves++); }")
        val outside =
            """(rootId) => [...document.querySelectorAll('#app-main *')]
              .filter((el) => !el.closest('#' + rootId))
              .map((el) => (el.id || el.tagName) + '|' + (el.getAttribute('style') || '') + '|' +
                (el.classList.contains('htmx-indicator') ? 'indicator' : Math.round(el.getBoundingClientRect().width)))
              .join('\n')"""
        val before = page.evaluate(outside, family.rootId) as String

        changing(url(family, "?lifecycle=released")) { page.selectOption("#${family.lifecycleId}", "released") }

        (page.evaluate("() => window.__saves") as Number).toInt() shouldBe 0
        page.evaluate(outside, family.rootId) as String shouldBe before
    }
}
