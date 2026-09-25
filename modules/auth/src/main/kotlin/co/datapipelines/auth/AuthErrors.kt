package co.datapipelines.auth

import co.datapipelines.typesystem.DatapipelinesException

private const val HTTP_BAD_REQUEST = 400
private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403
private const val HTTP_NOT_FOUND = 404
private const val HTTP_TOO_MANY_REQUESTS = 429
private const val HTTP_CONFLICT = 409

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

    /**
     * 403 — the surface declares NO catalog permission (#215 slice (b)): an unannotated governed
     * handler (`details.route`), a tool whose catalog entry has none (`details.tool`), or a denial
     * from the authorization layer that never reached a declared surface. A build defect the
     * coverage guards already fail — kept as the runtime defence, so an undeclared surface is
     * refused rather than served. Replaces `auth.scope.insufficient`, which retired with scopes.
     */
    const val PERMISSION_UNDECLARED = "auth.permission.undeclared"
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
     * THE permission refusal since scopes left (#215): a session, a key role (`api_caller`,
     * `promotion_receiver`) — every principal but the MCP key, whose refusal is
     * [KEY_ISSUER_ROLE_LOST] because its role is its member's.
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
     * 400 — on-demand issuance named NO kind at all (keys v2 A15: the retired login mint's
     * kind was the implicit default, and there is none now). A 400, not a 403: the caller's
     * credential and role are fine; the request body is not.
     */
    const val KEY_KIND_NOT_MINTABLE = "auth.key_kind_not_mintable"

    /**
     * 409 — keys v2 A18: the create path named a key that already EXISTS (live) in the
     * workspace. Its own code rather than a duplicate-name stand-in from another family,
     * because the recovery is auth's own (revoke the existing key, or rename) and the
     * conflict is the A18 index speaking through the service's clean check. Revoking the
     * existing key frees the name.
     */
    const val KEY_NAME_TAKEN = "auth.key_name_taken"

    /**
     * 404 — the key is pinned to a workspace that has been DEACTIVATED (D-R10, design §6).
     * The same status a member gets: the owner ruled (2026-09-14) that a deactivated
     * workspace answers not-found on EVERY surface, keys included — deactivation must not
     * become a signal anywhere. The code stays distinct from `workspace.not_found` because
     * the holder is a member of that workspace by construction (keys are issued by members
     * and pinned there), so it reveals nothing the holder did not already know — and it is
     * what an operator greps the audit log and the catalogue for.
     */
    const val KEY_WORKSPACE_INACTIVE = "auth.key_workspace_inactive"

    /**
     * 401 — the principal's USER is deactivated (D15, roles design §3.5): a session, a `user`
     * key, or the owner of an `endpoint`/`server` key. Judged by [PrincipalLiveness] where the
     * credential becomes a principal, through the same TTL as revocation, so deactivation kills
     * every credential within one window and reactivation restores them (deactivation is
     * reversible — nothing is revoked). Distinct from [API_KEY_INVALID], which since 180 means
     * only "no such usable key"; and NOT the code for a deactivated workspace, which keeps the
     * 404 rule ([KEY_WORKSPACE_INACTIVE], `workspace.not_found`). The promotion peer never
     * emits it — every refusal there is [PROMOTION_KEY_INVALID].
     */
    const val PRINCIPAL_DEACTIVATED = "auth.principal_deactivated"

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
            PERMISSION_UNDECLARED,
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
            KEY_KIND_NOT_MINTABLE,
            KEY_NAME_TAKEN,
            KEY_WORKSPACE_INACTIVE,
            PRINCIPAL_DEACTIVATED,
        )

    /**
     * The public docs page for an error code ([REST API §4.2] `doc_url`): the error-code
     * catalog, pipeline-contract §13, at the anchor of the section that lists the code's
     * family — `auth.api_key.missing` → `…/docs/pipeline-contract#137-authentication--authorization`.
     * There is no per-code page and no `docs.` host (there never was — the previous derivation
     * pointed at a hostname that does not resolve, #212). The anchors are the docs renderer's
     * heading ids; `AuthErrorSpecDriftTest` derives them from the doc and checks every
     * catalogued code lands on its own section. A code outside every family lands on the
     * catalog's top. Longest prefix wins: `pipeline.execution.datasource_unreachable` is listed
     * under §13.8 Datasource, not §13.3.
     *
     * Follow-up (#212): the host is the public site; a self-hosted deployment serves the same
     * docs under its own base URL, which the emitters could prefer once it reaches them.
     */
    fun docUrl(code: String): String = "$DOC_PAGE#${anchorFor(code)}"

    private fun anchorFor(code: String): String =
        SECTION_ANCHORS.firstOrNull { (prefix, _) -> code.startsWith(prefix) }?.second ?: CATALOG_ANCHOR

    private const val DOC_PAGE = "https://datapipelines.co/docs/pipeline-contract"
    private const val CATALOG_ANCHOR = "13-error-code-catalog"

    /** Code prefix → §13 section anchor, longest prefix first (order is the tie-break). */
    private val SECTION_ANCHORS: List<Pair<String, String>> =
        listOf(
            "pipeline.execution.datasource_unreachable" to "138-datasource",
            "pipeline.validation." to "131-pipeline-validation-write-time",
            "pipeline.import." to "132-pipeline-import",
            "pipeline.execution." to "133-pipeline-execution-run-time",
            "pipeline.node." to "134-node-execution",
            "pipeline.staging." to "135-staging",
            "pipeline.authoring." to "1313-versioning--draft-release-lifecycle--promotion",
            "pipeline.promotion." to "1313-versioning--draft-release-lifecycle--promotion",
            "pipeline.release." to "1313-versioning--draft-release-lifecycle--promotion",
            "pipeline.version." to "1313-versioning--draft-release-lifecycle--promotion",
            "pipeline.check." to "1317-release-checks",
            "pipeline.transform." to "1318-transform",
            "transform." to "1318-transform",
            "type_mapping." to "136-type-mapping",
            "auth." to "137-authentication--authorization",
            "datasource." to "138-datasource",
            "template." to "139-template",
            "result." to "1310-result-retrieval",
            "rate_limit." to "1311-rate-limiting--idempotency",
            "idempotency." to "1311-rate-limiting--idempotency",
            "workspace." to "1312-workspace-resolution",
            "endpoint." to "1314-published-endpoints",
            "semantics." to "1315-learned-semantics",
            "mcp." to "1316-mcp-surface",
        )
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
        "That key is not valid. An MCP key is created on the Keys page; an API key is created there too.",
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
        "That key has expired. Create a new one on the Keys page.",
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

