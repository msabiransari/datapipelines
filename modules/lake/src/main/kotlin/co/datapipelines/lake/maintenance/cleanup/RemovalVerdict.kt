package co.datapipelines.lake.maintenance.cleanup

import co.datapipelines.lake.maintenance.remove.LeftReason
import co.datapipelines.lake.maintenance.remove.MarkerFate
import co.datapipelines.lake.maintenance.remove.MarkerLeftReason
import co.datapipelines.lake.maintenance.remove.RefusalReason
import co.datapipelines.lake.maintenance.remove.RemovalResult
import co.datapipelines.lake.maintenance.remove.StopReason

/**
 * What a remover's result means for the part (brief 962 D2a, one row each; nothing else is possible). Shared by the
 * cleanup and the purge deletion: only [Complete] lets a part answer `done` or `nothing to do` (and a purge stamp).
 */
internal sealed interface RemovalVerdict {
    /** The remover returned complete: keys left for an ordinary reason (young, reached, a recipe, a live marker) are normal. */
    data object Complete : RemovalVerdict

    /** The caller's lease did not answer, at the remover's start or before a batch or its marker: the part ends `deadline`. */
    data object Deadline : RemovalVerdict

    /** The part throws [failure]: the run ends `failed` with its code. */
    data class Fail(
        val failure: RuntimeException,
    ) : RemovalVerdict

    companion object {
        fun of(result: RemovalResult): RemovalVerdict =
            when (result) {
                is RemovalResult.Refused -> refused(result)
                is RemovalResult.Completed -> completed(result)
            }

        private fun refused(result: RemovalResult.Refused): RemovalVerdict =
            when (result.reason) {
                RefusalReason.LEASE_LOST -> Deadline
                RefusalReason.MODE_NOT_NORMAL -> Fail(CleanupIncomplete(IncompleteReason.MODE))
                RefusalReason.CLOCK_SKEW -> Fail(CleanupClockSkew(result.skew))
                // The part checked the status itself before the lease (D4); a refusal here means the row changed under it.
                RefusalReason.NOT_PURGED -> Fail(IllegalStateException("the table is no longer purged"))
            }

        private fun completed(result: RemovalResult.Completed): RemovalVerdict =
            when {
                result.stopped == StopReason.LEASE_LOST -> Deadline
                result.stopped == StopReason.MODE_CHANGED -> Fail(CleanupIncomplete(IncompleteReason.MODE))
                result.stopped == StopReason.DELETE_FAILED -> Fail(CleanupIncomplete(IncompleteReason.DELETE_FAILED))
                result.left(LeftReason.DELETE_FAILED) > 0 -> Fail(CleanupIncomplete(IncompleteReason.DELETE_FAILED))
                else -> marker(result.marker)
            }

        /**
         * The pass's own marker after a pass that otherwise completed. Left because the lease or the mode said no: the same
         * rows as a stop. Left because its delete was unconfirmed or a check could not be made: a delete that did not
         * happen as planned, so `delete_failed` (the run's terminal row then lets a later pass clear the marker).
         */
        private fun marker(fate: MarkerFate): RemovalVerdict =
            when (fate) {
                MarkerFate.NotWritten, MarkerFate.Removed -> {
                    Complete
                }

                is MarkerFate.Left -> {
                    when (fate.reason) {
                        MarkerLeftReason.LEASE_LOST -> Deadline
                        MarkerLeftReason.MODE_CHANGED -> Fail(CleanupIncomplete(IncompleteReason.MODE))
                        MarkerLeftReason.CHECK_FAILED, MarkerLeftReason.DELETE_UNCONFIRMED ->
                            Fail(CleanupIncomplete(IncompleteReason.DELETE_FAILED))
                    }
                }
            }
    }
}
