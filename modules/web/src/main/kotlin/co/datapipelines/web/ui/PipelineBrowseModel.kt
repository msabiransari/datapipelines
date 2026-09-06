package co.datapipelines.web.ui

import co.datapipelines.pipeline.PipelineFolder
import co.datapipelines.pipeline.PipelineFolderLevel
import co.datapipelines.pipeline.PipelineNameGrammar
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.PipelineService
import org.springframework.ui.Model
import java.security.MessageDigest
import java.util.UUID

/**
 * The pipelines explorer's model, in one place for the page controller and the partial
 * controller (067; template-hierarchy-design §9.2, which this follows deliberately rather
 * than re-deciding).
 *
 * The screen has **two presentations and one contract**. Browsing renders a tree, one level
 * per request, each level a server-side prefix query (§9.1: no client-side tree assembly, at
 * any size). A non-empty search shows a flat list of full paths — not a tree pruned to
 * matching leaves, because pruning means walking the ancestors of every match, which is
 * exactly the whole-list-in-the-browser work the tree exists to avoid (§9.2, decided for
 * templates in 047 and inherited here).
 *
 * Both presentations swap the same stable root `#pipeline-list-wrapper` with `outerHTML`, and
 * both render the shared `partials/pager` — the SPA contract this screen has had since 028
 * (ui-screens.md §4.3) carries over unchanged, because this is a new fragment shape on an
 * existing surface, not a new surface.
 *
 * Nothing here creates, renames, moves or deletes a folder, and nothing can: a folder is a
 * name prefix with no identity (§3.1), derived per request from the live rows beneath it and
 * gone with them. An empty folder is unrepresentable rather than merely unrendered.
 *
 * The search half goes through [PipelineService.page] rather than a query of its own — the D2
 * rule that made one component answer the REST list, the MCP list, this screen and its
 * partial. The browse half is [PipelineRepository.listFolder], which has no service-level
 * sibling to duplicate.
 *
 * Declared as an explicit `@Bean` in [UiConfig], not by a stereotype: the house rule is zero
 * DI stereotypes in production code with **no** allowlist (015 / module-structure §8.4), and
 * `ArchitectureGuardTest` enforces it.
 */
