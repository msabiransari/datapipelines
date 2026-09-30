package co.datapipelines.browser

import com.microsoft.playwright.Page
import com.sun.net.httpserver.HttpServer
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Paths
import java.sql.DriverManager

/**
 * The promotion page's transfer-family rows in a REAL browser (#10 L1c) — the
 * [PromotionSetsBrowserTest] shape: a seeded workspace admin signs in, opens `/promotion`,
 * and the plan must offer the workspace's promotable visualizations and dashboards in their
 * own tables — `name="visualization"` / `name="dashboard"` checkboxes inside the same form as
 * the pipelines' — with the read-only arm for a non-promoter carrying the same rows without
 * any input.
 *
 * The higher environment is a STUB on a loopback port (the browser harness boots ONE app per
 * spec class), so every released object reads "absent on target" — the visible-candidate
 * state the screens photograph. No push is attempted: the promotion act itself is
 * `PromotionTwoDeploymentE2eTest`'s (Orders 60–61), and this suite's subject is the PAGE.
 *
 * The fixtures are released BY SQL — the evidence gate refuses every REST release until L4,
 * so the rows a real release writes (V42) are stamped directly, the L2 fixtures' precedent.
 * The dashboard's fixture pipeline is the suite's own (datasource, DQL template, pipeline —
 * each released over REST), so nothing depends on which sample content a deployment ships.
 */
class PromotionTransferBrowserTest : BrowserSuite() {
    @Test
    fun `the page offers the transfer families to a promoter, light and dark`() {
        startTrace()
        val admin = remember(seedLocalUser(uniqueEmail("promo-tx-admin"), generatedPassword("pw"), mustChange = false))
        login(admin.email, admin.oneTimePassword)
        page.waitForURL("**/dashboard")

        val suffix = "p" + suffix()
        postForBody(
            sessionAuth(),
            "/api/v1/datasources",
            """{"name": "tx-ds-$suffix", "display_name": "Transfer rows", "dialect": "H2",
               "jdbc_url": "jdbc:h2:mem:tx_$suffix;DB_CLOSE_DELAY=-1", "username": "sa", "password": "sa"}""",
            expected = 201,
        )
        val pipeline = createReleasedPipelineFixture(suffix)
        createReleasedVisualization(suffix)
        createReleasedDashboard(suffix, pipeline)

        // The promoter sees the two family tables inside the form, with the same Send column.
        page.navigate("$baseUrl/promotion")
        page.waitForSelector("h2:has-text('Visualizations')")
        // The reused browser database holds earlier runs' dashboards too (each admitted) — the
        // assertion is THIS run's rows, pinned by value, the 313 test's shape.
        page.locator("form input[name=visualization][value=\"tx-viz/$suffix\"]").count() shouldBe 1
        page.locator("form table[data-promotion-visualizations=send] th:has-text('Send')").count() shouldBe 1
        page.locator("form input[name=dashboard][value=\"tx-dash/$suffix\"]").count() shouldBe 1
        page.locator("form table[data-promotion-dashboards=send] th:has-text('Send')").count() shouldBe 1

        // One form, one submit — the family checkboxes ride the same form as the pipelines'.
        page.locator("form button[data-verb=promote]").count() shouldBe 1

        // Light and dark, per the screens contract.
        ensureTheme("light")
        screenshot("promotion-transfer-light")
        ensureTheme("dark")
        screenshot("promotion-transfer-dark")
    }

