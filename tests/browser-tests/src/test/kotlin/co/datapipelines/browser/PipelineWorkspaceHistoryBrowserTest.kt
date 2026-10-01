package co.datapipelines.browser

import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.Route
import com.microsoft.playwright.options.WaitForSelectorState
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/**
 * #358 — the pipeline workspace survives a cached-history restore, driven on the real app:
 *
 *  1. ONE component, ONE request per click, on the RESTORED page as on the first load
 *     (the owner's standing requirement): after a genuine cache-hit Back/Forward the
 *     root carries exactly one Alpine component, the node-SQL pane exists at most once,
 *     and one Execute click fires exactly one version-pinned POST.
 *  2. The five lifecycle flows: first full load (control), a first BOOSTED entry from an
 *     unrelated page, repeated genuine cache-hit Back/Forward, a fresh full document with
 *     surviving session history, and old-cache migration (a body-shaped entry from the
 *     pre-`hx-history-elt` build must never be swapped INTO `#app-main` — vetoed at the
 *     cache-hit and re-fetched) beside its cache-miss control.
 *  3. A second Alpine in the fragment (the singleton gate) still yields one observer and
 *     one init: the runtime's catalog skips a global that already exists and the ignored
 *     root is activatable by the runtime alone.
 *  4. A run that outlives a navigation is NOT cancelled: the stream detaches on leaving,
 *     the restored page re-attaches through the replay stream, the execution finishes
 *     server-side, and the terminal toast arrives exactly once.
 *
 * Every restore assertion reads a COUNT (panes, `_x_dataStack`, POSTs, toasts, cache
 * events) — never "looks fine". The non-vacuity floor is the pre-fix red run: at
 * `37556e18` the same class reads 2 panes, a stacked component and 2 POSTs per click
 * after one restore (the lane's witnessed defect, kept in the evidence record).
 */
class PipelineWorkspaceHistoryBrowserTest : BrowserSuite() {
    private fun loginReadyUser(
        slug: String,
        workspaceName: String = "pws-" + generatedPassword("w").take(8).lowercase(),
    ) {
        val user =
            seedLocalUser(
                uniqueEmail(slug + generatedPassword("u").take(8)),
                generatedPassword("pw"),
                mustChange = false,
            )
        login(user.email, user.oneTimePassword)
        page.waitForURL("**/dashboard")
        createWorkspace(workspaceName)
    }

    /** One REST call in-page, with the session's CSRF pair (every non-GET carries it). */
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

    /** A lifecycle call that MUST succeed — the status is the assertion, the body the clue. */
    private fun must(
        method: String,
        path: String,
        body: String?,
        ifMatch: String? = null,
    ): String {
        val (status, text) = api(method, path, body, ifMatch)
        check(status == 200 || status == 201) { "$method $path -> $status: ${text.take(400)}" }
        return text
    }

