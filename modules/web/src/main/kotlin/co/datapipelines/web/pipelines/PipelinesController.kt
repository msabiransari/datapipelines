package co.datapipelines.web.pipelines

import co.datapipelines.application.lens.PromoterLens
import co.datapipelines.auth.AuditEventSink
import co.datapipelines.auth.RequiredScope
import co.datapipelines.auth.ScopeMatrix
import co.datapipelines.pipeline.CheckRunVia
import co.datapipelines.pipeline.PipelineRecord
import co.datapipelines.pipeline.PipelineReleaseService
import co.datapipelines.pipeline.PipelineService
import co.datapipelines.pipeline.ReadLens
import co.datapipelines.web.api.ApiErrors
import co.datapipelines.web.api.ApiResponse
import co.datapipelines.web.api.CorrelationId
import co.datapipelines.web.api.PagedData
import co.datapipelines.web.api.Pagination
import co.datapipelines.web.api.currentPrincipal
import co.datapipelines.web.api.writeSurface
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
 * `If-Match` header (§4.2). It never appends a released version. `POST` lands v1 as a **DRAFT**
 * with `current_version` null (D55): creation is authoring, and DRAFT → RELEASED is a human step
 * without exception. It is executable immediately all the same — `execute` with no version runs
 * the working version.
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
    private val audit: AuditEventSink,
    private val checkRunner: co.datapipelines.application.checks.PipelineCheckRunner,
    private val checkRuns: co.datapipelines.application.checks.PipelineCheckRunRepository,
    /** 178 — the promoter lens: every read below passes the caller's view, never `Everything`. */
    private val lens: PromoterLens,
) {
    /**
     * §5.1 — create; the server assigns id, version 1 (**DRAFT**, D55), owner and timestamps.
     * `current_version` comes back null and the `draft` pointer is set: releasing it is a human
     * action (`POST /pipelines/{id}/release`, the editor's Release button).
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiredScope(ScopeMatrix.RestOperation.MUTATE_PIPELINES_TEMPLATES)
    fun create(
        @RequestBody body: String,
    ): ApiResponse<JsonNode> {
        val principal = currentPrincipal()
        val saved = pipelines.create(principal.requireWorkspace().id, body, principal.userId, principal.writeSurface())
        return ApiResponse.of(PipelineResponses.full(saved.record, saved.bodyJson, saved.version, saved.draft))
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
        val principal = currentPrincipal()
        val workspaceId = principal.requireWorkspace().id
        val loaded =
            pipelines.findWorking(workspaceId, lens.viewFor(principal).pipelines, id) ?: throw ApiErrors.pipelineNotFound(id.toString())
        return ApiResponse.of(PipelineResponses.full(loaded.record, loaded.bodyJson, loaded.version, loaded.draft))
    }

    /**
     * §5.16 (140) — run the version's release checks NOW (`via = rest`), against their own
     * datasources, bound with the pipeline's declared parameters (defaults filled the execute
     * way; an optional `{"parameters": {…}}` body overrides them for this run — the calculator
     * context is not available to a check). One `pipeline_check_runs` row per check is the
     * run's own record; `observed` exists only because the server's run produced it.
     */
    @PostMapping("/{id}/versions/{version}/checks/run")
    @RequiredScope(ScopeMatrix.RestOperation.EXECUTE_PIPELINE)
    fun runChecks(
        @PathVariable id: UUID,
        @PathVariable version: Int,
        @RequestBody(required = false) body: JsonNode?,
    ): ApiResponse<JsonNode> {
        val principal = currentPrincipal()
        val workspaceId = principal.requireWorkspace().id
        val parameters =
            body
                ?.get("parameters")
                ?.takeIf { it.isObject }
                ?.properties()
                ?.associate { it.key to it.value }
                ?: emptyMap()
        val outcomes =
            checkRunner.run(
                workspaceId,
                id,
                version,
                parameters,
                CheckRunVia.REST,
                principal.userId,
                CorrelationId.current(),
            ) ?: throw ApiErrors.pipelineNotFound(id.toString())
        return ApiResponse.of(PipelineResponses.checkRuns(version, outcomes))
    }

    /**
     * §5.16 (140) — the version's check definitions, each with its LATEST run (latest per
     * `(version, check_id)` of the append-only `pipeline_check_runs`), `latest_run: null`
     * when never run. The release dialog and the version page read exactly this.
     */
    @GetMapping("/{id}/versions/{version}/checks")
    @RequiredScope(ScopeMatrix.RestOperation.READ_RESOURCES)
    fun checks(
        @PathVariable id: UUID,
        @PathVariable version: Int,
    ): ApiResponse<JsonNode> {
        val principal = currentPrincipal()
        val workspaceId = principal.requireWorkspace().id
        val record =
            pipelines.findRecord(workspaceId, lens.viewFor(principal).pipelines, id) ?: throw ApiErrors.pipelineNotFound(id.toString())
        val executable = pipelines.findExecutable(workspaceId, record, version) ?: throw ApiErrors.pipelineNotFound(id.toString())
        return ApiResponse.of(
            PipelineResponses.checksLatest(version, executable.pipeline.checks, checkRuns.latestPerCheck(id, version)),
        )
    }

    /** §5.3 — a specific version. */
    @GetMapping("/{id}/versions/{version}")
    @RequiredScope(ScopeMatrix.RestOperation.READ_RESOURCES)
    fun getVersion(
        @PathVariable id: UUID,
        @PathVariable version: Int,
    ): ApiResponse<JsonNode> {
        val principal = currentPrincipal()
        val workspaceId = principal.requireWorkspace().id
        val record =
            pipelines.findRecord(workspaceId, lens.viewFor(principal).pipelines, id) ?: throw ApiErrors.pipelineNotFound(id.toString())
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
        val principal = currentPrincipal()
        val workspaceId = principal.requireWorkspace().id
        val view = lens.viewFor(principal).pipelines
        pipelines.findRecord(workspaceId, view, id) ?: throw ApiErrors.pipelineNotFound(id.toString())
        return ApiResponse.of(pipelines.listVersions(workspaceId, view, id).map(PipelineResponses::versionSummary))
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
                via = principal.writeSurface(),
            )
        return ApiResponse.of(PipelineResponses.full(saved.record, saved.bodyJson, saved.version, saved.draft))
    }

    /**
     * §5.10 — release (lock) the draft: `pipeline.version.not_draft` when none exists,
     * §12 re-validation on the draft body, `pipeline.release.template_not_released` when a
     * pinned template version is still a draft, `pipeline.version.conflict` on a stale hash,
     * and — for a body carrying `checks[]` — `pipeline.check.failed` when the gate's fresh
     * run did not pass, unless [overrideChecksReason] (≥ 10 characters) rides the request
     * (140; audited on the release event). `release_pinned_templates=true` (142) is the
     * consent to release the DRAFT template versions the body pins in the same transaction,
     * templates first — each audited as `template.version.released` with the cascade source,
     * the pipeline's event listing them; the refusal's `details.pins_not_released` is what a
     * client reads to decide whether to retry with it. Same query carrier as the override
     * flag, for the same reason: the endpoint has no body.
     * UI-driven in practice (D4: agents never release); no MCP tool is exposed.
     */
    @PostMapping("/{id}/release")
    @RequiredScope(ScopeMatrix.RestOperation.RELEASE_VERSION)
    fun release(
        @PathVariable id: UUID,
        @RequestHeader(value = IfMatchHeader.NAME, required = false) ifMatch: String?,
        @RequestParam(value = "override_checks_reason", required = false) overrideChecksReason: String? = null,
        @RequestParam(value = "release_pinned_templates", required = false, defaultValue = "false") releasePinnedTemplates: Boolean = false,
    ): ApiResponse<JsonNode> {
        val principal = currentPrincipal()
        val workspaceId = principal.requireWorkspace().id
        val released =
            pipelines.release(
                workspaceId,
                id,
                IfMatchHeader.required(ifMatch),
                principal.userId,
                overrideChecksReason,
                releasePinnedTemplates,
            )
        LifecycleVerbs.auditRelease(audit, principal, workspaceId, id, released)
        return ApiResponse.of(PipelineResponses.full(released.record, released.bodyJson, released.version))
    }

    /**
     * §5.11 (101) — purge the draft: the row **and its executions** are deleted; no tombstone.
     * Hash-guarded; the sole-draft case takes the entity with it. The route keeps its
     * historical spelling (`draft/discard`) for the editor's button — the verb underneath is
     * the purge (versioning §5.4).
     */
    @PostMapping("/{id}/draft/discard")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiredScope(ScopeMatrix.RestOperation.MUTATE_PIPELINES_TEMPLATES)
    fun discard(
        @PathVariable id: UUID,
        @RequestHeader(value = IfMatchHeader.NAME, required = false) ifMatch: String?,
    ) {
        val principal = LifecycleVerbs.requireSession()
        val workspaceId = principal.requireWorkspace().id
        val purged = pipelines.purge(workspaceId, id, IfMatchHeader.required(ifMatch))
        LifecycleVerbs.audit(
            audit,
            LifecycleVerbs.AUDIT_VERSION_PURGED,
            principal,
            workspaceId,
            mapOf(
                "pipeline_id" to id.toString(),
                "executions_deleted" to purged.executionsDeleted,
                "scope" to if (purged is PipelineReleaseService.Purged.Entity) "entity" else "version",
            ),
        )
    }

    /**
     * §7 (101) — discard RELEASED version v: flip to DISCARDED (reversible via restore),
     * pointer per D60. Session-only, audited.
     */
    @PostMapping("/{id}/versions/{version}/discard")
    @RequiredScope(ScopeMatrix.RestOperation.MUTATE_PIPELINES_TEMPLATES)
    fun discardVersion(
        @PathVariable id: UUID,
        @PathVariable version: Int,
    ): ApiResponse<JsonNode> {
        val principal = LifecycleVerbs.requireSession()
        val workspaceId = principal.requireWorkspace().id
        val result = pipelines.discardVersion(workspaceId, id, version, principal.userId)
        LifecycleVerbs.audit(
            audit,
            LifecycleVerbs.AUDIT_VERSION_DISCARDED,
            principal,
            workspaceId,
            mapOf(
                "pipeline_id" to id.toString(),
                "version" to version,
                "current_version_before" to result.recordBefore.currentVersion,
                "current_version_after" to result.recordAfter.currentVersion,
            ),
        )
        return ApiResponse.of(PipelineResponses.full(result.recordAfter, bodyFor(workspaceId, id, result.recordAfter), result.version))
    }

    /** §7 (101) — restore DISCARDED version v to RELEASED; pointer moves only above-current-or-NULL. */
    @PostMapping("/{id}/versions/{version}/restore")
    @RequiredScope(ScopeMatrix.RestOperation.MUTATE_PIPELINES_TEMPLATES)
    fun restoreVersion(
        @PathVariable id: UUID,
        @PathVariable version: Int,
    ): ApiResponse<JsonNode> {
        val principal = LifecycleVerbs.requireSession()
        val workspaceId = principal.requireWorkspace().id
        val record = pipelines.restoreVersion(workspaceId, id, version)
        LifecycleVerbs.audit(
            audit,
            LifecycleVerbs.AUDIT_VERSION_RESTORED,
            principal,
            workspaceId,
            mapOf(
                "pipeline_id" to id.toString(),
                "version" to version,
                "current_version_after" to record.currentVersion,
            ),
        )
        return ApiResponse.of(PipelineResponses.full(record, bodyFor(workspaceId, id, record)))
    }

    /**
     * §7 (101) — purge DRAFT version v (drafts only): the row and its executions go; the
     * sole-draft case takes the entity. Irreversible; session-only, audited.
     */
    @DeleteMapping("/{id}/versions/{version}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiredScope(ScopeMatrix.RestOperation.MUTATE_PIPELINES_TEMPLATES)
    fun purgeVersion(
        @PathVariable id: UUID,
        @PathVariable version: Int,
    ) {
        val principal = LifecycleVerbs.requireSession()
        val workspaceId = principal.requireWorkspace().id
        val purged = pipelines.purgeVersion(workspaceId, id, version)
        LifecycleVerbs.audit(
            audit,
            LifecycleVerbs.AUDIT_VERSION_PURGED,
            principal,
            workspaceId,
            mapOf(
                "pipeline_id" to id.toString(),
                "version" to version,
                "executions_deleted" to purged.executionsDeleted,
                "scope" to if (purged is PipelineReleaseService.Purged.Entity) "entity" else "version",
            ),
        )
    }

    /**
     * §7 (101) — the entity purge (replaces the V1 soft delete, retired in V19): allowed only
     * when the only version is a DRAFT. `include_exclusive_draft_templates=true` purges the
     * draft-only templates this pipeline exclusively pins; the response carries the offered
     * set either way, so 102's dialog can show it. Session-only, audited.
     */
    @DeleteMapping("/{id}")
    @RequiredScope(ScopeMatrix.RestOperation.MUTATE_PIPELINES_TEMPLATES)
    fun delete(
        @PathVariable id: UUID,
        @RequestParam("include_exclusive_draft_templates", required = false, defaultValue = "false")
        includeExclusiveDraftTemplates: Boolean = false,
    ): ApiResponse<Map<String, Any?>> {
        val principal = LifecycleVerbs.requireSession()
        val workspaceId = principal.requireWorkspace().id
        val result = pipelines.purgeEntity(workspaceId, id, includeExclusiveDraftTemplates)
        LifecycleVerbs.audit(
            audit,
            LifecycleVerbs.AUDIT_ENTITY_PURGED,
            principal,
            workspaceId,
            mapOf(
                "pipeline_id" to id.toString(),
                "executions_deleted" to result.executionsDeleted,
                "exclusive_draft_templates" to result.exclusiveDraftTemplates,
                "exclusive_templates_purged" to result.exclusiveTemplatesPurged,
            ),
        )
        return ApiResponse.of(
            mapOf(
                "id" to id.toString(),
                "purged" to true,
                "exclusive_draft_templates" to result.exclusiveDraftTemplates,
                "exclusive_draft_templates_purged" to result.exclusiveTemplatesPurged,
            ),
        )
    }

    /**
     * §7 (101) — the manual switch: `current = {version}`, which must be live and
     * posture-eligible. The promotion receiver's rollout/rollback lever; session-only,
     * audited.
     */
    @PostMapping("/{id}/current")
    @RequiredScope(ScopeMatrix.RestOperation.SWITCH_SERVED_VERSION)
    fun switchCurrent(
        @PathVariable id: UUID,
        @RequestBody body: JsonNode,
    ): ApiResponse<JsonNode> {
        val principal = LifecycleVerbs.requireSession()
        val workspaceId = principal.requireWorkspace().id
        if (!body.has("version") || !body["version"].canConvertToInt()) {
            throw ApiErrors.pipelineNotFound(id.toString())
        }
        val target = body["version"].asInt()
        val before =
            pipelines.findRecord(workspaceId, lens.viewFor(principal).pipelines, id) ?: throw ApiErrors.pipelineNotFound(id.toString())
        val record = pipelines.switchCurrent(workspaceId, id, target)
        LifecycleVerbs.audit(
            audit,
            LifecycleVerbs.AUDIT_CURRENT_SWITCHED,
            principal,
            workspaceId,
            mapOf(
                "pipeline_id" to id.toString(),
                "from" to before.currentVersion,
                "to" to record.currentVersion,
            ),
        )
        return ApiResponse.of(PipelineResponses.full(record, bodyFor(workspaceId, id, record)))
    }

    /** The pointer-named version's body for a full response; an empty object when the pointer is NULL. */
    private fun bodyFor(
        workspaceId: UUID,
        id: UUID,
        record: PipelineRecord,
    ): String =
        record.currentVersion
            // The write just resolved the record through the caller's view; the pointer's body
            // is a derived read of that same record (an author's, by the matrix).
            ?.let { pipelines.findVersionBody(workspaceId, ReadLens.Everything, id, it) }
            ?: "{}"

    /**
     * §5.7 — the listing, with the `owner` / `datasource` / `q` filters.
     *
     * The filter itself is [PipelineService.list] (D2, shared with `pipelines_list`); what stays
     * here is the offset/limit pagination contract, which the two surfaces genuinely differ on.
     */
    @GetMapping(params = ["!prefix"])
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
        val principal = currentPrincipal()
        val workspaceId = principal.requireWorkspace().id
        val filtered =
            pipelines.list(
                workspaceId,
                lens.viewFor(principal).pipelines,
                ownerId = owner,
                datasourceName = datasource,
                query = q,
            )
        val shown = filtered.drop(page).take(size)
        // One batched draft lookup for the page, so each row can state its working version (D55).
        val drafts = pipelines.findDrafts(workspaceId, shown.map { it.id })
        val items = shown.map { PipelineResponses.listEntry(it, drafts[it.id]) }
        val pagination = Pagination.of(page, size, filtered.size.toLong(), items.size)
        return ApiResponse.of(PagedData(items, pagination))
    }

    /**
     * §5.7 — ONE level of the pipeline tree, the REST mirror of `pipelines_list {prefix}`
     * (067): [prefix]'s direct sub-folders with their subtree counts and its direct pipeline
     * leaves, never a subtree. Chosen by the PRESENCE of `prefix`: `?prefix=` (empty) is the
     * ROOT — a different request from an absent `prefix` (the flat listing above). The
     * semantics are [PipelineService.browseLevel]'s, shared with the MCP tool: an unknown or
     * illegal prefix answers an ordinary EMPTY level with 200, never a 400 and never a query
     * error. `owner`/`datasource`/`q` do not apply here — browse and search are different
     * presentations (template-hierarchy-design §9.2).
     */
    @GetMapping(params = ["prefix"])
    @RequiredScope(ScopeMatrix.RestOperation.READ_RESOURCES)
    fun browse(
        @RequestParam prefix: String,
        @RequestParam(required = false) offset: Int?,
        @RequestParam(required = false) limit: Int?,
    ): ApiResponse<Map<String, Any?>> {
        val principal = currentPrincipal()
        val workspaceId = principal.requireWorkspace().id
        val level =
            pipelines.browseLevel(
                workspaceId,
                lens.viewFor(principal).pipelines,
                prefix,
                Pagination.clampOffset(offset),
                Pagination.clampLimit(limit),
            )
        return ApiResponse.of(
            mapOf(
                "prefix" to prefix,
                "folders" to level.folders.map { mapOf("path" to it.path, "segment" to it.segment, "pipeline_count" to it.pipelineCount) },
                "pipelines" to
                    pipelines.findDrafts(workspaceId, level.pipelines.map { it.id }).let { drafts ->
                        level.pipelines.map { PipelineResponses.listEntry(it, drafts[it.id]) }
                    },
                "total" to level.total,
                "has_more" to level.hasMore,
            ),
        )
    }
}
