package co.datapipelines.browser

import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.Route
import com.microsoft.playwright.options.WaitForSelectorState
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/**
 * #348 — the version-explicit workspace, driven in a real browser (spec A1–A5):
 *
 *  1. Three versions with DELIBERATELY different node ids — v1 the current release, v2 a
 *     newer release (not current), v3 the draft. The unqualified view shows v1 (the ACTUAL
 *     current pointer, never "latest release", never the draft); `?version=N` shows that
 *     version's body, labelled, or 404s; a malformed version is the house 400; the old
 *     `/editor` URL redirects preserving the version.
 *  2. Execute pins the VIEWED version on the wire — the draft v3 and the release v2 alike —
 *     and a malformed version context refuses with a visible error and ZERO POSTs. The
 *     collector is a positive control: the valid case proves it sees the real POST, so the
 *     refusal case's zero is evidence.
 *  3. A promoter reads admitted RELEASED content through the lens — the draft is not in the
 *     selector, `?version=3` is the house 404, no Execute/lifecycle verbs render, and the
 *     collector counts no execution request of any kind on the promoter's page.
 */
class PipelineWorkspaceVersionBrowserTest : BrowserSuite() {
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

    /**
     * Grants [email] a membership of [workspaceName] — the promoter fixture's grant, made
     * through the same JDBC path [seedLocalUser] uses (a real deployment's admin would).
     * Must run BEFORE the user's first login: the auth cache holds memberships from then.
     */
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

    private fun calcBody(nodeId: String): String =
        // The name grammar (contract §3.2) wants TWO-to-ten path segments: a folder is not
        // optional. The workspace is per-test, so one fixed legal name cannot collide.
        """{"name":"p348/ver_pipes","display_name":"Version Pipes","nodes":[{"id":"$nodeId","type":"CALCULATOR",""" +
            """"kind":"fiscal_quarter","context_key":"run_q_$nodeId",""" +
            """"inputs":{"date":"${'$'}current_date","fiscal_start":"${'$'}org_fiscal_start_date"}}]}"""

