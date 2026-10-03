package co.datapipelines.mcp

import co.datapipelines.application.lens.PromoterLens
import co.datapipelines.application.mcp.McpToolLearnings
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.mcp.docs.DocArea
import co.datapipelines.parameters.EvaluateResponseJson
import co.datapipelines.parameters.EvaluationAttempt
import co.datapipelines.parameters.EvaluationCaller
import co.datapipelines.parameters.ParameterEvaluator
import co.datapipelines.parameters.ParameterSetBody
import co.datapipelines.parameters.ParameterSetImported
import co.datapipelines.parameters.ParameterSetJson
import co.datapipelines.parameters.ParameterSetReader
import co.datapipelines.parameters.ParameterSetRepository
import co.datapipelines.parameters.ParameterSetService
import co.datapipelines.parameters.ParameterSetVersion
import co.datapipelines.parameters.ParametersConfig
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.ReadLens
import co.datapipelines.typesystem.DatapipelinesException
import com.fasterxml.jackson.databind.JsonNode
import io.modelcontextprotocol.spec.McpSchema
import java.time.Instant
import java.util.UUID

/** Reflected client input is bounded before it reaches a refusal's text. */
private const val MAX_ECHOED_ID_CHARS = 64

/**
 * `parameter_sets_list` (mcp-server.md §6.2.44, the record's §9.1) — the `pipelines_list` browse
 * and search by name/prefix, P24's addressing. Permission: `parameter_set.read` (it returns no
 * customer row data — only which sets exist, which any workspace reader may already see). A
 * promoter's key is lensed; every other key sees the workspace's sets.
 *
 * ## Two presentations, chosen by `prefix` (067's rule, #419)
 *
 * - **`prefix` present** (`""` is the ROOT) → ONE level of the name tree: its direct sub-folders
 *   with their subtree counts and its direct sets. Never a subtree, never the whole list.
 * - **`prefix` absent, `q` non-blank** → the flat search over name, display name and description
 *   ([ParameterSetService.search]) — the same lensed read the first-party pages search through.
 * - **neither** → today's root browse.
 *
 * A present `prefix` wins over `q`: browse and search are different presentations and a folder
 * listing is unambiguously a browse ([PipelinesListTool]'s rule).
 */
