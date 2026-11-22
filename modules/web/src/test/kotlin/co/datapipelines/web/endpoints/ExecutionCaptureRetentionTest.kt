package co.datapipelines.web.endpoints

import co.datapipelines.application.endpoints.ExecutionCaptureStore
import co.datapipelines.persistence.ScheduledJob
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.dao.QueryTimeoutException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * #1043 (metadata-db §8.6) — the capture retention step: batched by the store's own predicate until a batch deletes
 * nothing, bounded per tick, and a failed batch is counted on the real failed-tick counter while what was already
 * deleted is still reported. Nothing is remembered between ticks.
 */
class ExecutionCaptureRetentionTest {
    private val now = Instant.parse("2026-11-22T10:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val store = mockk<ExecutionCaptureStore>()
    private val registry = SimpleMeterRegistry()
    private val retention = ExecutionCaptureRetention(store, registry, clock)

    @Test
    fun `batches until a batch deletes nothing, at the tick's instant`() {
        every { store.purgeExpired(now, ExecutionCaptureRetention.BATCH_SIZE) } returnsMany listOf(1_000, 1_000, 7, 0)

        retention.retainOnce() shouldBe 2_007

        verify(exactly = 4) { store.purgeExpired(now, ExecutionCaptureRetention.BATCH_SIZE) }
        failedTicks() shouldBe 0.0
    }

    @Test
    fun `a backlog stops at the per-tick batch bound and the next tick continues it`() {
        every { store.purgeExpired(now, any()) } returns ExecutionCaptureRetention.BATCH_SIZE

        retention.retainOnce() shouldBe ExecutionCaptureRetention.BATCH_SIZE * ExecutionCaptureRetention.MAX_BATCHES

        verify(exactly = ExecutionCaptureRetention.MAX_BATCHES) { store.purgeExpired(now, any()) }
    }

    @Test
    fun `a failing batch is counted once, keeps what it deleted, and does not throw`() {
        every { store.purgeExpired(now, any()) } returns 1_000 andThenThrows QueryTimeoutException("statement timeout")

        retention.retainOnce() shouldBe 1_000

        failedTicks() shouldBe 1.0
    }

    @Test
    fun `nothing expired is no work and no failure`() {
        every { store.purgeExpired(now, any()) } returns 0

        retention.retainOnce() shouldBe 0

        verify(exactly = 1) { store.purgeExpired(now, any()) }
        failedTicks() shouldBe 0.0
    }

    private fun failedTicks(): Double =
        registry
            .get(ScheduledJob.FAILED_TICK_METER)
            .tag(ScheduledJob.JOB_TAG, ScheduledJob.CAPTURE_RETENTION.tag)
            .counter()
            .count()
}
