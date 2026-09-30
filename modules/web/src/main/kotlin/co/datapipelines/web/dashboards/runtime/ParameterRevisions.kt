package co.datapipelines.web.dashboards.runtime

import java.util.UUID

/**
 * The server-assigned `parameter_revision` (spec §8.2): monotonic PER CLIENT INSTANCE, so a client can tell which of its
 * overlapping parameter evaluations is the newest. In memory and bounded — a least-recently-used cap on tracked
 * instances — because the number is a hint the client compares, not a fact anything persists: a restart begins again at
 * 1 and a client that sees a lower number than it holds treats the instance as new.
 */
internal class ParameterRevisions(
    private val maxInstances: Int = MAX_INSTANCES,
) {
    private val counters =
        object : LinkedHashMap<UUID, Int>(INITIAL_CAPACITY, LOAD_FACTOR, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<UUID, Int>?): Boolean = size > maxInstances
        }

    /** The next revision for [instanceId], starting at 1. */
    @Synchronized
    fun next(instanceId: UUID): Int {
        val next = (counters[instanceId] ?: 0) + 1
        counters[instanceId] = next
        return next
    }

    @Synchronized
    fun tracked(): Int = counters.size

    private companion object {
        const val MAX_INSTANCES = 4_096
        const val INITIAL_CAPACITY = 16
        const val LOAD_FACTOR = 0.75f
    }
}
