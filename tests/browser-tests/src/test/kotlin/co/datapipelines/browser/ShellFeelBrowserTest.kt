package co.datapipelines.browser

import com.microsoft.playwright.APIResponse
import com.microsoft.playwright.Page
import com.microsoft.playwright.PlaywrightException
import com.microsoft.playwright.Route
import com.microsoft.playwright.options.LoadState
import com.microsoft.playwright.options.ReducedMotion
import com.microsoft.playwright.options.WaitForSelectorState
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.nio.file.Paths

/**
 * 103 — the shell's feedback and atmosphere layer, measured rather than admired.
 *
 * The round exists because of one owner observation (2026-09-08): "algoschool.app looks so
 * native SPA than datapipelines although I am using the same design library." Measured, that
 * app has ZERO `hx-boost` — every navigation is a full document load — and reads as the more
 * native of the two anyway, because a click there is acknowledged in the frame it happens and
 * the arriving page has motion, a ground and heading weight. We were already the more SPA of
 * the two architecturally and gave none of that back to the reader.
 *
 * Five properties, each pinned as a NUMBER rather than as a screenshot:
 *
 *  1. **The click is acknowledged before the response exists.** The pending class must land
 *     from the CLICK, not from `htmx:beforeRequest` and certainly not from the swap — so the
 *     latency is measured INSIDE the page (a capture-phase click listener and a
 *     MutationObserver, both stamped with `performance.now()`), never across the driver, whose
 *     round trip would be most of any number measured from Kotlin.
 *  2. **The pill waits 150ms and then arrives.** Proven from both sides: a boosted GET held
 *     open shows it, and the same navigation unheld never shows it at all. A
 *     pill that flashed on every fast swap would be worse than no pill.
 *  3. **The entrance runs and ends.** `#app-main` carries `app-enter` after the swap and has
 *     shed it inside [ENTRANCE_BUDGET_MILLIS]; under `prefers-reduced-motion: reduce` the
 *     computed `animation-name` is `none`, which is also why shell.js keeps a fallback timer
 *     (an animation that never runs never fires `animationend`).
 *  4. **The motion is free.** Cumulative layout shift over three boosted navigations stays
 *     under the geometry suite's own 0.05 budget — a transform/opacity entrance cannot move a
 *     box, and this is the instrument that would notice if someone animated a margin.
 *  5. **The ground stays readable.** Body text over the backdrop keeps the design system's
 *     4.5:1 text floor (ui-screens.md §2 principle 8) on EVERY vendored theme, measured
 *     against the worst composite the backdrop can paint — the brand glow over a grid dot
 *     over the page surface — not against the page surface alone.
 *
 * The throttle is [ShellBusyBrowserTest]'s capture-and-hold, narrowed to the one boosted
 * document GET: a route handler that SLEEPS would serialize the click behind its own request,
 * and the sleep lives in the release, the one sanctioned wait.
 */
class ShellFeelBrowserTest : BrowserSuite() {
    // ------------------------------------------------------------------ the throttle

    private class Held(
        val route: Route,
        val response: APIResponse,
    )

    /** Holds the boosted document GET for one section until [release] is called. */
    private inner class BoostThrottle(
        private val section: String,
    ) {
        private val held = mutableListOf<Held>()
        private var captured = 0

        @Suppress("SwallowedException") // a dead request has nothing to hold
        fun install() {
            page.route("**$section") { route ->
                // Only the BOOSTED navigation, never the first document load: htmx marks its
                // own requests, and holding the plain load would just be a slow test.
                if (route.request().headers()["hx-request"] != "true") {
                    route.resume()
                    return@route
                }
                try {
                    held.add(Held(route, route.fetch()))
                    captured += 1
                } catch (e: PlaywrightException) {
                    // Died between interception and fetch — nothing to hold.
                }
            }
        }

        /** §D's measured hardening: `waitForRequest` can return with the handler still queued. */
        fun awaitCaptured(n: Int) {
            val deadline = System.currentTimeMillis() + AWAIT_CAPTURED_TIMEOUT_MILLIS
            while (captured < n) {
                if (System.currentTimeMillis() > deadline) {
                    throw AssertionError("throttle captured $captured of $n expected boosted GETs")
                }
                page.evaluate("() => 0")
            }
        }

        @Suppress("SwallowedException") // already handled/aborted; nothing left to do
        fun release() {
            val batch = held.toList()
            held.clear()
            for (h in batch) {
                try {
                    h.route.fulfill(Route.FulfillOptions().setResponse(h.response))
                } catch (e: PlaywrightException) {
                    // Already handled.
                }
            }
        }
    }

