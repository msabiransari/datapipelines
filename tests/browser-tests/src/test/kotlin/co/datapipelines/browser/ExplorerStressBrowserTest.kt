package co.datapipelines.browser

import com.microsoft.playwright.APIResponse
import com.microsoft.playwright.Page
import com.microsoft.playwright.PlaywrightException
import com.microsoft.playwright.Route
import com.microsoft.playwright.options.WaitUntilState
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.util.Random

/** Real REST tree hammer under held responses: cancellation, parent ownership, complete levels and fresh reload. */
class ExplorerStressBrowserTest : BrowserSuite() {
    // ------------------------------------------------------------------ the throttle

    /** One intercepted response, held against its seeded release deadline. */
    private class Held(
        val route: Route,
        val response: APIResponse,
        val releaseAtMillis: Long,
    )

    /**
     * Holds every intercepted partial response against a seeded deadline; see the class KDoc
     * for why capture-and-release instead of sleeping inside the handler.
     */
    private inner class PartialThrottle(
        seed: Long,
    ) {
        private val rng = Random(seed)
        private val held = mutableListOf<Held>()

        /**
         * Partial requests the browser reported FAILED while held — for an in-flight htmx
         * request that is always an abort (hx-sync replace, a boosted navigation, a reload):
         * the app cancelling its own request, observed. route.fulfill on such a request does
         * NOT throw in Playwright-Java (measured — the response is accepted and discarded),
         * so the abort cannot be pinned at release time; onRequestFailed is the signal.
         */
        val failedPartials =
            java.util.concurrent.atomic
                .AtomicInteger(0)

        @Suppress("SwallowedException") // a dead request has nothing to hold; see the catch
        fun install() {
            page.onRequestFailed { req ->
                if (req.url().contains("/tree")) failedPartials.incrementAndGet()
            }
            page.route("**/api/v1/*/tree**") { route ->
                try {
                    val response = route.fetch()
                    held.add(Held(route, response, System.currentTimeMillis() + 400 + rng.nextInt(801)))
                } catch (e: PlaywrightException) {
                    // The request died between interception and fetch (a reload landing in
                    // between) — nothing to hold; the browser has already moved on.
                }
            }
        }

        /** Removes the route: requests after this call reach the server unheld. */
        fun uninstall() {
            page.unroute("**/api/v1/*/tree**")
        }

        /**
         * #350: releases held responses until [done] holds — for a SEQUENCE of requests where
         * each is sent only after the previous one lands (the sidebar's incremental restore:
         * root, then one folder level per request). One driver round trip per pass lets queued
         * route handlers capture the next request; bounded, never a bare sleep.
         */
        fun releaseUntil(done: () -> Boolean) {
            val deadline = System.currentTimeMillis() + RELEASE_UNTIL_MILLIS
            while (!done()) {
                check(System.currentTimeMillis() < deadline) { "the held sequence never settled" }
                releaseAll()
                page.evaluate("() => 0")
            }
            releaseAll()
        }

        /** Fulfills everything held, in deadline order; the ONLY place a sleep is allowed. */
        @Suppress("SwallowedException") // the abort is the counted signal; see the catch
        fun releaseAll() {
            val batch = held.sortedBy { it.releaseAtMillis }
            held.clear()
            for (h in batch) {
                val wait = h.releaseAtMillis - System.currentTimeMillis()
                if (wait > 0) Thread.sleep(wait)
                try {
                    h.route.fulfill(Route.FulfillOptions().setResponse(h.response))
                } catch (e: PlaywrightException) {
                    // Already handled/aborted — the corresponding onRequestFailed was counted.
                }
            }
        }
    }

    // ------------------------------------------------------------------ fixtures

