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
                "List the SQL templates of the key's pinned workspace. Templates are reusable SQL generators authored in " +
                    "Freemarker; pipelines reference them by id+version. Template ids are unique per workspace — " +
                    "another workspace's template resolves as not-found.",
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
        val workspaceId = ctx.principal.requireWorkspace().id
        val limit = args.int("limit", default = TemplateRepository.DEFAULT_PAGE_LIMIT, min = 1, max = TemplateRepository.MAX_PAGE_LIMIT)
        val isLibrary = args.boolean("is_library")
        // `is_library` has no repository-level filter (templates.md §9 does not expose one), so it
        // is applied here. It narrows a page rather than paging past it — same visible-set rule the
        // REST listing has, and the alternative would be an unbounded scan.
        return templates
            .list(workspaceId, dialect = args.dialect("dialect"), q = args.string("q"), limit = limit)
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

/** `templates_get` (mcp-server.md §6.2.7). Scope: `read`.
 *
 * Since 039 the DEFAULT is the **working version** (versioning §7): the DRAFT when one
 * exists, else the latest released version — an agent that read released while a draft was
 * open would rebase on stale content with its next write. The returned projection states
 * its `version` and `status`; an explicit `version` argument still wins.
 */
class TemplatesGetTool(
    private val templates: TemplateRepository,
) : McpTool {
    override val definition: McpSchema.Tool =
        McpTools.tool(
            name = "templates_get",
            description =
                "Get the body and metadata of a template version, including its imports array (the library " +
                    "macros it can call). Defaults to the working version — the draft when unreleased edits " +
                    "exist, else the latest released.",
            schema =
                """
                {
                  "type": "object",
                  "required": ["id"],
                  "properties": {
                    "id": {"type": "string"},
                    "version": {"type": "integer", "description": "Specific version. Defaults to the working version: the draft when one exists, else the latest released."}
                  }
                }
                """.trimIndent(),
        )

    override fun call(
        args: McpArguments,
        ctx: McpToolContext,
    ): Any {
        val workspaceId = ctx.principal.requireWorkspace().id
        val id = args.requiredString("id")
        // The explicit argument wins BEFORE the working-version lookup (B3); the default
        // is the DRAFT when one exists, else the latest released (§7's template mirror).
        val version = args.version() ?: templates.findDraftDetail(workspaceId, id)?.version
        return when (version) {
            null -> templates.findLatest(workspaceId, id) ?: throw McpNotFound.template(id)
            else ->
                templates.findVersion(workspaceId, id, version)
                    ?: if (templates.existsId(workspaceId, id)) {
                        throw McpNotFound.templateVersion(id, version)
                    } else {
                        throw McpNotFound.template(id)
                    }
        }
    }
}
