package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import co.datapipelines.integration.E2eSession.asSession
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.hamcrest.Matchers
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.assertTimeoutPreemptively
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import software.amazon.awssdk.services.s3.S3Client
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.sql.DriverManager
import java.time.Duration
import java.util.Base64
import java.util.UUID

/**
 * The SCRIPT node end to end (7c, #7; the design record's §10.9): a Postgres scratch table
 * and a MinIO lake table staged by two DQL nodes → a `row`-mode jsonata SCRIPT with rejects
 * (one row rejected on purpose) → a `value`-mode SCRIPT writing `:threshold` → a SQL node
 * binding `:threshold` → a `table`-mode SCRIPT as the caller node → a published endpoint
 * serving its rows through the existing contract (`schema`/`rows`/paging).
 *
 * The negative twins over REST and MCP: `strict: true` fails the run with
 * `pipeline.script.rejects_strict`, a false invariant with `pipeline.script.invariant_failed`,
 * R4's object key bound by SQL refused at save, R5 refused at save, and `pipelines_execute_node`
 * refused with `use: templates_evaluate`. The draft leg closes 7b's deferred Check B case: a
 * draft pipeline pinning a draft script is refused `template_unrendered` until
 * `templates_evaluate` — the evaluate counts as the render.
 *
 * REST legs run as a signed-in session (#215 B2); only `/mcp` legs use the seeded MCP key.
 */
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@Suppress("LargeClass") // One ordered lake/staging fixture shared by the two language walks.
class ScriptNodeE2eTest {
    @LocalServerPort
    private var port: Int = 0

    private val mapper = ObjectMapper()
    private val http: HttpClient = HttpClient.newHttpClient()

    @Test
    @Order(1)
    fun `the pipeline runs - row mode with rejects, the threshold bind, the table-mode caller`() {
        seedAll()
        registerDatasources()
        createSqlTemplates()
        // The row-mode script and the value script, created and evaluated over MCP (7b's
        // verbs) so the Check B leg below has an evaluate on record for them.
        createScriptTemplate(SHAPE_TEMPLATE, SHAPE_BODY, shapeContract(), shapeInvariants(), shapeTests())
        createScriptTemplate(THRESHOLD_TEMPLATE, THRESHOLD_BODY, thresholdContract(), emptyList(), thresholdTests())
        createScriptTemplate(SUMMARIZE_TEMPLATE, SUMMARIZE_BODY, summarizeContract(), emptyList(), summarizeTests())
        listOf(SHAPE_TEMPLATE, THRESHOLD_TEMPLATE, SUMMARIZE_TEMPLATE).forEach { evaluateTemplate(it) }

        val created =
            createPipeline(
                "test/script_node",
                mapOf(
                    "schema_version" to 1,
                    "name" to "test/script_node",
                    "display_name" to "Script Node E2E",
                    "description" to "7c E2E",
                    "parameters" to emptyMap<String, Any>(),
                    "nodes" to mainNodes(strict = false),
                ),
                expectedStatus = 201,
            )
        mainPipelineId = created

        val events = execute(mainPipelineId(), emptyMap())
        events.last().first shouldBe "data_ready"
        val executionId = events.last().second["execution_id"].asText()

        // The caller is the table-mode SCRIPT: the lake rows whose fare clears the computed
        // threshold (30.00, the max order amount), each flagged by the function.
        val expectedRows = (0 until ICEBERG_ROWS).count { it % 97 >= 30 }
        val result = resultOf(executionId)
        result["total_rows"].asLong() shouldBe expectedRows.toLong()
        result["schema"].map { it["name"].asText() } shouldBe listOf("id", "fare", "company", "flag")
        result["rows"][0][3].asText() shouldBe "big"

        // The script nodes report what they did (record §5.5's stats).
        val stats = nodeStats(executionId)
        val shape = stats.single { it["node_id"].asText() == "shape_orders" }
        shape["status"].asText() shouldBe "SUCCESS"
        shape["rows_in"].asLong() shouldBe 4
        shape["rows_out"].asLong() shouldBe 3
        shape["rows_rejected"].asLong() shouldBe 1
        shape["invariants_checked"].asInt() shouldBe 1
        val threshold = stats.single { it["node_id"].asText() == "threshold" }
        threshold["context_key"].asText() shouldBe "threshold"
        threshold["context_value"].asText() shouldBe "30.00"
        threshold["provided_by"] shouldBe null
    }

    @Test
    @Order(2)
    fun `a caller-supplied threshold skips the value node with provided_by caller`() {
        val events = execute(mainPipelineId(), mapOf("threshold" to 96.5))
        events.last().first shouldBe "data_ready"
        val executionId = events.last().second["execution_id"].asText()

        // 96.5 is the fixture's top fare — exactly one row clears it (id % 97 == 96).
        resultOf(executionId)["total_rows"].asLong() shouldBe (0 until ICEBERG_ROWS).count { it % 97 >= 96 }.toLong()
        val threshold = nodeStats(executionId).single { it["node_id"].asText() == "threshold" }
        threshold["provided_by"].asText() shouldBe "caller"
        threshold["context_value"].asText() shouldBe "96.5"
    }

    @Test
    @Order(3)
    fun `the published endpoint serves the script caller's rows through the existing contract`() {
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .asSession(ADMIN_SESSION)
            .header("If-Match", mainPipelineHash)
            .`when`()
            .post("/api/v1/pipelines/${mainPipelineId()}/release")
            .then()
            .log()
            .ifValidationFails()
            .statusCode(200)

        given()
            .port(port)
            .contentType(ContentType.JSON)
            .asSession(ADMIN_SESSION)
            .body("""{"path": "/script/v1/summary", "pipeline": "test/script_node"}""")
            .`when`()
            .post("/api/v1/endpoints")
            .then()
            .statusCode(201)

        val keyResponse =
            given()
                .port(port)
                .contentType(ContentType.JSON)
                .asSession(ADMIN_SESSION)
                .body("""{"name": "script-serving", "kind": "endpoint", "bindings": ["/script"]}""")
                .`when`()
                .post("/api/v1/auth/api-keys")
                .then()
                .statusCode(201)
                .extract()
        val key = keyResponse.jsonPath().getString("data.key")

        val expectedRows = (0 until ICEBERG_ROWS).count { it % 97 >= 30 }
        given()
            .port(port)
            .header("DP-API-Key", key)
            .`when`()
            .get("/api/script/v1/summary")
            .then()
            .statusCode(200)
            .body("schema.size()", Matchers.equalTo(4))
            // The first page is the page-size cap; the TOTAL is the script's row count.
            // That split IS the existing endpoint contract (schema / rows / paging).
            .body("row_count", Matchers.equalTo(1000))
            .body("total_rows", Matchers.equalTo(expectedRows))
            .body("has_more", Matchers.equalTo(true))
            .body("result_url", Matchers.containsString("/executions/"))
    }

    @Test
    @Order(4)
    fun `strict fails the run with rejects_strict`() {
        val strictPipelineId =
            createPipeline(
                "test/script_strict",
                mapOf(
                    "schema_version" to 1,
                    "name" to "test/script_strict",
                    "display_name" to "Script Strict E2E",
                    "description" to "7c E2E",
                    "parameters" to emptyMap<String, Any>(),
                    "nodes" to mainNodes(strict = true),
                ),
                expectedStatus = 201,
            )

        val events = execute(strictPipelineId, emptyMap())
        events.last().first shouldBe "pipeline_failed"
        val failed = events.filter { it.first == "node_failed" }.single().second
        withClue("the node_failed event: $failed") {
            failed["error"]["code"].asText() shouldBe "pipeline.script.rejects_strict"
        }
    }