    /**
     * The same interception, inverted: the boosted GET is fetched ONCE and every later one is
     * fulfilled from that captured response, so the swap costs a local write rather than a
     * server render. This is what makes "a fast swap" a controlled condition instead of a
     * hope about the test JVM's warmth.
     */
    private inner class PrimedRoute(
        private val section: String,
    ) {
        @Volatile
        private var primed: APIResponse? = null

        fun install() {
            page.route("**$section") { route ->
                if (route.request().headers()["hx-request"] != "true") {
                    route.resume()
                    return@route
                }
                val cached = primed ?: route.fetch().also { primed = it }
                route.fulfill(Route.FulfillOptions().setResponse(cached))
            }
        }

        /** Warms the render AND fills the cache, through the handler above. */
        fun prime() {
            page.evaluate(
                "(u) => fetch(u, { headers: { 'HX-Request': 'true' }, credentials: 'same-origin' })" +
                    ".then((r) => r.text()).then((t) => t.length > 0)",
                "$baseUrl$section",
            ) shouldBe true
        }
    }

    // ------------------------------------------------------------------ fixtures

    private fun ready() {
        val user =
            seedLocalUser(
                uniqueEmail("feel-" + generatedPassword("u").take(8)),
                generatedPassword("pw"),
                mustChange = false,
            )
        login(user.email, user.oneTimePassword)
        page.waitForURL("**/dashboard")
        createWorkspace("feelws-" + generatedPassword("w").take(8).lowercase())
    }

    /**
     * Two in-page stopwatches on the same clock: when the click was dispatched, and when each
     * of the two classes actually appeared. Measuring from Kotlin would measure the driver.
     */
    private fun armStopwatches(section: String) {
        page.evaluate(
            """(section) => {
              const link = document.querySelector("a[data-nav-section='" + section + "']");
              const pill = document.getElementById('app-status-pill');
              window.__clickAt = null;
              window.__pendingAt = null;
              window.__pillAt = null;
              // Capture phase: this listener runs BEFORE shell.js's own body-level handler,
              // so the stamp is the click itself and never the reaction to it.
              link.addEventListener('click', () => { window.__clickAt = performance.now(); }, true);
              new MutationObserver(() => {
                if (window.__pendingAt === null && link.classList.contains('is-pending')) {
                  window.__pendingAt = performance.now();
                }
              }).observe(link, { attributes: true, attributeFilter: ['class'] });
              new MutationObserver(() => {
                if (window.__pillAt === null && !pill.hasAttribute('hidden')) {
                  window.__pillAt = performance.now();
                }
              }).observe(pill, { attributes: true, attributeFilter: ['hidden'] });
            }""",
            section,
        )
    }

    private fun stopwatch(name: String): Double? = (page.evaluate("() => window.__$name") as? Number)?.toDouble()

    private fun shotDir(): Path = Paths.get("build", "reports", "103-screenshots").also { it.toFile().mkdirs() }

    /**
     * Every number this suite measures, written down where a reader can find it.
     *
     * An assertion proves a property to the build and then throws the measurement away; the
     * round's handback has to quote it. One file per metric, overwritten, so a stale run
     * cannot leave a number behind that looks like this one's.
     */
    private fun record(
        name: String,
        value: String,
    ) {
        val dir = Paths.get("build", "reports", "103-metrics").also { it.toFile().mkdirs() }
        dir.resolve("$name.txt").toFile().writeText(value + "\n")
    }

    // ------------------------------------------------------------------ §A the click

