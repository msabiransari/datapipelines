package co.datapipelines.web.templates

import co.datapipelines.auth.RequiredScope
import co.datapipelines.auth.ScopeMatrix
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.templates.Template
import co.datapipelines.templates.TemplateDeserializer
import co.datapipelines.templates.TemplateDraftService
import co.datapipelines.templates.TemplateJson
import co.datapipelines.templates.TemplateRepository
import co.datapipelines.templates.TemplateValidator
import co.datapipelines.templates.WorkspaceTemplateEngines
import co.datapipelines.typesystem.Dialect
import co.datapipelines.web.api.ApiErrors
import co.datapipelines.web.api.ApiException
import co.datapipelines.web.api.ApiResponse
import co.datapipelines.web.api.PagedData
import co.datapipelines.web.api.Pagination
import co.datapipelines.web.api.currentPrincipal
import co.datapipelines.web.pipelines.IfMatchHeader
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
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
    private val deserializer: TemplateDeserializer = TemplateDeserializer(),
) {
    /** §8.1 — create; the server assigns version 1 RELEASED (and the id when the body omits one). */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiredScope(ScopeMatrix.RestOperation.MUTATE_PIPELINES_TEMPLATES)
    fun create(
        @RequestBody body: String,
    ): ApiResponse<Template> {
        val principal = currentPrincipal()
        val workspaceId = principal.requireWorkspace().id
        val draft = validator.validateOrThrow(deserializer.readOrThrow(body), workspaceId)
        return ApiResponse.of(templates.create(workspaceId, draft, principal.userId))
    }

    /**
     * §8.2 — the latest RELEASED version, with the lifecycle read shape: the version's
     * `status`/`body_hash` (on the [Template] projection since V6) and the `draft` pointer
     * when one exists (versioning §7).
     */
    @GetMapping("/{id}")
    @RequiredScope(ScopeMatrix.RestOperation.READ_RESOURCES)
    fun get(
        @PathVariable id: String,
    ): ApiResponse<JsonNode> {
        val workspaceId = currentPrincipal().requireWorkspace().id
        val template = templates.findLatest(workspaceId, id) ?: throw ApiErrors.templateNotFound(id)
        return ApiResponse.of(withDraftPointer(template, templates.findDraftDetail(workspaceId, id)))
    }

    /** §8.3 — a specific version, including of a soft-deleted template (templates.md §5.1). */
    @GetMapping("/{id}/versions/{version}")
    @RequiredScope(ScopeMatrix.RestOperation.READ_RESOURCES)
    fun getVersion(
        @PathVariable id: String,
        @PathVariable version: Int,
    ): ApiResponse<Template> {
        val workspaceId = currentPrincipal().requireWorkspace().id
        return ApiResponse.of(
            templates.findVersion(workspaceId, id, version)
                ?: throw if (templates.existsId(
                        workspaceId,
                        id,
                    )
                ) {
                    ApiErrors.templateNotFound(id, version)
                } else {
                    ApiErrors.templateNotFound(id)
                },
        )
    }

    /** §8.5 — the listing; the repository paginates in SQL, `total` is the honest lower bound. */
    @GetMapping
    @RequiredScope(ScopeMatrix.RestOperation.READ_RESOURCES)
    fun list(
        @RequestParam(required = false) dialect: String?,
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) offset: Int?,
        @RequestParam(required = false) limit: Int?,
    ): ApiResponse<PagedData<Template>> {
        val workspaceId = currentPrincipal().requireWorkspace().id
        val page = Pagination.clampOffset(offset)
        val size = Pagination.clampLimit(limit)
        val filter = dialect?.let { parseDialect(it) }
        val raw = templates.list(workspaceId, dialect = filter, q = q, offset = page, limit = size + 1)
        val items = raw.take(size)
        return ApiResponse.of(PagedData(items, Pagination.unknownTotal(page, size, items.size, raw.size > size)))
    }

    /**
     * §8.4 — update, writing the DRAFT branch (versioning §5.1/§5.2): first write after a
     * release copies to a draft, later writes overwrite it in place. Requires the `If-Match`
     * hash precondition; the response is the draft version's projection plus its draft pointer.
     */
    @PutMapping("/{id}")
    @RequiredScope(ScopeMatrix.RestOperation.MUTATE_PIPELINES_TEMPLATES)
    fun update(
        @PathVariable id: String,
        @RequestHeader(value = IfMatchHeader.NAME, required = false) ifMatch: String?,
        @RequestBody body: String,
    ): ApiResponse<JsonNode> {
        val principal = currentPrincipal()
        val workspaceId = principal.requireWorkspace().id
        val draft = validator.validateOrThrow(deserializer.readOrThrow(body), workspaceId)
        val written = drafts.write(workspaceId, id, draft, IfMatchHeader.required(ifMatch), principal.userId)
        val stored =
            templates.findVersion(workspaceId, id, written.version) ?: throw ApiErrors.templateNotFound(id)
        return ApiResponse.of(withDraftPointer(stored, written))
    }

    /**
     * §8.9 — release (lock) the template draft: `template.version.not_draft` when none
     * exists, re-validation on the draft content, `template.version.conflict` on a stale
     * hash. Templates lock before pipelines (versioning §6). UI-driven (D4).
     */
    @PostMapping("/{id}/release")
    @RequiredScope(ScopeMatrix.RestOperation.MUTATE_PIPELINES_TEMPLATES)
    fun release(
        @PathVariable id: String,
        @RequestHeader(value = IfMatchHeader.NAME, required = false) ifMatch: String?,
    ): ApiResponse<JsonNode> {
        val principal = currentPrincipal()
        val workspaceId = principal.requireWorkspace().id
        val released = releases.release(workspaceId, id, IfMatchHeader.required(ifMatch), principal.userId)
        return ApiResponse.of(withDraftPointer(released.template, null))
    }

    /**
     * §8.10 — discard the template draft (always a hard delete: nothing references a
     * template version by FK). Hash-guarded.
     */
    @PostMapping("/{id}/draft/discard")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiredScope(ScopeMatrix.RestOperation.MUTATE_PIPELINES_TEMPLATES)
    fun discard(
        @PathVariable id: String,
        @RequestHeader(value = IfMatchHeader.NAME, required = false) ifMatch: String?,
    ) {
        val workspaceId = currentPrincipal().requireWorkspace().id
        releases.discard(workspaceId, id, IfMatchHeader.required(ifMatch))
    }

    /** §8.6 — soft delete; pipelines referencing any version continue to work. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiredScope(ScopeMatrix.RestOperation.MUTATE_PIPELINES_TEMPLATES)
    fun delete(
        @PathVariable id: String,
    ) {
        val workspaceId = currentPrincipal().requireWorkspace().id
        if (!templates.softDelete(workspaceId, id)) throw ApiErrors.templateNotFound(id)
    }

    /**
     * §8.7 — render against a sample context. The response `data` IS the rendered SQL string:
     * "Response: rendered SQL string" pins the payload, and the envelope (§4.1) wraps it.
     */
    @PostMapping("/{id}/versions/{version}/render")
    @RequiredScope(ScopeMatrix.RestOperation.MUTATE_PIPELINES_TEMPLATES)
    fun render(
        @PathVariable id: String,
        @PathVariable version: Int,
        @RequestBody body: String,
    ): ApiResponse<String> {
        val workspaceId = currentPrincipal().requireWorkspace().id
        if (templates.lookupVersion(workspaceId, id, version) == null) {
            throw if (templates.existsId(workspaceId, id)) ApiErrors.templateNotFound(id, version) else ApiErrors.templateNotFound(id)
        }
        val context = contextOf(body)
        return ApiResponse.of(templateEngines.engineFor(workspaceId).render(TemplateRef(id, version), context))
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

    /** The `render` request's `context` object as the engine's render map. */
    private fun contextOf(body: String): Map<String, Any?> {
        val tree =
            MAPPER.readTree(body) as? ObjectNode
                ?: throw ApiException(
                    PipelineErrorCodes.Template.SCHEMA_VERSION_UNSUPPORTED,
                    "The render request body must be a JSON object.",
                    mapOf(ApiErrors.REASON to ApiErrors.MALFORMED_JSON),
                )
        val context =
            tree.get("context") as? ObjectNode
                ?: throw ApiException(
                    PipelineErrorCodes.Execution.INVALID_PARAMETER_TYPE,
                    "The render request requires a 'context' object.",
                    mapOf(ApiErrors.REASON to "context_missing"),
                )
        return context.properties().associate { (key, value) -> key to MAPPER.treeToValue(value, Any::class.java) }
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

    private fun parseDialect(raw: String): Dialect =
        runCatching { Dialect.fromWire(raw.trim().uppercase()) }.getOrNull()
            ?: throw ApiException(
                PipelineErrorCodes.Execution.INVALID_PARAMETER_TYPE,
                "Unknown dialect '$raw'.",
                mapOf("dialect" to raw.take(MAX_ECHOED_VALUE_CHARS), "supported" to Dialect.entries.map { it.wire }),
            )

    private companion object {
        val MAPPER = TemplateJson.objectMapper()

        const val MAX_ECHOED_VALUE_CHARS = 32
    }
}
