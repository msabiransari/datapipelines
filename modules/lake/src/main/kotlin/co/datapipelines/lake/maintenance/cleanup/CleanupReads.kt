package co.datapipelines.lake.maintenance.cleanup

import co.datapipelines.lake.SnapshotRevision
import co.datapipelines.lake.maintenance.MaintenanceTable
import co.datapipelines.lake.maintenance.remove.RunStates
import org.apache.iceberg.TableMetadata
import org.apache.iceberg.TableMetadataParser
import org.apache.iceberg.io.FileIO
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** A purged table's rows as the purge deletion checks them (D4), read in ONE statement with the catalog's `now()`. */
data class PurgeState(
    val status: String,
    val purgeDeletedAt: Instant?,
    /** The final revision's `protected_until`: null when the table has no dropped revision of its own or it is not finalised. */
    val finalProtectedUntil: Instant?,
    val catalogNow: Instant,
) {
    /** D4: `purged`, not yet finished, the final revision finalised and passed, and the grace passed since. */
    fun ripe(grace: Duration): Boolean {
        val protectedUntil = finalProtectedUntil ?: return false
        return status == MaintenanceTable.STATUS_PURGED &&
            purgeDeletedAt == null &&
            protectedUntil.isBefore(catalogNow) &&
            protectedUntil.plus(grace).isBefore(catalogNow)
    }
}

/**
 * The metadata-database rows and Iceberg metadata the two parts read (brief 962 D2, D4). Every statement is parameterised
 * and its own autocommit statement on the lake's bounded template (never inside a transaction: a transaction would freeze
 * `now()`). Metadata files are read through the catalog's delete-refusing metadata FileIO.
 */