    @Test
    @Order(5)
    fun `a false invariant fails the run with invariant_failed`() {
        // The invariant is true on the template's own (empty) test case — 7b's suite gates the
        // save — and false on the real data, which is exactly §5.4's run-time verdict.
        createScriptTemplate(
            BAD_INVARIANT_TEMPLATE,
            SHAPE_BODY,
            shapeContract(),
            listOf(
                mapOf("name" to "always_empty", "expr" to "${'$'}count(rows) = 0", "message" to "no accepted rows — false on real data"),
            ),
            listOf(
                mapOf(
                    "name" to "empty input",
                    "input" to mapOf("rows" to emptyList<Any>(), "inputs" to emptyMap<String, Any>()),
                    "expect" to mapOf("output" to mapOf("rows" to emptyList<Any>(), "rejects" to emptyList<Any>())),
                ),
            ),
        )
        val pipelineId =
            createPipeline(
                "test/script_invariant",
                mapOf(
                    "schema_version" to 1,
                    "name" to "test/script_invariant",
                    "display_name" to "Script Invariant E2E",
                    "description" to "7c E2E",
                    "parameters" to emptyMap<String, Any>(),
                    "nodes" to mainNodes(strict = false, shapeTemplate = BAD_INVARIANT_TEMPLATE),
                ),
                expectedStatus = 201,
            )

        val events = execute(pipelineId, emptyMap())
        events.last().first shouldBe "pipeline_failed"
        val failed = events.filter { it.first == "node_failed" }.single().second
        withClue("the node_failed event: $failed") {
            failed["error"]["code"].asText() shouldBe "pipeline.script.invariant_failed"
            failed["error"]["details"]["invariant"].asText() shouldBe "always_empty"
        }
    }

    @Test
    @Order(6)
    fun `an object key bound by SQL is refused at save - R4`() {
        createScriptTemplate(WRAP_TEMPLATE, WRAP_BODY, wrapContract(), emptyList(), wrapTestCases())

        createPipeline(
            "test/script_object_key",
            objectKeyPipelineBody(),
            expectedStatus = 400,
            expectedCode = "pipeline.validation.script_object_key_bound",
        )
    }

    @Test
    @Order(7)
    fun `a rejects contract on a caller node is refused at save - R5`() {
        createPipeline(
            "test/script_rejects_caller",
            mapOf(
                "schema_version" to 1,
                "name" to "test/script_rejects_caller",
                "display_name" to "Script Rejects Caller E2E",
                "description" to "7c E2E",
                "parameters" to emptyMap<String, Any>(),
                "nodes" to
                    listOf(
                        stageOrdersNode(),
                        mapOf(
                            "id" to "shape_orders",
                            "description" to "rejects contract with a caller output — R5 refuses this",
                            "type" to "SCRIPT",
                            "template" to mapOf("id" to SHAPE_TEMPLATE, "version" to 1),
                            "inputs" to mapOf("orders" to "stg_orders"),
                            "output" to mapOf("target" to "caller"),
                            "depends_on" to listOf("stage_orders"),
                        ),
                    ),
            ),
            expectedStatus = 400,
            expectedCode = "pipeline.validation.script_rejects_on_caller",
        )
    }

    @Test
    @Order(8)
    fun `pipelines_execute_node refuses the SCRIPT node, naming templates_evaluate`() {
        val (payload, isError) =
            callTool(80, "pipelines_execute_node", mapOf("pipeline_id" to mainPipelineId(), "node_id" to "shape_orders"))

        isError shouldBe true
        payload["error"]["code"].asText() shouldBe "pipeline.node.standalone_execution_refused"
        payload["error"]["details"]["reason"].asText() shouldBe "script_node"
        payload["error"]["details"]["use"].asText() shouldBe "templates_evaluate"
    }

    @Test
    @Order(9)
    fun `Check B - a draft pipeline pinning a draft script needs the evaluate, and it is enough`() {
        // 7b's deferred case: the draft script has never been rendered, so the draft
        // pipeline pinning it cannot execute over MCP until templates_evaluate — which is the
        // render for a script (record §9.1).
        createScriptTemplate(DRAFT_TEMPLATE, SHAPE_BODY, shapeContract(), emptyList(), shapeTests(), release = false)
        // The stage template rides RELEASED, so the script draft is the only draft pin in play.
        callTool(91, "datasources_get_columns", mapOf("name" to SCRATCH_DS, "table" to "orders"))
        val (_, createError) =
            callTool(
                92,
                "pipelines_create",
                mapOf(
                    "name" to "test/script_check_b",
                    "display_name" to "Script Check B E2E",
                    "nodes" to
                        listOf(
                            stageOrdersNode(),
                            mapOf(
                                "id" to "shape_orders",
                                "description" to "draft script pin",
                                "type" to "SCRIPT",
                                "template" to mapOf("id" to DRAFT_TEMPLATE, "version" to 1),
                                "inputs" to mapOf("orders" to "stg_orders"),
                                "output" to mapOf("target" to "tempdb", "table" to "order_lines", "rejects" to "order_lines_rejected"),
                                "depends_on" to listOf("stage_orders"),
                            ),
                        ),
                ),
            )
        createError shouldBe false

        val draftPipeline =
            given()
                .port(port)
                .asSession(ADMIN_SESSION)
                .`when`()
                .get("/api/v1/pipelines?q=script_check_b")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getString("data.items[0].id")

        val (refused, refusedError) =
            callTool(
                93,
                "pipelines_execute",
                mapOf("id" to draftPipeline, "parameters" to emptyMap<String, Any>()),
            )
        refusedError shouldBe true
        refused["error"]["code"].asText() shouldBe "pipeline.execution.template_unrendered"

        evaluateTemplate(DRAFT_TEMPLATE)

        val (executed, executeError) =
            callTool(
                95,
                "pipelines_execute",
                mapOf(
                    "id" to draftPipeline,
                    "parameters" to emptyMap<String, Any>(),
                ),
            )
        withClue("the draft executes after templates_evaluate: $executed") { executeError shouldBe false }
        executed["status"].asText() shouldBe "SUCCESS"
    }

    @Test
    @Order(10)
    fun `the 260 shape - a jsonata script splitting 2000 ranked rows into terciles - runs end to end`() {
        // #260's reporting agent, end to end: a SQL node ranks every lake row, a jsonata
        // script splits them into thirds with a per-item block (outer assignment +
        // per-item block + multi-field object constructor — the exact shape that leaked
        // one depth unit per item under the library's Timebox and refused at ~100 rows
        // with pipeline.script.resource_limit).
        val rankHash =
            createSqlTemplate(
                RANK_ZONES_TEMPLATE,
                "LAKE",
                "SELECT id, ROW_NUMBER() OVER (ORDER BY fare DESC, id)::INT AS trip_rank FROM trips",
            )
        releaseTemplate(RANK_ZONES_TEMPLATE, rankHash)
        createScriptTemplate(TERCILES_TEMPLATE, TERCILES_BODY, tercilesContract(), emptyList(), tercilesTests())

        val tercilePipeline =
            createPipeline(
                "test/script_terciles",
                mapOf(
                    "schema_version" to 1,
                    "name" to "test/script_terciles",
                    "display_name" to "Script Terciles E2E",
                    "description" to "#260 E2E",
                    "parameters" to emptyMap<String, Any>(),
                    "nodes" to tercileNodes(),
                ),
                expectedStatus = 201,
            )

        val events = execute(tercilePipeline, emptyMap())
        withClue("the run's events: ${events.last()}") { events.last().first shouldBe "data_ready" }
        val executionId = events.last().second["execution_id"].asText()

        // The default result page is 1000 rows; the leg wants every row it ranked.
        val full =
            given()
                .port(port)
                .asSession(ADMIN_SESSION)
                .queryParam("offset", 0)
                .queryParam("limit", ICEBERG_ROWS)
                .`when`()
                .get("/api/v1/executions/$executionId/result")
                .then()
                .statusCode(200)
                .extract()
                .body()
                .asString()
        val result = mapper.readTree(full)["data"]
        result["total_rows"].asLong() shouldBe ICEBERG_ROWS.toLong()
        result["schema"].map { it["name"].asText() } shouldBe listOf("id", "trip_rank", "volume_class")
        // 2000 rows: ranks 1..667 top, 668..1334 middle, 1335..2000 bottom.
        result["rows"][0][1].asInt() shouldBe 1
        result["rows"][0][2].asText() shouldBe "top"
        result["rows"][667][2].asText() shouldBe "middle"
        result["rows"][1334][2].asText() shouldBe "bottom"
        result["rows"][1999][2].asText() shouldBe "bottom"

        val terciles = nodeStats(executionId).single { it["node_id"].asText() == "terciles" }
        terciles["status"].asText() shouldBe "SUCCESS"
        terciles["rows_in"].asLong() shouldBe ICEBERG_ROWS.toLong()
        terciles["rows_out"].asLong() shouldBe ICEBERG_ROWS.toLong()
    }

