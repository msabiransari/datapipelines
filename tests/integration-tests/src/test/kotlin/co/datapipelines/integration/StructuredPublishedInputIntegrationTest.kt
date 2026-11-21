package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import co.datapipelines.integration.E2eSession.asSession
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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
 * #1038 — structured published inputs through the production assembly and a real PostgreSQL target.
 *
 * A pipeline declares `request_body`; a SCRIPT transformer (JSONata and JavaScript, one pipeline each) binds
 * `@request_body`, turns the native object or array into typed rows, and a DML insert writes them. Every durable
 * claim is read back on this test's own JDBC connection: an order object with nested lines, a heterogeneous root
 * array, a singleton object, nested Unicode and nulls, `{}` and `[]`; an exact string id past 2^53 that
 * JavaScript's `Number` would round; framework execute and `pipelines_execute` writing the same rows from the same
 * fixture and honouring the same configured byte bound; every refusal (duplicate member, a top-level
 * `request_body` given twice, invalid root and invalid final element, a missing required body, a body the pipeline
 * does not accept, a parameter naming a body reader's output, over the byte cap, and a malformed fixture — the
 * existing whole-request refusal) leaving no row and no execution and naming no value; a transformer failure
 * failing the run before the DML writes anything; and one Idempotency-Key reused with a different fixture refused
 * as a different request.
 */
@Suppress("LargeClass") // one production-seam contract over one shared fixture family
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
class StructuredPublishedInputIntegrationTest {
    @LocalServerPort
    private var port: Int = 0

    private val mapper = ObjectMapper()
    private val http: HttpClient = HttpClient.newHttpClient()

    @Test
    fun `both transformers turn an object, a heterogeneous array, a singleton, an empty object and an empty array into rows`() {
        prepare()
        val fixtures =
            listOf(
                ORDER_OBJECT to listOf(Line("A-1", 2, null), Line("B-2", 1, EXACT_ID)),
                HETEROGENEOUS to listOf(Line("C-3", 4, null), Line("D-4", 5, EXACT_ID)),
                """{"sku":"E-5","qty":7}""" to listOf(Line("E-5", 7, null)),
                """[{"sku":"Ü-6 ✓ 😀","qty":3,"meta":{"name":"Zoë","list":[null,{"deep":"日本"}]}}]""" to listOf(Line("Ü-6 ✓ 😀", 3, null)),
                "{}" to emptyList(),
                "[]" to emptyList(),
            )
        for (language in listOf(JSONATA, JAVASCRIPT)) {
            val pipelineId = linesPipeline(language)
            fixtures.forEach { (fixture, expected) ->
                truncate()
                val events = executeStream(pipelineId, """{"parameters":{},"request_body":$fixture}""")
                withClue("$language $fixture ${describe(events)}") {
                    events.last().first shouldBe "data_ready"
                    lines().map { Line(it.sku, it.qty, it.extId) } shouldBe expected
                }
            }
        }
    }

    @Test
    fun `an exact string id past 2^53 is written unchanged, and JavaScript Number shows the precision limit`() {
        prepare()
        val pipelineId = linesPipeline(JAVASCRIPT)
        truncate()

        val events = executeStream(pipelineId, """{"parameters":{},"request_body":{"sku":"P-1","qty":1,"ext_id":"$EXACT_ID"}}""")

        withClue(describe(events)) { events.last().first shouldBe "data_ready" }
        val row = lines().single()
        row.extId shouldBe EXACT_ID
        row.extNumber shouldBe ROUNDED_BY_NUMBER
    }

    @Test
    fun `framework execute and pipelines_execute write the same rows from the same fixture`() {
        prepare()
        val pipelineId = linesPipeline(JSONATA)

        truncate()
        val events = executeStream(pipelineId, """{"parameters":{},"request_body":$ORDER_OBJECT}""")
        withClue(describe(events)) { events.last().first shouldBe "data_ready" }
        val viaFramework = lines()
        val frameworkRows = resultOf(events.first().second["execution_id"].asText())["rows"].map { row -> row.map { it.asText() } }

        truncate()
        val (payload, isError) =
            callTool(
                "pipelines_execute",
                mapOf(
                    "id" to pipelineId,
                    "parameters" to emptyMap<String, Any>(),
                    "request_body" to mapper.readValue(ORDER_OBJECT, Map::class.java),
                ),
            )
        withClue("pipelines_execute: $payload") {
            isError shouldBe false
            payload["status"].asText() shouldBe "SUCCESS"
        }
        val viaMcp = lines()

        viaMcp shouldBe viaFramework
        payload["rows"].map { row -> row.map { it.asText() } } shouldBe frameworkRows
    }

