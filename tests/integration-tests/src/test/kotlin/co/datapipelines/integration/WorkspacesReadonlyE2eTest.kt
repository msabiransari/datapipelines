package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.Response
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertTimeoutPreemptively
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.SecureRandom
import java.sql.DriverManager
import java.time.Duration
import java.util.Base64
import java.util.UUID

/**
 * Workspaces readonly enforcement E2E (design 2026-08-16-workspaces §6, D6/D10; slices 017/019
 * landed the V4 columns, this slice the enforcement).
 *
 * Three proofs, at the level each layer actually runs:
 *
 * 1. **Save-time, all three write shapes** — a DML `source`, a DDL `source`, and an
 *    `output.target: "datasource"` naming a readonly datasource each fail pipeline creation
 *    with HTTP 400 `pipeline.validation.datasource_readonly` and the details triple
 *    (node / datasource / shape); a DQL-only pipeline against the SAME datasource saves clean.
 * 2. **The D10 flip, at the row level** — a write-shaped pipeline saves clean against a
 *    writable datasource, `is_readonly` flips in the metadata DB (no registry save — the
 *    cache-invalidating path — so only the executor's LIVE read can see it), and the next
 *    execution fails the node with `pipeline.node.datasource_readonly` and the execution row
 *    reaches its FAILED terminal state.
 * 3. **The composed-child variant** — the write shape sits in a child pipeline a parent
 *    PIPELINE node runs; the CHILD's node fails on the same code and the child row reaches
 *    FAILED, because a child node executes through the same node runner in its own execution.
 *
 * Infrastructure: Postgres metadata DB + Redis (result store), the app in this JVM, and an
 * in-memory H2 as the "external" source database — the same shape PipelineCompositionE2eTest
 * uses. Each test registers its OWN datasource name so a flip in one test cannot leak into
 * another through the shared app context.
 */
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
class WorkspacesReadonlyE2eTest {
    @LocalServerPort
    private var port: Int = 0

    private val mapper = ObjectMapper()

    // ------------------------------------------------------------- Test 1: save-time shapes

    @Test
    fun `all three write shapes against a readonly datasource are refused at save - DQL saves clean`() {
        seedAuthRows()
        registerDatasource(SAVE_DS, SAVE_H2_URL)
        createTemplate("ro_e2e_ins1.sql", "INSERT INTO ro_t (n) VALUES (1)")
        createTemplate("ro_e2e_ddl1.sql", "CREATE TABLE ro_made (n INT)")
        createTemplate("ro_e2e_sel1.sql", "SELECT 1 AS n")
        flipReadonly(SAVE_DS, readonly = true)

        // Shape 1: DML node sourcing the readonly datasource.
        postRefused("ro_save_dml", listOf(dmlNode("insert_rows", SAVE_DS, "ro_e2e_ins1.sql")))
        // Shape 2: DDL node sourcing it.
        postRefused("ro_save_ddl", listOf(ddlNode("make_table", SAVE_DS, "ro_e2e_ddl1.sql")))
        // Shape 3: output.target "datasource" naming it — the write-back half, on a DQL node.
        postRefused(
            "ro_save_output",
            listOf(
                mapOf(
                    "id" to "fetch_and_write",
                    "description" to "DQL read from tempdb, written back to a readonly target",
                    "type" to "DQL",
                    "source" to "tempdb",
                    "template" to mapOf("id" to "ro_e2e_sel1.sql", "version" to 1),
                    "output" to
                        mapOf(
                            "target" to "datasource",
                            "datasource" to SAVE_DS,
                            "table" to "ro_target",
                            "mode" to "append",
                        ),
                    "depends_on" to emptyList<String>(),
                ),
            ),
        )

        // The details triple travels on every refusal (asserted once, on the DML shape).
        assertDetailsTriple()

        // The negative: DQL reads from the SAME readonly datasource save clean.
        createPipeline(
            "ro_save_dql_ok",
            listOf(
                mapOf(
                    "id" to "read_only_read",
                    "description" to "DQL read from the readonly datasource — legal",
                    "type" to "DQL",
                    "source" to SAVE_DS,
                    "template" to mapOf("id" to "ro_e2e_sel1.sql", "version" to 1),
                    "output" to mapOf("target" to "caller"),
                    "depends_on" to emptyList<String>(),
                ),
            ),
        ) shouldNotBe null
    }

