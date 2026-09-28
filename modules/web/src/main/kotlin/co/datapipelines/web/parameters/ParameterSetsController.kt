package co.datapipelines.web.parameters

import co.datapipelines.application.lens.PromoterLens
import co.datapipelines.auth.Permission
import co.datapipelines.auth.RequiredScope
import co.datapipelines.parameters.EvaluateResponseJson
import co.datapipelines.parameters.ParameterErrorCodes
import co.datapipelines.parameters.ParameterEvaluator
import co.datapipelines.parameters.ParameterSetImported
import co.datapipelines.parameters.ParameterSetReader
import co.datapipelines.parameters.ParameterSetRepository
import co.datapipelines.parameters.ParameterSetService
import co.datapipelines.parameters.ParametersConfig
import co.datapipelines.web.api.ApiErrors
import co.datapipelines.web.api.ApiResponse
import co.datapipelines.web.api.PagedData
import co.datapipelines.web.api.Pagination
import co.datapipelines.web.api.currentPrincipal
import co.datapipelines.web.api.writeSurface
import co.datapipelines.web.pipelines.IfMatchHeader
import co.datapipelines.web.pipelines.LifecycleVerbs
import com.fasterxml.jackson.databind.JsonNode
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
import java.util.UUID

/**
 * The parameter-set CRUD, versioning and evaluate endpoints (rest-api.md §21; the record's §9.2 —
 * the **pipelines** routes are the mould, addressed by id per P24). Envelopes and codes ride
 * rest-api §4; every refusal is a §13.20 `parameter.*` code the [co.datapipelines.web.api.ApiErrorCatalog]
 * maps.
 *
 * ## Thin, like every surface since 056
 * The rules are [ParameterSetService]'s (the versioning §3.5 verb table, the working-version read
 * rule, the lens) and [ParameterEvaluator]'s (the record's §5). What is left is genuinely REST:
 * the `If-Match` parse, the pagination, the §4 envelope, and turning an absent set into
 * [ApiErrors.parameterNotFound] — a 404 that never confirms existence across workspaces (§11A.1).
 *
 * ## The evaluate request's own bounds (the #279 gap is THIS route's to bound)
 * A viewer-reachable POST that runs SQL on customer datasources is bounded before anything runs:
 * the body is refused over [MAX_EVALUATE_REQUEST_BYTES] before its JSON is parsed (the largest
 * legal selections document is bounded by `max-parameters-per-set` × `max-input-length`, roughly
 * 256 KiB — the cap is stated, never negotiated); every key must name a parameter of the set
 * (`parameter.evaluate.unknown_parameter` — the caller's error, the whole request refused); and
 * every value is judged by the shared validator inside the evaluator (P28). The response is the
 * runtime's JSON verbatim — no echo of the request rides it.
 */
