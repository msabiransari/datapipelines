package co.datapipelines.web.ui

import co.datapipelines.application.lens.LensedView
import co.datapipelines.pipeline.PipelineNameGrammar
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.visualization.ArtifactFolder
import co.datapipelines.visualization.ArtifactVersion
import co.datapipelines.visualization.VisualizationBody
import co.datapipelines.visualization.VisualizationService
import org.springframework.ui.Model
import java.security.MessageDigest
import java.util.UUID

/**
 * The visualizations screens' browse model (#399, parent #396) — the sidebar tree level, the sidebar search
 * results and the `/visualizations` catalog, in [DashboardBrowseModel]'s and [ParameterSetsBrowseModel]'s shape
 * for the third artifact family on the #350 seam: one level per request, server-side prefix queries through
 * the family's lensed reads (`listChildFolders`/`listChildren`/`countChildren`), the root a directory of
 * folders only, nothing filtered in a template and nothing unfiltered shipped to the browser.
 *
 * The flat list (search results and catalog) is [VisualizationService.search] when `q` is present — the
 * lensed `ILIKE … ESCAPE` over name, display name and description, SQL-paged under the everything lens and
 * paged over the admitted set under a narrowing one — and [VisualizationService.listAll]/`countAll`
 * otherwise. Unlike the dashboards catalog's in-memory name filter, the search has no 200-row ceiling: the
 * total is the truthful count of every match.
 *
 * Declared as an explicit `@Bean` in [UiConfig] (the house rule: zero DI stereotypes in production code).
 */