    /** Posts [name] and asserts the 400 + `pipeline.validation.datasource_readonly` refusal. */
    private fun postRefused(
        name: String,
        nodes: List<Map<String, Any?>>,
    ) {
        postPipeline(name, nodes)
            .then()
            .statusCode(400)
            .body("error.code", org.hamcrest.Matchers.equalTo(READONLY_VALIDATION_CODE))
    }

    /** The details triple: node id + datasource name + which of the three shapes fired. */
    private fun assertDetailsTriple() {
        val failure =
            given()
                .port(port)
                .contentType(ContentType.JSON)
                .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
                .body(
                    mapper.writeValueAsString(
                        pipelineBody("ro_save_dml_details", listOf(dmlNode("insert_rows", SAVE_DS, "ro_e2e_ins1.sql"))),
                    ),
                ).`when`()
                .post("/api/v1/pipelines")
                .then()
                .statusCode(400)
                .extract()
        failure.jsonPath().getString("error.details.failures[0].details.node") shouldBe "insert_rows"
        failure.jsonPath().getString("error.details.failures[0].details.datasource") shouldBe SAVE_DS
        failure.jsonPath().getString("error.details.failures[0].details.shape") shouldBe "dml_source"
    }

    // ---------------------------------------------------------- Test 2: the D10 flip, row level

    @Test
    fun `a readonly flip after save fails the write node at execution with the terminal state`() {
        seedAuthRows()
        seedH2Table(FLIP_H2_URL, "flip_t")
        registerDatasource(FLIP_DS, FLIP_H2_URL)
        createTemplate(
            RO_H2_INSERT_TEMPLATE,
            "INSERT INTO flip_t (n) VALUES (1)",
        )

        // Saved clean: the datasource is writable at save time.
        val pipelineId =
            createPipeline(
                "ro_flip_dml",
                listOf(dmlNode("flip_insert", FLIP_DS, RO_H2_INSERT_TEMPLATE)),
            )

        // The flip lands at the ROW level — no registry save, so no cache invalidation: within
        // the 60s TTL only the executor's LIVE read can see it. That is the D10 window.
        flipReadonly(FLIP_DS, readonly = true)

        val events =
            assertTimeoutPreemptively(Duration.ofMinutes(SSE_BUDGET_MINUTES)) {
                consumeExecutionStream(pipelineId, UUID.randomUUID().toString())
            }

        events.map { it.first } shouldContainExactly
            listOf("execution_started", "node_started", "node_failed", "pipeline_failed")
        val nodeFailed = events.single { it.first == "node_failed" }.second
        nodeFailed["node_id"].asText() shouldBe "flip_insert"
        nodeFailed["error"]["code"].asText() shouldBe READONLY_NODE_CODE

        // The execution row reaches its terminal state with the same code (the 016 rule: the
        // streamed view and the persisted row must agree; the code lives in error_json).
        val executionId = events.first().second["execution_id"].asText()
        queryExecutions(
            "SELECT status, error_json->>'code' FROM pipeline_executions WHERE execution_id = '$executionId'",
        ) { rs -> rs.getString(1) to rs.getString(2) }.single() shouldBe ("FAILED" to READONLY_NODE_CODE)
    }

    // ------------------------------------------------------ Test 3: composed child, same backstop

    @Test
    fun `a composed child carrying the write shape fails on the same code in its own execution`() {
        seedAuthRows()
        seedH2Table(CHILD_H2_URL, "child_t")
        registerDatasource(CHILD_DS, CHILD_H2_URL)
        createTemplate(
            RO_CHILD_INSERT_TEMPLATE,
            "INSERT INTO child_t (n) VALUES (1)",
        )

        // The child (saved clean against a writable datasource) carries the DML write shape.
        createPipeline(
            "ro_child_pipeline",
            listOf(dmlNode("child_insert", CHILD_DS, RO_CHILD_INSERT_TEMPLATE)),
        )
        // The parent runs it through a PIPELINE node.
        val parentId =
            createPipeline(
                "ro_parent_pipeline",
                listOf(
                    mapOf(
                        "id" to "run_child",
                        "description" to "Runs the child whose node carries the write shape",
                        "type" to "PIPELINE",
                        "pipeline" to mapOf("name" to "ro_child_pipeline", "version" to 1),
                        "depends_on" to emptyList<String>(),
                    ),
                ),
            )

        flipReadonly(CHILD_DS, readonly = true)

        val events =
            assertTimeoutPreemptively(Duration.ofMinutes(SSE_BUDGET_MINUTES)) {
                consumeExecutionStream(parentId, UUID.randomUUID().toString())
            }

        // The parent's PIPELINE node fails as child_execution_failed (§13.4) — the child's own
        // failure is the readonly backstop.
        events.last().first shouldBe "pipeline_failed"
        val parentExecutionId = events.first().second["execution_id"].asText()
        val parentFailedCode = events.last().second["error"]["code"].asText()
        parentFailedCode shouldBe "pipeline.node.child_execution_failed"

        // The CHILD execution row is where the readonly refusal actually lives: FAILED, with
        // the backstop code, in its own execution.
        val child =
            queryExecutions(
                "SELECT execution_id::text, status, error_json->>'code' FROM pipeline_executions " +
                    "WHERE parent_execution_id = '$parentExecutionId'",
            ) { rs -> Triple(rs.getString(1), rs.getString(2), rs.getString(3)) }.single()
        child.second shouldBe "FAILED"
        child.third shouldBe READONLY_NODE_CODE
        queryExecutions(
            "SELECT status FROM pipeline_executions WHERE execution_id = '$parentExecutionId'",
        ) { it.getString(1) }.single() shouldBe "FAILED"
    }

