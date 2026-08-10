package co.datapipelines.mcp

import co.datapipelines.executor.ExecutionRecord
import co.datapipelines.executor.ExecutionRepository
import co.datapipelines.executor.ExecutionStatus
import co.datapipelines.executor.ExecutorJson
import co.datapipelines.executor.ResultConfig
import co.datapipelines.executor.ResultPage
import co.datapipelines.executor.ResultStore
import co.datapipelines.executor.ResultUrlFactory
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException
import io.modelcontextprotocol.spec.McpSchema
import java.util.UUID

/**
 * `executions_get_result` (mcp-server.md §6.2.15). Scope: `read` + ownership.
 *
 * A **thin adapter over the REST cursor** ([REST API §7](../../../../../../../docs/rest-api.md))
 * with identical semantics and identical guarantees: `offset`/`limit`/`format` map one-to-one onto
 * the cursor's query parameters, order is stable because the result was fully materialized before
 * the cursor existed, the TTL is fixed at result-write time and reading pages never extends it.
 *
 * ## The 1 MB inline cap
 *
 * `format: "arrow"`/`"csv"`, and any JSON payload whose encoding would exceed [INLINE_CAP_BYTES],
 * are **not** inlined: the tool returns the cursor URL instead. Megabytes of base64 in a tool
 * result poison an agent's context window for no benefit.
 *
 * The cap is enforced in two stages, and the order matters for more than tidiness. A `csv`/`arrow`
 * request never inlines anything, so fetching the page first meant pulling up to
 * `datapipelines.result.max-size-bytes` (100 MB) out of Redis and discarding it for a ~200-byte
 * reference — a request-amplification lever. So the format decision, and a **stored-metadata**
 * estimate of the page size ([exceedsCapByStoredSize]), both run *before* any page is read; the
 * exact measurement on the actual encoded payload still runs afterwards, because an estimate that
 * is wrong in the generous direction is exactly the failure the cap exists to prevent.
 *
 * ## Reported spec gap
 *
 * §6.2.15's error table has no row for a **successful execution that produced no caller result**
 * (a legal pure write-back pipeline, pipeline-contract §9). Returning `result.expired` or
 * `result.execution_failed` would both be false. This tool returns the documented JSON body with
 * an empty schema, no rows and `total_rows: 0` — truthful, and the same "zero caller nodes is
 * success, not an error" reading §6.2.3 applies to `pipelines_execute`.
 */
