package co.datapipelines.auth

import java.util.UUID

/**
 * How a principal proved its identity (auth.md §3).
 *
 * [PROMOTION] is not a human and not a key a human issued: it is the pre-shared server key of
 * a peer DEPLOYMENT (versioning §10.6), resolved onto R7's system service account. It is
 * carried here rather than folded into [API_KEY] so that every `authMethod` check in the
 * system states its intent explicitly — a promotion peer must not be mistaken for an agent's
 * key at a rule written for one of them.
 */
enum class AuthMethod { OIDC, API_KEY, PROMOTION }

/**
 * How a SESSION was established (RFC 8176 `amr` values): [PWD] by a local password, [OIDC]
 * by an identity provider. Carried on the session JWT and on the principal so that policies
 * about the local credential — the forced password change (auth.md §5A.4) — apply only to
 * sessions that credential opened. Null on non-session principals (API key, promotion) and
 * on session tokens minted before the claim existed, which the gate treats as [PWD].
 */
enum class LoginMethod(
    val amr: String,
) {
    PWD("pwd"),
    OIDC("oidc"),
    ;

    companion object {
        fun fromAmr(value: String?): LoginMethod? = entries.firstOrNull { it.amr == value }
    }
}

/**
 * The internal principal both auth paths resolve to (auth.md §3).
 *
 * [scopes] is the set of *granted* scopes; hierarchy expansion for enforcement is
 * done at the check site ([Scope.satisfies], [ScopeMatrix]). [keyId] is present
 * only when [authMethod] is [AuthMethod.API_KEY].
 *
 * ## Workspace (design §5)
 * [workspaceName] is the *unresolved* value the credential carries — the JWT's
 * `active_workspace` claim, or the key's pinned workspace name. [workspace] is the
 * resolved, membership-checked [WorkspaceContext] the `WorkspaceResolutionFilter`
 * stamps once per request; before that filter runs it is null, and after it, null
 * means "principal with zero memberships" (possible under `closed` provisioning —
 * every workspace-scoped operation must then 403, which is what [requireWorkspace]
 * raises).
 */
data class AuthenticatedPrincipal(
    val userId: UUID,
    val email: String,
    val displayName: String,
    val scopes: Set<Scope>,
    val authMethod: AuthMethod,
    val keyId: String? = null,
    val workspaceName: String? = null,
    val workspace: WorkspaceContext? = null,
    /**
     * The presented key's [ApiKeyKind] (§7.7), or null when the credential is not an API key.
     *
     * Carried on the principal rather than re-read per check because the decision it drives —
     * "may this credential be here at all?" — is made in a filter and again in the scope
     * interceptor, and a second database read between the two could see a different answer.
     */
    val keyKind: ApiKeyKind? = null,
    /**
     * See [LoginMethod]; null for API-key and promotion principals and for session tokens
     * minted before the `amr` claim existed. LAST on purpose: positional constructor calls
     * across the test suites must keep compiling.
     */
    val loginMethod: LoginMethod? = null,
) {
    /**
     * True when this principal's authority is endpoint bindings rather than scopes (§7.7).
     *
     * Such a principal is refused everywhere the published-endpoint surface does not reach —
     * including surfaces its scope set would otherwise open — and holds NO authority on an
     * endpoint with no binding on any ancestor.
     */
    val isEndpointKey: Boolean get() = keyKind == ApiKeyKind.ENDPOINT

    /** Global admin (D4): bypasses workspace membership checks. Same rule as `ExecutionRecord.visibleTo`. */
    val isAdmin: Boolean get() = Scope.satisfies(scopes, Scope.ADMIN)

    /**
     * The resolved active workspace, or [WorkspaceMembershipRequiredException] (403)
     * when the principal has none — the design §7 "zero memberships" refusal every
     * workspace-scoped operation shares.
     */
    fun requireWorkspace(): WorkspaceContext =
        workspace ?: throw WorkspaceMembershipRequiredException("Principal has no active workspace (zero memberships)")
}
