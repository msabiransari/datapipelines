package co.datapipelines.mcp

import co.datapipelines.pipeline.TemplateType
import co.datapipelines.templates.Template
import co.datapipelines.templates.TemplateNameGrammar
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

/** §6.2.6 — the `type` filter description (046 §10). */
private const val TYPE_FILTER_DESC = "Filter by template kind: 'sql' (pipeline-referenced SQL) or 'html' (rendered output)."

/**
 * `templates_list` (mcp-server.md §6.2.6). Scope: `read`.
 *
 * ## Two presentations, chosen by `prefix` (067)
 *
 * Template names have been paths since 043 and the templates BROWSER has rendered them as a
 * tree since 047 — but this tool had `q`/`dialect`/`type`/`is_library` and **no way to browse a
 * folder** (verified 2026-09-04). An agent could search for a name it already knew and could
 * not ask "what roots does this workspace use?", which is exactly the question the folder
 * convention needs answered before a new asset is named.
 *
 * - **`prefix` absent** → the flat listing under the existing filters, unchanged.
 * - **`prefix` present** (`""` is the ROOT) → **one level**: that prefix's direct sub-folders
 *   with their subtree counts, and its direct template leaves. The `dialect`/`type` filters
 *   still narrow both halves, so a folder whose whole subtree is filtered out is absent rather
 *   than empty (§9.1). `q` is ignored while `prefix` is present — browse and search are
 *   different presentations (§9.2).
 *
 * `is_library` stays a post-filter over the level's leaves, exactly as it is over the flat
 * page: the repository exposes no such filter (templates.md §9) and it narrows a page rather
 * than paging past it. It is deliberately NOT applied to the folder counts — a count is over
 * the whole subtree, and silently subtracting library templates from it would make the tree
 * disagree with what expanding the folder shows.
 */
class TemplatesListTool(
    private val templates: TemplateRepository,
) : McpTool {
    override val definition: McpSchema.Tool =
        McpTools.tool(
            name = "templates_list",
            description =
                "List the templates of the key's pinned workspace. Templates are reusable generators authored in " +
                    "Freemarker, referenced by id+version; each has a fixed type — 'sql' renders SQL for pipeline " +
                    "nodes (and carries a dialect), 'html' renders escaped output and declares none. Template ids are " +
                    "unique per workspace — another workspace's template resolves as not-found.",
            schema =
                """
                {
                  "type": "object",
                  "properties": {
                    "dialect": {"type": "string", "enum": $DIALECT_ENUM_JSON},
                    "type": {"type": "string", "enum": ["sql", "html"], "description": "$TYPE_FILTER_DESC"},
                    "q": {"type": "string"},
                    "prefix": {"type": "string", "description": "$PREFIX_ARG_DESC"},
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
        val dialect = args.dialect("dialect")
        val type = args.string("type")?.let { TemplateType.fromWire(it) }
        // `has` and not `string`: `prefix: ""` is PRESENT and means the root, while
        // `string("prefix")` normalizes blank to null. The distinction is the whole browse
        // contract — absent is "flat list", empty is "the tree's top level".
        if (args.has("prefix")) return level(workspaceId, args.string("prefix"), dialect, type, isLibrary, limit)
        // `is_library` has no repository-level filter (templates.md §9 does not expose one), so it
        // is applied here. It narrows a page rather than paging past it — same visible-set rule the
        // REST listing has, and the alternative would be an unbounded scan.
        return templates
            .list(
                workspaceId,
                dialect = dialect,
                type = type,
                q = args.string("q"),
                limit = limit,
            ).filter { isLibrary == null || it.isLibrary == isLibrary }
            .map { it.toMetadata() }
    }

    /**
     * ONE level of the template tree, in the same shape `pipelines_list` returns for a prefix
     * and the templates explorer renders from.
     *
     * A prefix that is not a legal template name cannot name a real folder, so it answers an
     * ordinary EMPTY level rather than an error — the rule `TemplateBrowseModel.fillLevel`
     * settled on, restated here rather than re-decided.
     */
    @Suppress("LongParameterList") // one filter per schema property; a parameter object would just re-list them
    private fun level(
        workspaceId: java.util.UUID,
        prefix: String?,
        dialect: Dialect?,
        type: TemplateType?,
        isLibrary: Boolean?,
        limit: Int,
    ): Map<String, Any?> {
        if (prefix != null && !TemplateNameGrammar.matches(prefix)) {
            return mapOf(
                "prefix" to prefix,
                "folders" to emptyList<Map<String, Any?>>(),
                "templates" to emptyList<Map<String, Any?>>(),
                "total" to 0,
                "has_more" to false,
            )
        }
        val folders = templates.listChildFolders(workspaceId, prefix, dialect, type, limit = TemplateRepository.MAX_PAGE_LIMIT)
        val probe = templates.listChildTemplates(workspaceId, prefix, dialect, type, offset = 0, limit = limit + 1)
        return mapOf(
            "prefix" to prefix.orEmpty(),
            "folders" to folders.map { mapOf("path" to it.path, "segment" to it.segment, "template_count" to it.templateCount) },
            "templates" to probe.take(limit).filter { isLibrary == null || it.isLibrary == isLibrary }.map { it.toMetadata() },
            "total" to templates.countChildTemplates(workspaceId, prefix, dialect, type),
            "has_more" to (probe.size > limit),
        )
    }

    /**
     * The §6.2.6 projection. A template's `description` is the only place it can hint at the
     * variables it expects — templates declare none (templates.md §3.2) — so it is always listed.
     */
    private fun Template.toMetadata(): Map<String, Any?> =
        mapOf(
            "id" to id,
            "version" to version,
            "type" to type.wire,
            "dialect" to dialect?.wire,
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
            null -> {
                templates.findLatest(workspaceId, id) ?: throw McpNotFound.template(id)
            }

            else -> {
                templates.findVersion(workspaceId, id, version)
                    ?: if (templates.existsId(workspaceId, id)) {
                        throw McpNotFound.templateVersion(id, version)
                    } else {
                        throw McpNotFound.template(id)
                    }
            }
        }
    }
}
