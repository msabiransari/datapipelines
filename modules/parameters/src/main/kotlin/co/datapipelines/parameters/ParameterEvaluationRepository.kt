package co.datapipelines.parameters

import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * Persistence for `parameter_evaluations` and `parameter_evaluation_queries` (V48; metadata-db §4.37/§4.38) — the
 * durable evaluation history (#376). `NamedParameterJdbcTemplate` exclusively, every value a bind (module-structure
 * §8.1); `app` owns the schema. The once-per-attempt writes are GUARDED in their `WHERE`, the
 * `DashboardRefreshRepository` shape: a terminal write lands only on a row still open, so no path can finish a record
 * twice. The reads take the workspace and the set and filter by both — a record of another workspace is absent.
 *
 * Nothing written here can carry a value: the bind maps below hold ids, names, codes, counts and instants only
 * (spec §2.2's never-stored list), and `outcomes_json` is [OutcomesJson]'s bounded encoding.
 */
@Suppress("TooManyFunctions") // the single owner of every parameter_evaluation* statement, as ParameterSetRepository is
class ParameterEvaluationRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) {
    // ---- the recorder's writes -----------------------------------------------------------------------

    /** The `RUNNING` row (the START write). */
    fun insertRunning(evaluation: EvaluationStarted): Boolean =
        jdbc.update(
            """
            INSERT INTO parameter_evaluations (id, workspace_id, parameter_set_id, parameter_set_version, caller,
                principal_user_id, principal_key_id, correlation_id, status, started_at)
            VALUES (:id, :workspaceId, :setId, :version, :caller, :userId, :keyId, :correlationId, 'RUNNING', :startedAt)
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("id", evaluation.key.evaluationId)
                .addValue("workspaceId", evaluation.key.workspaceId)
                .addValue("setId", evaluation.key.parameterSetId)
                .addValue("version", evaluation.parameterSetVersion)
                .addValue("caller", evaluation.attempt.caller.name)
                .addValue("userId", evaluation.attempt.principalUserId)
                .addValue("keyId", evaluation.attempt.principalKeyId)
                .addValue("correlationId", evaluation.attempt.correlationId)
                .addValue("startedAt", Timestamp.from(evaluation.startedAt)),
        ) == 1

    /** One statement attempt's row, its outcome open. */
    fun insertQuery(query: QueryQueued) {
        jdbc.update(
            """
            INSERT INTO parameter_evaluation_queries (id, evaluation_id, parameter, datasource, template_id, template_version, queued_at)
            VALUES (:id, :evaluationId, :parameter, :datasource, :templateId, :templateVersion, :queuedAt)
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("id", query.id)
                .addValue("evaluationId", query.key.evaluationId)
                .addValue("parameter", query.parameter)
                .addValue("datasource", query.datasource)
                .addValue("templateId", query.template.id)
                .addValue("templateVersion", query.template.version)
                .addValue("queuedAt", Timestamp.from(query.queuedAt)),
        )
    }

    /** A statement attempt's end — once: the guard is the still-open outcome. */
    fun finishQuery(query: QueryEnded): Boolean = jdbc.update(FINISH_QUERY, queryEnd(query)) == 1

    /** The statements an evaluation's end abandoned, closed in one round trip — each guarded like [finishQuery]. */
    fun finishQueries(queries: List<QueryEnded>): Int =
        if (queries.isEmpty()) 0 else jdbc.batchUpdate(FINISH_QUERY, queries.map(::queryEnd).toTypedArray()).count { it == 1 }

    /** The terminal write — once: the guard is `status = 'RUNNING'`. */
    fun finish(evaluation: EvaluationEnded): Boolean {
        require(evaluation.status.terminalByWrite()) { "the terminal write takes an evaluation's own ending, was ${evaluation.status}" }
        return jdbc.update(
            """
            UPDATE parameter_evaluations
               SET status = :status, outcome_code = :outcomeCode, valid = :valid,
                   outcomes_json = CAST(:outcomes AS jsonb), finished_at = :finishedAt
             WHERE id = :id AND status = 'RUNNING'
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("id", evaluation.key.evaluationId)
                .addValue("status", evaluation.status.name)
                .addValue("outcomeCode", evaluation.outcomeCode)
                .addValue("valid", evaluation.valid)
                .addValue("outcomes", OutcomesJson.encode(evaluation.outcomes))
                .addValue("finishedAt", Timestamp.from(evaluation.finishedAt)),
        ) == 1
    }

    // ---- housekeeping (the hourly retention tick) -------------------------------------------------------

    /**
     * The stale sweep (metadata-db §8.5): a `RUNNING` row older than [staleAfterSeconds] — past any evaluate deadline —
     * becomes `INCOMPLETE`, one guarded `UPDATE` over the partial `idx_parameter_evaluations_running` index. It never
     * writes `TIMEOUT`: nobody knows how the lost attempt ended. Returns the ids it closed.
     */
    fun sweepStale(staleAfterSeconds: Long): List<UUID> =
        jdbc.query(
            """
            UPDATE parameter_evaluations SET status = 'INCOMPLETE', finished_at = NOW()
             WHERE status = 'RUNNING' AND started_at < NOW() - make_interval(secs => :staleAfterSeconds)
            RETURNING id
            """.trimIndent(),
            mapOf("staleAfterSeconds" to staleAfterSeconds.toDouble()),
        ) { rs, _ -> rs.getObject("id", UUID::class.java) }

    /**
     * Retention (metadata-db §8.1): ONE bounded batch — at most [batchSize] finished records started more than
     * [retentionDays] days ago, oldest first over `idx_parameter_evaluations_finished`; their query rows cascade. A
     * `RUNNING` row is never a candidate. Returns the records deleted.
     */
    fun deleteFinishedOlderThan(
        retentionDays: Long,
        batchSize: Int,
    ): Int =
        jdbc.update(
            """
            DELETE FROM parameter_evaluations
             WHERE id IN (
                       SELECT id
                         FROM parameter_evaluations
                        WHERE finished_at IS NOT NULL AND started_at < NOW() - make_interval(days => :days)
                        ORDER BY started_at
                        LIMIT :batchSize
                   )
            """.trimIndent(),
            mapOf("days" to retentionDays.toInt(), "batchSize" to batchSize),
        )

    // ---- the History tab's reads ------------------------------------------------------------------------

    /**
     * One page of [parameterSetId]'s records, newest first. [versions] is the lens's admitted version list: null admits
     * every version (purged ones included — the version is data, not a join); an empty list admits nothing.
     */
    fun page(
        workspaceId: UUID,
        parameterSetId: UUID,
        versions: Collection<Int>?,
        limit: Int,
        offset: Int,
    ): List<EvaluationSummary> {
        if (versions != null && versions.isEmpty()) return emptyList()
        return jdbc.query(
            "$SELECT_SUMMARY WHERE e.workspace_id = :workspaceId AND e.parameter_set_id = :setId${versionFilter(versions)}" +
                " ORDER BY e.started_at DESC, e.id LIMIT :limit OFFSET :offset",
            reads(workspaceId, parameterSetId, versions).addValue("limit", limit).addValue("offset", offset),
            SUMMARY,
        )
    }

    /** One record of [parameterSetId], with its per-parameter outcomes — null when absent or not admitted by [versions]. */
    fun find(
        workspaceId: UUID,
        parameterSetId: UUID,
        evaluationId: UUID,
        versions: Collection<Int>?,
    ): EvaluationDetail? {
        if (versions != null && versions.isEmpty()) return null
        return jdbc
            .query(
                "$SELECT_DETAIL WHERE e.workspace_id = :workspaceId AND e.parameter_set_id = :setId AND e.id = :id" +
                    versionFilter(versions),
                reads(workspaceId, parameterSetId, versions).addValue("id", evaluationId),
                DETAIL,
            ).singleOrNull()
    }

    /**
     * Whether [workspaceId] already holds a record under [evaluationId] — the observed route's reuse read (#417), a
     * primary-key lookup. The id alone is the key (V48), yet the read is scoped to the workspace: another workspace's id
     * answers as unused, so the route gives no cross-workspace existence signal (its insert conflict stays the backstop).
     */
    fun exists(
        workspaceId: UUID,
        evaluationId: UUID,
    ): Boolean =
        jdbc
            .queryForList(
                "SELECT 1 FROM parameter_evaluations WHERE workspace_id = :workspaceId AND id = :id",
                MapSqlParameterSource().addValue("workspaceId", workspaceId).addValue("id", evaluationId),
                Int::class.java,
            ).isNotEmpty()

    /** The statement attempts of one record, in the order they asked for admission (the caller resolved the record). */
    fun queries(evaluationId: UUID): List<QueryAttemptRecord> =
        jdbc.query(
            """
            SELECT id, parameter, datasource, template_id, template_version, queued_at, started_at, ended_at, outcome,
                   refusal_code, error_code, row_count
              FROM parameter_evaluation_queries
             WHERE evaluation_id = :id
             ORDER BY queued_at, started_at, id
            """.trimIndent(),
            mapOf("id" to evaluationId),
            QUERY,
        )

    private fun reads(
        workspaceId: UUID,
        parameterSetId: UUID,
        versions: Collection<Int>?,
    ): MapSqlParameterSource =
        MapSqlParameterSource()
            .addValue("workspaceId", workspaceId)
            .addValue("setId", parameterSetId)
            .also { params -> versions?.let { params.addValue("versions", it) } }

    private fun versionFilter(versions: Collection<Int>?): String =
        if (versions ==
            null
        ) {
            ""
        } else {
            " AND e.parameter_set_version IN (:versions)"
        }

    private fun queryEnd(query: QueryEnded): MapSqlParameterSource =
        MapSqlParameterSource()
            .addValue("id", query.id)
            .addValue("evaluationId", query.key.evaluationId)
            .addValue("outcome", query.outcome.name)
            .addValue("refusalCode", query.code.takeIf { query.outcome == QueryAttemptOutcome.REFUSED })
            .addValue("errorCode", query.code.takeIf { query.outcome == QueryAttemptOutcome.FAILED })
            .addValue("rowCount", query.rowCount.takeIf { query.outcome == QueryAttemptOutcome.EXECUTED })
            .addValue("startedAt", query.startedAt?.let(Timestamp::from))
            .addValue("endedAt", query.endedAt?.let(Timestamp::from))

    private companion object {
        const val FINISH_QUERY =
            "UPDATE parameter_evaluation_queries SET outcome = :outcome, refusal_code = :refusalCode, error_code = :errorCode," +
                " row_count = :rowCount, started_at = :startedAt, ended_at = :endedAt" +
                " WHERE id = :id AND evaluation_id = :evaluationId AND outcome IS NULL"

        const val SUMMARY_COLUMNS =
            "e.id, e.parameter_set_version, e.caller, e.principal_user_id, u.display_name AS principal_name, e.principal_key_id," +
                " e.correlation_id, e.status, e.outcome_code, e.valid, e.started_at, e.finished_at," +
                " COALESCE(jsonb_array_length(e.outcomes_json), 0) AS outcome_count," +
                " (SELECT COUNT(*) FROM parameter_evaluation_queries q WHERE q.evaluation_id = e.id) AS query_count"

        const val FROM = " FROM parameter_evaluations e LEFT JOIN users u ON u.id = e.principal_user_id"

        const val SELECT_SUMMARY = "SELECT $SUMMARY_COLUMNS$FROM"

        const val SELECT_DETAIL = "SELECT $SUMMARY_COLUMNS, e.outcomes_json::text AS outcomes_json$FROM"

        fun ParameterEvaluationStatus.terminalByWrite(): Boolean =
            this != ParameterEvaluationStatus.RUNNING && this != ParameterEvaluationStatus.INCOMPLETE

        fun ResultSet.instant(column: String): Instant? = getTimestamp(column)?.toInstant()

        fun ResultSet.summary(): EvaluationSummary =
            EvaluationSummary(
                id = getObject("id", UUID::class.java),
                version = getInt("parameter_set_version"),
                caller = EvaluationCaller.valueOf(getString("caller")),
                principalUserId = getObject("principal_user_id", UUID::class.java),
                principalName = getString("principal_name"),
                principalKeyId = getString("principal_key_id"),
                correlationId = getString("correlation_id"),
                status = ParameterEvaluationStatus.valueOf(getString("status")),
                outcomeCode = getString("outcome_code"),
                valid = getObject("valid") as Boolean?,
                outcomeCount = getInt("outcome_count"),
                queryCount = getInt("query_count"),
                startedAt = requireNotNull(instant("started_at")),
                finishedAt = instant("finished_at"),
            )

        val SUMMARY = RowMapper { rs, _ -> rs.summary() }

        val DETAIL = RowMapper { rs, _ -> EvaluationDetail(rs.summary(), OutcomesJson.decode(rs.getString("outcomes_json"))) }

        val QUERY =
            RowMapper { rs, _ ->
                QueryAttemptRecord(
                    id = rs.getObject("id", UUID::class.java),
                    parameter = rs.getString("parameter"),
                    datasource = rs.getString("datasource"),
                    templateId = rs.getString("template_id"),
                    templateVersion = rs.getInt("template_version"),
                    queuedAt = rs.instant("queued_at"),
                    startedAt = rs.instant("started_at"),
                    endedAt = rs.instant("ended_at"),
                    outcome = rs.getString("outcome")?.let(QueryAttemptOutcome::valueOf),
                    refusalCode = rs.getString("refusal_code"),
                    errorCode = rs.getString("error_code"),
                    rowCount = rs.getObject("row_count") as Int?,
                )
            }
    }
}
