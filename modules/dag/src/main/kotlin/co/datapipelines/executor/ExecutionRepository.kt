package co.datapipelines.executor

import co.datapipelines.pipeline.PipelineErrorCodes
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

/** How an execution was initiated (enums.md §18) — the `triggered_via` CHECK constraint's values. */
enum class ExecutionTrigger {
    UI,
    REST,
    MCP,

    /** Spawned by a parent execution's PIPELINE node (metadata-db §4.6 lineage columns). */
    PIPELINE,
}

/**
 * One `pipeline_executions` row (metadata-db §4.6).
 *
 * `result_row_count` / `result_size_bytes` are **history, not availability**: a row saying
 * `resultSizeBytes = 4200` says nothing about whether that result is still fetchable — that is a
 * Redis TTL question, and past the TTL the cursor returns `result.expired`. Both are null for a
 * pipeline with zero caller nodes (legal under D1).
 *
 * The lineage fields (V3) link a composition family: a PIPELINE node's child execution records
 * [parentExecutionId]/[parentNodeId]; [rootExecutionId] is the top ancestor, equal to
 * [executionId] for roots — a null [rootExecutionId] on the record means "I am the root" and the
 * repository persists [executionId] itself (the column is NOT NULL since V3's backfill).
 */
data class ExecutionRecord(
    val executionId: UUID,
    val pipelineId: UUID,
    val pipelineVersion: Int,
    val status: ExecutionStatus,
    val parametersJson: String,
    val triggeredBy: UUID,
    val triggeredVia: ExecutionTrigger,
    val correlationId: UUID? = null,
    val startedAt: Instant = Instant.now(),
    val completedAt: Instant? = null,
    val durationMs: Long? = null,
    val failedNodeId: String? = null,
    val errorJson: String? = null,
    val nodeStatsJson: String? = null,
    val resultRowCount: Long? = null,
    val resultSizeBytes: Long? = null,
    val parentExecutionId: UUID? = null,
    val parentNodeId: String? = null,
    val rootExecutionId: UUID? = null,
)

/**
 * Persistence for `pipeline_executions` (metadata-db §4.6, module-structure §3.1).
 *
 * `NamedParameterJdbcTemplate` exclusively (§8.1) — explicit SQL, no ORM. The row's lifecycle is
 * INSERT-on-start then exactly one terminal UPDATE, which is why the table has no `updated_at`:
 * `completed_at` already carries that timestamp (metadata-db §4.6).
 *
 * The repository lives in `dag` because `dag` owns the entity; schema creation belongs to `app`'s
 * Flyway alone (§3.1 rule 2) and nothing here creates or alters a table.
 */
class ExecutionRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) {
    /** Inserts the `RUNNING` row at execution start. */
    fun create(record: ExecutionRecord): ExecutionRecord {
        jdbc.update(
            """
            INSERT INTO pipeline_executions (
                execution_id, pipeline_id, pipeline_version, status, parameters_json,
                triggered_by, triggered_via, correlation_id, started_at,
                parent_execution_id, parent_node_id, root_execution_id
            ) VALUES (
                :executionId, :pipelineId, :pipelineVersion, :status, CAST(:parametersJson AS jsonb),
                :triggeredBy, :triggeredVia, :correlationId, :startedAt,
                :parentExecutionId, :parentNodeId, :rootExecutionId
            )
            """.trimIndent(),
            mapOf(
                "executionId" to record.executionId,
                "pipelineId" to record.pipelineId,
                "pipelineVersion" to record.pipelineVersion,
                "status" to record.status.name,
                "parametersJson" to record.parametersJson,
                "triggeredBy" to record.triggeredBy,
                "triggeredVia" to record.triggeredVia.name,
                "correlationId" to record.correlationId,
                "startedAt" to java.sql.Timestamp.from(record.startedAt),
                "parentExecutionId" to record.parentExecutionId,
                "parentNodeId" to record.parentNodeId,
                // NOT NULL since V3: a null rootExecutionId on the record means "I am the root".
                "rootExecutionId" to (record.rootExecutionId ?: record.executionId),
            ),
        )
        return record
    }

