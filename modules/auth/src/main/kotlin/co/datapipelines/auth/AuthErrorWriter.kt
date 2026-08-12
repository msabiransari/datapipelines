package co.datapipelines.auth

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Writes the **full** [REST API §4.2](../../../../../../../docs/rest-api.md) error
 * envelope:
 *
 * ```json
 * {"schema_version": 1, "correlation_id": "…",
 *  "error": {"code": …, "message": …, "user_message": …, "details": {…}, "doc_url": …}}
 * ```
 *
 * Every auth rejection surface — [AuthEntryPoint], [AuthAccessDeniedHandler],
 * [ScopeInterceptor], [LoginRateLimitFilter] — emits through here, so one shape and
 * one correlation rule serve them all.
 *
 * ## Correlation (REST API §3.4, Observability §3.3)
 * The id is taken from the `correlation_id` MDC slot first — `web`'s
 * `CorrelationIdFilter` populates it ahead of the security chain, having already
 * sanitized the inbound header — then from the inbound `DP-Correlation-Id` header
 * **only when it parses as a UUID**, else generated. Taking the raw header first
 * would echo attacker-controlled text on 401/403/429 responses, defeating the
 * filter's sanitize-then-echo design on exactly the paths that need it; the UUID
 * gate is the fallback for contexts where the filter never ran (auth-only slice
 * tests). The id is always echoed back on the response header, so a client that
 * only sees a 401 can still quote an id.
 *
 * ## Reuse
 * The class is public and framework-agnostic beyond the servlet API on purpose: the
 * `web` module (P6a) writes non-auth errors through this same writer rather than
 * growing a second envelope implementation.
 */
@Component
class AuthErrorWriter(
    private val objectMapper: ObjectMapper,
) {
    /**
     * Writes [status] with the full envelope. [details] is the code-specific
     * structured payload; [userMessage] is the non-technical text safe to show a
     * user. `doc_url` is derived from [code] ([AuthErrorCodes.docUrl]).
     */
    @Suppress("LongParameterList")
    fun write(
        request: HttpServletRequest,
        response: HttpServletResponse,
        status: Int,
        code: String,
        message: String,
        userMessage: String,
        details: Map<String, Any?> = emptyMap(),
    ) {
        if (response.isCommitted) return
        val correlationId = correlationId(request)
        response.status = status
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = Charsets.UTF_8.name()
        response.setHeader(CORRELATION_HEADER, correlationId)
        val body =
            mapOf(
                "schema_version" to SCHEMA_VERSION,
                "correlation_id" to correlationId,
                "error" to
                    mapOf(
                        "code" to code,
                        "message" to message,
                        "user_message" to userMessage,
                        "details" to details,
                        "doc_url" to AuthErrorCodes.docUrl(code),
                    ),
            )
        response.writer.write(objectMapper.writeValueAsString(body))
    }

    /** Writes the envelope for an [AuthException], which already carries every field. */
    fun write(
        request: HttpServletRequest,
        response: HttpServletResponse,
        error: AuthException,
    ) = write(
        request = request,
        response = response,
        status = error.status,
        code = error.code,
        message = error.message ?: error.code,
        userMessage = error.userMessage,
        details = error.details,
    )

    private fun correlationId(request: HttpServletRequest): String =
        MDC.get(MDC_KEY)?.takeIf { it.isNotBlank() }
            ?: request.getHeader(CORRELATION_HEADER)?.let(::parseUuidOrNull)?.toString()
            ?: UUID.randomUUID().toString()

    /** UUID-shaped [value], or null — the adoption gate for attacker-controlled header text. */
    private fun parseUuidOrNull(value: String): UUID? =
        value.trim().takeIf { it.isNotEmpty() }?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    companion object {
        /** REST API §3.4 / §3.6 — the correlation header, echoed on every response. */
        const val CORRELATION_HEADER = "DP-Correlation-Id"

        /** Observability §3.1 — the MDC slot the correlation id lives in. */
        const val MDC_KEY = "correlation_id"

        /** REST API §4.1/§4.2 — the response envelope version. */
        const val SCHEMA_VERSION = 1
    }
}
