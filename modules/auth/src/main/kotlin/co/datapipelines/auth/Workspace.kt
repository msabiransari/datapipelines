package co.datapipelines.auth

import java.time.Instant
import java.util.UUID

/**
 * A row of `workspaces` (metadata-db §4.11). [name] matches `[a-z0-9_-]+`, 1–63, and is
 * immutable in v1 — it is what the `DP-Workspace` header and the JWT `active_workspace`
 * claim carry.
 *
 * [deactivatedAt] is the D-R10 state: deactivate, never delete. A deactivated workspace
 * cannot be selected, its endpoints 404, its keys are refused and its schedules do not
 * fire — and nothing it owns is ever purged. [isDeleted] is the older soft delete and
 * stays what it was; the two are independent.
 */
data class Workspace(
    val id: UUID,
    val name: String,
    val displayName: String,
    val isPersonal: Boolean,
    val createdBy: UUID?,
    val isDeleted: Boolean,
    val createdAt: Instant,
    val deactivatedAt: Instant? = null,
    val deactivatedBy: UUID? = null,
) {
    /** True when this workspace may be selected, served and scheduled (design §6). */
    val isActive: Boolean get() = !isDeleted && deactivatedAt == null
}

/**
 * A row of `workspace_members` joined with its workspace's name (metadata-db §4.12).
 *
 * [role] is the ONE role the member holds here (D1, V29). V23 had split it into three
 * additive flags; the 2026-09-20 rulings made the combinations meaningless (a promoter
 * authors nothing, an author releases), so the row is a single value again.
 */
data class WorkspaceMembership(
    val workspaceId: UUID,
    val workspaceName: String,
    val role: WorkspaceRole,
    val joinedAt: Instant,
    val workspaceActive: Boolean = true,
)

/**
 * A member of one workspace, as the `/members` listing projects them (metadata-db §4.12 ×
 * `users`): identity columns plus the role. No [WorkspaceMembership] reuse because that
 * type answers the reverse question ("which workspaces does THIS user belong to") and
 * carries no user identity.
 */
data class WorkspaceMemberRow(
    val userId: UUID,
    val email: String,
    val displayName: String,
    val role: WorkspaceRole,
    val joinedAt: Instant,
    /** The USER's instance authority (`users.is_admin`), not a membership fact — the members list shows it (#208). */
    val isSuperAdmin: Boolean = false,
)

/**
 * The resolved active workspace a request pipeline carries (design §5): everything
 * downstream — repositories, execution records, template resolution — is scoped to it,
 * and [role] + [superAdmin] are what [ScopeMatrix.allowed] reads to answer "may this
 * principal do this here" (roles design §2; the catalog, #215).
 *
 * Resolution produces this exactly once per request (see `WorkspaceResolutionFilter`):
 * from the JWT `active_workspace` claim or a `DP-Workspace` switch for session
 * principals, from the key's pinned `workspace_id` for API-key principals. A super
 * admin resolves ANY workspace (D7) with [superAdmin] set and [implicit] true when they
 * hold no explicit membership — which is what the `acting_via=super_admin` audit flag is
 * read from. Their [role] is the explicit membership's when they have one and [WorkspaceRole.VIEWER]
 * otherwise; [RolePermissions.holds] admits a super admin before it looks at the role.
 */
data class WorkspaceContext(
    val id: UUID,
    val name: String,
    val role: WorkspaceRole = WorkspaceRole.VIEWER,
    val superAdmin: Boolean = false,
    val implicit: Boolean = false,
    /**
     * #9 R2: this context is the SYSTEM IDENTITY's, built for one scheduled launch — not a
     * membership. [permits] then asks the resolver's system arm ([PermissionResolver.holdsAsSystemActor])
     * and [role] is never consulted. LAST and defaulting false, so every existing construction
     * is a member's.
     */
    val systemActor: Boolean = false,
) {
    /** D7 — this action is being taken by a super admin outside their own memberships. */
    val actingViaSuperAdmin: Boolean get() = implicit

    /**
     * Does a principal in this context hold [permission]? The matrix's one question, asked of the
     * installed [PermissionResolver] with this workspace named (security-assurance record §7.1, B4).
     */
    fun permits(permission: Permission): Boolean =
        if (systemActor) {
            PermissionResolution.resolver.holdsAsSystemActor(id, permission)
        } else {
            PermissionResolution.resolver.holds(id, role, superAdmin, permission)
        }

    /**
     * The ROLE this context was judged as — a refusal's `held` detail (#215 A.6): `super_admin`
     * for a super admin, else the membership's wire (`viewer` … `workspace_admin`). Informative
     * only: no code compares it, every decision asks [permits].
     */
    val heldRole: String
        get() =
            when {
                systemActor -> SYSTEM_ACTOR_WIRE
                superAdmin -> SUPER_ADMIN_WIRE
                else -> role.wire
            }

    companion object {
        /** [heldRole]'s token for the instance super admin — the fifth column of auth.md §7.6. */
        const val SUPER_ADMIN_WIRE = "super_admin"

        /** [heldRole]'s token for the system identity (#9 R2) — informative, like every `held` detail; not a role. */
        const val SYSTEM_ACTOR_WIRE = "system"

        /** The context a super admin runs in inside a workspace: their explicit [role] if any, [implicit] otherwise. */
        fun superAdminOver(
            id: UUID,
            name: String,
            explicitRole: WorkspaceRole?,
        ): WorkspaceContext =
            WorkspaceContext(
                id = id,
                name = name,
                role = explicitRole ?: WorkspaceRole.VIEWER,
                superAdmin = true,
                implicit = explicitRole == null,
            )
    }
}
