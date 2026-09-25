package co.datapipelines.mcp

import co.datapipelines.pipeline.CreateLifecycle
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.pipeline.TemplateType
import co.datapipelines.pipeline.WriteSurface
import co.datapipelines.templates.Template
import co.datapipelines.templates.TemplateDraft
import co.datapipelines.templates.TemplateDraftService
import co.datapipelines.templates.TemplateImport
import co.datapipelines.templates.TemplateJson
import co.datapipelines.templates.TemplateNameGrammar
import co.datapipelines.templates.TemplateRepository
import co.datapipelines.templates.TemplateValidator
import co.datapipelines.templates.TemplateVersionDetail
import co.datapipelines.templates.TransformBlocks
import co.datapipelines.templates.TransformContract
import co.datapipelines.templates.TransformInvariant
import co.datapipelines.templates.TransformTestCase
import co.datapipelines.templates.WorkspaceTemplateEngines
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.Dialect
import com.fasterxml.jackson.databind.node.ObjectNode
import io.modelcontextprotocol.spec.McpSchema

/**
 * §6.2.8 — the `id` argument's description, and the reason its `pattern` is
 * [TemplateNameGrammar.pattern] rather than a literal.
 *
 * Until 077 this schema advertised the **pre-043 flat rule** `[a-z0-9_.-]+` in prose and
 * carried no `pattern` at all — three grammar changes stale, and the only tool surface that
 * did not read the validator's own value the way `pipelines_create` has since 067 (audit
 * T129, 2026-09-05). Reading the grammar object is what makes a future change land here by
 * construction instead of by memory.
 */
private const val ID_ARG_DESC =
    "Template id, and a FOLDER PATH: 2-10 lower-case '/'-separated segments " +
        "(acme/finance/daily_orders.sql). A FOLDER IS REQUIRED — a bare 'daily_orders.sql' is refused with " +
        "template.validation.id_invalid and details.reason='folder_required'; put experiments under test/, and " +
        "shared macros under <owner>/lib/. Keep a template under the same prefix as the pipelines that read it. " +
        "Optional; auto-generated if omitted. There is no rename, so choose the folder now."

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

/**
 * `imports: [{id, version, alias}]` (D12), the shape both authoring tools accept. Shape errors
 * are `-32602` protocol faults, not validation failures.
 */
private fun parseImports(args: McpArguments): List<TemplateImport> =
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

/**
 * The transform blocks of §6.2.8/§6.2.36 (7b): `contract`, `invariants`, `tests` — bound
 * strictly from the tool arguments through the blocks' own mapper, so a typo inside a block
 * is `template.contract_invalid` with `unknown_field` and never a silent drop. Absent
 * arguments stay absent (null) — the validator's `blocks_missing` / `blocks_not_allowed`
 * rules own that verdict.
 */
@Suppress("UNCHECKED_CAST") // Jackson's convertValue with a typed JavaType is typed by construction
private fun parseBlocks(args: McpArguments): Triple<TransformContract?, List<TransformInvariant>?, List<TransformTestCase>?> {
    fun bind(
        name: String,
        type: com.fasterxml.jackson.databind.JavaType,
    ): Any? {
        val raw = args.rawMap()[name] ?: return null
        return try {
            TransformBlocks.mapper.convertValue(raw, type)
        } catch (err: IllegalArgumentException) {
            // convertValue wraps the mapping failure as IllegalArgumentException; the
            // mapping error rides as its cause.
            throw DatapipelinesException(
                code = PipelineErrorCodes.Template.CONTRACT_INVALID,
                message = "The '$name' block does not bind: ${err.message}. A typo is a refusal, never a silent drop.",
                details = mapOf("rule" to "unknown_field"),
                cause = err,
            )
        }
    }
    val contract =
        bind("contract", TransformBlocks.mapper.typeFactory.constructType(TransformContract::class.java))
            as TransformContract?
    val invariants =
        bind(
            "invariants",
            TransformBlocks.mapper.typeFactory.constructCollectionType(List::class.java, TransformInvariant::class.java),
        )
            as List<TransformInvariant>?
    val tests =
        bind(
            "tests",
            TransformBlocks.mapper.typeFactory.constructCollectionType(List::class.java, TransformTestCase::class.java),
        )
            as List<TransformTestCase>?
    return Triple(contract, invariants, tests)
}

