package co.datapipelines.web.ui

import co.datapipelines.application.endpoints.EndpointVersionService
import co.datapipelines.auth.Permission
import co.datapipelines.auth.RequiredScope
import co.datapipelines.pipeline.release.ReleaseFamily
import co.datapipelines.pipeline.release.ReleasePlanRequest
import co.datapipelines.web.api.currentPrincipal
import co.datapipelines.web.pipelines.LifecycleVerbs
import co.datapipelines.web.release.ReleaseCoordinator
import co.datapipelines.web.release.RootReleaseRequest
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * The API versions page and its native lifecycle dialogs (#786 B.3/C.3, record §24 UX; the pipeline and template twins
 * are [PipelineLifecycleDialogController] and [TemplateLifecycleDialogController]).
 *
 * Every POST calls the service the REST route calls — [EndpointVersionService] for save/discard/restore/purge/switch,
 * [ReleaseCoordinator.releaseRoot] for the release — under the same `@RequiredScope` row, and the lifecycle verbs are
 * session-only through [LifecycleVerbs.requireSession] exactly as the REST twins are. A dialog's GET pre-reads the guard
 * (a refused branch has no button); the POST re-runs it in the service. Success answers `HX-Redirect` back onto the page,
 * with a layout flash code (the `api_*` codes in `layouts/default.html`).
 *
 * The release dialog shows the shared planner's closure: the DRAFT dependencies the release would flip first, under one
 * consent box, and the tree hash it read, posted back as `tree_hash` so a closure changed after the dialog opened is
 * `endpoint.release.plan_conflict`, not a silent release of something the promoter never saw.
 */