    @Test
    fun `a rail click is acknowledged in the same frame, and the pill waits 150ms for a slow swap`() {
        startTrace()
        ready()
        page.setViewportSize(REVIEW_WIDTH, REVIEW_HEIGHT)
        page.navigate("$baseUrl/dashboard")
        page.waitForLoadState(LoadState.NETWORKIDLE)

        val throttle = BoostThrottle("/pipelines").apply { install() }
        try {
            armStopwatches("/pipelines")
            page.waitForRequest({ req -> req.url().endsWith("/pipelines") }) {
                page.click("a[data-nav-section='/pipelines']")
            }
            throttle.awaitCaptured(1)

            // (1) The link is pending WHILE the response is still held — the acknowledgement
            // cannot have come from the swap, because the swap has not happened.
            page.waitForSelector("a[data-nav-section='/pipelines'].is-pending[aria-disabled='true']")
            page.waitForSelector("nav.app-nav.is-pending-scope")
            val clickAt = stopwatch("clickAt")!!
            val pendingAt = stopwatch("pendingAt")!!
            withClue("click→is-pending was ${pendingAt - clickAt}ms") {
                (pendingAt - clickAt) shouldBeLessThan ACK_BUDGET_MILLIS
            }
            // A second click cannot fire: the pended link is pointer-events:none.
            page.evaluate(
                "() => getComputedStyle(document.querySelector(\"a[data-nav-section='/pipelines']\")).pointerEvents",
            ) shouldBe "none"

            // (2) …and the pill arrives, but only after the 150ms arm.
            page.waitForSelector("#app-status-pill.is-on")
            val pillAt = stopwatch("pillAt")!!
            withClue("click→pill was ${pillAt - clickAt}ms") {
                (pillAt - clickAt) shouldBeGreaterThanOrEqual PILL_DELAY_MILLIS
            }
            page.locator("#app-status-pill").innerText().trim() shouldBe "Loading…"
            record(
                "ack",
                "click→is-pending ${"%.1f".format(pendingAt - clickAt)}ms; " +
                    "click→pill ${"%.1f".format(pillAt - clickAt)}ms (arm $PILL_DELAY_MILLIS ms, boosted GET held)",
            )

            throttle.release()
            page.waitForURL("**/pipelines")
            page.waitForSelector("#app-status-pill.is-on", Page.WaitForSelectorOptions().setState(WaitForSelectorState.HIDDEN))
            // Everything the click set comes off on settle — including the aria-disabled.
            page.locator(".is-pending").count() shouldBe 0
            page.locator(".is-pending-scope").count() shouldBe 0
            page.locator("#app-status-pill[hidden]").count() shouldBe 1
        } finally {
            throttle.release()
        }
    }

    @Test
    fun `a fast swap never flashes the pill`() {
        startTrace()
        ready()
        page.navigate("$baseUrl/dashboard")
        page.waitForLoadState(LoadState.NETWORKIDLE)

        // "The in-process server is fast" is NOT a safe premise, and assuming it is cost this
        // test two runs: /datasources' first boosted render measured 777ms cold (Thymeleaf
        // parse plus JIT), the pill duly appeared, and the failure was the FEATURE WORKING.
        // The response is therefore primed once and replayed from memory, so what this test
        // measures is the 150ms arm and nothing about the server's warmth.
        val primed = PrimedRoute("/datasources").apply { install() }
        primed.prime()
        armStopwatches("/datasources")
        recordSettleEvents()
        page.click("a[data-nav-section='/datasources']")
        page.waitForURL("**/datasources")
        // The URL is NOT the settle: htmx pushes it while handling the response and only then
        // swaps and settles (a 20ms settleDelay by default), so a `waitForURL` can return with
        // the clear still one task away — which is exactly how an earlier run of this test read
        // a perfectly healthy `is-pending` as a stuck one. Synchronize on the event that
        // actually does the clearing, which also PINS that a boosted swap produces it (shell.js
        // listens to `htmx:pushedIntoHistory` as well for its own resync, and this is the
        // evidence that the pending clear does not need a second listener).
        page.waitForFunction("() => (window.__events || []).some((e) => e.startsWith('htmx:afterSettle'))")
        page.waitForLoadState(LoadState.NETWORKIDLE)

        // The MutationObserver watched the `hidden` attribute for the WHOLE navigation, so
        // this is a claim about every frame of it, not about the frame the assertion ran in.
        withClue("the pill was shown during a swap the reader never waited for") {
            stopwatch("pillAt") shouldBe null
        }
        page.locator("#app-status-pill[hidden]").count() shouldBe 1
        withClue("still pending: " + pendingReport() + "; htmx events: " + settleEvents()) {
            page.locator(".is-pending").count() shouldBe 0
        }
        record("fast-swap", "pill never shown; htmx events: " + settleEvents())
    }

