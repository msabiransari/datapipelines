package co.datapipelines.mcp.docs

/**
 * The product's functional areas — the record's §3.2 catalog (agent-docs-by-area design,
 * ratified 2026-09-25). Each shipped tool belongs to exactly ONE area, derived from its
 * catalog name's prefix; a document names its area in its front matter, and `docs_list`
 * groups by it.
 *
 * The area is a catalog attribute, never a path segment (owner ruling O3): document names
 * stay flat (`<area>` for a guide, `<area>-<topic>` for a reference) so the public HTTP glob
 * (the /skill wildcard) and the datapipelines docs resource URIs keep their shapes.
 */
enum class DocArea(
    /** The wire value `docs_list` answers with and its `area` filter takes. */
    val wire: String,
) {
    CORE("core"),
    PIPELINES("pipelines"),
    EXECUTIONS("executions"),
    TEMPLATES("templates"),
    TRANSFORMS("transforms"),
    DATASOURCES("datasources"),
    LAKE("lake"),
    ENDPOINTS("endpoints"),
    ;

    companion object {
        /**
         * The reserved areas (record §3.2): no shipped tool may map to one, and no document
         * may declare one before the lane that ships the capability adds the area to [DocArea].
         * Declared next to the catalog so the two fail together — a reserved prefix that grew
         * a tool without growing the catalog is exactly the drift this package exists to catch,
         * and [DocRenderer] refuses to render such a set.
         */
        val RESERVED_PREFIXES: Set<String> = setOf("scheduling", "reporting", "dashboards")

        /**
         * The area a shipped tool belongs to, from its catalog name (the record §3.2's table).
         *
         * Most tools derive from their name's first segment (`pipelines_execute` →
         * `pipelines`, `lake_tables_register` → `lake`). The exceptions are the record's own
         * placements, each a one-line ruling: `calculators_*` are DAG-authoring tools
         * (pipelines), `semantics_*` and `sql_probe` are discovery (datasources), and
         * `templates_evaluate` is the transform evaluator (transforms).
         */
        fun ofTool(toolName: String): DocArea {
            val prefix = toolName.substringBefore('_')
            require(prefix !in RESERVED_PREFIXES) {
                "docs: tool '$toolName' maps to the reserved area '$prefix' — the record says the " +
                    "area is added by the lane that ships the capability, never listed before (§3.2)"
            }
            return when (prefix) {
                "calculators" -> PIPELINES

                "semantics" -> DATASOURCES

                "docs" -> CORE

                "templates" -> if (toolName == "templates_evaluate") TRANSFORMS else TEMPLATES

                "pipelines" -> PIPELINES

                "executions" -> EXECUTIONS

                "datasources" -> DATASOURCES

                "lake" -> LAKE

                "endpoints" -> ENDPOINTS

                "sql" -> DATASOURCES

                else -> throw IllegalStateException(
                    "docs: tool '$toolName' has no area in the record §3.2 catalog — add the mapping " +
                        "here in the same commit that ships the tool",
                )
            }
        }
    }
}
