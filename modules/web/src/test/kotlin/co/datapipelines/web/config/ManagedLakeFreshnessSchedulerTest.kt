package co.datapipelines.web.config

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import co.datapipelines.datasources.DatasourceErrorCodes
import co.datapipelines.datasources.DatasourceGrantRepository
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.datasources.ManagedLakeCatalog
import co.datapipelines.logcapture.capturingLogEvents
import co.datapipelines.typesystem.guard.WriterBusy
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.slf4j.LoggerFactory
import org.springframework.dao.QueryTimeoutException
import java.sql.SQLException

/**
 * #818: the freshness tick refreshes the managed generations, logs how many it retired (the brief's DoS bound: a
 * retire storm is bounded by the pool count and the count is logged), then regrants. A quiet tick logs nothing.
 *
 * #818b A: both steps go through the scheduler's OWN catalog and grant repository (the bean's bounded pair, which
 * `ScheduledJobsStatementBoundsTest` pins), and a step that throws is one WARN with the class and SQLState only,
 * after which the next step still runs and nothing reaches the scheduler thread.
 */
class ManagedLakeFreshnessSchedulerTest {
    private val logger = LoggerFactory.getLogger(ManagedLakeFreshnessScheduler::class.java) as Logger
    private val catalog = ManagedLakeCatalog { emptyList() }
    private val grants = mockk<DatasourceGrantRepository>()

    @Test
    fun `a tick that retires generations logs the count, then regrants`() {
        val (datasources, registrar) = collaborators(retired = 3)

        val events = capturingLogEvents(logger) { scheduler(datasources, registrar).tick() }

        assertAll(
            {
                events.map { it.formattedMessage.substringBefore(" message=") } shouldBe
                    listOf("event=lake.freshness_tick_retired generations=3")
            },
            {
                verifyOrder {
                    datasources.refreshManagedGenerations(catalog)
                    registrar.regrant(grants)
                }
            },
        )
    }

    @Test
    fun `a tick that retires nothing logs nothing and still regrants`() {
        val (datasources, registrar) = collaborators(retired = 0)

        val events = capturingLogEvents(logger) { scheduler(datasources, registrar).tick() }

        assertAll(
            { events.shouldBeEmpty() },
            {
                verifyOrder {
                    datasources.refreshManagedGenerations(catalog)
                    registrar.regrant(grants)
                }
            },
        )
    }

    @Test
    fun `a refresh cancelled at its bound is one WARN, and the regrant still runs - #818b A`() {
        val (datasources, registrar) = collaborators(retired = 0)
        every { datasources.refreshManagedGenerations(catalog) } throws cancelled()

        val events = capturingLogEvents(logger) { scheduler(datasources, registrar).tick() }

        assertAll(
            {
                events.map { it.level to it.formattedMessage.substringBefore(" message=") } shouldBe
                    listOf(Level.WARN to "event=lake.freshness_tick_failed step=refresh error=QueryTimeoutException sql_state=57014")
            },
            { verify(exactly = 1) { registrar.regrant(grants) } },
        )
    }

    @Test
    fun `a regrant cancelled at its bound is one WARN and does not escape the tick - #818b A`() {
        val (datasources, registrar) = collaborators(retired = 0)
        every { registrar.regrant(grants) } throws cancelled()

        val events = capturingLogEvents(logger) { scheduler(datasources, registrar).tick() }

        events.map { it.level to it.formattedMessage.substringBefore(" message=") } shouldBe
            listOf(Level.WARN to "event=lake.freshness_tick_failed step=regrant error=QueryTimeoutException sql_state=57014")
    }

    @Test
    fun `a regrant refused busy by the writer guard is one WARN with the lock-timeout state and does not escape - #841 G0`() {
        val (datasources, registrar) = collaborators(retired = 0)
        every { registrar.regrant(grants) } throws
            WriterBusy.refusal(DatasourceErrorCodes.WRITE_BUSY, LOCK_WAIT_SECONDS, SQLException("lock timeout", "55P03"))

        val events = capturingLogEvents(logger) { scheduler(datasources, registrar).tick() }

        // Fail closed at the loop's existing boundary: nothing written (the guard's transaction rolled back), one WARN,
        // and the next tick retries the same idempotent repair on its own cadence.
        events.map { it.level to it.formattedMessage.substringBefore(" message=") } shouldBe
            listOf(Level.WARN to "event=lake.freshness_tick_failed step=regrant error=DatapipelinesException sql_state=55P03")
    }

    /** What Spring raises for a Postgres statement cancelled at its `queryTimeout`. */
    private fun cancelled() = QueryTimeoutException("statement cancelled", SQLException("canceling statement", "57014"))

    private fun scheduler(
        datasources: DatasourceRegistry,
        registrar: ManagedLakeDatasourceRegistrar,
    ) = ManagedLakeFreshnessScheduler(datasources, registrar, catalog, grants)

    private fun collaborators(retired: Int): Pair<DatasourceRegistry, ManagedLakeDatasourceRegistrar> =
        Pair(
            mockk { every { refreshManagedGenerations(catalog) } returns retired },
            mockk { every { regrant(grants) } returns Unit },
        )

    private companion object {
        const val LOCK_WAIT_SECONDS = 5
    }
}
