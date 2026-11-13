package co.datapipelines.staging

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Counted leftovers in the admission arithmetic (#803; staging.md §8.5; owner rulings 2026-11-12):
 * a request that can never fit is `scratch_blocked` — a type the delivery path does not swallow — and
 * one that fits once running reservations release stays the retryable `scratch_exhausted`.
 */
class DuckDbScratchLeftoverAdmissionTest {
    @TempDir
    lateinit var scratch: Path

    /** A budget of 100 with 60 bytes of leftovers counted, as a factory's sweep leaves it. */
    private fun budgetWithLeftovers(): ScratchBudget = ScratchBudget(capacityBytes = 100).also { it.countLeftovers(60) }

    /** A factory on a directory holding 60 bytes nobody can vouch for, aggregate 100. */
    private fun factoryWithLeftovers(): DuckDbStagingFactory {
        val planted = plantDatabase(scratch, ownerId = null, databaseBytes = 60, walBytes = 0, spillBytes = 0)
        planted.existing().size shouldBe 3
        return duckFactory(scratch, scratchCapacityBytes = 100)
    }

    /** The delivery path's call shape (LakeExecutionSetup): a `scratch_exhausted` becomes "no room yet". */
    private fun deliveryAdmission(
        factory: DuckDbStagingFactory,
        bytes: Long,
    ): StagingPreparation? =
        try {
            factory.prepareDelivery(UUID.randomUUID(), bytes)
        } catch (_: StagingScratchExhaustedException) {
            null
        }

    @Test
    fun `a request that cannot fit beside the leftovers is scratch_blocked with sizes only`() {
        val id = UUID.randomUUID()

        val refused = shouldThrow<StagingScratchBlockedException> { budgetWithLeftovers().reserve(id, 50) }

        refused.code shouldBe StagingErrorCodes.SCRATCH_BLOCKED
        refused.details shouldBe
            mapOf(
                "execution_id" to id.toString(),
                "requested_bytes" to 50L,
                "leftover_bytes" to 60L,
                "capacity_bytes" to 100L,
            )
        refused.message shouldContain "leftover files"
        refused.message shouldContain "clear the scratch directory"
        requireNotNull(refused.message).lowercase() shouldNotContain "retry"
        // The delivery path's catch is by type: the permanent refusal must not be one.
        StagingScratchExhaustedException::class.java.isInstance(refused) shouldBe false
    }

    @Test
    fun `through the delivery boundary the permanent refusal is not swallowed into a poll`() {
        val factory = factoryWithLeftovers()

        // Rules out: the permanent refusal raised as the retryable type, caught and polled until the wait expires.
        val refused = shouldThrow<StagingScratchBlockedException> { deliveryAdmission(factory, 50) }

        refused.leftoverBytes shouldBe 60L
        factory.budget.leftoverBytes shouldBe 60L
        factory.budget.reservedBytes shouldBe 0L
    }

    @Test
    fun `a request that fits once a running reservation releases stays scratch_exhausted and then passes`() {
        val budget = budgetWithLeftovers()
        val running = budget.reserve(UUID.randomUUID(), 20)

        val refused = shouldThrow<StagingScratchExhaustedException> { budget.reserve(UUID.randomUUID(), 30) }

        refused.code shouldBe StagingErrorCodes.SCRATCH_EXHAUSTED
        refused.details["reserved_bytes"] shouldBe 20L
        refused.details["leftover_bytes"] shouldBe 60L
        refused.message shouldContain "20 of 100 bytes are reserved by running executions and 60 are held by leftover files"
        running.release()
        budget.reserve(UUID.randomUUID(), 30).release()
        budget.reservedBytes shouldBe 0L
    }

    @Test
    fun `the same retryable refusal through the delivery boundary is still no room yet`() {
        val factory = factoryWithLeftovers()
        val running = factory.prepareDelivery(UUID.randomUUID(), 20)

        deliveryAdmission(factory, 30) shouldBe null

        running.close()
        val admitted = deliveryAdmission(factory, 30)
        admitted?.reservedBytes shouldBe 30L
        admitted?.close()
    }

    @Test
    fun `with no leftovers a request larger than the aggregate is scratch_blocked naming the capacity only`() {
        val budget = ScratchBudget(capacityBytes = 100).also { it.countLeftovers(0) }

        val refused = shouldThrow<StagingScratchBlockedException> { budget.reserve(UUID.randomUUID(), 101) }

        refused.details["leftover_bytes"] shouldBe 0L
        refused.message shouldContain "exceeds the configured scratch capacity of 100 bytes"
        refused.message shouldNotContain "leftover"
        refused.message shouldNotContain "clear"
        // Without leftovers the retryable message does not mention them either.
        budget.reserve(UUID.randomUUID(), 100)
        shouldThrow<StagingScratchExhaustedException> { budget.reserve(UUID.randomUUID(), 1) }.message shouldNotContain "leftover"
    }

    @Test
    fun `leftovers near Long MAX refuse instead of wrapping into a fit`() {
        val budget = ScratchBudget(capacityBytes = Long.MAX_VALUE).also { it.countLeftovers(Long.MAX_VALUE - 5) }
        budget.countLeftovers(Long.MAX_VALUE)
        budget.leftoverBytes shouldBe Long.MAX_VALUE

        shouldThrow<StagingScratchBlockedException> { budget.reserve(UUID.randomUUID(), 1) }
    }

    @Test
    fun `prepareDelivery without any open already sees the leftover debit`() {
        val factory = factoryWithLeftovers()

        // Rules out: a sweep placed at the first open, too late for admissions that precede it.
        shouldThrow<StagingScratchBlockedException> { factory.prepareDelivery(UUID.randomUUID(), 41) }
        val admitted = factory.prepareDelivery(UUID.randomUUID(), 40)

        admitted.close()
        factory.budget.leftoverBytes shouldBe 60L
        // Nothing was opened, so nothing of this factory is in the directory.
        namesIn(scratch).filter { it.startsWith("dp-owner_") }.shouldBeEmpty()
    }

    @Test
    fun `concurrent first admissions run one sweep and none is granted before it ends`() {
        plantDatabase(scratch, ownerId = null, databaseBytes = 60, walBytes = 0, spillBytes = 0)
        val inSweep = CountDownLatch(1)
        val release = CountDownLatch(1)
        val sweeps = AtomicInteger()
        val props = duckProps(scratch, scratchCapacityBytes = 100)
        val factory =
            DuckDbStagingFactory(props, ScratchBudget(100), beforeSweep = {
                sweeps.incrementAndGet()
                inSweep.countDown()
                release.await(30, TimeUnit.SECONDS)
            }) { _, _ -> error("nothing opens in this test") }
        val pool = Executors.newFixedThreadPool(4)
        try {
            val calls =
                (1..4).map {
                    pool.submit<Any> { runCatching { factory.prepareDelivery(UUID.randomUUID(), 50) }.exceptionOrNull() ?: "admitted" }
                }
            inSweep.await(30, TimeUnit.SECONDS) shouldBe true
            Thread.sleep(200)

            // While the sweep is held open: nobody returned, nothing reserved.
            calls.none { it.isDone } shouldBe true
            factory.budget.reservedBytes shouldBe 0L
            factory.budget.leftoverBytes shouldBe null
            release.countDown()

            calls.forEach { it.get(30, TimeUnit.SECONDS).shouldBeInstanceOf<StagingScratchBlockedException>() }
            sweeps.get() shouldBe 1
            factory.budget.reservedBytes shouldBe 0L
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }
}
