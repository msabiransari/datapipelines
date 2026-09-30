package co.datapipelines.visualization

import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.time.Instant
import java.util.UUID

/** A refresh's state (enums.md §38) — `dashboard_refreshes.status` (V43). [RUNNING] is the only non-terminal value. */
enum class RefreshStatus {
    RUNNING,
    COMPLETED,
    PARTIAL,
    FAILED,
    ABORTED,
    TIMED_OUT,
    ;

    val terminal: Boolean get() = this != RUNNING
}

/** One `dashboard_refreshes` row (metadata-db §4.34). JSON columns are carried as their text projection. */
data class RefreshRecord(
    val id: UUID,
    val dashboardId: UUID,
    val dashboardVersion: Int,
    val workspaceId: UUID,
    val instanceId: UUID,
    val principalUserId: UUID?,
    val principalKeyId: String?,
    /** The refresh's scope — [ActionScope] (enums.md §35); the column stores the constant's NAME. */
    val scope: ActionScope,
    val targetsJson: String,
    val parameterRevision: Int,
    val selectionsJson: String,
    val status: RefreshStatus,
    val startedAt: Instant,
    val finishedAt: Instant?,
    val summaryJson: String,
)

/** One `dashboard_refresh_executions` row: a source of the refresh and the execution that ran it. */
data class RefreshExecutionLink(
    val refreshId: UUID,
    val sourceName: String,
    val executionId: UUID,
    val shared: Boolean,
)

/**
 * `dashboard_refreshes` / `dashboard_refresh_executions` (V43; metadata-db §4.34–§4.35) — plain JDBC, every value a
 * bind. In `visualization` (not `web`) because two surfaces read it: the runtime's routes and `dashboards_get`'s
 * `last_refresh`; the runtime OWNS the writes.
 *
 * The rules the schema cannot say by itself: a row is inserted RUNNING only after admission ([insertRunning]); the
 * terminal write is guarded by `status = 'RUNNING'` so a late writer (the sweeper, an abort race) never overwrites a
 * finished refresh ([finish]); and a refresh is looked up by (workspace, id) everywhere — an id from another
 * workspace is absent, not forbidden.
 */
class DashboardRefreshRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) {
    /** Inserts [record] as RUNNING. False when the id is taken (a reused refresh id); the row is never overwritten. */
    fun insertRunning(record: RefreshRecord): Boolean =
        try {
            jdbc.update(
                """
                INSERT INTO dashboard_refreshes (id, dashboard_id, dashboard_version, workspace_id, instance_id, principal_user_id,
                    principal_key_id, scope, targets_json, parameter_revision, selections_json, status, started_at, summary_json)
                VALUES (:id, :dashboardId, :dashboardVersion, :workspaceId, :instanceId, :userId, :keyId, :scope,
                    CAST(:targets AS jsonb), :revision, CAST(:selections AS jsonb), 'RUNNING', :startedAt, '{}'::jsonb)
                """.trimIndent(),
                MapSqlParameterSource()
                    .addValue("id", record.id)
                    .addValue("dashboardId", record.dashboardId)
                    .addValue("dashboardVersion", record.dashboardVersion)
                    .addValue("workspaceId", record.workspaceId)
                    .addValue("instanceId", record.instanceId)
                    .addValue("userId", record.principalUserId)
                    .addValue("keyId", record.principalKeyId)
                    .addValue("scope", record.scope.name)
                    .addValue("targets", record.targetsJson)
                    .addValue("revision", record.parameterRevision)
                    .addValue("selections", record.selectionsJson)
                    .addValue("startedAt", java.sql.Timestamp.from(record.startedAt)),
            ) == 1
        } catch (_: DuplicateKeyException) {
            false
        }

    /** Writes the terminal state of a RUNNING refresh; false when it was no longer RUNNING (someone else ended it). */
    fun finish(
        id: UUID,
        status: RefreshStatus,
        summaryJson: String,
    ): Boolean {
        require(status.terminal) { "finish() takes a terminal status, was $status" }
        return jdbc.update(
            "UPDATE dashboard_refreshes SET status = :status, finished_at = NOW(), summary_json = CAST(:summary AS jsonb)" +
                " WHERE id = :id AND status = 'RUNNING'",
            mapOf("id" to id, "status" to status.name, "summary" to summaryJson),
        ) == 1
    }

    /** Links [sourceName] of [refreshId] to its execution — called before that execution's first event. */
    fun link(link: RefreshExecutionLink) {
        jdbc.update(
            "INSERT INTO dashboard_refresh_executions (refresh_id, source_name, execution_id, shared)" +
                " VALUES (:refreshId, :source, :executionId, :shared)",
            mapOf(
                "refreshId" to link.refreshId,
                "source" to link.sourceName,
                "executionId" to link.executionId,
                "shared" to link.shared,
            ),
        )
    }

    fun linksOf(refreshId: UUID): List<RefreshExecutionLink> =
        jdbc.query(
            "SELECT refresh_id, source_name, execution_id, shared FROM dashboard_refresh_executions" +
                " WHERE refresh_id = :id ORDER BY source_name",
            mapOf("id" to refreshId),
        ) { rs, _ ->
            RefreshExecutionLink(
                rs.getObject("refresh_id", UUID::class.java),
                rs.getString("source_name"),
                rs.getObject("execution_id", UUID::class.java),
                rs.getBoolean("shared"),
            )
        }

    fun find(
        workspaceId: UUID,
        id: UUID,
    ): RefreshRecord? =
        jdbc
            .query(
                "$SELECT WHERE workspace_id = :workspaceId AND id = :id",
                mapOf("workspaceId" to workspaceId, "id" to id),
                ROW,
            ).singleOrNull()

