package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import co.datapipelines.integration.E2eSession.asSession
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
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
 * #1036 R2 — the qualified STRING insert key on PostgreSQL (dag-executor.md §16.6), measured with the
 * pinned driver (pgjdbc) through the ordinary entry point, `POST /api/v1/pipelines/{id}/execute`, on a
 * real server, for the three physical column types the declared canonical type STRING can name there:
 * `text`, `varchar(n)` and `uuid`.
 *
 * For each: one insert node takes two GENERATED keys (a null `id` asks the column's own generator) and
 * one SUPPLIED key; the run's canonical output (decimal-free STRING ids, one row per input row, in
 * input order) is compared with the key each payload was stored under, read back on a JDBC connection
 * of this test's own — never through the product. A later failing node proves the keys roll back with
 * the family. A supplied uuid the driver would hand back in another spelling fails the node as a
 * `key_rewritten` correlation failure and keeps nothing: never a silently different id.
 *
 * INTEGER and BIGINTEGER are the other two qualified PostgreSQL key types; their evidence is
 * `LocalTransactionDialectQualificationTest` (INTEGER, generated and supplied, enrolled) and
 * `DmlOperationResultsIntegrationTest` (BIGINTEGER, past 2^53).
 */
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
class DmlKeyTypeQualificationIntegrationTest {
    @LocalServerPort
    private var port: Int = 0

    private val mapper = ObjectMapper()

    @Test
    fun `text keys - generated and supplied are returned in input order and match what the table stored`() {
        keysRoundTrip(TEXT)
    }

    @Test
    fun `varchar keys - generated and supplied are returned in input order and match what the table stored`() {
        keysRoundTrip(VARCHAR)
    }

    @Test
    fun `uuid keys - generated and supplied are returned in input order and match what the table stored`() {
        keysRoundTrip(UUID_KEY)
    }

    @Test
    fun `a failure after a text insert rolls its generated and supplied keys back`() {
        rollsBack(TEXT)
    }

    @Test
    fun `a failure after a varchar insert rolls its generated and supplied keys back`() {
        rollsBack(VARCHAR)
    }

    @Test
    fun `a failure after a uuid insert rolls its generated and supplied keys back`() {
        rollsBack(UUID_KEY)
    }

    @Test
    fun `an empty staged input executes nothing and returns zero rows under the STRING key schema`() {
        prepare()
        createTemplate("test/dml1036_stage_none.sql", "POSTGRES", "${TEXT.stage} WHERE 1 = 0")
        createTemplate(TEXT.insertTemplate, "POSTGRES", TEXT.insert)
        val pipelineId = createPipeline("test/dml1036_empty", listOf(stageNode("test/dml1036_stage_none.sql"), insertNode(TEXT, "caller")))

        val events = consumeExecutionStream(pipelineId, emptyMap())

        withClue(describe(events)) { events.last().first shouldBe "data_ready" }
        val result = resultOf(events.first().second["execution_id"].asText())
        result["total_rows"].asLong() shouldBe 0L
        result["schema"].map { it["name"].asText() to it["type"].asText() } shouldBe listOf("payload" to "STRING", "id" to "STRING")
        count(TEXT).shouldBe(0L)
    }

    @Test
    fun `an uppercase supplied uuid the driver returns in lower case fails the node and keeps no row`() {
        prepare()
        createTemplate("test/dml1036_stage_upper.sql", "POSTGRES", UPPERCASE_UUID_STAGE)
        createTemplate(UUID_KEY.insertTemplate, "POSTGRES", UUID_KEY.insert)
        val pipelineId =
            createPipeline("test/dml1036_upper", listOf(stageNode("test/dml1036_stage_upper.sql"), insertNode(UUID_KEY, "caller")))

        val events = consumeExecutionStream(pipelineId, emptyMap())

        withClue(describe(events)) {
            count(UUID_KEY) shouldBe 0L
            events.lifecycleNames().last() shouldBe "pipeline_failed"
            events.last().second["error"]["code"].asText("") shouldBe "pipeline.node.dml_key_correlation_failed"
        }
        // No key value reaches the diagnostic.
        events.last().second.toString() shouldNotContain UPPERCASE_UUID
    }

