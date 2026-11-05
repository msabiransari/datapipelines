package co.datapipelines.lake.settle

import co.datapipelines.lake.LakeLeases
import co.datapipelines.lake.LakeProperties
import co.datapipelines.lake.definitions.LakeDefinitionApplier
import co.datapipelines.persistence.FailureShape
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Bounded normal-mode recovery; durable revisions remain protected until an observation sees their supersession. */
class LakeSettlementSweep(
    private val jdbc: NamedParameterJdbcTemplate,
    private val leases: LakeLeases,
    private val settler: DeliverySettler,
    private val applier: LakeDefinitionApplier,
    private val semantics: LakeSemanticsRepair,
) {
    private var after: UUID? = null

    init {
        require(jdbc.jdbcTemplate.queryTimeout > 0) { "settlement sweep JDBC must be bounded" }
    }

    /** One finite page per tick, rotating through managed tables without starving the shared scheduled thread. */
    fun runOnce() {
        if (!normal()) return
        val deadline = leases.catalogNowPlus(SWEEP_BUDGET)
        finalizeProtection()
        val tables =
            jdbc.query(
                "SELECT id FROM lake_tables_managed WHERE (CAST(:after AS uuid) IS NULL OR id > CAST(:after AS uuid)) " +
                    "ORDER BY id LIMIT :limit",
                mapOf("after" to after, "limit" to PAGE_SIZE),
            ) { rows, _ -> rows.getObject("id", UUID::class.java) }
        var visited = 0
        for (table in tables) {
            if (!normal() || !leases.catalogNowPlus(Duration.ZERO).isBefore(deadline)) break
            recover(table, deadline)
            after = table
            visited++
        }
        if (visited == tables.size && tables.size < PAGE_SIZE) after = null
        jdbc.update(
            "UPDATE lake_control SET last_sweep_at = clock_timestamp() WHERE id = 1 AND mode = :mode",
            mapOf("mode" to LakeProperties.MODE_NORMAL),
        )
        log.info("event=lake.settle.sweep tables={} cause=none sql_state=none", visited)
    }

    /** Autocommit visibility, rather than a trigger timestamp, starts the full retention window. */
    fun finalizeProtection(): Int =
        jdbc.update(
            "UPDATE lake_table_revisions r SET protected_until = now() + c.protection_interval FROM lake_control c " +
                "WHERE c.id = 1 AND c.mode = :mode AND r.superseded_at IS NOT NULL AND r.protected_until IS NULL",
            mapOf("mode" to LakeProperties.MODE_NORMAL),
        )

    @Suppress("TooGenericExceptionCaught") // One busy/broken table cannot prevent recovery of the next page.
    private fun recover(table: UUID, deadline: Instant) {
        try {
            settler.settle(table, deadline)
            if (!leases.catalogNowPlus(Duration.ZERO).isBefore(deadline)) return
            applier.verifyApplying(table, deadline)
            if (!leases.catalogNowPlus(Duration.ZERO).isBefore(deadline)) return
            semantics.repair(table, deadline)
        } catch (failure: Exception) {
            log.warn(
                "event=lake.settle.deferred table_id={} cause={} sql_state={}",
                table,
                FailureShape.cause(failure),
                FailureShape.sqlState(failure),
            )
        }
    }

    private fun normal(): Boolean =
        jdbc.queryForObject(
            "SELECT mode = :mode FROM lake_control WHERE id = 1",
            mapOf("mode" to LakeProperties.MODE_NORMAL),
            Boolean::class.java,
        ) == true

    private companion object {
        const val PAGE_SIZE = 100
        val SWEEP_BUDGET: Duration = Duration.ofSeconds(10)
        val log = LoggerFactory.getLogger(LakeSettlementSweep::class.java)
    }
}