    /** A dashboard's refreshes, newest first — [principalUserId] narrows to one person's own (null = every refresh). */
    fun list(
        workspaceId: UUID,
        dashboardId: UUID,
        principalUserId: UUID?,
        limit: Int,
        offset: Int = 0,
    ): List<RefreshRecord> =
        jdbc.query(
            "$SELECT WHERE workspace_id = :workspaceId AND dashboard_id = :dashboardId" +
                (if (principalUserId != null) " AND principal_user_id = :userId" else "") +
                " ORDER BY started_at DESC, id LIMIT :limit OFFSET :offset",
            MapSqlParameterSource()
                .addValue("workspaceId", workspaceId)
                .addValue("dashboardId", dashboardId)
                .addValue("userId", principalUserId)
                .addValue("limit", limit)
                .addValue("offset", offset),
            ROW,
        )

    fun count(
        workspaceId: UUID,
        dashboardId: UUID,
        principalUserId: UUID?,
    ): Long =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM dashboard_refreshes WHERE workspace_id = :workspaceId AND dashboard_id = :dashboardId" +
                (if (principalUserId != null) " AND principal_user_id = :userId" else ""),
            MapSqlParameterSource()
                .addValue("workspaceId", workspaceId)
                .addValue("dashboardId", dashboardId)
                .addValue("userId", principalUserId),
            Long::class.java,
        ) ?: 0L

    /** The person's own latest refresh of the dashboard, or null (`dashboards_get`'s `last_refresh`). */
    fun latestOf(
        workspaceId: UUID,
        dashboardId: UUID,
        principalUserId: UUID,
    ): RefreshRecord? = list(workspaceId, dashboardId, principalUserId, limit = 1).singleOrNull()

    /**
     * Closes the RUNNING refreshes started more than [staleAfterSeconds] ago (metadata-db §8.4): TIMED_OUT, with
     * `reason` `instance_lost` when one of its executions was ABORTED by the stale-execution sweep, else
     * `deadline_passed`. Returns the ids closed. The `status = 'RUNNING'` guard is the safety.
     */
    fun sweepStale(staleAfterSeconds: Long): List<UUID> =
        jdbc.query(
            """
            UPDATE dashboard_refreshes
               SET status = 'TIMED_OUT', finished_at = NOW(),
                   summary_json = summary_json || jsonb_build_object('reason', CASE WHEN EXISTS (
                           SELECT 1 FROM dashboard_refresh_executions l
                             JOIN pipeline_executions e ON e.execution_id = l.execution_id
                            WHERE l.refresh_id = dashboard_refreshes.id AND e.status = 'ABORTED'
                              AND e.error_json ->> 'code' = 'pipeline.execution.instance_lost')
                       THEN 'instance_lost' ELSE 'deadline_passed' END)
             WHERE status = 'RUNNING' AND started_at < NOW() - make_interval(secs => :staleAfterSeconds)
            RETURNING id
            """.trimIndent(),
            mapOf("staleAfterSeconds" to staleAfterSeconds.toDouble()),
        ) { rs, _ -> rs.getObject("id", UUID::class.java) }

    /** Retention (metadata-db §8.1): deletes refreshes finished more than [retentionDays] days ago; the cascade takes their links. */
    fun deleteFinishedOlderThan(retentionDays: Long): Int =
        jdbc.update(
            "DELETE FROM dashboard_refreshes WHERE finished_at IS NOT NULL AND finished_at < NOW() - make_interval(days => :days)",
            mapOf("days" to retentionDays.toInt()),
        )

    private companion object {
        const val SELECT =
            "SELECT id, dashboard_id, dashboard_version, workspace_id, instance_id, principal_user_id, principal_key_id, scope," +
                " targets_json::text AS targets_json, parameter_revision, selections_json::text AS selections_json, status," +
                " started_at, finished_at, summary_json::text AS summary_json FROM dashboard_refreshes"

        val ROW =
            RowMapper<RefreshRecord> { rs, _ ->
                RefreshRecord(
                    id = rs.getObject("id", UUID::class.java),
                    dashboardId = rs.getObject("dashboard_id", UUID::class.java),
                    dashboardVersion = rs.getInt("dashboard_version"),
                    workspaceId = rs.getObject("workspace_id", UUID::class.java),
                    instanceId = rs.getObject("instance_id", UUID::class.java),
                    principalUserId = rs.getObject("principal_user_id", UUID::class.java),
                    principalKeyId = rs.getString("principal_key_id"),
                    scope = ActionScope.valueOf(rs.getString("scope")),
                    targetsJson = rs.getString("targets_json"),
                    parameterRevision = rs.getInt("parameter_revision"),
                    selectionsJson = rs.getString("selections_json"),
                    status = RefreshStatus.valueOf(rs.getString("status")),
                    startedAt = rs.getTimestamp("started_at").toInstant(),
                    finishedAt = rs.getTimestamp("finished_at")?.toInstant(),
                    summaryJson = rs.getString("summary_json"),
                )
            }
    }
}

/**
 * The read side of a dashboard's refresh record that a TRANSPORT may hold (`dashboards_get`'s `last_refresh`): a service,
 * not the repository, because `ArchitectureGuardTest` keeps transports off `*Repository` types. It answers the CALLER's
 * own latest refresh and nothing wider — `null` when the person has none — so a tool result never names another
 * person's refresh, whatever role they hold.
 */
class DashboardRefreshHistory(
    private val repository: DashboardRefreshRepository,
) {
    fun latestOf(
        workspaceId: UUID,
        dashboardId: UUID,
        principalUserId: UUID,
    ): RefreshRecord? = repository.latestOf(workspaceId, dashboardId, principalUserId)
}