@Controller
class EndpointLifecycleDialogController(
    private val pages: EndpointVersionsPageModel,
    private val versions: EndpointVersionService,
    private val coordinator: ReleaseCoordinator,
    private val themeResolver: ThemeResolver,
) : ShellPage {
    @GetMapping("/api-console/endpoints/{id}")
    @RequiredScope(Permission.ENDPOINT_READ)
    fun page(
        model: Model,
        request: HttpServletRequest,
        @PathVariable id: UUID,
        @RequestParam(required = false) version: Int?,
    ): String {
        val principal = currentPrincipal()
        val page = pages.page(principal, id, version) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        model.addAttribute("page", page)
        RoleModel.stamp(model, principal)
        model.addAttribute("activeTheme", themeResolver.resolve(request))
        return "api/endpoint"
    }

    // ------------------------------------------------------------------ draft (save)

    @GetMapping("/partials/endpoints/{id}/lifecycle/draft")
    @RequiredScope(Permission.ENDPOINT_PUBLISH)
    fun draftDialog(
        model: Model,
        @PathVariable id: UUID,
        @RequestParam(required = false) pipeline: String?,
    ): String {
        model.addAttribute("dlg", pages.draft(LifecycleVerbs.requireSession(), id, pipeline))
        return "partials/endpoint-lifecycle-draft"
    }

    @Suppress("LongParameterList") // the dialog's form fields, each bound by name
    @PostMapping("/partials/endpoints/{id}/lifecycle/draft")
    @RequiredScope(Permission.ENDPOINT_PUBLISH)
    fun saveDraft(
        @PathVariable id: UUID,
        @RequestParam ifMatch: String,
        @RequestParam pipeline: String,
        @RequestParam pipelineVersion: Int,
        @RequestParam(required = false) timeoutSeconds: Int?,
        @RequestParam(required = false) description: String?,
    ): ResponseEntity<String> {
        val principal = LifecycleVerbs.requireSession()
        val saved =
            versions.saveDraft(
                principal,
                id,
                EndpointVersionService.DraftInput(pipeline, pipelineVersion, timeoutSeconds, description),
                ifMatch,
            )
        return redirect(id, saved.version, "api_draft_saved")
    }

    // ------------------------------------------------------------------ release

    @GetMapping("/partials/endpoints/{id}/lifecycle/release")
    @RequiredScope(Permission.ENDPOINT_RELEASE)
    fun releaseDialog(
        model: Model,
        @PathVariable id: UUID,
    ): String {
        model.addAttribute("dlg", pages.release(LifecycleVerbs.requireSession(), id))
        return "partials/endpoint-lifecycle-release"
    }

    @PostMapping("/partials/endpoints/{id}/lifecycle/release")
    @RequiredScope(Permission.ENDPOINT_RELEASE)
    fun release(
        @PathVariable id: UUID,
        @RequestParam bodyHash: String,
        @RequestParam treeHash: String,
        @RequestParam(name = "dependency", required = false) dependencies: List<String>?,
        @RequestParam(required = false) includeDependencies: Boolean?,
    ): ResponseEntity<String> {
        val principal = LifecycleVerbs.requireSession()
        // The consent box decides the selection: unchecked sends none, and a DRAFT below the API then refuses
        // dependency_not_released under the guard — the order rule stays the server's, never the markup's.
        val include = if (includeDependencies == true) dependencies.orEmpty() else emptyList()
        val released =
            coordinator.releaseRoot(
                RootReleaseRequest(
                    family = ReleaseFamily.ENDPOINT,
                    workspaceId = principal.requireWorkspace().id,
                    entityId = id,
                    expectedHash = bodyHash,
                    actor = principal.userId,
                    plan = ReleasePlanRequest(treeHash = treeHash, includeDependencies = include),
                ),
            )
        return redirect(id, released.root.node.identity.version, "api_released")
    }

    // ------------------------------------------------------------------ discard / restore / purge / switch

    @GetMapping("/partials/endpoints/{id}/lifecycle/discard")
    @RequiredScope(Permission.ENDPOINT_VERSION_MANAGE)
    fun discardDialog(
        model: Model,
        @PathVariable id: UUID,
        @RequestParam version: Int,
    ): String = versionDialog(model, id, version, "partials/endpoint-lifecycle-discard")

    @PostMapping("/partials/endpoints/{id}/lifecycle/discard")
    @RequiredScope(Permission.ENDPOINT_VERSION_MANAGE)
    fun discard(
        @PathVariable id: UUID,
        @RequestParam version: Int,
    ): ResponseEntity<String> {
        versions.discardVersion(LifecycleVerbs.requireSession(), id, version)
        return redirect(id, version, "api_discarded")
    }

    @GetMapping("/partials/endpoints/{id}/lifecycle/restore")
    @RequiredScope(Permission.ENDPOINT_VERSION_MANAGE)
    fun restoreDialog(
        model: Model,
        @PathVariable id: UUID,
        @RequestParam version: Int,
    ): String = versionDialog(model, id, version, "partials/endpoint-lifecycle-restore")

    @PostMapping("/partials/endpoints/{id}/lifecycle/restore")
    @RequiredScope(Permission.ENDPOINT_VERSION_MANAGE)
    fun restore(
        @PathVariable id: UUID,
        @RequestParam version: Int,
    ): ResponseEntity<String> {
        versions.restoreVersion(LifecycleVerbs.requireSession(), id, version)
        return redirect(id, version, "api_restored")
    }

    @GetMapping("/partials/endpoints/{id}/lifecycle/purge")
    @RequiredScope(Permission.ENDPOINT_VERSION_MANAGE)
    fun purgeDialog(
        model: Model,
        @PathVariable id: UUID,
        @RequestParam version: Int,
    ): String = versionDialog(model, id, version, "partials/endpoint-lifecycle-purge")

    @PostMapping("/partials/endpoints/{id}/lifecycle/purge")
    @RequiredScope(Permission.ENDPOINT_VERSION_MANAGE)
    fun purge(
        @PathVariable id: UUID,
        @RequestParam version: Int,
    ): ResponseEntity<String> {
        val purged = versions.purgeVersion(LifecycleVerbs.requireSession(), id, version)
        // The sole version's purge took the API with it (record §24): the page it lived on is gone, so the console list.
        return if (purged.entityDeleted) {
            ResponseEntity.ok().header(HX_REDIRECT, "/api-console?ok=api_purged").body("")
        } else {
            redirect(id, null, "api_draft_purged")
        }
    }

    @GetMapping("/partials/endpoints/{id}/lifecycle/switch")
    @RequiredScope(Permission.ENDPOINT_VERSION_MANAGE)
    fun switchDialog(
        model: Model,
        @PathVariable id: UUID,
        @RequestParam version: Int,
    ): String = versionDialog(model, id, version, "partials/endpoint-lifecycle-switch")

    @PostMapping("/partials/endpoints/{id}/lifecycle/switch")
    @RequiredScope(Permission.ENDPOINT_VERSION_MANAGE)
    fun switchCurrent(
        @PathVariable id: UUID,
        @RequestParam version: Int,
    ): ResponseEntity<String> {
        versions.switchCurrent(LifecycleVerbs.requireSession(), id, version)
        return redirect(id, version, "api_switched")
    }

    private fun versionDialog(
        model: Model,
        id: UUID,
        version: Int,
        view: String,
    ): String {
        model.addAttribute("dlg", pages.version(LifecycleVerbs.requireSession(), id, version))
        return view
    }

    private fun redirect(
        id: UUID,
        version: Int?,
        code: String,
    ): ResponseEntity<String> {
        val query = listOfNotNull(version?.let { "version=$it" }, "ok=$code").joinToString("&")
        return ResponseEntity.ok().header(HX_REDIRECT, "/api-console/endpoints/$id?$query").body("")
    }

    private companion object {
        const val HX_REDIRECT = "HX-Redirect"
    }
}
