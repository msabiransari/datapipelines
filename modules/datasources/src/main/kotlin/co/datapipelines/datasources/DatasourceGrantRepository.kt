package co.datapipelines.datasources

import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.time.OffsetDateTime
import java.util.UUID

/**
 * One row of `datasource_workspaces` (metadata-db §4.16): a datasource is visible to a
 * workspace because somebody granted it (D-R7). Every grant records who and when — the
 * design says "every grant is audited", and the row itself is half of that record.
 */
data class DatasourceGrant(
    val datasourceName: String,
    val workspaceId: UUID,
    val workspaceName: String,
    val grantedBy: UUID,
    val grantedAt: java.time.Instant,
)

/**
 * `datasource_workspaces` persistence (metadata-db §4.16) — the table that REPLACED
 * `datasources.workspace_id` and with it the concept of a "global" datasource (D-R7).
 *
 * Visibility is the grant and nothing else. `DatasourceRepository`'s reads carry the
 * predicate in their SQL (`GRANTED_PREDICATE`), so this repository exists for the
 * MANAGEMENT half: listing a datasource's grants, adding one, removing one.
 *
 * A datasource is never made visible as a side effect of anything. The one place a grant is
 * created implicitly is registration by a workspace admin ([grantOnRegistration]), because
 * registering a datasource you then cannot see would be a bug wearing a rule's clothes.
 */
class DatasourceGrantRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) {
    /** Every workspace [datasourceName] is granted to, name order. */
    fun grantsOf(datasourceName: String): List<DatasourceGrant> =
        jdbc.query(
            """
            SELECT g.datasource_name, g.workspace_id, w.name AS workspace_name, g.granted_by, g.granted_at
              FROM datasource_workspaces g
              JOIN workspaces w ON w.id = g.workspace_id
             WHERE g.datasource_name = :name
             ORDER BY w.name
            """.trimIndent(),
            MapSqlParameterSource("name", datasourceName),
            MAPPER,
        )

    /** True when [datasourceName] is granted to [workspaceId] — the visibility question, asked directly. */
    fun isGranted(
        datasourceName: String,
        workspaceId: UUID,
    ): Boolean =
        jdbc.queryForObject(
            """
            SELECT EXISTS(
                SELECT 1 FROM datasource_workspaces WHERE datasource_name = :name AND workspace_id = :ws
            )
            """.trimIndent(),
            MapSqlParameterSource().addValue("name", datasourceName).addValue("ws", workspaceId),
            Boolean::class.java,
        ) == true

    /**
     * Grants [datasourceName] to [workspaceId]. Idempotent: re-granting is success and keeps
     * the ORIGINAL `granted_by`/`granted_at`, because the first grant is the decision and a
     * later no-op is not a new one — rewriting the actor would erase who actually decided.
     */
    fun grant(
        datasourceName: String,
        workspaceId: UUID,
        grantedBy: UUID,
    ): Boolean =
        jdbc.update(
            """
            INSERT INTO datasource_workspaces (datasource_name, workspace_id, granted_by)
            VALUES (:name, :ws, :by)
            ON CONFLICT (datasource_name, workspace_id) DO NOTHING
            """.trimIndent(),
            MapSqlParameterSource().addValue("name", datasourceName).addValue("ws", workspaceId).addValue("by", grantedBy),
        ) > 0

    /**
     * The grant a registration implies (RBAC design §4): a workspace admin who registers a
     * datasource bound to their own workspace gets the grant in the same breath.
     *
     * Tolerates the duplicate rather than pre-checking: registration and grant are two
     * statements, and a racing second registration of the same name has already failed on the
     * datasource's own primary key by the time this runs.
     */
    fun grantOnRegistration(
        datasourceName: String,
        workspaceId: UUID,
        grantedBy: UUID,
    ) {
        try {
            grant(datasourceName, workspaceId, grantedBy)
        } catch (_: DuplicateKeyException) {
            // Already granted — the outcome this method exists to produce.
        }
    }

    /**
     * Grants every INSTANCE datasource — one no workspace owns — to [workspaceId]. Returns how
     * many grants were created; existing ones are left with their original actor.
     *
     * Its only caller is the `demo` seeder (see `InstanceDatasourceGrants` for why that is the
     * one workspace this is right for). Done in ONE statement rather than a read-then-loop so
     * a datasource registered between the two cannot be missed.
     */
    fun grantAllInstanceDatasourcesTo(
        workspaceId: UUID,
        grantedBy: UUID,
    ): Int =
        jdbc.update(
            """
            INSERT INTO datasource_workspaces (datasource_name, workspace_id, granted_by)
            SELECT d.name, :ws, :by
              FROM datasources d
             WHERE d.owner_workspace_id IS NULL AND d.is_deleted = FALSE
            ON CONFLICT (datasource_name, workspace_id) DO NOTHING
            """.trimIndent(),
            MapSqlParameterSource().addValue("ws", workspaceId).addValue("by", grantedBy),
        )

    /** Revokes the grant; false when there was none. Nothing about the datasource itself changes. */
    fun revoke(
        datasourceName: String,
        workspaceId: UUID,
    ): Boolean =
        jdbc.update(
            "DELETE FROM datasource_workspaces WHERE datasource_name = :name AND workspace_id = :ws",
            MapSqlParameterSource().addValue("name", datasourceName).addValue("ws", workspaceId),
        ) > 0

    private companion object {
        val MAPPER =
            RowMapper { rs, _ ->
                DatasourceGrant(
                    datasourceName = rs.getString("datasource_name"),
                    workspaceId = rs.getObject("workspace_id", UUID::class.java),
                    workspaceName = rs.getString("workspace_name"),
                    grantedBy = rs.getObject("granted_by", UUID::class.java),
                    grantedAt = rs.getObject("granted_at", OffsetDateTime::class.java).toInstant(),
                )
            }
    }
}
