package co.datapipelines.web.ui

import co.datapipelines.auth.AuditEventSink
import co.datapipelines.auth.Permission
import co.datapipelines.auth.RequiredScope
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.ReadLens
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.visualization.ArtifactVersion
import co.datapipelines.visualization.Purged
import co.datapipelines.visualization.VisualizationBody
import co.datapipelines.visualization.VisualizationErrorCodes
import co.datapipelines.visualization.VisualizationService
import co.datapipelines.web.pipelines.LifecycleVerbs
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import java.util.UUID

/**
 * The visualization lifecycle verbs' dialog family (#399, ui-screens §4.24; [DashboardLifecycleDialogController]'s shape
 * over the visualization family's EXISTING rules): every GET opens a confirm partial into `#dp-dialog`, every POST
 * calls the SAME [VisualizationService] verb the REST routes wire — never the REST controller over HTTP, never a
 * second copy of a guard (the guard re-runs under the POST; the dialog only decides what to SAY). Session-only like
 * the REST verbs: an API key is refused `auth.session.required` before anything is read.
 *
 * Each route declares the verb's own permission row (release → `visualization.release`; purge draft, discard,
 * restore, purge version → `visualization.version.manage`; switch → `visualization.switch_version`; purge
 * visualization → `visualization.delete`); viewers and promoters hold none of them.
 *
 * Every success answers `HX-Redirect` onto the workspace with a flash toast, the URL built HERE from the path id, a
 * tab from the workspace's CLOSED tab enum (the `from` the dialog posts; anything else is Versions) and a constant
 * `ok` code — never a request string — so the family carries no open-redirect surface. A purge that takes the entity
 * redirects to the catalog. Refusals ride the UiExceptionHandler Shape C path with their real 4xx — including the
 * typed-confirm mismatch, checked BEFORE the service so a mismatched dialog can never purge anything.
 *
 * Every audit row is the REST route's twin (#332): the release's cascaded template events first, then the
 * visualization's own naming them — after the service returned, never on a refusal.
 */
