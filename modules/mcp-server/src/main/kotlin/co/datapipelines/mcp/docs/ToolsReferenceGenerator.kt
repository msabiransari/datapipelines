package co.datapipelines.mcp.docs

import co.datapipelines.mcp.McpTool
import co.datapipelines.mcp.McpToolCatalog
import com.fasterxml.jackson.databind.JsonNode
import io.modelcontextprotocol.json.McpJsonDefaults

/**
 * Renders the per-area tools reference (`<area>-tools`) from the SHIPPED tool surface —
 * `McpToolCatalog` for each tool's declared permission and `mutating` fact, the real tool
 * instance for its description and input schema (record §3.3 generator (b)).
 *
 * This is the SkillToolsDoc discipline moved into the served set: anything that enumerates
 * tools drifts, so the tools reference is not written at all — it is the catalog and the
 * shipped definitions, projected. Adding a tool to the catalog changes the rendered set with
 * no hand edit, and `DocSetStructureTest` holds the render to the catalog.
 *
 * The rendering of one tool (the permission · mutating line, the one-line description, the
 * argument table with each property's wire type) is the `references/tools.md` renderer's,
 * moved here unchanged so the text agents already read keeps its shape.
 */
object ToolsReferenceGenerator {
    private val mapper = McpJsonDefaults.getMapper()

    /** The generated `<area>-tools` document. */
    fun render(
        area: DocArea,
        tools: List<McpTool>,
    ): Doc {
        val byName = tools.associateBy { it.name }
        val ordered =
            McpToolCatalog.ENTRIES.mapNotNull { entry ->
                byName[entry.name]?.takeIf { DocArea.ofTool(it.name) == area }
            }
        val out = StringBuilder()
        out.append("# Tools — ${area.wire}\n\n")
        out.append(POINTER.trimIndent()).append("\n")
        var group: String? = null
        for (tool in ordered) {
            val toolGroup = tool.name.substringBefore('_')
            if (toolGroup != group) {
                group = toolGroup
                out.append("\n## ").append(toolGroup).append("\n")
            }
            out.append(renderTool(tool))
        }
        val markdown = out.toString()
        return Doc(
            name = "${area.wire}-tools",
            area = area,
            layer = DocLayer.REFERENCE,
            title = "Tools — ${area.wire}",
            purpose = PURPOSES.getValue(area),
            markdown = markdown,
            sections = MarkdownSections.cut(markdown),
        )
    }

    private fun renderTool(tool: McpTool): String {
        val permission =
            requireNotNull(McpToolCatalog.permissionOf(tool.name)) {
                "docs: tool '${tool.name}' is rendered but not catalogued — the catalog is the authority"
            }
        val out = StringBuilder()
        out.append("\n### `").append(tool.name).append("`\n\n")
        out
            .append("Permission `")
            .append(permission.wire)
            .append("` · ")
            .append(if (McpToolCatalog.isMutating(tool.name)) "**writes**" else "read-only")
            .append("\n\n")
        out.append(oneLine(tool.definition.description().orEmpty())).append("\n")

        val schema =
            co.datapipelines.mcp.McpTools
                .readTree(mapper.writeValueAsString(tool.definition.inputSchema()))
        val properties = schema.path("properties")
        if (properties.isObject && properties.size() > 0) {
            val required = schema.path("required").mapNotNull { it.asText() }.toSet()
            out.append("\n| Argument | Type | | What it is |\n|---|---|---|---|\n")
            properties.properties().forEach { field ->
                val name: String = field.key
                val node: JsonNode = field.value
                out
                    .append("| `")
                    .append(name)
                    .append("` | ")
                    .append(typeOf(node))
                    .append(" | ")
                    .append(if (name in required) "required" else "optional")
                    .append(" | ")
                    .append(cell(node.path("description").asText("")))
                    .append(" |\n")
            }
        } else {
            out.append("\nNo arguments.\n")
        }
        return out.toString()
    }

    /** The property's wire type, with the detail an agent needs to send a legal value. */
    private fun typeOf(node: JsonNode): String {
        val base =
            when {
                node.hasNonNull("type") && node["type"].isArray -> node["type"].joinToString("/") { it.asText() }
                node.hasNonNull("type") -> node["type"].asText()
                node.has("oneOf") -> "one of"
                else -> "any"
            }
        val items =
            node
                .path("items")
                .path("type")
                .asText("")
                .takeIf { it.isNotBlank() }
                ?.let { " of $it" }
                .orEmpty()
        val enum = node.path("enum").takeIf { it.isArray }?.joinToString(" \\| ") { "`${it.asText()}`" }
        val default =
            node
                .path("default")
                .takeIf { !it.isMissingNode }
                ?.let { ", default `$it`" }
                .orEmpty()
        return if (enum != null) "$base ($enum)$default" else "$base$items$default"
    }

    /** A table cell: one line, and never a raw `|`. */
    private fun cell(text: String): String = oneLine(text).replace("|", "\\|")

    private fun oneLine(text: String): String = text.replace(Regex("\\s+"), " ").trim()

    /**
     * The first paragraph of every tools reference: the set is per-area since 242a, and an
     * agent arriving through the old single `tools` name lands on `core-tools` and needs the
     * one sentence that re-orients it.
     */
    private const val POINTER =
        """
        Tools are documented per area — one `<area>-tools` reference each; `docs_list` lists them
        all. Permission is the auth §7.6 catalog permission the tool declares: your MCP key may
        call it when your role in the key's workspace holds it (a workspace admin's key acts as an
        author).
        """

    private val PURPOSES: Map<DocArea, String> =
        mapOf(
            DocArea.CORE to "The manual's own tools: the catalog and one document's retrieval.",
            DocArea.PIPELINES to "Author, read, run and check pipelines, and the calculator catalog.",
            DocArea.EXECUTIONS to "List, read, page and cancel runs.",
            DocArea.TEMPLATES to "The SQL template surface: list, read, write, render, purge drafts.",
            DocArea.TRANSFORMS to "Evaluate a transform template over a caller-supplied input.",
            DocArea.DATASOURCES to "Discovery, introspection, learned semantics and the SQL probe.",
            DocArea.LAKE to "The dp-lake registry writes: register, import, unregister.",
            DocArea.ENDPOINTS to "Publish and read the HTTP interfaces of released pipelines.",
        )
}
