package co.datapipelines.application.endpoints

import co.datapipelines.executor.CaptureSeal
import co.datapipelines.executor.CaptureValueStore
import co.datapipelines.executor.CapturedValue
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/** One stored declared output of a captured execution, as the diagnostic view reads it. */
data class StoredCaptureValue(
    val nodeId: String,
    val outputKey: String,
    /** The JSON text of the value; null for an omitted one. */
    val valueJson: String?,
    val valueBytes: Long?,
    /** The wire spelling of why the value was not kept (`byte_budget_exhausted`, `unserializable`); null when kept. */
    val omittedReason: String?,
    val recordedAt: Instant,
    val expiresAt: Instant,
)

/** A captured run's closing summary, as the diagnostic view reads it. */
data class StoredCaptureRun(
    val sessionId: UUID,
    val valuesCaptured: Int,
    val valuesOmitted: Int,
    val writeFailures: Int,
    val bytesUsed: Long,
    val sealedAt: Instant,
    val expiresAt: Instant,
)

/**
 * The private diagnostic store of runtime capture (metadata-db §4.63, §4.64; #1043): `execution_capture_values` and
 * `execution_capture_runs`. Never part of a business transaction — every statement is its own autocommit statement
 * on the metadata pool, so a diagnostic write can neither join nor roll back the run's business work.
 *
 * **The store refuses what was not admitted.** A write is an `INSERT … SELECT` from the execution's reservation in the
 * same workspace, conditional on the reservation having been captured and its `capture_expires_at` being after the
 * write's time; the stored `expires_at` is the reservation's, never the caller's. So an uncaptured execution, another
 * workspace's execution, or a write after expiry inserts nothing (false).
 *
 * **Reads refuse what has expired**, whether or not the retention tick has deleted it yet: every read is bounded by
 * `expires_at > :now`. [purgeExpired] deletes physically in bounded batches; it holds no state between ticks, so a
 * restart simply resumes with the next tick's batch.
 */