    /**
     * The events the pending clear can hang off, in the order they actually fire. shell.js's
     * own `resync` listens to BOTH `htmx:pushedIntoHistory` and `htmx:afterSettle` (076/079),
     * which is a standing hint that neither alone covers a boosted navigation — this records
     * which of them a real swap produces, so a stuck `is-pending` names its cause instead of
     * inviting a speculative third listener.
     */
    private fun recordSettleEvents() {
        page.evaluate(
            """() => {
              window.__events = [];
              for (const type of ['htmx:afterSwap', 'htmx:afterSettle', 'htmx:pushedIntoHistory', 'htmx:afterRequest']) {
                document.body.addEventListener(type, (e) => {
                  const t = e.detail && e.detail.target;
                  window.__events.push(type + '(' + (t && t.id ? '#' + t.id : (t && t.tagName) || '?') + ')');
                });
              }
            }""",
        )
    }

    private fun settleEvents(): String = (page.evaluate("() => (window.__events || []).join(' → ')") as String)

    /** Names whatever is still wearing the class — a bare count says nothing about the cause. */
    private fun pendingReport(): String =
        page.evaluate(
            "() => [...document.querySelectorAll('.is-pending')]" +
                ".map((e) => e.tagName + '[' + (e.getAttribute('data-nav-section') || e.getAttribute('href') || e.className) + ']')" +
                ".join(', ')",
        ) as String

    // ------------------------------------------------------------------ §B the entrance

    @Test
    fun `the swap arrives with an entrance that plays to completion`() {
        startTrace()
        ready()
        page.navigate("$baseUrl/dashboard")
        page.waitForLoadState(LoadState.NETWORKIDLE)

        armEntranceObservers()
        page.click("a[data-nav-section='/executions']")
        page.waitForURL("**/executions")
        page.waitForFunction("() => window.__enterGoneAt !== null")

        page.evaluate("() => window.__enterSeen") shouldBe true
        // The class landing is not the feature; the animation PLAYING is. The first version of
        // this round applied it on `htmx:afterSwap`, where htmx is still mid-ceremony
        // (`htmx-swapping htmx-added htmx-settling`) and replaces the element a frame later:
        // the entrance started and was cancelled 15ms in. Every class assertion passed. Only
        // these events knew, which is why they are the assertion.
        page.waitForFunction("() => (window.__anim || []).some((e) => e.startsWith('animationend'))")
        val anim = (page.evaluate("() => (window.__anim || []).join(', ')") as String)
        withClue("animation events: $anim") {
            anim shouldContain "animationstart:app-enter"
            anim shouldContain "animationend:app-enter"
            anim shouldNotContain "animationcancel"
        }
        // The budget is measured on the ANIMATION, start to end — not on the class's life
        // relative to `afterSwap`. An earlier version did the latter and quietly went vacuous
        // the moment a second (partial) swap moved the reference point: it recorded
        // "lived -159.7ms", which is comfortably under any budget and means nothing. A
        // two-sided bound is what makes it a real check — an entrance that is cut short is as
        // wrong as one that overstays.
        val startedAt = (page.evaluate("() => window.__animAt['animationstart']") as Number).toDouble()
        val endedAt = (page.evaluate("() => window.__animAt['animationend']") as Number).toDouble()
        val ran = endedAt - startedAt
        withClue("the entrance ran for ${"%.1f".format(ran)}ms; events: $anim") {
            ran shouldBeLessThan ENTRANCE_BUDGET_MILLIS
            ran shouldBeGreaterThanOrEqual ENTRANCE_FLOOR_MILLIS
        }
        val trace = page.evaluate("() => (window.__trace || []).join(' ; ')") as String
        val duration =
            page.evaluate(
                "() => { const m = document.getElementById('app-main'); m.classList.add('app-enter'); " +
                    "const d = getComputedStyle(m).animationDuration; m.classList.remove('app-enter'); return d; }",
            ) as String
        record(
            "entrance",
            "animationstart→animationend ${"%.1f".format(ran)}ms of a declared $duration " +
                "(floor $ENTRANCE_FLOOR_MILLIS ms, budget $ENTRANCE_BUDGET_MILLIS ms); " +
                "events: $anim; trace: $trace",
        )
    }