    private fun keysRoundTrip(kind: KeyKind) {
        prepare()
        createTemplate(kind.stageTemplate, "POSTGRES", "${kind.stage} ORDER BY payload")
        createTemplate(kind.insertTemplate, "POSTGRES", kind.insert)
        val pipelineId = createPipeline("test/dml1036_${kind.name}", listOf(stageNode(kind.stageTemplate), insertNode(kind, "caller")))

        val events = consumeExecutionStream(pipelineId, emptyMap())

        withClue(describe(events)) { events.last().first shouldBe "data_ready" }
        val result = resultOf(events.first().second["execution_id"].asText())
        result["schema"].map { it["name"].asText() to it["type"].asText() } shouldBe listOf("payload" to "STRING", "id" to "STRING")
        val returned = result["rows"].map { it[0].asText() to it[1].asText() }
        // One output row per input row, in input order, every id a JSON string.
        returned.map { it.first } shouldBe listOf("gen_a", "gen_b", "sup")
        result["rows"].map { it[1].isTextual } shouldBe listOf(true, true, true)
        // The supplied key is stored and returned unchanged.
        returned.single { it.first == "sup" }.second shouldBe kind.suppliedKey
        // The generated keys are the generator's, distinct, and not the supplied one.
        val generated = returned.filter { it.first != "sup" }.map { it.second }
        generated.toSet().size shouldBe 2
        generated.forEach { it shouldNotBe kind.suppliedKey }
        // Payload-to-key read back on this test's own connection must equal the product's output, row for row.
        stored(kind) shouldBe returned.toMap()
    }

    private fun rollsBack(kind: KeyKind) {
        prepare()
        createTemplate(kind.stageTemplate, "POSTGRES", "${kind.stage} ORDER BY payload")
        createTemplate(kind.insertTemplate, "POSTGRES", kind.insert)
        createTemplate("test/dml1036_boom.sql", "POSTGRES", "SELECT * FROM table_that_does_not_exist_1036")
        val boom =
            mapOf(
                "id" to "boom",
                "description" to "Fails after the insert",
                "type" to "DQL",
                "source" to DATASOURCE,
                "template" to mapOf("id" to "test/dml1036_boom.sql", "version" to 1),
                "output" to mapOf("target" to "caller"),
                "depends_on" to listOf("insert"),
            )
        val pipelineId =
            createPipeline(
                "test/dml1036_${kind.name}_rollback",
                listOf(stageNode(kind.stageTemplate), insertNode(kind, "discard"), boom),
            )

        val events = consumeExecutionStream(pipelineId, emptyMap())

        withClue(describe(events)) {
            events.lifecycleNames().last() shouldBe "pipeline_failed"
            // The failure is the LATER node's: the insert was admitted and ran to completion before it, so the empty
            // table below is a rollback, not a refusal that never wrote a row.
            events.last().second["failed_node_id"].asText("") shouldBe "boom"
            events.count { it.first == "node_completed" } shouldBe 2
            // Read back on this test's own connection: neither the generated nor the supplied key survives.
            count(kind) shouldBe 0L
            stored(kind).keys.shouldBeEmpty()
        }
    }

    // ------------------------------------------------------------ nodes

    private fun stageNode(templateId: String): Map<String, Any?> =
        mapOf(
            "id" to "stage",
            "description" to "Stages the insert's input rows",
            "type" to "DQL",
            "source" to DATASOURCE,
            "template" to mapOf("id" to templateId, "version" to 1),
            "output" to mapOf("target" to "tempdb", "table" to "keys_in"),
            "depends_on" to emptyList<String>(),
        )

    private fun insertNode(
        kind: KeyKind,
        output: String,
    ): Map<String, Any?> =
        mapOf(
            "id" to "insert",
            "description" to "A DML insert with a ${kind.name} key",
            "type" to "DML",
            "source" to DATASOURCE,
            "template" to mapOf("id" to kind.insertTemplate, "version" to 1),
            "operation" to "insert",
            "output" to mapOf("target" to output),
            "depends_on" to listOf("stage"),
            "key" to mapOf("column" to "id", "type" to "STRING"),
            "input" to mapOf("table" to "keys_in"),
        )

    // ------------------------------------------------------------ fixtures

    private fun prepare() {
        ensureAuthSeeded()
        registerDatasource()
        seed(
            "CREATE SEQUENCE IF NOT EXISTS key_seq_1036",
            "CREATE TABLE IF NOT EXISTS key_text (id text PRIMARY KEY, payload text NOT NULL)",
            "CREATE TABLE IF NOT EXISTS key_varchar (id varchar(40) PRIMARY KEY, payload text NOT NULL)",
            "CREATE TABLE IF NOT EXISTS key_uuid (id uuid PRIMARY KEY, payload text NOT NULL)",
            "TRUNCATE key_text, key_varchar, key_uuid",
        )
    }

