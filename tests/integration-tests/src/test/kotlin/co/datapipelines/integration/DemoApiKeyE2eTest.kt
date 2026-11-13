package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import co.datapipelines.integration.E2eSession.asSession
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
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
 *
 * #889 — retirement is the API lifecycle's (#786 R1: an API holding release history is never deleted). The examples
 * carry TWO pipelines so a manifest that drops one exercises retirement beside a path that keeps serving; an
 * independent `endpoint` key bound to both demo paths (minted through the real issuance service, not managed by the
 * seeder) proves the retirement itself stops serving, not merely the managed key's revocation. Retirement keeps the
 * row with every version DISCARDED; the assertions read that contract and the real requests' refusals.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@ExtendWith(OutputCaptureExtension::class)
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

    // ------------------------------------------------------------------ boot 4: a manifest drops a released API (#889)

    @Test
    @Order(6)
    fun `a manifest that drops a released demo API retires it through the version verbs - the row stays, nothing serves`(
        output: CapturedOutput,
    ) {
        independentKey = mintIndependentKey()
        // Before: the independent key is valid and the soon-dropped API serves it.
        get(SECOND_PATH, independentKey).statusCode(200)

        boot(key = KEY_TWO, examples = examplesWithoutSecond)

        withClue("the dropped API keeps its row, holds no servable version, and points at none") {
            rowCount(SECOND_PATH) shouldBe 1L
            servableVersions(SECOND_PATH) shouldBe 0L
            currentVersion(SECOND_PATH) shouldBe null
        }
        // The real requests: the independent key and the managed one are both refused — the API itself is retracted.
        get(SECOND_PATH, independentKey).statusCode(503).body("error.code", equalTo(PIPELINE_NOT_RELEASED))
        get(SECOND_PATH, KEY_TWO).statusCode(503)
        // The API the manifest still names is untouched.
        get(DEMO_PATH, KEY_TWO).statusCode(200)
        withClue("one INFO retirement line for the dropped path, no retirement failure") {
            output.out.lines().count { it.contains("event=demo.endpoint_retired path=$SECOND_PATH") } shouldBe 1
            output.out.contains("event=demo.endpoint_retire_failed") shouldBe false
        }
    }

    @Test
    @Order(7)
    fun `the next boot leaves an already-retired API alone - no ERROR line and no write`(output: CapturedOutput) {
        val before = versionRows(SECOND_PATH) to entityUpdatedAt(SECOND_PATH)

        boot(key = KEY_TWO, examples = examplesWithoutSecond)

        (versionRows(SECOND_PATH) to entityUpdatedAt(SECOND_PATH)) shouldBe before
        withClue("the retired path is not mentioned by the seeder, and nothing failed") {
            output.out.lines().count { it.contains("event=demo.endpoint_") && it.contains(SECOND_PATH) } shouldBe 0
            output.out.contains("event=demo.endpoint_retire_failed") shouldBe false
        }
        get(SECOND_PATH, independentKey).statusCode(503)
    }

    // ------------------------------------------------------------------ boot 6: the kill switch

    @Test
    @Order(8)
    fun `a blank key setting is the kill switch - the public API is retracted`(output: CapturedOutput) {
        boot(key = "")

        scalar<Long>(
            "SELECT COUNT(*) FROM api_keys WHERE kind = 'endpoint'" +
                " AND name = 'demo-public-key' AND is_revoked = FALSE",
        ) shouldBe 0L
        // #889 — reconciled with the history-retaining lifecycle (#786 R1): a released demo API is never deleted, so
        // its row stays; retracted means no servable version and no current pointer. Was: the row count is 0.
        withClue("the released demo API is retired, not deleted") {
            rowCount(DEMO_PATH) shouldBe 1L
            servableVersions(DEMO_PATH) shouldBe 0L
            currentVersion(DEMO_PATH) shouldBe null
        }
        withClue("one INFO retirement line, no retirement failure") {
            output.out.lines().count { it.contains("event=demo.endpoint_retired path=$DEMO_PATH") } shouldBe 1
            output.out.contains("event=demo.endpoint_retire_failed") shouldBe false
        }

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
        // The retraction is the API's own, not only the managed key's: a valid key still bound to the path is refused.
        get(DEMO_PATH, independentKey).statusCode(503).body("error.code", equalTo(PIPELINE_NOT_RELEASED))
    }

    @Test
    @Order(9)
    fun `the guarded deletion still refuses the retired API for a workspace admin - the seeder bypassed nothing`() {
        given()
            .port(port)
            .asSession(workspaceAdminSession())
            .queryParam("path", DEMO_PATH)
            .`when`()
            .delete("/api/v1/endpoints")
            .then()
            .statusCode(409)
            .body("error.code", equalTo(VERSION_LAST_RELEASE))

        rowCount(DEMO_PATH) shouldBe 1L
    }

    // ------------------------------------------------------------------ boot 7: the key set again

    @Test
    @Order(10)
    fun `a key set again revives both retired APIs through a new released version`() {
        boot(key = KEY_FOUR)

        listOf(DEMO_PATH, SECOND_PATH).forEach { path ->
            withClue(path) {
                rowCount(path) shouldBe 1L
                scalar<Long>(
                    "SELECT COUNT(*) FROM published_endpoint_versions v JOIN published_endpoints e ON e.id = v.endpoint_id" +
                        " WHERE e.path_pattern = '$path' AND v.status = 'RELEASED'",
                ) shouldBe 1L
                currentVersion(path) shouldBe 2
                get(path, KEY_FOUR).statusCode(200)
            }
        }
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

    /** A real request to the demo path under [key]. */
    private fun get(
        path: String,
        key: String,
    ) = given()
        .port(port)
        .header(API_KEY_HEADER, key)
        .`when`()
        .get("/api$path")
        .then()

    private fun rowCount(path: String): Long =
        scalar(
            "SELECT COUNT(*) FROM published_endpoints pe JOIN workspaces w ON w.id = pe.workspace_id" +
                " WHERE w.name = 'demo' AND pe.path_pattern = '$path'",
        )

    /** The versions that could serve: RELEASED always, a DRAFT in the development posture this suite runs. */
    private fun servableVersions(path: String): Long =
        scalar(
            "SELECT COUNT(*) FROM published_endpoint_versions v JOIN published_endpoints e ON e.id = v.endpoint_id" +
                " WHERE e.path_pattern = '$path' AND v.status IN ('RELEASED', 'DRAFT')",
        )

    private fun currentVersion(path: String): Int? = scalar("SELECT current_version FROM published_endpoints WHERE path_pattern = '$path'")

    /** Every version row of the API at [path], whole, in version order — what a write would change. */
    private fun versionRows(path: String): List<Map<String, Any?>> =
        rows(
            "SELECT v.* FROM published_endpoint_versions v JOIN published_endpoints e ON e.id = v.endpoint_id" +
                " WHERE e.path_pattern = '$path' ORDER BY v.version",
        )

    private fun entityUpdatedAt(path: String): Any? = scalar("SELECT updated_at FROM published_endpoints WHERE path_pattern = '$path'")

    /**
     * A member who is a workspace admin of `demo` and nothing more (not a super admin), seeded once by SQL, and a session
     * for them signed with the suite's own secret — the REST surface's caller, the way a person reaches it.
     */
    private fun workspaceAdminSession(): String {
        connection().use { c ->
            c.createStatement().use { s ->
                s.execute(
                    "INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin)" +
                        " VALUES ('$WS_ADMIN_USER', '$WS_ADMIN_EMAIL', 'Demo API E2E workspace admin', 'test', 'demo-api-e2e-ws-admin'," +
                        " TRUE, FALSE) ON CONFLICT (id) DO NOTHING",
                )
                s.execute(
                    "INSERT INTO workspace_members (workspace_id, user_id, role) VALUES ('$demoId', '$WS_ADMIN_USER', 'workspace_admin')" +
                        " ON CONFLICT DO NOTHING",
                )
            }
        }
        return E2eSession.jwt(jwtSecret, WS_ADMIN_USER, WS_ADMIN_EMAIL, workspace = "demo")
    }

    /** An `endpoint` key the seeder does not manage, bound to both demo paths, minted over the real REST issuance route. */
    private fun mintIndependentKey(): String =
        given()
            .port(port)
            .asSession(workspaceAdminSession())
            .contentType(ContentType.JSON)
            .body("""{"name": "independent-e2e-key", "kind": "endpoint", "bindings": ["$DEMO_PATH", "$SECOND_PATH"]}""")
            .`when`()
            .post("/api/v1/auth/api-keys")
            .then()
            .statusCode(201)
            .extract()
            .jsonPath()
            .getString("data.key")

    /** The non-demo endpoint row, created by nobody the seeder manages. */
    private fun insertForeignEndpoint() {
        val pipelineId = scalar<UUID>("SELECT id FROM pipelines WHERE workspace_id = '$demoId' AND name = '$EXAMPLE_PIPELINE'")
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

    private fun boot(
        key: String,
        examples: java.nio.file.Path = examplesFile,
    ) {
        context?.close()
        val args =
            (
                baseProperties() +
                    mapOf("datapipelines.bootstrap.demo-api-key" to key, "datapipelines.bootstrap.examples-file" to examples.toString())
            ).map { (k, v) -> "--$k=$v" }
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

    /** #889 — the independent bound key's plaintext, minted in the first retirement case. */
    private var independentKey: String = ""

    companion object {
        private const val API_KEY_HEADER = "DP-API-Key"
        private const val DEMO_PATH = "/demo/test/demo-api-e2e-example"
        private const val SECOND_PATH = "/demo/test/demo-api-e2e-second"
        private const val FOREIGN_PATH = "/test/v1/bootstrap-e2e-example"
        private const val KEY_ONE = "dpk_E2EKEYONEAAA.ONEKEYISPUBLICBYDESIGNDEMOONLYE2E222222222222222"
        private const val KEY_TWO = "dpk_E2EKEYTWOBBA.TWOKEYISPUBLICBYDESIGNDEMOONLYE2E222222222222222"
        private const val KEY_FOUR = "dpk_E2EKEYFOURDD.FOURKEYISPUBLICBYDESIGNDEMOONLYE2E22222222222222"

        private const val ADMIN_EMAIL = "demo-api-e2e-admin@example.com"
        private const val WS_ADMIN_EMAIL = "demo-api-e2e-ws-admin@example.com"
        private const val WS_ADMIN_USER = "de000000-0000-0000-0000-0000000088a9"

        /** The catalogued codes the real responses carry (pipeline-contract §13), as literals — this suite is black-box. */
        private const val PIPELINE_NOT_RELEASED = "endpoint.pipeline_not_released"
        private const val VERSION_LAST_RELEASE = "endpoint.version.last_release"
        private const val BOOT_RO = "demo-api-e2e-readonly"
        private const val EXAMPLE_TEMPLATE = "test/demo_api_e2e_example.sql"
        private const val EXAMPLE_PIPELINE = "test/demo_api_e2e_example"
        private const val SECOND_PIPELINE = "test/demo_api_e2e_second"

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

        /** One seeded pipeline as the examples file states it; both read the same seeded datasource and template. */
        private fun pipelineJson(name: String): String =
            """
            {
              "schema_version": 1,
              "name": "$name",
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
            """.trimIndent()

        private fun examplesJson(vararg pipelines: String): String =
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
              "pipelines": [${pipelines.joinToString(",") { pipelineJson(it) }}]
            }
            """.trimIndent()

        private val examplesFile: java.nio.file.Path = writeFile("examples.json", examplesJson(EXAMPLE_PIPELINE, SECOND_PIPELINE))

        /** #889 — the same examples with the second pipeline dropped: the manifest-removal trigger. */
        private val examplesWithoutSecond: java.nio.file.Path = writeFile("examples-without-second.json", examplesJson(EXAMPLE_PIPELINE))

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
                "datapipelines.redis.password" to "",
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
