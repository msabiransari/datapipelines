package co.datapipelines.auth

import co.datapipelines.typesystem.DatapipelinesException

private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403
private const val HTTP_TOO_MANY_REQUESTS = 429

/**
 * Request attributes shared between the auth filters and the Spring Security
 * entry point / access-denied handler.
 *
 * A filter that rejects a credential cannot write the response itself (the chain
 * continues so a permitAll path still serves), so it records the specific
 * [AuthException] here; [AuthEntryPoint] emits its exact §13.7 code instead of the
 * generic `auth.api_key.missing` (AU-TEST-3).
 */
object AuthAttributes {
    const val AUTH_ERROR = "co.datapipelines.auth.error"
}

/**
 * The authentication / authorization error codes.
 *
 * The registry of record is [Pipeline Contract §13.7]; this object mirrors it
 * exactly and is asserted against the doc by `AuthErrorSpecDriftTest`. Codes
 * follow the `{domain}.{entity}.{failure}` convention (auth.md §9).
 *
 * Note: auth.md §9 additionally lists `auth.login.oidc_error` (500), which is NOT
 * in the §13.7 registry-of-record. Per the spec-drift contract this catalog tracks
 * §13.7 exactly; OIDC provider failures are surfaced by the login redirect, not by
 * an error-code envelope, so the code is intentionally absent here.
 */
object AuthErrorCodes {
    const val API_KEY_MISSING = "auth.api_key.missing"
    const val API_KEY_INVALID = "auth.api_key.invalid"
    const val API_KEY_EXPIRED = "auth.api_key.expired"
    const val SESSION_INVALID = "auth.session.invalid"
    const val SESSION_EXPIRED = "auth.session.expired"
    const val SCOPE_INSUFFICIENT = "auth.scope.insufficient"
    const val CSRF_INVALID = "auth.csrf.invalid"
    const val LOGIN_DOMAIN_NOT_ALLOWED = "auth.login.domain_not_allowed"
    const val LOGIN_USER_INACTIVE = "auth.login.user_inactive"

    /**
     * The single system-wide rate-limit code ([Pipeline Contract §13.11]). It is
     * deliberately NOT part of [ALL]: auth.md §9 is explicit that there is no
     * auth-layer rate-limit code, and [ALL] tracks the §13.7 auth registry exactly.
     */
    const val RATE_LIMIT_EXCEEDED = "rate_limit.exceeded"

    /** The full §13.7 set — the spec-drift test asserts this equals the doc. */
    val ALL: Set<String> =
        setOf(
            API_KEY_MISSING,
            API_KEY_INVALID,
            API_KEY_EXPIRED,
            SESSION_INVALID,
            SESSION_EXPIRED,
            SCOPE_INSUFFICIENT,
            CSRF_INVALID,
            LOGIN_DOMAIN_NOT_ALLOWED,
            LOGIN_USER_INACTIVE,
        )

    /**
     * The public docs page for an error code ([REST API §4.2] `doc_url`): dots and
     * underscores collapse to hyphens, so `auth.api_key.missing` becomes
     * `…/errors/auth-api-key-missing`.
     */
    fun docUrl(code: String): String = "$DOC_BASE${code.replace('.', '-').replace('_', '-')}"

    private const val DOC_BASE = "https://docs.datapipelines.co/errors/"
}

/**
 * Base for every auth failure. Carries the HTTP [status] plus the non-technical
 * [userMessage] so [AuthErrorWriter] can emit the full [REST API §4.2] envelope
 * (`schema_version`, `correlation_id`, `error{code, message, user_message, details,
 * doc_url}`) without a mapping table.
 */
open class AuthException(
    code: String,
    val status: Int,
    message: String,
    val userMessage: String,
    details: Map<String, Any?> = emptyMap(),
    cause: Throwable? = null,
) : DatapipelinesException(code, message, details, cause)

