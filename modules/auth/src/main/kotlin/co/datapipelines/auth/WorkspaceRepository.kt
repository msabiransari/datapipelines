package co.datapipelines.auth

import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.util.UUID

/**
 * `workspaces` / `workspace_members` persistence (metadata-db §4.11/§4.12) via
 * `NamedParameterJdbcTemplate` — the auth module owns these tables (module-structure
 * §3.1: the workspace is an identity concept, resolved and membership-checked on every
 * authenticated request exactly like `users`).
 *
 * Reads here back the per-request resolution path, so they are always reached through
 * [WorkspaceService]'s 60s liveness cache (the D13 discipline of `users.is_active`,
 * design §4): membership revocation takes effect within one TTL, immediately on the
 * instance that performed the mutation. Nothing here caches on its own.
 */
class WorkspaceRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) {
    fun findById(id: UUID): Workspace? =
        jdbc
            .query(
                "$SELECT_COLUMNS WHERE id = :id AND is_deleted = FALSE",
                MapSqlParameterSource("id", id),
                MAPPER,
            ).firstOrNull()

    fun findByName(name: String): Workspace? =
        jdbc
            .query(
                "$SELECT_COLUMNS WHERE name = :name AND is_deleted = FALSE",
                MapSqlParameterSource("name", name),
                MAPPER,
            ).firstOrNull()

