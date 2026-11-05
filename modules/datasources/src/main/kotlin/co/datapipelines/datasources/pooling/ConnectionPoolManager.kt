package co.datapipelines.datasources.pooling

import co.datapipelines.datasources.Datasource
import co.datapipelines.datasources.DatasourceErrorCodes
import co.datapipelines.datasources.DatasourceFileRoots
import co.datapipelines.datasources.DialectAdapter
import co.datapipelines.datasources.DialectAdapters
import co.datapipelines.datasources.JdbcUrlForm
import co.datapipelines.datasources.LakeViewOutcomeRecorder
import co.datapipelines.datasources.LakeViewPlan
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.Dialect
import co.datapipelines.typesystem.jdbc.guardedForAuthorSql
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import com.zaxxer.hikari.util.DriverDataSource
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.SQLException
import java.time.Duration
import java.time.Instant
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

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
     * §5.3 — [connection], leased from THIS pool, must never return to it: close its physical
     * connection now, whatever its borrower is doing with it, and let the pool open a fresh one
     * in its place. The parameter engine's selector runner calls it on a statement it ABANDONED
     * at the evaluate's deadline (parameter-engine record §2.5, P31): `Statement.cancel()` is
     * best effort by the JDBC contract, and a connection whose statement may still be running
     * must not be handed to the next borrower. The borrower's own later `close()` then has
     * nothing to return. Never throws.
     *
     * The default closes the connection, which is a discard exactly for a pool that does NOT
     * pool — one fresh physical connection per lease, as the module's test pools are. A POOLING
     * implementation must override it: for one, closing IS returning. [HikariConnectionPool] —
     * every production pool kind, the in-process H2 and the lake generations included — overrides
     * it with `HikariDataSource.evictConnection`.
     */
    fun discard(connection: Connection) {
        runCatching { connection.close() }
    }

    /**
     * Connections leased out and not yet returned — the reaper's release signal (§5.2).
     *
     * `0` for a pool with no such notion, which makes it immediately reapable. See [softEvict].
     */
    val activeConnections: Int get() = 0

    /**
     * #818 — what this generation of the MANAGED lake datasource was built from, or null for every
     * other pool. Non-null is what subjects a pool to the age bound in [ConnectionPoolManager.poolFor]
     * and to the freshness tick (design record §10, RULING 4).
     */
    val managedGeneration: ManagedLakeGeneration? get() = null

    /**
     * #818d — the identity of the instance generation this pool owns ([LakeInstanceOwner.generation]), carried in the
     * closer's `datasource.pool_close_stuck` line; null for a pool that owns no instance (every non-LAKE pool).
     */
    val generation: String? get() = null
}

/**
 * 152 — the resource a pool generation OWNS beyond its physical connections: for a LAKE pool
 * the retained [LakeInstanceOwner] whose DuckDB instance every physical connection is a
 * duplicate of. The pool drives it through exactly three calls, in this order and from ONE
 * caller: [retire] as shutdown begins (no NEW physical connection may join the generation from
 * here on — one that is mid-creation closes itself instead of being handed to a pool that is
 * closing), then [interruptReaders] (#818d — every reader stops before Hikari's shutdown can make it
 * wait), then [close] after the Hikari pool has fully shut down.
 */
interface PoolInstanceOwner : AutoCloseable {
    /** This generation's identity for log lines; null when the owner has none. */
    val generation: String? get() = null

    /** Shutdown has begun: refuse every further physical connection; existing ones are Hikari's to abort. Never throws. */
    fun retire()

    /**
     * #818d (record §10 item 6) — interrupt first: stop every running reader of this generation NOW, before Hikari's
     * `shutdown()` runs its connection-creation wait. Returns how many physical handles it reached. Never throws and
     * never waits for a reader to stop. The default reaches nothing (an owner with no handles of its own).
     */
    fun interruptReaders(): Int = 0

    /**
     * Release the generation's resources after Hikari has shut down. Never throws: the caller is
     * a pool's close inside the shared manager's loop over every pool, and one generation's
     * driver fault must not stop the next pool's cleanup — a refusal is reported, not propagated.
     */
    override fun close()
}

