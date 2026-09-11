package co.datapipelines.auth

import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/**
 * `workspace_invitations` persistence (metadata-db §4.17, auth.md §4.6) via
 * `NamedParameterJdbcTemplate` — the auth module owns the table (module-structure
 * §3.1: an invitation is an identity concept, the bridge between an email and a
 * workspace membership).
 *
 * ## Deliberately NOT behind [AuthCache]
 * Memberships are read per request, so they ride the 60s liveness discipline. An
 * invitation is read at two moments only — the members listing (an admin surface)
 * and the materialise at row creation — neither of which is a per-request resolution
 * path, so caching would add staleness and buy nothing.
 *
 * ## The materialise is ONE statement
 * [materialiseFor] inserts the memberships and deletes the invitations in a single
 * data-modifying-CTE statement (the [WorkspaceRepository.create] precedent) — the
 * "same transaction" rule without an enclosing `@Transactional`. The login path
 * calls it on EVERY login ([WorkspaceService.workspaceForLogin], the one resolution
 * both credential paths share), not only the first — a no-op statement for a user
 * with no invitations, and it is what makes the bridge self-healing if an invite
 * lands in the instant between a row's creation and its provisioning.
 */
class WorkspaceInvitationRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) {
    /**
     * Inserts [email]'s invitation into [workspaceId], or REPLACES the flags of the
     * row already there — the latest admin decision wins (auth.md §4.6), audited by
     * the caller every time. The caller normalises the flags before calling; the
     * `admin → author` CHECK is the database's backstop, not this method's job.
     */
    fun upsert(
        workspaceId: UUID,
        email: String,
        flags: MembershipFlags,
        invitedBy: UUID,
    ): Boolean =
        jdbc.update(
            """
            INSERT INTO workspace_invitations (workspace_id, email, author, promoter, admin, invited_by)
            VALUES (:ws, :email, :author, :promoter, :admin, :by)
            ON CONFLICT (workspace_id, email) DO UPDATE
                SET author = EXCLUDED.author, promoter = EXCLUDED.promoter, admin = EXCLUDED.admin,
                    invited_by = EXCLUDED.invited_by, invited_at = NOW()
            """.trimIndent(),
            invitationParams(workspaceId, email, flags, invitedBy),
        ) > 0

    /** Every invitation of [workspaceId], oldest invitation first (the members listing's `invitations[]`). */
    fun findByWorkspace(workspaceId: UUID): List<WorkspaceInvitation> =
        jdbc.query(
            "$SELECT_COLUMNS WHERE workspace_id = :ws ORDER BY invited_at, email",
            MapSqlParameterSource("ws", workspaceId),
            MAPPER,
        )

    /** One invitation by (workspace, email) — normalized lowercase, the §4.2 rule — or null. */
    fun find(
        workspaceId: UUID,
        email: String,
    ): WorkspaceInvitation? =
        jdbc
            .query(
                "$SELECT_COLUMNS WHERE workspace_id = :ws AND email = :email",
                MapSqlParameterSource().addValue("ws", workspaceId).addValue("email", email),
                MAPPER,
            ).firstOrNull()

    /** Removes one invitation; false when there was none (the caller answers `workspace.invitation.not_found`). */
    fun delete(
        workspaceId: UUID,
        email: String,
    ): Boolean =
        jdbc.update(
            "DELETE FROM workspace_invitations WHERE workspace_id = :ws AND email = :email",
            MapSqlParameterSource().addValue("ws", workspaceId).addValue("email", email),
        ) > 0

