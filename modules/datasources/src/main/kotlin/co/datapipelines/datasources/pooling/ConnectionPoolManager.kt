package co.datapipelines.datasources.pooling

import co.datapipelines.datasources.Datasource
import co.datapipelines.datasources.DialectAdapters
import com.zaxxer.hikari.HikariDataSource
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * One pooled connection source for a single **user** datasource. Distinct from the metadata-DB
 * pool (module-structure §3.1 rule 4).
 */
interface ConnectionPool : AutoCloseable {
    /** The datasource name this pool serves. */
    val name: String

    /** Leases a connection, blocking up to `hikari.connectionTimeout` (§5.3). Caller closes it. */
    fun leaseConnection(): Connection

    /**
     * Retirement, step 2 (§5.2): stop handing out connections and close the IDLE ones now,
     * leaving the in-use ones to close when their caller returns them — never mid-statement.
     *
     * The default is a no-op, and that is not a hole: a pool that cannot soft-evict also
     * reports [activeConnections] `= 0`, so [ConnectionPoolManager.reapRetiring] closes it on
     * its very next tick — exactly the close-at-once behaviour that preceded 094. Only a pool
     * that can answer BOTH questions gets the gentler lifecycle.
     */
    fun softEvict() {}

    /**
     * Connections leased out and not yet returned — the reaper's release signal (§5.2).
     *
     * `0` for a pool with no such notion, which makes it immediately reapable. See [softEvict].
     */
    val activeConnections: Int get() = 0
}

/** A [ConnectionPool] backed by one [HikariDataSource]. */
class HikariConnectionPool(
    override val name: String,
    private val dataSource: HikariDataSource,
) : ConnectionPool {
    /** Whether the underlying pool has been shut down — the observable half of retirement (§5.2). */
    val isClosed: Boolean get() = dataSource.isClosed

    override fun leaseConnection(): Connection = dataSource.connection

    /**
     * `minimumIdle = 0` **then** `softEvictConnections()`, in that order.
     *
     * `softEvictConnections` marks every entry evicted and closes the ones the bag can reserve —
     * i.e. the idle ones — while an in-use entry is closed when its borrower returns it
     * (verified against the pinned HikariCP 6.3.3 `HikariPool.softEvictConnection`, whose
     * `connectionBag.reserve` succeeds only for `STATE_NOT_IN_USE`). Without the `minimumIdle`
     * write first, the pool's own house-keeper would top it back up every 30 s for the whole
     * retirement window — opening fresh connections to a database whose datasource was just
     * deleted. `minimumIdle` is one of the few `HikariConfig` setters with no seal check
     * precisely because it is `HikariConfigMXBean`'s runtime knob, and the house-keeper
     * re-reads it (and `fillPool`) on every tick.
     */
    override fun softEvict() {
        if (dataSource.isClosed) return
        dataSource.minimumIdle = 0
        dataSource.hikariPoolMXBean.softEvictConnections()
    }

    /** Hikari's own `STATE_IN_USE` count; `0` once the pool is shut down. */
    override val activeConnections: Int
        get() = if (dataSource.isClosed) 0 else dataSource.hikariPoolMXBean.activeConnections

    override fun close() = dataSource.close()
}

/**
 * Observability for the retirement lifecycle (observability.md §4.1) — an inverted port for the
 * same reason [co.datapipelines.datasources.PoolInvalidationPublisher] is one: `datasources` is a
 * plain library and Micrometer is wired in the assembling layer (module-structure §4.2).
 *
 * [NONE] is the library default. Tests use a RECORDING implementation and assert the counts —
 * never a strict mock, which would make the missing call the passing state.
 */
interface PoolLifecycleMetrics {
    /** `datapipelines.datasource.pool.retired{datasource_name}` — a pool left the live map. */
    fun poolRetired(datasourceName: String)

    /**
     * `datapipelines.datasource.pool.hard_closed{datasource_name}` — the ceiling fired on a
     * retiring pool that still had connections out.
     */
    fun poolHardClosed(
        datasourceName: String,
        activeConnections: Int,
    )

