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

    /**
     * Inserts the workspace and its creator's OWNER membership in one data-modifying CTE,
     * so a workspace without a member is unrepresentable without an enclosing transaction
     * (the PipelineRepository precedent, metadata-db §6.3).
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
    }
}