    @Test
    @Order(11)
    fun `a javascript script splits 2000 ranked lake rows into terciles through the real pool`() {
        val started = System.nanoTime()
        val jsTemplate = "test/it_terciles.javascript"
        val jsBody =
            """
            const ranked = input.inputs.ranked;
            const third = Math.ceil(ranked.length / 3);
            console.log("ranked " + ranked.length + " rows");
            return ranked.map(row => ({id: row.id, trip_rank: row.trip_rank,
              volume_class: row.trip_rank <= third ? "top" : row.trip_rank <= third * 2 ? "middle" : "bottom"}));
            """.trimIndent()
        createScriptTemplate(jsTemplate, jsBody, tercilesContract(), emptyList(), tercilesTests(), language = "javascript")

        val tercilePipeline =
            createPipeline(
                "test/script_terciles_js",
                mapOf(
                    "schema_version" to 1,
                    "name" to "test/script_terciles_js",
                    "display_name" to "Script Terciles E2E",
                    "description" to "#260 E2E",
                    "parameters" to emptyMap<String, Any>(),
                    "nodes" to tercileNodes(jsTemplate),
                ),
                expectedStatus = 201,
            )

        val events = execute(tercilePipeline, emptyMap())
        withClue("the run's events: ${events.last()}") { events.last().first shouldBe "data_ready" }
        val executionId = events.last().second["execution_id"].asText()

        // The default result page is 1000 rows; the leg wants every row it ranked.
        val full =
            given()
                .port(port)
                .asSession(ADMIN_SESSION)
                .queryParam("offset", 0)
                .queryParam("limit", ICEBERG_ROWS)
                .`when`()
                .get("/api/v1/executions/$executionId/result")
                .then()
                .statusCode(200)
                .extract()
                .body()
                .asString()
        val result = mapper.readTree(full)["data"]
        result["total_rows"].asLong() shouldBe ICEBERG_ROWS.toLong()
        result["schema"].map { it["name"].asText() } shouldBe listOf("id", "trip_rank", "volume_class")
        // 2000 rows: ranks 1..667 top, 668..1334 middle, 1335..2000 bottom.
        result["rows"][0][1].asInt() shouldBe 1
        result["rows"][0][2].asText() shouldBe "top"
        result["rows"][667][2].asText() shouldBe "middle"
        result["rows"][1334][2].asText() shouldBe "bottom"
        result["rows"][1999][2].asText() shouldBe "bottom"

        val terciles = nodeStats(executionId).single { it["node_id"].asText() == "terciles" }
        terciles["status"].asText() shouldBe "SUCCESS"
        terciles["rows_in"].asLong() shouldBe ICEBERG_ROWS.toLong()
        terciles["rows_out"].asLong() shouldBe ICEBERG_ROWS.toLong()
        println("8b JavaScript terciles: rows=2000 elapsed_ms=" + (System.nanoTime() - started) / 1_000_000)
    }

    @Test
    @Order(12)
    fun `javascript save parses only then runs the mandatory empty case and both evaluate surfaces return console`() {
        val contract = shapeContract()
        val body =
            """
            console.log("<img src=x onerror=alert(1)>");
            return {rows: input.rows.filter(row => row.customer_id != null).map(row => ({
              order_id: row.order_id, amount: row.amount_cents / 100, customer_id: row.customer_id
            })), rejects: input.rows.filter(row => row.customer_id == null).map(row => ({row: row, reason: "customer_id missing"}))};
            """.trimIndent()
        val id = "test/js_shape.javascript"
        createScriptTemplate(id, body, contract, shapeInvariants(), shapeTests(), release = false, language = "javascript")
        val input =
            mapOf(
                "rows" to listOf(mapOf("order_id" to 1, "amount_cents" to 1250, "customer_id" to null)),
                "inputs" to emptyMap<String, Any>(),
            )
        val (mcp, error) = callTool(nextMcpId(), "templates_evaluate", mapOf("id" to id, "input" to input))
        withClue(mcp.toString()) { error shouldBe false }
        mcp["rejects"].size() shouldBe 1
        mcp["invariants"][0]["passed"].asBoolean() shouldBe true
        mcp["console"][0]["level"].asText() shouldBe "log"
        mcp["console"][0]["text"].asText() shouldBe "<img src=x onerror=alert(1)>"
        val rest = evaluateRest(id, input, 200)
        rest["console"] shouldBe mcp["console"]
        println("8b REST console response: $rest")
        println("8b MCP console response: $mcp")

        val base =
            mapOf(
                "display_name" to "JS refusal",
                "description" to "save-time cases",
                "type" to "javascript",
                "contract" to contract,
                "invariants" to emptyList<Any>(),
            )

        fun save(
            suffix: String,
            statements: String,
            cases: List<Map<String, Any?>>,
        ): Pair<JsonNode, Boolean> =
            callTool(
                nextMcpId(),
                "templates_create",
                base + mapOf("id" to "test/js_$suffix.javascript", "body" to statements, "tests" to cases),
            )
        val (noCases, noCasesError) = save("no_cases", "const a = 1;", emptyList())
        noCasesError shouldBe true
        noCases.toString().contains("empty_case_missing") shouldBe true
        val (noReturn, noReturnError) = save("no_return", "const a = 1;", shapeTests().take(1))
        noReturnError shouldBe true
        noReturn["error"]["code"].asText() shouldBe "template.test_failed"
        noReturn.toString().contains("main returned nothing") shouldBe true
        println("8b pooled no-return refusal: $noReturn")
        val (badExpect, badExpectError) = save("bad_expect", body, shapeTests().take(1).map { it + ("expect" to mapOf("output" to 42)) })
        badExpectError shouldBe true
        badExpect.toString().contains("template.test_failed") shouldBe true
        val (syntax, syntaxError) = save("syntax", "if (x {", shapeTests())
        syntaxError shouldBe true
        syntax["error"]["details"]["failures"][0]["details"]["line"].asInt() shouldBe 1
        syntax["error"]["details"]["failures"][0]["details"]["column"].asInt() shouldBe 7
        syntax.toString().contains("template.validation.syntax_error") shouldBe true
        println("8b author-relative syntax refusal: $syntax")
    }

    @Test
    @Order(13)
    fun `a javascript failure retains console and its pool slot accepts the next evaluation`() {
        val id = "test/js_failure.javascript"
        val body = "console.warn('before failure'); throw new Error('failure witness');"
        val cases =
            listOf(
                mapOf(
                    "name" to "empty input",
                    "input" to mapOf("rows" to emptyList<Any>(), "inputs" to emptyMap<String, Any>()),
                    "expect" to mapOf("refusal" to "pipeline.script.evaluation_failed"),
                ),
            )
        createScriptTemplate(id, body, shapeContract(), emptyList(), cases, release = false, language = "javascript")
        val refused = evaluateRest(id, mapOf("rows" to emptyList<Any>(), "inputs" to emptyMap<String, Any>()), 500)
        refused["details"]["console"][0]["text"].asText() shouldBe "before failure"
        val (mcp, error) =
            callTool(
                nextMcpId(),
                "templates_evaluate",
                mapOf(
                    "id" to id,
                    "input" to mapOf("rows" to emptyList<Any>(), "inputs" to emptyMap<String, Any>()),
                ),
            )
        error shouldBe true
        withClue(mcp.toString()) { mcp.toString().contains("before failure") shouldBe true }
        evaluateRest(
            "test/js_shape.javascript",
            mapOf("rows" to emptyList<Any>(), "inputs" to emptyMap<String, Any>()),
            200,
        )["console"].size() shouldBe
            1
    }

