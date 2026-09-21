package co.datapipelines.auth

import java.util.UUID

/**
 * The ONE liveness predicate (D15, roles design §3.5): a principal is live when its user is
 * `is_active` AND, when the credential pins a workspace, that workspace is active. Judged where
 * each credential becomes a principal — `JwtAuthenticationFilter` for a session,
 * `ApiKeyService.validate` for `user`/`endpoint` keys, `ApiKeyService.validateServerKey` for
 * the promotion peer — which is the earliest point a refusal can happen and the one every
 * surface passes through (the security chain is global), so no boundary downstream has to
 * restate it and no new surface can forget it.
 *
 * Both reads go through [AuthCache]'s TTL (auth.md §11.4): `UserService.isActive` and
 * `WorkspaceLiveness.isActive` cost zero queries within the window, and the services that
 * flip the rows evict locally, so deactivation takes effect on this instance at once and
 * everywhere else within one TTL. Reactivation restores every credential — nothing is revoked
 * by deactivation (D-R10, V31).
 *
 * ## One predicate, two codes
 * The USER case is [Refusal.UserDeactivated] → `auth.principal_deactivated` (401), on every
 * surface but the promotion peer, whose filter folds every refusal into its one answer. The
 * WORKSPACE case keeps the 404 rule that predates this predicate (auth.md §11A.1: a workspace
 * a caller may not use is indistinguishable from one that does not exist) —
 * [Refusal.WorkspaceDeactivated] → `auth.key_workspace_inactive`. The user is judged FIRST,
 * so a deactivated person pinned to a deactivated workspace is told about themselves.
 */
class PrincipalLiveness(
    private val userService: UserService,
    private val workspaceLiveness: WorkspaceLiveness,
) {
    /** The workspace a credential is pinned to — id for the read, name for the refusal's detail. */
    data class Pin(
        val workspaceId: UUID,
        val workspaceName: String,
    )

    /** Why the principal is not live, and the catalogued refusal each reason maps to. */
    sealed interface Refusal {
        fun toException(): AuthException

        data class UserDeactivated(
            val userId: UUID,
        ) : Refusal {
            override fun toException(): AuthException = PrincipalDeactivatedException(userId)
        }

        data class WorkspaceDeactivated(
            val pin: Pin,
        ) : Refusal {
            override fun toException(): AuthException = KeyWorkspaceInactiveException(pin.workspaceName)
        }
    }

    /**
     * Null when [userId] is active and [pin] (if any) names an active workspace; otherwise the
     * first refusal in the order the KDoc states. A user the store no longer has is not live.
     */
    fun check(
        userId: UUID,
        pin: Pin?,
    ): Refusal? {
        if (!userService.isActive(userId)) return Refusal.UserDeactivated(userId)
        if (pin != null && !workspaceLiveness.isActive(pin.workspaceId)) return Refusal.WorkspaceDeactivated(pin)
        return null
    }

    /** [check], throwing the refusal's exception — the form the credential filters use. */
    fun require(
        userId: UUID,
        pin: Pin?,
    ) {
        check(userId, pin)?.let { throw it.toException() }
    }
}
