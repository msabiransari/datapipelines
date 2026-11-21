package co.datapipelines.executor

/**
 * (#1035) What a confirmed commit can publish of the caller result it held — decided ONCE, after the
 * confirmation and before the terminal event, from the stored result as it is NOW (never from the
 * view resolved before the commit).
 *
 * The three cases are deliberately distinct on every surface: a run with no caller node completes
 * with stats only; a deliverable result is announced (`data_ready`); a result that was prepared but
 * is no longer in the store (or whose announcement could not be built) is SUCCESS with
 * `result_unavailable` — never an empty successful result that reads as a zero-caller run.
 */
internal sealed interface HeldResultDelivery {
    /** The prepared caller result's description, kept as the run's result-history record; null without a caller. */
    val prepared: StoredResultView?

    /** The run has no caller node (or is not a held-result run): nothing to announce. */
    data object NoCaller : HeldResultDelivery {
        override val prepared: StoredResultView? = null
    }

    /** The stored result is still readable: announce it with [view], re-read after the confirmation, at [url]. */
    data class Deliverable(
        override val prepared: StoredResultView,
        val view: StoredResultView,
        val url: String,
    ) : HeldResultDelivery

    /** The result was prepared before the commit but cannot be delivered: SUCCESS, result unavailable. */
    data class Unavailable(
        override val prepared: StoredResultView,
    ) : HeldResultDelivery
}
