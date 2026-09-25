package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import io.kotest.matchers.shouldBe
import io.restassured.RestAssured.given
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
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
 * Keys v2 A16/B3 over the live wire: a published endpoint's API key SERVES, then pages its own
 * run under the business path it is bound to — `GET /api/<cat>/v<n>/<path>/executions/{id}`
 * and `…/result`, delegated from the serve catch-all (`PublishedExecutionPagingService`).
 *
 * The four facts the brief names, each on the real HTTP surface with everything else shipped:
 * - the key's own run: metadata and result answer 200 under the business path;
 * - another key's run is the plain `404 result.execution_not_found` (the URL is not a
 *   capability — [Auth §11A.1](../../../../docs/auth.md));
 * - a browser SESSION is refused exactly as on the serve route — `401 auth.session.required`,
 *   a machine surface;
 * - the framework's `/api/v1/executions/{id}`[`/result`] refuse EVERY key by kind
 *   (`403 endpoint.key_kind_refused`, `mcp_key_off_surface`) — session-only since keys v2.
 *
 * The boot is the demo-family harness (`DemoApiKeyE2eTest`'s shape — real Flyway, real seeder,
 * real filter chain), with the request budget set huge so a paging read can never 429: this
 * suite's subject is authorisation, and a budget forced by count belongs to that suite alone.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class KeysV2E2eTest {
    private var context: ConfigurableApplicationContext? = null

    private var port: Int = 0

    private var ownRunId: String = ""

    @BeforeAll
    fun boot() {
        val args =
            (baseProperties() + mapOf("datapipelines.bootstrap.demo-api-key" to KEY_ONE))
                .map { (k, v) -> "--$k=$v" }
                .toTypedArray()
        context =
            SpringApplicationBuilder(DatapipelinesApplication::class.java)
                .web(WebApplicationType.SERVLET)
                .run(*args)
        port = checkNotNull(context!!.environment.getProperty("local.server.port", Int::class.java))
        seedSecondKey()
    }

    @AfterAll
    fun close() {
        context?.close()
    }

    @Test
    @Order(1)
    fun `the key serves and pages its own run under the business path`() {
        val serveBody =
            given()
                .port(port)
                .header(API_KEY_HEADER, KEY_ONE)
                .`when`()
                .get("/api$DEMO_PATH")
                .then()
                .extract()
                .asString()
        println("event=keys-v2-e2e serve body: ${serveBody.take(600)}")
        check(serveBody.contains("\"row_count\"")) { "no row_count in the serve response: ${serveBody.take(400)}" }
        val executionId =
            io.restassured.path.json.JsonPath(serveBody).getString("execution_id")
                ?: error("the serve response carried no execution_id: ${serveBody.take(400)}")
        ownRunId = executionId

        // The metadata read, under the business path the key is bound to.
        given()
            .port(port)
            .header(API_KEY_HEADER, KEY_ONE)
            .`when`()
            .get("/api$DEMO_PATH/executions/$executionId")
            .then()
            .statusCode(200)
            .body("data.execution_id", equalTo(executionId))
            .body("data.executed_by_key_kind", equalTo("endpoint"))

        // The result cursor, same door: `format=json` pages the envelope.
        given()
            .port(port)
            .header(API_KEY_HEADER, KEY_ONE)
            .`when`()
            .get("/api$DEMO_PATH/executions/$executionId/result?offset=0&limit=10&format=json")
            .then()
            .statusCode(200)
            .body("data.rows.size()", equalTo(1))
    }

    @Test
    @Order(2)
    fun `another key's run is not found - the URL is not a capability`() {
        // The second key serves its own run on the shared path.
        val secondServe =
            given()
                .port(port)
                .header(API_KEY_HEADER, KEY_TWO)
                .`when`()
                .get("/api$DEMO_PATH")
        val secondRun = secondServe.jsonPath().getString("execution_id")
        check(secondServe.statusCode() == 200) { "second key serve: ${secondServe.asString().take(300)}" }
        check(secondRun != null)

        println("event=keys-v2-e2e second serve: ${secondServe.asString().take(300)}")
        // The first key's read of it: 404, never 403 — the run's existence is not an oracle.
        given()
            .port(port)
            .header(API_KEY_HEADER, KEY_ONE)
            .`when`()
            .get("/api$DEMO_PATH/executions/$secondRun")
            .then()
            .statusCode(404)
            .body("error.code", equalTo("result.execution_not_found"))
        // …and its own run still answers.
        given()
            .port(port)
            .header(API_KEY_HEADER, KEY_TWO)
            .`when`()
            .get("/api$DEMO_PATH/executions/$secondRun")
            .then()
            .statusCode(200)
    }

    @Test
    @Order(3)
    fun `a session is refused on the business path - a machine surface`() {
        val adminId = adminUserId()
        given()
            .port(port)
            .cookie("dp_session", sessionJwt(adminId))
            .`when`()
            .get("/api$DEMO_PATH/executions/$ownRunId")
            .then()
            .statusCode(403)
            .body("error.code", equalTo("auth.session.required"))
    }

    @Test
    @Order(4)
    fun `the framework reads refuse every key by kind`() {
        // Both keys are `endpoint` keys — the framework reads are off EVERY key's surface now
        // (keys v2 A16); the reason token is the kind table's per-kind entry.
        listOf(KEY_ONE, KEY_TWO).forEach { key ->
            given()
                .port(port)
                .header(API_KEY_HEADER, key)
                .`when`()
                .get("/api/v1/executions/$ownRunId")
                .then()
                .statusCode(403)
                .body("error.code", equalTo("endpoint.key_kind_refused"))
                .body("error.details.reason", equalTo("endpoint_key_off_surface"))
            given()
                .port(port)
                .header(API_KEY_HEADER, key)
                .`when`()
                .get("/api/v1/executions/$ownRunId/result")
                .then()
                .statusCode(403)
                .body("error.code", equalTo("endpoint.key_kind_refused"))
        }
    }

    // ------------------------------------------------------------------ the second key

    /**
     * A second `endpoint` key, bound to the same demo prefix — seeded by SQL (the fixture
     * writes the row, as every suite does; the creation path's own wire behaviour is
     * `ApiKeyMintingTest`'s subject). Its identity is built exactly as V34/ApiKeyService build
     * one (keys v2 A13).
     */
    private fun seedSecondKey() {
        val demoWorkspace = demoWorkspaceId()
        val creator =
            scalar<UUID>("SELECT id FROM users WHERE email = '$ADMIN_EMAIL'").toString()
        connection().use { c ->
            c.createStatement().use { s ->
                s.execute(
                    "INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin, kind) VALUES " +
                        "('$KEY_TWO_IDENTITY', '${KEY_TWO.lowercase()}@keys.invalid', 'keys-v2 second', 'key', " +
                        "'$KEY_TWO', TRUE, FALSE, 'service')",
                )
                val keyTwoId = KEY_TWO.substringBefore('.')
                s.execute(
                    "INSERT INTO api_keys (id, user_id, created_by, name, key_hash, workspace_id, kind, role)" +
                        " VALUES ('$keyTwoId', '$KEY_TWO_IDENTITY', '$creator', 'keys-v2-second', " +
                        " '${E2eAuth.argon2Hash(KEY_TWO)}', '$demoWorkspace', 'endpoint', 'api_caller')",
                )
                s.execute(
                    "INSERT INTO endpoint_key_bindings (path_prefix, api_key_id, workspace_id, created_by)" +
                        " VALUES ('$DEMO_PATH', '$keyTwoId', '$demoWorkspace', '$creator')",
                )
                val state =
                    rows("SELECT id, is_revoked, expires_at, kind, role FROM api_keys WHERE id = '${KEY_TWO.substringBefore('.')}'")
                println("event=keys-v2-e2e second key row: $state")
                val actor = rows("SELECT id, is_active, kind FROM users WHERE id = '$KEY_TWO_IDENTITY'")
                println("event=keys-v2-e2e second identity row: $actor")
            }
        }
    }

    private fun adminUserId(): String = scalar("SELECT id::text FROM users WHERE email = '$ADMIN_EMAIL'")

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

    private fun <T> scalar(sql: String): T =
        connection().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { rs ->
                    rs.next()
                    @Suppress("UNCHECKED_CAST")
                    rs.getObject(1) as T
                }
            }
        }

    /** A signed session for the bootstrap admin — the principal shape the refusal is ABOUT. */
    private fun sessionJwt(userId: String): String {
        val now = System.currentTimeMillis() / 1000
        fun b64(value: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray())
        val header = b64("""{"alg":"HS256","typ":"JWT"}""")
        val payload =
            b64(
                """{"sub":"$userId","email":"$ADMIN_EMAIL","name":"E2E","iss":"datapipelines",""" +
                    """"iat":$now,"exp":${now + 3600},"active_workspace":"demo"}""",
            )
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(Base64.getDecoder().decode(jwtSecret), "HmacSHA256"))
        val signature = Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal("$header.$payload".toByteArray()))
        return "$header.$payload.$signature"
    }

    companion object {
        private const val API_KEY_HEADER = "DP-API-Key"
        private const val DEMO_PATH = "/demo/test/keys-v2-e2e-example"
        private const val KEY_ONE = "dpk_E2EKEYV2ONEA.ONEKEYISPUBLICBYDESIGNDEMOONLYE2E222222222222222"
        private const val KEY_TWO = "dpk_E2EKEYV2TWOB.TWOKEYISPUBLICBYDESIGNDEMOONLYE2E222222222222222"
        private const val KEY_TWO_IDENTITY = "5e600000-0000-0000-0000-000000000001"

        private const val ADMIN_EMAIL = "keys-v2-e2e-admin@example.com"
        private const val BOOT_RO = "keys-v2-e2e-readonly"
        private const val EXAMPLE_TEMPLATE = "test/keys_v2_e2e_example.sql"
        private const val EXAMPLE_PIPELINE = "test/keys_v2_e2e_example"

        private val random = SecureRandom()
        private val jwtSecret = randomSecret()
        private val encryptionKey = randomSecret()

        private val fixtures: java.nio.file.Path = Files.createTempDirectory("keys-v2-e2e")
        private val datasourcesFile: java.nio.file.Path =
            writeFile(
                "bootstrap-datasources.yml",
                """
                datasources:
                  - name: $BOOT_RO
                    display_name: Keys v2 E2E read-only
                    dialect: H2
                    jdbc_url: jdbc:h2:mem:keys_v2_e2e_ro;DB_CLOSE_DELAY=-1
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
                      "display_name": "Keys v2 E2E example",
                      "description": "Seeded into the demo workspace",
                      "imports": [],
                      "body": "SELECT 1 AS n"
                    }
                  ],
                  "pipelines": [
                    {
                      "schema_version": 1,
                      "name": "$EXAMPLE_PIPELINE",
                      "display_name": "Keys v2 E2E example",
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
                // The budget is NOT this suite's subject — set it out of reach so a paging
                // read can never 429 (the count-forced 429 is DemoApiKeyE2eTest's).
                "datapipelines.endpoints.key-request-budget.window-seconds" to "3600",
                "datapipelines.endpoints.key-request-budget.max-requests" to "1000000",
            )
    }
}
