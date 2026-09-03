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
) {
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