/**
 * 7e (transform-nodes design §2.3) — the `implements` argument: the learned facts the version
 * cites, as strings. Absent (or JSON null) stays null — "not stated", which the write INHERITS
 * (owner ruling 2026-09-25); the entries' own verdict is the validator's
 * (`template.implements_unresolved`), so a malformed id is that catalogued refusal, not a
 * protocol fault. A non-array or a non-string entry IS a protocol fault (-32602).
 */
private fun parseImplements(args: McpArguments): List<String>? =
    args.listArg("implements")?.map { entry ->
        entry as? String ?: throw McpArguments.invalidParams("Each 'implements' entry must be a fact id string.")
    }

/** §6.2.8 — the `implements` description on create (7e; transform types only). */
private const val IMPLEMENTS_CREATE_DESC =
    "Transform types only (refused on sql/html with template.blocks_not_allowed): the learned facts this " +
        "transform implements — ids of WORKSPACE facts (definition, exclusion, preference) recorded in this " +
        "workspace; semantics_list and the definitions on datasources_list show them. A citation is not content: " +
        "it is outside body_hash. An id this workspace cannot cite is template.implements_unresolved. At most 50."

/** §6.2.36 — the `implements` description on update (7e): the inherit / clear / released-version rules. */
private const val IMPLEMENTS_UPDATE_DESC =
    "Transform types only: the learned facts this version implements (WORKSPACE definition, exclusion or " +
        "preference ids of this workspace). Omitted, the citations of the version you edit are kept (a new " +
        "draft inherits the released version's); [] clears them. Not content — outside body_hash — so an update " +
        "whose body equals the released version and changes only implements writes them ON the released " +
        "version and opens no draft: that is how you clear needs_review (cite the superseding fact). An id this " +
        "workspace cannot cite is template.implements_unresolved. At most 50."

/** §6.2.8/§6.2.36 — the `contract` block description (7b; transform types only). */
private const val CONTRACT_DESC =
    "Transform contract (transform types only — refused on sql/html with template.blocks_not_allowed): " +
        "{ mode: 'row'|'table'|'value', inputs: { name: { kind: 'table', columns: [{name, type, precision?, scale?, nullable?}] } " +
        "or { kind: 'value', type, precision?, scale? } }, output: { kind: 'table'|'value'|'object', ... }, rejects?: boolean }. " +
        "Types are LogicalType wire names; a row-mode contract requires exactly one table input."

/** §6.2.8/§6.2.36 — the `invariants` block description. */
private const val INVARIANTS_DESC =
    "Transform invariants: [{ name, expr, message }] — JSONata over { rows, rejects, inputs }, must be true on every " +
        "test case and every real execution. May be empty but is required on a transform type."

/** §6.2.8/§6.2.36 — the `tests` block description. */
private const val TESTS_DESC =
    "Transform test cases: [{ name, input: { rows?, inputs?, meta?, now? }, expect: { output } or { refusal } }] — " +
        "non-empty, at least one case whose every table input and rows are empty, expect is exactly one of output/refusal. " +
        "Save runs the suite; release re-runs it."

/** §6.2.9 — the render `context` description. */
private const val RENDER_CONTEXT_DESC =
    "Render context: the parameter map a calling pipeline would provide, defaults already applied. Values follow the " +
        "wire conventions of the Type System (BIGINTEGER/BIGDECIMAL as strings, TIMESTAMP with Z or offset)."