    /** True when [userId] holds any membership row in [workspaceId] (role is irrelevant to visibility, D4). */
    fun isMember(
        workspaceId: UUID,
        userId: UUID,
    ): Boolean =
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM workspace_members WHERE workspace_id = :ws AND user_id = :uid)",
            MapSqlParameterSource().addValue("ws", workspaceId).addValue("uid", userId),
            Boolean::class.java,
        ) == true

    /** Every workspace [userId] belongs to, oldest membership first — the login-time "first membership" order (design §5.1). */
    fun membershipsOf(userId: UUID): List<WorkspaceMembership> =
        jdbc.query(
            """
            SELECT m.workspace_id, w.name AS workspace_name, m.role, m.joined_at
              FROM workspace_members m
              JOIN workspaces w ON w.id = m.workspace_id
             WHERE m.user_id = :uid AND w.is_deleted = FALSE
             ORDER BY m.joined_at, w.name
            """.trimIndent(),
            MapSqlParameterSource("uid", userId),
            MEMBERSHIP_MAPPER,
        )

    /** True when [name] is taken, soft-deleted rows included — the house uniqueness rule (spec §8 `duplicate_name`). */
    fun nameExists(name: String): Boolean =
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM workspaces WHERE name = :name)",
            MapSqlParameterSource("name", name),
            Boolean::class.java,
        ) == true

    /** Every live workspace, name order — the `open-join` joinable listing (design §7). */
    fun findAll(): List<Workspace> = jdbc.query("$SELECT_COLUMNS WHERE is_deleted = FALSE ORDER BY name", MAPPER)

    /** Every member of [workspaceId] with identity columns, oldest membership first (the member listing). */
    fun findMembersOf(workspaceId: UUID): List<WorkspaceMemberRow> =
        jdbc.query(
            """
            SELECT m.user_id, u.email, u.display_name, m.role, m.joined_at
              FROM workspace_members m
              JOIN users u ON u.id = m.user_id
             WHERE m.workspace_id = :ws
             ORDER BY m.joined_at, u.email
            """.trimIndent(),
            MapSqlParameterSource("ws", workspaceId),
            MEMBER_MAPPER,
        )

    /** [userId]'s role in [workspaceId], or null when not a member. */
    fun roleOf(
        workspaceId: UUID,
        userId: UUID,
    ): WorkspaceRole? =
        jdbc
            .query(
                "SELECT role FROM workspace_members WHERE workspace_id = :ws AND user_id = :uid",
                MapSqlParameterSource().addValue("ws", workspaceId).addValue("uid", userId),
            ) { rs, _ -> WorkspaceRole.fromWire(rs.getString("role")) }
            .firstOrNull()

    /**
     * Adds [userId] to [workspaceId] as `member` (roles are assigned at creation; owner
     * transfer is not a v1 operation), then returns the membership row with identity
     * columns. Idempotent: an existing membership — whatever its role — is returned
     * unchanged, because "already a member" is success for both the open-join
     * self-service path and an owner re-adding someone.
     */
    fun addMember(
        workspaceId: UUID,
        userId: UUID,
    ): WorkspaceMemberRow? {
        jdbc.update(
            """
            INSERT INTO workspace_members (workspace_id, user_id, role)
            VALUES (:ws, :uid, 'member')
            ON CONFLICT (workspace_id, user_id) DO NOTHING
            """.trimIndent(),
            MapSqlParameterSource().addValue("ws", workspaceId).addValue("uid", userId),
        )
        return findMemberRow(workspaceId, userId)
    }

    /** One membership row with identity columns, or null when [userId] is not a member. */
    fun findMemberRow(
        workspaceId: UUID,
        userId: UUID,
    ): WorkspaceMemberRow? =
        jdbc
            .query(
                """
                SELECT m.user_id, u.email, u.display_name, m.role, m.joined_at
                  FROM workspace_members m
                  JOIN users u ON u.id = m.user_id
                 WHERE m.workspace_id = :ws AND m.user_id = :uid
                """.trimIndent(),
                MapSqlParameterSource().addValue("ws", workspaceId).addValue("uid", userId),
                MEMBER_MAPPER,
            ).singleOrNull()

    /** Removes [userId]'s membership row; false when there was none. Caller owns the owner-guard. */
    fun removeMember(
        workspaceId: UUID,
        userId: UUID,
    ): Boolean =
        jdbc.update(
            "DELETE FROM workspace_members WHERE workspace_id = :ws AND user_id = :uid",
            MapSqlParameterSource().addValue("ws", workspaceId).addValue("uid", userId),
        ) > 0

    /** Renames the display name (the one mutable field — `name` is immutable v1, design §8). */
    fun updateDisplayName(
        workspaceId: UUID,
        displayName: String,
    ): Workspace? =
        jdbc
            .query(
                """
                UPDATE workspaces SET display_name = :displayName, updated_at = NOW()
                 WHERE id = :ws AND is_deleted = FALSE
                RETURNING id, name, display_name, is_personal, created_by, is_deleted, created_at
                """.trimIndent(),
                MapSqlParameterSource().addValue("ws", workspaceId).addValue("displayName", displayName),
                MAPPER,
            ).singleOrNull()

    /** Soft-deletes [workspaceId]; false when already gone. The name stays taken (house rule). */
    fun softDelete(workspaceId: UUID): Boolean =
        jdbc.update(
            "UPDATE workspaces SET is_deleted = TRUE, updated_at = NOW() WHERE id = :ws AND is_deleted = FALSE",
            MapSqlParameterSource("ws", workspaceId),
        ) > 0

    /**
     * Inserts the workspace and its creator's OWNER membership in one data-modifying CTE,
     * so a workspace without a member is unrepresentable without an enclosing transaction
     * (the PipelineRepository precedent, metadata-db §6.3). A name collision surfaces as the
     * raw `DuplicateKeyException` — the database is the atomic authority; the service maps
     * it to `workspace.validation.duplicate_name` (same pattern as `PipelineRepository`).
     */
    fun create(
        name: String,
        displayName: String,
        isPersonal: Boolean,
        createdBy: UUID,
    ): Workspace =
        jdbc
            .query(
                """
                WITH new_workspace AS (
                    INSERT INTO workspaces (name, display_name, is_personal, created_by)
                    VALUES (:name, :displayName, :isPersonal, :createdBy)
                    RETURNING id, name, display_name, is_personal, created_by, is_deleted, created_at
                ), owner AS (
                    INSERT INTO workspace_members (workspace_id, user_id, role)
                    SELECT id, :createdBy, 'owner' FROM new_workspace
                    RETURNING workspace_id
                )
                SELECT w.id, w.name, w.display_name, w.is_personal, w.created_by, w.is_deleted, w.created_at
                  FROM new_workspace w
                  JOIN owner o ON o.workspace_id = w.id
                """.trimIndent(),
                MapSqlParameterSource()
                    .addValue("name", name)
                    .addValue("displayName", displayName)
                    .addValue("isPersonal", isPersonal)
                    .addValue("createdBy", createdBy),
                MAPPER,
            ).single()

    private companion object {
        const val SELECT_COLUMNS =
            "SELECT id, name, display_name, is_personal, created_by, is_deleted, created_at FROM workspaces"

        val MAPPER =
            RowMapper { rs: ResultSet, _: Int ->
                Workspace(
                    id = rs.getObject("id", UUID::class.java),
                    name = rs.getString("name"),
                    displayName = rs.getString("display_name"),
                    isPersonal = rs.getBoolean("is_personal"),
                    createdBy = rs.getObject("created_by", UUID::class.java),
                    isDeleted = rs.getBoolean("is_deleted"),
                    createdAt = rs.getObject("created_at", OffsetDateTime::class.java).toInstant(),
                )
            }

        val MEMBERSHIP_MAPPER =
            RowMapper { rs: ResultSet, _: Int ->
                WorkspaceMembership(
                    workspaceId = rs.getObject("workspace_id", UUID::class.java),
                    workspaceName = rs.getString("workspace_name"),
                    role = WorkspaceRole.fromWire(rs.getString("role")),
                    joinedAt = rs.getObject("joined_at", OffsetDateTime::class.java).toInstant(),
                )
            }

        val MEMBER_MAPPER =
            RowMapper { rs: ResultSet, _: Int ->
                WorkspaceMemberRow(
                    userId = rs.getObject("user_id", UUID::class.java),
                    email = rs.getString("email"),
                    displayName = rs.getString("display_name"),
                    role = WorkspaceRole.fromWire(rs.getString("role")),
                    joinedAt = rs.getObject("joined_at", OffsetDateTime::class.java).toInstant(),
                )
            }
    }
}
