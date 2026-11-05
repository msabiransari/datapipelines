package co.datapipelines.lake.delivery

import co.datapipelines.pipeline.PipelineJson
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.UUID

/**
 * Bounded, parameterized delivery SQL. Transactions belong to [DeliveryLedger]; writes remain conditional
 * on both state and operation id. Attempts and deliveries have no delete path.
 */
@Suppress("TooManyFunctions") // one statement per permanent-ledger operation
class DeliveryLedgerRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) {
    init {
        require(jdbc.jdbcTemplate.queryTimeout > 0) { "delivery JDBC must have a positive statement timeout" }
    }

    fun find(id: UUID): LakeDelivery? = jdbc.query("SELECT * FROM lake_deliveries WHERE id = :id", mapOf("id" to id), ROW).singleOrNull()

    /** The recorded effective identity, including service identities for keys; retention may already have removed the execution. */
    fun executionActor(executionId: UUID): UUID? =
        jdbc
            .query(
                "SELECT executed_by FROM pipeline_executions WHERE execution_id = :id",
                mapOf("id" to executionId),
            ) { rows, _ -> rows.getObject("executed_by", UUID::class.java) }
            .singleOrNull()

    /** Recovery lineage from the same bounded metadata source as the permanent attempts. */
    fun restoreEpoch(): UUID =
        requireNotNull(
            jdbc.queryForObject("SELECT restore_epoch FROM lake_control WHERE id = 1", emptyMap<String, Any>(), UUID::class.java),
        )

    /** Candidates only; the settling actor must separately establish lease ownership before a negative verdict. */
    fun unsettled(tableId: UUID): List<LakeDelivery> =
        jdbc.query(
            "SELECT * FROM lake_deliveries WHERE table_id = :table AND state IN ('RECEIVED', 'PUBLISHING', 'UNKNOWN') ORDER BY id",
            mapOf("table" to tableId),
            ROW,
        )

    internal fun insertIfAbsent(request: ReceiveDelivery) {
        requireTransaction()
        jdbc.update(
            "INSERT INTO lake_deliveries (id, table_id, feed, delivery_id, fingerprint, manifest_json, operation_id, " +
                "execution_id, pipeline_id) VALUES (:id, :table, :feed, :delivery, :fingerprint, CAST(:manifest AS jsonb), " +
                ":op, :execution, :pipeline) ON CONFLICT (table_id, feed, delivery_id) DO NOTHING",
            request.parameters() + ("id" to UUID.randomUUID()),
        )
    }

    internal fun lock(request: ReceiveDelivery): LakeDelivery {
        requireTransaction()
        return jdbc
            .query(
                "SELECT * FROM lake_deliveries WHERE table_id = :table AND feed = :feed AND delivery_id = :delivery FOR UPDATE",
                request.parameters(),
                ROW,
            ).single()
    }

    internal fun restart(
        previous: LakeDelivery,
        request: ReceiveDelivery,
    ): Int {
        requireTransaction()
        return jdbc.update(
            "UPDATE lake_deliveries SET state = 'RECEIVED', operation_id = :op, execution_id = :execution, " +
                "pipeline_id = :pipeline, received_at = now(), failure_phase = NULL, failure_code = NULL, error_json = NULL, " +
                "snapshot_id = NULL, metadata_location_after = NULL, published_at = NULL, replay_mode = NULL, " +
                "manifest_json = CAST(:manifest AS jsonb) WHERE id = :id AND state = :previous_state AND operation_id = :previous_op",
            request.parameters() +
                mapOf("id" to previous.id, "previous_state" to previous.state.name, "previous_op" to previous.operationId),
        )
    }

    internal fun insertAttempt(row: LakeDelivery) {
        requireTransaction()
        // An operation reused for another delivery must violate the unique constraint, not disappear behind DO NOTHING.
        val existing =
            jdbc.queryForList(
                "SELECT delivery_id FROM lake_delivery_attempts WHERE operation_id = :op",
                mapOf("op" to row.operationId),
                UUID::class.java,
            )
        if (existing.isNotEmpty()) {
            check(existing.single() == row.id) { "An operation cannot own two deliveries." }
        } else {
            jdbc.update(
                "INSERT INTO lake_delivery_attempts (id, delivery_id, operation_id) VALUES (:id, :delivery, :op)",
                mapOf("id" to UUID.randomUUID(), "delivery" to row.id, "op" to row.operationId),
            )
        }
    }

    internal fun beginPublishing(row: LakeDelivery): Int =
        jdbc.update(
            "UPDATE lake_deliveries SET state = 'PUBLISHING' WHERE id = :id AND state = 'RECEIVED' AND operation_id = :op",
            row.identity(),
        )

