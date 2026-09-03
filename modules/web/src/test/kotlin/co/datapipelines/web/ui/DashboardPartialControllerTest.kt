package co.datapipelines.web.ui

import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.Scope
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.executor.ExecutionRecord
import co.datapipelines.executor.ExecutionRepository
import co.datapipelines.executor.ExecutionStatus
import co.datapipelines.executor.ExecutionTrigger
import co.datapipelines.pipeline.PipelineRepository
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.ui.ExtendedModelMap
import java.time.Instant
import java.util.UUID

/**
 * [DashboardPartialController] — the scoping branch and the stats math, not the template
 * (DashboardPartialsRenderTest renders the views). The admin/user fork is an authorization
 * decision the same way ExecutionRepository's two listing methods are: admin sees the
 * workspace's runs, everyone else their own — this pins which repository path each
 * principal takes and the arithmetic the dashboard cards display.
 */
class DashboardPartialControllerTest {
    private val executions = mockk<ExecutionRepository>()
    private val pipelines = mockk<PipelineRepository>()
    private val controller = DashboardPartialController(executions, pipelines)

    private val userId = UUID.randomUUID()
    private val workspaceId = UUID.randomUUID()
    private val model = ExtendedModelMap()

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    private fun authenticate(scopes: Set<Scope>) {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(
                AuthenticatedPrincipal(
                    userId,
                    "a@b.c",
                    "A",
                    scopes,
                    AuthMethod.OIDC,
                    workspace = WorkspaceContext(workspaceId, "acme"),
                ),
                null,
                emptyList(),
            )
    }

    private fun record(
        status: ExecutionStatus,
        startedAt: Instant = Instant.now(),
    ) = ExecutionRecord(
        executionId = UUID.randomUUID(),
        pipelineId = UUID.randomUUID(),
        pipelineVersion = 1,
        status = status,
        parametersJson = "{}",
        triggeredBy = userId,
        triggeredVia = ExecutionTrigger.UI,
        startedAt = startedAt,
    )

    @Test
    fun `an admin reads the workspace-wide batch`() {
        authenticate(setOf(Scope.ADMIN))
        every { pipelines.countAll(workspaceId) } returns 7
        every {
            executions.findAll(workspaceId, null, null, null, null, any(), any())
        } returns listOf(record(ExecutionStatus.SUCCESS))

        controller.stats(model)

        verify(exactly = 1) {
            executions.findAll(workspaceId, null, null, null, null, any(), any())
        }
        model["totalPipelines"] shouldBe 7
    }

    @Test
    fun `a non-admin is confined to their own runs`() {
        authenticate(setOf(Scope.AUTHOR))
        every { pipelines.countAll(workspaceId) } returns 0
        every {
            executions.findByUser(workspaceId, userId, null, null, null, null, any(), any())
        } returns emptyList()

        controller.stats(model)

        verify(exactly = 0) { executions.findAll(any(), any(), any(), any(), any(), any(), any()) }
        model["successRate"] shouldBe 0
    }

    @Test
    fun `the stats math - today filter, success rate, empty batch guard`() {
        authenticate(setOf(Scope.ADMIN))
        every { pipelines.countAll(workspaceId) } returns 3
        // Two today (one SUCCESS, one FAILED), one yesterday (SUCCESS) — rate over the sample.
        val batch =
            listOf(
                record(ExecutionStatus.SUCCESS, Instant.now()),
                record(ExecutionStatus.FAILED, Instant.now()),
                record(ExecutionStatus.SUCCESS, Instant.now().minusSeconds(200_000)),
            )
        every { executions.findAll(workspaceId, null, null, null, null, any(), any()) } returns batch

        controller.stats(model)

        model["executionsToday"] shouldBe 2
        model["successRate"] shouldBe 66
    }

    @Test
    fun `both endpoints return the partial view names`() {
        authenticate(setOf(Scope.ADMIN))
        every { pipelines.countAll(workspaceId) } returns 0
        every { executions.findAll(workspaceId, null, null, null, null, any(), any()) } returns emptyList()

        controller.stats(model) shouldBe "partials/dashboard-stats"
        controller.recentExecutions(model) shouldBe "partials/recent-executions"
    }

    @Test
    fun `recent executions carries the batch for the template`() {
        authenticate(setOf(Scope.AUTHOR))
        val batch = listOf(record(ExecutionStatus.SUCCESS))
        every { executions.findByUser(workspaceId, userId, null, null, null, null, any(), any()) } returns batch

        controller.recentExecutions(model)

        model["executions"] shouldBe batch
    }
}
