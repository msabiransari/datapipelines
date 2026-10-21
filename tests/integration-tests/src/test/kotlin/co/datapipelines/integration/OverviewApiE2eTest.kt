package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import co.datapipelines.integration.E2eSession.asSession
import com.sun.net.httpserver.HttpServer
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.restassured.RestAssured.given
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.InetSocketAddress
import java.sql.DriverManager
import java.util.UUID

/**
 * #585 — the API home's numbers are the console's and the Keys page's, per role, over a real
 * database and the real promoter lens (a stub higher environment, [PromoterLensSweepTest]'s way).
 *
 * One workspace: two released pipelines, one the lens admits (absent on the target) and one it
 * hides (on the target at the same version and hash); three endpoints — a valid one on each
 * pipeline and a LEGACY row (a stored path the grammar refuses) on the admitted one; five keys of
 * three kinds by two creators (the author's `mcp` key; the workspace admin's `mcp`, `endpoint` and
 * two `dashboard` keys, one of them revoked). A second workspace holds an endpoint and a key whose
 * names must reach no page of the first.
 *
 * For the viewer, the author, the promoter and the workspace admin the home's Endpoints tile
 * equals the console's valid rows plus its legacy rows, and its keys tile equals the Keys page's
 * rows — the same reads, so the numbers cannot drift. The expected values are spelled out too, so
 * a narrowing that both pages lost together is still red: the viewer sees no key (it created
 * none — the Keys page's `mcp_key.own` view), the promoter sees only the admitted pipeline's two
 * endpoints, the workspace admin sees all five keys. #639 (#621): through the same real lens
 * the promoter's three endpoint metrics carry the frame's lens note and the keys metric does not,
 * under the heading "Inventory"; a viewer's page carries no lens note.
 *
 * ## Falsifying it
 * Drop the `API_KEY_READ` branch of `keysVisibleTo` (everyone sees every key) and the viewer's
 * expected 0 is red; count endpoints over the unlensed repository and the promoter's expected 2
 * is red. The #585 handback pastes both runs.
 */
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
class OverviewApiE2eTest {
    @LocalServerPort
    private var port: Int = 0

    @Test
    fun `per role the home's totals are the console's rows and the Keys page's rows, and nothing of another workspace`() {
        ensureSeeded()
        EXPECTED.forEach { (role, expected) ->
            withClue("as $role") {
                val home = page("/overview/api-console", role)
                val console = page("/api-console", role)
                val keysPage = page("/api-keys", role)
                val tiles = tiles(home)
                val consoleValid = GET_METHOD.findAll(console).count()
                val consoleLegacy = LEGACY_CHIP.findAll(console).count()
                val keyRows = KEY_ROW.findAll(keysPage).count()
                println(
                    "event=api.home.counts role=$role page_endpoints=${tiles["endpoints"]} console=${consoleValid + consoleLegacy} " +
                        "page_release=${tiles["release"]} page_draft=${tiles["draft"]} page_keys=${tiles["keys"]} keys_page=$keyRows",
                )

                withClue("the Endpoints tile is the console's valid rows plus its legacy rows") {
                    tiles["endpoints"] shouldBe consoleValid + consoleLegacy
                }
                withClue("the keys tile is the Keys page's rows") { tiles["keys"] shouldBe keyRows }
                tiles shouldBe expected.tiles
                consoleLegacy shouldBe expected.legacy
                home shouldContain expected.keysHeading

                withClue("no page of this workspace names another workspace's endpoint or key") {
                    listOf(home, console, keysPage).forEach { body ->
                        body shouldNotContain OTHER_ENDPOINT
                        body shouldNotContain OTHER_KEY_NAME
                    }
                }
                withClue("no credential value is rendered: the config's placeholder is the one dpk_ on the home") {
                    KEY_IDS.forEach { id -> home shouldNotContain id }
                    Regex("dpk_").findAll(home).count() shouldBe 1
                }
            }
        }

        withClue("the promoter's home and console never name the endpoint over the hidden pipeline") {
            page("/overview/api-console", PROMOTER) shouldNotContain HIDDEN_ENDPOINT
            page("/api-console", PROMOTER) shouldNotContain HIDDEN_ENDPOINT
        }
        withClue("#621 through the real lens: the promoter's three endpoint metrics say admitted, the keys metric does not") {
            val home = page("/overview/api-console", PROMOTER)
            home shouldContain "id=\"overview-inventory-h\">Inventory<"
            LENS_NOTE.findAll(home).count() shouldBe LENSED_METRICS
            KEYS_METRIC.find(home)!!.value shouldNotContain "app-overview-count-lens"
            LENS_NOTE.findAll(page("/overview/api-console", VIEWER)).count() shouldBe 0
        }
        withClue("the workspace admin's keys chart says what it did not count") {
            page("/overview/api-console", WORKSPACE_ADMIN) shouldContain "Not counted: 0 expired, 1 revoked."
        }
    }

