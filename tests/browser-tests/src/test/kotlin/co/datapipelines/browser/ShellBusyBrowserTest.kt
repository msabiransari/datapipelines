package co.datapipelines.browser

import com.microsoft.playwright.APIResponse
import com.microsoft.playwright.Page
import com.microsoft.playwright.PlaywrightException
import com.microsoft.playwright.Route
import com.microsoft.playwright.options.WaitForSelectorState
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import org.junit.jupiter.api.Test

/**
 * 085 §D — a signal for every server trip (owner: "we need some kind of an indicator").
 * Until this round the 2px top bar showed for BOOSTED navigation only; now every htmx
 * request shows it (an in-flight COUNT, not a boolean, so a tree expand and a detail
 * load running together cannot hide it early), the originating <button> goes busy
 * (aria-disabled + pointer-events off), and a swap target still waiting after 150ms
 * gets aria-busy plus ONE skeleton row. This spec pins the four halves of that contract
 * against real htmx 2.0.10 event sequences:
 *
 *  1. **The bar is active during any request and gone after settle** — including plain
 *     partial requests (a tree expand), never only boosted ones.
 *  2. **The clicked control is non-interactive while in flight** — `aria-disabled="true"`
 *     and the shell's `.app-busy` marker class (both set by shell.js), with computed
 *     `pointer-events: none` (app.css's `button.app-busy`), all removed by the request's
 *     terminal event. The marker is deliberately NOT htmx's `.htmx-request`: htmx 2.0.10
 *     applies that class to the hx-indicator TARGET instead when the element carries
 *     hx-indicator (verified against the vendored dist, `addRequestIndicatorClasses`),
 *     and the tree leaves do. The `disabled` property is deliberately NOT used either —
 *     a really-disabled button would drop focus mid-flight.
 *  3. **The delayed skeleton** — a held (>150ms) swap marks its target aria-busy and shows
 *     `.app-target-skeleton`; a fast swap must never flash either.
 *  4. **The bar never sticks on after an abort** — htmx 2.0.10 fires NO `htmx:afterSettle`
 *     for an aborted request (verified against the vendored dist: `xhr.onabort` →
 *     `htmx:afterRequest` + `htmx:sendAbort` only), which is exactly why shell.js counts on
 *     `htmx:afterRequest` instead. The hx-sync replace abort (two leaves selected quickly)
 *     is the probe, and the abort itself is pinned through the failed-request count.
 *
 * #398: the Templates page lost its explorer (a tree beside a detail pane), so the surfaces
 * this spec drives are the ones that remain: the SIDEBAR tree's folder summaries (the partial
 * request, the busy summary), and the catalog's search and "Clear search" button (the clicked
 * BUTTON, the swap target `#template-list-wrapper`, the hx-sync replace abort). A tree leaf is
 * a link now — a full navigation, no request for the shell to mark.
 *
 * ## The throttle
 *
 * ExplorerStressBrowserTest's capture-and-hold pattern (085 §C — a handler that SLEEPS
 * would serialize the clicks behind their own requests), simplified to a FIXED 600ms
 * hold: the deadlines here are not the subject, only "longer than the 150ms skeleton
 * arm" is, and a fixed hold keeps the sequence deterministic. The sleep lives inside
 * the throttle's release, the one sanctioned wait. One hardening over §C:
 * `waitForRequest` can return with the route handler still queued (handlers run during
 * a page API call's event pump), so every release is preceded by [PartialThrottle.awaitCaptured]
 * — measured in a gate run as a level request held 30s into the click timeout when a
 * release fired before the capture. Assertions otherwise synchronize on
 * `waitForRequest`/`waitForSelector` exclusively.
 */
class ShellBusyBrowserTest : BrowserSuite() {
    // ------------------------------------------------------------------ the throttle

    /** One intercepted response, held against its fixed release deadline. */
    private class Held(
        val route: Route,
        val response: APIResponse,
        val releaseAtMillis: Long,
    )

