package co.datapipelines.web.executions

import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.executor.ExecutionRecord
import co.datapipelines.executor.ExecutionRepository
import co.datapipelines.executor.ExecutionStatus
import co.datapipelines.executor.ResultConfig
import co.datapipelines.executor.ResultPage
import co.datapipelines.executor.ResultStore
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.ColumnSchema
import co.datapipelines.typesystem.LogicalType
import co.datapipelines.web.api.ApiErrors
import co.datapipelines.web.api.ApiException
import co.datapipelines.web.api.visibleTo
import co.datapipelines.web.metrics.WebMetrics
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * The uniform result cursor (rest-api.md §7) — `GET /executions/{id}/result`.
 *
 * One storage model, one retrieval path: every caller result was fully materialized in Redis
 * before `data_ready` existed, so paging here is a read against [ResultStore] keyed by
 * [ResultStore.keyFor] — never a live query, never a re-derivation of the key layout
 * (carry-forward #7).
 *
 * ## Rule order (§7.6)
 * Unknown **or non-owned** execution → `404 result.execution_not_found` (ownership failures are
 * indistinguishable from absence — carry-forward #2). Still running → `409
 * result.execution_incomplete`. Failed or aborted → `410 result.execution_failed`. TTL elapsed →
 * `410 result.expired`. Unknown format → `400 result.format_unsupported`.
 *
 * ## Formats (§7.5)
 * `json` pages (`offset`/`limit`, clamped by [ResultConfig]). `csv` is the full result with a
 * header row, written page by page to the response stream — never buffered whole. **`arrow` is
 * recognized but not served by this build**: no Arrow artifact exists in the version catalog and
 * hand-rolling the IPC format without one is not verifiable; the request is answered with
 * `result.format_unsupported` naming `json`/`csv`, and the missing dependency is reported to the
 * orchestrator rather than silently added. This is the one deliberate §7.5 deviation.
 */
class ResultCursor(
    private val executions: ExecutionRepository,
    private val resultStore: ResultStore,
    private val resultConfig: ResultConfig,
    private val metrics: WebMetrics,
) {
    /** The visibility + status gate every format passes through first (§7.6's row order). */
    @Suppress("ThrowsCount") // each §7.6 row is its own throw; collapsing them would obscure the table
    fun readable(
        executionId: UUID,
        principal: AuthenticatedPrincipal,
    ): ExecutionRecord {
        val record = executions.findById(principal.requireWorkspace().id, executionId)
        if (record == null || !record.visibleTo(principal)) {
            metrics.cursorRead(FORMAT_NONE, WebMetrics.OUTCOME_NOT_FOUND)
            throw ApiErrors.executionNotFound(executionId.toString())
        }
        when (record.status) {
            ExecutionStatus.RUNNING -> throw ApiErrors.executionIncomplete(executionId.toString())
            ExecutionStatus.FAILED, ExecutionStatus.ABORTED -> throw ApiErrors.executionFailed(executionId.toString())
            ExecutionStatus.SUCCESS -> Unit
        }
        return record
    }

    /** Parses the `format` parameter (§7.5/§7.6). See the class KDoc for `arrow`. */
    fun formatOf(raw: String?): String {
        val format = raw?.trim()?.lowercase() ?: FORMAT_JSON
        if (format !in FORMATS) throw ApiErrors.formatUnsupported(format, SUPPORTED_NOW)
        if (format == FORMAT_ARROW) {
            throw ApiException(
                PipelineErrorCodes.Result.FORMAT_UNSUPPORTED,
                "Result format 'arrow' is not available in this build (no Arrow encoder dependency).",
                mapOf("format" to format, "supported" to SUPPORTED_NOW, ApiErrors.REASON to "arrow_encoder_absent"),
            )
        }
        return format
    }

    /**
     * The JSON page (§7.3), or the zero-caller empty page (the same "no caller node is success,
     * not an error" reading `mcp-server` documented for §6.2.15 — rest-api §7 has no row for it).
     */
    fun jsonPage(
        record: ExecutionRecord,
        offset: Long,
        limit: Int?,
    ): Map<String, Any?> {
        val executionId = record.executionId
        if (record.resultRowCount == null) return emptyPage(executionId, offset, limit)
        val effectiveOffset = offset.coerceAtLeast(0)
        val page =
            resultStore.page(resultStore.keyFor(executionId), effectiveOffset, resultConfig.effectiveLimit(limit))
                ?: run {
                    metrics.cursorRead(FORMAT_JSON, WebMetrics.OUTCOME_EXPIRED)
                    throw ApiErrors.resultExpired(executionId.toString())
                }
        metrics.cursorRead(FORMAT_JSON, WebMetrics.OUTCOME_HIT)
        return pagePayload(page)
    }

    /**
     * Writes the full result as CSV (§7.5): header row of column names, one line per row, big
     * integers/decimals in their wire-string form. Values arrive from the store already
     * wire-encoded, so rendering is quoting, not conversion.
     *
     * The response streams page by page, so its status is committed with the first chunk: a
     * result whose TTL elapses **mid-download** (fixed expiry, §7.4 — reads never extend it)
     * surfaces as a truncated stream, not a retroactive 410. Clients near the boundary see the
     * honest `expires_at` on every JSON page and in `data_ready`.
     */
    fun writeCsv(
        record: ExecutionRecord,
        out: OutputStream,
    ) {
        val executionId = record.executionId
        if (record.resultRowCount == null) return // zero-caller: an empty 200 body
        val writer = OutputStreamWriter(out, StandardCharsets.UTF_8)
        var offset = 0L
        var headerWritten = false
        while (true) {
            val page =
                resultStore.page(resultStore.keyFor(executionId), offset, resultConfig.pageMaxRows)
                    ?: run {
                        metrics.cursorRead(FORMAT_CSV, WebMetrics.OUTCOME_EXPIRED)
                        throw ApiErrors.resultExpired(executionId.toString())
                    }
            if (!headerWritten) {
                // Column names are server-generated identifiers, not cell data — quote only.
                writer.write(page.schema.joinToString(",") { if (needsQuoting(it.name)) "\"${it.name}\"" else it.name } + "\n")
                headerWritten = true
            }
            page.rows.forEach { row ->
                writer.write(row.mapIndexed { i, value -> csvField(value, page.schema[i]) }.joinToString(",") + "\n")
            }
            writer.flush()
            offset += page.rows.size
            if (!page.hasMore || page.rows.isEmpty()) break
        }
        metrics.cursorRead(FORMAT_CSV, WebMetrics.OUTCOME_HIT)
    }

    private fun pagePayload(page: ResultPage): Map<String, Any?> =
        mapOf(
            "execution_id" to page.executionId.toString(),
            "schema" to page.schema,
            "rows" to page.rows,
            "row_count" to page.rows.size,
            "offset" to page.offset,
            "limit" to page.limit,
            "total_rows" to page.totalRows,
            "has_more" to page.hasMore,
            "expires_at" to page.expiresAt.toString(),
        )

    private fun emptyPage(
        executionId: UUID,
        offset: Long,
        limit: Int?,
    ): Map<String, Any?> =
        mapOf(
            "execution_id" to executionId.toString(),
            "schema" to emptyList<Any?>(),
            "rows" to emptyList<Any?>(),
            "row_count" to 0,
            "offset" to offset.coerceAtLeast(0),
            "limit" to resultConfig.effectiveLimit(limit),
            "total_rows" to 0L,
            "has_more" to false,
            "expires_at" to null,
        )

    /**
     * RFC 4180 quoting; null renders empty. Wire-encoded values need no further conversion.
     *
     * Formula-injection guard (gate C, F1): a cell of a **STRING** column whose first character is
     * `=`, `+`, `-`, `@` or a tab is prefixed with a single quote, so a CSV opened in a
     * spreadsheet renders it as text instead of evaluating it. The guard is scoped to
     * [LogicalType.STRING] on purpose: a `BIGINTEGER`/`BIGDECIMAL` wire-string is a number and
     * cannot legally contain formula text, so prefixing it would corrupt the §7.5 wire-string
     * form for nothing. The JSON cursor is not a spreadsheet import path and stays untouched.
     */
    private fun csvField(
        value: Any?,
        column: ColumnSchema,
    ): String {
        var text = value?.toString() ?: return ""
        if (isFormulaRisk(value, column, text)) text = "'$text"
        return if (needsQuoting(text)) "\"${text.replace("\"", "\"\"")}\"" else text
    }

    /** Only STRING columns can carry formula text; BIG* wire-strings are numbers (gate C, F1). */
    private fun isFormulaRisk(
        value: Any?,
        column: ColumnSchema,
        text: String,
    ): Boolean = column.type == LogicalType.STRING && value is String && text.isNotEmpty() && text.first() in FORMULA_PREFIXES

    private fun needsQuoting(text: String): Boolean = text.any { it in NEEDS_QUOTING }

    companion object {
        const val FORMAT_JSON = "json"
        const val FORMAT_CSV = "csv"
        const val FORMAT_ARROW = "arrow"

        /** Metrics tag for a read that never got as far as a format. */
        const val FORMAT_NONE = "none"

        /** Every token §7.5 names — recognition and rejection are separate questions. */
        val FORMATS = setOf(FORMAT_JSON, FORMAT_ARROW, FORMAT_CSV)

        /** The formats this build actually serves — see the class KDoc. */
        val SUPPORTED_NOW = listOf(FORMAT_JSON, FORMAT_CSV)

        /** The characters that force RFC 4180 quoting. */
        private val NEEDS_QUOTING = setOf(',', '"', '\n', '\r')

        /** First characters that make a spreadsheet evaluate a cell as a formula (gate C, F1). */
        private val FORMULA_PREFIXES = setOf('=', '+', '-', '@', '\t')
    }
}
