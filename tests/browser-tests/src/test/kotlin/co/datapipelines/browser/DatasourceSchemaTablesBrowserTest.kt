package co.datapipelines.browser

import com.microsoft.playwright.Page
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.sql.DriverManager
import kotlin.io.path.absolutePathString

/**
 * 162 (#156) browser leg: a VIEWER opens the read-only Tables view on both kinds the fix
 * introduces — a discovered-schema dialect (SQLite, schemaless, so its root level IS the
 * tables level — the `flat` branch) and the LAKE registry, unchanged — and neither renders a
 * verb ([RoleVisibilityBrowserTest]'s rule, extended here to a screen that suite does not
 * visit). Mirrors [LakeDatasourceDetailBrowserTest]'s fixture-then-viewer shape: an
 * author/admin session registers both datasources through the REAL REST surface (registration
 * is REST/MCP-only, R10, and a SQLite datasource needs no live server either), then a SEPARATE
 * viewer session does the reading.
 */
class DatasourceSchemaTablesBrowserTest : BrowserSuite() {
    @Test
    fun `a viewer opens a discovered-schema datasource's tables and columns, and the LAKE registry`() {
        startTrace()
        val admin = seedLocalUser(uniqueEmail("dst-admin-" + suffix()), generatedPassword("pw"), mustChange = false)
        login(admin.email, admin.oneTimePassword)
        page.waitForURL("**/dashboard")
        val auth = sessionAuth("default")

        val sqliteName = "sqlite-" + suffix()
        registerSqliteDatasource(auth, sqliteName, sqliteFile)
        val lakeName = "lake-" + suffix()
        registerLakeDatasource(auth, lakeName)
        registerLakeTable(
            auth,
            lakeName,
            """{"namespace": ["nyc"], "name": "trips", "format": "parquet",
               "location": "file://${fixtureDir.absolutePathString()}/trips/part-0.parquet"}""",
        )

        val viewer =
            seedLocalUser(
                uniqueEmail("dst-viewer-" + suffix()),
                generatedPassword("pw"),
                mustChange = false,
                isAdmin = false,
                role = "viewer",
            )
        // A FRESH session, not a second `login()` on the admin's own page: the admin's session
        // cookie is still live there, so navigating back to `/login` would redirect straight to
        // `/dashboard` and the form this test needs would never render (measured: `#login-email`
        // timed out — the admin's own dashboard, not the login form, was already on screen).
        val session = newSession()
        session.page.navigate("$baseUrl/login")
        session.page.fill("#login-email", viewer.email)
        session.page.fill("#login-password", viewer.oneTimePassword)
        session.page.click("form button[type=submit]")
        session.page.waitForURL("**/dashboard")

        walkDiscoveredSchemaDatasource(session.page, sqliteName)
        walkLakeDatasource(session.page, lakeName)
        session.close()
    }

    /** The list button reads "Tables"; the flat (schemaless) root is the tables level itself. */
    private fun walkDiscoveredSchemaDatasource(
        page: Page,
        sqliteName: String,
    ) {
        page.navigate("$baseUrl/datasources")
        val sqliteRow = page.locator("tr", Page.LocatorOptions().setHasText(sqliteName)).first()
        val sqliteTablesLink =
            sqliteRow.locator(
                "a",
                com.microsoft.playwright.Locator
                    .LocatorOptions()
                    .setHasText("Tables"),
            )
        sqliteTablesLink.innerText() shouldBe "Tables"
        sqliteTablesLink.click()
        page.waitForURL("**/datasources/$sqliteName")
        // SQLite is schemaless: the root level IS the tables level (`flat`) — no schema folder
        // to expand first, exactly `DatasourceSchemaTreeBrowseModel.fillRoot`'s valid non-error
        // answer for an empty schemas() page.
        page.waitForSelector(".tpl-tree")
        page.content() shouldContain "Nothing here writes to the datasource"

        val zonesRow = page.locator("summary.tpl-summary", Page.LocatorOptions().setHasText("zones")).first()
        zonesRow.locator(".tpl-label").innerText() shouldBe "zones"
        page.waitForResponse(
            { response -> response.url().contains("/tables/zones/columns") },
            { zonesRow.click() },
        )
        val columnNames = page.locator(".tpl-leaf-static .tpl-label").allInnerTexts()
        columnNames shouldContainExactly listOf("location_id", "borough", "zone")
        // 179: the screen's verbs — the top bar's MCP-key chip (every role, own key) is
        // chrome, not this screen, so the count reads the main region only.
        page.locator("#app-main [data-verb]").count() shouldBe 0
        screenshot(page, "dst-sqlite-columns")
    }