class CleanupReads(
    private val jdbc: JdbcTemplate,
    private val metadataIo: () -> FileIO,
    private val catalogName: String,
) {
    /**
     * The table's current metadata, read from the pointer `iceberg_tables` holds now (D2 step 1: under the caller's lease
     * this is the fresh load), or null when the catalog has no pointer for it.
     */
    fun metadata(table: MaintenanceTable): TableMetadata? {
        requireNoTransaction()
        val location =
            jdbc
                .queryForList(
                    "SELECT metadata_location FROM iceberg_tables WHERE catalog_name = ? AND table_namespace = ? AND table_name = ?" +
                        " AND metadata_location IS NOT NULL",
                    String::class.java,
                    catalogName,
                    table.namespace,
                    table.name,
                ).singleOrNull() ?: return null
        return read(location)
    }

    /** `lake_tables_managed.created_at`: a keep period that ended before it is never asked for (960 D1). */
    fun createdAt(tableId: UUID): Instant =
        requireNotNull(
            jdbc.queryForObject("SELECT created_at FROM lake_tables_managed WHERE id = ?", Timestamp::class.java, tableId),
        ) { "no managed table $tableId" }.toInstant()

    /**
     * The table's `lake_table_revisions` rows that [co.datapipelines.lake.SnapshotExpiryFloor.compute] can count as
     * protected at [now]: `protected_until` null or after [now], or the location in [pinned]. A row outside that set
     * changes neither the floor nor the protected set, so it is not read. Each row's snapshot time is its metadata's
     * CURRENT snapshot's commit time; a revision with no current snapshot holds no snapshot back and is left out. A
     * metadata file that cannot be read throws: the floor is unknown, so nothing is expired (the silence rule).
     */
    fun protectedRevisions(
        table: MaintenanceTable,
        now: Instant,
        pinned: Set<String>,
    ): List<SnapshotRevision> {
        requireNoTransaction()
        val rows =
            jdbc.query(
                "SELECT metadata_location, protected_until FROM lake_table_revisions WHERE namespace = ? AND table_name = ?" +
                    " AND (protected_until IS NULL OR protected_until > ? OR metadata_location = ANY (?)) ORDER BY seq",
                { rs, _ -> rs.getString("metadata_location") to rs.getTimestamp("protected_until")?.toInstant() },
                table.namespace,
                table.name,
                Timestamp.from(now),
                pinned.toTypedArray(),
            )
        return rows.mapNotNull { (location, protectedUntil) ->
            read(location).currentSnapshot()?.let { SnapshotRevision(location, protectedUntil, Instant.ofEpochMilli(it.timestampMillis())) }
        }
    }

    /**
     * When the table's last finished cleanup ended: the newest `finished` run whose `cleanup` entry was attempted and
     * answered `done` or `nothing_to_do` (959 D5, the planner's `last_cleanup_at`, read the same way for one table).
     */
    fun lastFinishedCleanup(tableId: UUID): Instant? =
        jdbc
            .query(
                "SELECT r.ended_at FROM lake_maintenance_runs r WHERE r.table_id = ? AND r.state = 'finished' AND EXISTS (" +
                    "SELECT 1 FROM jsonb_array_elements(r.parts_json) e WHERE e->>'name' = ? AND e->'attempted' = 'true'::jsonb" +
                    " AND e->>'outcome' IN ('done', 'nothing_to_do')) ORDER BY r.ended_at DESC, r.id DESC LIMIT 1",
                { rs, _ -> rs.getTimestamp("ended_at").toInstant() },
                tableId,
                CleanupPart.NAME,
            ).singleOrNull()

    /**
     * D4's rows for [tableId] with the catalog's `now()`, or null when there is no managed table by that id. The final
     * revision is the planner's (959): the newest DROPPED revision whose location is this table's own.
     */
    fun purgeState(tableId: UUID): PurgeState? =
        jdbc
            .query(
                "SELECT t.status, t.purge_deleted_at, now() AS catalog_now, (SELECT v.protected_until FROM lake_table_revisions v" +
                    " WHERE v.namespace = n.name AND v.table_name = t.name AND v.dropped_at IS NOT NULL" +
                    " AND strpos(v.metadata_location, '/' || t.id::text || '/') > 0 ORDER BY v.seq DESC LIMIT 1) AS final_protected_until" +
                    " FROM lake_tables_managed t JOIN lake_namespaces n ON n.id = t.namespace_id WHERE t.id = ?",
                { rs, _ ->
                    PurgeState(
                        status = rs.getString("status"),
                        purgeDeletedAt = rs.getTimestamp("purge_deleted_at")?.toInstant(),
                        finalProtectedUntil = rs.getTimestamp("final_protected_until")?.toInstant(),
                        catalogNow = rs.getTimestamp("catalog_now").toInstant(),
                    )
                },
                tableId,
            ).singleOrNull()

    /**
     * Stamps `purge_deleted_at` (record §11a "When a purge deletion is finished"), once: only a `purged` row not stamped yet
     * changes. Answers whether this call stamped it.
     */
    fun stampPurgeDeleted(tableId: UUID): Boolean =
        jdbc.update(
            "UPDATE lake_tables_managed SET purge_deleted_at = now() WHERE id = ? AND status = ? AND purge_deleted_at IS NULL",
            tableId,
            MaintenanceTable.STATUS_PURGED,
        ) == 1

    private fun read(location: String): TableMetadata = TableMetadataParser.read(metadataIo(), location)

    private fun requireNoTransaction() =
        check(!TransactionSynchronizationManager.isActualTransactionActive()) {
            "the cleanup's catalog reads run on their own autocommit statements, never inside a transaction"
        }
}

/**
 * The production [RunStates] (brief 962 D5) over `lake_maintenance_runs`: `finished`, `failed` and `abandoned` are
 * terminal, `running` is live, and no row is unknown. Only "terminal" lets the remover delete another run's marker.
 */
class MaintenanceRunStates(
    private val jdbc: JdbcTemplate,
) : RunStates {
    override fun isTerminal(runId: UUID): Boolean? =
        when (
            jdbc.queryForList("SELECT state FROM lake_maintenance_runs WHERE id = ?", String::class.java, runId).singleOrNull()
        ) {
            null -> null
            RUNNING -> false
            in TERMINAL -> true
            // A state V60's check does not admit: unclear, so the marker stays (the silence rule).
            else -> null
        }

    private companion object {
        const val RUNNING = "running"
        val TERMINAL = setOf("finished", "failed", "abandoned")
    }
}
