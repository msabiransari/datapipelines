package co.datapipelines.web.templates

import co.datapipelines.auth.RequiredScope
import co.datapipelines.auth.ScopeMatrix
import co.datapipelines.pipeline.CreateLifecycle
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.pipeline.TemplateType
import co.datapipelines.templates.Template
import co.datapipelines.templates.TemplateDeserializer
import co.datapipelines.templates.TemplateDraftService
import co.datapipelines.templates.TemplateFolder
import co.datapipelines.templates.TemplateJson
import co.datapipelines.templates.TemplateNameGrammar
import co.datapipelines.templates.TemplateRepository
import co.datapipelines.templates.TemplateValidator
import co.datapipelines.templates.TemplateVersionDetail
import co.datapipelines.templates.WorkspaceTemplateEngines
import co.datapipelines.typesystem.Dialect
import co.datapipelines.web.api.ApiErrors
import co.datapipelines.web.api.ApiException
import co.datapipelines.web.api.ApiResponse
import co.datapipelines.web.api.PagedData
import co.datapipelines.web.api.Pagination
import co.datapipelines.web.api.currentPrincipal
import co.datapipelines.web.pipelines.IfMatchHeader
import co.datapipelines.web.pipelines.LifecycleVerbs
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * The template endpoints (rest-api.md §8; the draft/release lifecycle per versioning.md
 * §6/§7).
 *
 * ## Addressing (rest-api.md §8, template-hierarchy-design §9.6): the name NEVER travels in a
 * URL path segment
 *
 * A template name may contain `/` (`acme/finance/report`), and on the pinned Tomcat an
 * encoded `%2F` in the path is refused **400 below routing and below the security chain** —
 * no handler can reach past it. Every route here therefore carries the name as a query
 * parameter or a body field; the old `/{id}` forms are removed, not kept alongside (one
 * addressing form — the owner's ruling recorded in §9.6). `GET /api/v1/templates` answers
 * THREE shapes on one route: the single-resource envelope with `404 template.not_found` when
 * `name` is present, one tree level (folders with counts + leaves) when `prefix` is present
 * (067's browse presentation), and the paged list envelope when neither is. Preserving
 * `template.not_found` is deliberate: an exact-match filter returning an
 * empty list would make "no such template" indistinguishable from "empty result".
 *
 * Save-time validation is **parse-only** (templates.md §7.1) and runs on every write:
 * [TemplateDeserializer] binds the wire shape, [TemplateValidator] checks syntax, forbidden
 * constructs and import resolution — nothing invalid reaches the database (§2.8 universal).
 * Bodies are raw JSON for the same reason as pipelines: the template wire shape is frozen and its
 * deserializer is the single place its rules live.
 *
 * ## The write rule (versioning §3.2/§6)
 *
 * `PUT` always writes the DRAFT branch — copy-on-write first (§5.1), in-place overwrite
 * after (§5.2) — with the `If-Match` hash precondition (§4.2). `POST` still lands v1
 * RELEASED. Release/discard are hash-guarded, UI-driven actions; agents never release (D4).
 */
@RestController
@RequestMapping("/api/v1/templates")
class TemplatesController(
    private val templates: TemplateRepository,
    private val validator: TemplateValidator,
    private val templateEngines: WorkspaceTemplateEngines,
    private val importService: TemplateImportService,
    private val drafts: TemplateDraftService,
    private val releases: TemplateReleaseService,
    private val authoring: co.datapipelines.pipeline.AuthoringGuard,
    private val audit: co.datapipelines.auth.AuditEventSink,
    private val deserializer: TemplateDeserializer = TemplateDeserializer(),
) {
    /** §8.1 — create; the server assigns version 1 RELEASED (and the id when the body omits one). */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiredScope(ScopeMatrix.RestOperation.MUTATE_PIPELINES_TEMPLATES)
    fun create(
        @RequestBody body: String,
    ): ApiResponse<Template> {
        // §5.5: creation is authoring — a promotion receiver refuses it.
        authoring.requireTemplateAuthoring()
        val principal = currentPrincipal()
        val workspaceId = principal.requireWorkspace().id
        val draft = validator.validateOrThrow(deserializer.readOrThrow(body), workspaceId)
        // D55: authoring lands version 1 DRAFT (`status: "DRAFT"` in the response); a human
        // releases it. The RELEASED create belongs to the import path alone.
        return ApiResponse.of(templates.create(workspaceId, draft, principal.userId, CreateLifecycle.DRAFT))
    }

    /**
     * §8.2 — the **working version**: the DRAFT's projection when one exists, else the
     * latest RELEASED version (versioning §7, since 039 — the mirror of §5.2). The
     * response carries the returned version's `status`/`body_hash` (on the [Template]
     * projection since V6) and the `draft` pointer when one exists.
     *
     * One of the three shapes on `GET /api/v1/templates` (§9.6): this one answers when `name`
     * is present, with `404 template.not_found` on a miss — never an empty list.
     */
    @GetMapping(params = ["name"])
    @RequiredScope(ScopeMatrix.RestOperation.READ_RESOURCES)
    fun get(
        @RequestParam name: String,
    ): ApiResponse<JsonNode> {
        val workspaceId = currentPrincipal().requireWorkspace().id
        val draft = templates.findDraftDetail(workspaceId, name)
        val template =
            draft?.let { templates.findVersion(workspaceId, name, it.version) }
                ?: templates.findLatest(workspaceId, name)
                ?: throw ApiErrors.templateNotFound(name)
        return ApiResponse.of(withDraftPointer(template, draft))
    }

    /** §8.3 — a specific version, including of a soft-deleted template (templates.md §5.1). */
    @GetMapping("/versions")
    @RequiredScope(ScopeMatrix.RestOperation.READ_RESOURCES)
    fun getVersion(
        @RequestParam name: String,
        @RequestParam version: Int,
    ): ApiResponse<Template> {
        val workspaceId = currentPrincipal().requireWorkspace().id
        return ApiResponse.of(
            templates.findVersion(workspaceId, name, version)
                ?: throw if (templates.existsId(
                        workspaceId,
                        name,
                    )
                ) {
                    ApiErrors.templateNotFound(name, version)
                } else {
                    ApiErrors.templateNotFound(name)
                },
        )
    }

    /**
     * §8.5 — the listing; the repository paginates in SQL, `total` is the honest lower bound.
     * One of the two list shapes on `GET /api/v1/templates` (§9.6): answers when `name` AND
     * `prefix` are both ABSENT.
     */
    @GetMapping(params = ["!name", "!prefix"])
    @RequiredScope(ScopeMatrix.RestOperation.READ_RESOURCES)
    fun list(
        @RequestParam(required = false) dialect: String?,
        @RequestParam(required = false) type: String?,
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) offset: Int?,
        @RequestParam(required = false) limit: Int?,
    ): ApiResponse<PagedData<Template>> {
        val workspaceId = currentPrincipal().requireWorkspace().id
        val page = Pagination.clampOffset(offset)
        val size = Pagination.clampLimit(limit)
        val filter = dialect?.let { parseDialect(it) }
        val typeFilter = type?.let { parseType(it) }
        val raw =
            templates.list(workspaceId, dialect = filter, type = typeFilter, q = q, offset = page, limit = size + 1)
        val items = raw.take(size)
        return ApiResponse.of(PagedData(items, Pagination.unknownTotal(page, size, items.size, raw.size > size)))
    }

    /**
     * §8.5 — ONE level of the template tree, the REST mirror of `templates_list {prefix}`
     * (067): [prefix]'s direct sub-folders with their subtree counts and its direct template
     * leaves, never a subtree. The other list shape on this route: answers when `name` is
     * absent and `prefix` is PRESENT — `?prefix=` (empty) is the ROOT, a different request
     * from an absent `prefix` (the flat listing above). `dialect`/`type` narrow both halves,
     * exactly as on the flat list, so a folder whose whole subtree is filtered out is absent
     * rather than empty (§9.1); `q` does not apply — browse and search are different
     * presentations (template-hierarchy-design §9.2). An unknown or illegal prefix answers an
     * ordinary EMPTY level with 200 — never a 400, never a query error — the rule
     * `templates_list` and [TemplateBrowseModel][co.datapipelines.web.ui.TemplateBrowseModel]
     * already settled on.
     */
    @GetMapping(params = ["!name", "prefix"])
    @RequiredScope(ScopeMatrix.RestOperation.READ_RESOURCES)
    fun browse(
        @RequestParam prefix: String,
        @RequestParam(required = false) dialect: String?,
        @RequestParam(required = false) type: String?,
        @RequestParam(required = false) offset: Int?,
        @RequestParam(required = false) limit: Int?,
    ): ApiResponse<Map<String, Any?>> {
        val workspaceId = currentPrincipal().requireWorkspace().id
        val page = Pagination.clampOffset(offset)
        val size = Pagination.clampLimit(limit)
        // Blank-is-root, exactly as the MCP tool's `string("prefix")` normalizes it: present
        // but empty names the tree's top level, not "no prefix".
        val normalized = prefix.takeIf { it.isNotBlank() }
        if (normalized != null && !TemplateNameGrammar.matchesPrefix(normalized)) {
            return ApiResponse.of(levelPayload(prefix, emptyList(), emptyList(), total = 0, hasMore = false))
        }
        val filter = dialect?.let { parseDialect(it) }
        val typeFilter = type?.let { parseType(it) }
        val folders = templates.listChildFolders(workspaceId, normalized, filter, typeFilter, limit = TemplateRepository.MAX_PAGE_LIMIT)
        val probe = templates.listChildTemplates(workspaceId, normalized, filter, typeFilter, offset = page, limit = size + 1)
        return ApiResponse.of(
            levelPayload(
                normalized.orEmpty(),
                folders,
                probe.take(size),
                total = templates.countChildTemplates(workspaceId, normalized, filter, typeFilter),
                hasMore = probe.size > size,
            ),
        )
    }

    /**
     * §8.4 — update, writing the DRAFT branch (versioning §5.1/§5.2): first write after a
     * release copies to a draft, later writes overwrite it in place. Requires the `If-Match`
     * hash precondition; the response is the draft version's projection plus its draft pointer.
     * The template's name travels in the body's `id` field (§9.6), never in the path.
     */
    @PutMapping
    @RequiredScope(ScopeMatrix.RestOperation.MUTATE_PIPELINES_TEMPLATES)
    fun update(
        @RequestHeader(value = IfMatchHeader.NAME, required = false) ifMatch: String?,
        @RequestBody body: String,
    ): ApiResponse<JsonNode> {
        val principal = currentPrincipal()
        val workspaceId = principal.requireWorkspace().id
        val draft = validator.validateOrThrow(deserializer.readOrThrow(body), workspaceId)
        val id =
            draft.id ?: throw ApiException(
                PipelineErrorCodes.Template.ID_INVALID,
                "PUT /api/v1/templates requires the template 'id' in the body (§9.6: the name never travels in the path).",
                mapOf(ApiErrors.REASON to "id_missing"),
            )
        val written = drafts.write(workspaceId, id, draft, IfMatchHeader.required(ifMatch), principal.userId)
        val stored =
            templates.findVersion(workspaceId, id, written.version) ?: throw ApiErrors.templateNotFound(id)
        return ApiResponse.of(withDraftPointer(stored, written))
    }

    /**
     * §8.9 — release (lock) the template draft: `template.version.not_draft` when none
     * exists, re-validation on the draft content, `template.version.conflict` on a stale
     * hash. Templates lock before pipelines (versioning §6). UI-driven (D4).
     * The name is the body's `name` field (§9.6).
     */
    @PostMapping("/release")
    @RequiredScope(ScopeMatrix.RestOperation.MUTATE_PIPELINES_TEMPLATES)
    fun release(
        @RequestHeader(value = IfMatchHeader.NAME, required = false) ifMatch: String?,
        @RequestBody body: String,
    ): ApiResponse<JsonNode> {
        val principal = currentPrincipal()
        val workspaceId = principal.requireWorkspace().id
        val released = releases.release(workspaceId, nameOf(body), IfMatchHeader.required(ifMatch), principal.userId)
        return ApiResponse.of(withDraftPointer(released.template, null))
    }

    /**
     * §8.10 (101) — purge the template draft (always a hard delete: nothing references a
     * template version by FK; the sole-draft case takes the entity with it). Hash-guarded.
     * The route keeps its historical spelling for the editor; the verb underneath is the
     * purge (versioning §5.4). Session-only, audited. The name is the body's `name` (§9.6).
     */
    @PostMapping("/draft/discard")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiredScope(ScopeMatrix.RestOperation.MUTATE_PIPELINES_TEMPLATES)
    fun discard(
        @RequestHeader(value = IfMatchHeader.NAME, required = false) ifMatch: String?,
        @RequestBody body: String,
    ) {
        val principal = LifecycleVerbs.requireSession()
        val workspaceId = principal.requireWorkspace().id
        val name = nameOf(body)
        releases.purge(workspaceId, name, IfMatchHeader.required(ifMatch))
        LifecycleVerbs.audit(
            audit,
            LifecycleVerbs.TEMPLATE_AUDIT_VERSION_PURGED,
            principal,
            workspaceId,
            mapOf("template_id" to name, "scope" to "draft"),
        )
    }

    /**
     * §8 (101) — discard RELEASED version v: flip to DISCARDED (reversible via restore),
     * pointer per D60; `template.in_use` while a live pipeline version pins it. The name and
     * version are body fields (§9.6: the name never travels in a path segment). Session-only,
     * audited.
     */
    @PostMapping("/version/discard")
    @RequiredScope(ScopeMatrix.RestOperation.MUTATE_PIPELINES_TEMPLATES)
    fun discardVersion(
        @RequestBody body: String,
    ): ApiResponse<Map<String, Any?>> {
        val principal = LifecycleVerbs.requireSession()
        val workspaceId = principal.requireWorkspace().id
        val request = versionVerbRequestOf(body)
        val detail = releases.discardVersion(workspaceId, request.name, request.version, principal.userId)
        LifecycleVerbs.audit(
            audit,
            LifecycleVerbs.TEMPLATE_AUDIT_VERSION_DISCARDED,
            principal,
            workspaceId,
            mapOf("template_id" to request.name, "version" to request.version),
        )
        return ApiResponse.of(versionDetailJson(detail))
    }

    /** §8 (101) — restore DISCARDED version v to RELEASED; pointer moves only above-current-or-NULL. */
    @PostMapping("/version/restore")
    @RequiredScope(ScopeMatrix.RestOperation.MUTATE_PIPELINES_TEMPLATES)
    fun restoreVersion(
        @RequestBody body: String,
    ): ApiResponse<Map<String, Any?>> {
        val principal = LifecycleVerbs.requireSession()
        val workspaceId = principal.requireWorkspace().id
        val request = versionVerbRequestOf(body)
        val detail = releases.restoreVersion(workspaceId, request.name, request.version)
        LifecycleVerbs.audit(
            audit,
            LifecycleVerbs.TEMPLATE_AUDIT_VERSION_RESTORED,
            principal,
            workspaceId,
            mapOf("template_id" to request.name, "version" to request.version),
        )
        return ApiResponse.of(versionDetailJson(detail))
    }

    /** §8 (101) — purge DRAFT version v (drafts only; the sole-draft case takes the entity). Irreversible. */
    @DeleteMapping("/version")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiredScope(ScopeMatrix.RestOperation.MUTATE_PIPELINES_TEMPLATES)
    fun purgeVersion(
        @RequestParam name: String,
        @RequestParam version: Int,
    ) {
        val principal = LifecycleVerbs.requireSession()
        val workspaceId = principal.requireWorkspace().id
        releases.purgeVersion(workspaceId, name, version)
        LifecycleVerbs.audit(
            audit,
            LifecycleVerbs.TEMPLATE_AUDIT_VERSION_PURGED,
            principal,
            workspaceId,
            mapOf("template_id" to name, "version" to version, "scope" to "version"),
        )
    }

    /** §8 (101) — the manual switch: `current = version`, live and posture-eligible. The receiver's lever. */
    @PostMapping("/current")
    @RequiredScope(ScopeMatrix.RestOperation.MUTATE_PIPELINES_TEMPLATES)
    fun switchCurrent(
        @RequestBody body: String,
    ): ApiResponse<Map<String, Any?>> {
        val principal = LifecycleVerbs.requireSession()
        val workspaceId = principal.requireWorkspace().id
        val request = versionVerbRequestOf(body)
        val current = releases.switchCurrent(workspaceId, request.name, request.version)
        LifecycleVerbs.audit(
            audit,
            LifecycleVerbs.TEMPLATE_AUDIT_CURRENT_SWITCHED,
            principal,
            workspaceId,
            mapOf("template_id" to request.name, "to" to current),
        )
        return ApiResponse.of(mapOf("name" to request.name, "current_version" to current))
    }

    /** A 101 version-verb body: `name` + `version` (§9.6), read through the house body reader. */
    private fun versionVerbRequestOf(body: String): VersionVerbRequest {
        val tree = objectOf(body)
        val name = tree.get("name")?.asText("")
        val version = tree.get("version")?.takeIf { it.isInt }?.asInt()
        if (name.isNullOrBlank() || version == null || version < 1) {
            throw ApiException(
                PipelineErrorCodes.Execution.INVALID_PARAMETER_TYPE,
                "The version verb requires the template 'name' and a numeric 'version' in the body (§9.6).",
                mapOf(ApiErrors.REASON to "name_and_version_required"),
            )
        }
        return VersionVerbRequest(name, version)
    }

    private data class VersionVerbRequest(
        val name: String,
        val version: Int,
    )

    private fun versionDetailJson(detail: TemplateVersionDetail): Map<String, Any?> =
        mapOf(
            "name" to detail.templateId,
            "version" to detail.version,
            "status" to detail.status.name,
            "body_hash" to detail.bodyHash,
            "released_at" to (detail.releasedAt?.toString() ?: ""),
            "discarded_at" to (detail.discardedAt?.toString() ?: ""),
        )

    /**
     * §8.6 (101) — the entity purge, replacing the V1 soft delete (retired in V19): allowed
     * only when the template's ONLY version is a DRAFT, and refused with `409 template.in_use`
     * while any pipeline version pins any version of it (040 D4's reverse scan — the refusal
     * is retirement protection: pipelines referencing a discarded template's versions keep
     * resolving, so it exists to make "who still uses this?" a refusal the author cannot
     * miss). A template holding a non-draft version is refused
     * `template.version.last_release`. Session-only, audited.
     */
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiredScope(ScopeMatrix.RestOperation.MUTATE_PIPELINES_TEMPLATES)
    fun delete(
        @RequestParam name: String,
    ) {
        val principal = LifecycleVerbs.requireSession()
        releases.purgeEntity(principal.requireWorkspace().id, name)
        LifecycleVerbs.audit(
            audit,
            LifecycleVerbs.TEMPLATE_AUDIT_ENTITY_PURGED,
            principal,
            principal.requireWorkspace().id,
            mapOf("template_id" to name),
        )
    }

    /**
     * §8.7 — render against a sample context. The response `data` IS the rendered SQL string:
     * "Response: rendered SQL string" pins the payload, and the envelope (§4.1) wraps it.
     * `name`, `version` and `context` are body fields (§9.6).
     */
    @PostMapping("/render")
    @RequiredScope(ScopeMatrix.RestOperation.MUTATE_PIPELINES_TEMPLATES)
    fun render(
        @RequestBody body: String,
    ): ApiResponse<String> {
        val workspaceId = currentPrincipal().requireWorkspace().id
        val request = renderRequestOf(body)
        if (templates.lookupVersion(workspaceId, request.name, request.version) == null) {
            throw if (templates.existsId(
                    workspaceId,
                    request.name,
                )
            ) {
                ApiErrors.templateNotFound(request.name, request.version)
            } else {
                ApiErrors.templateNotFound(request.name)
            }
        }
        return ApiResponse.of(
            templateEngines.engineFor(workspaceId).render(TemplateRef(request.name, request.version), request.context),
        )
    }

    /**
     * §8.8 — import a template library: each entry is created, or versioned when its id exists.
     * All-or-nothing per entry is deliberate: a failure aborts the import at that entry and the
     * earlier ones stay — a library import is re-runnable, so a partial import is completable by
     * retry, and transactional multi-row semantics are not something the repositories offer.
     */
    @PostMapping("/import")
    @RequiredScope(ScopeMatrix.RestOperation.MUTATE_PIPELINES_TEMPLATES)
    fun import(
        @RequestBody body: String,
    ): ApiResponse<Map<String, Any?>> {
        val principal = currentPrincipal()
        val stored = importService.import(body, principal.requireWorkspace().id, principal.userId)
        return ApiResponse.of(mapOf("imported" to stored.size, "templates" to stored))
    }

    /** A parsed `POST /render` body: `name`, `version` and the sample `context` (§9.6). */
    private data class RenderRequest(
        val name: String,
        val version: Int,
        val context: Map<String, Any?>,
    )

    /** The `{"name": ...}` field of a release/discard body — the §9.6 addressing form. */
    private fun nameOf(body: String): String = nameOf(objectOf(body))

    private fun nameOf(tree: ObjectNode): String =
        tree.get("name")?.asText()?.takeIf { it.isNotBlank() }
            ?: throw ApiException(
                PipelineErrorCodes.Execution.INVALID_PARAMETER_TYPE,
                "The request requires a 'name' field (§9.6: the name never travels in the path).",
                mapOf(ApiErrors.REASON to "name_missing"),
            )

    private fun renderRequestOf(body: String): RenderRequest {
        val tree = objectOf(body)
        val version =
            tree.get("version")?.takeIf { it.isInt }?.asInt()
                ?: throw ApiException(
                    PipelineErrorCodes.Execution.INVALID_PARAMETER_TYPE,
                    "The render request requires an integer 'version' field.",
                    mapOf(ApiErrors.REASON to "version_missing"),
                )
        val context =
            tree.get("context") as? ObjectNode
                ?: throw ApiException(
                    PipelineErrorCodes.Execution.INVALID_PARAMETER_TYPE,
                    "The render request requires a 'context' object.",
                    mapOf(ApiErrors.REASON to "context_missing"),
                )
        return RenderRequest(
            name = nameOf(tree),
            version = version,
            context = context.properties().associate { (key, value) -> key to MAPPER.treeToValue(value, Any::class.java) },
        )
    }

    /** The request body as a JSON object, or the catalogued malformed-body refusal. */
    private fun objectOf(body: String): ObjectNode {
        val tree =
            try {
                MAPPER.readTree(body)
            } catch (e: com.fasterxml.jackson.core.JsonProcessingException) {
                throw ApiException(
                    PipelineErrorCodes.Template.SCHEMA_VERSION_UNSUPPORTED,
                    "The request body must be a JSON object.",
                    mapOf(ApiErrors.REASON to ApiErrors.MALFORMED_JSON),
                    e,
                )
            }
        return tree as? ObjectNode
            ?: throw ApiException(
                PipelineErrorCodes.Template.SCHEMA_VERSION_UNSUPPORTED,
                "The request body must be a JSON object.",
                mapOf(ApiErrors.REASON to ApiErrors.MALFORMED_JSON),
            )
    }

    /**
     * The §7 read shape: the [Template] projection (which carries `status`/`body_hash`)
     * with the `draft` pointer merged in when one exists. Merged as a tree rather than by
     * adding a field to [Template]: the draft pointer belongs to the TEMPLATE, not to any
     * one version, and the wire type should not grow a field no stored row can populate.
     */
    private fun withDraftPointer(
        template: Template,
        draft: co.datapipelines.templates.TemplateVersionDetail?,
    ): JsonNode {
        val node = MAPPER.valueToTree<JsonNode>(template) as ObjectNode
        if (draft != null) {
            val pointer = node.putObject("draft")
            pointer.put("version", draft.version)
            pointer.put("body_hash", draft.bodyHash)
            pointer.put("updated_by", draft.updatedBy?.toString() ?: "")
            pointer.put("updated_at", draft.updatedAt?.toString() ?: "")
        }
        return node
    }

    /**
     * One level of the template tree, in the same shape `templates_list {prefix}` answers:
     * the level's sub-folders with their subtree counts, its direct leaves as the same
     * [Template] rows the flat list returns, the leaves' truthful total and the page probe's
     * `has_more`. [prefix] is echoed as requested — the raw value for an illegal prefix, the
     * normalized one (root = `""`) otherwise, exactly the MCP tool's echo rule.
     */
    private fun levelPayload(
        prefix: String,
        folders: List<TemplateFolder>,
        leaves: List<Template>,
        total: Int,
        hasMore: Boolean,
    ): Map<String, Any?> =
        mapOf(
            "prefix" to prefix,
            "folders" to folders.map { mapOf("path" to it.path, "segment" to it.segment, "template_count" to it.templateCount) },
            "templates" to leaves,
            "total" to total,
            "has_more" to hasMore,
        )

    private fun parseDialect(raw: String): Dialect =
        runCatching { Dialect.fromWire(raw.trim().uppercase()) }.getOrNull()
            ?: throw ApiException(
                PipelineErrorCodes.Execution.INVALID_PARAMETER_TYPE,
                "Unknown dialect '$raw'.",
                mapOf("dialect" to raw.take(MAX_ECHOED_VALUE_CHARS), "supported" to Dialect.entries.map { it.wire }),
            )

    /** The `type` list filter (046 §10) — the `parseDialect` shape, for the type enum. */
    private fun parseType(raw: String): TemplateType =
        TemplateType.fromWire(raw.trim().lowercase())
            ?: throw ApiException(
                PipelineErrorCodes.Execution.INVALID_PARAMETER_TYPE,
                "Unknown template type '$raw'.",
                mapOf("type" to raw.take(MAX_ECHOED_VALUE_CHARS), "supported" to TemplateType.WIRE_VALUES),
            )

    private companion object {
        val MAPPER = TemplateJson.objectMapper()

        const val MAX_ECHOED_VALUE_CHARS = 32
    }
}
