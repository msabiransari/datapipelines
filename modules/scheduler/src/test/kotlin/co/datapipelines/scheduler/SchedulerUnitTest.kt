package co.datapipelines.scheduler

import co.datapipelines.pipeline.PipelineErrorCodes
import com.github.kagkarlsson.scheduler.Scheduler
import com.github.kagkarlsson.scheduler.SchedulerState
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.Duration

/** The scheduler's small contracts: code spellings, property bounds, the API-mode start gate, the executor allowlist. */
class SchedulerUnitTest {
    @Test
    fun `the codes are the catalog's - the idempotency row spelled identically, the sixteen §13-19 codes the same set`() {
        ScheduleErrorCodes.IDEMPOTENCY_KEY_REUSED shouldBe PipelineErrorCodes.Limits.IDEMPOTENCY_KEY_REUSED
        // The catalog's copy (§13.19) and the scheduler's are the same sixteen codes (read by reflection).
        val catalog =
            PipelineErrorCodes.Schedule::class.java.declaredFields
                .filter {
                    java.lang.reflect.Modifier
                        .isStatic(it.modifiers) && it.type == String::class.java
                }.map { it.get(null) as String }
                .toSet()
        catalog shouldBe ScheduleErrorCodes.ALL
        ScheduleErrorCodes.ALL.all { it.startsWith("schedule.") } shouldBe true
    }

    @Test
    fun `the documented defaults and the bounds that keep shutdown and the window scan honest`() {
        val p = SchedulerProperties()
        listOf(p.enabled, p.threads, p.pollingIntervalSeconds, p.heartbeatIntervalSeconds, p.tickIntervalSeconds) shouldBe
            listOf(true, 2, 10L, 30L, 10L)
        listOf(p.maxConcurrentRuns, p.latenessSeconds, p.catchUpMaxAgeSeconds, p.shutdownWaitSeconds) shouldBe listOf(4, 600L, 86_400L, 5L)
        listOf(p.minIntervalSeconds, p.maxSchedulesPerWorkspace) shouldBe listOf(300L, 100)
        p.startGrace shouldBe Duration.ofMinutes(3)
        shouldThrow<IllegalArgumentException> { SchedulerProperties(shutdownWaitSeconds = 30) }
        shouldThrow<IllegalArgumentException> { SchedulerProperties(minIntervalSeconds = 30) }
        shouldThrow<IllegalArgumentException> { SchedulerProperties(catchUpMaxAgeSeconds = 700_000) }
        shouldThrow<IllegalArgumentException> { SchedulerProperties(threads = 0) }
    }

    @Test
    fun `API mode - the start gate never starts db-scheduler, a worker instance does (A19)`() {
        fun gate(enabled: Boolean): Scheduler {
            val state =
                mockk<SchedulerState> {
                    every { isStarted } returns false
                    every { isShuttingDown } returns false
                }
            val scheduler = mockk<Scheduler>(relaxed = true) { every { schedulerState } returns state }
            SchedulerStartGate(scheduler, SchedulerProperties(enabled = enabled)).doStart()
            return scheduler
        }
        val apiMode = gate(enabled = false)
        val worker = gate(enabled = true)
        verify(exactly = 0) { apiMode.start() }
        verify(exactly = 1) { worker.start() }
    }

    @Test
    fun `executors are an allowlist - an unknown id is refused, a duplicate id refuses to wire`() {
        val executors = JobExecutors(listOf(FakeExecutor()))
        shouldThrow<ScheduleException> { executors.require("shell") }.code shouldBe ScheduleErrorCodes.EXECUTOR_UNKNOWN
        executors.find("shell") shouldBe null
        shouldThrow<IllegalArgumentException> { JobExecutors(listOf(FakeExecutor(), FakeExecutor())) }
    }
}