    @Test
    fun `reduced motion silences the entrance in CSS, not in JS`() {
        startTrace()
        ready()
        page.navigate("$baseUrl/dashboard")
        page.waitForLoadState(LoadState.NETWORKIDLE)

        // shell.js has no business reading a media query, so the class lands either way and
        // the STYLESHEET refuses to animate it. The computed `animation-name` is the only
        // place the media query's answer exists — and it is also why shell.js carries a
        // fallback timer: `animation: none` never fires `animationend`.
        page.evaluate(
            "() => { document.getElementById('app-main').classList.add('app-enter'); " +
                "return getComputedStyle(document.getElementById('app-main')).animationName; }",
        ) shouldBe "app-enter"
        page.emulateMedia(Page.EmulateMediaOptions().setReducedMotion(ReducedMotion.REDUCE))
        page.evaluate(
            "() => getComputedStyle(document.getElementById('app-main')).animationName",
        ) shouldBe "none"
        page.emulateMedia(Page.EmulateMediaOptions().setReducedMotion(ReducedMotion.NO_PREFERENCE))
    }

    /**
     * The entrance is transient by design, so it is WATCHED rather than polled for: one
     * observer records that `#app-main` carried the class and when it came off, and a set of
     * animation listeners records whether the animation actually PLAYED. The trace snapshots
     * are what turned "the class is applied" into "the animation was cancelled 15ms in".
     */
    private fun armEntranceObservers() {
        // The class is transient by design, so it is WATCHED rather than polled for: an
        // observer records that #app-main carried it and when it came off.
        page.evaluate(
            """() => {
              window.__enterSeen = false;
              window.__enterGoneAt = null;
              window.__swapAt = null;
              window.__animAt = {};
              window.__trace = [];
              document.body.addEventListener('htmx:afterSwap', () => {
                if (window.__swapAt === null) window.__swapAt = performance.now();
                const snap = (tag) => {
                  const m = document.getElementById('app-main');
                  window.__trace.push(tag + ':' + (m ? m.className + '|' + getComputedStyle(m).animationName +
                    '|dup=' + document.querySelectorAll('#app-main').length : 'none') + '@' + Math.round(performance.now()));
                };
                snap('afterSwap');
                requestAnimationFrame(() => snap('raf1'));
                setTimeout(() => snap('t50'), 50);
              });
              window.__anim = [];
              for (const t of ['animationstart', 'animationend', 'animationcancel']) {
                document.body.addEventListener(t, (e) => {
                  if (e.target && e.target.id === 'app-main' && e.animationName === 'app-enter') {
                    window.__anim.push(t + ':' + e.animationName + '@' + Math.round(performance.now()));
                    window.__animAt = window.__animAt || {};
                    window.__animAt[t] = performance.now();
                  }
                });
              }
              new MutationObserver(() => {
                const main = document.getElementById('app-main');
                if (!main) return;
                if (main.classList.contains('app-enter')) window.__enterSeen = true;
                else if (window.__enterSeen && window.__enterGoneAt === null) window.__enterGoneAt = performance.now();
              }).observe(document.body, { attributes: true, subtree: true, childList: true, attributeFilter: ['class'] });
            }""",
        )
    }

    @Test
    fun `the entrance costs no layout shift`() {
        startTrace()
        ready()
        page.setViewportSize(REVIEW_WIDTH, REVIEW_HEIGHT)
        // The geometry suite's own observer and its own budget: a transform/opacity entrance
        // cannot move a box, and this is what would notice a margin or height one.
        page.addInitScript(
            """
            window.__cls = 0;
            new PerformanceObserver((l) => { for (const e of l.getEntries())
              if (!e.hadRecentInput) window.__cls += e.value; }).observe({type: 'layout-shift', buffered: true});
            """.trimIndent(),
        )
        page.navigate("$baseUrl/dashboard")
        page.waitForLoadState(LoadState.NETWORKIDLE)
        page.evaluate("() => { window.__cls = 0; }")

        val shifts = mutableListOf<String>()
        for (section in listOf("/templates", "/pipelines", "/executions")) {
            page.click("a[data-nav-section='$section']")
            page.waitForLoadState(LoadState.NETWORKIDLE)
            val cls = (page.evaluate("() => window.__cls") as Number).toDouble()
            withClue("boosted navigation to $section: cumulative layout shift $cls") { cls shouldBeLessThan CLS_BUDGET }
            shifts += "$section=$cls"
            page.evaluate("() => { window.__cls = 0; }")
        }
        record("cls", shifts.joinToString(", ") + " (budget $CLS_BUDGET)")
    }

    // ------------------------------------------------------------------ §C the ground

