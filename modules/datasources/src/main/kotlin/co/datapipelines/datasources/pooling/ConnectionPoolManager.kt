package co.datapipelines.datasources.pooling

import co.datapipelines.datasources.Datasource
import co.datapipelines.datasources.DatasourceErrorCodes
import co.datapipelines.datasources.DatasourceFileRoots
import co.datapipelines.datasources.DialectAdapter
import co.datapipelines.datasources.DialectAdapters
import co.datapipelines.datasources.JdbcUrlForm
import co.datapipelines.datasources.LakeViewOutcomeRecorder
import co.datapipelines.datasources.LakeViewPlan
import co.datapipelines.datasources.ManagedLakeErrorCodes
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
import java.util.UUID
import java.util.WeakHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

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

    /** Physical handles, including the retained owner and any close still in flight. */
    val holdsPhysicalHandles: Boolean get() = false

    /**
     * #840 — the Hikari `connectionTimeout` this pool runs with: the bound of its shutdown's first wait (Hikari sets the
     * login timeout from it), so the first term of this pool's own close deadline ([PoolCloser.deadlineFor]). A pool
     * with no Hikari configuration (the test doubles) answers HikariCP's default, [DEFAULT_CONNECTION_TIMEOUT].
     */
    val connectionTimeout: Duration get() = DEFAULT_CONNECTION_TIMEOUT

    companion object {
        /** A fresh `HikariConfig`'s `connectionTimeout` (30 s on the pinned HikariCP), read once, never typed here. */
        val DEFAULT_CONNECTION_TIMEOUT: Duration = Duration.ofMillis(HikariConfig().connectionTimeout)
    }
}

/**
 * 152 — the resource a pool generation OWNS beyond its physical connections: for a LAKE pool
 * the retained [LakeInstanceOwner] whose DuckDB instance every physical connection is a
 * duplicate of. The pool drives it through exactly three calls, in this order and from ONE
 * caller: [retire] as shutdown begins (no NEW physical connection may join the generation from
 * here on — one that is mid-creation closes itself instead of being handed to a pool that is
 * closing), then [interruptReaders] (#818d — independent physical close attempts before Hikari shutdown),
 * then [close] after the Hikari pool has fully shut down.
 */
interface PoolInstanceOwner : AutoCloseable {
    /** This generation's identity for log lines; null when the owner has none. */
    val generation: String? get() = null

    /** Shutdown has begun: refuse every further physical connection; existing ones are Hikari's to abort. Never throws. */
    fun retire()

    /**
     * #818d (record §10 item 6) — interrupt first: attempt every physical close independently, before Hikari's
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

    /** #840 — this pool's own `connectionTimeout`, as its datasource's pool settings set it ([ConnectionPool.connectionTimeout]). */
    override val connectionTimeout: Duration get() = Duration.ofMillis(dataSource.connectionTimeout)

    /** Won by the ONE caller that runs the whole retire → Hikari close → owner release sequence. */
    private val closing = AtomicBoolean(false)
    private val ownerReleased = AtomicBoolean(false)

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

    override val holdsPhysicalHandles: Boolean
        get() = instanceOwner != null && (!ownerReleased.get() || (instanceOwner as? LakeInstanceOwner)?.holdsPhysicalHandles == true)

    /**
     * Retire the owner, then start each physical handle's close independently
     * ([PoolInstanceOwner.interruptReaders]), before Hikari shutdown. A native reader may remain
     * blocked in that close; its reader pins stay live until physical closure is confirmed.
     * Hikari shutdown waits
     * (bounded by the DataSource's login timeout, which Hikari itself sets from
     * `connectionTimeout`) for a physical connection mid-creation, then aborts the leases still
     * out once its shutdown grace expires (the existing §5.2 hard-close policy) — then the owner's
     * release, which closes every physical handle the generation still owns (one Hikari abandoned
     * at that ceiling included: a duplicate does not outlive its generation) and the retained
     * connection. The close attempts start BEFORE Hikari's shutdown because that shutdown's first act
     * is `addConnectionExecutor.awaitTermination(loginTimeout)` (HikariCP 6.3.3, read off the
     * bytecode), so a stuck connection creation cannot delay starting those attempts. Hikari's later
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
            ownerReleased.set(true)
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
     * running at that pool's own deadline (`PoolCloser.deadlineFor`, #840). Defaulted so an implementation that
     * predates it keeps compiling.
     */
    fun poolCloseStuck(datasourceName: String) = Unit