    /**
     * The single terminal UPDATE: status, timings, stats and — when the pipeline had a caller
     * node — the result's history columns.
     *
     * @return true when a row was updated; false when [executionId] is unknown.
     */
    @Suppress("LongParameterList")
    fun complete(
        executionId: UUID,
        status: ExecutionStatus,
        completedAt: Instant,
        durationMs: Long,
        nodeStatsJson: String,
        failedNodeId: String? = null,
        errorJson: String? = null,
        resultRowCount: Long? = null,
        resultSizeBytes: Long? = null,
    ): Boolean =
        jdbc.update(
            """
            UPDATE pipeline_executions
               SET status = :status,
                   completed_at = :completedAt,
                   duration_ms = :durationMs,
                   node_stats_json = CAST(:nodeStatsJson AS jsonb),
                   failed_node_id = :failedNodeId,
                   error_json = CAST(:errorJson AS jsonb),
                   result_row_count = :resultRowCount,
                   result_size_bytes = :resultSizeBytes
             WHERE execution_id = :executionId
            """.trimIndent(),
            mapOf(
                "executionId" to executionId,
                "status" to status.name,
                "completedAt" to java.sql.Timestamp.from(completedAt),
                "durationMs" to durationMs,
                "nodeStatsJson" to nodeStatsJson,
                "failedNodeId" to failedNodeId,
                "errorJson" to errorJson,
                "resultRowCount" to resultRowCount,
                "resultSizeBytes" to resultSizeBytes,
            ),
        ) == 1

    /**
     * Fills the §4.6 result-history columns **after** [complete] (P7 additive API).
     *
     * The terminal event that completes the row carries no result size, and the emitter
     * fires before `PipelineExecutor.execute` returns the `resultRef` — so the surface
     * that receives the `ExecutionResult` describes the stored result and lands
     * `result_row_count` / `result_size_bytes` here. Without them the REST cursor and
     * the §10.2 metadata cannot distinguish "succeeded with a result" from "zero caller
     * nodes" — both would read as NULL.
     *
     * @return true when a row was updated; false when [executionId] is unknown.
     */
    fun recordResult(
        executionId: UUID,
        resultRowCount: Long,
        resultSizeBytes: Long,
    ): Boolean =
        jdbc.update(
            """
            UPDATE pipeline_executions
               SET result_row_count = :resultRowCount,
                   result_size_bytes = :resultSizeBytes
             WHERE execution_id = :executionId
            """.trimIndent(),
            mapOf(
                "executionId" to executionId,
                "resultRowCount" to resultRowCount,
                "resultSizeBytes" to resultSizeBytes,
            ),
        ) == 1

    /** One execution's metadata — `GET /api/v1/executions/{id}` (rest-api §10.2). */
    fun findById(executionId: UUID): ExecutionRecord? =
        jdbc
            .query("$SELECT_COLUMNS WHERE execution_id = :executionId", mapOf("executionId" to executionId), MAPPER)
            .singleOrNull()

    /** Executions for one pipeline, newest first — the `idx_executions_pipeline` access path. */
    fun findByPipeline(
        pipelineId: UUID,
        limit: Int = DEFAULT_PAGE,
        offset: Int = 0,
    ): List<ExecutionRecord> =
        jdbc.query(
            "$SELECT_COLUMNS WHERE pipeline_id = :pipelineId ORDER BY started_at DESC LIMIT :limit OFFSET :offset",
            mapOf("pipelineId" to pipelineId, "limit" to limit, "offset" to offset),
            MAPPER,
        )

    /** The whole execution family (root + descendants), newest first — the `idx_executions_root` access path. */
    fun findByRoot(rootExecutionId: UUID): List<ExecutionRecord> =
        jdbc.query(
            "$SELECT_COLUMNS WHERE root_execution_id = :root ORDER BY started_at DESC",
            mapOf("root" to rootExecutionId),
            MAPPER,
        )

