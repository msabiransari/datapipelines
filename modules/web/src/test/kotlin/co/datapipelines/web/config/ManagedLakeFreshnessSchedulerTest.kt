package co.datapipelines.web.config

import ch.qos.logback.classic.Logger
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.web.capturingLogEvents
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verifyOrder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.slf4j.LoggerFactory

/**
 * #818: the freshness tick refreshes the managed generations, logs how many it retired (the brief's DoS bound: a
 * retire storm is bounded by the pool count and the count is logged), then regrants. A quiet tick logs nothing.
 */
class ManagedLakeFreshnessSchedulerTest {
    private val logger = LoggerFactory.getLogger(ManagedLakeFreshnessScheduler::class.java) as Logger

    @Test
    fun `a tick that retires generations logs the count, then regrants`() {
        val (datasources, registrar) = collaborators(retired = 3)

        val events = capturingLogEvents(logger) { ManagedLakeFreshnessScheduler(datasources, registrar).tick() }

        assertAll(
            {
                events.map { it.formattedMessage.substringBefore(" message=") } shouldBe
                    listOf("event=lake.freshness_tick_retired generations=3")
            },
            {
                verifyOrder {
                    datasources.refreshManagedGenerations()
                    registrar.regrant()
                }
            },
        )
    }

    @Test
    fun `a tick that retires nothing logs nothing and still regrants`() {
        val (datasources, registrar) = collaborators(retired = 0)

        val events = capturingLogEvents(logger) { ManagedLakeFreshnessScheduler(datasources, registrar).tick() }

        assertAll(
            { events.shouldBeEmpty() },
            {
                verifyOrder {
                    datasources.refreshManagedGenerations()
                    registrar.regrant()
                }
            },
        )
    }

    private fun collaborators(retired: Int): Pair<DatasourceRegistry, ManagedLakeDatasourceRegistrar> =
        Pair(
            mockk { every { refreshManagedGenerations() } returns retired },
            mockk { every { regrant() } returns Unit },
        )
}
