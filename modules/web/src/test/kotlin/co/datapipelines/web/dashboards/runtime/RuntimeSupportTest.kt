package co.datapipelines.web.dashboards.runtime

import co.datapipelines.application.dashboards.RefreshAdmission
import co.datapipelines.executor.ExecutionSlots
import co.datapipelines.persistence.FailureShape
import co.datapipelines.visualization.ActionScope
import co.datapipelines.visualization.DashboardBody
import co.datapipelines.visualization.DashboardLayout
import co.datapipelines.visualization.DashboardRefreshRepository
import co.datapipelines.visualization.DashboardRuntimeConfig
import co.datapipelines.visualization.DashboardTimeouts
import co.datapipelines.visualization.RefreshExecutionLink
import co.datapipelines.visualization.RefreshRecord
import co.datapipelines.visualization.RefreshStatus
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.springframework.dao.DataAccessResourceFailureException
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** The small pieces around the runtime: the revision counter, the views' pure parts, the housekeeping and the metrics. */
class RuntimeSupportTest {
    // ---- ParameterRevisions ---------------------------------------------------------------------------------

    @Test
    fun `revisions climb per instance from one, independently`() {
        val revisions = ParameterRevisions()
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()

        listOf(revisions.next(a), revisions.next(a), revisions.next(b), revisions.next(a)) shouldBe listOf(1, 2, 1, 3)
    }

    @Test
    fun `the tracked instances are bounded - the least recently used starts again from one`() {
        val revisions = ParameterRevisions(maxInstances = 2)
        val first = UUID.randomUUID()
        revisions.next(first)
        revisions.next(first)
        revisions.next(UUID.randomUUID())
        revisions.next(UUID.randomUUID()) // evicts `first`

        revisions.tracked() shouldBe 2
        revisions.next(first) shouldBe 1
    }

    // ---- RuntimeViews -----------------------------------------------------------------------------------------

    @Test
    fun `the refresh deadline is the dashboard's own or the default - never above the cap`() {
        val runtime = DashboardRuntimeConfig(defaultRefreshSeconds = 100, maxRefreshSeconds = 300)
        val body = { seconds: Int? ->
            DashboardBody("d", visualizations = emptyList(), layout = DashboardLayout(), timeouts = seconds?.let { DashboardTimeouts(it) })
        }

        RuntimeViews.refreshSeconds(body(null), runtime) shouldBe 100
        RuntimeViews.refreshSeconds(body(250), runtime) shouldBe 250
        // the validator refuses this at save; the view would still not exceed it
        RuntimeViews.refreshSeconds(body(9_999), runtime) shouldBe 300
    }

    @Test
    fun `a refresh view lists executions only for a reader of executions`() {
        val record =
            RefreshRecord(
                UUID.randomUUID(),
                UUID.randomUUID(),
                2,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                null,
                ActionScope.ALL,
                "[]",
                4,
                """{"year":2026}""",
                RefreshStatus.PARTIAL,
                Instant.parse("2026-09-29T10:00:00Z"),
                Instant.parse("2026-09-29T10:00:03Z"),
                """{"targets":{}}""",
            )
        val links = listOf(RefreshExecutionLink(record.id, "s", UUID.randomUUID(), shared = true))

        val withLinks = RuntimeViews.refresh(record, links, showExecutions = true)
        val without = RuntimeViews.refresh(record, links, showExecutions = false)

        withLinks["executions"][0]["source"].asText() shouldBe "s"
        withLinks["executions"][0]["shared"].asBoolean() shouldBe true
        without.has("executions") shouldBe false // a promoter's refresh names no execution
        withLinks["status"].asText() shouldBe "PARTIAL"
        withLinks["scope"].asText() shouldBe "all"
        withLinks["selections"]["year"].asInt() shouldBe 2026
        withLinks["finished_at"].asText() shouldBe "2026-09-29T10:00:03Z"
    }

    // ---- housekeeping -----------------------------------------------------------------------------------------

    @Test
    fun `the sweeper reports the rows it closed, and a store fault is a zero tick - retried, never thrown`() {
        val repository = mockk<DashboardRefreshRepository>()
        val ids = listOf(UUID.randomUUID(), UUID.randomUUID())
        every { repository.sweepStale(915) } returns ids
        DashboardRefreshSweeper(repository, 915).sweepOnce() shouldBe 2

        every { repository.sweepStale(915) } throws DataAccessResourceFailureException("db down: SECRET-ROW")
        DashboardRefreshSweeper(repository, 915).sweepOnce() shouldBe 0
        shouldThrow<IllegalArgumentException> { DashboardRefreshSweeper(repository, 0) }
        FailureShape.cause(DataAccessResourceFailureException("x")) shouldBe "DataAccessResourceFailureException"
    }

    @Test
    fun `retention purges finished refreshes on the event cutoff, and a store fault is a zero step`() {
        val repository = mockk<DashboardRefreshRepository>()
        every { repository.deleteFinishedOlderThan(7) } returns 3
        DashboardRefreshRetention(repository, 7).retainOnce() shouldBe 3
        verify(exactly = 1) { repository.deleteFinishedOlderThan(7) }

        every { repository.deleteFinishedOlderThan(7) } throws DataAccessResourceFailureException("down")
        DashboardRefreshRetention(repository, 7).retainOnce() shouldBe 0
        shouldThrow<IllegalArgumentException> { DashboardRefreshRetention(repository, 0) }
    }

    // ---- DashboardMetrics -------------------------------------------------------------------------------------

    @Test
    fun `the metrics count endings by status, time them, count refusals and gauge the admission`() {
        val registry = SimpleMeterRegistry()
        val metrics = DashboardMetrics(registry)
        val admission = RefreshAdmission(ExecutionSlots(1, 10), DashboardRuntimeConfig(maxWaitSeconds = 0))

        metrics.bind(admission)
        metrics.refreshEnded(RefreshStatus.COMPLETED, Duration.ofMillis(250))
        metrics.refreshEnded(RefreshStatus.COMPLETED, Duration.ofMillis(750))
        metrics.refreshEnded(RefreshStatus.ABORTED, Duration.ofMillis(5))
        metrics.refused(DashboardMetrics.REASON_SATURATED)

        registry.counter(DashboardMetrics.REFRESHES, "status", "COMPLETED").count() shouldBe 2.0
        registry.counter(DashboardMetrics.REFRESHES, "status", "ABORTED").count() shouldBe 1.0
        registry
            .timer(
                DashboardMetrics.REFRESH_DURATION,
                "status",
                "COMPLETED",
            ).totalTime(java.util.concurrent.TimeUnit.MILLISECONDS) shouldBe
            1000.0
        registry.counter(DashboardMetrics.REFRESHES_REFUSED, "reason", "saturated").count() shouldBe 1.0
        registry.get(DashboardMetrics.REFRESHES_ACTIVE).gauge().value() shouldBe 0.0
        val held = runBlocking { admission.admit(UUID.randomUUID(), executions = 1) }!!
        registry.get(DashboardMetrics.REFRESHES_ACTIVE).gauge().value() shouldBe 1.0
        registry.get(DashboardMetrics.EXECUTIONS_RESERVED).gauge().value() shouldBe 1.0
        held.close()
        registry.get(DashboardMetrics.EXECUTIONS_RESERVED).gauge().value() shouldBe 0.0
    }
}
