package co.datapipelines.web.schedules

import co.datapipelines.executor.ExecutionSlots
import co.datapipelines.executor.PipelineConcurrencyLimitException
import co.datapipelines.executor.SlotLease
import co.datapipelines.scheduler.CapacityGate
import co.datapipelines.scheduler.CapacityLease
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import java.util.UUID

/**
 * The scheduler's capacity port over `dag`'s [ExecutionSlots] (#9 R4, A6): one instance-wide slot
 * and one of the system identity's per-user slots, bounded by `max-concurrent-runs` — the
 * scheduler's OWN budget, separate from every person's, because every scheduled run executes as
 * that one identity. Taken BEFORE the claim; the lease rides `ExecuteRequest.slotLease` into the
 * execution, which releases it when it ends.
 *
 * Registers `datapipelines.scheduler.runs.in_flight` (observability.md §4.1): the scheduled
 * executions holding a slot on this instance right now.
 */
class SlotCapacityGate(
    private val slots: ExecutionSlots,
    private val systemActor: () -> UUID,
    private val maxConcurrentRuns: Int,
    registry: MeterRegistry? = null,
) : CapacityGate {
    init {
        registry?.let {
            Gauge.builder(IN_FLIGHT) { slots.inFlightFor(systemActor()).toDouble() }.register(it)
        }
    }

    override fun tryAcquire(): CapacityLease? =
        try {
            SlotCapacityLease(slots.acquire(systemActor(), maxConcurrentRuns))
        } catch (_: PipelineConcurrencyLimitException) {
            // Instance-wide ceiling or the scheduler's budget: a definitive "not now" (R4) — the
            // worker records a capacity retry and comes back within the lateness window.
            null
        }

    companion object {
        const val IN_FLIGHT = "datapipelines.scheduler.runs.in_flight"
    }
}

/** A [CapacityLease] holding one `dag` [SlotLease]; the adapter hands [slot] to the execution. */
class SlotCapacityLease(
    val slot: SlotLease,
) : CapacityLease {
    override fun close() = slot.close()
}