    // ---------------------------------------------------------------- helpers

    private fun dmlNode(
        id: String,
        datasource: String,
        templateId: String = RO_H2_INSERT_TEMPLATE,
    ): Map<String, Any?> =
        mapOf(
            "id" to id,
            "description" to "DML insert against $datasource",
            "type" to "DML",
            "source" to datasource,
            "template" to mapOf("id" to templateId, "version" to 1),
            "depends_on" to emptyList<String>(),
        )

    private fun ddlNode(
        id: String,
        datasource: String,
        templateId: String,
    ): Map<String, Any?> =
        mapOf(
            "id" to id,
            "description" to "DDL against $datasource",
            "type" to "DDL",
            "source" to datasource,
            "template" to mapOf("id" to templateId, "version" to 1),
            "depends_on" to emptyList<String>(),
        )

    private fun pipelineBody(
        name: String,
        nodes: List<Map<String, Any?>>,
    ): Map<String, Any?> =
        mapOf(
            "schema_version" to 1,
            "name" to name,
            "display_name" to name,
            "description" to "Readonly E2E — $name",
            "parameters" to emptyMap<String, String>(),
            "nodes" to nodes,
        )

    private fun postPipeline(
        name: String,
        nodes: List<Map<String, Any?>>,
    ): Response =
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
            .body(mapper.writeValueAsString(pipelineBody(name, nodes)))
            .`when`()
            .post("/api/v1/pipelines")

    private fun createPipeline(
        name: String,
        nodes: List<Map<String, Any?>>,
    ): String {
        val response = postPipeline(name, nodes)
        if (response.statusCode() != 201) {
            throw AssertionError(
                "Pipeline '$name' creation failed (status=${response.statusCode()}): ${response.body().asString()}",
            )
        }
        return response.jsonPath().getString("data.id")
    }

    private fun createTemplate(
        id: String,
        body: String,
    ) {
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
            .body(
                """
                {"id": "$id", "dialect": "H2", "display_name": "Readonly E2E $id",
                 "description": "Readonly E2E template", "imports": [],
                 "body": ${mapper.writeValueAsString(body)}}
                """.trimIndent(),
            ).`when`()
            .post("/api/v1/templates")
            .then()
            .statusCode(201)
    }

    /** Registers the datasource if absent — the flag itself arrives only via [flipReadonly]. */
    private fun registerDatasource(
        name: String,
        jdbcUrl: String,
    ) {
        val existing =
            given()
                .port(port)
                .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
                .`when`()
                .get("/api/v1/datasources/$name")
                .then()
                .extract()
        if (existing.statusCode() == 200) return
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
            .body(
                """
                {"name": "$name", "display_name": "Readonly E2E $name", "dialect": "H2",
                 "jdbc_url": "$jdbcUrl", "username": "$H2_USER", "password": "$H2_PASSWORD"}
                """.trimIndent(),
            ).`when`()
            .post("/api/v1/datasources")
            .then()
            .statusCode(201)
    }