    private fun hashOf(text: String): String = Regex(""""body_hash"\s*:\s*"([^"]+)"""").find(text)!!.groupValues[1]

    private fun templateBody(
        templateId: String,
        sql: String,
    ): String =
        """{"id":"$templateId","type":"sql","dialect":"H2","display_name":"tpl","description":"348-c lifecycle","imports":[],"body":"$sql"}"""

    private fun sqlBody(
        nodeId: String,
        templateId: String,
        templateVersion: Int,
    ): String =
        """{"name":"p348/sql_pipes","display_name":"SQL Pipes",""" +
            """"nodes":[{"id":"$nodeId","type":"DQL",""" +
            """"source":"tempdb","template":{"id":"$templateId","version":$templateVersion},"depends_on":[]}]}"""

    /**
     * The SQL lifecycle fixture (the #348-b shape): template v1 ("one") and v2 ("two")
     * both RELEASED; pipeline v1 (sql_v1 → tpl@1) and v2 (sql_v2 → tpl@2) both RELEASED,
     * current switched back to v1; a v3 draft. Distinct nodes AND distinct SQL per version.
     */
    private fun seedSqlVersions(slug: String): String {
        loginReadyUser(slug)
        // Per-test template id: the suite's database is per JVM, and a fixed id would
        // collide across the class's tests (template.version.conflict).
        val templateId = "test/p348c_sql_" + generatedPassword("t").take(6).lowercase()
        val tplV1Hash = hashOf(must("POST", "/api/v1/templates", templateBody(templateId, "SELECT 1 AS one")))
        must("POST", "/api/v1/templates/release", """{"name":"$templateId"}""", ifMatch = tplV1Hash)
        val tplV2Hash = hashOf(must("PUT", "/api/v1/templates", templateBody(templateId, "SELECT 2 AS two"), ifMatch = tplV1Hash))
        must("POST", "/api/v1/templates/release", """{"name":"$templateId"}""", ifMatch = tplV2Hash)

        val created = must("POST", "/api/v1/pipelines", sqlBody("sql_v1", templateId, 1))
        val uuid = "([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})"
        val id = Regex(""""id"\s*:\s*"$uuid"""").find(created)!!.groupValues[1]
        val v1Hash = hashOf(created)
        must("POST", "/api/v1/pipelines/$id/release", null, ifMatch = v1Hash)

        val v2Hash = hashOf(must("PUT", "/api/v1/pipelines/$id", sqlBody("sql_v2", templateId, 2), ifMatch = v1Hash))
        must("POST", "/api/v1/pipelines/$id/release", null, ifMatch = v2Hash)

        must(
            "PUT",
            "/api/v1/pipelines/$id",
            """{"name":"p348/ver_pipes","display_name":"Version Pipes","nodes":[{"id":"n_v3","type":"CALCULATOR",""" +
                """"kind":"fiscal_quarter","context_key":"run_q_n_v3",""" +
                """"inputs":{"date":"${'$'}current_date","fiscal_start":"${'$'}org_fiscal_start_date"}}]}""",
            ifMatch = v2Hash,
        )
        must("POST", "/api/v1/pipelines/$id/current", """{"version": 1}""")
        return id
    }

    /**
     * The cache counters live on the persistent <body>: they survive every restore.
     * `restore` is the sync point — a Back/Forward assertion reads the counts only
     * AFTER htmx's restore event, so no count is read against the outgoing DOM.
     */
    private fun seedHistoryCounters() {
        page.evaluate(
            """() => {
              const counters = { hit: 0, miss: 0, restore: 0 };
              window.__peHistoryShape = counters;
              document.body.addEventListener('htmx:historyCacheHit', () => counters.hit++);
              document.body.addEventListener('htmx:historyCacheMiss', () => counters.miss++);
              document.body.addEventListener('htmx:historyRestore', () => counters.restore++);
            }""",
        )
    }

    private fun waitForRestore(pastCount: Int) {
        page.waitForFunction(
            "(n) => window.__peHistoryShape && window.__peHistoryShape.restore > n",
            pastCount,
        )
    }

    private fun counter(key: String): Int =
        page.evaluate("k => (window.__peHistoryShape || {})[k] || 0", key) as Int

    /** The restored root's stacked-component count — 1 is the contract, more is the defect. */
    private fun stackDepth(): Int =
        (page.evaluate(
            "() => { const r = document.querySelector('#app-main .pe-root'); return r && r._x_dataStack ? r._x_dataStack.length : 0; }",
        ) as Number).toInt()

    /** The runtime's ONE activation completes when the root carries a bound component —
     *  the sync point every restore assertion reads after. */
    private fun waitActivated() {
        page.waitForFunction(
            "() => { const r = document.querySelector('#app-main .pe-root'); return !!(r && r._x_dataStack && r._x_dataStack.length >= 1); }",
        )
    }

    /** Live `#pe-node-sql` elements — the x-if pane renders one per LIVE component. */
    private fun sqlPaneCount(): Int = page.locator("#pe-node-sql").count()

    /** One runtime identity capture: the Alpine INSTANCE (marked on first sight), the runtime epoch. */
    private fun runtimeIdentity(): Map<*, *> =
        page.evaluate(
            """() => {
              if (window.Alpine && !window.Alpine.__peInstanceMark) {
                window.Alpine.__peInstanceMark = 'alpine-' + Math.random().toString(36).slice(2);
              }
              return {
                alpineMark: window.Alpine ? window.Alpine.__peInstanceMark : null,
                epoch: window.PEPipelineRuntime ? window.PEPipelineRuntime.epoch : null,
                activations: window.PEPipelineRuntime ? window.PEPipelineRuntime.activations : null,
              };
            }""",
        ) as Map<*, *>

    private fun openFirstCardSql(expectation: String) {
        page.locator(".pe-card-open").first().click()
        page.waitForSelector("#pe-node-sql .pe-sql-code")
        page.locator("#pe-node-sql").innerText() shouldContain expectation
    }

    private fun executeOnce(version: Int) {
        val posts = mutableListOf<String>()
        page.route(EXECUTE_PATTERN) { route ->
            if (route.request().method() == "POST") posts += route.request().postData() ?: ""
            route.resume()
        }
        page.locator("[data-verb='pipeline-execute']").click()
        page.locator("[data-verb='pipeline-execute']:not([disabled])").waitFor(
            Locator.WaitForOptions().setTimeout(90_000.0),
        )
        page.unroute(EXECUTE_PATTERN)
        posts.size shouldBe 1
        versionOf(posts.single()) shouldBe version
    }

    private fun leaveThroughTheUi() {
        page.locator(".pe-back").click()
        page.waitForURL("**/pipelines")
        page.waitForSelector("#pipeline-list-wrapper")
    }

    @Test
    fun `a full editor load is one component, one pane and one pinned POST (control)`() {
        val id = seedSqlVersions("pwsh" + generatedPassword("s").take(6).lowercase())
        page.navigate("$baseUrl/pipelines/$id?version=2")
        page.waitForSelector(".pe-root")
        waitActivated()

        stackDepth() shouldBe 1
        sqlPaneCount() shouldBe 0
        page.locator(".pe-vchip").innerText() shouldBe "v2 · released"

        openFirstCardSql("SELECT 2 AS two")
        sqlPaneCount() shouldBe 1
        executeOnce(2)
        page.locator(".pe-status:has-text('Completed')").waitFor()
    }

    @Test
    fun `repeated cache-hit restores keep one component, one pane and one pinned POST`() {
        val id = seedSqlVersions("pwsr" + generatedPassword("s").take(6).lowercase())
        page.navigate("$baseUrl/pipelines/$id?version=2")
        page.waitForSelector(".pe-root")
        seedHistoryCounters()
        val identityBefore = runtimeIdentity()

        repeat(2) {
            leaveThroughTheUi()
            val hitsBefore = counter("hit")
            val restoresBefore = counter("restore")
            page.goBack()
            waitForRestore(restoresBefore)
            waitActivated()
            page.waitForSelector(".pe-root")

            counter("hit") shouldBeGreaterThanOrEqual hitsBefore + 1
            counter("miss") shouldBe 0
            stackDepth() shouldBe 1
            sqlPaneCount() shouldBe 0
            val identityAfter = runtimeIdentity()
            identityAfter["alpineMark"] shouldBe identityBefore["alpineMark"]
            identityAfter["epoch"] shouldBe identityBefore["epoch"]
            (identityAfter["activations"] as? Number)?.toInt()
                ?.shouldBeGreaterThanOrEqual(((identityBefore["activations"] as? Number)?.toInt() ?: 0) + 1)

            // The restored page works like the first load: the SAME body, ONE pane, ONE POST.
            openFirstCardSql("SELECT 2 AS two")
            sqlPaneCount() shouldBe 1
            executeOnce(2)
            page.locator(".pe-status:has-text('Completed')").waitFor()

            // Forward to the list and back again: one restore cycle per iteration.
            page.goForward()
            page.waitForURL("**/pipelines")
            val restoresBeforeBack = counter("restore")
            page.goBack()
            waitForRestore(restoresBeforeBack)
            waitActivated()
            page.waitForSelector(".pe-root")
            stackDepth() shouldBe 1
        }
    }

    @Test
    fun `a first BOOSTED entry from an unrelated page boots one component and enters the cache`() {
        val id = seedSqlVersions("pwsb" + generatedPassword("s").take(6).lowercase())
        page.navigate("$baseUrl/pipelines")
        page.waitForSelector("#pipeline-list-wrapper")
        seedHistoryCounters()

        // A boosted anchor into the workspace (plain anchors inherit #app-main's
        // hx-boost): a genuine htmx navigation — swap, URL push, and the outgoing
        // /pipelines page into the cache.
        page.evaluate(
            """(url) => {
              const a = document.createElement('a');
              a.href = url;
              a.id = 'pe-boosted-entry';
              a.textContent = 'workspace';
              document.querySelector('#app-main').appendChild(a);
            }""",
            "/pipelines/$id?version=2",
        )
        page.locator("#pe-boosted-entry").click()
        page.waitForSelector(".pe-root")
        waitActivated()

        stackDepth() shouldBe 1
        page.locator(".pe-vchip").innerText() shouldBe "v2 · released"

        // Leaving through the UI (a boosted swap) is what SAVES the workspace into
        // the scoped cache — the record flow 2 asks for: a main-shaped entry (the
        // region only), scripts inert inside the catalog template, the root ignored.
        // A raw browser Back across the document-load boundary performs no htmx save
        // (and in this Chromium lands a fresh document, its own bfcache semantics);
        // the cache-hit restore cycle is `repeated cache-hit restores`' contract.
        leaveThroughTheUi()
        val cached =
            page.evaluate(
                """(url) => {
                  const cache = JSON.parse(sessionStorage.getItem('htmx-history-cache') || '[]');
                  const item = cache.find((i) => i.url === url);
                  return item ? item.content : null;
                }""",
                "/pipelines/$id?version=2",
            ) as String?
        check(cached != null) { "leaving the workspace must save its entry" }
        cached shouldNotContain "id=\"app-main\""
        cached shouldContain "x-ignore"
        cached shouldContain "pe-runtime-scripts"
    }

    @Test
    fun `a fresh full document with surviving session history boots exactly one component`() {
        val id = seedSqlVersions("pwsf" + generatedPassword("s").take(6).lowercase())
        page.navigate("$baseUrl/pipelines/$id?version=2")
        page.waitForSelector(".pe-root")
        seedHistoryCounters()
        leaveThroughTheUi()
        val restoresBeforeBack = counter("restore")
        page.goBack()
        waitForRestore(restoresBeforeBack)
        waitActivated()
        page.waitForSelector(".pe-root")
        stackDepth() shouldBe 1

        // The reload: a brand-new document over a session that still holds the cache.
        page.reload()
        page.waitForSelector(".pe-root")
        seedHistoryCounters()
        stackDepth() shouldBe 1
        sqlPaneCount() shouldBe 0

        // And the cached history still works beside the fresh document: one restore,
        // one init, one POST.
        leaveThroughTheUi()
        val restoresBeforeTail = counter("restore")
        page.goBack()
        waitForRestore(restoresBeforeTail)
        waitActivated()
        page.waitForSelector(".pe-root")
        stackDepth() shouldBe 1
        openFirstCardSql("SELECT 2 AS two")
        executeOnce(2)
    }

    @Test
    fun `an old body-shaped cache entry is refused - never swapped into app-main`() {
        val id = seedSqlVersions("pwsm" + generatedPassword("s").take(6).lowercase())
        page.navigate("$baseUrl/pipelines/$id?version=2")
        page.waitForSelector(".pe-root")
        seedHistoryCounters()
        leaveThroughTheUi()

        // Fabricate the OLD build's entry shape: the pre-hx-history-elt build cached the
        // whole <body>, so the workspace region rides INSIDE a serialized <main>. htmx
        // stores no marker of which element an entry holds — the content is the tell.
        val seeded =
            page.evaluate(
                """(url) => {
                  const cache = JSON.parse(sessionStorage.getItem('htmx-history-cache') || '[]');
                  const item = cache.find((i) => i.url === url);
                  if (!item) return 'no entry';
                  item.content = '<div>stale-footer</div><main id="app-main"><p>stale-workspace</p>' + item.content + '</main>';
                  sessionStorage.setItem('htmx-history-cache', JSON.stringify(cache));
                  return 'seeded';
                }""",
                "/pipelines/$id?version=2",
            )
        seeded shouldBe "seeded"

        val refetched = mutableListOf<String>()
        page.onRequest { request ->
            if (request.url().contains("/pipelines/$id")) refetched += request.url()
        }

        // The stale hit is dropped and the restore takes a full fetch (htmx's own
        // refreshOnHistoryMiss policy — a cancelled cache hit in htmx 2.0.10 would
        // simply die, so the guard reloads): the stale body is NEVER swapped into
        // the region, and the cache no longer holds it.
        page.goBack()
        page.waitForLoadState()
        page.waitForSelector(".pe-root")
        waitActivated()

        page.url() shouldContain "/pipelines/$id?version=2"
        check(refetched.isNotEmpty()) { "the refused entry must be answered by a server fetch" }
        val region = page.evaluate("() => document.querySelector('#app-main').innerHTML") as String
        region shouldNotContain "stale-workspace"
        region shouldNotContain "stale-footer"
        (page.evaluate("() => document.querySelector('#app-main').querySelectorAll('main').length") as Number).toInt() shouldBe 0
        page.evaluate(
            """() => (sessionStorage.getItem('htmx-history-cache') || '').includes('stale-workspace')""",
        ) shouldBe false

        // …and the re-fetched page is one healthy component.
        stackDepth() shouldBe 1
        sqlPaneCount() shouldBe 0
        openFirstCardSql("SELECT 2 AS two")
    }

    @Test
    fun `an evicted entry is the cache-miss control - server fetch, one init`() {
        val id = seedSqlVersions("pwsi" + generatedPassword("s").take(6).lowercase())
        page.navigate("$baseUrl/pipelines/$id?version=2")
        page.waitForSelector(".pe-root")
        seedHistoryCounters()
        leaveThroughTheUi()

        page.evaluate(
            """(url) => {
              const cache = JSON.parse(sessionStorage.getItem('htmx-history-cache') || '[]');
              sessionStorage.setItem('htmx-history-cache', JSON.stringify(cache.filter((i) => i.url !== url)));
            }""",
            "/pipelines/$id?version=2",
        )

        val restoresBeforeBack = counter("restore")
        page.goBack()
        waitForRestore(restoresBeforeBack)
        waitActivated()
        page.waitForSelector(".pe-root")
        counter("miss") shouldBeGreaterThanOrEqual 1
        stackDepth() shouldBe 1
        openFirstCardSql("SELECT 2 AS two")
    }

    @Test
    fun `a second Alpine in the fragment still boots ONE observer and binds ONE component`() {
        val id = seedSqlVersions("pwsg" + generatedPassword("s").take(6).lowercase())
        // The singleton gate's fault injection: a second bare Alpine tag, exactly where a
        // regression would put it (back in the fragment, after the runtime). The loader
        // must skip its own catalog entry (the global exists) and the ignored root must
        // stay activatable by the runtime alone.
        page.route("**/pipelines/$id**") { route ->
            val original = route.fetch().text()
            route.fulfill(
                Route.FulfillOptions().setContentType("text/html").setBody(
                    original.replace(
                        "</main>",
                        "<script src=\"/vendor/alpinejs/alpine.min.js\"></script></main>",
                    ),
                ),
            )
        }
        page.navigate("$baseUrl/pipelines/$id?version=2")
        page.unroute("**/pipelines/$id**")
        page.waitForSelector(".pe-root")

        stackDepth() shouldBe 1
        sqlPaneCount() shouldBe 0

        // And the restore beside the planted tag: still one component, still one init.
        seedHistoryCounters()
        leaveThroughTheUi()
        val restoresBeforeBack = counter("restore")
        page.goBack()
        waitForRestore(restoresBeforeBack)
        waitActivated()
        page.waitForSelector(".pe-root")
        stackDepth() shouldBe 1
        sqlPaneCount() shouldBe 0
        openFirstCardSql("SELECT 2 AS two")
    }

    @Test
    fun `navigation detaches the stream without cancelling - the restored page follows the run to ONE toast`() {
        val user =
            seedLocalUser(
                uniqueEmail("pwsc" + generatedPassword("u").take(8)),
                generatedPassword("pw"),
                mustChange = false,
            )
        login(user.email, user.oneTimePassword)
        page.waitForURL("**/dashboard")
        createWorkspace("pwsc" + generatedPassword("w").take(8).lowercase())

        val datasource = "pwc-src-" + generatedPassword("d").take(6).lowercase()
        EditorRunFixtures.registerSourceDatasource(page, baseUrl, datasource) shouldBe emptyList<String>()
        val name = "test/p348c_" + generatedPassword("p").take(8).lowercase()
        val id = EditorRunFixtures.createStageChainPipeline(page, name, datasource, ROWS)
        page.navigate("$baseUrl/pipelines/$id")
        page.waitForSelector(".pe-root")
        seedHistoryCounters()

        val deletes = mutableListOf<String>()
        page.route("**/api/v1/executions/*") { route ->
            if (route.request().method() == "DELETE") deletes += route.request().url()
            route.resume()
        }

        // Start the run and LEAVE while it is still going: the detach must be a detach.
        page.locator("[data-verb='pipeline-execute']").click()
        page.locator(".pe-status:has-text('Running')").waitFor()
        leaveThroughTheUi()
        val restoresBeforeBack = counter("restore")
        page.goBack()
        waitForRestore(restoresBeforeBack)
        waitActivated()
        page.waitForSelector(".pe-root")

        stackDepth() shouldBe 1

        // The restored page follows the run to its terminal — ONE completion toast.
        page.locator(".pe-status:has-text('Completed')").waitFor(
            Locator.WaitForOptions().setTimeout(180_000.0),
        )
        val completed = page.locator(".ds-toast:has-text('Pipeline completed')")
        completed.first().waitFor(Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE))
        completed.count() shouldBe 1

        // The run finished server-side and was never cancelled: the newest execution of
        // this pipeline is COMPLETED, and the wire holds no DELETE.
        val execution =
            page.evaluate(
                """async (pipelineId) => {
                  const res = await fetch('/api/v1/executions?pipeline_id=' + pipelineId + '&limit=1', { credentials: 'same-origin' });
                  const data = await res.json();
                  const items = (data.data && data.data.items) || data.items || [];
                  return items.length ? { id: items[0].id, status: items[0].status } : null;
                }""",
                id,
            ) as Map<*, *>
        ((execution["status"] as String)).lowercase() shouldBe "success"
        deletes.shouldBeEmpty()

        // The record cleared at the terminal: restoring again never re-announces.
        page.evaluate("() => window.__peLiveExecution || null") shouldBe null
        leaveThroughTheUi()
        val restoresBeforeTail = counter("restore")
        page.goBack()
        waitForRestore(restoresBeforeTail)
        waitActivated()
        page.waitForSelector(".pe-root")
        stackDepth() shouldBe 1
        completed.count() shouldBeLessThanOrEqual 1
        deletes.shouldBeEmpty()
    }

    private companion object {
        const val EXECUTE_PATTERN = "**/api/v1/pipelines/*/execute"

        /** A run long enough to outlive a navigation, short enough to keep the suite honest. */
        const val ROWS = 400_000

        /** The execute POST's pinned version, read off the captured wire body. */
        fun versionOf(postBody: String): Int = Regex(""""version"\s*:\s*(\d+)""").find(postBody)!!.groupValues[1].toInt()
    }
}
