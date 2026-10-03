package co.datapipelines.web.ui

import co.datapipelines.application.lens.LensedView
import co.datapipelines.parameters.EvaluationDetail
import co.datapipelines.parameters.EvaluationSummary
import co.datapipelines.parameters.ParameterEvaluationRepository
import co.datapipelines.parameters.ParameterEvaluationStatus
import co.datapipelines.parameters.ParameterOutcome
import co.datapipelines.parameters.ParameterSetService
import co.datapipelines.parameters.QueryAttemptRecord
import org.springframework.http.HttpStatus
import org.springframework.ui.Model
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * The Parameter Sets workspace's History tab (#376; the workspace spec §6.4, R3) — ONE model for the page that paints the
 * first page ([ParameterSetsUiController], `?tab=history`) and the partial that pages it and opens a record
 * ([ParameterSetEvaluationsPartialController]): the house table's rows, 25 per page, newest first, and the record detail.
 *
 * **Lensed server-side, never in a template.** Every read starts at the set's own lens oracle — the working version
 * through the caller's lens ([ParameterSetService.findWorking], the workspace page's oracle) — so a set the lens hides is
 * the family's ONE 404 here exactly as on the page. Under a narrowing lens the rows are ALSO narrowed to the versions it
 * admits (released ones): a record of a draft names the draft's parameters, which a promoter may not read. Under the
 * Everything lens every record shows, a purged version's included — the version is data, not a join.
 *
 * Every field is projected to text here (ids, names, codes, counts, UTC stamps — the headers say UTC); the templates print them with
 * `th:text` only.
 */