class VisualizationBrowseModel(
    private val visualizations: VisualizationService,
) {
    /** One level of the SIDEBAR tree — [prefix] `null`/empty is the root (folders only). */
    fun fillLevel(
        model: Model,
        workspaceId: UUID,
        view: LensedView,
        prefix: String?,
        offset: Int,
    ): String {
        // A prefix that is not a legal name prefix cannot name a real folder: an ordinary EMPTY level,
        // never an error, and never an arbitrary-length pattern match (the dashboards/pipelines rule).
        val scoped = prefix?.takeIf { it.isNotEmpty() }
        val legal = scoped == null || PipelineNameGrammar.matchesPrefix(scoped)
        val page = maxOf(0, offset)
        val folders = if (legal) visualizations.listChildFolders(workspaceId, view.visualizations, scoped) else emptyList()
        // THE ROOT HOLDS FOLDERS ONLY (077): every visualization name has a folder.
        val leaves =
            if (!legal || scoped == null) {
                emptyList()
            } else {
                visualizations.listChildren(workspaceId, view.visualizations, scoped, page, PAGE_SIZE)
            }
        val total = if (legal && scoped != null) visualizations.countChildren(workspaceId, view.visualizations, scoped) else 0
        model.addAttribute("lensUnavailable", view.unavailable)
        model.addAttribute("prefix", prefix ?: "")
        model.addAttribute("scope", SCOPE_NAV)
        model.addAttribute("levelId", levelId(prefix))
        model.addAttribute("folders", folders.map(::FolderView))
        model.addAttribute("visualizations", leaves.map(::LeafView))
        model.addAttribute("offset", page)
        model.addAttribute("hasMore", page + leaves.size < total)
        model.addAttribute("total", total)
        return LEVEL_VIEW
    }

    /**
     * A FLAT list — the sidebar's search results ([VisualizationListScope.NAV], `q` non-empty) or the catalog
     * ([VisualizationListScope.PAGE], every readable visualization when `q` is empty): a page, the truthful
     * total, the pager bound to the scope's stable root.
     */
    fun fillList(
        model: Model,
        workspaceId: UUID,
        view: LensedView,
        q: String?,
        offset: Int,
        scope: VisualizationListScope,
    ): String {
        val page = maxOf(0, offset)
        val needle = q?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_QUERY_LENGTH)
        val (rows, total) =
            if (needle == null) {
                visualizations.listAll(workspaceId, view.visualizations, page, PAGE_SIZE) to
                    visualizations.countAll(workspaceId, view.visualizations)
            } else {
                visualizations.search(workspaceId, view.visualizations, needle, page, PAGE_SIZE).let { it.items to it.total }
            }
        model.addAttribute("lensUnavailable", view.unavailable)
        model.addAttribute("searching", true)
        model.addAttribute("scope", scope.wire)
        model.addAttribute("rootId", scope.rootId)
        model.addAttribute("q", needle.orEmpty())
        model.addAttribute("offset", page)
        model.addAttribute("visualizations", rows.map(::LeafView))
        model.addAttribute("hasMore", page + rows.size < total)
        model.addAttribute("total", total)
        return SEARCH_VIEW
    }

    /**
     * The dispatcher: in the sidebar an empty `q` is the tree's ROOT level and a non-empty one the flat list,
     * so clearing the box returns to the tree by construction; the catalog is always the flat list.
     */
    fun fillWrapper(
        model: Model,
        workspaceId: UUID,
        view: LensedView,
        q: String?,
        offset: Int,
        scope: VisualizationListScope,
    ): String {
        if (scope == VisualizationListScope.NAV && q.isNullOrBlank()) {
            fillLevel(model, workspaceId, view, prefix = null, offset = offset)
        } else {
            fillList(model, workspaceId, view, q, offset, scope)
        }
        return WRAPPER_VIEW
    }

    /** One tree folder, with the id of the container its level will replace. */
    data class FolderView(
        val path: String,
        val segment: String,
        val count: Int,
        val levelId: String,
    ) {
        constructor(folder: ArtifactFolder) : this(folder.path, folder.segment, folder.count, levelId(folder.path))
    }

    /** One visualization row at its listed (working) version — the dashboards leaf's twin. */
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
        /** The explorers' page size — the value every tree and catalog uses. */
        const val PAGE_SIZE = 25

        /** A search term longer than any legal name is truncated, never echoed back at length. */
        const val MAX_QUERY_LENGTH = 200

        const val LEVEL_VIEW = "partials/visualization-tree-level"
        const val SEARCH_VIEW = "partials/visualization-search"

        /** The dispatcher: `searching ? visualization-search : visualization-tree-level`. */
        const val WRAPPER_VIEW = "partials/visualizations"

        const val SCOPE_NAV = "nav"

        /** The DOM id of the sidebar's root level. */
        const val NAV_ROOT_ID = "viz-tree-nav"

        /** The DOM id of the catalog's stable swap root. */
        const val PAGE_ROOT_ID = "viz-list-wrapper"

        private const val LEVEL_ID_HEX_LENGTH = 16

        /** The rail's admission stamp header ([PipelineBrowseModel.NAV_STAMP_HEADER]): `<workspace>|<lens>`. */
        const val NAV_STAMP_HEADER = PipelineBrowseModel.NAV_STAMP_HEADER

        fun navStamp(
            workspaceName: String,
            view: LensedView,
        ): String = workspaceName + "|" + if (view.visualizations.isEverything) "all" else "lens"

        /**
         * The DOM id of the container holding one level: the root is [NAV_ROOT_ID]; a nested level's id is a
         * digest of its prefix (a name holds `/` and `.`), derived here once so the placeholder a folder renders
         * and the fragment that replaces it cannot disagree.
         */
        fun levelId(prefix: String?): String {
            if (prefix.isNullOrEmpty()) return NAV_ROOT_ID
            val digest = MessageDigest.getInstance("SHA-256").digest(prefix.toByteArray(Charsets.UTF_8))
            return "viz-level-" + digest.joinToString("") { "%02x".format(it) }.take(LEVEL_ID_HEX_LENGTH)
        }
    }
}

/** One flat-list instance: which stable swap root the fragment renders and the pager targets. */
enum class VisualizationListScope(
    val wire: String,
    val rootId: String,
) {
    NAV(VisualizationBrowseModel.SCOPE_NAV, VisualizationBrowseModel.NAV_ROOT_ID),
    PAGE("page", VisualizationBrowseModel.PAGE_ROOT_ID),
    ;

    companion object {
        fun fromWire(value: String?): VisualizationListScope = if (value == NAV.wire) NAV else PAGE
    }
}