@RestController
@RequestMapping("/api/v1/parameter-sets")
class ParameterSetsController(
    private val sets: ParameterSetService,
    private val repository: ParameterSetRepository,
    private val evaluator: ParameterEvaluator,
    private val transfer: ParameterSetTransferService,
    private val config: ParametersConfig,
    /** 178 — the promoter lens: every read below passes the caller's view, never `Everything`. */
    private val lens: PromoterLens,
) {
    /** §21 — create; the server assigns the id (P24) and lands version 1 DRAFT (D55). */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiredScope(Permission.PARAMETER_SET_CREATE)
    fun create(
        @RequestBody body: String,
    ): ApiResponse<JsonNode> {
        val principal = currentPrincipal()
        val document = ParameterSetReader(config).readOrThrow(TREE.readTree(body))
        val saved = sets.create(principal.requireWorkspace().id, document, principal.userId, principal.writeSurface())
        return ApiResponse.of(ParameterSetResponses.full(saved.record, saved.body, saved.detail))
    }

    /** §21 — the WORKING version's full JSON: the draft when one exists, else the current release (versioning §7.1). */
    @GetMapping("/{id}")
    @RequiredScope(Permission.PARAMETER_SET_READ)
    fun get(
        @PathVariable id: UUID,
    ): ApiResponse<JsonNode> {
        val principal = currentPrincipal()
        val workspaceId = principal.requireWorkspace().id
        val view = lens.viewFor(principal).parameterSets
        val loaded = sets.findWorking(workspaceId, view, id) ?: throw ApiErrors.parameterNotFound(id.toString())
        val draft = repository.findDraft(workspaceId, id)
        return ApiResponse.of(ParameterSetResponses.full(loaded.record, loaded.body, loaded.detail, draft))
    }

    /** §21 — a specific version; a version other than RELEASED is not-found under a narrowing lens. */
    @GetMapping("/{id}/versions/{version}")
    @RequiredScope(Permission.PARAMETER_SET_READ)
    fun getVersion(
        @PathVariable id: UUID,
        @PathVariable version: Int,
    ): ApiResponse<JsonNode> {
        val principal = currentPrincipal()
        val workspaceId = principal.requireWorkspace().id
        val view = lens.viewFor(principal).parameterSets
        val loaded =
            sets.findVersion(workspaceId, view, id, version)
                ?: throw ApiErrors.parameterNotFound(id.toString(), version)
        return ApiResponse.of(ParameterSetResponses.full(loaded.record, loaded.body, loaded.detail))
    }

    /** §21 — version metadata, newest first; no bodies. */
    @GetMapping("/{id}/versions")
    @RequiredScope(Permission.PARAMETER_SET_READ)
    fun versions(
        @PathVariable id: UUID,
    ): ApiResponse<List<Map<String, Any?>>> {
        val principal = currentPrincipal()
        val workspaceId = principal.requireWorkspace().id
        val view = lens.viewFor(principal).parameterSets
        val record = repository.findRecord(workspaceId, id) ?: throw ApiErrors.parameterNotFound(id.toString())
        if (!view.admits(record.name)) throw ApiErrors.parameterNotFound(id.toString())
        return ApiResponse.of(sets.listVersions(workspaceId, view, id).map(ParameterSetResponses::versionSummary))
    }

    /** §21 — the draft write (versioning §5.1/§5.2); `If-Match` carries the hash precondition. */
    @PutMapping("/{id}")
    @RequiredScope(Permission.PARAMETER_SET_UPDATE)
    fun update(
        @PathVariable id: UUID,
        @RequestHeader(value = IfMatchHeader.NAME, required = false) ifMatch: String?,
        @RequestBody body: String,
    ): ApiResponse<JsonNode> {
        // The precondition is checked BEFORE the body is parsed (the pipelines PUT's rule).
        val expectedHash = IfMatchHeader.required(ifMatch)
        val principal = currentPrincipal()
        val document = ParameterSetReader(config).readOrThrow(TREE.readTree(body))
        val written =
            sets.write(
                workspaceId = principal.requireWorkspace().id,
                id = id,
                document = document,
                expectedHash = expectedHash,
                actor = principal.userId,
                via = principal.writeSurface(),
            )
        return ApiResponse.of(ParameterSetResponses.full(written.record, written.body, written.detail))
    }

    /**
     * §21 — release (lock) the draft: every pinned template version RELEASED — or, with
     * `release_pinned_templates=true`, released WITH the set in one transaction, templates
     * first (142); §4 steps 4–6 re-run against the pins as they are now (record §8.2).
     * Session-style in practice; no MCP twin exists (§9.1).
     */
    @PostMapping("/{id}/release")
    @RequiredScope(Permission.PARAMETER_SET_RELEASE)
    fun release(
        @PathVariable id: UUID,
        @RequestHeader(value = IfMatchHeader.NAME, required = false) ifMatch: String?,
        @RequestParam(value = "release_pinned_templates", required = false, defaultValue = "false") releasePinnedTemplates: Boolean = false,
    ): ApiResponse<JsonNode> {
        val principal = currentPrincipal()
        val released =
            sets.release(
                principal.requireWorkspace().id,
                id,
                IfMatchHeader.required(ifMatch),
                principal.userId,
                releasePinnedTemplates,
            )
        return ApiResponse.of(ParameterSetResponses.full(released.version.record, released.version.body, released.version.detail))
    }

    /** §21 — purge the draft (versioning §5.4); the sole draft takes the set with it. Session-only, the pipelines spelling. */
    @PostMapping("/{id}/draft/discard")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiredScope(Permission.PARAMETER_SET_VERSION_MANAGE)
    fun purgeDraft(
        @PathVariable id: UUID,
        @RequestHeader(value = IfMatchHeader.NAME, required = false) ifMatch: String?,
    ) {
        LifecycleVerbs.requireSession()
        sets.purgeDraft(currentPrincipal().requireWorkspace().id, id, IfMatchHeader.required(ifMatch))
    }

    /** §21 — discard RELEASED version v (reversible via restore); pointer per D60. Session-only. */
    @PostMapping("/{id}/versions/{version}/discard")
    @RequiredScope(Permission.PARAMETER_SET_VERSION_MANAGE)
    fun discardVersion(
        @PathVariable id: UUID,
        @PathVariable version: Int,
    ): ApiResponse<Map<String, Any?>> {
        val principal = LifecycleVerbs.requireSession()
        val detail = sets.discardVersion(principal.requireWorkspace().id, id, version, principal.userId)
        return ApiResponse.of(summary(detail))
    }

    /** §21 — restore DISCARDED version v; the pointer moves only above-current-or-NULL. Session-only. */
    @PostMapping("/{id}/versions/{version}/restore")
    @RequiredScope(Permission.PARAMETER_SET_VERSION_MANAGE)
    fun restoreVersion(
        @PathVariable id: UUID,
        @PathVariable version: Int,
    ): ApiResponse<Map<String, Any?>> {
        LifecycleVerbs.requireSession()
        val detail = sets.restoreVersion(currentPrincipal().requireWorkspace().id, id, version)
        return ApiResponse.of(summary(detail))
    }

    /** §21 — purge DRAFT version v (drafts only). Session-only; irreversible. */
    @DeleteMapping("/{id}/versions/{version}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiredScope(Permission.PARAMETER_SET_VERSION_MANAGE)
    fun purgeVersion(
        @PathVariable id: UUID,
        @PathVariable version: Int,
    ) {
        LifecycleVerbs.requireSession()
        sets.purgeVersion(currentPrincipal().requireWorkspace().id, id, version)
    }

    /** §21 — the manual switch (the promotion receiver's rollout/rollback lever). Session-only. */
    @PostMapping("/{id}/current")
    @RequiredScope(Permission.PARAMETER_SET_SWITCH_VERSION)
    fun switchCurrent(
        @PathVariable id: UUID,
        @RequestBody body: JsonNode,
    ): ApiResponse<Map<String, Any?>> {
        val principal = LifecycleVerbs.requireSession()
        val workspaceId = principal.requireWorkspace().id
        if (!body.has("version") || !body["version"].canConvertToInt()) {
            throw ApiErrors.parameterNotFound(id.toString())
        }
        val target = body["version"].asInt()
        repository.findRecord(workspaceId, id) ?: throw ApiErrors.parameterNotFound(id.toString())
        val current = sets.switchCurrent(workspaceId, id, target)
        return ApiResponse.of(mapOf("id" to id.toString(), "current_version" to current))
    }

    /** §21 — the entity purge, only when the set's only version is a DRAFT (versioning §3.2, graph rule 3). */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiredScope(Permission.PARAMETER_SET_DELETE)
    fun delete(
        @PathVariable id: UUID,
    ) {
        LifecycleVerbs.requireSession()
        sets.purgeEntity(currentPrincipal().requireWorkspace().id, id)
    }

    /** §21 — ONE level of the set tree, the `?prefix=` browse (a multi-segment name never travels in a path — P24). */
    @GetMapping(params = ["prefix"])
    @RequiredScope(Permission.PARAMETER_SET_READ)
    fun browse(
        @RequestParam prefix: String,
        @RequestParam(required = false) offset: Int?,
        @RequestParam(required = false) limit: Int?,
    ): ApiResponse<Map<String, Any?>> {
        val principal = currentPrincipal()
        val workspaceId = principal.requireWorkspace().id
        val view = lens.viewFor(principal).parameterSets
        val folders = sets.listChildFolders(workspaceId, view, prefix)
        val loaded =
            sets.listChildSets(workspaceId, view, prefix, Pagination.clampOffset(offset), Pagination.clampLimit(limit))
        return ApiResponse.of(
            mapOf(
                "prefix" to prefix,
                "folders" to
                    folders.map { mapOf("path" to it.path, "segment" to it.segment, "parameter_set_count" to it.setCount) },
                "parameter_sets" to loaded.map { ParameterSetResponses.listEntry(it) },
                "total" to (folders.size + loaded.size),
                "has_more" to false,
            ),
        )
    }

    /** §21 — the flat listing (offset/limit), the pipelines §5.7 shape without its owner/datasource filters. */
    @GetMapping(params = ["!prefix"])
    @RequiredScope(Permission.PARAMETER_SET_READ)
    fun list(
        @RequestParam(required = false) offset: Int?,
        @RequestParam(required = false) limit: Int?,
    ): ApiResponse<PagedData<Map<String, Any?>>> {
        val page = Pagination.clampOffset(offset)
        val size = Pagination.clampLimit(limit)
        val principal = currentPrincipal()
        val workspaceId = principal.requireWorkspace().id
        val view = lens.viewFor(principal).parameterSets
        val loaded = sets.listChildSets(workspaceId, view, null, page, size)
        val items = loaded.map { ParameterSetResponses.listEntry(it) }
        return ApiResponse.of(PagedData(items, Pagination.of(page, size, items.size.toLong(), items.size)))
    }

    /** §21 — export (§8.3): the current release, the pinned templates' closure, the manifest. */
    @GetMapping("/{id}/export")
    @RequiredScope(Permission.PARAMETER_SET_READ)
    fun export(
        @PathVariable id: UUID,
    ): ApiResponse<Map<String, Any?>> {
        val principal = currentPrincipal()
        return ApiResponse.of(transfer.export(principal.requireWorkspace().id, id))
    }

    /** §21 — import (§8.3): templates first, then the set; the exported id is KEPT (P24; C29 refuses a taken id). */
    @PostMapping("/import")
    @RequiredScope(Permission.PARAMETER_SET_IMPORT)
    fun import(
        @RequestBody body: String,
    ): ApiResponse<JsonNode> {
        val principal = currentPrincipal()
        val workspaceId = principal.requireWorkspace().id
        val imported = transfer.import(body, workspaceId, principal.userId)
        val loaded =
            repository.findVersion(workspaceId, imported.detail.parameterSetId, imported.detail.version)
                ?: throw ApiErrors.parameterNotFound(imported.detail.parameterSetId.toString(), imported.detail.version)
        val data =
            ParameterSetResponses.full(loaded.record, loaded.body, loaded.detail) as com.fasterxml.jackson.databind.node.ObjectNode
        data.put("import_created", imported.created)
        data.put("import_unchanged", imported.unchanged)
        return ApiResponse.of(data)
    }

    /**
     * §21 — **evaluate** (the record's §5): the whole set re-rendered against the submitted
     * selections, by id. The version resolves the served one by default; an explicit version
     * (a draft by its number, the working-version rule) is read through the caller's lens. The
     * response is the runtime's `EvaluateResponseJson` verbatim inside the §4 envelope.
     */
    @PostMapping("/{id}/evaluate")
    @RequiredScope(Permission.PARAMETER_SET_EVALUATE)
    fun evaluate(
        @PathVariable id: UUID,
        @RequestBody body: String,
    ): ApiResponse<JsonNode> {
        refuseOversizedBody(body)
        val tree = TREE.readTree(body)
        val principal = currentPrincipal()
        val workspaceId = principal.requireWorkspace().id
        val view = lens.viewFor(principal).parameterSets
        // An EXPLICIT version never falls back to the served one: a miss is the catalogued 404,
        // not a silent evaluate of a different version than the caller named (§5.1).
        val loaded =
            tree.get("version")?.takeIf(JsonNode::isInt)?.asInt()?.let { version ->
                sets.findVersion(workspaceId, view, id, version)
                    ?: throw ApiErrors.parameterNotFound(id.toString(), version)
            } ?: repository.findCurrent(workspaceId, id)?.takeIf { view.admits(it.record.name) }
        val set = loaded ?: throw ApiErrors.parameterNotFound(id.toString())
        val selections =
            tree
                .get("selections")
                ?.takeIf(JsonNode::isObject)
                ?.properties()
                ?.associate { it.key to it.value as JsonNode }
                ?: emptyMap()
        val response = evaluator.evaluateBlocking(workspaceId, set, selections)
        return ApiResponse.of(EvaluateResponseJson.write(response))
    }

    /** The stated bound, BEFORE the JSON parse: an oversized body never reaches the parser (#279). */
    private fun refuseOversizedBody(body: String) {
        if (body.toByteArray(Charsets.UTF_8).size <= MAX_EVALUATE_REQUEST_BYTES) return
        throw co.datapipelines.web.api.ApiException(
            ParameterErrorCodes.EVALUATE_UNKNOWN_PARAMETER,
            "The evaluate request body exceeds $MAX_EVALUATE_REQUEST_BYTES bytes; the largest legal " +
                "selections document for any set is far smaller.",
            mapOf("reason" to "request_too_large", "max_request_bytes" to MAX_EVALUATE_REQUEST_BYTES),
        )
    }

    // ---- helpers ----------------------------------------------------------------------------------

    private fun summary(detail: co.datapipelines.parameters.ParameterSetVersionDetail) =
        mapOf<String, Any?>(
            "parameter_set_id" to detail.parameterSetId.toString(),
            "version" to detail.version,
            "status" to detail.status.name,
            "body_hash" to detail.bodyHash,
        )

    private companion object {
        private val TREE = co.datapipelines.parameters.ParameterSetJson.mapper

        /**
         * The stated evaluate-request bound (rest-api §21): 1 MiB of UTF-8 — comfortably above
         * the largest legal selections document (`max-parameters-per-set`=64 × `max-input-length`
         * =4,096 ≈ 256 KiB + JSON overhead) and far below anything a parser should be asked to read.
         */
        const val MAX_EVALUATE_REQUEST_BYTES = 1_048_576
    }
}
