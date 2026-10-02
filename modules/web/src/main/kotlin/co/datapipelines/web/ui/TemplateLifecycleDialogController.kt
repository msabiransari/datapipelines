package co.datapipelines.web.ui

import co.datapipelines.auth.AuditEventSink
import co.datapipelines.auth.Permission
import co.datapipelines.auth.RequiredScope
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.templates.TemplateRepository
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.web.api.currentPrincipal
import co.datapipelines.web.pipelines.LifecycleVerbs
import co.datapipelines.web.templates.TemplateReleaseService
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.util.UriComponentsBuilder

/**
 * The template twin of [PipelineLifecycleDialogController] (ui-screens §4.3d/§4.6, 102):
 * every dialog is addressed by NAME in the query (§9.6 — a `%2F` in a path segment is refused
 * below routing, which is the whole reason the templates partial routes are query-parameter
 * surface), every POST calls [TemplateReleaseService] — the service 101 wired — and a success
 * answers `HX-Redirect` with a layout flash (§4.3d's editor leg, the shape the #395 ruling
 * gave the pipelines twin).
 *
 * Since #398 the dialogs have ONE page: the template workspace. The explorer's Shape A leg
 * (the detail-pane re-render and its `lifecycle-changed` badge rewrite) is gone with the
 * page the pane lived in — every success redirects onto the workspace's Versions tab (or,
 * when the verb removed the whole template, onto the catalog), where the flash toast lands
 * and the new version set is on the page. The dialogs' GETs all carry the optional `from`
 * (the wire value the workspace sends is `editor`, the surface this controller's redirects
 * serve); the POSTs accept it and answer the same redirect for any value, because there is
 * no second surface left to render a result into. The dialogs' pre-read guards are
 * unchanged: a refused branch opens with no button, and the POST re-runs the guard.
 *
 * There is deliberately no Switch dialog: templates are pinned by exact version, so there is
 * no served pointer a human would roll back (§4.6 records the absence).
 */
