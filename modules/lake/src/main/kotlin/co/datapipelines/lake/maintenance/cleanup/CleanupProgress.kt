package co.datapipelines.lake.maintenance.cleanup

import co.datapipelines.lake.maintenance.Guarded
import co.datapipelines.lake.maintenance.MaintenanceOutcome
import co.datapipelines.lake.maintenance.MaintenanceRun
import co.datapipelines.lake.maintenance.PartResult
import co.datapipelines.lake.maintenance.remove.MarkerFate
import co.datapipelines.lake.maintenance.remove.RemovalResult

/** The figures of one pass so far: what a result carries, or what `lake.cleanup.stopped` logs when the part throws. */
internal class CleanupProgress {
    var figures = CleanupFigures()
        private set

    fun update(change: (CleanupFigures) -> CleanupFigures) {
        figures = change(figures)
    }

    /**
     * The remover's counts (D3): a refused pass listed nothing and leaves the figures as they are. 958 writes the pass's
     * own clock marker (`dp/clock/<run-id>`, an empty object) before its listing and, once removed, counts it among the
     * deleted keys; it is the pass's bookkeeping, not a file of the table, so [CleanupFigures.deleted] leaves it out and a
     * pass that deleted only its own marker deleted nothing (D2a). The bytes need no correction: the marker is empty.
     */
    fun record(result: RemovalResult) {
        if (result !is RemovalResult.Completed) return
        val ownMarker = if (result.marker == MarkerFate.Removed) 1 else 0
        update {
            it.copy(
                listed = result.listed,
                deleted = (result.deleted - ownMarker).coerceAtLeast(0),
                bytesDeleted = result.bytesDeleted,
                left = result.left,
                hintsDeferred = result.deferredHints,
                purge = result.purge,
            )
        }
    }

    fun result(outcome: MaintenanceOutcome): PartResult = PartResult(outcome, figures.figures(), details = figures.details())

    /**
     * The part's answer for the remover's [verdict] (D2a): complete → `done` when [didWork], else `nothing to do`; the
     * lease lost → `deadline`; anything else throws, so the run ends `failed` with the code.
     */
    fun answer(
        verdict: RemovalVerdict,
        didWork: Boolean,
    ): PartResult =
        when (verdict) {
            RemovalVerdict.Complete -> result(if (didWork) MaintenanceOutcome.DONE else MaintenanceOutcome.NOTHING_TO_DO)
            RemovalVerdict.Deadline -> result(MaintenanceOutcome.DEADLINE)
            is RemovalVerdict.Fail -> throw verdict.failure
        }
}

/**
 * Runs [block] under the run's table lease (D2: one lease, held to the end; 957's `underLease` releases it in every
 * case). A refused acquire is the part's `busy` or `deadline` with nothing done. A throw after the acquire logs the
 * figures so far in `lake.cleanup.stopped` and propagates unchanged.
 */
internal fun MaintenanceRun.cleanupUnderLease(
    part: String,
    progress: CleanupProgress,
    block: () -> PartResult,
): PartResult {
    val guarded =
        underLease {
            @Suppress("TooGenericExceptionCaught") // logged and rethrown unchanged: the worker ends the run `failed`
            try {
                block()
            } catch (failure: Exception) {
                CleanupEvents.stopped(part, table.id, runId, failure, progress.figures)
                throw failure
            }
        }
    return when (guarded) {
        is Guarded.Done -> guarded.value
        is Guarded.Refused -> progress.result(guarded.outcome)
    }
}
