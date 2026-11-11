package co.datapipelines.application.dependencies

import org.springframework.jdbc.core.ConnectionCallback
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.SQLException
import java.sql.Statement
import java.time.Duration
import javax.sql.DataSource

/**
 * Applies the statement bound of record §25.4 — `min(statement-timeout-seconds, remaining deadline)`, the provider
 * context's `statementBound()` — to the metadata statements a release source issues (#786 R3). The release providers run
 * every source read inside [within]; the bound is a SUPPLIER, read again immediately before EACH statement the read issues
 * (#786 R3, landing review r4: a value read once for a multi-statement source keeps the budget measured before its first
 * statement). A statement the database cancels at its bound surfaces as [StatementCancelled]; a statement whose bound is
 * already spent is never issued ([StatementBudgetExhausted]). The provider turns both into the walk's `DeadlineExceeded`,
 * and the walk into the root family's `release.metadata_unavailable`.
 */
interface StatementCeiling {
    /**
     * Runs [read] with every statement it issues bounded by [bound], read when that statement starts. [bound] is positive
     * when [within] is entered (the provider refuses a spent bound before calling it); null means unbounded.
     */
    fun <T> within(
        bound: () -> Duration?,
        read: () -> T,
    ): T

    companion object {
        /** No ceiling: for sources that issue no statement (in-memory sources, unit tests). Production never wires it. */
        val NONE: StatementCeiling =
            object : StatementCeiling {
                override fun <T> within(
                    bound: () -> Duration?,
                    read: () -> T,
                ): T = read()

                override fun toString(): String = "StatementCeiling.NONE"
            }
    }
}

/** A metadata statement reached its statement bound — retryable, never a graph fact; the provider's `DeadlineExceeded`. */
sealed class StatementBoundReached(
    message: String,
    cause: Throwable?,
) : RuntimeException(message, cause)

/** A metadata statement was cancelled at its bound (PostgreSQL SQLSTATE 57014). */
class StatementCancelled(
    cause: Throwable,
) : StatementBoundReached("a release metadata statement was cancelled at its statement bound", cause)

/**
 * A metadata statement was about to start with its bound already spent, so it was not issued (#786 R3): to PostgreSQL a
 * `0` timeout means "none", so a spent budget is a refusal before the statement, never an unbounded one.
 */
class StatementBudgetExhausted : StatementBoundReached("a release metadata statement was not issued: its statement bound is spent", null)

/**
 * The PostgreSQL [StatementCeiling]. Every source a release descriptor wraps builds its repositories over [jdbc] — this
 * ceiling's own template over the metadata [DataSource] — and inside [within] that template, IMMEDIATELY BEFORE EACH
 * statement it executes, reads the bound supplier and runs `set_config('statement_timeout', <bound>, true)` (`SET LOCAL`)
 * on the statement's own connection: a later statement of a multi-statement source gets the budget left at ITS start. A
 * spent bound throws [StatementBudgetExhausted] instead of executing; a bound below one millisecond is rounded up to one
 * ([millis]), never to PostgreSQL's unbounded `0`. Outside [within] the template behaves as a plain one.
 *
 * On entry [within] also sets the entry bound once, so a statement on the walk's connection that does not go through
 * [jdbc] is still bounded by the budget measured when the read began (round 4's fixed ceiling, kept as the floor). A raw
 * `ConnectionCallback` on [jdbc] inside [within] is refused: it would issue statements the per-statement hook never sees.
 *
 * `SET LOCAL` lives only in the transaction the walk reads in (the planning transaction, or the guarded caller's). The
 * value in force before [within] is put back after a read that returns, so the guarded caller's own statements after the
 * walk keep their setting. After a failed read the restore is attempted and its own failure suppressed onto the read's:
 * a cancelled statement aborts the transaction (the restore then meets SQLSTATE 25P02) — or, inside the walk's
 * speculative savepoint, the savepoint rollback reverts the setting with the read. Either way the read's failure is what
 * propagates, and an aborted transaction is rolled back by its owner, so nothing of a cancelled read is ever committed.
 *
 * Only inside a transaction that holds a connection of THIS ceiling's DataSource: outside one `SET LOCAL` would end with
 * its own statement and bound nothing (and on another DataSource it would land on another connection), so [within]
 * refuses (IllegalStateException) before the read.
 */
