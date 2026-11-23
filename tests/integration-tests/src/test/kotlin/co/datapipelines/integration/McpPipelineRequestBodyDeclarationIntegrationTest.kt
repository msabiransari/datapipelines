package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import co.datapipelines.integration.E2eSession.asSession
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.Response
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.DriverManager
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * #1100 — a pipeline's request-body declaration authored through the real `/mcp` endpoint (pipeline-contract §3.4,
 * mcp-server §6.2.4–5), in the production assembly.
 *
 * Every write goes through `pipelines_create` / `pipelines_update` with a workspace-admin MCP key; every read-back is
 * a separate `pipelines_get` (and, for a published pin, `endpoints_get`). The human steps an agent cannot take —
 * releasing a pipeline, publishing and releasing a route, minting and granting an endpoint key — use the session
 * REST surface, as a person would. The pipelines read and write `tempdb` only, so no table-learning precondition
 * applies, and every body value is synthetic.
 */
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["datapipelines.endpoints.mutations-enabled=true"],
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Suppress("LargeClass") // one authoring surface over one seed; the cases share templates rather than re-seeding
class McpPipelineRequestBodyDeclarationIntegrationTest {
    @LocalServerPort
    private var port: Int = 0

    private val mapper = ObjectMapper()
    private val http: HttpClient = HttpClient.newHttpClient()
    private val rpcIds = AtomicInteger(1)

