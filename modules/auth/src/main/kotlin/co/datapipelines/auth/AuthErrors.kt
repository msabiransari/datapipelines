package co.datapipelines.auth

import co.datapipelines.typesystem.DatapipelinesException

private const val HTTP_BAD_REQUEST = 400
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
    const val LOGIN_BAD_CREDENTIALS = "auth.login.bad_credentials"
    const val LOGIN_LOCKED = "auth.login.locked"
    const val PASSWORD_CHANGE_REQUIRED = "auth.password.change_required"
    const val SESSION_REQUIRED = "auth.session.required"

    /**
     * 403 — the principal holds the right CREDENTIAL and the wrong ROLE (RBAC design §2,
     * D-R1). `details.required` names the capability the operation needs and `details.held`
     * what the membership carries, because "ask a workspace admin for the author flag" is
     * the only useful next step and it needs both halves to be stated.
     *
     * Distinct from [SCOPE_INSUFFICIENT], which is the credential axis: a `read` key on an
     * authoring route gets that one, an author-scoped key held by a demoted issuer gets
     * [KEY_ISSUER_ROLE_LOST], and a viewer's session gets this.
     */
    const val ROLE_REQUIRED = "auth.role_required"

    /**
     * 403 — the KEY was valid; its issuer no longer holds the capability the operation needs
     * (D-R12). Its own code rather than [ROLE_REQUIRED] because the recovery differs and a
     * caller cannot guess it: retrying with this key will never work, and the fix is a new
     * key from somebody who still holds the role. A demotion takes effect within one
     * `AuthCache` TTL (60 s by default), so a key can start refusing mid-session.
     */
    const val KEY_ISSUER_ROLE_LOST = "auth.key_issuer_role_lost"

    /**
     * 400 — issuance asked for a scope this surface no longer grants to keys. Round 1 removed
     * `admin` from the key wire entirely (D-R12/O-2: release, promote and membership are human
     * verbs), so a key can hold at most `author`. A 400 and not a 403: the credential issuing
     * the request is fine, the requested scope is not a scope keys have.
     */
    const val KEY_SCOPE_UNAVAILABLE = "auth.key_scope_unavailable"

    /**
     * 403 — the key is pinned to a workspace that has been DEACTIVATED (D-R10, design §6).
     * A 403 rather than the members' 404: a key's workspace is pinned at issuance, so the
     * holder already knows the workspace exists and there is no oracle to protect — telling
     * them the truth is what lets an operator act.
     */
    const val KEY_WORKSPACE_INACTIVE = "auth.key_workspace_inactive"

    /**
     * 091 — an issuance whose EXPIRY is not a usable one: an unknown preset, a custom date
     * that is not a date, or an expiry already in the past. A 400, unlike every other code
     * here: nothing is wrong with the caller's credential, the request body is wrong.
     */
    const val API_KEY_EXPIRY_INVALID = "auth.api_key.expiry_invalid"

    /**
     * versioning §10.6 — the promotion peer credential. Absent, malformed, mismatched,
     * and "this receiver configured no key at all" all answer with this ONE code: the
     * response must not tell a wrong key apart from a receiver that has promotion
     * disabled.
     */
    const val PROMOTION_KEY_INVALID = "auth.promotion.key_invalid"

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
            LOGIN_BAD_CREDENTIALS,
            LOGIN_LOCKED,
            PASSWORD_CHANGE_REQUIRED,
            API_KEY_EXPIRY_INVALID,
            SESSION_REQUIRED,
            PROMOTION_KEY_INVALID,
            ROLE_REQUIRED,
            KEY_ISSUER_ROLE_LOST,
            KEY_SCOPE_UNAVAILABLE,
            KEY_WORKSPACE_INACTIVE,
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

/**
 * The promotion peer credential was refused (versioning §10.6, auth.md §9).
 *
 * ONE code for every refusal on that route — header absent, header malformed, key
 * mismatched, and no `server-key` configured on this receiver at all. A caller must not be
 * able to tell "your key is wrong" from "this deployment does not accept promotion", and a
 * receiver that never configured a key refuses everything (fail closed).
 *
 * The key itself never reaches the message, the details, or a log line.
 */
