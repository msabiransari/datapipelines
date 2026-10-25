package co.datapipelines.pipeline.release

import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * A constraint race answered from INSIDE a metadata transaction (#517). The writer guard put the draft writes and the
 * imports into one transaction each; a statement that loses a unique-constraint race to an unguarded writer then
 * leaves PostgreSQL's transaction aborted (`25P02`), and the read that names the winner — the `version.conflict`
 * details, an import's classification — would fail instead of answering. Under a savepoint only the losing statement
 * is rolled back, so that read still sees the committed winner (READ COMMITTED takes a fresh snapshot per statement).
 *
 * Outside a transaction (a directly constructed service, autocommit) the statement runs bare, exactly as before.
 */
object RaceSavepoint {
    private const val NAME = "dp_constraint_race"

    /** Runs [block]; on a [DuplicateKeyException] inside a transaction, rolls back to before [block] and rethrows. */
    fun <T> attempt(
        jdbc: JdbcOperations,
        block: () -> T,
    ): T {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) return block()
        jdbc.execute("SAVEPOINT $NAME")
        val result =
            try {
                block()
            } catch (e: DuplicateKeyException) {
                jdbc.execute("ROLLBACK TO SAVEPOINT $NAME")
                throw e
            }
        jdbc.execute("RELEASE SAVEPOINT $NAME")
        return result
    }
}
