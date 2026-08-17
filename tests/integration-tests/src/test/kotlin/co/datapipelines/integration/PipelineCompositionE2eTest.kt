package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import de.mkammerer.argon2.Argon2Factory
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.Response
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.assertTimeoutPreemptively
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
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
 * Pipeline composition E2E (design 2026-08-13-pipeline-node-type, §8): a `PIPELINE` node
 * executes a version-pinned child pipeline as a real, separate execution and consumes its
 * caller result directly (delivery mode `direct`, internal-only).
 *
 * Infrastructure: Postgres for the metadata DB (Flyway V1–V3 on startup, V3 carrying the
 * lineage columns) and Redis for the result store. The child's datasource is an **in-memory
 * H2** database — the app runs in this JVM, so the test seeds it over the same JDBC URL the
 * registered datasource uses. `DB_CLOSE_DELAY=-1` keeps the database alive across the app's
 * pooled connections; it is not a §5.6 refused key.
 *
 * Scenario 1 asserts the full contract: the parent's result rows equal the child's data,
 * exactly two execution rows exist for the family, the child row carries
 * `parent_execution_id`/`root_execution_id` = the parent's id and `triggered_via = 'PIPELINE'`,
 * and the parent's node stats (durable `node_stats_json` and the `node_completed` SSE event)
 * carry the child execution id. Scenario 2 runs a grandchild depth-3 chain; scenario 3 proves
 * the static save-time guard refuses a depth-6 chain with
 * `pipeline.validation.composition_too_deep` (max-composition-depth default 5).
 */
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class PipelineCompositionE2eTest {
    @LocalServerPort
    private var port: Int = 0

    private val mapper = ObjectMapper()

    @Test
    @Order(1)
    fun `parent with PIPELINE node runs child as a linked execution and returns its rows`() {
        seedAuthRows()
        seedH2()
        registerH2Datasource()
        createTemplate(
            "comp_users.sql",
            "H2",
            "Composition Users",
            "SELECT id, email FROM comp_users ORDER BY id",
        )

        // Pipeline A (child): one DQL caller node over the H2 datasource.
        createPipeline(
            "comp_leaf",
            "Composition Leaf",
            listOf(
                mapOf(
                    "id" to "fetch_users",
                    "description" to "Fetch users from H2",
                    "type" to "DQL",
                    "source" to H2_DATASOURCE,
                    "template" to mapOf("id" to "comp_users.sql", "version" to 1),
                    "output" to mapOf("target" to "caller"),
                    "depends_on" to emptyList<String>(),
                ),
            ),
        )

        // Pipeline B (parent): one PIPELINE node pinning A by name+version, result to caller.
        val parentId =
            createPipeline(
                "comp_parent",
                "Composition Parent",
                listOf(pipelineNode("run_leaf", "comp_leaf", 1)),
            )

        val correlationId = UUID.randomUUID().toString()
        val events =
            assertTimeoutPreemptively(Duration.ofMinutes(SSE_BUDGET_MINUTES)) {
                consumeExecutionStream(parentId, correlationId)
            }
        events.map { it.first } shouldContainExactly
            listOf("execution_started", "node_started", "node_completed", "pipeline_completed", "data_ready")
        val parentExecutionId = events.last().second["execution_id"].asText()

        // The parent's result rows equal the child's data (§4.2 direct delivery, caller target).
        assertResultRows(parentExecutionId)

        // Exactly TWO execution rows in the family, with the lineage links of §5.
        val childExecutionId = assertFamilyOfTwo(parentExecutionId)

        // The parent's node stats carry the child execution id — both the durable
        // node_stats_json and the SSE node_completed event (design §5/§7).
        assertNodeStatsCarryChild(parentExecutionId, childExecutionId)
        // Per-execution stats separation (design §8): the child's own stats record its DQL
        // node's rows, even though its result streamed `direct` to the parent.
        assertChildNodeStats(childExecutionId)
        val nodeCompleted = events.single { it.first == "node_completed" }.second
        nodeCompleted["node_id"].asText() shouldBe "run_leaf"
        nodeCompleted["child_execution_id"].asText() shouldBe childExecutionId
    }

    @Test
    @Order(2)
    fun `grandchild depth-3 chain succeeds with per-generation lineage`() {
        // comp_mid → comp_leaf (depth 2), comp_root → comp_mid (depth 3).
        createPipeline(
            "comp_mid",
            "Composition Mid",
            listOf(pipelineNode("run_leaf", "comp_leaf", 1)),
        )
        val rootId =
            createPipeline(
                "comp_root",
                "Composition Root",
                listOf(pipelineNode("run_mid", "comp_mid", 1)),
            )

        val events =
            assertTimeoutPreemptively(Duration.ofMinutes(SSE_BUDGET_MINUTES)) {
                consumeExecutionStream(rootId, UUID.randomUUID().toString())
            }
        events.last().first shouldBe "data_ready"
        val rootExecutionId = events.last().second["execution_id"].asText()

        assertResultRows(rootExecutionId)

        // Three rows: root, mid (child of root), leaf (child of mid); both children PIPELINE.
        val (familySize, children) = loadFamily(rootExecutionId)
        familySize shouldBe 3
        children.size shouldBe 2
        children.values.forEach { (parent, triggeredVia, status) ->
            parent shouldNotBe null
            triggeredVia shouldBe "PIPELINE"
            status shouldBe "SUCCESS"
        }
        // One generation each: mid's parent is the root, leaf's parent is mid's execution.
        val midExecutionId = children.entries.single { it.value.parent == rootExecutionId }.key
        val leafParent =
            children.entries
                .single { it.key != midExecutionId }
                .value
                .parent
        leafParent shouldBe midExecutionId
    }

    /** The execution family under a root: its size, and each non-root row's lineage triple. */
    private data class ChildRow(
        val parent: String?,
        val triggeredVia: String,
        val status: String,
    )

    private fun loadFamily(rootExecutionId: String): Pair<Int, Map<String, ChildRow>> {
        val familySize =
            queryExecutions(
                "SELECT count(*) FROM pipeline_executions WHERE root_execution_id = '$rootExecutionId'",
            ) { it.getInt(1) }
                .single()
        val children =
            queryExecutions(
                "SELECT execution_id::text, parent_execution_id::text, triggered_via, status " +
                    "FROM pipeline_executions WHERE root_execution_id = '$rootExecutionId' " +
                    "AND execution_id != '$rootExecutionId'",
            ) { rs ->
                rs.getString(1) to ChildRow(rs.getString(2), rs.getString(3), rs.getString(4))
            }.toMap()
        return familySize to children
    }

    /** Runs a metadata-DB query against the Testcontainers Postgres, collecting every row. */
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

    @Test
    @Order(3)
    fun `a depth-6 chain is refused at save with composition_too_deep`() {
        // comp_root is depth 3; comp_d4 → depth 4, comp_d5 → depth 5 (the configured max),
        // comp_d6 → depth 6 must fail validation at save (§12.9, static reference-tree walk).
        createPipeline("comp_d4", "Composition Depth 4", listOf(pipelineNode("run_root", "comp_root", 1)))
        createPipeline("comp_d5", "Composition Depth 5", listOf(pipelineNode("run_d4", "comp_d4", 1)))

        postPipeline("comp_d6", "Composition Depth 6", listOf(pipelineNode("run_d5", "comp_d5", 1)))
            .then()
            .statusCode(400)
            .body("error.code", org.hamcrest.Matchers.equalTo("pipeline.validation.composition_too_deep"))
    }

    // ------------------------------------------------------------ helpers

    private fun pipelineNode(
        id: String,
        childName: String,
        childVersion: Int,
    ): Map<String, Any?> =
        mapOf(
            "id" to id,
            "description" to "Invoke $childName v$childVersion",
            "type" to "PIPELINE",
            "pipeline" to mapOf("name" to childName, "version" to childVersion),
            "output" to mapOf("target" to "caller"),
            "depends_on" to emptyList<String>(),
        )

    /** §7.2 — the result cursor returns exactly the seeded H2 rows, in `id` order. */
    private fun assertResultRows(executionId: String) {
        val resultResponse =
            given()
                .port(port)
                .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
                .`when`()
                .get("/api/v1/executions/$executionId/result")
                .then()
                .statusCode(200)
                .extract()
        resultResponse.jsonPath().getLong("data.total_rows") shouldBe SEED_EMAILS.size.toLong()
        val rows: List<List<Any?>> = resultResponse.jsonPath().get("data.rows")
        rows.map { (it[0] as Number).toInt() } shouldContainExactly listOf(1, 2)
        rows.map { it[1] } shouldContainExactly SEED_EMAILS
    }

    /**
     * §5 — the family of a root execution: two rows total; the child row links
     * `parent_execution_id` and `root_execution_id` to the parent and records
     * `triggered_via = 'PIPELINE'`; the root's own root is itself with no parent.
     * Returns the child execution id.
     */
    private fun assertFamilyOfTwo(parentExecutionId: String): String {
        var childExecutionId = ""
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                statement
                    .executeQuery(
                        "SELECT count(*) FROM pipeline_executions WHERE root_execution_id = '$parentExecutionId'",
                    ).use { rs ->
                        rs.next()
                        rs.getInt(1) shouldBe 2
                    }
                statement
                    .executeQuery(
                        "SELECT root_execution_id::text, parent_execution_id::text FROM pipeline_executions " +
                            "WHERE execution_id = '$parentExecutionId'",
                    ).use { rs ->
                        rs.next() shouldBe true
                        rs.getString(1) shouldBe parentExecutionId
                        rs.getString(2) shouldBe null
                    }
                statement
                    .executeQuery(
                        "SELECT execution_id::text, parent_execution_id::text, root_execution_id::text, " +
                            "triggered_via, parent_node_id, status, result_row_count FROM pipeline_executions " +
                            "WHERE parent_execution_id = '$parentExecutionId'",
                    ).use { rs ->
                        rs.next() shouldBe true
                        childExecutionId = rs.getString(1)
                        rs.getString(2) shouldBe parentExecutionId
                        rs.getString(3) shouldBe parentExecutionId
                        rs.getString(4) shouldBe "PIPELINE"
                        rs.getString(5) shouldBe "run_leaf"
                        rs.getString(6) shouldBe "SUCCESS"
                        // `direct` delivery: nothing is materialized for the child (design §4.2),
                        // so there is no stored result to count — the delivered rows are visible
                        // in the child's own node stats (asserted below).
                        rs.getLong(7)
                        rs.wasNull() shouldBe true
                    }
            }
        }
        return childExecutionId
    }

    /** §5 — the parent execution's durable `node_stats_json` carries `child_execution_id`. */
    private fun assertNodeStatsCarryChild(
        parentExecutionId: String,
        childExecutionId: String,
    ) {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                statement
                    .executeQuery(
                        "SELECT node_stats_json::text FROM pipeline_executions WHERE execution_id = '$parentExecutionId'",
                    ).use { rs ->
                        rs.next() shouldBe true
                        val stats = mapper.readTree(rs.getString(1))
                        stats.size() shouldBe 1
                        val node = stats[0]
                        node["node_id"].asText() shouldBe "run_leaf"
                        node["status"].asText() shouldBe "SUCCESS"
                        node["child_execution_id"].asText() shouldBe childExecutionId
                    }
            }
        }
    }

    /** §5/§8 — the child execution's own stats: its DQL caller node succeeded with the seeded rows. */
    private fun assertChildNodeStats(childExecutionId: String) {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                statement
                    .executeQuery(
                        "SELECT node_stats_json::text FROM pipeline_executions WHERE execution_id = '$childExecutionId'",
                    ).use { rs ->
                        rs.next() shouldBe true
                        val stats = mapper.readTree(rs.getString(1))
                        stats.size() shouldBe 1
                        val node = stats[0]
                        node["node_id"].asText() shouldBe "fetch_users"
                        node["status"].asText() shouldBe "SUCCESS"
                        node["rows_out"].asLong() shouldBe SEED_EMAILS.size.toLong()
                    }
            }
        }
    }

    private fun registerH2Datasource() {
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
            .body(
                """
                {"name": "$H2_DATASOURCE", "display_name": "Composition H2", "dialect": "H2",
                 "jdbc_url": "$H2_JDBC_URL", "username": "$H2_USER", "password": "$H2_PASSWORD"}
                """.trimIndent(),
            ).`when`()
            .post("/api/v1/datasources")
            .then()
            .statusCode(201)
    }

    private fun createTemplate(
        id: String,
        dialect: String,
        displayName: String,
        body: String,
    ) {
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
            .body(
                """
                {"id": "$id", "dialect": "$dialect", "display_name": "$displayName",
                 "description": "Composition E2E template", "imports": [],
                 "body": ${mapper.writeValueAsString(body)}}
                """.trimIndent(),
            ).`when`()
            .post("/api/v1/templates")
            .then()
            .statusCode(201)
    }

    private fun createPipeline(
        name: String,
        displayName: String,
        nodes: List<Map<String, Any?>>,
    ): String {
        val response = postPipeline(name, displayName, nodes)
        if (response.statusCode() != 201) {
            throw AssertionError(
                "Pipeline '$name' creation failed (status=${response.statusCode()}): ${response.body().asString()}",
            )
        }
        return response.jsonPath().getString("data.id")
    }

    private fun postPipeline(
        name: String,
        displayName: String,
        nodes: List<Map<String, Any?>>,
    ): Response {
        val bodyJson =
            mapper.writeValueAsString(
                mapOf(
                    "schema_version" to 1,
                    "name" to name,
                    "display_name" to displayName,
                    "description" to "Composition E2E pipeline — $displayName",
                    "parameters" to emptyMap<String, String>(),
                    "nodes" to nodes,
                ),
            )
        return given()
            .port(port)
            .contentType(ContentType.JSON)
            .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
            .body(bodyJson)
            .`when`()
            .post("/api/v1/pipelines")
    }

    /**
     * Reads the SSE stream to its end (EOF is the completion signal — see TracerBulletE2eTest),
     * returning (event name, payload) pairs. Callers wrap this in assertTimeoutPreemptively:
     * the client sets no read timeout of its own.
     */
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

        val events = mutableListOf<Pair<String, JsonNode>>()
        var currentEvent: String? = null
        for (line in response.body().lines()) {
            if (line.startsWith("event:")) {
                currentEvent = line.removePrefix("event:").trim()
            } else if (line.startsWith("data:")) {
                events += (currentEvent ?: "unknown") to mapper.readTree(line.removePrefix("data:").trim())
            }
        }
        return events
    }

    /** The H2 seed runs before datasource registration: first connection creates the database. */
    private fun seedH2() {
        DriverManager.getConnection(H2_JDBC_URL, H2_USER, H2_PASSWORD).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE comp_users (id INT PRIMARY KEY, email VARCHAR(255) NOT NULL)")
                statement.execute(
                    "INSERT INTO comp_users (id, email) VALUES (1, '${SEED_EMAILS[0]}'), (2, '${SEED_EMAILS[1]}')",
                )
            }
        }
    }

    private fun seedAuthRows() {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin)
                    VALUES ('$ADMIN_USER_ID', 'e2e-composition@datapipelines.test', 'E2E Composition', 'test', 'e2e-comp-sub', TRUE, TRUE)
                    """.trimIndent(),
                )
            }
            connection.prepareStatement("INSERT INTO api_keys (id, user_id, name, key_hash, scopes) VALUES (?, ?, ?, ?, ?)").use { ps ->
                ps.setString(1, ADMIN_KEY.id)
                ps.setObject(2, UUID.fromString(ADMIN_USER_ID))
                ps.setString(3, ADMIN_KEY.name)
                ps.setString(4, ADMIN_KEY.hash)
                ps.setArray(5, connection.createArrayOf("text", ADMIN_KEY.scopes))
                ps.executeUpdate()
            }
        }
    }

    /** A generated `dpk_<id>.<secret>` key and its stored Argon2id hash (auth.md §7.1/§7.2). */
    private class SeededKey(
        val name: String,
        val scopes: Array<out String>,
        val id: String,
        val plaintext: String,
        val hash: String,
    )

    companion object {
        private const val REDIS_PORT = 6379
        private const val SECRET_BYTES = 32
        private const val SSE_BUDGET_MINUTES = 2L
        private const val API_KEY_HEADER = "DP-API-Key"
        private const val BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

        private const val H2_DATASOURCE = "h2-comp"
        private const val H2_JDBC_URL = "jdbc:h2:mem:compdb;DB_CLOSE_DELAY=-1"
        private const val H2_USER = "sa"
        private const val H2_PASSWORD = "sa"

        private val ADMIN_USER_ID: String = UUID.randomUUID().toString()

        /** Seed rows in `id` order, the order the template returns them. */
        private val SEED_EMAILS = listOf("first@datapipelines.test", "second@datapipelines.test")

        private val random = SecureRandom()

        // Argon2id with auth's exact parameters (SecretHasher.kt: 2 / 19 456 / 1) — see
        // TracerBulletE2eTest for why the auth class itself is not referenced here.
        private val argon2 = Argon2Factory.create(Argon2Factory.Argon2Types.ARGON2id)

        private val ADMIN_KEY = generateKey("e2e-composition-key", arrayOf("admin"))

        private fun generateKey(
            name: String,
            scopes: Array<String>,
        ): SeededKey {
            val id = "dpk_" + (1..12).map { BASE32[random.nextInt(BASE32.length)] }.joinToString("")
            val plaintext = id + "." + (1..48).map { BASE32[random.nextInt(BASE32.length)] }.joinToString("")
            val chars = plaintext.toCharArray()
            val hash =
                try {
                    argon2.hash(2, 19_456, 1, chars)
                } finally {
                    argon2.wipeArray(chars)
                }
            return SeededKey(name = name, scopes = scopes, id = id, plaintext = plaintext, hash = hash)
        }

        @Container
        @JvmStatic
        private val postgres =
            PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("datapipelines")
                .withUsername("datapipelines")
                .withPassword("datapipelines")

        @Container
        @JvmStatic
        private val redis =
            GenericContainer("redis:7-alpine")
                .withCommand("redis-server", "--maxmemory-policy", "noeviction")
                .withExposedPorts(REDIS_PORT)

        private fun randomSecret(): String =
            Base64
                .getEncoder()
                .encodeToString(ByteArray(SECRET_BYTES).also { random.nextBytes(it) })

        private val oidc = OidcDiscoveryStub()

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("management.server.port") { "0" }

            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }

            registry.add("spring.data.redis.host") { redis.host }
            registry.add("spring.data.redis.port") { redis.getMappedPort(REDIS_PORT) }
            registry.add("spring.data.redis.password") { "" }
            registry.add("datapipelines.redis.host") { redis.host }
            registry.add("datapipelines.redis.port") { redis.getMappedPort(REDIS_PORT) }

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
