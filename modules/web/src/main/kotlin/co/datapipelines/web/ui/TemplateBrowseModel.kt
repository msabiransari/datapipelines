package co.datapipelines.web.ui

import co.datapipelines.application.lens.LensedView
import co.datapipelines.application.templates.TemplateUsage
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.executor.ExecutionRecord
import co.datapipelines.executor.ExecutionRepository
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.SearchNeedle
import co.datapipelines.pipeline.TemplateType
import co.datapipelines.templates.Template
import co.datapipelines.templates.TemplateService
import co.datapipelines.typesystem.Dialect
import org.springframework.ui.Model
import java.time.Instant
import java.util.UUID

/**
 * The templates browser's model, in one place for the page controller and the partial
 * controller (template-hierarchy-design §9.2).
 *
 * The `/templates` catalog (#398) is a flat list of full paths under `#template-list-wrapper`:
 * every template when `q` is empty, the matches otherwise — not a tree pruned to matching
 * leaves, because pruning means walking the ancestors of every match, and a flat list of full
 * paths is what someone searching `finance/agg` wants to see (§9.2, decided). Every row links
 * to the template workspace; none is a row the lens hides. The folder tree is the global
 * sidebar's, read from `/api/v1/templates/tree` since #460; its server-rendered levels
 * (`scope=nav`, `prefix`) are gone with #466.
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
     * Fills [model] for the catalog's **flat list of full paths** under the filters, paged by
     * the shared pager against [CATALOG_ROOT_ID] (§9.2), and returns the view: the matches of
     * `q`, or — `q` absent — every template the caller may read (the owner's ruling for the
     * pipelines catalog, #398's own floor).
     *
     * Also stamps `filtered` (#510): whether ANY of `q`, `dialect` or `type` stands — the
     * flag the fragment's empty states read to choose "nothing exists" vs "nothing
     * matched" (§5.1), never `q` alone.
     */
    fun fillSearch(
        model: Model,
        workspaceId: UUID,
        view: LensedView,
        q: String?,
        dialect: Dialect?,
        type: TemplateType?,
        offset: Int,
    ): String {
        val page = maxOf(0, offset)
        // A box truncates its own term to the shared bound (#504b) before the call, never refuses;
        // the service owns the refusal for REST and MCP.
        val needle = q?.trim()?.takeIf { it.isNotEmpty() }?.take(SearchNeedle.MAX_LENGTH)
        val probe =
            templates.list(
                workspaceId,
                view.templates,
                dialect = dialect,
                type = type,
                q = needle,
                offset = page,
                limit = PAGE_SIZE + 1,
            )
        val items = probe.take(PAGE_SIZE)
        model.addAttribute(PipelineBrowseModel.LENS_UNAVAILABLE, view.unavailable)
        model.addAttribute("searching", true)
        model.addAttribute("rootId", CATALOG_ROOT_ID)
        model.addAttribute("templates", items)
        model.addAttribute("drafts", templates.findDrafts(workspaceId, view.templates, items.map { it.id }))
        model.addAttribute(NEEDS_REVIEW_IDS, needReview(items))
        model.addAttribute("offset", page)
        model.addAttribute("hasMore", probe.size > PAGE_SIZE)
        model.addAttribute("total", templates.count(workspaceId, view.templates, dialect = dialect, type = type, q = needle))
        // #510: the empty catalog's answer — "nothing exists" vs "nothing matched" (§5.1) —
        // reads ANY standing filter, not q alone; the fragment's clear control is chosen on this.
        model.addAttribute("filtered", needle != null || dialect != null || type != null)
        return SEARCH_VIEW
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
     * projection's own query. The marker renders behind this set in the catalog list (7d); a
     * real [HashSet], never Kotlin's internal `EmptySet`, which SpEL cannot call into.
     */
    private fun needReview(rows: List<Template>): Set<String> = rows.filter { it.needsReview }.mapTo(HashSet()) { it.id }

    companion object {
        /** The templates screen's page size — the value the flat list has always used. */
        const val PAGE_SIZE = 25

        /**
         * The catalog's list root — the screen's long-standing stable swap root, kept for the
         * page's search control and pager (ui-screens §4.5's contract).
         */
        const val CATALOG_ROOT_ID = "template-list-wrapper"

        const val SEARCH_VIEW = "partials/template-search"
        const val RUNS_VIEW = "partials/template-runs"

        /** The model attribute the needs-review marker reads in the catalog list (7d). */
        const val NEEDS_REVIEW_IDS = "needsReviewIds"

        /** How many pinning pipelines the Runs tab will ask for executions (see [fillRuns]). */
        const val USED_BY_FANOUT = 20
    }
}
