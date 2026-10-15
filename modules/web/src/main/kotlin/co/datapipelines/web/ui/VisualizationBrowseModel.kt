package co.datapipelines.web.ui

import co.datapipelines.application.lens.LensedView
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.SearchNeedle
import co.datapipelines.visualization.ArtifactVersion
import co.datapipelines.visualization.VisualizationBody
import co.datapipelines.visualization.VisualizationService
import org.springframework.ui.Model
import java.util.UUID

/**
 * The visualizations screens' browse model (#399, parent #396) — the `/visualizations` catalog, in
 * [DashboardBrowseModel]'s and [ParameterSetsBrowseModel]'s shape for the third artifact family on the #350
 * seam: nothing filtered in a template and nothing unfiltered shipped to the browser. The folder tree is the
 * global sidebar's, read from `/api/v1/visualizations/tree` since #460; its server-rendered levels
 * (`/partials/visualizations/tree`) are gone with #466.
 *
 * The flat list is [VisualizationService.search] when `q` is present — the lensed `ILIKE … ESCAPE` over name,
 * display name and description, SQL-paged under the everything lens and paged over the admitted set under a
 * narrowing one — and [VisualizationService.listAll]/`countAll` otherwise. Unlike the dashboards catalog's
 * in-memory name filter, the search has no 200-row ceiling: the total is the truthful count of every match.
 *
 * Declared as an explicit `@Bean` in [UiConfig] (the house rule: zero DI stereotypes in production code).
 */
class VisualizationBrowseModel(
    private val visualizations: VisualizationService,
) {
    /**
     * The catalog's FLAT list — every readable visualization when `q` is empty, the matches otherwise: a page,
     * the truthful total, the pager bound to the catalog's stable root [PAGE_ROOT_ID].
     */
    fun fillList(
        model: Model,
        workspaceId: UUID,
        view: LensedView,
        q: String?,
        offset: Int,
    ): String {
        val page = maxOf(0, offset)
        // The needle's bound is the shared one (#504b, `SearchNeedle.MAX_LENGTH`): the model keeps
        // only the truncating half — a box truncates its own term, never refuses.
        val needle = q?.trim()?.takeIf { it.isNotEmpty() }?.take(SearchNeedle.MAX_LENGTH)
        val (rows, total) =
            if (needle == null) {
                visualizations.listAll(workspaceId, view.visualizations, page, PAGE_SIZE) to
                    visualizations.countAll(workspaceId, view.visualizations)
            } else {
                visualizations.search(workspaceId, view.visualizations, needle, page, PAGE_SIZE).let { it.items to it.total }
            }
        model.addAttribute("lensUnavailable", view.unavailable)
        model.addAttribute("searching", true)
        model.addAttribute("rootId", PAGE_ROOT_ID)
        model.addAttribute("q", needle.orEmpty())
        model.addAttribute("offset", page)
        model.addAttribute("visualizations", rows.map(::LeafView))
        model.addAttribute("hasMore", page + rows.size < total)
        model.addAttribute("total", total)
        return SEARCH_VIEW
    }

    /** Fills the catalog's list ([fillList]) and returns its swap-root view, `partials/visualizations`. */
    fun fillWrapper(
        model: Model,
        workspaceId: UUID,
        view: LensedView,
        q: String?,
        offset: Int,
    ): String {
        fillList(model, workspaceId, view, q, offset)
        return WRAPPER_VIEW
    }

    /** One visualization row at its listed (working) version — the dashboards row's twin. */
    data class LeafView(
        val id: UUID,
        val name: String,
        val segment: String,
        val displayName: String,
        val version: Int,
        val released: Boolean,
    ) {
        constructor(version: ArtifactVersion<VisualizationBody>) : this(
            version.record.id,
            version.record.name,
            version.record.name.substringAfterLast('/'),
            version.body.displayName,
            version.detail.version,
            version.detail.status == PipelineVersionStatus.RELEASED,
        )
    }

    companion object {
        /** The catalogs' page size — the value every catalog uses. */
        const val PAGE_SIZE = 25

        const val SEARCH_VIEW = "partials/visualization-search"

        /** The catalog's swap-root view: it renders `visualization-search`. */
        const val WRAPPER_VIEW = "partials/visualizations"

        /** The DOM id of the catalog's stable swap root. */
        const val PAGE_ROOT_ID = "viz-list-wrapper"
    }
}
