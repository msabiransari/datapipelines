package co.datapipelines.browser

import com.microsoft.playwright.APIResponse
import com.microsoft.playwright.Page
import com.microsoft.playwright.PlaywrightException
import com.microsoft.playwright.Route
import com.microsoft.playwright.options.WaitForSelectorState
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
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
 *  3. **The delayed skeleton** — a held (>150ms) detail swap marks `#template-detail`
 *     aria-busy and shows `.app-target-skeleton`; a fast swap must never flash either.
 *  4. **The bar never sticks on after an abort** — htmx 2.0.10 fires NO `htmx:afterSettle`
 *     for an aborted request (verified against the vendored dist: `xhr.onabort` →
 *     `htmx:afterRequest` + `htmx:sendAbort` only), which is exactly why shell.js counts on
 *     `htmx:afterRequest` instead. The hx-sync replace abort (two leaves selected quickly)
 *     is the probe, and the abort itself is pinned through the failed-request count.
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
            page.route("**/partials/**") { route ->
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

    private fun folderSummary(path: String) = "summary.tpl-summary:has(span.tpl-label[title='$path'])"

    private fun leafButton(path: String) = "button.tpl-leaf:has(span.tpl-label[title='$path'])"

    /** Expands one folder and waits until its level request has actually fired (held or not). */
    private fun expandFolder(path: String) {
        page.waitForRequest({ req -> req.url().contains("/partials/") && req.url().contains("prefix=") }) {
            page.click(folderSummary(path))
        }
    }

    /** Drills nyc → nyc/lib → nyc/lib/mobility, awaiting the capture and releasing per level. */
    private fun drillToMobility(throttle: PartialThrottle) {
        expandFolder("nyc")
        throttle.awaitCaptured(1)
        throttle.releaseAll()
        expandFolder("nyc/lib")
        throttle.awaitCaptured(2)
        throttle.releaseAll()
        expandFolder("nyc/lib/mobility")
        throttle.awaitCaptured(3)
        throttle.releaseAll()
        page.waitForSelector(leafButton("nyc/lib/mobility/trips"))
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
        val throttle = PartialThrottle(holdMillis = 600).apply { install() }
        try {
            page.navigate("$baseUrl/templates")
            page.waitForSelector(folderSummary("nyc"))

            // (1) A plain PARTIAL request (a tree expand, not a boosted navigation) activates
            // the bar, and the requesting summary carries the shell's .app-busy marker —
            // the hook the §D CSS spins the chevron on. The computed animation pins the spin
            // without relying on motion.
            expandFolder("nyc")
            page.waitForSelector("#app-progress.active")
            page.waitForSelector("summary.tpl-summary.app-busy")
            page.evaluate(
                "() => getComputedStyle(document.querySelector('summary.tpl-summary.app-busy > .tpl-chevron')).animationName",
            ) shouldBe "ds-spin"
            throttle.awaitCaptured(1)
            throttle.releaseAll()
            page.waitForSelector(folderSummary("nyc/lib"))
            barHidden()

            // Drill to the leaves (each level captured, held and released), then select
            // trips held.
            expandFolder("nyc/lib")
            throttle.awaitCaptured(2)
            throttle.releaseAll()
            expandFolder("nyc/lib/mobility")
            throttle.awaitCaptured(3)
            throttle.releaseAll()
            page.waitForSelector(leafButton("nyc/lib/mobility/trips"))

            page.waitForRequest({ req -> req.url().contains("/partials/templates/versions") }) {
                page.click(leafButton("nyc/lib/mobility/trips"))
            }
            throttle.awaitCaptured(4)

            // (2) The clicked control is busy for the flight: the .app-busy marker AND
            // aria-disabled (both shell.js) AND computed pointer-events:none (app.css) —
            // and it is the ATTRIBUTE, never the disabled property, so focus survives.
            page.waitForSelector(leafButton("nyc/lib/mobility/trips") + ".app-busy[aria-disabled='true']")
            page.evaluate(
                """(sel) => {
                  const b = document.querySelector(sel);
                  return getComputedStyle(b).pointerEvents + '/' + b.disabled;
                }""",
                leafButton("nyc/lib/mobility/trips"),
            ) shouldBe "none/false"

            // (3) The held (>150ms) swap marks the detail pane aria-busy and shows ONE
            // skeleton row — the design system's family plus the app marker class.
            page.waitForSelector("#template-detail[aria-busy='true']")
            page.locator("#template-detail .ds-skeleton.app-target-skeleton").count() shouldBe 1

            // Settle: every marker comes off — bar, aria-disabled, aria-busy, the skeleton —
            // and the real content lands.
            throttle.releaseAll()
            page.waitForSelector("#template-detail h2.tplx-detail-title")
            page.locator("#template-detail h2.tplx-detail-title").getAttribute("title") shouldBe "nyc/lib/mobility/trips"
            barHidden()
            page.waitForSelector("#template-detail[aria-busy='true']", Page.WaitForSelectorOptions().setState(WaitForSelectorState.HIDDEN))
            page.locator(".app-target-skeleton").count() shouldBe 0
            page.evaluate(
                """(sel) => {
                  const b = document.querySelector(sel);
                  return b.hasAttribute('aria-disabled') + '/' + b.classList.contains('app-busy');
                }""",
                leafButton("nyc/lib/mobility/trips"),
            ) shouldBe "false/false"
        } finally {
            throttle.releaseAll()
        }
    }

    @Test
    fun `a fast swap never flashes the skeleton`() {
        startTrace()
        loginReadyUser("busyf")
        seedTemplates()
        // No throttle: the in-process server answers in milliseconds, far under the 150ms arm.
        page.navigate("$baseUrl/templates")
        page.waitForSelector(folderSummary("nyc"))
        page.click(folderSummary("nyc"))
        page.waitForSelector(folderSummary("nyc/lib"))
        page.click(folderSummary("nyc/lib"))
        page.waitForSelector(folderSummary("nyc/lib/mobility"))
        page.click(folderSummary("nyc/lib/mobility"))
        page.waitForSelector(leafButton("nyc/lib/mobility/trips"))

        page.click(leafButton("nyc/lib/mobility/trips"))
        page.waitForSelector("#template-detail h2.tplx-detail-title")
        page.locator("#template-detail h2.tplx-detail-title").getAttribute("title") shouldBe "nyc/lib/mobility/trips"
        // The whole point of the 150ms arm: fast swaps pay nothing. After settle the pane
        // carries neither the busy marker nor a skeleton — and with the server this fast the
        // timer can never have fired.
        page.locator(".app-target-skeleton").count() shouldBe 0
        page.evaluate("() => document.getElementById('template-detail').hasAttribute('aria-busy')") shouldBe false
        barHidden()
    }

    @Test
    fun `the bar never sticks on after an aborted request`() {
        startTrace()
        loginReadyUser("busya")
        seedTemplates()
        val throttle = PartialThrottle(holdMillis = 600).apply { install() }
        try {
            page.navigate("$baseUrl/templates")
            page.waitForSelector(folderSummary("nyc"))
            drillToMobility(throttle)

            // Select trips (A) held; its skeleton and busy markers arm. Then select
            // stations (B): hx-sync="#template-detail:replace" ABORTS A's in-flight request.
            val failuresBefore = throttle.failedPartials.get()
            page.waitForRequest({ req -> req.url().contains("/partials/templates/versions") }) {
                page.click(leafButton("nyc/lib/mobility/trips"))
            }
            throttle.awaitCaptured(4)
            page.waitForSelector("#template-detail[aria-busy='true']")
            page.waitForRequest({ req -> req.url().contains("/partials/templates/versions") }) {
                page.click(leafButton("nyc/lib/mobility/stations"))
            }
            throttle.awaitCaptured(5)

            // (4) The abort's effects, synchronized on the DOM: A's own busy state goes away
            // even though B is still in flight (the HIDDEN wait retries until A's aborted
            // afterRequest has cleaned up), and the bar STAYS active — the count, not a
            // boolean: B is still holding it.
            page.waitForSelector(
                leafButton("nyc/lib/mobility/trips") + "[aria-disabled='true']",
                Page.WaitForSelectorOptions().setState(WaitForSelectorState.HIDDEN),
            )
            page.waitForSelector("#app-progress.active")

            throttle.releaseAll()
            page.waitForSelector("#template-detail h2.tplx-detail-title")
            page.locator("#template-detail h2.tplx-detail-title").getAttribute("title") shouldBe "nyc/lib/mobility/stations"

            // The abort itself, pinned: A's held request FAILED (cancelled by the app via
            // hx-sync replace), not merely ordered behind B's. Asserted after the release —
            // onRequestFailed delivery is only guaranteed settled by then (the §C lesson).
            throttle.failedPartials.get() shouldBeGreaterThanOrEqual (failuresBefore + 1)

            // The terminal state is fully clean: no stuck bar, no orphaned skeleton or
            // aria-busy (A's abort cleaned its half; B's settle cleaned the rest).
            barHidden()
            page.locator(".app-target-skeleton").count() shouldBe 0
            page.evaluate("() => document.getElementById('template-detail').hasAttribute('aria-busy')") shouldBe false
            page.evaluate(
                "(sel) => document.querySelector(sel).hasAttribute('aria-disabled')",
                leafButton("nyc/lib/mobility/stations"),
            ) shouldBe false
        } finally {
            throttle.releaseAll()
        }
    }

    private companion object {
        /** awaitCaptured's hard deadline — a capture that never comes is a test bug, named. */
        const val AWAIT_CAPTURED_TIMEOUT_MILLIS = 10_000L
    }
}
