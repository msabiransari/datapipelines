package co.datapipelines.lake.maintenance.plan

import co.datapipelines.lake.maintenance.MaintenanceOutcome
import co.datapipelines.lake.profiles.LakeProfile
import co.datapipelines.lake.profiles.LakeProfileDocument
import co.datapipelines.lake.profiles.LakeTablePauses
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/** The profile a measurement computes under, read ONCE before the manifests (D3a): its id, `updated_at` and values. */
data class ProfileCopy(
    val id: UUID,
    val updatedAt: Instant,
    val profile: LakeProfile,
)

/** The plan row's counts (D3; `lake_maintenance_plan`). */
data class PlanCounts(
    val tablesWithWork: Int,
    val tasksEnqueued: Int,
    val tasksMoved: Int,
    val tasksStillQueued: Int,
    val tablesMeasured: Int,
    val tablesNotMeasured: Int,
    val tablesStale: Int,
    val tablesRuleSilent: Int,
)

/**
 * The planner's statements (brief 959 D2, D3, D5): every one parameterised, autocommitting on its own through the lake's
 * bounded template. Table ids come from the catalog's rows. The figures' write is fenced by the attempt stamp: it
 * changes the row only while `attempt_id` is still the one this attempt set (a restart or another instance may have
 * attempted the table since).
 */
class PlanRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) {
    /** The catalog's clock: decisions read `now` from the database, never from the JVM. */
    fun catalogNow(): Instant = jdbc.queryForObject("SELECT now()", emptyMap<String, Any>(), Timestamp::class.java)!!.toInstant()

    /**
     * The page (D2): measured statuses only, the tables with an outstanding abandoned read left out; never attempted
     * first, and a table not yet attempted under its CURRENT profile pair counts as never attempted (D3a); then the oldest
     * attempt; then the id.
     */
    fun page(
        size: Int,
        excluded: Set<UUID>,
    ): List<PlanTarget> {
        val exclusion = if (excluded.isEmpty()) "" else "AND t.id NOT IN (:excluded) "
        return jdbc.query(
            "SELECT t.id, n.name AS namespace, t.name FROM lake_tables_managed t " +
                "JOIN lake_namespaces n ON n.id = t.namespace_id JOIN lake_profiles p ON p.id = t.profile_id " +
                "LEFT JOIN lake_table_measurements m ON m.table_id = t.id " +
                "WHERE t.status IN (:statuses) $exclusion" +
                "ORDER BY (m.table_id IS NULL OR m.attempt_profile_id <> t.profile_id " +
                "OR m.attempt_profile_updated_at <> p.updated_at) DESC, m.attempted_at ASC NULLS FIRST, t.id LIMIT :size",
            mapOf("statuses" to PlanStatus.MEASURED, "excluded" to excluded.toList(), "size" to size),
        ) { rs, _ -> PlanTarget(rs.getObject("id", UUID::class.java), rs.getString("namespace"), rs.getString("name")) }
    }

    /**
     * Starts an attempt: reads the table's profile once and stamps `attempt_id`, `attempted_at` and that profile pair,
     * keeping the figures and the last error. Null when the table has gone.
     */
    fun startAttempt(
        tableId: UUID,
        attemptId: UUID,
    ): ProfileCopy? {
        val copy =
            jdbc
                .query(
                    "SELECT p.id, p.updated_at, p.values_json::text AS values_json FROM lake_tables_managed t " +
                        "JOIN lake_profiles p ON p.id = t.profile_id WHERE t.id = :table",
                    mapOf("table" to tableId),
                ) { rs, _ -> Triple(rs.getObject("id", UUID::class.java), instant(rs, "updated_at")!!, rs.getString("values_json")) }
                .singleOrNull() ?: return null
        jdbc.update(
            "INSERT INTO lake_table_measurements (table_id, attempt_id, attempted_at, attempt_profile_id, attempt_profile_updated_at) " +
                "VALUES (:table, :attempt, now(), :profile, :updated) ON CONFLICT (table_id) DO UPDATE SET " +
                "attempt_id = EXCLUDED.attempt_id, attempted_at = EXCLUDED.attempted_at, " +
                "attempt_profile_id = EXCLUDED.attempt_profile_id, attempt_profile_updated_at = EXCLUDED.attempt_profile_updated_at",
            mapOf("table" to tableId, "attempt" to attemptId, "profile" to copy.first, "updated" to Timestamp.from(copy.second)),
        )
        return ProfileCopy(copy.first, copy.second, LakeProfileDocument.parse(copy.third))
    }

