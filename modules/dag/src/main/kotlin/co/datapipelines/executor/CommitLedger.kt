package co.datapipelines.executor

import java.util.UUID

/**
 * Where the root execution persists its commit boundary (pipeline-write-transactions §4: "Persist
 * enough execution-side evidence before calling commit that the stale sweeper cannot label a possibly
 * committed execution ABORTED").
 *
 * Two durable facts per family root, each written by its OWN statement — a metadata transaction is
 * never held across the customer database's commit:
 *
 * 1. [recordAttempt], before the driver's commit is called;
 * 2. [recordConfirmed], after the driver confirmed it.
 *
 * The stale sweep reads the pair (V66): confirmed → SUCCESS, attempted but unconfirmed →
 * OUTCOME_UNKNOWN, neither → ABORTED. An implementation reports failure by returning false or
 * throwing; the caller treats both as "not durable".
 */
interface CommitLedger {
    /** True when the attempt stamp is durable; the caller must NOT commit when it is not. */
    fun recordAttempt(executionId: UUID): Boolean

    /** True when the confirmation stamp is durable. Best effort: the terminal write also settles the row. */
    fun recordConfirmed(executionId: UUID): Boolean

    /** Marks that the confirmed commit's caller result could not be delivered (status stays SUCCESS). */
    fun recordResultUnavailable(executionId: UUID): Boolean

    companion object {
        /** No ledger: tests and runtimes without a metadata store keep the pre-#1035 behaviour. */
        val NONE: CommitLedger =
            object : CommitLedger {
                override fun recordAttempt(executionId: UUID) = true

                override fun recordConfirmed(executionId: UUID) = true

                override fun recordResultUnavailable(executionId: UUID) = true
            }

        /** The production ledger: the two stamps on `pipeline_executions`. */
        fun over(executions: ExecutionRepository): CommitLedger =
            object : CommitLedger {
                override fun recordAttempt(executionId: UUID) = executions.markCommitAttempt(executionId)

                override fun recordConfirmed(executionId: UUID) = executions.markCommitConfirmed(executionId)

                override fun recordResultUnavailable(executionId: UUID) = executions.markResultUnavailable(executionId)
            }
    }
}
