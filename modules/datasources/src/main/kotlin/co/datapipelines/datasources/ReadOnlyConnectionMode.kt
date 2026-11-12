package co.datapipelines.datasources

import co.datapipelines.typesystem.Dialect
import java.sql.Connection

/**
 * The per-lease **read-only mode** (#463; dag-executor.md §8.2's per-dialect table): a leased
 * connection asks its DATABASE to refuse writes for exactly the span of one block, on the
 * execution paths that promise to read only — dashboard sources (refresh and draft preview),
 * published GET endpoints, and every child those paths compose.
 *
 * ## Strength varies by dialect, and the table says which
 *
 * [enforcementFor] is a closed per-dialect table, exhaustive over [Dialect] with no `else`, so a
 * new dialect cannot ship without a decision. Every entry is a MEASUREMENT on the pinned driver
 * (`ReadOnlyConnectionModeIntegrationTest`, `ReadOnlyConnectionModeEmbeddedTest`), never driver
 * documentation:
 *
 *  - [Enforcement.DATABASE] — the database itself refuses a write inside [within], with an
 *    `SQLException` from the driver. The mechanism is [Connection.setReadOnly] where the pinned
 *    driver turns it into a database-side mode (pgjdbc scopes it to a transaction, so the mode
 *    owns one), or the dialect's own constant session statement where the JDBC flag does nothing.
 *  - [Enforcement.DECLARATIVE_ONLY] — no measured mechanism: [within] runs the block as it is,
 *    and the path's guarantee stays the read-only rule plus a SELECT-only database user
 *    (datasources.md §5.7). LAKE, ORACLE and MSSQL are fixed here (LAKE's enforcement surface is
 *    the managed-table gate, ORACLE's driver is opt-in at build and MSSQL's container is
 *    host-gated, so no default run can measure them).
 *
 * No SQL text is parsed and no statement is built from input: every statement this object runs
 * is a constant of the table.
 *
 * ## The lifecycle contract of [within]: it owns what it changes and nothing else
 *
 * Entry captures `autoCommit`, `isReadOnly` and — for a session-statement mechanism — that
 * mechanism's OWN setting read from the database (a JDBC flag does not report it). A lease that
 * arrives out of autocommit is rolled back first so the driver sees an idle connection. An entry
 * step that fails undoes the steps already taken, in reverse, and the ENTRY failure propagates
 * with any undo failure suppressed onto it.
 *
 * Exit, in a `finally`, in this order: roll back if the connection is out of autocommit now (a
 * read-only transaction holds nothing durable, and rollback is valid on an aborted one too);
 * restore exactly what the mechanism changed to its CAPTURED value (a lease that arrived
 * restricted leaves restricted — never forced writable); restore autocommit. The block's own
 * failure propagates as the SAME instance with exit failures suppressed onto it; with no block
 * failure, the first exit failure is thrown (the caller decides its code).
 *
 * **The one disposal rule:** the connection is handed to `discard` — so the pool never lends it
 * again — if and only if an undo or exit step failed. A failed entry step whose undo succeeded
 * leaves the lease reusable in its captured state. Pool hygiene is structural, not a caller's
 * duty: the restore lives here, in the `finally`, and HikariCP's own reset on `close()` is not
 * relied on.
 */
object ReadOnlyConnectionMode {
    /** How strongly [within] keeps a write out of the database on a dialect. */
    enum class Enforcement {
        /** The database refuses the write (measured). */
        DATABASE,

        /** Nothing at the connection; the read-only rule and the database user are the guard. */
        DECLARATIVE_ONLY,
    }

    /** What [within] enforces on [dialect] — the measured table (see the object KDoc). */
    fun enforcementFor(dialect: Dialect): Enforcement = mechanismFor(dialect).enforcement

    /** The documented name of [dialect]'s mechanism — the dag-executor.md table's third column. */
    fun mechanismLabel(dialect: Dialect): String = mechanismFor(dialect).label

    /**
     * Runs [block] with [connection] in [dialect]'s read-only mode, restoring the connection on
     * every path (see the object KDoc for the contract). [discard] takes a connection that must
     * never return to its pool — for a pooled lease, [co.datapipelines.datasources.pooling.ConnectionPool.discard].
     *
     * [active] false runs [block] as it is, exactly like a `DECLARATIVE_ONLY` dialect (the caller's
     * writable path). The two mappers let a caller give each failure ITS code without telling them
     * apart itself: [onEntryFailure] receives an entry step's failure (the block never ran),
     * [onExitFailure] an exit step's failure after a block that returned; the block's own failure
     * is never mapped and propagates as the same instance.
     *
     * Inline so [block] may suspend in a suspending caller; [block] stays the last parameter.
     */
    @Suppress("TooGenericExceptionCaught") // the block's failure may be ANY throwable, cancellation included
    inline fun <T> within(
        connection: Connection,
        dialect: Dialect,
        noinline discard: (Connection) -> Unit,
        active: Boolean = true,
        noinline onEntryFailure: (Exception) -> Throwable = { it },
        noinline onExitFailure: (Exception) -> Throwable = { it },
        block: () -> T,
    ): T {
        val scope = enter(connection, if (active) dialect else null, discard, onEntryFailure)
        var primary: Throwable? = null
        try {
            return block()
        } catch (t: Throwable) {
            primary = t
            throw t
        } finally {
            scope.exit(primary, onExitFailure)
        }
    }