/** §6.2.8 — the `type` field's description, kept off the schema line for length. */
private const val TYPE_FIELD_DESC =
    "Template kind, fixed at creation and identical on every version: 'sql' renders SQL for pipeline nodes " +
        "(requires 'dialect'); 'html' renders HTML through an auto-escaping engine (must have NO 'dialect'); " +
        "'jsonata' and 'javascript' are transform types — the body is one expression evaluated as a pure function " +
        "of its input, engine is 'none', dialect/imports/is_library are refused, and contract/invariants/tests " +
        "blocks are required. 'javascript' is refused at save until round two."

/** §6.2.36 — `type` on an UPDATE: inherited from the working version when absent (136 §D / T289b). */
private const val UPDATE_TYPE_FIELD_DESC =
    "Template kind — fixed at creation, so on an update it is OPTIONAL: omitted, the working version's is " +
        "inherited; stated, it must equal it (template.validation.type_immutable otherwise)."

/** §6.2.8 — the `dialect` description, stating the type/dialect rule (046 §10). */
private const val DIALECT_FIELD_DESC =
    "SQL execution target. Required when type is 'sql' (the default); forbidden otherwise — html and the " +
        "transform types declare no dialect."

/**
 * §6.2.36 — the update tool's `id` description. It is REQUIRED here (§9.6 — the name never
 * travels anywhere else) and no auto-generation applies: the id names the template that
 * already exists, so only the folder-identity half of the create-time prose carries over.
 */
private const val UPDATE_ID_ARG_DESC =
    "Template to update — the FOLDER PATH id it was created under (acme/finance/daily_orders.sql). " +
        "Required here: §9.6, the name never travels in a path or anywhere else. There is no rename, " +
        "so the id cannot change — an unknown id is the catalogued template.not_found."

/**
 * §6.2.36 — the update tool's `dialect` description (135 §C / T276). The dialect is a property
 * of the template the id names: absent, it is inherited from the working version; present, it
 * must agree with it.
 */
private const val UPDATE_DIALECT_DESC =
    "Optional on update: omit it and the working version's dialect is inherited. When present it must be the " +
        "dialect the template already has — a different one is refused with template.validation.dialect_invalid " +
        "(a template pinned by pipeline nodes cannot change engine; create a new template instead). Never " +
        "present for an html template."

/** §6.2.36 — the `expected_hash` description, the §4.2 precondition's agent-facing wording. */
private const val EXPECTED_HASH_DESC =
    "The body_hash of the version this edit is based on — templates_get, or a previous " +
        "templates_create/templates_update result. A mismatch is a 409 template.version.conflict; " +
        "re-read and rebase, never retry blindly."

/** §6.2.8/§6.2.36 — the two engine values the model admits (record §2.1: freemarker iff sql/html, none iff a transform type). */
private val ENGINE_VALUES = setOf(Template.FREEMARKER_ENGINE, Template.NONE_ENGINE)

/** §6.2.8 — the `engine` description, stating the type-conditional rule (record §2.1). */
private const val ENGINE_FIELD_DESC =
    "Template engine, matched to the type: 'freemarker' for sql/html, 'none' for the transform types " +
        "('jsonata'/'javascript' — the body is evaluated, never rendered). Any other pairing is refused " +
        "with template.validation.engine_unsupported."

/** The engine an omitted `engine` argument means: the type's one legal value (record §2.1). */
private fun defaultEngineFor(type: TemplateType?): String =
    if (type?.isTransform == true) Template.NONE_ENGINE else Template.FREEMARKER_ENGINE

/**
 * `templates_create` (mcp-server.md §6.2.8). Permission: `template.create`.
 *
 * Save-time validation is **parse-only** (templates.md §7.1): syntax, forbidden constructs,
 * import resolution, and the type/dialect consistency rules. A template is never rendered
 * against a sample context at save time because it does not know its callers' parameters —
 * which is why an authoring agent is told to call `templates_render` next.
 */
