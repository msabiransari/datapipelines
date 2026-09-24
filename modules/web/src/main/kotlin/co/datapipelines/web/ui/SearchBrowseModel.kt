package co.datapipelines.web.ui

import co.datapipelines.application.lens.PromoterLens
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.Permission
import co.datapipelines.executor.ExecutionRecord
import co.datapipelines.executor.ExecutionRepository
import co.datapipelines.executor.ExecutionStatus
import co.datapipelines.pipeline.PipelineRecord
import co.datapipelines.pipeline.PipelineService
import co.datapipelines.templates.Template
import co.datapipelines.templates.TemplateService
import org.springframework.ui.Model
import java.util.UUID

/**
 * The shell search palette's model, in one place for its one controller (#155, ui-screens.md
 * §3.4).
 *
 * The palette answers the three things its placeholder always promised — pipelines, templates
 * and executions — and it answers them by READING the screens' own models' queries, never by
 * carrying a second copy of any of them:
 *
 * - **Pipelines** go through [PipelineService.list]'s `query`, the exact in-memory match over
 *   name / display name / description the REST list, the MCP list and the explorer's flat
 *   search all share (the D2 rule; the search half of `PipelineBrowseModel` goes through the
 *   same service).
 * - **Templates** go through [TemplateRepository.list]'s `q`, the same ILIKE over path /
 *   display name / description the template explorer's flat search renders
 *   (`partials/template-search`).
 * - **Executions** reuse the execution-history screen's two decisions verbatim: an admin sees
 *   the workspace's runs and everyone else their own ([ExecutionRepository.findAll] /
 *   [ExecutionRepository.findByUser] — "a second surface over the same rows must not be a
 *   wider one", `PipelineBrowseModel.fillRuns`), and the pipeline fan-out is capped the way
 *   `TemplateBrowseModel.fillRuns` caps its pinning pipelines. A `q` names executions three
 *   ways: a STATUS word (`failed`) filters by status directly; otherwise the matching
 *   PIPELINES (name, display name or id prefix) give their recent runs. That query shape did
 *   not exist on the executions list, which filters by one known pipeline id or one status —
 *   this model composes it from the two existing queries rather than adding a third to the
 *   repository (the fence's "extend them only if a query shape is missing" — none was).
 *
 * Every group is capped at [GROUP_LIMIT] with one extra row fetched so `hasMore` is a fact
 * about the data, and the "more…" row links the FILTERED list page (`?q=`, `?status=`,
 * `?pipeline_id=`) — the palette is a jump list, not a second browser.
 *
 * Declared as an explicit `@Bean` in [UiConfig], not by a stereotype: the house rule is zero
 * DI stereotypes in production code with **no** allowlist (015 / module-structure §8.4), and
 * `ArchitectureGuardTest` enforces it.
 */
