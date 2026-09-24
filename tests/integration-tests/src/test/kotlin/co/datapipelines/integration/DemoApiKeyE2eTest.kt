package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import io.kotest.matchers.shouldBe
import io.restassured.RestAssured.given
import org.hamcrest.Matchers.equalTo
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
 * The demo workspace's public API key, end to end (#224): the WHOLE application boots with a
 * demo family configured (an examples file, the way `--demo` deploys do), and the demo-data
 * page's key really works — and nothing more than the demo endpoints answers it.
 *
 * The boots are SEQUENTIAL against one database, because the contract spans restarts:
 * idempotence (re-seed changes nothing), rotation (a changed key setting revokes the old key
 * and the new one serves), and the kill switch (blank setting retracts the public API) are all
 * restart facts. The budget's `window-seconds` is set huge for the suite: the 429 must be
 * forced by COUNT, never by racing a window rollover.
 *
 * What is real: Flyway on a clean Postgres, the bootstrap startup steps at their actual
 * lifecycle point, the shipped import/publish/mint services, the security filter chain, the
 * budget filter, and the HTTP surface. What is simulated: the OIDC callback (the demo content
 * is seeded by the boot itself; no human logs in), and one published endpoint row written by
 * SQL between boots — a non-demo endpoint the seeder did not create, so the demo key's
 * binding refusal can be answered by a path that genuinely EXISTS (an unknown path would 404
 * for anyone, proving nothing).
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class DemoApiKeyE2eTest {
    private var context: ConfigurableApplicationContext? = null

    private var port: Int = 0

    // ------------------------------------------------------------------ boot 1: fresh database

    @Test
    @Order(1)
    fun `a fresh boot publishes the demo endpoint, mints one bound key, and the key serves`() {
        boot(key = KEY_ONE)

        scalar<Long>(
            "SELECT COUNT(*) FROM published_endpoints pe JOIN workspaces w ON w.id = pe.workspace_id" +
                " WHERE w.name = 'demo' AND pe.path_pattern = '$DEMO_PATH'",
        ) shouldBe 1L
        scalar<Long>(
            "SELECT COUNT(*) FROM api_keys WHERE workspace_id = '$demoId'" +
                " AND kind = 'endpoint' AND name = 'demo-public-key' AND is_revoked = FALSE",
        ) shouldBe 1L
        scalar<Long>(
            "SELECT COUNT(*) FROM endpoint_key_bindings b JOIN api_keys k ON k.id = b.api_key_id" +
                " WHERE k.name = 'demo-public-key' AND b.path_prefix = '$DEMO_PATH'",
        ) shouldBe 1L

        val body =
            given()
                .port(port)
                .header(API_KEY_HEADER, KEY_ONE)
                .`when`()
                .get("/api$DEMO_PATH")
                .then()
                .statusCode(200)
                .body("row_count", equalTo(1))
                .extract()
                .body()
                .asString()
        body.contains("execution_id") shouldBe true
    }

    @Test
    @Order(2)
    fun `the demo key's reach is the demo endpoints only`() {
        // The MCP surface: an endpoint key is refused there by KIND.
        given()
            .port(port)
            .header(API_KEY_HEADER, KEY_ONE)
            .`when`()
            .post("/mcp")
            .then()
            .statusCode(403)
        // The product's own API: the same kind refusal, one line above the scope matrix.
        given()
            .port(port)
            .header(API_KEY_HEADER, KEY_ONE)
            .`when`()
            .get("/api/v1/pipelines")
            .then()
            .statusCode(403)
    }

    @Test
    @Order(3)
    fun `the budget answers the (max+1)th request in the window with the catalogued 429`() {
        // The ceiling is three and the window's first request was spent in the first test:
        // two more 200s spend the budget, the next request is forced over it.
        listOf(200, 200).forEach {
            given()
                .port(port)
                .header(API_KEY_HEADER, KEY_ONE)
                .`when`()
                .get("/api$DEMO_PATH")
                .then()
                .statusCode(it)
        }
        val response =
            given()
                .port(port)
                .header(API_KEY_HEADER, KEY_ONE)
                .`when`()
                .get("/api$DEMO_PATH")
                .then()
                .statusCode(429)
                .header("Retry-After", org.hamcrest.Matchers.notNullValue())
                .extract()
        response.body().asString().contains("rate_limit.exceeded") shouldBe true
    }

    // ------------------------------------------------------------------ boot 2: idempotence

    @Test
    @Order(4)
    fun `a second boot changes nothing, and the non-demo row proves the binding refusal is real`() {
        val before = seededCounts()

        // A row the seeder did not write, planted between boots: the registry of the NEXT boot
        // serves it, so the demo key's binding refusal is answered by an endpoint that exists.
        insertForeignEndpoint()

        boot(key = KEY_ONE)

        seededCounts() shouldBe before
        given()
            .port(port)
            .header(API_KEY_HEADER, KEY_ONE)
            .`when`()
            .get("/api$FOREIGN_PATH")
            .then()
            .statusCode(403)
    }

    // ------------------------------------------------------------------ boot 3: rotation

    @Test
    @Order(5)
    fun `a changed key setting rotates - the old key is refused, the new one serves`() {
        boot(key = KEY_TWO)

        scalar<Long>(
            "SELECT COUNT(*) FROM api_keys WHERE workspace_id = '$demoId'" +
                " AND kind = 'endpoint' AND name = 'demo-public-key' AND is_revoked = FALSE",
        ) shouldBe 1L
        scalar<Long>(
            "SELECT COUNT(*) FROM api_keys WHERE kind = 'endpoint' AND name = 'demo-public-key'" +
                " AND is_revoked = TRUE",
        ) shouldBe 1L

        given()
            .port(port)
            .header(API_KEY_HEADER, KEY_ONE)
            .`when`()
            .get("/api$DEMO_PATH")
            .then()
            .statusCode(401)
        given()
            .port(port)
            .header(API_KEY_HEADER, KEY_TWO)
            .`when`()
            .get("/api$DEMO_PATH")
            .then()
            .statusCode(200)
    }

    // ------------------------------------------------------------------ boot 4: the kill switch

    @Test
    @Order(6)
    fun `a blank key setting is the kill switch - the public API is retracted`() {
        boot(key = "")

        scalar<Long>(
            "SELECT COUNT(*) FROM api_keys WHERE kind = 'endpoint'" +
                " AND name = 'demo-public-key' AND is_revoked = FALSE",
        ) shouldBe 0L
        scalar<Long>(
            "SELECT COUNT(*) FROM published_endpoints pe JOIN workspaces w ON w.id = pe.workspace_id" +
                " WHERE w.name = 'demo' AND pe.path_pattern = '$DEMO_PATH'",
        ) shouldBe 0L

        given()
            .port(port)
            .header(API_KEY_HEADER, KEY_TWO)
            .`when`()
            .get("/api$DEMO_PATH")
            .then()
            .statusCode(401)
        given()
            .port(port)
            .`when`()
            .get("/api$DEMO_PATH")
            .then()
            .statusCode(401)
    }

    // ------------------------------------------------------------------ helpers

    @org.junit.jupiter.api.AfterAll
    fun closeLastContext() {
        context?.close()
    }

    private fun seededCounts(): Map<String, Long> {
        fun count(sql: String): Long = scalar(sql)

        return mapOf(
            "demo endpoints" to
                count(
                    "SELECT COUNT(*) FROM published_endpoints pe JOIN workspaces w ON w.id = pe.workspace_id" +
                        " WHERE w.name = 'demo' AND pe.path_pattern LIKE '/demo/%'",
                ),
            "live managed keys" to
                count(
                    "SELECT COUNT(*) FROM api_keys WHERE workspace_id = '$demoId' AND kind = 'endpoint'" +
                        " AND name = 'demo-public-key' AND is_revoked = FALSE",
                ),
            "bindings" to
                count(
                    "SELECT COUNT(*) FROM endpoint_key_bindings b JOIN api_keys k ON k.id = b.api_key_id" +
                        " WHERE k.name = 'demo-public-key'",
                ),
        )
    }

    /** The non-demo endpoint row, created by nobody the seeder manages. */
    private fun insertForeignEndpoint() {
        val pipelineId = scalar<UUID>("SELECT id FROM pipelines WHERE workspace_id = '$demoId'")
        val creatorId = scalar<UUID>("SELECT id FROM users WHERE email = '$ADMIN_EMAIL'")
        connection().use { c ->
            c.createStatement().use { s ->
                s.execute(
                    "INSERT INTO published_endpoints (id, workspace_id, path_pattern, pipeline_id, timeout_seconds," +
                        " description, is_enabled, created_by, created_at, updated_at)" +
                        " VALUES ('${UUID.randomUUID()}', '$demoId', '$FOREIGN_PATH', '$pipelineId', 30," +
                        " 'planted by the E2E', TRUE, '$creatorId', NOW(), NOW())",
                )
            }
        }
    }

    private fun demoWorkspaceId(): UUID = scalar("SELECT id FROM workspaces WHERE name = 'demo'")

    private fun connection() = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)

    private fun rows(sql: String): List<Map<String, Any?>> =
        connection().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { rs ->
                    val columns = (1..rs.metaData.columnCount).map { rs.metaData.getColumnLabel(it) }
                    generateSequence { if (rs.next()) columns.associateWith { rs.getObject(it) } else null }.toList()
                }
            }
        }

    @Suppress("UNCHECKED_CAST")
    private fun <T> scalar(sql: String): T = rows(sql).single().values.first() as T

    private fun boot(key: String) {
        context?.close()
        val args =
            (baseProperties() + mapOf("datapipelines.bootstrap.demo-api-key" to key))
                .map { (k, v) -> "--$k=$v" }
                .toTypedArray()
        val newContext =
            SpringApplicationBuilder(DatapipelinesApplication::class.java)
                .web(WebApplicationType.SERVLET)
                .run(*args)
        context = newContext
        val reported =
            checkNotNull(newContext.environment.getProperty("local.server.port", Int::class.java)) {
                "the server did not report its port"
            }
        port = reported
        demoId = demoWorkspaceId()
    }

    private var demoId: UUID = UUID.fromString("de000000-0000-0000-0000-000000000001")

    companion object {
        private const val API_KEY_HEADER = "DP-API-Key"
        private const val DEMO_PATH = "/demo/test/demo-api-e2e-example"
        private const val FOREIGN_PATH = "/test/v1/bootstrap-e2e-example"
        private const val KEY_ONE = "dpk_E2EKEYONEAAA.ONEKEYISPUBLICBYDESIGNDEMOONLYE2E222222222222222"
        private const val KEY_TWO = "dpk_E2EKEYTWOBBA.TWOKEYISPUBLICBYDESIGNDEMOONLYE2E222222222222222"

        private const val ADMIN_EMAIL = "demo-api-e2e-admin@example.com"
        private const val BOOT_RO = "demo-api-e2e-readonly"
        private const val EXAMPLE_TEMPLATE = "test/demo_api_e2e_example.sql"
        private const val EXAMPLE_PIPELINE = "test/demo_api_e2e_example"

        private val random = SecureRandom()
        private val jwtSecret = randomSecret()
        private val encryptionKey = randomSecret()

        private val fixtures: java.nio.file.Path = Files.createTempDirectory("demo-api-key-e2e")
        private val datasourcesFile: java.nio.file.Path =
            writeFile(
                "bootstrap-datasources.yml",
                """
                datasources:
                  - name: $BOOT_RO
                    display_name: Demo API E2E read-only
                    dialect: H2
                    jdbc_url: jdbc:h2:mem:demo_api_e2e_ro;DB_CLOSE_DELAY=-1
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
                      "id": "$EXAMPLE_TEMPLATE",
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
                      "name": "$EXAMPLE_PIPELINE",
                      "display_name": "Demo API E2E example",
                      "description": "Reads the seeded read-only datasource",
                      "parameters": {},
                      "nodes": [
                        {
                          "id": "read_sample",
                          "description": "DQL read from the seeded read-only datasource",
                          "type": "DQL",
                          "source": "$BOOT_RO",
                          "template": {"id": "$EXAMPLE_TEMPLATE", "version": 1},
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

        private val oidc = OidcDiscoveryStub()

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
                "datapipelines.auth.oidc.providers[0].name" to "google",
                "datapipelines.auth.oidc.providers[0].client-id" to "test-client-id",
                "datapipelines.auth.oidc.providers[0].client-secret" to "test-client-secret",
                "datapipelines.auth.oidc.providers[0].issuer-uri" to oidc.issuer,
                "datapipelines.auth.oidc.providers[0].display-name" to "Test Google",
                "datapipelines.auth.base-url" to "http://localhost:8080",
                "datapipelines.auth.bootstrap-admin-email" to ADMIN_EMAIL,
                "datapipelines.bootstrap.datasources-file" to datasourcesFile.toString(),
                "datapipelines.bootstrap.examples-file" to examplesFile.toString(),
                "datapipelines.endpoints.key-request-budget.window-seconds" to "3600",
                "datapipelines.endpoints.key-request-budget.max-requests" to "3",
            )

        @JvmStatic
        @org.junit.jupiter.api.AfterAll
        fun tearDown() {
            oidc.close()
        }
    }
}