class ExecutionsGetResultTool(
    private val executions: ExecutionRepository,
    private val resultStore: ResultStore,
    private val resultUrls: ResultUrlFactory,
    private val resultConfig: ResultConfig = ResultConfig(),
    /**
     * How an execution id maps to its stored-result key.
     *
     * `ResultStore` exposes no `keyFor(executionId)` — `RedisResultStore` builds `dp:result:{id}`
     * from a private constant — so the derivation is injected rather than copied as a literal into
     * a second module. `app` overrides it the day `dag` publishes the authoritative mapping; that
     * addition is reported to the orchestrator (the REST cursor in `web` needs exactly the same
     * thing).
     */
    private val resultKey: (UUID) -> String = { "dp:result:$it" },
) : McpTool {
    override val definition: McpSchema.Tool =
        McpTools.tool(
            name = "executions_get_result",
            description =
                "Fetch result rows for a completed execution, paginated via offset+limit. Returns schema + rows + " +
                    "pagination metadata. Works for ANY completed execution that produced a caller result, of any size, " +
                    "until its TTL expires (default 300s, set at execution time). Order is stable across pages. Reading " +
                    "pages does NOT extend the TTL — after expiry the result is gone and the pipeline must be re-run.",
            schema =
                """
                {
                  "type": "object",
                  "required": ["execution_id"],
                  "properties": {
                    "execution_id": {"type": "string", "format": "uuid"},
                    "offset": {"type": "integer", "default": 0, "minimum": 0},
                    "limit": {
                      "type": "integer", "default": 1000, "minimum": 1, "maximum": 100000,
                      "description": "Rows per page. Defaults to the server's result page size."
                    },
                    "format": {"type": "string", "enum": ["json", "arrow", "csv"], "default": "json"}
                  },
                  "additionalProperties": false
                }
                """.trimIndent(),
        )

    override fun call(
        args: McpArguments,
        ctx: McpToolContext,
    ): Any {
        val executionId = args.requiredUuid("execution_id")
        val format = format(args)
        val record = readable(executionId, ctx)
        if (record.resultRowCount == null) return emptyResult(executionId, args)

        // B3: decide BEFORE materializing. A `csv`/`arrow` request, and a JSON page the stored
        // metadata already says is over the cap, are answered from a one-row probe instead of
        // pulling up to `result.max-size-bytes` (100 MB) out of Redis to throw it away.
        if (format != FORMAT_JSON || exceedsCapByStoredSize(record, args)) {
            val probe = probe(record) ?: throw expired(executionId)
            return cursorReference(executionId, format, probe.totalRows, probe.expiresAt)
        }

        val page = page(record, args) ?: throw expired(executionId)
        val payload = jsonPayload(executionId, page)
        val encoded = ExecutorJson.write(payload)
        return if (encoded.toByteArray(Charsets.UTF_8).size > INLINE_CAP_BYTES) {
            cursorReference(executionId, format, page.totalRows, page.expiresAt)
        } else {
            payload
        }
    }

    /**
     * Does the stored metadata already prove this page cannot be inlined?
     *
     * `pipeline_executions` records the whole result's `result_size_bytes` and `result_row_count`
     * (metadata-db §4.6), so the requested page's encoded size can be estimated — bytes-per-row ×
     * requested rows — without reading a single row back. The estimate only ever *skips* a fetch
     * that the exact post-serialization check at the call site would have rejected anyway, so a
     * wrong estimate costs at most one avoidable fetch, never a wrong answer.
     */
    private fun exceedsCapByStoredSize(
        record: ExecutionRecord,
        args: McpArguments,
    ): Boolean {
        val totalRows = record.resultRowCount ?: return false
        val totalBytes = record.resultSizeBytes ?: return false
        if (totalRows <= 0 || totalBytes <= 0) return false
        val offset = offsetOf(args)
        val remaining = (totalRows - offset).coerceAtLeast(0)
        val rows = minOf(limitOf(args).toLong(), remaining)
        return rows * (totalBytes / totalRows) > INLINE_CAP_BYTES
    }

    /**
     * A one-row read, purely for `total_rows` / `expires_at` / "does the result still exist".
     *
     * The cursor-reference shape has to carry a real expiry, and `ResultStore` exposes no
     * header-only read ([ResultStore.describe] drags the whole inline first page with it), so the
     * smallest honest read is a single row.
     */
    private fun probe(record: ExecutionRecord): ResultPage? = resultStore.page(resultKey(record.executionId), 0, 1)

    /**
     * §6.2.15's error rows, in the order the cursor applies them: unknown or non-owned execution →
     * `execution_not_found`; still running → `execution_incomplete`; ended in failure or abort →
     * `execution_failed` (an `ABORTED` execution has no result either, and §13.10 catalogues no
     * separate code for it).
     */
    private fun readable(
        executionId: UUID,
        ctx: McpToolContext,
    ): ExecutionRecord {
        val record = executions.findById(executionId)?.takeIf { it.visibleTo(ctx) } ?: throw McpNotFound.execution(executionId)
        notReadable(record)?.let { throw it }
        return record
    }

    /** The §6.2.15 status rows, as an exception or null when the result is readable. */
    private fun notReadable(record: ExecutionRecord): DatapipelinesException? {
        val details = mapOf("execution_id" to record.executionId.toString(), "status" to record.status.name)
        return when (record.status) {
            ExecutionStatus.RUNNING -> {
                DatapipelinesException(
                    code = PipelineErrorCodes.Result.EXECUTION_INCOMPLETE,
                    message = "Execution ${record.executionId} has not completed yet.",
                    details = details,
                )
            }

            ExecutionStatus.FAILED, ExecutionStatus.ABORTED -> {
                DatapipelinesException(
                    code = PipelineErrorCodes.Result.EXECUTION_FAILED,
                    message = "Execution ${record.executionId} ended ${record.status.name}; there is no result.",
                    details = details,
                )
            }

            ExecutionStatus.SUCCESS -> {
                null
            }
        }
    }

    private fun page(
        record: ExecutionRecord,
        args: McpArguments,
    ): ResultPage? = resultStore.page(resultKey(record.executionId), offsetOf(args), limitOf(args))

    /** `offset` — clamped at 0; a page past the end is an empty page, not an error (REST §7.3). */
    private fun offsetOf(args: McpArguments): Long = args.int("offset", default = 0, min = 0, max = Int.MAX_VALUE).toLong()

    /** `limit` — clamped into the server's own page bounds, which IS the documented behaviour. */
    private fun limitOf(args: McpArguments): Int =
        args.int("limit", default = resultConfig.pageSizeRows, min = 1, max = resultConfig.pageMaxRows)

    /** `format` is a tool error (§6.2.15 `result.format_unsupported`), not a protocol error. */
    private fun format(args: McpArguments): String {
        val format = args.string("format") ?: FORMAT_JSON
        if (format !in FORMATS) {
            throw DatapipelinesException(
                code = PipelineErrorCodes.Result.FORMAT_UNSUPPORTED,
                message = "Unknown result format '$format'.",
                details = mapOf("format" to format, "supported" to FORMATS.toList()),
            )
        }
        return format
    }

    private fun jsonPayload(
        executionId: UUID,
        page: ResultPage,
    ): Map<String, Any?> =
        mapOf(
            "execution_id" to executionId.toString(),
            "schema" to page.schema,
            "rows" to page.rows,
            "row_count" to page.rows.size,
            "offset" to page.offset,
            "limit" to page.limit,
            "total_rows" to page.totalRows,
            "has_more" to page.hasMore,
            "expires_at" to page.expiresAt,
        )

    /** The §6.2.15 non-inline reference: the REST cursor URL the agent fetches with the same API key. */
    private fun cursorReference(
        executionId: UUID,
        format: String,
        totalRows: Long,
        expiresAt: Any?,
    ): Map<String, Any?> =
        mapOf(
            "result_url" to resultUrls.urlFor(executionId),
            "expires_at" to expiresAt,
            "format" to format,
            "total_rows" to totalRows,
            "reason" to REASON_TOO_LARGE,
        )

    private fun emptyResult(
        executionId: UUID,
        args: McpArguments,
    ): Map<String, Any?> =
        mapOf(
            "execution_id" to executionId.toString(),
            "schema" to emptyList<Any?>(),
            "rows" to emptyList<Any?>(),
            "row_count" to 0,
            "offset" to offsetOf(args),
            "limit" to limitOf(args),
            "total_rows" to 0,
            "has_more" to false,
            "expires_at" to null,
        )

    private fun expired(executionId: UUID): DatapipelinesException =
        DatapipelinesException(
            code = PipelineErrorCodes.Result.EXPIRED,
            message = "The result of execution $executionId has expired; re-run the pipeline.",
            details = mapOf("execution_id" to executionId.toString()),
        )

    private companion object {
        const val FORMAT_JSON = "json"
        val FORMATS = setOf(FORMAT_JSON, "arrow", "csv")

        /** §6.2.15 — the inline cap on an encoded tool payload. */
        const val INLINE_CAP_BYTES = 1024 * 1024

        /** The one `reason` string §6.2.15 defines for a non-inlined payload. */
        const val REASON_TOO_LARGE = "payload_exceeds_inline_cap"
    }
}
