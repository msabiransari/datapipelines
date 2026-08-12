package co.datapipelines.mcp

import co.datapipelines.pipeline.PipelineRecord
import co.datapipelines.pipeline.PipelineRepository
import com.fasterxml.jackson.databind.JsonNode
import io.modelcontextprotocol.spec.McpSchema
import java.util.UUID

/** `pipelines_list` (mcp-server.md §6.2.1). Scope: `read`. */
class PipelinesListTool(
    private val pipelines: PipelineRepository,
) : McpTool {
    override val definition: McpSchema.Tool =
        McpTools.tool(
            name = "pipelines_list",
            description =
                "List pipelines registered on this datapipelines.co instance, filtered by owner, datasource, or text " +
                    "search. Returns metadata (id, name, display_name, description, version, updated_at) — not the full " +
                    "body. Use pipelines_get for the body.",
            schema =
                """
                {
                  "type": "object",
                  "properties": {
                    "owner": {"type": "string", "description": "Filter by owner user ID."},
                    "datasource": {"type": "string", "description": "Filter by datasource name."},
                    "q": {"type": "string", "description": "Full-text search on name and description."},
                    "limit": {"type": "integer", "default": 50, "maximum": 200}
                  }
                }
                """.trimIndent(),
        )

    override fun call(
        args: McpArguments,
        ctx: McpToolContext,
    ): Any {
        val owner = args.uuid("owner")
        val query = args.string("q")?.lowercase()
        val datasource = args.string("datasource")
        val limit = args.int("limit", default = DEFAULT_LIMIT, min = 1, max = MAX_LIMIT)

        val records =
            if (datasource != null) {
                pipelines.findAllByDatasource(datasource, owner)
            } else {
                pipelines.findAll(owner)
            }

        return records
            .asSequence()
            .filter { query == null || it.matches(query) }
            .take(limit)
            .map { it.toMetadata() }
            .toList()
    }

    private fun PipelineRecord.matches(query: String): Boolean =
        name.lowercase().contains(query) ||
            displayName.lowercase().contains(query) ||
            description.lowercase().contains(query)

    private fun PipelineRecord.toMetadata(): Map<String, Any?> =
        mapOf(
            "id" to id.toString(),
            "name" to name,
            "display_name" to displayName,
            "description" to description,
            "version" to currentVersion,
            "owner_id" to ownerId.toString(),
            "updated_at" to updatedAt,
        )

    private companion object {
        const val DEFAULT_LIMIT = 50
        const val MAX_LIMIT = 200
    }
}

/**
 * `pipelines_get` (mcp-server.md §6.2.2). Scope: `read`.
 *
 * Returns the **pipeline JSON body** exactly as stored (pipeline-contract §3) — not a wrapper
 * carrying server-assigned fields, which §6.2.2 does not describe. `version` defaults to the
 * pipeline's current version.
 */
class PipelinesGetTool(
    private val pipelines: PipelineRepository,
) : McpTool {
    override val definition: McpSchema.Tool =
        McpTools.tool(
            name = "pipelines_get",
            description =
                "Get the full definition of a pipeline (latest version, or a specific version). Use this to read the " +
                    "pipeline body before executing or modifying it.",
            schema =
                """
                {
                  "type": "object",
                  "required": ["id"],
                  "properties": {
                    "id": {"type": "string", "format": "uuid", "description": "Pipeline ID."},
                    "version": {"type": "integer", "description": "Specific version. Defaults to latest."}
                  }
                }
                """.trimIndent(),
        )

    override fun call(
        args: McpArguments,
        ctx: McpToolContext,
    ): Any {
        val id = args.requiredUuid("id")
        val record = pipelines.findById(id) ?: throw McpNotFound.pipeline(id)
        return body(id, args.version() ?: record.currentVersion)
    }

    private fun body(
        id: UUID,
        version: Int,
    ): JsonNode {
        val json = pipelines.findVersionBody(id, version) ?: throw McpNotFound.pipelineVersion(id, version)
        return McpTools.readTree(json)
    }
}
