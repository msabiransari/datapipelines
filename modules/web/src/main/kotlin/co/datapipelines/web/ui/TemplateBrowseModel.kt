package co.datapipelines.web.ui

import co.datapipelines.pipeline.TemplateType
import co.datapipelines.templates.TemplateFolder
import co.datapipelines.templates.TemplateNameGrammar
import co.datapipelines.templates.TemplateRepository
import co.datapipelines.typesystem.Dialect
import org.springframework.ui.Model
import java.security.MessageDigest
import java.util.UUID

/**
 * The templates browser's model, in one place for the page controller and the partial
 * controller (template-hierarchy-design §9.2).
 *
 * The screen has **two presentations and one contract**. Browsing renders a tree, one level
 * per request, each level a server-side prefix query (§9.1: no client-side tree assembly, at
 * any size). A non-empty search shows a flat list of full paths — not a tree pruned to
 * matching leaves, because pruning means walking the ancestors of every match, which is
 * exactly the whole-list-in-the-browser work the tree exists to avoid, and a flat list of
 * full paths is what someone searching `finance/agg` wants to see (§9.2, decided).
 *
 * Both presentations swap the same stable root `#template-list-wrapper` with `outerHTML`, and
 * both render the shared `partials/pager` — the existing SPA contract (ui-screens.md §4.5)
 * carries over unchanged, because this is a new fragment shape on an existing surface, not a
 * new surface.
 *
 * Nothing here creates, renames, moves or deletes a folder, and nothing can: a folder is a
 * name prefix with no identity (§3.1), so it is derived per request from the live rows
 * beneath it and disappears with them. An empty folder is unrepresentable rather than merely
 * unrendered.
 *
 * Declared as an explicit `@Bean` in [UiConfig], not by a stereotype: the house rule is zero
 * DI stereotypes in production code with **no** allowlist (015 / module-structure §8.4), and
 * `ArchitectureGuardTest` enforces it.
 */