    /** Entry half of [within] (a null [dialect] = inactive); public only because [within] is inline. */
    @PublishedApi
    @Suppress("TooGenericExceptionCaught") // an entry step may refuse in ANY shape; the caller maps it
    internal fun enter(
        connection: Connection,
        dialect: Dialect?,
        discard: (Connection) -> Unit,
        onEntryFailure: (Exception) -> Throwable,
    ): Scope {
        val mechanism = dialect?.let(::mechanismFor) ?: Mechanism.None(INACTIVE)
        return try {
            Scope.enter(connection, mechanism, discard)
        } catch (e: Exception) {
            throw onEntryFailure(e)
        }
    }

    /** The closed per-dialect table. No `else`: a new [Dialect] is a compile error here. */
    internal fun mechanismFor(dialect: Dialect): Mechanism =
        when (dialect) {
            Dialect.POSTGRES -> {
                Mechanism.JdbcReadOnly(ownsTransaction = true)
            }

            Dialect.MYSQL -> {
                Mechanism.JdbcReadOnly(ownsTransaction = false)
            }

            Dialect.SQLITE -> {
                Mechanism.SessionSetting(read = "PRAGMA query_only", on = "PRAGMA query_only = ON", off = "PRAGMA query_only = OFF")
            }

            Dialect.H2 -> {
                Mechanism.None(MEASURED_NONE)
            }

            Dialect.DUCKDB -> {
                Mechanism.None(MEASURED_NONE)
            }

            Dialect.ORACLE -> {
                Mechanism.None(FIXED_NONE)
            }

            Dialect.MSSQL -> {
                Mechanism.None(FIXED_NONE)
            }

            Dialect.LAKE -> {
                Mechanism.None(FIXED_NONE)
            }
        }

    private const val MEASURED_NONE = "none (measured)"
    private const val FIXED_NONE = "none (fixed)"
    private const val INACTIVE = "inactive"

    /** One dialect's mechanism. */
    internal sealed interface Mechanism {
        val enforcement: Enforcement
        val label: String

        /** No connection-level mechanism: [within] runs the block as it is. */
        data class None(
            override val label: String,
        ) : Mechanism {
            override val enforcement: Enforcement get() = Enforcement.DECLARATIVE_ONLY
        }

        /**
         * `Connection.setReadOnly(true)`; [ownsTransaction] when the pinned driver scopes the flag
         * to a transaction (pgjdbc refuses the change unless idle and enforces nothing under
         * autocommit), so the mode switches autocommit off for the block and owns that transaction.
         */
        data class JdbcReadOnly(
            val ownsTransaction: Boolean,
        ) : Mechanism {
            override val enforcement: Enforcement get() = Enforcement.DATABASE
            override val label: String
                get() = if (ownsTransaction) "`setReadOnly(true)` in a transaction the mode owns" else "`setReadOnly(true)`"
        }

        /** A constant session statement pair, and the query that reads the setting back. */
        data class SessionSetting(
            val read: String,
            val on: String,
            val off: String,
        ) : Mechanism {
            override val enforcement: Enforcement get() = Enforcement.DATABASE
            override val label: String get() = "`$on`"
        }
    }

