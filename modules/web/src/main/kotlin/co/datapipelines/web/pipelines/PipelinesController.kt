package co.datapipelines.web.pipelines

import co.datapipelines.auth.RequiredScope
import co.datapipelines.auth.ScopeMatrix
import co.datapipelines.pipeline.PipelineService
import co.datapipelines.web.api.ApiErrors
import co.datapipelines.web.api.ApiResponse
import co.datapipelines.web.api.PagedData
import co.datapipelines.web.api.Pagination
import co.datapipelines.web.api.currentPrincipal
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
 * The pipeline CRUD and versioning endpoints (rest-api.md §5; the draft/release lifecycle
 * per versioning.md §7).
 *
 * Every write runs the **universal save-time validation** (pipeline-contract §2.8): deserialize →
 * §12 validate → store. Nothing invalid reaches the database, and a validation failure surfaces as
 * `400` with the §13.1 code the validator chose ([PipelineValidationException][co.datapipelines.pipeline.PipelineValidationException]
 * carries the full failure list in `details.failures`).
 *
 * ## The write rule (versioning §3.2)
 *
 * `PUT` always writes the DRAFT branch — copy-on-write from the released version on the first
 * write (§5.1), in-place overwrite after (§5.2) — and carries the hash precondition in the
 * `If-Match` header (§4.2). It never appends a released version. `POST` still lands v1
 * RELEASED and immediately executable (creation is not modification).
 *
 * Bodies are accepted as raw JSON (`String`) and bound by the pipeline deserializer rather than by
 * a Spring DTO: the pipeline body is the frozen pipeline-contract shape, and its deserializer is
 * the single place the wire rules (unknown fields, `type`/`output` enums, schema version) live.
 *
 * ## Thin since 056
 *
 * Every rule this class used to hold — the deserialize→validate→canonical triple (D1), the
 * owner/datasource/`q` filter (D2), the draft-pointer branch of a no-op PUT — is
 * [PipelineService]'s, shared with the MCP tools that each had their own copy. What is left is
 * genuinely REST: the `If-Match` header parse, pagination, the §4.2 envelope, and turning an
 * absent row into [ApiErrors.pipelineNotFound]. A handler that reads more than that is a handler
 * that has started re-implementing the service.
 */