    @Test
    @Order(14)
    fun `javascript resource refusals name the kind on REST and MCP, retain console and release the one-slot pool`() {
        val input = mapOf("rows" to emptyList<Any>(), "inputs" to emptyMap<String, Any>())
        val large = "x".repeat(1_048_576)
        for ((kind, body) in listOf(
            "STATEMENTS" to "console.log('before limit'); while(true){}",
            "OUTPUT" to "console.log('before limit'); console.log('x'.repeat(2097152)); return input.rows;",
            "RESULT_BYTES" to "console.log('before limit'); return 'x'.repeat(1048577);",
            "INPUT_BYTES" to "console.log('before limit'); return input.rows;",
        )) {
            val id = "test/js_limit_" + kind.lowercase()
            val expected =
                if (kind ==
                    "INPUT_BYTES"
                ) {
                    mapOf("output" to emptyList<Any>())
                } else {
                    mapOf("refusal" to "pipeline.script.resource_limit")
                }
            val cases = listOf(mapOf("name" to "empty input", "input" to input, "expect" to expected))
            val columns = listOf(mapOf("name" to "text", "type" to "STRING"))
            val contract =
                mapOf(
                    "mode" to "row",
                    "inputs" to mapOf("data" to mapOf("kind" to "table", "columns" to columns)),
                    "output" to mapOf("kind" to "table", "columns" to columns),
                )
            createScriptTemplate(id, body, contract, emptyList(), cases, release = false, language = "javascript")
            val evaluationInput = if (kind == "INPUT_BYTES") input + ("rows" to listOf(mapOf("text" to large))) else input
            val started = System.nanoTime()
            val rest = evaluateRest(id, evaluationInput, 500)
            rest["code"].asText() shouldBe "pipeline.script.resource_limit"
            rest["details"]["kind"].asText() shouldBe kind
            rest["message"].asText().contains(kind) shouldBe true
            if (kind != "INPUT_BYTES") rest["details"]["console"][0]["text"].asText() shouldBe "before limit"
            if (kind == "STATEMENTS") {
                withClue("statement refusal took " + (System.nanoTime() - started) / 1_000_000 + " ms") {
                    ((System.nanoTime() - started) < Duration.ofSeconds(1).toNanos()) shouldBe true
                }
            }
            val (mcp, error) = callTool(nextMcpId(), "templates_evaluate", mapOf("id" to id, "input" to evaluationInput))
            error shouldBe true
            mcp["error"]["code"].asText() shouldBe "pipeline.script.resource_limit"
            mcp["error"]["details"]["kind"].asText() shouldBe kind
            evaluateRest("test/js_shape.javascript", input, 200)["console"].size() shouldBe 1
            println("8b resource kind=$kind REST=$rest")
        }
    }

    @Test
    @Order(15)
    fun `javascript console preserves order, truncates long lines and reports dropped lines`() {
        val id = "test/js_console_caps"
        val body = "for (let i=0;i<201;i++) console.log(i); console.warn('x'.repeat(3000)); return {rows:[], rejects:[]};"
        val input = mapOf("rows" to emptyList<Any>(), "inputs" to emptyMap<String, Any>())
        createScriptTemplate(id, body, shapeContract(), emptyList(), shapeTests().take(1), release = false, language = "javascript")
        val console = evaluateRest(id, input, 200)["console"]
        console.size() shouldBe 201
        console[0]["text"].asText() shouldBe "0"
        console[199]["text"].asText() shouldBe "199"
        console[200]["text"].asText() shouldBe "… 2 more lines dropped"
        val longId = "test/js_console_long"
        createScriptTemplate(
            longId,
            "console.warn('x'.repeat(3000)); return {rows:[], rejects:[]};",
            shapeContract(),
            emptyList(),
            shapeTests().take(1),
            release = false,
            language = "javascript",
        )
        val longLine = evaluateRest(longId, input, 200)["console"][0]
        longLine["level"].asText() shouldBe "warn"
        longLine["text"].asText().length shouldBe 2000
    }

    @Test
    @Order(16)
    fun `javascript row and table nodes preserve a large integer and exact decimal digits across real staging`() {
        val source = seedNumericSource()
        val columns = numericColumns()
        val output = columns + listOf("id_kind", "money_kind", "wide_kind").map { mapOf("name" to it, "type" to "STRING") }
        for (mode in listOf("row", "table")) {
            val id = "test/js_numeric_$mode"
            val rows = if (mode == "row") "input.rows" else "input.inputs.numbers"
            val body =
                "return $rows.map(row => ({id: row.id, money: row.money, wide: row.wide, " +
                    "id_kind: typeof row.id, money_kind: typeof row.money, wide_kind: typeof row.wide}));"
            val contract =
                mapOf(
                    "mode" to mode,
                    "inputs" to mapOf("numbers" to mapOf("kind" to "table", "columns" to columns)),
                    "output" to mapOf("kind" to "table", "columns" to output),
                )
            val input =
                if (mode == "row") {
                    mapOf("rows" to emptyList<Any>(), "inputs" to emptyMap<String, Any>())
                } else {
                    mapOf("inputs" to mapOf("numbers" to emptyList<Any>()))
                }
            val cases = listOf(mapOf("name" to "empty input", "input" to input, "expect" to mapOf("output" to emptyList<Any>())))
            createScriptTemplate(id, body, contract, emptyList(), cases, language = "javascript")
            val nodes = numericNodes(source, id)
            val pipeline =
                createPipeline(
                    "test/js_numeric_$mode",
                    mapOf(
                        "schema_version" to 1,
                        "name" to "test/js_numeric_$mode",
                        "display_name" to "JavaScript numeric $mode",
                        "description" to "precision",
                        "parameters" to emptyMap<String, Any>(),
                        "nodes" to nodes,
                    ),
                    201,
                )
            val events = execute(pipeline, emptyMap())
            withClue(events.last().toString()) { events.last().first shouldBe "data_ready" }
            val result = resultOf(events.last().second["execution_id"].asText())
            val row = result["rows"][0]
            row[0].asText() shouldBe "9007199254740993"
            row[1].decimalValue().compareTo(java.math.BigDecimal("10.50")) shouldBe 0
            row[2].asText() shouldBe "12345678901234567890"
            row[3].asText() shouldBe "string"
            row[4].asText() shouldBe "number"
            row[5].asText() shouldBe "string"
            println("8b numeric mode=$mode row=$row")
        }
    }

    private fun seedNumericSource(): String {
        val source = "test/js_numeric_source.sql"
        val hash =
            createSqlTemplate(
                source,
                "H2",
                """
                SELECT CAST(9007199254740993 AS BIGINT) AS "id",
                  CAST(10.50 AS DECIMAL(4,2)) AS "money", CAST(12345678901234567890 AS DECIMAL(20,0)) AS "wide"
                """.trimIndent(),
            )
        releaseTemplate(source, hash)
        return source
    }

    private fun numericColumns(): List<Map<String, Any>> =
        listOf(
            mapOf("name" to "id", "type" to "BIGINTEGER"),
            mapOf("name" to "money", "type" to "DECIMAL", "precision" to 4, "scale" to 2),
            mapOf("name" to "wide", "type" to "BIGDECIMAL", "precision" to 20, "scale" to 0),
        )

