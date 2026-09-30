package co.datapipelines.executor

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID

/**
 * **All-or-none, instance-only, bounded-wait** admission (#10 L2, dashboards spec §9.4, §18 premise 2) on a REAL
 * [ExecutionSlots] — every assertion reads its counters, never a double's calls.
 *
 * The properties a dashboard refresh leans on: N slots are reserved together or none are (a partial fan-out
 * under saturation is the defect), the reservation takes NO per-user slot (a refresh must not starve the viewer's
 * own runs), the wait never exceeds its bound, and every reserved slot is released exactly once however the
 * reservation ends.
 *
 * Falsified at birth: reserving per slot instead of atomically (a loop of single CAS increments that keeps what
 * it got) turns the all-or-none case red — the refused reservation leaves slots held.
 */
class ExecutionSlotsInstanceOnlyTest {
    private val viewer: UUID = UUID.fromString("5a570000-0000-0000-0000-0000000000d1")
    private val noWait: Duration = Duration.ZERO

    @Test
    fun `a reservation holds count instance slots and no per-user slot`() =
        runBlocking<Unit> {
            val slots = ExecutionSlots(maxPerUser = 1, maxPerInstance = 10)
            val reservation = slots.acquireInstanceOnly(4, noWait).shouldNotBeNull()

            slots.inFlight shouldBe 4
            slots.trackedUsers shouldBe 0
            // …so the viewer's own run is not refused by a refresh they started (the per-user cap is 1).
            val own = slots.acquire(viewer)
            slots.inFlightFor(viewer) shouldBe 1
            own.close()
            reservation.close()
            slots.inFlight shouldBe 0
        }

    @Test
    fun `all or none - a reservation the instance cannot hold entirely holds nothing`() =
        runBlocking<Unit> {
            val slots = ExecutionSlots(maxPerUser = 5, maxPerInstance = 5)
            val held = slots.acquireInstanceOnly(3, noWait).shouldNotBeNull()

            slots.acquireInstanceOnly(3, noWait).shouldBeNull() // 2 free, 3 asked

            slots.inFlight shouldBe 3 // the refusal took NOTHING, not even the two that were free
            held.close()
            slots.inFlight shouldBe 0
        }

    @Test
    fun `a reservation above the instance ceiling can never be admitted and answers at once`() =
        runBlocking<Unit> {
            val slots = ExecutionSlots(maxPerUser = 5, maxPerInstance = 4)
            val started = System.nanoTime()

            slots.acquireInstanceOnly(5, Duration.ofSeconds(30)).shouldBeNull()

            ((System.nanoTime() - started) / NANOS_PER_MILLI) shouldBeLessThan 1_000L
            slots.inFlight shouldBe 0
        }

    @Test
    fun `it waits for room and takes it the moment it frees - within the bound`() =
        runBlocking<Unit> {
            val slots = ExecutionSlots(maxPerUser = 5, maxPerInstance = 4)
            val blocker = slots.acquireInstanceOnly(4, noWait).shouldNotBeNull()
            val lease = blocker.next()
            launch(Dispatchers.Default) {
                kotlinx.coroutines.delay(150)
                lease.close() // one slot frees…
                blocker.close() // …and the three never handed out
            }

            val waited = slots.acquireInstanceOnly(2, Duration.ofSeconds(10))

            waited.shouldNotBeNull()
            slots.inFlight shouldBe 2
            waited.close()
            slots.inFlight shouldBe 0
        }

    @Test
    fun `the wait never exceeds its bound - a full instance answers null after about the bound`() =
        runBlocking<Unit> {
            val slots = ExecutionSlots(maxPerUser = 5, maxPerInstance = 1)
            val held = slots.acquireInstanceOnly(1, noWait).shouldNotBeNull()
            val started = System.nanoTime()

            slots.acquireInstanceOnly(1, Duration.ofMillis(300)).shouldBeNull()

            val elapsedMillis = (System.nanoTime() - started) / NANOS_PER_MILLI
            elapsedMillis shouldBeGreaterThanOrEqual 250L
            elapsedMillis shouldBeLessThan 3_000L
            slots.inFlight shouldBe 1
            held.close()
        }

    @Test
    fun `two racing reservations that cannot both fit - exactly one wins, the other holds nothing`() =
        runBlocking<Unit> {
            repeat(50) {
                val slots = ExecutionSlots(maxPerUser = 5, maxPerInstance = 5)
                val results =
                    (1..2)
                        .map { async(Dispatchers.Default) { slots.acquireInstanceOnly(3, noWait) } }
                        .awaitAll()

                results.count { it != null } shouldBe 1
                slots.inFlight shouldBe 3
                results.filterNotNull().single().close()
                slots.inFlight shouldBe 0
            }
        }

    @Test
    fun `each handed-out lease releases its own slot and close releases only the ones never handed out`() =
        runBlocking<Unit> {
            val slots = ExecutionSlots(maxPerUser = 5, maxPerInstance = 10)
            val reservation = slots.acquireInstanceOnly(3, noWait).shouldNotBeNull()
            val first = reservation.next()
            val second = reservation.next()
            reservation.remaining shouldBe 1

            reservation.close() // the one never handed out
            slots.inFlight shouldBe 2
            reservation.close() // idempotent
            slots.inFlight shouldBe 2
            first.close()
            first.close() // a lease is idempotent too
            slots.inFlight shouldBe 1
            second.close()
            slots.inFlight shouldBe 0
        }

    @Test
    fun `a lease from a reservation is adopted by withSlot like any other - and taking one past the count is refused`() =
        runBlocking<Unit> {
            val slots = ExecutionSlots(maxPerUser = 1, maxPerInstance = 3)
            val reservation = slots.acquireInstanceOnly(1, noWait).shouldNotBeNull()
            val lease = reservation.next()
            shouldThrow<IllegalStateException> { reservation.next() }

            slots.withSlot(viewer, lease) { slots.inFlight shouldBe 1 }

            slots.inFlight shouldBe 0
            slots.trackedUsers shouldBe 0
            shouldThrow<IllegalStateException> { reservation.next() }
        }

    @Test
    fun `a non-positive count is a programming error`() {
        val slots = ExecutionSlots(maxPerUser = 1, maxPerInstance = 1)
        shouldThrow<IllegalArgumentException> { runBlocking { slots.acquireInstanceOnly(0, noWait) } }
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