class PostgresStatementCeiling(
    private val source: DataSource,
) : StatementCeiling {
    private val active = ThreadLocal<() -> Duration?>()
    private val template = BoundedJdbcTemplate(source)

    /** The template every release source's repositories issue their statements through (each one bounded inside [within]). */
    val jdbc: NamedParameterJdbcTemplate = NamedParameterJdbcTemplate(template)

    @Suppress("TooGenericExceptionCaught") // whatever the read throws, the setting is put back before it propagates
    override fun <T> within(
        bound: () -> Duration?,
        read: () -> T,
    ): T {
        val entry = bound() ?: return read()
        require(entry > Duration.ZERO) { "a statement bound is positive when the read starts, got $entry" }
        check(TransactionSynchronizationManager.isActualTransactionActive() && TransactionSynchronizationManager.hasResource(source)) {
            "a release statement bound is SET LOCAL, so it applies only inside the walk's transaction on the ceiling's DataSource"
        }
        check(active.get() == null) { "a release statement bound is already in force on this thread" }
        val previous = checkNotNull(template.queryForObject(CURRENT_SQL, String::class.java)) { "statement_timeout has a value" }
        template.queryForObject(SET_SQL, String::class.java, "${millis(entry)}ms")
        val value =
            try {
                eachStatementBounded(bound, read)
            } catch (failure: RuntimeException) {
                restoreAfter(failure, previous)
                throw if (cancelled(failure)) StatementCancelled(failure) else failure
            }
        template.queryForObject(SET_SQL, String::class.java, previous)
        return value
    }

    /** Runs [read] with [bound] as this thread's live per-statement bound; the hook is off again however [read] ends. */
    private fun <T> eachStatementBounded(
        bound: () -> Duration?,
        read: () -> T,
    ): T {
        active.set(bound)
        try {
            return read()
        } finally {
            active.remove()
        }
    }

    @Suppress("TooGenericExceptionCaught") // any restore failure rides on the read's, which is what propagates
    private fun restoreAfter(
        failure: RuntimeException,
        previous: String,
    ) {
        try {
            template.queryForObject(SET_SQL, String::class.java, previous)
        } catch (restore: RuntimeException) {
            failure.addSuppressed(restore)
        }
    }

    override fun toString(): String = "PostgresStatementCeiling"

    /**
     * The per-statement hook: [applyStatementSettings] runs for every statement `JdbcTemplate` creates, after it is
     * prepared and before it executes. Inside [within] it reads the live bound and sets it on that statement's connection.
     */
    private inner class BoundedJdbcTemplate(
        dataSource: DataSource,
    ) : JdbcTemplate(dataSource) {
        override fun applyStatementSettings(stmt: Statement) {
            super.applyStatementSettings(stmt)
            val supplier = active.get() ?: return
            val bound = supplier() ?: return
            if (bound <= Duration.ZERO) throw StatementBudgetExhausted()
            stmt.connection.prepareStatement(SET_SQL).use { set ->
                set.setString(1, "${millis(bound)}ms")
                set.executeQuery().close()
            }
        }

        override fun <T : Any?> execute(action: ConnectionCallback<T>): T? {
            check(active.get() == null) { "a raw connection callback bypasses the per-statement release bound" }
            return super.execute(action)
        }
    }

    companion object {
        /** PostgreSQL `query_canceled` — what `statement_timeout` raises. */
        const val QUERY_CANCELED = "57014"

        private const val CURRENT_SQL = "SELECT current_setting('statement_timeout')"
        private const val SET_SQL = "SELECT set_config('statement_timeout', ?, true)"

        /** Whole milliseconds, rounded UP and at least one: `0` would mean "no timeout" to PostgreSQL. */
        fun millis(bound: Duration): Long {
            val whole = bound.toMillis()
            return (if (bound > Duration.ofMillis(whole)) whole + 1 else whole).coerceAtLeast(1)
        }

        /** True when [failure]'s cause chain holds a JDBC exception with SQLSTATE [QUERY_CANCELED]. */
        fun cancelled(failure: Throwable): Boolean =
            generateSequence(failure) { it.cause.takeIf { cause -> cause !== it } }
                .filterIsInstance<SQLException>()
                .any { it.sqlState == QUERY_CANCELED }
    }
}