class ApiKeyMissingException :
    AuthException(
        AuthErrorCodes.API_KEY_MISSING,
        HTTP_UNAUTHORIZED,
        "No credentials provided",
        "You are not signed in. Sign in and try again.",
    )

class ApiKeyInvalidException(
    reason: String = "API key not recognized or revoked",
) : AuthException(
        AuthErrorCodes.API_KEY_INVALID,
        HTTP_UNAUTHORIZED,
        reason,
        "That API key is not valid. Generate a new one from the API Keys page.",
    )

class ApiKeyExpiredException :
    AuthException(
        AuthErrorCodes.API_KEY_EXPIRED,
        HTTP_UNAUTHORIZED,
        "API key past expiration",
        "That API key has expired. Generate a new one from the API Keys page.",
    )

class SessionInvalidException(
    reason: String = "Session JWT malformed or signature invalid",
    cause: Throwable? = null,
) : AuthException(
        AuthErrorCodes.SESSION_INVALID,
        HTTP_UNAUTHORIZED,
        reason,
        "Your session is no longer valid. Please sign in again.",
        cause = cause,
    )

class SessionExpiredException(
    cause: Throwable? = null,
) : AuthException(
        AuthErrorCodes.SESSION_EXPIRED,
        HTTP_UNAUTHORIZED,
        "Session JWT past its expiry",
        "Your session has expired. Please sign in again.",
        cause = cause,
    )

class ScopeInsufficientException(
    required: Scope,
    held: Set<Scope>,
) : AuthException(
        AuthErrorCodes.SCOPE_INSUFFICIENT,
        HTTP_FORBIDDEN,
        "Principal lacks required scope for this operation",
        "You do not have permission to perform this action.",
        details = mapOf("required" to required.wire, "held" to held.map { it.wire }),
    )

/**
 * A 403 from Spring Security's authorization layer on a request that never reached a
 * [RequiredScope]-annotated handler, so no documented §7.6 minimum applies.
 *
 * Carries the §13.7 `auth.scope.insufficient` code with **empty** details on purpose
 * (security NEW-7): the previous fallback reported `required: "admin", held: []` for
 * *any* such denial, which is a fabricated value in an error payload — a caller
 * debugging against it would chase a scope the server never actually required. When
 * the handler does not know what was needed, saying nothing is the honest answer.
 */
class AccessDeniedWithoutScopeException :
    AuthException(
        AuthErrorCodes.SCOPE_INSUFFICIENT,
        HTTP_FORBIDDEN,
        "Access denied by the authorization layer",
        "You do not have permission to perform this action.",
    )

/** CSRF double-submit failure (auth.md §8.4/§9). [reason] is `missing` or `mismatch`. */
class CsrfInvalidException(
    reason: String,
) : AuthException(
        AuthErrorCodes.CSRF_INVALID,
        HTTP_FORBIDDEN,
        "CSRF token missing or mismatched",
        "Your page is out of date. Reload it and try again.",
        details = mapOf("reason" to reason),
    )

/** Per-IP login rate limit ([Pipeline Contract §13.11], auth.md §9). */
class RateLimitExceededException(
    limitPerMinute: Int,
) : AuthException(
        AuthErrorCodes.RATE_LIMIT_EXCEEDED,
        HTTP_TOO_MANY_REQUESTS,
        "Rate limit exceeded",
        "Too many sign-in attempts. Wait a minute and try again.",
        details = mapOf("limit" to limitPerMinute, "window" to "1m"),
    )

/**
 * A JWT (or API key) whose owner is no longer `is_active`. Distinct type so the
 * filter's defined failure boundary (auth.md §6.3, rules/02) can log it as a
 * liveness rejection rather than a malformed-token error.
 */
class DeactivatedUserException(
    val userId: java.util.UUID,
) : AuthException(
        AuthErrorCodes.API_KEY_INVALID,
        HTTP_UNAUTHORIZED,
        "Account deactivated",
        "This account has been deactivated. Contact an administrator.",
    )
