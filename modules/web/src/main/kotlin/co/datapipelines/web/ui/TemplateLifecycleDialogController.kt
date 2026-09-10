package co.datapipelines.web.ui

import co.datapipelines.auth.AuditEventSink
import co.datapipelines.auth.RequiredScope
import co.datapipelines.auth.ScopeMatrix
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.templates.TemplateRepository
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.web.api.currentPrincipal
import co.datapipelines.web.pipelines.LifecycleVerbs
import co.datapipelines.web.templates.TemplateReleaseService
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.ResponseEntity
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import java.util.UUID

/**
 * The template twin of [PipelineLifecycleDialogController] (ui-screens §4.3d/§4.6, 102):
 * every dialog is addressed by NAME in the query (§9.6 — a `%2F` in a path segment is refused
 * below routing, which is the whole reason the templates partial routes are query-parameter
 * surface), every POST calls [TemplateReleaseService] — the service 101 wired — and the
 * shapes are the pipeline controller's: Shape A for the explorer, `HX-Redirect` for the
 * editor and the entity purge, Shape C for every refusal.
 *
 * There is deliberately no Switch dialog: templates are pinned by exact version, so there is
 * no served pointer a human would roll back (§4.6 records the absence).
 */
@org.springframework.stereotype.Controller
class TemplateLifecycleDialogController(
    private val releases: TemplateReleaseService,
    private val templates: TemplateRepository,
    private val dialogs: TemplateLifecycleDialogModel,
    private val browse: TemplateBrowseModel,
    private val audit: AuditEventSink,
) {
    // ------------------------------------------------------------------ release

    @GetMapping("/partials/templates/lifecycle/release")
    @RequiredScope(ScopeMatrix.RestOperation.RELEASE_VERSION)
    fun releaseDialog(
        model: Model,
        @RequestParam name: String,
        @RequestParam(required = false) from: String?,
    ): String {
        LifecycleVerbs.requireSession()
        model.addAttribute("dlg", dialogs.release(currentPrincipal().requireWorkspace().id, name))
        model.addAttribute("from", from ?: FROM_EXPLORER)
        return "partials/template-lifecycle-release"
    }

    @PostMapping("/partials/templates/lifecycle/release")
    @RequiredScope(ScopeMatrix.RestOperation.RELEASE_VERSION)
    fun release(
        model: Model,
        response: HttpServletResponse,
        @RequestParam name: String,
        @RequestParam(required = false) from: String?,
    ): Any {
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
        // No audit, exactly like the REST release (the discard family is what 101 audited).
        val released = releases.release(workspaceId, name, draft.bodyHash, principal.userId)
        return if (from == FROM_EDITOR) {
            redirect("/templates/editor?name=${urlEncode(name)}&ok=released")
        } else {
            applied(
                model,
                response,
                workspaceId,
                name,
                "Released v${released.detail.version}",
                "v${released.detail.version} is the version a pin without a number resolves to now.",
            )
        }
    }

    // ------------------------------------------------------------------ purge draft (the versioned verb)

    @GetMapping("/partials/templates/lifecycle/purge")
    @RequiredScope(ScopeMatrix.RestOperation.MUTATE_PIPELINES_TEMPLATES)
    fun purgeDialog(
        model: Model,
        @RequestParam name: String,
        @RequestParam version: Int,
        @RequestParam(required = false) from: String?,
    ): String {
        LifecycleVerbs.requireSession()
        model.addAttribute("dlg", dialogs.purge(currentPrincipal().requireWorkspace().id, name, version))
        model.addAttribute("from", from ?: FROM_EXPLORER)
        return "partials/template-lifecycle-purge"
    }

    @PostMapping("/partials/templates/lifecycle/purge")
    @RequiredScope(ScopeMatrix.RestOperation.MUTATE_PIPELINES_TEMPLATES)
    fun purge(
        model: Model,
        response: HttpServletResponse,
        @RequestParam name: String,
        @RequestParam version: Int,
        @RequestParam confirm: String?,
        @RequestParam(required = false) from: String?,
    ): Any {
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
                // the leaf, so the page navigates and the flash carries the toast.
                redirect("/templates?ok=template_purged")
            }

            from == FROM_EDITOR -> {
                redirect("/templates/editor?name=${urlEncode(name)}&ok=draft_purged")
            }

            else -> {
                applied(
                    model,
                    response,
                    workspaceId,
                    name,
                    "Purged v$version",
                    "The draft is gone. This cannot be undone.",
                )
            }
        }
    }

    // ------------------------------------------------------------------ discard release

    @GetMapping("/partials/templates/lifecycle/discard")
    @RequiredScope(ScopeMatrix.RestOperation.MUTATE_PIPELINES_TEMPLATES)
    fun discardDialog(
        model: Model,
        @RequestParam name: String,
        @RequestParam version: Int,
    ): String {
        LifecycleVerbs.requireSession()
        model.addAttribute("dlg", dialogs.discard(currentPrincipal().requireWorkspace().id, name, version))
        model.addAttribute("from", FROM_EXPLORER)
        return "partials/template-lifecycle-discard"
    }

    @PostMapping("/partials/templates/lifecycle/discard")
    @RequiredScope(ScopeMatrix.RestOperation.MUTATE_PIPELINES_TEMPLATES)
    fun discard(
        model: Model,
        response: HttpServletResponse,
        @RequestParam name: String,
        @RequestParam version: Int,
    ): Any {
        val principal = LifecycleVerbs.requireSession()
        val workspaceId = principal.requireWorkspace().id
        val detail = releases.discardVersion(workspaceId, name, version, principal.userId)
        LifecycleVerbs.audit(
            audit,
            LifecycleVerbs.TEMPLATE_AUDIT_VERSION_DISCARDED,
            principal,
            workspaceId,
            mapOf("template_id" to name, "version" to version),
        )
        // The pointer outcome from the row the service returned — never the dialog's guess.
        val current = templates.findLatest(workspaceId, name)?.version
        val outcome =
            if (current != null) {
                "v$current is the resolved version now."
            } else {
                "Nothing eligible remains — the template has no current version."
            }
        return applied(model, response, workspaceId, name, "Discarded v$version", outcome)
    }

    // ------------------------------------------------------------------ restore

    @GetMapping("/partials/templates/lifecycle/restore")
    @RequiredScope(ScopeMatrix.RestOperation.MUTATE_PIPELINES_TEMPLATES)
    fun restoreDialog(
        model: Model,
        @RequestParam name: String,
        @RequestParam version: Int,
    ): String {
        LifecycleVerbs.requireSession()
        model.addAttribute("dlg", dialogs.restore(currentPrincipal().requireWorkspace().id, name, version))
        model.addAttribute("from", FROM_EXPLORER)
        return "partials/template-lifecycle-restore"
    }

    @PostMapping("/partials/templates/lifecycle/restore")
    @RequiredScope(ScopeMatrix.RestOperation.MUTATE_PIPELINES_TEMPLATES)
    fun restore(
        model: Model,
        response: HttpServletResponse,
        @RequestParam name: String,
        @RequestParam version: Int,
    ): Any {
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
        val current = templates.findLatest(workspaceId, name)?.version
        return applied(
            model,
            response,
            workspaceId,
            name,
            "Restored v$version",
            if (current == version) {
                "v$version is the resolved version now."
            } else {
                "v$version is released again; the pointer stays at v$current."
            },
        )
    }

    // ------------------------------------------------------------------ purge entity

    @GetMapping("/partials/templates/lifecycle/purge-entity")
    @RequiredScope(ScopeMatrix.RestOperation.MUTATE_PIPELINES_TEMPLATES)
    fun purgeEntityDialog(
        model: Model,
        @RequestParam name: String,
    ): String {
        LifecycleVerbs.requireSession()
        model.addAttribute("dlg", dialogs.purgeEntity(currentPrincipal().requireWorkspace().id, name))
        model.addAttribute("from", FROM_EXPLORER)
        return "partials/template-lifecycle-purge-entity"
    }

    @PostMapping("/partials/templates/lifecycle/purge-entity")
    @RequiredScope(ScopeMatrix.RestOperation.MUTATE_PIPELINES_TEMPLATES)
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

    private fun applied(
        model: Model,
        response: HttpServletResponse,
        workspaceId: UUID,
        name: String,
        toastTitle: String,
        toastMessage: String,
    ): String {
        browse.fillDetail(model, workspaceId, name)
        model.addAttribute("lifecycleToastTitle", toastTitle)
        model.addAttribute("lifecycleToastMessage", toastMessage)
        val draft = templates.findDraftDetail(workspaceId, name)
        val latest = templates.findLatest(workspaceId, name)?.version
        val working = (draft?.version ?: latest)?.toString() ?: "null"
        response.setHeader(
            "HX-Trigger",
            "{\"lifecycle-changed\":{\"leafId\":\"${jsonEscape(name)}\",\"workingVersion\":$working,\"hasDraft\":${draft != null}}}",
        )
        return APPLIED_VIEW
    }

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

    private fun urlEncode(raw: String): String = java.net.URLEncoder.encode(raw, Charsets.UTF_8)

    private fun jsonEscape(raw: String): String = raw.replace("\\", "\\\\").replace("\"", "\\\"")

    private companion object {
        const val FROM_EXPLORER = "explorer"
        const val FROM_EDITOR = "editor"
        const val APPLIED_VIEW = "partials/template-lifecycle-applied"
    }
}
