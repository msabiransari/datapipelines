package co.datapipelines.web.visualizations

import co.datapipelines.application.lens.PromoterLens
import co.datapipelines.auth.AuditEventSink
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.Permission
import co.datapipelines.auth.RequiredScope
import co.datapipelines.visualization.VisualizationReader
import co.datapipelines.visualization.VisualizationService
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
 * The visualization lifecycle routes (rest-api.md §22; the implementation spec's §6.1) — the parameter-set mould
 * (`ParameterSetsController`), addressed by id (P24). Every refusal is a §13.22 `visualization.*` code the
 * [co.datapipelines.web.api.ApiErrorCatalog] maps.
 *
 * ## Thin, and lensed
 * The rules are [VisualizationService]'s — the verb table, the working-version rule, the pin guard, the release
 * gate. What is left is REST: the `If-Match` parse, the body read, the pagination, the envelope. Every read passes
 * the caller's lens (`lens.viewFor(principal).visualizations`, never `Everything`) through the service's lensed
 * reads — no route reads a repository (the L1a security pass's O3): a promoter's hidden visualization is the
 * family's 404, never a hint.
 *
 * ## Bodies
 * Read through the request copy of the module's strict mapper (#291 — a malformed body is the family's 400, not a
 * 500) and bound by [VisualizationReader], which applies the seven `datapipelines.visualization.*` bounds before it
 * walks a member; the platform's 2 MiB request cap stands ahead of both.
 *
 * ## Not here
 * Export/import (L1c), the test sessions and runs (L4: `VisualizationTestsController`, 352/353). A release is
 * judged by the installed evidence gate (`VisualizationReleaseEvidence`, the spec's §11.4): no run for the
 * candidate → `release.tests_missing`, a stale or moved-on run → `tests_stale`, a RED or INCOMPLETE run →
 * `tests_red`, a mechanical check that stopped passing → `mechanical_failed` — by design.
 */
@RestController
@RequestMapping("/api/v1/visualizations")
@Suppress("TooManyFunctions") // one handler per §22 route, the ParameterSetsController shape
class VisualizationsController(
    private val visualizations: VisualizationService,
    private val reader: VisualizationReader,
    /** The promoter lens: every read below passes the caller's view, never `Everything`. */
    private val lens: PromoterLens,
    /** #332 — every lifecycle verb and the release audit, the pipelines mould (enums.md §15). */
    private val audit: AuditEventSink,
) {
    /** §22 — create; the server assigns the id (P24) and lands version 1 DRAFT (D55). */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiredScope(Permission.VISUALIZATION_CREATE)
    fun create(
        @RequestBody body: String,
    ): ApiResponse<JsonNode> {
        val principal = currentPrincipal()
        val document = reader.readOrThrow(ArtifactHttp.readTree(FAMILY, body))
        val saved = visualizations.create(principal.requireWorkspace().id, document, principal.userId, principal.writeSurface())
        return ApiResponse.of(ArtifactResponses.full(saved, ArtifactResponses.draftOf(saved)))
    }

    /** §22 — the WORKING version's full JSON: the draft when one exists, else the current release (versioning §7.1). */
    @GetMapping("/{id}")
    @RequiredScope(Permission.VISUALIZATION_READ)
    fun get(
        @PathVariable id: UUID,
    ): ApiResponse<JsonNode> {
        val principal = currentPrincipal()
        val view = lens.viewFor(principal).visualizations
        val loaded = visualizations.findWorking(principal.requireWorkspace().id, view, id) ?: throw FAMILY.notFound(id.toString())
        // A narrowing lens never sees a draft (the service returns RELEASED only); the pointer is a whole-view field.
        return ApiResponse.of(ArtifactResponses.full(loaded, if (view.isEverything) ArtifactResponses.draftOf(loaded) else null))
    }

    /** §22 — one version; a version other than RELEASED is not-found under a narrowing lens. */
    @GetMapping("/{id}/versions/{version}")
    @RequiredScope(Permission.VISUALIZATION_READ)
    fun getVersion(
        @PathVariable id: UUID,
        @PathVariable version: Int,
    ): ApiResponse<JsonNode> {
        val principal = currentPrincipal()
        val view = lens.viewFor(principal).visualizations
        val loaded =
            visualizations.findVersion(principal.requireWorkspace().id, view, id, version)
                ?: throw FAMILY.notFound(id.toString(), version)
        return ApiResponse.of(ArtifactResponses.full(loaded))
    }

    /** §22 — version metadata, newest first; no bodies. An artifact always holds a version, so none is a 404. */
    @GetMapping("/{id}/versions")
    @RequiredScope(Permission.VISUALIZATION_READ)
    fun versions(
        @PathVariable id: UUID,
    ): ApiResponse<List<Map<String, Any?>>> {
        val principal = currentPrincipal()
        val listed = visualizations.listVersions(principal.requireWorkspace().id, lens.viewFor(principal).visualizations, id)
        if (listed.isEmpty()) throw FAMILY.notFound(id.toString())
        return ApiResponse.of(listed.map(ArtifactResponses::versionSummary))
    }

    /** §22 — the draft write (versioning §5.1/§5.2); `If-Match` carries the hash precondition, checked before the parse. */
    @PutMapping("/{id}")
    @RequiredScope(Permission.VISUALIZATION_UPDATE)
    fun update(
        @PathVariable id: UUID,
        @RequestHeader(value = IfMatchHeader.NAME, required = false) ifMatch: String?,
        @RequestBody body: String,
    ): ApiResponse<JsonNode> {
        val expectedHash = IfMatchHeader.required(ifMatch)
        val principal = currentPrincipal()
        val document = reader.readOrThrow(ArtifactHttp.readTree(FAMILY, body))
        val written =
            visualizations.write(
                workspaceId = principal.requireWorkspace().id,
                id = id,
                document = document,
                expectedHash = expectedHash,
                actor = principal.userId,
                via = principal.writeSurface(),
            )
        return ApiResponse.of(ArtifactResponses.full(written, ArtifactResponses.draftOf(written)))
    }

    /**
     * §22 — release the draft at `If-Match` (D56, D61): a test case, the pin as it is NOW, the transform pin RELEASED
     * or — with `release_pinned_templates=true` — released WITH it, then the evidence gate (the spec's §11.4 —
     * `VisualizationReleaseEvidence` judges the latest run for THIS draft: `tests_missing`, `tests_stale`,
     * `tests_red` or `mechanical_failed`, else PASS), all in one transaction.
     */
    @PostMapping("/{id}/release")
    @RequiredScope(Permission.VISUALIZATION_RELEASE)
    fun release(
        @PathVariable id: UUID,
        @RequestHeader(value = IfMatchHeader.NAME, required = false) ifMatch: String?,
        @RequestParam(value = "release_pinned_templates", required = false, defaultValue = "false") releasePinnedTemplates: Boolean = false,
    ): ApiResponse<JsonNode> {
        val principal = currentPrincipal()
        val workspaceId = principal.requireWorkspace().id
        val released =
            visualizations.release(
                workspaceId,
                id,
                IfMatchHeader.required(ifMatch),
                principal.userId,
                releasePinnedTemplates = releasePinnedTemplates,
            )
        // #332 — the release audit, the auditRelease twin: each cascaded template's event first, then
        // the visualization's own naming them — after the one transaction committed, never on a refusal.
        val cascade = LifecycleVerbs.FamilyCascade("visualization_id", id, released.version.detail.version)
        released.templatesReleased.forEach { template ->
            LifecycleVerbs.audit(
                audit,
                LifecycleVerbs.TEMPLATE_AUDIT_VERSION_RELEASED,
                principal,
                workspaceId,
                LifecycleVerbs.cascadedReleaseDetails(principal, "template_id", template.id, template.version, cascade),
            )
        }
        LifecycleVerbs.audit(
            audit,
            LifecycleVerbs.VISUALIZATION_EVENTS.versionReleased,
            principal,
            workspaceId,
            LifecycleVerbs.familyReleaseDetails(
                principal,
                LifecycleVerbs.FamilyIdentity(
                    "visualization_id",
                    "visualization_name",
                    id,
                    released.version.record.name,
                    released.version.detail.version,
                ),
                "templates_released",
                released.templatesReleased.map { mapOf("template_id" to it.id, "version" to it.version) },
            ),
        )
        val data = ArtifactResponses.full(released.version) as com.fasterxml.jackson.databind.node.ObjectNode
        data.putArray("templates_released").also { array ->
            released.templatesReleased.forEach { array.addObject().put("template_id", it.id).put("version", it.version) }
        }
        return ApiResponse.of(data)
    }

    /** §22 — purge the draft (versioning §5.4) at `If-Match`; the sole draft takes the visualization. Session-only. */
    @PostMapping("/{id}/draft/discard")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiredScope(Permission.VISUALIZATION_VERSION_MANAGE)
    fun purgeDraft(
        @PathVariable id: UUID,
        @RequestHeader(value = IfMatchHeader.NAME, required = false) ifMatch: String?,
    ) {
        val principal = LifecycleVerbs.requireSession()
        val workspaceId = principal.requireWorkspace().id
        // The name and version for the audit row, read through the caller's lens BEFORE the purge —
        // a sole-draft purge takes the visualization, so afterwards there is nothing to read (#332).
        val audited = workingForAudit(principal, workspaceId, id)
        visualizations.purgeDraft(workspaceId, id, IfMatchHeader.required(ifMatch))
        LifecycleVerbs.audit(
            audit,
            LifecycleVerbs.VISUALIZATION_EVENTS.versionPurged,
            principal,
            workspaceId,
            auditedDetails(id, audited),
        )
    }

    /** §22 — discard RELEASED version v (reversible via restore); never a version a live dashboard pins. Session-only. */
    @PostMapping("/{id}/versions/{version}/discard")
    @RequiredScope(Permission.VISUALIZATION_VERSION_MANAGE)
    fun discardVersion(
        @PathVariable id: UUID,
        @PathVariable version: Int,
    ): ApiResponse<Map<String, Any?>> {
        val principal = LifecycleVerbs.requireSession()
        val workspaceId = principal.requireWorkspace().id
        val audited = versionForAudit(principal, workspaceId, id, version)
        val detail = visualizations.discardVersion(workspaceId, id, version, principal.userId)
        LifecycleVerbs.audit(
            audit,
            LifecycleVerbs.VISUALIZATION_EVENTS.versionDiscarded,
            principal,
            workspaceId,
            auditedDetails(id, audited, version),
        )
        return ApiResponse.of(ArtifactResponses.lifecycleSummary(detail))
    }

    /** §22 — restore DISCARDED version v; the pointer moves only above-current-or-NULL (D60). Session-only. */
    @PostMapping("/{id}/versions/{version}/restore")
    @RequiredScope(Permission.VISUALIZATION_VERSION_MANAGE)
    fun restoreVersion(
        @PathVariable id: UUID,
        @PathVariable version: Int,
    ): ApiResponse<Map<String, Any?>> {
        val principal = LifecycleVerbs.requireSession()
        val workspaceId = principal.requireWorkspace().id
        val audited = versionForAudit(principal, workspaceId, id, version)
        val detail = visualizations.restoreVersion(workspaceId, id, version)
        LifecycleVerbs.audit(
            audit,
            LifecycleVerbs.VISUALIZATION_EVENTS.versionRestored,
            principal,
            workspaceId,
            auditedDetails(id, audited, version),
        )
        return ApiResponse.of(ArtifactResponses.lifecycleSummary(detail))
    }

    /** §22 — purge DRAFT version v (drafts only; never a pinned one). Session-only; irreversible. */
    @DeleteMapping("/{id}/versions/{version}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiredScope(Permission.VISUALIZATION_VERSION_MANAGE)
    fun purgeVersion(
        @PathVariable id: UUID,
        @PathVariable version: Int,
    ) {
        val principal = LifecycleVerbs.requireSession()
        val workspaceId = principal.requireWorkspace().id
        val audited = versionForAudit(principal, workspaceId, id, version)
        visualizations.purgeVersion(workspaceId, id, version)
        LifecycleVerbs.audit(
            audit,
            LifecycleVerbs.VISUALIZATION_EVENTS.versionPurged,
            principal,
            workspaceId,
            auditedDetails(id, audited, version),
        )
    }

    /** §22 — the manual switch (D60, the receiver's lever); the body's shape is judged before the lookup. Session-only. */
    @PostMapping("/{id}/current")
    @RequiredScope(Permission.VISUALIZATION_SWITCH_VERSION)
    fun switchCurrent(
        @PathVariable id: UUID,
        @RequestBody body: String,
    ): ApiResponse<Map<String, Any?>> {
        val principal = LifecycleVerbs.requireSession()
        val workspaceId = principal.requireWorkspace().id
        val target = ArtifactHttp.switchTarget(FAMILY, body)
        val current = visualizations.switchCurrent(workspaceId, id, target)
        LifecycleVerbs.audit(
            audit,
            LifecycleVerbs.VISUALIZATION_EVENTS.currentSwitched,
            principal,
            workspaceId,
            mapOf(
                "visualization_id" to id.toString(),
                "visualization_name" to
                    visualizations
                        .findVersion(workspaceId, lens.viewFor(principal).visualizations, id, current)
                        ?.record
                        ?.name,
                "to" to current,
            ),
        )
        return ApiResponse.of(mapOf("id" to id.toString(), "current_version" to current))
    }

    /** §22 — the entity purge, only when the only version is a DRAFT and no live dashboard pins any version. Session-only. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiredScope(Permission.VISUALIZATION_DELETE)
    fun delete(
        @PathVariable id: UUID,
    ) {
        val principal = LifecycleVerbs.requireSession()
        val workspaceId = principal.requireWorkspace().id
        val audited = workingForAudit(principal, workspaceId, id)
        visualizations.purgeEntity(workspaceId, id)
        LifecycleVerbs.audit(
            audit,
            LifecycleVerbs.VISUALIZATION_EVENTS.entityPurged,
            principal,
            workspaceId,
            auditedDetails(id, audited),
        )
    }

    /** §22 — ONE level of the tree, the `?prefix=` browse (a multi-segment name never travels in a path — P24). */
    @GetMapping(params = ["prefix"])
    @RequiredScope(Permission.VISUALIZATION_READ)
    fun browse(
        @RequestParam prefix: String,
        @RequestParam(required = false) offset: Int?,
        @RequestParam(required = false) limit: Int?,
    ): ApiResponse<Map<String, Any?>> {
        val page = Pagination.clampOffset(offset)
        val size = Pagination.clampLimit(limit)
        val principal = currentPrincipal()
        val workspaceId = principal.requireWorkspace().id
        val view = lens.viewFor(principal).visualizations
        val folders = visualizations.listChildFolders(workspaceId, view, prefix)
        val loaded = visualizations.listChildren(workspaceId, view, prefix, page, size)
        // `total` counts the level's VISUALIZATIONS the lens admits, never the folders — lens-true (#300).
        val total = visualizations.countChildren(workspaceId, view, prefix)
        return ApiResponse.of(
            mapOf(
                "prefix" to prefix,
                "folders" to folders.map { ArtifactResponses.folder(FAMILY, it) },
                FAMILY.listKey to loaded.map { ArtifactResponses.listEntry(it) },
                "total" to total,
                "has_more" to (page + loaded.size < total),
            ),
        )
    }

    /** §22 — the FLAT listing (#312's shape): every visualization the caller's lens admits, paged, with a lens-true total. */
    @GetMapping(params = ["!prefix"])
    @RequiredScope(Permission.VISUALIZATION_READ)
    fun list(
        @RequestParam(required = false) offset: Int?,
        @RequestParam(required = false) limit: Int?,
    ): ApiResponse<PagedData<Map<String, Any?>>> {
        val page = Pagination.clampOffset(offset)
        val size = Pagination.clampLimit(limit)
        val principal = currentPrincipal()
        val workspaceId = principal.requireWorkspace().id
        val view = lens.viewFor(principal).visualizations
        val items = visualizations.listAll(workspaceId, view, page, size).map { ArtifactResponses.listEntry(it) }
        val total = visualizations.countAll(workspaceId, view)
        return ApiResponse.of(PagedData(items, Pagination.of(page, size, total.toLong(), items.size)))
    }

    // ---- the audit rows' pre-reads (#332) ----------------------------------------------------------

    /** The working version's (name, number) for an audit row — read through the caller's lens, before the verb. */
    private fun workingForAudit(
        principal: AuthenticatedPrincipal,
        workspaceId: UUID,
        id: UUID,
    ): Pair<String, Int>? =
        visualizations.findWorking(workspaceId, lens.viewFor(principal).visualizations, id)?.let { it.record.name to it.detail.version }

    /** A named version's (name, number) — the same lens rule. */
    private fun versionForAudit(
        principal: AuthenticatedPrincipal,
        workspaceId: UUID,
        id: UUID,
        version: Int,
    ): Pair<String, Int>? =
        visualizations
            .findVersion(workspaceId, lens.viewFor(principal).visualizations, id, version)
            ?.let { it.record.name to it.detail.version }

    /** The purge row's details: the id, plus the pre-read name and version when the artifact still existed. */
    private fun auditedDetails(
        id: UUID,
        audited: Pair<String, Int>?,
        version: Int? = null,
    ): Map<String, Any?> =
        buildMap {
            put("visualization_id", id.toString())
            put("visualization_name", audited?.first)
            put("version", version ?: audited?.second)
        }

    private companion object {
        val FAMILY = ArtifactFamily.VISUALIZATION
    }
}