class ParameterSetEvaluationsBrowseModel(
    private val sets: ParameterSetService,
    private val evaluations: ParameterEvaluationRepository,
) {
    /** One page of the set's records into [model]; answers the history fragment's view name. */
    fun fillHistory(
        model: Model,
        workspaceId: UUID,
        view: LensedView,
        parameterSetId: UUID,
        offset: Int,
    ): String {
        val versions = admittedVersions(workspaceId, view, parameterSetId)
        val from = offset.coerceAtLeast(0)
        val rows = evaluations.page(workspaceId, parameterSetId, versions, PAGE_SIZE + 1, from)
        model.addAttribute("historySetId", parameterSetId)
        model.addAttribute("evaluations", rows.take(PAGE_SIZE).map(::row))
        model.addAttribute("historyOffset", from)
        model.addAttribute("historyPageSize", PAGE_SIZE)
        model.addAttribute("historyHasMore", rows.size > PAGE_SIZE)
        model.addAttribute("historyNextOffset", from + PAGE_SIZE)
        model.addAttribute("historyPreviousOffset", (from - PAGE_SIZE).coerceAtLeast(0))
        return HISTORY_VIEW
    }

    /** One record's detail into [model] — the header, the per-parameter outcomes, the statement attempts. */
    fun fillDetail(
        model: Model,
        workspaceId: UUID,
        view: LensedView,
        parameterSetId: UUID,
        evaluationId: UUID,
    ): String {
        val versions = admittedVersions(workspaceId, view, parameterSetId)
        val detail = evaluations.find(workspaceId, parameterSetId, evaluationId, versions) ?: throw notFound(EVALUATION_NOT_FOUND)
        model.addAttribute("evaluation", header(detail))
        model.addAttribute("evaluationOutcomes", detail.outcomes.map(::outcome))
        model.addAttribute("evaluationQueries", evaluations.queries(evaluationId).map(::query))
        return DETAIL_VIEW
    }

    /** The set's lens oracle, then the versions the lens admits — null under the Everything lens (every version). */
    private fun admittedVersions(
        workspaceId: UUID,
        view: LensedView,
        parameterSetId: UUID,
    ): List<Int>? {
        val lens = view.parameterSets
        sets.findWorking(workspaceId, lens, parameterSetId) ?: throw notFound(SET_NOT_FOUND)
        return if (lens.isEverything) null else sets.listVersions(workspaceId, lens, parameterSetId).map { it.version }
    }

    /** The workspace page's own 404 (its message, word for word), so the page and the partial cannot be told apart. */
    private fun notFound(reason: String) = ResponseStatusException(HttpStatus.NOT_FOUND, reason)

    private fun row(summary: EvaluationSummary) =
        HistoryRow(
            id = summary.id.toString(),
            started = stamp(summary.startedAt),
            startedIso = summary.startedAt.toString(),
            caller = summary.caller.name,
            principal = principal(summary),
            version = "v${summary.version}",
            status = summary.status.name,
            statusTone = tone(summary.status),
            valid = summary.valid?.let { if (it) "yes" else "no" } ?: NONE,
            outcomes = summary.outcomeCount,
            queries = summary.queryCount,
            took = summary.tookMillis?.let { "$it ms" } ?: NONE,
        )

    private fun header(detail: EvaluationDetail): DetailHeader {
        val summary = detail.summary
        return DetailHeader(
            row = row(summary),
            correlationId = summary.correlationId,
            outcomeCode = summary.outcomeCode,
            finished = summary.finishedAt?.let(::stamp) ?: NONE,
        )
    }

    private fun outcome(outcome: ParameterOutcome) =
        OutcomeRow(outcome.name, outcome.outcome, outcome.errorCode ?: NONE, outcome.detail ?: NONE)

    private fun query(record: QueryAttemptRecord) =
        QueryRow(
            parameter = record.parameter,
            datasource = record.datasource,
            template = "${record.templateId} v${record.templateVersion}",
            queued = record.queuedAt?.let(::precise) ?: NONE,
            started = record.startedAt?.let(::precise) ?: NONE,
            ended = record.endedAt?.let(::precise) ?: NONE,
            outcome = record.outcome?.name ?: IN_FLIGHT,
            rows = record.rowCount?.toString() ?: NONE,
            code = record.refusalCode ?: record.errorCode ?: NONE,
        )

    private fun principal(summary: EvaluationSummary): String =
        summary.principalName ?: summary.principalKeyId?.let { "key $it" } ?: summary.principalUserId?.toString() ?: NONE

    /** One row of the History tab, every cell already text. */
    data class HistoryRow(
        val id: String,
        val started: String,
        val startedIso: String,
        val caller: String,
        val principal: String,
        val version: String,
        val status: String,
        /** `ok`, `bad`, `run` or `warn` — the house chip's tone. */
        val statusTone: String,
        val valid: String,
        val outcomes: Int,
        val queries: Int,
        val took: String,
    )

    /** The record detail's header: the row plus what only the detail shows. */
    data class DetailHeader(
        val row: HistoryRow,
        val correlationId: String?,
        val outcomeCode: String?,
        val finished: String,
    )

    /** One parameter's end, as `outcomes_json` recorded it. */
    data class OutcomeRow(
        val name: String,
        val outcome: String,
        val errorCode: String,
        val detail: String,
    )

    /** One statement attempt — the three stamps to the millisecond, so concurrent attempts visibly overlap. */
    data class QueryRow(
        val parameter: String,
        val datasource: String,
        val template: String,
        val queued: String,
        val started: String,
        val ended: String,
        val outcome: String,
        val rows: String,
        val code: String,
    )

    companion object {
        /** The spec's 25 (§6.4) — the dashboards' and the parameter-set catalog's page size. */
        const val PAGE_SIZE = 25

        const val HISTORY_VIEW = "partials/parameter-set-evaluations :: history"
        const val DETAIL_VIEW = "partials/parameter-set-evaluation :: detail"

        /** The workspace page's 404 text ([ParameterSetsWorkspaceModel]) — the same words, so the two are one answer. */
        const val SET_NOT_FOUND = "Parameter set not found"
        const val EVALUATION_NOT_FOUND = "Evaluation not found"

        private const val NONE = "—"
        private const val IN_FLIGHT = "in flight"

        private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC)
        private val PRECISE = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneOffset.UTC)

        private fun stamp(instant: Instant): String = STAMP.format(instant)

        private fun precise(instant: Instant): String = PRECISE.format(instant)

        private fun tone(status: ParameterEvaluationStatus): String =
            when (status) {
                ParameterEvaluationStatus.COMPLETED -> "ok"
                ParameterEvaluationStatus.FAILED, ParameterEvaluationStatus.TIMEOUT -> "bad"
                ParameterEvaluationStatus.RUNNING -> "run"
                ParameterEvaluationStatus.ABORTED, ParameterEvaluationStatus.INCOMPLETE -> "warn"
            }
    }
}
