package co.datapipelines.executor

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
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
     * Runs [body] holding one instance-wide and one per-user slot — [lease]'s, when the caller
     * acquired them first ([acquire]), else a pair taken here. Either way the pair is released
     * when [body] ends, however it ends.
     *
     * @throws PipelineConcurrencyLimitException when either limit is already reached; the
     *   instance-wide slot is released before throwing, so a per-user rejection never burns an
     *   instance-wide one.
     */
    suspend fun <T> withSlot(
        userId: UUID,
        lease: SlotLease? = null,
        body: suspend () -> T,
    ): T {
        val held = lease ?: acquire(userId)
        try {
            return body()
        } finally {
            held.close()
        }
    }

    /**
     * **Acquire-before-claim** (#9 R4, scheduler design revision §2.1, A6): takes one instance-wide
     * and one per-user slot NOW and hands them back as a [SlotLease], so a caller can learn it has
     * capacity before it commits to anything — the scheduler takes capacity before it claims a
     * run's start, and a refusal is then a definitive "never started". The lease is passed to the
     * execution in `ExecuteRequest.slotLease`, which releases it at the end ([withSlot]).
     *
     * [perUserLimit] replaces the per-user bound for this acquisition: the scheduler's
     * `max-concurrent-runs` budget is exactly the system identity's per-user bound (R4 — its own
     * budget, separate from any person's), because every scheduled run executes as that identity.
     * The instance-wide ceiling applies unchanged.
     *
     * @throws PipelineConcurrencyLimitException as [withSlot]; nothing is held after a throw.
     */
    fun acquire(
        userId: UUID,
        perUserLimit: Int = maxPerUser,
    ): SlotLease {
        acquireInstanceWide()
        try {
            acquirePerUser(userId, perUserLimit)
        } catch (e: PipelineConcurrencyLimitException) {
            instanceWide.decrementAndGet()
            throw e
        }
        return SlotLease {
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
    private fun acquirePerUser(
        userId: UUID,
        limit: Int,
    ) {
        var rejected = false
        perUser.compute(userId) { _, current ->
            val held = current ?: 0
            if (held >= limit) {
                rejected = true
                current
            } else {
                held + 1
            }
        }
        if (rejected) throw PipelineConcurrencyLimitException(LimitScope.PER_USER, limit)
    }

    /** Returning null from `compute` removes the entry — this is what keeps the map from growing. */
    private fun releasePerUser(userId: UUID) {
        perUser.compute(userId) { _, current ->
            val held = current ?: 0
            if (held <= 1) null else held - 1
        }
    }
}

/**
 * One held slot pair from [ExecutionSlots.acquire] (#9 R4). [close] releases it and is idempotent:
 * the execution that took it over releases it at its end, and a caller that never handed it over
 * closes it too — whichever comes first releases, the second is a no-op. A lease never released
 * is a leaked slot, which `ExecutionSlotsLeaseTest` asserts cannot happen on any path.
 */
class SlotLease internal constructor(
    private val release: () -> Unit,
) : AutoCloseable {
    private val released = AtomicBoolean(false)

    /** True once released. */
    val isReleased: Boolean get() = released.get()

    override fun close() {
        if (released.compareAndSet(false, true)) release()
    }
}