class TemplatesCreateTool(
    private val templates: TemplateRepository,
    private val authoring: co.datapipelines.pipeline.AuthoringGuard,
    private val validator: TemplateValidator,
    /** 7e — the create half of the write rule: version 1 and its stated citations, one service. */
    private val drafts: TemplateDraftService,
) : McpTool {
    override val definition: McpSchema.Tool =
        McpTools.tool(
            name = "templates_create",
            description =
                "Create a new template. Templates use Freemarker syntax. A template declares NO parameters of its " +
                    "own: the variables its body may reference are exactly the parameters declared by the pipeline that " +
                    "calls it, with defaults applied. Describe the variables you expect in 'description' — that free " +
                    "text is how humans and agents discover them. Macros from library templates are made available by " +
                    "listing them in 'imports'; the body must NOT contain import or include directives, they are " +
                    "synthesized from the imports array. The 'type' is chosen here and never changes afterwards: " +
                    "'sql' (default) requires a dialect and is what pipeline nodes reference; 'html' takes no dialect " +
                    "and renders through an auto-escaping engine. A NEW top-level folder is refused until you " +
                    "confirm it: reuse an existing root, or ask the person first and then pass " +
                    "confirm_new_root: true. Version 1 lands as a DRAFT: a pipeline draft may pin it and render " +
                    "against it while you iterate, and a human releases it from the UI — a RELEASED pipeline may " +
                    "only pin RELEASED template versions, so the template is released first.",
            schema = SCHEMA,
        )

    override fun call(
        args: McpArguments,
        ctx: McpToolContext,
    ): Any {
        // versioning §5.5: creation is authoring — a promotion receiver refuses it.
        authoring.requireTemplateAuthoring()
        val workspaceId = ctx.principal.requireWorkspace().id
        // 094 addendum. An OMITTED id is generated under `test/`, which needs no confirmation —
        // the rule reads null as "no root to mint" rather than guessing at the generated one.
        NewRootConfirmation.require(
            name = args.string("id"),
            confirmed = args.boolean(NewRootConfirmation.ARG),
            code = PipelineErrorCodes.Template.NEW_ROOT_REQUIRES_CONFIRMATION,
        ) { templates.listChildFolders(workspaceId).map { it.segment } }
        val type =
            args
                .enumString("type", TemplateType.WIRE_VALUES.toSet(), TemplateType.SQL.wire)
                ?.let { TemplateType.fromWire(it)!! }
        val blocks = parseBlocks(args)
        val draft =
            TemplateDraft(
                id = args.string("id"),
                // The omitted-engine default follows the type (record §2.1): a transform create
                // that names no engine means 'none', not the sql/html default.
                engine = args.enumString("engine", ENGINE_VALUES, defaultEngineFor(type))!!,
                type = type,
                // Null is legal only for html — the validator's type/dialect consistency pair
                // refuses a missing dialect on sql and a present one on html, with the same
                // catalogued codes the REST surface raises.
                dialect = args.dialect("dialect"),
                displayName = args.requiredString("display_name"),
                description = args.requiredString("description"),
                imports = parseImports(args),
                body = args.requiredString("body"),
                isLibrary = args.boolean("is_library") ?: false,
                contract = blocks.first,
                invariants = blocks.second,
                tests = blocks.third,
                implements = parseImplements(args),
            )
        // D55: authoring lands version 1 DRAFT — the response's `status` says so, and a human
        // releases it from the UI. Pinning it from a draft pipeline is legal meanwhile
        // (versioning §6 only bites when the PIPELINE is released). 7e: the service lands the
        // stated `implements` on that version and returns the stored projection.
        return drafts.create(
            workspaceId,
            validator.validateOrThrow(draft, workspaceId),
            ctx.principal.userId,
            CreateLifecycle.DRAFT,
            // The MCP surface stamp (V20) — the tool is the only caller that knows.
            WriteSurface.MCP,
        )
    }

    private companion object {
        val SCHEMA =
            """
            {
              "type": "object",
              "required": ["display_name", "description", "body"],
              "properties": {
                "id": {"type": "string", "pattern": "${TemplateNameGrammar.pattern}", "description": "$ID_ARG_DESC"},
                "engine": {
                  "type": "string", "enum": $TEMPLATE_ENGINE_ENUM_JSON, "default": "freemarker",
                  "description": "$ENGINE_FIELD_DESC"
                },
                "type": {"type": "string", "enum": $TEMPLATE_TYPE_ENUM_JSON, "default": "sql", "description": "$TYPE_FIELD_DESC"},
                "dialect": {"type": "string", "enum": $DIALECT_ENUM_JSON, "description": "$DIALECT_FIELD_DESC"},
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
                "body": {"type": "string", "description": "$BODY_DESC"},
                "contract": {"type": "object", "description": "$CONTRACT_DESC"},
                "invariants": {"type": "array", "description": "$INVARIANTS_DESC"},
                "tests": {"type": "array", "description": "$TESTS_DESC"},
                "implements": {"type": "array", "items": {"type": "string", "format": "uuid"}, "description": "$IMPLEMENTS_CREATE_DESC"},
                "confirm_new_root": {"type": "boolean", "description": "${NewRootConfirmation.ARG_DESC}"}
              },
              "additionalProperties": false
            }
            """.trimIndent()
    }
}