    /** LAKE keeps its registry unchanged; the list button now reads "Lake tables". */
    private fun walkLakeDatasource(
        page: Page,
        lakeName: String,
    ) {
        page.navigate("$baseUrl/datasources")
        val lakeRow = page.locator("tr", Page.LocatorOptions().setHasText(lakeName)).first()
        val lakeTablesLink =
            lakeRow.locator(
                "a",
                com.microsoft.playwright.Locator
                    .LocatorOptions()
                    .setHasText("Lake tables"),
            )
        lakeTablesLink.innerText() shouldBe "Lake tables"
        lakeTablesLink.click()
        page.waitForURL("**/datasources/$lakeName")
        page.waitForSelector("#lake-table-tree")
        page.content() shouldContain "Registration and import are agent-first"

        val nyc = page.locator("summary.tpl-summary", Page.LocatorOptions().setHasText("nyc")).first()
        page.waitForResponse(
            { response -> response.url().contains("/lake-tables") && response.url().contains("prefix=nyc") },
            { nyc.click() },
        )
        val trips = page.locator(".tpl-leaf-static", Page.LocatorOptions().setHasText("trips")).first()
        trips.locator(".tpl-label").innerText() shouldBe "trips"
        page.locator("#app-main [data-verb]").count() shouldBe 0
        screenshot(page, "dst-lake-registry")
    }

    private fun screenshot(
        page: Page,
        name: String,
    ) {
        val dir = Paths.get("build", "reports", "browser-screenshots").also { it.toFile().mkdirs() }
        page.screenshot(Page.ScreenshotOptions().setPath(dir.resolve("$name.png")))
    }

    // ------------------------------------------------------------------ REST seeding

    /**
     * The signed-in page's OWN session, for the REST fixture calls: REST is a session's surface
     * since #215 B2 (the MCP key reaches `/mcp` only), and registering an in-process engine is a
     * super admin's act — which no key is (B1). [workspace] is named explicitly on each call.
     */
    private fun sessionAuth(workspace: String): RestAuth {
        val cookies = page.context().cookies().associate { it.name to it.value }
        val session = checkNotNull(cookies["dp_session"]) { "no dp_session cookie in the browser context" }
        val csrf = checkNotNull(cookies["dp_csrf"]) { "no dp_csrf cookie in the browser context" }
        return RestAuth("dp_session=$session; dp_csrf=$csrf", csrf, workspace)
    }

    /** A session's REST credentials: the cookie pair, the CSRF double-submit value, the workspace. */
    private class RestAuth(
        val cookie: String,
        val csrf: String,
        val workspace: String,
    )

    private fun registerSqliteDatasource(
        auth: RestAuth,
        name: String,
        file: Path,
    ) {
        post(
            auth,
            "/api/v1/datasources",
            // `open_mode: "1"` (xerial SQLiteOpenMode.READONLY) is required alongside
            // `readonly: true` (datasources.md §8A.4) — without it the pool fails to
            // build and every introspection call answers `datasource_unreachable`.
            """{"name": "$name", "display_name": "Browser SQLite", "dialect": "SQLITE",
               "jdbc_url": "jdbc:sqlite:${file.absolutePathString()}", "readonly": true,
               "credential": {"kind": "none"},
               "properties": {"jdbc": {"open_mode": "1"}}}""",
            expected = 201,
        )
    }

    private fun registerLakeDatasource(
        auth: RestAuth,
        name: String,
    ) {
        post(
            auth,
            "/api/v1/datasources",
            """{"name": "$name", "display_name": "Browser Lake", "dialect": "LAKE",
               "jdbc_url": "jdbc:duckdb::memory:", "readonly": true,
               "credential": {"kind": "none"}}""",
            expected = 201,
        )
    }

    private fun registerLakeTable(
        auth: RestAuth,
        datasource: String,
        body: String,
    ) = post(auth, "/api/v1/datasources/$datasource/tables", body, expected = 201)

    private fun post(
        auth: RestAuth,
        path: String,
        body: String,
        expected: Int,
    ) {
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
    }

    private fun suffix(): String = generatedPassword("s").take(8).lowercase()

    private companion object {
        private lateinit var sqliteFile: Path
        private lateinit var fixtureDir: Path

        /** A `zones`-shaped SQLite file (the [co.datapipelines.integration]-suite fixture, one table) plus a one-row lake Parquet. */
        @BeforeAll
        @JvmStatic
        fun generateFixtures() {
            val dir = Files.createTempDirectory("dst-browser-it")
            sqliteFile = dir.resolve("reference.db")
            DriverManager.getConnection("jdbc:sqlite:${sqliteFile.absolutePathString()}").use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("CREATE TABLE zones (location_id INTEGER PRIMARY KEY, borough TEXT NOT NULL, zone TEXT NOT NULL)")
                    statement.execute(
                        "INSERT INTO zones (location_id, borough, zone) VALUES " +
                            "(1, 'Manhattan', 'Midtown'), (2, 'Brooklyn', 'Williamsburg')",
                    )
                }
            }
            fixtureDir = dir.resolve("lake")
            Files.createDirectories(fixtureDir.resolve("trips"))
            DriverManager.getConnection("jdbc:duckdb:").use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("CREATE TABLE trips AS SELECT i AS id, CAST(i * 1.5 AS DOUBLE) AS fare FROM range(0, 5) tbl(i)")
                    statement.execute(
                        "COPY trips TO '${fixtureDir.resolve("trips/part-0.parquet").absolutePathString()}' (FORMAT PARQUET)",
                    )
                }
            }
        }

        @AfterAll
        @JvmStatic
        fun removeFixtures() {
            sqliteFile.parent.toFile().deleteRecursively()
        }
    }
}