class SearchBrowseModel(
    private val pipelines: PipelineService,
    private val templates: TemplateService,
    private val executions: ExecutionRepository,
    private val pipelineNames: PipelineNames,
    /** 178 — the promoter lens: all three groups are the caller's view (executions through the pipeline arm). */
    private val lens: PromoterLens,
) {
    /**
     * Fills [model] with the three groups [principal] may open in [rawQuery]'s workspace, and
     * returns the fragment view.
     *
     * A blank query fills nothing and marks `asked` false — the palette renders its hint
     * client-side and the endpoint refuses to pretend an empty box matched nothing. A null
     * principal cannot happen past the controller's `@RequiredScope`, and renders as an
     * unanswered palette rather than as rows.
     */
    fun fill(
        model: Model,
        principal: AuthenticatedPrincipal?,
        rawQuery: String,
    ): String {
        val q = rawQuery.trim()
        model.addAttribute("q", q)
        val workspace = principal?.requireWorkspace()
        if (q.isEmpty() || workspace == null) {
            model.addAttribute("asked", false)
            model.addAttribute("empty", false)
            model.addAttribute("pipelineHits", emptyList<PipelineRecord>())
            model.addAttribute("pipelinesMore", false)
            model.addAttribute("templateHits", emptyList<Template>())
            model.addAttribute("templatesMore", false)
            model.addAttribute("executionHits", emptyList<ExecutionRecord>())
            model.addAttribute("executionsMore", false)
            model.addAttribute("executionNames", emptyMap<UUID, PipelineName>())
            model.addAttribute("statusMatch", null as ExecutionStatus?)
            model.addAttribute("pipelineMatch", null as PipelineRecord?)
            return RESULTS_VIEW
        }
        model.addAttribute("asked", true)

        val workspaceId = workspace.id
        val view = lens.viewFor(principal)
        model.addAttribute(PipelineBrowseModel.LENS_UNAVAILABLE, view.unavailable)

        val pipelineProbe = pipelines.list(workspaceId, view.pipelines, query = q)
        model.addAttribute("pipelineHits", pipelineProbe.take(GROUP_LIMIT))
        model.addAttribute("pipelinesMore", pipelineProbe.size > GROUP_LIMIT)

        val templateProbe = templates.list(workspaceId, view.templates, q = q, limit = GROUP_LIMIT + 1)
        model.addAttribute("templateHits", templateProbe.take(GROUP_LIMIT))
        model.addAttribute("templatesMore", templateProbe.size > GROUP_LIMIT)

        // A q that names exactly ONE status filters by it — `failed` is how anyone says FAILED.
        // Several statuses containing the needle ("a") is not a status the user named, so the
        // word falls through to the pipeline match instead of silently OR-ing four filters.
        val statusMatch = ExecutionStatus.entries.singleOrNull { it.name.lowercase().contains(q.lowercase()) }
        val needle = q.lowercase()
        // The id-prefix arm is the one match [PipelineService.list] does not do (it matches
        // name / display name / description): a pasted pipeline UUID has no name to contain
        // it. One extra in-memory scan over THIS workspace's rows, deduplicated with the
        // service's own matches — no second copy of the name matching, no third query shape.
        val byIdPrefix = pipelines.list(workspaceId, view.pipelines).filter { it.matchesIdPrefix(needle) }
        val candidates =
            (pipelines.list(workspaceId, view.pipelines, query = q) + byIdPrefix)
                .distinctBy { it.id }
                .sortedBy(PipelineRecord::name)
        val rows =
            if (statusMatch != null) {
                recentExecutions(workspaceId, principal, statusMatch, pipelineId = null)
            } else {
                candidates
                    .take(EXECUTION_FANOUT)
                    .flatMap { recentExecutions(workspaceId, principal, status = null, pipelineId = it.id) }
                    .sortedByDescending(ExecutionRecord::startedAt)
            }
        model.addAttribute("statusMatch", statusMatch)
        model.addAttribute("pipelineMatch", candidates.singleOrNull())
        model.addAttribute("executionHits", rows.take(GROUP_LIMIT))
        model.addAttribute("executionsMore", rows.size > GROUP_LIMIT)
        model.addAttribute("executionNames", pipelineNames.lookup(workspaceId, rows.map { it.pipelineId }))

        model.addAttribute(
            "empty",
            pipelineProbe.isEmpty() && templateProbe.isEmpty() && rows.isEmpty(),
        )
        return RESULTS_VIEW
    }

    /** The history screen's visibility fork, verbatim: the workspace's runs for an admin, the principal's own otherwise. */
    private fun recentExecutions(
        workspaceId: UUID,
        principal: AuthenticatedPrincipal,
        status: ExecutionStatus?,
        pipelineId: UUID?,
    ): List<ExecutionRecord> =
        if (principal.holds(Permission.EXECUTION_READ_ALL)) {
            executions.findAll(workspaceId, pipelineId, status, limit = GROUP_LIMIT + 1)
        } else {
            executions.findByUser(workspaceId, principal.userId, pipelineId, status, limit = GROUP_LIMIT + 1)
        }

    /** The id-prefix arm's match: a pasted pipeline UUID's prefix, case-insensitive. */
    private fun PipelineRecord.matchesIdPrefix(needle: String): Boolean = id.toString().startsWith(needle, ignoreCase = true)

    companion object {
        /**
         * The palette's per-group ceiling — the brief's "≤ 8 each" — with the probe fetching
         * one extra so `…More` is a fact rather than an estimate.
         */
        const val GROUP_LIMIT = 8

        /**
         * How many matching pipelines the executions group asks for recent runs, the
         * `TemplateBrowseModel.USED_BY_FANOUT` idiom: a query matching 300 pipelines must not
         * turn one keystroke into 300 queries, and the palette's promise is "recent runs of
         * what you probably meant", not "every run".
         */
        const val EXECUTION_FANOUT = 8

        /** The fragment the palette swaps into its results container. */
        const val RESULTS_VIEW = "partials/search"
    }
}
