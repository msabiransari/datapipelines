package co.datapipelines.browser

import com.microsoft.playwright.APIResponse
import com.microsoft.playwright.Page
import com.microsoft.playwright.PlaywrightException
import com.microsoft.playwright.Route
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * #350 (workspace spec §5, A11/A12) — the sidebar tree's STATE, on the real application:
 *
 *  - a full-document entry (the workspace's graph entry) restores the tree and reveals the
 *    current leaf's folders one level per request — and fetches no unrelated branch;
 *  - a current leaf beyond its level's first page is walked to through the level's pager;
 *  - a refused level is a visible row with a Retry that works — never a silent spinner or a toast;
 *  - the ADMISSION guard: a folder answer that lands after the search changed is dropped, an
 *    answer rendered for another workspace never joins the rows, a changed lens resets the tree
 *    — each beside its admitted positive control; a response held across a workspace SWITCH
 *    never reaches the new workspace's tree;
 *  - boosted navigations and history restores keep ONE set of tree handlers (counted per source
 *    file) and re-mark the current leaf; the templates explorer keeps its own keyboard.
 *
 * Every guard is asserted on rows/requests/counts read back from the page, never on "looks fine".
 */
class PipelineSidebarTreeStateBrowserTest : BrowserSuite() {
    // ------------------------------------------------------------------ fixtures

    private fun loginReadyUser(slug: String): String {
        val user =
            seedLocalUser(
                uniqueEmail(slug + generatedPassword("u").take(8)),
                generatedPassword("pw"),
                mustChange = false,
            )
        login(user.email, user.oneTimePassword)
        page.waitForURL("**/dashboard")
        val workspace = newWorkspaceName()
        createWorkspace(workspace)
        page.onConsoleMessage { message -> if (message.type() == "error") consoleErrors += message.text() }
        return workspace
    }

    private fun newWorkspaceName() = "p350s-" + generatedPassword("w").take(8).lowercase()

    private val consoleErrors = mutableListOf<String>()

    private fun api(
        method: String,
        path: String,
        body: String?,
        ifMatch: String? = null,
    ): Pair<Int, String> {
        val answer =
            page.evaluate(
                """async ([method, path, body, ifMatch]) => {
                  const csrf = document.cookie.match(/(?:^|;\s*)dp_csrf=([^;]*)/);
                  const headers = { 'Content-Type': 'application/json' };
                  if (ifMatch) headers['If-Match'] = ifMatch;
                  if (method !== 'GET') headers['DP-CSRF-Token'] = csrf ? decodeURIComponent(csrf[1]) : '';
                  const res = await fetch(path, { method, credentials: 'same-origin', headers, body });
                  let text = '';
                  try { text = await res.text(); } catch (e) {}
                  return [res.status, text];
                }""",
                arrayOf(method, path, body, ifMatch),
            ) as List<*>
        return (answer[0] as Number).toInt() to (answer[1] as String)
    }

    private fun must(
        method: String,
        path: String,
        body: String?,
        ifMatch: String? = null,
    ): String {
        val (status, text) = api(method, path, body, ifMatch)
        check(status == 200 || status == 201) { "$method $path -> $status: ${text.take(300)}" }
        return text
    }

    private fun hashOf(text: String): String = Regex(""""body_hash"\s*:\s*"([^"]+)"""").find(text)!!.groupValues[1]

    private fun idOf(text: String): String = Regex(""""id"\s*:\s*"([0-9a-f-]{36})"""").find(text)!!.groupValues[1]

    private fun seedPipeline(name: String): String {
        val body =
            """{"name":"$name","display_name":"${name.substringAfterLast('/')}","nodes":[{"id":"n1","type":"CALCULATOR",""" +
                """"kind":"fiscal_quarter","context_key":"run_q_n1",""" +
                """"inputs":{"date":"${'$'}current_date","fiscal_start":"${'$'}org_fiscal_start_date"}}]}"""
        val created = must("POST", "/api/v1/pipelines", body)
        must("POST", "/api/v1/pipelines/${idOf(created)}/release", null, ifMatch = hashOf(created))
        return idOf(created)
    }

    private val panel = "#nav-tree-pipelines"

    private fun settle() {
        page.evaluate("() => new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r)))")
    }

    private fun openTree() {
        page.click("[data-nav-branch='pipelines'] [data-nav-tree-toggle]")
        page.waitForSelector("#pipeline-nav-root .tpl-tree, #pipeline-nav-root .ds-empty")
    }

    private fun folder(path: String) = "$panel details.tpl-folder:has(> summary > span.tpl-label[title='$path'])"

    private fun expand(path: String) {
        page.click("${folder(path)} > summary")
        page.waitForSelector("${folder(path)} > .tpl-level:not(.tpl-level-pending)")
    }

    private fun leaf(path: String) = "$panel a.tpl-leaf:has(span.tpl-label[title='$path'])"

    private fun titlesInTree(): List<String> =
        (
            page.evaluate(
                "s => [...document.querySelectorAll(s + ' .tpl-label[title], ' + s + ' .tpl-path[title]')]" +
                    ".map(e => e.getAttribute('title'))",
                panel,
            ) as List<*>
        ).map { it.toString() }

    /** Requests to the sidebar's level route, recorded as their `prefix` (null = the root/search). */
    private fun recordLevelRequests(into: MutableList<String?>) {
        page.onRequest { request ->
            val url = request.url()
            if (url.contains("/partials/pipelines?") || url.endsWith("/partials/pipelines")) {
                val prefix = Regex("[?&]prefix=([^&]*)").find(url)?.groupValues?.get(1)
                into += prefix?.let { java.net.URLDecoder.decode(it, Charsets.UTF_8) }
            }
        }
    }

    /** True for a sidebar level request for exactly [prefix] (encoded or not — Thymeleaf's choice). */
    private fun levelOf(prefix: String): java.util.function.Predicate<String> =
        java.util.function.Predicate { url ->
            url.contains("/partials/pipelines?") &&
                Regex("[?&]prefix=([^&]*)")
                    .find(url)
                    ?.groupValues
                    ?.get(1)
                    ?.let { java.net.URLDecoder.decode(it, Charsets.UTF_8) } == prefix
        }

    /** Holds every captured response until [release]; the capture-and-hold idiom of ShellBusyBrowserTest. */
    private inner class Hold(
        private val pattern: java.util.function.Predicate<String>,
    ) {
        private val held = mutableListOf<Pair<Route, APIResponse>>()

        @Suppress("SwallowedException") // a dead request has nothing to hold; see the catch
        fun install(fetch: (Route) -> APIResponse = { it.fetch() }) {
            page.route(pattern) { route ->
                try {
                    held.add(route to fetch(route))
                } catch (e: PlaywrightException) {
                    // The request died before it could be held — nothing to release.
                }
            }
        }

        /** Pumps the driver until the handler has captured [n] responses (never a sleep). */
        fun awaitCaptured(n: Int) {
            val deadline = System.currentTimeMillis() + 15_000
            while (held.size < n) {
                check(System.currentTimeMillis() < deadline) { "captured ${held.size} of $n held responses" }
                page.evaluate("() => 0")
            }
        }

        @Suppress("SwallowedException") // a dead route (its document navigated away) is the point
        fun release(): Int {
            var delivered = 0
            held.forEach { (route, response) ->
                try {
                    route.fulfill(Route.FulfillOptions().setResponse(response))
                    delivered += 1
                } catch (e: PlaywrightException) {
                    // fulfilling a route whose page is gone
                }
            }
            held.clear()
            page.unroute(pattern)
            return delivered
        }
    }

    private fun noToast() = page.locator("#toast .ds-toast").count() shouldBe 0

    // ------------------------------------------------------------------ restore + reveal

    @Test
    fun `A12 - a full-document entry reveals the current leaf's folders one level per request, and nothing unrelated`() {
        page.setViewportSize(1440, 900)
        loginReadyUser("p350rev")
        val id = seedPipeline("acme/a1/a2/target")
        seedPipeline("acme/a1/other/y")
        seedPipeline("acme/b1/x")
        seedPipeline("trade/t1/z")
        // The tree was opened once (and nothing expanded): the remembered state is "open".
        page.navigate("$baseUrl/dashboard")
        openTree()

        val requests = mutableListOf<String?>()
        recordLevelRequests(requests)
        page.navigate("$baseUrl/pipelines/$id")
        page.waitForSelector(".pe-root")
        page.waitForSelector("${leaf("acme/a1/a2/target")}[aria-current='page']")
        settle()

        // The root, then exactly the three folders above the leaf — no sibling branch.
        requests shouldBe listOf(null, "acme", "acme/a1", "acme/a1/a2")
        val titles = titlesInTree()
        (titles.contains("acme/b1/x") || titles.contains("acme/a1/other/y") || titles.contains("trade/t1/z")) shouldBe false
        page.locator(leaf("acme/a1/a2/target")).getAttribute("aria-selected") shouldBe "true"
        // The reveal scrolled the region (only the region) so the leaf is inside it.
        page.evaluate(
            """([l, r]) => { const a = document.querySelector(l).getBoundingClientRect();
                             const b = document.querySelector(r).getBoundingClientRect();
                             return a.top >= b.top - 1 && a.bottom <= b.bottom + 1; }""",
            arrayOf(leaf("acme/a1/a2/target"), "$panel [data-nav-tree-scroll]"),
        ) shouldBe true
        // The opened path is now remembered for this workspace (paths only).
        val stored =
            page.evaluate(
                "() => Object.entries(localStorage).filter(([k]) => k.startsWith('dp-nav:pipelines:')).map(([, v]) => v).join()",
            ) as String
        stored shouldContain "\"acme/a1/a2\""
        consoleErrors.shouldBeEmpty()
    }

    @Test
    fun `A11 - a current leaf past the first page of its level is walked to through the level's pager`() {
        page.setViewportSize(1440, 900)
        loginReadyUser("p350page")
        // 26 + 1 leaves in one folder: the level pages at 25, and the target sorts LAST.
        (0 until 26).forEach { seedPipeline("acme/many/p" + it.toString().padStart(2, '0')) }
        val id = seedPipeline("acme/many/zz_target")
        page.navigate("$baseUrl/dashboard")
        openTree()

        val requests = mutableListOf<String>()
        page.onRequest { request -> if (request.url().contains("/partials/pipelines?prefix=")) requests += request.url() }
        page.navigate("$baseUrl/pipelines/$id")
        page.waitForSelector("${leaf("acme/many/zz_target")}[aria-current='page']")
        // The second page was asked for by offset — the walk, not a full listing.
        requests.any { it.contains("prefix=acme%2Fmany&offset=25") || it.contains("prefix=acme/many&offset=25") } shouldBe true
        page.locator("${folder("acme/many")} .tpl-leaf").count() shouldBe 2 // page 2 of 27 rows
        consoleErrors.shouldBeEmpty()
    }

    // ------------------------------------------------------------------ failure + retry

    @Test
    fun `A11 - a refused level is a visible row with a working Retry - no silent spinner, no toast`() {
        page.setViewportSize(1440, 900)
        loginReadyUser("p350err")
        seedPipeline("acme/fails/leaf_one")
        page.navigate("$baseUrl/dashboard")
        openTree()

        val acmeLevel = levelOf("acme")
        page.route(acmeLevel) { route -> route.fulfill(Route.FulfillOptions().setStatus(500).setBody("boom")) }
        page.click("${folder("acme")} > summary")
        page.waitForSelector("${folder("acme")} .app-nav-tree-error")
        page.locator("${folder("acme")} .app-nav-tree-error").innerText() shouldContain "could not be loaded"
        page.locator("${folder("acme")} .ds-spinner").count() shouldBe 0
        noToast()

        page.unroute(acmeLevel)
        page.click("${folder("acme")} [data-nav-tree-retry]")
        page.waitForSelector("${folder("acme")} > .tpl-level:not(.tpl-level-pending) .tpl-summary")
        page.locator("${folder("acme")} .app-nav-tree-error").count() shouldBe 0
        expand("acme/fails")
        page.waitForSelector(leaf("acme/fails/leaf_one"))
        noToast()
    }

    // ------------------------------------------------------------------ the admission guard

    /** True for a sidebar SEARCH request (a `q` on the nav-scoped wrapper route). */
    private val navSearch =
        java.util.function.Predicate<String> { url ->
            url.contains("/partials/pipelines?") && url.contains("scope=nav") &&
                url.contains("q=")
        }

    @Test
    fun `A12 - an old query's page that lands after a NEW query was typed is dropped - its positive control lands`() {
        // The stale answer that CAN do harm: two answers aimed at the SAME, still-attached root —
        // the old query's "Next" (the pager's request) and the new query's results (the box's).
        // Without the generation guard, the old page landing first replaces the root and the new
        // results then land in the detached copy: the box says "omega", the tree shows "alpha".
        page.setViewportSize(1440, 900)
        loginReadyUser("p350gen")
        (0 until 27).forEach { seedPipeline("acme/gen/alpha_" + it.toString().padStart(2, '0')) }
        seedPipeline("acme/gen/omega_only")
        page.navigate("$baseUrl/dashboard")
        openTree()
        page.fill("$panel [data-nav-tree-search]", "alpha")
        page.waitForSelector("#pipeline-nav-root a.tpl-result")
        page.locator("#pipeline-nav-root a.tpl-result").count() shouldBe 25

        // Positive control: the old query's page 2, held and released with nothing changed, lands.
        val control = Hold(navSearch)
        control.install()
        page.click("#pipeline-nav-root button:has-text('Next')")
        control.awaitCaptured(1)
        control.release() shouldBe 1
        page.waitForFunction("() => document.querySelectorAll('#pipeline-nav-root a.tpl-result').length === 2")
        page.click("#pipeline-nav-root button:has-text('Previous')")
        page.waitForFunction("() => document.querySelectorAll('#pipeline-nav-root a.tpl-result').length === 25")

        // The guard: hold BOTH — the old query's Next, then the new query — and release the OLD one first.
        val hold = Hold(navSearch)
        hold.install()
        page.click("#pipeline-nav-root button:has-text('Next')")
        hold.awaitCaptured(1)
        page.fill("$panel [data-nav-tree-search]", "omega")
        hold.awaitCaptured(2)
        hold.release() shouldBe 2 // in capture order: the stale page first
        page.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE)
        settle()
        val shown =
            page.evaluate(
                "() => [...document.querySelectorAll('#pipeline-nav-root .tpl-path')].map(e => e.getAttribute('title'))",
            ) as List<*>
        shown shouldBe listOf("acme/gen/omega_only")
        noToast()
        consoleErrors.shouldBeEmpty()
    }

    @Test
    fun `A12 - another workspace's answer never joins the rows, a visible notice and no toast - its own-workspace control lands`() {
        page.setViewportSize(1440, 900)
        val other = loginReadyUser("p350ws")
        // The SAME folder exists in both workspaces with different leaves, so the other
        // workspace's answer for `acme/home` really has rows to leak.
        seedPipeline("acme/home/b_only")
        val mine = newWorkspaceName()
        createWorkspace(mine)
        seedPipeline("acme/home/a_only")
        page.navigate("$baseUrl/dashboard")
        openTree()

        // Control: the request re-fetched with THIS document's workspace lands normally.
        val control = Hold(levelOf("acme"))
        control.install { route -> route.fetch(Route.FetchOptions().setHeaders(route.request().headers() + ("dp-workspace" to mine))) }
        page.click("${folder("acme")} > summary")
        control.awaitCaptured(1)
        control.release()
        page.waitForSelector("${folder("acme/home")}")

        // The guard: the same level answered under the OTHER workspace (the user is a member of
        // both, so the server renders it) — its stamp names that workspace.
        val foreign = Hold(levelOf("acme/home"))
        foreign.install { route -> route.fetch(Route.FetchOptions().setHeaders(route.request().headers() + ("dp-workspace" to other))) }
        page.click("${folder("acme/home")} > summary")
        foreign.awaitCaptured(1)
        foreign.release()
        // Whichever way the answer is decided, the folder stops waiting: refused (the notice) or
        // swapped (its level lands). The ROWS are the assertion; the notice is the second one.
        page.waitForFunction(
            "s => !!document.querySelector(s + ' [data-nav-tree-reload]') || !document.querySelector(s + ' .tpl-level-pending')",
            panel,
        )
        settle()
        titlesInTree().contains("acme/home/b_only") shouldBe false
        page.locator("$panel [data-nav-tree-reload]").count() shouldBe 1
        page.locator("$panel .app-nav-tree-error").innerText() shouldContain "another workspace"
        noToast()
        consoleErrors.shouldBeEmpty()
    }

    @Test
    fun `A12 - a response held across a workspace SWITCH never reaches the new workspace's tree`() {
        page.setViewportSize(1440, 900)
        val first = loginReadyUser("p350sw")
        seedPipeline("acme/first_only/f")
        val second = newWorkspaceName()
        createWorkspace(second)
        seedPipeline("acme/second_only/s")
        // Back in the first workspace, tree open, a folder answer held in flight.
        enterWorkspace(page, first)
        openTree()
        val hold = Hold(levelOf("acme"))
        hold.install()
        page.click("${folder("acme")} > summary")
        hold.awaitCaptured(1)

        // The switch is a FULL navigation; the held answer belongs to the old document.
        page.selectOption("#workspace-switcher", arrayOf(second), Page.SelectOptionOptions().setForce(true))
        page.waitForURL("**/dashboard")
        page.waitForSelector("[data-nav-branch='pipelines']")
        hold.release()
        // The new workspace's tree starts from ITS OWN state key (never opened here, so closed)
        // and shows only its own rows when opened.
        page.locator(panel).isVisible shouldBe false
        openTree()
        expand("acme")
        titlesInTree() shouldContainExactlyInAnyOrder listOf("acme", "acme/second_only")
        page.evaluate("() => document.querySelector('#nav-tree-pipelines').getAttribute('data-nav-workspace')") shouldBe second
        noToast()
        consoleErrors.shouldBeEmpty()
    }

    @Test
    fun `A12 - a changed lens resets the tree and re-reads the root - the stale level never joins`() {
        page.setViewportSize(1440, 900)
        val workspace = loginReadyUser("p350lens")
        seedPipeline("acme/lensed/l")
        page.navigate("$baseUrl/dashboard")
        openTree()
        val requests = mutableListOf<String?>()
        recordLevelRequests(requests)

        // The folder's FIRST answer arrives stamped with a NARROWER lens than the tree's rows:
        // the tree must not mix them — it resets and asks for the root again under the view the
        // server now applies, then re-opens the remembered folder under that view.
        val acmeLevel = levelOf("acme")
        val rewritten =
            java.util.concurrent.atomic
                .AtomicInteger(0)
        page.route(acmeLevel) { route ->
            if (rewritten.getAndIncrement() > 0) {
                route.resume()
                return@route
            }
            val response = route.fetch()
            route.fulfill(
                Route
                    .FulfillOptions()
                    .setResponse(response)
                    .setHeaders(response.headers() + ("dp-nav-stamp" to "$workspace|lens")),
            )
        }
        page.click("${folder("acme")} > summary")
        page.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE)
        page.waitForSelector("${folder("acme")} > .tpl-level:not(.tpl-level-pending) .tpl-summary")
        page.unroute(acmeLevel)
        // The stale level was refused (the reset), the root re-read, the folder re-opened ONCE.
        requests shouldBe listOf("acme", null, "acme")
        page.evaluate("() => window.DpNavTree.trees()[0].lens") shouldBe "all"
        noToast()
    }

    // ------------------------------------------------------------------ history + handlers

    @Test
    @Suppress("LongMethod") // one session on purpose: the listener counts are only meaningful across ONE document
    fun `A12 - boosted and history navigation keep ONE set of tree handlers and re-mark the leaf - the templates tree keeps its keyboard`() {
        page.setViewportSize(1440, 900)
        loginReadyUser("p350hist")
        val id = seedPipeline("acme/hist/current")
        val tplHash =
            hashOf(
                must(
                    "POST",
                    "/api/v1/templates",
                    """{"id":"acme/tpl/one","type":"sql","dialect":"H2","display_name":"one",""" +
                        """"description":"350","imports":[],"body":"SELECT 1"}""",
                ),
            )
        must("POST", "/api/v1/templates/release", """{"name":"acme/tpl/one"}""", ifMatch = tplHash)
        // Count every listener the two tree files install, by source file (from the stack).
        page.addInitScript(
            """(() => {
              const counts = {};
              const add = EventTarget.prototype.addEventListener;
              EventTarget.prototype.addEventListener = function (type, fn, opts) {
                const stack = (new Error()).stack || '';
                const file = stack.includes('template-explorer.js') ? 'engine' : stack.includes('nav-tree.js') ? 'nav' : null;
                if (file && (this === document || this === document.body || this === window)) {
                  counts[file + ':' + type] = (counts[file + ':' + type] || 0) + 1;
                }
                return add.call(this, type, fn, opts);
              };
              window.__p350Listeners = counts;
            })();""",
        )
        page.navigate("$baseUrl/dashboard")
        openTree()
        page.navigate("$baseUrl/pipelines/$id") // full-document graph entry
        page.waitForSelector("${leaf("acme/hist/current")}[aria-current='page']")
        val initial = listeners()

        // Boosted: the Templates catalog (a page with no tree of its own since #398), the
        // Pipelines catalog, the Templates catalog again. The Templates sidebar branch is the
        // keyboard's surface now: open it and drive it with the keys (the nav context's arrows
        // move FOCUS only — it selects nothing, so the assertion is where focus lands).
        page.click(".app-nav-link[data-nav-section='/templates']")
        page.waitForSelector("#template-list-wrapper")
        noCurrentLeaf() // not a pipeline page: the re-mark (settle, then the next task) clears it
        page.click(".app-nav-link[data-nav-section='/pipelines']")
        page.waitForSelector("#pipeline-list-wrapper")
        page.click(".app-nav-link[data-nav-section='/templates']")
        page.waitForSelector("#template-list-wrapper")
        page.click("[data-nav-branch='templates'] [data-nav-tree-toggle]")
        page.waitForSelector("#template-nav-root .tpl-tree .tpl-summary")
        // The templates tree's keyboard (its context is wired once, by the one engine) still
        // moves through it after the boosted swaps — and htmx has processed the swapped-in
        // summary (its `click once` is bound a beat after the swap — the window nav-tree.js's
        // restore defers past; a person cannot hit it).
        page.waitForFunction("() => document.querySelector('#nav-tree-templates').__tplxWired === true")
        page.waitForFunction(
            "() => { const s = document.querySelector('#template-nav-root .tpl-summary'); " +
                "const d = s && s['htmx-internal-data']; return !!(d && d.initHash); }",
        )
        page.focus("#template-nav-root .tpl-summary")
        page.keyboard().press("ArrowRight")
        page.waitForSelector("#template-nav-root details.tpl-folder[open] .tpl-level:not(.tpl-level-pending)")
        page.keyboard().press("ArrowDown")
        page.waitForFunction(
            "() => !!document.activeElement?.matches('#template-nav-root .tpl-summary, #template-nav-root .tpl-leaf') " +
                "&& document.activeElement !== document.querySelector('#template-nav-root .tpl-summary')",
        )

        // History: back to the Pipelines catalog, back to the Templates catalog, back to the workspace (a restore).
        page.goBack()
        page.waitForSelector("#pipeline-list-wrapper")
        page.goBack()
        page.waitForSelector("#template-list-wrapper")
        page.goBack()
        page.waitForURL("**/pipelines/$id")
        page.waitForSelector("${leaf("acme/hist/current")}[aria-current='page']")
        page.goForward()
        page.waitForSelector("#template-list-wrapper")
        noCurrentLeaf()

        // Not one tree listener more than the document started with.
        listeners() shouldBe initial
        println("350-measure listeners=$initial")
        consoleErrors.shouldBeEmpty()
    }

    private fun noCurrentLeaf() {
        page.waitForFunction("s => document.querySelectorAll(s + \" [aria-current='page']\").length === 0", panel)
    }

    private fun listeners(): Map<*, *> = page.evaluate("() => Object.assign({}, window.__p350Listeners)") as Map<*, *>
}
