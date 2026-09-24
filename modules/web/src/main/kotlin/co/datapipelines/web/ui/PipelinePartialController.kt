package co.datapipelines.web.ui

import co.datapipelines.application.lens.PromoterLens
import co.datapipelines.auth.Permission
import co.datapipelines.auth.RequiredScope
import co.datapipelines.web.api.currentPrincipal
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
 */
@Controller
class PipelinePartialController(
    private val browse: PipelineBrowseModel,
    /** 178 — the promoter lens: every fragment here renders the caller's view. */
    private val lens: PromoterLens,
) {
    @GetMapping("/partials/pipelines")
    @RequiredScope(Permission.PIPELINE_READ)
    fun list(
        model: Model,
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) prefix: String?,
        @RequestParam(required = false) offset: Int?,
    ): String {
        val principal = currentPrincipal()
        val workspaceId = principal.requireWorkspace().id
        val view = lens.viewFor(principal)
        model.addAttribute("q", q ?: "")
        return if (prefix != null) {
            browse.fillLevel(model, workspaceId, view, prefix, offset ?: 0)
        } else {
            browse.fillWrapper(model, workspaceId, view, q?.trim()?.takeIf { it.isNotEmpty() }, offset ?: 0)
        }
    }

    /**
     * The SELECTED pipeline, for the explorer's right pane — 106's three regions: the header
     * (folder path, leaf name, Open in editor and the lifecycle verbs 101 shipped), the
     * READING column (Overview + Parameters) and the ACTING column (the tabbed card, whose
     * first paint is Versions).
     *
     * A selection swaps this fragment into `#pipeline-detail` with `innerHTML` and touches
     * nothing else: the tree pane's DOM is never re-rendered by a selection, which is the
     * whole point of the two-pane layout.
     *
     * Everything the three regions need arrives in ONE model call ([PipelineBrowseModel.fillDetail]);
     * Runs and Usage are separate fragments below, because a tab the user never opens should
     * cost nothing.
     *
     * The id travels as a QUERY parameter for symmetry with the templates pane, not out of
     * necessity — a pipeline is UUID-addressed everywhere (§9.6's `%2F` problem cannot arise
     * for a UUID).
     */
    @GetMapping("/partials/pipelines/detail")
    @RequiredScope(Permission.PIPELINE_READ)
    fun detail(
        model: Model,
        @RequestParam id: UUID,
    ): String {
        RoleModel.stamp(model)
        val principal = currentPrincipal()
        return browse.fillDetail(model, principal.requireWorkspace().id, lens.viewFor(principal), id)
    }

    /**
     * The acting column's **Runs** tab — this pipeline's last 20 executions, loaded on the
     * tab's first click and then swapped in place.
     *
     * Visibility is the execution-history screen's, unchanged: an admin sees the workspace's
     * runs, everyone else sees their own. A second surface over the same rows is not a wider
     * one — that would be a read-scope hole opened by a UI convenience.
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
            principal.userId,
            principal.holds(Permission.EXECUTION_READ_ALL),
        )
    }

    /**
     * The acting column's **Usage** tab — the published endpoints serving this pipeline and
     * the live pipeline versions pinning it.
     *
     * This is 101's discard evidence, read BEFORE the refusal rather than after it: the parent
     * half runs the same `findLiveParentsPinningVersion` query `PipelineService.refuseIfPinned`
     * runs, so what the user sees is what the server will decide on. Schedules join the list
     * when 092 lands; there is no schedule table to query today.
     */
    @GetMapping("/partials/pipelines/{id}/usage")
    @RequiredScope(Permission.PIPELINE_READ)
    fun usage(
        model: Model,
        @PathVariable id: UUID,
    ): String {
        val principal = currentPrincipal()
        return browse.fillUsage(model, principal.requireWorkspace().id, lens.viewFor(principal), id)
    }
}
