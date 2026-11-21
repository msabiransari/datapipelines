package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import co.datapipelines.integration.E2eSession.asSession
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
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
 * #1034 — DML operation results through the ordinary entry point (`POST /api/v1/pipelines/{id}/execute`)
 * under the production assembly and a real PostgreSQL target.
 *
 * Every durable claim is read back on a JDBC connection of this test's own, never through the
 * product: generated versus supplied keys, a key past 2^53 (identity starts at 2^53+1) kept exact on
 * the wire, a typed empty input, a BEFORE INSERT trigger that rewrites a supplied key, an insert that
 * affects two rows, an order whose staged id a downstream line insert consumes beside a discarded
 * result with no caller (stats only), update/delete counts for many and zero rows, and the legacy
 * operation-less DML refused at save.
 */
@Suppress("LargeClass") // one production-seam contract over one shared fixture family
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
class DmlOperationResultsIntegrationTest {
    @LocalServerPort
    private var port: Int = 0

    private val mapper = ObjectMapper()

    @Test
    fun `an insert returns generated and supplied keys under the physical key order_id, exact past 2^53`() {
        prepare()
        createTemplate("test/dml_stage_rows.sql", "POSTGRES", STAGE_ROWS)
        createTemplate("test/dml_insert_order.sql", "POSTGRES", INSERT_ORDER)
        val pipelineId =
            createPipeline("test/dml_keys", listOf(stageNode("stage", "test/dml_stage_rows.sql", "orders_in"), orderInsert("orders_in")))

        val events = consumeExecutionStream(pipelineId, emptyMap())

        withClue(describe(events)) { events.last().first shouldBe "data_ready" }
        val result = resultOf(events.first().second["execution_id"].asText())
        result["schema"].map { it["name"].asText() to it["type"].asText() } shouldBe listOf("customer" to "STRING", "id" to "BIGINTEGER")
        // Two generated keys past 2^53 and one supplied key: decimal strings, never rounded JSON numbers.
        val returned = result["rows"].associate { it[0].asText() to it[1].asText() }
        returned shouldBe mapOf("gen_a" to GENERATED_FIRST, "gen_b" to GENERATED_SECOND, "sup" to "500")
        result["rows"].map { it[1].isTextual } shouldBe listOf(true, true, true)
        // Matched by payload against the target read on this test's own connection.
        orders().associate { (id, customer) -> customer to id.toString() } shouldBe returned
    }

    @Test
    fun `an empty staged input executes nothing and returns zero rows under the typed schema`() {
        prepare()
        createTemplate("test/dml_stage_none.sql", "POSTGRES", "$STAGE_ROWS_SELECT WHERE 1 = 0")
        createTemplate("test/dml_insert_order.sql", "POSTGRES", INSERT_ORDER)
        val pipelineId =
            createPipeline("test/dml_empty", listOf(stageNode("stage", "test/dml_stage_none.sql", "orders_in"), orderInsert("orders_in")))

        val events = consumeExecutionStream(pipelineId, emptyMap())

        withClue(describe(events)) { events.last().first shouldBe "data_ready" }
        val result = resultOf(events.first().second["execution_id"].asText())
        result["total_rows"].asLong() shouldBe 0L
        result["schema"].map { it["name"].asText() to it["type"].asText() } shouldBe listOf("customer" to "STRING", "id" to "BIGINTEGER")
        orders().shouldBeEmpty()
    }

    @Test
    fun `a trigger that rewrites a supplied key fails the node and keeps no row of the insert`() {
        prepare()
        createTemplate("test/dml_stage_rewrite.sql", "POSTGRES", STAGE_REWRITE)
        createTemplate("test/dml_insert_rewrite.sql", "POSTGRES", "INSERT INTO rewrite_orders (order_id, customer) VALUES (:id, :customer)")
        val insert =
            dmlNode("rewrite", "test/dml_insert_rewrite.sql", "insert", mapOf("target" to "caller"), listOf("stage")) +
                mapOf("key" to mapOf("column" to "order_id", "type" to "BIGINTEGER"), "input" to mapOf("table" to "rewrite_in"))
        val pipelineId = createPipeline("test/dml_rewrite", listOf(stageNode("stage", "test/dml_stage_rewrite.sql", "rewrite_in"), insert))

        val events = consumeExecutionStream(pipelineId, emptyMap())

        // Durable state first, on this test's own connection: neither the correlated first row nor
        // the rewritten second survives.
        withClue(describe(events)) { count("rewrite_orders") shouldBe 0L }
        withClue(describe(events)) {
            events.lifecycleNames().last() shouldBe "pipeline_failed"
            events.last().second["error"]["code"].asText("") shouldBe "pipeline.node.dml_key_correlation_failed"
        }
        // No value reaches the diagnostic.
        events.last().second.toString() shouldNotContain REWRITTEN_KEY
    }

