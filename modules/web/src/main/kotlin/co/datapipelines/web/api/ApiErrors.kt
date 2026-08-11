package co.datapipelines.web.api

import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * The error envelope (rest-api.md §4.2), byte-identical to the one `auth`'s
 * [co.datapipelines.auth.AuthErrorWriter] emits from the filter chain — the same shape whether a
 * request is rejected before or after handler resolution.
 */
data class ApiErrorResponse(
    @field:JsonProperty("schema_version") @get:JsonProperty("schema_version") @param:JsonProperty("schema_version")
    val schemaVersion: Int,
    @field:JsonProperty("correlation_id") @get:JsonProperty("correlation_id") @param:JsonProperty("correlation_id")
    val correlationId: String,
    @field:JsonProperty("error") @get:JsonProperty("error") @param:JsonProperty("error")
    val error: ApiErrorBody,
) {
    companion object {
        /** Builds the envelope for [code], filling `user_message` and `doc_url` from the catalog. */
        fun of(
            code: String,
            message: String,
            details: Map<String, Any?> = emptyMap(),
            userMessage: String = ApiErrorCatalog.userMessageFor(code),
        ): ApiErrorResponse =
            ApiErrorResponse(
                schemaVersion = ApiResponse.SCHEMA_VERSION,
                correlationId = CorrelationId.current(),
                error =
                    ApiErrorBody(
                        code = code,
                        message = message,
                        userMessage = userMessage,
                        details = details,
                        docUrl = ApiErrorCatalog.docUrl(code),
                    ),
            )
    }
}

/** The `error` object of [ApiErrorResponse] (rest-api §4.2). */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ApiErrorBody(
    @field:JsonProperty("code") @get:JsonProperty("code") @param:JsonProperty("code")
    val code: String,
    @field:JsonProperty("message") @get:JsonProperty("message") @param:JsonProperty("message")
    val message: String,
    @field:JsonProperty("user_message") @get:JsonProperty("user_message") @param:JsonProperty("user_message")
    val userMessage: String,
    @field:JsonProperty("details") @get:JsonProperty("details") @param:JsonProperty("details")
    val details: Map<String, Any?>,
    @field:JsonProperty("doc_url") @get:JsonProperty("doc_url") @param:JsonProperty("doc_url")
    val docUrl: String,
)

/**
 * A REST-surface failure carrying a [pipeline-contract §13] code.
 *
 * Web raises no codes of its own — every constructor below names a catalogued constant, and the
 * HTTP status comes from [ApiErrorCatalog], never from the call site. That is what keeps the
 * status for a code identical at every endpoint that can raise it.
 */
class ApiException(
    code: String,
    message: String,
    details: Map<String, Any?> = emptyMap(),
    cause: Throwable? = null,
) : DatapipelinesException(code, message, details, cause)

/**
 * Constructors for the failures this module raises.
 *
 * ## Known spec gap — reported, not papered over
 * §13 has **no code for "this request body is not JSON"**. `PipelineDeserializer`'s own KDoc says
 * so explicitly and hands the question to the REST layer, but leaves no code to answer it with,
 * and this module may not invent one (§13 is the single catalog and a spec-drift test parses it).
 * Until a transport-level code is catalogued, an unparseable body is reported with the surface's
 * `schema_version_unsupported` code — the first check a body must pass and the one an unreadable
 * body demonstrably fails — plus `details.reason = "malformed_json"` so the real cause is never
 * ambiguous. The status (400) is right either way; only the code is a stand-in.
 */
object ApiErrors {
    /** `details` key carrying why a 400 was raised when the code alone is a stand-in. */
    const val REASON = "reason"

    /** `details.reason` value for an unparseable request body. */
    const val MALFORMED_JSON = "malformed_json"

    /** The pipeline was not found, or the caller may not see it (rest-api §5.2/§5.6). */
    fun pipelineNotFound(id: String): ApiException =
        ApiException(
            PipelineErrorCodes.Execution.NOT_FOUND,
            "Pipeline '$id' not found.",
            mapOf("pipeline_id" to id),
        )

    /** A pipeline version that does not exist (rest-api §5.3). */
    fun pipelineVersionNotFound(
        id: String,
        version: Int,
    ): ApiException =
        ApiException(
            PipelineErrorCodes.Execution.NOT_FOUND,
            "Pipeline '$id' has no version $version.",
            mapOf("pipeline_id" to id, "pipeline_version" to version),
        )

    /**
     * The execution is unknown — or is another user's (rest-api §10.2, §10.4, §7.6).
     *
     * A non-owner gets exactly this, not a 403: telling a stranger that an execution id exists is
     * itself a disclosure, so ownership failures are indistinguishable from "no such execution".
     */
    fun executionNotFound(id: String): ApiException =
        ApiException(
            PipelineErrorCodes.Result.EXECUTION_NOT_FOUND,
            "Execution '$id' not found.",
            mapOf("execution_id" to id),
        )