class ParameterSetsListTool(
    private val sets: ParameterSetService,
    /** The promoter lens; resolved from the KEY's principal, never a thread-local (134). */
    private val lens: PromoterLens,
) : McpTool {
    override val definition: McpSchema.Tool =
        McpTools.tool(
            name = "parameter_sets_list",
            description =
                "List the parameter sets of the key's pinned workspace, or BROWSE one level of the name tree. " +
                    "Parameter sets are versioned definitions of form controls (selectors, inputs) that a client " +
                    "evaluates server-side; every row carries the id you pass to the other parameter_sets_* tools. " +
                    "Names are FOLDER PATHS (acme/sales/region_filters): pass prefix to browse one level — prefix:\"\" " +
                    "lists the roots, prefix:\"acme\" what is directly under acme — and the response separates folders " +
                    "from sets at that level. Pass q to SEARCH name, display name and description case-insensitively; " +
                    "q is ignored while prefix is present." +
                    " A promoter's key sees only RELEASED sets newer than the promotion target's (the promoter lens); " +
                    "every other set is absent for it.",
            schema =
                """
                {
                  "type": "object",
                  "properties": {
                    "prefix": {"type": "string", "description": "Browse ONE level of the name tree at this prefix. Empty string browses the roots."},
                    "q": {"type": "string", "description": "Case-insensitive substring search over name, display name and description (the whole path counts as the name); ignored when prefix is present — pass prefix to browse, q to search."},
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
        val view = lens.viewFor(ctx.principal)
        val limit = args.int("limit", default = DEFAULT_LIMIT, min = 1, max = MAX_LIMIT)
        // `has` and not `string`: `prefix: ""` is PRESENT and means the roots, while
        // `string("prefix")` normalizes blank to null. A present prefix is a BROWSE, so it also
        // wins over `q`: browse and search are different presentations (`PipelinesListTool`'s rule).
        if (args.has("prefix")) return browse(workspaceId, view.parameterSets, args.string("prefix"), limit)
        val q = args.string("q")
        if (q != null) return search(workspaceId, view.parameterSets, q, limit)
        return browse(workspaceId, view.parameterSets, null, limit)
    }

    /** ONE level of the name tree — the shape `parameter_sets_list {prefix}` has always answered. */
    private fun browse(
        workspaceId: UUID,
        view: ReadLens,
        prefix: String?,
        limit: Int,
    ): Map<String, Any?> {
        val folders = sets.listChildFolders(workspaceId, view, prefix)
        val loaded = sets.listChildSets(workspaceId, view, prefix, 0, limit)
        return mapOf(
            "prefix" to (prefix ?: ""),
            "folders" to folders.map { mapOf("path" to it.path, "segment" to it.segment, "parameter_set_count" to it.setCount) },
            "parameter_sets" to loaded.map(::row),
            "returned" to (folders.size + loaded.size),
        )
    }

    /**
     * The flat search (#419) — the same lensed [ParameterSetService.search] the pages use, with the
     * FULL match count as `total` so an agent sees when `limit` truncated. No `folders` key: a
     * search is not a level.
     */
    private fun search(
        workspaceId: UUID,
        view: ReadLens,
        q: String,
        limit: Int,
    ): Map<String, Any?> {
        val loaded = sets.search(workspaceId, view, q, 0, limit)
        return mapOf(
            "q" to q,
            "parameter_sets" to loaded.map(::row),
            "returned" to loaded.size,
            "total" to sets.countSearch(workspaceId, view, q),
        )
    }

    private fun row(it: ParameterSetVersion): Map<String, Any?> =
        mapOf(
            "id" to it.record.id.toString(),
            "name" to it.record.name,
            "display_name" to it.record.displayName,
            "version" to it.detail.version,
            "status" to it.detail.status.name,
            "current_version" to it.record.currentVersion,
        )

    private companion object {
        const val DEFAULT_LIMIT = 50
        const val MAX_LIMIT = 200
    }
}

/**
 * `parameter_sets_get` (mcp-server.md §6.2.45) — the working version by ID, the
 * `pipelines_get` twin (P24). Permission: `parameter_set.read`. Another workspace's set —
 * or a lens-hidden one — answers `parameter.not_found`, never a permission error (§11A.1).
 */
class ParameterSetsGetTool(
    private val sets: ParameterSetService,
    private val repository: ParameterSetRepository,
    private val templates: co.datapipelines.templates.TemplateRepository,
    private val lens: PromoterLens,
) : McpTool {
    override val definition: McpSchema.Tool =
        McpTools.tool(
            name = "parameter_sets_get",
            description =
                "Read one parameter set by ID: the WORKING version's full §3 document (every parameter with its " +
                    "type, kind, cardinality, source, depends_on, constraints and presentation), plus the lifecycle " +
                    "state — version and status name the returned row (the DRAFT when one exists, else the current " +
                    "release), current_version names the latest release, and body_hash is what an update's " +
                    "expected_hash carries. A set of another workspace — or one the promoter lens hides — answers " +
                    "not-found, never a permission error. Nothing is evaluated here; parameter_sets_evaluate runs one.",
            schema =
                """
                {
                  "type": "object",
                  "required": ["id"],
                  "properties": {
                    "id": {"type": "string", "format": "uuid", "description": "The set's id (parameter_sets_list returns it)."}
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
        val id = args.requiredUuid("id")
        val view = lens.viewFor(ctx.principal)
        val loaded = sets.findWorking(workspaceId, view.parameterSets, id) ?: throw McpNotFound.parameterSet(id)
        // Null under a narrowing lens: a promoter never sees a draft pointer (178; the pipelines_get
        // shape — the 194d merge's security pass, F2).
        val draft = if (view.parameterSets.isEverything) repository.findDraft(workspaceId, id) else null
        return mapOf(
            "id" to loaded.record.id.toString(),
            "name" to loaded.record.name,
            "display_name" to loaded.record.displayName,
            "description" to loaded.record.description,
            "version" to loaded.detail.version,
            "status" to loaded.detail.status.name,
            "body_hash" to loaded.detail.bodyHash,
            "current_version" to loaded.record.currentVersion,
            "draft" to
                draft?.let {
                    mapOf(
                        "version" to it.version,
                        "body_hash" to it.bodyHash,
                        "updated_at" to (it.updatedAt?.toString() ?: ""),
                    )
                },
            "document" to documentOf(loaded),
            // The record's §8.4 — the pipelines_get upgrade signal's twin: a pin below the
            // template's latest RELEASED version is surfaced, never applied.
            "upgrade_available" to upgradeAvailable(workspaceId, loaded),
        )
    }

    /** One row per pin whose template has a NEWER RELEASED version; a pin of a DRAFT is not an upgrade. */
    private fun upgradeAvailable(
        workspaceId: UUID,
        loaded: ParameterSetVersion,
    ): List<Map<String, Any?>> {
        val body = loaded.body
        val pins =
            body
                .parameters
                .mapNotNull { it.source?.template }
                .distinct()
        val latest = templates.findCurrentVersions(workspaceId, pins.map { it.id }.toSet())
        return pins.mapNotNull { ref ->
            val newest = latest[ref.id] ?: return@mapNotNull null
            if (ref.version < newest) {
                mapOf(
                    "parameter" to
                        loaded.body.parameters
                            .firstOrNull { it.source?.template == ref }
                            ?.name,
                    "template_id" to ref.id,
                    "pinned" to ref.version,
                    "latest_released" to newest,
                )
            } else {
                null
            }
        }
    }

    /** The §3 document as stored — the strict mapper's projection (the definitions' one wire form). */
    private fun documentOf(loaded: ParameterSetVersion): JsonNode = ParameterSetJson.mapper.valueToTree(loaded.body)
}

/**
 * `parameter_sets_create` (mcp-server.md §6.2.46) — full §4 validation at save (a set whose
 * selector cannot be proven is refused, not stored), the 094 new-root confirmation, and the
 * §13a.2 ask-before-a-MULTI-without-a-hint rule in the description.
 * Permission: `parameter_set.create`.
 */
class ParameterSetsCreateTool(
    private val sets: ParameterSetService,
    /** The folder tree, for the 094 new-root rule alone — the same one-level browse the list tool serves. */
    private val repository: ParameterSetRepository,
    private val config: ParametersConfig,
    private val lens: PromoterLens,
) : McpTool {
    override val definition: McpSchema.Tool =
        McpTools.tool(
            name = "parameter_sets_create",
            description =
                "Create a parameter set: version 1 lands as a DRAFT (a human releases it; no tool releases anything). " +
                    "The document is validated in FULL at save — including a metadata run of every source template " +
                    "against its datasource — so a set whose selector cannot be proven is refused, not stored. A NEW " +
                    "top-level folder is refused until you confirm it: reuse an existing root, or ask the person first " +
                    "and then pass confirm_new_root: true. Before creating a SELECT with cardinality MULTI and no " +
                    "presentation.control, ASK the person how it should render (dropdown, checkboxes or list) and set " +
                    "presentation.control — a MULTI without a hint is refused by this rule's ask-first discipline and " +
                    "renders as a dropdown by default everywhere else. Returns the created set with its id, which the " +
                    "other tools take.",
            schema =
                """
                {
                  "type": "object",
                  "required": ["name", "display_name", "parameters"],
                  "properties": {
                    "name": {"type": "string", "description": "Folder-path name (2-10 segments, lower-case): acme/sales/region_filters."},
                    "display_name": {"type": "string", "description": "Human label, 1-120 characters."},
                    "description": {"type": "string", "description": "Optional; 2000 characters max."},
                    "parameters": {
                      "type": "array",
                      "description": "The ordered parameter definitions (the record's §3.2): name, label, type, kind (INPUT|SELECT), cardinality (SINGLE|MULTI), required, default_value, source (constants | template+datasource), depends_on, hidden_expression, disabled_expression, constraints (INPUT only), presentation.",
                      "items": {"type": "object"}
                    },
                    "confirm_new_root": {"type": "boolean", "description": "Set true ONLY after a person has agreed to a new top-level folder; the refusal names the roots that exist. 'test/' never needs it."}
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
        val name = args.requiredString("name")
        // 094 addendum, BEFORE the write: a root nobody has used is a permanent decision (no rename).
        NewRootConfirmation.require(
            name = name,
            confirmed = args.boolean(NewRootConfirmation.ARG),
            code = co.datapipelines.parameters.ParameterErrorCodes.NEW_ROOT_REQUIRES_CONFIRMATION,
        ) { repository.listChildFolders(workspaceId, prefix = null).map { it.segment } }
        val document =
            ParameterSetReader(config).readOrThrow(
                ParameterSetJson.mapper.readTree(documentJson(args, name)),
            )
        val saved = sets.create(workspaceId, document, ctx.principal.userId, co.datapipelines.pipeline.WriteSurface.MCP)
        return mapOf(
            "id" to saved.record.id.toString(),
            "name" to saved.record.name,
            "version" to saved.detail.version,
            "status" to saved.detail.status.name,
            "body_hash" to saved.detail.bodyHash,
        )
    }

    private fun documentJson(
        args: McpArguments,
        name: String,
    ): String {
        val body =
            buildMap<String, Any?> {
                put("name", name)
                put("display_name", args.requiredString("display_name"))
                args.string("description")?.let { put("description", it) }
                put("parameters", args.requiredList("parameters"))
            }
        return ParameterSetJson.mapper.writeValueAsString(body)
    }
}

/**
 * `parameter_sets_update` (mcp-server.md §6.2.47) — the hash-preconditioned draft write, the
 * `templates_update` twin. Permission: `parameter_set.update`.
 */
class ParameterSetsUpdateTool(
    private val sets: ParameterSetService,
    private val config: ParametersConfig,
    private val lens: PromoterLens,
) : McpTool {
    override val definition: McpSchema.Tool =
        McpTools.tool(
            name = "parameter_sets_update",
            description =
                "Edit a parameter set by ID: the first change after a release opens a DRAFT (copy-on-write); later " +
                    "updates overwrite that same draft in place. Requires expected_hash: the body_hash you read from " +
                    "parameter_sets_get (or the previous update's result) for the version this edit is based on — a " +
                    "mismatch is a 409 conflict; re-read and rebase. The document is validated in full, and steps 5–6 " +
                    "(the source dry run and metadata execution) run only when the body actually changed. A set is " +
                    "never renamed: a document naming another set is refused.",
            schema =
                """
                {
                  "type": "object",
                  "required": ["id", "expected_hash", "name", "display_name", "parameters"],
                  "properties": {
                    "id": {"type": "string", "format": "uuid"},
                    "expected_hash": {"type": "string", "description": "The body_hash of the version this edit is based on. A mismatch is a 409 conflict; re-read and rebase."},
                    "name": {"type": "string", "description": "Must equal the stored name — a set is never renamed."},
                    "display_name": {"type": "string"},
                    "description": {"type": "string"},
                    "parameters": {"type": "array", "items": {"type": "object"}}
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
        val id = args.requiredUuid("id")
        val expectedHash = args.requiredString("expected_hash")
        val name = args.requiredString("name")
        val document =
            ParameterSetReader(config).readOrThrow(
                ParameterSetJson.mapper.readTree(documentJson(args, name)),
            )
        val written =
            sets.write(workspaceId, id, document, expectedHash, ctx.principal.userId, co.datapipelines.pipeline.WriteSurface.MCP)
        return mapOf(
            "id" to written.record.id.toString(),
            "name" to written.record.name,
            "version" to written.detail.version,
            "status" to written.detail.status.name,
            "body_hash" to written.detail.bodyHash,
        )
    }

    private fun documentJson(
        args: McpArguments,
        name: String,
    ): String {
        val body =
            buildMap<String, Any?> {
                put("name", name)
                put("display_name", args.requiredString("display_name"))
                args.string("description")?.let { put("description", it) }
                put("parameters", args.requiredList("parameters"))
            }
        return ParameterSetJson.mapper.writeValueAsString(body)
    }
}

/**
 * `parameter_sets_evaluate` (mcp-server.md §6.2.48) — the §5 runtime's tool face; a VIEWER row
 * (C24). Permission: `parameter_set.evaluate`. Carries the 139 gate's twin, MCP-only: a DRAFT
 * evaluate whose pinned DRAFT template postdates the key's last `templates_render` of it is
 * refused `parameter.evaluate.template_unrendered`.
 */
class ParameterSetsEvaluateTool(
    private val sets: ParameterSetService,
    private val repository: ParameterSetRepository,
    private val evaluator: ParameterEvaluator,
    private val learnings: McpToolLearnings,
    private val templates: co.datapipelines.templates.TemplateRepository,
    private val lens: PromoterLens,
) : McpTool {
    override val definition: McpSchema.Tool =
        McpTools.tool(
            name = "parameter_sets_evaluate",
            description =
                "Evaluate a parameter set by ID: submit EVERY parameter's current value (wire-encoded for its type, a " +
                    "MULTI as an array) and receive the WHOLE set re-rendered — every parameter's options, resolved " +
                    "value, origin, computed_default and reset flag, plus values, the consumer payload. The first " +
                    "render sends selections:{} and the server initialises the form. There is no client dependency " +
                    "logic: a stale child walks the server's selection priority (never an error — reset:true says so). " +
                    "The version resolves to the SERVED one unless you pass version; a DRAFT may be evaluated by its " +
                    "number — but a draft whose pinned DRAFT template changed after this key's last templates_render " +
                    "of it is refused parameter.evaluate.template_unrendered (render first, then evaluate). An unknown " +
                    "selections key refuses the whole request.",
            schema =
                """
                {
                  "type": "object",
                  "required": ["id"],
                  "properties": {
                    "id": {"type": "string", "format": "uuid"},
                    "version": {"type": "integer", "description": "A specific version to evaluate. Absent: the SERVED version (current_version). A DRAFT may be evaluated by its own number."},
                    "selections": {
                      "type": "object",
                      "description": "Every parameter's current value keyed by parameter name — the first render sends {}. null and [] mean 'nothing chosen' and walk the selection priority. Unknown keys refuse the whole request.",
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
        val id = args.requiredUuid("id")
        val view = lens.viewFor(ctx.principal)
        // The version resolution is the route's rule (C26): explicit never falls back, absent is
        // the SERVED version — a draft only ever by its own number.
        val explicit = args.version()?.let { version -> resolveExplicit(workspaceId, id, view, version) }
        val loaded = explicit ?: currentFor(workspaceId, id, view) ?: throw McpNotFound.parameterSet(id)
        // The 139 gate's twin, MCP-only (the record's §5.1): a DRAFT set whose pinned DRAFT
        // template was written after this key's last render of it is not its render — the fact is
        // read from McpToolLearnings (the audit row templates_render writes), per pinned template.
        if (loaded.detail.status == PipelineVersionStatus.DRAFT) {
            requireRenderedDraftPins(workspaceId, ctx.principal, loaded)
        }
        val selections =
            args
                .objectArg("selections")
                ?.mapValues { (_, value) -> ParameterSetJson.mapper.valueToTree<JsonNode>(value) }
                ?: emptyMap()
        val attempt = EvaluationAttempt.of(EvaluationCaller.MCP, ctx.principal.userId, ctx.principal.keyId)
        val response = evaluator.evaluateBlocking(workspaceId, loaded, selections, attempt)
        return EvaluateResponseJson.write(response)
    }

    /** The SERVED version (the current release) through the caller's lens — the no-version default. */
    private fun currentFor(
        workspaceId: UUID,
        id: UUID,
        view: co.datapipelines.application.lens.LensedView,
    ): ParameterSetVersion? = repository.findCurrent(workspaceId, id)?.takeIf { view.parameterSets.admits(it.record.name) }

    /** An explicit version NEVER falls back to the served one — a miss is the catalogued 404 (C26). */
    private fun resolveExplicit(
        workspaceId: UUID,
        id: UUID,
        view: co.datapipelines.application.lens.LensedView,
        version: Int,
    ): ParameterSetVersion =
        sets.findVersion(workspaceId, view.parameterSets, id, version)
            ?: throw DatapipelinesException(
                co.datapipelines.parameters.ParameterErrorCodes.NOT_FOUND,
                "Parameter set $id has no version $version.",
                mapOf("id" to id.toString(), "version" to version),
            )

    /** The draft pin freshness rule: one read of the template version's updatedAt per DRAFT pin. */
    private fun requireRenderedDraftPins(
        workspaceId: UUID,
        principal: AuthenticatedPrincipal,
        loaded: ParameterSetVersion,
    ) {
        val stale =
            loaded.body.parameters
                .mapNotNull { it.source?.template }
                .distinct()
                .mapNotNull { ref ->
                    val templateVersion = templates.lookupVersion(workspaceId, ref.id, ref.version)
                    if (templateVersion?.status != PipelineVersionStatus.DRAFT) return@mapNotNull null
                    val updatedAt = templateVersion.updatedAt ?: Instant.EPOCH
                    val lastRender = learnings.lastRenderAt(principal.keyId, ref.id)
                    if (lastRender != null && lastRender.isAfter(updatedAt)) {
                        null
                    } else {
                        mapOf(
                            "template_id" to ref.id.take(MAX_ECHOED_ID_CHARS),
                            "template_version" to ref.version,
                            "draft_updated_at" to updatedAt.toString(),
                            "last_render_at" to lastRender?.toString(),
                        )
                    }
                }
        if (stale.isNotEmpty()) {
            throw DatapipelinesException(
                co.datapipelines.parameters.ParameterErrorCodes.EVALUATE_TEMPLATE_UNRENDERED,
                "A pinned template draft was written after this key's last render of it — the evaluate is not its " +
                    "render. Render before you evaluate: " +
                    stale.joinToString("; ") { "${it["template_id"]} v${it["template_version"]}" } +
                    ". Call templates_render for each, then evaluate again.",
                mapOf("templates" to stale),
            )
        }
    }
}

/**
 * `parameter_sets_purge_draft` (mcp-server.md §6.2.49) — the bounded 107-style self-service
 * verb, versioning §5.4: hash-guarded, drafts only, the sole draft taking the set.
 * Permission: `parameter_set.version.manage`.
 */
class ParameterSetsPurgeDraftTool(
    private val sets: ParameterSetService,
    private val repository: ParameterSetRepository,
) : McpTool {
    override val definition: McpSchema.Tool =
        McpTools.tool(
            name = "parameter_sets_purge_draft",
            description =
                "Hard-delete a parameter set's DRAFT by ID (versioning §5.4): the draft row is deleted, never " +
                    "restorable. Requires expected_hash — the body_hash you read from parameter_sets_get — so you " +
                    "purge the draft you actually looked at. If the draft is the set's ONLY version, the set goes " +
                    "with it. A RELEASED version is never touched here (discard it over REST if that is the intent). " +
                    "A draft a dashboard pins is refused parameter.in_use, naming the dashboards (#320). " +
                    "This is a write: it is audited as one.",
            schema =
                """
                {
                  "type": "object",
                  "required": ["id", "expected_hash"],
                  "properties": {
                    "id": {"type": "string", "format": "uuid"},
                    "expected_hash": {"type": "string", "description": "The draft's body_hash. A mismatch is a 409 conflict; re-read first."}
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
        val id = args.requiredUuid("id")
        repository.findRecord(workspaceId, id) ?: throw McpNotFound.parameterSet(id)
        sets.purgeDraft(workspaceId, id, args.requiredString("expected_hash"))
        return mapOf("id" to id.toString(), "purged" to true)
    }
}
