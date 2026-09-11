package co.datapipelines.web.ui

import co.datapipelines.auth.Capability
import co.datapipelines.auth.MembershipFlags
import co.datapipelines.auth.WorkspaceInvitation
import co.datapipelines.auth.WorkspaceMemberRow
import co.datapipelines.auth.WorkspaceMembership
import java.util.UUID

/**
 * The row shapes `workspaces/index.html` renders (114 §C.1/§C.3).
 *
 * Before this round the template derived a role label from the flags in an inline ternary,
 * twice — once per table — and the two spellings had already diverged from the one the shell
 * shows. The label is derived HERE, by [RoleModel.labelOf], so the members table, the
 * workspace list and the switcher's badge all say the same word about the same row.
 *
 * The three checkboxes render straight off [MembershipFlags]; the SERVER normalises them
 * (`admin` forces `author`) whatever the client sends, so the template's tick-author-with-admin
 * script is a convenience and never the rule.
 */
data class MemberRowView(
    val userId: UUID,
    val email: String,
    val displayName: String,
    val flags: MembershipFlags,
    val roleLabel: String,
) {
    companion object {
        fun of(row: WorkspaceMemberRow): MemberRowView =
            MemberRowView(
                userId = row.userId,
                email = row.email,
                displayName = row.displayName,
                flags = row.flags,
                roleLabel = RoleModel.labelOf(row.flags),
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
                roleLabel = RoleModel.labelOf(membership.flags),
                active = membership.workspaceActive,
                isCurrent = membership.workspaceName == activeWorkspace,
                canAdmin = superAdmin || Capability.WS_ADMIN.satisfiedBy(membership.flags),
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
                roleLabel = RoleModel.labelOf(row.flags),
                invitedAt = row.invitedAt,
            )
    }
}