    private fun postPipeline(
        name: String,
        nodes: List<Map<String, Any?>>,
        parameters: Map<String, Any?>,
    ): Response =
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .asSession(ADMIN_SESSION)
            .body(
                mapper.writeValueAsString(
                    mapOf(
                        "schema_version" to 1,
                        "name" to name,
                        "display_name" to "DML 1036 $name",
                        "description" to "DML key type E2E pipeline",
                        "parameters" to parameters,
                        "nodes" to nodes,
                    ),
                ),
            ).`when`()
            .post("/api/v1/pipelines")
            .thenReturn()

    private fun createPipeline(
        name: String,
        nodes: List<Map<String, Any?>>,
        parameters: Map<String, Any?> = emptyMap(),
    ): String {
        val response = postPipeline(name, nodes, parameters)
        if (response.statusCode() != 201) {
            throw AssertionError("Pipeline '$name' creation failed (${response.statusCode()}): ${response.body().asString()}")
        }
        return response.jsonPath().getString("data.id")
    }

    // ------------------------------------------------------------ reads (a connection of this test's own)

    private fun <T> query(
        sql: String,
        read: (java.sql.ResultSet) -> T,
    ): List<T> =
        DriverManager.getConnection(db.jdbcUrl, db.username, db.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { rs -> generateSequence { if (rs.next()) read(rs) else null }.toList() }
            }
        }

    /** Payload to the key the table stored it under; the key as the server prints it (uuid in lower case). */
    private fun stored(kind: KeyKind): Map<String, String> =
        query("SELECT payload, CAST(id AS text) FROM ${kind.table}") { it.getString(1) to it.getString(2) }.toMap()

    private fun count(kind: KeyKind): Long = query("SELECT count(*) FROM ${kind.table}") { it.getLong(1) }.single()

    private fun resultOf(executionId: String): JsonNode =
        mapper.readTree(
            given()
                .port(port)
                .asSession(ADMIN_SESSION)
                .`when`()
                .get("/api/v1/executions/$executionId/result")
                .then()
                .statusCode(200)
                .extract()
                .body()
                .asString(),
        )["data"]

    private fun describe(events: List<Pair<String, JsonNode>>): String =
        events.joinToString(prefix = "[", postfix = "]") { (name, payload) ->
            val code = payload.path("error").path("code").asText("")
            if (code.isEmpty()) name else "$name($code)"
        }

    private fun consumeExecutionStream(
        pipelineId: String,
        parameters: Map<String, Any?>,
    ): List<Pair<String, JsonNode>> =
        assertTimeoutPreemptively(Duration.ofMinutes(SSE_BUDGET_MINUTES)) {
            val request =
                HttpRequest
                    .newBuilder(URI.create("http://localhost:$port/api/v1/pipelines/$pipelineId/execute"))
                    .header("Cookie", E2eSession.cookieHeader(ADMIN_SESSION))
                    .header(E2eSession.CSRF_HEADER, E2eSession.CSRF_TOKEN)
                    .header("DP-Correlation-Id", UUID.randomUUID().toString())
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(mapOf("parameters" to parameters))))
                    .build()
            val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
            response.statusCode() shouldBe 200
            E2eSse.parseEvents(response.body(), mapper)
        }

    private fun registerDatasource() {
        val existing =
            given()
                .port(port)
                .asSession(ADMIN_SESSION)
                .`when`()
                .get("/api/v1/datasources/$DATASOURCE")
                .then()
                .extract()
        if (existing.statusCode() == 200) return
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .asSession(ADMIN_SESSION)
            .body(
                """
                {"name": "$DATASOURCE", "display_name": "DML 1036 key types", "dialect": "POSTGRES",
                 "jdbc_url": "${db.jdbcUrl}", "username": "${db.username}", "password": "${db.password}"}
                """.trimIndent(),
            ).`when`()
            .post("/api/v1/datasources")
            .then()
            .statusCode(201)
    }

    private fun ensureAuthSeeded() {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin)
                    VALUES ('$ADMIN_USER_ID', '$ADMIN_EMAIL', '$ADMIN_EMAIL', 'test', 'sub-$ADMIN_USER_ID', TRUE, TRUE)
                    ON CONFLICT (id) DO NOTHING
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    INSERT INTO workspace_members (workspace_id, user_id, role)
                    VALUES ('$DEFAULT_WORKSPACE', '$ADMIN_USER_ID', 'workspace_admin')
                    ON CONFLICT DO NOTHING
                    """.trimIndent(),
                )
            }
        }
    }

    /** Creates and releases a template once per context (the name memo is shared by the class). */
    private fun createTemplate(
        id: String,
        dialect: String,
        body: String,
    ) {
        synchronized(createdTemplates) {
            if (!createdTemplates.add(id)) return
        }
        val response =
            given()
                .port(port)
                .contentType(ContentType.JSON)
                .asSession(ADMIN_SESSION)
                .body(
                    """
                    {"id": "$id", "dialect": "$dialect", "display_name": "DML 1036 $id",
                     "description": "DML key type qualification E2E template", "imports": [],
                     "body": ${mapper.writeValueAsString(body)}}
                    """.trimIndent(),
                ).`when`()
                .post("/api/v1/templates")
                .then()
                .statusCode(201)
                .extract()
        given()
            .port(port)
            .asSession(ADMIN_SESSION)
            .header("If-Match", response.jsonPath().getString("data.body_hash"))
            .contentType(ContentType.JSON)
            .body("""{"name": "$id"}""")
            .`when`()
            .post("/api/v1/templates/release")
            .then()
            .statusCode(200)
    }

    private fun seed(vararg statements: String) {
        DriverManager.getConnection(db.jdbcUrl, db.username, db.password).use { connection ->
            connection.createStatement().use { statement -> statements.forEach { statement.execute(it) } }
        }
    }

    companion object {
        private const val DATASOURCE = "dml1036k"
        private const val SSE_BUDGET_MINUTES = 2L

        private const val SUPPLIED_UUID = "0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"
        private val UPPERCASE_UUID = SUPPLIED_UUID.uppercase()

        /** One physical column type the declared STRING key can name on PostgreSQL. */
        private data class KeyKind(
            val name: String,
            val table: String,
            val sqlType: String,
            val suppliedKey: String,
            val generator: String,
        ) {
            val stageTemplate get() = "test/dml1036_stage_$name.sql"
            val insertTemplate get() = "test/dml1036_insert_$name.sql"
            val stage: String
                get() =
                    "SELECT id, payload FROM (VALUES (CAST(NULL AS $sqlType), 'gen_a'), (CAST(NULL AS $sqlType), 'gen_b'), " +
                        "(CAST('$suppliedKey' AS $sqlType), 'sup')) AS v(id, payload)"

            /** A null `:id` asks the column's own generator; a supplied one is kept as given. */
            val insert: String
                get() = "INSERT INTO $table (id, payload) VALUES (COALESCE(CAST(:id AS $sqlType), $generator), :payload)"
        }

        private val TEXT = KeyKind("text", "key_text", "text", "supplied-key-1", "'gen-' || nextval('key_seq_1036')")
        private val VARCHAR = KeyKind("varchar", "key_varchar", "varchar(40)", "supplied-key-2", "'gen-' || nextval('key_seq_1036')")
        private val UUID_KEY = KeyKind("uuid", "key_uuid", "uuid", SUPPLIED_UUID, "gen_random_uuid()")

        private val UPPERCASE_UUID_STAGE =
            // Staged as TEXT: a uuid-typed staging column would already be the canonical lower-case spelling.
            "SELECT id, payload FROM (VALUES (CAST('$UPPERCASE_UUID' AS text), 'upper')) AS v(id, payload)"

        private const val DEFAULT_WORKSPACE = "defa0000-0000-0000-0000-000000000001"
        private val ADMIN_USER_ID: String = UUID.randomUUID().toString()
        private const val ADMIN_EMAIL = "e2e-dml1036k@datapipelines.test"
        private val ADMIN_SESSION get() = E2eSession.jwt(JWT_SECRET, ADMIN_USER_ID, ADMIN_EMAIL)
        private val JWT_SECRET = E2eSession.newSecret()

        private val postgres get() = SharedE2e.postgres
        private val db = SharedE2e.scratchDatabase("dml1036k")
        private val redis get() = SharedE2e.redis
        private val oidc = OidcDiscoveryStub()
        private val createdTemplates = mutableSetOf<String>()

        private fun randomSecret(): String = Base64.getEncoder().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })

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
            registry.add("datapipelines.jwt.secret") { JWT_SECRET }
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
