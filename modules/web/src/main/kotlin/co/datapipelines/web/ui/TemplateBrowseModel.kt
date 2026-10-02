package co.datapipelines.web.ui

import co.datapipelines.application.lens.LensedView
import co.datapipelines.application.templates.TemplateUsage
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.executor.ExecutionRecord
import co.datapipelines.executor.ExecutionRepository
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.TemplateType
import co.datapipelines.templates.Template
import co.datapipelines.templates.TemplateFolder
import co.datapipelines.templates.TemplateNameGrammar
import co.datapipelines.templates.TemplateRepository
import co.datapipelines.templates.TemplateService
import co.datapipelines.typesystem.Dialect
import org.springframework.ui.Model
import java.security.MessageDigest
import java.time.Instant
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
 * Since #398 there are **two instances** of the presentation, chosen by
 * [TemplateListScope]: the global sidebar's tree (the root level, or its flat search) and
 * the `/templates` catalog's flat list. Both render rows that link to the template
 * workspace; neither renders a row the lens hides.
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
    private val templates: TemplateService,
    private val usage: TemplateUsage,
    private val executions: ExecutionRepository,
    private val actors: ActorNames,
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
     *
     * **The ROOT level has no leaves at all** (§4.1, 077): a template name carries a folder,
     * so nothing sits directly at the root and the leaf query is not issued there.
     */
    fun fillLevel(
        model: Model,
        workspaceId: UUID,
        view: LensedView,
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
        if (!prefix.isNullOrEmpty() && !TemplateNameGrammar.matchesPrefix(prefix)) {
            return emptyLevel(model, prefix)
        }
        val folderProbe =
            templates.listChildFolders(workspaceId, view.templates, prefix, dialect, type, limit = FOLDER_LIMIT + 1)
        // THE ROOT HOLDS FOLDERS ONLY (§4.1, 077). A template name needs a folder, so the root
        // level has no direct children to fetch — the query is skipped rather than run and
        // discarded, and the fragment's "leaves at the root" branch is gone with it. The
        // deploy gate `V12__folder_required.sql` is what makes this true of stored data too,
        // so this is a structural consequence of the grammar, not a filter hiding rows.
        val root = prefix.isNullOrEmpty()
        val leafProbe =
            if (root) {
                emptyList()
            } else {
                templates.listChildTemplates(workspaceId, view.templates, prefix, dialect, type, offset = page, limit = PAGE_SIZE + 1)
            }
        val leaves = leafProbe.take(PAGE_SIZE)
        model.addAttribute("searching", false)
        // #398: a tree level exists only in the sidebar.
        model.addAttribute("scope", TemplateListScope.NAV.wire)
        model.addAttribute("prefix", prefix ?: "")
        model.addAttribute("levelId", levelId(prefix))
        model.addAttribute("folders", folderProbe.take(FOLDER_LIMIT).map(::TemplateFolderView))
        model.addAttribute("foldersTruncated", folderProbe.size > FOLDER_LIMIT)
        model.addAttribute("templates", leaves)
        model.addAttribute("drafts", templates.findDrafts(workspaceId, view.templates, leaves.map { it.id }))
        model.addAttribute(NEEDS_REVIEW_IDS, needReview(leaves))
        model.addAttribute("offset", page)
        model.addAttribute("hasMore", leafProbe.size > PAGE_SIZE)
        model.addAttribute("total", if (root) 0 else templates.countChildTemplates(workspaceId, view.templates, prefix, dialect, type))
        model.addAttribute(PipelineBrowseModel.LENS_UNAVAILABLE, view.unavailable)
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
        model.addAttribute(NEEDS_REVIEW_IDS, needReview(emptyList()))
        model.addAttribute("offset", 0)
        model.addAttribute("hasMore", false)
        model.addAttribute("total", 0)
        return LEVEL_VIEW
    }

    /**
     * Fills [model] for a **flat list of full paths** under the same filters, paged by the
     * shared pager against the [scope]'s root (§9.2): the sidebar's search results
     * ([TemplateListScope.NAV], `q` non-empty), or the `/templates` catalog
     * ([TemplateListScope.CATALOG], where an absent `q` lists every template the caller may
     * read — the owner's ruling for the pipelines catalog, #398's own floor).
     */
    fun fillSearch(
        model: Model,
        workspaceId: UUID,
        view: LensedView,
        q: String?,
        dialect: Dialect?,
        type: TemplateType?,
        offset: Int,
        scope: TemplateListScope = TemplateListScope.NAV,
    ): String {
        val page = maxOf(0, offset)
        val probe = templates.list(workspaceId, view.templates, dialect = dialect, type = type, q = q, offset = page, limit = PAGE_SIZE + 1)
        val items = probe.take(PAGE_SIZE)
        model.addAttribute(PipelineBrowseModel.LENS_UNAVAILABLE, view.unavailable)
        model.addAttribute("searching", true)
        model.addAttribute("scope", scope.wire)
        model.addAttribute("rootId", scope.rootId)
        model.addAttribute("templates", items)
        model.addAttribute("drafts", templates.findDrafts(workspaceId, view.templates, items.map { it.id }))
        model.addAttribute(NEEDS_REVIEW_IDS, needReview(items))
        model.addAttribute("offset", page)
        model.addAttribute("hasMore", probe.size > PAGE_SIZE)
        model.addAttribute("total", templates.count(workspaceId, view.templates, dialect = dialect, type = type, q = q))
        return SEARCH_VIEW
    }

    /**
     * Fills [model] for whichever presentation [q] and [scope] select, and returns the
     * concrete view to render — the sidebar's tree root ([TemplateListScope.NAV], `q` empty)
     * or one flat list either way (partials/templates' two-pane dispatcher is gone with
     * #398's explorer page: the catalog has no tree to return to, and the sidebar's search
     * clearing returns to its tree by the nav's own convention).
     */
    fun fillWrapper(
        model: Model,
        workspaceId: UUID,
        view: LensedView,
        q: String?,
        dialect: Dialect?,
        type: TemplateType?,
        offset: Int,
        scope: TemplateListScope = TemplateListScope.NAV,
    ): String {
        if (scope == TemplateListScope.NAV && q.isNullOrEmpty()) {
            return fillLevel(model, workspaceId, view, prefix = null, dialect = dialect, type = type, offset = offset)
        }
        return fillSearch(model, workspaceId, view, q, dialect, type, offset, scope)
    }

    // -------------------------------------------------------------------------------------
    // #398 — the detail pane is gone; the workspace page (TemplateWorkspaceModel) owns the
    // per-template facts. What stays here is the derived Runs read, which the workspace's
    // Runs tab lazy-loads.
    // -------------------------------------------------------------------------------------

    /**
     * Fills [model] for the templates twin's Runs tab — the recent executions of the pipelines
     * that pin this template.
     *
     * There is no execution → template edge in the database (an execution names a pipeline and
     * a version, not the templates its nodes rendered), so this is derived: the pins give the
     * pipelines, the pipelines give their runs, and the merged list is cut to
     * [PipelineBrowseModel.RUNS_LIMIT]. The fan-out over pipelines is capped at
     * [USED_BY_FANOUT] — a template pinned by 300 pipelines must not turn one tab into 300
     * queries, and the tab's promise is "recent runs", not "every run".
     *
     * Visibility is the execution-history screen's, through [listVisibleTo] (#275): an admin
     * sees the workspace's runs, a member with `execution.read` her own plus the SCHEDULED runs
     * (#9 R3), the promoter — who reaches this `template.read` pane — her own only.
     */
    fun fillRuns(
        model: Model,
        workspaceId: UUID,
        view: LensedView,
        id: String,
        principal: AuthenticatedPrincipal,
    ): String {
        // 178: no runs for a template the lens hides, and none from pipelines it hides.
        if (!templates.existsId(workspaceId, view.templates, id)) return fillRunRows(model, emptyList())
        val pipelineIds =
            usage
                .pipelinesReferencedAnywhere(workspaceId, view, id)
                .map { it.pipelineId }
                .distinct()
                .take(USED_BY_FANOUT)
        val rows =
            pipelineIds
                .flatMap { pipelineId ->
                    executions.listVisibleTo(principal, workspaceId, pipelineId, status = null, limit = PipelineBrowseModel.RUNS_LIMIT)
                }.sortedByDescending(ExecutionRecord::startedAt)
                .take(PipelineBrowseModel.RUNS_LIMIT)
        return fillRunRows(model, rows)
    }

    private fun fillRunRows(
        model: Model,
        rows: List<ExecutionRecord>,
    ): String {
        model.addAttribute("runs", rows)
        model.addAttribute("runActors", actors.lookup(rows.map { it.executedBy }))
        val now = Instant.now()
        model.addAttribute("runAgo", rows.associate { it.executionId to RelativeTime.since(it.startedAt, now) })
        model.addAttribute("runPipelines", rows.associate { it.executionId to it.pipelineId })
        return RUNS_VIEW
    }

    /**
     * 7e (transform-nodes design §8.2) — the ids among [rows] whose listed version reads
     * `needs_review` (it cites a retired or superseded fact), computed on read by the template
     * projection's own query. The marker renders behind this set in the tree and the search list
     * (7d); a real [HashSet], never Kotlin's internal `EmptySet`, which SpEL cannot call into.
     */
    private fun needReview(rows: List<Template>): Set<String> = rows.filter { it.needsReview }.mapTo(HashSet()) { it.id }

    companion object {
        /** The templates screen's page size — the value the flat list has always used. */
        const val PAGE_SIZE = 25

        /** Sub-folders returned for one level before the level reports an overflow rather than hiding it. */
        const val FOLDER_LIMIT = TemplateRepository.MAX_PAGE_LIMIT

        /**
         * #398 — the sidebar tree's root container id (the nav scope's stable swap root, the
         * pipelines twin's shape). The historical `#template-list-wrapper` stays the CATALOG's
         * list root ([TemplateListScope.CATALOG]).
         */
        const val ROOT_LEVEL_ID = "template-nav-root"

        /**
         * The catalog's list root — the screen's long-standing stable swap root, kept for the
         * page's search control and pager (ui-screens §4.5's contract).
         */
        const val CATALOG_ROOT_ID = "template-list-wrapper"

        const val LEVEL_VIEW = "partials/template-tree-level"
        const val SEARCH_VIEW = "partials/template-search"
        const val RUNS_VIEW = "partials/template-runs"

        /** The model attribute the needs-review marker reads in the tree and the search list (7d). */
        const val NEEDS_REVIEW_IDS = "needsReviewIds"

        /** How many pinning pipelines the Runs tab will ask for executions (see [fillRuns]). */
        const val USED_BY_FANOUT = 20

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

/**
 * #398 — which instance of the templates list a `/partials/templates` request renders, the
 * pipelines twin's shape ([PipelineListScope]).
 *
 * [NAV] is the global sidebar: the lazy tree (one level per request) and its flat search, under
 * `#template-nav-root`. [CATALOG] is the `/templates` landing page: the flat full-path list only,
 * under `#template-list-wrapper` — the page carries no tree since #398. Both render rows that
 * link to the template workspace; neither renders a row the lens hides.
 *
 * [wire] is the `scope` query value; anything but `nav` is the catalog, so an old or hand-typed
 * URL degrades to the page's own list rather than to an error.
 */
enum class TemplateListScope(
    val wire: String,
    val rootId: String,
) {
    NAV("nav", TemplateBrowseModel.ROOT_LEVEL_ID),
    CATALOG("page", TemplateBrowseModel.CATALOG_ROOT_ID),
    ;

    companion object {
        fun fromWire(value: String?): TemplateListScope = if (value == NAV.wire) NAV else CATALOG
    }
}
