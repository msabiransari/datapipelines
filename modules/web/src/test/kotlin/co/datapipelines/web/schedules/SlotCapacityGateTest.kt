package co.datapipelines.web.schedules

import co.datapipelines.executor.ExecutionSlots
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The scheduler's capacity port over `dag`'s slots (#9 R4): the budget is `max-concurrent-runs`
 * as the system identity's per-user bound — separate from a person's, beneath the instance-wide
 * ceiling — and a refusal is `null`, holding nothing. The gauge is read off a REAL registry
 * (MISTAKES.md: a "must record" contract is asserted by its effect, never by a strict mock).
 */
class SlotCapacityGateTest {
    private val systemActor = UUID.fromString("5a570000-0000-0000-0000-000000000001")

    @Test
    fun `the scheduler's own budget bounds it, not the per-user limit a person has`() {
        val slots = ExecutionSlots(maxPerUser = 1, maxPerInstance = 10)
        val gate = SlotCapacityGate(slots, { systemActor }, maxConcurrentRuns = 3)

        val leases = (1..3).map { gate.tryAcquire().shouldNotBeNull() }
        gate.tryAcquire().shouldBeNull()

        slots.inFlightFor(systemActor) shouldBe 3
        leases.forEach { it.close() }
        slots.inFlight shouldBe 0
    }

    @Test
    fun `the instance-wide ceiling still applies, and a refusal takes nothing`() {
        val slots = ExecutionSlots(maxPerUser = 5, maxPerInstance = 1)
        val person = slots.acquire(UUID.randomUUID())
        val gate = SlotCapacityGate(slots, { systemActor }, maxConcurrentRuns = 4)

        gate.tryAcquire().shouldBeNull()

        slots.inFlight shouldBe 1
        slots.inFlightFor(systemActor) shouldBe 0
        person.close()
    }

    @Test
    fun `the lease carries the dag slot the execution adopts, and closing either releases once`() {
        val slots = ExecutionSlots(maxPerUser = 1, maxPerInstance = 10)
        val lease = SlotCapacityGate(slots, { systemActor }, maxConcurrentRuns = 2).tryAcquire() as SlotCapacityLease

        lease.slot.close()
        lease.close()

        lease.slot.isReleased shouldBe true
        slots.inFlight shouldBe 0
    }

    @Test
    fun `runs_in_flight reports the system identity's held slots`() {
        val registry = SimpleMeterRegistry()
        val slots = ExecutionSlots(maxPerUser = 5, maxPerInstance = 10)
        val gate = SlotCapacityGate(slots, { systemActor }, maxConcurrentRuns = 4, registry = registry)
        val personal = slots.acquire(UUID.randomUUID())

        val held = listOf(gate.tryAcquire()!!, gate.tryAcquire()!!)

        registry.get(SlotCapacityGate.IN_FLIGHT).gauge().value() shouldBe 2.0
        held.first().close()
        registry.get(SlotCapacityGate.IN_FLIGHT).gauge().value() shouldBe 1.0
        held.last().close()
        personal.close()
    }
}
