package co.datapipelines.web.ui

import co.datapipelines.application.lens.PromoterLens
import co.datapipelines.auth.Permission
import co.datapipelines.auth.RequiredScope
import co.datapipelines.web.api.currentPrincipal
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import java.util.UUID

/**
 * The pipelines screen's htmx fragments (067; ui-screens.md §4.3, template-hierarchy-design
 * §9.2).
 *
 * Every handler here is under `/partials`, which the [co.datapipelines.auth.ScopeInterceptor]
 * governs as **default-deny**: a partial carrying no [RequiredScope] is refused, so a new
 * fragment endpoint joins the same authorization posture as the one it sits beside rather
 * than quietly opening a hole (§9.1).
 *
 * ## One route, two fragment shapes — chosen by `prefix`
 *
 * - **`prefix` absent** → the wrapper: the whole `#pipeline-list-wrapper`, which is the search
 *   result list when `q` is non-empty and the tree's root level otherwise. This is the target
 *   of the search box and of the search pager — the existing SPA contract, unchanged.
 * - **`prefix` present** (empty string = the root) → that ONE tree level: its direct
 *   sub-folders and its direct pipeline children, and nothing else. This is what a folder's
 *   lazy expansion fetches, so expanding `nyc/mobility` never returns `trade`'s rows and never
 *   returns the whole list (§9.1: the tree is backed by server-side prefix queries).
 *
 * `q` is ignored while `prefix` is present: browse and search are different presentations
 * (§9.2) and a folder expansion is unambiguously a browse.
 *
 * ## #350 — which instance: `scope`
 *
 * The tree moved into the global sidebar. `scope=nav` is the sidebar's wrapper (the root level,
 * or its flat search when `q` is set, under `#pipeline-nav-root`); any other value — absent
 * included — is the `/pipelines` catalog's flat list under `#pipeline-list-wrapper`. A `prefix`
 * request is always a sidebar level. Same route, same `PIPELINE_READ`, same lens: the scope
 * picks the markup, never the rows. Every sidebar response carries
 * [PipelineBrowseModel.NAV_STAMP_HEADER] so the rail can refuse a level rendered under another
 * workspace or lens.
 */
@Controller
class PipelinePartialController(
    private val browse: PipelineBrowseModel,
    /** 178 — the promoter lens: every fragment here renders the caller's view. */
    private val lens: PromoterLens,
) {
    @GetMapping("/partials/pipelines")
    @RequiredScope(Permission.PIPELINE_READ)
    @Suppress("LongParameterList") // one request parameter per query value the route has always taken, plus #350's scope
    fun list(
        model: Model,
        response: HttpServletResponse,
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) prefix: String?,
        @RequestParam(required = false) offset: Int?,
        @RequestParam(required = false) scope: String?,
    ): String {
        val principal = currentPrincipal()
        val workspace = principal.requireWorkspace()
        val view = lens.viewFor(principal)
        val listScope = if (prefix != null) PipelineListScope.NAV else PipelineListScope.fromWire(scope)
        model.addAttribute("q", q ?: "")
        if (listScope == PipelineListScope.NAV) {
            response.setHeader(PipelineBrowseModel.NAV_STAMP_HEADER, PipelineBrowseModel.navStamp(workspace.name, view))
        }
        return if (prefix != null) {
            browse.fillLevel(model, workspace.id, view, prefix, offset ?: 0)
        } else {
            browse.fillWrapper(model, workspace.id, view, q?.trim()?.takeIf { it.isNotEmpty() }, offset ?: 0, listScope)
        }
    }

    /**
     * The acting column's **Runs** tab — this pipeline's last 20 executions, loaded on the
     * tab's first click and then swapped in place.
     *
     * Visibility is the execution-history screen's ([PipelineBrowseModel.fillRuns], #275): an
     * admin sees the workspace's runs, a member with `execution.read` her own plus the
     * scheduled runs (R3), a promoter her own only. A second surface over the same rows is not
     * a wider one — that would be a read-scope hole opened by a UI convenience.
     */
    @GetMapping("/partials/pipelines/{id}/runs")
    @RequiredScope(Permission.PIPELINE_READ)
    fun runs(
        model: Model,
        @PathVariable id: UUID,
    ): String {
        val principal = currentPrincipal()
        return browse.fillRuns(
            model,
            principal.requireWorkspace().id,
            lens.viewFor(principal),
            id,
            principal,
        )
    }

    /**
     * The acting column's **Usage** tab — the published endpoints serving this pipeline, the
     * live pipeline versions pinning it, and (#259) the schedules that run it.
     *
     * This is 101's discard evidence, read BEFORE the refusal rather than after it: the parent
     * half runs the same `findLiveParentsPinningVersion` query `PipelineService.refuseIfPinned`
     * runs, so what the user sees is what the server will decide on. The Schedules list is the
     * scheduler's by-target read through the promoter lens — a schedule that would silently
     * block after a discard (`pointer_null` / `target_not_found`) is on the tab, a hidden
     * target's schedule is not.
     */
    @GetMapping("/partials/pipelines/{id}/usage")
    @RequiredScope(Permission.PIPELINE_READ)
    fun usage(
        model: Model,
        @PathVariable id: UUID,
    ): String {
        val principal = currentPrincipal()
        return browse.fillUsage(model, principal.requireWorkspace().id, lens.viewFor(principal), id, principal)
    }
}
