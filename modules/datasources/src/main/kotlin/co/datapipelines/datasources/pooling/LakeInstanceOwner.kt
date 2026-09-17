package co.datapipelines.datasources.pooling

import org.slf4j.LoggerFactory
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.sql.Connection
import java.sql.SQLException
import java.sql.SQLNonTransientConnectionException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.sql.DataSource

/**
 * 152 (#128) — the retained OWNER of one LAKE pool generation's DuckDB instance.
 *
 * ## Why an owner exists
 *
 * On `jdbc:duckdb::memory:` every `DriverManager`/`DriverDataSource` open creates a NEW engine
 * instance, and the instance dies with its last connection — so with one open per physical
 * connection, HikariCP's routine replacement (`maxLifetime`, idle eviction, a failed
 * validation) threw away the engine's external-file cache every 22.5–30 min and the next scan
 * of the same objects was cold again (the 128 investigation's Hikari witness: 2.1 MB cached →
 * 0 on the replacement, 23 s later). The remedy is ownership: ONE raw driver connection is
 * opened per pool generation, the instance is initialized on it exactly once (extensions,
 * secret, ATTACHes, engine limits, per-table views), and it is then RETAINED, never leased and
 * never queried again — its only job is to keep the instance alive. Every physical connection
 * Hikari opens is a [duplicate] of it: the pinned driver's `DuckDBConnection.duplicate()`
 * opens a second connection to the SAME native instance (verified 2026-09-16 on duckdb_jdbc
 * 1.5.5.1: catalog, secrets, `memory_limit`/`threads` and the file cache are shared;
 * `search_path` is per connection; a duplicate outlives a closed owner; `duplicate()` on a
 * closed owner throws `"Connection was closed"`).
 *
 * ## Ownership rules (datasources.md §5.2, §8C.2)
 *
 * - One owner = one generation = one instance. A rebuilt pool (registry change, credential
 *   rotation, reconcile) opens a NEW owner, so a retiring generation and its replacement never
 *   share catalog or credential state; two datasources never share an instance either — the
 *   anonymous URL has no process-global name to collide on.
 * - The owner is retired then closed by ONE caller of [HikariConnectionPool.close]: [retire]
 *   before Hikari's shutdown (no new duplicate may join a generation being torn down), [close]
 *   AFTER it — the last handle drops and the instance is freed. [close] is idempotent.
 * - **Every physical duplicate has an owner until its closure is CONFIRMED.** [duplicate] hands
 *   out a [TrackedDuplicate] registered with the generation; it leaves the registry only when the
 *   driver has returned from `close()` without throwing (an abort, a throwing close or a close
 *   still in flight leave it registered), and [close] releases whatever is still registered —
 *   a creation Hikari's closed bag refused (HikariCP does not close what it refuses), a borrower
 *   abandoned at Hikari's ceiling, a handle whose first close the driver refused — retrying
 *   once and counting what actually closed. Registration is atomic with [retire]; every registry
 *   access takes the same monitor; no driver work ever runs under it. (R152-2/3/4)
 * - **No silent reconnect.** If the owner is lost (closed by a driver fault, or a bug), every
 *   subsequent [duplicate] fails with an [SQLNonTransientConnectionException] naming the
 *   datasource and generation; the pool does NOT open a fresh engine behind the caller's back,
 *   because that engine would carry none of the generation's views or secrets. The existing
 *   retire-and-rebuild path (§5.2) is the remedy, and the failure is logged once per owner.
 *
 * The driver is reached REFLECTIVELY: this module never compiles against a JDBC driver
 * (datasources.md §10.3), and `duplicate()` is DuckDB's own method, not a `java.sql` one.
 */
