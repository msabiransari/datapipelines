package co.datapipelines.datasources.pooling

import co.datapipelines.datasources.LakeUnavailableTable
import co.datapipelines.datasources.ManagedLakeTable

/**
 * #818 (dp-lake P5, design record §10, RULING 4): what ONE generation of the managed lake datasource
 * was built from. The pool keeps it from build to close, so the freshness tick compares against the
 * views this generation actually carries and never re-plans to find out.
 *
 * Only the managed datasource's pools carry one ([ConnectionPool.managedGeneration] is null for every
 * other pool). That null is also what keeps the age bound and the freshness tick away from external
 * LAKE datasources and from every other dialect.
 */
data class ManagedLakeGeneration(
    val datasourceName: String,
    /** Every managed table the catalog read returned, the unavailable ones included (they are part of the snapshot). */
    val tables: List<ManagedLakeTable>,
) {
    /** The facts the views were built from, keyed by qualified name (see [ManagedLakeTable.generationKey]). */
    private val snapshot: Map<String, ManagedLakeTable.GenerationKey> = tables.associate { it.qualifiedName to it.generationKey }

    /**
     * The versions this generation reads, for the P8 surface (record §17(a): the reference index's
     * `versionsReading()` scopes protection to them). One key per table of the snapshot, so the map is
     * exactly as complete as [tables]:
     *
     * - a table the planner gave a view (`unavailableReason` null: `active` or `deprecated`) maps to the
     *   catalog metadata location that view reads;
     * - every other table maps to null, its location withheld even when the catalog row has one: `archived`,
     *   `purged`, every table of a disabled lake, no catalog row, no lifecycle row. This generation reads none
     *   of those versions, so P8 must not count them as read.
     *
     * Planned, not proven built: a listed view whose creation failed (the row's `lastError`) is still listed.
     * That over-reports, which for a retention guard is the safe direction; the map never omits a version a
     * view reads. Staleness compares the full snapshot ([staleTableCount]), not this map.
     */
    val locations: Map<String, String?>
        get() = tables.associate { it.qualifiedName to it.metadataLocation.takeIf { _ -> it.unavailableReason == null } }

    /**
     * #818b B — the tables this generation planned no view for, each with the reason its snapshot recorded: what
     * the executor's CONNECT check refuses (`lake.table.unavailable`) for a node leased from THIS generation. Read
     * from the snapshot, never from a fresh catalog read: a node is judged by the views its connection carries, so
     * a catalog outage cannot block a node the live generation can serve, and a table restored after this build
     * stays refused until the generation that serves it.
     */
    val unavailableTables: List<LakeUnavailableTable>
        get() = tables.mapNotNull { table -> table.unavailableReason?.let { LakeUnavailableTable(table.namespace, table.name, it) } }

    /**
     * How many tables differ between this generation and [current], a fresh catalog read: a table
     * whose location or availability changed, one that appeared and one that went away each count
     * once. Zero means the generation is current.
     */
    fun staleTableCount(current: List<ManagedLakeTable>): Int {
        val now = current.associate { it.qualifiedName to it.generationKey }
        return (snapshot.keys + now.keys).count { snapshot[it] != now[it] }
    }

    companion object {
        /**
         * The §15a filesystem lock (owner ruling 2026-11-04): run on the generation's owner connection
         * strictly, AFTER the adapter init (extensions, secret, limits), the prelude and the views.
         *
         * 1. One read of the secret manager. On the pinned 1.5.x it initializes lazily on first touch,
         *    and that first touch reads the local secret directory, so it must happen while the local
         *    filesystem is still enabled. Otherwise the first query of the generation fails.
         * 2. Disable the local filesystem. A lease can then reach no local file, whether by a table
         *    function, `ATTACH` or `COPY`. The managed reader serves every workspace (the grant the
         *    registrar writes), so the instance must not expose the server's disk to any of them.
         *    Spilling to `temp_directory` is refused too, measured on 1.5.6.0, 2026-11-13. The reader's
         *    `memory_limit` is therefore its working-set bound (`reads.memory-limit`).
         * 3. Lock the configuration, so no lease can turn either setting back.
         *
         * Safe on THIS instance only. The managed datasource's URL is an anonymous in-memory engine,
         * so the engine-wide lock reaches no other pool (the reason an external LAKE owner is never
         * locked: [ConnectionPoolManager.buildHikariPool]'s owner KDoc). The session postlude's
         * `SET search_path` is a session setting the lock admits (measured).
         */
        val LOCKDOWN_STATEMENTS: List<String> =
            listOf(
                "SELECT count(*) FROM duckdb_secrets()",
                "SET disabled_filesystems = 'LocalFileSystem'",
                "SET lock_configuration = true",
            )
    }
}
