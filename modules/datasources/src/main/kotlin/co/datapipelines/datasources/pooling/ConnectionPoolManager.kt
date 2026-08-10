package co.datapipelines.datasources.pooling

import co.datapipelines.datasources.Datasource
import co.datapipelines.datasources.DialectAdapters
import com.zaxxer.hikari.HikariDataSource
import java.sql.Connection
import java.util.concurrent.ConcurrentHashMap

/**
 * One pooled connection source for a single **user** datasource. Distinct from the metadata-DB
 * pool (module-structure §3.1 rule 4).
 */
interface ConnectionPool : AutoCloseable {
    /** The datasource name this pool serves. */
    val name: String

    /** Leases a connection, blocking up to `hikari.connectionTimeout` (§5.3). Caller closes it. */
    fun leaseConnection(): Connection
}

/** A [ConnectionPool] backed by one [HikariDataSource]. */
class HikariConnectionPool(
    override val name: String,
    private val dataSource: HikariDataSource,
) : ConnectionPool {
    /** Whether the underlying pool has been shut down — the observable half of eviction (§5.2). */
    val isClosed: Boolean get() = dataSource.isClosed

    override fun leaseConnection(): Connection = dataSource.connection

    override fun close() = dataSource.close()
}

/**
 * Owns the per-datasource HikariCP pools for **user** datasources (datasources.md §5.2).
 *
 * ## Concurrency (§5.2)
 *
 * `poolFor` is called from many executor coroutines at once, so lazy initialization must be
 * atomic: pools live in a [ConcurrentHashMap] keyed by datasource name and are created with
 * [ConcurrentHashMap.computeIfAbsent], which runs the mapping function **at most once per key**
 * even under a concurrent first-lease burst — so exactly one [HikariDataSource] is constructed
 * per datasource ([ConnectionPoolManagerTest] proves this with an N-coroutine race). The
 * mapping function does no blocking I/O beyond `HikariDataSource` construction; Hikari fills the
 * pool asynchronously and `initializationFailTimeout` is left at its default, so an unreachable
 * DB surfaces as a lease failure rather than a map-wide stall.
 *
 * ## Replacement (§5.2)
 *
 * On update/delete the evicted pool is `remove()`-d from the map and only *then* `close()`-d,
 * never closed while still reachable from the map — in-flight leases drain against the old
 * instance while new leases go to the new one.
 *
 * [poolFactory] is injectable so tests can substitute a counting or fake pool; production uses
 * the default, which builds a real Hikari pool through the dialect adapter.
 */
class ConnectionPoolManager(
    private val poolFactory: (Datasource) -> ConnectionPool = ::buildHikariPool,
) : AutoCloseable {
    private val pools = ConcurrentHashMap<String, ConnectionPool>()

    /** The pool for [datasource], created atomically on first call and cached thereafter. */
    fun poolFor(datasource: Datasource): ConnectionPool = pools.computeIfAbsent(datasource.name) { poolFactory(datasource) }

    /**
     * Drains and replaces the pool for a datasource whose connection config changed (PUT).
     * Returns the freshly built pool. The old pool is removed before it is closed (§5.2).
     */
    fun rebuild(datasource: Datasource): ConnectionPool {
        evict(datasource.name)
        return poolFor(datasource)
    }

    /**
     * Drains and drops the pool for [name] (update or soft delete). No-op when no pool exists.
     *
     * `remove()` **then** `close()` (§5.2): the pool is unreachable from the map before it is
     * closed, so in-flight leases drain against the old instance while new leases miss and build
     * a fresh one — closing a pool still reachable from the map would fail live callers.
     *
     * @return true when a pool existed and was closed — the caller uses this to decide whether a
     *   `datasource.pool_rebuild` audit event is warranted (§7.4 audits decryption, and evicting
     *   nothing decrypted nothing).
     */
    fun evict(name: String): Boolean {
        val evicted = pools.remove(name) ?: return false
        evicted.close()
        return true
    }

    /** Whether a live pool currently exists for [name] (test/observability aid). */
    fun hasPool(name: String): Boolean = pools.containsKey(name)

    /** Closes every pool — application shutdown. */
    override fun close() {
        pools.keys.toList().forEach(::evict)
    }

    companion object {
        /** The production pool factory: a real Hikari pool built through the dialect adapter. */
        fun buildHikariPool(datasource: Datasource): ConnectionPool {
            val config = DialectAdapters.forDialect(datasource.dialect).buildHikariConfig(datasource)
            return HikariConnectionPool(datasource.name, HikariDataSource(config))
        }
    }
}
