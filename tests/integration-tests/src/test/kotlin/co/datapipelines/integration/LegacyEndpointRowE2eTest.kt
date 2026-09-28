package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import co.datapipelines.integration.E2eSession.asSession
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.restassured.RestAssured.given
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.nio.file.Files
import java.security.SecureRandom
import java.sql.DriverManager
import java.util.Base64
import java.util.UUID
import kotlin.io.path.writeText

/**
 * A published-endpoints row saved before R-EP5 never kills a boot (#274) — the owner's incident,
 * end to end: a whole application boot over a database holding the two-segment row
 * `/nyc/revenue-by-borough`, then the assertions that the poison row is inert and visible.
 *
 * ## The boots are sequential, because the incident spans a restart
 * [DemoApiKeyE2eTest]'s shape (its own Postgres, `SpringApplicationBuilder`, the shipped
 * bootstrap): boot 1 seeds the demo family and publishes the demo endpoint; the legacy row is
 * then inserted by SQL BETWEEN boots — the way it got into the owner's database, written under
 * the grammar of 2026-09-18 — and boot 2 restarts over it. On the pre-fix code the restart DIED
 * (`EndpointPathException` from the seeder's conflict check): that is this suite's red. The row
 * is seeded in its post-V41 shape (disabled, `retired_reason` set) — what an upgraded database
 * holds across every later restart; the repository's mapper-level skip of a row V41 has not
 * reached yet is pinned separately by [co.datapipelines.application.endpoints.EndpointPersistenceIntegrationTest].
 *
 * ## What boot 2 must prove
 * `/health` UP; the demo endpoint still published and still served by its key; the legacy row
 * LISTED — flagged with `legacy: true`, its reason and `enabled: false` — on the REST listing
 * and on the real MCP `endpoints_list`; and its path answering `404` on the serve path: never
 * served, never fatal, visible and removable.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class LegacyEndpointRowE2eTest {
    private var context: ConfigurableApplicationContext? = null
    private var port: Int = 0
    private var demoId: UUID = UUID.fromString("de000000-0000-0000-0000-000000000001")
    private var adminJwt: String = ""

    @Test
    @Order(1)
    fun `boot one seeds the demo family over a clean database`() {
        boot(withDemoKey = true)

        scalar<Long>(
            "SELECT COUNT(*) FROM published_endpoints pe JOIN workspaces w ON w.id = pe.workspace_id" +
                " WHERE w.name = 'demo' AND pe.path_pattern = '$DEMO_PATH'",
        ) shouldBe 1L
        demoId = scalar("SELECT id FROM workspaces WHERE name = 'demo'")
        seedSessionUser()
    }

    @Test
    @Order(2)
    fun `the legacy row is inserted between boots the way 2026-09-18 wrote it`() {
        val pipelineId = scalar<UUID>("SELECT id FROM pipelines WHERE workspace_id = '$demoId'")
        val systemActor = scalar<UUID>("SELECT id FROM users WHERE kind = 'system' LIMIT 1")
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection
                .prepareStatement(
                    "INSERT INTO published_endpoints" +
                        " (id, workspace_id, path_pattern, pipeline_id, timeout_seconds, description," +
                        " is_enabled, retired_reason, created_by)" +
                        " VALUES (?, ?, ?, ?, 30, '', FALSE, 'pre-R-EP5 path', ?)",
                ).use { ps ->
                    ps.setObject(1, UUID.nameUUIDFromBytes(LEGACY_PATH.toByteArray()))
                    ps.setObject(2, demoId)
                    ps.setString(3, LEGACY_PATH)
                    ps.setObject(4, pipelineId)
                    ps.setObject(5, systemActor)
                    ps.executeUpdate()
                }
        }

        scalar<Long>("SELECT COUNT(*) FROM published_endpoints WHERE path_pattern = '$LEGACY_PATH'") shouldBe 1L
    }

    @Test
    @Order(3)
    fun `a restart over the legacy row boots, serves the demo endpoint, and answers the legacy path 404`() {
        // THE subject: on the pre-fix code this whole boot died with EndpointPathException
        // ("Path has 2 segment(s); an endpoint is at least 3") from the demo seeder's conflict
        // check. With the fix the restart is ordinary.
        boot(withDemoKey = true)

        withClue("/health is UP — the context survived the legacy row") {
            given()
                .port(port)
                .accept("application/json")
                .`when`()
                .get("/health")
                .then()
                .statusCode(200)
                .body("status", equalTo("UP"))
        }

        // The demo endpoint is still published at its current path and still serves.
        scalar<Long>(
            "SELECT COUNT(*) FROM published_endpoints pe JOIN workspaces w ON w.id = pe.workspace_id" +
                " WHERE w.name = 'demo' AND pe.path_pattern = '$DEMO_PATH'",
        ) shouldBe 1L
        given()
            .port(port)
            .header(API_KEY_HEADER, DEMO_KEY)
            .`when`()
            .get("/api$DEMO_PATH")
            .then()
            .statusCode(200)

        // The legacy path is not served: the row is retired, and the registry never sees it.
        given()
            .port(port)
            .header(API_KEY_HEADER, DEMO_KEY)
            .`when`()
            .get("/api$LEGACY_PATH")
            .then()
            .statusCode(404)
            .body("error.code", equalTo("endpoint.not_found"))
    }

    @Test
    @Order(4)
    fun `the listing flags the legacy row with its reason, on REST and on endpoints_list`() {
        val rest =
            session(adminJwt)
                .get("/api/v1/endpoints")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()

        withClue("the REST listing carries the legacy row, flagged") {
            rest.getList<String>("data.findAll { it.legacy == true }.path") shouldBe listOf(LEGACY_PATH)
            rest.getString("data.find { it.legacy == true }.reason").shouldContain("at least 3")
            rest.getBoolean("data.find { it.legacy == true }.enabled") shouldBe false
        }

        // The real MCP surface, through a real minted key and a real tools/call.
        val minted =
            session(adminJwt)
                .contentType(io.restassured.http.ContentType.JSON)
                .body("""{"name": "legacy-e2e-agent", "kind": "mcp", "role": "author"}""")
                .post("/api/v1/auth/api-keys")
        minted.statusCode shouldBe 201
        val agentKey = minted.jsonPath().getString("data.key")

        // The tools/call result's text is a JSON payload INSIDE the JSON-RPC envelope — unescape
        // the inner quotes before matching on it (the same move SchedulerE2eTest makes).
        val mcpBody =
            given()
                .port(port)
                .header("DP-API-Key", agentKey)
                .contentType(io.restassured.http.ContentType.JSON)
                .accept("application/json, text/event-stream")
                .body("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"endpoints_list","arguments":{}}}""")
                .`when`()
                .post("/mcp")
                .then()
                .statusCode(200)
                .extract()
                .asString()
                .replace("\\\"", "\"")

        withClue("endpoints_list answers the legacy row flagged") {
            mcpBody.shouldContain("\"legacy\":true")
            mcpBody.shouldContain(LEGACY_PATH)
            mcpBody.shouldContain("at least 3")
        }
    }

    @AfterAll
    fun close() {
        context?.close()
    }

    // ------------------------------------------------------------------ boot + seed plumbing

    private fun boot(withDemoKey: Boolean) {
        context?.close()
        val args =
            (baseProperties() + if (withDemoKey) mapOf("datapipelines.bootstrap.demo-api-key" to DEMO_KEY) else emptyMap())
                .map { (k, v) -> "--$k=$v" }
                .toTypedArray()
        val newContext =
            SpringApplicationBuilder(DatapipelinesApplication::class.java)
                .web(WebApplicationType.SERVLET)
                .run(*args)
        context = newContext
        port =
            checkNotNull(newContext.environment.getProperty("local.server.port", Int::class.java)) {
                "the server did not report its port"
            }
    }

    private fun seedSessionUser() {
        // The bootstrap pre-provisions the admin from `bootstrap-admin-email` — reuse that row,
        // never a second insert of the same email; only the DEMO MEMBERSHIP is ours. The
        // session's JWT is signed for the row's own id, read back after the fact.
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    INSERT INTO workspace_members (workspace_id, user_id, role)
                    VALUES ('$demoId', (SELECT id FROM users WHERE email = 'legacy-row-admin@e2e.test'), 'author')
                    ON CONFLICT DO NOTHING
                    """.trimIndent(),
                )
            }
        }
        adminJwt =
            E2eSession.jwt(
                jwtSecret,
                scalar("SELECT id::text FROM users WHERE email = 'legacy-row-admin@e2e.test'"),
                "legacy-row-admin@e2e.test",
                "demo",
            )
    }

    private fun session(jwt: String) = given().port(port).asSession(jwt)

    @Suppress("UNCHECKED_CAST")
    private fun <T> scalar(sql: String): T = rows(sql).single().values.first() as T

    private fun rows(sql: String): List<Map<String, Any?>> =
        DriverManager
            .getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
            .use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery(sql).use { rs ->
                        val meta = rs.metaData
                        buildList {
                            while (rs.next()) {
                                add((1..meta.columnCount).associate { meta.getColumnLabel(it) to rs.getObject(it) })
                            }
                        }
                    }
                }
            }

    companion object {
        private const val API_KEY_HEADER = "DP-API-Key"
        private const val DEMO_PATH = "/demo/test/demo-api-e2e-example"
        private const val LEGACY_PATH = "/nyc/revenue-by-borough"
        private const val DEMO_KEY = "dpk_LEGACYROWAAA.ONEKEYISPUBLICBYDESIGNDEMOONLYE2E222222222222222"

        private val random = SecureRandom()
        private val jwtSecret = randomSecret()
        private val encryptionKey = randomSecret()

        private val fixtures: java.nio.file.Path = Files.createTempDirectory("legacy-row-e2e")
        private val datasourcesFile: java.nio.file.Path =
            writeFile(
                "bootstrap-datasources.yml",
                """
                datasources:
                  - name: legacy_e2e_ro
                    display_name: Legacy Row E2E read-only
                    dialect: H2
                    jdbc_url: jdbc:h2:mem:legacy_row_e2e_ro;DB_CLOSE_DELAY=-1
                    username: sa
                    password: secret
                    readonly: true
                    global: true
                """.trimIndent(),
            )
        private val examplesFile: java.nio.file.Path =
            writeFile(
                "examples.json",
                """
                {
                  "templates": [
                    {
                      "id": "test/demo_api_e2e_example.sql",
                      "dialect": "H2",
                      "display_name": "Demo API E2E example",
                      "description": "Seeded into the demo workspace",
                      "imports": [],
                      "body": "SELECT 1 AS n"
                    }
                  ],
                  "pipelines": [
                    {
                      "schema_version": 1,
                      "name": "test/demo_api_e2e_example",
                      "display_name": "Demo API E2E example",
                      "description": "Reads the seeded read-only datasource",
                      "parameters": {},
                      "nodes": [
                        {
                          "id": "read_sample",
                          "description": "DQL read from the seeded read-only datasource",
                          "type": "DQL",
                          "source": "legacy_e2e_ro",
                          "template": {"id": "test/demo_api_e2e_example.sql", "version": 1},
                          "output": {"target": "caller"},
                          "depends_on": []
                        }
                      ]
                    }
                  ]
                }
                """.trimIndent(),
            )

        private fun writeFile(
            name: String,
            content: String,
        ): java.nio.file.Path = fixtures.resolve(name).also { it.writeText(content) }

        private fun randomSecret(): String = Base64.getEncoder().encodeToString(ByteArray(32).also { random.nextBytes(it) })

        @Container
        @JvmStatic
        private val postgres =
            PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("datapipelines")
                .withUsername("datapipelines")
                .withPassword("datapipelines")

        private val redis get() = SharedE2e.redis

        private fun baseProperties(): Map<String, String> =
            mapOf(
                "server.port" to "0",
                "management.server.port" to "0",
                "spring.datasource.url" to postgres.jdbcUrl,
                "spring.datasource.username" to postgres.username,
                "spring.datasource.password" to postgres.password,
                "spring.data.redis.host" to redis.host,
                "spring.data.redis.port" to SharedE2e.redisPort.toString(),
                "spring.data.redis.password" to "",
                "datapipelines.redis.host" to redis.host,
                "datapipelines.redis.port" to SharedE2e.redisPort.toString(),
                "datapipelines.jwt.secret" to jwtSecret,
                "datapipelines.db.encryption-key" to encryptionKey,
                "datapipelines.auth.local.enabled" to "true",
                "datapipelines.auth.base-url" to "http://localhost:8080",
                "datapipelines.auth.bootstrap-admin-email" to "legacy-row-admin@e2e.test",
                "datapipelines.bootstrap.datasources-file" to datasourcesFile.toString(),
                "datapipelines.bootstrap.examples-file" to examplesFile.toString(),
            )
    }
}
