package co.datapipelines.web.ui

import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.auth.WorkspaceRole
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
    private val pipelineNames = mockk<PipelineNames>().also { every { it.lookup(any(), any()) } returns emptyMap() }

    // #10 L3b — the dashboards fragments' model: relaxed, because these cases pin WHAT the
    // controller asks of it (the tree partial's parameters), not what it renders.
    private val browse = mockk<DashboardBrowseModel>(relaxed = true)
    private val controller =
        DashboardPartialController(
            executions,
            co.datapipelines.web.pipelineServiceOver(pipelines),
            pipelineNames,
            co.datapipelines.web.EVERYTHING_LENS,
            browse,
            // #400 — the family's service (the Overview/Versions/Keys tabs' reads); relaxed,
            // because these cases pin WHAT the controller asks, not what the service answers.
            mockk<co.datapipelines.visualization.DashboardService>(relaxed = true),
        )

    private val userId = UUID.randomUUID()
    private val workspaceId = UUID.randomUUID()
    private val model = ExtendedModelMap()

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    private fun authenticate(
        workspaceAdmin: Boolean = false,
        role: WorkspaceRole = if (workspaceAdmin) WorkspaceRole.WORKSPACE_ADMIN else WorkspaceRole.VIEWER,
    ) {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(
                AuthenticatedPrincipal(
                    userId,
                    "a@b.c",
                    "A",
                    AuthMethod.OIDC,
                    workspace =
                        WorkspaceContext(
                            workspaceId,
                            "acme",
                            // RBAC round 1: "an admin sees the workspace's runs" is the CAPABILITY
                            // now — `Scope.ADMIN` is a scope no principal can hold (D-R1, O-2).
                            role,
                        ),
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
        executedBy = userId,
        triggeredVia = ExecutionTrigger.UI,
        startedAt = startedAt,
    )

    @Test
    fun `an admin reads the workspace-wide batch`() {
        authenticate(workspaceAdmin = true)
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
        authenticate()
        every { pipelines.countAll(workspaceId) } returns 0
        every {
            executions.findVisible(workspaceId, userId, null, null, null, null, any(), any())
        } returns emptyList()

        controller.stats(model)

        verify(exactly = 0) { executions.findAll(any(), any(), any(), any(), any(), any(), any()) }
        model["successRate"] shouldBe 0
    }

    @Test
    fun `the stats math - today filter, success rate, empty batch guard`() {
        authenticate(workspaceAdmin = true)
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
        authenticate(workspaceAdmin = true)
        every { pipelines.countAll(workspaceId) } returns 0
        every { executions.findAll(workspaceId, null, null, null, null, any(), any()) } returns emptyList()

        controller.stats(model) shouldBe "partials/dashboard-stats"
        controller.recentExecutions(model) shouldBe "partials/recent-executions"
    }

    @Test
    fun `recent executions carries the batch for the template`() {
        authenticate()
        val batch = listOf(record(ExecutionStatus.SUCCESS))
        every { executions.findVisible(workspaceId, userId, null, null, null, null, any(), any()) } returns batch

        controller.recentExecutions(model)

        model["executions"] shouldBe batch
    }

    @Test
    fun `a promoter's tiles count their OWN runs - every surface answers as visibleTo does (#293)`() {
        // Pre-fix the promoter's tiles read none (`!holds(execution.read) -> emptyList()`) while
        // the explorers' Runs tabs and the search palette showed the same promoter their own runs.
        // The ruling (orchestrator default, 2026-09-28): the dashboard agrees — own runs, in SQL
        // (the D11 pattern), never the workspace's and never the scheduled arm.
        authenticate(role = WorkspaceRole.PROMOTER)
        every { pipelines.countAll(workspaceId) } returns 0
        val own = listOf(record(ExecutionStatus.SUCCESS), record(ExecutionStatus.FAILED))
        every { executions.findByUser(workspaceId, userId, null, null, null, null, any(), any()) } returns own

        controller.stats(model)

        verify(exactly = 1) { executions.findByUser(workspaceId, userId, null, null, null, null, any(), any()) }
        verify(exactly = 0) { executions.findVisible(any(), any(), any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { executions.findAll(any(), any(), any(), any(), any(), any(), any()) }
        model["executionsToday"] shouldBe 2
        model["successRate"] shouldBe 50
    }

    // ------------------------------------------------------------ #10 L3b: the tree partial

    @Test
    fun `the tree partial threads the pager's offset to the level - the NAV branch's only instance (#400)`() {
        authenticate()

        controller.dashboardTree(model, prefix = "finance/dashboards", q = null, scope = "nav", offset = 25)

        verify(exactly = 1) {
            browse.fillLevel(
                model,
                workspaceId,
                any(),
                prefix = "finance/dashboards",
                offset = 25,
            )
        }
    }

    @Test
    fun `the tree partial without an offset is page one, and an unknown scope is the flat branch`() {
        authenticate()

        controller.dashboardTree(model, prefix = null, q = null, scope = "bogus", offset = null)

        verify(exactly = 1) {
            browse.fillWrapper(model, workspaceId, any(), q = null, offset = 0, scope = DashboardListScope.PAGE)
        }
    }

    @Test
    fun `the branch's search dispatches the flat list into the nav root, and the catalog partial is always flat (#400)`() {
        authenticate()

        controller.dashboardTree(model, prefix = null, q = "revenue", scope = "nav", offset = null)
        verify(exactly = 1) {
            browse.fillWrapper(model, workspaceId, any(), q = "revenue", offset = 0, scope = DashboardListScope.NAV)
        }

        controller.dashboardCatalog(model, q = null, offset = 50)
        verify(exactly = 1) {
            browse.fillWrapper(model, workspaceId, any(), q = null, offset = 50, scope = DashboardListScope.PAGE)
        }
    }

    @Test
    fun `the overview partial bounds the version and threads the lensed read (#400)`() {
        authenticate()
        val id = UUID.randomUUID()

        controller.dashboardOverview(model, id, version = 2)
        verify(exactly = 1) { browse.fillOverview(model, workspaceId, any(), id, 2) }

        controller.dashboardVersions(model, id)
        verify(exactly = 1) { browse.fillVersions(model, workspaceId, any(), id, any()) }
    }
}