    /**
     * The D10 mechanism under test: the flag flips at the ROW level, in the metadata DB — not
     * through the registry's save boundary — so the §6.3 metadata cache is NOT invalidated and
     * the flip is visible only to the executor's live read within the TTL.
     */
    private fun flipReadonly(
        name: String,
        readonly: Boolean,
    ) {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("UPDATE datasources SET is_readonly = $readonly WHERE name = '$name'")
            }
        }
    }

    private fun seedH2Table(
        jdbcUrl: String,
        table: String,
    ) {
        DriverManager.getConnection(jdbcUrl, H2_USER, H2_PASSWORD).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE IF NOT EXISTS $table (n INT)")
            }
        }
    }

    private fun consumeExecutionStream(
        pipelineId: String,
        correlationId: String,
    ): List<Pair<String, JsonNode>> {
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port/api/v1/pipelines/$pipelineId/execute"))
                .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
                .header("DP-Correlation-Id", correlationId)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString("""{"parameters": {}}"""))
                .build()
        val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
        response.statusCode() shouldBe 200

        return E2eSse.parseEvents(response.body(), mapper)
    }

    private fun <T> queryExecutions(
        sql: String,
        read: (java.sql.ResultSet) -> T,
    ): List<T> =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { rs ->
                    generateSequence { if (rs.next()) read(rs) else null }.toList()
                }
            }
        }

    private fun seedAuthRows() {
        synchronized(authLock) {
            if (authSeeded) return
            authSeeded = true
        }
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin)
                    VALUES ('$ADMIN_USER_ID', 'e2e-readonly@datapipelines.test', 'E2E Readonly', 'test', 'e2e-ro-sub', TRUE, TRUE)
                    ON CONFLICT (id) DO NOTHING
                    """.trimIndent(),
                )
            }
            val insertSql =
                "INSERT INTO api_keys (id, user_id, name, key_hash, scopes, workspace_id)" +
                    " VALUES (?, ?, ?, ?, ?, 'defa0000-0000-0000-0000-000000000001') ON CONFLICT (id) DO NOTHING"
            connection.prepareStatement(insertSql).use { ps ->
                ps.setString(1, ADMIN_KEY.id)
                ps.setObject(2, UUID.fromString(ADMIN_USER_ID))
                ps.setString(3, ADMIN_KEY.name)
                ps.setString(4, ADMIN_KEY.hash)
                ps.setArray(5, connection.createArrayOf("text", ADMIN_KEY.scopes))
                ps.executeUpdate()
            }
        }
    }

    companion object {
        private const val SECRET_BYTES = 32
        private const val SSE_BUDGET_MINUTES = 2L
        private const val API_KEY_HEADER = "DP-API-Key"

        private const val READONLY_VALIDATION_CODE = "pipeline.validation.datasource_readonly"
        private const val READONLY_NODE_CODE = "pipeline.node.datasource_readonly"

        // One datasource (and H2 database) per test — a flip in one must not leak into another.
        private const val SAVE_DS = "h2-ro-save"
        private const val SAVE_H2_URL = "jdbc:h2:mem:ro_save;DB_CLOSE_DELAY=-1"
        private const val FLIP_DS = "h2-ro-flip"
        private const val FLIP_H2_URL = "jdbc:h2:mem:ro_flip;DB_CLOSE_DELAY=-1"
        private const val CHILD_DS = "h2-ro-child"
        private const val CHILD_H2_URL = "jdbc:h2:mem:ro_child;DB_CLOSE_DELAY=-1"

        private const val H2_USER = "sa"
        private const val H2_PASSWORD = "sa"

        private const val RO_H2_INSERT_TEMPLATE = "ro_e2e_insert.sql"
        private const val RO_CHILD_INSERT_TEMPLATE = "ro_e2e_child_insert.sql"

        private val ADMIN_USER_ID: String = UUID.randomUUID().toString()

        @Volatile
        private var authSeeded = false
        private val authLock = Any()

        private val random = SecureRandom()

        private val ADMIN_KEY = E2eAuth.generateKey("e2e-readonly-key", arrayOf("admin"))

        private fun randomSecret(): String =
            Base64
                .getEncoder()
                .encodeToString(ByteArray(SECRET_BYTES).also { random.nextBytes(it) })

        /** The module's shared containers — started on first touch, migrated by the first context's Flyway. */
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

            registry.add("spring.data.redis.host") { redis.host }
            registry.add("spring.data.redis.port") { SharedE2e.redisPort }
            registry.add("spring.data.redis.password") { "" }
            registry.add("datapipelines.redis.host") { redis.host }
            registry.add("datapipelines.redis.port") { SharedE2e.redisPort }

            registry.add("datapipelines.jwt.secret") { randomSecret() }
            registry.add("datapipelines.db.encryption-key") { randomSecret() }

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
        }
    }
}