    private fun numericNodes(
        source: String,
        id: String,
    ): List<Map<String, Any>> =
        listOf(
            mapOf(
                "id" to "numbers",
                "type" to "DQL",
                "description" to "numeric source",
                "source" to "tempdb",
                "template" to mapOf("id" to source, "version" to 1),
                "output" to mapOf("target" to "tempdb", "table" to "numbers"),
                "depends_on" to emptyList<String>(),
            ),
            mapOf(
                "id" to "identity",
                "type" to "SCRIPT",
                "description" to "identity",
                "template" to mapOf("id" to id, "version" to 1),
                "inputs" to mapOf("numbers" to "numbers"),
                "output" to mapOf("target" to "caller"),
                "depends_on" to listOf("numbers"),
            ),
        )

    private fun evaluateRest(
        id: String,
        input: Map<String, Any?>,
        status: Int,
    ): JsonNode {
        val response =
            given()
                .port(port)
                .contentType(ContentType.JSON)
                .asSession(ADMIN_SESSION)
                .body(mapOf("name" to id, "input" to input))
                .post("/api/v1/templates/evaluate")
                .then()
                .statusCode(status)
                .extract()
                .asString()
        return mapper.readTree(response)[if (status == 200) "data" else "error"]
    }

    // ------------------------------------------------------------ the graph

    // ------------------------------------------------------------ fixture builders

    private fun registerDatasources() {
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .asSession(ADMIN_SESSION)
            .body(
                """
                {"name": "$SCRATCH_DS", "display_name": "Script IT scratch", "dialect": "POSTGRES",
                 "jdbc_url": "${scratch.jdbcUrl}", "username": "${scratch.username}", "password": "${scratch.password}"}
                """.trimIndent(),
            ).`when`()
            .post("/api/v1/datasources")
            .then()
            .log()
            .ifValidationFails()
            .statusCode(201)

        given()
            .port(port)
            .contentType(ContentType.JSON)
            .asSession(ADMIN_SESSION)
            .body(
                """
                {"name": "$LAKE_DS", "display_name": "Script IT lake (MinIO)", "dialect": "LAKE",
                 "jdbc_url": "jdbc:duckdb::memory:", "readonly": true,
                 "credential": {"kind": "password", "username": "${SharedE2e.MINIO_USER}", "secret": "${SharedE2e.MINIO_PASSWORD}"},
                 "properties": {"dialect": {"catalog.kind": "s3", "region": "us-east-1",
                   "endpoint": "${SharedE2e.minioEndpoint}", "url_style": "path"}}}
                """.trimIndent(),
            ).`when`()
            .post("/api/v1/datasources")
            .then()
            .log()
            .ifValidationFails()
            .statusCode(201)

        given()
            .port(port)
            .contentType(ContentType.JSON)
            .asSession(ADMIN_SESSION)
            .body(
                """
                {"namespace": ["test", "lake"], "name": "trips", "format": "iceberg",
                 "location": "s3://$BUCKET/iceberg/trips_iceberg/metadata/$icebergMetadataFile"}
                """.trimIndent(),
            ).`when`()
            .post("/api/v1/datasources/$LAKE_DS/tables")
            .then()
            .log()
            .ifValidationFails()
            .statusCode(201)
    }

    private fun createSqlTemplates() {
        val stageHash =
            createSqlTemplate(STAGE_ORDERS_TEMPLATE, "POSTGRES", "SELECT order_id, amount_cents, customer_id FROM orders ORDER BY order_id")
        val tripsHash = createSqlTemplate(STAGE_TRIPS_TEMPLATE, "LAKE", "SELECT id, fare, company FROM trips")
        val bigHash =
            createSqlTemplate(BIG_TRIPS_TEMPLATE, "H2", "SELECT id, fare, company FROM stg_trips WHERE fare >= :threshold ORDER BY id")
        val bindHash = createSqlTemplate(BIND_PAYLOAD_TEMPLATE, "H2", "SELECT * FROM order_lines WHERE customer_id = :payload")
        // The pins ride RELEASED so a pipeline release is possible and the script draft is
        // the only draft pin in the Check B leg (Order 9).
        listOf(
            STAGE_ORDERS_TEMPLATE to stageHash,
            STAGE_TRIPS_TEMPLATE to tripsHash,
            BIG_TRIPS_TEMPLATE to bigHash,
            BIND_PAYLOAD_TEMPLATE to bindHash,
        ).forEach { (id, hash) -> releaseTemplate(id, hash) }
    }

    private fun releaseTemplate(
        id: String,
        hash: String,
    ) {
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .asSession(ADMIN_SESSION)
            .header("If-Match", hash)
            .body("""{"name": "$id"}""")
            .`when`()
            .post("/api/v1/templates/release")
            .then()
            .log()
            .ifValidationFails()
            .statusCode(200)
    }

    private fun createSqlTemplate(
        id: String,
        dialect: String,
        body: String,
    ): String =
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .asSession(ADMIN_SESSION)
            .body(
                """
                {"id": "$id", "dialect": "$dialect", "display_name": "$id",
                 "description": "7c E2E", "imports": [],
                 "body": ${mapper.writeValueAsString(body)}}
                """.trimIndent(),
            ).`when`()
            .post("/api/v1/templates")
            .then()
            .log()
            .ifValidationFails()
            .statusCode(201)
            .extract()
            .jsonPath()
            .getString("data.body_hash")

    // ------------------------------------------------------------ HTTP helpers

    private fun createScriptTemplate(
        id: String,
        body: String,
        contract: Map<String, Any?>,
        invariants: List<Map<String, Any?>>,
        tests: List<Map<String, Any?>>,
        release: Boolean = true,
        language: String = "jsonata",
    ) {
        val (payload, isError) =
            callTool(
                nextMcpId(),
                "templates_create",
                mapOf(
                    "id" to id,
                    "display_name" to id,
                    "description" to "7c E2E script",
                    "type" to language,
                    "body" to body,
                    "contract" to contract,
                    "invariants" to invariants,
                    "tests" to tests,
                ),
            )
        withClue("templates_create $id failed: $payload") { isError shouldBe false }
        if (release) releaseTemplate(id, payload["body_hash"].asText())
    }

    private fun evaluateTemplate(id: String) {
        val (payload, isError) =
            callTool(
                nextMcpId(),
                "templates_evaluate",
                mapOf(
                    "id" to id,
                    "input" to mapOf("rows" to emptyList<Any>(), "inputs" to emptyMap<String, Any>()),
                ),
            )
        withClue("templates_evaluate $id failed: $payload") { isError shouldBe false }
    }

    private fun createPipeline(
        displayName: String,
        body: Map<String, Any?>,
        expectedStatus: Int,
        expectedCode: String? = null,
    ): String {
        val response =
            given()
                .port(port)
                .contentType(ContentType.JSON)
                .asSession(ADMIN_SESSION)
                .body(mapper.writeValueAsString(body))
                .`when`()
                .post("/api/v1/pipelines")
                .thenReturn()
        if (response.statusCode() != expectedStatus) {
            throw AssertionError(
                "Pipeline $displayName: expected $expectedStatus but was ${response.statusCode()}: ${response.body().asString()}",
            )
        }
        if (expectedCode != null) {
            val error = mapper.readTree(response.body().asString())["error"]
            withClue("the refusal code: ${response.body().asString()}") {
                error["code"].asText() shouldBe expectedCode
            }
            return ""
        }
        if (displayName == "test/script_node") mainPipelineHash = response.jsonPath().getString("data.body_hash")
        return response.jsonPath().getString("data.id")
    }

    private fun execute(
        pipelineId: String,
        parameters: Map<String, Any>,
    ): List<Pair<String, JsonNode>> =
        assertTimeoutPreemptively(EXECUTION_BUDGET) {
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
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            response.statusCode() shouldBe 200
            E2eSse.parseEvents(response.body(), mapper)
        }

    private fun resultOf(executionId: String): JsonNode {
        val body =
            given()
                .port(port)
                .asSession(ADMIN_SESSION)
                .`when`()
                .get("/api/v1/executions/$executionId/result")
                .then()
                .statusCode(200)
                .extract()
                .body()
                .asString()
        return mapper.readTree(body)["data"]
    }