    /** Holds every partial response for [holdMillis]; see the class KDoc for why fixed. */
    private inner class PartialThrottle(
        private val holdMillis: Long,
    ) {
        private val held = mutableListOf<Held>()
        private var captured = 0

        /**
         * Partial requests the browser reported FAILED while held — for an in-flight htmx
         * request that is always an abort (hx-sync replace, a navigation): the app cancelling
         * its own request, observed. Same measured caveat as §C: fulfilling a dead route does
         * not throw in Playwright-Java, so onRequestFailed is the only abort signal.
         */
        val failedPartials =
            java.util.concurrent.atomic
                .AtomicInteger(0)

        @Suppress("SwallowedException") // a dead request has nothing to hold; see the catch
        fun install() {
            page.onRequestFailed { req ->
                if (req.url().contains("/partials/")) failedPartials.incrementAndGet()
            }
            page.route(
                java.util.function.Predicate { url ->
                    url.contains("/partials/") || url.contains("/api/v1/templates/tree")
                },
            ) { route ->
                try {
                    val response = route.fetch()
                    held.add(Held(route, response, System.currentTimeMillis() + holdMillis))
                    captured += 1
                } catch (e: PlaywrightException) {
                    // The request died between interception and fetch — nothing to hold.
                }
            }
        }

        /**
         * Waits until the route handler has actually CAPTURED [n] requests (cumulative).
         *
         * Route handlers run during the event pump of a page API call (the §C KDoc's model),
         * and `waitForRequest` can return with the request's handler still queued: a
         * `releaseAll` called right then fulfills NOTHING and the request stays held forever
         * — measured in a gate run as `prefix=nyc/lib` held 30s into the click timeout, the
         * level never landing. One driver round trip per poll lets the queued handler run;
         * the wait is on captured state with a hard deadline, never on wall time.
         */
        fun awaitCaptured(n: Int) {
            val deadline = System.currentTimeMillis() + AWAIT_CAPTURED_TIMEOUT_MILLIS
            while (captured < n) {
                if (System.currentTimeMillis() > deadline) {
                    throw AssertionError("throttle captured $captured of $n expected partial requests")
                }
                page.evaluate("() => 0")
            }
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
            "nyc/lib/mobility/trips",
            "nyc/lib/mobility/stations",
        )

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

    /** In-page REST seeding (the §C pattern): cookie session + the dp_csrf double-submit pair. */
    private fun seedTemplates() {
        val failures =
            page.evaluate(
                """async (bodies) => {
                  const csrf = document.cookie.match(/(?:^|;\s*)dp_csrf=([^;]*)/);
                  const failures = [];
                  for (const body of bodies) {
                    const res = await fetch('/api/v1/templates', {
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
                treeNames.map { name ->
                    """{"id":"$name","type":"sql","dialect":"POSTGRES","display_name":"$name",""" +
                        """"description":"085d browser busy seed","body":"SELECT 1"}"""
                },
            ) as List<*>
        failures.shouldBeEmpty()
    }

    // ------------------------------------------------------------------ tree driving

    private val navRoot = "#nav-tree-templates"

    private val catalog = "#template-list-wrapper"

    private fun folderSummary(path: String) = "$navRoot [data-tree-key='folder:$path'] > .dp-tree-line button"

    private fun leafLink(path: String) = "$navRoot a.dp-tree-activate[title='$path']"

    /** The catalog page, its sidebar Templates branch open and its root level loaded. */
    private fun openCatalogAndSidebarTree() {
        page.navigate("$baseUrl/templates")
        page.waitForSelector(catalog)
        page.click("[data-nav-branch='templates'] [data-nav-tree-toggle]")
        page.waitForSelector(folderSummary("nyc"))
    }

    /** One catalog result row, by the template's full path (it rides on `title`, §9.4). */
    private fun catalogRow(path: String) = "$catalog a.tpl-result:has(.tpl-path[title='$path'])"

    /** Expands one folder and waits until its level request has actually fired (held or not). */
    private fun expandFolder(path: String) {
        page.waitForRequest({ req -> req.url().contains("/api/v1/templates/tree") && req.url().contains("parent=") }) {
            page.click(folderSummary(path))
        }
    }

    private fun barHidden() =
        page.waitForSelector(
            "#app-progress.active",
            Page.WaitForSelectorOptions().setState(WaitForSelectorState.HIDDEN),
        )

    // ------------------------------------------------------------------ the stresses

    @Test
    fun `every request shows the bar, the busy control and the delayed skeleton`() {
        startTrace()
        loginReadyUser("busyt")
        seedTemplates()
        openCatalogAndSidebarTree()
        val throttle = PartialThrottle(holdMillis = 600).apply { install() }
        try {
            // (1) A plain PARTIAL request (a sidebar tree expand, not a boosted navigation)
            // activates the bar, and the requesting summary carries the shell's .app-busy
            // marker — the hook the §D CSS spins the chevron on. The computed animation pins
            // the spin without relying on motion.
            expandFolder("nyc")
            // REST tree work has local progress; htmx's global busy policy still owns catalog requests.
            page.waitForSelector("$navRoot [data-tree-key='folder:nyc'] > .dp-tree-group[aria-busy=true]")
            page
                .locator("$navRoot [data-tree-key='folder:nyc'] > .dp-tree-group[aria-busy=true] > .dp-tree-status")
                .innerText() shouldMatch Regex("^Loading… \\d+ loaded$")
            throttle.awaitCaptured(1)
            throttle.releaseAll()
            page.waitForSelector(folderSummary("nyc/lib"))
            barHidden()

            // Drill to the leaves (each level captured, held and released): a leaf is a link
            // and carries no request of its own.
            expandFolder("nyc/lib")
            throttle.awaitCaptured(2)
            throttle.releaseAll()
            expandFolder("nyc/lib/mobility")
            throttle.awaitCaptured(3)
            throttle.releaseAll()
            page.waitForSelector(leafLink("nyc/lib/mobility/trips"))

            // The catalog's search with no hit ends on a "Clear search" BUTTON whose own request
            // is the clicked-control probe.
            page.fill("#template-filter-q", "zzz-no-such-template")
            throttle.awaitCaptured(4)
            throttle.releaseAll()
            val clear = "$catalog .ds-empty-actions button" // plain CSS: it is also handed to document.querySelector
            page.waitForSelector(clear)
            barHidden()
            page.click(clear)
            throttle.awaitCaptured(5)

            // (2) The clicked control is busy for the flight: the .app-busy marker AND
            // aria-disabled (both shell.js) AND computed pointer-events:none (app.css) —
            // and it is the ATTRIBUTE, never the disabled property, so focus survives.
            page.waitForSelector("$clear.app-busy[aria-disabled='true']")
            page.evaluate(
                """(sel) => {
                  const b = document.querySelector(sel);
                  return getComputedStyle(b).pointerEvents + '/' + b.disabled;
                }""",
                clear,
            ) shouldBe "none/false"

            // (3) The held (>150ms) swap marks the target aria-busy and shows ONE skeleton
            // row — the design system's family plus the app marker class.
            page.waitForSelector("$catalog[aria-busy='true']")
            page.locator("$catalog .ds-skeleton.app-target-skeleton").count() shouldBe 1

            // Settle: every marker comes off — bar, aria-busy, the skeleton, the busy class —
            // and the real content lands (the button went with the swapped-out empty state).
            throttle.releaseAll()
            page.waitForSelector(catalogRow("nyc/lib/mobility/trips"))
            barHidden()
            page.waitForSelector("$catalog[aria-busy='true']", Page.WaitForSelectorOptions().setState(WaitForSelectorState.HIDDEN))
            page.locator(".app-target-skeleton").count() shouldBe 0
            page.locator(".app-busy").count() shouldBe 0
        } finally {
            throttle.releaseAll()
        }
    }

    /**
     * The owner's document scrollbar (2026-09-13, `/dashboard`): a boosted navigation's htmx
     * target is `<body>` until shell.js retargets it at beforeSwap, so a navigation still in
     * flight after 150ms armed the skeleton row ON BODY — outside the 100dvh shell, the one
     * place that can grow the document. htmx then snapshotted the page for history with the
     * row in it (the snapshot is taken during the swap, before afterRequest removes the live
     * one), and Back restored the row as static markup no tracker owned. Held boosted GET,
     * settle, Back: no skeleton anywhere, no body-level busy mark, and the document exactly
     * the viewport's height — the same measurement AppShellBrowserTest's guard makes on a
     * plain load, taken here on the path only a history restore walks.
     */
    @Test
    fun `a slow boosted navigation leaves no skeleton behind - not live, not in the history snapshot`() {
        startTrace()
        loginReadyUser("orphan")
        page.navigate("$baseUrl/dashboard")
        page.waitForSelector("a.app-nav-link[href='/pipelines']")
        val hold = BoostHold("/pipelines").apply { install() }
        try {
            page.waitForRequest({ req -> req.url().endsWith("/pipelines") && req.headers()["hx-request"] == "true" }) {
                page.click("a.app-nav-link[href='/pipelines']")
            }
            hold.awaitCaptured(1)
            // Past the arm: the skeleton exists, and it is INSIDE #app-main, not on body.
            page.waitForSelector(".app-target-skeleton")
            page.locator("#app-main > .app-target-skeleton").count() shouldBe 1
            page.locator("body > .app-target-skeleton").count() shouldBe 0
            hold.release()
            page.waitForURL("**/pipelines")
            barHidden()
            page.locator(".app-target-skeleton").count() shouldBe 0

            page.goBack()
            page.waitForURL("**/dashboard")
            page.waitForSelector("a.app-nav-link[href='/pipelines']")
            page.locator(".app-target-skeleton").count() shouldBe 0
            page.locator("body[aria-busy='true']").count() shouldBe 0
            documentOverflow() shouldBe 0L
        } finally {
            hold.release()
        }
    }

    /** How many pixels the DOCUMENT is taller than the viewport — 0 on a correct page. */
    private fun documentOverflow(): Long =
        (page.evaluate("() => document.documentElement.scrollHeight - document.documentElement.clientHeight") as Number).toLong()

    /** Holds the boosted document GET for one section until [release] — ShellFeelBrowserTest's throttle. */
    private inner class BoostHold(
        private val section: String,
    ) {
        private val held = mutableListOf<Pair<Route, APIResponse>>()

        @Volatile
        private var captured = 0

        @Suppress("SwallowedException") // a dead request has nothing to hold
        fun install() {
            page.route("**$section") { route ->
                if (route.request().headers()["hx-request"] != "true") {
                    route.resume()
                    return@route
                }
                try {
                    held.add(route to route.fetch())
                    captured += 1
                } catch (e: PlaywrightException) {
                    // Died between interception and fetch — nothing to hold.
                }
            }
        }

        fun awaitCaptured(n: Int) {
            val deadline = System.currentTimeMillis() + AWAIT_CAPTURED_TIMEOUT_MILLIS
            while (captured < n) {
                if (System.currentTimeMillis() > deadline) throw AssertionError("hold captured $captured of $n boosted GETs")
                page.evaluate("() => 0")
            }
        }

        @Suppress("SwallowedException") // already handled; nothing left to do
        fun release() {
            val batch = held.toList()
            held.clear()
            for ((route, response) in batch) {
                try {
                    route.fulfill(Route.FulfillOptions().setResponse(response))
                } catch (e: PlaywrightException) {
                    // Already handled.
                }
            }
        }
    }

    @Test
    fun `a fast swap never flashes the skeleton`() {
        startTrace()
        loginReadyUser("busyf")
        seedTemplates()
        // No throttle: the in-process server answers in milliseconds, far under the 150ms arm.
        openCatalogAndSidebarTree()
        page.click(folderSummary("nyc"))
        page.waitForSelector(folderSummary("nyc/lib"))
        page.click(folderSummary("nyc/lib"))
        page.waitForSelector(folderSummary("nyc/lib/mobility"))
        page.click(folderSummary("nyc/lib/mobility"))
        page.waitForSelector(leafLink("nyc/lib/mobility/trips"))

        page.fill("#template-filter-q", "mobility/trips")
        page.waitForSelector(catalogRow("nyc/lib/mobility/trips"))
        // The whole point of the 150ms arm: fast swaps pay nothing. After settle the target
        // carries neither the busy marker nor a skeleton — and with the server this fast the
        // timer can never have fired.
        page.locator(".app-target-skeleton").count() shouldBe 0
        page.evaluate("() => document.getElementById('template-list-wrapper').hasAttribute('aria-busy')") shouldBe false
        barHidden()
    }

    @Test
    fun `the bar never sticks on after an aborted request`() {
        startTrace()
        loginReadyUser("busya")
        seedTemplates()
        page.navigate("$baseUrl/templates")
        page.waitForSelector(catalog)
        val throttle = PartialThrottle(holdMillis = 600).apply { install() }
        try {
            // Search A held; its skeleton and busy markers arm. Then search B:
            // hx-sync="this:replace" on the search box ABORTS A's in-flight request.
            val failuresBefore = throttle.failedPartials.get()
            page.waitForRequest({ req -> req.url().contains("/partials/templates") && req.url().contains("q=trips") }) {
                page.fill("#template-filter-q", "trips")
            }
            throttle.awaitCaptured(1)
            page.waitForSelector("$catalog[aria-busy='true']")
            page.waitForRequest({ req -> req.url().contains("/partials/templates") && req.url().contains("q=stations") }) {
                page.fill("#template-filter-q", "stations")
            }
            throttle.awaitCaptured(2)

            // (4) The abort's effects, read once B is in flight. htmx aborts A BEFORE it sends B,
            // so A's terminal event has already torn A's busy state down (bar, aria-busy,
            // skeleton) and B's begin re-arms them: the bar is active again, the target gets its
            // aria-busy after B's own 150ms, and there is exactly ONE skeleton — A's did not
            // outlive its request and B did not stack a second one on top of it.
            page.waitForSelector("#app-progress.active")
            page.waitForSelector("$catalog[aria-busy='true']")
            page.locator("$catalog .app-target-skeleton").count() shouldBe 1

            throttle.releaseAll()
            page.waitForSelector(catalogRow("nyc/lib/mobility/stations"))
            page.locator("$catalog a.tpl-result").count() shouldBe 1

            // The abort itself, pinned: A's held request FAILED (cancelled by the app via
            // hx-sync replace), not merely ordered behind B's. Asserted after the release —
            // onRequestFailed delivery is only guaranteed settled by then (the §C lesson).
            throttle.failedPartials.get() shouldBeGreaterThanOrEqual (failuresBefore + 1)

            // The terminal state is fully clean: no stuck bar, no orphaned skeleton or
            // aria-busy, no control left marked busy (A's abort cleaned its half; B's settle
            // cleaned the rest).
            barHidden()
            page.locator(".app-target-skeleton").count() shouldBe 0
            page.evaluate("() => document.getElementById('template-list-wrapper').hasAttribute('aria-busy')") shouldBe false
            page.locator(".app-busy").count() shouldBe 0
        } finally {
            throttle.releaseAll()
        }
    }

    private companion object {
        /** awaitCaptured's hard deadline — a capture that never comes is a test bug, named. */
        const val AWAIT_CAPTURED_TIMEOUT_MILLIS = 10_000L
    }
}