    @Test
    fun `a non-promoter reads the family rows with no input anywhere`() {
        // The datasource is a workspace-admin fixture (datasource.manage); the AUTHOR builds the rest —
        // templates, pipelines, visualizations and dashboards are all author verbs.
        val admin = remember(seedLocalUser(uniqueEmail("promo-tx-admin2"), generatedPassword("pw"), mustChange = false))
        val author =
            remember(
                seedLocalUser(
                    uniqueEmail("promo-tx-author"),
                    generatedPassword("pw"),
                    mustChange = false,
                    isAdmin = false,
                    role = "author",
                ),
            )
        val suffix = "a" + suffix()
        login(admin.email, admin.oneTimePassword)
        page.waitForURL("**/dashboard")
        postForBody(
            sessionAuth(),
            "/api/v1/datasources",
            """{"name": "tx-ds-$suffix", "display_name": "Transfer rows", "dialect": "H2",
               "jdbc_url": "jdbc:h2:mem:txa_$suffix;DB_CLOSE_DELAY=-1", "username": "sa", "password": "sa"}""",
            expected = 201,
        )
        // The author signs in on a FRESH context (the admin's page cannot reach /login again) and
        // walks there: the reader arm's subject is what a non-promoter sees.
        val readerPage = newSession().page
        readerPage.navigate("$baseUrl/login")
        readerPage.fill("#login-email", author.email)
        readerPage.fill("#login-password", author.oneTimePassword)
        readerPage.click("form button[type=submit]")
        readerPage.waitForURL("**/dashboard")

        val pipeline = createReleasedPipelineFixture(suffix)
        createReleasedVisualization(suffix)
        createReleasedDashboard(suffix, pipeline)

        readerPage.navigate("$baseUrl/promotion")
        readerPage.waitForSelector("table[data-promotion-plan-visualizations=read-only]")
        readerPage.content() shouldContain "tx-viz/$suffix"
        readerPage.content() shouldContain "tx-dash/$suffix"
        // The reader arm: no form, no checkbox — the same content, no control the server would refuse.
        val main = readerPage.locator("main").innerHTML()
        main.contains("<form") shouldBe false
        main.contains("name=\"visualization\"") shouldBe false
        main.contains("name=\"dashboard\"") shouldBe false
    }

    // ------------------------------------------------------------------ fixture

    /** Unique per call AND per run — the browser database is a reused container; a reused name is a 409. */
    private fun suffix(): String =
        generatedPassword("s").take(4).lowercase() +
            java.lang.Long
                .toString(System.nanoTime(), 36)
                .takeLast(8)

    /** The response's `data.id` — the server-assigned UUID (the body's own `id` keys are node ids, never UUIDs). */
    private fun dataId(body: String): String =
        Regex("\"id\":\"([0-9a-f-]{36})\"").find(body)?.groupValues?.get(1) ?: error("no data.id (uuid) in $body")

    /** The response's `data.body_hash` — the 64-hex sha256 (the body's node keys never carry one). */
    private fun dataHash(body: String): String =
        Regex("\"body_hash\":\"([0-9a-f]{64})\"").find(body)?.groupValues?.get(1) ?: error("no data.body_hash in $body")

    /** The fixture pipeline over the datasource the CALLER registered (a workspace-admin fixture — see the tests). */
    private fun createReleasedPipelineFixture(suffix: String): String {
        val auth = sessionAuth()
        val template =
            postForBody(
                auth,
                "/api/v1/templates",
                """{"id": "tx-tpl-$suffix/rows.sql", "dialect": "H2", "display_name": "Rows", "description": "the fixture select",
                   "body": "SELECT month, amount FROM revenue ORDER BY month"}""",
                expected = 201,
            )
        post(
            auth,
            "/api/v1/templates/release",
            """{"name": "tx-tpl-$suffix/rows.sql"}""",
            headers = mapOf("If-Match" to dataHash(template)),
            expected = 200,
        )
        val pipeline =
            postForBody(
                auth,
                "/api/v1/pipelines",
                """{"schema_version": 1, "name": "tx-pipe-$suffix/revenue", "display_name": "Revenue", "description": "the fixture source",
                   "nodes": [{"id": "read", "description": "read", "type": "DQL", "source": "tx-ds-$suffix",
                              "template": {"id": "tx-tpl-$suffix/rows.sql", "version": 1},
                              "output": {"target": "caller"}, "depends_on": []}]}""",
                expected = 201,
            )
        post(
            auth,
            "/api/v1/pipelines/${dataId(pipeline)}/release",
            "",
            headers = mapOf("If-Match" to dataHash(pipeline)),
            expected = 200,
        )
        return "tx-pipe-$suffix/revenue"
    }