    @Test
    fun `an insert that affects two rows fails the node with the mismatch and keeps nothing`() {
        prepare()
        createTemplate(
            "test/dml_insert_two.sql",
            "POSTGRES",
            "INSERT INTO orders (customer) SELECT CAST(:customer AS text) FROM generate_series(1, 2)",
        )
        val insert =
            dmlNode("twice", "test/dml_insert_two.sql", "insert", mapOf("target" to "discard")) +
                mapOf("key" to mapOf("column" to "order_id", "type" to "BIGINTEGER"), "input" to mapOf("parameters" to listOf("customer")))
        val pipelineId = createPipeline("test/dml_mismatch", listOf(insert), CUSTOMER_PARAMETER)

        val events = consumeExecutionStream(pipelineId, mapOf("customer" to "ada"))

        withClue(describe(events)) {
            events.lifecycleNames().last() shouldBe "pipeline_failed"
            events.last().second["error"]["code"].asText("") shouldBe "pipeline.node.dml_affected_rows_mismatch"
        }
        orders().shouldBeEmpty()
    }

    @Test
    fun `a staged order id feeds a line insert, a separate result is discarded, and a zero-caller run returns stats only`() {
        prepare()
        createTemplate("test/dml_insert_customer.sql", "POSTGRES", "INSERT INTO orders (customer) VALUES (:customer)")
        createTemplate("test/dml_line_input.sql", "H2", """SELECT "id" AS "order_id", 'SKU-1' AS "sku" FROM "new_order"""")
        createTemplate("test/dml_insert_line.sql", "POSTGRES", "INSERT INTO order_lines (order_id, sku) VALUES (:order_id, :sku)")
        createTemplate("test/dml_audit.sql", "POSTGRES", "UPDATE orders SET customer = customer WHERE customer = 'ada'")
        val order =
            dmlNode("order", "test/dml_insert_customer.sql", "insert", mapOf("target" to "tempdb", "table" to "new_order")) +
                mapOf("key" to mapOf("column" to "order_id", "type" to "BIGINTEGER"), "input" to mapOf("parameters" to listOf("customer")))
        val lineInput =
            mapOf(
                "id" to "line_input",
                "description" to "Projects the staged order id as the line's order_id",
                "type" to "DQL",
                "source" to "tempdb",
                "template" to mapOf("id" to "test/dml_line_input.sql", "version" to 1),
                "output" to mapOf("target" to "tempdb", "table" to "lines_in"),
                "depends_on" to listOf("order"),
            )
        val lines =
            dmlNode("lines", "test/dml_insert_line.sql", "insert", mapOf("target" to "discard"), listOf("line_input")) +
                mapOf("key" to mapOf("column" to "line_id", "type" to "INTEGER"), "input" to mapOf("table" to "lines_in"))
        val audit = dmlNode("audit", "test/dml_audit.sql", "update", mapOf("target" to "discard"), listOf("lines"))
        val pipelineId = createPipeline("test/dml_order_lines", listOf(order, lineInput, lines, audit), CUSTOMER_PARAMETER)

        val events = consumeExecutionStream(pipelineId, mapOf("customer" to "ada"))

        withClue(describe(events)) { events.lifecycleNames().last() shouldBe "pipeline_completed" }
        val executionId = events.first().second["execution_id"].asText()
        val result = resultOf(executionId)
        result["total_rows"].asLong() shouldBe 0L
        result["rows"].size() shouldBe 0
        result["schema"].size() shouldBe 0
        resultRowCount(executionId) shouldBe null
        orders() shouldBe listOf(GENERATED_FIRST.toLong() to "ada")
        lines() shouldBe listOf(GENERATED_FIRST.toLong() to "SKU-1")
    }

    @Test
    fun `update and delete return affected_rows for many and for zero as one BIGINTEGER row`() {
        prepare()
        seed("INSERT INTO orders (customer) VALUES ('bulk'), ('bulk'), ('bulk')")
        createTemplate("test/dml_update_bulk.sql", "POSTGRES", "UPDATE orders SET customer = 'bulk2' WHERE customer = 'bulk'")
        createTemplate("test/dml_delete_none.sql", "POSTGRES", "DELETE FROM orders WHERE customer = 'nobody'")

        listOf(
            Triple("test/dml_update_many", dmlNode("bulk", "test/dml_update_bulk.sql", "update", mapOf("target" to "caller")), "3"),
            Triple("test/dml_delete_zero", dmlNode("none", "test/dml_delete_none.sql", "delete", mapOf("target" to "caller")), "0"),
        ).forEach { (name, node, expected) ->
            val events = consumeExecutionStream(createPipeline(name, listOf(node)), emptyMap())
            withClue("$name ${describe(events)}") {
                events.last().first shouldBe "data_ready"
                val result = resultOf(events.first().second["execution_id"].asText())
                result["schema"].map { it["name"].asText() to it["type"].asText() } shouldBe listOf("affected_rows" to "BIGINTEGER")
                result["rows"].map { row -> row.map { it.asText() } } shouldBe listOf(listOf(expected))
            }
        }
        orders().map { it.second } shouldBe listOf("bulk2", "bulk2", "bulk2")
    }

    @Test
    fun `a DML node with no operation is refused at save`() {
        prepare()
        createTemplate("test/dml_audit.sql", "POSTGRES", "UPDATE orders SET customer = customer WHERE customer = 'ada'")
        val legacy = dmlNode("legacy", "test/dml_audit.sql", "update", mapOf("target" to "discard")) - "operation"

        val response = postPipeline("test/dml_legacy", listOf(legacy), emptyMap())

        withClue(response.body().asString()) {
            response.statusCode() shouldBe 400
            response.jsonPath().getString("error.code") shouldBe "pipeline.validation.dml_operation_required"
        }
    }

    // ------------------------------------------------------------ nodes

    private fun stageNode(
        id: String,
        templateId: String,
        table: String,
    ): Map<String, Any?> =
        mapOf(
            "id" to id,
            "description" to "Stages the insert's input rows",
            "type" to "DQL",
            "source" to DATASOURCE,
            "template" to mapOf("id" to templateId, "version" to 1),
            "output" to mapOf("target" to "tempdb", "table" to table),
            "depends_on" to emptyList<String>(),
        )

    private fun orderInsert(table: String): Map<String, Any?> =
        dmlNode("insert", "test/dml_insert_order.sql", "insert", mapOf("target" to "caller"), listOf("stage")) +
            mapOf("key" to mapOf("column" to "order_id", "type" to "BIGINTEGER"), "input" to mapOf("table" to table))

    private fun dmlNode(
        id: String,
        templateId: String,
        operation: String,
        output: Map<String, Any?>,
        dependsOn: List<String> = emptyList(),
    ): Map<String, Any?> =
        mapOf(
            "id" to id,
            "description" to "A DML $operation",
            "type" to "DML",
            "source" to DATASOURCE,
            "template" to mapOf("id" to templateId, "version" to 1),
            "operation" to operation,
            "output" to output,
            "depends_on" to dependsOn,
        )

    // ------------------------------------------------------------ fixtures

    private fun prepare() {
        ensureAuthSeeded()
        registerDatasource()
        seed(
            "CREATE TABLE IF NOT EXISTS orders " +
                "(order_id bigint GENERATED BY DEFAULT AS IDENTITY (START WITH $GENERATED_FIRST) PRIMARY KEY, customer text)",
            "CREATE TABLE IF NOT EXISTS order_lines " +
                "(line_id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, order_id bigint NOT NULL, sku text)",
            "CREATE TABLE IF NOT EXISTS rewrite_orders (order_id bigint PRIMARY KEY, customer text)",
            """
            CREATE OR REPLACE FUNCTION rewrite_key() RETURNS trigger AS ${'$'}${'$'}
            BEGIN
              IF NEW.customer = 'rewrite' THEN NEW.order_id := NEW.order_id + 1000; END IF;
              RETURN NEW;
            END ${'$'}${'$'} LANGUAGE plpgsql
            """.trimIndent(),
            "DROP TRIGGER IF EXISTS rewrite_key ON rewrite_orders",
            "CREATE TRIGGER rewrite_key BEFORE INSERT ON rewrite_orders FOR EACH ROW EXECUTE FUNCTION rewrite_key()",
            "TRUNCATE orders, order_lines, rewrite_orders RESTART IDENTITY",
        )
    }

    private fun seed(vararg statements: String) {
        DriverManager.getConnection(db.jdbcUrl, db.username, db.password).use { connection ->
            connection.createStatement().use { statement -> statements.forEach { statement.execute(it) } }
        }
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
                {"name": "$DATASOURCE", "display_name": "DML 1034", "dialect": "POSTGRES",
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
                    {"id": "$id", "dialect": "$dialect", "display_name": "DML 1034 $id",
                     "description": "DML operation results E2E template", "imports": [],
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
                        "display_name" to "DML 1034 $name",
                        "description" to "DML operation results E2E pipeline",
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

    private fun orders(): List<Pair<Long, String>> =
        query("SELECT order_id, customer FROM orders ORDER BY order_id") {
            it.getLong(1) to
                it.getString(2)
        }

    private fun lines(): List<Pair<Long, String>> =
        query("SELECT order_id, sku FROM order_lines ORDER BY line_id") {
            it.getLong(1) to
                it.getString(2)
        }

    private fun count(table: String): Long = query("SELECT count(*) FROM $table") { it.getLong(1) }.single()

    private fun resultRowCount(executionId: String): Any? =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.prepareStatement("SELECT result_row_count FROM pipeline_executions WHERE execution_id = ?::uuid").use { statement ->
                statement.setString(1, executionId)
                statement.executeQuery().use { rs ->
                    rs.next() shouldBe true
                    rs.getObject(1)
                }
            }
        }

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

    companion object {
        private const val DATASOURCE = "dml1034"
        private const val SSE_BUDGET_MINUTES = 2L

        /** 2^53 + 1 — the first identity value, unrepresentable as a double. */
        private const val GENERATED_FIRST = "9007199254740993"
        private const val GENERATED_SECOND = "9007199254740994"

        /** What the trigger turns the supplied key 2 into; it must never reach a diagnostic. */
        private const val REWRITTEN_KEY = "1002"

        private const val STAGE_ROWS_SELECT =
            "SELECT id, customer FROM (VALUES (CAST(NULL AS bigint), 'gen_a'), (CAST(NULL AS bigint), 'gen_b'), " +
                "(CAST(500 AS bigint), 'sup')) AS v(id, customer)"
        private const val STAGE_ROWS = "$STAGE_ROWS_SELECT ORDER BY customer"
        private const val STAGE_REWRITE =
            "SELECT id, customer FROM (VALUES (CAST(1 AS bigint), 'ok'), (CAST(2 AS bigint), 'rewrite')) AS v(id, customer) ORDER BY id"

        /** A null `:id` asks the identity for the next key; a supplied one is kept as given. */
        private const val INSERT_ORDER =
            "INSERT INTO orders (order_id, customer) " +
                "VALUES (COALESCE(CAST(:id AS bigint), nextval(pg_get_serial_sequence('orders', 'order_id'))), :customer)"

        private val CUSTOMER_PARAMETER =
            mapOf("customer" to mapOf("type" to "STRING", "required" to true, "description" to "The ordering customer."))

        private const val DEFAULT_WORKSPACE = "defa0000-0000-0000-0000-000000000001"
        private val ADMIN_USER_ID: String = UUID.randomUUID().toString()
        private const val ADMIN_EMAIL = "e2e-dml1034@datapipelines.test"
        private val ADMIN_SESSION get() = E2eSession.jwt(JWT_SECRET, ADMIN_USER_ID, ADMIN_EMAIL)
        private val JWT_SECRET = E2eSession.newSecret()

        private val postgres get() = SharedE2e.postgres
        private val db = SharedE2e.scratchDatabase("dml1034")
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