    private fun nodeStats(executionId: String): List<JsonNode> {
        val body =
            given()
                .port(port)
                .asSession(ADMIN_SESSION)
                .`when`()
                .get("/api/v1/executions/$executionId")
                .then()
                .statusCode(200)
                .extract()
                .body()
                .asString()
        return mapper.readTree(body)["data"]["node_stats"].toList()
    }

    private fun mainPipelineId(): String = requireNotNull(mainPipelineId) { "Order(1) creates the pipeline this leg reads" }

    // ------------------------------------------------------------ MCP (the key's legs)

    private fun mcp(
        id: Int,
        method: String,
        params: Map<String, Any?>,
    ): JsonNode {
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port/mcp"))
                .header("DP-API-Key", ADMIN_KEY.plaintext)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        mapper.writeValueAsString(
                            mapOf("jsonrpc" to "2.0", "id" to id, "method" to method, "params" to params),
                        ),
                    ),
                ).build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        response.statusCode() shouldBe 200
        val body = mapper.readTree(response.body())
        withClue("JSON-RPC error on $method: ${response.body()}") { body.has("error") shouldBe false }
        return body["result"]
    }

    private fun callTool(
        id: Int,
        name: String,
        arguments: Map<String, Any?>,
    ): Pair<JsonNode, Boolean> {
        val result = mcp(id, "tools/call", mapOf("name" to name, "arguments" to arguments))
        val text = result["content"][0]["text"].asText()
        val payload =
            try {
                mapper.readTree(text)
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                throw AssertionError("tools/call $name returned non-JSON text: ${text.take(400)}", e)
            }
        return payload to result.path("isError").asBoolean(false)
    }

    private fun nextMcpId(): Int = mcpCounter++

    // ------------------------------------------------------------ seeds

    companion object {
        /** The fixture's own bucket: the checked-in Iceberg metadata's absolute `s3://dp-lake-it/…`
         * URIs resolve only there (LakeMinioE2eTest's note). Uploading the same bytes is idempotent. */
        private const val BUCKET = "dp-lake-it"
        private const val ICEBERG_ROWS = 2_000

        private const val BAD_INVARIANT_TEMPLATE = "test/it_shape_bad.jsonata"

        private const val DRAFT_TEMPLATE = "test/it_shape_draft.jsonata"

        private const val SHAPE_BODY =
            """{
              "rows": [ rows[customer_id != null].{
                "order_id": order_id, "amount": amount_cents / 100, "customer_id": customer_id } ],
              "rejects": [ rows[customer_id = null].{ "row": ${'$'}, "reason": "customer_id missing" } ]
            }"""

        private const val THRESHOLD_BODY =
            """(${ '$' }m := ${ '$' }max(inputs.lines.amount); ${ '$' }m ? ${ '$' }m : 0)"""

        private const val SUMMARIZE_BODY =
            """[ inputs.trips.{ "id": id, "fare": fare, "company": company, "flag": "big" } ]"""

        private val EXECUTION_BUDGET: Duration = Duration.ofSeconds(180)

        private const val WORKSPACE_ID = "defa0000-0000-0000-0000-000000000001"
        private const val ADMIN_USER_ID = "a11e0000-0000-0000-0000-000000000007"

        /** The key's own `service` identity (keys v2 A13). */
        private const val KEY_IDENTITY = "a11e0000-0000-0000-0000-000000000008"

        private val random = SecureRandom()
        private val SECRET = E2eSession.newSecret()
        private val ADMIN_SESSION get() = E2eSession.jwt(SECRET, ADMIN_USER_ID, "e2e-script@datapipelines.test")
        private val ADMIN_KEY = E2eAuth.generateKey("e2e-7c-script-key")

        private val oidc = OidcDiscoveryStub()
        private var s3: S3Client? = null
        private var stagingDir: Path? = null
        private var mcpCounter = 60

        /** The fixture's current metadata file name, discovered by [seedAll]. */
        private lateinit var icebergMetadataFile: String

        private var mainPipelineId: String? = null
        private var mainPipelineHash: String? = null

        private val postgres get() = SharedE2e.postgres
        private val redis get() = SharedE2e.redis
        private val scratch = SharedE2e.scratchDatabase("script_it")

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("management.server.port") { "0" }
            registry.add("datapipelines.script.pool-size") { "1" }
            registry.add("datapipelines.script.pool-queue") { "1" }
            registry.add("datapipelines.script.evaluate-timeout-seconds") { "2" }
            registry.add("datapipelines.script.js.max-input-mb") { "1" }
            registry.add("datapipelines.script.js.max-result-mb") { "1" }

            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
            registry.add("datapipelines.redis.password") { "" }
            registry.add("datapipelines.redis.host") { redis.host }
            registry.add("datapipelines.redis.port") { SharedE2e.redisPort }
            registry.add("datapipelines.jwt.secret") { SECRET }
            registry.add("datapipelines.db.encryption-key") {
                Base64.getEncoder().encodeToString(ByteArray(32).also { random.nextBytes(it) })
            }
            listOf("google", "microsoft").forEachIndexed { index, name ->
                registry.add("datapipelines.auth.oidc.providers[$index].name") { name }
                registry.add("datapipelines.auth.oidc.providers[$index].client-id") { "test-$name-client-id" }
                registry.add("datapipelines.auth.oidc.providers[$index].client-secret") { "test-$name-client-secret" }
                registry.add("datapipelines.auth.oidc.providers[$index].issuer-uri") { oidc.issuer }
                registry.add("datapipelines.auth.oidc.providers[$index].display-name") { "Test $name" }
            }
            registry.add("datapipelines.auth.base-url") { "http://localhost:8080" }
        }

        /** The whole seed — called from Order(1), when a context (and Flyway) exists. */
        @Suppress("LongMethod") // the seed IS the fixture: identities, keys, datasources, scratch tables in one block
        private fun seedAll() {
            E2eClean.beforeSeeding()
            // The session user (workspace admin), its membership, and the MCP key (#215: the key
            // acts as its member, capped at author).
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin) VALUES
                            ('$ADMIN_USER_ID', 'e2e-script@datapipelines.test', 'E2E Script', 'test',
                             'e2e-script-sub', TRUE, TRUE)
                        """.trimIndent(),
                    )
                    statement.execute(
                        "INSERT INTO workspace_members (workspace_id, user_id, role)" +
                            " VALUES ('$WORKSPACE_ID', '$ADMIN_USER_ID', 'workspace_admin')",
                    )
                    // The key's own identity (keys v2 A13).
                    statement.execute(
                        "INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin, kind) VALUES " +
                            "('$KEY_IDENTITY', '${ADMIN_KEY.id.lowercase()}@keys.invalid', '${ADMIN_KEY.name}', 'key', " +
                            "'${ADMIN_KEY.id}', TRUE, FALSE, 'service')",
                    )
                }
                connection
                    .prepareStatement(
                        "INSERT INTO api_keys (id, user_id, created_by, name, key_hash, workspace_id, kind, role)" +
                            " VALUES (?, ?, ?, ?, ?, ?, 'mcp', 'workspace_admin')",
                    ).use { ps ->
                        ps.setString(1, ADMIN_KEY.id)
                        ps.setObject(2, UUID.fromString(KEY_IDENTITY))
                        ps.setObject(3, UUID.fromString(ADMIN_USER_ID))
                        ps.setString(4, ADMIN_KEY.name)
                        ps.setString(5, ADMIN_KEY.hash)
                        ps.setObject(6, UUID.fromString(WORKSPACE_ID))
                        ps.executeUpdate()
                    }
            }
            // The scratch orders: one missing customer (rejected on purpose).
            DriverManager.getConnection(scratch.jdbcUrl, scratch.username, scratch.password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        "CREATE TABLE orders (order_id INT PRIMARY KEY, amount_cents INT NOT NULL, customer_id VARCHAR(64))",
                    )
                    statement.execute("INSERT INTO orders VALUES (1, 1250, 'alice'), (2, 800, NULL), (3, 3000, 'bob'), (4, 450, 'carol')")
                }
            }
            // The lake: the checked-in Iceberg fixture, uploaded key-for-key (its metadata's
            // absolute s3:// URIs resolve by construction), registered through the app in Order(1).
            val dir = Files.createTempDirectory("script-lake-it")
            stagingDir = dir
            val client = SharedE2e.s3Client()
            s3 = client
            // LakeMinioE2eTest shares the bucket with this suite (same fixture, same bytes);
            // whichever runs first creates it.
            runCatching { client.createBucket { it.bucket(BUCKET) } }
            val fixture =
                Path.of(
                    checkNotNull(ScriptNodeE2eTest::class.java.getResource("/lake-fixture/iceberg")).toURI(),
                )
            uploadTree(client, fixture, "iceberg")
            // The current metadata file's name (the registration needs the FILE, not the root —
            // DuckDB cannot scan a pyiceberg table by its root); the zero-padded prefix makes
            // the lexicographically last metadata.json the newest.
            icebergMetadataFile =
                Files
                    .list(fixture.resolve("trips_iceberg/metadata"))
                    .use { stream -> stream.map { it.fileName.toString() }.toList() }
                    .filter { it.endsWith(".metadata.json") }
                    .maxOrNull() ?: error("the lake-fixture Iceberg table carries no metadata file")
        }

        /** Uploads [root] under [prefix], key for key — LakeMinioE2eTest's own helper shape. */
        private fun uploadTree(
            client: S3Client,
            root: Path,
            prefix: String,
        ) {
            Files.walk(root).use { paths ->
                paths
                    .filter { Files.isRegularFile(it) }
                    .forEach { file ->
                        client.putObject(
                            { it.bucket(BUCKET).key("$prefix/" + root.relativize(file).toString().replace('\\', '/')) },
                            file,
                        )
                    }
            }
        }

        @JvmStatic
        @AfterAll
        fun tearDown() {
            s3?.close()
            stagingDir?.toFile()?.deleteRecursively()
            oidc.close()
        }
    }
}

// ---------------------------------------------------------------- fixtures, file-level

// The pure fixture builders and the template/datasource names they read live outside the
// spec class: the class sat well over detekt's LargeClass threshold (600 lines of code,
// companion excluded) and the relocation is byte-identical - no assertion moved. The
// network-facing helpers stay members; they read the suite's clients and sessions.

private const val SCRATCH_DS = "it-script-scratch"
private const val LAKE_DS = "it_script_lake"
private const val STAGE_ORDERS_TEMPLATE = "test/it_stage_orders.sql"
private const val STAGE_TRIPS_TEMPLATE = "test/it_stage_trips.sql"
private const val THRESHOLD_TEMPLATE = "test/it_threshold.jsonata"
private const val BIG_TRIPS_TEMPLATE = "test/it_big_trips.sql"
private const val SUMMARIZE_TEMPLATE = "test/it_summarize.jsonata"
private const val SHAPE_TEMPLATE = "test/it_shape.jsonata"
private const val BIND_PAYLOAD_TEMPLATE = "test/it_bind_payload.sql"
private const val WRAP_TEMPLATE = "test/it_wrap.jsonata"
private const val RANK_ZONES_TEMPLATE = "test/it_rank_zones.sql"
private const val TERCILES_TEMPLATE = "test/it_terciles.jsonata"

/** #260's reporting agent body, adapted to the E2E's ranked-trips columns. */
private const val TERCILES_BODY =
    """( ${'$'}n := ${'$'}count(inputs.ranked); ${'$'}classes := ["top","middle","bottom"]; """ +
        """${'$'}append([], inputs.ranked.( ${'$'}tercile := ${'$'}floor((trip_rank - 1) * 3 / ${'$'}n); """ +
        """{ "id": id, "trip_rank": trip_rank, "volume_class": ${'$'}classes[${'$'}tercile] } )) )"""

private fun tercileNodes(script: String = TERCILES_TEMPLATE): List<Map<String, Any?>> =
    listOf(
        mapOf(
            "id" to "rank_zones",
            "description" to "lake trips ranked by fare",
            "type" to "DQL",
            "source" to LAKE_DS,
            "template" to mapOf("id" to RANK_ZONES_TEMPLATE, "version" to 1),
            "output" to mapOf("target" to "tempdb", "table" to "ranked_trips"),
            "depends_on" to emptyList<String>(),
        ),
        mapOf(
            "id" to "terciles",
            "description" to "terciles by per-item block over the ranked rows (#260)",
            "type" to "SCRIPT",
            "template" to mapOf("id" to script, "version" to 1),
            "inputs" to mapOf("ranked" to "ranked_trips"),
            "output" to mapOf("target" to "caller"),
            "depends_on" to listOf("rank_zones"),
        ),
    )

private fun tercilesContract() =
    mapOf(
        "mode" to "table",
        "inputs" to
            mapOf(
                "ranked" to
                    mapOf(
                        "kind" to "table",
                        "columns" to
                            listOf(
                                mapOf("name" to "id", "type" to "BIGINTEGER"),
                                mapOf("name" to "trip_rank", "type" to "INTEGER"),
                            ),
                    ),
            ),
        "output" to
            mapOf(
                "kind" to "table",
                "columns" to
                    listOf(
                        mapOf("name" to "id", "type" to "BIGINTEGER"),
                        mapOf("name" to "trip_rank", "type" to "INTEGER"),
                        mapOf("name" to "volume_class", "type" to "STRING"),
                    ),
            ),
    )

private fun tercilesTests(): List<Map<String, Any?>> {
    fun row(id: Int): Map<String, Any?> = mapOf("id" to id.toString(), "trip_rank" to id)
    return listOf(
        mapOf(
            "name" to "empty input",
            "input" to mapOf("inputs" to mapOf("ranked" to emptyList<Any>())),
            "expect" to mapOf("output" to emptyList<Any>()),
        ),
        mapOf(
            "name" to "six rows split evenly",
            "input" to mapOf("inputs" to mapOf("ranked" to (1..6).map { row(it) })),
            "expect" to
                mapOf(
                    "output" to
                        (1..6).map { rank ->
                            mapOf(
                                "id" to rank.toString(),
                                "trip_rank" to rank,
                                "volume_class" to
                                    when {
                                        rank <= 2 -> "top"
                                        rank <= 4 -> "middle"
                                        else -> "bottom"
                                    },
                            )
                        },
                ),
        ),
    )
}

private val WRAP_BODY = """{ "total_cents": ${'$'}sum(inputs.orders.amount_cents) }"""

private fun mainNodes(
    strict: Boolean,
    shapeTemplate: String = SHAPE_TEMPLATE,
): List<Map<String, Any?>> =
    listOf(
        stageOrdersNode(),
        mapOf(
            "id" to "stage_trips",
            "description" to "lake trips to tempdb",
            "type" to "DQL",
            "source" to LAKE_DS,
            "template" to mapOf("id" to STAGE_TRIPS_TEMPLATE, "version" to 1),
            "output" to mapOf("target" to "tempdb", "table" to "stg_trips"),
            "depends_on" to emptyList<String>(),
        ),
        mapOf(
            "id" to "shape_orders",
            "description" to "row mode with rejects",
            "type" to "SCRIPT",
            "template" to mapOf("id" to shapeTemplate, "version" to 1),
            "inputs" to mapOf("orders" to "stg_orders"),
            "output" to mapOf("target" to "tempdb", "table" to "order_lines", "rejects" to "order_lines_rejected"),
            "strict" to strict,
            "depends_on" to listOf("stage_orders"),
        ),
        mapOf(
            "id" to "threshold",
            "description" to "value mode writes :threshold",
            "type" to "SCRIPT",
            "template" to mapOf("id" to THRESHOLD_TEMPLATE, "version" to 1),
            "inputs" to mapOf("lines" to "order_lines"),
            "context_key" to "threshold",
            "depends_on" to listOf("shape_orders"),
        ),
        mapOf(
            "id" to "big_trips",
            "description" to "SQL binds :threshold",
            "type" to "DQL",
            "source" to "tempdb",
            "template" to mapOf("id" to BIG_TRIPS_TEMPLATE, "version" to 1),
            "output" to mapOf("target" to "tempdb", "table" to "big_trips"),
            "depends_on" to listOf("stage_trips", "threshold"),
        ),
        mapOf(
            "id" to "summarize",
            "description" to "table mode as the caller node",
            "type" to "SCRIPT",
            "template" to mapOf("id" to SUMMARIZE_TEMPLATE, "version" to 1),
            "inputs" to mapOf("trips" to "big_trips"),
            "output" to mapOf("target" to "caller"),
            "depends_on" to listOf("big_trips"),
        ),
    )

private fun stageOrdersNode(): Map<String, Any?> =
    mapOf(
        "id" to "stage_orders",
        "description" to "postgres orders to tempdb",
        "type" to "DQL",
        "source" to SCRATCH_DS,
        "template" to mapOf("id" to STAGE_ORDERS_TEMPLATE, "version" to 1),
        "output" to mapOf("target" to "tempdb", "table" to "stg_orders"),
        "depends_on" to emptyList<String>(),
    )

private fun orderColumns() =
    listOf(
        mapOf("name" to "order_id", "type" to "INTEGER"),
        mapOf("name" to "amount_cents", "type" to "INTEGER"),
        mapOf("name" to "customer_id", "type" to "STRING", "nullable" to true),
    )

private fun outColumns() =
    listOf(
        mapOf("name" to "order_id", "type" to "INTEGER"),
        mapOf("name" to "amount", "type" to "DECIMAL", "precision" to 12, "scale" to 2),
        mapOf("name" to "customer_id", "type" to "STRING"),
    )

private fun shapeContract() =
    mapOf(
        "mode" to "row",
        "inputs" to mapOf("orders" to mapOf("kind" to "table", "columns" to orderColumns())),
        "output" to mapOf("kind" to "table", "columns" to outColumns()),
        "rejects" to true,
    )

private fun shapeInvariants() =
    listOf(
        mapOf(
            "name" to "one_to_one",
            "expr" to "${'$'}count(rows) + ${'$'}count(rejects) = ${'$'}count(inputs.orders)",
            "message" to "every input row is accepted or rejected, never lost",
        ),
    )

private fun shapeTests() =
    listOf(
        mapOf(
            "name" to "empty input",
            "input" to mapOf("rows" to emptyList<Any>(), "inputs" to emptyMap<String, Any>()),
            "expect" to mapOf("output" to mapOf("rows" to emptyList<Any>(), "rejects" to emptyList<Any>())),
        ),
        mapOf(
            "name" to "missing customer is rejected",
            "input" to
                mapOf(
                    "rows" to listOf(mapOf("order_id" to 1, "amount_cents" to 1250, "customer_id" to null)),
                    "inputs" to emptyMap<String, Any>(),
                ),
            "expect" to
                mapOf(
                    "output" to
                        mapOf(
                            "rows" to emptyList<Any>(),
                            "rejects" to
                                listOf(
                                    mapOf(
                                        "row" to mapOf("order_id" to 1, "amount_cents" to 1250, "customer_id" to null),
                                        "reason" to "customer_id missing",
                                    ),
                                ),
                        ),
                ),
        ),
    )

private fun thresholdContract() =
    mapOf(
        "mode" to "value",
        "inputs" to mapOf("lines" to mapOf("kind" to "table", "columns" to outColumns())),
        "output" to mapOf("kind" to "value", "type" to "DECIMAL", "precision" to 12, "scale" to 2),
    )

private fun thresholdTests() =
    listOf(
        mapOf(
            "name" to "empty input",
            "input" to mapOf("inputs" to mapOf("lines" to emptyList<Any>())),
            "expect" to mapOf("output" to 0),
        ),
        mapOf(
            "name" to "the max",
            "input" to
                mapOf(
                    "inputs" to
                        mapOf(
                            "lines" to
                                listOf(
                                    mapOf("order_id" to 1, "amount" to 12.5, "customer_id" to "alice"),
                                    mapOf("order_id" to 3, "amount" to 30.0, "customer_id" to "bob"),
                                ),
                        ),
                ),
            "expect" to mapOf("output" to 30.0),
        ),
    )

private fun summarizeContract() =
    mapOf(
        "mode" to "table",
        "inputs" to
            mapOf(
                "trips" to
                    mapOf(
                        "kind" to "table",
                        "columns" to
                            listOf(
                                mapOf("name" to "id", "type" to "BIGINTEGER"),
                                mapOf("name" to "fare", "type" to "DECIMAL", "precision" to 15),
                                mapOf("name" to "company", "type" to "STRING"),
                            ),
                    ),
            ),
        "output" to
            mapOf(
                "kind" to "table",
                "columns" to
                    listOf(
                        mapOf("name" to "id", "type" to "BIGINTEGER"),
                        mapOf("name" to "fare", "type" to "DECIMAL", "precision" to 15),
                        mapOf("name" to "company", "type" to "STRING"),
                        mapOf("name" to "flag", "type" to "STRING"),
                    ),
            ),
    )

private fun summarizeTests() =
    listOf(
        mapOf(
            "name" to "empty input",
            "input" to mapOf("inputs" to mapOf("trips" to emptyList<Any>())),
            "expect" to mapOf("output" to emptyList<Any>()),
        ),
        mapOf(
            "name" to "one trip",
            "input" to
                mapOf(
                    "inputs" to
                        mapOf(
                            "trips" to listOf(mapOf("id" to "7", "fare" to 42.5, "company" to "acme")),
                        ),
                ),
            "expect" to
                mapOf(
                    "output" to listOf(mapOf("id" to "7", "fare" to 42.5, "company" to "acme", "flag" to "big")),
                ),
        ),
    )

private fun wrapContract() =
    mapOf(
        "mode" to "value",
        "inputs" to
            mapOf(
                "orders" to mapOf("kind" to "table", "columns" to orderColumns()),
            ),
        "output" to mapOf("kind" to "object"),
    )

private fun wrapTestCases() =
    listOf(
        mapOf(
            "name" to "empty input",
            "input" to mapOf("inputs" to mapOf("orders" to emptyList<Any>())),
            "expect" to mapOf("output" to emptyMap<String, Any>()),
        ),
        mapOf(
            "name" to "one row",
            "input" to
                mapOf(
                    "inputs" to
                        mapOf(
                            "orders" to listOf(mapOf("order_id" to 1, "amount_cents" to 1250, "customer_id" to "alice")),
                        ),
                ),
            "expect" to mapOf("output" to mapOf("total_cents" to 1250)),
        ),
    )

private fun objectKeyPipelineBody() =
    mapOf(
        "schema_version" to 1,
        "name" to "test/script_object_key",
        "display_name" to "Script Object Key E2E",
        "description" to "7c E2E",
        "parameters" to emptyMap<String, Any>(),
        "nodes" to
            listOf(
                stageOrdersNode(),
                mapOf(
                    "id" to "wrap",
                    "description" to "object output",
                    "type" to "SCRIPT",
                    "template" to mapOf("id" to WRAP_TEMPLATE, "version" to 1),
                    "inputs" to mapOf("orders" to "stg_orders"),
                    "context_key" to "payload",
                    "depends_on" to listOf("stage_orders"),
                ),
                mapOf(
                    "id" to "report",
                    "description" to "a SQL node binding the object key — R4 refuses this",
                    "type" to "DQL",
                    "source" to "tempdb",
                    "template" to mapOf("id" to BIND_PAYLOAD_TEMPLATE, "version" to 1),
                    "output" to mapOf("target" to "caller"),
                    "depends_on" to listOf("wrap"),
                ),
            ),
    )
