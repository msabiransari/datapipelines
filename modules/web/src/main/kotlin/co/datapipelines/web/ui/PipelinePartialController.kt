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
 * ## The catalog's list
 *
 * `GET /partials/pipelines` is the `/pipelines` catalog's swap root, `#pipeline-list-wrapper`:
 * every pipeline the caller may read when `q` is empty, the matches of `q` otherwise, paged by
 * `offset` (§9.2, #350). It is the target of the catalog's search box, its pager and its
 * "Clear search" control — and, #556, of its lifecycle control: `lifecycle` keeps one inventory
 * bucket ([CatalogLifecycle]), the page's own parse and 400. #591: every answer carries the
 * catalog URL of the list it renders ([CatalogUrl]), so the address bar follows the controls.
 *
 * #466: the sidebar's server-rendered tree (`scope=nav`, `prefix` levels and their stamp
 * header) is gone — since #460 the sidebar reads `/api/v1/pipelines/tree`. A stale `scope` or
 * `prefix` is not a bound parameter, so it is ignored like any other unknown query value and the
 * request answers the catalog list: degrading to the page's own list costs a stale client
 * nothing, where a 400 would add a refusal path for a parameter no client sends.
 */
@Controller
class PipelinePartialController(
    private val browse: PipelineBrowseModel,
    /** 178 — the promoter lens: every fragment here renders the caller's view. */
    private val lens: PromoterLens,
) : ShellPage {
    @GetMapping("/partials/pipelines")
    @RequiredScope(Permission.PIPELINE_READ)
    fun list(
        model: Model,
        response: HttpServletResponse,
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) offset: Int?,
        @RequestParam(required = false) lifecycle: String?,
    ): String {
        val bucket = CatalogLifecycle.parse(lifecycle, CatalogLifecycle.PIPELINES)
        val principal = currentPrincipal()
        model.addAttribute("q", q ?: "")
        CatalogUrl.replace(response, CatalogUrl.PIPELINES, q, bucket, offset)
        return browse.fillWrapper(
            model,
            principal.requireWorkspace().id,
            lens.viewFor(principal),
            q?.trim()?.takeIf { it.isNotEmpty() },
            offset ?: 0,
            bucket,
        )
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
