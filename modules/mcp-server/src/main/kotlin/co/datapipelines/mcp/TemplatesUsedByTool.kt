package co.datapipelines.mcp

import co.datapipelines.templates.TemplateUsageService
import io.modelcontextprotocol.spec.McpSchema

/**
 * `templates_used_by` (mcp-server.md §6.2.8). Scope: `read` (040 D7 — it returns no customer
 * row data, only which pipelines reference which template version, which any workspace reader
 * may already see by reading the pipelines themselves).
 *
 * The reverse arrow of a node's `{id, version}` pin: which pipelines pin THIS template version
 * in their **working version** right now (040 D1 question 1 — the draft when one exists, else
 * the latest released, so a draft that just adopted the pin is counted). The answer names the
 * pipeline, the node id and the carrying pipeline version — something an author can go and
 * change, not just a count. "Is it safe to delete the template?" is a DIFFERENT question (any
 * pipeline version ever); its answer is the server's own `template.in_use` delete refusal, not
 * this tool.
 */
class TemplatesUsedByTool(
    private val usage: TemplateUsageService,
) : McpTool {
    override val definition: McpSchema.Tool =
        McpTools.tool(
            name = "templates_used_by",
            description =
                "Which pipelines pin a given template version in their working version (the draft when unreleased " +
                    "edits exist, else the latest released). Returns one reference per node — pipeline name and id, " +
                    "node id, and the pipeline version carrying the pin — plus the distinct pipeline count. Use it " +
                    "before editing or retiring a template version to see who you would affect. It does not answer " +
                    "'is it safe to delete' (that scan includes historical pipeline versions and lives in the " +
                    "delete refusal), and it never changes anything.",
            schema =
                """
                {
                  "type": "object",
                  "required": ["id", "version"],
                  "properties": {
                    "id": {"type": "string", "description": "Template id."},
                    "version": {"type": "integer", "description": "The pinned version to look for."}
                  },
                  "additionalProperties": false
                }
                """.trimIndent(),
        )

    override fun call(
        args: McpArguments,
        ctx: McpToolContext,
    ): Any {
        val workspaceId = ctx.principal.requireWorkspace().id
        val id = args.requiredString("id")
        // Never clamped (the McpArguments.version rule): an off-by-one version must refuse,
        // not silently answer for a neighbouring version.
        val version = args.version() ?: throw McpArguments.invalidParams("Missing required argument 'version'.")
        val used = usage.usedBy(workspaceId, id, version)
        return mapOf(
            "template" to mapOf("id" to used.templateId, "version" to used.version),
            "scan" to "working_version",
            "pipeline_count" to used.pipelineCount,
            "references" to
                used.references.map {
                    mapOf(
                        "pipeline" to it.pipelineName,
                        "pipeline_id" to it.pipelineId.toString(),
                        "node_id" to it.nodeId,
                        "pipeline_version" to it.pipelineVersion,
                        "pipeline_version_status" to it.versionStatus.name,
                    )
                },
        )
    }
}