@RestController
@RequestMapping("/api/v1/pipelines")
class PipelinesController(
    private val pipelines: PipelineService,
) {
    /** §5.1 — create; the server assigns id, version 1 (RELEASED), owner and timestamps. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiredScope(ScopeMatrix.RestOperation.MUTATE_PIPELINES_TEMPLATES)
    fun create(
        @RequestBody body: String,
    ): ApiResponse<JsonNode> {
        val principal = currentPrincipal()
        val saved = pipelines.create(principal.requireWorkspace().id, body, principal.userId)
        return ApiResponse.of(PipelineResponses.full(saved.record, saved.bodyJson, saved.version))
    }

    /**
     * §5.2 — the **working version's** full JSON: the DRAFT when one exists, else the
     * current RELEASED version (versioning §7, since 039 — a RELEASED version is never
     * modified and a draft is always reused, so authoring reads must show the draft or an
     * editor rebases on released and quietly discards it). The response states which
     * `version` and `status` it returned; `current_version` still names the latest RELEASED
     * version (the execute-default pointer, unmoved), and the `draft` pointer is present
     * whenever one exists.
     */
    @GetMapping("/{id}")
    @RequiredScope(ScopeMatrix.RestOperation.READ_RESOURCES)
    fun get(
        @PathVariable id: UUID,
    ): ApiResponse<JsonNode> {
        val workspaceId = currentPrincipal().requireWorkspace().id
        val loaded = pipelines.findWorking(workspaceId, id) ?: throw ApiErrors.pipelineNotFound(id.toString())
        return ApiResponse.of(PipelineResponses.full(loaded.record, loaded.bodyJson, loaded.version, loaded.draft))
    }

    /** §5.3 — a specific version. */
    @GetMapping("/{id}/versions/{version}")
    @RequiredScope(ScopeMatrix.RestOperation.READ_RESOURCES)
    fun getVersion(
        @PathVariable id: UUID,
        @PathVariable version: Int,
    ): ApiResponse<JsonNode> {
        val workspaceId = currentPrincipal().requireWorkspace().id
        val record = pipelines.findRecord(workspaceId, id) ?: throw ApiErrors.pipelineNotFound(id.toString())
        val loaded =
            pipelines.findVersion(workspaceId, record, version)
                ?: throw ApiErrors.pipelineVersionNotFound(id.toString(), version)
        return ApiResponse.of(PipelineResponses.full(record, loaded.bodyJson, loaded.version))
    }

    /** §5.4 — version metadata, newest first; no bodies. */
    @GetMapping("/{id}/versions")
    @RequiredScope(ScopeMatrix.RestOperation.READ_RESOURCES)
    fun versions(
        @PathVariable id: UUID,
    ): ApiResponse<List<Map<String, Any?>>> {
        val workspaceId = currentPrincipal().requireWorkspace().id
        pipelines.findRecord(workspaceId, id) ?: throw ApiErrors.pipelineNotFound(id.toString())
        return ApiResponse.of(pipelines.listVersions(workspaceId, id).map(PipelineResponses::versionSummary))
    }

    /**
     * §5.5 — update, writing the DRAFT branch (versioning §5.1/§5.2): the first write after a
     * release copies the released version to a draft; later writes overwrite that draft in
     * place. Requires the `If-Match` hash precondition; the response carries the draft's
     * `version`, `status: "DRAFT"` and `body_hash`. A PUT whose body is identical to the
     * released one is a NO-OP (versioning §5.1): no draft is created and the response
     * reports the current state — `status: "RELEASED"`, no draft pointer.
     */
    @PutMapping("/{id}")
    @RequiredScope(ScopeMatrix.RestOperation.MUTATE_PIPELINES_TEMPLATES)
    fun update(
        @PathVariable id: UUID,
        @RequestHeader(value = IfMatchHeader.NAME, required = false) ifMatch: String?,
        @RequestBody body: String,
    ): ApiResponse<JsonNode> {
        // The precondition is checked BEFORE the body is parsed: a caller that did not
        // participate in the hash protocol at all should not burn validation first.
        val expectedHash = IfMatchHeader.required(ifMatch)
        val principal = currentPrincipal()
        val saved =
            pipelines.update(
                workspaceId = principal.requireWorkspace().id,
                pipelineId = id,
                bodyJson = body,
                expectedHash = expectedHash,
                actor = principal.userId,
            )
        return ApiResponse.of(PipelineResponses.full(saved.record, saved.bodyJson, saved.version, saved.draft))
    }

    /**
     * §5.10 — release (lock) the draft: `pipeline.version.not_draft` when none exists,
     * §12 re-validation on the draft body, `pipeline.release.template_not_released` when a
     * pinned template version is still a draft, `pipeline.version.conflict` on a stale hash.
     * UI-driven in practice (D4: agents never release); no MCP tool is exposed.
     */
    @PostMapping("/{id}/release")
    @RequiredScope(ScopeMatrix.RestOperation.MUTATE_PIPELINES_TEMPLATES)
    fun release(
        @PathVariable id: UUID,
        @RequestHeader(value = IfMatchHeader.NAME, required = false) ifMatch: String?,
    ): ApiResponse<JsonNode> {
        val principal = currentPrincipal()
        val workspaceId = principal.requireWorkspace().id
        val released = pipelines.release(workspaceId, id, IfMatchHeader.required(ifMatch), principal.userId)
        return ApiResponse.of(PipelineResponses.full(released.record, released.bodyJson, released.version))
    }

    /**
     * §5.11 — discard the draft: hard-delete when never executed, DISCARDED-flip when the
     * executions FK blocks the delete (both transparent to the caller). Hash-guarded.
     */
    @PostMapping("/{id}/draft/discard")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiredScope(ScopeMatrix.RestOperation.MUTATE_PIPELINES_TEMPLATES)
    fun discard(
        @PathVariable id: UUID,
        @RequestHeader(value = IfMatchHeader.NAME, required = false) ifMatch: String?,
    ) {
        val workspaceId = currentPrincipal().requireWorkspace().id
        pipelines.discard(workspaceId, id, IfMatchHeader.required(ifMatch))
    }

    /** §5.6 — soft delete. Historical executions remain queryable. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiredScope(ScopeMatrix.RestOperation.MUTATE_PIPELINES_TEMPLATES)
    fun delete(
        @PathVariable id: UUID,
    ) {
        val workspaceId = currentPrincipal().requireWorkspace().id
        if (!pipelines.delete(workspaceId, id)) throw ApiErrors.pipelineNotFound(id.toString())
    }

    /**
     * §5.7 — the listing, with the `owner` / `datasource` / `q` filters.
     *
     * The filter itself is [PipelineService.list] (D2, shared with `pipelines_list`); what stays
     * here is the offset/limit pagination contract, which the two surfaces genuinely differ on.
     */
    @GetMapping
    @RequiredScope(ScopeMatrix.RestOperation.READ_RESOURCES)
    fun list(
        @RequestParam(required = false) owner: UUID?,
        @RequestParam(required = false) datasource: String?,
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) offset: Int?,
        @RequestParam(required = false) limit: Int?,
    ): ApiResponse<PagedData<Map<String, Any?>>> {
        val page = Pagination.clampOffset(offset)
        val size = Pagination.clampLimit(limit)
        val workspaceId = currentPrincipal().requireWorkspace().id
        val filtered = pipelines.list(workspaceId, ownerId = owner, datasourceName = datasource, query = q)
        val items = filtered.drop(page).take(size).map(PipelineResponses::listEntry)
        val pagination = Pagination.of(page, size, filtered.size.toLong(), items.size)
        return ApiResponse.of(PagedData(items, pagination))
    }
}
