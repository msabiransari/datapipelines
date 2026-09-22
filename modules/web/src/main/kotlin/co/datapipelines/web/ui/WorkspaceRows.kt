package co.datapipelines.web.ui

import co.datapipelines.auth.Permission
import co.datapipelines.auth.WorkspaceInvitation
import co.datapipelines.auth.WorkspaceMemberRow
import co.datapipelines.auth.WorkspaceMembership
import co.datapipelines.auth.WorkspaceRole
import java.util.UUID

/**
 * The row shapes `workspaces/index.html` and `partials/workspace-member-row.html` render
 * (114 §C.1/§C.3; D22).
 *
 * Before 114 the template derived a role label from the row in an inline ternary, twice — once
 * per table — and the two spellings had already diverged from the one the shell shows. The label
 * is derived HERE, by [RoleModel.labelOf], so the members table, the workspace list and the
 * switcher's badge all say the same word about the same row.
 *
 * Since D22 (2026-09-20) a member row carries ONE [role], rendered as the selected option of the
 * role dropdown; the SERVER decides the value set (`WorkspaceRole`, and the database CHECK
 * behind it), so the template lists `WorkspaceRole.entries` and never spells a role name.
 *
 * Since #200 (2026-09-21) it also carries [hasKey] — whether the member holds a live
 * login-minted key in this workspace. The state shows for every row ("has a key" / "no key")
 * and gates the row's Revoke-key verb; it is the admin's own workspace's fact, owner-id
 * derived, and never a key id or prefix.
 */
data class MemberRowView(
    val userId: UUID,
    val email: String,
    val displayName: String,
    val role: WorkspaceRole,
    val roleLabel: String,
    val hasKey: Boolean,
) {
    companion object {
        fun of(
            row: WorkspaceMemberRow,
            hasKey: Boolean,
        ): MemberRowView =
            MemberRowView(
                userId = row.userId,
                email = row.email,
                displayName = row.displayName,
                role = row.role,
                roleLabel = RoleModel.labelOf(row.role),
                hasKey = hasKey,
            )
    }
}

/**
 * One row of "your workspaces".
 *
 * [active] is the workspace's own liveness (D-R10): a deactivated workspace is listed ONLY to
 * a super admin — the switcher hides it from everyone, and for a member it does not exist at
 * all — and it is the row that carries the Reactivate verb.
 */
data class WorkspaceRowView(
    val name: String,
    val roleLabel: String,
    val active: Boolean,
    val isCurrent: Boolean,
    val canAdmin: Boolean,
) {
    companion object {
        fun of(
            membership: WorkspaceMembership,
            activeWorkspace: String?,
            superAdmin: Boolean,
        ): WorkspaceRowView =
            WorkspaceRowView(
                name = membership.workspaceName,
                roleLabel = RoleModel.labelOf(membership.role),
                active = membership.workspaceActive,
                isCurrent = membership.workspaceName == activeWorkspace,
                canAdmin = Permission.WS_ADMIN.satisfiedBy(membership.role, superAdmin),
            )
    }
}

/**
 * A pending invitation (113) as the members table shows it — a ghost row: the email that will
 * become a member at first login, the role it will carry, and when it was invited. Never mixed
 * into the member rows, so nothing that counts members counts a person who has not signed in.
 */
data class InvitationRowView(
    val email: String,
    val roleLabel: String,
    val invitedAt: java.time.Instant,
) {
    companion object {
        fun of(row: WorkspaceInvitation): InvitationRowView =
            InvitationRowView(
                email = row.email,
                roleLabel = RoleModel.labelOf(row.role),
                invitedAt = row.invitedAt,
            )
    }
}
