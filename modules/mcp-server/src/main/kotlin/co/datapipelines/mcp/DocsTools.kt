package co.datapipelines.mcp

import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException
import io.modelcontextprotocol.spec.McpSchema

/**
 * `docs_list` (mcp-server.md §6.2.40) — the skill's document catalog, as a TOOL (120, owner
 * ruling R3). Permission: `docs.read`.
 *
 * The skill has been served as MCP resources since 095, but resources are a weak surface:
 * several MCP clients fetch them reluctantly or never, while every client calls tools. This
 * and [DocsGetTool] serve EXACTLY what the resources serve, from the same [SkillDocs] loader,
 * so the two surfaces cannot answer differently.
 *
 * The `read` floor is the [CalculatorsListTool] reasoning: the answer is a property of the
 * BUILD — the manual this deployment ships — identical for every caller, every key and every
 * workspace.
 */
class DocsListTool : McpTool {
    override val definition: McpSchema.Tool =
        McpTools.tool(
            name = "docs_list",
            description =
                "The datapipelines skill's document catalog: the operating core (`skill`) first, then every " +
                    "reference in the order the skill's own map lists them, each with its title and a one-line " +
                    "purpose (when to open it). The same documents the datapipelines://docs/skill resources " +
                    "serve, for clients that fetch resources reluctantly or never. Read-only.",
            schema =
                """
                {
                  "type": "object",
                  "properties": {},
                  "additionalProperties": false
                }
                """.trimIndent(),
        )

    override fun call(
        args: McpArguments,
        ctx: McpToolContext,
    ): Any =
        SkillDocs.catalog.map { entry ->
            mapOf("name" to entry.name, "title" to entry.title, "purpose" to entry.purpose)
        }
}

/**
 * `docs_get` (mcp-server.md §6.2.41) — one skill document's full markdown: the same bytes the
 * `datapipelines://docs/skill/<name>` resource serves (asserted byte-for-byte in
 * `DocsToolsTest`). Permission: `docs.read`.
 *
 * An unknown name is `mcp.doc_not_found` with the catalogued names in the detail — the
 * tool-surface answer to the resource read's RESOURCE_NOT_FOUND, which is a protocol-level
 * error a tool result cannot carry (§9.2 errors are content, with a §13 code).
 */
class DocsGetTool : McpTool {
    override val definition: McpSchema.Tool =
        McpTools.tool(
            name = "docs_get",
            description =
                "One datapipelines skill document's full markdown: `name` is `skill` for the operating core " +
                    "or a reference name from docs_list. Returns {name, title, markdown} — the same bytes the " +
                    "datapipelines://docs/skill/<name> resource serves. An unknown name is refused with the " +
                    "catalogued names in the error detail. Read-only.",
            schema =
                """
                {
                  "type": "object",
                  "required": ["name"],
                  "properties": {
                    "name": {"type": "string", "description": "The document name: `skill` for the operating core, or a reference name from docs_list, e.g. authoring-playbook."}
                  },
                  "additionalProperties": false
                }
                """.trimIndent(),
        )

    override fun call(
        args: McpArguments,
        ctx: McpToolContext,
    ): Any {
        // The `.md` tolerance is SkillDocs.reference's own: the bare form is canonical, the
        // suffixed form is what someone copying a file name types.
        val name = args.requiredString("name").removeSuffix(".md")
        val markdown =
            if (name == SkillDocs.SKILL_NAME) {
                SkillDocs.skill
            } else {
                SkillDocs.reference(name)
                    ?: throw DatapipelinesException(
                        code = PipelineErrorCodes.Mcp.DOC_NOT_FOUND,
                        message = "No skill doc named '$name'.",
                        details = mapOf("name" to name, "known_docs" to SkillDocs.catalog.map { it.name }),
                    )
            }
        return mapOf(
            "name" to name,
            "title" to SkillDocs.catalog.first { it.name == name }.title,
            "markdown" to markdown,
        )
    }
}

/**
 * Both docs tools, in catalog order (120/R3) — the production bean and the wiring fixtures
 * append them as one, the [EndpointsTools] / [LakeTableTools] / [SemanticsTools] pattern.
 */
object DocsTools {
    fun all(): List<McpTool> = listOf(DocsListTool(), DocsGetTool())
}
