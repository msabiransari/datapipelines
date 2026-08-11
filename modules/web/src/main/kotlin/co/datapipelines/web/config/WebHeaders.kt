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

/** The client's `Idempotency-Key`, trimmed; null when absent or blank (rest-api §3.5). */
fun HttpServletRequest.idempotencyKey(): String? = getHeader(WebHeaders.IDEMPOTENCY_KEY)?.trim()?.takeIf { it.isNotEmpty() }
