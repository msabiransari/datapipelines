package co.datapipelines.web.ui

import co.datapipelines.auth.AuditEventSink
import co.datapipelines.auth.Permission
import co.datapipelines.auth.RequiredScope
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineReleaseService
import co.datapipelines.pipeline.PipelineService
import co.datapipelines.pipeline.ReadLens
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.web.api.currentPrincipal
import co.datapipelines.web.pipelines.LifecycleVerbs
import co.datapipelines.web.schedules.PrincipalTargetViewer
import jakarta.servlet.http.HttpSession
import org.springframework.http.ResponseEntity
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import java.util.UUID

/**
 * The pipeline lifecycle verbs' dialog family (ui-screens §4.3d, 102): every GET opens a
 * confirm partial into `#pe-dialog` (the pipeline workspace's container — the `from=editor`
 * surface; since #401 there is no other), every POST calls the SAME service 101 wired —
 * never the REST controllers over HTTP, never a second copy of a guard (the guard re-runs
 * under the POST; the dialog only decides what to SAY). Session-only like the verbs
 * themselves (§4.18); an API key is refused `auth.session.required` before anything is read.
 *
 * #401 removed the explorer legs: a rail leaf navigates to the workspace and no page renders
 * `#pipeline-detail`, so the Shape A answer (the re-rendered pane, `HX-Trigger:
 * lifecycle-changed`) has no reader. EVERY success answers `HX-Redirect` (the workspace's
 * draft state is document-wide; §4.4 records why the reload stays) — the layout's flash bin
 * renders the toast after it lands, and the release that cascaded names its templates from
 * the server's own list ([ReleaseFlash], #407), never from the client. Refusals ride the
 * `UiExceptionHandler` Shape C path with their real 4xx — including the typed-confirm
 * mismatch, which is checked BEFORE the service runs so a mismatched dialog can never purge
 * anything.
 */
