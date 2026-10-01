package co.datapipelines.web.visualizations

import co.datapipelines.auth.ApiKeyKind
import co.datapipelines.auth.ApiKeyRepository
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.Permission
import co.datapipelines.auth.PrincipalLiveness
import co.datapipelines.auth.User
import co.datapipelines.auth.UserService
import co.datapipelines.auth.Workspace
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.auth.WorkspaceRepository
import co.datapipelines.auth.WorkspaceRole
import co.datapipelines.auth.WorkspaceService
import co.datapipelines.visualization.StarterAuthority
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID

/**
 * The starter's CURRENT authority for a capability-bearing request (#353 DECISION 3; the owner's 2026-09-30 ruling,
 * "current authority on token use"): the [co.datapipelines.auth] state of the run's `started_by` re-derived at the
 * moment the preview or the upload capability is used — the `RefreshStream` authority shape, with no principal to
 * start from. Admitted only when, NOW:
 *
 * - the identity is live, and so is the run's workspace ([PrincipalLiveness], the cached 60 s reads every request
 *   pays — a deactivated person, a revoked key's deactivated identity, a deactivated workspace all fail here);
 * - a PERSON still reaches the workspace (an explicit membership, or the instance super admin — the one resolution
 *   path, [WorkspaceService.contextFor]) and that role holds `visualization.update`;
 * - a KEY identity's key is the live `mcp` key pinned to this workspace (not revoked, not expired), its creator is
 *   live (A20), and its KEY ROLE holds `visualization.update` — exactly what `ApiKeyService.validate` would admit,
 *   minus the secret the capability stands in for.
 *
 * Never throws: an unsettleable answer is `false`, logged by exception class only (no identity, no capability).
 */
class WebStarterAuthority(
    private val users: UserService,
    private val workspaces: WorkspaceService,
    private val workspaceRows: WorkspaceRepository,
    private val keys: ApiKeyRepository,
    private val liveness: PrincipalLiveness,
    private val now: () -> Instant = Instant::now,
) : StarterAuthority {
    private val log = LoggerFactory.getLogger(WebStarterAuthority::class.java)

    override fun holdsUpdate(
        workspaceId: UUID,
        startedBy: UUID,
    ): Boolean =
        try {
            judge(workspaceId, startedBy)
        } catch (
            @Suppress("TooGenericExceptionCaught") e: RuntimeException,
        ) {
            log.warn("event=visualization.test.starter_authority_failed error={}", e.javaClass.simpleName)
            false
        }

    @Suppress("ReturnCount") // each gate is its own named exit (the RefreshStream.judge shape)
    private fun judge(
        workspaceId: UUID,
        startedBy: UUID,
    ): Boolean {
        val user = users.snapshot(startedBy) ?: return false
        val workspace = workspaceRows.findById(workspaceId)?.takeUnless { it.isDeleted } ?: return false
        if (liveness.check(startedBy, PrincipalLiveness.Pin(workspace.id, workspace.name)) != null) return false
        val principal = (if (user.isHuman) person(user, workspace) else keyIdentity(user, workspace)) ?: return false
        return principal.holds(Permission.VISUALIZATION_UPDATE)
    }

    private fun person(
        user: User,
        workspace: Workspace,
    ): AuthenticatedPrincipal? {
        val bare =
            AuthenticatedPrincipal(
                userId = user.id,
                email = user.email,
                displayName = user.displayName,
                authMethod = AuthMethod.OIDC,
                workspaceName = workspace.name,
                superAdmin = user.isAdmin,
            )
        val context = workspaces.contextFor(bare, workspace.name)?.takeIf { it.id == workspace.id } ?: return null
        return bare.copy(workspace = context)
    }

    @Suppress("ReturnCount") // each key-liveness rule is its own named exit
    private fun keyIdentity(
        user: User,
        workspace: Workspace,
    ): AuthenticatedPrincipal? {
        // A key identity's provider subject IS its key id (UserService.provisionIdentity).
        val key = keys.findById(user.providerSubject) ?: return null
        if (key.userId != user.id || key.kind != ApiKeyKind.MCP) return null // not this identity's agent key
        if (key.isRevoked || key.workspaceId != workspace.id) return null
        if (key.expiresAt?.isAfter(now()) == false) return null
        if (liveness.check(key.createdBy, null) != null) return null
        val role = key.role ?: return null
        return AuthenticatedPrincipal(
            userId = user.id,
            email = user.email,
            displayName = user.displayName,
            authMethod = AuthMethod.API_KEY,
            keyId = key.id,
            workspaceName = workspace.name,
            workspace = WorkspaceContext(workspace.id, workspace.name, WorkspaceRole.VIEWER),
            keyKind = key.kind,
            keyRole = role,
        )
    }
}