    /**
     * Publishes a successful attempt's figures, clears the error and upserts the day's usage row (a table with a
     * snapshot only; a later measurement of the day wins), in ONE statement, conditional on the attempt stamp. Answers
     * whether the row was this attempt's.
     */
    fun publish(
        tableId: UUID,
        attemptId: UUID,
        figures: TableFigures,
        profile: ProfileCopy,
    ): Boolean {
        val params =
            mapOf(
                "table" to tableId,
                "attempt" to attemptId,
                "snapshot" to figures.snapshotId,
                "bytes" to figures.currentBytes,
                "rows" to figures.currentRows,
                "dataFiles" to figures.dataFiles,
                "deleteFiles" to figures.deleteFiles,
                "manifests" to figures.manifests,
                "unsorted" to figures.unsortedFiles,
                "small" to figures.smallFiles,
                "overCap" to figures.unsortedOverCap,
                "candidate" to figures.candidateBytes,
                "profile" to profile.id,
                "updated" to Timestamp.from(profile.updatedAt),
                "files" to figures.dataFiles?.let { it + (figures.deleteFiles ?: 0) },
            )
        return jdbc.queryForObject(PUBLISH_SQL, params, Int::class.java) == 1
    }

    /** Stamps a failed or abandoned attempt's error, keeping the last good figures; conditional on the attempt stamp. */
    fun fail(
        tableId: UUID,
        attemptId: UUID,
        errorCode: String,
    ): Boolean =
        jdbc.update(
            "UPDATE lake_table_measurements SET error_code = :code WHERE table_id = :table AND attempt_id = :attempt",
            mapOf("table" to tableId, "attempt" to attemptId, "code" to errorCode),
        ) == 1

    /** Deletes the usage rows measured more than the retention ago (L8: 35 days), in one statement. */
    fun pruneUsage(): Int =
        jdbc.update(
            "DELETE FROM lake_storage_usage WHERE measured_at < now() - make_interval(days => :days)",
            mapOf("days" to USAGE_RETENTION_DAYS),
        )

    /** Overwrites the ONE plan row (id = 1) with this plan's time and counts. */
    fun writePlan(
        plannedAt: Instant,
        counts: PlanCounts,
    ) {
        jdbc.update(
            "INSERT INTO lake_maintenance_plan (id, planned_at, tables_with_work, tasks_enqueued, tasks_moved, tasks_still_queued, " +
                "tables_measured, tables_not_measured, tables_stale, tables_rule_silent) VALUES (1, :at, :work, :enqueued, :moved, " +
                ":still, :measured, :notMeasured, :stale, :silent) ON CONFLICT (id) DO UPDATE SET planned_at = EXCLUDED.planned_at, " +
                "tables_with_work = EXCLUDED.tables_with_work, tasks_enqueued = EXCLUDED.tasks_enqueued, " +
                "tasks_moved = EXCLUDED.tasks_moved, tasks_still_queued = EXCLUDED.tasks_still_queued, " +
                "tables_measured = EXCLUDED.tables_measured, tables_not_measured = EXCLUDED.tables_not_measured, " +
                "tables_stale = EXCLUDED.tables_stale, tables_rule_silent = EXCLUDED.tables_rule_silent",
            mapOf(
                "at" to Timestamp.from(plannedAt),
                "work" to counts.tablesWithWork,
                "enqueued" to counts.tasksEnqueued,
                "moved" to counts.tasksMoved,
                "still" to counts.tasksStillQueued,
                "measured" to counts.tablesMeasured,
                "notMeasured" to counts.tablesNotMeasured,
                "stale" to counts.tablesStale,
                "silent" to counts.tablesRuleSilent,
            ),
        )
    }

    /** The plan row, or null before the first plan. */
    fun lastPlan(): Pair<Instant, PlanCounts>? =
        jdbc
            .query("SELECT * FROM lake_maintenance_plan WHERE id = 1", emptyMap<String, Any>()) { rs, _ ->
                instant(rs, "planned_at")!! to
                    PlanCounts(
                        rs.getInt("tables_with_work"),
                        rs.getInt("tasks_enqueued"),
                        rs.getInt("tasks_moved"),
                        rs.getInt("tasks_still_queued"),
                        rs.getInt("tables_measured"),
                        rs.getInt("tables_not_measured"),
                        rs.getInt("tables_stale"),
                        rs.getInt("tables_rule_silent"),
                    )
            }.singleOrNull()

    /**
     * Every table the plan decides for, with its profile, pauses, measurement and run history, in ONE statement (D5):
     * a purged table whose purge deletion has finished is never read again.
     */
    fun decisionInputs(): List<DueInput> = jdbc.query(DECISION_SQL, mapOf("catalogPurged" to PlanStatus.PURGED)) { rs, _ -> dueInput(rs) }

    /** The measurement row of [tableId], or null when it was never attempted. */
    fun measurement(tableId: UUID): StoredMeasurement? =
        jdbc
            .query("SELECT * FROM lake_table_measurements WHERE table_id = :table", mapOf("table" to tableId)) { rs, _ ->
                measurement(rs, prefix = "")
            }.singleOrNull()

