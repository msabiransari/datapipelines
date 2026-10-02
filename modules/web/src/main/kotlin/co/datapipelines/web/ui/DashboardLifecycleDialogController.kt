package co.datapipelines.web.ui

import co.datapipelines.application.lens.LensedView
import co.datapipelines.auth.AuditEventSink
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.Permission
import co.datapipelines.auth.RequiredScope
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.ReadLens
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.visualization.DashboardErrorCodes
import co.datapipelines.visualization.DashboardService
import co.datapipelines.web.api.currentPrincipal
import co.datapipelines.web.pipelines.LifecycleVerbs
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.ResponseEntity
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import java.util.UUID

/**
 * The dashboard lifecycle verbs' dialog family (#400, ui-screens §4.21; [PipelineLifecycleDialogController]'s
 * shape over the dashboard family's EXISTING rule): every GET opens a confirm partial into
 * `#dp-dialog`, every POST calls the SAME [DashboardService] the REST routes wire — never the REST
 * controllers over HTTP, never a second copy of a guard (the guard re-runs under the POST; the dialog
 * only decides what to SAY). Session-only like the verbs themselves; an API key is refused
 * `auth.session.required` before anything is read — a `dashboard` key never reaches a UI page
 * (session-only surface, §5.2's key rule).
 *
 * Every success answers `HX-Redirect` onto the workspace's Versions tab with a flash toast
 * (`/dashboards/{id}?tab=versions&ok=…`) — the editor-surface shape (#395): the dashboards family
 * has NO explorer detail region, so there is no Shape A re-render to answer with, and a lifecycle
 * change moves the version set the whole page reads. The entity purge redirects to the CATALOG —
 * the row is gone, the tree and the catalog must lose it. Refusals ride the UiExceptionHandler
 * Shape C path with their real 4xx — including the typed-confirm mismatch, which is checked BEFORE
 * the service runs so a mismatched dialog can never purge anything.
 *
 * The release is audited exactly as the REST route audits it (#332): each cascaded VISUALIZATION's
 * own event first (the 142 provenance, `cascade_from_dashboard_id`), then the dashboard's own event
 * naming them — after the service returned, never on a refusal.
 */