@Controller
@RequestMapping("/partials/visualizations/{id}/lifecycle")
@Suppress("TooManyFunctions") // one GET + one POST per verb
class VisualizationLifecycleDialogController(
    private val visualizations: VisualizationService,
    private val dialogs: VisualizationLifecycleDialogModel,
    private val audit: AuditEventSink,
) {
    // ------------------------------------------------------------------ release

    @GetMapping("/release")
    @RequiredScope(Permission.VISUALIZATION_RELEASE)
    fun releaseDialog(
        model: Model,
        @PathVariable id: UUID,
        @RequestParam(required = false) from: String?,
    ): String {
        val principal = LifecycleVerbs.requireSession()
        model.addAttribute("dlg", dialogs.release(principal.requireWorkspace().id, id))
        model.addAttribute("from", origin(from).wire)
        RoleModel.stamp(model)
        return "partials/visualization-lifecycle-release"
    }

    @PostMapping("/release")
    @RequiredScope(Permission.VISUALIZATION_RELEASE)
    fun release(
        @PathVariable id: UUID,
        @RequestParam(name = "body_hash") bodyHash: String,
        @RequestParam(name = "release_pinned_templates", required = false, defaultValue = "false") releasePinnedTemplates: Boolean,
        @RequestParam(required = false) from: String?,
    ): ResponseEntity<String> {
        val principal = LifecycleVerbs.requireSession()
        val workspaceId = principal.requireWorkspace().id
        // #416 — [bodyHash] is the hash the DIALOG read (you release what you tested). The draft is read here only
        // to refuse the no-draft case; its CURRENT hash is never the one released, so a draft that changed after
        // the dialog opened is a stale hash and the service answers visualization.version.conflict (the evidence
        // gate, which judges the current body, still answers first when the new draft has no green run).
        draftOf(workspaceId, id, version = null)
        // D61 — the ONE consent: without it a DRAFT transform pin refuses release.dependency_not_released as it
        // always has; the server, never the checkbox, is the guard.
        val released = visualizations.release(workspaceId, id, bodyHash, principal.userId, releasePinnedTemplates)
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
        return redirect(workspaceTab(id, from, if (released.templatesReleased.isEmpty()) OK_RELEASED else OK_RELEASED_WITH_TEMPLATES))
    }

    // ------------------------------------------------------------------ purge draft (the working draft, at its hash)

    @GetMapping("/purge-draft")
    @RequiredScope(Permission.VISUALIZATION_VERSION_MANAGE)
    fun purgeDraftDialog(
        model: Model,
        @PathVariable id: UUID,
        @RequestParam version: Int,
        @RequestParam(required = false) from: String?,
    ): String {
        val principal = LifecycleVerbs.requireSession()
        model.addAttribute("dlg", dialogs.purge(principal.requireWorkspace().id, id, version))
        model.addAttribute("from", origin(from).wire)
        RoleModel.stamp(model)
        return "partials/visualization-lifecycle-purge-draft"
    }

    @PostMapping("/purge-draft")
    @RequiredScope(Permission.VISUALIZATION_VERSION_MANAGE)
    fun purgeDraft(
        @PathVariable id: UUID,
        @RequestParam version: Int,
        @RequestParam(required = false) confirm: String?,
        @RequestParam(required = false) from: String?,
    ): ResponseEntity<String> {
        val principal = LifecycleVerbs.requireSession()
        val workspaceId = principal.requireWorkspace().id
        requireTypedConfirm(confirm, "v$version")
        // The working draft at its hash, read fresh — never trusted from the form; its name is the audit row's
        // (a sole-draft purge takes the visualization, so afterwards there is nothing to read — #332).
        val draft = draftOf(workspaceId, id, version)
        val purged = visualizations.purgeDraft(workspaceId, id, draft.detail.bodyHash)
        LifecycleVerbs.audit(
            audit,
            LifecycleVerbs.VISUALIZATION_EVENTS.versionPurged,
            principal,
            workspaceId,
            auditedDetails(id, draft, version) + mapOf("scope" to purged.scope),
        )
        return afterPurge(id, from, purged)
    }

    // ------------------------------------------------------------------ discard

    @GetMapping("/discard")
    @RequiredScope(Permission.VISUALIZATION_VERSION_MANAGE)
    fun discardDialog(
        model: Model,
        @PathVariable id: UUID,
        @RequestParam version: Int,
        @RequestParam(required = false) from: String?,
    ): String {
        val principal = LifecycleVerbs.requireSession()
        model.addAttribute("dlg", dialogs.discard(principal.requireWorkspace().id, id, version))
        model.addAttribute("from", origin(from).wire)
        RoleModel.stamp(model)
        return "partials/visualization-lifecycle-discard"
    }

    @PostMapping("/discard")
    @RequiredScope(Permission.VISUALIZATION_VERSION_MANAGE)
    fun discard(
        @PathVariable id: UUID,
        @RequestParam version: Int,
        @RequestParam(required = false) from: String?,
    ): ResponseEntity<String> {
        val principal = LifecycleVerbs.requireSession()
        val workspaceId = principal.requireWorkspace().id
        // The name from the pre-verb read; the pointer pair from the verb's own result (#372).
        val audited = visualizations.findWorking(workspaceId, ReadLens.Everything, id)
        val moved = visualizations.discardVersion(workspaceId, id, version, principal.userId)
        LifecycleVerbs.audit(
            audit,
            LifecycleVerbs.VISUALIZATION_EVENTS.versionDiscarded,
            principal,
            workspaceId,
            auditedDetails(id, audited, version) +
                mapOf("current_version_before" to moved.pointer.before, "current_version_after" to moved.pointer.after),
        )
        return redirect(workspaceTab(id, from, OK_DISCARDED))
    }

    // ------------------------------------------------------------------ restore

    @GetMapping("/restore")
    @RequiredScope(Permission.VISUALIZATION_VERSION_MANAGE)
    fun restoreDialog(
        model: Model,
        @PathVariable id: UUID,
        @RequestParam version: Int,
        @RequestParam(required = false) from: String?,
    ): String {
        val principal = LifecycleVerbs.requireSession()
        model.addAttribute("dlg", dialogs.restore(principal.requireWorkspace().id, id, version))
        model.addAttribute("from", origin(from).wire)
        RoleModel.stamp(model)
        return "partials/visualization-lifecycle-restore"
    }

    @PostMapping("/restore")
    @RequiredScope(Permission.VISUALIZATION_VERSION_MANAGE)
    fun restore(
        @PathVariable id: UUID,
        @RequestParam version: Int,
        @RequestParam(required = false) from: String?,
    ): ResponseEntity<String> {
        val principal = LifecycleVerbs.requireSession()
        val workspaceId = principal.requireWorkspace().id
        val audited = visualizations.findWorking(workspaceId, ReadLens.Everything, id)
        val moved = visualizations.restoreVersion(workspaceId, id, version)
        LifecycleVerbs.audit(
            audit,
            LifecycleVerbs.VISUALIZATION_EVENTS.versionRestored,
            principal,
            workspaceId,
            auditedDetails(id, audited, version) +
                mapOf("current_version_before" to moved.pointer.before, "current_version_after" to moved.pointer.after),
        )
        return redirect(workspaceTab(id, from, OK_RESTORED))
    }

    // ------------------------------------------------------------------ purge version (any DRAFT version)

    @GetMapping("/purge-version")
    @RequiredScope(Permission.VISUALIZATION_VERSION_MANAGE)
    fun purgeVersionDialog(
        model: Model,
        @PathVariable id: UUID,
        @RequestParam version: Int,
        @RequestParam(required = false) from: String?,
    ): String {
        val principal = LifecycleVerbs.requireSession()
        model.addAttribute("dlg", dialogs.purge(principal.requireWorkspace().id, id, version))
        model.addAttribute("from", origin(from).wire)
        RoleModel.stamp(model)
        return "partials/visualization-lifecycle-purge-version"
    }

    @PostMapping("/purge-version")
    @RequiredScope(Permission.VISUALIZATION_VERSION_MANAGE)
    fun purgeVersion(
        @PathVariable id: UUID,
        @RequestParam version: Int,
        @RequestParam(required = false) confirm: String?,
        @RequestParam(required = false) from: String?,
    ): ResponseEntity<String> {
        val principal = LifecycleVerbs.requireSession()
        val workspaceId = principal.requireWorkspace().id
        requireTypedConfirm(confirm, "v$version")
        val audited = visualizations.findWorking(workspaceId, ReadLens.Everything, id)
        val purged = visualizations.purgeVersion(workspaceId, id, version)
        LifecycleVerbs.audit(
            audit,
            LifecycleVerbs.VISUALIZATION_EVENTS.versionPurged,
            principal,
            workspaceId,
            auditedDetails(id, audited, version) + mapOf("scope" to purged.scope),
        )
        return afterPurge(id, from, purged)
    }

    // ------------------------------------------------------------------ switch

    @GetMapping("/switch")
    @RequiredScope(Permission.VISUALIZATION_SWITCH_VERSION)
    fun switchDialog(
        model: Model,
        @PathVariable id: UUID,
        @RequestParam(required = false) version: Int?,
        @RequestParam(required = false) from: String?,
    ): String {
        val principal = LifecycleVerbs.requireSession()
        model.addAttribute("dlg", dialogs.switch(principal.requireWorkspace().id, id))
        model.addAttribute("preselect", version)
        model.addAttribute("from", origin(from).wire)
        RoleModel.stamp(model)
        return "partials/visualization-lifecycle-switch"
    }

    @PostMapping("/switch")
    @RequiredScope(Permission.VISUALIZATION_SWITCH_VERSION)
    fun switchCurrent(
        @PathVariable id: UUID,
        @RequestParam version: Int,
        @RequestParam(required = false) from: String?,
    ): ResponseEntity<String> {
        val principal = LifecycleVerbs.requireSession()
        val workspaceId = principal.requireWorkspace().id
        // The name and the pointer pair come from the verb's own result (#372).
        val switched = visualizations.switchCurrent(workspaceId, id, version)
        LifecycleVerbs.audit(
            audit,
            LifecycleVerbs.VISUALIZATION_EVENTS.currentSwitched,
            principal,
            workspaceId,
            mapOf(
                "visualization_id" to id.toString(),
                "visualization_name" to switched.name,
                "from" to switched.pointer.before,
                "to" to switched.pointer.after,
            ),
        )
        return redirect(workspaceTab(id, from, OK_SWITCHED))
    }

    // ------------------------------------------------------------------ purge visualization

    @GetMapping("/purge-entity")
    @RequiredScope(Permission.VISUALIZATION_DELETE)
    fun purgeEntityDialog(
        model: Model,
        @PathVariable id: UUID,
        @RequestParam(required = false) from: String?,
    ): String {
        val principal = LifecycleVerbs.requireSession()
        model.addAttribute("dlg", dialogs.purgeEntity(principal.requireWorkspace().id, id))
        model.addAttribute("from", origin(from).wire)
        RoleModel.stamp(model)
        return "partials/visualization-lifecycle-purge-entity"
    }

    @PostMapping("/purge-entity")
    @RequiredScope(Permission.VISUALIZATION_DELETE)
    fun purgeEntity(
        @PathVariable id: UUID,
        @RequestParam(required = false) confirm: String?,
    ): ResponseEntity<String> {
        val principal = LifecycleVerbs.requireSession()
        val workspaceId = principal.requireWorkspace().id
        // The typed confirm names the visualization, read fresh — a mismatch refuses rather than guesses.
        val working = visualizations.findWorking(workspaceId, ReadLens.Everything, id) ?: throw VisualizationWorkspaceModel.notFound(id)
        requireTypedConfirm(confirm, working.record.name)
        val purged = visualizations.purgeEntity(workspaceId, id)
        LifecycleVerbs.audit(
            audit,
            LifecycleVerbs.VISUALIZATION_EVENTS.entityPurged,
            principal,
            workspaceId,
            auditedDetails(id, working) + mapOf("scope" to purged.scope),
        )
        return redirect(CATALOG_PURGED)
    }

    // ------------------------------------------------------------------ shared shapes

    /** The working DRAFT, at [version] when named — the hash precondition read fresh, never from the form. */
    private fun draftOf(
        workspaceId: UUID,
        id: UUID,
        version: Int?,
    ): ArtifactVersion<VisualizationBody> =
        visualizations
            .findWorking(workspaceId, ReadLens.Everything, id)
            ?.takeIf { it.detail.status == PipelineVersionStatus.DRAFT && (version == null || it.detail.version == version) }
            ?: throw DatapipelinesException(
                code = VisualizationErrorCodes.VERSION_NOT_DRAFT,
                message = if (version == null) "This visualization has no draft." else "Version $version is not the working draft.",
                details =
                    buildMap {
                        put("visualization_id", id.toString())
                        version?.let { put("version", it) }
                    },
            )

    /** A purge that took the visualization lands on the catalog (the row is gone); otherwise back onto the tab. */
    private fun afterPurge(
        id: UUID,
        from: String?,
        purged: Purged,
    ): ResponseEntity<String> = if (purged == Purged.Entity) redirect(CATALOG_PURGED) else redirect(workspaceTab(id, from, OK_DRAFT_PURGED))

    /** An empty 200 carrying `HX-Redirect` — htmx navigates the whole page; the layout's flash bin renders the toast. */
    private fun redirect(url: String): ResponseEntity<String> = ResponseEntity.ok().header("HX-Redirect", url).body("")

    /** The audit `details` the REST twins carry (#332): id, the pre-read name, the version. */
    private fun auditedDetails(
        id: UUID,
        working: ArtifactVersion<VisualizationBody>?,
        version: Int? = null,
    ): Map<String, Any?> =
        buildMap {
            put("visualization_id", id.toString())
            put("visualization_name", working?.record?.name)
            put("version", version ?: working?.detail?.version)
        }

    /** The typed-confirm guard — BEFORE the service, so a mismatch never reaches the verb (the family's conflict row). */
    private fun requireTypedConfirm(
        confirm: String?,
        expected: String,
    ) {
        if (confirm != expected) {
            throw DatapipelinesException(
                code = VisualizationErrorCodes.VERSION_CONFLICT,
                message = "Type $expected to confirm — the typed confirm did not match.",
                details = mapOf("expected" to expected),
            )
        }
    }

    companion object {
        const val OK_RELEASED = "released"
        const val OK_RELEASED_WITH_TEMPLATES = "released_with_templates"
        const val OK_DISCARDED = "discarded"
        const val OK_RESTORED = "restored"
        const val OK_SWITCHED = "visualization_switched"
        const val OK_DRAFT_PURGED = "visualization_draft_purged"
        const val CATALOG_PURGED = "/visualizations?ok=visualization_purged"

        /**
         * The redirect's tab: the dialog's `from`, admitted only when it names a workspace tab (the CLOSED enum is the
         * whitelist); anything else is Versions, where every verb lives. The input is never echoed into the URL —
         * the enum's own wire value is.
         */
        fun origin(from: String?): VisualizationWorkspaceModel.Tab =
            VisualizationWorkspaceModel.Tab.entries.firstOrNull { it.wire == from } ?: VisualizationWorkspaceModel.Tab.VERSIONS

        /** `/visualizations/{id}?tab=<whitelisted>&ok=<constant>` — built from the path UUID and constants only. */
        fun workspaceTab(
            id: UUID,
            from: String?,
            ok: String,
        ): String = "/visualizations/$id?tab=${origin(from).wire}&ok=$ok"
    }
}
