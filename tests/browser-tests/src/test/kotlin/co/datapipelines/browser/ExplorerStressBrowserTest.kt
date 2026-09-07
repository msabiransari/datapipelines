package co.datapipelines.browser

import com.microsoft.playwright.APIResponse
import com.microsoft.playwright.Page
import com.microsoft.playwright.PlaywrightException
import com.microsoft.playwright.Route
import com.microsoft.playwright.options.WaitForSelectorState
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.util.Random

/**
 * 085 §C — the two explorers (Templates at /templates, Pipelines at /pipelines) under a
 * throttled network. The owner's report: "the tree component struggles rendering sometimes".
 * This class puts the tree's htmx wiring under the stresses a slow network creates and pins
 * the four failure modes that wiring could plausibly fall into:
 *
 *  1. **A level that never arrives** — a folder expands and its pending spinner turns
 *     forever. The sharp edge is `hx-trigger="click once"`: the fetch fires on the FIRST
 *     open only, so a collapse/re-expand while that fetch is still in flight cannot re-arm
 *     it — if anything drops the in-flight response, the level is lost for the life of the
 *     element. The hammer below (20 collapse/re-expand cycles per folder, all while the
 *     first fetch is held) is the direct probe.
 *  2. **A level swapped into the wrong target** — `hx-target="next .tpl-level"` is resolved
 *     by htmx against a DOM that a search swap, a pager swap or a boosted navigation may
 *     already have replaced. Asserted structurally: every level must land inside ITS OWN
 *     folder's <details>, with its own children and no sibling's.
 *  3. **A stale detail pane** — leaf A selected, then leaf B, and the pane still shows A.
 *     The leaves carry `hx-sync="#...-detail:replace"`, whose whole job is aborting the
 *     older in-flight selection; the test clicks A then B with both requests held, and pins
 *     BOTH the outcome (the pane shows B) and the mechanism (A's request was aborted, not
 *     merely ordered behind B — an ordering-only fix would still lose to a slower B).
 *  4. **Duplicated rows** — the same folder or leaf rendered twice in one level. Every swap
 *     here is outerHTML-into-a-stable-target, so duplication should be structurally
 *     impossible; the assertion exists because "impossible" is exactly the kind of claim a
 *     race falsifies.
 *
 * ## The throttle (the suite's first page.route)
 *
 * Playwright-Java invokes route handlers during the event pump of whatever API call the test
 * thread is inside, so a handler that SLEEPS would serialize every click behind its own
 * triggered request — the "request in flight while the user does something else" window, which
 * is the entire point, would collapse to zero. Instead the handler only CAPTURES: it fetches
 * the response eagerly (the server, in-process, answers in milliseconds) and holds it against
 * a deadline 400–1200ms out (java.util.Random on a FIXED seed — the delays are deterministic,
 * the assertion is about DOM integrity, not timing). [releaseAll] then fulfills the held
 * responses in deadline order, sleeping only inside the throttle itself — the one sanctioned
 * wait (house rule: route-delay is the throttle, never a wait crutch). Between actions the
 * test synchronizes on `waitForRequest`/`waitForSelector` exclusively.
 *
 * A route the browser abandons while held (an hx-sync abort, a boosted navigation, the
 * mid-expand reload) is observed through `onRequestFailed` — fulfilling it does NOT throw in
 * Playwright-Java (measured), so the abort cannot be pinned at release time. The throttle
 * counts those failures, and the detail-pane test asserts on the count.
 *
 * ## The seeded tree
 *
 * Identical for both explorers (one helper, two REST surfaces — the PipelineEditorDetails
 * in-page fetch pattern, cookie session + the dp_csrf double-submit pair): root folders
 * `nyc`, `trade`, `wide`; a three-level chain `nyc/lib/mobility/...`; siblings and leaves at
 * every level; and `wide/item_00..29` — 30 leaves, one over the 25/page pager, so the pager
 * rides the stress too.
 */
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
                if (req.url().contains("/partials/")) failedPartials.incrementAndGet()
            }
            page.route("**/partials/**") { route ->
                try {
                    val response = route.fetch()
                    held.add(Held(route, response, System.currentTimeMillis() + 400 + rng.nextInt(801)))
                } catch (e: PlaywrightException) {
                    // The request died between interception and fetch (a reload landing in
                    // between) — nothing to hold; the browser has already moved on.
                }
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

    // ------------------------------------------------------------------ tree driving

    private fun folderSummary(path: String) = "summary.tpl-summary:has(span.tpl-label[title='$path'])"

    private fun leafButton(path: String) = "button.tpl-leaf:has(span.tpl-label[title='$path'])"

    /** The level that landed (or the pending placeholder) directly under one folder's details. */
    private fun levelOf(path: String) = "details.tpl-folder:has(> summary.tpl-summary:has(span.tpl-label[title='$path'])) > div.tpl-level"

    /** Expands one folder and waits until its level request has actually fired (held or not). */
    private fun expandFolder(path: String) {
        page.waitForRequest({ req -> req.url().contains("/partials/") && req.url().contains("prefix=") }) {
            page.click(folderSummary(path))
        }
    }

    /**
     * FM1's probe from the brief: collapse and re-expand every visible root folder 20× WHILE
     * the first-open fetch is still held. `click once` was consumed by the first click, so
     * every one of these toggles is a pure <details> state flip — the level must STILL land
     * when the held response is finally released.
     */
    private fun hammerRootFolders(folders: List<String>) {
        repeat(20) {
            folders.forEach { folder ->
                page.click(folderSummary(folder)) // collapse
                page.click(folderSummary(folder)) // re-expand
            }
        }
    }

    /**
     * FM1: every open folder's level arrived — no folder left open over a pending spinner.
     *
     * The check WAITS for absence first (the HIDDEN state): a level's swap lands
     * asynchronously after its response is released, and a synchronous count races the last
     * millisecond of it — measured: the same folder reported "stranded" with its level
     * already landed by the time the diagnostic dump ran one evaluate later. A GENUINELY
     * stranded level never satisfies the wait; the timeout is caught, the tree is dumped,
     * and the assertion fails naming the folder(s).
     *
     * Each entry is flagged when its folder is hidden by a collapsed ancestor
     * (locator counts match invisible rows too, and a shut folder's pending placeholder is
     * the tree's normal not-yet-fetched state, not a failure).
     */
    @Suppress("SwallowedException") // the timeout is the stranded signal; see the catch
    private fun assertNoStrandedLevels() {
        val pending = "details.tpl-folder[open] > div.tpl-level-pending"
        try {
            page.waitForSelector(
                pending,
                Page.WaitForSelectorOptions().setState(WaitForSelectorState.HIDDEN),
            )
        } catch (e: PlaywrightException) {
            // fall through to the named assertion — the timeout IS the stranded signal
        }
        val stranded = strandedFolders(pending)
        if (stranded.isNotEmpty()) {
            // Diagnosis for the failure report: every folder, its open state, and the classes
            // of the divs DIRECTLY under it — a landed level next to a pending placeholder is
            // the signature to distinguish from a level that never arrived.
            println(treeDump())
        }
        stranded.shouldBeEmpty()
    }

    @Suppress("UNCHECKED_CAST")
    private fun strandedFolders(pending: String): List<String> =
        (
            page.evaluate(
                """(pending) => Array.from(document.querySelectorAll(pending))
                     .map(p => {
                       const s = p.parentElement.querySelector(':scope > summary span.tpl-label');
                       return (s ? s.getAttribute('title') : '(no label)') +
                         (p.offsetParent === null ? ' [hidden by a collapsed ancestor]' : '');
                     })""",
                pending,
            ) as List<String>
        )

    private fun treeDump(): String =
        page.evaluate(
            """() => Array.from(document.querySelectorAll('[data-explorer-pane] details.tpl-folder'))
                 .map(d => {
                   const s = d.querySelector(':scope > summary span.tpl-label');
                   const kids = Array.from(d.querySelectorAll(':scope > div')).map(k => k.className).join('|');
                   return (d.open ? 'OPEN  ' : 'shut  ') + (s ? s.getAttribute('title') : '?') + '  [' + kids + ']';
                 }).join('\n')""",
        ) as String

    /** FM4: one title per row, tree-wide. Returns the duplicated full paths (empty = clean). */
    @Suppress("UNCHECKED_CAST")
    private fun duplicatedRowTitles(): List<String> =
        (
            page.evaluate(
                """() => {
                  const seen = new Set(), dups = new Set();
                  document.querySelectorAll('[data-explorer-pane] .tpl-label[title]').forEach(el => {
                    const t = el.getAttribute('title');
                    if (seen.has(t)) dups.add(t); else seen.add(t);
                  });
                  return Array.from(dups);
                }""",
            ) as List<String>
        )

    // ------------------------------------------------------------------ the stresses

    @Test
    fun `the expand-collapse hammer never strands a level, misplaces one, or doubles a row`() {
        startTrace()
        loginReadyUser("tplx")
        seedTemplates()
        val throttle = PartialThrottle(seed = 85_071).apply { install() }
        try {
            page.navigate("$baseUrl/templates")
            page.waitForSelector(folderSummary("nyc"))

            // Open all three root folders — every level request is HELD by the throttle —
            // then hammer each folder 20× while its first fetch is still in flight.
            listOf("nyc", "trade", "wide").forEach { expandFolder(it) }
            hammerRootFolders(listOf("nyc", "trade", "wide"))
            throttle.releaseAll()

            // FM1: all three levels landed over folders left open by the hammer.
            page.waitForSelector(folderSummary("nyc/lib"))
            page.waitForSelector(leafButton("nyc/overview"))
            page.waitForSelector(leafButton("wide/item_00"))
            assertNoStrandedLevels()

            // FM2: nyc's level landed inside nyc's OWN details, holding exactly nyc's
            // children — nothing of trade's subtree leaked across.
            val nycLevel = page.locator(levelOf("nyc"))
            nycLevel.locator(folderSummary("nyc/lib")).count() shouldBe 1
            nycLevel.locator(folderSummary("nyc/hr")).count() shouldBe 1
            nycLevel.locator(leafButton("nyc/overview")).count() shouldBe 1
            nycLevel.locator(leafButton("trade/ledger")).count() shouldBe 0

            // FM4: not one row doubled anywhere in the tree.
            duplicatedRowTitles().shouldBeEmpty()

            // Drill the three-level chain, collapsing and re-opening the PARENT mid-flight —
            // the target placeholder lives inside the parent's level, and a <details> close
            // must not lose the swap that is already on its way.
            expandFolder("nyc/lib")
            page.click(folderSummary("nyc")) // collapse the parent while lib's fetch is held
            page.click(folderSummary("nyc")) // re-open it
            throttle.releaseAll()
            page.waitForSelector(folderSummary("nyc/lib/mobility"))
            val libLevel = page.locator(levelOf("nyc/lib"))
            libLevel.locator(folderSummary("nyc/lib/mobility")).count() shouldBe 1
            libLevel.locator(leafButton("nyc/lib/agg_daily")).count() shouldBe 1
            libLevel.locator(leafButton("nyc/hr/roster")).count() shouldBe 0
            assertNoStrandedLevels()

            expandFolder("nyc/lib/mobility")
            throttle.releaseAll()
            page.waitForSelector(leafButton("nyc/lib/mobility/trips"))
            page
                .locator(levelOf("nyc/lib/mobility"))
                .locator(leafButton("nyc/lib/mobility/routes"))
                .count() shouldBe 1
            assertNoStrandedLevels()
            duplicatedRowTitles().shouldBeEmpty()

            // The wide folder's pager (30 leaves, 25/page) under the same abuse: click Next,
            // hammer the open folders while the page-2 request is held, then release. Page 2
            // must REPLACE page 1 (outerHTML), never append to it.
            page.waitForRequest({ req -> req.url().contains("/partials/templates") && req.url().contains("offset=25") }) {
                page
                    .locator("details.tpl-folder:has(> summary.tpl-summary:has(span.tpl-label[title='wide']))")
                    .locator("button:has-text('Next')")
                    .click()
            }
            hammerRootFolders(listOf("nyc", "wide"))
            throttle.releaseAll()
            page.waitForSelector(leafButton("wide/item_29"))
            page.locator(leafButton("wide/item_00")).count() shouldBe 0
            page.locator(levelOf("wide")).innerText() shouldContain "Showing 5 of 30"
            assertNoStrandedLevels()
            duplicatedRowTitles().shouldBeEmpty()
        } finally {
            throttle.releaseAll()
        }
    }

    @Test
    fun `the detail pane always settles on the last selected leaf`() {
        startTrace()
        loginReadyUser("dtlx")
        seedTemplates()
        seedPipelines()
        val throttle = PartialThrottle(seed = 85_072).apply { install() }
        try {
            // ---- templates: select trips (A) then stations (B) with BOTH requests held.
            // hx-sync="#template-detail:replace" must ABORT A's request outright — if A were
            // merely allowed to land, a slower B would leave the pane showing A.
            page.navigate("$baseUrl/templates")
            page.waitForSelector(folderSummary("nyc"))
            expandFolder("nyc")
            throttle.releaseAll()
            expandFolder("nyc/lib")
            throttle.releaseAll()
            expandFolder("nyc/lib/mobility")
            throttle.releaseAll()
            page.waitForSelector(leafButton("nyc/lib/mobility/trips"))

            val failuresBefore = throttle.failedPartials.get()
            page.waitForRequest({ req -> req.url().contains("/partials/templates/versions") }) {
                page.click(leafButton("nyc/lib/mobility/trips"))
            }
            page.waitForRequest({ req -> req.url().contains("/partials/templates/versions") }) {
                page.click(leafButton("nyc/lib/mobility/stations"))
            }
            throttle.releaseAll()
            page.waitForSelector("#template-detail h2.tplx-detail-path")
            page.locator("#template-detail h2.tplx-detail-path").innerText() shouldBe "nyc/lib/mobility/stations"
            page.locator("#template-detail").innerText() shouldNotContain "trips"
            // The mechanism, pinned: A's held request was ABORTED by hx-sync replace (a failed
            // partial), not merely released-and-ordered behind B's — an ordering-only defense
            // would still leave the pane stale whenever B is the slower response.
            throttle.failedPartials.get() shouldBeGreaterThanOrEqual (failuresBefore + 1)

            // Rapid alternation, ending on routes (C): every selection but the last must die.
            val chain = listOf("trips", "stations", "trips", "stations", "routes")
            chain.forEach { leaf ->
                page.waitForRequest({ req -> req.url().contains("/partials/templates/versions") }) {
                    page.click(leafButton("nyc/lib/mobility/$leaf"))
                }
            }
            throttle.releaseAll()
            page.locator("#template-detail h2.tplx-detail-path").innerText() shouldBe "nyc/lib/mobility/routes"

            // ---- pipelines: same contract, second explorer. ledger (A) then settlement (B).
            page.navigate("$baseUrl/pipelines")
            page.waitForSelector(folderSummary("trade"))
            expandFolder("trade")
            throttle.releaseAll()
            page.waitForSelector(leafButton("trade/ledger"))

            page.waitForRequest({ req -> req.url().contains("/partials/pipelines/detail") }) {
                page.click(leafButton("trade/ledger"))
            }
            page.waitForRequest({ req -> req.url().contains("/partials/pipelines/detail") }) {
                page.click(leafButton("trade/settlement"))
            }
            throttle.releaseAll()
            page.waitForSelector("#pipeline-detail h2.tplx-detail-path")
            page.locator("#pipeline-detail h2.tplx-detail-path").innerText() shouldBe "trade/settlement"
            page.locator("#pipeline-detail").innerText() shouldNotContain "ledger"
        } finally {
            throttle.releaseAll()
        }
    }

    @Test
    fun `search, boosted navigation and a mid-expand reload leave both explorers consistent`() {
        startTrace()
        loginReadyUser("plpx")
        seedPipelines()
        val throttle = PartialThrottle(seed = 85_073).apply { install() }
        try {
            page.navigate("$baseUrl/pipelines")
            page.waitForSelector(folderSummary("nyc"))

            // The pipelines half of the hammer — both explorers must take the stress.
            listOf("nyc", "trade", "wide").forEach { expandFolder(it) }
            hammerRootFolders(listOf("nyc", "trade", "wide"))
            throttle.releaseAll()
            page.waitForSelector(folderSummary("nyc/lib"))
            page.waitForSelector(leafButton("trade/ledger"))
            assertNoStrandedLevels()
            duplicatedRowTitles().shouldBeEmpty()

            // Search, then clear, with the search response held: htmx queues the clear
            // behind the in-flight search request on the same input (queue:last), so the
            // tree must return AFTER the results — never the stale order.
            page.waitForRequest({ req -> req.url().contains("/partials/pipelines") && req.url().contains("q=mob") }) {
                page.fill("#pipeline-filter-q", "mob")
            }
            page.fill("#pipeline-filter-q", "")
            // The clear's request is QUEUED by htmx behind the held search request on the same
            // input (queue:last) — it fires only once the search response completes, so the
            // release rides INSIDE the wait: the wait starts before the request can exist.
            page.waitForRequest({ req ->
                req.url().contains("/partials/pipelines") && !req.url().contains("q=mob") && !req.url().contains("prefix=")
            }) {
                throttle.releaseAll()
            }
            page.waitForSelector("button.tpl-result") // the search results DID land first
            throttle.releaseAll() // now the queued clear
            page.waitForSelector(folderSummary("nyc"))
            page.locator("button.tpl-result").count() shouldBe 0
            duplicatedRowTitles().shouldBeEmpty()

            // Boosted navigation with a level request held: the response lands in a DOM the
            // nav already replaced. The LIVE tree must stay whole, and the other explorer's
            // tree must be unaffected. (The clear re-rendered the tree collapsed, so nyc
            // opens first — nyc/lib's summary does not exist until its level lands.)
            expandFolder("nyc")
            throttle.releaseAll()
            expandFolder("nyc/lib")
            page.click("a.app-nav-link[data-nav-section='/templates']")
            page.waitForSelector("#template-list-wrapper")
            throttle.releaseAll() // releases nyc/lib's orphaned response into the void
            page.click("a.app-nav-link[data-nav-section='/pipelines']")
            page.waitForSelector(folderSummary("nyc"))
            // The fresh tree re-fetches on demand: nyc/lib opens again and lands.
            expandFolder("nyc")
            throttle.releaseAll()
            expandFolder("nyc/lib")
            throttle.releaseAll()
            page.waitForSelector(folderSummary("nyc/lib/mobility"))
            assertNoStrandedLevels()
            duplicatedRowTitles().shouldBeEmpty()

            // Reload MID-EXPAND: initiate trade's level fetch and reload while it is held.
            // The reload aborts the held request; the fresh page must render a clean tree,
            // and the same folder must open normally afterwards (recoverability, FM1's tail).
            expandFolder("trade")
            page.reload()
            page.waitForSelector(folderSummary("nyc"))
            throttle.releaseAll() // the mid-flight trade fetch was aborted by the navigation
            assertNoStrandedLevels()
            expandFolder("trade")
            throttle.releaseAll()
            page.waitForSelector(leafButton("trade/ledger"))
            assertNoStrandedLevels()
            duplicatedRowTitles().shouldBeEmpty()
        } finally {
            throttle.releaseAll()
        }
    }
}
