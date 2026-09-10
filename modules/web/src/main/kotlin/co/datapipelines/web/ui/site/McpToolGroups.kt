package co.datapipelines.web.ui.site

import co.datapipelines.auth.ScopeMatrix
import co.datapipelines.mcp.McpToolCatalog

/** One row of the public tool list: the name, the scope the matrix requires, whether it writes. */
data class ToolRow(
    val name: String,
    val scope: String,
    val mutating: Boolean,
)

/** A family of tools on the `/mcp-tools` page, keyed by the name prefix the catalogue uses. */
data class ToolGroup(
    val key: String,
    val title: String,
    val tools: List<ToolRow>,
)

/**
 * The `/mcp-tools` page's data — derived from [McpToolCatalog.ENTRIES] (the list the server
 * ships, in `tools/list` order) and [ScopeMatrix.requiredScopeForTool] (the authority for the
 * scope column). Nothing here is typed by hand, so the page cannot list a tool that does not
 * exist or misstate its scope; `SiteMcpToolsPageTest` pins that every catalogue name appears.
 */
object McpToolGroups {
    private val ORDER: List<Pair<String, String>> =
        listOf(
            "pipelines" to "Pipelines — author, run, inspect",
            "templates" to "Templates — the SQL an agent writes",
            "datasources" to "Datasources — what the agent may see",
            "lake_tables" to "dp-lake — tables over your bucket",
            "sql_probe" to "The SQL probe",
            "executions" to "Executions — results and cancellation",
            "endpoints" to "Published endpoints",
            "calculators" to "Calculators",
        )

    fun groups(): List<ToolGroup> {
        val rows =
            McpToolCatalog.ENTRIES.map { e ->
                ToolRow(e.name, ScopeMatrix.requiredScopeForTool(e.name)?.wire ?: "—", e.mutating)
            }
        val byKey = rows.groupBy { keyOf(it.name) }
        val known = ORDER.map { (key, title) -> ToolGroup(key, title, byKey[key].orEmpty()) }.filter { it.tools.isNotEmpty() }
        val leftover = byKey.keys - ORDER.map { it.first }.toSet()
        // A prefix the ORDER above does not know still renders — under its own name — rather
        // than vanish; the page must list every tool the server ships.
        return known + leftover.sorted().map { key -> ToolGroup(key, key, byKey.getValue(key)) }
    }

    private fun keyOf(name: String): String =
        when {
            name.startsWith("lake_tables_") -> "lake_tables"
            name == "sql_probe" -> "sql_probe"
            else -> name.substringBefore('_')
        }
}