    @Test
    fun `body text keeps the 4_5 to 1 text floor over the backdrop on every vendored theme`() {
        startTrace()
        ready()
        page.setViewportSize(REVIEW_WIDTH, REVIEW_HEIGHT)
        page.navigate("$baseUrl/dashboard")
        page.waitForLoadState(LoadState.NETWORKIDLE)

        // The list is DERIVED FROM THE RUNNING APP, never recalled and never hard-coded: the
        // top bar renders one swatch per palette and one control per mode, both computed
        // server-side from `VendoredThemes.names()`, so a design-system sync that adds a tenth
        // theme brings it into this sweep by itself. (`co.datapipelines.web` is not on this
        // module's compile classpath — reading the rendered shell is both available and a
        // stronger statement: it is the list the USER is offered.)
        @Suppress("UNCHECKED_CAST")
        val themes =
            (
                page.evaluate(
                    "() => [...document.querySelectorAll('#app-appearance [data-mode]')].map((e) => e.dataset.mode)" +
                        ".concat([...document.querySelectorAll('.app-swatch[data-swatch]')].map((e) => e.dataset.swatch))",
                ) as List<*>
            ).map { it as String }
        themes.shouldNotBeEmpty()

        val measured =
            themes.associateWith { theme ->
                page.evaluate(SWAP_THEME, theme) shouldBe true
                page.evaluate(CONTRAST_PROBE) as Map<*, *>
            }

        val floor =
            measured.mapNotNull { (theme, m) ->
                val worst = (m["worst"] as Number).toDouble()
                val where = m["worstLayer"] as String
                "$theme: ${"%.2f".format(worst)}:1 on $where".takeIf { worst < TEXT_CONTRAST_FLOOR }
            }
        val report =
            measured.entries.joinToString("\n") { (t, m) ->
                "$t: ${"%.2f".format((m["worst"] as Number).toDouble())}:1 worst layer=${m["worstLayer"]} " +
                    "(base=${m["base"]}, ${m["layers"]} gradient colours: ${m["image"]})"
            }
        record("contrast", "floor $TEXT_CONTRAST_FLOOR:1 — body text over the backdrop\n" + report)
        withClue(report) { floor shouldBe emptyList() }
    }

    // ------------------------------------------------------------------ the deliverable

    @Test
    fun `the four review screens, photographed light and dark at 1440`() {
        startTrace()
        ready()
        page.setViewportSize(REVIEW_WIDTH, REVIEW_HEIGHT)

        page.navigate("$baseUrl/dashboard")
        ensureTheme("light")
        walk("light")
        page.navigate("$baseUrl/dashboard")
        ensureTheme("dark")
        walk("dark")
    }

    /** Each screen asserted before it is photographed — no image here is of a state nobody checked. */
    private fun walk(mode: String) {
        for ((slug, path) in SHOT_SCREENS) {
            page.navigate("$baseUrl$path")
            page.waitForLoadState(LoadState.NETWORKIDLE)
            // The ground is behind the content on every screen, and the page header is the
            // one this round re-weighted.
            page.locator(".app-backdrop").count() shouldBe 1
            page.evaluate("() => getComputedStyle(document.querySelector('.app-backdrop')).pointerEvents") shouldBe "none"
            page.locator(".app-page-h h1").count() shouldBe 1
            page.evaluate("() => getComputedStyle(document.querySelector('.app-page-h h1')).fontWeight") shouldBe "700"
            page.screenshot(Page.ScreenshotOptions().setPath(shotDir().resolve("103-$slug-$mode.png")))
        }
    }