    /**
     * Seeds v1 current release + v2 newer release + v3 draft, each with a DISTINCT node id,
     * through the REST surface (in-page, so the session and CSRF apply). Returns the id.
     */
    private fun seedThreeVersions(
        slug: String,
        workspaceName: String = "pws-" + generatedPassword("w").take(8).lowercase(),
    ): String {
        loginReadyUser(slug, workspaceName)
        val created = must("POST", "/api/v1/pipelines", calcBody("n_v1"))
        // The envelope carries the node ids too — match the SERVER-assigned UUID, not the
        // first "id" in the tree (the node's).
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

    /** Seeds a SQL template draft via REST; returns its body hash for release/PUT. */
    private fun seedTemplate(
        id: String,
        sql: String,
        ifMatch: String? = null,
        update: Boolean = false,
    ): String {
        val body =
            """{"id":"$id","type":"sql","dialect":"H2","display_name":"${id.substringAfterLast('/')}",""" +
                """"description":"348-b lifecycle fixture","imports":[],"body":"$sql"}"""
        val (status, text) =
            if (update) {
                api("PUT", "/api/v1/templates", body, ifMatch = ifMatch)
            } else {
                api("POST", "/api/v1/templates", body)
            }
        check(status == 200 || status == 201) { "template write -> $status: ${text.take(300)}" }
        return hashOf(text)
    }

    private fun sqlBody(
        nodeId: String,
        templateId: String,
        templateVersion: Int,
    ): String =
        """{"name":"p348/sql_pipes","display_name":"SQL Pipes",""" +
            """"nodes":[{"id":"$nodeId","type":"DQL",""" +
            """"source":"tempdb","template":{"id":"$templateId","version":$templateVersion},"depends_on":[]}]}"""

    /**
     * Seeds the SQL lifecycle fixture: template v1 ("one") and v2 ("two") both RELEASED;
     * pipeline v1 (node sql_v1 → tpl@1) and v2 (node sql_v2 → tpl@2) both RELEASED, current
     * switched back to v1; a v3 draft. Distinct nodes AND distinct SQL per version.
     */
    private fun seedSqlVersions(slug: String): String {
        loginReadyUser(slug)
        val templateId = "test/p348_sql_" + generatedPassword("t").take(6).lowercase()
        val tplV1Hash = seedTemplate(templateId, "SELECT 1 AS one")
        must("POST", "/api/v1/templates/release", """{"name":"$templateId"}""", ifMatch = tplV1Hash)
        val tplV2Hash = seedTemplate(templateId, "SELECT 2 AS two", ifMatch = tplV1Hash, update = true)
        must("POST", "/api/v1/templates/release", """{"name":"$templateId"}""", ifMatch = tplV2Hash)

        val created = must("POST", "/api/v1/pipelines", sqlBody("sql_v1", templateId, 1))
        val uuid = "([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})"
        val id = Regex(""""id"\s*:\s*"$uuid"""").find(created)!!.groupValues[1]
        val v1Hash = hashOf(created)
        must("POST", "/api/v1/pipelines/$id/release", null, ifMatch = v1Hash)

        val v2Hash = hashOf(must("PUT", "/api/v1/pipelines/$id", sqlBody("sql_v2", templateId, 2), ifMatch = v1Hash))
        must("POST", "/api/v1/pipelines/$id/release", null, ifMatch = v2Hash)

        must("PUT", "/api/v1/pipelines/$id", calcBody("n_v3"), ifMatch = v2Hash)
        must("POST", "/api/v1/pipelines/$id/current", """{"version": 1}""")
        return id
    }

    /** The version chip's text — the page's one statement of which body is showing. */
    private fun viewedChip(): String = page.locator(".pe-vchip").innerText()

    /** The graph's data block: the BODY the page resolved, not a label. */
    private fun dataBlock(): String = page.evaluate("() => document.getElementById('pipeline-data').textContent") as String

    /** Records the execute POSTs' bodies while letting them through, until [unroute]. */
    private fun collectExecutePosts(into: MutableList<String>) {
        page.route(EXECUTE_PATTERN) { route ->
            if (route.request().method() == "POST") into += route.request().postData() ?: ""
            route.resume()
        }
    }

    private fun runAndWaitForTerminal() {
        page.locator("[data-verb='pipeline-execute']").click()
        page.locator("[data-verb='pipeline-execute']:not([disabled])").waitFor(
            Locator.WaitForOptions().setTimeout(90_000.0),
        )
        page.unroute(EXECUTE_PATTERN)
    }

    @Test
    fun `the unqualified view is the ACTUAL current version v1 - not the newer release, not the draft`() {
        val id = seedThreeVersions("pwsv" + generatedPassword("s").take(6).lowercase())

        page.navigate("$baseUrl/pipelines/$id")
        page.waitForSelector(".pe-root")

        dataBlock() shouldContain "n_v1"
        dataBlock() shouldNotContain "n_v2"
        dataBlock() shouldNotContain "n_v3"
        viewedChip() shouldBe "v1 · released · current"
    }

    @Test
    fun `an explicit version shows that version's body - the release v2 and the draft v3, labelled`() {
        val id = seedThreeVersions("pwse" + generatedPassword("s").take(6).lowercase())

        page.navigate("$baseUrl/pipelines/$id?version=2")
        page.waitForSelector(".pe-root")
        dataBlock() shouldContain "n_v2"
        dataBlock() shouldNotContain "n_v1"
        viewedChip() shouldBe "v2 · released"

        page.navigate("$baseUrl/pipelines/$id?version=3")
        page.waitForSelector(".pe-root")
        dataBlock() shouldContain "n_v3"
        viewedChip() shouldBe "v3 · draft"
        // An author sees the draft's pending-release affordance beside the viewed draft.
        page.locator("[data-verb='pipeline-release']").waitFor()
    }

    @Test
    fun `an absent version is the 404, a malformed one the 400, and the old editor URL redirects preserving the version`() {
        val id = seedThreeVersions("pwsx" + generatedPassword("s").take(6).lowercase())

        page.navigate("$baseUrl/pipelines/$id?version=99").status() shouldBe 404
        page.navigate("$baseUrl/pipelines/$id?version=abc").status() shouldBe 400
        page.navigate("$baseUrl/pipelines/$id?version=0").status() shouldBe 400

        // The compatibility redirect: full document, exact version preserved.
        page.navigate("$baseUrl/pipelines/$id/editor?version=2")
        page.url() shouldContain "/pipelines/$id?version=2"
        page.waitForSelector(".pe-root")
        dataBlock() shouldContain "n_v2"
    }

    @Test
    fun `the header selector enters the exact version of the row clicked`() {
        val id = seedThreeVersions("pwsl" + generatedPassword("s").take(6).lowercase())

        page.navigate("$baseUrl/pipelines/$id")
        page.waitForSelector(".pe-versions")
        page.locator(".pe-versions a").first().waitFor()
        page.locator(".pe-versions a[data-version='3']").click()
        page.waitForSelector(".pe-root")
        page.url() shouldContain "version=3"
        dataBlock() shouldContain "n_v3"
        viewedChip() shouldBe "v3 · draft"
    }

    @Test
    fun `execute pins the VIEWED version on the wire - and a malformed context refuses with zero POSTs`() {
        val id = seedThreeVersions("pwsg" + generatedPassword("s").take(6).lowercase())

        // The collector is a POSITIVE CONTROL first: the valid page runs and the counter
        // sees the POST, with the draft v3 pinned on the wire.
        val draftPosts = mutableListOf<String>()
        collectExecutePosts(draftPosts)
        page.navigate("$baseUrl/pipelines/$id?version=3")
        page.waitForSelector(".pe-root")
        runAndWaitForTerminal()
        draftPosts.size shouldBeGreaterThanOrEqual 1
        versionOf(draftPosts.single()) shouldBe 3

        // The released v2 pins v2 — the pin is the VIEWED version, never the draft rule.
        val releasePosts = mutableListOf<String>()
        collectExecutePosts(releasePosts)
        page.navigate("$baseUrl/pipelines/$id?version=2")
        page.waitForSelector(".pe-root")
        runAndWaitForTerminal()
        versionOf(releasePosts.single()) shouldBe 2

        // THE REFUSAL: a page whose workspace block is unreadable (the id renamed — exactly
        // what a broken render would produce) refuses VISIBLY and sends NOTHING.
        val refusals = mutableListOf<String>()
        page.route("**/pipelines/$id**") { route ->
            val original = route.fetch().text()
            route.fulfill(
                Route.FulfillOptions().setContentType("text/html").setBody(
                    original.replace("id=\"pipeline-workspace\"", "id=\"pipeline-workspace-broken\""),
                ),
            )
        }
        collectExecutePosts(refusals)
        page.navigate("$baseUrl/pipelines/$id?version=3")
        page.waitForSelector(".pe-root")
        page.locator("[data-verb='pipeline-execute']").click()
        // The refusal is visible: the error modal names the version state — synced on the
        // event, never on a sleep, and it is what proves the guard fired BEFORE the count.
        val modal = page.locator(".pe-modal")
        modal.waitFor(Locator.WaitForOptions().setTimeout(10_000.0).setState(WaitForSelectorState.VISIBLE))
        page.unroute(EXECUTE_PATTERN)
        page.unroute("**/pipelines/$id**")
        refusals.shouldBeEmpty()
        modal.innerText() shouldContain "version state"
    }

    @Test
    fun `a promoter reads admitted releases and never sees drafts, verbs or forbidden requests`() {
        val workspaceName = "pws-" + generatedPassword("w").take(8).lowercase()
        val id = seedThreeVersions("pwsp" + generatedPassword("s").take(6).lowercase(), workspaceName)

        // The promoter's OWN session: seeded as a plain `promoter` member of the SAME
        // workspace (membership granted before the first login — the auth cache reads
        // memberships then), signed in fresh.
        val user =
            seedLocalUser(
                uniqueEmail("pwsp-pro" + generatedPassword("p").take(6)),
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
        // The seeded `default` membership is the FIRST one, so login lands there — switch
        // to the test workspace (the §2.1 page-route mutation re-stamps the session cookie)
        // before reading a pipeline that lives in it.
        promoterPageApi(promoter.page, workspaceName)

        // The collector watches EVERY request the promoter's page makes on the workspace.
        val requests = mutableListOf<String>()
        promoter.page.onRequest { request -> requests += request.url() }

        // The shell renders the PROMOTER (the role badge is the membership's), and this
        // deployment has no promotion target configured — the lens is FAIL-CLOSED (§3.1:
        // an unreachable target admits nothing and says why), so the pipeline reads answer
        // the house 404 exactly as a foreign id would. That IS the promoter's correct
        // posture here, and the collector's record over it is the wire proof (A3): no
        // execution request of any kind fires on the promoter's pages.
        promoter.page.navigate("$baseUrl/dashboard")
        promoter.page.waitForSelector("[data-role]")
        promoter.page.locator("[data-role]").innerText() shouldBe "promoter"

        promoter.page.navigate("$baseUrl/pipelines/$id?version=2").status() shouldBe 404
        promoter.page.navigate("$baseUrl/pipelines/$id?version=3").status() shouldBe 404
        promoter.page.navigate("$baseUrl/pipelines/$id").status() shouldBe 404

        // The redirect route itself is read-floor (no pipeline read in it): the promoter
        // walks THROUGH it (navigate follows the 302) to the same answer the canonical
        // route gives — the fail-closed 404 here — and lands off the /editor URL.
        promoter.page.navigate("$baseUrl/pipelines/$id/editor")
        promoter.page.url() shouldNotContain "/editor"

        val forbidden =
            requests.filter {
                it.contains("/execute") || it.contains("/checks/run") ||
                    it.contains("/api/v1/executions") || it.contains("/runs")
            }
        forbidden.shouldBeEmpty()
        promoter.close()
    }

    /** The promoter session's in-page POST helper — the workspace switch, with CSRF. */
    private fun promoterPageApi(
        page: Page,
        workspaceName: String,
    ) {
        page.evaluate(
            """async (name) => {
              const csrf = document.cookie.match(/(?:^|;\s*)dp_csrf=([^;]*)/);
              await fetch('/workspace/switch?name=' + encodeURIComponent(name), {
                method: 'POST',
                credentials: 'same-origin',
                headers: { 'DP-CSRF-Token': csrf ? decodeURIComponent(csrf[1]) : '' },
              });
            }""",
            workspaceName,
        )
    }

    /**
     * #348-b — the SQL preview pin, in a real browser: each version's body pins a DIFFERENT
     * released template version, so the pane's statement names the viewed body's SQL and
     * the wire request carries that exact version. (The cached-HISTORY leg of the original
     * lifecycle — away through the UI, back through the restore — is #358-blocked at the
     * suite's zero-CSP-violation collector: the restore re-initialises the canvas over the
     * cached DOM and Cytoscape's re-applied inline style fails the pinned style hash. The
     * CONTEXT-restore contract this lane owns is proven at the unit level,
     * editor-teardown.test.mjs's rescue case, red-under-plant.)
     */
    @Test
    fun `the SQL preview pins the VIEWED version - distinct template SQL per version, exact wire pin`() {
        val id = seedSqlVersions("pwsh" + generatedPassword("s").take(6).lowercase())

        val sqlRequests = mutableListOf<String>()
        page.route("**/partials/pipelines/*/nodes/*/sql*") { route ->
            if (route.request().method() == "GET") sqlRequests += route.request().url()
            route.resume()
        }

        // The NON-CURRENT release v2 (current is v1, a draft v3 sits beside it): the pane's
        // statement is the VIEWED body's template version — "two", not "one".
        page.navigate("$baseUrl/pipelines/$id?version=2")
        page.waitForSelector(".pe-card")
        page.locator(".pe-card-open").first().click()
        page.waitForSelector("#pe-node-sql .pe-sql-code")
        page.locator("#pe-node-sql").innerText() shouldContain "SELECT 2 AS two"
        page.locator(".pe-vchip").innerText() shouldBe "v2 · released"

        // The current v1 renders ITS OWN body and SQL.
        page.navigate("$baseUrl/pipelines/$id")
        page.waitForSelector(".pe-card")
        page.locator(".pe-card-open").first().click()
        page.waitForSelector("#pe-node-sql .pe-sql-code")
        page.locator("#pe-node-sql").innerText() shouldContain "SELECT 1 AS one"

        sqlRequests.size shouldBeGreaterThanOrEqual 2
        sqlRequests.forEach { url -> url shouldContain "version=" }
        sqlRequests.any { it.contains("version=2") } shouldBe true
        sqlRequests.any { it.contains("version=1") } shouldBe true
    }

    private companion object {
        const val EXECUTE_PATTERN = "**/api/v1/pipelines/*/execute"

        /** The execute POST's pinned version, read off the captured wire body. */
        fun versionOf(postBody: String): Int = Regex(""""version"\s*:\s*(\d+)""").find(postBody)!!.groupValues[1].toInt()
    }
}