/**
 * `templates_update` (mcp-server.md §6.2.36). Permission: `template.update`. Mutating.
 *
 * The MCP twin of REST `PUT /templates` (§8.4): the body is validated exactly as
 * [TemplatesCreateTool] validates it (parse-only, §7.1), then [TemplateDraftService.write] —
 * the SAME call REST makes, so the copy-on-write draft semantics (versioning §3.2/§5.1/§5.2)
 * have one owner: the first write after a release copies the released version to a draft,
 * later writes overwrite that one draft in place, and a write whose CONTENT equals the
 * released content is the §5.1 no-op — status RELEASED, no draft opened or burned. The agent
 * never releases (D4): the draft waits for a human.
 *
 * `expected_hash` is the §4.2 precondition — the `body_hash` the caller read from
 * `templates_get` or a previous `templates_create`/`templates_update` result. A stale hash
 * surfaces as `template.version.conflict` with the current state in `details`: re-read and
 * rebase, never retry blindly. The template's `type` is fixed at creation: an update naming a
 * different type is refused with `template.validation.type_immutable` (046 §5.3, raised by
 * [TemplateTypeRule.forExisting] inside the service — the same refusal every write surface
 * raises). An unknown id is the catalogued `template.not_found`.
 *
 * There is deliberately no `confirm_new_root` argument: the update names a template that
 * already exists and cannot mint a folder, so the argument would advertise a decision this
 * tool cannot take (094's create-only rule).
 *
 * `dialect` is OPTIONAL here (135 §C / T276): the working version already carries it, so an
 * absent one is inherited before validation — three acceptance-run updates were refused
 * `dialect_invalid` for omitting a field the draft already had, and each was resent with that
 * same value. A present dialect must equal the established one; a different one is refused
 * with the same catalogued code, `details` naming both, BEFORE validation or any write — the
 * template's pipeline nodes pin it against a source of the established dialect, so an update
 * re-targeting it is a new template wearing an old id. Since 136 §D the REST PUT (§8.4)
 * resolves both the same way, so the shared write keeps receiving a complete draft from either
 * surface; and `type` is inherited here too (T289b) — it used to default to `sql`, so an html
 * template updated without `type` was refused `type_immutable` for a field it never changed.
 */
