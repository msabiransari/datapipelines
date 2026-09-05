package co.datapipelines.web.config

import jakarta.servlet.http.HttpServletRequest

/**
 * The custom-header registry (rest-api.md §3.6), for the two headers no other module owns.
 *
 * `DP-API-Key`, `DP-Correlation-Id` and `DP-CSRF-Token` are declared where they are enforced —
 * `auth`'s `ApiKeyCredential.HEADER`, `CorrelationId.HEADER` and `SecurityConfig.CSRF_HEADER` —
 * and are referenced from there rather than re-spelled here.
 */
object WebHeaders {
    /** rest-api §3.6 — the client-requested result TTL, clamped server-side (§7.4). */
    const val RESULT_TTL: String = "DP-Result-TTL-Seconds"

    /**
     * rest-api §3.6 — the client-requested INLINE first-page size (ruling R-EP4), clamped to
     * `datapipelines.result.page-max-rows`.
     *
     * **One contract, two surfaces.** It is honoured by a published endpoint AND by
     * `POST /pipelines/{id}/execute`, which is why it lives here beside the TTL header rather
     * than in the endpoint package: a client that learns the header on one surface must not
     * find it ignored on the other.
     */
    const val RESULT_PAGE_ROWS: String = "DP-Result-Page-Rows"

    /** rest-api §3.6 — the execution a published endpoint's response belongs to (§19.4). */
    const val EXECUTION_ID: String = "DP-Execution-Id"

    /** rest-api §3.5 — a de-facto standard header, deliberately NOT `DP-`-prefixed. */
    const val IDEMPOTENCY_KEY: String = "Idempotency-Key"
}

/**
 * The client's requested result TTL, or null when the header is absent or unparseable.
 *
 * An unparseable value is treated as absent rather than as a 400: the server clamps the value
 * anyway (rest-api §7.4), so the worst outcome of ignoring garbage is the documented default.
 */
fun HttpServletRequest.requestedResultTtlSeconds(): Long? = getHeader(WebHeaders.RESULT_TTL)?.trim()?.toLongOrNull()

/**
 * The client's requested inline first-page size, or null when the header is absent or
 * unparseable (rest-api §3.6, R-EP4).
 *
 * Unparseable reads as absent for the same reason [requestedResultTtlSeconds] does: the server
 * clamps the value anyway, so the worst outcome of ignoring garbage is the documented default.
 */
fun HttpServletRequest.requestedResultPageRows(): Int? = getHeader(WebHeaders.RESULT_PAGE_ROWS)?.trim()?.toIntOrNull()

/** The client's `Idempotency-Key`, trimmed; null when absent or blank (rest-api §3.5). */
fun HttpServletRequest.idempotencyKey(): String? = getHeader(WebHeaders.IDEMPOTENCY_KEY)?.trim()?.takeIf { it.isNotEmpty() }