class ExecutionCaptureStore(
    private val jdbc: NamedParameterJdbcTemplate,
) : CaptureValueStore {
    override fun write(value: CapturedValue): Boolean =
        jdbc.update(
            """
            INSERT INTO execution_capture_values (
                execution_id, node_id, output_key, workspace_id, state, omitted_reason, value_json, value_bytes,
                recorded_at, expires_at)
            SELECT r.execution_id, :nodeId, :outputKey, r.workspace_id, :state, :omittedReason, :valueJson, :valueBytes,
                   :recordedAt, r.capture_expires_at
              FROM published_mutation_reservations r
             WHERE r.execution_id = :executionId AND r.workspace_id = :workspaceId
               AND r.capture_session_id IS NOT NULL AND :recordedAt < r.capture_expires_at
            ON CONFLICT (execution_id, node_id, output_key) DO NOTHING
            """.trimIndent(),
            mapOf(
                "executionId" to value.executionId,
                "workspaceId" to value.workspaceId,
                "nodeId" to value.nodeId,
                "outputKey" to value.outputKey,
                "state" to if (value.omittedReason == null) STATE_CAPTURED else STATE_OMITTED,
                "omittedReason" to value.omittedReason?.wire,
                "valueJson" to value.valueJson,
                "valueBytes" to value.valueBytes,
                "recordedAt" to Timestamp.from(value.recordedAt),
            ),
        ) == 1

    override fun seal(seal: CaptureSeal): Boolean =
        jdbc.update(
            """
            INSERT INTO execution_capture_runs (
                execution_id, workspace_id, session_id, values_captured, values_omitted, write_failures, bytes_used,
                sealed_at, expires_at)
            SELECT r.execution_id, r.workspace_id, r.capture_session_id, :captured, :omitted, :failures, :bytes,
                   :sealedAt, r.capture_expires_at
              FROM published_mutation_reservations r
             WHERE r.execution_id = :executionId AND r.workspace_id = :workspaceId
               AND r.capture_session_id = :sessionId AND :sealedAt < r.capture_expires_at
            ON CONFLICT (execution_id) DO NOTHING
            """.trimIndent(),
            mapOf(
                "executionId" to seal.executionId,
                "workspaceId" to seal.workspaceId,
                "sessionId" to seal.sessionId,
                "captured" to seal.valuesCaptured,
                "omitted" to seal.valuesOmitted,
                "failures" to seal.writeFailures,
                "bytes" to seal.bytesUsed,
                "sealedAt" to Timestamp.from(seal.sealedAt),
            ),
        ) == 1

    /** The unexpired captured values of [executionId] in [workspaceId] at [now], in recording order. */
    fun values(
        executionId: UUID,
        workspaceId: UUID,
        now: Instant,
    ): List<StoredCaptureValue> =
        jdbc.query(
            """
            SELECT node_id, output_key, value_json, value_bytes, omitted_reason, recorded_at, expires_at
              FROM execution_capture_values
             WHERE execution_id = :executionId AND workspace_id = :workspaceId AND expires_at > :now
             ORDER BY recorded_at, node_id, output_key
            """.trimIndent(),
            mapOf("executionId" to executionId, "workspaceId" to workspaceId, "now" to Timestamp.from(now)),
            VALUE_MAPPER,
        )

    /** The unexpired closing summary of [executionId] in [workspaceId] at [now], or null (unsealed, or expired). */
    fun run(
        executionId: UUID,
        workspaceId: UUID,
        now: Instant,
    ): StoredCaptureRun? =
        jdbc
            .query(
                """
                SELECT session_id, values_captured, values_omitted, write_failures, bytes_used, sealed_at, expires_at
                  FROM execution_capture_runs
                 WHERE execution_id = :executionId AND workspace_id = :workspaceId AND expires_at > :now
                """.trimIndent(),
                mapOf("executionId" to executionId, "workspaceId" to workspaceId, "now" to Timestamp.from(now)),
                RUN_MAPPER,
            ).firstOrNull()

    /**
     * Deletes at most [batchSize] expired value rows and at most [batchSize] expired summaries (oldest first, through
     * the `expires_at` indexes) as of [now]. Returns the number of rows deleted; a result of `2 * batchSize` or more
     * means a backlog the next call continues.
     */
    fun purgeExpired(
        now: Instant,
        batchSize: Int,
    ): Int {
        require(batchSize > 0) { "the purge batch must be positive, was $batchSize" }
        val params = mapOf("now" to Timestamp.from(now), "batch" to batchSize)
        val values =
            jdbc.update(
                """
                DELETE FROM execution_capture_values
                 WHERE (execution_id, node_id, output_key) IN (
                     SELECT execution_id, node_id, output_key FROM execution_capture_values
                      WHERE expires_at <= :now ORDER BY expires_at LIMIT :batch)
                """.trimIndent(),
                params,
            )
        val runs =
            jdbc.update(
                """
                DELETE FROM execution_capture_runs
                 WHERE execution_id IN (
                     SELECT execution_id FROM execution_capture_runs WHERE expires_at <= :now ORDER BY expires_at LIMIT :batch)
                """.trimIndent(),
                params,
            )
        return values + runs
    }

    private companion object {
        const val STATE_CAPTURED = "CAPTURED"
        const val STATE_OMITTED = "OMITTED"

        val VALUE_MAPPER =
            RowMapper { rs: ResultSet, _: Int ->
                StoredCaptureValue(
                    nodeId = rs.getString("node_id"),
                    outputKey = rs.getString("output_key"),
                    valueJson = rs.getString("value_json"),
                    valueBytes = rs.getObject("value_bytes") as Long?,
                    omittedReason = rs.getString("omitted_reason"),
                    recordedAt = rs.getTimestamp("recorded_at").toInstant(),
                    expiresAt = rs.getTimestamp("expires_at").toInstant(),
                )
            }

        val RUN_MAPPER =
            RowMapper { rs: ResultSet, _: Int ->
                StoredCaptureRun(
                    sessionId = rs.getObject("session_id", UUID::class.java),
                    valuesCaptured = rs.getInt("values_captured"),
                    valuesOmitted = rs.getInt("values_omitted"),
                    writeFailures = rs.getInt("write_failures"),
                    bytesUsed = rs.getLong("bytes_used"),
                    sealedAt = rs.getTimestamp("sealed_at").toInstant(),
                    expiresAt = rs.getTimestamp("expires_at").toInstant(),
                )
            }
    }
}