    @BeforeAll
    fun seed() {
        E2eClean.beforeSeeding()
        seedPrincipals()
        scriptTemplate(COUNT_TEMPLATE, COUNT_JSONATA, expectEmpty = 0)
        scriptTemplate(TALLY_TEMPLATE, TALLY_JSONATA, expectEmpty = TALLY_BASE)
        sqlTemplate(READ_COUNT_TEMPLATE, """SELECT CAST(:line_count AS INTEGER) AS "n"""")
        sqlTemplate(READ_TALLY_TEMPLATE, """SELECT CAST(:tally AS INTEGER) AS "n"""")
        sqlTemplate(PLAIN_TEMPLATE, """SELECT 1 AS "one"""")
    }

    // -----------------------------------------------------------------------------------------
    // A.2 — author, read back and replace
    // -----------------------------------------------------------------------------------------

    @Test
    fun `pipelines_create stores a required declaration that the write body and a separate read agree on`() {
        val write = ok("pipelines_create", document("test/b1100/created_required", readerNodes(), mapOf("required" to true)))

        write["version"].asInt() shouldBe 1
        write["status"].asText() shouldBe "DRAFT"
        write["body"]["request_body"] shouldBe json("""{"required":true}""")
        val read = get(write["id"].asText())
        assertAgree(write, read)
        read["request_body"] shouldBe json("""{"required":true}""")
    }

    @Test
    fun `a pre-declaration draft gains, canonicalises, carries forward and finally drops its declaration`() {
        val original = document("test/b1100/evolving", plainNodes(), requestBody = null)
        val created = ok("pipelines_create", original)
        val id = created["id"].asText()
        created["body"].has("request_body") shouldBe false
        val h0 = created["body_hash"].asText()
        var read = get(id)
        assertAgree(created, read)
        read.has("request_body") shouldBe false

        // Add the reader and an explicit optional declaration.
        val withReader = original + mapOf("nodes" to readerNodes(), "request_body" to mapOf("required" to false))
        val added = update(id, read, withReader)
        added["body"]["request_body"] shouldBe json("""{"required":false}""")
        val h1 = added["body_hash"].asText()
        h1 shouldNotBe h0
        read = get(id)
        assertAgree(added, read)
        read["request_body"] shouldBe json("""{"required":false}""")

        // `{}` and an unknown inner member alone both canonicalise to the same stored declaration and hash.
        for (spelling in listOf(emptyMap<String, Any?>(), mapOf("later_addition" to "ignored"))) {
            val resaved = update(id, read, withReader + mapOf("request_body" to spelling))
            withClue("re-save with $spelling") {
                resaved["body"]["request_body"] shouldBe json("""{"required":false}""")
                resaved["body_hash"].asText() shouldBe h1
            }
            read = get(id)
            assertAgree(resaved, read)
        }

        // An agent's edit loop: read, change the display name, write back only the authored fields it read
        // (schema_version is the server's: no tool argument carries it).
        val edited =
            authored(read).apply {
                remove("schema_version")
                put("display_name", "Evolving, renamed")
            }
        val renamed = update(id, read, mapper.convertValue(edited, Map::class.java).mapKeys { it.key.toString() })
        renamed["body"]["display_name"].asText() shouldBe "Evolving, renamed"
        renamed["body"]["request_body"] shouldBe json("""{"required":false}""")
        renamed["body_hash"].asText() shouldNotBe h1
        read = get(id)
        assertAgree(renamed, read)

        // Restore the original fields exactly and omit the declaration: the member goes, the hash returns to H0.
        val restored = update(id, read, original)
        restored["body"].has("request_body") shouldBe false
        restored["body_hash"].asText() shouldBe h0
        read = get(id)
        assertAgree(restored, read)
        read.has("request_body") shouldBe false
        read["version"].asInt() shouldBe 1
        read["status"].asText() shouldBe "DRAFT"
    }

    // -----------------------------------------------------------------------------------------
    // A.1 — the public schema owns the declaration's shape
    // -----------------------------------------------------------------------------------------

    @Test
    fun `both write tools refuse a scalar, array, null or non-boolean-required declaration and save nothing`() {
        val badValues: List<Any?> = listOf("required", listOf(mapOf("required" to true)), null, mapOf("required" to "true"))
        badValues.forEach { bad ->
            val name = "test/b1100/schema_refused_create"
            val (payload, isError) = callTool("pipelines_create", document(name, plainNodes(), null) + mapOf("request_body" to bad))
            withClue("pipelines_create request_body=$bad -> $payload") {
                isError shouldBe true
                payload.asText() shouldContain "validation"
                payload.asText() shouldContain "request_body"
            }
            println(
                "event=b1100.schema_refusal tool=pipelines_create case=${bad?.javaClass?.simpleName} " +
                    "text=${payload.asText().take(EXCERPT)}",
            )
            metaCount("SELECT count(*) FROM pipelines WHERE name = '$name'") shouldBe 0
        }

        val base = document("test/b1100/schema_refused_update", plainNodes(), null)
        val id = ok("pipelines_create", base)["id"].asText()
        val before = get(id)
        badValues.forEach { bad ->
            val arguments = base + mapOf("id" to id, "expected_hash" to before["body_hash"].asText(), "request_body" to bad)
            val (payload, isError) = callTool("pipelines_update", arguments)
            withClue("pipelines_update request_body=$bad -> $payload") {
                isError shouldBe true
                payload.asText() shouldContain "validation"
                payload.asText() shouldContain "request_body"
            }
            val after = get(id)
            after["body_hash"] shouldBe before["body_hash"]
            after["version"] shouldBe before["version"]
            after["status"] shouldBe before["status"]
        }
    }

    @Test
    fun `a declaration repeating a member is refused at the transport and saves nothing`() {
        // Beyond the brief: the #1038 transport guard reads every call's `request_body`, the declaration included.
        val name = "test/b1100/repeated_member"
        val nodes = mapper.writeValueAsString(plainNodes())
        val response =
            rawToolCall(
                "pipelines_create",
                """{"name":"$name","display_name":"Repeated","nodes":$nodes,"request_body":{"required":true,"required":false}}""",
            )
        withClue("${response.statusCode()} ${response.body().take(300)}") {
            val refusedByTool =
                response.statusCode() == 200 &&
                    mapper
                        .readTree(response.body())
                        .path("result")
                        .path("isError")
                        .asBoolean(false)
            (response.statusCode() != 200 || refusedByTool) shouldBe true
        }
        metaCount("SELECT count(*) FROM pipelines WHERE name = '$name'") shouldBe 0
    }

    // -----------------------------------------------------------------------------------------
    // A.3 — the shared save rules, at their own entry points
    // -----------------------------------------------------------------------------------------

    @Test
    fun `a bound reader without the declaration is refused script_input_contract by both write tools`() {
        assertCreateRefused("test/b1100/undeclared_create", readerNodes(), null, emptyMap(), SCRIPT_INPUT_CONTRACT)
        assertUpdateRefused("test/b1100/undeclared_update", readerNodes(), null, emptyMap(), SCRIPT_INPUT_CONTRACT)
    }

    @Test
    fun `a declared parameter naming the reader's value output is refused calculator_output_collision by both write tools`() {
        val colliding = mapOf("line_count" to mapOf("type" to "INTEGER", "required" to false, "description" to "Collides."))
        assertCreateRefused("test/b1100/collision_create", readerNodes(), mapOf("required" to true), colliding, OUTPUT_COLLISION)
        assertUpdateRefused("test/b1100/collision_update", readerNodes(), mapOf("required" to true), colliding, OUTPUT_COLLISION)
    }

    @Test
    fun `an execution parameter overriding a body reader is refused at execution, not at authoring`() {
        val id = ok("pipelines_create", document("test/b1100/override", readerNodes(), mapOf("required" to true)))["id"].asText()
        val executions = metaCount("SELECT count(*) FROM pipeline_executions WHERE pipeline_id = '$id'")

        val (accepted, acceptedError) =
            callTool("pipelines_execute", mapOf("id" to id, "parameters" to emptyMap<String, Any>(), "request_body" to lines(2)))
        withClue("control: $accepted") {
            acceptedError shouldBe false
            accepted["rows"][0][0].asInt() shouldBe 2
        }

        val (refused, isError) =
            callTool(
                "pipelines_execute",
                mapOf("id" to id, "parameters" to mapOf("line_count" to 5), "request_body" to lines(2)),
            )
        withClue("override: $refused") {
            isError shouldBe true
            refused["error"]["code"].asText() shouldBe "pipeline.execution.request_body_invalid"
            refused["error"]["details"]["reason"].asText() shouldBe "override_refused"
        }
        metaCount("SELECT count(*) FROM pipeline_executions WHERE pipeline_id = '$id'") shouldBe executions + 1
    }

    @Test
    fun `a declaration with zero readers saves on both tools at each setting, and its POST pin reports the mode and no readers`() {
        for (required in listOf(true, false)) {
            val declaration = mapOf("required" to required)
            val canonical = json("""{"required":$required}""")
            val mode = if (required) "required" else "optional"

            val created = ok("pipelines_create", document("test/b1100/zero_create_$mode", plainNodes(), declaration))
            created["body"]["request_body"] shouldBe canonical
            assertAgree(created, get(created["id"].asText()))

            val base = document("test/b1100/zero_update_$mode", plainNodes(), null)
            val bare = ok("pipelines_create", base)
            val updated = update(bare["id"].asText(), get(bare["id"].asText()), base + mapOf("request_body" to declaration))
            updated["body"]["request_body"] shouldBe canonical
            updated["version"].asInt() shouldBe 1
            assertAgree(updated, get(bare["id"].asText()))

            for ((write, path) in listOf(created to "/b1100/v1/zero-create-$mode", updated to "/b1100/v1/zero-update-$mode")) {
                releasePipeline(write["id"].asText(), write["body_hash"].asText())
                publish(path, write["name"].asText())
                val endpoint = ok("endpoints_get", mapOf("path" to path, "method" to "POST"))
                withClue("$path -> $endpoint") {
                    endpoint["pipeline_version"].asInt() shouldBe 1
                    endpoint["request_body"] shouldBe json("""{"mode":"$mode","readers":[]}""")
                }
            }
        }
    }

    // -----------------------------------------------------------------------------------------
    // A.4 — a published pin keeps the exact version's contract while the draft changes
    // -----------------------------------------------------------------------------------------

    @Test
    fun `a published POST keeps serving its pinned version's body reader after the MCP draft changes mode and readers`() {
        val created = ok("pipelines_create", document(PUBLISHED_PIPELINE, readerNodes(), mapOf("required" to true)))
        val id = created["id"].asText()
        releasePipeline(id, created["body_hash"].asText())
        publish(PUBLISHED_PATH, PUBLISHED_PIPELINE)
        val key = mintEndpointKey()
        grant(key, PUBLISHED_PATH)
        val pinnedContract = json("""{"mode":"required","readers":[{"node_id":"count","template":"$COUNT_TEMPLATE@1"}]}""")

        val before = serve(key, lines(2))
        withClue("before the draft edit: ${before.asString()}") {
            before.statusCode() shouldBe 200
            before.jsonPath().getInt("rows[0][0]") shouldBe 2
        }
        val pinnedBefore = ok("endpoints_get", mapOf("path" to PUBLISHED_PATH, "method" to "POST"))
        pinnedBefore["pipeline_version"].asInt() shouldBe 1
        pinnedBefore["request_body"] shouldBe pinnedContract

        // The working draft changes mode (optional) and readers (another node, another template).
        val draft = update(id, get(id), document(PUBLISHED_PIPELINE, tallyNodes(), mapOf("required" to false)))
        draft["status"].asText() shouldBe "DRAFT"
        draft["version"].asInt() shouldBe 2

        val working = get(id)
        assertAgree(draft, working)
        working["request_body"] shouldBe json("""{"required":false}""")
        working["nodes"].map { it["id"].asText() } shouldContain "tally"

        val pinnedAfter = ok("endpoints_get", mapOf("path" to PUBLISHED_PATH, "method" to "POST"))
        pinnedAfter["pipeline_version"].asInt() shouldBe 1
        pinnedAfter["request_body"] shouldBe pinnedContract

        // Same route, same grant, a fresh idempotency key: the original count reader answers, not the draft's tally.
        val after = serve(key, lines(3))
        withClue("after the draft edit: ${after.asString()}") {
            after.statusCode() shouldBe 200
            after.jsonPath().getInt("rows[0][0]") shouldBe 3
        }
        println(
            "event=b1100.published before_status=${before.statusCode()} before_n=${before.jsonPath().getInt("rows[0][0]")} " +
                "after_status=${after.statusCode()} after_n=${after.jsonPath().getInt("rows[0][0]")} pin=1 draft_version=2",
        )
    }

    // -----------------------------------------------------------------------------------------
    // helpers — documents
    // -----------------------------------------------------------------------------------------

    private fun document(
        name: String,
        nodes: List<Map<String, Any?>>,
        requestBody: Map<String, Any?>?,
        parameters: Map<String, Any?> = emptyMap(),
    ): Map<String, Any?> =
        mapOf(
            "name" to name,
            "display_name" to "Body declaration ${name.substringAfterLast('/')}",
            "description" to "#1100 MCP-authored declaration",
            "parameters" to parameters,
            "nodes" to nodes,
        ) + (requestBody?.let { mapOf("request_body" to it) } ?: emptyMap())

    /** A value-mode body reader writing `line_count`, read back by a tempdb caller. */
    private fun readerNodes(): List<Map<String, Any?>> =
        listOf(
            mapOf(
                "id" to "count",
                "description" to "Counts the body's lines",
                "type" to "SCRIPT",
                "template" to mapOf("id" to COUNT_TEMPLATE, "version" to 1),
                "inputs" to mapOf("order" to "@request_body"),
                "context_key" to "line_count",
                "depends_on" to emptyList<String>(),
            ),
            caller(READ_COUNT_TEMPLATE, "count"),
        )

    /** A different reader (node and template) — the draft edit of the published case. */
    private fun tallyNodes(): List<Map<String, Any?>> =
        listOf(
            mapOf(
                "id" to "tally",
                "description" to "Offsets the body's line count",
                "type" to "SCRIPT",
                "template" to mapOf("id" to TALLY_TEMPLATE, "version" to 1),
                "inputs" to mapOf("order" to "@request_body"),
                "context_key" to "tally",
                "depends_on" to emptyList<String>(),
            ),
            caller(READ_TALLY_TEMPLATE, "tally"),
        )

    private fun plainNodes(): List<Map<String, Any?>> = listOf(caller(PLAIN_TEMPLATE, null))

    private fun caller(
        template: String,
        dependsOn: String?,
    ): Map<String, Any?> =
        mapOf(
            "id" to "read",
            "description" to "The caller result",
            "type" to "DQL",
            "source" to "tempdb",
            "template" to mapOf("id" to template, "version" to 1),
            "output" to mapOf("target" to "caller"),
            "depends_on" to listOfNotNull(dependsOn),
        )

    private fun lines(count: Int): Map<String, Any?> = mapOf("lines" to (1..count).map { mapOf("sku" to "SYN-$it", "qty" to it) })

    // -----------------------------------------------------------------------------------------
    // helpers — MCP
    // -----------------------------------------------------------------------------------------

    /** A successful tool payload; anything else fails with the payload. */
    private fun ok(
        tool: String,
        arguments: Map<String, Any?>,
    ): JsonNode {
        val (payload, isError) = callTool(tool, arguments)
        withClue("$tool -> $payload") { isError shouldBe false }
        return payload
    }

    private fun get(id: String): JsonNode = ok("pipelines_get", mapOf("id" to id))

    /** `pipelines_update` based on [read]'s hash, which must be the stored one. */
    private fun update(
        id: String,
        read: JsonNode,
        fields: Map<String, Any?>,
    ): JsonNode = ok("pipelines_update", fields + mapOf("id" to id, "expected_hash" to read["body_hash"].asText()))

    /** The authored document of a `pipelines_get` answer: no server metadata, no derived parameter. */
    private fun authored(read: JsonNode): ObjectNode {
        val copy = read.deepCopy<ObjectNode>()
        SERVER_METADATA.forEach { copy.remove(it) }
        (copy["parameters"] as? ObjectNode)?.let { parameters ->
            parameters
                .properties()
                .filter { it.value.path("derived").asBoolean(false) }
                .map { it.key }
                .forEach { parameters.remove(it) }
        }
        return copy
    }

    /** A write's `body` and the following read's top-level document agree, and the read carries the write's hash. */
    private fun assertAgree(
        write: JsonNode,
        read: JsonNode,
    ) {
        withClue("write body vs pipelines_get document") {
            read["body_hash"].asText() shouldBe write["body_hash"].asText()
            authored(read) shouldBe authored(write["body"])
            read["request_body"] shouldBe write["body"]["request_body"]
        }
    }

    private fun assertCreateRefused(
        name: String,
        nodes: List<Map<String, Any?>>,
        requestBody: Map<String, Any?>?,
        parameters: Map<String, Any?>,
        code: String,
    ) {
        val (payload, isError) = callTool("pipelines_create", document(name, nodes, requestBody, parameters))
        withClue("pipelines_create $name -> $payload") {
            isError shouldBe true
            payload["error"]["code"].asText() shouldBe code
        }
        metaCount("SELECT count(*) FROM pipelines WHERE name = '$name'") shouldBe 0
    }

    private fun assertUpdateRefused(
        name: String,
        nodes: List<Map<String, Any?>>,
        requestBody: Map<String, Any?>?,
        parameters: Map<String, Any?>,
        code: String,
    ) {
        val base = document(name, plainNodes(), null)
        val id = ok("pipelines_create", base)["id"].asText()
        val before = get(id)
        val (payload, isError) =
            callTool(
                "pipelines_update",
                document(name, nodes, requestBody, parameters) + mapOf("id" to id, "expected_hash" to before["body_hash"].asText()),
            )
        withClue("pipelines_update $name -> $payload") {
            isError shouldBe true
            payload["error"]["code"].asText() shouldBe code
        }
        val after = get(id)
        after["body_hash"] shouldBe before["body_hash"]
        after["version"] shouldBe before["version"]
        after["status"] shouldBe before["status"]
        after.has("request_body") shouldBe false
    }

    private fun callTool(
        name: String,
        arguments: Map<String, Any?>,
    ): Pair<JsonNode, Boolean> {
        val response = rawToolCall(name, mapper.writeValueAsString(arguments))
        withClue("$name HTTP ${response.statusCode()}") { response.statusCode() shouldBe 200 }
        val body = mapper.readTree(response.body())
        withClue("JSON-RPC error on $name: ${response.body().take(400)}") { body.has("error") shouldBe false }
        val result = body["result"]
        val text = result["content"][0]["text"].asText()
        // A refusal the transport makes before the tool runs (input-schema validation) is plain text, not JSON.
        val payload = runCatching { mapper.readTree(text) }.getOrElse { mapper.nodeFactory.textNode(text) }
        return payload to result.path("isError").asBoolean(false)
    }

    /** The arguments go on the wire exactly as written. */
    private fun rawToolCall(
        name: String,
        argumentsJson: String,
    ): HttpResponse<String> {
        val message =
            """{"jsonrpc":"2.0","id":${rpcIds.getAndIncrement()},"method":"tools/call",""" +
                """"params":{"name":"$name","arguments":$argumentsJson}}"""
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port/mcp"))
                .header("DP-API-Key", MCP_KEY.plaintext)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(message))
                .build()
        return http.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun json(text: String): JsonNode = mapper.readTree(text)

    // -----------------------------------------------------------------------------------------
    // helpers — the human steps (session REST) and the published call
    // -----------------------------------------------------------------------------------------

    private fun releasePipeline(
        id: String,
        hash: String,
    ) {
        given()
            .port(port)
            .asSession(ADMIN_SESSION)
            .header("If-Match", hash)
            .`when`()
            .post("/api/v1/pipelines/$id/release")
            .then()
            .statusCode(200)
    }

    /** Publishes a POST at [path] pinning v1 of [pipeline] and releases the route. */
    private fun publish(
        path: String,
        pipeline: String,
    ) {
        val id =
            given()
                .port(port)
                .contentType(ContentType.JSON)
                .asSession(ADMIN_SESSION)
                .body("""{"path": "$path", "method": "POST", "pipeline": "$pipeline", "pipeline_version": 1, "timeout_seconds": 60}""")
                .`when`()
                .post("/api/v1/endpoints")
                .then()
                .statusCode(201)
                .extract()
                .jsonPath()
                .getString("data.id")
        val hash =
            given()
                .port(port)
                .asSession(ADMIN_SESSION)
                .get("/api/v1/endpoints/$id/versions/1")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getString("data.body_hash")
        given()
            .port(port)
            .asSession(ADMIN_SESSION)
            .header("If-Match", hash)
            .post("/api/v1/endpoints/$id/release")
            .then()
            .statusCode(200)
    }

    private fun mintEndpointKey(): EndpointKey {
        val minted =
            given()
                .port(port)
                .contentType(ContentType.JSON)
                .asSession(ADMIN_SESSION)
                .body("""{"name": "b1100-writer", "kind": "endpoint", "bindings": []}""")
                .`when`()
                .post("/api/v1/auth/api-keys")
                .then()
                .statusCode(201)
                .extract()
                .jsonPath()
        return EndpointKey(minted.getString("data.id"), minted.getString("data.key"))
    }

    private fun grant(
        key: EndpointKey,
        path: String,
    ) {
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .asSession(ADMIN_SESSION)
            .body("""{"api_key_id": "${key.id}", "path_prefix": "$path", "method": "POST"}""")
            .`when`()
            .post("/api/v1/endpoints/bindings")
            .then()
            .statusCode(201)
    }

    /** One published POST with a fresh idempotency key. */
    private fun serve(
        key: EndpointKey,
        body: Map<String, Any?>,
    ): Response =
        given()
            .port(port)
            .header("DP-API-Key", key.plaintext)
            .header("Idempotency-Key", "b1100-${UUID.randomUUID()}")
            .contentType(ContentType.JSON)
            .body(mapper.writeValueAsString(body))
            .`when`()
            .post("/api$PUBLISHED_PATH")
            .thenReturn()

    private fun scriptTemplate(
        id: String,
        body: String,
        expectEmpty: Int,
    ) {
        val payload =
            mapOf(
                "id" to id,
                "display_name" to id,
                "description" to "#1100 body reader",
                "type" to "jsonata",
                "body" to body,
                "contract" to
                    mapOf(
                        "mode" to "value",
                        "inputs" to mapOf("order" to mapOf("kind" to "body")),
                        "output" to mapOf("kind" to "value", "type" to "INTEGER"),
                    ),
                "invariants" to emptyList<Any>(),
                "tests" to
                    listOf(
                        mapOf(
                            "name" to "empty body",
                            "input" to mapOf("inputs" to mapOf("order" to emptyList<Any>())),
                            "expect" to mapOf("output" to expectEmpty),
                        ),
                    ),
            )
        createAndReleaseTemplate(id, payload)
    }

    private fun sqlTemplate(
        id: String,
        body: String,
    ) {
        val payload =
            mapOf(
                "id" to id,
                "dialect" to "H2",
                "display_name" to id,
                "description" to "#1100 template",
                "imports" to emptyList<Any>(),
                "body" to body,
            )
        createAndReleaseTemplate(id, payload)
    }

    private fun createAndReleaseTemplate(
        id: String,
        payload: Map<String, Any?>,
    ) {
        val created =
            given()
                .port(port)
                .contentType(ContentType.JSON)
                .asSession(ADMIN_SESSION)
                .body(mapper.writeValueAsString(payload))
                .`when`()
                .post("/api/v1/templates")
                .thenReturn()
        withClue("template $id: ${created.asString()}") { created.statusCode() shouldBe 201 }
        given()
            .port(port)
            .asSession(ADMIN_SESSION)
            .header("If-Match", created.jsonPath().getString("data.body_hash"))
            .contentType(ContentType.JSON)
            .body("""{"name": "$id"}""")
            .`when`()
            .post("/api/v1/templates/release")
            .then()
            .statusCode(200)
    }

    private fun seedPrincipals() {
        DriverManager.getConnection(SharedE2e.postgres.jdbcUrl, SharedE2e.postgres.username, SharedE2e.postgres.password).use { c ->
            c.createStatement().use { s ->
                s.execute(
                    "INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin) " +
                        "VALUES ('$ADMIN_USER', '$ADMIN_EMAIL', 'B1100 Admin', 'test', 'b1100-admin', TRUE, TRUE)",
                )
                s.execute(
                    "INSERT INTO workspace_members (workspace_id, user_id, role) " +
                        "VALUES ('$DEFAULT_WORKSPACE', '$ADMIN_USER', 'workspace_admin')",
                )
                // The MCP key's own `service` identity (keys v2 A13); the key acts as its creator's member.
                s.execute(
                    "INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin, kind) VALUES " +
                        "('$KEY_IDENTITY', '${MCP_KEY.id.lowercase()}@keys.invalid', '${MCP_KEY.name}', 'key', " +
                        "'${MCP_KEY.id}', TRUE, FALSE, 'service')",
                )
            }
            c
                .prepareStatement(
                    "INSERT INTO api_keys (id, user_id, created_by, name, key_hash, workspace_id, kind, role)" +
                        " VALUES (?, ?, ?, ?, ?, ?, 'mcp', 'workspace_admin')",
                ).use { ps ->
                    ps.setString(1, MCP_KEY.id)
                    ps.setObject(2, UUID.fromString(KEY_IDENTITY))
                    ps.setObject(3, UUID.fromString(ADMIN_USER))
                    ps.setString(4, MCP_KEY.name)
                    ps.setString(5, MCP_KEY.hash)
                    ps.setObject(6, UUID.fromString(DEFAULT_WORKSPACE))
                    ps.executeUpdate()
                }
        }
    }

    private fun metaCount(sql: String): Long =
        DriverManager.getConnection(SharedE2e.postgres.jdbcUrl, SharedE2e.postgres.username, SharedE2e.postgres.password).use { c ->
            c.createStatement().use { s ->
                s.executeQuery(sql).use { rs ->
                    rs.next()
                    rs.getLong(1)
                }
            }
        }

    private class EndpointKey(
        val id: String,
        val plaintext: String,
    )

    companion object {
        private const val DEFAULT_WORKSPACE = "defa0000-0000-0000-0000-000000000001"
        private const val SCRIPT_INPUT_CONTRACT = "pipeline.validation.script_input_contract"
        private const val OUTPUT_COLLISION = "pipeline.validation.calculator_output_collision"

        /** The members `pipelines_get` adds to the stored document. */
        private val SERVER_METADATA = listOf("id", "version", "status", "body_hash", "draft", "upgrade_available", "current_version")

        private const val COUNT_TEMPLATE = "test/b1100_count.jsonata"
        private const val TALLY_TEMPLATE = "test/b1100_tally.jsonata"
        private const val READ_COUNT_TEMPLATE = "test/b1100_read_count.sql"
        private const val READ_TALLY_TEMPLATE = "test/b1100_read_tally.sql"
        private const val PLAIN_TEMPLATE = "test/b1100_plain.sql"
        private const val TALLY_BASE = 1000
        private const val EXCERPT = 200

        private const val COUNT_JSONATA =
            """${'$'}count(${'$'}type(inputs.order) = "array" ? inputs.order : inputs.order.lines)"""
        private const val TALLY_JSONATA =
            """1000 + ${'$'}count(${'$'}type(inputs.order) = "array" ? inputs.order : inputs.order.lines)"""

        private const val PUBLISHED_PIPELINE = "test/b1100/published"
        private const val PUBLISHED_PATH = "/b1100/v1/orders"

        private val ADMIN_USER: String = UUID.randomUUID().toString()
        private val KEY_IDENTITY: String = UUID.randomUUID().toString()
        private const val ADMIN_EMAIL = "b1100-admin@datapipelines.test"
        private val JWT_SECRET = E2eSession.newSecret()
        private val ADMIN_SESSION get() = E2eSession.jwt(JWT_SECRET, ADMIN_USER, ADMIN_EMAIL)
        private val MCP_KEY = E2eAuth.generateKey("e2e-1100-declaration-key")

        private val ENCRYPTION_KEY: String =
            Base64.getEncoder().encodeToString(ByteArray(32).also { java.security.SecureRandom().nextBytes(it) })

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("management.server.port") { "0" }
            registry.add("spring.datasource.url") { SharedE2e.postgres.jdbcUrl }
            registry.add("spring.datasource.username") { SharedE2e.postgres.username }
            registry.add("spring.datasource.password") { SharedE2e.postgres.password }
            registry.add("datapipelines.redis.password") { "" }
            registry.add("datapipelines.redis.host") { SharedE2e.redisHost }
            registry.add("datapipelines.redis.port") { SharedE2e.redisPort }
            registry.add("datapipelines.jwt.secret") { JWT_SECRET }
            registry.add("datapipelines.db.encryption-key") { ENCRYPTION_KEY }
            registry.add("datapipelines.auth.local.enabled") { "true" }
        }
    }
}
