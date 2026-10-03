package co.datapipelines.web.ui

import co.datapipelines.application.lens.LensedView
import co.datapipelines.parameters.ParameterSetFolder
import co.datapipelines.parameters.ParameterSetService
import co.datapipelines.parameters.ParameterSetVersion
import co.datapipelines.pipeline.PipelineNameGrammar
import co.datapipelines.pipeline.PipelineVersionStatus
import org.springframework.ui.Model
import java.security.MessageDigest
import java.util.UUID

/**
 * The Parameter Sets screens' browse model (#374, #357 S1) — [DashboardBrowseModel]'s mechanics
 * copied for the third artifact family: the rail's lazy tree and the `/parameter-sets` catalog page
 * render fragments over the SAME lensed reads, so the screen and the fragment cannot disagree about
 * what a level holds.
 *
 * Two presentations, chosen by the request's `scope` (the pipelines catalog's shape, #350):
 *
 * - [SCOPE_NAV] — the sidebar's tree: ONE prefix level per request (folders, then the sets directly
 *   under the prefix), through [ParameterSetService.listChildFolders]/`listChildSets`/`countChildSets`;
 *   a non-empty `q` is instead the branch's flat search through [fillSearch]. A level's id is derived
 *   per prefix so a folder's placeholder and the fragment that replaces it cannot disagree
 *   ([levelId]).
 * - [SCOPE_PAGE] — the catalog page: ONE flat, server-paged list of full paths, every set the
 *   caller's lens admits or the matches of `q` (#415), through `search`/`countSearch`, rooted at
 *   the stable [CATALOG_ROOT_ID].
 *
 * Every read passes the caller's `view.parameterSets` lens — under the promoter's narrowing lens the
 * service derives levels from the admitted current RELEASED sets, so a set the lens hides is absent
 * from the tree, the count and the list, never rendered disabled and never hidden by CSS. Nothing is
 * cached across requests, workspaces or lenses: each call re-reads.
 *
 * Declared as an explicit `@Bean` in [UiConfig] (zero DI stereotypes in production code,
 * module-structure §8.4); no repository is read here — every read goes through the service.
 */
