package co.datapipelines.staging

/**
 * What a stage reports at its measured boundaries (149; staging.md §4.3). The `CREATE TABLE`
 * takes a lease first and reports it as steps 3–4; then, per batch:
 *
 *  1. [fetchStarted] — about to read the next batch off the SOURCE cursor (or the decoded
 *     sequence), holding no staging connection;
 *  2. [fetchFinished] — the batch is in memory; how many rows it holds;
 *  3. [connectionRequested] — a staging connection is being asked for (admission may suspend);
 *  4. [connectionAcquired] — the lease is held; the `INSERT` follows on it;
 *  5. [batchWritten] — the batch is accepted by tempdb and the lease was returned;
 *  6. [partialTableDropped] — only on FAILURE: the partial table was dropped (the confirmed undo).
 *
 * Every call is made OUTSIDE the pool's metadata lock and, except [connectionAcquired], outside
 * the lease. Implementations must be non-suspending and must return promptly: they run on the
 * drain's own path and a slow observer is a slow drain — the same rule the 108 `onProgress`
 * sink carried. [NONE] is the default; every existing caller is unchanged.
 */
interface StageObserver {
    fun fetchStarted() = Unit

    fun fetchFinished(rows: Int) = Unit

    fun connectionRequested() = Unit

    fun connectionAcquired() = Unit

    /** [rows] accepted in this batch; [rowsSoFar] is the cumulative count — the 108 progress figure. */
    fun batchWritten(
        rows: Int,
        rowsSoFar: Long,
    ) = Unit

    /**
     * The stage FAILED and its partial table was dropped on a fresh lease (the confirmed undo).
     * Not reported when the drop itself failed or timed out — that write's fate is then unknown,
     * and the observer must not be told otherwise.
     */
    fun partialTableDropped() = Unit

    companion object {
        val NONE: StageObserver = object : StageObserver {}
    }
}
