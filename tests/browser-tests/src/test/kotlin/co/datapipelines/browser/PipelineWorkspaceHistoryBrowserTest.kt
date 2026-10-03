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
 *
 * #402 adds the workspace's OWN entries beside htmx's: in-page version and tab switches push,
 * Back/Forward replay them on the live instance, and a boosted leave after a switch is a cache
 * HIT whose restored instance replays the rest. The base's reds (Back left the workspace; the
 * leave-and-Back was a MISS) are in the lane's evidence record.
 */
@Suppress("LargeClass") // #358's restore cases and #402's replay cases share one fixture set and its probes
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
        """{"id":"$templateId","type":"sql","dialect":"H2","display_name":"tpl",""" +
            """"description":"348-c lifecycle","imports":[],"body":"$sql"}"""

    private fun sqlBody(
        nodeId: String,
        templateId: String,
        templateVersion: Int,
        description: String = "",
    ): String {
        // Emitted only when given, so every other case's request body is unchanged.
        val descriptionField =
            if (description.isEmpty()) "" else """"description":"${description.replace("\\", "\\\\").replace("\"", "\\\"")}","""
        return """{"name":"p348/sql_pipes","display_name":"SQL Pipes",""" + descriptionField +
            """"nodes":[{"id":"$nodeId","type":"DQL",""" +
            """"source":"tempdb","template":{"id":"$templateId","version":$templateVersion},"depends_on":[]}]}"""
    }

    /**
     * The SQL lifecycle fixture (the #348-b shape): template v1 ("one") and v2 ("two")
     * both RELEASED; pipeline v1 (sql_v1 → tpl@1) and v2 (sql_v2 → tpl@2) both RELEASED,
     * current switched back to v1; a v3 draft. Distinct nodes AND distinct SQL per version.
     */
    private fun seedSqlVersions(
        slug: String,
        description: String = "",
    ): String {
        loginReadyUser(slug)
        // Per-test template id: the suite's database is per JVM, and a fixed id would
        // collide across the class's tests (template.version.conflict).
        val templateId = "test/p348c_sql_" + generatedPassword("t").take(6).lowercase()
        val tplV1Hash = hashOf(must("POST", "/api/v1/templates", templateBody(templateId, "SELECT 1 AS one")))
        must("POST", "/api/v1/templates/release", """{"name":"$templateId"}""", ifMatch = tplV1Hash)
        val tplV2Hash = hashOf(must("PUT", "/api/v1/templates", templateBody(templateId, "SELECT 2 AS two"), ifMatch = tplV1Hash))
        must("POST", "/api/v1/templates/release", """{"name":"$templateId"}""", ifMatch = tplV2Hash)

        val created = must("POST", "/api/v1/pipelines", sqlBody("sql_v1", templateId, 1, description))
        val uuid = "([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})"
        val id = Regex(""""id"\s*:\s*"$uuid"""").find(created)!!.groupValues[1]
        val v1Hash = hashOf(created)
        must("POST", "/api/v1/pipelines/$id/release", null, ifMatch = v1Hash)

        val v2Hash = hashOf(must("PUT", "/api/v1/pipelines/$id", sqlBody("sql_v2", templateId, 2, description), ifMatch = v1Hash))
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

    private fun counter(key: String): Int = page.evaluate("k => (window.__peHistoryShape || {})[k] || 0", key) as Int

    /** The restored root's stacked-component count — 1 is the contract, more is the defect. */
    private fun stackDepth(): Int = (page.evaluate(STACK_DEPTH_JS) as Number).toInt()

    /** The runtime's ONE activation completes when the root carries a bound component —
     *  the sync point every restore assertion reads after. */
    private fun waitActivated() {
        page.waitForFunction(ACTIVATED_JS)
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

    /** The slow-run fixture: a workspace, a Postgres source and the #151 stage chain. */
    private fun seedSlowRunPipeline(): String {
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
        return EditorRunFixtures.createStageChainPipeline(page, name, datasource, ROWS)
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
            val before = (identityBefore["activations"] as? Number)?.toInt() ?: 0
            val after = (identityAfter["activations"] as? Number)?.toInt() ?: 0
            after.shouldBeGreaterThanOrEqual(before + 1)

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
    fun `a main-shaped entry whose TEXT carries the id literal is a cache hit - no reload (#364)`() {
        // The user-authored text node: the pipeline's description, rendered by the Overview
        // pane's `x-text` (hidden, but always in the DOM — and so in the cached snapshot).
        // innerHTML serialisation escapes `<`, `>` and `&` in text and NOT the double quote,
        // so this is the literal the old substring guard mistook for a body-shaped entry.
        // The description also types the whole start tag: its `<` is escaped in the text
        // node, and in the editor's raw-text JSON blob (where `<` survives ScriptSafeJson)
        // every quote is a backslash-escaped one — neither can forge the opening tag.
        val literal = "id=\"app-main\""
        val id =
            seedSqlVersions(
                "pwsl" + generatedPassword("s").take(6).lowercase(),
                description = "Quarterly roll-up <main $literal> and $literal",
            )
        val cacheKey = "/pipelines/$id?version=2"
        page.navigate("$baseUrl$cacheKey")
        page.waitForSelector(".pe-root")
        waitActivated()

        // Premise (non-vacuity): the rendered region AND the entry htmx caches carry the literal.
        page.locator("#pe-pane-overview .tplx-measure").textContent() shouldContain literal
        (page.evaluate("() => document.querySelector('#app-main').innerHTML") as String) shouldContain literal
        seedHistoryCounters()
        leaveThroughTheUi()
        val cached =
            page.evaluate(
                """(url) => {
                  const cache = JSON.parse(sessionStorage.getItem('htmx-history-cache') || '[]');
                  const item = cache.find((i) => i.url === url);
                  return item ? item.content : '';
                }""",
                cacheKey,
            ) as String
        cached shouldContain literal

        // Any request for the workspace PAGE itself (its navigation, or an htmx cache-miss
        // fetch) is a re-fetch; the editor's own /api/ reads are not.
        val refetched = mutableListOf<String>()
        page.onRequest { request ->
            if (java.net.URI(request.url()).path == "/pipelines/$id") refetched += request.url()
        }
        val hitsBefore = counter("hit")
        val restoresBefore = counter("restore")

        page.goBack()
        page.waitForSelector(".pe-root")
        waitActivated()

        check(refetched.isEmpty()) { "a main-shaped entry must be restored from the cache, but the page was re-fetched: $refetched" }
        counter("restore") shouldBeGreaterThanOrEqual restoresBefore + 1
        counter("hit") shouldBeGreaterThanOrEqual hitsBefore + 1
        counter("miss") shouldBe 0
        stackDepth() shouldBe 1
        sqlPaneCount() shouldBe 0
        (page.evaluate("() => document.querySelector('#app-main').innerHTML") as String) shouldContain literal
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
    fun `navigation detaches the stream without cancelling`() {
        val id = seedSlowRunPipeline()
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

    // ------------------------------------------------------------------ #402

    private fun historyLength(): Int = (page.evaluate("() => history.length") as Number).toInt()

    private fun openTree() {
        page.click("[data-nav-branch='pipelines'] [data-nav-tree-toggle]")
        page.waitForSelector("#pipeline-nav-root .tpl-tree, #pipeline-nav-root .ds-empty")
    }

    /** The sidebar tree's current leaf — #350's own history proof, read, never driven here. */
    private fun currentLeaves(): Int =
        (page.evaluate("() => document.querySelectorAll(\"#nav-tree-pipelines a.tpl-leaf[aria-current='page']\").length") as Number)
            .toInt()

    /** An IN-PAGE version switch through the header selector, synced on the landed body. */
    private fun switchVersionInPage(
        version: Int,
        bodyMarker: String,
    ) {
        page.locator(".pe-versions a[data-version='$version']").click()
        page.waitForFunction(
            "([v, m]) => window.PEWorkspace && window.PEWorkspace.viewedVersion === v && " +
                "(document.getElementById('pipeline-data')?.textContent ?? '').includes(m)",
            arrayOf<Any>(version, bodyMarker),
        )
    }

    /**
     * One view, asserted whole: the URL's canonical version/tab, the viewed pin, the body
     * block, the chip, the tab strip and the root's tab attribute (what a restore re-reads).
     */
    private fun expectView(
        version: Int,
        tab: String,
        chip: String,
        bodyMarker: String,
    ) {
        page.waitForFunction(
            "([v, t, m]) => window.PEWorkspace && window.PEWorkspace.viewedVersion === v && " +
                "document.getElementById('pe-tab-' + t)?.getAttribute('aria-selected') === 'true' && " +
                "(document.getElementById('pipeline-data')?.textContent ?? '').includes(m)",
            arrayOf<Any>(version, tab, bodyMarker),
        )
        val url = page.url()
        url shouldContain "version=$version"
        if (tab == "flow") url shouldNotContain "tab=" else url shouldContain "tab=$tab"
        page.locator(".pe-vchip").innerText() shouldBe chip
        page.locator(".pe-root").getAttribute("data-active-tab") shouldBe tab
        val selected = "() => [...document.querySelectorAll('.pe-tab')].filter(b => b.getAttribute('aria-selected') === 'true').length"
        (page.evaluate(selected) as Number).toInt() shouldBe 1
    }

    /** No reload, no re-activation: the SAME Alpine, runtime epoch and activation count. */
    private fun sameInstanceAs(identity: Map<*, *>) {
        val now = runtimeIdentity()
        now["alpineMark"] shouldBe identity["alpineMark"]
        now["epoch"] shouldBe identity["epoch"]
        now["activations"] shouldBe identity["activations"]
        stackDepth() shouldBe 1
    }

    @Test
    fun `#402 - Back and Forward replay in-page version and tab switches on the live instance`() {
        val id = seedSqlVersions("pwhb" + generatedPassword("s").take(6).lowercase())
        ensureTheme("light")
        openTree()
        page.navigate("$baseUrl/pipelines/$id?version=1")
        page.waitForSelector(".pe-root")
        waitActivated()
        page.waitForSelector("#nav-tree-pipelines a.tpl-leaf[aria-current='page']")
        seedHistoryCounters()
        val identity = runtimeIdentity()
        val length = historyLength()
        val v1 = "v1 · released · current"
        val v2 = "v2 · released"

        // v1 → v2 → Overview: two user switches, two entries of the workspace's own.
        switchVersionInPage(2, "sql_v2")
        page.locator("#pe-tab-overview").click()
        expectView(2, "overview", v2, "sql_v2")
        historyLength() shouldBe length + 2

        // Back twice lands on v2/Flow, then v1/Flow; Forward replays both — all IN PAGE.
        val walk =
            listOf(
                { page.goBack() } to listOf<Any>(2, "flow", v2, "sql_v2"),
                { page.goBack() } to listOf<Any>(1, "flow", v1, "sql_v1"),
                { page.goForward() } to listOf<Any>(2, "flow", v2, "sql_v2"),
                { page.goForward() } to listOf<Any>(2, "overview", v2, "sql_v2"),
            )
        for ((move, view) in walk) {
            move()
            expectView(view[0] as Int, view[1] as String, view[2] as String, view[3] as String)
            sameInstanceAs(identity)
            historyLength() shouldBe length + 2 // a replay never mints
            counter("restore") shouldBe 0
            counter("miss") shouldBe 0
            currentLeaves() shouldBe 1
        }
        (page.evaluate("() => window.WorkspaceHistory.stats().replayed.pipelines") as Number).toInt() shouldBe 4
        (page.evaluate("() => window.WorkspaceHistory.stats().listeners") as Number).toInt() shouldBe 1
    }

    @Test
    fun `#402 - a version the read refuses is the house banner on a switch and on a replay, and mints nothing`() {
        val id = seedSqlVersions("pwhr" + generatedPassword("s").take(6).lowercase())
        page.navigate("$baseUrl/pipelines/$id?version=2")
        page.waitForSelector(".pe-root")
        waitActivated()
        val length = historyLength()
        val refuse = { route: Route ->
            route.fulfill(
                Route
                    .FulfillOptions()
                    .setStatus(404)
                    .setContentType("application/json")
                    .setBody("{}"),
            )
        }

        // The lens-hidden read, stood in by the read's own refusal: a USER's switch to it is the
        // house banner, the body stays v2, and no entry is pushed.
        page.route("**/api/v1/pipelines/$id/versions/1", refuse)
        page.locator(".pe-versions a[data-version='1']").click()
        page.locator(".pe-banner:has-text('Version v1 is not available')").waitFor()
        historyLength() shouldBe length
        page.url() shouldContain "version=2"
        (page.evaluate("() => document.getElementById('pipeline-data').textContent") as String) shouldContain "sql_v2"
        page.unroute("**/api/v1/pipelines/$id/versions/1")
        page.locator(".pe-banner-dismiss").click()

        // A REPLAY onto a version the read now refuses: the same banner, the body unchanged.
        switchVersionInPage(1, "sql_v1")
        historyLength() shouldBe length + 1
        page.route("**/api/v1/pipelines/$id/versions/2", refuse)
        page.goBack()
        page.locator(".pe-banner:has-text('Version v2 is not available')").waitFor()
        (page.evaluate("() => window.PEWorkspace.viewedVersion") as Number).toInt() shouldBe 1
        (page.evaluate("() => document.getElementById('pipeline-data').textContent") as String) shouldContain "sql_v1"
        historyLength() shouldBe length + 1
        page.unroute("**/api/v1/pipelines/$id/versions/2")
    }

    @Test
    fun `#402 - after an in-page switch a boosted leave is a cache HIT with its selector, and Back replays in the restored instance`() {
        val id = seedSqlVersions("pwhc" + generatedPassword("s").take(6).lowercase())
        ensureTheme("dark")
        page.navigate("$baseUrl/pipelines/$id?version=1")
        page.waitForSelector(".pe-root")
        waitActivated()
        seedHistoryCounters()
        val arrival = runtimeIdentity()
        val arrivalActivations = (arrival["activations"] as Number).toInt()
        page.locator(".pe-versions a").count() shouldBe 3
        val v2 = "v2 · released"

        switchVersionInPage(2, "sql_v2")
        page.locator("#pe-tab-overview").click()
        expectView(2, "overview", v2, "sql_v2")

        leaveThroughTheUi()
        val restoresBefore = counter("restore")
        page.goBack()
        waitForRestore(restoresBefore)
        waitActivated()
        page.waitForSelector(".pe-root")

        // htmx's snapshot was keyed by the URL the user returned to: a HIT, one restore, one
        // activation, one component — and the page is the one that was left, selector included.
        counter("hit") shouldBe 1
        counter("miss") shouldBe 0
        counter("restore") shouldBe 1
        expectView(2, "overview", v2, "sql_v2")
        val restored = runtimeIdentity()
        (restored["activations"] as Number).toInt() shouldBe arrivalActivations + 1
        restored["epoch"] shouldBe arrival["epoch"]
        stackDepth() shouldBe 1
        page.locator(".pe-versions a").count() shouldBe 3
        page.locator(".pe-versions a[aria-current='page']").getAttribute("data-version") shouldBe "2"

        // Back again: the workspace's own entries replay IN the restored instance.
        page.goBack()
        expectView(2, "flow", v2, "sql_v2")
        page.goBack()
        expectView(1, "flow", "v1 · released · current", "sql_v1")
        counter("restore") shouldBe 1
        sameInstanceAs(restored)
        page.locator(".pe-versions a[aria-current='page']").getAttribute("data-version") shouldBe "1"
        ensureTheme("light")
    }

    @Test
    fun `#402 - an author's markup in the rewritten blocks stays text through a cache-hit restore`() {
        // The security pass's regression: the in-page switch rewrites the two script blocks, the
        // boosted leave snapshots #app-main as innerHTML (a <script>'s text serialises RAW), and
        // the HIT re-parses it. A raw `</script>` would close the block and inject the rest.
        val id = seedSqlVersions("pwhx" + generatedPassword("s").take(6).lowercase(), INJECTION)
        page.navigate("$baseUrl/pipelines/$id?version=1")
        page.waitForSelector(".pe-root")
        waitActivated()
        seedHistoryCounters()
        switchVersionInPage(2, "sql_v2")
        leaveThroughTheUi()
        val restoresBefore = counter("restore")
        page.goBack()
        waitForRestore(restoresBefore)
        waitActivated()
        counter("hit") shouldBe 1
        page.locator("#p402-injected").count() shouldBe 0
        expectView(2, "flow", "v2 · released", "sql_v2")
        page.locator(".pe-versions a").count() shouldBe 3
        (page.evaluate("() => JSON.parse(document.getElementById('pipeline-data').textContent).description") as String) shouldBe
            INJECTION
    }

    @Test
    fun `#402 - Forward from the list onto a workspace entry is handed to htmx and restores the workspace`() {
        val id = seedSqlVersions("pwhf" + generatedPassword("s").take(6).lowercase())
        page.navigate("$baseUrl/pipelines")
        page.waitForSelector("#pipeline-list-wrapper")
        seedHistoryCounters()
        // A boosted entry into the workspace: htmx's own entry, which the first switch converts.
        page.evaluate(
            """(url) => {
              const a = document.createElement('a');
              a.href = url;
              a.id = 'pe-boosted-entry';
              a.textContent = 'workspace';
              document.querySelector('#app-main').appendChild(a);
              window.htmx.process(a); // an anchor added after load is boosted only once processed
            }""",
            "/pipelines/$id?version=1",
        )
        page.locator("#pe-boosted-entry").click()
        page.waitForSelector(".pe-root")
        waitActivated()
        // The entry IS boosted: the same window (the counters survive) and htmx's own state.
        (page.evaluate("() => !!window.__peHistoryShape && !!(history.state && history.state.htmx)") as Boolean) shouldBe true
        switchVersionInPage(2, "sql_v2")
        page.goBack()
        expectView(1, "flow", "v1 · released · current", "sql_v1")

        // Back to the list: htmx's entry, htmx's restore.
        val beforeList = counter("restore")
        page.goBack()
        waitForRestore(beforeList)
        page.waitForSelector("#pipeline-list-wrapper")

        // Forward onto OUR entry while the list is showing: nothing here can replay it, so the
        // helper hands it to htmx — a cache HIT that restores the workspace the reader left.
        val handed = (page.evaluate("() => window.WorkspaceHistory.stats().handedToHtmx") as Number).toInt()
        val beforeWorkspace = counter("restore")
        page.goForward()
        waitForRestore(beforeWorkspace)
        waitActivated()
        expectView(1, "flow", "v1 · released · current", "sql_v1")
        stackDepth() shouldBe 1
        counter("miss") shouldBe 0
        (page.evaluate("() => window.WorkspaceHistory.stats().handedToHtmx") as Number).toInt() shouldBe handed + 1
        val restored = runtimeIdentity()

        // And Forward again replays v2 IN the restored instance.
        page.goForward()
        expectView(2, "flow", "v2 · released", "sql_v2")
        sameInstanceAs(restored)
    }

    @Test
    fun `#402 - a run started on v1 keeps its stream and identity across a switch to v2 and Back`() {
        val id = seedSlowRunPipeline()
        seedSecondChainVersion(id)
        page.navigate("$baseUrl/pipelines/$id?version=1")
        page.waitForSelector(".pe-root")
        waitActivated()
        seedHistoryCounters()
        val identity = runtimeIdentity()

        val deletes = mutableListOf<String>()
        page.route("**/api/v1/executions/*") { route ->
            if (route.request().method() == "DELETE") deletes += route.request().url()
            route.resume()
        }
        page.locator("[data-verb='pipeline-execute']").click()
        page.locator(".pe-status:has-text('Running')").waitFor()
        val strip = page.locator(".pe-run-strip span").nth(1)
        val runVersion = strip.innerText()

        // In page to v2 while the run streams, then Back: v1 replayed, the stream untouched.
        switchVersionInPage(2, "p402 second")
        page.goBack()
        page.waitForFunction(
            "() => window.PEWorkspace && window.PEWorkspace.viewedVersion === 1 && " +
                "!(document.getElementById('pipeline-data')?.textContent ?? '').includes('p402 second')",
        )
        sameInstanceAs(identity)
        strip.innerText() shouldBe runVersion

        page.locator(".pe-status:has-text('Completed')").waitFor(Locator.WaitForOptions().setTimeout(180_000.0))
        val completed = page.locator(".ds-toast:has-text('Pipeline completed')")
        completed.first().waitFor(Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE))
        completed.count() shouldBe 1
        deletes.shouldBeEmpty()
        counter("restore") shouldBe 0
        sameInstanceAs(identity)
        page.unroute("**/api/v1/executions/*")
    }

    /** v2 of the slow chain: the same nodes as a draft over the released v1 ("p402 second" marks its body). */
    private fun seedSecondChainVersion(id: String) {
        val status =
            page.evaluate(
                """async (id) => {
                  const csrf = document.cookie.match(/(?:^|;\s*)dp_csrf=([^;]*)/);
                  const token = csrf ? decodeURIComponent(csrf[1]) : '';
                  const get = async () => { const r = await fetch('/api/v1/pipelines/' + id, { credentials: 'same-origin' }); const e = await r.json(); return e.data || e; };
                  let d = await get();
                  // The chain's templates are drafts: a release pins RELEASED template versions.
                  for (const tid of [...new Set(d.nodes.map((n) => n.template && n.template.id).filter(Boolean))]) {
                    const tr = await fetch('/api/v1/templates?name=' + encodeURIComponent(tid), { credentials: 'same-origin' });
                    const te = await tr.json();
                    const t = te.data || te;
                    const trel = await fetch('/api/v1/templates/release', { method: 'POST', credentials: 'same-origin',
                      headers: { 'Content-Type': 'application/json', 'DP-CSRF-Token': token, 'If-Match': t.body_hash },
                      body: JSON.stringify({ name: tid }) });
                    if (trel.status !== 200) return 'template ' + tid + ' ' + trel.status;
                  }
                  const rel = await fetch('/api/v1/pipelines/' + id + '/release', { method: 'POST', credentials: 'same-origin',
                    headers: { 'DP-CSRF-Token': token, 'If-Match': d.body_hash } });
                  if (rel.status !== 200) return 'release ' + rel.status + ' ' + (await rel.text()).slice(0, 300);
                  d = await get();
                  const put = await fetch('/api/v1/pipelines/' + id, { method: 'PUT', credentials: 'same-origin',
                    headers: { 'Content-Type': 'application/json', 'DP-CSRF-Token': token, 'If-Match': d.body_hash },
                    body: JSON.stringify({ name: d.name, display_name: 'p402 second', nodes: d.nodes }) });
                  return 'put ' + put.status;
                }""",
                id,
            )
        status shouldBe "put 200"
    }

    private companion object {
        const val EXECUTE_PATTERN = "**/api/v1/pipelines/*/execute"

        /** The restored root's stacked-component count, read in one probe. */
        const val STACK_DEPTH_JS =
            "() => { const r = document.querySelector('#app-main .pe-root'); " +
                "return r && r._x_dataStack ? r._x_dataStack.length : 0; }"

        /** The runtime's ONE activation completed: the root carries a bound component. */
        const val ACTIVATED_JS =
            "() => { const r = document.querySelector('#app-main .pe-root'); " +
                "return !!(r && r._x_dataStack && r._x_dataStack.length >= 1); }"

        /** #402 security pass: markup an author could write into a description (unquoted id: JSON escapes quotes). */
        const val INJECTION = "</script><b id=p402-injected>owned</b><!--"

        /** A run long enough to outlive a navigation, short enough to keep the suite honest. */
        const val ROWS = 400_000

        /** The execute POST's pinned version, read off the captured wire body. */
        fun versionOf(postBody: String): Int = Regex(""""version"\s*:\s*(\d+)""").find(postBody)!!.groupValues[1].toInt()
    }
}
