package co.datapipelines.lake.settle

import co.datapipelines.lake.LakeLeases
import co.datapipelines.lake.LeaseHandle
import co.datapipelines.lake.LeaseKind
import co.datapipelines.lake.LeaseOutcome
import co.datapipelines.lake.definitions.LakeApplyRepository
import co.datapipelines.lake.definitions.LakeDefinitionBodies
import co.datapipelines.lake.definitions.LakeDefinitionRepository
import co.datapipelines.lake.definitions.LakeSemanticsColumn
import co.datapipelines.lake.definitions.LakeTableStatus
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.util.UUID

/**
 * Repairs definition-derived semantics only under a current table lease and only when its version changed. Runs on the
 * sweep's tick (record §8b): the budget is checked before every statement group, the committed definition version comes
 * from [metadata], off the tick's thread, and no catalog session is opened.
 */
@Suppress("LongParameterList")
class LakeSemanticsRepair(
    private val jdbc: NamedParameterJdbcTemplate,
    private val tx: TransactionTemplate,
    private val observations: DeliverySettlementRepository,
    private val definitions: LakeDefinitionRepository,
    private val leases: LakeLeases,
    private val metadata: LakeMetadataReads,
) {
    /** Answers why it stopped with nothing rebuilt, or null when it ran to its end (nothing to do is an end). */
    @Suppress("ReturnCount") // Each missing or unchanged durable prerequisite exits before any projection write.
    fun repair(
        tableId: UUID,
        budget: SettleBudget,
    ): SettleDeferCause? {
        if (budget.expired()) return SettleDeferCause.BUDGET
        val managed = definitions.findTable(tableId) ?: return null
        if (managed.status == LakeTableStatus.PURGED || managed.appliedVersionHere == null) return null
        if (budget.expired()) return SettleDeferCause.BUDGET
        val target = observations.target(tableId) ?: return null
        if (metadata.stuck()) return SettleDeferCause.STUCK_READ
        if (budget.expired()) return SettleDeferCause.BUDGET
        val operation = UUID.randomUUID()
        val outcome = leases.acquire(LeaseKind.TABLE, target.leaseName, operation, TTL, budget.deadline, "settle_semantics")
        if (outcome !is LeaseOutcome.Acquired) return null
        try {
            return repairLeased(target, outcome.lease, budget)
        } finally {
            leases.release(operation)
        }
    }

    @Suppress("ReturnCount") // As above; the caller releases the lease.
    private fun repairLeased(
        target: DeliverySettlementTarget,
        lease: LeaseHandle,
        budget: SettleBudget,
    ): SettleDeferCause? {
        val version =
            when (val read = metadata.definitionVersion(target.id, target.namespace, target.table, budget)) {
                is LakeMetadataRead.Deferred -> return read.cause
                is LakeMetadataRead.Version -> read.version ?: return null
            }
        if (budget.expired()) return SettleDeferCause.BUDGET
        val previous =
            jdbc.queryForList(
                "SELECT DISTINCT applied_version FROM lake_column_semantics WHERE table_id = :id",
                mapOf("id" to target.id),
                Int::class.java,
            )
        if (previous == listOf(version)) return null
        if (budget.expired()) return SettleDeferCause.BUDGET
        val body = definitions.findVersion(target.id, version)?.let { LakeDefinitionBodies.read(it.bodyJson) } ?: return null
        if (budget.expired()) return SettleDeferCause.BUDGET
        tx.executeWithoutResult {
            if (owns(lease)) LakeApplyRepository(jdbc).rebuildSemantics(target.id, version, LakeSemanticsColumn.of(body))
        }
        return null
    }

    // The fence row lock prevents takeover while this projection transaction uses the observed definition.
    private fun owns(lease: LeaseHandle): Boolean =
        jdbc
            .queryForList(
                "SELECT token FROM lake_leases WHERE kind = 'table' AND name = :name AND operation_id = :op AND token = :token " +
                    "AND expires_at > clock_timestamp() AND deadline_at > clock_timestamp() FOR SHARE",
                mapOf("name" to lease.name, "op" to lease.operationId, "token" to lease.token),
                UUID::class.java,
            ).isNotEmpty()

    private companion object {
        val TTL: Duration = Duration.ofMinutes(2)
    }
}
