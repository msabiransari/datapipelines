package co.datapipelines.web.config

import ch.qos.logback.classic.Logger
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.datasources.pooling.ReapOutcome
import co.datapipelines.web.capturingLogEvents
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

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

    private fun registryReaping(outcome: ReapOutcome): DatasourceRegistry = mockk { every { reapRetiredPools() } returns outcome }
}