/**
 * A [ConnectionPool] backed by one [HikariDataSource].
 *
 * [instanceOwner] is 152's one generic seam; `null` for every other dialect, whose pools are
 * byte-for-byte what they were. **One caller owns the whole shutdown sequence**: the first
 * [close] retires the owner, shuts Hikari down and then releases the owner; any concurrent or
 * later [close] returns at once — the same contract `HikariDataSource.close` has for its own
 * second caller, and the reason the guard sits BEFORE the Hikari close rather than around the
 * owner release (a guard after it let a second, immediately-returning caller release the owner
 * while the first was still draining physical connections). So the instance outlives every
 * lease Hikari could still abort and dies with the generation, never before it (§5.2
 * retirement leaves both alive until the reaper's close).
 *
 * [dialect] arms #816's TEMPORARY author-SQL guard (§5.8): a `DUCKDB` or `LAKE` pool hands every
 * lease out as [guardedForAuthorSql], so every statement text a caller runs on it passes the
 * raw-text gate first — one wrap point for every author path (`SqlRunner`, `SqlProbe`,
 * `ReadOnlyStatementLease`, the executor's nodes), with no call site changed. `null` and every
 * other dialect hand out Hikari's own proxy, byte-for-byte as before. DP's own connection setup
 * (`connectionInitSql`, the lake owner's initializer, the session postlude) runs on the physical
 * connection inside Hikari and never meets the guard.
 */