    @Test
    fun `framework execute and pipelines_execute both decode under the configured byte bound`() {
        prepare()
        val pipelineId = linesPipeline(JSONATA)
        truncate()
        // Just under the configured bound is admitted on both surfaces: the note travels as a member the transformer
        // ignores. Just over it, both refuse with the same reason before anything runs.
        val note = "x".repeat(BODY_MAX_BYTES - """{"sku":"N-1","qty":1,"note":""}""".length - BOUND_MARGIN)
        val events = executeStream(pipelineId, """{"parameters":{},"request_body":{"sku":"N-1","qty":1,"note":"$note"}}""")
        withClue(describe(events)) { events.last().first shouldBe "data_ready" }
        val (admitted, admittedError) =
            callTool(
                "pipelines_execute",
                mapOf(
                    "id" to pipelineId,
                    "parameters" to emptyMap<String, Any>(),
                    "request_body" to mapOf("sku" to "N-2", "qty" to 1, "note" to note),
                ),
            )
        withClue("pipelines_execute at the bound: $admitted") { admittedError shouldBe false }
        lines().map { it.sku } shouldBe listOf("N-1", "N-2")

        val (refused, refusedError) =
            callTool(
                "pipelines_execute",
                mapOf(
                    "id" to pipelineId,
                    "parameters" to emptyMap<String, Any>(),
                    "request_body" to mapOf("sku" to "N-3", "qty" to 1, "note" to note + "x".repeat(2 * BOUND_MARGIN)),
                ),
            )
        withClue("pipelines_execute over the bound: $refused") {
            refusedError shouldBe true
            refused["error"]["code"].asText() shouldBe "pipeline.execution.request_body_invalid"
            refused["error"]["details"]["reason"].asText() shouldBe "too_many_bytes"
        }
        lines().map { it.sku } shouldBe listOf("N-1", "N-2")
    }