    /**
     * Executions triggered by one user, newest first — the `idx_executions_user` access path.
     *
     * The optional predicates are evaluated **in SQL**: a surface that paginates (rest-api §10.1)
     * must cut the page after filtering, or `has_more` and page fullness are wrong — filtering in
     * memory after `LIMIT` was exactly that bug (gate C, B4). [pipelineId] narrows to one
     * pipeline; it is not an authorization dimension — [userId] is always required, so no null
     * can widen the scope onto another user's history.
     */
    @Suppress("LongParameterList")
    fun findByUser(
        userId: UUID,
        pipelineId: UUID? = null,
        status: ExecutionStatus? = null,
        startedAfter: Instant? = null,
        startedBefore: Instant? = null,
        limit: Int = DEFAULT_PAGE,
        offset: Int = 0,
    ): List<ExecutionRecord> {
        val (where, params) =
            filteredQuery(
                "triggered_by = :userId",
                mapOf("userId" to userId),
                pipelineId,
                status,
                startedAfter,
                startedBefore,
            )
        return jdbc.query(
            "$SELECT_COLUMNS $where ORDER BY started_at DESC LIMIT :limit OFFSET :offset",
            params + mapOf("limit" to limit, "offset" to offset),
            MAPPER,
        )
    }

    /**
     * Every execution, newest first — the **admin** listing behind `GET /executions` (rest-api
     * §10.1) when the principal holds `admin` and is therefore not confined to their own runs
     * (auth §7.6). Deliberately a separate method rather than a nullable `userId` on [findByUser]:
     * "list across all users" is an authorization decision, and a method whose scoping vanishes
     * when an argument happens to be null is one null away from leaking another user's history.
     *
     * @param pipelineId when set, the same `pipeline_id` filter §10.1 documents — served by
     *   `idx_executions_pipeline (pipeline_id, started_at DESC)`. Unfiltered, this sorts on
     *   `started_at` with no index of its own to lean on (metadata-db §4.6 indexes all lead with
     *   another column); that is acceptable for a paginated admin-only path and is the reason
     *   [limit] is bounded by default rather than optional.
     *
     * The optional status/time predicates are SQL-level for the same reason as [findByUser]'s
     * (gate C, B4): pagination must happen after filtering, not before it.
     */
    @Suppress("LongParameterList")
    fun findAll(
        pipelineId: UUID? = null,
        status: ExecutionStatus? = null,
        startedAfter: Instant? = null,
        startedBefore: Instant? = null,
        limit: Int = DEFAULT_PAGE,
        offset: Int = 0,
    ): List<ExecutionRecord> {
        val (where, params) = filteredQuery(null, emptyMap(), pipelineId, status, startedAfter, startedBefore)
        return jdbc.query(
            "$SELECT_COLUMNS $where ORDER BY started_at DESC LIMIT :limit OFFSET :offset",
            params + mapOf("limit" to limit, "offset" to offset),
            MAPPER,
        )
    }

    /**
     * Builds the WHERE clause and its parameters from the optional §10.1 filters. Every value is
     * bound, never interpolated — only the clause *shape* is composed.
     */
    private fun filteredQuery(
        baseClause: String?,
        baseParams: Map<String, Any?>,
        pipelineId: UUID?,
        status: ExecutionStatus?,
        startedAfter: Instant?,
        startedBefore: Instant?,
    ): Pair<String, Map<String, Any?>> {
        val clauses = mutableListOf<String>()
        listOfNotNull(baseClause).forEach(clauses::add)
        val params = baseParams.toMutableMap()
        if (pipelineId != null) {
            clauses += "pipeline_id = :pipelineId"
            params["pipelineId"] = pipelineId
        }
        if (status != null) {
            clauses += "status = :status"
            params["status"] = status.name
        }
        if (startedAfter != null) {
            clauses += "started_at >= :startedAfter"
            params["startedAfter"] = java.sql.Timestamp.from(startedAfter)
        }
        if (startedBefore != null) {
            clauses += "started_at <= :startedBefore"
            params["startedBefore"] = java.sql.Timestamp.from(startedBefore)
        }
        val where = if (clauses.isEmpty()) "" else "WHERE " + clauses.joinToString(" AND ")
        return where to params
    }

