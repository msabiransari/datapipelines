package co.datapipelines.application.dependencies

import co.datapipelines.dependencies.ReadIsolation
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * The release planning walk's [ReadIsolation] (#868 R1, round 3): a speculative read runs under a SAVEPOINT in the
 * transaction the walk already reads in — `PROPAGATION_NESTED` on [transactionManager], which
 * `DataSourceTransactionManager` maps to a JDBC savepoint. A failed read rolls back to it and is rethrown (the same
 * exception, as the contract requires); a read that succeeds releases it.
 *
 * Why a savepoint, and why it keeps the walk's guarantees:
 * - A failed PostgreSQL statement aborts the whole transaction (SQLSTATE 25P02 for every later statement); rolling back
 *   to a savepoint taken before the read is the one way to forgive the read inside that transaction.
 * - The rollback undoes only the read's own statements. The planning transaction's REPEATABLE READ snapshot is taken at
 *   its first statement and kept across a savepoint rollback, so every read still sees ONE snapshot (record §7.2 step 2,
 *   §7.3); the guarded caller's write transaction keeps its isolation, its guard and everything it wrote before the walk.
 * - A read-only savepoint is assigned no transaction id, so the reads add no write load.
 *
 * Only inside a transaction: outside one, NESTED would BEGIN a transaction of its own, and a speculative read would
 * leave the walk's snapshot. [isolate] refuses that (IllegalStateException) before the read; the refusal is not the
 * read's failure, so the walk propagates it. A failure to set up or roll back to the savepoint propagates the same way.
 */
class SavepointReadIsolation(
    transactionManager: PlatformTransactionManager,
) : ReadIsolation {
    private val savepoint =
        TransactionTemplate(transactionManager).apply {
            propagationBehavior = TransactionDefinition.PROPAGATION_NESTED
            isReadOnly = true
            setName(SAVEPOINT_NAME)
        }

    override fun <T> isolate(read: () -> T): T {
        check(TransactionSynchronizationManager.isActualTransactionActive()) {
            "a speculative release read is fenced by a savepoint, so it runs only inside a transaction"
        }
        // NESTED inside an active transaction is a savepoint: a RuntimeException from `read` rolls back to it and is
        // rethrown as the same instance; a value returns as is.
        @Suppress("UNCHECKED_CAST")
        return savepoint.execute { read() } as T
    }

    override fun toString(): String = "SavepointReadIsolation"

    companion object {
        /** The savepoint's transaction name, for a reader of the transaction log. */
        const val SAVEPOINT_NAME = "release-planning-speculative-read"
    }
}