    /**
     * Turns every invitation for [email] into a membership of [userId], and deletes
     * exactly those invitations — one atomic statement, both halves of the bridge.
     *
     * - **Active workspaces only** (113 §B.5): the JOIN requires `is_deleted = FALSE AND
     *   deactivated_at IS NULL`, so an invitation into a deactivated workspace WAITS —
     *   reactivation makes it live at the next materialisation (every login materialises,
     *   not only the first).
     * - **`joined_at` = `invited_at`.** The materialised memberships enter the membership
     *   ordering (§4.12: `ORDER BY joined_at`) at their invitation dates, so a user invited
     *   into two workspaces stamps `active_workspace` to the EARLIER invitation — not the
     *   alphabetical accident of two same-transaction `NOW()` values.
     * - **A workspace the user already belongs to never inserts** (`ON CONFLICT DO NOTHING`)
     *   and its stale invitation is DELETED, not upgraded: an invitation can only exist for
     *   an email with no `users` row (auth.md §4.6 rule 1), so membership-with-invitation is
     *   always the check-then-act race of an invite landing mid-provisioning — and the
     *   membership the user actually holds is the later, deliberate decision. The invited
     *   flags survive in the audit trail, not on the row.
     *
     * Returns what materialised, in `invited_at` order, so the caller audits each row with
     * the workspace name, the flags and the INVITER (auth.md §10).
     */
    fun materialiseFor(
        email: String,
        userId: UUID,
    ): List<MaterialisedInvitation> =
        jdbc.query(
            """
            WITH invited AS (
                INSERT INTO workspace_members (workspace_id, user_id, author, promoter, admin, joined_at)
                SELECT i.workspace_id, :uid, i.author, i.promoter, i.admin, i.invited_at
                  FROM workspace_invitations i
                  JOIN workspaces w ON w.id = i.workspace_id
                 WHERE i.email = :email AND w.is_deleted = FALSE AND w.deactivated_at IS NULL
                 ORDER BY i.invited_at
                ON CONFLICT (workspace_id, user_id) DO NOTHING
                RETURNING workspace_id, author, promoter, admin
            ), gone AS (
                DELETE FROM workspace_invitations i
                 WHERE i.email = :email
                   AND (i.workspace_id IN (SELECT workspace_id FROM invited)
                        OR EXISTS (SELECT 1 FROM workspace_members m
                                    WHERE m.workspace_id = i.workspace_id AND m.user_id = :uid))
                RETURNING i.workspace_id, i.invited_by, i.invited_at
            )
            SELECT w.name AS workspace_name, v.author, v.promoter, v.admin,
                   g.invited_by, g.invited_at
              FROM gone g
              JOIN invited v ON v.workspace_id = g.workspace_id
              JOIN workspaces w ON w.id = g.workspace_id
             ORDER BY g.invited_at
            """.trimIndent(),
            MapSqlParameterSource().addValue("email", email).addValue("uid", userId),
            MATERIALISED_MAPPER,
        )

    /** One invitation that became a membership — what the audit event records (auth.md §10). */
    data class MaterialisedInvitation(
        val workspaceName: String,
        val flags: MembershipFlags,
        val invitedBy: UUID,
        val invitedAt: Instant,
    )

    private fun invitationParams(
        workspaceId: UUID,
        email: String,
        flags: MembershipFlags,
        invitedBy: UUID,
    ): MapSqlParameterSource =
        MapSqlParameterSource()
            .addValue("ws", workspaceId)
            .addValue("email", email)
            .addValue("author", flags.author)
            .addValue("promoter", flags.promoter)
            .addValue("admin", flags.admin)
            .addValue("by", invitedBy)

    private companion object {
        const val SELECT_COLUMNS =
            "SELECT workspace_id, email, author, promoter, admin, invited_by, invited_at FROM workspace_invitations"

        val MAPPER =
            RowMapper { rs: ResultSet, _: Int ->
                WorkspaceInvitation(
                    workspaceId = rs.getObject("workspace_id", UUID::class.java),
                    email = rs.getString("email"),
                    flags = rs.flags(),
                    invitedBy = rs.getObject("invited_by", UUID::class.java),
                    invitedAt = rs.getObject("invited_at", OffsetDateTime::class.java).toInstant(),
                )
            }

        val MATERIALISED_MAPPER =
            RowMapper { rs: ResultSet, _: Int ->
                MaterialisedInvitation(
                    workspaceName = rs.getString("workspace_name"),
                    flags = rs.flags(),
                    invitedBy = rs.getObject("invited_by", UUID::class.java),
                    invitedAt = rs.getObject("invited_at", OffsetDateTime::class.java).toInstant(),
                )
            }

        /** The three flag columns off one invitation row — the membership row's own shape. */
        fun ResultSet.flags(): MembershipFlags =
            MembershipFlags(
                author = getBoolean("author"),
                promoter = getBoolean("promoter"),
                admin = getBoolean("admin"),
            )
    }
}