/**
 * A 403 from Spring Security's authorization layer on a request that never reached a
 * [RequiredScope]-annotated handler, so no catalog permission was declared for it.
 *
 * Carries `auth.permission.undeclared` with **empty** details on purpose (security NEW-7): a
 * fallback that named a requirement would be a fabricated value in an error payload — a caller
 * debugging against it would chase a permission the server never actually required. When the
 * handler does not know what was needed, saying nothing is the honest answer.
 */
class AccessDeniedUndeclaredException :
    AuthException(
        AuthErrorCodes.PERMISSION_UNDECLARED,
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
 * Why a permission is not enough here: a role check cannot distinguish a browser session
 * from a `dpk_` key holding the same role — and a key that can mint a local account reads that
 * account's one-time
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
 * The principal's user is deactivated (D15, roles design §3.5) — the one refusal
 * [PrincipalLiveness] raises for a session, a `user` key, or the owner of an `endpoint` or
 * `server` key. Distinct type so `JwtAuthenticationFilter`'s defined failure boundary
 * (auth.md §6.3, rules/02) can clear the cookie and log a liveness rejection rather than a
 * malformed-token error, and so `AuthEntryPoint` can send an HTML navigation to
 * `/login?error=inactive`. Carries no workspace: the refusal is about the person.
 */
class PrincipalDeactivatedException(
    val userId: java.util.UUID,
) : AuthException(
        AuthErrorCodes.PRINCIPAL_DEACTIVATED,
        HTTP_UNAUTHORIZED,
        "Account deactivated",
        "This account has been deactivated. Contact an administrator.",
    )

/**
 * The principal's ROLE in the active workspace does not hold the permission the action needs
 * (RBAC design §2; the catalog, #215). Raised by boundaries that authorize outside the
 * interceptor — services and partials — so they answer with the SAME code the interceptor
 * writes rather than a hand-rolled 403; the interceptor itself renders
 * [ScopeMatrix.Decision.Refused] directly.
 *
 * Details (#215 A.6): `required` is the catalog permission's wire (`workspace.members.manage`);
 * `held` is the ROLE the principal was judged as ([WorkspaceContext.heldRole] — `viewer` …
 * `workspace_admin`, `super_admin`), absent when there was no workspace to hold a role in.
 * Informative only — no code compares it.
 */
class RoleRequiredException(
    required: Permission,
    heldRole: String?,
    workspace: String? = null,
) : AuthException(
        AuthErrorCodes.ROLE_REQUIRED,
        HTTP_FORBIDDEN,
        "Principal lacks the '${required.wire}' permission for this operation",
        "You do not have the role needed for this action in this workspace.",
        details =
            buildMap {
                put("required", required.wire)
                heldRole?.let { put("held", it) }
                workspace?.let { put("workspace", it) }
            },
    )

/**
 * A create request named NO kind (keys v2, A15): the login mint is gone, every key is created
 * on demand by a person who chooses what it is, and guessing a default would mint a credential
 * the caller did not ask for. A 400, not a 403: the caller's credential and role are fine; the
 * request body is incomplete. (The code predates keys v2, when the login-minted kind was the
 * one that could not be minted on demand; with that kind retired the condition it answers is
 * "no kind at all".)
 */
class KeyKindNotMintableException :
    AuthException(
        AuthErrorCodes.KEY_KIND_NOT_MINTABLE,
        HTTP_BAD_REQUEST,
        "Key issuance requires a kind",
        "Choose what the key is for: an MCP key, an API key, or a server key.",
        details = mapOf("supported" to ApiKeyKind.WIRE_VALUES),
    )

/**
 * The key's pinned workspace is deactivated (D-R10). A 404, like every other surface of a
 * deactivated workspace (the owner's 2026-09-14 ruling — deactivation must not become a
 * signal anywhere). The key is otherwise valid and stays
 * valid — reactivating the workspace restores it, which is the whole point of "deactivate,
 * never delete".
 */
class KeyWorkspaceInactiveException(
    workspace: String,
) : AuthException(
        AuthErrorCodes.KEY_WORKSPACE_INACTIVE,
        HTTP_NOT_FOUND,
        "The workspace '$workspace' this key is pinned to is deactivated",
        "This API key's workspace has been deactivated. Contact an administrator.",
        details = mapOf("workspace" to workspace),
    )

/**
 * The subset rule refused the requested key role (keys v2 A14, record O3 ruled): a creator may
 * give a key only a role whose permission set is a subset of the creator's own permissions in
 * that workspace. No ordinal ladder exists — promoter and author are not comparable and neither
 * may mint the other; a workspace admin may mint any of the three member roles; a super admin
 * any role at all.
 *
 * The refusal is `auth.role_required` (the same code the matrix writes), and per A14 the
 * details carry the ROLE that was asked for (`required = <the role>`) and the role the creator
 * was judged as (`held = <creator role>`) — "ask someone who holds it" is the only useful next
 * step, and it needs both halves.
 */
class KeyRoleNotOfferableException(
    requested: KeyRole,
    heldRole: String?,
    workspace: String?,
) : AuthException(
        AuthErrorCodes.ROLE_REQUIRED,
        HTTP_FORBIDDEN,
        "A key may only carry a role whose permissions the creator holds; '${requested.wire}' exceeds the creator's",
        "You can only give a key a role whose permissions you hold yourself in this workspace.",
        details =
            buildMap {
                put("required", requested.wire)
                heldRole?.let { put("held", it) }
                workspace?.let { put("workspace", it) }
            },
    )

/**
 * Keys v2 A18 — the create path named a key that already EXISTS (live) in the workspace.
 * A 409 with its own catalogued code ([AuthErrorCodes.KEY_NAME_TAKEN]) — the conflict is
 * auth's (the A18 unique index speaks through the service's clean check ahead of it), and
 * `details.reason = key_name_taken` keeps the stable token the UI matches on.
 * Revoking the existing key frees the name.
 */
class KeyNameTakenException(
    name: String,
    workspace: String?,
) : AuthException(
        AuthErrorCodes.KEY_NAME_TAKEN,
        HTTP_CONFLICT,
        "A live key named '$name' already exists in this workspace (keys v2 A18)",
        "That key name is already in use here. Revoke the old key first, or pick another name.",
        details =
            buildMap {
                put("reason", "key_name_taken")
                put("field", "name")
                put("value", name.take(MAX_ECHOED_EXPIRY_CHARS))
                workspace?.let { put("workspace", it) }
            },
    )