    /**
     * #818d — `datapipelines.datasource.pool.reaper_pass_slow`: one reaper pass ran longer than its stated bound
     * (`ReaperDelay.PASS_BOUND_SECONDS`). Untagged: the pass is per instance, not per datasource.
     */
    fun reaperPassSlow() = Unit

    /**
     * #819 — `datapipelines.lake.freshness_tick_capped{datasource_name}`: the freshness tick found a managed generation
     * stale and did NOT retire it, because the retired generations still holding their engine were at
     * `reads.max-retired-generations` ([ConnectionPoolManager.retireStaleGeneration]). Defaulted like the two above.
     */
    fun freshnessTickCapped(datasourceName: String) = Unit

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
 * #819 — what the freshness tick's retirement did ([ConnectionPoolManager.retireStaleGeneration]).
 */
sealed interface StaleRetirement {
    /** The compared generation left the live map; the next lease builds from the catalog. */
    data object Retired : StaleRetirement

    /** Another caller already replaced or retired the compared generation; nothing was done. */
    data object NotLive : StaleRetirement

    /**
     * The retired generations still holding their engine were at the cap: the live generation was NOT retired and
     * serves until its age bound (owner ruling 2026-11-15).
     */
    data class Capped(
        val retiredHolding: Int,
        val maxRetiredGenerations: Int,
    ) : StaleRetirement
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
 * ## The retired-generation cap (#819, owner ruling 2026-11-15)
 *
 * With [maxRetiredGenerations] = K set, for each datasource name R is the number of retired MANAGED generations whose
 * pool still holds a physical handle ([ConnectionPool.holdsPhysicalHandles]: draining, or handed to the closer and
 * its close not returned). The invariant is: **at every instant, live-or-building + R ≤ 1 + K for the name.** It
 * holds by construction, not by timing:
 *
 * 1. A generation is built only inside `computeIfAbsent`, so at most one is live or building per name, and the build
 *    runs only after [admitBuild] read R ≤ K under the same bin lock (more than K → `lake.unavailable`).
 * 2. R grows only by [takeOut], which removes the live entry and counts it in one remapping under that bin lock, so
 *    R + 1 can never be observed beside a live or building generation; every other change to R is a decrease.
 * 3. So while a generation is live or building R ≤ K, and once it is retired nothing is live and R ≤ K + 1.
 *
 * The freshness tick retires only while R < K ([retireStaleGeneration]); every other retirement — the age bound on
 * the lease path and the reaper, a lost pin, a save, a delete, an invalidation, the managed re-sync — happens at any
 * R (RULING 4 unchanged). Above the cap, [admitBuild] — under the bin lock, so after any retirement that preceded it
 * — sends the lease to [settleIdleRetired] instead of refusing while an idle retired generation can still be handed
 * off or its close waited for (bounded, once per lease); the settlement hands them off and waits, and the lease
 * decides again under the lock. So an idle generation never refuses a lease, wherever its retirement fell. Every
 * other pool counts R = 0 and is never capped.
 *
 * [poolFactory] is injectable so tests can substitute a counting or fake pool; production uses
 * the default, which builds a real Hikari pool through the dialect adapter.
 */
class ConnectionPoolManager(
    private val poolFactory: (Datasource) -> ConnectionPool = ::buildHikariPool,
    /** How long a retired pool may keep connections out before [reapRetiring] closes it anyway. */
    private val retireCeiling: Duration = DEFAULT_RETIRE_CEILING,
    /**
     * #824 (owner ruling 2026-11-07) — the same ceiling for a retired MANAGED lake generation
     * ([ConnectionPool.managedGeneration] non-null) only: the assembling layer derives it from the node query timeout's
     * maximum, because a generation is retired by age or by the freshness tick, never because its readers are done.
     * Every other retired pool keeps [retireCeiling]; unset, the two are the same.
     */
    private val managedRetireCeiling: Duration = retireCeiling,
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
     * #819 (owner ruling 2026-11-15, design record §6 amendment) — `datapipelines.lake.reads.max-retired-generations`,
     * written K: the most retired MANAGED generations of one datasource name that may still hold a physical handle
     * while a replacement is built. Null (no lake configured, every test that is not about it) caps nothing. See
     * "The retired-generation cap" in the class KDoc.
     */
    private val maxRetiredGenerations: Int? = null,
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
    private val pins: ManagedGenerationPins = ManagedGenerationPins.NONE,
    private val metrics: PoolLifecycleMetrics = PoolLifecycleMetrics.NONE,
) : AutoCloseable {
    private val pools = ConcurrentHashMap<String, LivePool>()

    /**
     * Pools queued for draining. A caller may close an entry only after it leaves [pools].
     * A datasource saved twice, with a lease in between, can have two retiring pools at once.
     * A name-keyed map would lose the first pool before closing it.
     */
    private val retiring = ConcurrentLinkedQueue<RetiringPool>()

    /**
     * #819 — every retired MANAGED generation taken out of [pools] while [maxRetiredGenerations] is set, until its
     * pool no longer holds a physical handle ([ConnectionPool.holdsPhysicalHandles]). An entry is NOT removed when
     * it leaves [retiring] or is handed to the closer: only when its close has returned and released the engine.
     * Added under the live entry's bin lock ([takeOut]), so the removal from the live map and the count's growth are
     * one step for every other caller that takes that lock.
     */
    private val retained = ConcurrentLinkedQueue<RetiringPool>()

    init {
        maxRetiredGenerations?.let { require(it >= 1) { "$MAX_RETIRED_GENERATIONS_KEY must be at least 1, was $it" } }
    }

    /** Pin after view construction, before computeIfAbsent publishes the pool. */
    @Suppress("TooGenericExceptionCaught")
    private fun buildProtected(datasource: Datasource): LivePool {
        val builtAt = clock()
        val pool = poolFactory(datasource)
        val managed = pool.managedGeneration ?: return LivePool(builtAt, pool)
        if (pins === ManagedGenerationPins.NONE) return LivePool(builtAt, pool)
        val id = UUID.fromString(requireNotNull(pool.generation) { "managed generation requires an owner id" })
        val published = AtomicBoolean(false)
        lateinit var protectedPool: ConnectionPool
        try {
            check(
                pins.pin(
                    id,
                    datasource.name,
                    managed.locations
                        .mapNotNull { (name, location) ->
                            location?.let { name.substringAfter('.') to it }
                        }.toMap(),
                    builtAt,
                    { pool.holdsPhysicalHandles },
                    { if (published.get()) retireGeneration(datasource.name, protectedPool) },
                ),
            ) { "reader pin was refused" }
        } catch (failure: Exception) {
            pool.close()
            pins.unpin(id)
            throw DatapipelinesException(
                ManagedLakeErrorCodes.UNAVAILABLE,
                "Managed lake reader protection could not be acquired",
                cause = failure,
            )
        }
        protectedPool =
            object : ConnectionPool by pool {
                override fun leaseConnection(): Connection {
                    if (!pins.valid(id)) unpinned()
                    val connection = pool.leaseConnection()
                    if (pins.valid(id)) return connection
                    connection.use {
                        pool.discard(it)
                        unpinned()
                    }
                }

                private fun unpinned(): Nothing =
                    throw DatapipelinesException(ManagedLakeErrorCodes.UNAVAILABLE, "Managed lake reader protection was lost")

                override fun close() {
                    pool.close()
                    if (!pool.holdsPhysicalHandles) pins.unpin(id)
                }
            }
        published.set(true)
        return LivePool(builtAt, protectedPool)
    }

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
        val name = datasource.name
        val settlement = Settlement()
        while (true) {
            // #819 D7 (round 3): the choice between building, settling first and refusing is made only here, inside
            // the mapping, under the bin lock every retirement of [name] takes; no read before this step decides it.
            // A mapping that throws records nothing: on SettleFirst, settle outside the lock, then decide again.
            val live =
                try {
                    pools.computeIfAbsent(name) {
                        if (!admitBuild(name, settlement)) throw SettleFirst()
                        buildProtected(datasource)
                    }
                } catch (expected: SettleFirst) {
                    settleIdleRetired(name, settlement)
                    continue
                }
            val age = expiredAge(live) ?: return live.pool
            retireExpired(name, live, age, TRIGGER_LEASE)
        }
    }

