package co.datapipelines.browser

import com.microsoft.playwright.Page
import de.mkammerer.argon2.Argon2Factory
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
import java.util.UUID
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
        // 179 (V31): the fixture key exists BEFORE the login — the login's own mint then
        // loses the unique-index race by design and this key stays the suite's credential.
        val key = seedApiKey(admin.email)
        login(admin.email, admin.oneTimePassword)
        page.waitForURL("**/dashboard")

        val sqliteName = "sqlite-" + suffix()
        registerSqliteDatasource(key, sqliteName, sqliteFile)
        val lakeName = "lake-" + suffix()
        registerLakeDatasource(key, lakeName)
        registerLakeTable(
            key,
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

    /** An admin-scoped key for [email]'s membership of the shared `default` workspace. */
    private fun seedApiKey(email: String): String {
        val keyId = "dpk_" + (1..12).map { BASE32[random.nextInt(BASE32.length)] }.joinToString("")
        val plaintext = keyId + "." + (1..48).map { BASE32[random.nextInt(BASE32.length)] }.joinToString("")
        val argon2 = Argon2Factory.create(Argon2Factory.Argon2Types.ARGON2id)
        val hash = argon2.hash(2, 19_456, 1, plaintext.toCharArray())
        DriverManager
            .getConnection(SharedBrowserE2e.jdbcUrl, SharedBrowserE2e.username, SharedBrowserE2e.password)
            .use { connection ->
                val userId =
                    connection.createStatement().use { statement ->
                        statement.executeQuery("SELECT id FROM users WHERE email = '$email'").use { rs ->
                            rs.next()
                            rs.getObject(1, UUID::class.java)
                        }
                    }
                // 179 (V31): the sign-in minted the user's login key in this workspace
                // already (one live `user` key per pair is a UNIQUE INDEX now) — the
                // fixture's key replaces it. Revoke, never delete: audit_log.key_id keeps
                // resolving.
                connection
                    .prepareStatement(
                        "UPDATE api_keys SET is_revoked = TRUE WHERE user_id = ? AND kind = 'user' AND is_revoked = FALSE" +
                            " AND workspace_id = 'defa0000-0000-0000-0000-000000000001'",
                    ).use { ps ->
                        ps.setObject(1, userId)
                        ps.executeUpdate()
                    }
                connection
                    .prepareStatement(
                        "INSERT INTO api_keys (id, user_id, name, key_hash, scopes, workspace_id)" +
                            " VALUES (?, ?, ?, ?, ?, 'defa0000-0000-0000-0000-000000000001')",
                    ).use { ps ->
                        ps.setString(1, keyId)
                        ps.setObject(2, userId)
                        ps.setString(3, "browser-schema-tables-key")
                        ps.setString(4, hash)
                        ps.setArray(5, connection.createArrayOf("text", arrayOf("read", "execute", "author")))
                        ps.executeUpdate()
                    }
            }
        return plaintext
    }

    private fun registerSqliteDatasource(
        key: String,
        name: String,
        file: Path,
    ) {
        post(
            key,
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
        key: String,
        name: String,
    ) {
        post(
            key,
            "/api/v1/datasources",
            """{"name": "$name", "display_name": "Browser Lake", "dialect": "LAKE",
               "jdbc_url": "jdbc:duckdb::memory:", "readonly": true,
               "credential": {"kind": "none"}}""",
            expected = 201,
        )
    }

    private fun registerLakeTable(
        key: String,
        datasource: String,
        body: String,
    ) = post(key, "/api/v1/datasources/$datasource/tables", body, expected = 201)

    private fun post(
        key: String,
        path: String,
        body: String,
        expected: Int,
    ) {
        val request =
            HttpRequest
                .newBuilder(URI.create("$baseUrl$path"))
                .header("DP-API-Key", key)
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
        private const val BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        private val random = java.security.SecureRandom()

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
