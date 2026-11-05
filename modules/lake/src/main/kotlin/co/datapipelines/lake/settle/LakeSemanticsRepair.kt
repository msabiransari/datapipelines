package co.datapipelines.lake.settle

import co.datapipelines.lake.LakeCatalogs
import co.datapipelines.lake.LakeLeases
import co.datapipelines.lake.LeaseHandle
import co.datapipelines.lake.LeaseKind
import co.datapipelines.lake.LeaseOutcome
import co.datapipelines.lake.definitions.LakeApplyRepository
import co.datapipelines.lake.definitions.LakeDefinitionBodies
import co.datapipelines.lake.definitions.LakeDefinitionRepository
import co.datapipelines.lake.definitions.LakeSemanticsColumn
import co.datapipelines.lake.definitions.LakeTableShape
import co.datapipelines.lake.definitions.LakeTableStatus
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Repairs definition-derived semantics only under a current table lease and only when its version changed. */
@Suppress("LongParameterList")
class LakeSemanticsRepair(
    private val jdbc: NamedParameterJdbcTemplate,
    private val tx: TransactionTemplate,
    private val observations: DeliverySettlementRepository,
    private val definitions: LakeDefinitionRepository,
    private val leases: LakeLeases,
    private val catalogs: LakeCatalogs,
) {
    @Suppress("ReturnCount") // Each missing or unchanged durable prerequisite exits before any projection write.
    fun repair(
        tableId: UUID,
        deadline: Instant,
    ) {
        val managed = definitions.findTable(tableId) ?: return
        if (managed.status == LakeTableStatus.PURGED || managed.appliedVersionHere == null) return
        val target = observations.target(tableId) ?: return
        val operation = UUID.randomUUID()
        val outcome = leases.acquire(LeaseKind.TABLE, target.leaseName, operation, TTL, deadline, "settle_semantics")
        if (outcome !is LeaseOutcome.Acquired) return
        try {
            catalogs.open(outcome.lease).use { session ->
                val version = LakeTableShape.definitionVersionOf(session.loadTable(target.namespace, target.table)) ?: return
                val previous =
                    jdbc.queryForList(
                        "SELECT DISTINCT applied_version FROM lake_column_semantics WHERE table_id = :id",
                        mapOf("id" to tableId),
                        Int::class.java,
                    )
                if (previous == listOf(version)) return
                val body = definitions.findVersion(tableId, version)?.let { LakeDefinitionBodies.read(it.bodyJson) } ?: return
                tx.executeWithoutResult {
                    if (owns(outcome.lease)) LakeApplyRepository(jdbc).rebuildSemantics(tableId, version, LakeSemanticsColumn.of(body))
                }
            }
        } finally {
            leases.release(operation)
        }
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
