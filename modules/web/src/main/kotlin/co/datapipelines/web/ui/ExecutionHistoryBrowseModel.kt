package co.datapipelines.web.ui

import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.Scope
import co.datapipelines.executor.ExecutionRepository
import co.datapipelines.executor.ExecutionStatus
import co.datapipelines.pipeline.PipelineRepository
import org.springframework.ui.Model
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

/**
 * The execution-history screen's model, in one place for the page controller and the partial
 * controller (097 §B; ui-screens.md §5's BrowseModel rule).
 *
 * The screen used to be the tree's third first-paint idiom: the page rendered a spinner and
 * `hx-trigger="load"` fetched the rows in a second round trip, so `/executions` was an empty
 * frame until a request the browser had not yet made came back. It now renders the shell AND
 * the initial fragment like every other list screen — through this model, which is also what
 * lets the page honour the filters in a shared `/executions?status=FAILED` link instead of
 * silently listing everything.
 *
 * Declared as an explicit `@Bean` in [UiConfig], not by a stereotype (015 / module-structure
 * §8.4; `ArchitectureGuardTest` enforces it).
 */
class ExecutionHistoryBrowseModel(
    private val executions: ExecutionRepository,
    private val pipelineNames: PipelineNames,
    /**
     * Only for §8's draft-run markers, read in ONE batched query for the page — the same
     * dependency and the same derivation `ExecutionsController` uses for the REST `draft_run`
     * field. Since D55 a v1 run is routinely a draft run, so the column has to say which.
     */
    private val pipelines: PipelineRepository,
) {
    /**
     * Fills [model] with one page of the history [principal] may see, and returns the fragment
     * view — the page renders it inline, the partial returns it whole.
     *
     * One row MORE than the page is fetched so `hasMore` is a fact about the data rather than
     * an estimate. A filter value that cannot be parsed — a status the enum dropped, a date the
     * browser never produced — degrades to NO filter rather than a 500: this is a listing, and
     * showing everything is a recoverable surprise where an error page is not.
     */
    @Suppress("LongParameterList") // the filter bar's fields, one parameter each (the §5 idiom)
    fun fillList(
        model: Model,
        principal: AuthenticatedPrincipal,
        pipelineId: java.util.UUID?,
        status: String?,
        startedAfter: String?,
        startedBefore: String?,
        offset: Int,
    ): String {
        val workspaceId = principal.requireWorkspace().id
        val isAdmin = principal.isWorkspaceAdmin
        val wanted = status?.let { runCatching { ExecutionStatus.valueOf(it.trim().uppercase()) }.getOrNull() }
        val page = maxOf(0, offset)
        val raw =
            if (isAdmin) {
                executions.findAll(
                    workspaceId,
                    pipelineId,
                    wanted,
                    instantOf(startedAfter, endOfDay = false),
                    instantOf(startedBefore, endOfDay = true),
                    limit = PAGE_SIZE + 1,
                    offset = page,
                )
            } else {
                executions.findByUser(
                    workspaceId,
                    principal.userId,
                    pipelineId,
                    wanted,
                    instantOf(startedAfter, endOfDay = false),
                    instantOf(startedBefore, endOfDay = true),
                    limit = PAGE_SIZE + 1,
                    offset = page,
                )
            }

        val items = raw.take(PAGE_SIZE)
        val hasMore = raw.size > PAGE_SIZE
        val releasedAt = pipelines.releasedAtFor(workspaceId, items.map { it.pipelineId to it.pipelineVersion })
        model.addAttribute("executions", items)
        model.addAttribute(
            "draftRuns",
            items.associate { row ->
                val at = releasedAt[row.pipelineId to row.pipelineVersion]
                row.executionId to (at == null || row.startedAt.isBefore(at))
            },
        )
        model.addAttribute("pipelineNames", pipelineNames.lookup(workspaceId, items.map { it.pipelineId }))
        model.addAttribute("offset", page)
        model.addAttribute("pageSize", PAGE_SIZE)
        model.addAttribute("hasMore", hasMore)
        model.addAttribute("nextOffset", if (hasMore) page + PAGE_SIZE else null)
        return LIST_VIEW
    }

    /**
     * The filter bar's `<input type="date">` posts `2026-09-09`, which `Instant.parse` rejects
     * — every date filter on this screen used to be a `DateTimeParseException` and a 500 page.
     * A bare date is read in UTC, and a `before` date is INCLUSIVE of the day the operator
     * picked (they chose a day, not a midnight), so the two pickers select a closed range.
     * A full ISO instant still parses; anything else filters nothing, like an unknown status.
     */
    private fun instantOf(
        raw: String?,
        endOfDay: Boolean,
    ): Instant? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching { Instant.parse(value) }.getOrNull()
            ?: runCatching {
                val date = LocalDate.parse(value)
                (if (endOfDay) date.plusDays(1) else date).atStartOfDay(ZoneOffset.UTC).toInstant()
            }.getOrElse { if (it is DateTimeParseException) null else throw it }
    }

    companion object {
        /** The history screen's page size — the value the partial has always used. */
        const val PAGE_SIZE = 20

        /** The fragment both the page's first paint and every later refresh render. */
        const val LIST_VIEW = "partials/executions"
    }
}