    /**
     * #819 D5/D7 — the build check, run INSIDE [poolFor]'s `computeIfAbsent` mapping, so it and the build are one step
     * under [name]'s bin lock: two racing leases cannot both build, and no retirement of [name] can interleave (a
     * retirement takes the same lock, [takeOut]; while it is held R can only fall). True: build. With more than K
     * retired managed generations of [name] still holding their engine: false — settle first ([settleIdleRetired],
     * outside the lock) — while one of them is idle and not yet handed to the closer, or was handed off idle and
     * [settlement] may still wait for its close; otherwise refused with `lake.unavailable`. A name with no retired
     * managed generation counts 0, so every pool that is not the managed lake's is never refused here (ruling 2).
     *
     * Each false is followed by a settlement that hands every idle generation off (once per generation, ever) and
     * waits for idle closes until the one [Settlement] deadline, so the decide–settle loop ends: a later false needs
     * a generation that turned idle or was retired since, and after the deadline only a hand-off, never a wait.
     */
    private fun admitBuild(
        name: String,
        settlement: Settlement,
    ): Boolean {
        val cap = maxRetiredGenerations ?: return true
        val counted = retainedHolding(name)
        if (counted.size <= cap) return true
        val idle = { entry: RetiringPool -> entry.handOff.get() == HandOff.NONE && entry.pool.activeConnections == 0 }
        if (counted.any { idle(it) || settlement.mayWaitFor(it) }) return false
        // A close may have returned between the count and the reads above (its entry then reads as neither idle nor
        // waitable): count again before refusing; under this lock the count can only have fallen.
        val holding = retainedHolding(name).size
        if (holding <= cap) return true
        val cause = "$MAX_RETIRED_GENERATIONS_KEY=$cap; retired generations still holding their engine: $holding"
        throw DatapipelinesException(
            ManagedLakeErrorCodes.UNAVAILABLE,
            "The managed lake '$name' is unavailable: no new reader generation is built while the retired ones " +
                "still hold their engines — $cause",
            mapOf("datasource" to name, "cause" to cause),
        )
    }

