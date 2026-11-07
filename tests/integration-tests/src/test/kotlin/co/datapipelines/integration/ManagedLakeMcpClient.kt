package co.datapipelines.integration

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.matchers.shouldBe
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** One real JSON-RPC tools/call over the existing stateless MCP transport, with no internal module dependency. */
internal class ManagedLakeMcpClient(
    private val port: Int,
    private val key: String,
) {
    private val json = ObjectMapper()

    fun call(
        tool: String,
        arguments: Map<String, Any?>,
    ): Pair<JsonNode, Boolean> {
        val body =
            json.writeValueAsString(
                mapOf(
                    "jsonrpc" to "2.0",
                    "id" to 1,
                    "method" to "tools/call",
                    "params" to mapOf("name" to tool, "arguments" to arguments),
                ),
            )
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port/mcp"))
                .timeout(Duration.ofSeconds(45))
                .header("DP-API-Key", key)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
        val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
        response.statusCode() shouldBe 200
        val result = json.readTree(response.body()).path("result")
        val text =
            result
                .path("content")
                .path(0)
                .path("text")
                .asText()
        check(text.isNotEmpty()) { "MCP response contained no tool payload." }
        return json.readTree(text) to result.path("isError").asBoolean(false)
    }
}
