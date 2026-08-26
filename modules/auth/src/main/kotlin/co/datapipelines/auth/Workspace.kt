package co.datapipelines.auth

import java.time.Instant
import java.util.UUID

/** Workspace member roles (metadata-db §4.12, design D4). Global `is_admin` bypasses membership entirely. */
enum class WorkspaceRole {
    OWNER,
    MEMBER,
    ;

    val wire: String get() = name.lowercase()

    companion object {
        fun fromWire(value: String): WorkspaceRole =
            entries.firstOrNull { it.wire == value }
                ?: throw IllegalArgumentException("Unknown workspace role: '$value' (chk_workspace_member_role allows owner|member)")
    }
}

/**
 * A row of `workspaces` (metadata-db §4.11). [name] matches `[a-z0-9_-]+`, 1–63, and is
 * immutable in v1 — it is what the `DP-Workspace` header and the JWT `active_workspace`
 * claim carry.
 */
data class Workspace(
    val id: UUID,
    val name: String,
    val displayName: String,
    val isPersonal: Boolean,
    val createdBy: UUID?,
    val isDeleted: Boolean,
    val createdAt: Instant,
)

/** A row of `workspace_members` joined with its workspace's name (metadata-db §4.12). */
data class WorkspaceMembership(
    val workspaceId: UUID,
    val workspaceName: String,
    val role: WorkspaceRole,
    val joinedAt: Instant,
)

/**
 * The resolved active workspace a request pipeline carries (design §5): everything
 * downstream — repositories, execution records, template resolution — is scoped to it.
 *
 * Resolution produces this exactly once per request (see `WorkspaceResolutionFilter`):
 * from the JWT `active_workspace` claim or a `DP-Workspace` switch for session
 * principals, from the key's pinned `workspace_id` for API-key principals.
 */
data class WorkspaceContext(
    val id: UUID,
    val name: String,
)