    /**
     * A template id (or id+version) that does not exist (rest-api §8.2/§8.3) — the §13.9
     * read/mutate-path code added at gate C; a version miss carries `details.version`.
     */
    fun templateNotFound(
        id: String,
        version: Int? = null,
    ): ApiException =
        ApiException(
            PipelineErrorCodes.Template.NOT_FOUND,
            if (version == null) "Template '$id' not found." else "Template '$id' has no version $version.",
            if (version == null) mapOf("template_id" to id) else mapOf("template_id" to id, "version" to version),
        )

    /** A datasource name that does not exist (rest-api §9.3/§9.4/§9.5/§9.6) — the §13.8 code. */
    fun datasourceNotFound(name: String): ApiException =
        ApiException(
            PipelineErrorCodes.Datasource.NOT_FOUND,
            "Datasource '$name' not found.",
            mapOf("datasource_name" to name),
        )

    /** An unparseable pipeline body — see the gap note on [ApiErrors]. */
    fun malformedPipelineBody(cause: Throwable): ApiException =
        ApiException(
            PipelineErrorCodes.Validation.SCHEMA_VERSION_UNSUPPORTED,
            "Request body is not valid JSON: ${cause.message?.take(MAX_CAUSE_CHARS)}",
            mapOf(REASON to MALFORMED_JSON),
            cause,
        )

    /** An unparseable template body — see the gap note on [ApiErrors]. */
    fun malformedTemplateBody(cause: Throwable): ApiException =
        ApiException(
            PipelineErrorCodes.Template.SCHEMA_VERSION_UNSUPPORTED,
            "Request body is not valid JSON: ${cause.message?.take(MAX_CAUSE_CHARS)}",
            mapOf(REASON to MALFORMED_JSON),
            cause,
        )

    /** An unparseable datasource body — see the gap note on [ApiErrors]. */
    fun malformedDatasourceBody(cause: Throwable): ApiException =
        ApiException(
            PipelineErrorCodes.Datasource.PROPERTIES_INVALID,
            "Request body is not valid JSON: ${cause.message?.take(MAX_CAUSE_CHARS)}",
            mapOf(REASON to MALFORMED_JSON),
            cause,
        )

    /** `?format=` was not one of json/arrow/csv (rest-api §7.5/§7.6). */
    fun formatUnsupported(
        format: String,
        supported: List<String>,
    ): ApiException =
        ApiException(
            PipelineErrorCodes.Result.FORMAT_UNSUPPORTED,
            "Unknown result format '$format'.",
            mapOf("format" to format, "supported" to supported),
        )

    /** The execution has not reached a terminal state yet (rest-api §7.6). */
    fun executionIncomplete(id: String): ApiException =
        ApiException(
            PipelineErrorCodes.Result.EXECUTION_INCOMPLETE,
            "Execution '$id' has not completed.",
            mapOf("execution_id" to id),
        )

    /** The execution failed, so no result exists (rest-api §7.6). */
    fun executionFailed(id: String): ApiException =
        ApiException(
            PipelineErrorCodes.Result.EXECUTION_FAILED,
            "Execution '$id' ended in failure; there is no result to retrieve.",
            mapOf("execution_id" to id),
        )

    /** The stored result's TTL has elapsed (rest-api §7.4/§7.6). */
    fun resultExpired(id: String): ApiException =
        ApiException(
            PipelineErrorCodes.Result.EXPIRED,
            "The result for execution '$id' has expired.",
            mapOf("execution_id" to id),
        )

    /** Cancel requested for an execution that is already terminal (rest-api §10.4). */
    fun executionNotRunning(
        id: String,
        status: String,
    ): ApiException =
        ApiException(
            PipelineErrorCodes.Execution.NOT_RUNNING,
            "Execution '$id' is already $status.",
            mapOf("execution_id" to id, "status" to status),
        )

    /** Same `Idempotency-Key`, different request (rest-api §3.5). */
    fun idempotencyKeyReused(key: String): ApiException =
        ApiException(
            PipelineErrorCodes.Limits.IDEMPOTENCY_KEY_REUSED,
            "Idempotency-Key was already used with a different request body.",
            mapOf("idempotency_key" to key),
        )

    /** The per-user request rate limit (rest-api §12). */
    fun rateLimitExceeded(
        limit: Long,
        window: String,
    ): ApiException =
        ApiException(
            PipelineErrorCodes.Limits.RATE_LIMIT_EXCEEDED,
            "Per-user rate limit of $limit requests per $window exceeded.",
            mapOf("limit" to limit, "window" to window),
        )

    /** The per-user concurrent SSE stream cap (rest-api §12.1, `sse.max-streams-per-user`). */
    fun streamLimitExceeded(max: Int): ApiException =
        ApiException(
            PipelineErrorCodes.Limits.RATE_LIMIT_EXCEEDED,
            "Per-user limit of $max concurrent execution streams reached.",
            mapOf("limit" to max, "window" to "concurrent_streams"),
        )

    private const val MAX_CAUSE_CHARS = 200
}
