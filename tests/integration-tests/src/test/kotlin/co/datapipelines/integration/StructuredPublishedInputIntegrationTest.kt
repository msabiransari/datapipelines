package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import co.datapipelines.integration.E2eSession.asSession
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
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
        // Non-vacuity: two empty tables would compare equal.
        viaFramework.map { Line(it.sku, it.qty, it.extId) } shouldBe listOf(Line("A-1", 2, null), Line("B-2", 1, EXACT_ID))
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

    // ------------------------------------------------------------ round 2: R1 — the raw /mcp fixture contract

    @Test
    fun `a duplicate member in a pipelines_execute request_body is refused at the real mcp endpoint before anything runs`() {
        prepare()
        val pipelineId = linesPipeline(JSONATA)
        truncate()
        val before = executionCount(pipelineId)
        val cases =
            listOf(
                """{"sku":"secret-a","qty":1,"sku":"secret-b"}""",
                """{"lines":[{"sku":"A","qty":1},{"sku":"secret-a","qty":1,"qty":2}]}""",
                """[{"sku":"A","qty":1},{"meta":{"k":"secret-a","k":"secret-b"},"sku":"B","qty":1}]""",
            )
        cases.forEach { body ->
            val response = rawToolCall("pipelines_execute", """{"id":"$pipelineId","parameters":{},"request_body":$body}""")
            withClue("duplicate in $body -> ${response.statusCode()} ${response.body().take(300)}") {
                val refusedAtTransport = response.statusCode() != 200
                val refusedByTool =
                    response.statusCode() == 200 &&
                        mapper
                            .readTree(response.body())
                            .path("result")
                            .path("isError")
                            .asBoolean(false)
                (refusedAtTransport || refusedByTool) shouldBe true
                response.body() shouldNotContain "secret"
            }
        }
        // A repeated request_body member is the same ambiguity one level up.
        val twice =
            rawToolCall(
                "pipelines_execute",
                """{"id":"$pipelineId","parameters":{},"request_body":{"sku":"secret-a"},"request_body":{"sku":"secret-b"}}""",
            )
        withClue("repeated request_body -> ${twice.statusCode()} ${twice.body().take(300)}") {
            (
                twice.statusCode() != 200 ||
                    mapper
                        .readTree(twice.body())
                        .path("result")
                        .path("isError")
                        .asBoolean(false)
            ) shouldBe true
            twice.body() shouldNotContain "secret"
        }
        lines().shouldBeEmpty()
        executionCount(pipelineId) shouldBe before
    }

    @Test
    fun `a fraction needing more than double precision reaches the transformer exactly through the real mcp endpoint`() {
        prepare()
        val pipelineId = pricePipeline()
        val fixture = """{"price":$LONG_FRACTION}"""

        val framework = executeStream(pipelineId, """{"parameters":{},"request_body":$fixture}""")
        withClue(describe(framework)) { framework.last().first shouldBe "data_ready" }
        val viaFramework = resultOf(framework.first().second["execution_id"].asText())["rows"][0][0].asText()

        val response = rawToolCall("pipelines_execute", """{"id":"$pipelineId","parameters":{},"request_body":$fixture}""")
        withClue(response.body().take(400)) { response.statusCode() shouldBe 200 }
        val result = mapper.readTree(response.body())["result"]
        withClue(response.body().take(400)) { result.path("isError").asBoolean(false) shouldBe false }
        val viaMcp = mapper.readTree(result["content"][0]["text"].asText())["rows"][0][0].asText()

        withClue("framework=$viaFramework mcp=$viaMcp") {
            // Non-vacuity: the framework run is itself exact, so equality below is not two lossy doubles agreeing.
            viaFramework shouldBe LONG_FRACTION
            viaMcp shouldBe viaFramework
        }
    }

    @Test
    fun `every body shape reaches both transformers the same way through framework execute and the real mcp endpoint`() {
        prepare()
        val fixtures =
            listOf(
                ORDER_OBJECT to 2,
                HETEROGENEOUS to 2,
                """{"sku":"E-5","qty":7}""" to 1,
                "{}" to 0,
                "[]" to 0,
            )
        for (language in listOf(JSONATA, JAVASCRIPT)) {
            val pipelineId = linesPipeline(language)
            fixtures.forEach { (fixture, expectedRows) ->
                truncate()
                val framework = executeStream(pipelineId, """{"parameters":{},"request_body":$fixture}""")
                withClue("$language $fixture ${describe(framework)}") { framework.last().first shouldBe "data_ready" }
                val viaFramework = lines()
                viaFramework.size shouldBe expectedRows

                truncate()
                val response = rawToolCall("pipelines_execute", """{"id":"$pipelineId","parameters":{},"request_body":$fixture}""")
                val result = mapper.readTree(response.body())["result"]
                withClue("$language $fixture mcp ${response.body().take(300)}") { result.path("isError").asBoolean(false) shouldBe false }
                withClue("$language $fixture") { lines() shouldBe viaFramework }
            }
        }
    }

    // ------------------------------------------------------------ round 2: R2 — the shared budget on authored fixtures

    @Test
    fun `authored body fixtures obey the shared depth and byte budget through REST and MCP evaluation and the release test fixtures`() {
        prepare()
        linesPipeline(JSONATA)
        val template = "test/body1038_lines.$JSONATA"
        val atDepth = nested(BODY_MAX_DEPTH)
        val pastDepth = nested(BODY_MAX_DEPTH + 1)
        val pastBytes = """{"note":"secret-${"x".repeat(BODY_MAX_BYTES)}"}"""

        fun rest(order: String): Response =
            given()
                .port(port)
                .asSession(ADMIN_SESSION)
                .contentType(ContentType.JSON)
                .body("""{"name":"$template","input":{"inputs":{"order":$order}}}""")
                .`when`()
                .post("/api/v1/templates/evaluate")
                .thenReturn()

        fun mcp(order: String): Pair<JsonNode, Boolean> =
            callTool("templates_evaluate", mapOf("id" to template, "input" to mapOf("inputs" to mapOf("order" to mapper.readTree(order)))))

        // The configured depth is inside the budget on every authored surface.
        rest(atDepth).statusCode() shouldBe 200
        mcp(atDepth).second shouldBe false
        // Depth 33 and an over-byte fixture are refused on both, with the same code, naming no value.
        listOf(pastDepth to "too_deep", pastBytes to "too_many_bytes").forEach { (order, reason) ->
            val response = rest(order)
            withClue("REST $reason ${response.body().asString().take(300)}") {
                // The catalogued status of the code (pipeline-contract 13.18), the one every script input refusal has.
                response.statusCode() shouldNotBe 200
                response.jsonPath().getString("error.code") shouldBe "pipeline.script.input_contract_violation"
                response.body().asString() shouldContain reason
                response.body().asString() shouldNotContain "secret"
            }
            val (payload, isError) = mcp(order)
            withClue("MCP $reason $payload") {
                isError shouldBe true
                payload.toString() shouldContain reason
                payload.toString() shouldNotContain "secret"
            }
        }

        // The release/test fixtures: a case at the budget passes the save-time run, a case past it makes the save refuse.
        fun create(
            id: String,
            order: String,
        ) = callTool(
            "templates_create",
            mapOf(
                "id" to id,
                "display_name" to id,
                "description" to "#1038 fixture budget",
                "type" to JSONATA,
                "body" to LINES_JSONATA,
                "contract" to linesContract(),
                "invariants" to emptyList<Any>(),
                "tests" to
                    listOf(
                        mapOf(
                            "name" to "nested",
                            "input" to mapOf("inputs" to mapOf("order" to mapper.readTree(order))),
                            "expect" to mapOf("output" to emptyList<Any>()),
                        ),
                    ),
            ),
        )
        val (accepted, acceptedError) = create("test/body1038_fixture_ok.jsonata", atDepth)
        withClue("fixture at depth $BODY_MAX_DEPTH: $accepted") { acceptedError shouldBe false }
        val (refused, refusedError) = create("test/body1038_fixture_deep.jsonata", pastDepth)
        withClue("fixture past the depth: $refused") {
            refusedError shouldBe true
            refused.toString() shouldContain "too_deep"
        }
    }

    // ------------------------------------------------------------ round 2: R3 — default diagnostics

    @Test
    fun `a failing body execution discloses codes and metadata only, on every surface that carries a failure`() {
        prepare()
        val pipelineId = failPipeline()
        truncate()
        val cases =
            listOf(
                """{"boom":true,"secret":"$CANARY_BODY"}""" to "pipeline.script.evaluation_failed",
                """{"row":true,"secret":"$CANARY_BODY"}""" to "pipeline.script.invariant_failed",
                """{"reject":true,"secret":"$CANARY_BODY"}""" to "pipeline.script.rejects_strict",
            )
        cases.forEach { (fixture, code) ->
            val key = "k1038f-${UUID.randomUUID()}"
            val events = executeStream(pipelineId, """{"parameters":{},"request_body":$fixture}""", key)
            val executionId = events.first().second["execution_id"].asText()
            val replay = executeStream(pipelineId, """{"parameters":{},"request_body":$fixture}""", key)
            val surfaces =
                mapOf(
                    "live stream" to events.joinToString { it.second.toString() },
                    "replayed stream" to replay.joinToString { it.second.toString() },
                    "stored rows" to storedRows(executionId),
                    "stored events (json)" to restText("/api/v1/executions/$executionId/events?format=json"),
                    "execution record" to restText("/api/v1/executions/$executionId"),
                    "mcp executions_get" to callTool("executions_get", mapOf("id" to executionId)).first.toString(),
                )
            withClue("$code ${describe(events)}") { events.lifecycleNames().last() shouldBe "pipeline_failed" }
            surfaces.forEach { (surface, text) ->
                withClue("$code on $surface") {
                    text shouldNotContain CANARY_BODY
                    text shouldNotContain CANARY_AUTHOR
                }
            }
            // Non-vacuity: the failure itself is on every surface that was scanned.
            withClue("$code on stored rows") { surfaces.getValue("stored rows") shouldContain code }
            withClue("$code on the live stream") { surfaces.getValue("live stream") shouldContain code }
        }
        lines().shouldBeEmpty()
    }

    @Test
    fun `the input body and a transformed value are persisted nowhere but where the access rules already put the context`() {
        prepare()
        val countId = countPipeline()
        val events =
            executeStream(countId, """{"parameters":{},"request_body":{"note":"$CANARY_BODY","lines":[{"sku":"A"},{"sku":"B"}]}}""")
        withClue(describe(events)) { events.last().first shouldBe "data_ready" }
        val executionId = events.first().second["execution_id"].asText()
        // The raw request body is stored on no surface of a successful run either: not the execution row, not an event.
        storedRows(executionId) shouldNotContain CANARY_BODY
        events.joinToString { it.second.toString() } shouldNotContain CANARY_BODY
    }

    // ------------------------------------------------------------ round 3: C1 — one canonical fixture budget

    @Test
    fun `framework execute and pipelines_execute share one canonical byte budget, exact at the limit and refused one byte over`() {
        prepare()
        val pipelineId = linesPipeline(JSONATA)
        truncate()
        val before = executionCount(pipelineId)
        // 4076 canonical bytes written as 4197: n's 120 trailing zeros are spelling, not body.
        val spelled = paddedDecimal(CANONICAL_4076 - PADDED_OVERHEAD)
        spelled.toByteArray().size shouldBe SPELLED_4197
        val atLimit = paddedDecimal(BODY_MAX_BYTES - PADDED_OVERHEAD)
        listOf("4076 canonical / 4197 written" to spelled, "exactly $BODY_MAX_BYTES canonical" to atLimit).forEach { (label, body) ->
            val events = executeStream(pipelineId, """{"parameters":{},"request_body":$body}""")
            withClue("framework $label ${describe(events)}") { events.last().first shouldBe "data_ready" }
            val (payload, isError) = mcpExecute(pipelineId, body)
            withClue("pipelines_execute $label: $payload") {
                isError shouldBe false
                payload["status"].asText() shouldBe "SUCCESS"
            }
        }
        executionCount(pipelineId) shouldBe before + 4

        val over = paddedDecimal(BODY_MAX_BYTES - PADDED_OVERHEAD + 1)
        val refused = post(pipelineId, """{"parameters":{},"request_body":$over}""")
        withClue("framework one over ${refused.body().asString().take(300)}") {
            refused.statusCode() shouldBe 400
            refused.jsonPath().getString("error.code") shouldBe "pipeline.execution.request_body_invalid"
            refused.jsonPath().getString("error.details.reason") shouldBe "too_many_bytes"
        }
        val (payload, isError) = mcpExecute(pipelineId, over)
        withClue("pipelines_execute one over: $payload") {
            isError shouldBe true
            payload["error"]["code"].asText() shouldBe "pipeline.execution.request_body_invalid"
            payload["error"]["details"]["reason"].asText() shouldBe "too_many_bytes"
        }
        executionCount(pipelineId) shouldBe before + 4
        lines().shouldBeEmpty()
    }

    // ------------------------------------------------------------ round 3: C2 — authored decoding and fidelity

    @Test
    fun `an authored evaluation agrees with full execution on a long fraction, through REST and MCP evaluate`() {
        prepare()
        val pipelineId = pricePipeline()
        val framework = executeStream(pipelineId, """{"parameters":{},"request_body":{"price":$LONG_FRACTION}}""")
        withClue(describe(framework)) { framework.last().first shouldBe "data_ready" }
        val viaFramework = resultOf(framework.first().second["execution_id"].asText())["rows"][0][0].asText()
        // Non-vacuity: the full run is itself exact, so agreement below is not two rounded doubles agreeing.
        viaFramework shouldBe LONG_FRACTION

        val input = """{"inputs":{"order":{"price":$LONG_FRACTION}}}"""
        val rest = restEvaluate(PRICE_TEMPLATE, input)
        withClue(rest.body().asString().take(400)) { rest.statusCode() shouldBe 200 }
        mapper.readTree(rest.body().asString())["data"]["output"][0]["price_text"].asText() shouldBe viaFramework
        val (mcp, isError) = mcpEvaluate(PRICE_TEMPLATE, input)
        withClue("templates_evaluate: $mcp") { isError shouldBe false }
        mcp["output"][0]["price_text"].asText() shouldBe viaFramework
    }

    @Test
    fun `a repeated body member, top-level or nested, is refused on every authored surface naming no value`() {
        prepare()
        linesPipeline(JSONATA)
        val template = "test/body1038_lines.$JSONATA"
        val inputs =
            listOf(
                """{"inputs":{"order":{"sku":"secret-a"},"order":{"sku":"secret-b"}}}""",
                """{"inputs":{"order":{"lines":[{"sku":"A","qty":1,"qty":2}],"note":"secret-a"}}}""",
                """{"inputs":{"order":[{"sku":"A","qty":1},{"m":{"k":"secret-a","k":"secret-b"}}]}}""",
            )
        inputs.forEachIndexed { index, input ->
            val rest = restEvaluate(template, input)
            withClue("REST evaluate $input -> ${rest.body().asString().take(300)}") {
                rest.statusCode() shouldNotBe 200
                rest.jsonPath().getString("error.code") shouldBe "pipeline.script.input_contract_violation"
                rest.jsonPath().getString("error.details.reason") shouldBe "duplicate_member"
                rest.body().asString() shouldNotContain "secret"
            }
            val (mcp, isError) = mcpEvaluate(template, input)
            withClue("MCP evaluate $input -> $mcp") {
                isError shouldBe true
                mcp.toString() shouldContain "duplicate_member"
                mcp.toString() shouldNotContain "secret"
            }
            // The save-time suite runs the same fixture: a case expecting an output is refused, naming the reason.
            val tests = """[{"name":"dup","input":$input,"expect":{"output":[]}}]"""
            val saved = restCreate(scriptPayload("test/body1038_dup_rest_$index.jsonata", LINES_JSONATA, linesContract(), tests))
            withClue("REST save $input -> ${saved.body().asString().take(400)}") {
                saved.statusCode() shouldNotBe 201
                saved.body().asString() shouldContain "duplicate_member"
                saved.body().asString() shouldContain "dup"
                saved.body().asString() shouldNotContain "secret"
            }
            val (created, createError) =
                mcpCreate(scriptPayload("test/body1038_dup_mcp_$index.jsonata", LINES_JSONATA, linesContract(), tests))
            withClue("MCP save $input -> $created") {
                createError shouldBe true
                created.toString() shouldContain "duplicate_member"
                created.toString() shouldNotContain "secret"
            }
        }
    }

    @Test
    fun `every body root reaches the authored surfaces and the full run with the same root identity, member order and contents`() {
        prepare()
        val pipelineId = rootPipeline()
        val fixtures =
            listOf(
                """{"order_no":"O-1","lines":[{"sku":"A"},{"sku":"B","note":null}],"meta":{"tags":["x","y"],"name":"Zoë"}}""" to "object",
                """[{"sku":"C"},{"comment":"no sku","tags":["x"]},{"extra":{"k":null}}]""" to "array",
                """[{"only":"one"}]""" to "array",
                "{}" to "object",
                "[]" to "array",
            )
        fixtures.forEach { (fixture, root) ->
            // The oracle: the root form and the fixture itself, member order included (the text compares in order).
            val expected = mapper.writeValueAsString(mapOf("root" to root, "body" to mapper.readTree(fixture)))
            val events = executeStream(pipelineId, """{"parameters":{},"request_body":$fixture}""")
            withClue("full run $fixture ${describe(events)}") { events.last().first shouldBe "data_ready" }
            val viaRun = resultOf(events.first().second["execution_id"].asText())["rows"][0][0].asText()
            val rest = restEvaluate(ROOT_TEMPLATE, """{"inputs":{"order":$fixture}}""")
            withClue("REST evaluate $fixture ${rest.body().asString().take(300)}") { rest.statusCode() shouldBe 200 }
            val viaRest = mapper.readTree(rest.body().asString())["data"]["output"].asText()
            val (mcp, isError) = mcpEvaluate(ROOT_TEMPLATE, """{"inputs":{"order":$fixture}}""")
            withClue("MCP evaluate $fixture $mcp") { isError shouldBe false }
            val viaMcp = mcp["output"].asText()
            withClue(fixture) {
                normalized(viaRun) shouldBe expected
                normalized(viaRest) shouldBe expected
                normalized(viaMcp) shouldBe expected
            }
        }
    }

    @Test
    fun `the operator-set depth and the canonical byte budget bound authored evaluation exactly as they bound a full run`() {
        prepare()
        val pipelineId = linesPipeline(JSONATA)
        val template = "test/body1038_lines.$JSONATA"
        truncate()
        // Depth: the configured BODY_MAX_DEPTH (not the default 32) on all four surfaces.
        val atDepth = nested(BODY_MAX_DEPTH)
        val pastDepth = nested(BODY_MAX_DEPTH + 1)
        restEvaluate(template, """{"inputs":{"order":$atDepth}}""").statusCode() shouldBe 200
        mcpEvaluate(template, """{"inputs":{"order":$atDepth}}""").second shouldBe false
        executeStream(pipelineId, """{"parameters":{},"request_body":$atDepth}""").last().first shouldBe "data_ready"
        mcpExecute(pipelineId, atDepth).second shouldBe false
        assertAuthoredRefusal(template, pastDepth, "too_deep")
        post(pipelineId, """{"parameters":{},"request_body":$pastDepth}""").jsonPath().getString("error.details.reason") shouldBe "too_deep"
        mcpExecute(pipelineId, pastDepth)
            .first["error"]["details"]["reason"]
            .asText() shouldBe "too_deep"

        // Bytes: canonical, exactly at the configured budget and one over, on both authored surfaces.
        val atBytes = paddedDecimal(BODY_MAX_BYTES - PADDED_OVERHEAD)
        withClue("REST at the byte budget") { restEvaluate(template, """{"inputs":{"order":$atBytes}}""").statusCode() shouldBe 200 }
        withClue("MCP at the byte budget") { mcpEvaluate(template, """{"inputs":{"order":$atBytes}}""").second shouldBe false }
        assertAuthoredRefusal(template, paddedDecimal(BODY_MAX_BYTES - PADDED_OVERHEAD + 1), "too_many_bytes")
        lines().shouldBeEmpty()
    }

    @Test
    fun `a body fixture saved with a long fraction keeps it through save, read-back and release, on both save surfaces`() {
        prepare()
        val exactCase =
            """[{"name":"exact","input":{"inputs":{"order":{"price":$LONG_FRACTION}}},""" +
                """"expect":{"output":[{"price_text":"$LONG_FRACTION"}]}}]"""
        val id = "test/body1038_price_saved.jsonata"
        val created = restCreate(scriptPayload(id, PRICE_JSONATA, priceContract(), exactCase))
        // The save-time suite passes only over the exact value: the expectation is the full 30-digit text.
        withClue(created.body().asString().take(400)) { created.statusCode() shouldBe 201 }
        // A number (not the expectation's string): the fraction followed by the order object's closing brace.
        val readBack = restText("/api/v1/templates?name=$id")
        withClue(readBack.take(900)) { readBack shouldContain """"price":$LONG_FRACTION}""" }
        val viaGet = rawToolCall("templates_get", """{"id":"$id"}""").body()
        withClue(viaGet.take(900)) { viaGet shouldContain "$LONG_FRACTION}" }
        // Release re-runs the suite from the stored tests.
        release(id, created.jsonPath().getString("data.body_hash"))

        val (viaMcp, mcpError) =
            mcpCreate(scriptPayload("test/body1038_price_saved_mcp.jsonata", PRICE_JSONATA, priceContract(), exactCase))
        withClue("templates_create: $viaMcp") { mcpError shouldBe false }

        // Non-vacuity: a case expecting the rounded double is refused — the suite really ran over the exact value.
        val roundedCase = exactCase.replace("\"price_text\":\"$LONG_FRACTION\"", "\"price_text\":\"0.123456789012346\"")
        val rounded = restCreate(scriptPayload("test/body1038_price_rounded.jsonata", PRICE_JSONATA, priceContract(), roundedCase))
        withClue(rounded.body().asString().take(400)) {
            rounded.statusCode() shouldNotBe 201
            rounded.body().asString() shouldContain "output_differs"
        }
    }

    // ------------------------------------------------------------ round 3: C3 — default diagnostics on authored entry points

    @Test
    fun `a failing body fixture's authored evaluation and suite verdict disclose codes and safe metadata only`() {
        prepare()
        failPipeline()
        val boom = """{"inputs":{"order":{"boom":true,"secret":"$CANARY_BODY"}}}"""
        val rest = restEvaluate(FAIL_TEMPLATE, boom)
        val (mcp, mcpError) = mcpEvaluate(FAIL_TEMPLATE, boom)
        withClue("REST ${rest.body().asString().take(400)}") {
            rest.statusCode() shouldNotBe 200
            rest.jsonPath().getString("error.code") shouldBe "pipeline.script.evaluation_failed"
        }
        withClue("MCP $mcp") {
            mcpError shouldBe true
            mcp.toString() shouldContain "pipeline.script.evaluation_failed"
        }
        // An invariant that fails keeps its name and verdict; its authored message text is withheld.
        val row = restEvaluate(FAIL_TEMPLATE, """{"inputs":{"order":{"row":true,"secret":"$CANARY_BODY"}}}""")
        withClue("REST row ${row.body().asString().take(400)}") { row.statusCode() shouldBe 200 }
        val verdict = mapper.readTree(row.body().asString())["data"]["invariants"][0]
        verdict["name"].asText() shouldBe "no_rows"
        verdict["passed"].asBoolean() shouldBe false

        val tests =
            """[{"name":"boom_case","input":$boom,"expect":{"output":{"rows":[],"rejects":[]}}},""" +
                """{"name":"row_case","input":{"inputs":{"order":{"row":true,"secret":"$CANARY_BODY"}}},""" +
                """"expect":{"output":{"rows":[],"rejects":[]}}}]"""
        val payload = scriptPayload("test/body1038_fail_suite.jsonata", FAIL_JSONATA, linesContract(rejects = true), tests, FAIL_INVARIANTS)
        val (suite, suiteError) = mcpCreate(payload)
        val saved = restCreate(payload.replace("fail_suite", "fail_suite_rest"))
        withClue("templates_create $suite") {
            suiteError shouldBe true
            suite.toString() shouldContain "boom_case"
            suite.toString() shouldContain "row_case"
        }
        withClue("REST save ${saved.body().asString().take(400)}") { saved.statusCode() shouldNotBe 201 }
        mapOf(
            "REST evaluate" to rest.body().asString(),
            "MCP evaluate" to mcp.toString(),
            "REST evaluate (invariant)" to row.body().asString(),
            "MCP save-time suite" to suite.toString(),
            "REST save-time suite" to saved.body().asString(),
        ).forEach { (surface, text) ->
            withClue(surface) {
                text shouldNotContain CANARY_BODY
                text shouldNotContain CANARY_AUTHOR
                text shouldNotContain "engine says"
            }
        }
    }

    @Test
    fun `a value extracted from the body reaches only the caller's result and the history context snapshot, never the raw body`() {
        prepare()
        val pipelineId = notePipeline()
        val events =
            executeStream(pipelineId, """{"parameters":{},"request_body":{"note":"$CANARY_NOTE","secret":"$CANARY_BODY"}}""")
        withClue(describe(events)) { events.last().first shouldBe "data_ready" }
        val executionId = events.first().second["execution_id"].asText()
        // The pipeline selected the extracted value for its caller: it is the run's result (existing result TTL/ownership).
        resultOf(executionId)["rows"][0][0].asText() shouldBe CANARY_NOTE

        // The execution summary's context snapshot (the node's context_value, repeated in its node_completed and
        // pipeline_completed events, and the run's context in parameters_json) is where the existing history access rules
        // already put a context value (record §6: "Existing access rules continue to govern the ordinary execution
        // summary"). It is the only stored place the extracted value appears, and the body member the transformer did not
        // extract appears nowhere.
        val rows = storedRows(executionId).lines().map { mapper.readTree(it) }
        val execution = rows.single { it.has("node_stats_json") }
        execution["node_stats_json"].findValues("context_value").map { it.asText() } shouldBe listOf(CANARY_NOTE)
        execution["parameters_json"]["order_note"].asText() shouldBe CANARY_NOTE
        val record = mapper.readTree(restText("/api/v1/executions/$executionId"))["data"]
        record.findValues("context_value").map { it.asText() } shouldBe listOf(CANARY_NOTE)
        // The stored data_ready event IS the caller's result (its replay), so it carries the selected value too.
        val (result, lifecycle) = rows.filter { !it.has("node_stats_json") }.partition { it["event_type"].asText() == "data_ready" }
        result.single()["payload_json"]["rows"][0][0].asText() shouldBe CANARY_NOTE
        // Non-vacuity for the removal below: the lifecycle events really carry the snapshot (node_completed, pipeline_completed).
        lifecycle.flatMap { it.findValues("context_value") }.map { it.asText() }.toSet() shouldBe setOf(CANARY_NOTE)
        val storedEvents = mapper.readTree(restText("/api/v1/executions/$executionId/events?format=json"))["data"]["events"].toList()
        // Non-vacuity: the events read really is the run's event list, result included.
        storedEvents.any { it["event"].asText() == "data_ready" } shouldBe true
        val stored =
            mapOf(
                "execution row (snapshot removed)" to withoutSnapshot(execution, "order_note"),
                "stored lifecycle events (snapshot removed)" to lifecycle.joinToString("\n") { withoutSnapshot(it, "order_note") },
                "execution record (snapshot removed)" to withoutSnapshot(record, "order_note"),
                "stored events (json) before the result, snapshot removed" to
                    storedEvents.filter { it["event"].asText() != "data_ready" }.joinToString("\n") { withoutSnapshot(it, "order_note") },
                "live stream before the result, snapshot removed" to
                    events.filter { it.first != "data_ready" }.joinToString { withoutSnapshot(it.second, "order_note") },
            )
        stored.forEach { (surface, text) ->
            withClue(surface) {
                text shouldNotContain CANARY_NOTE
                text shouldNotContain CANARY_BODY
            }
        }

        // A body whose note is not text fails the transformer's typed output; the failure names neither value.
        val failed = executeStream(pipelineId, """{"parameters":{},"request_body":{"note":["$CANARY_NOTE"],"secret":"$CANARY_BODY"}}""")
        withClue(describe(failed)) { failed.lifecycleNames().last() shouldBe "pipeline_failed" }
        val failedId = failed.first().second["execution_id"].asText()
        mapOf(
            "failed stream" to failed.joinToString { it.second.toString() },
            "failed stored rows" to storedRows(failedId),
            "failed execution record" to restText("/api/v1/executions/$failedId"),
        ).forEach { (surface, text) ->
            withClue(surface) {
                text shouldNotContain CANARY_NOTE
                text shouldNotContain CANARY_BODY
            }
        }
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

    private fun linesContract(rejects: Boolean = false) =
        mapOf(
            "mode" to "table",
            "rejects" to rejects,
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
        invariants: List<Map<String, Any?>> = emptyList(),
        tests: List<Map<String, Any?>>? = null,
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
                    "invariants" to invariants,
                    "tests" to (tests ?: listOf(emptyCase + mapOf("expect" to mapOf("output" to expectEmpty)))),
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

    /** The fraction a double cannot hold: 30 significant digits. */
    private fun pricePipeline(): String {
        createScriptTemplate(PRICE_TEMPLATE, PRICE_JSONATA, priceContract(), JSONATA)
        val shape =
            mapOf(
                "id" to "price",
                "description" to "Echoes the price as text",
                "type" to "SCRIPT",
                "template" to mapOf("id" to PRICE_TEMPLATE, "version" to 1),
                "inputs" to mapOf("order" to "@request_body"),
                "output" to mapOf("target" to "caller"),
                "depends_on" to emptyList<String>(),
            )
        return createPipeline("test/body1038_price", listOf(shape), mapOf("required" to true))
    }

    /** One strict SCRIPT node that fails three ways on demand (engine text, invariant author text, strict reject reason). */
    private fun failPipeline(): String {
        createScriptTemplate(
            FAIL_TEMPLATE,
            FAIL_JSONATA,
            linesContract(rejects = true),
            JSONATA,
            invariants = FAIL_INVARIANTS,
            tests =
                listOf(
                    mapOf(
                        "name" to "quiet",
                        "input" to mapOf("inputs" to mapOf("order" to emptyMap<String, Any>())),
                        "expect" to mapOf("output" to mapOf("rows" to emptyList<Any>(), "rejects" to emptyList<Any>())),
                    ),
                ),
        )
        val shape =
            mapOf(
                "id" to "shape",
                "description" to "Fails on demand",
                "type" to "SCRIPT",
                "template" to mapOf("id" to FAIL_TEMPLATE, "version" to 1),
                "inputs" to mapOf("order" to "@request_body"),
                "output" to mapOf("target" to "tempdb", "table" to "lines_in", "rejects" to "lines_rejected"),
                "strict" to true,
                "depends_on" to emptyList<String>(),
            )
        return createPipeline("test/body1038_fail", listOf(shape), mapOf("required" to true))
    }

    private fun priceContract() =
        mapOf(
            "mode" to "table",
            "inputs" to mapOf("order" to mapOf("kind" to "body")),
            "output" to mapOf("kind" to "table", "columns" to listOf(mapOf("name" to "price_text", "type" to "STRING"))),
        )

    /** A JSON object nested to [depth] containers (the root counts as one). */
    private fun nested(depth: Int): String = "{\"a\":".repeat(depth - 1) + "{}" + "}".repeat(depth - 1)

    /** One JSON-RPC tools/call with the arguments text exactly as written — a duplicate member stays a duplicate. */
    private fun rawToolCall(
        name: String,
        argumentsJson: String,
    ): HttpResponse<String> =
        http.send(
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port/mcp"))
                .header("DP-API-Key", MCP_KEY.plaintext)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        """{"jsonrpc":"2.0","id":${mcpCounter++},"method":"tools/call",""" +
                            """"params":{"name":"$name","arguments":$argumentsJson}}""",
                    ),
                ).build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    /** `{"n":1.000…(120 zeros),"p":"x…"}`: 14 + [pad] canonical bytes (`n` is canonically `1`), 135 + [pad] as written. */
    private fun paddedDecimal(pad: Int): String = """{"n":1.${"0".repeat(TRAILING_ZEROS)},"p":"${"x".repeat(pad)}"}"""

    /** [json] re-written by Jackson: member order kept, spacing and escapes made comparable. */
    private fun normalized(json: String): String = mapper.writeValueAsString(mapper.readTree(json))

    /** `POST /api/v1/templates/evaluate` with the input object's text exactly as written. */
    private fun restEvaluate(
        template: String,
        inputJson: String,
    ): Response =
        given()
            .port(port)
            .asSession(ADMIN_SESSION)
            .contentType(ContentType.JSON)
            .body("""{"name":"$template","input":$inputJson}""")
            .`when`()
            .post("/api/v1/templates/evaluate")
            .thenReturn()

    /** `templates_evaluate` at the real /mcp endpoint with the input object's text exactly as written. */
    private fun mcpEvaluate(
        template: String,
        inputJson: String,
    ): Pair<JsonNode, Boolean> = mcpResult(rawToolCall("templates_evaluate", """{"id":"$template","input":$inputJson}"""))

    /** A raw /mcp response's tool payload and `isError`; a JSON-RPC error counts as an error carrying its object. */
    private fun mcpResult(response: HttpResponse<String>): Pair<JsonNode, Boolean> {
        withClue(response.body().take(400)) { response.statusCode() shouldBe 200 }
        val body = mapper.readTree(response.body())
        if (body.has("error")) return body["error"] to true
        val result = body["result"]
        val text = result["content"][0]["text"].asText()
        val payload = runCatching { mapper.readTree(text) }.getOrElse { mapper.nodeFactory.textNode(text) }
        return payload to result.path("isError").asBoolean(false)
    }

    /** [node] as text without the context snapshot's fields: every `context_value` and every member named [contextKey]. */
    private fun withoutSnapshot(
        node: JsonNode,
        contextKey: String,
    ): String {
        val copy = node.deepCopy<JsonNode>()

        fun strip(current: JsonNode) {
            if (current is com.fasterxml.jackson.databind.node.ObjectNode) current.remove(listOf("context_value", contextKey))
            current.forEach { strip(it) }
        }
        strip(copy)
        return copy.toString()
    }

    /** `pipelines_execute` at the real /mcp endpoint with the request body's text exactly as written. */
    private fun mcpExecute(
        pipelineId: String,
        bodyJson: String,
    ): Pair<JsonNode, Boolean> =
        mcpResult(rawToolCall("pipelines_execute", """{"id":"$pipelineId","parameters":{},"request_body":$bodyJson}"""))

    /** `templates_create` at the real /mcp endpoint with the payload text exactly as written. */
    private fun mcpCreate(payloadJson: String): Pair<JsonNode, Boolean> = mcpResult(rawToolCall("templates_create", payloadJson))

    /** `POST /api/v1/templates` with the payload text exactly as written. */
    private fun restCreate(payload: String): Response =
        given()
            .port(port)
            .asSession(ADMIN_SESSION)
            .contentType(ContentType.JSON)
            .body(payload)
            .`when`()
            .post("/api/v1/templates")
            .thenReturn()

    /** A script template's create payload, with the `tests` block spliced in as raw text (duplicates and long fractions intact). */
    private fun scriptPayload(
        id: String,
        body: String,
        contract: Map<String, Any?>,
        testsJson: String,
        invariants: List<Map<String, Any?>> = emptyList(),
    ): String =
        mapper
            .writeValueAsString(
                mapOf(
                    "id" to id,
                    "display_name" to id,
                    "description" to "#1038 authored fixture",
                    "type" to JSONATA,
                    "body" to body,
                    "contract" to contract,
                    "invariants" to invariants,
                    "tests" to TESTS_PLACEHOLDER,
                ),
            ).replace("\"$TESTS_PLACEHOLDER\"", testsJson)

    /** An authored refusal of [order] with [reason] on REST and MCP evaluate, naming no value. */
    private fun assertAuthoredRefusal(
        template: String,
        order: String,
        reason: String,
    ) {
        val rest = restEvaluate(template, """{"inputs":{"order":$order}}""")
        withClue("REST $reason ${rest.body().asString().take(300)}") {
            rest.statusCode() shouldNotBe 200
            rest.jsonPath().getString("error.code") shouldBe "pipeline.script.input_contract_violation"
            rest.jsonPath().getString("error.details.reason") shouldBe reason
        }
        val (mcp, isError) = mcpEvaluate(template, """{"inputs":{"order":$order}}""")
        withClue("MCP $reason $mcp") {
            isError shouldBe true
            mcp.toString() shouldContain reason
        }
    }

    /** A JavaScript value-mode echo of the body's root form and contents; a tempdb DQL hands it to the caller. */
    private fun rootPipeline(): String {
        createScriptTemplate(
            ROOT_TEMPLATE,
            ROOT_JAVASCRIPT,
            stringValueContract(),
            JAVASCRIPT,
            tests =
                listOf(
                    mapOf(
                        "name" to "empty",
                        "input" to mapOf("inputs" to mapOf("order" to emptyList<Any>())),
                        "expect" to mapOf("output" to """{"root":"array","body":[]}"""),
                    ),
                ),
        )
        return contextPipeline("test/body1038_root", ROOT_TEMPLATE, "root_echo", READ_ROOT_TEMPLATE)
    }

    /** A JSONata value-mode reader extracting the body's `note` into the context; a tempdb DQL selects it for the caller. */
    private fun notePipeline(): String {
        createScriptTemplate(
            NOTE_TEMPLATE,
            "inputs.order.note",
            stringValueContract(),
            JSONATA,
            tests =
                listOf(
                    mapOf(
                        "name" to "note",
                        "input" to mapOf("inputs" to mapOf("order" to mapOf("note" to "n"))),
                        "expect" to mapOf("output" to "n"),
                    ),
                ),
        )
        return contextPipeline("test/body1038_note", NOTE_TEMPLATE, "order_note", READ_NOTE_TEMPLATE)
    }

    private fun contextPipeline(
        name: String,
        template: String,
        key: String,
        readTemplate: String,
    ): String {
        createSqlTemplate(readTemplate, "H2", """SELECT CAST(:$key AS VARCHAR) AS "value"""")
        val extract =
            mapOf(
                "id" to "extract",
                "description" to "Reads the request body into the context",
                "type" to "SCRIPT",
                "template" to mapOf("id" to template, "version" to 1),
                "inputs" to mapOf("order" to "@request_body"),
                "context_key" to key,
                "depends_on" to emptyList<String>(),
            )
        val read =
            mapOf(
                "id" to "read",
                "description" to "Binds :$key",
                "type" to "DQL",
                "source" to "tempdb",
                "template" to mapOf("id" to readTemplate, "version" to 1),
                "output" to mapOf("target" to "caller"),
                "depends_on" to listOf("extract"),
            )
        return createPipeline(name, listOf(extract, read), mapOf("required" to true))
    }

    private fun stringValueContract() =
        mapOf(
            "mode" to "value",
            "inputs" to mapOf("order" to mapOf("kind" to "body")),
            "output" to mapOf("kind" to "value", "type" to "STRING"),
        )

    private fun restText(path: String): String =
        given()
            .port(port)
            .asSession(ADMIN_SESSION)
            .`when`()
            .get(path)
            .then()
            .statusCode(200)
            .extract()
            .body()
            .asString()

    /** Every stored row a run leaves — the execution record and its events — as text, whole rows, not chosen columns. */
    private fun storedRows(executionId: String): String =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection
                .prepareStatement(
                    "SELECT to_jsonb(e)::text FROM pipeline_executions e WHERE execution_id = ?::uuid " +
                        "UNION ALL SELECT to_jsonb(v)::text FROM execution_events v WHERE execution_id = ?::uuid",
                ).use { statement ->
                    statement.setString(1, executionId)
                    statement.setString(2, executionId)
                    statement.executeQuery().use { rs ->
                        generateSequence {
                            if (rs.next()) {
                                rs.getString(
                                    1,
                                )
                            } else {
                                null
                            }
                        }.toList().also { it.isNotEmpty() shouldBe true }.joinToString("\n")
                    }
                }
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

        private const val LONG_FRACTION = "0.123456789012345678901234567890"
        private const val CANARY_BODY = "CANARY-body-7f3a91"
        private const val CANARY_AUTHOR = "CANARY-author-52c0de"
        private const val CANARY_NOTE = "CANARY-note-e41b07"

        /** The operator-adjusted body depth this context runs with (the default is 32): every surface must read it. */
        private const val BODY_MAX_DEPTH = 12

        /** paddedDecimal: canonical bytes around the padding, the trailing zeros of `n`, and the brief's 4076/4197 pair. */
        private const val PADDED_OVERHEAD = 14
        private const val TRAILING_ZEROS = 120
        private const val CANONICAL_4076 = 4076
        private const val SPELLED_4197 = 4197
        private const val TESTS_PLACEHOLDER = "__tests_1038__"

        private const val ROOT_TEMPLATE = "test/body1038_root.javascript"
        private const val NOTE_TEMPLATE = "test/body1038_note.jsonata"
        private const val READ_ROOT_TEMPLATE = "test/body1038_read_root.sql"
        private const val READ_NOTE_TEMPLATE = "test/body1038_read_note.sql"

        /** The body's root form and the body itself, as JSON text (JSON.stringify keeps member order). */
        private const val ROOT_JAVASCRIPT =
            """
            const o = input.inputs.order;
            return JSON.stringify({root: Array.isArray(o) ? "array" : typeof o, body: o});
            """

        private val FAIL_INVARIANTS =
            listOf(mapOf("name" to "no_rows", "expr" to "\$count(rows) = 0", "message" to "no rows allowed $CANARY_AUTHOR"))
        private const val PRICE_TEMPLATE = "test/body1038_price.jsonata"
        private const val FAIL_TEMPLATE = "test/body1038_fail.jsonata"

        private const val PRICE_JSONATA =
            """${'$'}exists(inputs.order.price) ? [{"price_text": ${'$'}string(inputs.order.price)}] : []"""

        /** Engine text (an error quoting a body value), a row the invariant refuses, or a reject the strict node refuses. */
        private const val FAIL_JSONATA =
            """
            (${'$'}o := inputs.order;
             ${'$'}exists(${'$'}o.boom) ? ${'$'}error("engine says " & ${'$'}o.secret) :
             {"rows": ${'$'}exists(${'$'}o.row) ? [{"sku": "A", "qty": 1, "ext_id": null, "ext_number": null}] : [],
              "rejects": ${'$'}exists(${'$'}o.reject) ? [{"row": {"sku": "A", "qty": 1, "ext_id": null, "ext_number": null}, "reason": "rejected " & ${'$'}o.secret}] : []})
            """

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
            // An operator-adjusted depth: framework execute, pipelines_execute and every authored surface must read it.
            registry.add("datapipelines.endpoints.body-max-depth") { BODY_MAX_DEPTH.toString() }
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