    /**
     * The crash sweep (metadata-db §8.3): `RUNNING` rows older than
     * `datapipelines.executions.stale-timeout-minutes` belong to an instance that died.
     *
     * This is **not** a cancellation (§8.3): nothing is running any more, so there is no statement
     * to cancel and no `execution_aborted` event — only the persisted status changes, recorded
     * with `pipeline.execution.instance_lost`.
     *
     * `duration_ms` is written like every other terminal transition (F1, metadata-db §8.3): a swept
     * row that carried `completed_at` but a null duration was the one terminal shape a consumer had
     * to special-case, for no reason.
     *
     * The error envelope is built **here** (F2) rather than taken from the caller. The code is
     * `pipeline.execution.instance_lost` and there is exactly one correct spelling of it; letting
     * each caller pass a string invited two.
     *
     * @return the number of rows swept.
     */
    fun sweepStaleRunning(olderThan: Instant): Int =
        jdbc.update(
            """
            UPDATE pipeline_executions
               SET status = 'ABORTED',
                   completed_at = NOW(),
                   duration_ms = EXTRACT(EPOCH FROM (NOW() - started_at)) * 1000,
                   error_json = CAST(:errorJson AS jsonb)
             WHERE status = 'RUNNING' AND started_at < :olderThan
            """.trimIndent(),
            mapOf("olderThan" to java.sql.Timestamp.from(olderThan), "errorJson" to INSTANCE_LOST_JSON),
        )

    private companion object {
        const val DEFAULT_PAGE = 50

        /** The one place the crash sweep's error envelope is written (F2). */
        val INSTANCE_LOST_JSON = """{"code":"${PipelineErrorCodes.Execution.INSTANCE_LOST}"}"""

        val SELECT_COLUMNS =
            """
            SELECT execution_id, pipeline_id, pipeline_version, status, parameters_json::TEXT AS parameters_json,
                   triggered_by, triggered_via, correlation_id, started_at, completed_at, duration_ms,
                   failed_node_id, error_json::TEXT AS error_json, node_stats_json::TEXT AS node_stats_json,
                   result_row_count, result_size_bytes,
                   parent_execution_id, parent_node_id, root_execution_id
              FROM pipeline_executions
            """.trimIndent()

        val MAPPER =
            RowMapper { rs: ResultSet, _: Int ->
                ExecutionRecord(
                    executionId = rs.getObject("execution_id", UUID::class.java),
                    pipelineId = rs.getObject("pipeline_id", UUID::class.java),
                    pipelineVersion = rs.getInt("pipeline_version"),
                    status = ExecutionStatus.valueOf(rs.getString("status")),
                    parametersJson = rs.getString("parameters_json"),
                    triggeredBy = rs.getObject("triggered_by", UUID::class.java),
                    triggeredVia = ExecutionTrigger.valueOf(rs.getString("triggered_via")),
                    correlationId = rs.getObject("correlation_id", UUID::class.java),
                    startedAt = rs.getTimestamp("started_at").toInstant(),
                    completedAt = rs.getTimestamp("completed_at")?.toInstant(),
                    durationMs = rs.getObject("duration_ms")?.let { (it as Number).toLong() },
                    failedNodeId = rs.getString("failed_node_id"),
                    errorJson = rs.getString("error_json"),
                    nodeStatsJson = rs.getString("node_stats_json"),
                    resultRowCount = rs.getObject("result_row_count")?.let { (it as Number).toLong() },
                    resultSizeBytes = rs.getObject("result_size_bytes")?.let { (it as Number).toLong() },
                    parentExecutionId = rs.getObject("parent_execution_id", UUID::class.java),
                    parentNodeId = rs.getString("parent_node_id"),
                    rootExecutionId = rs.getObject("root_execution_id", UUID::class.java),
                )
            }
    }
}
