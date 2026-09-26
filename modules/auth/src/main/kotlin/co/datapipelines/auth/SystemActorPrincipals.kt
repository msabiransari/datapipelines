package co.datapipelines.auth

import java.util.UUID

/**
 * The principal a schedule FIRES as (#9 R2, scheduler design revision §4) — the existing system
 * service account ([UserService.provisionSystemActor]), in the schedule's workspace, holding the
 * fixed [RolePermissions.SYSTEM_ACTOR] set through the resolver's system arm and nothing else.
 *
 * ## Why this principal cannot be reached from a request
 * It is built here, in-process, and nowhere else: no filter produces [AuthMethod.SYSTEM]. The row
 * it names can never authenticate — no OIDC provider may be named `system`, the local-password
 * paths refuse it, it has no `api_keys` row, and `JwtAuthenticationFilter` refuses a session whose
 * subject is not a person. `SystemActorSecurityE2eTest` presents each of those and is refused.
 *
 * ## What it can and cannot do
 * [AuthenticatedPrincipal.holds] routes through [WorkspaceContext.permits], which asks
 * [PermissionResolver.holdsAsSystemActor] — so an INSTANCE permission is refused (the instance arm
 * judges `superAdmin`, false here), a key role is absent, and every member permission is the fixed
 * set's answer. It is never a super admin and never "in" any other workspace.
 */
object SystemActorPrincipals {
    /** The system identity acting in [workspaceId] / [workspaceName], as [actor] (the provisioned row). */
    fun forWorkspace(
        actor: User,
        workspaceId: UUID,
        workspaceName: String,
    ): AuthenticatedPrincipal {
        require(actor.kind == UserKind.SYSTEM) { "Only the system identity fires a schedule; ${actor.id} is ${actor.kind.wire}" }
        return AuthenticatedPrincipal(
            userId = actor.id,
            email = actor.email,
            displayName = actor.displayName,
            authMethod = AuthMethod.SYSTEM,
            workspaceName = workspaceName,
            workspace = WorkspaceContext(id = workspaceId, name = workspaceName, systemActor = true),
        )
    }
}