    private fun dueInput(rs: ResultSet): DueInput =
        DueInput(
            tableId = rs.getObject("id", UUID::class.java),
            status = rs.getString("status"),
            createdAt = instant(rs, "created_at")!!,
            profileId = rs.getObject("profile_id", UUID::class.java),
            profileUpdatedAt = instant(rs, "profile_updated_at")!!,
            profileAssignedAt = instant(rs, "profile_assigned_at")!!,
            profile = LakeProfileDocument.parse(rs.getString("values_json")),
            pause = LakeTablePauses.parse(rs.getString("policy_json")),
            measurement = rs.getObject("m_attempt_id")?.let { measurement(rs, prefix = "m_") },
            purgeDeletedAt = instant(rs, "purge_deleted_at"),
            finalProtectedUntil = instant(rs, "final_protected_until"),
            history =
                RunHistory(
                    lastCleanup = instant(rs, "last_cleanup_at"),
                    lastFinishedRewrite = instant(rs, "last_rewrite_at"),
                    lastRewriteAttempt =
                        instant(rs, "attempt_at")?.let { at ->
                            RewriteAttempt(
                                at = at,
                                outcome = MaintenanceOutcome.entries.single { it.wire == rs.getString("attempt_outcome") },
                                committed = rs.getObject("attempt_committed") as Boolean?,
                                leftForFreshRun = (rs.getObject("attempt_left") as Number?)?.toLong(),
                            )
                        },
                ),
        )

    private fun measurement(
        rs: ResultSet,
        prefix: String,
    ): StoredMeasurement {
        fun long(column: String): Long? = (rs.getObject(prefix + column) as Number?)?.toLong()
        val measuredAt = instant(rs, prefix + "measured_at")
        return StoredMeasurement(
            attemptId = rs.getObject(prefix + "attempt_id", UUID::class.java),
            attemptedAt = instant(rs, prefix + "attempted_at")!!,
            attemptProfileId = rs.getObject(prefix + "attempt_profile_id", UUID::class.java),
            attemptProfileUpdatedAt = instant(rs, prefix + "attempt_profile_updated_at")!!,
            errorCode = rs.getString(prefix + "error_code"),
            measuredAt = measuredAt,
            figures =
                measuredAt?.let {
                    TableFigures(
                        snapshotId = long("snapshot_id"),
                        currentBytes = long("current_bytes"),
                        currentRows = long("current_rows"),
                        dataFiles = long("data_files"),
                        deleteFiles = long("delete_files"),
                        manifests = long("manifests"),
                        unsortedFiles = long("unsorted_files"),
                        smallFiles = long("small_files"),
                        unsortedOverCap = long("unsorted_over_cap"),
                        candidateBytes = long("candidate_bytes"),
                    )
                },
            figuresProfileId = rs.getObject(prefix + "figures_profile_id", UUID::class.java),
            figuresProfileUpdatedAt = instant(rs, prefix + "figures_profile_updated_at"),
        )
    }

    private fun instant(
        rs: ResultSet,
        column: String,
    ): Instant? = rs.getTimestamp(column)?.toInstant()

