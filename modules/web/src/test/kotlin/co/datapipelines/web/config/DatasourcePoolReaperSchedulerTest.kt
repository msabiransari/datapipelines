package co.datapipelines.web.config

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.datasources.pooling.PoolLifecycleMetrics
import co.datapipelines.datasources.pooling.ReapOutcome
import co.datapipelines.logcapture.capturingLogEvents
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled

/**
 * #818c (record §10 RULING 4 amended 2026-11-05, item 3) — the reaper pass logs the count of managed generations it
 * retired at their age bound, once per pass and only when it is not zero (the retire-storm bound's observable, the
 * freshness tick's pattern). A pass that only closes drained or hung pools logs no count line: those keep their own
 * lines in the pool manager. That the real pass retires with no borrower is `ManagedLakeReadsE2eTest` H's.
 */
class DatasourcePoolReaperSchedulerTest {
    private val logger = LoggerFactory.getLogger(DatasourcePoolReaperScheduler::class.java) as Logger

    @Test
    fun `a pass that retired generations logs their count once`() {
        val datasources = registryReaping(ReapOutcome(drained = 1, hardClosed = 0, expired = 2))

        val events = capturingLogEvents(logger) { DatasourcePoolReaperScheduler(datasources).reap() }

        events.map { it.formattedMessage.substringBefore(" message=") } shouldBe
            listOf("event=lake.reaper_retired generations=2")
        verify(exactly = 1) { datasources.reapRetiredPools() }
    }

    @Test
    fun `a pass that retired no generation logs no count line - closes alone do not count`() {
        val datasources = registryReaping(ReapOutcome(drained = 3, hardClosed = 1, expired = 0))

        val events = capturingLogEvents(logger) { DatasourcePoolReaperScheduler(datasources).reap() }

        events.shouldBeEmpty()
        verify(exactly = 1) { datasources.reapRetiredPools() }
    }

    @Test
    fun `a pass over its bound logs one WARN with its elapsed time and counts it - #818d`() {
        val metrics = SlowPassMetrics()
        val scheduler = DatasourcePoolReaperScheduler(registryReaping(ReapOutcome(0, 0, 0)), metrics)
        val over = DatasourcePoolReaperScheduler.PASS_BOUND_MILLIS + 1

        val events = capturingLogEvents(logger) { scheduler.passMeasured(over) }

        events.map { it.level to it.formattedMessage.substringBefore(" message=") } shouldBe
            listOf(Level.WARN to "event=datasource.reaper_pass_slow elapsed_ms=$over bound_ms=1000")
        metrics.slow shouldBe 1
    }

    @Test
    fun `a pass at or under its bound is silent and uncounted - #818d`() {
        val metrics = SlowPassMetrics()
        val scheduler = DatasourcePoolReaperScheduler(registryReaping(ReapOutcome(0, 0, 0)), metrics)

        val events =
            capturingLogEvents(logger) {
                scheduler.passMeasured(0)
                scheduler.passMeasured(DatasourcePoolReaperScheduler.PASS_BOUND_MILLIS)
            }

        events.shouldBeEmpty()
        metrics.slow shouldBe 0
    }

    @Test
    fun `the reaper names the dp-reaper scheduler, and only it - #818d`() {
        val scheduled = DatasourcePoolReaperScheduler::class.java.getMethod("reap").getAnnotation(Scheduled::class.java)

        scheduled.scheduler shouldBe PoolReaperTaskScheduler.BEAN_NAME
        PoolReaperTaskScheduler.THREAD_NAME shouldBe "dp-reaper"
    }

    private class SlowPassMetrics : PoolLifecycleMetrics {
        var slow = 0

        override fun poolRetired(datasourceName: String) = Unit

        override fun poolHardClosed(
            datasourceName: String,
            activeConnections: Int,
        ) = Unit

        override fun reaperPassSlow() {
            slow++
        }
    }

    private fun registryReaping(outcome: ReapOutcome): DatasourceRegistry = mockk { every { reapRetiredPools() } returns outcome }
}
