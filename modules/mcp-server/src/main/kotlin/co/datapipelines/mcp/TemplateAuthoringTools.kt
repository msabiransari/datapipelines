package co.datapipelines.mcp

import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.templates.Template
import co.datapipelines.templates.TemplateDraft
import co.datapipelines.templates.TemplateImport
import co.datapipelines.templates.TemplateRepository
import co.datapipelines.templates.TemplateValidator
import co.datapipelines.templates.WorkspaceTemplateEngines
import io.modelcontextprotocol.spec.McpSchema

/** §6.2.8 — the `description` field's own description, kept off the schema line for length. */
private const val DESCRIPTION_FIELD_DESC =
    "Free text. State the variables the body expects and their types — the template declares none."

/** §6.2.8 — the `imports` array description. */
private const val IMPORTS_DESC =
    "Library templates whose macros this body calls. Aliases must be unique within the template; each referenced " +
        "template must exist at that exact version and be is_library=true."

/** §6.2.8 — the `is_library` description, verbatim from the frozen doc. */
private const val IS_LIBRARY_DESC =
    "true if this template exists to be imported by others. A library body contains only " +
        "<#macro>/<#function> definitions — no output outside macro definitions. body is still required."

/** §6.2.8 — the `imports[].alias` description, verbatim from the frozen doc. */
private const val ALIAS_DESC = "Namespace the macros are bound to, e.g. 'dates' → <@dates.date_range .../>."

/** §6.2.8 — the `body` description, verbatim from the frozen doc. */
private const val BODY_DESC = "Template source. Must not contain <#import> or <#include>."

/** §6.2.9 — the render `context` description. */
private const val RENDER_CONTEXT_DESC =
    "Render context: the parameter map a calling pipeline would provide, defaults already applied. Values follow the " +
        "wire conventions of the Type System (BIGINTEGER/BIGDECIMAL as strings, TIMESTAMP with Z or offset)."

/**
 * `templates_create` (mcp-server.md §6.2.8). Scope: `author`.
 *
 * Save-time validation is **parse-only** (templates.md §7.1): syntax, forbidden constructs,
 * import resolution. A template is never rendered against a sample context at save time because
 * it does not know its callers' parameters — which is why an authoring agent is told to call
 * `templates_render` next.
 */