    /** A released visualization: created as a DRAFT over the session's REST, then stamped by SQL (the L2 precedent). */
    private fun createReleasedVisualization(suffix: String) {
        val created =
            postForBody(
                sessionAuth(),
                "/api/v1/visualizations",
                """
                {"name": "tx-viz/$suffix", "display_name": "Revenue", "description": "the promotion page's family rows (#10 L1c)",
                 "renderer": {"kind": "plotly", "version": "4"},
                 "inputs": {"revenue": {"columns": [{"name": "month", "type": "DATE", "nullable": false},
                                                    {"name": "amount", "type": "DECIMAL", "nullable": false}]}},
                 "config": {"data": [{"type": "bar", "x": [], "y": []}], "layout": {}},
                 "bindings": {"data[0].x": "month", "data[0].y": "amount"},
                 "presentation": {"title": "Revenue", "tokens": {"series": "categorical"}},
                 "tests": {"cases": [{"name": "twelve months", "fixtures": {"revenue": [{"month": "2026-01-01", "amount": 10.5}]},
                                      "assertions": [{"kind": "rendered"}]}]}}
                """.trimIndent(),
                expected = 201,
            )
        stampReleased("visualizations", "visualization_versions", "visualization_id", dataId(created))
    }

    /** A released dashboard pinning the visualization over the fixture pipeline (no set pin — the set facts are optional). */
    private fun createReleasedDashboard(
        suffix: String,
        pipeline: String,
    ) {
        val pipelineVersion = scalar("SELECT current_version FROM pipelines WHERE name = '$pipeline'")
        val created =
            postForBody(
                sessionAuth(),
                "/api/v1/dashboards",
                """
                {"name": "tx-dash/$suffix", "display_name": "Revenue overview", "description": "the promotion page's family rows (#10 L1c)",
                 "sources": [
                   {"name": "src0", "pipeline": {"name": "$pipeline", "version": $pipelineVersion}, "parameters": {}}
                 ],
                 "visualizations": [
                   {"name": "chart_a", "type": "visualization", "visualization": {"name": "tx-viz/$suffix", "version": 1},
                    "inputs": {"revenue": {"source": "src0"}}, "timeout_seconds": 120}
                 ],
                 "actions": [{"name": "refresh_overview", "type": "refresh", "scope": "targets",
                              "targets": ["chart_a"], "initial": true}],
                 "action_controls": [{"name": "refresh_button", "type": "action_control",
                                      "action": "refresh_overview", "label": "Apply"}],
                 "layout": {"grid": [
                            {"name": "chart_a", "x": 0, "y": 0, "w": 6, "h": 4},
                            {"name": "refresh_button", "x": 0, "y": 4, "w": 2, "h": 1}], "columns": 12},
                 "timeouts": {"refresh_seconds": 300}}
                """.trimIndent(),
                expected = 201,
            )
        stampReleased("dashboards", "dashboard_versions", "dashboard_id", dataId(created))
    }