    @Test
    fun `every refusal leaves no row and no execution, and names no supplied value`() {
        prepare()
        val linesId = linesPipeline(JSONATA)
        val plainId = plainPipeline()
        val countId = countPipeline()
        truncate()
        val before = listOf(linesId, plainId, countId).associateWith { executionCount(it) }
        val cases =
            listOf(
                Triple(linesId, """{"parameters":{},"request_body":{"sku":"secret-a","sku":"secret-b"}}""", "duplicate_member"),
                Triple(linesId, """{"parameters":{},"request_body":"secret-text"}""", "invalid_root"),
                Triple(linesId, """{"parameters":{},"request_body":[{"sku":"A"},"secret-text"]}""", "invalid_root"),
                Triple(linesId, """{"parameters":{},"request_body":{},"request_body":{"sku":"secret-a"}}""", "duplicate_member"),
                Triple(linesId, """{"parameters":{}}""", "required"),
                Triple(plainId, """{"parameters":{},"request_body":{"sku":"secret-a"}}""", "not_accepted"),
                Triple(countId, """{"parameters":{"line_count":5},"request_body":{"lines":[]}}""", "override_refused"),
                Triple(linesId, """{"parameters":{},"request_body":{"note":"secret-${"x".repeat(BODY_MAX_BYTES)}"}}""", "too_many_bytes"),
            )
        cases.forEach { (pipelineId, body, reason) ->
            val response = post(pipelineId, body)
            withClue("$reason ${response.body().asString().take(300)}") {
                response.statusCode() shouldBe 400
                response.jsonPath().getString("error.code") shouldBe "pipeline.execution.request_body_invalid"
                response.jsonPath().getString("error.details.reason") shouldBe reason
                response.body().asString() shouldNotContain "secret"
            }
        }
        // A malformed fixture makes the whole execute body unreadable: the existing whole-request refusal answers
        // first (the published codec's malformed_json is PublishedRequestBodyCodecTest's), still naming no value.
        val malformed = post(linesId, """{"parameters":{},"request_body":{"sku":"secret-a"""")
        withClue(malformed.body().asString().take(300)) {
            malformed.statusCode() shouldBe 400
            malformed.jsonPath().getString("error.code") shouldBe "pipeline.execution.invalid_parameter_type"
            malformed.body().asString() shouldNotContain "secret"
        }
        lines().shouldBeEmpty()
        before.forEach { (pipelineId, count) -> withClue(pipelineId) { executionCount(pipelineId) shouldBe count } }

        val (payload, isError) =
            callTool(
                "pipelines_execute",
                mapOf(
                    "id" to linesId,
                    "parameters" to emptyMap<String, Any>(),
                    "request_body" to null,
                ),
            )
        // The tool's input schema (object or array of objects) refuses a null before the tool runs; the tool's own
        // decoder refuses it too (invalid_root, PipelineExecuteToolRequestBodyTest) for a transport that does not.
        withClue("pipelines_execute null body: $payload") {
            isError shouldBe true
            payload.asText() shouldContain "/request_body"
        }
        before.forEach { (pipelineId, count) -> withClue(pipelineId) { executionCount(pipelineId) shouldBe count } }
        lines().shouldBeEmpty()

        // A scalar-only pipeline still runs with no body at all.
        val plain = executeStream(plainId, """{"parameters":{}}""")
        withClue(describe(plain)) { plain.last().first shouldBe "data_ready" }
    }

    @Test
    fun `a transformer failure fails the run before any business write and names no supplied value`() {
        prepare()
        val pipelineId = linesPipeline(JAVASCRIPT)
        truncate()

        val events =
            executeStream(pipelineId, """{"parameters":{},"request_body":[{"sku":"F-1","qty":1},{"sku":"F-2","qty":"secret-many"}]}""")

        withClue(describe(events)) { events.lifecycleNames().last() shouldBe "pipeline_failed" }
        lines().shouldBeEmpty()
        events.joinToString { it.second.toString() } shouldNotContain "secret-many"
    }

    @Test
    fun `a value-mode body reader feeds a downstream bind, and the same key reused with another fixture is a different request`() {
        prepare()
        val countId = countPipeline()
        val events = executeStream(countId, """{"parameters":{},"request_body":{"lines":[{"sku":"A"},{"sku":"B"},{"sku":"C"}]}}""")
        withClue(describe(events)) { events.last().first shouldBe "data_ready" }
        resultOf(events.first().second["execution_id"].asText())["rows"][0][0].asLong() shouldBe 3L

        val linesId = linesPipeline(JSONATA)
        truncate()
        val key = "k1038-${UUID.randomUUID()}"
        val before = executionCount(linesId)
        val first = executeStream(linesId, """{"parameters":{},"request_body":{"sku":"K-1","qty":1}}""", key)
        withClue(describe(first)) { first.last().first shouldBe "data_ready" }
        // Member order is immaterial to the fingerprint: the same request attaches to the same execution.
        val replay = executeStream(linesId, """{"request_body":{"qty":1,"sku":"K-1"},"parameters":{}}""", key)
        replay.first().second["execution_id"].asText() shouldBe first.first().second["execution_id"].asText()

        val reused = post(linesId, """{"parameters":{},"request_body":{"sku":"K-2","qty":1}}""", key)
        withClue(reused.body().asString()) {
            reused.statusCode() shouldBe 409
            reused.jsonPath().getString("error.code") shouldBe "idempotency.key_reused_for_different_request"
        }
        lines().map { it.sku } shouldBe listOf("K-1")
        executionCount(linesId) shouldBe before + 1
    }

    // ------------------------------------------------------------ pipelines

    private fun linesPipeline(language: String): String {
        val template = "test/body1038_lines.$language"
        createScriptTemplate(template, if (language == JSONATA) LINES_JSONATA else LINES_JAVASCRIPT, linesContract(), language)
        createSqlTemplate(INSERT_TEMPLATE, "POSTGRES", INSERT_LINE)
        val shape =
            mapOf(
                "id" to "shape",
                "description" to "Turns the request body into order lines",
                "type" to "SCRIPT",
                "template" to mapOf("id" to template, "version" to 1),
                "inputs" to mapOf("order" to "@request_body"),
                "output" to mapOf("target" to "tempdb", "table" to "lines_in"),
                "depends_on" to emptyList<String>(),
            )
        val insert =
            mapOf(
                "id" to "insert",
                "description" to "Writes the lines",
                "type" to "DML",
                "source" to DATASOURCE,
                "template" to mapOf("id" to INSERT_TEMPLATE, "version" to 1),
                "operation" to "insert",
                "key" to mapOf("column" to "line_id", "type" to "INTEGER"),
                "input" to mapOf("table" to "lines_in"),
                "output" to mapOf("target" to "caller"),
                "depends_on" to listOf("shape"),
            )
        return createPipeline("test/body1038_lines_$language", listOf(shape, insert), mapOf("required" to true))
    }

    /** A value-mode reader writes `line_count`; a tempdb DQL binds it as the caller. */
    private fun countPipeline(): String {
        createScriptTemplate(COUNT_TEMPLATE, COUNT_JSONATA, countContract(), JSONATA)
        createSqlTemplate(READ_COUNT_TEMPLATE, "H2", """SELECT CAST(:line_count AS INTEGER) AS "n"""")
        val count =
            mapOf(
                "id" to "count",
                "description" to "Counts the body's lines",
                "type" to "SCRIPT",
                "template" to mapOf("id" to COUNT_TEMPLATE, "version" to 1),
                "inputs" to mapOf("order" to "@request_body"),
                "context_key" to "line_count",
                "depends_on" to emptyList<String>(),
            )
        val read =
            mapOf(
                "id" to "read",
                "description" to "Binds :line_count",
                "type" to "DQL",
                "source" to "tempdb",
                "template" to mapOf("id" to READ_COUNT_TEMPLATE, "version" to 1),
                "output" to mapOf("target" to "caller"),
                "depends_on" to listOf("count"),
            )
        return createPipeline("test/body1038_count", listOf(count, read), mapOf("required" to false))
    }

    /** A pipeline that declares no request body. */
    private fun plainPipeline(): String {
        createSqlTemplate(PLAIN_TEMPLATE, "H2", """SELECT 1 AS "one"""")
        val read =
            mapOf(
                "id" to "read",
                "description" to "One row",
                "type" to "DQL",
                "source" to "tempdb",
                "template" to mapOf("id" to PLAIN_TEMPLATE, "version" to 1),
                "output" to mapOf("target" to "caller"),
                "depends_on" to emptyList<String>(),
            )
        return createPipeline("test/body1038_plain", listOf(read), null)
    }

    private fun linesContract() =
        mapOf(
            "mode" to "table",
            "inputs" to mapOf("order" to mapOf("kind" to "body")),
            "output" to
                mapOf(
                    "kind" to "table",
                    "columns" to
                        listOf(
                            mapOf("name" to "sku", "type" to "STRING"),
                            mapOf("name" to "qty", "type" to "INTEGER"),
                            mapOf("name" to "ext_id", "type" to "STRING", "nullable" to true),
                            mapOf("name" to "ext_number", "type" to "STRING", "nullable" to true),
                        ),
                ),
        )

    private fun countContract() =
        mapOf(
            "mode" to "value",
            "inputs" to mapOf("order" to mapOf("kind" to "body")),
            "output" to mapOf("kind" to "value", "type" to "INTEGER"),
        )

    // ------------------------------------------------------------ fixtures

    private fun prepare() {
        ensureAuthSeeded()
        registerDatasource()
        seed(
            "CREATE TABLE IF NOT EXISTS body_lines " +
                "(line_id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, sku text NOT NULL, qty int NOT NULL, " +
                "ext_id text, ext_number text)",
        )
    }

    private fun truncate() = seed("TRUNCATE body_lines RESTART IDENTITY")

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
                {"name": "$DATASOURCE", "display_name": "Body 1038", "dialect": "POSTGRES",
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
                // The MCP key's own `service` identity (keys v2 A13); the key acts as its creator's member.
                statement.execute(
                    "INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin, kind) VALUES " +
                        "('$KEY_IDENTITY', '${MCP_KEY.id.lowercase()}@keys.invalid', '${MCP_KEY.name}', 'key', " +
                        "'${MCP_KEY.id}', TRUE, FALSE, 'service') ON CONFLICT (id) DO NOTHING",
                )
            }
            connection
                .prepareStatement(
                    "INSERT INTO api_keys (id, user_id, created_by, name, key_hash, workspace_id, kind, role)" +
                        " VALUES (?, ?, ?, ?, ?, ?, 'mcp', 'workspace_admin') ON CONFLICT (id) DO NOTHING",
                ).use { ps ->
                    ps.setString(1, MCP_KEY.id)
                    ps.setObject(2, UUID.fromString(KEY_IDENTITY))
                    ps.setObject(3, UUID.fromString(ADMIN_USER_ID))
                    ps.setString(4, MCP_KEY.name)
                    ps.setString(5, MCP_KEY.hash)
                    ps.setObject(6, UUID.fromString(DEFAULT_WORKSPACE))
                    ps.executeUpdate()
                }
        }
    }

    /** Creates (over MCP) and releases (as the session) a script template once per context. */
    private fun createScriptTemplate(
        id: String,
        body: String,
        contract: Map<String, Any?>,
        language: String,
    ) {
        synchronized(createdTemplates) { if (!createdTemplates.add(id)) return }
        val emptyCase = mapOf("name" to "empty body", "input" to mapOf("inputs" to mapOf("order" to emptyList<Any>())))
        val expectEmpty = if (contract["mode"] == "value") 0 else emptyList<Any>()
        val (payload, isError) =
            callTool(
                "templates_create",
                mapOf(
                    "id" to id,
                    "display_name" to id,
                    "description" to "#1038 body transformer",
                    "type" to language,
                    "body" to body,
                    "contract" to contract,
                    "invariants" to emptyList<Any>(),
                    "tests" to listOf(emptyCase + mapOf("expect" to mapOf("output" to expectEmpty))),
                ),
            )
        withClue("templates_create $id: $payload") { isError shouldBe false }
        release(id, payload["body_hash"].asText())
    }

    private fun createSqlTemplate(
        id: String,
        dialect: String,
        body: String,
    ) {
        synchronized(createdTemplates) { if (!createdTemplates.add(id)) return }
        val hash =
            given()
                .port(port)
                .contentType(ContentType.JSON)
                .asSession(ADMIN_SESSION)
                .body(
                    mapper.writeValueAsString(
                        mapOf(
                            "id" to id,
                            "dialect" to dialect,
                            "display_name" to id,
                            "description" to "#1038 template",
                            "imports" to emptyList<Any>(),
                            "body" to body,
                        ),
                    ),
                ).`when`()
                .post("/api/v1/templates")
                .then()
                .statusCode(201)
                .extract()
                .jsonPath()
                .getString("data.body_hash")
        release(id, hash)
    }

    private fun release(
        id: String,
        hash: String,
    ) {
        given()
            .port(port)
            .asSession(ADMIN_SESSION)
            .header("If-Match", hash)
            .contentType(ContentType.JSON)
            .body("""{"name": "$id"}""")
            .`when`()
            .post("/api/v1/templates/release")
            .then()
            .statusCode(200)
    }

    private fun createPipeline(
        name: String,
        nodes: List<Map<String, Any?>>,
        requestBody: Map<String, Any?>?,
    ): String {
        synchronized(createdPipelines) { createdPipelines[name]?.let { return it } }
        val document =
            mapOf(
                "schema_version" to 1,
                "name" to name,
                "display_name" to "Body 1038 $name",
                "description" to "#1038 structured request body",
                "parameters" to emptyMap<String, Any>(),
                "nodes" to nodes,
            ) + (requestBody?.let { mapOf("request_body" to it) } ?: emptyMap())
        val response =
            given()
                .port(port)
                .contentType(ContentType.JSON)
                .asSession(ADMIN_SESSION)
                .body(mapper.writeValueAsString(document))
                .`when`()
                .post("/api/v1/pipelines")
                .thenReturn()
        if (response.statusCode() != 201) {
            throw AssertionError("Pipeline '$name' creation failed (${response.statusCode()}): ${response.body().asString()}")
        }
        return response.jsonPath().getString("data.id").also { synchronized(createdPipelines) { createdPipelines[name] = it } }
    }

    // ------------------------------------------------------------ calls

    /** The raw text goes on the wire as written — a duplicate member must reach the server as a duplicate. */
    private fun post(
        pipelineId: String,
        rawBody: String,
        idempotencyKey: String? = null,
    ): Response {
        val spec =
            given()
                .port(port)
                .asSession(ADMIN_SESSION)
                .contentType(ContentType.JSON)
                .body(rawBody)
        idempotencyKey?.let { spec.header("Idempotency-Key", it) }
        return spec.`when`().post("/api/v1/pipelines/$pipelineId/execute").thenReturn()
    }

    private fun executeStream(
        pipelineId: String,
        rawBody: String,
        idempotencyKey: String? = null,
    ): List<Pair<String, JsonNode>> =
        assertTimeoutPreemptively(Duration.ofMinutes(SSE_BUDGET_MINUTES)) {
            val builder =
                HttpRequest
                    .newBuilder(URI.create("http://localhost:$port/api/v1/pipelines/$pipelineId/execute"))
                    .header("Cookie", E2eSession.cookieHeader(ADMIN_SESSION))
                    .header(E2eSession.CSRF_HEADER, E2eSession.CSRF_TOKEN)
                    .header("DP-Correlation-Id", UUID.randomUUID().toString())
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
            idempotencyKey?.let { builder.header("Idempotency-Key", it) }
            val response =
                http.send(
                    builder.POST(HttpRequest.BodyPublishers.ofString(rawBody)).build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
            withClue(response.body().take(400)) { response.statusCode() shouldBe 200 }
            E2eSse.parseEvents(response.body(), mapper)
        }

    private fun callTool(
        name: String,
        arguments: Map<String, Any?>,
    ): Pair<JsonNode, Boolean> {
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port/mcp"))
                .header("DP-API-Key", MCP_KEY.plaintext)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        mapper.writeValueAsString(
                            mapOf(
                                "jsonrpc" to "2.0",
                                "id" to mcpCounter++,
                                "method" to "tools/call",
                                "params" to mapOf("name" to name, "arguments" to arguments),
                            ),
                        ),
                    ),
                ).build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        response.statusCode() shouldBe 200
        val body = mapper.readTree(response.body())
        withClue("JSON-RPC error on $name: ${response.body()}") { body.has("error") shouldBe false }
        val result = body["result"]
        val text = result["content"][0]["text"].asText()
        // A refusal the transport makes before the tool runs (input-schema validation) is plain text, not JSON.
        val payload = runCatching { mapper.readTree(text) }.getOrElse { mapper.nodeFactory.textNode(text) }
        return payload to result.path("isError").asBoolean(false)
    }

