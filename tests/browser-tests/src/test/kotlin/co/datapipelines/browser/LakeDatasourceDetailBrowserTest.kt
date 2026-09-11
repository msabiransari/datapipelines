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
import java.sql.DriverManager
import java.util.UUID
import kotlin.io.path.absolutePathString

/**
 * 089 §F browser leg: the LAKE datasource detail page renders the dp-lake catalog as a
 * read-only namespace tree (metadata-db §4.15, ui-screens). A LAKE datasource over a LOCAL
 * `file://` Parquet fixture (no `catalog.kind` — the on-prem shape, no MinIO needed) with
 * two tables in the two-segment namespace `[nyc, mobility]`, registered through the REAL
 * REST surface (registration is REST/MCP-only — R10 — so the test seeds an API key and calls
 * it), then the UI walk: datasources list → the LAKE row's "Tables" link → the detail →
 * expand `nyc` → expand `mobility` → the leaves with their format and partition badges.
 *
 * The fixture is generated in `@BeforeAll` with the app's own pinned DuckDB JDBC, the
 * integration suite's generator narrowed to a local directory: `trips` day-partitioned
 * (so one leaf carries a `partition_column`) and `companies` as a single flat file.
 */
class LakeDatasourceDetailBrowserTest : BrowserSuite() {
    @Test
    fun `the lake datasource detail lists the registered tables as a namespace tree`() {
        startTrace()
        val user = seedLocalUser(uniqueEmail("lake-" + generatedPassword("u").take(8)), generatedPassword("pw"), mustChange = false)
        login(user.email, user.oneTimePassword)
        page.waitForURL("**/dashboard")
        val workspaceName = "ws-lake-" + generatedPassword("w").take(8).lowercase()
        createWorkspace(workspaceName)

        val datasource = "lake-" + generatedPassword("d").take(10).lowercase()
        val key = seedApiKey(user.email, workspaceName)
        registerLakeDatasource(key, datasource)
        registerLakeTable(
            key,
            datasource,
            """{"namespace": ["nyc", "mobility"], "name": "trips", "format": "parquet",
               "location": "file://${fixtureDir.absolutePathString()}/trips/pickup_date=*/data_*.parquet",
               "partition_column": "pickup_date"}""",
        )
        registerLakeTable(
            key,
            datasource,
            """{"namespace": ["nyc", "mobility"], "name": "companies", "format": "parquet",
               "location": "file://${fixtureDir.absolutePathString()}/companies/part-0.parquet"}""",
        )

        // The listing: the LAKE row carries the "Tables" link to the detail (089 §A).
        page.navigate("$baseUrl/datasources")
        val row = page.locator("tr", Page.LocatorOptions().setHasText(datasource)).first()
        row
            .locator(
                "a",
                com.microsoft.playwright.Locator
                    .LocatorOptions()
                    .setHasText("Tables"),
            ).click()
        page.waitForURL("**/datasources/$datasource")
        page.waitForSelector("#lake-table-tree")

        // The REST/MCP-only registration note (R10) is on the page.
        page.content() shouldContain "Registration and import are agent-first"
        page.content() shouldContain "lake_tables_"
        // 118 §7.3 — the read-only learned-facts section, in its empty state on a fresh store.
        page.waitForSelector("#ds-facts")
        page.locator("#ds-facts").innerText() shouldContain "Nothing learned yet"

        // Root level: the first namespace segment, with the live table count of its subtree.
        val nyc = page.locator("summary.tpl-summary", Page.LocatorOptions().setHasText("nyc")).first()
        nyc.locator(".tpl-label").innerText() shouldBe "nyc"
        nyc.locator(".tpl-count").innerText() shouldBe "2"

        // Expanding a folder fetches exactly one more level (the 058/067 explorer pattern).
        // PREDICATES, not globs — the namespace prefix travels as a SLASH path
        // (`?prefix=nyc/mobility`), which a `*` glob cannot cross (the suite's measured rule).
        page.waitForResponse(
            { response -> response.url().contains("/partials/datasources/") && response.url().contains("prefix=nyc") },
            { nyc.click() },
        )
        val mobility = page.locator("summary.tpl-summary", Page.LocatorOptions().setHasText("mobility")).first()
        mobility.locator(".tpl-label").innerText() shouldBe "mobility"
        page.waitForResponse(
            { response -> response.url().contains("/partials/datasources/") && response.url().contains("prefix=nyc/mobility") },
            { mobility.click() },
        )

        // The leaves: table name, parquet format badge, and the partition column where
        // the registry row carries one — static rows, no actions (read-only).
        val trips = page.locator(".tpl-leaf-static", Page.LocatorOptions().setHasText("trips")).first()
        trips.locator(".tpl-label").innerText() shouldBe "trips"
        trips.locator(".ds-badge").allInnerTexts() shouldContainExactly listOf("parquet", "pickup_date")
        val companies = page.locator(".tpl-leaf-static", Page.LocatorOptions().setHasText("companies")).first()
        companies.locator(".tpl-label").innerText() shouldBe "companies"
        companies.locator(".ds-badge").allInnerTexts() shouldContainExactly listOf("parquet")
    }