    private fun page(
        path: String,
        role: String,
    ): String {
        val response =
            given()
                .port(port)
                .asSession(E2eSession.jwt(jwtSecret, USERS.getValue(role), "$role@apihome.test", WS_NAME))
                .redirects()
                .follow(false)
                .`when`()
                .get(path)
                .then()
                .extract()
        withClue("GET $path as $role -> ${response.statusCode()}") { response.statusCode() shouldBe HTTP_OK }
        return response.asString()
    }

    /** The frame's tiles: `data-count="<key>"` and the number in its value cell, in page order. */
    private fun tiles(html: String): Map<String, Int> = TILE.findAll(html).associate { it.groupValues[1] to it.groupValues[2].toInt() }

    private data class Expected(
        val tiles: Map<String, Int>,
        val legacy: Int,
        val keysHeading: String,
    )

    companion object {
        private const val HTTP_OK = 200
        private const val VIEWER = "viewer"
        private const val AUTHOR = "author"
        private const val PROMOTER = "promoter"
        private const val WORKSPACE_ADMIN = "workspace_admin"
        private const val WS_ID = "abc00000-0000-0000-0000-000000000585"
        private const val WS_NAME = "apihome"
        private const val OTHER_WS_ID = "abc00000-0000-0000-0000-000000005852"
        private const val OTHER_WS_NAME = "apihome_other"
        private const val ADMITTED_ENDPOINT = "/apihome/v1/admitted"
        private const val HIDDEN_ENDPOINT = "/apihome/v1/hidden_on_target"

        /** One segment: the grammar's floor is three (R-EP5), so the row reads back as legacy. */
        private const val LEGACY_ENDPOINT = "/apihome_legacy"
        private const val OTHER_ENDPOINT = "/apiother/v1/never_shown"
        private const val OTHER_KEY_NAME = "apiother-never-shown"

        private val PIPE_ADMITTED: UUID = UUID.fromString("1a000000-0000-0000-0000-000000000585")
        private val PIPE_HIDDEN: UUID = UUID.fromString("1b000000-0000-0000-0000-000000000585")
        private val PIPE_OTHER: UUID = UUID.fromString("1c000000-0000-0000-0000-000000000585")

        private val USERS: Map<String, String> =
            mapOf(
                VIEWER to "0a000000-0000-0000-0000-000000000585",
                AUTHOR to "0b000000-0000-0000-0000-000000000585",
                PROMOTER to "0c000000-0000-0000-0000-000000000585",
                WORKSPACE_ADMIN to "0d000000-0000-0000-0000-000000000585",
            )
        private const val OTHER_ADMIN = "0e000000-0000-0000-0000-000000000585"

        private val EXPECTED: Map<String, Expected> =
            mapOf(
                VIEWER to Expected(tiles(endpoints = 3, release = 2, keys = 0), legacy = 1, keysHeading = "Your keys"),
                AUTHOR to Expected(tiles(endpoints = 3, release = 2, keys = 1), legacy = 1, keysHeading = "Your keys"),
                PROMOTER to Expected(tiles(endpoints = 2, release = 1, keys = 0), legacy = 1, keysHeading = "Your keys"),
                WORKSPACE_ADMIN to Expected(tiles(endpoints = 3, release = 2, keys = 5), legacy = 1, keysHeading = "Workspace keys"),
            )

        private fun tiles(
            endpoints: Int,
            release: Int,
            keys: Int,
        ): Map<String, Int> = mapOf("endpoints" to endpoints, "release" to release, "draft" to 0, "keys" to keys)

        private val TILE = Regex("data-count=\"([a-z-]+)\"[\\s\\S]*?app-overview-count-v\">(\\d+)<")
        private val GET_METHOD = Regex("<span class=\"app-method\">GET</span>")
        private val LEGACY_CHIP = Regex("</i>Legacy</span>")
        private val KEY_ROW = Regex("<span class=\"app-name\">")
        private val LENS_NOTE = Regex("class=\"app-overview-count-lens\"")
        private val KEYS_METRIC = Regex("data-count=\"keys\">[\\s\\S]*?</div>")
        private const val LENSED_METRICS = 3

        /** A seeded key: its creator, kind, role and whether it is revoked; the workspace's unless [workspace] says otherwise. */
        private class Key(
            val name: String,
            val creator: String,
            val kind: String,
            val role: String?,
            val revoked: Boolean = false,
            val workspace: String = WS_ID,
        ) {
            val seeded: E2eAuth.SeededKey = E2eAuth.generateKey(name, ownerId = UUID.randomUUID().toString())
        }

        private val KEYS: List<Key> =
            listOf(
                Key("author-mcp", USERS.getValue(AUTHOR), "mcp", "author"),
                Key("admin-mcp", USERS.getValue(WORKSPACE_ADMIN), "mcp", "workspace_admin"),
                Key("admin-endpoint", USERS.getValue(WORKSPACE_ADMIN), "endpoint", "api_caller"),
                Key("admin-dashboard", USERS.getValue(WORKSPACE_ADMIN), "dashboard", "dashboard_viewer"),
                Key("admin-dashboard-old", USERS.getValue(WORKSPACE_ADMIN), "dashboard", "dashboard_viewer", revoked = true),
                Key(OTHER_KEY_NAME, OTHER_ADMIN, "mcp", "workspace_admin", workspace = OTHER_WS_ID),
            )
        private val KEY_IDS: List<String> = KEYS.map { it.seeded.id }

        private const val PIPELINE_BODY = """{"schema_version":1,"name":"apihome","display_name":"API home","description":"","nodes":[]}"""

        /** The target holds the hidden pipeline at the same version and hash, and nothing else. */
        private val INVENTORY =
            """{"schema_version":1,"correlation_id":"stub","data":{"deployment":"uat","authoring_enabled":false,"workspace":"$WS_NAME",""" +
                """"pipelines":[{"name":"apihome/hidden_on_target","current_version":1,"body_hash":"hash-hidden"}],""" +
                """"templates":[],"datasources":[]}}"""

        private val jwtSecret: String = E2eSession.newSecret()
        private val stub: HttpServer by lazy {
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).also { server ->
                server.createContext("/api/v1/promotion/inventory") { exchange ->
                    val bytes = INVENTORY.toByteArray(Charsets.UTF_8)
                    exchange.responseHeaders.add("Content-Type", "application/json")
                    exchange.sendResponseHeaders(HTTP_OK, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
                server.start()
            }
        }

        @Volatile
        private var seeded = false

        @Synchronized
        fun ensureSeeded() {
            if (seeded) return
            seeded = true
            E2eClean.beforeSeeding()
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                connection.createStatement().use { statement ->
                    seedPrincipals(statement)
                    seedPipelinesAndEndpoints(statement)
                }
                seedKeys(connection)
            }
        }

        private fun seedPrincipals(statement: java.sql.Statement) {
            statement.execute(
                "INSERT INTO workspaces (id, name, display_name) VALUES " +
                    "('$WS_ID', '$WS_NAME', 'API home'), ('$OTHER_WS_ID', '$OTHER_WS_NAME', 'API other')",
            )
            (USERS.map { (role, id) -> Triple(role, id, WS_ID) } + Triple(WORKSPACE_ADMIN, OTHER_ADMIN, OTHER_WS_ID))
                .forEach { (role, id, workspace) ->
                    statement.execute(
                        "INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin) VALUES " +
                            "('$id', '$id@apihome.test', '$role', 'test', '$id-apihome-sub', TRUE, FALSE)",
                    )
                    statement.execute("INSERT INTO workspace_members (workspace_id, user_id, role) VALUES ('$workspace', '$id', '$role')")
                }
        }

        private fun seedPipelinesAndEndpoints(statement: java.sql.Statement) {
            val owner = USERS.getValue(WORKSPACE_ADMIN)
            statement.execute(
                """
                INSERT INTO pipelines (id, name, display_name, description, owner_id, workspace_id, current_version) VALUES
                    ('$PIPE_ADMITTED', 'apihome/admitted', 'Admitted', '', '$owner', '$WS_ID', 1),
                    ('$PIPE_HIDDEN', 'apihome/hidden_on_target', 'Hidden on target', '', '$owner', '$WS_ID', 1),
                    ('$PIPE_OTHER', 'apiother/never_shown', 'Never shown', '', '$OTHER_ADMIN', '$OTHER_WS_ID', 1)
                """.trimIndent(),
            )
            statement.execute(
                """
                INSERT INTO pipeline_versions
                    (pipeline_id, version, body_json, body_hash, status, created_by, released_by, released_at) VALUES
                    ('$PIPE_ADMITTED', 1, '$PIPELINE_BODY'::jsonb, 'hash-admitted', 'RELEASED', '$owner', '$owner', NOW()),
                    ('$PIPE_HIDDEN', 1, '$PIPELINE_BODY'::jsonb, 'hash-hidden', 'RELEASED', '$owner', '$owner', NOW()),
                    ('$PIPE_OTHER', 1, '$PIPELINE_BODY'::jsonb, 'hash-other', 'RELEASED', '$OTHER_ADMIN', '$OTHER_ADMIN', NOW())
                """.trimIndent(),
            )
            statement.execute(
                "INSERT INTO published_endpoints " +
                    "(id, workspace_id, path_pattern, pipeline_id, timeout_seconds, description, created_by) VALUES " +
                    "('${UUID.randomUUID()}', '$WS_ID', '$ADMITTED_ENDPOINT', '$PIPE_ADMITTED', 30, '', '$owner'), " +
                    "('${UUID.randomUUID()}', '$WS_ID', '$HIDDEN_ENDPOINT', '$PIPE_HIDDEN', 30, '', '$owner'), " +
                    "('${UUID.randomUUID()}', '$WS_ID', '$LEGACY_ENDPOINT', '$PIPE_ADMITTED', 30, '', '$owner'), " +
                    "('${UUID.randomUUID()}', '$OTHER_WS_ID', '$OTHER_ENDPOINT', '$PIPE_OTHER', 30, '', '$OTHER_ADMIN')",
            )
        }

        /** Each key's own `service` identity FIRST (the row FKs to it — keys v2 A13), then the row. */
        private fun seedKeys(connection: java.sql.Connection) {
            connection.createStatement().use { statement ->
                KEYS.forEach { key ->
                    statement.execute(
                        "INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin, kind) VALUES " +
                            "('${key.seeded.ownerId}', '${key.seeded.id.lowercase()}@keys.invalid', '${key.name}', 'key', " +
                            "'${key.seeded.id}', TRUE, FALSE, 'service')",
                    )
                }
            }
            connection
                .prepareStatement(
                    "INSERT INTO api_keys (id, user_id, created_by, name, key_hash, workspace_id, kind, role, is_revoked)" +
                        " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                ).use { ps ->
                    KEYS.forEach { key ->
                        ps.setString(1, key.seeded.id)
                        ps.setObject(2, UUID.fromString(key.seeded.ownerId))
                        ps.setObject(3, UUID.fromString(key.creator))
                        ps.setString(4, key.name)
                        ps.setString(5, key.seeded.hash)
                        ps.setObject(6, UUID.fromString(key.workspace))
                        ps.setString(7, key.kind)
                        ps.setString(8, key.role)
                        ps.setBoolean(9, key.revoked)
                        ps.addBatch()
                    }
                    ps.executeBatch()
                }
        }

        private val postgres get() = SharedE2e.postgres
        private val redis get() = SharedE2e.redis
        private val oidc = OidcDiscoveryStub()

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("management.server.port") { "0" }
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
            registry.add("datapipelines.redis.password") { "" }
            registry.add("datapipelines.redis.host") { redis.host }
            registry.add("datapipelines.redis.port") { SharedE2e.redisPort }
            registry.add("datapipelines.jwt.secret") { jwtSecret }
            registry.add("datapipelines.db.encryption-key") { E2eSession.newSecret() }
            // The lens needs a higher environment: the stub is it, one long cache window.
            registry.add("datapipelines.deployment.promotion.target.base-url") { "http://127.0.0.1:${stub.address.port}" }
            registry.add("datapipelines.deployment.promotion.target.server-key") { "api-home-" + UUID.randomUUID() }
            registry.add("datapipelines.deployment.promotion.inventory-cache-ttl-seconds") { "600" }
            listOf("google", "microsoft").forEachIndexed { index, name ->
                registry.add("datapipelines.auth.oidc.providers[$index].name") { name }
                registry.add("datapipelines.auth.oidc.providers[$index].client-id") { "test-$name-client-id" }
                registry.add("datapipelines.auth.oidc.providers[$index].client-secret") { "test-$name-client-secret" }
                registry.add("datapipelines.auth.oidc.providers[$index].issuer-uri") { oidc.issuer }
                registry.add("datapipelines.auth.oidc.providers[$index].display-name") { "Test $name" }
            }
            registry.add("datapipelines.auth.base-url") { "http://localhost:8080" }
        }

        @JvmStatic
        @AfterAll
        fun tearDown() {
            oidc.close()
            runCatching { stub.stop(0) }
        }
    }
}
