package co.datapipelines.web.ui

import co.datapipelines.application.lens.LensedView
import co.datapipelines.parameters.ParameterSetService
import co.datapipelines.parameters.ParameterSetVersion
import co.datapipelines.pipeline.LifecycleBucket
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.SearchNeedle
import org.springframework.ui.Model
import java.util.UUID

/**
 * The Parameter Sets screens' browse model (#374, #357 S1) — [DashboardBrowseModel]'s mechanics
 * copied for the third artifact family: the `/parameter-sets` catalog page and its fragment render
 * over the SAME lensed reads, so the screen and the fragment cannot disagree about what the list holds.
 *
 * The catalog is ONE flat, server-paged list of full paths, every set the caller's lens admits or the
 * matches of `q` (#415), through `search`/`countSearch`, rooted at the stable [CATALOG_ROOT_ID]. The
 * folder tree is the global sidebar's, read from `/api/v1/parameter-sets/tree` since #460; its
 * server-rendered levels (`scope=nav`, `prefix`) are gone with #466.
 *
 * Every read passes the caller's `view.parameterSets` lens, so a set the lens hides is absent from the
 * count and the list, never rendered disabled and never hidden by CSS. Nothing is cached across
 * requests, workspaces or lenses: each call re-reads.
 *
 * Declared as an explicit `@Bean` in [UiConfig] (zero DI stereotypes in production code,
 * module-structure §8.4); no repository is read here — every read goes through the service.
 */
class ParameterSetsBrowseModel(
    private val sets: ParameterSetService,
) {
    /**
     * Fills [model] for the catalog's **flat list of sets** — every set the caller may read when `q`
     * is absent, the matches otherwise — and returns the view name. [PipelineBrowseModel.fillSearch]'s
     * shape: the lensed flat listing through [ParameterSetService.search], the truthful total through
     * [ParameterSetService.countSearch], the pager bound to [CATALOG_ROOT_ID] (`rootId`). #582: a
     * [lifecycle] keeps one inventory bucket, decided in the service's read; the fragment renders it
     * back into the catalog's control and carries it on the pager (PipelineBrowseModel's shape).
     */
    fun fillSearch(
        model: Model,
        workspaceId: UUID,
        view: LensedView,
        q: String?,
        offset: Int,
        lifecycle: LifecycleBucket? = null,
    ): String {
        val page = maxOf(0, offset)
        // The trim is the model's, in one place: a blank `q` is no search (the catalog's plain
        // listing), and what renders back into the box is the trimmed term.
        // The length is the shared bound (#490, `SearchNeedle.MAX_LENGTH` since #504b), which
        // refuses a longer needle on REST and MCP; a search box TRUNCATES to it before the call
        // instead, so a typed overflow never errors the box (#415's posture, unchanged).
        val needle = q?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_QUERY_LENGTH)
        val loaded = sets.search(workspaceId, view.parameterSets, needle, page, PAGE_SIZE, lifecycle)
        val total = sets.countSearch(workspaceId, view.parameterSets, needle, lifecycle)
        model.addAttribute("lensUnavailable", view.unavailable)
        model.addAttribute("lifecycle", lifecycle?.wire.orEmpty())
        model.addAttribute("lifecycleLabel", lifecycle?.let(CatalogLifecycle::label))
        model.addAttribute("lifecycleOptions", CatalogLifecycle.options(CatalogLifecycle.PARAMETER_SETS))
        model.addAttribute("searching", true)
        model.addAttribute("rootId", CATALOG_ROOT_ID)
        model.addAttribute("q", needle.orEmpty())
        model.addAttribute("parameterSets", loaded.map(::ParameterSetLeafView))
        model.addAttribute("offset", page)
        model.addAttribute("hasMore", page + loaded.size < total)
        model.addAttribute("total", total)
        return SEARCH_VIEW
    }

    /**
     * Fills [model] for the catalog's list ([fillSearch]) and returns the catalog's swap-root view,
     * `partials/parameter-sets`, whose one root element is [CATALOG_ROOT_ID]
     * ([PipelineBrowseModel.fillWrapper]'s shape).
     */
    fun fillWrapper(
        model: Model,
        workspaceId: UUID,
        view: LensedView,
        q: String?,
        offset: Int,
        lifecycle: LifecycleBucket? = null,
    ): String {
        fillSearch(model, workspaceId, view, q, offset, lifecycle)
        return WRAPPER_VIEW
    }

    /** One set row, at its listed (working) version — the dashboards row's twin. */
    data class ParameterSetLeafView(
        val id: UUID,
        val name: String,
        val segment: String,
        val displayName: String,
        val version: Int,
        val released: Boolean,
    ) {
        constructor(loaded: ParameterSetVersion) : this(
            loaded.record.id,
            loaded.record.name,
            loaded.record.name.substringAfterLast('/'),
            loaded.body.displayName,
            loaded.detail.version,
            loaded.detail.status == PipelineVersionStatus.RELEASED,
        )
    }

    companion object {
        /** The catalogs' page size — the value the three sibling catalogs use. */
        const val PAGE_SIZE = 25

        /** The search needle's length bound — the shared one (#490, #504b), which this box truncates to. */
        const val MAX_QUERY_LENGTH = SearchNeedle.MAX_LENGTH

        const val SEARCH_VIEW = "partials/parameter-set-search"
        const val WRAPPER_VIEW = "partials/parameter-sets"

        /** The catalog page's stable swap root: its search box and pager target it. */
        const val CATALOG_ROOT_ID = "parameter-set-list-wrapper"
    }
}