class ParameterSetsBrowseModel(
    private val sets: ParameterSetService,
) {
    /**
     * Fills [model] for one **tree level** — [prefix] `null`/empty is the root — and returns the
     * view name. The sidebar's tree is the only caller of a level; [scope] is carried so the pager's
     * links and the folder placeholders name the instance they live in.
     */
    fun fillLevel(
        model: Model,
        workspaceId: UUID,
        view: LensedView,
        prefix: String?,
        offset: Int,
        scope: String = SCOPE_NAV,
    ): String {
        // A prefix is user input that becomes a LIKE pattern: bound and escaped in the repository, but a
        // value that is not a legal artifact name cannot name a real folder either, and an arbitrary-length
        // string would make a level request an arbitrary-length pattern match. An illegal prefix renders an
        // ordinary EMPTY level, never an error (the three sibling browsers' rule).
        if (!prefix.isNullOrEmpty() && !PipelineNameGrammar.matchesPrefix(prefix)) {
            return emptyLevel(model, prefix, scope)
        }
        val normalised = prefix?.takeIf { it.isNotEmpty() }
        val page = maxOf(0, offset)
        val folders = sets.listChildFolders(workspaceId, view.parameterSets, normalised)
        val children = sets.listChildSets(workspaceId, view.parameterSets, normalised, page, PAGE_SIZE)
        val total = sets.countChildSets(workspaceId, view.parameterSets, normalised)
        // THE ROOT HOLDS FOLDERS ONLY (077, the sibling trees' rule): every name has a folder, so the root
        // is a directory of folders and nothing else.
        val leaves = if (normalised == null) emptyList() else children
        model.addAttribute("lensUnavailable", view.unavailable)
        model.addAttribute("prefix", prefix ?: "")
        model.addAttribute("scope", scope)
        model.addAttribute("levelId", levelId(scope, prefix))
        model.addAttribute("folders", folders.map { ParameterSetFolderView(it, scope) })
        model.addAttribute("parameterSets", leaves.map(::ParameterSetLeafView))
        model.addAttribute("offset", page)
        model.addAttribute("hasMore", page + leaves.size < total)
        model.addAttribute("total", total)
        return LEVEL_VIEW
    }

    /**
     * Fills [model] for a **flat list of sets** — the sidebar search's results ([SCOPE_NAV], `q`
     * non-empty) or the `/parameter-sets` catalog ([SCOPE_PAGE], where an absent `q` lists every set
     * the caller may read) — and returns the view name. [PipelineBrowseModel.fillSearch]'s shape:
     * the lensed flat listing through [ParameterSetService.search], the truthful total through
     * [ParameterSetService.countSearch], the pager bound to the scope's stable root (`rootId`).
     */
    fun fillSearch(
        model: Model,
        workspaceId: UUID,
        view: LensedView,
        q: String?,
        offset: Int,
        scope: String = SCOPE_NAV,
    ): String {
        val page = maxOf(0, offset)
        // The trim is the model's, in one place: a blank `q` is no search (the dispatcher's level,
        // or the catalog's plain listing), and what renders back into the box is the trimmed term.
        // The length is bounded because this `q` becomes a bound ILIKE pattern (#415's new SQL
        // path): a needle is at most [MAX_QUERY_LENGTH] characters, longer input truncated to what
        // a search box is for. The sibling searches predate the bound and carry none — this is the
        // new path's stated posture, not a copied house rule.
        val needle = q?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_QUERY_LENGTH)
        val loaded = sets.search(workspaceId, view.parameterSets, needle, page, PAGE_SIZE)
        val total = sets.countSearch(workspaceId, view.parameterSets, needle)
        model.addAttribute("lensUnavailable", view.unavailable)
        model.addAttribute("searching", true)
        model.addAttribute("scope", scope)
        model.addAttribute("rootId", rootIdOf(scope))
        model.addAttribute("q", needle.orEmpty())
        model.addAttribute("parameterSets", loaded.map(::ParameterSetLeafView))
        model.addAttribute("offset", page)
        model.addAttribute("hasMore", page + loaded.size < total)
        model.addAttribute("total", total)
        return SEARCH_VIEW
    }

    /**
     * Fills [model] for whichever presentation [q] and [scope] select, and returns the
     * **dispatcher** view whose one root element is the scope's stable swap root either way
     * ([PipelineBrowseModel.fillWrapper]'s shape). In the sidebar ([SCOPE_NAV]) an empty `q` is the
     * tree's ROOT level and a non-empty one the flat search, so clearing the box returns to the tree
     * by construction. The catalog ([SCOPE_PAGE]) has no tree to return to: it is always the flat
     * list — every set the lens admits when `q` is empty, the matches otherwise.
     */
    fun fillWrapper(
        model: Model,
        workspaceId: UUID,
        view: LensedView,
        q: String?,
        offset: Int,
        scope: String = SCOPE_NAV,
    ): String {
        val needle = q?.trim()?.takeIf { it.isNotEmpty() }
        if (scope == SCOPE_NAV && needle == null) {
            fillLevel(model, workspaceId, view, prefix = null, offset = offset)
        } else {
            fillSearch(model, workspaceId, view, needle, offset, scope)
        }
        return WRAPPER_VIEW
    }

    /** A level that cannot exist: rendered as an ordinary empty level, never as an error. */
    private fun emptyLevel(
        model: Model,
        prefix: String,
        scope: String,
    ): String {
        model.addAttribute("prefix", prefix)
        model.addAttribute("scope", scope)
        model.addAttribute("levelId", levelId(scope, prefix))
        model.addAttribute("folders", emptyList<ParameterSetFolderView>())
        model.addAttribute("parameterSets", emptyList<ParameterSetLeafView>())
        model.addAttribute("offset", 0)
        model.addAttribute("hasMore", false)
        model.addAttribute("total", 0)
        return LEVEL_VIEW
    }

    /** One tree folder, with the scope-aware id of the container that will hold its level. */
    data class ParameterSetFolderView(
        val path: String,
        val segment: String,
        val count: Int,
        val levelId: String,
        val scope: String,
    ) {
        constructor(folder: ParameterSetFolder, scope: String) : this(
            folder.path,
            folder.segment,
            folder.setCount,
            levelId(scope, folder.path),
            scope,
        )
    }

    /** One set row, at its listed (working) version — the dashboards leaf's twin. */
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
        /** The explorers' page size — the value the three sibling trees use. */
        const val PAGE_SIZE = 25

        /** The search needle's length bound (#415) — a search box is not a text field. */
        const val MAX_QUERY_LENGTH = 200

        const val LEVEL_VIEW = "partials/parameter-set-tree-level"
        const val SEARCH_VIEW = "partials/parameter-set-search"
        const val WRAPPER_VIEW = "partials/parameter-sets"

        /** The DOM id of the NAV instance's root level — the sidebar tree's lazy container. */
        const val NAV_ROOT_ID = "params-tree-nav"

        /** The catalog page's stable swap root: its search box and pager target it. */
        const val CATALOG_ROOT_ID = "parameter-set-list-wrapper"

        /** The swap root a presentation renders into: the sidebar's tree panel or the catalog's list. */
        fun rootIdOf(scope: String): String = if (scope == SCOPE_NAV) NAV_ROOT_ID else CATALOG_ROOT_ID

        /** Hex characters of a nested level's id digest — 64 bits, over one screen's folders. */
        private const val LEVEL_ID_HEX_LENGTH = 16

        const val SCOPE_PAGE = "page"
        const val SCOPE_NAV = "nav"

        /**
         * The response header every sidebar fragment carries — the pipelines tree's own
         * ([PipelineBrowseModel.NAV_STAMP_HEADER]): `<workspace>|<lens>`. nav-tree.js admits a tree
         * swap only when the stamp matches the tree it lands in, so a level fetched under another
         * workspace or another lens can never join rows rendered under this one.
         */
        const val NAV_STAMP_HEADER = PipelineBrowseModel.NAV_STAMP_HEADER

        /** The [NAV_STAMP_HEADER] value for [workspaceName] under [view]: `all` or `lens`. */
        fun navStamp(
            workspaceName: String,
            view: LensedView,
        ): String = workspaceName + "|" + if (view.parameterSets.isEverything) "all" else "lens"

        /**
         * The DOM id of the container that holds one tree level. A prefix cannot be an id (`/` and `.` are
         * legal in a name), so the id is a digest of the prefix, derived in ONE place so the placeholder a
         * folder renders and the root of the fragment that replaces it cannot disagree.
         */
        fun levelId(
            scope: String,
            prefix: String?,
        ): String {
            if (prefix.isNullOrEmpty()) return NAV_ROOT_ID
            val digest = MessageDigest.getInstance("SHA-256").digest(prefix.toByteArray(Charsets.UTF_8))
            return "params-level-$scope-" + digest.joinToString("") { "%02x".format(it) }.take(LEVEL_ID_HEX_LENGTH)
        }
    }
}