    /** Recipe provenance is attached only while this attempt still owns a publishing row. */
    fun attachRecipe(
        row: LakeDelivery,
        key: String,
        sha256: String,
    ): Int =
        jdbc.update(
            "UPDATE lake_delivery_attempts a SET recipe_key = :key, recipe_sha256 = :sha " +
                "WHERE a.delivery_id = :id AND a.operation_id = :op AND a.recipe_key IS NULL " +
                "AND EXISTS (SELECT 1 FROM lake_deliveries d WHERE d.id = a.delivery_id AND d.operation_id = :op " +
                "AND d.state = 'PUBLISHING')",
            row.identity() + mapOf("key" to key, "sha" to sha256),
        )

    internal fun conclude(
        row: LakeDelivery,
        conclusion: DeliveryConclusion,
    ): Int {
        requireTransaction()
        val target =
            when (conclusion) {
                is DeliveryConclusion.Published -> LakeDeliveryState.PUBLISHED
                is DeliveryConclusion.Failed -> LakeDeliveryState.FAILED
                is DeliveryConclusion.Unknown -> LakeDeliveryState.UNKNOWN
            }
        val failure = conclusion as? DeliveryConclusion.Failed
        val published = conclusion as? DeliveryConclusion.Published
        return jdbc.update(
            "UPDATE lake_deliveries SET state = :next, failure_phase = :phase, failure_code = :code, " +
                "metadata_location_after = :metadata, snapshot_id = :snapshot, " +
                "published_at = CASE WHEN :next = 'PUBLISHED' THEN now() ELSE NULL END " +
                "WHERE id = :id AND state = :state AND operation_id = :op",
            row.identity() +
                mapOf(
                    "state" to row.state.name,
                    "next" to target.name,
                    "phase" to failure?.phase?.wire,
                    "code" to (failure?.code ?: (conclusion as? DeliveryConclusion.Unknown)?.code),
                    "metadata" to published?.metadataLocation,
                    "snapshot" to published?.snapshotId,
                ),
        )
    }

    internal fun finishAttempt(
        row: LakeDelivery,
        outcome: LakeDeliveryAttemptOutcome,
    ): Int {
        requireTransaction()
        return jdbc.update(
            "UPDATE lake_delivery_attempts SET outcome = :outcome, ended_at = now() WHERE delivery_id = :id " +
                "AND operation_id = :op AND (outcome IS NULL OR outcome = 'UNKNOWN')",
            row.identity() + ("outcome" to outcome.name),
        )
    }

    /** All attempts count, including earlier ones; the revision trigger is the only writer of commit evidence. */
    fun committedEvidence(
        deliveryId: UUID,
        operationId: UUID? = null,
    ): DeliveryCommitEvidence? =
        jdbc
            .query(
                "SELECT r.operation_id, r.seq, r.metadata_location FROM lake_table_revisions r " +
                    "JOIN lake_delivery_attempts a ON a.operation_id = r.operation_id " +
                    "JOIN lake_deliveries d ON d.id = a.delivery_id JOIN lake_tables_managed t ON t.id = d.table_id " +
                    "JOIN lake_namespaces n ON n.id = t.namespace_id " +
                    "WHERE d.id = :id AND r.namespace = n.name AND r.table_name = t.name " +
                    "AND (CAST(:op AS uuid) IS NULL OR r.operation_id = CAST(:op AS uuid)) ORDER BY r.seq DESC LIMIT 1",
                mapOf("id" to deliveryId, "op" to operationId),
            ) { rs, _ ->
                DeliveryCommitEvidence(rs.getObject("operation_id", UUID::class.java), rs.getLong("seq"), rs.getString("metadata_location"))
            }.singleOrNull()

    private fun ReceiveDelivery.parameters(): Map<String, Any?> =
        mapOf(
            "table" to tableId,
            "feed" to manifest.key.feed,
            "delivery" to manifest.key.id,
            "fingerprint" to manifest.fingerprint,
            "manifest" to MAPPER.writeValueAsString(manifest.objects),
            "op" to operationId,
            "execution" to executionId,
            "pipeline" to pipelineId,
        )

    private fun LakeDelivery.identity(): Map<String, Any> = mapOf("id" to id, "op" to operationId)

    private fun requireTransaction() {
        check(TransactionSynchronizationManager.isActualTransactionActive()) { "Delivery writes need the ledger transaction." }
    }

    private companion object {
        val MAPPER = PipelineJson.objectMapper()
        val ROW =
            RowMapper { rs, _ ->
                LakeDelivery(
                    id = rs.getObject("id", UUID::class.java),
                    tableId = rs.getObject("table_id", UUID::class.java),
                    key = DeliveryKey(rs.getString("feed"), rs.getString("delivery_id")),
                    fingerprint = rs.getString("fingerprint"),
                    state = LakeDeliveryState.valueOf(rs.getString("state")),
                    operationId = rs.getObject("operation_id", UUID::class.java),
                    executionId = rs.getObject("execution_id", UUID::class.java),
                    pipelineId = rs.getObject("pipeline_id", UUID::class.java),
                    receivedAt = rs.getTimestamp("received_at").toInstant(),
                    metadataLocationAfter = rs.getString("metadata_location_after"),
                )
            }
    }
}
