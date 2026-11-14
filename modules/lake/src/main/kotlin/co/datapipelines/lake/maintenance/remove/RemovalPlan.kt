package co.datapipelines.lake.maintenance.remove

import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * The pure half of a pass: which listed keys are candidates and why each other key is left (D4, D5, D8), and what each
 * queued hint is (D4). Built only from what the pass itself read after the call began: its listing, its walk's reached
 * keys and one reading of the catalog clock. Nothing here touches the store.
 */
internal data class RemovalPlan(
    val candidates: List<ListedObject>,
    val left: Map<String, LeftReason>,
    val hints: Map<HintReason, Int>,
    /** Keys under `dp/` that are neither a recipe nor a clock marker, in listing order (D8: logged, never deleted). */
    val otherDpKeys: List<String>,
) {
    companion object {
        /**
         * [ownMarker] is the exact key this pass PUT: it is neither a candidate nor left here; the pass removes it last.
         * A key is old when `now − LastModified` is MORE than [grace] (record §8a (iii)).
         */
        @Suppress("LongParameterList") // the pure inputs of one decision, each named at the one call site
        fun of(
            target: RemovalTarget,
            listing: List<ListedObject>,
            reached: Set<String>,
            now: Instant,
            grace: Duration,
            ownMarker: String,
            runStates: RunStates,
            hints: Collection<String>,
        ): RemovalPlan {
            val candidates = ArrayList<ListedObject>()
            val left = LinkedHashMap<String, LeftReason>()
            val otherDp = ArrayList<String>()

            fun old(o: ListedObject) = Duration.between(o.lastModified, now) > grace
            for (o in listing) {
                if (o.key == ownMarker || !o.key.startsWith(target.keyPrefix)) continue
                val relative = target.relative(o.key)
                val reason =
                    if (relative.startsWith(RemovalTarget.DP_DIR)) {
                        dpReason(relative, old(o), runStates).also { if (it == LeftReason.OTHER_DP_KEY) otherDp += relative }
                    } else {
                        when {
                            o.key in reached -> LeftReason.REACHED
                            !old(o) -> LeftReason.YOUNG
                            else -> null
                        }
                    }
                if (reason == null) candidates += o else left[o.key] = reason
            }
            val byKey = listing.associateBy { it.key }
            val hinted = hints.distinct().mapNotNull { hintReason(target, it, reached, byKey, ::old) }
            return RemovalPlan(candidates, left, hinted.groupingBy { it }.eachCount(), otherDp)
        }

        /** The run id of a clock marker `dp/clock/<uuid>` (nothing after it), or null: then the key is just another `dp/` key. */
        fun markerRun(relative: String): UUID? =
            relative
                .removePrefix(RemovalTarget.CLOCK_DIR)
                .takeIf { '/' !in it }
                ?.let { runCatching { UUID.fromString(it) }.getOrNull()?.takeIf { id -> id.toString() == it } }

        /** A key under `dp/`: a recipe never goes, a clock marker by D5's rule, any other `dp/` key never goes (D8). */
        private fun dpReason(
            relative: String,
            old: Boolean,
            runStates: RunStates,
        ): LeftReason? {
            val run = if (relative.startsWith(RemovalTarget.CLOCK_DIR)) markerRun(relative) else null
            return when {
                relative.startsWith(RemovalTarget.RECIPES_DIR) -> LeftReason.RECIPE
                run != null -> markerReason(old, runStates.isTerminal(run))
                else -> LeftReason.OTHER_DP_KEY
            }
        }

        /** Another run's marker: deleted only when older than grace AND its run is terminal; null = a candidate (D5). */
        private fun markerReason(
            old: Boolean,
            terminal: Boolean?,
        ): LeftReason? =
            when {
                !old -> LeftReason.YOUNG
                terminal == true -> null
                terminal == false -> LeftReason.MARKER_RUN_LIVE
                else -> LeftReason.MARKER_RUN_UNKNOWN
            }

        /** A queued hint passes the same four tests as a listed key; null = it is a candidate already (D4). */
        private fun hintReason(
            target: RemovalTarget,
            hint: String,
            reached: Set<String>,
            listed: Map<String, ListedObject>,
            old: (ListedObject) -> Boolean,
        ): HintReason? {
            val key = target.keyOf(hint) ?: hint.takeIf { "://" !in it }
            val listedObject = key?.let { listed[it] }
            return when {
                key == null || !key.startsWith(target.keyPrefix) -> HintReason.OUTSIDE_LOCATION
                target.relative(key).startsWith(RemovalTarget.DP_DIR) -> HintReason.UNDER_DP
                key in reached -> HintReason.REACHED
                listedObject == null -> HintReason.NOT_LISTED
                !old(listedObject) -> HintReason.YOUNG
                else -> null
            }
        }
    }
}