    /** One entered read-only span; [exit] ends it. Public only because [within] is inline. */
    @PublishedApi
    internal class Scope private constructor(
        private val connection: Connection,
        private val mechanism: Mechanism,
        private val discard: (Connection) -> Unit,
        private val capturedAutoCommit: Boolean,
        private val capturedReadOnly: Boolean,
        private val capturedSetting: Boolean?,
    ) {
        /**
         * Ends the span (see the object KDoc): every step is attempted; with [primary] the
         * failures are suppressed onto it, without one the first, mapped by [onExitFailure], is
         * thrown with the rest suppressed onto it. Any failure disposes the connection.
         */
        @Suppress("TooGenericExceptionCaught") // a restore step may refuse in ANY shape; each is recorded
        fun exit(
            primary: Throwable?,
            onExitFailure: (Exception) -> Throwable,
        ) {
            if (mechanism is Mechanism.None) return
            val failures = mutableListOf<Exception>()

            fun attempt(step: () -> Unit) {
                try {
                    step()
                } catch (e: Exception) {
                    failures += e
                }
            }
            attempt { if (!connection.autoCommit) connection.rollback() }
            attempt { undoMechanism() }
            attempt { if (connection.autoCommit != capturedAutoCommit) connection.autoCommit = capturedAutoCommit }
            if (failures.isEmpty()) return
            val carrier = primary ?: failures.first()
            failures.filter { it !== carrier }.forEach(carrier::addSuppressed)
            dispose(carrier)
            if (primary == null) throw onExitFailure(failures.first())
        }

        private fun undoMechanism() {
            when (mechanism) {
                is Mechanism.None -> Unit
                is Mechanism.JdbcReadOnly -> connection.isReadOnly = capturedReadOnly
                is Mechanism.SessionSetting -> execute(if (capturedSetting == true) mechanism.on else mechanism.off)
            }
        }

        private fun execute(sql: String) {
            connection.createStatement().use { it.execute(sql) }
        }

        /** Hands the connection to `discard`; a discard that breaks its never-throws contract is recorded, not raised. */
        @Suppress("TooGenericExceptionCaught") // the discard contract says never throws; a broken one must not mask the real failure
        private fun dispose(onto: Throwable) {
            try {
                discard(connection)
            } catch (e: Exception) {
                onto.addSuppressed(e)
            }
        }

        companion object {
            /** Captures, then takes the mechanism's steps; a failed step undoes the taken ones in reverse. */
            fun enter(
                connection: Connection,
                mechanism: Mechanism,
                discard: (Connection) -> Unit,
            ): Scope {
                if (mechanism is Mechanism.None) return Scope(connection, mechanism, discard, true, false, null)
                val autoCommit = connection.autoCommit
                val readOnly = connection.isReadOnly
                val setting = (mechanism as? Mechanism.SessionSetting)?.let { readSetting(connection, it) }
                if (!autoCommit) connection.rollback()
                val scope = Scope(connection, mechanism, discard, autoCommit, readOnly, setting)
                scope.takeSteps()
                return scope
            }

            private fun readSetting(
                connection: Connection,
                mechanism: Mechanism.SessionSetting,
            ): Boolean =
                connection.createStatement().use { statement ->
                    statement.executeQuery(mechanism.read).use { rows ->
                        check(rows.next()) { "the read-only setting read returned no row" }
                        rows.getBoolean(1)
                    }
                }
        }

        /** The mechanism's entry steps, each paired with its undo to the captured value. */
        private fun steps(): List<Pair<() -> Unit, () -> Unit>> =
            when (mechanism) {
                is Mechanism.None -> {
                    emptyList()
                }

                is Mechanism.JdbcReadOnly -> {
                    listOfNotNull(
                        if (mechanism.ownsTransaction && capturedAutoCommit) {
                            Pair({ connection.autoCommit = false }, { connection.autoCommit = capturedAutoCommit })
                        } else {
                            null
                        },
                        Pair({ connection.isReadOnly = true }, { connection.isReadOnly = capturedReadOnly }),
                    )
                }

                is Mechanism.SessionSetting -> {
                    listOf(Pair({ execute(mechanism.on) }, { undoMechanism() }))
                }
            }

        /**
         * Takes [steps] in order. A step that fails is undone itself (a driver may have applied it
         * before refusing) and so is every step already taken, in reverse; each undo failure is
         * suppressed onto the entry failure, and any one of them disposes the connection.
         */
        @Suppress("TooGenericExceptionCaught") // an entry step may refuse in ANY shape; it is rethrown as it is
        private fun takeSteps() {
            val taken = ArrayDeque<() -> Unit>()
            for ((step, undo) in steps()) {
                taken.addLast(undo)
                try {
                    step()
                } catch (entryFailure: Exception) {
                    unwind(taken, entryFailure)
                    throw entryFailure
                }
            }
        }

        /** Runs [taken] newest first; every undo failure rides on [entryFailure], and any one disposes the connection. */
        @Suppress("TooGenericExceptionCaught") // an undo may refuse in ANY shape; it rides on the entry failure
        private fun unwind(
            taken: ArrayDeque<() -> Unit>,
            entryFailure: Exception,
        ) {
            var undoFailed = false
            while (taken.isNotEmpty()) {
                try {
                    taken.removeLast()()
                } catch (e: Exception) {
                    undoFailed = true
                    entryFailure.addSuppressed(e)
                }
            }
            if (undoFailed) dispose(entryFailure)
        }
    }
}