@org.springframework.stereotype.Controller
class DashboardLifecycleDialogController(
    private val dashboards: DashboardService,
    private val dialogs: DashboardLifecycleDialogModel,
    private val audit: AuditEventSink,
) {
    // ------------------------------------------------------------------ release

    @GetMapping("/partials/dashboards/{id}/lifecycle/release")
    @RequiredScope(Permission.DASHBOARD_RELEASE)
    fun releaseDialog(
        model: Model,
        @PathVariable id: UUID,
    ): String {
        LifecycleVerbs.requireSession()
        model.addAttribute("dlg", dialogs.release(currentPrincipal().requireWorkspace().id, id))
        // 177 §D.8: the dialog's verb renders inside a role guard like every other verb — the route
        // already refuses the wrong role; the markup now says so too, and the exemption list is empty.
        RoleModel.stamp(model)
        return "partials/dashboard-lifecycle-release"
    }

    @PostMapping("/partials/dashboards/{id}/lifecycle/release")
    @RequiredScope(Permission.DASHBOARD_RELEASE)
    fun release(
        @Suppress("UNUSED_PARAMETER") // the form posts it; the redirect target is the Versions tab regardless
        @PathVariable id: UUID,
        @RequestParam(required = false, defaultValue = "false") releasePinnedVisualizations: Boolean,
    ): ResponseEntity<String> {
        val principal = LifecycleVerbs.requireSession()
        val workspaceId = principal.requireWorkspace().id
        // The hash the DIALOG read (§4.2: you release what you tested); a draft that changed
        // in between is a stale hash and the service answers dashboard.version.conflict.
        val draft =
            dashboards.findWorking(workspaceId, ReadLens.Everything, id)?.takeIf { it.detail.status == PipelineVersionStatus.DRAFT }
                ?: throw DatapipelinesException(
                    code = DashboardErrorCodes.VERSION_NOT_DRAFT,
                    message = "This dashboard has no draft to release.",
                    details = mapOf("dashboard_id" to id.toString()),
                )
        // D61: the ONE consent — without it a DRAFT visualization pin refuses
        // dashboard.release.dependency_not_released as it always has; the server, never the
        // checkbox, is the guard. The set and the source pins have no cascade: RELEASED or refused.
        val released =
            dashboards.release(workspaceId, id, draft.detail.bodyHash, principal.userId, releasePinnedVisualizations)
        // #332 — the release audit, the REST route's twin: each cascaded visualization's own event
        // first, then the dashboard's own event naming them.
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
        return redirect(
            versionsTab(id, if (released.visualizationsReleased.isEmpty()) OK_RELEASED else OK_RELEASED_WITH_VISUALIZATIONS),
        )
    }

    // ------------------------------------------------------------------ purge draft / purge version (the versioned verb)

    @GetMapping("/partials/dashboards/{id}/lifecycle/purge")
    @RequiredScope(Permission.DASHBOARD_VERSION_MANAGE)
    fun purgeDialog(
        model: Model,
        @PathVariable id: UUID,
        @RequestParam version: Int,
    ): String {
        LifecycleVerbs.requireSession()
        model.addAttribute("dlg", dialogs.purge(currentPrincipal().requireWorkspace().id, id, version))
        RoleModel.stamp(model)
        return "partials/dashboard-lifecycle-purge"
    }

    @PostMapping("/partials/dashboards/{id}/lifecycle/purge")
    @RequiredScope(Permission.DASHBOARD_VERSION_MANAGE)
    fun purge(
        @Suppress("UNUSED_PARAMETER") // the form posts it; the redirect target is the Versions tab regardless
        @PathVariable id: UUID,
        @RequestParam version: Int,
        @RequestParam confirm: String?,
    ): ResponseEntity<String> {
        val principal = LifecycleVerbs.requireSession()
        val workspaceId = principal.requireWorkspace().id
        requireTypedConfirm(confirm, "v$version")
        // The name and version for the audit row, read through the everything view BEFORE the
        // purge — a sole-draft purge takes the dashboard, so afterwards there is nothing to read (#332).
        val audited = dashboards.findWorking(workspaceId, ReadLens.Everything, id)
        val purged = dashboards.purgeDraft(workspaceId, id, draftHash(workspaceId, id, version))
        LifecycleVerbs.audit(
            audit,
            LifecycleVerbs.DASHBOARD_EVENTS.versionPurged,
            principal,
            workspaceId,
            auditedDetails(id, audited) + mapOf("version" to version, "scope" to purged.scope),
        )
        // The row may be gone (a sole-draft purge): the catalog loses the leaf.
        return if (purged == co.datapipelines.visualization.Purged.Entity) {
            redirect("/dashboards?ok=dashboard_purged")
        } else {
            redirect(versionsTab(id, OK_DRAFT_PURGED))
        }
    }

    // ------------------------------------------------------------------ discard release

    @GetMapping("/partials/dashboards/{id}/lifecycle/discard")
    @RequiredScope(Permission.DASHBOARD_VERSION_MANAGE)
    fun discardDialog(
        model: Model,
        @PathVariable id: UUID,
        @RequestParam version: Int,
    ): String {
        LifecycleVerbs.requireSession()
        model.addAttribute("dlg", dialogs.discard(currentPrincipal().requireWorkspace().id, id, version))
        RoleModel.stamp(model)
        return "partials/dashboard-lifecycle-discard"
    }

    @PostMapping("/partials/dashboards/{id}/lifecycle/discard")
    @RequiredScope(Permission.DASHBOARD_VERSION_MANAGE)
    fun discard(
        @Suppress("UNUSED_PARAMETER") // the form posts it; the redirect target is the Versions tab regardless
        @PathVariable id: UUID,
        @RequestParam version: Int,
    ): ResponseEntity<String> {
        val principal = LifecycleVerbs.requireSession()
        val workspaceId = principal.requireWorkspace().id
        // The name comes from the pre-verb read; the pointer pair from the verb's own
        // result — never a re-read after the fact (#372).
        val audited = dashboards.findWorking(workspaceId, ReadLens.Everything, id)
        val moved = dashboards.discardVersion(workspaceId, id, version, principal.userId)
        LifecycleVerbs.audit(
            audit,
            LifecycleVerbs.DASHBOARD_EVENTS.versionDiscarded,
            principal,
            workspaceId,
            auditedDetails(id, audited, version) +
                mapOf("current_version_before" to moved.pointer.before, "current_version_after" to moved.pointer.after),
        )
        return redirect(versionsTab(id, OK_DISCARDED))
    }

    // ------------------------------------------------------------------ restore

    @GetMapping("/partials/dashboards/{id}/lifecycle/restore")
    @RequiredScope(Permission.DASHBOARD_VERSION_MANAGE)
    fun restoreDialog(
        model: Model,
        @PathVariable id: UUID,
        @RequestParam version: Int,
    ): String {
        LifecycleVerbs.requireSession()
        model.addAttribute("dlg", dialogs.restore(currentPrincipal().requireWorkspace().id, id, version))
        RoleModel.stamp(model)
        return "partials/dashboard-lifecycle-restore"
    }

    @PostMapping("/partials/dashboards/{id}/lifecycle/restore")
    @RequiredScope(Permission.DASHBOARD_VERSION_MANAGE)
    fun restore(
        @Suppress("UNUSED_PARAMETER") // the form posts it; the redirect target is the Versions tab regardless
        @PathVariable id: UUID,
        @RequestParam version: Int,
    ): ResponseEntity<String> {
        val principal = LifecycleVerbs.requireSession()
        val workspaceId = principal.requireWorkspace().id
        val audited = dashboards.findWorking(workspaceId, ReadLens.Everything, id)
        val moved = dashboards.restoreVersion(workspaceId, id, version)
        LifecycleVerbs.audit(
            audit,
            LifecycleVerbs.DASHBOARD_EVENTS.versionRestored,
            principal,
            workspaceId,
            auditedDetails(id, audited, version) +
                mapOf("current_version_before" to moved.pointer.before, "current_version_after" to moved.pointer.after),
        )
        return redirect(versionsTab(id, OK_RESTORED))
    }

    // ------------------------------------------------------------------ purge version

    @GetMapping("/partials/dashboards/{id}/lifecycle/purge-version")
    @RequiredScope(Permission.DASHBOARD_VERSION_MANAGE)
    fun purgeVersionDialog(
        model: Model,
        @PathVariable id: UUID,
        @RequestParam version: Int,
    ): String {
        LifecycleVerbs.requireSession()
        model.addAttribute("dlg", dialogs.purge(currentPrincipal().requireWorkspace().id, id, version))
        RoleModel.stamp(model)
        return "partials/dashboard-lifecycle-purge-version"
    }

    @PostMapping("/partials/dashboards/{id}/lifecycle/purge-version")
    @RequiredScope(Permission.DASHBOARD_VERSION_MANAGE)
    fun purgeVersion(
        @Suppress("UNUSED_PARAMETER") // the form posts it; the redirect target is the Versions tab regardless
        @PathVariable id: UUID,
        @RequestParam version: Int,
        @RequestParam confirm: String?,
    ): ResponseEntity<String> {
        val principal = LifecycleVerbs.requireSession()
        val workspaceId = principal.requireWorkspace().id
        requireTypedConfirm(confirm, "v$version")
        val audited = dashboards.findWorking(workspaceId, ReadLens.Everything, id)
        val purged = dashboards.purgeVersion(workspaceId, id, version)
        LifecycleVerbs.audit(
            audit,
            LifecycleVerbs.DASHBOARD_EVENTS.versionPurged,
            principal,
            workspaceId,
            auditedDetails(id, audited, version) + mapOf("scope" to purged.scope),
        )
        return if (purged == co.datapipelines.visualization.Purged.Entity) {
            redirect("/dashboards?ok=dashboard_purged")
        } else {
            redirect(versionsTab(id, OK_DRAFT_PURGED))
        }
    }

    // ------------------------------------------------------------------ purge entity

    @GetMapping("/partials/dashboards/{id}/lifecycle/purge-entity")
    @RequiredScope(Permission.DASHBOARD_DELETE)
    fun purgeEntityDialog(
        model: Model,
        @PathVariable id: UUID,
    ): String {
        LifecycleVerbs.requireSession()
        model.addAttribute("dlg", dialogs.purgeEntity(currentPrincipal().requireWorkspace().id, id))
        RoleModel.stamp(model)
        return "partials/dashboard-lifecycle-purge-entity"
    }

    @PostMapping("/partials/dashboards/{id}/lifecycle/purge-entity")
    @RequiredScope(Permission.DASHBOARD_DELETE)
    fun purgeEntity(
        @Suppress("UNUSED_PARAMETER") // the form posts it; the redirect target is the Versions tab regardless
        @PathVariable id: UUID,
        @RequestParam confirm: String?,
    ): ResponseEntity<String> {
        val principal = LifecycleVerbs.requireSession()
        val workspaceId = principal.requireWorkspace().id
        // The typed confirm names the DASHBOARD (its name, which the dialog showed); the name
        // is read fresh — a rename between dialog and POST refuses rather than guesses.
        val record =
            dashboards.findWorking(workspaceId, ReadLens.Everything, id)
                ?: throw DatapipelinesException(
                    code = DashboardErrorCodes.NOT_FOUND,
                    message = "No dashboard with id '$id' in this workspace.",
                    details = mapOf("dashboard_id" to id.toString()),
                )
        requireTypedConfirm(confirm, record.record.name)
        val audited = record
        val purged = dashboards.purgeEntity(workspaceId, id)
        LifecycleVerbs.audit(
            audit,
            LifecycleVerbs.DASHBOARD_EVENTS.entityPurged,
            principal,
            workspaceId,
            auditedDetails(id, audited) + mapOf("scope" to purged.scope),
        )
        // The row is gone: redirect so the CATALOG and the tree lose the leaf (the flash carries the toast).
        return redirect("/dashboards?ok=dashboard_purged")
    }

    // ------------------------------------------------------------------ switch

    @GetMapping("/partials/dashboards/{id}/lifecycle/switch")
    @RequiredScope(Permission.DASHBOARD_SWITCH_VERSION)
    fun switchDialog(
        model: Model,
        @PathVariable id: UUID,
        @RequestParam(required = false) version: Int?,
    ): String {
        LifecycleVerbs.requireSession()
        model.addAttribute("dlg", dialogs.switch(currentPrincipal().requireWorkspace().id, id))
        model.addAttribute("preselect", version)
        RoleModel.stamp(model)
        return "partials/dashboard-lifecycle-switch"
    }

    @PostMapping("/partials/dashboards/{id}/lifecycle/switch")
    @RequiredScope(Permission.DASHBOARD_SWITCH_VERSION)
    fun switchCurrent(
        @Suppress("UNUSED_PARAMETER") // the form posts it; the redirect target is the Versions tab regardless
        @PathVariable id: UUID,
        @RequestParam version: Int,
    ): ResponseEntity<String> {
        val principal = LifecycleVerbs.requireSession()
        val workspaceId = principal.requireWorkspace().id
        // The name and the pointer pair come from the verb's own result (#372).
        val switched = dashboards.switchCurrent(workspaceId, id, version)
        LifecycleVerbs.audit(
            audit,
            LifecycleVerbs.DASHBOARD_EVENTS.currentSwitched,
            principal,
            workspaceId,
            mapOf(
                "dashboard_id" to id.toString(),
                "dashboard_name" to switched.name,
                "from" to switched.pointer.before,
                "to" to switched.pointer.after,
            ),
        )
        return redirect(versionsTab(id, OK_SWITCHED))
    }

    // ------------------------------------------------------------------ shared shapes

    /**
     * The editor/entity shape (TemplateEditorController's openWorkingVersion pattern): an
     * empty 200 carrying `HX-Redirect`, so htmx navigates the whole page and the layout's
     * flash bin renders the toast after it lands. The target is built HERE from the path id
     * and a wire constant — never from a request parameter — so the family carries no
     * open-redirect surface.
     */
    private fun redirect(url: String): ResponseEntity<String> = ResponseEntity.ok().header("HX-Redirect", url).body("")

    /** #400 — the workspace's Versions tab with the layout flash [ok] names (default.html's bin). */
    private fun versionsTab(
        id: UUID,
        ok: String,
    ): String = "/dashboards/$id?tab=versions&ok=$ok"

    /** The working draft's hash — the purge precondition, read fresh, never trusted from the form. */
    private fun draftHash(
        workspaceId: UUID,
        id: UUID,
        version: Int,
    ): String {
        val draft =
            dashboards
                .findWorking(workspaceId, ReadLens.Everything, id)
                ?.takeIf { it.detail.status == PipelineVersionStatus.DRAFT && it.detail.version == version }
                ?: throw DatapipelinesException(
                    code = DashboardErrorCodes.VERSION_NOT_DRAFT,
                    message = "Version $version is not the working draft — only a draft is purged.",
                    details = mapOf("dashboard_id" to id.toString(), "version" to version),
                )
        return draft.detail.bodyHash
    }

    /** The audit `details` keys the REST surface's twins carry (#332): ids, versions, scope — never names' bodies. */
    private fun auditedDetails(
        id: UUID,
        working: co.datapipelines.visualization.ArtifactVersion<co.datapipelines.visualization.DashboardBody>?,
        version: Int? = null,
    ): Map<String, Any?> =
        buildMap {
            put("dashboard_id", id.toString())
            working?.let { put("dashboard_name", it.record.name) }
            version?.let { put("version", it) }
        }

    /**
     * The typed-confirm guard — BEFORE the service, so a mismatch never reaches the verb. The
     * code is the family's version-conflict row (the catalog answers 409): the dashboard
     * family defines no confirm-mismatch code of its own, and `modules/visualization` is not
     * this lane's to edit — declared in the handback.
     */
    private fun requireTypedConfirm(
        confirm: String?,
        expected: String,
    ) {
        if (confirm != expected) {
            throw DatapipelinesException(
                code = DashboardErrorCodes.VERSION_CONFLICT,
                message = "Type $expected to confirm — the typed confirm did not match.",
                details = mapOf("expected" to expected),
            )
        }
    }

    private companion object {
        /** The layout flash bin's ok codes — the version-generic ones reused, three family words added. */
        const val OK_RELEASED = "released"
        const val OK_RELEASED_WITH_VISUALIZATIONS = "released_with_visualizations"
        const val OK_DISCARDED = "discarded"
        const val OK_RESTORED = "restored"
        const val OK_SWITCHED = "switched"
        const val OK_DRAFT_PURGED = "dashboard_draft_purged"
    }
}