    // ------------------------------------------------------------------ REST seeding

    /** An `admin`-scoped key for the UI user, in the UI-created workspace. */
    private fun seedApiKey(
        email: String,
        workspaceName: String,
    ): String {
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
                val workspaceId =
                    connection.createStatement().use { statement ->
                        statement.executeQuery("SELECT id FROM workspaces WHERE name = '$workspaceName'").use { rs ->
                            rs.next()
                            rs.getObject(1, UUID::class.java)
                        }
                    }
                // D-R12: a key can do at most what its ISSUER can do in the pinned workspace
                // NOW. Without a membership there the issuer is a viewer, and every authoring
                // call the suite makes would be `auth.key_issuer_role_lost` — a correct refusal
                // for a fixture that forgot to say who the person is.
                connection
                    .prepareStatement(
                        "INSERT INTO workspace_members (workspace_id, user_id, author, promoter, admin)" +
                            " VALUES (?, ?, TRUE, FALSE, TRUE) ON CONFLICT (workspace_id, user_id) DO NOTHING",
                    ).use { ps ->
                        ps.setObject(1, workspaceId)
                        ps.setObject(2, userId)
                        ps.executeUpdate()
                    }
                connection
                    .prepareStatement(
                        "INSERT INTO api_keys (id, user_id, name, key_hash, scopes, workspace_id) VALUES (?, ?, ?, ?, ?, ?)",
                    ).use { ps ->
                        ps.setString(1, keyId)
                        ps.setObject(2, userId)
                        ps.setString(3, "browser-lake-detail-key")
                        ps.setString(4, hash)
                        // O-2: `admin` is not a scope a key may hold. A workspace-owned
                        // datasource registration is `author` on the credential axis and
                        // `ws_admin` on the role one — and this key's owner is an admin of the
                        // workspace it is pinned to, which is where that capability now lives.
                        ps.setArray(5, connection.createArrayOf("text", arrayOf("read", "execute", "author")))
                        ps.setObject(6, workspaceId)
                        ps.executeUpdate()
                    }
            }
        return plaintext
    }

    /** A LAKE datasource with NO `catalog.kind`: a lake over paths the process can reach. */
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

    companion object {
        private const val BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        private val random = java.security.SecureRandom()

        private lateinit var fixtureDir: Path

        /** The local Parquet set: `trips` day-partitioned (20 rows x 2 days), `companies` flat. */
        @BeforeAll
        @JvmStatic
        fun generateFixture() {
            fixtureDir = Files.createTempDirectory("lake-browser-it")
            Files.createDirectories(fixtureDir.resolve("companies"))
            DriverManager.getConnection("jdbc:duckdb:").use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        CREATE TABLE trips AS
                        SELECT i AS id,
                               CAST('2024-12-0' || (1 + (i % 2)) AS DATE) AS pickup_date,
                               CAST(i * 1.5 AS DOUBLE) AS fare
                        FROM range(0, 20) tbl(i)
                        """.trimIndent(),
                    )
                    statement.execute(
                        "COPY trips TO '${fixtureDir.resolve("trips").absolutePathString()}' " +
                            "(FORMAT PARQUET, PARTITION_BY (pickup_date))",
                    )
                    statement.execute(
                        """
                        CREATE TABLE companies AS
                        SELECT * FROM (VALUES ('uber'), ('lyft'), ('via'), ('juno')) AS t(company)
                        """.trimIndent(),
                    )
                    statement.execute(
                        "COPY companies TO '${fixtureDir.resolve("companies/part-0.parquet").absolutePathString()}' (FORMAT PARQUET)",
                    )
                }
            }
        }

        @AfterAll
        @JvmStatic
        fun removeFixture() {
            fixtureDir.toFile().deleteRecursively()
        }
    }
}