class HikariConnectionPool(
    override val name: String,
    private val dataSource: HikariDataSource,
    private val instanceOwner: PoolInstanceOwner? = null,
    /** The datasource's dialect; `DUCKDB` and `LAKE` arm the author-SQL guard (class KDoc). */
    private val dialect: Dialect? = null,
    /** #818 — set only for a generation of the managed lake datasource ([ConnectionPool.managedGeneration]). */
    override val managedGeneration: ManagedLakeGeneration? = null,
    /**
     * #204 L2 — armed ONLY for the de-privileged in-process H2 pool ([H2InProcessPool.build]).
     * Invoked, exactly once per failing lease, when the acquisition failure's exception graph
     * carries SQLState `28000` — then the original error is rethrown unchanged. The callback
     * evicts the pool from its manager so the next acquisition rebuilds it through `build`,
     * whose bootstrap rotates the restricted user's password again (repair at the next build —
     * the same act that rotates on every rebuild). Null for every other pool kind: their
     * behavior is byte-identical to before this parameter existed.
     */
    private val onRestrictedAuthFailure: (() -> Unit)? = null,
) : ConnectionPool {
    /** Whether the underlying pool has been shut down — the observable half of retirement (§5.2). */
    val isClosed: Boolean get() = dataSource.isClosed

    /** Won by the ONE caller that runs the whole retire → Hikari close → owner release sequence. */
    private val closing = AtomicBoolean(false)

    /** Whether leases are handed out behind the author-SQL guard (class KDoc). */
    private val guardsAuthorSql: Boolean = dialect in GUARDED_DIALECTS

    /**
     * Guarded lease → the Hikari proxy it decorates. [discard] needs the proxy: Hikari's
     * `evictConnection` recognises only its own proxy class and is a silent no-op for anything
     * else, so an abandoned guarded lease would otherwise stay checked out forever. Keys are
     * weak and compared by identity (the decorator inherits `Any`'s `equals`), so a lease its
     * borrower dropped leaves the map with it; no public route from the guard to the driver
     * exists outside this module.
     */
    private val guardedLeases: MutableMap<Connection, Connection> = Collections.synchronizedMap(WeakHashMap())

    /**
     * HikariCP 6.3.3's `HikariPool.createTimeoutException` (pinned sources): a pool-growth
     * failure surfaces as the pool's `SQLTransientConnectionException` whose SQLState/errorCode
     * are COPIED from the last failed create and whose `nextException` carries the driver's own
     * exception — so a wrong restricted-user password (H2 `28000`) IS observable here, on the
     * caller's thread, after `connectionTimeout`. Both dimensions are walked — `cause` and
     * `nextException` — because drivers nest either way; the seen-set makes the graph walk
     * cycle-safe. Never swallows, never retries: the detection's only effect is the eviction
     * callback; the caller's exception is what it would have been without the guard.
     */
    override fun leaseConnection(): Connection {
        val leased =
            try {
                dataSource.connection
            } catch (e: SQLException) {
                if (onRestrictedAuthFailure != null && carriesAuthFailure(e)) onRestrictedAuthFailure.invoke()
                throw e
            }
        if (!guardsAuthorSql) return leased
        return leased.guardedForAuthorSql().also { guardedLeases[it] = leased }
    }

    /**
     * The pool's own connection behind [lease] — Hikari's proxy for a guarded lease, [lease]
     * itself otherwise. Module-internal on purpose: [discard] and this module's tests (a lake
     * test's physical-identity read) are its only callers.
     */
    internal fun poolConnectionOf(lease: Connection): Connection = guardedLeases[lease] ?: lease

    private fun carriesAuthFailure(failure: Throwable): Boolean {
        val seen = HashSet<Throwable>()
        val queue = ArrayDeque<Throwable>()
        queue.add(failure)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (!seen.add(current)) continue
            if (current is SQLException && current.sqlState == AUTH_FAILURE_SQLSTATE) return true
            current.cause?.let { queue.add(it) }
            (current as? SQLException)?.nextException?.let { queue.add(it) }
        }
        return false
    }

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

    /**
     * `HikariDataSource.evictConnection` — the pinned HikariCP 6.3.3's per-connection discard: on a
     * connection its borrower has not closed "the eviction is immediate" — the entry leaves the bag,
     * its physical connection is closed on the pool's close executor, and the pool refills towards
     * `minimumIdle`; the borrower's later `close()` finds an evicted entry and returns nothing (its
     * rollback failure on the dead connection is swallowed for an evicted entry). A connection that
     * is not this pool's (Hikari checks the proxy class) or a pool already shut down is a no-op —
     * a shut-down pool has aborted its leases already.
     */
    override fun discard(connection: Connection) {
        if (dataSource.isClosed) return
        val pooled = guardedLeases.remove(connection) ?: connection
        runCatching { dataSource.evictConnection(pooled) }
            .onFailure { LOG.warn("event=pool_discard_failed datasource={} error={}", name, it.javaClass.simpleName) }
    }

    /** Hikari's own `STATE_IN_USE` count; `0` once the pool is shut down. */
    override val activeConnections: Int
        get() = if (dataSource.isClosed) 0 else dataSource.hikariPoolMXBean.activeConnections

    /** #818d — the owner's generation, for the closer's stuck-close line; null without an owner. */
    override val generation: String? get() = instanceOwner?.generation

    /**
     * Retire the owner, then **interrupt first** (#818d, record §10 item 6: every physical handle of
     * the generation is put into its own close, which interrupts the running DuckDB query before
     * anything else — [PoolInstanceOwner.interruptReaders]), then Hikari shutdown — it waits
     * (bounded by the DataSource's login timeout, which Hikari itself sets from
     * `connectionTimeout`) for a physical connection mid-creation, then aborts the leases still
     * out once its shutdown grace expires (the existing §5.2 hard-close policy) — then the owner's
     * release, which closes every physical handle the generation still owns (one Hikari abandoned
     * at that ceiling included: a duplicate does not outlive its generation) and the retained
     * connection. The interrupt comes BEFORE Hikari's shutdown because that shutdown's first act
     * is `addConnectionExecutor.awaitTermination(loginTimeout)` (HikariCP 6.3.3, read off the
     * bytecode), and a reader must never wait behind a stuck connection creation. Hikari's later
     * abort of a handle the interrupt already closed is harmless: the driver refuses `abort`,
     * Hikari falls back to `close()`, and a handle mid-close or closed returns at once
     * (`TrackedDuplicate`). No step throws: Hikari's close contains its own failures, and the
     * owner contains the driver's, so this method is safe inside [ConnectionPoolManager.close]'s
     * loop and on the closer's threads. No lock is held across any of it: the losing caller does
     * not wait, it returns.
     */
    override fun close() {
        if (!closing.compareAndSet(false, true)) return
        instanceOwner?.retire()
        instanceOwner?.interruptReaders()
        try {
            dataSource.close()
        } finally {
            instanceOwner?.close()
        }
    }

    private companion object {
        private val LOG = LoggerFactory.getLogger(HikariConnectionPool::class.java)

        /** A wrong-password failure: H2's `JdbcSQLInvalidAuthorizationSpecException` SQLState (#204 L2). */
        private const val AUTH_FAILURE_SQLSTATE = "28000"

        /** The in-process DuckDB dialects whose leases pass the author-SQL guard (§5.8, TEMPORARY). */
        private val GUARDED_DIALECTS = setOf(Dialect.DUCKDB, Dialect.LAKE)
    }
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

    /**
     * #818d — `datapipelines.datasource.pool.close_stuck{datasource_name}`: a close handed to the closer was still
     * running at `PoolCloser.DEADLINE_SECONDS`. Defaulted so an implementation that predates it keeps compiling.
     */
    fun poolCloseStuck(datasourceName: String) = Unit

    /**
     * #818d — `datapipelines.datasource.pool.reaper_pass_slow`: one reaper pass ran longer than its stated bound
     * (`ReaperDelay.PASS_BOUND_SECONDS`). Untagged: the pass is per instance, not per datasource.
     */
    fun reaperPassSlow() = Unit

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
    /**
     * #818c (record §10 RULING 4 amended, item 3) — managed lake generations this pass retired at their age bound
     * before it reaped ([ConnectionPoolManager.retireExpiredGenerations]); at most the live managed generation count.
     */
    val expired: Int = 0,
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
     * #818 RULING 4 item 1 — `datapipelines.lake.reads.max-generation-age-seconds`: [poolFor] retires a
     * MANAGED lake generation ([ConnectionPool.managedGeneration] non-null) older than this before it
     * leases from it, whatever messages did or did not arrive; #818c (item 3, amended 2026-11-05) — and
     * [reapRetiring] retires one on the reaper's next pass with no borrower at all. Null (no lake
     * configured, every test that is not about it) bounds nothing. Pools of every other kind are never
     * aged out.
     */
    private val maxGenerationAge: Duration? = null,
    /**
     * #818d (record §10 RULING 4 amended, item 6) — who runs the closes [reapRetiring] decides on. The pass hands
     * each one here and never waits for it. [PoolCloser.SYNCHRONOUS] (the default: library callers, this module's
     * tests) closes on the caller's thread; the assembling layer injects the `dp-pool-close` closer. NOT last: a
     * `fun interface` would accept the `ConnectionPoolManager { ds -> … }` trailing lambda by SAM conversion (see
     * [metrics]). [close] (application shutdown) never uses it.
     */
    private val closer: PoolCloser = PoolCloser.SYNCHRONOUS,
    /**
     * LAST on purpose, and not a function type: Kotlin's trailing-lambda form binds to the last
     * parameter, so with a `() -> Instant` there the long-standing
     * `ConnectionPoolManager { ds -> … }` call shape would silently start supplying a CLOCK
     * instead of a pool factory. A non-SAM interface here makes that a compile error instead.
     */
    private val metrics: PoolLifecycleMetrics = PoolLifecycleMetrics.NONE,
) : AutoCloseable {
    private val pools = ConcurrentHashMap<String, LivePool>()

    /**
     * Pools that have left [pools] and are draining. A QUEUE rather than a map keyed by name:
     * a datasource saved twice in quick succession, with a lease in between, legitimately has
     * two pools retiring at once, and a name-keyed map would drop the first one un-closed.
     */
    private val retiring = ConcurrentLinkedQueue<RetiringPool>()

    /**
     * The pool for [datasource], created atomically on first call and cached thereafter, except that a
     * managed lake generation older than [maxGenerationAge] is retired first and a new one built
     * (#818, design record §10 RULING 4 item 1).
     *
     * The expiry runs INSIDE the lease path, so it needs no tick, no message and no peer: a clock
     * comparison on this instance. `remove(name, live)` retires exactly the generation that was
     * judged old. When two callers race on an expired generation, one wins the removal and the other
     * finds a fresh entry, or builds one through the same at-most-once `computeIfAbsent`. The new
     * generation's `builtAt` is read BEFORE its factory call, so a slow build is never credited with
     * a younger snapshot than the catalog read it began with.
     */
    fun poolFor(datasource: Datasource): ConnectionPool {
        while (true) {
            val live = pools.computeIfAbsent(datasource.name) { LivePool(builtAt = clock(), pool = poolFactory(datasource)) }
            val age = expiredAge(live) ?: return live.pool
            retireExpired(datasource.name, live, age, TRIGGER_LEASE)
        }
    }

    /**
     * #818c (record §10 RULING 4 amended 2026-11-05, item 3) — the reaper's half of the age bound: retires every
     * live MANAGED lake generation past [maxGenerationAge], with no borrower, through the SAME judgement
     * ([expiredAge]) and the same compare-and-remove `remove(name, live)` the lease path uses. A generation another
     * caller already replaced (a lease that judged it first, the freshness tick, an invalidation) fails the
     * compare and is left alone, so a lease racing this pass can neither double-retire it nor lose the fresh entry
     * it built. [reapRetiring] runs this FIRST, so an over-age generation that has already drained is retired and
     * closed in one pass.
     *
     * Statement-free by construction: a walk of the in-memory map and one clock comparison per pool; the soft
     * evict of a retired generation closes its idle in-process duplicates and sends nothing to any database. So a
     * held lock can never starve it (the reason 818b bounded the freshness tick). Its work per pass is bounded by
     * the live managed generation count — one per managed datasource name.
     *
     * @return how many generations it retired; 0 at once when no age bound is configured.
     */
    fun retireExpiredGenerations(): Int {
        if (maxGenerationAge == null) return 0
        var retired = 0
        pools.forEach { (name, live) ->
            val age = expiredAge(live) ?: return@forEach
            if (retireExpired(name, live, age, TRIGGER_REAPER)) retired++
        }
        return retired
    }

    /** The age of [live] when it is a managed generation past [maxGenerationAge]; null when it may still serve. */
    private fun expiredAge(live: LivePool): Duration? {
        val bound = maxGenerationAge ?: return null
        if (live.pool.managedGeneration == null) return null
        val age = Duration.between(live.builtAt, clock())
        return age.takeIf { it > bound }
    }

    /** Retires [live] only while it is still [name]'s live entry; true when this caller won the removal. */
    private fun retireExpired(
        name: String,
        live: LivePool,
        age: Duration,
        trigger: String,
    ): Boolean {
        if (!pools.remove(name, live)) return false
        enqueueRetired(name, live.pool)
        LOG.info(
            "event=lake.generation_expired datasource={} trigger={} age_seconds={} max_age_seconds={} " +
                "message=\"managed lake generation retired at its age bound; the next lease builds from the catalog\"",
            name,
            trigger,
            age.seconds,
            maxGenerationAge?.seconds,
        )
        return true
    }

    /**
     * #818 — the freshness tick's retirement: retires [name]'s live pool only while it is still
     * [expected], the generation the caller compared. A generation another caller already replaced is
     * left alone, so a tick never retires a pool it did not judge stale.
     */
    fun retireGeneration(
        name: String,
        expected: ConnectionPool,
    ): Boolean {
        val live = pools[name] ?: return false
        if (live.pool !== expected || !pools.remove(name, live)) return false
        enqueueRetired(name, live.pool)
        return true
    }

    /** #818 — every live managed lake generation, by datasource name: what the freshness tick compares. */
    fun liveManagedGenerations(): Map<String, ConnectionPool> =
        pools.entries.filter { it.value.pool.managedGeneration != null }.associate { it.key to it.value.pool }

    /**
     * #818 — the instant [name]'s live pool was built (read before its factory call), or null when no
     * pool is live. The age bound's input, exposed for the bound's proofs and the P8 lake surface.
     */
    fun generationBuiltAt(name: String): Instant? = pools[name]?.builtAt

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
        enqueueRetired(name, retired.pool)
        return true
    }

    /** Retirement step 1's tail, shared by every path that took a pool out of the map. */
    private fun enqueueRetired(
        name: String,
        retired: ConnectionPool,
    ) {
        retiring += RetiringPool(retired, deadline = clock().plus(retireCeiling))
        retired.softEvict()
        metrics.poolRetired(name)
    }

    /**
     * The reaper's pass: first retires every managed lake generation past its age bound
     * ([retireExpiredGenerations], #818c), then hands to [closer] every retiring pool that has drained, plus every
     * one whose ceiling has passed.
     *
     * Driven by the assembling layer's scheduled tick (one per instance). Idempotent and cheap:
     * with no managed generation and nothing retiring it walks the live map and reads one empty queue.
     *
     * #818d (record §10 item 6) — **the pass decides; it never closes.** Its whole work is a clock comparison per
     * live pool, a walk of the retiring queue reading each pool's in-memory active count, a compare-and-remove per
     * retirement (whose soft evict only schedules Hikari's idle closes on Hikari's own executor) and a hand-off per
     * pool to close. No statement, no close, no lock held across I/O — so its duration is a property of this code,
     * not of a database or a driver (`ReaperDelay.PASS_BOUND_SECONDS`, measured). A pool leaves the queue BEFORE it
     * is handed off, so a slow close can never be handed off twice.
     */
    fun reapRetiring(): ReapOutcome {
        val expired = retireExpiredGenerations()
        if (retiring.isEmpty()) return ReapOutcome(drained = 0, hardClosed = 0, expired = expired)
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
                    closer.close(entry.pool)
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
                    closer.close(entry.pool)
                    metrics.poolHardClosed(entry.pool.name, active)
                    hardClosed++
                }
            }
        }
        return ReapOutcome(drained = drained, hardClosed = hardClosed, expired = expired)
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
     *
     * #818d — synchronous on the caller's thread, NOT through [closer]: this is not the reaper's path, and a shutdown
     * that returned before its pools closed would release the context with generations still open. Each close is
     * still interrupt-first ([HikariConnectionPool.close]).
     */
    override fun close() {
        pools.keys.toList().forEach { name -> pools.remove(name)?.pool?.close() }
        generateSequence { retiring.poll() }.forEach { it.pool.close() }
    }

    /** A live pool with the instant it was built: the age bound's input (#818). */
    private class LivePool(
        val builtAt: Instant,
        val pool: ConnectionPool,
    )

    /** A pool out of the map, draining, with the instant after which it is closed regardless. */
    private data class RetiringPool(
        val pool: ConnectionPool,
        val deadline: Instant,
    )

    companion object {
        private val LOG = LoggerFactory.getLogger(ConnectionPoolManager::class.java)

        /** `lake.generation_expired`'s `trigger`: the lease path judged the age ([poolFor]). */
        const val TRIGGER_LEASE = "lease"

        /** `lake.generation_expired`'s `trigger`: the reaper's pass judged it with no borrower ([retireExpiredGenerations]). */
        const val TRIGGER_REAPER = "reaper"

        /**
         * The library default ceiling: the executor's own default `node-query-timeout-seconds`
         * (60) plus 30 s of slack. A deployment overrides it from
         * `datapipelines.datasources.retire-ceiling-seconds`, whose default is computed from the
         * CONFIGURED node query timeout — this constant only serves callers that wire no config.
         */
        val DEFAULT_RETIRE_CEILING: Duration = Duration.ofSeconds(90)

        /**
         * The production pool factory: a real Hikari pool built through the dialect adapter.
         *
         * [additionalConnectionInit] carries statements derived OUTSIDE the adapter — the
         * pre-109 shape of the per-table lake views ([LakeViewStatements.forTables]), kept for
         * non-LAKE use and as the strict-composition reference. They are appended AFTER the
         * adapter's own `connectionInit` statements (extensions → secret → attach → limits →
         * views), inside the same `connectionInitSql` slot, so the save-time test pool build —
         * which passes none — stays byte-identical to the pre-view config (§4.2's "built the
         * same way" invariant covers the adapter's half; views are runtime-only).
         *
         * [lakeViews] is 109 §A's per-table-isolation path, and since 152 (#128) it rides the
         * SHARED-INSTANCE build: a LAKE pool that passes no [additionalConnectionInit] — the
         * production factory's shape, tableless or not — opens ONE retained owner connection
         * ([LakeInstanceOwner]), initializes the instance on it exactly once (the adapter init
         * and the plan's prelude strict, each table's view independently, a failing view recorded
         * and skipped), and hands HikariCP a [LakeInstanceDataSource] whose every physical
         * connection is a `duplicate()` of that owner plus the session-scoped postlude. Routine
         * physical replacement therefore keeps the generation's catalog AND its external-file
         * cache; a tableless lake takes the same path with an empty plan. Mutually exclusive
         * with [additionalConnectionInit]: the two are strict-vs-isolated compositions of the
         * SAME statements, and passing both would create every view twice — the legacy
         * strict composition stays one-instance-per-connection, as its callers expect.
         *
         * [duckdbExtensionDirectory] is the deployment's bundled DuckDB extension directory
         * (089 §D, configuration.md §3.25) and [duckdbMemoryLimit] is its operator default
         * `memory_limit` (153, configuration.md §3.25): both forwarded to
         * [DialectAdapters.forDialect], which only a LAKE build honors — the adapter then SETs
         * `extension_directory` and emits bare `LOAD`s, never an `INSTALL`, and SETs
         * `memory_limit` to the deployment's number when a datasource declares none of its own.
         */
        fun buildHikariPool(
            datasource: Datasource,
            additionalConnectionInit: List<String> = emptyList(),
            duckdbExtensionDirectory: String? = null,
            duckdbMemoryLimit: String? = null,
            lakeViews: LakeViewInit? = null,
            fileRoots: DatasourceFileRoots? = null,
            onRestrictedAuthFailure: (() -> Unit)? = null,
        ): ConnectionPool {
            val adapter = DialectAdapters.forDialect(datasource.dialect, duckdbExtensionDirectory, duckdbMemoryLimit)
            val config = adapter.buildHikariConfig(datasource)
            if (lakeViews != null) {
                require(additionalConnectionInit.isEmpty()) {
                    "lake view isolation and additionalConnectionInit compose the same statements — pass exactly one"
                }
            }
            if (additionalConnectionInit.isNotEmpty()) {
                config.connectionInitSql =
                    listOfNotNull(config.connectionInitSql, additionalConnectionInit.joinToString("; "))
                        .joinToString("; ")
            }
            if (datasource.dialect != Dialect.LAKE) {
                // 186 §A3: an in-process H2 datasource (mem:/file:/bare path) never pools its
                // REGISTERED credential — its connections run as a freshly minted non-admin
                // user, staging §9.5's discipline on the datasource path. Server-form H2
                // (tcp:/ssl:) is a network client like any other and is untouched. The
                // classification runs BEFORE any caller-supplied init SQL can choose a plain
                // pool: an in-process form never has an admin-credentialed path, whatever the
                // caller passed (186 review L1).
                val form = JdbcUrlForm.classify(datasource.dialect, datasource.jdbcUrl)
                // 186b: the runtime backstop to registration's refusal. A stored row can never
                // carry an Unknown form (the validator refuses it), so reaching this line with
                // one means the row predates the rule or a caller bypassed it — and a plain
                // pool would then open the URL with the REGISTERED credential as H2's admin,
                // or (H2's case-sensitive prefixes) create a literal file under the process's
                // working directory. Fail closed here too.
                require(form !is JdbcUrlForm.Form.Unknown) {
                    "datasource '${datasource.name}' carries a URL form this product refuses to pool " +
                        "(unrecognised in-process prefix); re-register it with a supported URL form"
                }
                if (form is JdbcUrlForm.Form.InProcessFile) {
                    // #204 L4: the file-roots rule is a property of the BUILD, not only of
                    // registration. A row saved while its file sat under a declared root can
                    // leave that root afterwards — the file moved, the file replaced by a
                    // symlink aimed outside, the root removed from configuration — and the next
                    // build must refuse, exactly as the update gate's validator would refuse the
                    // same row today. `null` means the caller wired no roots instance (a plain
                    // library caller, the tests' default); the production registry always passes
                    // one, and an EMPTY instance refuses every file-backed build, the same
                    // fail-closed posture as an unconfigured registration. The refusal carries
                    // the SAME catalogued code the update gate throws, so the API answer is
                    // unchanged (ApiExceptionHandler keys on the shared DatapipelinesException
                    // base; ApiErrorCatalog maps the code). Re-raised, not invented.
                    fileRoots?.refusalFor(form.rawPath)?.let { refusal ->
                        throw DatapipelinesException(
                            DatasourceErrorCodes.WORKSPACE_FORBIDDEN,
                            "datasource '${datasource.name}' cannot build a pool: $refusal",
                            mapOf("datasource" to datasource.name),
                        )
                    }
                }
                if (datasource.dialect == Dialect.H2 && form.isInProcess) {
                    return H2InProcessPool.build(datasource, config, onRestrictedAuthFailure)
                }
                return HikariConnectionPool(datasource.name, HikariDataSource(config), dialect = datasource.dialect)
            }
            if (additionalConnectionInit.isNotEmpty()) {
                // The legacy strict composition for a lake — the views ride in the init SQL and
                // each connection is its own instance: a plain pool with no generation owner,
                // exactly as before the classification moved above this line.
                return HikariConnectionPool(datasource.name, HikariDataSource(config), dialect = datasource.dialect)
            }
            val owner = openLakeInstance(config, adapter, datasource, lakeViews)
            val hikari =
                try {
                    HikariDataSource(config)
                } catch (
                    // PoolInitializationException, or validate()'s IllegalArgument/IllegalState:
                    // whichever it is, the owner must not outlive a pool that never existed —
                    // the same "fully built or not at all" rule LakeInstanceOwner.open applies.
                    @Suppress("TooGenericExceptionCaught") e: RuntimeException,
                ) {
                    owner.close()
                    throw e
                }
            return HikariConnectionPool(
                datasource.name,
                hikari,
                owner,
                dialect = datasource.dialect,
                managedGeneration = lakeViews?.managed,
            )
        }

        /**
         * Opens the pool generation's instance owner and rewires [config] from driver-URL
         * pooling to the [LakeInstanceDataSource] (109 §A's seam, 152's ownership). The adapter
         * init statements come back OUT of the `connectionInitSql` slot — re-derived as the LIST
         * from [DialectAdapter.connectionInit], never split back out of the joined string (a
         * credential containing `; ` would survive the join but not a re-split) — and become the
         * initializer's strict half, run ONCE on the owner. `DriverDataSource` is HikariCP's own
         * driver delegate, so the owner connection is opened exactly as the jdbcUrl path would
         * have opened every pooled connection.
         *
         * The captured `jdbcUrl`/`driverClassName` are deliberately LEFT on the config:
         * `HikariConfig.validate()` skips every field check once `dataSource` is set with no
         * `dataSourceClassName` (they are ignored, not mutually exclusive), while the
         * `setDriverClassName(null)` that "clearing" would require loads a class whose name is
         * null — an unconditional `NullPointerException` on HikariCP 6.3.x. Only
         * `connectionInitSql` is nulled, so the pool cannot re-run the joined init on the
         * duplicates (global SETs and `CREATE OR REPLACE VIEW` beside active readers are exactly
         * what the once-per-generation rule removes).
         *
         * #816 (§5.8): the owner is NOT configuration-locked, unlike a `DUCKDB` connection. The
         * engine-wide lock is shared by every pool on the same named or file engine, so a second
         * pool joining one (a rebuild beside a live generation, a second datasource on the same file)
         * would have its own `memory_limit`/`threads`/`temp_directory` refused — measured on the
         * pinned 1.5.5.1 (`LakeSpillIntegrationTest`'s joining pool, both URL kinds). A lake keeps
         * external access by design, so the author-SQL guard on every lease is its control here.
         */
        private fun openLakeInstance(
            config: HikariConfig,
            adapter: DialectAdapter,
            datasource: Datasource,
            lakeViews: LakeViewInit?,
        ): LakeInstanceOwner {
            val delegate =
                DriverDataSource(
                    config.jdbcUrl,
                    config.driverClassName,
                    config.dataSourceProperties,
                    config.username,
                    config.password,
                )
            val initializer =
                LakeInstanceInitializer(
                    adapterInit = adapter.connectionInit(datasource),
                    plan = lakeViews?.plan ?: LakeViewPlan(emptyList(), emptyList(), emptyList()),
                    datasourceName = datasource.name,
                    recorder = lakeViews?.recorder ?: LakeViewOutcomeRecorder.NONE,
                    lockdown = if (lakeViews?.managed != null) ManagedLakeGeneration.LOCKDOWN_STATEMENTS else emptyList(),
                )
            val owner = LakeInstanceOwner.open(delegate, datasource.name, initializer::initialize)
            config.connectionInitSql = null
            config.dataSource = LakeInstanceDataSource(owner, initializer.sessionInit)
            return owner
        }
    }
}
