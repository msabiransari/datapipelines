package co.datapipelines.lake.settle

import co.datapipelines.lake.definitions.LakeApplyRepository
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * Repairs the display projection from durable revisions (§8b), using P3's monotone writer verbatim.
 * The datasource bump commits with the repair; local retirement and peer invalidation follow that commit.
 * Every statement uses a dedicated bounded template, never the runtime's shared JDBC template.
 */
class LakeProjectionRepair(
    private val jdbc: NamedParameterJdbcTemplate,
    private val tx: TransactionTemplate,
    private val lakeName: String,
    private val onChanged: (String) -> Unit,
) {
    private val applies = LakeApplyRepository(jdbc)

    init {
        require(jdbc.jdbcTemplate.queryTimeout > 0) { "projection repair JDBC must have a positive statement timeout" }
    }

    /** Returns changed registry rows; a no-op changes neither datasource generation nor invalidation. */
    fun repair(target: DeliverySettlementTarget): Int {
        check(!TransactionSynchronizationManager.isActualTransactionActive()) { "Projection repair must own its commit." }
        val changed = requireNotNull(tx.execute { repairInTransaction(target) })
        if (changed > 0) onChanged(lakeName)
        return changed
    }

    private fun repairInTransaction(target: DeliverySettlementTarget): Int {
        val changed = applies.upsertRegistryRow(lakeName, target.namespace, target.table, target.createdBy)
        if (changed > 0) {
            check(
                jdbc.update(
                    "UPDATE datasources SET updated_at = clock_timestamp() WHERE name = :lake AND NOT is_deleted",
                    mapOf("lake" to lakeName),
                ) == 1,
            )
        }
        return changed
    }
}