class PipelineBrowseModel(
    private val pipelines: PipelineService,
    private val repository: PipelineRepository,
) {
    /**
     * Fills [model] for one **tree level** — [prefix] `null`/empty is the root — and returns
     * the view name to render.
     *
     * The level's own leaves are paged with the shared pager against the level's own id, so
     * every level pages the same way and no level silently truncates. Its sub-folders are not
     * paged: they are a `GROUP BY` over one path segment, capped with an honest overflow flag
     * rather than a silent cut.
     *
     * **The ROOT level renders no leaves** (§4.1, 077) — see the comment on the call below for
     * why that is a rendering rule here and a data guarantee on the templates side.
     */
    fun fillLevel(
        model: Model,
        workspaceId: UUID,
        prefix: String?,
        offset: Int,
    ): String {
        val page = maxOf(0, offset)
        // A prefix is user input that becomes a LIKE pattern. It is bound and escaped in the
        // repository, so nothing can be injected — but a value that is not a legal pipeline
        // name cannot name a real folder either, and letting an arbitrary-length string
        // through would turn a level request into an arbitrary-length pattern match. The
        // grammar it is checked against is the SERVER's own, not a second copy.
        //
        // An illegal prefix renders an ordinary EMPTY level, never an error: the templates
        // browser settled that (`TemplateBrowseModel.fillLevel`), a level that cannot exist is
        // not a client fault worth a 400, and two sibling explorers answering the same input
        // differently would be the surprise.
        if (!prefix.isNullOrEmpty() && !PipelineNameGrammar.matchesPrefix(prefix)) {
            return emptyLevel(model, prefix)
        }
        // "" and absent are the SAME level — the root — so they normalize to one repository
        // call rather than two shapes of the same query. The model keeps the caller's `""`,
        // because that is what the fragment renders its own prefix as.
        val level = repository.listFolder(workspaceId, prefix?.takeIf { it.isNotEmpty() }, offset = page, limit = PAGE_SIZE)
        // THE ROOT HOLDS FOLDERS ONLY (§4.1, 077). A pipeline name needs a folder, so the root
        // level renders sub-folders and nothing else, and the fragment's "leaves at the root"
        // branch is gone.
        //
        // Unlike templates there is no deploy gate here, deliberately (§14.2): a pipeline name
        // is validated at SAVE only, so a pre-077 flat row still exists, still opens and still
        // executes. Dropping it from the ROOT LEVEL is a rendering decision about a tree whose
        // root is now a directory of folders — it is not a disappearance. That row is still
        // returned by search (`q`, a flat list of full paths), by `pipelines_list`, and by its
        // own UUID URL, which is how it is opened and run.
        val rendered = if (prefix.isNullOrEmpty()) level.withoutLeaves() else level
        fillLevelAttributes(model, workspaceId, prefix, page, rendered)
        return LEVEL_VIEW
    }

    /** A level that cannot exist: rendered as an ordinary empty level, never as an error. */
    private fun emptyLevel(
        model: Model,
        prefix: String,
    ): String {
        fillLevelAttributes(model, workspaceId = null, prefix = prefix, page = 0, level = EMPTY_LEVEL)
        return LEVEL_VIEW
    }

    private fun fillLevelAttributes(
        model: Model,
        workspaceId: UUID?,
        prefix: String?,
        page: Int,
        level: PipelineFolderLevel,
    ) {
        model.addAttribute("searching", false)
        model.addAttribute("prefix", prefix ?: "")
        model.addAttribute("levelId", levelId(prefix))
        model.addAttribute("folders", level.folders.map(::PipelineFolderView))
        model.addAttribute("foldersTruncated", level.foldersTruncated)
        model.addAttribute("pipelines", level.pipelines)
        // versioning §7: the "unreleased edits exist" badge, for the rows actually shown. An
        // empty level has no rows, so it needs no query — and has no workspace to run one in.
        val drafts =
            if (workspaceId == null || level.pipelines.isEmpty()) {
                emptyMap()
            } else {
                pipelines.findDrafts(workspaceId, level.pipelines.map { it.id })
            }
        model.addAttribute("drafts", drafts)
        model.addAttribute("offset", page)
        model.addAttribute("hasMore", level.hasMore)
        model.addAttribute("total", level.total)
    }

    /**
     * Fills [model] for a **search** — a flat list of full paths, paged by the shared pager
     * against `#pipeline-list-wrapper` (§9.2).
     */
    fun fillSearch(
        model: Model,
        workspaceId: UUID,
        q: String,
        offset: Int,
    ): String {
        val page = maxOf(0, offset)
        val result = pipelines.page(workspaceId, q, page, PAGE_SIZE)
        model.addAttribute("searching", true)
        model.addAttribute("pipelines", result.items)
        model.addAttribute("drafts", result.drafts)
        model.addAttribute("offset", page)
        model.addAttribute("hasMore", result.hasMore)
        model.addAttribute("total", result.total)
        return SEARCH_VIEW
    }

    /**
     * Fills [model] for whichever presentation [q] selects, and returns the **dispatcher**
     * view whose one root element is `#pipeline-list-wrapper` either way.
     *
     * This is what the search box's swap and the page's first render both go through, so
     * clearing the search box returns to the tree by construction (§9.2).
     */
    fun fillWrapper(
        model: Model,
        workspaceId: UUID,
        q: String?,
        offset: Int,
    ): String {
        if (q.isNullOrEmpty()) {
            fillLevel(model, workspaceId, prefix = null, offset = offset)
        } else {
            fillSearch(model, workspaceId, q, offset)
        }
        return WRAPPER_VIEW
    }

    companion object {
        /** The pipelines screen's page size — the value the flat list has always used. */
        const val PAGE_SIZE = 25

        /**
         * The root level's container id is the screen's long-standing stable swap root, so the
         * browse tree inherits the existing SPA contract instead of inventing a second one.
         */
        const val ROOT_LEVEL_ID = "pipeline-list-wrapper"

        const val WRAPPER_VIEW = "partials/pipelines"
        const val LEVEL_VIEW = "partials/pipeline-tree-level"
        const val SEARCH_VIEW = "partials/pipeline-search"

        /** Hex characters of a nested level's id digest — 64 bits, over one screen's folders. */
        private const val LEVEL_ID_HEX_LENGTH = 16

        private val EMPTY_LEVEL =
            PipelineFolderLevel(emptyList(), foldersTruncated = false, pipelines = emptyList(), total = 0, hasMore = false)

        /**
         * The DOM id of the container that holds one tree level.
         *
         * A prefix cannot be used as an id directly: `/` and `.` are legal in a pipeline name
         * and would need escaping at every htmx selector, and a naive substitution would map
         * `a/b` and `a-b` onto the same id. A digest is unambiguous, bounded, and — this is
         * the point — **derived** in one place, so the placeholder the folder renders and the
         * root of the fragment that replaces it cannot disagree.
         */
        fun levelId(prefix: String?): String {
            if (prefix.isNullOrEmpty()) return ROOT_LEVEL_ID
            val digest = MessageDigest.getInstance("SHA-256").digest(prefix.toByteArray(Charsets.UTF_8))
            return "pl-level-" + digest.joinToString("") { "%02x".format(it) }.take(LEVEL_ID_HEX_LENGTH)
        }
    }
}

/**
 * One virtual folder, as the tree fragment needs it.
 *
 * [PipelineFolder] is the repository's shape and knows nothing about the DOM; this adds the
 * one thing the markup needs and cannot derive for itself — the id of the container that will
 * hold the folder's level. Deriving it HERE, once, is what keeps the placeholder the folder
 * renders and the root of the fragment that replaces it from disagreeing.
 */
data class PipelineFolderView(
    val path: String,
    val segment: String,
    val pipelineCount: Int,
    val levelId: String,
) {
    constructor(folder: PipelineFolder) : this(
        folder.path,
        folder.segment,
        folder.pipelineCount,
        PipelineBrowseModel.levelId(folder.path),
    )
}
