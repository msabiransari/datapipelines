package co.datapipelines.executor

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Per-user and instance-wide execution-slot admission (dag-executor.md §5.1 step 2, §5.3).
 *
 * One slot per **execution**, not per node (§12.1): it is taken before any work starts and held
 * until the execution finishes, so a pipeline can never run out of slots halfway through.
 *
 * The instance-wide ceiling is **per JVM** (050/R2): `datapipelines.executor
 * .max-concurrent-executions-per-instance` bounds THIS instance; N replicas admit N × it in
 * total, and operators size accordingly (deployment.md §6.2). No cross-instance semaphore
 * exists by owner ruling — rejected with the renaming.
 *
 * Admission is **reject, not queue**: a request over the limit fails immediately with
 * `pipeline.execution.concurrency_limit`. Waiting for a slot would turn a limit into a latency
 * cliff on a synchronous SSE call whose client is holding a connection open.
 *
 * ## Why per-user counters are a map of ints and not a map of semaphores
 *
 * A `Map<userId, Semaphore>` leaks an entry per user forever, which the §14 resource-leak test
 * exists to catch. `ConcurrentHashMap.compute` gives atomic check-and-increment *and* removes
 * the entry when the count returns to zero, so an idle user leaves nothing behind.
 */
class ExecutionSlots(
    private val maxPerUser: Int,
    private val maxPerInstance: Int,
) {
    private val instanceWide = AtomicInteger()
    private val perUser = ConcurrentHashMap<UUID, Int>()

    /** Live executions across all users on THIS instance — observability and the §15.3 gauge. */
    val inFlight: Int get() = instanceWide.get()

    /** Live executions for [userId]; zero when the user has none (no entry is retained). */
    fun inFlightFor(userId: UUID): Int = perUser[userId] ?: 0

    /** Users with at least one live execution — the leak assertion surface. */
    val trackedUsers: Int get() = perUser.size

    /**
     * Runs [body] holding one instance-wide and one per-user slot.
     *
     * @throws PipelineConcurrencyLimitException when either limit is already reached; the
     *   instance-wide slot is released before throwing, so a per-user rejection never burns an
     *   instance-wide one.
     */
    suspend fun <T> withSlot(
        userId: UUID,
        body: suspend () -> T,
    ): T {
        acquireInstanceWide()
        try {
            acquirePerUser(userId)
        } catch (e: PipelineConcurrencyLimitException) {
            instanceWide.decrementAndGet()
            throw e
        }
        try {
            return body()
        } finally {
            releasePerUser(userId)
            instanceWide.decrementAndGet()
        }
    }

    private fun acquireInstanceWide() {
        while (true) {
            val current = instanceWide.get()
            if (current >= maxPerInstance) throw PipelineConcurrencyLimitException(LimitScope.GLOBAL, maxPerInstance)
            if (instanceWide.compareAndSet(current, current + 1)) return
        }
    }

    /**
     * `compute` is the atomic unit here: the check and the increment happen under the map's own
     * per-bin lock, so two concurrent requests for the same user cannot both see `maxPerUser - 1`.
     */
    private fun acquirePerUser(userId: UUID) {
        var rejected = false
        perUser.compute(userId) { _, current ->
            val held = current ?: 0
            if (held >= maxPerUser) {
                rejected = true
                current
            } else {
                held + 1
            }
        }
        if (rejected) throw PipelineConcurrencyLimitException(LimitScope.PER_USER, maxPerUser)
    }

    /** Returning null from `compute` removes the entry — this is what keeps the map from growing. */
    private fun releasePerUser(userId: UUID) {
        perUser.compute(userId) { _, current ->
            val held = current ?: 0
            if (held <= 1) null else held - 1
        }
    }
}
