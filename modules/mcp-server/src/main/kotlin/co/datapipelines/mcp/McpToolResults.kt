package co.datapipelines.mcp

import co.datapipelines.auth.AuthErrorCodes
import co.datapipelines.auth.AuthException
import co.datapipelines.executor.ExecutorJson
import co.datapipelines.executor.PipelineExecutionFailed
import co.datapipelines.typesystem.DatapipelinesException
import io.modelcontextprotocol.spec.McpSchema
import java.util.UUID

/**
 * The one place a tool result envelope is built (mcp-server.md §6.3).
 *
 * ```json
 * {"content": [{"type": "text", "text": "…"}], "isError": false, "_meta": {"correlation_id": "…"}}
 * ```
 *
 * Success payloads are JSON-stringified into a single text content block; errors carry the
 * [REST API §4.2](../../../../../../../docs/rest-api.md) `error` object — same codes, same shape
 * as REST, so an agent sees consistent errors on both surfaces (§9.2). **Every** result, success
 * or error, echoes the request's `correlation_id` in `_meta` (§6.3) so an agent's output can be
 * handed straight to an operator.
 *
 * JSON is written through `dag`'s [ExecutorJson] mapper rather than a second local one: it
 * already encodes the type-system wire rules the result rows depend on (plain `BIGDECIMAL`
 * strings, no float round-tripping, ISO-8601 `Instant`s).
 */
object McpToolResults {
    /** `_meta` key carrying the request correlation id (§6.3, Observability §9). */
    const val META_CORRELATION_ID: String = "correlation_id"

    /** A successful result whose [payload] is serialized to JSON in one text block. */
    fun success(
        payload: Any?,
        correlationId: UUID,
    ): McpSchema.CallToolResult =
        McpSchema.CallToolResult
            .builder()
            .addTextContent(ExecutorJson.write(payload))
            .isError(false)
            .meta(meta(correlationId))
            .build()

    /** An application error (§9.2) — `isError: true`, REST §4.2 `error` object as the text block. */
    fun error(
        error: McpErrorPayload,
        correlationId: UUID,
    ): McpSchema.CallToolResult =
        McpSchema.CallToolResult
            .builder()
            .addTextContent(ExecutorJson.write(mapOf("error" to error.toMap())))
            .isError(true)
            .meta(meta(correlationId))
            .build()

    private fun meta(correlationId: UUID): Map<String, Any> = mapOf(META_CORRELATION_ID to correlationId.toString())
}

/**
 * The REST §4.2 `error` object as this surface emits it.
 *
 * `user_message` is present only when the source exception carries one ([AuthException] does);
 * inventing one would put a fabricated string in an error payload.
 *
 * 057: an **execution** failure additionally carries the failure record — the node context, the
 * rendered SQL and the exception chain — the same object `executions_get` returns from
 * `error_json`, so an agent that reads the tool error it just got has nothing further to fetch.
 * Present only when the failure is a node/execution failure under `error-detail=full`; absent
 * (never null) otherwise.
 */
data class McpErrorPayload(
    val code: String,
    val message: String,
    val userMessage: String? = null,
    val details: Map<String, Any?> = emptyMap(),
    val node: Any? = null,
    val sql: String? = null,
    val exception: Any? = null,
) {
    /** `doc_url` is derived from [code] exactly as auth derives it (REST §4.2). */
    fun toMap(): Map<String, Any?> =
        buildMap {
            put("code", code)
            put("message", message)
            userMessage?.let { put("user_message", it) }
            put("details", details)
            put("doc_url", AuthErrorCodes.docUrl(code))
            node?.let { put("node", it) }
            sql?.let { put("sql", it) }
            exception?.let { put("exception", it) }
        }

    companion object {
        /**
         * Maps a domain exception onto the payload.
         *
         * Every module in this system raises [DatapipelinesException] subclasses carrying the
         * catalogued code (module-structure §4.3), so the mapping is a projection, not a lookup
         * table that could drift from [co.datapipelines.pipeline.PipelineErrorCodes].
         */
        fun of(e: DatapipelinesException): McpErrorPayload {
            // 057: the record the executor completed at the failure site, when this failure is
            // an execution failure — everything else maps exactly as before.
            val record = (e as? PipelineExecutionFailed)?.errorRecord
            return McpErrorPayload(
                code = e.code,
                message = e.message.orEmpty(),
                userMessage = (e as? AuthException)?.userMessage,
                details = e.details,
                node = record?.node,
                sql = record?.sql,
                exception = record?.exception,
            )
        }
    }
}
