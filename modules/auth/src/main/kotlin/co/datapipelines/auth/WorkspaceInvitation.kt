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
 * before they do. [flags] are the membership's flags, carried forward unchanged;
 * `admin` implies `author` by the database CHECK, exactly as on the membership row.
 */
data class WorkspaceInvitation(
    val workspaceId: UUID,
    val email: String,
    val flags: MembershipFlags,
    val invitedBy: UUID,
    val invitedAt: Instant,
)
