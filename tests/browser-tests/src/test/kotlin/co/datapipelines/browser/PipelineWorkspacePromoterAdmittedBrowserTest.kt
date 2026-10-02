package co.datapipelines.browser

import com.microsoft.playwright.Locator
import com.sun.net.httpserver.HttpServer
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

/**
 * #348-b — A3 completed: an ADMITTED promoter's released page, rendered by the REAL
 * production lens. The original lane's browser arm could only prove the fail-closed 404 —
 * its fixture had no promotion target, and the lens admits nothing when the target cannot
 * be read (roles design §3.1). This class boots the app with an ISOLATED in-test target
 * (the [PipelineEditorLeaseWaitBrowserTest] per-class property precedent) whose inventory
 * answers the promotion wire — so the lens admits the seeded released pipeline exactly as
 * production would — and proves, on the promoter's own session with a request collector:
 *
 *  - the released page renders the real body (graph root + v2 node ids);
 *  - the draft and every draft/current-draft metadata are absent (selector, chip, JSON);
 *  - no execution/lifecycle control renders, and ZERO execution/run/cancel requests fire;
 *  - the draft URL is still the house 404 (admitted is not everything);
 *  - the target-less fail-closed posture keeps its own control in
 *    [PipelineWorkspaceVersionBrowserTest] — this class is the ADMITTED arm.
 *
 * Viewer-execute and author/admin-management positive controls live in
 * [PipelineWorkspaceVersionBrowserTest] (admin executes v2/v3 on the wire; the author's
 * Release affordance renders and the seeding itself releases through the API).
 */
class PipelineWorkspacePromoterAdmittedBrowserTest : BrowserSuite() {
    private fun loginReadyUser(
        slug: String,
        workspaceName: String,
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
        check(status == 200 || status == 201) { "$method $path -> $status: ${text.take(300)}" }
        return text
    }