class TemplatesCreateTool(
    private val templates: TemplateRepository,
    private val validator: TemplateValidator,
) : McpTool {
    override val definition: McpSchema.Tool =
        McpTools.tool(
            name = "templates_create",
            description =
                "Create a new SQL template. Templates use Freemarker syntax. A template declares NO parameters of its " +
                    "own: the variables its body may reference are exactly the parameters declared by the pipeline that " +
                    "calls it, with defaults applied. Describe the variables you expect in 'description' — that free " +
                    "text is how humans and agents discover them. Macros from library templates are made available by " +
                    "listing them in 'imports'; the body must NOT contain import or include directives, they are " +
                    "synthesized from the imports array.",
            schema = SCHEMA,
        )

    override fun call(
        args: McpArguments,
        ctx: McpToolContext,
    ): Any {
        val workspaceId = ctx.principal.requireWorkspace().id
        val draft =
            TemplateDraft(
                id = args.string("id"),
                engine = args.enumString("engine", setOf(Template.FREEMARKER_ENGINE), Template.FREEMARKER_ENGINE)!!,
                dialect = args.dialect("dialect") ?: throw McpArguments.invalidParams("Missing required argument 'dialect'."),
                displayName = args.requiredString("display_name"),
                description = args.requiredString("description"),
                imports = imports(args),
                body = args.requiredString("body"),
                isLibrary = args.boolean("is_library") ?: false,
            )
        return templates.create(workspaceId, validator.validateOrThrow(draft, workspaceId), ctx.principal.userId)
    }

    /** `imports: [{id, version, alias}]` (D12). Shape errors are `-32602`, not validation failures. */
    private fun imports(args: McpArguments): List<TemplateImport> =
        args.listArg("imports").orEmpty().map { entry ->
            val map = entry as? Map<*, *> ?: throw McpArguments.invalidParams("Each 'imports' entry must be an object.")
            TemplateImport(
                id = map["id"] as? String ?: throw McpArguments.invalidParams("An 'imports' entry is missing 'id'."),
                version =
                    (map["version"] as? Number)?.toInt()
                        ?: throw McpArguments.invalidParams("An 'imports' entry is missing 'version'."),
                alias = map["alias"] as? String ?: throw McpArguments.invalidParams("An 'imports' entry is missing 'alias'."),
            )
        }

    private companion object {
        val SCHEMA =
            """
            {
              "type": "object",
              "required": ["dialect", "display_name", "description", "body"],
              "properties": {
                "id": {"type": "string", "description": "Optional; auto-generated if omitted. Pattern [a-z0-9_.-]+."},
                "engine": {
                  "type": "string", "enum": ["freemarker"], "default": "freemarker",
                  "description": "Template engine. v1 supports freemarker only."
                },
                "dialect": {"type": "string", "enum": $DIALECT_ENUM_JSON},
                "display_name": {"type": "string"},
                "description": {"type": "string", "description": "$DESCRIPTION_FIELD_DESC"},
                "imports": {
                  "type": "array",
                  "description": "$IMPORTS_DESC",
                  "items": {
                    "type": "object",
                    "required": ["id", "version", "alias"],
                    "properties": {
                      "id": {"type": "string"},
                      "version": {"type": "integer"},
                      "alias": {"type": "string", "description": "$ALIAS_DESC"}
                    },
                    "additionalProperties": false
                  }
                },
                "is_library": {"type": "boolean", "default": false, "description": "$IS_LIBRARY_DESC"},
                "body": {"type": "string", "description": "$BODY_DESC"}
              },
              "additionalProperties": false
            }
            """.trimIndent()
    }
}

/**
 * `templates_render` (mcp-server.md §6.2.9). Scope: `author`.
 *
 * A preview: nothing is executed and nothing is stored. Referencing a key absent from the context
 * fails the render with the same failure a pipeline save would report (templates.md §7.2).
 *
 * §6.2.9 pins the return as the **rendered SQL string** — not an object. The doc pins object
 * shapes where it means to (§6.2.12 spells one out), so the bare string is the contract, and the
 * agent already holds the id and version it passed in.
 */
class TemplatesRenderTool(
    private val templates: TemplateRepository,
    private val engines: WorkspaceTemplateEngines,
) : McpTool {
    override val definition: McpSchema.Tool =
        McpTools.tool(
            name = "templates_render",
            description =
                "Render a template against the provided context values and return the SQL it produces. Use this to " +
                    "preview generated SQL before creating a pipeline that references the template. The context is a " +
                    "free-form map: supply the same keys the calling pipeline would declare as parameters. Referencing " +
                    "a key absent from the context fails the render — that is the same failure a pipeline save would " +
                    "report.",
            schema =
                """
                {
                  "type": "object",
                  "required": ["id", "context"],
                  "properties": {
                    "id": {"type": "string"},
                    "version": {"type": "integer", "description": "Defaults to latest."},
                    "context": {
                      "type": "object",
                      "description": "$RENDER_CONTEXT_DESC",
                      "additionalProperties": true
                    }
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
        val version = resolveVersion(args, workspaceId, id)
        return engines.engineFor(workspaceId).render(TemplateRef(id, version), args.requiredObject("context"))
    }

    private fun resolveVersion(
        args: McpArguments,
        workspaceId: java.util.UUID,
        id: String,
    ): Int {
        val version = args.version() ?: return templates.findLatest(workspaceId, id)?.version ?: throw McpNotFound.template(id)
        if (templates.lookupVersion(workspaceId, id, version) == null) {
            throw if (templates.existsId(workspaceId, id)) McpNotFound.templateVersion(id, version) else McpNotFound.template(id)
        }
        return version
    }
}
