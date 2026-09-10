package co.datapipelines.web.ui

import co.datapipelines.auth.AuditEventSink
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.RequiredScope
import co.datapipelines.auth.ScopeMatrix
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineReleaseService
import co.datapipelines.pipeline.PipelineService
import co.datapipelines.typesystem.DatapipelinesException
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
 * The lifecycle verbs' dialog family (ui-screens §4.3d, 102): every GET opens a confirm
 * partial into `#px-dialog` (the explorer) or `#pe-dialog` (the editor, `from=editor`), every
 * POST calls the SAME service 101 wired — never the REST controllers over HTTP, never a
 * second copy of a guard (the guard re-runs under the POST; the dialog only decides what to
 * SAY). Session-only like the verbs themselves (§4.18); an API key is refused
 * `auth.session.required` before anything is read.
 *
 * Success is Shape A for the explorer verbs that keep the entity: the re-rendered detail
 * region (106's model, not a copy) with the `toast-oob` spliced in and `HX-Trigger:
 * lifecycle-changed` carrying the new working-version facts for the tree badge. A verb that
 * removes the ROW (the entity purge) answers `HX-Redirect` back to the explorer with a flash
 * toast — the tree must lose the leaf, and this controller may never touch the tree. The
 * editor surface always answers `HX-Redirect` (the editor's draft state is document-wide;
 * §4.4 records why the reload stays). Refusals ride the `UiExceptionHandler` Shape C path
 * with their real 4xx — including the typed-confirm mismatch, which is checked BEFORE the
 * service runs so a mismatched dialog can never purge anything.
 */
@org.springframework.stereotype.Controller
class PipelineLifecycleDialogController(
    private val pipelines: PipelineService,
    private val dialogs: PipelineLifecycleDialogModel,
    private val browse: PipelineBrowseModel,
    private val audit: AuditEventSink,
) {
    // ------------------------------------------------------------------ release

    @GetMapping("/partials/pipelines/{id}/lifecycle/release")
    @RequiredScope(ScopeMatrix.RestOperation.RELEASE_VERSION)
    fun releaseDialog(
        model: Model,
        @PathVariable id: UUID,
        @RequestParam(required = false) from: String?,
    ): String {
        LifecycleVerbs.requireSession()
        model.addAttribute("dlg", dialogs.release(currentPrincipal().requireWorkspace().id, id))
        model.addAttribute("from", from ?: FROM_EXPLORER)
        return "partials/pipeline-lifecycle-release"
    }

    @PostMapping("/partials/pipelines/{id}/lifecycle/release")
    @RequiredScope(ScopeMatrix.RestOperation.RELEASE_VERSION)
    fun release(
        model: Model,
        response: HttpServletResponse,
        @PathVariable id: UUID,
        @RequestParam(required = false) from: String?,
    ): Any {
        val principal = LifecycleVerbs.requireSession()
        val workspaceId = principal.requireWorkspace().id
        // The hash the DIALOG read (§4.2: you release what you tested); a draft that changed
        // in between is a stale hash and the service answers pipeline.version.conflict.
        val draft =
            pipelines.findDraft(workspaceId, id)
                ?: throw DatapipelinesException(
                    code = PipelineErrorCodes.Versioning.NOT_DRAFT,
                    message = "This pipeline has no draft to release.",
                    details = mapOf("pipeline_id" to id.toString()),
                )
        // No audit here, exactly like the REST release: 101 audited the discard family;
        // release stayed the editor's plain verb, and the dialog does not widen the event set.
        val released = pipelines.release(workspaceId, id, draft.bodyHash, principal.userId)
        return if (from == FROM_EDITOR) {
            redirect("/pipelines/$id/editor?ok=released")
        } else {
            applied(
                model,
                response,
                workspaceId,
                id,
                "Released v${released.version.version}",
                "v${released.version.version} is the current version now, and it is locked.",
            )
        }
    }

    // ------------------------------------------------------------------ purge draft (the versioned verb)

    @GetMapping("/partials/pipelines/{id}/lifecycle/purge")
    @RequiredScope(ScopeMatrix.RestOperation.MUTATE_PIPELINES_TEMPLATES)
    fun purgeDialog(
        model: Model,
        @PathVariable id: UUID,
        @RequestParam version: Int,
        @RequestParam(required = false) from: String?,
    ): String {
        LifecycleVerbs.requireSession()
        model.addAttribute("dlg", dialogs.purge(currentPrincipal().requireWorkspace().id, id, version))
        model.addAttribute("from", from ?: FROM_EXPLORER)
        return "partials/pipeline-lifecycle-purge"
    }

    @PostMapping("/partials/pipelines/{id}/lifecycle/purge")
    @RequiredScope(ScopeMatrix.RestOperation.MUTATE_PIPELINES_TEMPLATES)
    fun purge(
        model: Model,
        response: HttpServletResponse,
        @PathVariable id: UUID,
        @RequestParam version: Int,
        @RequestParam confirm: String?,
        @RequestParam(required = false) from: String?,
    ): Any {
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
        val runs = purged.executionsDeleted
        return when {
            purged is PipelineReleaseService.Purged.Entity -> {
                redirect("/pipelines?ok=entity_purged")
            }

            from == FROM_EDITOR -> {
                redirect("/pipelines/$id/editor?ok=draft_purged")
            }

            else -> {
                applied(
                    model,
                    response,
                    workspaceId,
                    id,
                    "Purged v$version",
                    if (runs >
                        0
                    ) {
                        "Its $runs run${if (runs == 1) "" else "s"} went with it. This cannot be undone."
                    } else {
                        "The draft is gone. This cannot be undone."
                    },
                )
            }
        }
    }

    // ------------------------------------------------------------------ discard release

    @GetMapping("/partials/pipelines/{id}/lifecycle/discard")
    @RequiredScope(ScopeMatrix.RestOperation.MUTATE_PIPELINES_TEMPLATES)
    fun discardDialog(
        model: Model,
        @PathVariable id: UUID,
        @RequestParam version: Int,
        @RequestParam(required = false) from: String?,
    ): String {
        LifecycleVerbs.requireSession()
        model.addAttribute("dlg", dialogs.discard(currentPrincipal().requireWorkspace().id, id, version))
        model.addAttribute("from", from ?: FROM_EXPLORER)
        return "partials/pipeline-lifecycle-discard"
    }

    @PostMapping("/partials/pipelines/{id}/lifecycle/discard")
    @RequiredScope(ScopeMatrix.RestOperation.MUTATE_PIPELINES_TEMPLATES)
    fun discard(
        model: Model,
        response: HttpServletResponse,
        @PathVariable id: UUID,
        @RequestParam version: Int,
    ): Any {
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
        // §3.4's pointer outcome, from the SERVICE's result — never the dialog's guess.
        val pointer = result.recordAfter.currentVersion
        val outcome =
            if (pointer != null) {
                "v$pointer is current now."
            } else {
                "Nothing eligible remains — the pipeline has no current version; its endpoints answer 503."
            }
        return applied(model, response, workspaceId, id, "Discarded v$version", outcome)
    }

    // ------------------------------------------------------------------ restore

    @GetMapping("/partials/pipelines/{id}/lifecycle/restore")
    @RequiredScope(ScopeMatrix.RestOperation.MUTATE_PIPELINES_TEMPLATES)
    fun restoreDialog(
        model: Model,
        @PathVariable id: UUID,
        @RequestParam version: Int,
        @RequestParam(required = false) from: String?,
    ): String {
        LifecycleVerbs.requireSession()
        model.addAttribute("dlg", dialogs.restore(currentPrincipal().requireWorkspace().id, id, version))
        model.addAttribute("from", from ?: FROM_EXPLORER)
        return "partials/pipeline-lifecycle-restore"
    }

    @PostMapping("/partials/pipelines/{id}/lifecycle/restore")
    @RequiredScope(ScopeMatrix.RestOperation.MUTATE_PIPELINES_TEMPLATES)
    fun restore(
        model: Model,
        response: HttpServletResponse,
        @PathVariable id: UUID,
        @RequestParam version: Int,
    ): Any {
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
        val moved = record.currentVersion == version
        return applied(
            model,
            response,
            workspaceId,
            id,
            "Restored v$version",
            if (moved) "v$version is current now." else "v$version is released again; the pointer stays at v${record.currentVersion}.",
        )
    }

    // ------------------------------------------------------------------ purge entity

    @GetMapping("/partials/pipelines/{id}/lifecycle/purge-entity")
    @RequiredScope(ScopeMatrix.RestOperation.MUTATE_PIPELINES_TEMPLATES)
    fun purgeEntityDialog(
        model: Model,
        @PathVariable id: UUID,
        @RequestParam(required = false) from: String?,
    ): String {
        LifecycleVerbs.requireSession()
        model.addAttribute("dlg", dialogs.purgeEntity(currentPrincipal().requireWorkspace().id, id))
        model.addAttribute("from", from ?: FROM_EXPLORER)
        return "partials/pipeline-lifecycle-purge-entity"
    }

    @PostMapping("/partials/pipelines/{id}/lifecycle/purge-entity")
    @RequiredScope(ScopeMatrix.RestOperation.MUTATE_PIPELINES_TEMPLATES)
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
            pipelines.findRecord(workspaceId, id)
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
            ),
        )
        // The row is gone: redirect so the TREE loses the leaf (the flash carries the toast).
        return redirect("/pipelines?ok=entity_purged")
    }

    // ------------------------------------------------------------------ switch

    @GetMapping("/partials/pipelines/{id}/lifecycle/switch")
    @RequiredScope(ScopeMatrix.RestOperation.SWITCH_SERVED_VERSION)
    fun switchDialog(
        model: Model,
        @PathVariable id: UUID,
        @RequestParam(required = false) version: Int?,
    ): String {
        LifecycleVerbs.requireSession()
        model.addAttribute("dlg", dialogs.switch(currentPrincipal().requireWorkspace().id, id))
        model.addAttribute("preselect", version)
        model.addAttribute("from", FROM_EXPLORER)
        return "partials/pipeline-lifecycle-switch"
    }

    @PostMapping("/partials/pipelines/{id}/lifecycle/switch")
    @RequiredScope(ScopeMatrix.RestOperation.SWITCH_SERVED_VERSION)
    fun switchCurrent(
        model: Model,
        response: HttpServletResponse,
        @PathVariable id: UUID,
        @RequestParam version: Int,
    ): String {
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
        return applied(
            model,
            response,
            workspaceId,
            id,
            "Switched to v$version",
            "Endpoints published on this pipeline serve v$version after this.",
        )
    }

    // ------------------------------------------------------------------ shared shapes

    /**
     * Shape A: the re-rendered detail (106's model fills it — no copy), the toast, and the
     * `lifecycle-changed` payload the tree badge refresh reads. All server facts, no guesses.
     */
    private fun applied(
        model: Model,
        response: HttpServletResponse,
        workspaceId: UUID,
        id: UUID,
        toastTitle: String,
        toastMessage: String,
    ): String {
        browse.fillDetail(model, workspaceId, id)
        model.addAttribute("lifecycleToastTitle", toastTitle)
        model.addAttribute("lifecycleToastMessage", toastMessage)
        // The payload's facts: the working version the tree badge shows (§4.3's rule), the
        // draft flag, and the leaf id the badge hangs on.
        val record = pipelines.findRecord(workspaceId, id)
        val draft = pipelines.findDraft(workspaceId, id)
        val working = (draft?.version ?: record?.currentVersion)?.toString() ?: "null"
        response.setHeader(
            "HX-Trigger",
            "{\"lifecycle-changed\":{\"leafId\":\"$id\",\"workingVersion\":$working,\"hasDraft\":${draft != null}}}",
        )
        return APPLIED_VIEW
    }

    /**
     * The editor/entity shape (TemplateEditorController's openWorkingVersion pattern): an
     * empty 200 carrying `HX-Redirect`, so htmx navigates the whole page and the layout's
     * flash bin renders the toast after it lands.
     */
    private fun redirect(url: String): ResponseEntity<String> = ResponseEntity.ok().header("HX-Redirect", url).body("")

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
        const val FROM_EXPLORER = "explorer"
        const val FROM_EDITOR = "editor"

        /** `partials/pipeline-lifecycle-applied` — the Shape A wrapper over 106's detail. */
        const val APPLIED_VIEW = "partials/pipeline-lifecycle-applied"
    }
}
