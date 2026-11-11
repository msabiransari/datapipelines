package co.datapipelines.typesystem.guard

import co.datapipelines.typesystem.DatapipelinesException

/**
 * The one refusal an exhausted writer-guard wait answers, whatever the family (G0 refusal contract, owner ruling
 * 2026-11-08 R1-2 option 1): the release message verbatim, `details` exactly `{lock_wait_seconds}` — never a lock name,
 * SQL or another workspace's identity — and nothing written. [cause] is the `55P03` exception when Postgres timed the
 * statement out, and null when the cumulative deadline had already passed before another statement was issued.
 */
object WriterBusy {
    const val MESSAGE = "Another change in this workspace is in progress. Nothing was written; try again in a moment."
    const val DETAIL_LOCK_WAIT_SECONDS = "lock_wait_seconds"

    fun refusal(
        code: String,
        lockWaitSeconds: Int,
        cause: Throwable? = null,
    ): DatapipelinesException =
        DatapipelinesException(
            code = code,
            message = MESSAGE,
            details = mapOf(DETAIL_LOCK_WAIT_SECONDS to lockWaitSeconds),
            cause = cause,
        )
}

/**
 * Thrown inside a guarded transaction when re-resolution under protection found a different key set than the one
 * planned: the transaction rolls back (transaction-scoped advisory locks release with it) and [Replanning] retries with
 * the new set inside the ORIGINAL deadline — never acquiring an earlier class after a later one.
 */
class KeySetChanged : RuntimeException("the guarded key set changed under protection; replanning") {
    // A control-flow signal, not a diagnostic: no stack trace to fill.
    override fun fillInStackTrace(): Throwable = this
}

/** The release/replan loop: one cumulative deadline across every attempt; the deadline, never a count, ends it. */
object Replanning {
    fun <T> run(
        guard: MetadataWriterGuard,
        attempt: () -> T,
    ): T =
        guard.withinOneDeadline {
            var result: T
            while (true) {
                try {
                    result = attempt()
                    break
                } catch (_: KeySetChanged) {
                    // Rolled back by the attempt's own transaction; the next attempt replans under the same deadline,
                    // and an exhausted one refuses before issuing another acquisition statement.
                }
            }
            result
        }
}