    private fun hashOf(text: String): String = Regex(""""body_hash"\s*:\s*"([^"]+)"""").find(text)!!.groupValues[1]

    private fun calcBody(nodeId: String): String =
        """{"name":"p348/promoted","display_name":"Promoted","nodes":[{"id":"$nodeId","type":"CALCULATOR",""" +
            """"kind":"fiscal_quarter","context_key":"run_q_$nodeId",""" +
            """"inputs":{"date":"${'$'}current_date","fiscal_start":"${'$'}org_fiscal_start_date"}}]}"""

    /** v1 current release + v2 newer release + v3 draft, per test workspace. Returns the id. */
    private fun seedThreeVersions(
        slug: String,
        workspaceName: String,
    ): String {
        loginReadyUser(slug, workspaceName)
        val created = must("POST", "/api/v1/pipelines", calcBody("n_v1"))
        val uuid = "([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})"
        val id = Regex(""""id"\s*:\s*"$uuid"""").find(created)!!.groupValues[1]
        val v1Hash = hashOf(created)

        must("POST", "/api/v1/pipelines/$id/release", null, ifMatch = v1Hash)
        val v2Hash = hashOf(must("PUT", "/api/v1/pipelines/$id", calcBody("n_v2"), ifMatch = v1Hash))
        must("POST", "/api/v1/pipelines/$id/release", null, ifMatch = v2Hash)
        must("PUT", "/api/v1/pipelines/$id", calcBody("n_v3"), ifMatch = v2Hash)
        must("POST", "/api/v1/pipelines/$id/current", """{"version": 1}""")
        return id
    }

    private fun seedMembership(
        workspaceName: String,
        email: String,
        role: String,
    ) {
        java.sql.DriverManager
            .getConnection(SharedBrowserE2e.jdbcUrl, SharedBrowserE2e.username, SharedBrowserE2e.password)
            .use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        INSERT INTO workspace_members (workspace_id, user_id, role)
                        SELECT w.id, u.id, '$role'
                          FROM workspaces w, users u
                         WHERE w.name = '$workspaceName' AND u.email = '$email'
                        ON CONFLICT (workspace_id, user_id) DO NOTHING
                        """.trimIndent(),
                    )
                }
            }
    }

    /** The promoter's OWN session: granted in [workspaceName] before first login, switched in after. */
    private fun openPromoterSession(workspaceName: String): BrowserSuite.Session {
        val user =
            seedLocalUser(
                uniqueEmail("p348a-pro" + generatedPassword("p").take(6)),
                generatedPassword("pw"),
                mustChange = false,
                isAdmin = false,
                role = "promoter",
            )
        seedMembership(workspaceName, user.email, role = "promoter")
        val promoter = newSession()
        promoter.page.navigate("$baseUrl/login")
        promoter.page.fill("#login-email", user.email)
        promoter.page.fill("#login-password", user.oneTimePassword)
        promoter.page.click("form button[type=submit]")
        promoter.page.waitForURL("**/dashboard")
        promoter.page.evaluate(
            """async (name) => {
              const csrf = document.cookie.match(/(?:^|;\s*)dp_csrf=([^;]*)/);
              await fetch('/workspace/switch?name=' + encodeURIComponent(name), {
                method: 'POST', credentials: 'same-origin',
                headers: { 'DP-CSRF-Token': csrf ? decodeURIComponent(csrf[1]) : '' },
              });
            }""",
            workspaceName,
        )
        return promoter
    }

    /** The execution resources a read-only page must never fetch. */
    private fun forbiddenRequests(requests: List<String>): List<String> =
        requests.filter { request ->
            if (request.contains("/js/")) return@filter false
            val execute = request.contains("/api/v1/pipelines/") && request.contains("/execute")
            val checks = request.contains("/checks/run")
            val executions = request.contains("/api/v1/executions")
            val runs = request.contains("/runs")
            execute || checks || executions || runs
        }

    @Test
    fun `an admitted promoter renders the released page - real body, no drafts, no verbs, zero execution requests`() {
        val workspaceName = "p348ws-" + generatedPassword("w").take(8).lowercase()
        val id = seedThreeVersions("p348a" + generatedPassword("s").take(6).lowercase(), workspaceName)

        // The collector watches EVERY request the promoter's page makes.
        val promoter = openPromoterSession(workspaceName)
        val requests = mutableListOf<String>()
        promoter.page.onRequest { request -> requests += request.url() }

        // The lens ADMITS the released v2 (the configured target's inventory names no newer
        // version): the page renders the REAL body through the production lens.
        promoter.page.navigate("$baseUrl/pipelines/$id?version=2")
        promoter.page.waitForSelector(".pe-root")
        val pageJson =
            promoter.page.evaluate("() => document.getElementById('pipeline-data').textContent").toString()
        pageJson shouldContain "n_v2"
        pageJson shouldNotContain "n_v3"
        // No draft metadata anywhere in the projection: no pointer, no draft status, and the
        // current pointer states only what the lens admits (v1, released).
        pageJson shouldNotContain """"draft""""
        pageJson shouldNotContain "\"DRAFT\""
        promoter.page.locator(".pe-vchip").innerText() shouldBe "v2 · released"
        promoter.page.locator("[data-verb='pipeline-execute']").count() shouldBe 0
        promoter.page.locator("[data-verb='execution-cancel']").count() shouldBe 0
        promoter.page.locator("[data-verb='pipeline-release']").count() shouldBe 0
        promoter.page.locator("[data-verb='pipeline-purge']").count() shouldBe 0
        // The admitted history for a promoter: released rows only — the draft is not offered.
        val selector = promoter.page.locator(".pe-versions").innerText()
        selector shouldContain "v1"
        selector shouldContain "v2"
        selector shouldNotContain "v3"

        // The unqualified current (v1) renders too — admitted, current marker on.
        promoter.page.navigate("$baseUrl/pipelines/$id")
        promoter.page.waitForSelector(".pe-root")
        promoter.page.locator(".pe-vchip").innerText() shouldBe "v1 · released · current"

        // The draft is still refused EXACTLY as an absent number is (admitted ≠ everything).
        promoter.page.navigate("$baseUrl/pipelines/$id?version=3").status() shouldBe 404

        // The wire proof (A3): the promoter's admitted read-only pages fetched no
        // execution resource at all.
        forbiddenRequests(requests).shouldBeEmpty()
        // The fixture's own non-vacuity: the production lens really probed the configured
        // target (with the server key — the stub refuses anything else).
        org.junit.jupiter.api.Assertions.assertTrue(
            promoteProbeHits.get() >= 1,
            "the lens never probed the target — the fixture is vacuous",
        )
        promoter.close()
    }

    @Test
    fun `the promoter's workspace omits the Runs tab and the execution dock tabs - and no tab interaction fetches`() {
        // #349 (spec §4.2, A3): a promoter retains Node Details WITHOUT the execution
        // tabs; the Runs TAB is omitted entirely; Usage stays (a pipeline-read surface).
        // The omission is proven on the WIRE — opening every admitted tab costs zero
        // execution fetches.
        val workspaceName = "p348ws-" + generatedPassword("w").take(8).lowercase()
        val id = seedThreeVersions("p348t" + generatedPassword("s").take(6).lowercase(), workspaceName)

        val promoter = openPromoterSession(workspaceName)
        val requests = mutableListOf<String>()
        promoter.page.onRequest { request -> requests += request.url() }

        promoter.page.navigate("$baseUrl/pipelines/$id?version=2")
        promoter.page.waitForSelector(".pe-root")
        promoter.page.waitForFunction("() => document.querySelectorAll('.pe-versions a').length > 0")

        // The workspace tab strip: five tabs — no Runs.
        promoter.page.locator("#pe-tab-runs").count() shouldBe 0
        val tabs = promoter.page.evaluate("() => [...document.querySelectorAll('.pe-tab')].map(b => b.id)").toString()
        tabs shouldNotContain "pe-tab-runs"
        tabs shouldContain "pe-tab-usage"
        // The dock: Node Details only — no Results, no Errors, no Events.
        promoter.page.locator("#pe-dock-tab-results").count() shouldBe 0
        promoter.page.locator("#pe-dock-tab-errors").count() shouldBe 0
        promoter.page.locator("#pe-dock-tab-events").count() shouldBe 0
        promoter.page.locator("#pe-dock-tab-details").count() shouldBe 1
        // No execution identity strip markup either.
        promoter.page.locator(".pe-run-strip").count() shouldBe 0

        // Opening every admitted tab costs nothing execution-owned.
        for (tabId in listOf("pe-tab-overview", "pe-tab-parameters", "pe-tab-usage", "pe-tab-versions", "pe-tab-flow")) {
            promoter.page.locator("#" + tabId).click()
            promoter.page.waitForTimeout(400.0)
        }
        forbiddenRequests(requests).shouldBeEmpty()
        promoter.close()
    }

    @Test
    fun `a RESTORED page keeps the promoter read-only - one component, no verbs, zero execution requests`() {
        val workspaceName = "p348ws-" + generatedPassword("w").take(8).lowercase()
        val id = seedThreeVersions("p348r" + generatedPassword("s").take(6).lowercase(), workspaceName)

        val promoter = openPromoterSession(workspaceName)
        val requests = mutableListOf<String>()
        promoter.page.onRequest { request -> requests += request.url() }

        // The admitted released page, left through the UI (the boosted swap saves it),
        // then a genuine cache-hit restore: the restored page must carry NOTHING the
        // first render didn't — no verbs, no drafts, no execution traffic (#358).
        promoter.page.navigate("$baseUrl/pipelines/$id?version=2")
        promoter.page.waitForSelector(".pe-root")
        promoter.page.locator(".pe-back").click()
        promoter.page.waitForURL("**/pipelines")
        promoter.page.goBack()
        promoter.page.waitForSelector(".pe-root")
        promoter.page.waitForFunction(
            "() => { const r = document.querySelector('#app-main .pe-root'); " +
                "return !!(r && r._x_dataStack && r._x_dataStack.length >= 1); }",
        )

        val stack =
            (
                promoter.page.evaluate(
                    "() => { const r = document.querySelector('#app-main .pe-root'); " +
                        "return r && r._x_dataStack ? r._x_dataStack.length : 0; }",
                ) as Number
            ).toInt()
        stack shouldBe 1
        promoter.page.locator(".pe-vchip").innerText() shouldBe "v2 · released"
        promoter.page.locator("#pe-node-sql").count() shouldBe 0
        promoter.page.locator("[data-verb='pipeline-execute']").count() shouldBe 0
        promoter.page.locator("[data-verb='execution-cancel']").count() shouldBe 0
        promoter.page.locator("[data-verb='pipeline-release']").count() shouldBe 0
        promoter.page.locator("[data-verb='pipeline-purge']").count() shouldBe 0
        val restoredJson =
            promoter.page.evaluate("() => document.getElementById('pipeline-data').textContent").toString()
        restoredJson shouldContain "n_v2"
        restoredJson shouldNotContain "n_v3"

        forbiddenRequests(requests).shouldBeEmpty()
        promoter.close()
    }

    /**
     * #350 (A3 repeated, A11's lensed catalog) — the promoter's SIDEBAR tree is the lens's answer:
     * the admitted released pipeline is a leaf (and the current page's), a draft-only sibling the
     * lens does not admit is absent from the rows AND from the wire (a direct read of the same
     * level carries no trace of it — no route returns hidden rows for CSS to hide), the folder's
     * count is the lensed count, and every sidebar answer is stamped `<workspace>|lens`.
     */
    @Test
    fun `#350 - an admitted promoter's sidebar tree lists only the admitted pipeline, stamped with the lens`() {
        val workspaceName = "p350ws-" + generatedPassword("w").take(8).lowercase()
        val id = seedThreeVersions("p350l" + generatedPassword("s").take(6).lowercase(), workspaceName)
        // A draft-only sibling in the same folder: nothing to promote, so the lens never admits it.
        must(
            "POST",
            "/api/v1/pipelines",
            """{"name":"p348/hidden_draft","display_name":"Hidden","nodes":[{"id":"h1","type":"CALCULATOR",""" +
                """"kind":"fiscal_quarter","context_key":"run_q_h1",""" +
                """"inputs":{"date":"${'$'}current_date","fiscal_start":"${'$'}org_fiscal_start_date"}}]}""",
        )

        val promoter = openPromoterSession(workspaceName)
        val stamps = mutableListOf<String>()
        promoter.page.onResponse { response ->
            if (response.url().contains("/partials/pipelines?")) stamps += (response.headers()["dp-nav-stamp"] ?: "none")
        }
        promoter.page.navigate("$baseUrl/pipelines/$id")
        promoter.page.waitForSelector(".pe-root")
        promoter.page.click("[data-nav-branch='pipelines'] [data-nav-tree-toggle]")
        promoter.page.waitForSelector("#nav-tree-pipelines a.tpl-leaf[aria-current='page']")

        val titles =
            (
                promoter.page.evaluate(
                    "() => [...document.querySelectorAll('#nav-tree-pipelines .tpl-label[title]')].map(e => e.getAttribute('title'))",
                ) as List<*>
            ).map { it.toString() }
        titles.contains("p348/promoted") shouldBe true
        (titles.contains("p348/hidden_draft")) shouldBe false
        promoter.page
            .locator(
                "#nav-tree-pipelines details.tpl-folder:has(> summary span[title='p348']) .tpl-count",
            ).first()
            .innerText() shouldBe
            "1"

        // The wire: the same level, read directly, carries no trace of the hidden row.
        val level =
            promoter.page
                .evaluate(
                    "async () => (await fetch('/partials/pipelines?prefix=p348'," +
                        " { credentials: 'same-origin', headers: { 'HX-Request': 'true' } })).text()",
                ).toString()
        level shouldContain "p348/promoted"
        level shouldNotContain "hidden_draft"
        (stamps.isNotEmpty()) shouldBe true
        stamps.all { it == "$workspaceName|lens" } shouldBe true
        promoter.close()
    }

    private companion object {
        const val SERVER_KEY = "p348-browser-server-key"

        /** The stub's probe counter — the fixture's own non-vacuity witness. */
        val promoteProbeHits = AtomicInteger(0)

        /**
         * The ISOLATED promotion target: a loopback HTTP server serving the promotion
         * inventory wire (the exact contract [co.datapipelines.web.pipelines.PromotionTargetClient]
         * reads), reachable only inside this test JVM. It validates the presented server
         * key — a credential check, not just a JSON endpoint — and names no pipeline, so
         * every seeded local pipeline is "newer than the target" and admitted (§10.2).
         */
        val target: HttpServer =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).also { server ->
                server.createContext("/api/v1/promotion/inventory") { exchange ->
                    val presented = exchange.requestHeaders.getFirst("DP-Promotion-Key")
                    if (presented != SERVER_KEY) {
                        val refused =
                            """{"schema_version":1,"correlation_id":"00000000-0000-0000-0000-000000000000",""" +
                                """"error":{"code":"promotion.key_invalid","message":"bad key"}}"""
                        val bytes = refused.toByteArray(Charsets.UTF_8)
                        exchange.responseHeaders.add("Content-Type", "application/json")
                        exchange.sendResponseHeaders(401, bytes.size.toLong())
                        exchange.responseBody.use { it.write(bytes) }
                        return@createContext
                    }
                    promoteProbeHits.incrementAndGet()
                    val workspace =
                        exchange.requestURI.query
                            ?.split('&')
                            ?.firstOrNull { it.startsWith("workspace=") }
                            ?.substringAfter("workspace=")
                            ?.let { java.net.URLDecoder.decode(it, Charsets.UTF_8) }
                            ?: ""
                    val body =
                        """{"schema_version":1,"correlation_id":"00000000-0000-0000-0000-000000000000","data":{""" +
                            """"deployment":"p348-lower","authoring_enabled":true,"workspace":"$workspace",""" +
                            """"pipelines":[],"templates":[],"datasources":[],"parameter_sets":[]}}"""
                    val bytes = body.toByteArray(Charsets.UTF_8)
                    exchange.responseHeaders.add("Content-Type", "application/json")
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
                server.start()
            }

        @JvmStatic
        @org.springframework.test.context.DynamicPropertySource
        fun promotionTarget(registry: org.springframework.test.context.DynamicPropertyRegistry) {
            // The documented configuration pair: a target that answers the inventory wire
            // with the server key the sender presents (configuration.md §3.19's shape).
            registry.add("datapipelines.deployment.promotion.target.base-url") { "http://127.0.0.1:${target.address.port}" }
            registry.add("datapipelines.deployment.promotion.target.server-key") { SERVER_KEY }
        }
    }
}
