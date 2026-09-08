package co.datapipelines.mcp

import co.datapipelines.auth.ScopeMatrix
import com.fasterxml.jackson.databind.JsonNode
import io.modelcontextprotocol.json.McpJsonDefaults

/**
 * Renders `references/tools.md` of the agent skill from the SHIPPED tool surface (095 §B).
 *
 * ## Why this is generated
 *
 * Anything that enumerates tools drifts. The skill's own prose said "31 tools" and listed 31
 * names after 094 removed `datasources_create`; the marketing page said "28" twice; the docs
 * index still says 31 as this is written. Every one of those was a hand-typed copy of a list
 * that lives in [McpToolCatalog], and every one went stale silently, because nothing compares
 * prose to the catalog.
 *
 * So the skill does not TYPE the tool list any more. This renderer reads the real
 * `mcpTools` bean's output ([realShippedTools]) — name, description, input schema — joins it
 * with the auth §7.6 minimum scope ([ScopeMatrix.requiredScopeForTool]) and the catalog's
 * `mutating` declaration, and writes the file. `SkillToolsDocDriftTest` fails when the
 * committed file is not what this produces, and `./gradlew :modules:mcp-server:skillToolsDoc`
 * rewrites it. Adding a tool then costs the skill nothing.
 *
 * ## Why it lives in the test source set
 *
 * The precedent is web's `SiteExportMain`/`SitePageRenderer`: a build-time renderer that needs
 * test-only machinery (here, the mocked collaborators [realShippedTools] hands the real
 * `@Bean` method) belongs beside the tests, not in the production jar. Nothing on the serving
 * path calls it — the server ships the file, not the renderer.
 *
 * ## Grouping
 *
 * Groups are the catalog's own grouping, derived rather than re-declared: a tool's group is
 * its name's first segment (`pipelines_execute` → `pipelines`, `lake_tables_register` →
 * `lake`), and the groups appear in the order [McpToolCatalog.ENTRIES] first mentions them —
 * which is `tools/list` order, which is mcp-server.md §6.1's order. A tool added to the
 * catalog therefore lands in the right place with no edit here.
 */
object SkillToolsDoc {
    /** The generated file's path inside the skill, relative to the repository root. */
    const val PATH: String = ".agents/skills/datapipelines/references/tools.md"

    private val mapper = McpJsonDefaults.getMapper()

    /** The whole file, for [tools] in catalog order. */
    fun render(tools: List<McpTool>): String {
        val byName = tools.associateBy { it.name }
        val ordered = McpToolCatalog.ENTRIES.map { it to byName.getValue(it.name) }
        val out = StringBuilder()
        out.append(HEADER.trimIndent()).append("\n\n")
        out.append("There are **${McpToolCatalog.NAMES.size} tools**, in `tools/list` order.\n")

        var group: String? = null
        for ((entry, tool) in ordered) {
            val toolGroup = groupOf(entry.name)
            if (toolGroup != group) {
                group = toolGroup
                out.append("\n## ").append(toolGroup).append("\n")
            }
            out.append(renderTool(entry, tool))
        }
        return out.toString()
    }

    private fun renderTool(
        entry: McpToolCatalog.Entry,
        tool: McpTool,
    ): String {
        val out = StringBuilder()
        out.append("\n### `").append(entry.name).append("`\n\n")
        out
            .append("Scope `")
            .append(ScopeMatrix.requiredScopeForTool(entry.name)?.wire ?: "—")
            .append("` · ")
            .append(if (entry.mutating) "**writes**" else "read-only")
            .append("\n\n")
        out.append(oneLine(tool.definition.description().orEmpty())).append("\n")

        val schema = McpTools.readTree(mapper.writeValueAsString(tool.definition.inputSchema()))
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

    /** `pipelines_execute` → `pipelines`; `lake_tables_register` → `lake`. */
    private fun groupOf(name: String): String = name.substringBefore('_')

    private const val HEADER =
        """
        # The MCP tools

        Open when you need a tool's exact arguments, its scope, or whether calling it writes.

        Part of the `datapipelines` skill — the operating core is `SKILL.md` beside this file.

        **This file is GENERATED** from the server's own tool catalog
        (`./gradlew :modules:mcp-server:skillToolsDoc`), so it cannot describe a surface the
        server does not ship. Do not edit it by hand; a drift test fails if you do. Scope is the
        auth §7.6 minimum: scopes are hierarchical (`admin ⊃ author ⊃ execute ⊃ read`), so a key
        with a higher scope satisfies a lower requirement.
        """
}
