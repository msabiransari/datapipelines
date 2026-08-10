package co.datapipelines.mcp

import co.datapipelines.templates.Template
import co.datapipelines.templates.TemplateRepository
import co.datapipelines.typesystem.Dialect
import io.modelcontextprotocol.spec.McpSchema

/** The `dialect` enum, restated in every schema that accepts one (§6.2.6, §6.2.8). */
internal const val DIALECT_ENUM_JSON: String = """["POSTGRES", "ORACLE", "MSSQL", "MYSQL", "H2", "DUCKDB", "SQLITE"]"""

/** Parses a `dialect` argument against the seven supported values (enums.md §5). */
internal fun McpArguments.dialect(name: String): Dialect? =
    enumString(name, Dialect.entries.map { it.wire }.toSet())?.let { Dialect.fromWire(it) }

/** §6.2.6 — the `is_library` filter description, kept off the schema line for length. */
private const val IS_LIBRARY_FILTER_DESC = "Filter to library templates (macro collections) or executable templates."

/** `templates_list` (mcp-server.md §6.2.6). Scope: `read`. */
class TemplatesListTool(
    private val templates: TemplateRepository,
) : McpTool {
    override val definition: McpSchema.Tool =
        McpTools.tool(
            name = "templates_list",
            description =
                "List SQL templates registered on this instance. Templates are reusable SQL generators authored in " +
                    "Freemarker; pipelines reference them by id+version.",
            schema =
                """
                {
                  "type": "object",
                  "properties": {
                    "dialect": {"type": "string", "enum": $DIALECT_ENUM_JSON},
                    "q": {"type": "string"},
                    "is_library": {"type": "boolean", "description": "$IS_LIBRARY_FILTER_DESC"},
                    "limit": {"type": "integer", "default": 50, "maximum": 200}
                  }
                }
                """.trimIndent(),
        )

    override fun call(
        args: McpArguments,
        ctx: McpToolContext,
    ): Any {
        val limit = args.int("limit", default = TemplateRepository.DEFAULT_PAGE_LIMIT, min = 1, max = TemplateRepository.MAX_PAGE_LIMIT)
        val isLibrary = args.boolean("is_library")
        // `is_library` has no repository-level filter (templates.md §9 does not expose one), so it
        // is applied here. It narrows a page rather than paging past it — same visible-set rule the
        // REST listing has, and the alternative would be an unbounded scan.
        return templates
            .list(dialect = args.dialect("dialect"), q = args.string("q"), limit = limit)
            .filter { isLibrary == null || it.isLibrary == isLibrary }
            .map { it.toMetadata() }
    }

    /**
     * The §6.2.6 projection. A template's `description` is the only place it can hint at the
     * variables it expects — templates declare none (templates.md §3.2) — so it is always listed.
     */
    private fun Template.toMetadata(): Map<String, Any?> =
        mapOf(
            "id" to id,
            "version" to version,
            "dialect" to dialect.wire,
            "display_name" to displayName,
            "description" to description,
            "is_library" to isLibrary,
        )
}

/** `templates_get` (mcp-server.md §6.2.7). Scope: `read`. */
class TemplatesGetTool(
    private val templates: TemplateRepository,
) : McpTool {
    override val definition: McpSchema.Tool =
        McpTools.tool(
            name = "templates_get",
            description =
                "Get the body and metadata of a specific template version, including its imports array (the library " +
                    "macros it can call). Defaults to the latest version.",
            schema =
                """
                {
                  "type": "object",
                  "required": ["id"],
                  "properties": {
                    "id": {"type": "string"},
                    "version": {"type": "integer", "description": "Defaults to latest."}
                  }
                }
                """.trimIndent(),
        )

    override fun call(
        args: McpArguments,
        ctx: McpToolContext,
    ): Any {
        val id = args.requiredString("id")
        val version = args.version() ?: return templates.findLatest(id) ?: throw McpNotFound.template(id)
        return templates.findVersion(id, version)
            ?: if (templates.existsId(id)) throw McpNotFound.templateVersion(id, version) else throw McpNotFound.template(id)
    }
}