    companion object {
        /** L8: usage rows are kept 35 days (a record rule, not tuning). */
        const val USAGE_RETENTION_DAYS = 35

        private const val PUBLISH_SQL =
            "WITH m AS (UPDATE lake_table_measurements SET error_code = NULL, measured_at = now(), snapshot_id = :snapshot, " +
                "current_bytes = :bytes, current_rows = :rows, data_files = :dataFiles, delete_files = :deleteFiles, " +
                "manifests = :manifests, unsorted_files = :unsorted, small_files = :small, unsorted_over_cap = :overCap, " +
                "candidate_bytes = :candidate, figures_profile_id = :profile, figures_profile_updated_at = :updated " +
                "WHERE table_id = :table AND attempt_id = :attempt RETURNING table_id, measured_at), " +
                "u AS (INSERT INTO lake_storage_usage (table_id, usage_day, measured_at, current_bytes, current_rows, current_files) " +
                "SELECT table_id, (measured_at AT TIME ZONE 'UTC')::date, measured_at, CAST(:bytes AS BIGINT), CAST(:rows AS BIGINT), " +
                "CAST(:files AS BIGINT) FROM m WHERE CAST(:snapshot AS BIGINT) IS NOT NULL " +
                "ON CONFLICT (table_id, usage_day) DO UPDATE SET measured_at = EXCLUDED.measured_at, " +
                "current_bytes = EXCLUDED.current_bytes, current_rows = EXCLUDED.current_rows, current_files = EXCLUDED.current_files " +
                "WHERE lake_storage_usage.measured_at <= EXCLUDED.measured_at) " +
                "SELECT count(*)::int FROM m"

        /** D5's three history values per table, each its own lateral over the table's FINISHED runs, newest first. */
        private const val ENTRY = "jsonb_array_elements(r.parts_json) e"
        private const val FINISHED =
            "FROM lake_maintenance_runs r WHERE r.table_id = t.id AND r.state = 'finished'"
        private const val NEWEST = "ORDER BY r.ended_at DESC, r.started_at DESC, r.id DESC LIMIT 1"
        private const val ATTEMPTED = "e->'attempted' = 'true'::jsonb"

        val DECISION_SQL =
            "SELECT t.id, t.status, t.created_at, t.profile_id, t.profile_assigned_at, t.policy_json::text AS policy_json, " +
                "t.purge_deleted_at, p.updated_at AS profile_updated_at, p.values_json::text AS values_json, " +
                "m.attempt_id AS m_attempt_id, m.attempted_at AS m_attempted_at, m.attempt_profile_id AS m_attempt_profile_id, " +
                "m.attempt_profile_updated_at AS m_attempt_profile_updated_at, m.error_code AS m_error_code, " +
                "m.measured_at AS m_measured_at, m.snapshot_id AS m_snapshot_id, m.current_bytes AS m_current_bytes, " +
                "m.current_rows AS m_current_rows, m.data_files AS m_data_files, m.delete_files AS m_delete_files, " +
                "m.manifests AS m_manifests, m.unsorted_files AS m_unsorted_files, m.small_files AS m_small_files, " +
                "m.unsorted_over_cap AS m_unsorted_over_cap, m.candidate_bytes AS m_candidate_bytes, " +
                "m.figures_profile_id AS m_figures_profile_id, m.figures_profile_updated_at AS m_figures_profile_updated_at, " +
                "fr.protected_until AS final_protected_until, lc.ended_at AS last_cleanup_at, lr.ended_at AS last_rewrite_at, " +
                "la.ended_at AS attempt_at, la.outcome AS attempt_outcome, la.committed AS attempt_committed, " +
                "la.left_count AS attempt_left " +
                "FROM lake_tables_managed t JOIN lake_namespaces n ON n.id = t.namespace_id " +
                "JOIN lake_profiles p ON p.id = t.profile_id LEFT JOIN lake_table_measurements m ON m.table_id = t.id " +
                // The final revision of a purged table: its newest dropped revision whose location is this table's own.
                "LEFT JOIN LATERAL (SELECT v.protected_until FROM lake_table_revisions v WHERE t.status = :catalogPurged " +
                "AND v.namespace = n.name AND v.table_name = t.name AND v.dropped_at IS NOT NULL " +
                "AND strpos(v.metadata_location, '/' || t.id::text || '/') > 0 ORDER BY v.seq DESC LIMIT 1) fr ON true " +
                "LEFT JOIN LATERAL (SELECT r.ended_at $FINISHED AND EXISTS (SELECT 1 FROM $ENTRY WHERE e->>'name' = 'cleanup' " +
                "AND $ATTEMPTED AND e->>'outcome' IN ('done', 'nothing_to_do')) $NEWEST) lc ON true " +
                "LEFT JOIN LATERAL (SELECT r.ended_at $FINISHED AND EXISTS (SELECT 1 FROM $ENTRY WHERE $ATTEMPTED AND " +
                "((e->>'name' IN ('manifest_rewrite', 'compaction') AND e->>'outcome' IN ('done', 'nothing_to_do')) OR " +
                "(e->>'name' = 'compaction' AND e->>'outcome' = 'capacity' AND e->'committed' = 'true'::jsonb))) $NEWEST) lr ON true " +
                "LEFT JOIN LATERAL (SELECT r.ended_at, e->>'outcome' AS outcome, " +
                "CASE WHEN jsonb_typeof(e->'committed') = 'boolean' THEN (e->>'committed')::boolean END AS committed, " +
                "CASE WHEN jsonb_typeof(e->'left_for_fresh_run') = 'number' AND (e->>'left_for_fresh_run') ~ '^[0-9]{1,18}$' " +
                "THEN (e->>'left_for_fresh_run')::bigint END AS left_count " +
                "FROM lake_maintenance_runs r CROSS JOIN LATERAL $ENTRY WHERE r.table_id = t.id AND r.state = 'finished' " +
                "AND e->>'name' = 'compaction' AND $ATTEMPTED AND e->>'outcome' IN ('done', 'nothing_to_do', 'capacity', 'deadline') " +
                "$NEWEST) la ON true " +
                "WHERE NOT (t.status = :catalogPurged AND t.purge_deleted_at IS NOT NULL) ORDER BY t.id"
    }
}