class TemplatesUpdateTool(
    private val templates: TemplateRepository,
    private val drafts: TemplateDraftService,
    private val validator: TemplateValidator,
) : McpTool {
    override val definition: McpSchema.Tool =
        McpTools.tool(
            name = "templates_update",
            description =
                "Update an existing template by writing its DRAFT — the first update after a release creates the " +
                    "draft (copy-on-write); later updates overwrite that same draft in place. Requires expected_hash: " +
                    "the body_hash you read (templates_get, or a previous templates_create/templates_update result) " +
                    "for the version you based your edit on. The result carries status='DRAFT' — your work is NOT " +
                    "released; a human releases it from the UI. On template.version.conflict someone modified it " +
                    "after you loaded it: re-read with templates_get, rebase, retry; never retry blindly. The body " +
                    "takes the same fields as templates_create, and the template's type is fixed at creation — an " +
                    "update naming a different type is refused with template.validation.type_immutable. type and " +
                    "dialect are optional: omitted, the working version's are inherited; a different dialect is " +
                    "refused. templates_purge_draft is for a template that was a mistake, not for editing one.",
            schema = SCHEMA,
        )

    override fun call(
        args: McpArguments,
        ctx: McpToolContext,
    ): Any {
        val workspaceId = ctx.principal.requireWorkspace().id
        // §9.6: the name travels HERE and nowhere else — the update has no path, no other id.
        val id = args.requiredString("id")
        val expectedHash = args.requiredString("expected_hash")
        val suppliedType = args.enumString("type", TemplateType.WIRE_VALUES.toSet())?.let { TemplateType.fromWire(it)!! }
        // 135 §C: the dialect is the template's, read from the same working version the
        // service writes against (D55 — between creation and first release there is no
        // released projection). Read after the required arguments, so a protocol error
        // still costs no database read. 136 §D (T289b): `type` is inherited the same way —
        // it no longer defaults to `sql`, which sent every html update to `type_immutable`;
        // a stated type still goes to the service's own immutability refusal.
        val working = templates.findWorking(workspaceId, id) ?: throw McpNotFound.template(id)
        val blocks = parseBlocks(args)
        val draft =
            TemplateDraft(
                id = id,
                engine = args.enumString("engine", ENGINE_VALUES, defaultEngineFor(suppliedType ?: working.type))!!,
                type = suppliedType ?: working.type,
                // Null is legal only for html — the validator's type/dialect consistency pair
                // refuses a missing dialect on sql and a present one on html, with the same
                // catalogued codes the REST surface raises.
                dialect = inheritedDialect(id, args.dialect("dialect"), working.dialect),
                displayName = args.requiredString("display_name"),
                description = args.requiredString("description"),
                imports = parseImports(args),
                body = args.requiredString("body"),
                isLibrary = args.boolean("is_library") ?: false,
                contract = blocks.first,
                invariants = blocks.second,
                tests = blocks.third,
                // 7e: absent stays null — the service inherits the base version's citations.
                implements = parseImplements(args),
            )
        // Parse-only validation (§7.1) exactly as templates_create and the REST PUT run it,
        // then the SAME draft write PUT /templates makes (§8.4) — one write path, two surfaces.
        val validated = validator.validateOrThrow(draft, workspaceId)
        val written = drafts.write(workspaceId, id, validated, expectedHash, ctx.principal.userId, WriteSurface.MCP)
        // The REST twin's read-back: the response is the STORED version's projection, so the
        // agent reads one shape here and from templates_get (and chains templates_render next).
        val stored =
            templates.findVersion(workspaceId, id, written.version) ?: throw McpNotFound.template(id)
        return projection(stored, written)
    }

    /**
     * The §8.4 read shape: the stored [Template] projection with the `draft` pointer merged in
     * when the write produced one. Merged as a tree with the SAME mapper the REST controller
     * merges it with, rather than by restating [Template]'s pinned wire keys a second time.
     * The pipelines_update rule applies to the pointer: a §5.1 no-op (status RELEASED) carries
     * NO draft — the agent must see that nothing was opened.
     */
    private fun projection(
        stored: Template,
        written: TemplateVersionDetail,
    ): ObjectNode {
        val node = TemplateJson.objectMapper().valueToTree<ObjectNode>(stored)
        if (written.status == PipelineVersionStatus.DRAFT) {
            node.putObject("draft").apply {
                put("version", written.version)
                put("body_hash", written.bodyHash)
                put("updated_by", written.updatedBy?.toString() ?: "")
                put("updated_at", written.updatedAt?.toString() ?: "")
            }
        }
        return node
    }

    /**
     * The 135 §C resolution: [supplied] absent → [established]; equal → itself; different from
     * a non-null established dialect → `template.validation.dialect_invalid` naming both. A
     * supplied dialect on an html template (established null) passes through to the
     * validator's own `dialect_not_allowed` refusal.
     */
    private fun inheritedDialect(
        id: String,
        supplied: Dialect?,
        established: Dialect?,
    ): Dialect? {
        if (supplied == null) return established
        if (established != null && supplied != established) {
            throw DatapipelinesException(
                code = PipelineErrorCodes.Template.DIALECT_INVALID,
                message =
                    "Template '$id' is a '${established.wire}' template; the update names '${supplied.wire}'. " +
                        "A template's dialect is fixed by the version you are editing — omit dialect to inherit it, " +
                        "or create a new template for another engine.",
                details = mapOf("template_id" to id, "dialect" to supplied.wire, "established_dialect" to established.wire),
            )
        }
        return supplied
    }

    private companion object {
        val SCHEMA =
            """
            {
              "type": "object",
              "required": ["id", "expected_hash", "display_name", "description", "body"],
              "properties": {
                "id": {"type": "string", "pattern": "${TemplateNameGrammar.pattern}", "description": "$UPDATE_ID_ARG_DESC"},
                "expected_hash": {"type": "string", "description": "$EXPECTED_HASH_DESC"},
                "engine": {
                  "type": "string", "enum": $TEMPLATE_ENGINE_ENUM_JSON, "default": "freemarker",
                  "description": "$ENGINE_FIELD_DESC"
                },
                "type": {"type": "string", "enum": $TEMPLATE_TYPE_ENUM_JSON, "description": "$UPDATE_TYPE_FIELD_DESC"},
                "dialect": {"type": "string", "enum": $DIALECT_ENUM_JSON, "description": "$UPDATE_DIALECT_DESC"},
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
                "body": {"type": "string", "description": "$BODY_DESC"},
                "contract": {"type": "object", "description": "$CONTRACT_DESC"},
                "invariants": {"type": "array", "description": "$INVARIANTS_DESC"},
                "tests": {"type": "array", "description": "$TESTS_DESC"},
                "implements": {"type": "array", "items": {"type": "string", "format": "uuid"}, "description": "$IMPLEMENTS_UPDATE_DESC"}
              },
              "additionalProperties": false
            }
            """.trimIndent()
    }
}