    /**
     * #819 D7 — healthy operation is never refused: run only when [admitBuild], under the bin lock, chose to settle
     * first. Hands every IDLE retired managed generation of [name] (not yet handed off, no connection out) to
     * [closer] now, as the reaper's next pass would, then waits for the closes of the generations handed off idle,
     * until [settlement]'s one deadline: the longest `connectionTimeout` among the closes pending at its first wait,
     * the wait a lease already admits for a connection. No lock is held and nothing polls. It decides nothing: the
     * caller goes back to [admitBuild], which judges what this left. A generation with a reader still running is
     * never waited for: it is the cap's incident case.
     */
    private fun settleIdleRetired(
        name: String,
        settlement: Settlement,
    ) {
        val holding = retainedHolding(name)
        holding
            .filter { it.handOff.get() == HandOff.NONE && pools[it.name] !== it.live && it.pool.activeConnections == 0 }
            .forEach { entry ->
                retiring.remove(entry)
                handOff(entry, drained = true)
            }
        val pending = holding.filter { it.handOff.get() == HandOff.DRAINED && !it.closeReturned.isDone }
        if (pending.isEmpty()) return
        val deadline = settlement.deadline(pending)
        for (entry in pending) {
            val left = deadline - System.nanoTime()
            if (left <= 0) break
            runCatching { entry.closeReturned.get(left, TimeUnit.NANOSECONDS) }
                .onFailure { if (it !is TimeoutException) throw it }
        }
    }

    /**
     * #819 D7 — one [poolFor] call's bound on waiting for idle closes: fixed at the call's first wait, never moved, so
     * the total wait of one lease is at most one `connectionTimeout` however often it decides again. Confined to the
     * calling thread.
     */
    private class Settlement {
        private var deadline: Long? = null

        /** The call's deadline (a `System.nanoTime` instant), fixed from [pending] at the first wait. */
        fun deadline(pending: List<RetiringPool>): Long =
            deadline ?: (System.nanoTime() + pending.maxOf { it.pool.connectionTimeout }.toNanos()).also { deadline = it }

        /** Whether [entry]'s close is one this call may still wait for: handed off idle, not returned, bound not spent. */
        fun mayWaitFor(entry: RetiringPool): Boolean {
            if (entry.handOff.get() != HandOff.DRAINED || entry.closeReturned.isDone) return false
            val fixed = deadline ?: return true
            return fixed - System.nanoTime() > 0
        }
    }

