package co.datapipelines.web.dashboards

import co.datapipelines.application.lens.PromoterLens
import co.datapipelines.auth.AuditEventSink
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.Permission
import co.datapipelines.auth.RequiredScope
import co.datapipelines.visualization.ArtifactValidation
import co.datapipelines.visualization.DashboardDocument
import co.datapipelines.visualization.DashboardReader
import co.datapipelines.visualization.DashboardService
import co.datapipelines.web.api.ApiResponse
import co.datapipelines.web.api.PagedData
import co.datapipelines.web.api.Pagination
import co.datapipelines.web.api.currentPrincipal
import co.datapipelines.web.api.writeSurface
import co.datapipelines.web.pipelines.IfMatchHeader
import co.datapipelines.web.pipelines.LifecycleVerbs
import co.datapipelines.web.visualizations.ArtifactFamily
import co.datapipelines.web.visualizations.ArtifactHttp
import co.datapipelines.web.visualizations.ArtifactResponses
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
 * The dashboard lifecycle routes (rest-api.md §23; the implementation spec's §6.2) — `VisualizationsController`'s
 * twin, addressed by id (P24), plus `validate`. Every refusal is a §13.23 `dashboard.*` code the
 * [co.datapipelines.web.api.ApiErrorCatalog] maps.
 *
 * ## Thin, and lensed
 * The rules are [DashboardService]'s — the verb table, the working-version rule, the D61 release cascade. Every read
 * passes the caller's lens (`lens.viewFor(principal).dashboards` — RELEASED dashboards whose every source pipeline
 * the promoter's pipeline lens admits) through the service's lensed reads; no route reads a repository (O3).
 *
 * ## Validate is an AUTHOR verb (owner ruling 2026-09-29, the L1a security pass's O5)
 * `POST /{id}/validate` runs §3.2's rules against the dependencies' CURRENT state, and the validator's port reads
 * (a pipeline's release status, a set's, a visualization's) are NOT lensed — its refusals name the status of pins
 * the caller may not otherwise see. So it declares `dashboard.update`: a viewer and a promoter are refused by the
 * interceptor (`auth.role_required`) before this handler runs, instead of by a bespoke check here.
 *
 * ## Not here
 * Export/import (L1c), the runtime and refresh routes and `dashboard.execute` (L2), the key bindings (L5).
 */
@RestController
@RequestMapping("/api/v1/dashboards")
@Suppress("TooManyFunctions") // one handler per §23 route, the ParameterSetsController shape
class DashboardsController(
    private val dashboards: DashboardService,
    private val reader: DashboardReader,
    /** The promoter lens: every read below passes the caller's view, never `Everything`. */
    private val lens: PromoterLens,
    /** #332 — every lifecycle verb and the release audit, the pipelines mould (enums.md §15). */
    private val audit: AuditEventSink,
) {
    /** §23 — create; the server assigns the id (P24) and lands version 1 DRAFT (D55). */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiredScope(Permission.DASHBOARD_CREATE)
    fun create(
        @RequestBody body: String,
    ): ApiResponse<JsonNode> {
        val principal = currentPrincipal()
        val document = reader.readOrThrow(ArtifactHttp.readTree(FAMILY, body))
        val saved = dashboards.create(principal.requireWorkspace().id, document, principal.userId, principal.writeSurface())
        return ApiResponse.of(ArtifactResponses.full(saved, ArtifactResponses.draftOf(saved)))
    }

    /** §23 — the WORKING version's full JSON: the draft when one exists, else the current release (versioning §7.1). */
    @GetMapping("/{id}")
    @RequiredScope(Permission.DASHBOARD_READ)
    fun get(
        @PathVariable id: UUID,
    ): ApiResponse<JsonNode> {
        val principal = currentPrincipal()
        val view = lens.viewFor(principal).dashboards
        val loaded = dashboards.findWorking(principal.requireWorkspace().id, view, id) ?: throw FAMILY.notFound(id.toString())
        // A narrowing lens never sees a draft (the service returns RELEASED only); the pointer is a whole-view field.
        return ApiResponse.of(ArtifactResponses.full(loaded, if (view.isEverything) ArtifactResponses.draftOf(loaded) else null))
    }

    /** §23 — one version; a version other than RELEASED is not-found under a narrowing lens. */
    @GetMapping("/{id}/versions/{version}")
    @RequiredScope(Permission.DASHBOARD_READ)
    fun getVersion(
        @PathVariable id: UUID,
        @PathVariable version: Int,
    ): ApiResponse<JsonNode> {
        val principal = currentPrincipal()
        val view = lens.viewFor(principal).dashboards
        val loaded =
            dashboards.findVersion(principal.requireWorkspace().id, view, id, version)
                ?: throw FAMILY.notFound(id.toString(), version)
        return ApiResponse.of(ArtifactResponses.full(loaded))
    }

    /** §23 — version metadata, newest first; no bodies. An artifact always holds a version, so none is a 404. */
    @GetMapping("/{id}/versions")
    @RequiredScope(Permission.DASHBOARD_READ)
    fun versions(
        @PathVariable id: UUID,
    ): ApiResponse<List<Map<String, Any?>>> {
        val principal = currentPrincipal()
        val listed = dashboards.listVersions(principal.requireWorkspace().id, lens.viewFor(principal).dashboards, id)
        if (listed.isEmpty()) throw FAMILY.notFound(id.toString())
        return ApiResponse.of(listed.map(ArtifactResponses::versionSummary))
    }

    /** §23 — the draft write (versioning §5.1/§5.2); `If-Match` carries the hash precondition, checked before the parse. */
    @PutMapping("/{id}")
    @RequiredScope(Permission.DASHBOARD_UPDATE)
    fun update(
        @PathVariable id: UUID,
        @RequestHeader(value = IfMatchHeader.NAME, required = false) ifMatch: String?,
        @RequestBody body: String,
    ): ApiResponse<JsonNode> {
        val expectedHash = IfMatchHeader.required(ifMatch)
        val principal = currentPrincipal()
        val document = reader.readOrThrow(ArtifactHttp.readTree(FAMILY, body))
        val written =
            dashboards.write(
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
     * §23 — release the draft at `If-Match` (D61): §3.2's rules against the dependencies as they are NOW; the set
     * release RELEASED; every pinned visualization RELEASED or — with `release_pinned_visualizations=true` — released
     * through its OWN gate in the same transaction (its template drafts are not consented by this flag); every
     * source release RELEASED and read-only. Anything else is `dashboard.release.dependency_not_released`.
     */
    @PostMapping("/{id}/release")
    @RequiredScope(Permission.DASHBOARD_RELEASE)
    fun release(
        @PathVariable id: UUID,
        @RequestHeader(value = IfMatchHeader.NAME, required = false) ifMatch: String?,
        @RequestParam(value = "release_pinned_visualizations", required = false, defaultValue = "false")
        releasePinnedVisualizations: Boolean = false,
    ): ApiResponse<JsonNode> {
        val principal = currentPrincipal()
        val workspaceId = principal.requireWorkspace().id
        val released =
            dashboards.release(
                workspaceId,
                id,
                IfMatchHeader.required(ifMatch),
                principal.userId,
                releasePinnedVisualizations = releasePinnedVisualizations,
            )
        // #332 — the release audit, the auditRelease twin: each cascaded VISUALIZATION's own event
        // first (the 142 "who released X v2 and why" provenance, cascade_from_dashboard_id), then the
        // dashboard's own event naming them — after the one transaction committed, never on a refusal.
        val cascade = LifecycleVerbs.FamilyCascade("dashboard_id", id, released.version.detail.version)
        released.visualizationsReleased.forEach { pin ->
            LifecycleVerbs.audit(
                audit,
                LifecycleVerbs.VISUALIZATION_EVENTS.versionReleased,
                principal,
                workspaceId,
                LifecycleVerbs.cascadedReleaseDetails(principal, null, null, pin.version, cascade) + mapOf("name" to pin.name),
            )
        }
        LifecycleVerbs.audit(
            audit,
            LifecycleVerbs.DASHBOARD_EVENTS.versionReleased,
            principal,
            workspaceId,
            LifecycleVerbs.familyReleaseDetails(
                principal,
                LifecycleVerbs.FamilyIdentity(
                    "dashboard_id",
                    "dashboard_name",
                    id,
                    released.version.record.name,
                    released.version.detail.version,
                ),
                "visualizations_released",
                released.visualizationsReleased.map { mapOf("name" to it.name, "version" to it.version) },
            ),
        )
        val data = ArtifactResponses.full(released.version) as com.fasterxml.jackson.databind.node.ObjectNode
        data.putArray("visualizations_released").also { array ->
            released.visualizationsReleased.forEach { array.addObject().put("name", it.name).put("version", it.version) }
        }
        return ApiResponse.of(data)
    }

    /**
     * §23 — validate the WORKING version (the draft, else the current release) against the dependencies' CURRENT
     * state — §3.2's rules, no write (the agent's loop before a human releases). The verdict is the answer: `200`
     * with `valid` and every refusal, never a 400 — the request was fine; the dashboard may not be. An AUTHOR verb
     * (`dashboard.update`; see the class KDoc).
     */
    @PostMapping("/{id}/validate")
    @RequiredScope(Permission.DASHBOARD_UPDATE)
    fun validate(
        @PathVariable id: UUID,
    ): ApiResponse<Map<String, Any?>> {
        val principal = currentPrincipal()
        val workspaceId = principal.requireWorkspace().id
        val loaded =
            dashboards.findWorking(workspaceId, lens.viewFor(principal).dashboards, id) ?: throw FAMILY.notFound(id.toString())
        val result =
            when (val validation = dashboards.validate(workspaceId, DashboardDocument(loaded.record.name, loaded.body))) {
                is ArtifactValidation.Valid -> co.datapipelines.pipeline.ValidationResult.VALID
                is ArtifactValidation.Invalid -> validation.result
            }
        return ApiResponse.of(ArtifactResponses.validation(loaded, result))
    }

    /** §23 — purge the draft (versioning §5.4) at `If-Match`; the sole draft takes the dashboard. Session-only. */
    @PostMapping("/{id}/draft/discard")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiredScope(Permission.DASHBOARD_VERSION_MANAGE)
    fun purgeDraft(
        @PathVariable id: UUID,
        @RequestHeader(value = IfMatchHeader.NAME, required = false) ifMatch: String?,
    ) {
        val principal = LifecycleVerbs.requireSession()
        val workspaceId = principal.requireWorkspace().id
        // The name and version for the audit row, read through the caller's lens BEFORE the purge —
        // a sole-draft purge takes the dashboard, so afterwards there is nothing to read (#332).
        val audited = workingForAudit(principal, workspaceId, id)
        dashboards.purgeDraft(workspaceId, id, IfMatchHeader.required(ifMatch))
        LifecycleVerbs.audit(
            audit,
            LifecycleVerbs.DASHBOARD_EVENTS.versionPurged,
            principal,
            workspaceId,
            auditedDetails(id, audited),
        )
    }

    /** §23 — discard RELEASED version v (reversible via restore); the served pointer falls back (D60). Session-only. */
    @PostMapping("/{id}/versions/{version}/discard")
    @RequiredScope(Permission.DASHBOARD_VERSION_MANAGE)
    fun discardVersion(
        @PathVariable id: UUID,
        @PathVariable version: Int,
    ): ApiResponse<Map<String, Any?>> {
        val principal = LifecycleVerbs.requireSession()
        val workspaceId = principal.requireWorkspace().id
        val audited = versionForAudit(principal, workspaceId, id, version)
        val detail = dashboards.discardVersion(workspaceId, id, version, principal.userId)
        LifecycleVerbs.audit(
            audit,
            LifecycleVerbs.DASHBOARD_EVENTS.versionDiscarded,
            principal,
            workspaceId,
            auditedDetails(id, audited, version),
        )
        return ApiResponse.of(ArtifactResponses.lifecycleSummary(detail))
    }

    /** §23 — restore DISCARDED version v; the pointer moves only above-current-or-NULL (D60). Session-only. */
    @PostMapping("/{id}/versions/{version}/restore")
    @RequiredScope(Permission.DASHBOARD_VERSION_MANAGE)
    fun restoreVersion(
        @PathVariable id: UUID,
        @PathVariable version: Int,
    ): ApiResponse<Map<String, Any?>> {
        val principal = LifecycleVerbs.requireSession()
        val workspaceId = principal.requireWorkspace().id
        val audited = versionForAudit(principal, workspaceId, id, version)
        val detail = dashboards.restoreVersion(workspaceId, id, version)
        LifecycleVerbs.audit(
            audit,
            LifecycleVerbs.DASHBOARD_EVENTS.versionRestored,
            principal,
            workspaceId,
            auditedDetails(id, audited, version),
        )
        return ApiResponse.of(ArtifactResponses.lifecycleSummary(detail))
    }

    /** §23 — purge DRAFT version v (drafts only). Session-only; irreversible. */
    @DeleteMapping("/{id}/versions/{version}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiredScope(Permission.DASHBOARD_VERSION_MANAGE)
    fun purgeVersion(
        @PathVariable id: UUID,
        @PathVariable version: Int,
    ) {
        val principal = LifecycleVerbs.requireSession()
        val workspaceId = principal.requireWorkspace().id
        val audited = versionForAudit(principal, workspaceId, id, version)
        dashboards.purgeVersion(workspaceId, id, version)
        LifecycleVerbs.audit(
            audit,
            LifecycleVerbs.DASHBOARD_EVENTS.versionPurged,
            principal,
            workspaceId,
            auditedDetails(id, audited, version),
        )
    }

    /** §23 — the manual switch (D60, the receiver's lever); the body's shape is judged before the lookup. Session-only. */
    @PostMapping("/{id}/current")
    @RequiredScope(Permission.DASHBOARD_SWITCH_VERSION)
    fun switchCurrent(
        @PathVariable id: UUID,
        @RequestBody body: String,
    ): ApiResponse<Map<String, Any?>> {
        val principal = LifecycleVerbs.requireSession()
        val workspaceId = principal.requireWorkspace().id
        val target = ArtifactHttp.switchTarget(FAMILY, body)
        val current = dashboards.switchCurrent(workspaceId, id, target)
        LifecycleVerbs.audit(
            audit,
            LifecycleVerbs.DASHBOARD_EVENTS.currentSwitched,
            principal,
            workspaceId,
            mapOf(
                "dashboard_id" to id.toString(),
                "dashboard_name" to dashboards.findVersion(workspaceId, lens.viewFor(principal).dashboards, id, current)?.record?.name,
                "to" to current,
            ),
        )
        return ApiResponse.of(mapOf("id" to id.toString(), "current_version" to current))
    }

    /** §23 — the entity purge, only when the dashboard's only version is a DRAFT. Session-only. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiredScope(Permission.DASHBOARD_DELETE)
    fun delete(
        @PathVariable id: UUID,
    ) {
        val principal = LifecycleVerbs.requireSession()
        val workspaceId = principal.requireWorkspace().id
        val audited = workingForAudit(principal, workspaceId, id)
        dashboards.purgeEntity(workspaceId, id)
        LifecycleVerbs.audit(
            audit,
            LifecycleVerbs.DASHBOARD_EVENTS.entityPurged,
            principal,
            workspaceId,
            auditedDetails(id, audited),
        )
    }

    /** §23 — ONE level of the tree, the `?prefix=` browse (a multi-segment name never travels in a path — P24). */
    @GetMapping(params = ["prefix"])
    @RequiredScope(Permission.DASHBOARD_READ)
    fun browse(
        @RequestParam prefix: String,
        @RequestParam(required = false) offset: Int?,
        @RequestParam(required = false) limit: Int?,
    ): ApiResponse<Map<String, Any?>> {
        val page = Pagination.clampOffset(offset)
        val size = Pagination.clampLimit(limit)
        val principal = currentPrincipal()
        val workspaceId = principal.requireWorkspace().id
        val view = lens.viewFor(principal).dashboards
        val folders = dashboards.listChildFolders(workspaceId, view, prefix)
        val loaded = dashboards.listChildren(workspaceId, view, prefix, page, size)
        // `total` counts the level's DASHBOARDS the lens admits, never the folders — lens-true (#300).
        val total = dashboards.countChildren(workspaceId, view, prefix)
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

    /** §23 — the FLAT listing (#312's shape): every dashboard the caller's lens admits, paged, with a lens-true total. */
    @GetMapping(params = ["!prefix"])
    @RequiredScope(Permission.DASHBOARD_READ)
    fun list(
        @RequestParam(required = false) offset: Int?,
        @RequestParam(required = false) limit: Int?,
    ): ApiResponse<PagedData<Map<String, Any?>>> {
        val page = Pagination.clampOffset(offset)
        val size = Pagination.clampLimit(limit)
        val principal = currentPrincipal()
        val workspaceId = principal.requireWorkspace().id
        val view = lens.viewFor(principal).dashboards
        val items = dashboards.listAll(workspaceId, view, page, size).map { ArtifactResponses.listEntry(it) }
        val total = dashboards.countAll(workspaceId, view)
        return ApiResponse.of(PagedData(items, Pagination.of(page, size, total.toLong(), items.size)))
    }

    // ---- the audit rows' pre-reads (#332) ----------------------------------------------------------

    /** The working version's (name, number) for an audit row — read through the caller's lens, before the verb. */
    private fun workingForAudit(
        principal: AuthenticatedPrincipal,
        workspaceId: UUID,
        id: UUID,
    ): Pair<String, Int>? =
        dashboards.findWorking(workspaceId, lens.viewFor(principal).dashboards, id)?.let { it.record.name to it.detail.version }

    /** A named version's (name, number) — the same lens rule. */
    private fun versionForAudit(
        principal: AuthenticatedPrincipal,
        workspaceId: UUID,
        id: UUID,
        version: Int,
    ): Pair<String, Int>? =
        dashboards.findVersion(workspaceId, lens.viewFor(principal).dashboards, id, version)?.let { it.record.name to it.detail.version }

    /** The purge row's details: the id, plus the pre-read name and version when the artifact still existed. */
    private fun auditedDetails(
        id: UUID,
        audited: Pair<String, Int>?,
        version: Int? = null,
    ): Map<String, Any?> =
        buildMap {
            put("dashboard_id", id.toString())
            put("dashboard_name", audited?.first)
            put("version", version ?: audited?.second)
        }

    private companion object {
        val FAMILY = ArtifactFamily.DASHBOARD
    }
}
