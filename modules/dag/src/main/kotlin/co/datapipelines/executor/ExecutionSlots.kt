package co.datapipelines.executor

import kotlinx.coroutines.delay
import java.time.Duration
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
 * cliff on a synchronous SSE call whose client is holding a connection open. The one exception is
 * [acquireInstanceOnly] (#10 L2): a dashboard refresh reserves all its executions' slots together and MAY wait,
 * boundedly (`datapipelines.dashboards.admission.max-wait-seconds`), because it has not yet opened its stream
 * and refusing it costs the viewer a whole refresh.
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

    private companion object {
        /** How often a bounded wait re-tries: a slot frees on an execution's end, so tens of milliseconds is prompt and cheap. */
        const val POLL_INTERVAL_MILLIS = 25L
    }

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

    /**
     * **All-or-none, instance-only, bounded-wait** admission for a dashboard refresh (#10 L2, spec §9.4, §18
     * premise 2): reserves [count] INSTANCE-wide slots atomically and takes NO per-user slot — a refresh runs
     * as the viewer (D50) and must not starve the viewer's own runs, which `acquire(userId, Int.MAX)` would
     * still have counted against. If the instance does not have [count] free it waits, polling, for at most
     * [maxWait] and then answers null with NOTHING held (all or none: a partial fan-out under saturation is
     * the defect this exists to prevent). A [count] above the instance ceiling can never be admitted and
     * answers null at once. Never queues past [maxWait]; a zero wait is a single attempt.
     *
     * The reservation hands out one [SlotLease] per execution ([SlotReservation.next]) — each execution
     * releases its own slot at its end, exactly as a scheduled run's lease does — and its [SlotReservation.close]
     * releases the slots never handed out.
     */
    suspend fun acquireInstanceOnly(
        count: Int,
        maxWait: Duration,
    ): SlotReservation? {
        require(count >= 1) { "count must be positive, was $count" }
        if (count > maxPerInstance) return null
        val deadline = System.nanoTime() + maxWait.toNanos()
        while (true) {
            if (tryReserveInstanceWide(count)) return SlotReservation(count) { instanceWide.decrementAndGet() }
            val remainingNanos = deadline - System.nanoTime()
            if (remainingNanos <= 0) return null
            delay(minOf(POLL_INTERVAL_MILLIS, Duration.ofNanos(remainingNanos).toMillis().coerceAtLeast(1)))
        }
    }

    /** One CAS loop: `+count` iff it stays within the ceiling — the atomic unit that makes the reservation all-or-none. */
    private fun tryReserveInstanceWide(count: Int): Boolean {
        while (true) {
            val current = instanceWide.get()
            if (current + count > maxPerInstance) return false
            if (instanceWide.compareAndSet(current, current + count)) return true
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

/**
 * [count] instance-wide slots reserved together by [ExecutionSlots.acquireInstanceOnly] (#10 L2), handed out one
 * [SlotLease] at a time. Every reserved slot is released exactly once: by its lease's [SlotLease.close] when it
 * was handed out, by [close] when it was not. [close] is idempotent and never touches a lease already handed out
 * (that execution releases its own slot at its end), so a refresh that fails halfway through its fan-out closes
 * the reservation in a `finally` and leaks nothing.
 */
class SlotReservation internal constructor(
    count: Int,
    private val releaseOne: () -> Unit,
) : AutoCloseable {
    private val unhandedOut = AtomicInteger(count)

    /** Slots reserved and not yet handed out — the leak assertion surface. */
    val remaining: Int get() = unhandedOut.get()

    /**
     * One reserved slot as a lease for one execution.
     *
     * @throws IllegalStateException every slot was already handed out, or the reservation is closed.
     */
    fun next(): SlotLease {
        while (true) {
            val current = unhandedOut.get()
            check(current > 0) { "no reserved slot left to hand out" }
            if (unhandedOut.compareAndSet(current, current - 1)) return SlotLease(releaseOne)
        }
    }

    /** Releases the slots never handed out. */
    override fun close() {
        repeat(unhandedOut.getAndSet(0)) { releaseOne() }
    }
}
