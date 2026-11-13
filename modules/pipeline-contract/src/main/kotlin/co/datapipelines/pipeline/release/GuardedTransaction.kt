package co.datapipelines.pipeline.release

import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionOperations
import java.util.UUID

/**
 * A pin-affecting write in one metadata transaction with the workspace writer guard acquired FIRST (record §4.3): the
 * shape every programmatic writer the guard enrols uses — imports, transfers, promotion receives. Inside an outer
 * transaction (a promotion receive importing its entries) the default propagation joins it, and re-acquiring the
 * guard there is a no-op for the holder.
 */
object GuardedTransaction {
    /** For directly constructed services in unit tests: runs the block with no transaction, as they always did. */
    val DIRECT: TransactionOperations =
        object : TransactionOperations {
            override fun <T : Any?> execute(action: TransactionCallback<T>): T? = action.doInTransaction(SimpleTransactionStatus())
        }

    /** Runs [block] in [transactions] after [guard] admitted [workspaceId] for [family]. */
    fun <T> run(
        transactions: TransactionOperations,
        guard: ReleaseMutationGuard,
        workspaceId: UUID,
        family: ReleaseFamily,
        block: () -> T,
    ): T {
        // A holder, not a cast: `execute` answers T? and T may itself be nullable.
        var result: Result<T>? = null
        transactions.executeWithoutResult {
            guard.acquire(workspaceId, family)
            result = Result.success(block())
        }
        return requireNotNull(result) { "the transaction callback did not run" }.getOrThrow()
    }
}
