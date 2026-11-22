package co.datapipelines.web.endpoints

import co.datapipelines.application.endpoints.PublishedMutationReservation
import co.datapipelines.executor.ExecutionRecord
import co.datapipelines.executor.ExecutionStatus
import co.datapipelines.executor.ExecutorJson
import co.datapipelines.executor.NodeStats
import co.datapipelines.executor.ResultStore
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.web.endpoints.PublishedEndpointServeService.Outcome
import org.springframework.dao.DataAccessException

/**
 * The published-mutation terminal-state table (published-mutation-apis §4; #1039) — ONE shaping for the first answer
 * and for every authenticated identical retry, so the two can never disagree:
 *
 * | State | Answer |
 * |---|---|
 * | running (or not yet recorded) | `202` — the same execution id, `status_url`, `result_url` |
 * | SUCCESS with a live caller result | `200` — the paged result, `status_url`, `result_url`; no rerun |
 * | SUCCESS, zero caller | `200` — `status: SUCCESS`, `status_url`, a `node_stats` summary (ids, statuses, counts) |
 * | SUCCESS, rows expired | `410 result.expired` with `status: SUCCESS`, the id and `status_url` |
 * | SUCCESS, result storage unavailable | `503 result.storage_unavailable`, likewise |
 * | FAILED | `409` with the stored bounded cause code (`pipeline.script.rejects_strict`, `…pool_exhausted`, …) |
 * | ABORTED | `409 pipeline.execution.aborted` |
 * | OUTCOME_UNKNOWN | `409` with `status: OUTCOME_UNKNOWN`, its code and `status_url`; never a suggestion to replay |
 *
 * Only a bounded code, the status, the id and the status URL leave a failure: never an engine message or author
 * free text (the run's diagnostics are bounded too, `ExecuteRequest.boundedDiagnosticsRequired`). Nothing here stores
 * a copy of an answer: a retry re-reads the execution and the result store, so an expired result reads as expired.
 */