    companion object {
        val NONE =
            object : PoolLifecycleMetrics {
                override fun poolRetired(datasourceName: String) = Unit

                override fun poolHardClosed(
                    datasourceName: String,
                    activeConnections: Int,
                ) = Unit
            }
    }
}

/** What one [ConnectionPoolManager.reapRetiring] tick did — the reaper's own return value. */
data class ReapOutcome(
    /** Pools closed because they had drained (`activeConnections == 0`). */
    val drained: Int,
    /** Pools closed at the ceiling with connections still out — each logged one WARN. */
    val hardClosed: Int,
) {
    /** Total pools closed this tick. */
    val closed: Int get() = drained + hardClosed

    companion object {
        val NOTHING = ReapOutcome(drained = 0, hardClosed = 0)
    }
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
 * ## Retirement, not eviction (§5.2, 094)
 *
 * Until 094 a save or delete `remove()`-d the pool and `close()`-d it in the same breath, and
 * `HikariDataSource.close()` **aborts** the connections still in use once its shutdown grace
 * elapses — so an execution that happened to be mid-statement against that datasource lost its
 * connection and failed. Retirement splits the two halves that were being conflated:
 *
 * 1. [retire] takes the pool out of the map (new leases miss and build a fresh pool from the new
 *    row, exactly as before) and asks it to [ConnectionPool.softEvict] — idle connections close
 *    immediately, in-use ones close when their statement finishes and the caller returns them.
 * 2. [reapRetiring], driven by the assembling layer's scheduled tick, `close()`-s each retired
 *    pool once it has drained — or at its **ceiling**, whichever comes first. The ceiling exists
 *    because a genuinely hung statement must not pin a deleted datasource's pool forever; it
 *    logs one WARN naming the datasource and the connections it took down.
 *
 * The ordering rule the old code stated still holds and is now stronger: a pool is never
 * `close()`-d while it is still reachable from the map.
 *
 * [poolFactory] is injectable so tests can substitute a counting or fake pool; production uses
 * the default, which builds a real Hikari pool through the dialect adapter.
 */
class ConnectionPoolManager(
    private val poolFactory: (Datasource) -> ConnectionPool = ::buildHikariPool,
    /** How long a retired pool may keep connections out before [reapRetiring] closes it anyway. */
    private val retireCeiling: Duration = DEFAULT_RETIRE_CEILING,
    /** Injectable so the reaper's state machine can be tested without sleeping. */
    private val clock: () -> Instant = Instant::now,
    /**
     * LAST on purpose, and not a function type: Kotlin's trailing-lambda form binds to the last
     * parameter, so with a `() -> Instant` there the long-standing
     * `ConnectionPoolManager { ds -> … }` call shape would silently start supplying a CLOCK
     * instead of a pool factory. A non-SAM interface here makes that a compile error instead.
     */
    private val metrics: PoolLifecycleMetrics = PoolLifecycleMetrics.NONE,
) : AutoCloseable {
    private val pools = ConcurrentHashMap<String, ConnectionPool>()

    /**
     * Pools that have left [pools] and are draining. A QUEUE rather than a map keyed by name:
     * a datasource saved twice in quick succession, with a lease in between, legitimately has
     * two pools retiring at once, and a name-keyed map would drop the first one un-closed.
     */
    private val retiring = ConcurrentLinkedQueue<RetiringPool>()

    /** The pool for [datasource], created atomically on first call and cached thereafter. */
    fun poolFor(datasource: Datasource): ConnectionPool = pools.computeIfAbsent(datasource.name) { poolFactory(datasource) }

    /**
     * Retires the pool for a datasource whose connection config changed and returns the freshly
     * built one. Only for callers that want an EAGER rebuild; the save path leaves the rebuild
     * to the next lease (§5.4 — a save must not require a reachable database).
     */
    fun rebuild(datasource: Datasource): ConnectionPool {
        retire(datasource.name)
        return poolFor(datasource)
    }

    /**
     * Retirement step 1 (§5.2): remove the pool for [name] from the map, then soft-evict it.
     * No-op when no live pool exists.
     *
     * `remove()` **before** `softEvict()`: the pool is unreachable from the map before anything
     * is done to it, so a caller that already holds it drains against the old instance while
     * new leases miss and build a fresh one. Nothing is closed here — [reapRetiring] does that
     * once the pool has no connections out, or at the ceiling.
     *
     * @return true when a live pool existed and was retired — the caller uses this to decide
     *   whether a `datasource.pool_rebuild` audit event is warranted (§7.4 audits decryption,
     *   and retiring nothing decrypted nothing).
     */
    fun retire(name: String): Boolean {
        val retired = pools.remove(name) ?: return false
        retiring += RetiringPool(retired, deadline = clock().plus(retireCeiling))
        retired.softEvict()
        metrics.poolRetired(name)
        return true
    }

    /**
     * Closes every retiring pool that has drained, plus every one whose ceiling has passed.
     *
     * Driven by the assembling layer's scheduled tick (one per instance). Idempotent and cheap:
     * with nothing retiring it reads one empty queue.
     */
    fun reapRetiring(): ReapOutcome {
        if (retiring.isEmpty()) return ReapOutcome.NOTHING
        val now = clock()
        var drained = 0
        var hardClosed = 0
        val entries = retiring.iterator()
        while (entries.hasNext()) {
            val entry = entries.next()
            val active = entry.pool.activeConnections
            when {
                active == 0 -> {
                    entries.remove()
                    entry.pool.close()
                    drained++
                }

                now >= entry.deadline -> {
                    entries.remove()
                    LOG.warn(
                        "event=datasource.pool_hard_closed datasource={} active_connections={} ceiling_seconds={} " +
                            "message=\"retired pool still had connections out at its ceiling; closing it anyway\"",
                        entry.pool.name,
                        active,
                        retireCeiling.seconds,
                    )
                    entry.pool.close()
                    metrics.poolHardClosed(entry.pool.name, active)
                    hardClosed++
                }
            }
        }
        return ReapOutcome(drained = drained, hardClosed = hardClosed)
    }

    /** Whether a live pool currently exists for [name] (test/observability aid). */
    fun hasPool(name: String): Boolean = pools.containsKey(name)

    /** The names of every live pool — what the §5.7 reconcile compares against the rows. */
    fun livePoolNames(): Set<String> = pools.keys.toSet()

    /** How many pools are retired but not yet closed (test/observability aid). */
    fun retiringCount(): Int = retiring.size

    /**
     * Closes every pool, live and retiring — application shutdown, where the gentle path has no
     * value: `ExecutionDrainLifecycle` has already cancelled the live statements by the time
     * this runs, and the JVM is going away regardless.
     */
    override fun close() {
        pools.keys.toList().forEach { name -> pools.remove(name)?.close() }
        generateSequence { retiring.poll() }.forEach { it.pool.close() }
    }

    /** A pool out of the map, draining, with the instant after which it is closed regardless. */
    private data class RetiringPool(
        val pool: ConnectionPool,
        val deadline: Instant,
    )

    companion object {
        private val LOG = LoggerFactory.getLogger(ConnectionPoolManager::class.java)

        /**
         * The library default ceiling: the executor's own default `node-query-timeout-seconds`
         * (60) plus 30 s of slack. A deployment overrides it from
         * `datapipelines.datasources.retire-ceiling-seconds`, whose default is computed from the
         * CONFIGURED node query timeout — this constant only serves callers that wire no config.
         */
        val DEFAULT_RETIRE_CEILING: Duration = Duration.ofSeconds(90)

        /** The production pool factory: a real Hikari pool built through the dialect adapter. */
        fun buildHikariPool(datasource: Datasource): ConnectionPool {
            val config = DialectAdapters.forDialect(datasource.dialect).buildHikariConfig(datasource)
            return HikariConnectionPool(datasource.name, HikariDataSource(config))
        }
    }
}
