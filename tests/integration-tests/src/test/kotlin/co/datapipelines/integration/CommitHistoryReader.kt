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
        val body =
            mapper.writeValueAsString(
                mapOf(
                    "jsonrpc" to "2.0",
                    "id" to 1,
                    "method" to "resources/read",
                    "params" to mapOf("uri" to "datapipelines://executions/$executionId/events"),
                ),
            )
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
        withClue("mcp ${raw.take(EXCERPT)}") { parsed.has("error") shouldBe false }
        return parseFrames(
            parsed
                .path("result")
                .path("contents")
                .path(0)
                .path("text")
                .asText(),
        )
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
        val LIVE_NAMES = setOf("execution_started", "node_started", "node_progress", "node_completed")
        val TERMINAL_NAMES = setOf("pipeline_completed", "pipeline_failed", "execution_aborted", "data_ready")
    }
}
