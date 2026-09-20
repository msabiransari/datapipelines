package co.datapipelines.auth

import java.time.Instant
import java.util.UUID

/**
 * One `workspace_invitations` row (metadata-db §4.17, auth.md §4.6): a membership
 * waiting for its user. A workspace admin creates it for an email that has no
 * `users` row yet, and the login path materialises it into a real membership the
 * moment that row comes into existence.
 *
 * The invitee is an EMAIL, never a user id — deliberately: `workspace_members.user_id`
 * stays `NOT NULL REFERENCES users(id)` and nothing here pretends a person exists
 * before they do. [role] is the membership's ONE role (D1, D20), carried forward unchanged
 * when the row materialises.
 */
data class WorkspaceInvitation(
    val workspaceId: UUID,
    val email: String,
    val role: WorkspaceRole,
    val invitedBy: UUID,
    val invitedAt: Instant,
)