/**
 * `templates_render` (mcp-server.md §6.2.9). Permission: `template.render`.
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
        val (version, type) = resolveVersion(args, workspaceId, id)
        // 7b (record §9.1): a transform type has nothing to render — the refusal points at
        // the evaluate tool, the way the REST /render twin does.
        if (type != null && type.isTransform) {
            throw DatapipelinesException(
                code = PipelineErrorCodes.Template.RENDER_NOT_APPLICABLE,
                message =
                    "Template '$id' has type '${type.wire}' — a transform is evaluated, not rendered; " +
                        "use templates_evaluate.",
                details = mapOf("type" to type.wire, "use" to "templates_evaluate"),
            )
        }
        return engines.engineFor(workspaceId).render(TemplateRef(id, version), args.requiredObject("context"))
    }

    private fun resolveVersion(
        args: McpArguments,
        workspaceId: java.util.UUID,
        id: String,
    ): Pair<Int, TemplateType?> {
        // The working version (D55/§7.1): a template created and not yet released has only a
        // draft, and defaulting to the released one would refuse to render it.
        val explicit = args.version()
        if (explicit == null) {
            val working = templates.findWorking(workspaceId, id) ?: throw McpNotFound.template(id)
            return working.version to working.type
        }
        val stored =
            templates.lookupVersion(workspaceId, id, explicit)
                ?: throw if (templates.existsId(workspaceId, id)) McpNotFound.templateVersion(id, explicit) else McpNotFound.template(id)
        return explicit to stored.type
    }
}
