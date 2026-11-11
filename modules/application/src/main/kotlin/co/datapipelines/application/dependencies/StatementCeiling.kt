package co.datapipelines.application.dependencies

import org.springframework.jdbc.core.JdbcOperations
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.SQLException
import java.time.Duration

/**
 * Applies one statement bound (record §25.4: `min(statement-timeout-seconds, remaining deadline)`, the provider context's
 * `statementBound()`) to the metadata statements a release source issues (#786 R3). The release providers run every
 * source read inside [within]; a statement the database cancels at the bound surfaces as [StatementCancelled], which the
 * provider turns into the walk's `DeadlineExceeded` and the walk into the root family's `release.metadata_unavailable`.
 */
interface StatementCeiling {
    /** Runs [read] with every statement it issues bounded by [bound] (positive); a statement cancelled at it is [StatementCancelled]. */
    fun <T> within(
        bound: Duration,
        read: () -> T,
    ): T

    companion object {
        /** No ceiling: for sources that issue no statement (in-memory sources, unit tests). Production never wires it. */
        val NONE: StatementCeiling =
            object : StatementCeiling {
                override fun <T> within(
                    bound: Duration,
                    read: () -> T,
                ): T = read()

                override fun toString(): String = "StatementCeiling.NONE"
            }
    }
}

/** A metadata statement was cancelled at its bound (PostgreSQL SQLSTATE 57014) — retryable, never a graph fact. */
class StatementCancelled(
    cause: Throwable,
) : RuntimeException("a release metadata statement was cancelled at its statement bound", cause)

/**
 * The PostgreSQL [StatementCeiling]: `set_config('statement_timeout', <bound>, true)` — `SET LOCAL`, so it lives only in
 * the transaction the walk reads in (the planning transaction, or the guarded caller's) and binds every statement the
 * read issues on that connection, lock waits included. The previous value is put back after a read that returns, so the
 * guarded caller's own statements after the walk keep their setting.
 *
 * After a failed read the restore is attempted and its own failure suppressed onto the read's: a cancelled statement
 * aborts the transaction (the restore then meets SQLSTATE 25P02) — or, inside the walk's speculative savepoint, the
 * savepoint rollback reverts the setting with the read. Either way the read's failure is what propagates, and an
 * aborted transaction is rolled back by its owner, so nothing of a cancelled read is ever committed.
 *
 * Only inside a transaction: outside one `SET LOCAL` would end with its own statement and bound nothing, so [within]
 * refuses (IllegalStateException) before the read.
 */
class PostgresStatementCeiling(
    private val jdbc: JdbcOperations,
) : StatementCeiling {
    @Suppress("TooGenericExceptionCaught") // whatever the read throws, the setting is put back before it propagates
    override fun <T> within(
        bound: Duration,
        read: () -> T,
    ): T {
        require(bound > Duration.ZERO) { "a statement bound is positive, got $bound" }
        check(TransactionSynchronizationManager.isActualTransactionActive()) {
            "a release statement bound is SET LOCAL, so it applies only inside the walk's transaction"
        }
        val previous = checkNotNull(jdbc.queryForObject(CURRENT_SQL, String::class.java)) { "statement_timeout has a value" }
        apply("${millis(bound)}ms")
        val value =
            try {
                read()
            } catch (failure: RuntimeException) {
                restoreAfter(failure, previous)
                throw if (cancelled(failure)) StatementCancelled(failure) else failure
            }
        apply(previous)
        return value
    }

    private fun apply(setting: String) {
        jdbc.queryForObject(SET_SQL, String::class.java, setting)
    }

    @Suppress("TooGenericExceptionCaught") // any restore failure rides on the read's, which is what propagates
    private fun restoreAfter(
        failure: RuntimeException,
        previous: String,
    ) {
        try {
            apply(previous)
        } catch (restore: RuntimeException) {
            failure.addSuppressed(restore)
        }
    }

    override fun toString(): String = "PostgresStatementCeiling"

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