@Controller
class TemplateLifecycleDialogController(
    private val releases: TemplateReleaseService,
    private val templates: TemplateRepository,
    private val dialogs: TemplateLifecycleDialogModel,
    private val audit: AuditEventSink,
) {
    // ------------------------------------------------------------------ release

    @GetMapping("/partials/templates/lifecycle/release")
    @RequiredScope(Permission.TEMPLATE_RELEASE)
    fun releaseDialog(
        model: Model,
        @RequestParam name: String,
        @RequestParam(required = false) from: String?,
    ): String {
        LifecycleVerbs.requireSession()
        model.addAttribute("dlg", dialogs.release(currentPrincipal().requireWorkspace().id, name))
        model.addAttribute("from", from ?: FROM_WORKSPACE)
        // 177 §D.8: the dialog's verb renders inside a role guard like every other verb — the route
        // already refuses the wrong role; the markup now says so too, and the exemption list is empty.
        RoleModel.stamp(model)
        return "partials/template-lifecycle-release"
    }

    @PostMapping("/partials/templates/lifecycle/release")
    @RequiredScope(Permission.TEMPLATE_RELEASE)
    fun release(
        @RequestParam name: String,
    ): ResponseEntity<String> {
        val principal = LifecycleVerbs.requireSession()
        val workspaceId = principal.requireWorkspace().id
        // The hash the DIALOG read (§4.2); a stale hash is the service's version.conflict.
        val draft =
            templates.findDraftDetail(workspaceId, name)
                ?: throw DatapipelinesException(
                    code = PipelineErrorCodes.Template.VERSION_NOT_DRAFT,
                    message = "This template has no draft to release.",
                    details = mapOf("template_id" to name),
                )
        val released = releases.release(workspaceId, name, draft.bodyHash, principal.userId)
        // T187 — the release is the D4 human step; it is audited on every surface that offers it.
        LifecycleVerbs.audit(
            audit,
            LifecycleVerbs.TEMPLATE_AUDIT_VERSION_RELEASED,
            principal,
            workspaceId,
            LifecycleVerbs.templateReleaseDetails(principal, released.detail.templateId, released.detail.version),
        )
        return redirect(versionsTab(name, "released"))
    }

    // ------------------------------------------------------------------ purge draft (the versioned verb)

    @GetMapping("/partials/templates/lifecycle/purge")
    @RequiredScope(Permission.TEMPLATE_VERSION_MANAGE)
    fun purgeDialog(
        model: Model,
        @RequestParam name: String,
        @RequestParam version: Int,
        @RequestParam(required = false) from: String?,
    ): String {
        LifecycleVerbs.requireSession()
        model.addAttribute("dlg", dialogs.purge(currentPrincipal().requireWorkspace().id, name, version))
        model.addAttribute("from", from ?: FROM_WORKSPACE)
        RoleModel.stamp(model)
        return "partials/template-lifecycle-purge"
    }

    @PostMapping("/partials/templates/lifecycle/purge")
    @RequiredScope(Permission.TEMPLATE_VERSION_MANAGE)
    fun purge(
        @RequestParam name: String,
        @RequestParam version: Int,
        @RequestParam confirm: String?,
    ): ResponseEntity<String> {
        val principal = LifecycleVerbs.requireSession()
        val workspaceId = principal.requireWorkspace().id
        requireTypedConfirm(confirm, "v$version")
        val soleDraft = templates.listVersions(workspaceId, name).size == 1
        releases.purgeVersion(workspaceId, name, version)
        LifecycleVerbs.audit(
            audit,
            LifecycleVerbs.TEMPLATE_AUDIT_VERSION_PURGED,
            principal,
            workspaceId,
            mapOf("template_id" to name, "version" to version, "scope" to if (soleDraft) "entity" else "version"),
        )
        return when {
            soleDraft -> {
                // The sole draft's purge took the TEMPLATE with it (§5.4): the tree must lose
                // the leaf, so the page navigates to the CATALOG and the flash carries the toast.
                redirect("/templates?ok=template_purged")
            }

            else -> redirect(versionsTab(name, "draft_purged"))
        }
    }

    // ------------------------------------------------------------------ discard release

    @GetMapping("/partials/templates/lifecycle/discard")
    @RequiredScope(Permission.TEMPLATE_VERSION_MANAGE)
    fun discardDialog(
        model: Model,
        @RequestParam name: String,
        @RequestParam version: Int,
        @RequestParam(required = false) from: String?,
    ): String {
        LifecycleVerbs.requireSession()
        model.addAttribute("dlg", dialogs.discard(currentPrincipal().requireWorkspace().id, name, version))
        // #395's shape — the `from` the opener carries decides the markup, and every GET
        // accepts it; gone is the hard-set explorer value the #395 ruling called a defect.
        model.addAttribute("from", from ?: FROM_WORKSPACE)
        RoleModel.stamp(model)
        return "partials/template-lifecycle-discard"
    }

    @PostMapping("/partials/templates/lifecycle/discard")
    @RequiredScope(Permission.TEMPLATE_VERSION_MANAGE)
    fun discard(
        @RequestParam name: String,
        @RequestParam version: Int,
    ): ResponseEntity<String> {
        val principal = LifecycleVerbs.requireSession()
        val workspaceId = principal.requireWorkspace().id
        releases.discardVersion(workspaceId, name, version, principal.userId)
        LifecycleVerbs.audit(
            audit,
            LifecycleVerbs.TEMPLATE_AUDIT_VERSION_DISCARDED,
            principal,
            workspaceId,
            mapOf("template_id" to name, "version" to version),
        )
        return redirect(versionsTab(name, "discarded"))
    }

    // ------------------------------------------------------------------ restore

    @GetMapping("/partials/templates/lifecycle/restore")
    @RequiredScope(Permission.TEMPLATE_VERSION_MANAGE)
    fun restoreDialog(
        model: Model,
        @RequestParam name: String,
        @RequestParam version: Int,
        @RequestParam(required = false) from: String?,
    ): String {
        LifecycleVerbs.requireSession()
        model.addAttribute("dlg", dialogs.restore(currentPrincipal().requireWorkspace().id, name, version))
        model.addAttribute("from", from ?: FROM_WORKSPACE)
        RoleModel.stamp(model)
        return "partials/template-lifecycle-restore"
    }

    @PostMapping("/partials/templates/lifecycle/restore")
    @RequiredScope(Permission.TEMPLATE_VERSION_MANAGE)
    fun restore(
        @RequestParam name: String,
        @RequestParam version: Int,
    ): ResponseEntity<String> {
        val principal = LifecycleVerbs.requireSession()
        val workspaceId = principal.requireWorkspace().id
        releases.restoreVersion(workspaceId, name, version)
        LifecycleVerbs.audit(
            audit,
            LifecycleVerbs.TEMPLATE_AUDIT_VERSION_RESTORED,
            principal,
            workspaceId,
            mapOf("template_id" to name, "version" to version),
        )
        return redirect(versionsTab(name, "restored"))
    }

    // ------------------------------------------------------------------ purge entity

    @GetMapping("/partials/templates/lifecycle/purge-entity")
    @RequiredScope(Permission.TEMPLATE_DELETE)
    fun purgeEntityDialog(
        model: Model,
        @RequestParam name: String,
        @RequestParam(required = false) from: String?,
    ): String {
        LifecycleVerbs.requireSession()
        model.addAttribute("dlg", dialogs.purgeEntity(currentPrincipal().requireWorkspace().id, name))
        model.addAttribute("from", from ?: FROM_WORKSPACE)
        RoleModel.stamp(model)
        return "partials/template-lifecycle-purge-entity"
    }

    @PostMapping("/partials/templates/lifecycle/purge-entity")
    @RequiredScope(Permission.TEMPLATE_DELETE)
    fun purgeEntity(
        @RequestParam name: String,
        @RequestParam confirm: String?,
    ): ResponseEntity<String> {
        val principal = LifecycleVerbs.requireSession()
        val workspaceId = principal.requireWorkspace().id
        // The typed confirm names the TEMPLATE (§9.6 keeps the name out of paths; the dialog
        // showed it as the thing to type).
        requireTypedConfirm(confirm, name)
        releases.purgeEntity(workspaceId, name)
        LifecycleVerbs.audit(
            audit,
            LifecycleVerbs.TEMPLATE_AUDIT_ENTITY_PURGED,
            principal,
            workspaceId,
            mapOf("template_id" to name),
        )
        return redirect("/templates?ok=template_purged")
    }

    // ------------------------------------------------------------------ shared shapes

    /**
     * The Versions tab's landing URL — the canonical workspace with the tab the verbs live
     * on and the layout flash's code. The name's SEGMENTS build the path (the same rule the
     * editor redirect uses); the tab and the code are the controller's own wire words, so the
     * built URI carries no free input but the grammar-checked name.
     */
    private fun versionsTab(
        name: String,
        ok: String,
    ): String =
        UriComponentsBuilder
            .fromPath("/templates")
            .pathSegment(*name.split("/").toTypedArray())
            .queryParam("tab", TemplateWorkspaceController.TemplateWorkspaceTab.VERSIONS.wire)
            .queryParam("ok", ok)
            .build()
            .toUriString()

    private fun redirect(url: String): ResponseEntity<String> = ResponseEntity.ok().header("HX-Redirect", url).body("")

    /** The typed-confirm guard — BEFORE the service, so a mismatch never reaches the verb. */
    private fun requireTypedConfirm(
        confirm: String?,
        expected: String,
    ) {
        if (confirm != expected) {
            throw DatapipelinesException(
                code = PipelineErrorCodes.Template.VERSION_CONFIRM_MISMATCH,
                message = "Type $expected to confirm — the typed confirm did not match.",
                details = mapOf("expected" to expected),
            )
        }
    }

    private companion object {
        /**
         * The `from` wire value the workspace's dialogs send — the successor of the old
         * editor surface, whose value (`editor`) the routes keep accepting. An absent value
         * resolves here too: the dialogs' one page is the workspace.
         */
        const val FROM_WORKSPACE = "editor"
    }
}