class PromotionKeyInvalidException :
    AuthException(
        AuthErrorCodes.PROMOTION_KEY_INVALID,
        HTTP_UNAUTHORIZED,
        "Promotion peer credential not accepted",
        "This deployment did not accept the promotion request.",
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

/**
 * 091 — the API-key form's expiry could not be turned into an instant (§7.4).
 *
 * [reason] is a stable token (`unknown_preset`, `date_unparseable`, `date_in_past`,
 * `date_missing`) so a caller can branch, and [detail] is the offending value ECHOED BACK
 * TRUNCATED — an expiry is not a secret, and a refusal that does not say what it refused
 * costs a support round trip. 400, not 403: the credential is fine, the body is not.
 */
class ApiKeyExpiryInvalidException(
    reason: String,
    detail: String? = null,
) : AuthException(
        AuthErrorCodes.API_KEY_EXPIRY_INVALID,
        HTTP_BAD_REQUEST,
        "API key expiry is not usable: $reason",
        "That expiry is not one this form can use. Pick one of the listed options, or a future date.",
        details = mapOf("reason" to reason, "value" to detail?.take(MAX_ECHOED_EXPIRY_CHARS)),
    )

/** The longest slice of a rejected expiry value that is echoed back. */
private const val MAX_ECHOED_EXPIRY_CHARS = 32

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

/**
 * A session principal whose `must_change_password` is TRUE called an API path
 * (auth.md §5A.4) — the forced-change gate redirects browsers, but a JSON client
 * gets the envelope instead. (Constant declared here; the §13.7 registry row,
 * the [AuthErrorCodes.ALL] membership and the drift-test literals land in the
 * single isolated catalog commit per the parallel-lane contract.)
 */
class PasswordChangeRequiredException :
    AuthException(
        AuthErrorCodes.PASSWORD_CHANGE_REQUIRED,
        HTTP_FORBIDDEN,
        "Password change is required before any other operation",
        "You must set a new password before continuing.",
    )

/**
 * An API-key principal reached an operation that MINTS OR ROTATES an interactive
 * credential: admin local-account creation, admin password reset, `disable-local`,
 * `unlock`, and the self-service password change.
 *
 * Why scope is not enough here. `AuthenticatedPrincipal.isAdmin` is *defined as*
 * holding [Scope.ADMIN], so a scope test cannot distinguish a browser session from a
 * `dpk_` key — and a key that can mint a local account reads that account's one-time
 * password straight out of the response body, then signs in with it. That trades a
 * revocable, workspace-pinned, non-interactive credential for a permanent `dp_session`
 * which is NOT pinned and which outlives revocation of the key that created it,
 * defeating the whole revocation contract keys exist under (auth.md §2, §8).
 *
 * This is the same escalation class 96240ed closed on the workspace UI actions with a
 * session check; [WorkspaceSessionRequiredException] is its workspace-scoped sibling.
 * The programmatic path for keys stays the `/api/v1/auth/users` REST surface, which administers users
 * WITHOUT ever emitting a usable credential.
 */
class SessionRequiredException(
    operation: String,
) : AuthException(
        AuthErrorCodes.SESSION_REQUIRED,
        HTTP_FORBIDDEN,
        "Credential-minting operation '$operation' is session-only; an API key cannot drive it",
        "This action needs an interactive sign-in. Sign in and try again.",
        details = mapOf("operation" to operation),
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

/**
 * The principal's ROLE in the active workspace is below what the operation needs (RBAC
 * design §2). Raised by boundaries that authorize outside the interceptor — services and
 * partials — so they answer with the SAME code the interceptor writes rather than a
 * hand-rolled 403; the interceptor itself renders [ScopeMatrix.Decision.Refused] directly.
 */
class RoleRequiredException(
    required: Capability,
    held: Set<Capability>,
    workspace: String? = null,
) : AuthException(
        AuthErrorCodes.ROLE_REQUIRED,
        HTTP_FORBIDDEN,
        "Principal lacks the '${required.wire}' role for this operation",
        "You do not have the role needed for this action in this workspace.",
        details =
            buildMap {
                put("required", required.wire)
                put("held", held.map { it.wire }.sorted())
                workspace?.let { put("workspace", it) }
            },
    )

/**
 * Issuance asked for a scope keys may no longer hold (D-R12/O-2). Today that is exactly
 * `admin`: release, promote and membership are human verbs, so no key expresses them.
 */
class KeyScopeUnavailableException(
    requested: Scope,
) : AuthException(
        AuthErrorCodes.KEY_SCOPE_UNAVAILABLE,
        HTTP_BAD_REQUEST,
        "Scope '${requested.wire}' is not available to API keys",
        "API keys can be granted read, execute or author. Administrative actions need a signed-in person.",
        details = mapOf("requested" to requested.wire, "available" to KEY_SCOPES.map { it.wire }),
    )

/**
 * The key's pinned workspace is deactivated (D-R10). The key is otherwise valid and stays
 * valid — reactivating the workspace restores it, which is the whole point of "deactivate,
 * never delete".
 */
class KeyWorkspaceInactiveException(
    workspace: String,
) : AuthException(
        AuthErrorCodes.KEY_WORKSPACE_INACTIVE,
        HTTP_FORBIDDEN,
        "The workspace '$workspace' this key is pinned to is deactivated",
        "This API key's workspace has been deactivated. Contact an administrator.",
        details = mapOf("workspace" to workspace),
    )

/**
 * The scopes an API key may hold since RBAC round 1 (D-R12, O-2). `admin` is deliberately
 * absent: it is the only scope that ever bought a key an INSTANCE verb, and instance verbs
 * are human. Read at issuance ([KeyScopeUnavailableException]) and again at validation, where
 * a key minted before this rule is capped rather than trusted.
 */
val KEY_SCOPES: Set<Scope> = setOf(Scope.READ, Scope.EXECUTE, Scope.AUTHOR)
