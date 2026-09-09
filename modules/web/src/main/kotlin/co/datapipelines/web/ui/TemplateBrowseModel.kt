package co.datapipelines.web.ui

import co.datapipelines.executor.ExecutionRecord
import co.datapipelines.executor.ExecutionRepository
import co.datapipelines.pipeline.TemplateType
import co.datapipelines.templates.TemplateFolder
import co.datapipelines.templates.TemplateNameGrammar
import co.datapipelines.templates.TemplateRepository
import co.datapipelines.templates.TemplateUsageService
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
    private val usage: TemplateUsageService,
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
            templates.listChildFolders(workspaceId, prefix, dialect, type, limit = FOLDER_LIMIT + 1)
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
                templates.listChildTemplates(workspaceId, prefix, dialect, type, offset = page, limit = PAGE_SIZE + 1)
            }
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
        model.addAttribute("total", if (root) 0 else templates.countChildTemplates(workspaceId, prefix, dialect, type))
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

    // -------------------------------------------------------------------------------------
    // 106 — the detail pane's three regions, the pipelines explorer's twin
    // -------------------------------------------------------------------------------------

    /**
     * Fills [model] for the SELECTED template's detail — header, reading column (Overview +
     * Used by), acting column (Versions · Source · Runs) — and returns the view name.
     *
     * Versions and Source ride the FIRST PAINT because both are already in hand: the working
     * version's body is one read and the version list is another, and a tab that costs nothing
     * extra should not cost a round trip. Runs is the one lazy tab, for the same reason as the
     * pipelines twin.
     */
    fun fillDetail(
        model: Model,
        workspaceId: UUID,
        id: String,
    ): String {
        val template = templates.findWorking(workspaceId, id)
        model.addAttribute("templateId", id)
        model.addAttribute("template", template)
        if (template == null) return DETAIL_VIEW

        val cut = id.lastIndexOf('/')
        model.addAttribute("folderPath", if (cut < 0) "" else id.substring(0, cut + 1))
        model.addAttribute("leafName", if (cut < 0) id else id.substring(cut + 1))

        val draft = templates.findDraftDetail(workspaceId, id)
        // The CURRENT RELEASE, not the working version: "current" in a version row means the
        // one a pin without a version would resolve to, which a draft never is.
        val currentRelease = templates.findLatest(workspaceId, id)?.version
        val versions = templates.listVersions(workspaceId, id)
        val names = actors.lookup(versions.map { it.createdBy })
        val inUse = usage.inUseCounts(workspaceId, id)
        val now = Instant.now()
        model.addAttribute("draftVersion", draft?.version)
        model.addAttribute("draftHash", draft?.bodyHash)
        model.addAttribute("inUse", inUse)
        model.addAttribute(
            "versions",
            versions.map { v ->
                VersionRowView.of(
                    version = v.version,
                    status = v.status,
                    createdAt = v.createdAt,
                    actor = names[v.createdBy] ?: ActorNames.fallback(v.createdBy),
                    now = now,
                    usage = inUse[v.version] ?: 0,
                    usageUnit = "pipeline",
                    isCurrent = v.version == currentRelease,
                )
            },
        )
        model.addAttribute("versionCount", versions.size)
        model.addAttribute("releasableVersion", draft?.version)
        model.addAttribute("canDelete", versions.size == 1 && draft != null)
        model.addAttribute("excerpt", excerpt(template.body))
        model.addAttribute("excerptTruncated", template.body.lineSequence().count() > EXCERPT_LINES)
        model.addAttribute("interpolations", interpolations(template.body))

        val pins = usage.referencedAnywhere(workspaceId, id)
        model.addAttribute("usedBy", pins)
        model.addAttribute("usedByCount", pins.map { it.pipelineId }.distinct().size)
        model.addAttribute("runCount", pins.map { it.pipelineId }.distinct().size)
        return DETAIL_VIEW
    }

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
     * Visibility is the execution-history screen's: an admin sees the workspace's runs,
     * everyone else their own.
     */
    fun fillRuns(
        model: Model,
        workspaceId: UUID,
        id: String,
        userId: UUID,
        isAdmin: Boolean,
    ): String {
        val pipelineIds =
            usage
                .referencedAnywhere(workspaceId, id)
                .map { it.pipelineId }
                .distinct()
                .take(USED_BY_FANOUT)
        val rows =
            pipelineIds
                .flatMap { pipelineId ->
                    if (isAdmin) {
                        executions.findAll(workspaceId, pipelineId, limit = PipelineBrowseModel.RUNS_LIMIT)
                    } else {
                        executions.findByUser(workspaceId, userId, pipelineId, limit = PipelineBrowseModel.RUNS_LIMIT)
                    }
                }.sortedByDescending(ExecutionRecord::startedAt)
                .take(PipelineBrowseModel.RUNS_LIMIT)
        model.addAttribute("runs", rows)
        model.addAttribute("runActors", actors.lookup(rows.map { it.triggeredBy }))
        val now = Instant.now()
        model.addAttribute("runAgo", rows.associate { it.executionId to RelativeTime.since(it.startedAt, now) })
        model.addAttribute("runPipelines", rows.associate { it.executionId to it.pipelineId })
        return RUNS_VIEW
    }

    /** The first [EXCERPT_LINES] lines of the current body — the Overview's peek at the source. */
    private fun excerpt(body: String): String = body.lineSequence().take(EXCERPT_LINES).joinToString("\n")

    /**
     * The distinct leading identifiers the body interpolates: `start_date` from a
     * `start_date` interpolation, `row` from a `row.borough` one.
     *
     * This is a **derived reading, not a declared contract**: a template declares no parameter
     * schema anywhere in this system (only a pipeline does), so the honest thing to show is
     * what the text references, labelled as that. Directives (`<#if …>`) are deliberately not
     * scanned — a loop variable is not an input, and listing one would invent a parameter.
     */
    private fun interpolations(body: String): List<String> =
        INTERPOLATION
            .findAll(body)
            .map { it.groupValues[1] }
            .distinct()
            .sorted()
            .toList()

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
        const val DETAIL_VIEW = "partials/template-detail"
        const val RUNS_VIEW = "partials/template-runs"

        /** The Overview's source peek — the mock's "first 12 lines, then open full source". */
        const val EXCERPT_LINES = 12

        /** How many pinning pipelines the Runs tab will ask for executions (see [fillRuns]). */
        const val USED_BY_FANOUT = 20

        /** A Freemarker interpolation's leading identifier — the scan [interpolations] runs. */
        private val INTERPOLATION = Regex("""\$\{\s*([A-Za-z_][A-Za-z0-9_]*)""")

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
