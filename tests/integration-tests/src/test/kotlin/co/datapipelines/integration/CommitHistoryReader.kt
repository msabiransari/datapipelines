package co.datapipelines.integration

import co.datapipelines.integration.E2eSession.asSession
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeIn
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.restassured.RestAssured.given
import io.restassured.http.ContentType

/** One entry of an execution's history as a reader sees it: its sequence id, wire name and payload `status`. */
data class HistoryEvent(
    val id: Int,
    val name: String,
    val status: String?,
)

/**
 * #1035 R2 — an execution's history read through every surface that serves it, on the real wire: the durable
 * record (`GET /executions/{id}/events?format=json`), the Redis replay (`GET /executions/{id}/events`, the
 * event stream) and the MCP resource (`datapipelines://executions/{id}/events` over `/mcp` with a key). The
 * three read different stores; a family's child must read the same on all of them.
 */
class CommitHistoryReader(
    private val port: Int,
    private val session: String,
    private val mcpKey: String,
) {
    private val mapper = ObjectMapper()

    /** The three views of [executionId], keyed `durable`, `replay`, `mcp`. */
    fun views(executionId: String): Map<String, List<HistoryEvent>> =
        linkedMapOf(
            "durable" to durable(executionId),
            "replay" to replay(executionId),
            "mcp" to mcp(executionId),
        )

    private fun durable(executionId: String): List<HistoryEvent> {
        val response =
            given()
                .port(port)
                .asSession(session)
                .`when`()
                .get("/api/v1/executions/$executionId/events?format=json&limit=500")
                .thenReturn()
        withClue("durable ${response.statusCode} ${response.asString().take(EXCERPT)}") { response.statusCode shouldBe 200 }
        return mapper.readTree(response.asString()).path("data").path("events").map {
            HistoryEvent(it.path("event_id").asInt(), it.path("event").asText(), it.path("data").path("status").textOrNull())
        }
    }

    private fun replay(executionId: String): List<HistoryEvent> {
        val response =
            given()
                .port(port)
                .asSession(session)
                .accept("text/event-stream")
                .`when`()
                .get("/api/v1/executions/$executionId/events")
                .thenReturn()
        withClue("replay ${response.statusCode} ${response.asString().take(EXCERPT)}") { response.statusCode shouldBe 200 }
        return parseFrames(response.asString())
    }

    private fun mcp(executionId: String): List<HistoryEvent> {
        val result = mcpCall("resources/read", mapOf("uri" to "datapipelines://executions/$executionId/events"))
        return parseFrames(
            result
                .path("contents")
                .path(0)
                .path("text")
                .asText(),
        )
    }

    /** One JSON-RPC call on `/mcp` with the key; returns the `result` member and fails on an `error` member. */
    fun mcpCall(
        method: String,
        params: Map<String, Any?>,
    ): JsonNode {
        val body = mapper.writeValueAsString(mapOf("jsonrpc" to "2.0", "id" to 1, "method" to method, "params" to params))
        val raw =
            given()
                .port(port)
                .header("DP-API-Key", mcpKey)
                .contentType(ContentType.JSON)
                .accept("application/json, text/event-stream")
                .body(body)
                .`when`()
                .post("/mcp")
                .thenReturn()
                .asString()
        // The JSON-RPC envelope is one line, plain or framed as an event: the line that opens with the jsonrpc member.
        val envelope = raw.lineSequence().map { it.removePrefix("data:").trim() }.first { it.startsWith("{\"jsonrpc\"") }
        val parsed = mapper.readTree(envelope)
        withClue("mcp $method ${raw.take(EXCERPT)}") { parsed.has("error") shouldBe false }
        return parsed.path("result")
    }

    private fun parseFrames(text: String): List<HistoryEvent> {
        val events = mutableListOf<HistoryEvent>()
        var id: Int? = null
        var name: String? = null
        text.lineSequence().forEach { line ->
            when {
                line.startsWith("id:") -> {
                    id = line.removePrefix("id:").trim().toInt()
                }

                line.startsWith("event:") -> {
                    name = line.removePrefix("event:").trim()
                }

                line.startsWith("data:") -> {
                    val payload = mapper.readTree(line.removePrefix("data:").trim())
                    events +=
                        HistoryEvent(
                            requireNotNull(id) { "frame without id: $line" },
                            requireNotNull(name),
                            payload.path("status").textOrNull(),
                        )
                }
            }
        }
        return events
    }

    private fun JsonNode.textOrNull(): String? = if (isMissingNode || isNull) null else asText()

    /**
     * The execution's record as every reader of it sees it: REST `GET /executions/{id}`, the MCP `executions_get` tool, and
     * the paged listing walked one row per page (`limit=1`) until the execution turns up — all must agree on [status] and on
     * [resultUnavailable].
     */
    fun assertRecordOnEverySurface(
        executionId: String,
        status: String,
        resultUnavailable: Boolean,
        label: String,
    ) {
        val rest =
            given()
                .port(port)
                .asSession(session)
                .`when`()
                .get("/api/v1/executions/$executionId")
                .thenReturn()
        withClue("$label REST ${rest.asString().take(EXCERPT)}") {
            rest.statusCode shouldBe 200
            rest.jsonPath().getString("data.status") shouldBe status
            rest.jsonPath().getBoolean("data.result_unavailable") shouldBe resultUnavailable
        }
        val pipelineId = rest.jsonPath().getString("data.pipeline_id")
        val tool = mcpCall("tools/call", mapOf("name" to "executions_get", "arguments" to mapOf("execution_id" to executionId)))
        val text =
            tool
                .path("content")
                .path(0)
                .path("text")
                .asText()
        val record = mapper.readTree(text).let { if (it.has("data")) it.path("data") else it }
        withClue("$label MCP $text") {
            record.path("status").asText() shouldBe status
            record.path("result_unavailable").asBoolean() shouldBe resultUnavailable
        }
        val paged = pagedStatus(executionId, pipelineId)
        withClue("$label paged listing") { paged shouldBe status }
    }

    private fun pagedStatus(
        executionId: String,
        pipelineId: String,
    ): String? {
        for (offset in 0 until MAX_PAGES) {
            val page =
                given()
                    .port(port)
                    .asSession(session)
                    .`when`()
                    .get("/api/v1/executions?pipeline_id=$pipelineId&limit=1&offset=$offset")
                    .thenReturn()
            val items = page.jsonPath().getList<Map<String, Any?>>("data.items")
            if (items.isNullOrEmpty()) return null
            items.firstOrNull { it["execution_id"] == executionId }?.let { return it["status"] as String? }
        }
        return null
    }

    /** While the family is open the child's history says nothing terminal, on every surface. */
    fun assertNothingTerminal(
        executionId: String,
        label: String,
    ) {
        views(executionId).forEach { (surface, events) ->
            withClue("$label / $surface: $events") {
                events.isNotEmpty() shouldBe true
                events.map { it.name }.forEach { it shouldBeIn LIVE_NAMES }
            }
        }
    }

    /**
     * A root whose instance died after its commit was confirmed: the history ends at its `pipeline_completed` (SUCCESS) and
     * announces no result (`data_ready` was never delivered), identically on every surface.
     */
    fun assertCompletedWithoutResult(
        executionId: String,
        label: String,
    ) {
        val views = views(executionId)
        views.forEach { (surface, events) ->
            withClue("$label / $surface: $events") {
                events.last().name shouldBe "pipeline_completed"
                events.last().status shouldBe "SUCCESS"
                events.none { it.name == "data_ready" } shouldBe true
            }
        }
        withClue("$label: the three surfaces read the same history: $views") {
            views.getValue("replay") shouldContainExactly views.getValue("durable")
            views.getValue("mcp") shouldContainExactly views.getValue("durable")
        }
    }

    /** A root's history ends in exactly ONE terminal event, [terminalName] carrying [status], identically on every surface. */
    fun assertEndsAt(
        executionId: String,
        terminalName: String,
        status: String,
        label: String,
    ) {
        val views = views(executionId)
        views.forEach { (surface, events) ->
            withClue("$label / $surface: $events") {
                events.map { it.name }.count { it in TERMINAL_NAMES } shouldBe 1
                events.last().name shouldBe terminalName
                events.last().status shouldBe status
            }
        }
        withClue("$label: the three surfaces read the same history: $views") {
            views.getValue("replay") shouldContainExactly views.getValue("durable")
            views.getValue("mcp") shouldContainExactly views.getValue("durable")
        }
    }

    /** Once the root decided, the child's history ends in ONE terminal event carrying the root's outcome, on every surface alike. */
    fun assertSettledAs(
        executionId: String,
        terminalName: String,
        status: String,
        label: String,
    ) {
        val views = views(executionId)
        views.forEach { (surface, events) ->
            withClue("$label / $surface: $events") {
                events.map { it.name }.count { it in TERMINAL_NAMES } shouldBe 1
                events.last().name shouldBe terminalName
                events.last().status shouldBe status
                // The terminal event takes the next id: the provisional completion spent none, so no gap precedes it.
                events.map { it.id }.zipWithNext().forEach { (a, b) -> (b > a) shouldBe true }
                events.last().id shouldBe events.dropLast(1).last().id + 1
            }
        }
        withClue("$label: the three surfaces read the same history: $views") {
            views.getValue("replay") shouldContainExactly views.getValue("durable")
            views.getValue("mcp") shouldContainExactly views.getValue("durable")
        }
    }

    private companion object {
        const val EXCERPT = 300
        const val MAX_PAGES = 50
        val LIVE_NAMES = setOf("execution_started", "node_started", "node_progress", "node_completed")
        val TERMINAL_NAMES = setOf("pipeline_completed", "pipeline_failed", "execution_aborted", "data_ready")
    }
}
