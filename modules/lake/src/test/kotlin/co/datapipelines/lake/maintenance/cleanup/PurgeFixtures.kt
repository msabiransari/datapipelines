package co.datapipelines.lake.maintenance.cleanup

import co.datapipelines.lake.LakeTestDb
import co.datapipelines.lake.LeaseKind
import co.datapipelines.lake.maintenance.MaintenanceTable
import org.apache.iceberg.catalog.Namespace
import org.apache.iceberg.catalog.TableIdentifier
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

// #962 C11–C13a: the purge sequence up to the purge deletion, as P8's actions will leave it (brief "What is verified":
// no code writes `purged` yet, so the status is set by SQL and the pointer dropped through the catalog).

/** Drops [table] from the catalog under its writer lease, without purging (the revision trigger stamps it dropped). */
internal fun CleanupHarness.drop(table: MaintenanceTable) {
    val lease = LakeTestDb.lease(LeaseKind.TABLE, table.leaseName)
    try {
        m.defs.catalogs.open(lease).use { session ->
            check(session.write { it.dropTable(TableIdentifier.of(Namespace.of(table.namespace), table.name), false) })
        }
    } finally {
        LakeTestDb.leases.release(lease.operationId)
    }
}

/** The status `purged` with its stamp (`chk_lake_tables_managed_purge_stamps`). */
internal fun CleanupHarness.markPurged(table: MaintenanceTable): MaintenanceTable {
    check(jdbc.update("UPDATE lake_tables_managed SET status = 'purged', purged_at = now() WHERE id = ?", table.id) == 1)
    return table.copy(status = MaintenanceTable.STATUS_PURGED)
}

/** Dropped, every revision's `protected_until` at [until] (null = not finalised), status `purged`. */
internal fun CleanupHarness.purged(
    table: MaintenanceTable,
    until: Instant?,
): MaintenanceTable {
    drop(table)
    finalUntil(table, until)
    return markPurged(table)
}

/** The settlement sweep's finalisation of every superseded (the dropped one included) revision, at [until]. */
internal fun CleanupHarness.finalUntil(
    table: MaintenanceTable,
    until: Instant?,
) = jdbc.update(
    "UPDATE lake_table_revisions SET protected_until = ? WHERE namespace = ? AND table_name = ? AND superseded_at IS NOT NULL",
    until?.let { Timestamp.from(it) },
    table.namespace,
    table.name,
)

internal fun CleanupHarness.purgeDeletedAt(table: MaintenanceTable): Instant? =
    jdbc
        .queryForObject("SELECT purge_deleted_at FROM lake_tables_managed WHERE id = ?", Timestamp::class.java, table.id)
        ?.toInstant()

/** A recipe under [table]'s `dp/recipes/` (§8b: never deleted, by either pass, at any age). */
internal fun CleanupHarness.recipe(table: MaintenanceTable): String =
    "${target(table).keyPrefix}dp/recipes/feed_a/${UUID.randomUUID()}/${UUID.randomUUID()}.json".also { put(it) }

/**
 * One real planner pass (959's [co.datapipelines.lake.maintenance.plan.MaintenancePlanner], both parts registered):
 * answers whether it enqueued [table]'s task. The queue is the recording one, emptied first.
 */
internal fun CleanupHarness.planned(table: MaintenanceTable): Boolean {
    p.queue.queued.clear()
    p.queue.enqueues.clear()
    p.pass(p.planner(registered = CleanupHarness.REGISTERED))
    return table.id.toString() in p.queue.enqueues
}
