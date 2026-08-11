package co.datapipelines.web.api

import co.datapipelines.auth.AuthErrorWriter
import org.slf4j.MDC
import java.util.UUID

/**
 * The request's correlation id (rest-api.md §3.4, observability.md §3.3 — normative).
 *
 * One value per request, established once by [co.datapipelines.web.CorrelationIdFilter] and read
 * from the MDC everywhere else. Nothing else in this module generates one: the id in the response
 * header, in the success/error envelope, in every log line of the request, on every SSE event
 * payload of an execution the request started, and in `pipeline_executions.correlation_id` must
 * all be the *same* value, and the only way to guarantee that is a single origin.
 *
 * ## Why the id is always a UUID
 * `pipeline_executions.correlation_id` is a `UUID` column (metadata-db §4.6) and
 * [co.datapipelines.executor.ExecuteRequest.correlationId] is a `UUID?`, so a free-form inbound
 * string could not be carried past the HTTP boundary — which is precisely where observability
 * §3.3 says it must go. An inbound `DP-Correlation-Id` that parses as a UUID is therefore
 * adopted; anything else is replaced by a fresh UUID (the same rule `mcp-server`'s
 * `McpAuthFilter` already applies, so the two surfaces agree). Replacing rather than echoing also
 * keeps attacker-controlled text out of the response header and the log stream.
 */
object CorrelationId {
    /** rest-api §3.4 / §3.6 — the request+response header. Shared with `auth` so both agree. */
    const val HEADER: String = AuthErrorWriter.CORRELATION_HEADER

    /** observability §3.1 — the MDC slot. Shared with `auth`'s error writer. */
    const val MDC_KEY: String = AuthErrorWriter.MDC_KEY

    /**
     * This request's correlation id as a string, or a fresh one when called outside a request.
     *
     * The fallback is deliberate rather than an error: an envelope with *some* id beats a 500
     * raised while composing a response, and the filter covers every servlet-dispatched path.
     */
    fun current(): String = MDC.get(MDC_KEY)?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()

    /** This request's correlation id as a UUID, or null when the slot is empty or unparseable. */
    fun currentUuid(): UUID? = MDC.get(MDC_KEY)?.let(::parseOrNull)

    /** Parses [value] as a UUID, returning null instead of throwing. */
    fun parseOrNull(value: String?): UUID? =
        value?.trim()?.takeIf { it.isNotEmpty() }?.let {
            runCatching { UUID.fromString(it) }.getOrNull()
        }

    /** Adopts an inbound header value when it is a UUID; otherwise mints a fresh one. */
    fun resolve(headerValue: String?): UUID = parseOrNull(headerValue) ?: UUID.randomUUID()

    /** Runs [body] with [id] installed in the MDC, restoring the previous value afterwards. */
    fun <T> withId(
        id: UUID,
        body: () -> T,
    ): T {
        val previous = MDC.get(MDC_KEY)
        MDC.put(MDC_KEY, id.toString())
        return try {
            body()
        } finally {
            if (previous == null) MDC.remove(MDC_KEY) else MDC.put(MDC_KEY, previous)
        }
    }
}