    private val treeNames =
        listOf(
            "nyc/overview",
            "nyc/lib/agg_daily",
            "nyc/lib/agg_weekly",
            "nyc/lib/mobility/trips",
            "nyc/lib/mobility/stations",
            "nyc/lib/mobility/routes",
            "nyc/hr/roster",
            "trade/ledger",
            "trade/settlement",
            "trade/root/daily",
        ) + (0..29).map { "wide/item_%02d".format(it) }

    private fun loginReadyUser(slug: String) {
        val user =
            seedLocalUser(
                uniqueEmail("$slug-" + generatedPassword("u").take(8)),
                generatedPassword("pw"),
                mustChange = false,
            )
        login(user.email, user.oneTimePassword)
        page.waitForURL("**/dashboard")
        createWorkspace(slug + "ws-" + generatedPassword("w").take(8).lowercase())
    }

    /** In-page REST seeding (the PipelineEditorDetails pattern): cookie session + dp_csrf. */
    private fun postJson(
        url: String,
        bodies: List<String>,
    ) {
        val failures =
            page.evaluate(
                """async (args) => {
                  const csrf = document.cookie.match(/(?:^|;\s*)dp_csrf=([^;]*)/);
                  const failures = [];
                  for (const body of args.bodies) {
                    const res = await fetch(args.url, {
                      method: 'POST',
                      credentials: 'same-origin',
                      headers: {
                        'Content-Type': 'application/json',
                        'DP-CSRF-Token': csrf ? decodeURIComponent(csrf[1]) : '',
                      },
                      body,
                    });
                    if (res.status !== 201) failures.push(res.status + ': ' + body.slice(0, 80));
                  }
                  return failures;
                }""",
                mapOf("url" to url, "bodies" to bodies),
            ) as List<*>
        failures.shouldBeEmpty()
    }

    private fun seedTemplates() =
        postJson(
            "/api/v1/templates",
            treeNames.map { name ->
                """{"id":"$name","type":"sql","dialect":"POSTGRES","display_name":"$name",""" +
                    """"description":"085c browser stress seed","body":"SELECT 1"}"""
            },
        )

    private fun seedPipelines() =
        postJson(
            "/api/v1/pipelines",
            treeNames.map { name ->
                """{"name":"$name","display_name":"$name","nodes":[{"id":"fq","type":"CALCULATOR",""" +
                    """"kind":"fiscal_quarter","context_key":"run_fiscal_quarter",""" +
                    """"inputs":{"date":"${'$'}current_date","fiscal_start":"${'$'}org_fiscal_start_date"}}]}"""
            },
        )

    private var tree = "#nav-tree-templates"

    private fun folder(path: String) = "$tree [data-tree-key='folder:$path']"

    private fun summary(path: String) = "${folder(path)} > .dp-tree-line button"

    private fun leaf(path: String) = "$tree a.dp-tree-activate[title='$path']"

    private fun level(path: String) = "${folder(path)} > .dp-tree-group"

    private fun expand(path: String) {
        page.waitForRequest({ request -> request.url().contains("/tree?") && request.url().contains("parent=") }) {
            page.click(summary(path))
        }
    }

    private fun uniqueRows() {
        page.evaluate(
            """selector => {
                const keys=Array.from(document.querySelectorAll(selector+' [data-tree-key]')).map(row=>row.dataset.treeKey);
                return keys.length===new Set(keys).size;
            }""",
            tree,
        ) shouldBe true
    }

