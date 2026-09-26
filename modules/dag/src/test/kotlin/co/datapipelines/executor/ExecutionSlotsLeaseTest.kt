package co.datapipelines.executor

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * **Acquire-before-claim** (#9 R4, dag-executor.md §5.3): [ExecutionSlots.acquire] takes the slot
 * pair NOW as a [SlotLease]; the execution adopts it through [ExecutionSlots.withSlot] and releases
 * it at its end. The properties the scheduler leans on: a refusal holds NOTHING (so "no capacity"
 * is a definitive not-started), the per-user bound can be the scheduler's own budget, adoption
 * never takes a second pair, and release is exactly once however many parties close the lease.
 */
class ExecutionSlotsLeaseTest {
    private val user: UUID = UUID.fromString("5a570000-0000-0000-0000-000000000001")

    @Test
    fun `a lease holds one instance-wide and one per-user slot, and closing it leaves nothing tracked`() {
        val slots = ExecutionSlots(maxPerUser = 2, maxPerInstance = 5)
        val lease = slots.acquire(user)
        slots.inFlight shouldBe 1
        slots.inFlightFor(user) shouldBe 1

        lease.close()

        lease.isReleased shouldBe true
        slots.inFlight shouldBe 0
        slots.trackedUsers shouldBe 0
    }

    @Test
    fun `closing twice releases once - the execution and the caller may both close it`() {
        val slots = ExecutionSlots(maxPerUser = 2, maxPerInstance = 5)
        val first = slots.acquire(user)
        val second = slots.acquire(user)

        first.close()
        first.close()

        slots.inFlight shouldBe 1
        slots.inFlightFor(user) shouldBe 1
        second.close()
        slots.inFlight shouldBe 0
    }

    @Test
    fun `the per-user limit of an acquisition replaces the configured one - the scheduler's budget`() {
        val slots = ExecutionSlots(maxPerUser = 1, maxPerInstance = 10)
        val leases = (1..3).map { slots.acquire(user, perUserLimit = 3) }

        val refused = shouldThrow<PipelineConcurrencyLimitException> { slots.acquire(user, perUserLimit = 3) }

        refused.scope shouldBe LimitScope.PER_USER
        refused.limit shouldBe 3
        slots.inFlight shouldBe 3 // the refusal took nothing
        leases.forEach(SlotLease::close)
        slots.inFlight shouldBe 0
        slots.trackedUsers shouldBe 0
    }

    @Test
    fun `an instance-wide refusal holds nothing either`() {
        val slots = ExecutionSlots(maxPerUser = 5, maxPerInstance = 1)
        val held = slots.acquire(UUID.randomUUID())

        val refused = shouldThrow<PipelineConcurrencyLimitException> { slots.acquire(user) }

        refused.scope shouldBe LimitScope.GLOBAL
        slots.inFlight shouldBe 1
        slots.inFlightFor(user) shouldBe 0
        held.close()
    }

    @Test
    fun `withSlot adopts a handed-over lease - no second pair - and releases it when the body ends`() {
        val slots = ExecutionSlots(maxPerUser = 1, maxPerInstance = 1)
        val lease = slots.acquire(user)

        // With maxPerUser = maxPerInstance = 1, taking a second pair would throw: adoption must not.
        val seen = runBlocking { slots.withSlot(user, lease) { slots.inFlight to slots.inFlightFor(user) } }

        seen shouldBe (1 to 1)
        lease.isReleased shouldBe true
        slots.inFlight shouldBe 0
        slots.trackedUsers shouldBe 0
    }

    @Test
    fun `withSlot releases an adopted lease when the body throws`() {
        val slots = ExecutionSlots(maxPerUser = 1, maxPerInstance = 1)
        val lease = slots.acquire(user)

        shouldThrow<IllegalStateException> {
            runBlocking { slots.withSlot(user, lease) { error("the execution failed") } }
        }

        lease.isReleased shouldBe true
        slots.inFlight shouldBe 0
    }
}
