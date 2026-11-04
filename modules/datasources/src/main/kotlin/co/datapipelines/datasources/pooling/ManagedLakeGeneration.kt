package co.datapipelines.datasources.pooling

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

    /** Qualified name → the metadata location its view reads, or null for an omitted table. The P8 surface reads this. */
    val locations: Map<String, String?> get() = tables.associate { it.qualifiedName to it.metadataLocation }

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
         *    Spilling to `temp_directory` is refused too, measured on 1.5.5.1. The reader's
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