    @Test
    fun `the expand-collapse hammer never strands a level misplaces one or doubles a row`() {
        startTrace()
        loginReadyUser("tplx")
        seedTemplates()
        val throttle = PartialThrottle(seed = 85_071).apply { install() }
        try {
            page.navigate("$baseUrl/dashboard", Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED))
            page.click("[data-nav-branch=templates] [data-nav-tree-toggle]")
            throttle.releaseUntil { page.locator(summary("nyc")).count() > 0 }
            listOf("nyc", "trade", "wide").forEach { expand(it) }
            repeat(20) {
                listOf("nyc", "trade", "wide").forEach { path ->
                    page.click(summary(path))
                    page.click(summary(path))
                }
            }
            throttle.releaseUntil { page.locator(leaf("wide/item_29")).count() > 0 }
            page.waitForSelector(leaf("nyc/overview"))
            page.locator(level("wide") + " > [role=treeitem]").count() shouldBe 30
            page.locator(level("nyc") + " [data-tree-key='folder:nyc/lib']").count() shouldBe 1
            page.locator(level("nyc") + " a[title='trade/ledger']").count() shouldBe 0
            uniqueRows()
            expand("nyc/lib")
            page.click(summary("nyc"))
            page.click(summary("nyc"))
            throttle.releaseUntil { page.locator(summary("nyc/lib/mobility")).count() > 0 }
            page.locator(level("nyc/lib") + " a[title='nyc/lib/agg_daily']").count() shouldBe 1
            page.locator(level("nyc/lib") + " a[title='nyc/hr/roster']").count() shouldBe 0
            expand("nyc/lib/mobility")
            throttle.releaseUntil { page.locator(leaf("nyc/lib/mobility/trips")).count() > 0 }
            page.locator(level("nyc/lib/mobility") + " > [role=treeitem]").count() shouldBe 3
            page.locator("$tree .dp-tree-group[aria-busy=true]").count() shouldBe 0
            uniqueRows()
        } finally {
            throttle.releaseAll()
        }
    }

    @Test
    fun `search clear boosted navigation and a mid-expand reload retain truthful tree state`() {
        startTrace()
        loginReadyUser("plpx")
        seedPipelines()
        seedTemplates()
        tree = "#nav-tree-pipelines"
        val throttle = PartialThrottle(seed = 85_073).apply { install() }
        try {
            page.navigate("$baseUrl/dashboard", Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED))
            page.click("[data-nav-branch=pipelines] [data-nav-tree-toggle]")
            throttle.releaseUntil { page.locator(summary("nyc")).count() > 0 }
            expand("nyc")
            throttle.releaseUntil { page.locator(summary("nyc/lib")).count() > 0 }
            page.waitForRequest({ it.url().contains("/tree/search") }) {
                page.fill("$tree input[type=search]", "mob")
            }
            page.fill("$tree input[type=search]", "")
            throttle.releaseAll()
            page.locator("$tree [aria-expanded=true]").count() shouldBe 0
            page.locator("$tree .dp-tree-rows > [role=treeitem]").count() shouldBe 3
            page.evaluate("() => window.__stressRail=document.getElementById('app-rail')")
            page.click(summary("nyc"))
            expand("nyc/lib")
            page.click("a.app-nav-link[data-nav-section='/templates']")
            page.waitForSelector("#template-list-wrapper")
            throttle.releaseUntil { page.locator(summary("nyc/lib/mobility")).count() > 0 }
            page.evaluate("() => window.__stressRail===document.getElementById('app-rail')") shouldBe true
            uniqueRows()
            expand("nyc/hr")
            page.reload()
            throttle.releaseUntil { page.locator(summary("nyc")).count() > 0 }
            page.locator("$tree [aria-expanded=true]").count() shouldBe 0
            page.locator(leaf("nyc/hr/roster")).count() shouldBe 0
            uniqueRows()
            throttle.uninstall()
            tree = "#nav-tree-templates"
            page.click("[data-nav-branch=templates] [data-nav-tree-toggle]")
            page.waitForSelector(summary("nyc"))
            page.fill("$tree input[type=search]", "mob")
            page.waitForSelector(leaf("nyc/lib/mobility/trips"))
            page.locator("$tree [aria-expanded=true]").count() shouldBe 3
            page.click("$tree button[aria-label='Clear search']")
            page.locator("$tree [aria-expanded=true]").count() shouldBe 0
            uniqueRows()
        } finally {
            throttle.releaseAll()
        }
    }

    private companion object {
        const val RELEASE_UNTIL_MILLIS = 30_000L
    }
}