    /**
     * #819 D2 — the retired managed generations of [name] whose pool still holds a physical handle: draining on
     * [retiring], or handed to the closer and its close has not returned. Entries whose engine is released are
     * dropped here (and on the reaper's pass); that is the only way an entry leaves the count.
     */
    private fun retainedHolding(name: String): List<RetiringPool> {
        retained.removeIf { !it.pool.holdsPhysicalHandles }
        return retained.filter { it.name == name }
    }

    /**
     * #819 — how many retired managed generations of [name] still hold their engine (R in the class KDoc); 0 when
     * no cap is configured. Observability and the cap's proofs.
     */
    fun retiredGenerationsHolding(name: String): Int = if (maxRetiredGenerations == null) 0 else retainedHolding(name).size

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
        if (takeOut(name) { it === live } == null) return false
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
    ): Boolean = takeOut(name) { it.pool === expected } != null

    /**
     * #819 D3 (owner ruling 2026-11-15) — the freshness tick's retirement: as [retireGeneration], except that while
     * K or more retired managed generations of [name] still hold their engine the live generation is NOT retired and
     * serves until its age bound ([StaleRetirement.Capped]: one WARN `lake.freshness_tick_capped` naming the key, K,
     * the count and [staleTables], and one `freshness_tick_capped` increment). The judgement and the removal are one
     * step under [name]'s bin lock. Every other retirement path ignores the cap (D4).
     */
    fun retireStaleGeneration(
        name: String,
        expected: ConnectionPool,
        staleTables: Int,
    ): StaleRetirement {
        var capped: StaleRetirement.Capped? = null
        val taken =
            takeOut(name) { live ->
                if (live.pool !== expected) return@takeOut false
                val cap = maxRetiredGenerations ?: return@takeOut true
                val holding = retainedHolding(name).size
                if (holding < cap) return@takeOut true
                capped = StaleRetirement.Capped(holding, cap)
                false
            }
        capped?.let {
            LOG.warn(
                "event=lake.freshness_tick_capped datasource={} tables_stale={} retired_generations={} " +
                    "max_retired_generations={} key={} " +
                    "message=\"freshness tick skipped; the live generation serves until its age bound\"",
                name,
                staleTables,
                it.retiredHolding,
                it.maxRetiredGenerations,
                MAX_RETIRED_GENERATIONS_KEY,
            )
            metrics.freshnessTickCapped(name)
            return it
        }
        return if (taken != null) StaleRetirement.Retired else StaleRetirement.NotLive
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
    fun retire(name: String): Boolean = takeOut(name) { true } != null

    /**
     * Retirement step 1, shared by every path that takes a pool out of the map: removes [name]'s live entry when
     * [judge] accepts it and queues it on [retiring] (and, for a managed generation under a cap, on [retained])
     * INSIDE the map's remapping, so no caller holding [name]'s bin lock ever sees the entry gone from the map and
     * not yet counted (#819 D6). The reaper and settlement compare the entry's identity with the live map before
     * handing it off. A lookup can still return the live entry until the remapping returns. The soft evict and
     * metric run outside the lock, after removal. Returns the entry taken out, or null when none was live or
     * [judge] refused it.
     */
    private fun takeOut(
        name: String,
        judge: (LivePool) -> Boolean,
    ): LivePool? {
        var taken: LivePool? = null
        pools.computeIfPresent(name) { _, current ->
            if (!judge(current)) return@computeIfPresent current
            taken = current
            val pool = current.pool
            val ceiling = if (pool.managedGeneration != null) managedRetireCeiling else retireCeiling
            val entry = RetiringPool(name, current, deadline = clock().plus(ceiling), ceiling = ceiling)
            if (maxRetiredGenerations != null && pool.managedGeneration != null) retained += entry
            retiring += entry
            null
        }
        taken?.let {
            it.pool.softEvict()
            metrics.poolRetired(name)
        }
        return taken
    }

    /**
     * Hands [entry] to [closer] exactly once, whoever asks first (the reaper's pass or [settleIdleRetired]). A
     * [retained] entry is handed off behind a delegate whose close completes [RetiringPool.closeReturned] when the
     * real close returns, whatever it threw; every other pool is handed off as itself, as before #819.
     */
    private fun handOff(
        entry: RetiringPool,
        drained: Boolean,
    ): Boolean {
        if (!entry.handOff.compareAndSet(HandOff.NONE, if (drained) HandOff.DRAINED else HandOff.HARD)) return false
        if (entry !in retained) {
            closer.close(entry.pool)
            return true
        }
        val pool = entry.pool
        closer.close(
            object : ConnectionPool by pool {
                override fun close() {
                    try {
                        pool.close()
                    } finally {
                        entry.closeReturned.complete(Unit)
                    }
                }
            },
        )
        return true
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
        if (maxRetiredGenerations != null) retained.removeIf { !it.pool.holdsPhysicalHandles }
        if (retiring.isEmpty()) return ReapOutcome(drained = 0, hardClosed = 0, expired = expired)
        val now = clock()
        var drained = 0
        var hardClosed = 0
        val entries = retiring.iterator()
        while (entries.hasNext()) {
            val entry = entries.next()
            val active = if (pools[entry.name] === entry.live) null else entry.pool.activeConnections
            when {
                active == null -> {
                    // The remapping has queued the entry and still owns the live slot.
                }

                active == 0 -> {
                    entries.remove()
                    if (handOff(entry, drained = true)) drained++
                }

                now >= entry.deadline -> {
                    entries.remove()
                    if (!handOff(entry, drained = false)) continue
                    LOG.warn(
                        "event=datasource.pool_hard_closed datasource={} active_connections={} ceiling_seconds={} " +
                            "message=\"retired pool still had connections out at its ceiling; closing it anyway\"",
                        entry.pool.name,
                        active,
                        entry.ceiling.seconds,
                    )
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
     * value: `ExecutionDrainLifecycle` has requested cancellation, but a native reader can still be running.
     * The pin port's shutdown barrier keeps its heartbeat and metadata resources alive until physical closure.
     *
     * #818d — synchronous on the caller's thread, NOT through [closer]: this is not the reaper's path, and a shutdown
     * that returned before its pools closed would release the context with generations still open. Each close is
     * still interrupt-first ([HikariConnectionPool.close]).
     */
    override fun close() {
        pools.keys.toList().forEach { name -> pools.remove(name)?.pool?.close() }
        generateSequence { retiring.poll() }.forEach { it.pool.close() }
        retained.clear()
        pins.close()
    }

    /**
     * #819 D7 — thrown by [poolFor]'s mapping when [admitBuild] chose to settle first, so the map records nothing and
     * the bin lock is released before the settlement waits. Never leaves [poolFor]; no stack trace is filled.
     */
    private class SettleFirst : RuntimeException("settle idle retired generations first", null, false, false)

    /** Where a retired pool stands with the closer ([RetiringPool.handOff]). */
    private enum class HandOff { NONE, DRAINED, HARD }

    /** A live pool with the instant it was built: the age bound's input (#818). */
    private class LivePool(
        val builtAt: Instant,
        val pool: ConnectionPool,
    )

    /**
     * A pool queued for draining, with the instant after which it is closed regardless of active connections and
     * the [ceiling] used to stamp it (#824: the managed one for a managed generation).
     */
    private class RetiringPool(
        val name: String,
        val live: LivePool,
        val deadline: Instant,
        val ceiling: Duration,
    ) {
        val pool: ConnectionPool get() = live.pool

        /**
         * Won by the ONE caller that hands this pool to the closer ([handOff]), and whether it had no connection out
         * then ([HandOff.DRAINED]: only such a close is waited for, [settleIdleRetired]). One atomic, so no reader can
         * see "handed off" without "how" (#819 round 3).
         */
        val handOff = AtomicReference(HandOff.NONE)

        /** Completed when the close handed off for a [retained] entry has returned ([handOff]). */
        val closeReturned = CompletableFuture<Unit>()
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(ConnectionPoolManager::class.java)

        /** `lake.generation_expired`'s `trigger`: the lease path judged the age ([poolFor]). */
        const val TRIGGER_LEASE = "lease"

        /** `lake.generation_expired`'s `trigger`: the reaper's pass judged it with no borrower ([retireExpiredGenerations]). */
        const val TRIGGER_REAPER = "reaper"

        /** #819 — the key K is bound from, named in the cap's refusal and its boot violation. */
        const val MAX_RETIRED_GENERATIONS_KEY = "datapipelines.lake.reads.max-retired-generations"

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
         * pinned 1.5.6.0, 2026-11-13 (`LakeSpillIntegrationTest`'s joining pool, both URL kinds). A lake keeps
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
