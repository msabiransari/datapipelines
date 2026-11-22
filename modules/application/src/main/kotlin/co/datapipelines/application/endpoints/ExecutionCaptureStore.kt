package co.datapipelines.application.endpoints

import co.datapipelines.executor.CaptureSeal
import co.datapipelines.executor.CaptureValueStore
import co.datapipelines.executor.CapturedValue
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.support.TransactionOperations
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Clock
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
 * `execution_capture_runs`. Never part of a business transaction — each write is its own short metadata transaction,
 * so a diagnostic write can neither join nor roll back the run's business work.
 *
 * **The store refuses what was not admitted, at the time the write reaches storage.** A write first locks the
 * execution's reservation in the same workspace (`FOR KEY SHARE`, the lock the detail rows' foreign key takes anyway),
 * requires it to have been captured and reads [clock] once — refusing at once a write already past the deadline. The
 * INSERT itself can still wait (a lock on its own detail table, a conflicting uncommitted row), so [clock] is read
 * AGAIN after the INSERT returns, and a write whose deadline passed during that wait rolls its transaction back and
 * reports false: no lock the write takes comes after that second read, so an expiry purge that completed while the
 * INSERT waited is never followed by the insertion of the expired row. A value or seal produced before the deadline
 * but queued, delayed or blocked until after it is refused (false) — its own `recorded_at`/`sealed_at` is kept as
 * metadata, never as expiry authority. The stored `expires_at` is the reservation's, never the caller's. So an
 * uncaptured execution, another workspace's execution, or a write after expiry inserts nothing.
 *
 * **Reads refuse what has expired**, whether or not the retention tick has deleted it yet: every read is bounded by
 * `expires_at > :now`. [purgeExpired] deletes physically in bounded batches; it holds no state between ticks, so a
 * restart simply resumes with the next tick's batch. A write that passed its second deadline check has no lock left to
 * wait for and commits at once; only a deadline falling between that check and the commit can leave a row past it,
 * until the next purge batch — and such a row is never readable.
 */
class ExecutionCaptureStore(
    private val jdbc: NamedParameterJdbcTemplate,
    /** The metadata transactions each write runs in; null only for a [purgeOnly] store, whose writes then refuse to run. */
    private val transactions: TransactionOperations?,
    private val clock: Clock = Clock.systemUTC(),
) : CaptureValueStore {
    override fun write(value: CapturedValue): Boolean =
        beforeDeadline(value.executionId, value.workspaceId, sessionId = null) { expiresAt ->
            jdbc.update(
                """
                INSERT INTO execution_capture_values (
                    execution_id, node_id, output_key, workspace_id, state, omitted_reason, value_text, value_bytes,
                    recorded_at, expires_at)
                VALUES (
                    :executionId, :nodeId, :outputKey, :workspaceId, :state, :omittedReason, :valueJson, :valueBytes,
                    :recordedAt, :expiresAt)
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
                    "expiresAt" to Timestamp.from(expiresAt),
                ),
            ) == 1
        }

    override fun seal(seal: CaptureSeal): Boolean =
        beforeDeadline(seal.executionId, seal.workspaceId, seal.sessionId) { expiresAt ->
            jdbc.update(
                """
                INSERT INTO execution_capture_runs (
                    execution_id, workspace_id, session_id, values_captured, values_omitted, write_failures, bytes_used,
                    sealed_at, expires_at)
                VALUES (
                    :executionId, :workspaceId, :sessionId, :captured, :omitted, :failures, :bytes, :sealedAt, :expiresAt)
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
                    "expiresAt" to Timestamp.from(expiresAt),
                ),
            ) == 1
        }

    /**
     * Runs [insert] with the reservation's detail deadline, in one transaction, iff [executionId] in [workspaceId] was
     * captured (by window [sessionId] when given) and [clock] is before that deadline both when read after the
     * reservation row is locked AND when read again after [insert] returns. False otherwise, with nothing written: a
     * deadline that passed while the insert waited rolls the transaction back.
     */
    private fun beforeDeadline(
        executionId: UUID,
        workspaceId: UUID,
        sessionId: UUID?,
        insert: (Instant) -> Boolean,
    ): Boolean =
        checkNotNull(transactions) { "a purge-only capture store does not write" }.execute { status ->
            val expiresAt =
                jdbc
                    .query(
                        """
                        SELECT capture_expires_at FROM published_mutation_reservations
                         WHERE execution_id = :executionId AND workspace_id = :workspaceId
                           AND capture_session_id IS NOT NULL
                           AND (CAST(:sessionId AS uuid) IS NULL OR capture_session_id = CAST(:sessionId AS uuid))
                           FOR KEY SHARE
                        """.trimIndent(),
                        mapOf("executionId" to executionId, "workspaceId" to workspaceId, "sessionId" to sessionId?.toString()),
                    ) { rs, _ -> rs.getTimestamp("capture_expires_at").toInstant() }
                    .firstOrNull()
            if (expiresAt == null || !clock.instant().isBefore(expiresAt)) return@execute false
            if (!insert(expiresAt)) return@execute false
            // The insert may have waited on a lock; its deadline is judged again now that it holds every lock it takes.
            if (clock.instant().isBefore(expiresAt)) return@execute true
            status.setRollbackOnly()
            false
        } == true

    /** The unexpired captured values of [executionId] in [workspaceId] at [now], in recording order. */
    fun values(
        executionId: UUID,
        workspaceId: UUID,
        now: Instant,
    ): List<StoredCaptureValue> =
        jdbc.query(
            """
            SELECT node_id, output_key, value_text, value_bytes, omitted_reason, recorded_at, expires_at
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

    companion object {
        /** The store the retention step purges with — on the retention tick's statement bound; it never writes. */
        fun purgeOnly(jdbc: NamedParameterJdbcTemplate): ExecutionCaptureStore = ExecutionCaptureStore(jdbc, transactions = null)

        private const val STATE_CAPTURED = "CAPTURED"
        private const val STATE_OMITTED = "OMITTED"

        private val VALUE_MAPPER =
            RowMapper { rs: ResultSet, _: Int ->
                StoredCaptureValue(
                    nodeId = rs.getString("node_id"),
                    outputKey = rs.getString("output_key"),
                    valueJson = rs.getString("value_text"),
                    valueBytes = rs.getObject("value_bytes") as Long?,
                    omittedReason = rs.getString("omitted_reason"),
                    recordedAt = rs.getTimestamp("recorded_at").toInstant(),
                    expiresAt = rs.getTimestamp("expires_at").toInstant(),
                )
            }

        private val RUN_MAPPER =
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