    // ------------------------------------------------------------ reads (a connection of this test's own)

    private data class Line(
        val sku: String,
        val qty: Int,
        val extId: String?,
    )

    private data class StoredLine(
        val sku: String,
        val qty: Int,
        val extId: String?,
        val extNumber: String?,
    )

    private fun lines(): List<StoredLine> =
        DriverManager.getConnection(db.jdbcUrl, db.username, db.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT sku, qty, ext_id, ext_number FROM body_lines ORDER BY line_id").use { rs ->
                    generateSequence {
                        if (rs.next()) {
                            StoredLine(
                                rs.getString(1),
                                rs.getInt(2),
                                rs.getString(3),
                                rs.getString(4),
                            )
                        } else {
                            null
                        }
                    }.toList()
                }
            }
        }

    private fun executionCount(pipelineId: String): Long =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.prepareStatement("SELECT count(*) FROM pipeline_executions WHERE pipeline_id = ?::uuid").use { statement ->
                statement.setString(1, pipelineId)
                statement.executeQuery().use { rs ->
                    rs.next()
                    rs.getLong(1)
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

    companion object {
        private const val DATASOURCE = "body1038"
        private const val SSE_BUDGET_MINUTES = 2L
        private const val JSONATA = "jsonata"
        private const val JAVASCRIPT = "javascript"
        private const val BODY_MAX_BYTES = 4096
        private const val BOUND_MARGIN = 16

        /** 2^53 + 1 as a string, and what JavaScript's Number makes of it. */
        private const val EXACT_ID = "9007199254740993"
        private const val ROUNDED_BY_NUMBER = "9007199254740992"

        private const val ORDER_OBJECT =
            """{"order_no":"O-1","lines":[{"sku":"A-1","qty":2},{"sku":"B-2","qty":1,"ext_id":"$EXACT_ID"}],"meta":{"channel":"web"}}"""
        private const val HETEROGENEOUS =
            """[{"sku":"C-3","qty":4},{"comment":"no sku","tags":["x"]},{"sku":"D-4","qty":5,"ext_id":"$EXACT_ID","extra":{"k":null}}]"""

        private const val INSERT_TEMPLATE = "test/body1038_insert_line.sql"
        private const val COUNT_TEMPLATE = "test/body1038_count.jsonata"
        private const val READ_COUNT_TEMPLATE = "test/body1038_read_count.sql"
        private const val PLAIN_TEMPLATE = "test/body1038_plain.sql"

        private const val INSERT_LINE =
            "INSERT INTO body_lines (sku, qty, ext_id, ext_number) " +
                "VALUES (CAST(:sku AS text), CAST(:qty AS int), CAST(:ext_id AS text), CAST(:ext_number AS text))"

        /** Lines from an order object, every element of a root array that carries a sku, or a singleton line. */
        private const val LINES_JSONATA =
            """
            (${'$'}o := inputs.order;
             ${'$'}items := ${'$'}type(${'$'}o) = "array" ? ${'$'}o[${'$'}exists(sku)]
               : (${'$'}exists(${'$'}o.lines) ? ${'$'}o.lines : (${'$'}exists(${'$'}o.sku) ? [${'$'}o] : []));
             [${'$'}items.{"sku": sku, "qty": qty, "ext_id": ${'$'}exists(ext_id) ? ext_id : null, "ext_number": null}])
            """

        private const val LINES_JAVASCRIPT =
            """
            const o = input.inputs.order;
            const items = Array.isArray(o) ? o.filter(e => e.sku !== undefined)
              : Array.isArray(o.lines) ? o.lines : (o.sku !== undefined ? [o] : []);
            return items.map(e => ({sku: e.sku, qty: e.qty,
              ext_id: e.ext_id === undefined ? null : e.ext_id,
              ext_number: e.ext_id === undefined ? null : String(Number(e.ext_id))}));
            """

        private const val COUNT_JSONATA =
            """${'$'}count(${'$'}type(inputs.order) = "array" ? inputs.order : inputs.order.lines)"""

        private const val DEFAULT_WORKSPACE = "defa0000-0000-0000-0000-000000000001"
        private val ADMIN_USER_ID: String = UUID.randomUUID().toString()
        private val KEY_IDENTITY: String = UUID.randomUUID().toString()
        private const val ADMIN_EMAIL = "e2e-body1038@datapipelines.test"
        private val ADMIN_SESSION get() = E2eSession.jwt(JWT_SECRET, ADMIN_USER_ID, ADMIN_EMAIL)
        private val JWT_SECRET = E2eSession.newSecret()
        private val MCP_KEY = E2eAuth.generateKey("e2e-1038-body-key")

        private val postgres get() = SharedE2e.postgres
        private val db = SharedE2e.scratchDatabase("body1038")
        private val redis get() = SharedE2e.redis
        private val oidc = OidcDiscoveryStub()
        private val createdTemplates = mutableSetOf<String>()
        private val createdPipelines = mutableMapOf<String, String>()
        private var mcpCounter = 1

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
            // A small byte cap so the over-cap refusal is cheap; framework execute and pipelines_execute read it.
            registry.add("datapipelines.endpoints.body-max-bytes") { BODY_MAX_BYTES.toString() }
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