    private companion object {
        const val REVIEW_WIDTH = 1440
        const val REVIEW_HEIGHT = 900

        /** shell.js's own arm; the pill may never appear before it. */
        const val PILL_DELAY_MILLIS = 150.0

        /** One frame at 60Hz is 16.7ms; three is generous for a synchronous class toggle. */
        const val ACK_BUDGET_MILLIS = 50.0

        /** 150ms of animation plus shell.js's 250ms fallback, with room for a slow CI box. */
        const val ENTRANCE_BUDGET_MILLIS = 300.0

        /**
         * …and its other side. A declared 150ms animation that ENDS in 20ms was cancelled, not
         * completed — which is exactly the defect this round shipped and then caught, so the
         * floor is not decoration. Set below 150ms only for frame-timing slack on a busy box.
         */
        const val ENTRANCE_FLOOR_MILLIS = 120.0

        /** ExplorerPaneGeometryBrowserTest's budget, restated so this file fails on its own. */
        const val CLS_BUDGET = 0.05

        /** ui-screens.md §2 principle 8: text keeps its 4.5:1 floors. */
        const val TEXT_CONTRAST_FLOOR = 4.5

        const val AWAIT_CAPTURED_TIMEOUT_MILLIS = 30_000L

        val SHOT_SCREENS =
            listOf(
                "dashboard" to "/dashboard",
                "pipelines" to "/pipelines",
                "datasources" to "/datasources",
                "api" to "/api-console",
            )

        /**
         * Swapping the live theme by REPLACING the stylesheet link and waiting for its own
         * `load` — mutating the existing element's href leaves `sheet` pointing at the old
         * rules for an unbounded moment, which is exactly how a nine-theme sweep measures one
         * theme nine times. `onerror` rejecting also makes a missing stylesheet a failure
         * rather than a silently-unchanged measurement.
         */
        val SWAP_THEME =
            """
            (t) => new Promise((resolve, reject) => {
              const old = document.getElementById('theme-link');
              const link = document.createElement('link');
              link.rel = 'stylesheet';
              link.onload = () => { old.remove(); link.id = 'theme-link'; resolve(true); };
              link.onerror = () => reject(new Error('theme ' + t + ' did not load'));
              link.href = '/vendor/design-system/themes/' + t + '.css';
              old.insertAdjacentElement('afterend', link);
            })
            """.trimIndent()

        /**
         * "Contrast on the backdrop" has as many answers as the backdrop has layers, and this
         * reports the WORST. The layers are not rebuilt here — they are read back off the REAL
         * `.app-backdrop` element's computed `background-color` and `background-image`, where
         * Chromium has already resolved every `color-mix()` to an `rgb()`/`rgba()`. Rebuilding
         * them on a probe element measured a reconstruction, and the first version of this
         * test proved it: a nested `color-mix` behind a custom property failed to parse on the
         * probe, `backgroundColor` stayed `rgba(0, 0, 0, 0)`, and the sweep reported 1.17:1 on
         * a dot that is in fact almost the page colour.
         *
         * Every stacked composite of those colours over the base is scored, not just the ones
         * the paint can actually produce: a superset is conservative, and cheap at four
         * colours.
         */
        val CONTRAST_PROBE =
            """
            () => {
              const bd = getComputedStyle(document.querySelector('.app-backdrop'));
              // Chromium computes `color-mix(in srgb, …)` to `color(srgb r g b / a)` — 0..1
              // floats — and leaves plain colours as `rgb()/rgba()` — 0..255. A parser that
              // knew only the second form matched nothing but the gradients' two `transparent`
              // stops and reported the PAGE SURFACE as the worst layer on eight of nine themes.
              const parse = (s) => { const n = s.match(/[\d.]+/g).map(Number);
                const scale = s.startsWith('color(') ? 255 : 1;
                return { r: n[0] * scale, g: n[1] * scale, b: n[2] * scale, a: n.length > 3 ? n[3] : 1 }; };
              const over = (top, base) => ({
                r: top.a * top.r + (1 - top.a) * base.r,
                g: top.a * top.g + (1 - top.a) * base.g,
                b: top.a * top.b + (1 - top.a) * base.b,
                a: 1,
              });
              const lum = (c) => { const f = (v) => { const s = v / 255;
                  return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4); };
                return 0.2126 * f(c.r) + 0.7152 * f(c.g) + 0.0722 * f(c.b); };
              const ratio = (a, b) => { const l1 = lum(a), l2 = lum(b);
                return (Math.max(l1, l2) + 0.05) / (Math.min(l1, l2) + 0.05); };

              const base = parse(bd.backgroundColor);
              const layers = (bd.backgroundImage.match(/(?:rgba?|color)\([^)]*\)/g) || []).map(parse);
              const candidates = { base: base };
              layers.forEach((top, i) => {
                const single = over(top, base);
                candidates['layer' + i] = single;
                layers.forEach((second, j) => { candidates['layer' + i + '_over_' + j] = over(second, single); });
              });
              const text = parse(getComputedStyle(document.querySelector('.app-main')).color);

              let worst = Infinity, worstLayer = '';
              for (const [name, colour] of Object.entries(candidates)) {
                const r = ratio(text, colour);
                if (r < worst) { worst = r; worstLayer = name; }
              }
              return { worst, worstLayer, layers: layers.length, base: bd.backgroundColor, image: bd.backgroundImage };
            }
            """.trimIndent()
    }
}
