package co.datapipelines.lake.settle

import co.datapipelines.lake.LakeNames
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.UUID

/** Identity needed to take a table's lease and repair its projection. */
data class DeliverySettlementTarget(
    val id: UUID,
    val namespace: String,
    val table: String,
    val createdBy: UUID,
) {
    val leaseName: String get() = LakeNames.tableLeaseName(namespace, table)
}

/** Each observation is a bounded autocommit statement, with its own PostgreSQL now() and snapshot. */
class DeliverySettlementRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) {
    init {
        require(jdbc.jdbcTemplate.queryTimeout > 0) { "settlement JDBC must have a positive statement timeout" }
    }

    fun target(tableId: UUID): DeliverySettlementTarget? {
        requireAutocommit()
        return jdbc
            .query(
                "SELECT t.id, n.name AS namespace, t.name, t.created_by FROM lake_tables_managed t " +
                    "JOIN lake_namespaces n ON n.id = t.namespace_id WHERE t.id = :id",
                mapOf("id" to tableId),
            ) { rs, _ ->
                DeliverySettlementTarget(
                    rs.getObject("id", UUID::class.java),
                    rs.getString("namespace"),
                    rs.getString("name"),
                    rs.getObject("created_by", UUID::class.java),
                )
            }.singleOrNull()
    }

    /** A different operation's lease is no evidence that this attempt is still running. Takeover decides eligibility. */
    fun attemptLeaseLive(
        target: DeliverySettlementTarget,
        operationId: UUID,
    ): Boolean {
        requireAutocommit()
        return jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM lake_leases WHERE kind = 'table' AND name = :name AND operation_id = :op " +
                "AND expires_at > now() AND deadline_at > now())",
            mapOf("name" to target.leaseName, "op" to operationId),
            Boolean::class.java,
        ) == true
    }

    /** Retention may already have removed an ended execution; a live RUNNING row alone keeps RECEIVED open. */
    fun executionEnded(executionId: UUID): Boolean {
        requireAutocommit()
        return jdbc.queryForObject(
            "SELECT NOT EXISTS (SELECT 1 FROM pipeline_executions WHERE execution_id = :id AND status = 'RUNNING')",
            mapOf("id" to executionId),
            Boolean::class.java,
        ) == true
    }

    private fun requireAutocommit() {
        check(!TransactionSynchronizationManager.isActualTransactionActive()) { "Settlement observations need fresh autocommit snapshots." }
    }
}
