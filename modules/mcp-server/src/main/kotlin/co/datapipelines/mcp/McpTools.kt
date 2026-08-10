package co.datapipelines.mcp

import co.datapipelines.executor.ExecutorJson
import com.fasterxml.jackson.databind.JsonNode
import io.modelcontextprotocol.json.McpJsonDefaults
import io.modelcontextprotocol.spec.McpSchema

/**
 * Shared construction helpers for the tool surface (mcp-server.md §6.2).
 *
 * Tool input schemas are written as the **literal JSON of the spec** rather than assembled from
 * Kotlin maps: §6.2 is the contract an agent reads, and a hand-built map is one refactor away
 * from silently disagreeing with it. The SDK parses them through its own json mapper
 * ([McpJsonDefaults.getMapper], supplied by `mcp-json-jackson2`).
 */
object McpTools {
    /** Builds a tool definition from its §6.2 name, description and inputSchema JSON. */
    fun tool(
        name: String,
        description: String,
        schema: String,
    ): McpSchema.Tool =
        McpSchema.Tool
            .builder(name, McpJsonDefaults.getMapper(), schema)
            .description(description)
            .build()

    /** Parses stored entity JSON (a pipeline body) so it is re-emitted as JSON, not as a string. */
    fun readTree(json: String): JsonNode = ExecutorJson.mapper.readTree(json)
}