class LakeInstanceOwner private constructor(
    /** The datasource this generation serves — for logs and failure messages. */
    val datasourceName: String,
    private val connection: Connection,
    private val duplicateMethod: Method,
) : PoolInstanceOwner {
    /** This generation's identity — distinct per pool build, carried in every lifecycle log line. */
    val generation: String = UUID.randomUUID().toString().substring(0, GENERATION_CHARS)

    private val retired = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val lossLogged = AtomicBoolean(false)

    /** True while the retained connection is open — the generation is alive. */
    val isOpen: Boolean
        get() = !closed.get() && runCatching { !connection.isClosed }.getOrDefault(false)

    /** True once the pool's shutdown has begun: no further physical connection may join. */
    val isRetired: Boolean get() = retired.get()

    /**
     * The pool is shutting down (the FIRST step of [HikariConnectionPool.close], before Hikari's
     * own shutdown): from here every [duplicate] is refused, so a physical connection Hikari's
     * creator thread was about to open cannot join a generation that is being torn down. The
     * retained connection stays open — Hikari still has borrowers to abort — until [close].
     */
    override fun retire() {
        retired.set(true)
    }

    /**
     * A NEW connection to this generation's instance — what every physical Hikari connection is.
     *
     * @throws SQLNonTransientConnectionException when the owner is retired, closed or lost — the
     *   caller (Hikari's connection creation) surfaces it as a lease failure; nothing reconnects.
     */
    fun duplicate(): Connection {
        refusal()?.let { throw it }
        val raw =
            try {
                duplicateMethod.invoke(connection) as Connection
            } catch (e: InvocationTargetException) {
                throw unwrapped(e)
            }
        return track(raw)
    }

    /**
     * Registration is atomic with retirement (R152-2): the driver call above ran outside any
     * lock, and here — a set insert under the handles lock, no driver work — a retire that landed
     * meanwhile finds the handle and it closes at once; a retire that lands later finds it in
     * the set and [close] takes it. From this point the handle has an owner for its whole life:
     * Hikari (through the wrapper's own close/abort), or this generation.
     */
    private fun track(raw: Connection): Connection {
        val tracked = TrackedDuplicate(raw, registry)
        val refusedAfterRelease: Boolean? =
            synchronized(handles) {
                // Registered EITHER way (protocol A1/A2): an admitted handle for its holder, a
                // refused one for accounting — its creator closes it below, and if that close
                // fails the generation's release (not yet run: closed=false) will visit it, or,
                // when the release has already passed (closed=true), the creator is the holder of
                // last resort and takes the hand-off retry itself. Both facts are read under the
                // same monitor the release sets `closed` and snapshots under, so they cannot cross.
                handles.add(tracked)
                if (retired.get() || closed.get()) closed.get() else null
            }
        if (refusedAfterRelease != null) {
            runCatching { tracked.closeAsRefused(generationAlreadyReleased = refusedAfterRelease) }
                .onFailure {
                    LOG.debug(
                        "event=lake.instance_refused_duplicate_close_failed datasource={} generation={} error=\"{}\"",
                        datasourceName,
                        generation,
                        it.message,
                    )
                }
            throw retiring()
        }
        return tracked
    }

    /**
     * Physical duplicates this generation created whose closure it has not yet seen CONFIRMED —
     * see [track]. EVERY access — insert, forget, count, the release's snapshot — takes this
     * set's monitor (R152-3); nothing else is ever done under it, so no driver work is ever
     * inside the lock. A handle leaves the set only through [registry], on a confirmed close.
     */
    private val handles = HashSet<TrackedDuplicate>()

    private val registry =
        object : TrackedDuplicate.Registry {
            override fun forget(handle: TrackedDuplicate) {
                synchronized(handles) { handles.remove(handle) }
            }

            /**
             * A handle's permitted attempts are all refused and no actor is left (protocol F1/F3
             * after the release, or G3): it stays registered and counted; this is the report.
             */
            override fun exhausted(
                handle: TrackedDuplicate,
                error: Exception,
            ) {
                LOG.warn(
                    "event=lake.instance_handle_exhausted datasource={} generation={} attempts={} error=\"{}\" " +
                        "message=\"a physical connection refused every permitted close; it stays counted as this generation's residual\"",
                    datasourceName,
                    generation,
                    handle.closeAttempts,
                    error.message,
                )
            }
        }

    /**
     * How many physical duplicates the generation still owns — registered, and not confirmed
     * closed. After [close] this is the honest residual: handles whose close the driver refused,
     * or whose in-flight close on another thread has not yet reported (tests, diagnostics).
     */
    val liveDuplicates: Int get() = synchronized(handles) { handles.size }

    /** The driver's own exception out of the reflective call; an owner lost mid-call is named as such. */
    private fun unwrapped(e: InvocationTargetException): SQLException {
        val cause = e.targetException
        return when {
            cause is SQLException && !isOpen -> ownerLost(cause)
            cause is SQLException -> cause
            else -> SQLException("duplicate() on datasource '$datasourceName' failed", cause)
        }
    }

    /** Why no duplicate may be opened right now — retiring first, then lost — or null when one may. */
    private fun refusal(): SQLNonTransientConnectionException? =
        when {
            retired.get() -> retiring()
            !isOpen -> ownerLost()
            else -> null
        }

    private fun retiring(): SQLNonTransientConnectionException =
        SQLNonTransientConnectionException(
            "the DuckDB instance owner for datasource '$datasourceName' (generation $generation) is shutting down; " +
                "no new connection can join it",
            SQLSTATE_CONNECTION_FAILURE,
        )

    private fun ownerLost(cause: Throwable? = null): SQLNonTransientConnectionException {
        if (lossLogged.compareAndSet(false, true) && !closed.get()) {
            LOG.error(
                "event=lake.instance_owner_lost datasource={} generation={} " +
                    "message=\"the retained DuckDB instance owner is closed; new connections are refused until the pool is rebuilt\"",
                datasourceName,
                generation,
            )
        }
        return SQLNonTransientConnectionException(
            "the DuckDB instance owner for datasource '$datasourceName' (generation $generation) is closed; " +
                "no new connection can join it — the pool must be rebuilt",
            SQLSTATE_CONNECTION_FAILURE,
            cause,
        )
    }

    /**
     * Closes, once, every physical handle this generation still owns and then the retained
     * connection; later calls are no-ops. Never throws — not for an SQLException and not for a
     * nonfatal RuntimeException from the driver, on the duplicates or on the retained connection
     * (the caller is the pool's close, and beyond it the shared manager's queue loop and the
     * reaper, which visit every retired pool in turn).
     *
     * By now the pool has shut Hikari down, so a handle still in [handles] is one Hikari never
     * accepted (its creation straddled the shutdown — the bag was already closed when the creator
     * tried to add it, and Hikari does not close what it refuses) or one Hikari abandoned to a
     * borrower at its own shutdown ceiling. Neither may outlive the generation: an open duplicate
     * keeps the whole instance alive. They are closed here, the count logged — a non-zero count is
     * the rare event, and the only residual is the driver refusing a close.
     */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // Snapshot under the monitor, never clear: a handle leaves the set only when its closure
        // is confirmed, so whatever a failed or in-flight close leaves behind stays counted.
        val remaining = synchronized(handles) { handles.toList() }
        if (remaining.isNotEmpty()) {
            // releaseByGeneration never throws for a driver exception (that is a FAILED outcome);
            // the runCatching is the belt for anything else nonfatal, so one handle can never
            // stop the others or the retained owner's close below.
            val outcomes =
                remaining
                    .groupingBy { handle ->
                        runCatching { handle.releaseByGeneration() }.getOrDefault(TrackedDuplicate.ReleaseOutcome.FAILED)
                    }.eachCount()
            val failed = outcomes[TrackedDuplicate.ReleaseOutcome.FAILED] ?: 0
            val inFlight = outcomes[TrackedDuplicate.ReleaseOutcome.IN_FLIGHT] ?: 0
            LOG.warn(
                "event=lake.instance_handles_closed datasource={} generation={} handles={} closed={} already_closed={} " +
                    "in_flight={} close_failures={} error=\"{}\" " +
                    "message=\"physical connections the pool never accepted or abandoned were released with the generation\"",
                datasourceName,
                generation,
                remaining.size,
                outcomes[TrackedDuplicate.ReleaseOutcome.CLOSED] ?: 0,
                outcomes[TrackedDuplicate.ReleaseOutcome.ALREADY_CLOSED] ?: 0,
                inFlight,
                failed,
                remaining.firstNotNullOfOrNull { it.lastCloseFailure }?.message.orEmpty(),
            )
        }
        // The retained connection: ONE attempt, and the same nonfatal-exception policy as the
        // duplicates' — an SQLException or a RuntimeException the driver surfaces is contained
        // here and reported, never propagated: this runs inside HikariConnectionPool.close's
        // finally, and from there through ConnectionPoolManager.close's queue loop and the
        // reaper, where an escaping exception would stop the cleanup of UNRELATED pools
        // (R152-8). A refused close is not called a closure: the WARN carries the error and
        // the closing line says whether the physical owner actually closed.
        val ownerClosed =
            try {
                connection.close()
                true
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                ownerCloseFailure = e
                LOG.warn(
                    "event=lake.instance_owner_close_failed datasource={} generation={} error=\"{}\" " +
                        "message=\"the retained DuckDB owner connection refused to close; it is the driver's residual, reported once\"",
                    datasourceName,
                    generation,
                    e.message,
                )
                false
            }
        LOG.info(
            "event=lake.instance_closed datasource={} generation={} retained_owner_closed={}",
            datasourceName,
            generation,
            ownerClosed,
        )
    }

    /** The driver's refusal to close the retained connection at release, if any — the residual's diagnostic. */
    @Volatile
    var ownerCloseFailure: Exception? = null
        private set

    /** The physical truth about the retained connection — never inferred from [close] having run. */
    val isRetainedConnectionOpen: Boolean
        get() = runCatching { !connection.isClosed }.getOrDefault(false)

    companion object {
        private val LOG = LoggerFactory.getLogger(LakeInstanceOwner::class.java)

        /** SQL standard class 08 — the connection-exception family every lease boundary already classifies. */
        private const val SQLSTATE_CONNECTION_FAILURE = "08003"

        private const val GENERATION_CHARS = 8

        /**
         * Opens the owner connection through [delegate] — the pool's own driver `DataSource`,
         * so the instance opens exactly as a pooled connection would — resolves the driver's
         * `duplicate()`, then runs [initialize] on it ONCE. A failure anywhere after the open
         * closes the connection before rethrowing: an owner either exists fully initialized or
         * not at all, and no half-initialized instance is ever handed to a pool.
         */
        fun open(
            delegate: DataSource,
            datasourceName: String,
            initialize: (Connection) -> Unit,
        ): LakeInstanceOwner {
            val connection = delegate.connection
            val owner =
                try {
                    val method =
                        runCatching { connection.javaClass.getMethod("duplicate") }.getOrNull()
                            ?: throw SQLNonTransientConnectionException(
                                "datasource '$datasourceName': driver ${connection.javaClass.name} has no duplicate() — " +
                                    "a LAKE pool needs the DuckDB driver's shared-instance connection",
                            )
                    initialize(connection)
                    LakeInstanceOwner(datasourceName, connection, method)
                } catch (
                    // The probe's DS-SEC-6 rule: a driver reports failure as SQLException, but
                    // DuckDB also surfaces internal faults as RuntimeExceptions — both must close
                    // the half-initialized instance before propagating.
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    runCatching { connection.close() }
                    throw e
                }
            LOG.info("event=lake.instance_opened datasource={} generation={}", datasourceName, owner.generation)
            return owner
        }
    }
}
