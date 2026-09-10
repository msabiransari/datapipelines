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
    /**
     * `users.is_admin` — the INSTANCE super admin (D-R1). Read from the live user snapshot on
     * every request, never from a JWT claim, so a revoked super admin loses it within one
     * `AuthCache` TTL instead of at token expiry.
     *
     * It replaced the derived `isAdmin` (which read `Scope.ADMIN` out of the principal's
     * scopes) when round 1 emptied session scope sets: capability is the membership now, and
     * the ONE thing that is still global is this flag. Named `superAdmin` because that is what
     * it means — the old name read as "workspace owner" at half its call sites.
     *
     * LAST and defaulting FALSE, for the same two reasons `loginMethod` is: positional
     * constructions across the suites keep compiling, and a caller that forgets it gets the
     * unprivileged answer.
     */
    val superAdmin: Boolean = false,
) {
    /**
     * True when this principal's authority is endpoint bindings rather than scopes (§7.7).
     *
     * Such a principal is refused everywhere the published-endpoint surface does not reach —
     * including surfaces its scope set would otherwise open — and holds NO authority on an
     * endpoint with no binding on any ancestor.
     */
    val isEndpointKey: Boolean get() = keyKind == ApiKeyKind.ENDPOINT

    /**
     * True when this principal's authority is the promotion route family (§7.7).
     *
     * A server key opens the `/api/v1/promotion/` subtree and nothing else. It reaches that route only
     * through `DP-Promotion-Key` — presented as an ordinary `DP-API-Key` it authenticates a
     * principal that [ScopeInterceptor] refuses on every route, `/mcp` included (there by
     * `McpAuthFilter`, because a servlet never reaches the interceptor).
     */
    val isServerKey: Boolean get() = keyKind == ApiKeyKind.SERVER

    /**
     * Instance super admin (D-R1/D-R8): an implicit member of every workspace, with every
     * capability, and every action taken outside an explicit membership is audited with
     * `acting_via=super_admin`.
     *
     * Reads [superAdmin] rather than the scope set. Before round 1 this was
     * `Scope.satisfies(scopes, ADMIN)`, which was the same question only because
     * `JwtService.scopesFor` derived the scope set FROM `is_admin`; with session scopes gone
     * (D-R1) the derivation would answer false for every session, so the flag is carried.
     */
    val isSuperAdmin: Boolean get() = superAdmin

    /**
     * True when this principal administers the ACTIVE workspace — its `admin` flag, or super
     * admin (D-R8, who administers every workspace).
     *
     * The successor to `Scope.satisfies(scopes, ADMIN)`, which a dozen surfaces used as "is
     * this an administrator" and which round 1 made permanently FALSE: a session carries no
     * scopes (D-R1) and no key may hold `admin` (O-2). Every one of those sites had silently
     * become "nobody", and the two that were load-bearing — the admin user screens and
     * execution visibility — were found by E2Es rather than by the compiler, because a scope
     * test still compiles perfectly after the scope stops being reachable.
     *
     * Spelled ONCE here for that reason: the next person to ask "is this an administrator"
     * should find one answer, not re-derive a tenth.
     */
    val isWorkspaceAdmin: Boolean
        get() = superAdmin || Capability.WS_ADMIN.satisfiedBy(workspace?.flags ?: MembershipFlags.VIEWER)

    /**
     * The resolved active workspace, or [WorkspaceMembershipRequiredException] (403)
     * when the principal has none — the "zero memberships" refusal every workspace-scoped
     * operation shares. This is the one place [WorkspaceErrorCodes.MEMBERSHIP_REQUIRED]
     * survived D-R5's 404 rule, and deliberately: no workspace was ADDRESSED here, so there
     * is no name whose existence a 403 could leak.
     */
    fun requireWorkspace(): WorkspaceContext =
        workspace ?: throw WorkspaceMembershipRequiredException("Principal has no active workspace (zero memberships)")
}
