package co.datapipelines.auth

import java.util.UUID

/** How a principal proved its identity (auth.md §3). */
enum class AuthMethod { OIDC, API_KEY }

/**
 * The internal principal both auth paths resolve to (auth.md §3).
 *
 * [scopes] is the set of *granted* scopes; hierarchy expansion for enforcement is
 * done at the check site ([Scope.satisfies], [ScopeMatrix]). [keyId] is present
 * only when [authMethod] is [AuthMethod.API_KEY].
 */
data class AuthenticatedPrincipal(
    val userId: UUID,
    val email: String,
    val displayName: String,
    val scopes: Set<Scope>,
    val authMethod: AuthMethod,
    val keyId: String? = null,
)