class TemplateBrowseModel(
    private val templates: TemplateRepository,
) {
    /**
     * Fills [model] for one **tree level** — [prefix] `null`/empty is the root — and returns
     * the view name to render.
     *
     * The level's own leaves are paged with the shared pager against the level's own id, so
     * every level pages the same way and no level silently truncates. Its sub-folders are not
     * paged: they are a `GROUP BY` over one path segment, capped at
     * [TemplateRepository.MAX_PAGE_LIMIT] with an honest overflow flag rather than a silent
     * cut.
     */
    fun fillLevel(
        model: Model,
        workspaceId: UUID,
        prefix: String?,
        dialect: Dialect?,
        type: TemplateType?,
        offset: Int,
    ): String {
        val page = maxOf(0, offset)
        // A prefix is user input that becomes a LIKE pattern. It is bound and escaped in the
        // repository, so nothing can be injected — but a value that is not a legal template
        // path cannot name a real folder either, and letting an arbitrary-length string
        // through would turn a level request into an arbitrary-length pattern match. The
        // grammar it is checked against is the SERVER's own (§4.1), not a second copy.
        if (!prefix.isNullOrEmpty() && !TemplateNameGrammar.matches(prefix)) {
            return emptyLevel(model, prefix)
        }
        val folderProbe =
            templates.listChildFolders(workspaceId, prefix, dialect, type, limit = FOLDER_LIMIT + 1)
        val leafProbe =
            templates.listChildTemplates(workspaceId, prefix, dialect, type, offset = page, limit = PAGE_SIZE + 1)
        val leaves = leafProbe.take(PAGE_SIZE)
        model.addAttribute("searching", false)
        model.addAttribute("prefix", prefix ?: "")
        model.addAttribute("levelId", levelId(prefix))
        model.addAttribute("folders", folderProbe.take(FOLDER_LIMIT).map(::TemplateFolderView))
        model.addAttribute("foldersTruncated", folderProbe.size > FOLDER_LIMIT)
        model.addAttribute("templates", leaves)
        model.addAttribute("drafts", templates.findDrafts(workspaceId, leaves.map { it.id }))
        model.addAttribute("offset", page)
        model.addAttribute("hasMore", leafProbe.size > PAGE_SIZE)
        model.addAttribute("total", templates.countChildTemplates(workspaceId, prefix, dialect, type))
        return LEVEL_VIEW
    }

    /** A level that cannot exist: rendered as an ordinary empty level, never as an error. */
    private fun emptyLevel(
        model: Model,
        prefix: String,
    ): String {
        model.addAttribute("searching", false)
        model.addAttribute("prefix", prefix)
        model.addAttribute("levelId", levelId(prefix))
        model.addAttribute("folders", emptyList<TemplateFolderView>())
        model.addAttribute("foldersTruncated", false)
        model.addAttribute("templates", emptyList<Any>())
        model.addAttribute("drafts", emptyMap<String, Any>())
        model.addAttribute("offset", 0)
        model.addAttribute("hasMore", false)
        model.addAttribute("total", 0)
        return LEVEL_VIEW
    }

    /**
     * Fills [model] for a **search** — a flat list of full paths under the same filters,
     * paged by the shared pager against `#template-list-wrapper` (§9.2).
     */
    fun fillSearch(
        model: Model,
        workspaceId: UUID,
        q: String,
        dialect: Dialect?,
        type: TemplateType?,
        offset: Int,
    ): String {
        val page = maxOf(0, offset)
        val probe = templates.list(workspaceId, dialect = dialect, type = type, q = q, offset = page, limit = PAGE_SIZE + 1)
        val items = probe.take(PAGE_SIZE)
        model.addAttribute("searching", true)
        model.addAttribute("templates", items)
        model.addAttribute("drafts", templates.findDrafts(workspaceId, items.map { it.id }))
        model.addAttribute("offset", page)
        model.addAttribute("hasMore", probe.size > PAGE_SIZE)
        model.addAttribute("total", templates.count(workspaceId, dialect = dialect, type = type, q = q))
        return SEARCH_VIEW
    }

    /**
     * Fills [model] for whichever presentation [q] selects, and returns the **dispatcher**
     * view whose one root element is `#template-list-wrapper` either way.
     *
     * This is what a filter control's swap and the page's first render both go through, so
     * clearing the search box returns to the tree by construction (§9.2).
     */
    fun fillWrapper(
        model: Model,
        workspaceId: UUID,
        q: String?,
        dialect: Dialect?,
        type: TemplateType?,
        offset: Int,
    ): String {
        if (q.isNullOrEmpty()) {
            fillLevel(model, workspaceId, prefix = null, dialect = dialect, type = type, offset = offset)
        } else {
            fillSearch(model, workspaceId, q, dialect, type, offset)
        }
        return WRAPPER_VIEW
    }

    companion object {
        /** The templates screen's page size — the value the flat list has always used. */
        const val PAGE_SIZE = 25

        /** Sub-folders returned for one level before the level reports an overflow rather than hiding it. */
        const val FOLDER_LIMIT = TemplateRepository.MAX_PAGE_LIMIT

        /**
         * The root level's container id is the screen's long-standing stable swap root, so the
         * browse tree inherits the existing SPA contract instead of inventing a second one.
         */
        const val ROOT_LEVEL_ID = "template-list-wrapper"

        const val WRAPPER_VIEW = "partials/templates"
        const val LEVEL_VIEW = "partials/template-tree-level"
        const val SEARCH_VIEW = "partials/template-search"

        /** Hex characters of a nested level's id digest — 64 bits, over one screen's folders. */
        private const val LEVEL_ID_HEX_LENGTH = 16

        /**
         * The DOM id of the container that holds one tree level.
         *
         * A prefix cannot be used as an id directly: `/` and `.` are legal in a template name
         * and would need escaping at every htmx selector, and a naive substitution would map
         * `a/b` and `a-b` onto the same id. A digest is unambiguous, bounded, and — this is
         * the point — **derived** in one place, so the placeholder the folder renders and the
         * root of the fragment that replaces it cannot disagree.
         */
        fun levelId(prefix: String?): String {
            if (prefix.isNullOrEmpty()) return ROOT_LEVEL_ID
            val digest = MessageDigest.getInstance("SHA-256").digest(prefix.toByteArray(Charsets.UTF_8))
            return "tpl-level-" + digest.joinToString("") { "%02x".format(it) }.take(LEVEL_ID_HEX_LENGTH)
        }
    }
}

/**
 * One virtual folder, as the tree fragment needs it.
 *
 * [TemplateFolder] is the repository's shape and knows nothing about the DOM; this adds the
 * one thing the markup needs and cannot derive for itself — the id of the container that will
 * hold the folder's level. Deriving it HERE, once, is what keeps the placeholder the folder
 * renders and the root of the fragment that replaces it from disagreeing.
 */
data class TemplateFolderView(
    val path: String,
    val segment: String,
    val templateCount: Int,
    val levelId: String,
) {
    constructor(folder: TemplateFolder) : this(
        folder.path,
        folder.segment,
        folder.templateCount,
        TemplateBrowseModel.levelId(folder.path),
    )
}