@org.springframework.stereotype.Controller
class PipelineLifecycleDialogController(
    private val pipelines: PipelineService,
    private val dialogs: PipelineLifecycleDialogModel,
    private val audit: AuditEventSink,
    /** #407 — the one-shot, session-held cascade names the workspace's release flash renders. */
    private val releaseFlash: ReleaseFlash,
) {
    // ------------------------------------------------------------------ release

    @GetMapping("/partials/pipelines/{id}/lifecycle/release")
    @RequiredScope(Permission.PIPELINE_RELEASE)
    fun releaseDialog(
        model: Model,
        @PathVariable id: UUID,
    ): String {
        LifecycleVerbs.requireSession()
        model.addAttribute("dlg", dialogs.release(currentPrincipal().requireWorkspace().id, id))
        // #401 — the editor surface is the only one; the `from` the Versions tab's
        // links still send is ignored, and the dialog always renders the editor shape.
        model.addAttribute("from", FROM_EDITOR)
        // 177 §D.8: the dialog's verb renders inside a role guard like every other verb — the route
        // already refuses the wrong role; the markup now says so too, and the exemption list is empty.
        RoleModel.stamp(model)
        return "partials/pipeline-lifecycle-release"
    }

    @PostMapping("/partials/pipelines/{id}/lifecycle/release")
    @RequiredScope(Permission.PIPELINE_RELEASE)
    fun release(
        session: HttpSession,
        @PathVariable id: UUID,
        @RequestParam bodyHash: String,
        @RequestParam(required = false) overrideChecksReason: String?,
        @RequestParam(required = false, defaultValue = "false") releasePinnedTemplates: Boolean,
    ): ResponseEntity<String> {
        val principal = LifecycleVerbs.requireSession()
        val workspaceId = principal.requireWorkspace().id
        // #416 — [bodyHash] is the hash the DIALOG read (§4.2: you release what you tested). The
        // draft is read here only to refuse the no-draft case; its CURRENT hash is never the one
        // released, so a draft that changed after the dialog opened is a stale hash and the
        // service answers pipeline.version.conflict.
        pipelines.findDraft(workspaceId, ReadLens.Everything, id)
            ?: throw DatapipelinesException(
                code = PipelineErrorCodes.Versioning.NOT_DRAFT,
                message = "This pipeline has no draft to release.",
                details = mapOf("pipeline_id" to id.toString()),
            )
        // 140: a failing check run refuses with pipeline.check.failed unless the dialog's
        // override disclosure supplied the reason — it arrives audited on the release event.
        // 142: the consent checkbox ("Also release these N draft templates") posts
        // releasePinnedTemplates; without it a DRAFT pin refuses template_not_released as
        // it always has, and the server — never the checkbox — is the guard.
        val released =
            pipelines.release(workspaceId, id, bodyHash, principal.userId, overrideChecksReason, releasePinnedTemplates)
        // T187 — the release is the D4 human step; it is audited on every surface that offers
        // it — one event per cascaded template first, then the pipeline's (142).
        LifecycleVerbs.auditRelease(audit, principal, workspaceId, id, released)
        // #407 — the workspace flash names the cascade, derived HERE from the release's own
        // list: held once for this actor's session, rendered by the next GET of THIS pipeline
        // that carries `ok=released_with_templates`, never trusted from the client.
        if (released.templatesReleased.isNotEmpty()) {
            releaseFlash.hold(session, id, principal.userId, released.templatesReleased)
        }
        // #348 merge: the canonical workspace route — `/pipelines/{id}/editor` is a compatibility
        // redirect now and forwards only `version` and `tab`, so an `ok` sent there was dropped and
        // the release toast never rendered (PipelineLifecycleDialogControllerTest pins the target).
        return redirect("/pipelines/$id?ok=" + if (released.templatesReleased.isEmpty()) "released" else "released_with_templates")
    }

    // ------------------------------------------------------------------ purge draft (the versioned verb)

    @GetMapping("/partials/pipelines/{id}/lifecycle/purge")
    @RequiredScope(Permission.PIPELINE_VERSION_MANAGE)
    fun purgeDialog(
        model: Model,
        @PathVariable id: UUID,
        @RequestParam version: Int,
    ): String {
        LifecycleVerbs.requireSession()
        model.addAttribute("dlg", dialogs.purge(currentPrincipal().requireWorkspace().id, id, version))
        // #401 — the editor surface is the only one; the `from` the Versions tab's
        // links still send is ignored, and the dialog always renders the editor shape.
        model.addAttribute("from", FROM_EDITOR)
        // 177 §D.8: the dialog's verb renders inside a role guard like every other verb — the route
        // already refuses the wrong role; the markup now says so too, and the exemption list is empty.
        RoleModel.stamp(model)
        return "partials/pipeline-lifecycle-purge"
    }

    @PostMapping("/partials/pipelines/{id}/lifecycle/purge")
    @RequiredScope(Permission.PIPELINE_VERSION_MANAGE)
    fun purge(
        @PathVariable id: UUID,
        @RequestParam version: Int,
        @RequestParam confirm: String?,
    ): ResponseEntity<String> {
        val principal = LifecycleVerbs.requireSession()
        val workspaceId = principal.requireWorkspace().id
        requireTypedConfirm(confirm, "v$version")
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
        // An entity purge reloads the catalog (the tree loses the leaf); a version purge
        // reloads the workspace (the layout's flash names what happened).
        return if (purged is PipelineReleaseService.Purged.Entity) {
            redirect("/pipelines?ok=entity_purged")
        } else {
            redirect("/pipelines/$id?ok=draft_purged")
        }
    }

    // ------------------------------------------------------------------ discard release

    @GetMapping("/partials/pipelines/{id}/lifecycle/discard")
    @RequiredScope(Permission.PIPELINE_VERSION_MANAGE)
    fun discardDialog(
        model: Model,
        @PathVariable id: UUID,
        @RequestParam version: Int,
    ): String {
        LifecycleVerbs.requireSession()
        // #273 — the dialog's schedules evidence is read through the caller's lens (the Usage
        // tab's PrincipalTargetViewer): a promoter's view is R3's, never a second answer.
        model.addAttribute(
            "dlg",
            dialogs.discard(currentPrincipal().requireWorkspace().id, id, version, PrincipalTargetViewer(currentPrincipal())),
        )
        // #401 — the editor surface is the only one; the `from` the Versions tab's
        // links still send is ignored, and the dialog always renders the editor shape.
        model.addAttribute("from", FROM_EDITOR)
        // 177 §D.8: the dialog's verb renders inside a role guard like every other verb — the route
        // already refuses the wrong role; the markup now says so too, and the exemption list is empty.
        RoleModel.stamp(model)
        return "partials/pipeline-lifecycle-discard"
    }

    @PostMapping("/partials/pipelines/{id}/lifecycle/discard")
    @RequiredScope(Permission.PIPELINE_VERSION_MANAGE)
    fun discard(
        @PathVariable id: UUID,
        @RequestParam version: Int,
    ): ResponseEntity<String> {
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
        // #395: the reload lands on the Versions tab with a flash; the tab shows the pointer
        // the service left (§3.4's outcome is the tab's `current` mark, never a client guess).
        return redirect(versionsTab(id, "discarded"))
    }

    // ------------------------------------------------------------------ restore

    @GetMapping("/partials/pipelines/{id}/lifecycle/restore")
    @RequiredScope(Permission.PIPELINE_VERSION_MANAGE)
    fun restoreDialog(
        model: Model,
        @PathVariable id: UUID,
        @RequestParam version: Int,
    ): String {
        LifecycleVerbs.requireSession()
        model.addAttribute("dlg", dialogs.restore(currentPrincipal().requireWorkspace().id, id, version))
        // #401 — the editor surface is the only one; the `from` the Versions tab's
        // links still send is ignored, and the dialog always renders the editor shape.
        model.addAttribute("from", FROM_EDITOR)
        // 177 §D.8: the dialog's verb renders inside a role guard like every other verb — the route
        // already refuses the wrong role; the markup now says so too, and the exemption list is empty.
        RoleModel.stamp(model)
        return "partials/pipeline-lifecycle-restore"
    }

    @PostMapping("/partials/pipelines/{id}/lifecycle/restore")
    @RequiredScope(Permission.PIPELINE_VERSION_MANAGE)
    fun restore(
        @PathVariable id: UUID,
        @RequestParam version: Int,
    ): ResponseEntity<String> {
        val principal = LifecycleVerbs.requireSession()
        val workspaceId = principal.requireWorkspace().id
        val record = pipelines.restoreVersion(workspaceId, id, version)
        LifecycleVerbs.audit(
            audit,
            LifecycleVerbs.AUDIT_VERSION_RESTORED,
            principal,
            workspaceId,
            mapOf("pipeline_id" to id.toString(), "version" to version, "current_version_after" to record.currentVersion),
        )
        return redirect(versionsTab(id, "restored")) // #395, as discard
    }

    // ------------------------------------------------------------------ purge entity

    @GetMapping("/partials/pipelines/{id}/lifecycle/purge-entity")
    @RequiredScope(Permission.PIPELINE_DELETE)
    fun purgeEntityDialog(
        model: Model,
        @PathVariable id: UUID,
    ): String {
        LifecycleVerbs.requireSession()
        model.addAttribute("dlg", dialogs.purgeEntity(currentPrincipal().requireWorkspace().id, id))
        // #401 — the editor surface is the only one; the `from` the Versions tab's
        // links still send is ignored, and the dialog always renders the editor shape.
        model.addAttribute("from", FROM_EDITOR)
        // 177 §D.8: the dialog's verb renders inside a role guard like every other verb — the route
        // already refuses the wrong role; the markup now says so too, and the exemption list is empty.
        RoleModel.stamp(model)
        return "partials/pipeline-lifecycle-purge-entity"
    }

    @PostMapping("/partials/pipelines/{id}/lifecycle/purge-entity")
    @RequiredScope(Permission.PIPELINE_DELETE)
    fun purgeEntity(
        @PathVariable id: UUID,
        @RequestParam("include_exclusive", required = false, defaultValue = "false") includeExclusive: Boolean,
        @RequestParam confirm: String?,
    ): ResponseEntity<String> {
        val principal = LifecycleVerbs.requireSession()
        val workspaceId = principal.requireWorkspace().id
        // The typed confirm names the PIPELINE (its name, which the dialog showed); the name
        // is read fresh — a rename between dialog and POST refuses rather than guesses.
        val record =
            pipelines.findRecord(workspaceId, ReadLens.Everything, id)
                ?: throw DatapipelinesException(
                    code = PipelineErrorCodes.Validation.PIPELINE_NOT_FOUND,
                    message = "No pipeline with id '$id' in this workspace.",
                    details = mapOf("pipeline_id" to id.toString()),
                )
        requireTypedConfirm(confirm, record.name)
        val result = pipelines.purgeEntity(workspaceId, id, includeExclusiveDraftTemplates = includeExclusive)
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
                "kept_draft_templates" to result.keptDraftTemplates.map { it.templateId },
            ),
        )
        // The row is gone: redirect so the TREE loses the leaf (the flash carries the toast).
        return redirect("/pipelines?ok=entity_purged")
    }

    // ------------------------------------------------------------------ switch

    @GetMapping("/partials/pipelines/{id}/lifecycle/switch")
    @RequiredScope(Permission.PIPELINE_SWITCH_VERSION)
    fun switchDialog(
        model: Model,
        @PathVariable id: UUID,
        @RequestParam(required = false) version: Int?,
    ): String {
        LifecycleVerbs.requireSession()
        model.addAttribute("dlg", dialogs.switch(currentPrincipal().requireWorkspace().id, id))
        model.addAttribute("preselect", version)
        // #401 — the editor surface is the only one; the `from` the Versions tab's
        // links still send is ignored, and the dialog always renders the editor shape.
        model.addAttribute("from", FROM_EDITOR)
        // 177 §D.8: the dialog's verb renders inside a role guard like every other verb — the route
        // already refuses the wrong role; the markup now says so too, and the exemption list is empty.
        RoleModel.stamp(model)
        return "partials/pipeline-lifecycle-switch"
    }

    @PostMapping("/partials/pipelines/{id}/lifecycle/switch")
    @RequiredScope(Permission.PIPELINE_SWITCH_VERSION)
    fun switchCurrent(
        @PathVariable id: UUID,
        @RequestParam version: Int,
    ): ResponseEntity<String> {
        val principal = LifecycleVerbs.requireSession()
        val workspaceId = principal.requireWorkspace().id
        val record = pipelines.switchCurrent(workspaceId, id, version)
        LifecycleVerbs.audit(
            audit,
            LifecycleVerbs.AUDIT_CURRENT_SWITCHED,
            principal,
            workspaceId,
            mapOf("pipeline_id" to id.toString(), "to" to record.currentVersion),
        )
        return redirect(versionsTab(id, "switched")) // #395, as discard
    }

    // ------------------------------------------------------------------ shared shapes

    /**
     * The editor surface's shape (TemplateEditorController's openWorkingVersion pattern): an
     * empty 200 carrying `HX-Redirect`, so htmx navigates the whole page and the layout's
     * flash bin renders the toast after it lands. #401 made this EVERY surface's shape — the
     * explorer detail the Shape A answer re-rendered has no page.
     */
    private fun redirect(url: String): ResponseEntity<String> = ResponseEntity.ok().header("HX-Redirect", url).body("")

    /** #395 — the workspace's Versions tab with the layout flash [ok] names (default.html's bin). */
    private fun versionsTab(
        id: UUID,
        ok: String,
    ): String = "/pipelines/$id?tab=versions&ok=$ok"

    /** The typed-confirm guard — BEFORE the service, so a mismatch never reaches the verb. */
    private fun requireTypedConfirm(
        confirm: String?,
        expected: String,
    ) {
        if (confirm != expected) {
            throw DatapipelinesException(
                code = PipelineErrorCodes.Versioning.CONFIRM_MISMATCH,
                message = "Type $expected to confirm — the typed confirm did not match.",
                details = mapOf("expected" to expected),
            )
        }
    }

    private companion object {
        const val FROM_EDITOR = "editor"
    }
}
