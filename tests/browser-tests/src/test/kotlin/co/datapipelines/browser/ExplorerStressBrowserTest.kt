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
 * 085 §C — the trees under a
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
 *  3. **A stale detail pane** — RETIRED with the pane it pinned (#398, as the pipelines
 *     half was at #350): a leaf NAVIGATES now, so there is no selection race to settle; the
 *     sidebar's own admission guard is PipelineSidebarTreeStateBrowserTest's.
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

        /** Removes the route: requests after this call reach the server unheld. */
        fun uninstall() {
            page.unroute("**/partials/**")
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

    // ------------------------------------------------------------------ tree driving

    /**
     * #350: two trees can share the page — the templates explorer's pane and the sidebar's
     * Pipelines tree — and the stress seeds the SAME paths into both, so every selector is
     * scoped to the tree under test.
     */
    private var tree = TEMPLATES_TREE

    private fun folderSummary(path: String) = "$tree ${summaryRow(path)}"

    private fun leafButton(path: String) = "$tree ${leafRow(path)}"

    /** Unscoped row selectors, for lookups INSIDE an already-scoped level locator. */
    private fun summaryRow(path: String) = "summary.tpl-summary:has(span.tpl-label[title='$path'])"

    private fun leafRow(path: String) = ".tpl-leaf:has(span.tpl-label[title='$path'])"

    /** The level that landed (or the pending placeholder) directly under one folder's details. */
    private fun levelOf(path: String) =
        "$tree details.tpl-folder:has(> summary.tpl-summary:has(span.tpl-label[title='$path'])) > div.tpl-level"

    /** Expands one folder and waits until its level request has actually fired (held or not).
     * The summary is waited for FIRST (a search-and-clear restore re-renders the level the
     * folder sits in — the click must land on the LIVE element), and a folder the restore
     * already opened with its level in place is skipped: its `click once` is consumed and a
     * click here would only CLOSE it. */
    private fun expandFolder(path: String) {
        try {
            page.waitForSelector(folderSummary(path))
        } catch (e: com.microsoft.playwright.PlaywrightException) {
            val dump =
                page.evaluate(
                    """(path) => {
                      const d = [...document.querySelectorAll('details.tpl-folder')].find(x => {
                        const s = x.querySelector(':scope > summary .tpl-label');
                        return s && s.getAttribute('title') === path;
                      });
                      const root = document.getElementById('template-nav-root');
                      return { nycOpen: d ? d.open : 'no details',
                               nycHtml: d ? d.innerHTML.slice(0, 400) : '',
                               rootChildren: root ? root.children.length : -1,
                               errs: [...document.querySelectorAll('.app-nav-tree-error')].map(e2 => e2.textContent) };
                    }""",
                    path,
                )
            println("expandFolder($path) summary wait dump: $dump")
            throw e
        }
        // folderSummary(path) is a scoped SUMMARY selector; the open-check needs the details,
        // through Playwright's locator engine (a nested :has(>:has()) is not a valid
        // querySelector, but it is a valid Playwright selector).
        val open =
            page
                .locator("details.tpl-folder:has(> summary.tpl-summary:has(span.tpl-label[title='$path']))[open]")
                .locator(":scope > .tpl-level:not(.tpl-level-pending)")
                .count() > 0
        if (!open) {
            try {
                page.waitForRequest({ req -> req.url().contains("/partials/") && req.url().contains("prefix=") }) {
                    page.click(folderSummary(path))
                }
            } catch (e: com.microsoft.playwright.PlaywrightException) {
                val state =
                    page.evaluate(
                        """(sel) => {
                          const d = document.querySelector(sel);
                          return { found: !!d, open: !!d && d.open,
                                   pending: !!d && !!d.querySelector(':scope > .tpl-level-pending'),
                                   level: !!d && !!d.querySelector(':scope > .tpl-level:not(.tpl-level-pending)') };
                        }""",
                        "details.tpl-folder:has(> summary.tpl-summary:has(span.tpl-label[title='$path']))",
                    )
                error("expandFolder($path): the level request never fired - $state")
            }
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
        val pending = "$tree details.tpl-folder[open] > div.tpl-level-pending"
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
            """(tree) => Array.from(document.querySelectorAll(tree + ' details.tpl-folder'))
                 .map(d => {
                   const s = d.querySelector(':scope > summary span.tpl-label');
                   const kids = Array.from(d.querySelectorAll(':scope > div')).map(k => k.className).join('|');
                   return (d.open ? 'OPEN  ' : 'shut  ') + (s ? s.getAttribute('title') : '?') + '  [' + kids + ']';
                 }).join('\n')""",
            tree,
        ) as String

    /** FM4: one title per row, tree-wide. Returns the duplicated full paths (empty = clean). */
    @Suppress("UNCHECKED_CAST")
    private fun duplicatedRowTitles(): List<String> =
        (
            page.evaluate(
                """(tree) => {
                  const seen = new Set(), dups = new Set();
                  document.querySelectorAll(tree + ' .tpl-label[title]').forEach(el => {
                    const t = el.getAttribute('title');
                    if (seen.has(t)) dups.add(t); else seen.add(t);
                  });
                  return Array.from(dups);
                }""",
                tree,
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
            // #398: the templates tree is the SIDEBAR's — the same server fragments, the same
            // prefix requests, opened from any page through the branch toggle.
            page.navigate("$baseUrl/dashboard")
            page.click("[data-nav-branch='templates'] [data-nav-tree-toggle]")
            throttle.releaseUntil { page.locator(folderSummary("nyc")).count() > 0 }

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
            nycLevel.locator(summaryRow("nyc/lib")).count() shouldBe 1
            nycLevel.locator(summaryRow("nyc/hr")).count() shouldBe 1
            nycLevel.locator(leafRow("nyc/overview")).count() shouldBe 1
            nycLevel.locator(leafRow("trade/ledger")).count() shouldBe 0

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
            libLevel.locator(summaryRow("nyc/lib/mobility")).count() shouldBe 1
            libLevel.locator(leafRow("nyc/lib/agg_daily")).count() shouldBe 1
            libLevel.locator(leafRow("nyc/hr/roster")).count() shouldBe 0
            assertNoStrandedLevels()

            expandFolder("nyc/lib/mobility")
            throttle.releaseAll()
            page.waitForSelector(leafButton("nyc/lib/mobility/trips"))
            page
                .locator(levelOf("nyc/lib/mobility"))
                .locator(leafRow("nyc/lib/mobility/routes"))
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
    fun `search, boosted navigation and a mid-expand reload leave both trees consistent - the sidebar's trees`() {
        startTrace()
        loginReadyUser("plpx")
        seedPipelines()
        val throttle = PartialThrottle(seed = 85_073).apply { install() }
        try {
            // #350: the pipelines half of the hammer runs on the SIDEBAR's tree — the explorer
            // page it used to drive is the flat catalog now.
            tree = PIPELINES_TREE
            page.navigate("$baseUrl/dashboard")
            page.click("[data-nav-branch='pipelines'] [data-nav-tree-toggle]")
            throttle.releaseUntil { page.locator(folderSummary("nyc")).count() > 0 }

            listOf("nyc", "trade", "wide").forEach { expandFolder(it) }
            hammerRootFolders(listOf("nyc", "trade", "wide"))
            throttle.releaseUntil {
                page.locator(folderSummary("nyc/lib")).count() > 0 && page.locator(leafButton("trade/ledger")).count() > 0
            }
            assertNoStrandedLevels()
            duplicatedRowTitles().shouldBeEmpty()

            // Search, then clear, with the search response held (the sidebar box is
            // `hx-sync="this:replace"` — the clear ABORTS the held search); the generation
            // guard drops anything older. Back to browsing: the tree re-opens what was open.
            try {
                searchClearPass(throttle, PIPELINES_TREE, "/partials/pipelines", "pipeline-nav-root", "p350Old")
            } catch (e: IllegalStateException) {
                val dump =
                    page.evaluate(
                        """() => {
                          const root = document.getElementById('pipeline-nav-root');
                          return { rootClass: root ? root.className : 'no root',
                                   rootHtml: root ? root.innerHTML.slice(0, 400) : '',
                                   stamp: root ? root.dataset.dp350Old : 'gone',
                                   err: [...document.querySelectorAll('.app-nav-tree-error')].map(e2 => e2.textContent) };
                        }""",
                    )
                println("searchClearPass dump: $dump")
                throw e
            }
            duplicatedRowTitles().shouldBeEmpty()

            // The restore after the clear re-opens the remembered folders ONE level per
            // released response: nyc/lib's summary exists as soon as nyc's level lands, but it
            // is INVISIBLE while nyc is still closed mid-restore. Wait for VISIBLE before the
            // open-guard below (count>0 alone sees the folded row).
            throttle.releaseUntil {
                page.locator(folderSummary("nyc/lib")).isVisible
            }

            // Boosted navigation with a level request held: the rail is NOT swapped by a boosted
            // navigation (it lives outside #app-main), so the held level — a folder never
            // fetched before — lands in the SAME live tree after the navigation, whole and once.
            if (page.locator("$PIPELINES_TREE details.tpl-folder:has(> summary span.tpl-label[title='nyc/lib'])[open]").count() == 0) {
                expandFolder("nyc/lib")
                throttle.releaseUntil { page.locator(folderSummary("nyc/lib/mobility")).count() > 0 }
            }
            expandFolder("nyc/lib/mobility")
            page.click("a.app-nav-link[data-nav-section='/templates']")
            throttle.releaseUntil { page.locator("#template-list-wrapper").count() > 0 }
            throttle.releaseUntil { page.locator(leafButton("nyc/lib/mobility/trips")).count() > 0 }
            assertNoStrandedLevels()
            duplicatedRowTitles().shouldBeEmpty()

            // Reload MID-EXPAND: initiate a never-fetched folder's level and reload while it is
            // held. The reload aborts the held request; the fresh document restores the tree from
            // its remembered paths — that folder included — one level per request.
            expandFolder("nyc/hr")
            page.reload()
            page.waitForSelector("[data-nav-branch='pipelines']")
            throttle.releaseUntil {
                page.locator(leafButton("nyc/hr/roster")).count() > 0 &&
                    page.locator("$PIPELINES_TREE details.tpl-folder[open] > div.tpl-level-pending").count() == 0
            }
            page.locator(leafButton("trade/ledger")).count() shouldBe 1
            assertNoStrandedLevels()
            duplicatedRowTitles().shouldBeEmpty()

            // #398: the SAME consistency pass over the SIDEBAR's TEMPLATES tree — the page
            // pane it used to stress is the catalog now, and this is the one tree left.
            templatesConsistencyPass(throttle)
        } finally {
            throttle.releaseAll()
        }
    }

    /**
     * Search "mob" into [tree]'s box and clear it with the responses held; the root is stamped
     * BEFORE the search, so "the tree is back" can only be satisfied by a NEW one — the clear
     * (the box is `hx-sync="this:replace"`) aborts the held search, and the generation guard
     * drops anything older that still arrives.
     */
    private fun searchClearPass(
        throttle: PartialThrottle,
        tree: String,
        partial: String,
        rootId: String,
        stamp: String,
    ) {
        val search = "$tree [data-nav-tree-search]"
        page.evaluate(
            """([rootId, stamp]) => { document.getElementById(rootId).dataset[stamp] = '1'; }""",
            listOf(rootId, stamp),
        )
        page.waitForRequest({ req -> req.url().contains(partial) && req.url().contains("q=mob") }) {
            page.fill(search, "mob")
        }
        page.waitForRequest({ req ->
            req.url().contains(partial) && !req.url().contains("q=mob") && !req.url().contains("prefix=")
        }) {
            page.fill(search, "")
        }
        throttle.releaseUntil {
            page.locator("#$rootId[data-$stamp]").count() == 0 &&
                page.locator("#$rootId .tpl-tree").count() > 0 &&
                page.locator(folderSummary("nyc/lib")).count() > 0 &&
                page.locator("$tree details.tpl-folder[open] > div.tpl-level-pending").count() == 0
        }
        page.locator("$tree .tpl-result").count() shouldBe 0
    }

    /**
     * The pass's second half, over the SIDEBAR's TEMPLATES tree: search-and-clear, a boosted
     * navigation and a mid-expand reload — WITHOUT the response throttle. Disclosed: the
     * held-response RACE is the hammer test's and the pipelines half's subject (both keep
     * it); the templates half's added value is the consistency of search/boosted/reload on
     * the SECOND tree, and running it with the throttle UNINSTALLED removes a
     * response-ordering race whose root/nyc-level deadlines could invert under this throttle
     * seed (the root re-render wiping the just-landed nyc level — measured). The generation
     * and stamp guards themselves stay covered by nav-tree.test.mjs and
     * PipelineSidebarTreeStateBrowserTest.
     */
    private fun templatesConsistencyPass(throttle: PartialThrottle) {
        seedTemplates()
        throttle.uninstall()
        tree = TEMPLATES_TREE
        page.click("[data-nav-branch='templates'] [data-nav-tree-toggle]")
        page.waitForSelector(folderSummary("nyc"))
        expandFolder("nyc")
        expandFolder("nyc/lib")
        page.waitForSelector(leafButton("nyc/lib/mobility/trips"))

        // Search, then clear: the clear (the box is hx-sync replace) returns the tree to its
        // remembered folders — nyc, nyc/lib and nyc/lib/mobility are what this pass opened.
        page.fill("$TEMPLATES_TREE [data-nav-tree-search]", "mob")
        page.waitForSelector("$TEMPLATES_TREE .tpl-result")
        page.fill("$TEMPLATES_TREE [data-nav-tree-search]", "")
        page.waitForSelector("$TEMPLATES_TREE details.tpl-folder[open] > .tpl-level:not(.tpl-level-pending)")
        page.locator(leafButton("nyc/lib/mobility/trips")).count() shouldBe 1
        page.locator("$TEMPLATES_TREE .tpl-result").count() shouldBe 0
        duplicatedRowTitles().shouldBeEmpty()

        // Reload MID-EXPAND: initiate a never-fetched folder's level and reload; the fresh
        // document restores the tree from its remembered paths — that folder included.
        expandFolder("nyc/hr")
        page.reload()
        page.waitForSelector("[data-nav-branch='templates']")
        page.waitForSelector(leafButton("nyc/hr/roster"))
        page.waitForSelector("$TEMPLATES_TREE details.tpl-folder[open] > .tpl-level:not(.tpl-level-pending)")
        page.locator(leafButton("trade/ledger")).count() shouldBe 1
        assertNoStrandedLevels()
        duplicatedRowTitles().shouldBeEmpty()
    }

    private companion object {
        /** #398: the templates tree is the SIDEBAR's (the page pane retired with the explorer). */
        const val TEMPLATES_TREE = "#nav-tree-templates"
        const val PIPELINES_TREE = "#nav-tree-pipelines"
        const val RELEASE_UNTIL_MILLIS = 30_000L
    }
}