    /** The rows a real release writes (V42), stamped by SQL — the evidence gate refuses REST releases until L4. */
    private fun stampReleased(
        table: String,
        versionsTable: String,
        fkColumn: String,
        id: String,
    ) {
        // The seeded member of `default` released these rows (V42's released_by FK needs a real user).
        val admin = scalar("SELECT id FROM users WHERE email = '$seededEmail'")
        DriverManager
            .getConnection(SharedBrowserE2e.jdbcUrl, SharedBrowserE2e.username, SharedBrowserE2e.password)
            .use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        "UPDATE $versionsTable SET status = 'RELEASED', released_at = NOW(), released_by = '$admin'" +
                            " WHERE $fkColumn = '$id' AND version = 1",
                    )
                    statement.execute("UPDATE $table SET current_version = 1 WHERE id = '$id'")
                }
            }
    }

    private fun scalar(sql: String): String =
        DriverManager
            .getConnection(SharedBrowserE2e.jdbcUrl, SharedBrowserE2e.username, SharedBrowserE2e.password)
            .use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery(sql).use { rs ->
                        check(rs.next()) { "no row for: $sql" }
                        rs.getString(1)
                    }
                }
            }

    /** POSTs [body] as the signed-in session and returns the response body — the fixture suites' helper shape. */
    private fun postForBody(
        auth: RestAuth,
        path: String,
        body: String,
        expected: Int,
    ): String {
        val request =
            HttpRequest
                .newBuilder(URI.create("$baseUrl$path"))
                .header("Cookie", auth.cookie)
                .header("DP-CSRF-Token", auth.csrf)
                .header("DP-Workspace", auth.workspace)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
        val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != expected) {
            throw AssertionError("POST $path failed (status=${response.statusCode()}): ${response.body()}")
        }
        return response.body()
    }

    private fun post(
        auth: RestAuth,
        path: String,
        body: String,
        expected: Int,
        headers: Map<String, String> = emptyMap(),
    ) {
        val builder =
            HttpRequest
                .newBuilder(URI.create("$baseUrl$path"))
                .header("Cookie", auth.cookie)
                .header("DP-CSRF-Token", auth.csrf)
                .header("DP-Workspace", auth.workspace)
                .header("Content-Type", "application/json")
        headers.forEach { (name, value) -> builder.header(name, value) }
        val response =
            HttpClient
                .newHttpClient()
                .send(builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != expected) {
            throw AssertionError("POST $path failed (status=${response.statusCode()}): ${response.body()}")
        }
    }

    private fun sessionAuth(): RestAuth {
        val cookies = page.context().cookies().associate { it.name to it.value }
        val session = checkNotNull(cookies["dp_session"]) { "no dp_session cookie in the browser context" }
        val csrf = checkNotNull(cookies["dp_csrf"]) { "no dp_csrf cookie in the browser context" }
        return RestAuth("dp_session=$session; dp_csrf=$csrf", csrf, "default")
    }

    /** A session's REST credentials: the cookie pair, the CSRF double-submit value, the workspace. */
    private class RestAuth(
        val cookie: String,
        val csrf: String,
        val workspace: String,
    )

    private fun screenshot(name: String) {
        val dir = Paths.get("build", "reports", "browser-screenshots").also { it.toFile().mkdirs() }
        page.screenshot(Page.ScreenshotOptions().setPath(dir.resolve("$name.png")))
    }

    private companion object {
        /** The signed-in user's email — `seedLocalUser`'s return carries it, captured per test. */
        var seededEmail: String = ""

        /**
         * The stub higher environment: the inventory holds NOTHING, so every released object here is a
         * promotable candidate ("absent" on target) — the visible-candidate state the screens photograph.
         * The dashboard lens's target arm reads the EMPTY dashboard list (the target holds none), so an
         * admitted dashboard is always "newer". Answered per request; the walk reads the page, never promotes.
         */
        private val stub: HttpServer =
            HttpServer
                .create(InetSocketAddress("127.0.0.1", 0), 0)
                .also { server ->
                    server.createContext("/api/v1/promotion/inventory") { exchange ->
                        val body =
                            """{"schema_version":1,"correlation_id":"stub","data":{"deployment":"uat",""" +
                                """"authoring_enabled":false,"workspace":"default",""" +
                                """"pipelines":[],"templates":[],"datasources":[],"parameter_sets":[],"visualizations":[],"dashboards":[]}}"""
                        val bytes = body.toByteArray(Charsets.UTF_8)
                        exchange.responseHeaders.add("Content-Type", "application/json")
                        exchange.sendResponseHeaders(200, bytes.size.toLong())
                        exchange.responseBody.use { it.write(bytes) }
                    }
                    server.start()
                }

        @DynamicPropertySource
        @JvmStatic
        fun stubTarget(registry: DynamicPropertyRegistry) {
            registry.add("datapipelines.deployment.promotion.target.base-url") { "http://127.0.0.1:${stub.address.port}" }
            registry.add("datapipelines.deployment.promotion.target.server-key") { "browser-promo-stub-key" }
        }
    }

    private fun remember(user: LocalUser): LocalUser {
        seededEmail = user.email
        return user
    }
}