class PublishedMutationAnswers(
    private val resultStore: ResultStore,
    private val urls: PublishedMutationUrls,
) {
    /** The answer for [reservation] from its persisted execution [record] (null = not recorded yet: running). */
    fun fromRecord(
        reservation: PublishedMutationReservation,
        record: ExecutionRecord?,
        pageRows: Int,
    ): Outcome =
        when (record?.status) {
            null, ExecutionStatus.RUNNING -> running(reservation)
            ExecutionStatus.SUCCESS -> success(reservation, record.resultUnavailable, pageRows) { nodeSummary(record.nodeStatsJson) }
            ExecutionStatus.FAILED, ExecutionStatus.OUTCOME_UNKNOWN -> failed(reservation, record.status, codeOf(record.errorJson))
            ExecutionStatus.ABORTED -> failed(reservation, ExecutionStatus.ABORTED, PipelineErrorCodes.Execution.ABORTED)
        }

    /** The first answer of a SUCCESS run, from the executor's own result (the record's result columns are write-behind). */
    fun fromSuccess(
        reservation: PublishedMutationReservation,
        resultUnavailable: Boolean,
        nodeStats: List<NodeStats>,
        pageRows: Int,
    ): Outcome = success(reservation, resultUnavailable, pageRows) { nodeStats.map { summary(it.nodeId, it.status.name, it.rowsOut) } }

    /** `202`: the execution is admitted and not finished; come back on the status URL. */
    fun running(reservation: PublishedMutationReservation): Outcome =
        Outcome.Accepted(
            executionId = reservation.executionId,
            body =
                mapOf(
                    "execution_id" to reservation.executionId.toString(),
                    "status" to ExecutionStatus.RUNNING.name,
                    "status_url" to statusUrl(reservation),
                    "result_url" to urls.resultUrl(reservation.businessPath, reservation.executionId),
                ),
        )

    @Suppress("ReturnCount") // each missing piece of a stored result answers on its own terms
    private fun success(
        reservation: PublishedMutationReservation,
        resultUnavailable: Boolean,
        pageRows: Int,
        nodeStats: () -> List<Map<String, Any?>>,
    ): Outcome {
        val id = reservation.executionId
        if (resultUnavailable) return storageUnavailable(reservation)
        if (!reservation.hasCaller) {
            return Outcome.Served(
                executionId = id,
                payload =
                    mapOf(
                        "execution_id" to id.toString(),
                        "status" to ExecutionStatus.SUCCESS.name,
                        "status_url" to statusUrl(reservation),
                        "node_stats" to nodeStats(),
                    ),
            )
        }
        return try {
            val key = resultStore.keyFor(id)
            val view = resultStore.describe(key) ?: return expired(reservation)
            val page = resultStore.page(key, 0, pageRows) ?: return expired(reservation)
            Outcome.Served(
                executionId = id,
                payload =
                    mapOf(
                        "execution_id" to id.toString(),
                        "status" to ExecutionStatus.SUCCESS.name,
                        "schema" to view.schema,
                        "rows" to page.rows,
                        "row_count" to page.rows.size,
                        "total_rows" to view.totalRows,
                        "has_more" to (page.rows.size.toLong() < view.totalRows),
                        "status_url" to statusUrl(reservation),
                        "result_url" to urls.resultUrl(reservation.businessPath, id),
                        "expires_at" to view.expiresAt.toString(),
                        "warnings" to view.warnings,
                    ),
            )
        } catch (_: DataAccessException) {
            // The store did not answer: neither an expiry nor an absence. The commit stays confirmed.
            storageUnavailable(reservation)
        }
    }

    private fun failed(
        reservation: PublishedMutationReservation,
        status: ExecutionStatus,
        code: String,
    ): Outcome =
        Outcome.Refused(
            status = HTTP_CONFLICT,
            code = code,
            message =
                when (status) {
                    ExecutionStatus.OUTCOME_UNKNOWN -> {
                        "The execution's commit outcome is unknown: the target may or may not hold its writes. " +
                            "Reconcile before deciding to send a new request."
                    }

                    ExecutionStatus.ABORTED -> {
                        "The execution was aborted."
                    }

                    else -> {
                        "The execution failed; its code names the cause."
                    }
                },
            details = terminalDetails(reservation, status),
        )

    private fun expired(reservation: PublishedMutationReservation): Outcome =
        Outcome.Refused(
            HTTP_GONE,
            PipelineErrorCodes.Result.EXPIRED,
            "The execution succeeded; its result is no longer stored.",
            terminalDetails(reservation, ExecutionStatus.SUCCESS),
        )

    private fun storageUnavailable(reservation: PublishedMutationReservation): Outcome =
        Outcome.Refused(
            HTTP_SERVICE_UNAVAILABLE,
            PipelineErrorCodes.Result.STORAGE_UNAVAILABLE,
            "The execution succeeded; its result cannot be read right now.",
            terminalDetails(reservation, ExecutionStatus.SUCCESS),
        )

    private fun terminalDetails(
        reservation: PublishedMutationReservation,
        status: ExecutionStatus,
    ): Map<String, Any?> =
        mapOf(
            "execution_id" to reservation.executionId.toString(),
            "status" to status.name,
            "status_url" to statusUrl(reservation),
        )

    private fun statusUrl(reservation: PublishedMutationReservation): String =
        urls.statusUrl(reservation.businessPath, reservation.executionId)

    companion object {
        /** The persisted node stats, reduced to ids, statuses and counts — never an error message or a timing. */
        internal fun nodeSummary(nodeStatsJson: String?): List<Map<String, Any?>> {
            val tree = nodeStatsJson?.let { runCatching { ExecutorJson.mapper.readTree(it) }.getOrNull() } ?: return emptyList()
            return tree.filter { it.isObject }.map { node ->
                summary(node.path("node_id").asText(), node.path("status").asText(), node.path("rows_out").asLong())
            }
        }

        private fun summary(
            nodeId: String,
            status: String,
            rowsOut: Long,
        ): Map<String, Any?> = mapOf("node_id" to nodeId, "status" to status, "rows_out" to rowsOut)

        private const val HTTP_CONFLICT = 409
        private const val HTTP_GONE = 410
        private const val HTTP_SERVICE_UNAVAILABLE = 503

        /** The longest stored code echoed; a code is a catalog identifier, never free text. */
        private const val MAX_CODE_CHARS = 128
        private val CODE_GRAMMAR = Regex("^[a-z0-9_]+(\\.[a-z0-9_]+){1,4}$")

        /**
         * The bounded cause code of a stored failure, or `result.execution_failed` when the row carries none (or anything
         * that is not a catalog-shaped identifier — the answer never relays stored text it cannot vouch for).
         */
        internal fun codeOf(errorJson: String?): String {
            val code =
                errorJson
                    ?.let {
                        runCatching {
                            ExecutorJson.mapper
                                .readTree(it)
                                .path("code")
                                .asText("")
                        }.getOrDefault("")
                    }.orEmpty()
            return code.takeIf { it.length <= MAX_CODE_CHARS && CODE_GRAMMAR.matches(it) } ?: PipelineErrorCodes.Result.EXECUTION_FAILED
        }
    }
}
