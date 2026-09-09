package co.datapipelines.web.ui

import co.datapipelines.executor.ExecutionRepository
import co.datapipelines.pipeline.PipelineRecord
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
 * [ExecutionHistoryController] — the page model, not the template (the partial's contract
 * is ExecutionHistoryPartialControllerTest's). The page hands the filter bar its two
 * inputs: every pipeline in the workspace and every status — the bar must never offer a
 * status the enum dropped or a pipeline the workspace cannot see.
 */
class ExecutionHistoryControllerTest {
    private val pipelines = mockk<PipelineRepository>()
    private val executions = mockk<ExecutionRepository>()
    private val pipelineNames = mockk<PipelineNames>().also { every { it.lookup(any(), any()) } returns emptyMap() }
    private val controller =
        ExecutionHistoryController(pipelines, ExecutionHistoryBrowseModel(executions, pipelineNames, pipelines))

    private val workspaceId = UUID.randomUUID()

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    init {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(
                co.datapipelines.auth.AuthenticatedPrincipal(
                    UUID.randomUUID(),
                    "a@b.c",
                    "A",
                    setOf(co.datapipelines.auth.Scope.AUTHOR),
                    co.datapipelines.auth.AuthMethod.OIDC,
                    workspace = co.datapipelines.auth.WorkspaceContext(workspaceId, "acme"),
                ),
                null,
                emptyList(),
            )
    }

    private fun stubRows() {
        every { executions.findByUser(workspaceId, any(), any(), any(), any(), any(), any(), any()) } returns emptyList()
        every { pipelines.releasedAtFor(any(), any()) } returns emptyMap()
    }

    @Test
    fun `the page carries the workspace's pipelines and every status for the filter bar`() {
        val rows = listOf(pipeline("monthly_revenue"))
        every { pipelines.findAll(workspaceId) } returns rows
        stubRows()

        val model = ExtendedModelMap()
        controller.list(model, null, null, null, null, 0) shouldBe "executions/list"

        model["pipelines"] shouldBe rows
        model["statuses"] shouldBe co.datapipelines.executor.ExecutionStatus.entries
        verify(exactly = 1) { pipelines.findAll(workspaceId) }
    }

    /**
     * §5 (097 §B): the page paints the FIRST FRAGMENT itself. `/executions` used to render a
     * spinner and let `hx-trigger="load"` fetch the rows, so the screen's first paint was an
     * empty frame — the attributes the fragment reads were not on the page's model at all.
     */
    @Test
    fun `the page fills the list fragment's own model, not just the filter bar's`() {
        every { pipelines.findAll(workspaceId) } returns emptyList()
        stubRows()

        val model = ExtendedModelMap()
        controller.list(model, null, null, null, null, 0)

        model["executions"] shouldBe emptyList<Any>()
        model["offset"] shouldBe 0
        model["hasMore"] shouldBe false
        model["pageSize"] shouldBe ExecutionHistoryBrowseModel.PAGE_SIZE
    }

    /**
     * A shared `/executions?status=FAILED&started_after=…` link used to open on EVERYTHING:
     * the page route ignored every filter, and only the fragment request that followed it
     * applied them. The controls echo their values back too, or the bar would render blank
     * over rows that were filtered.
     */
    @Test
    fun `the page applies the filters in the URL and echoes them back to the bar`() {
        every { pipelines.findAll(workspaceId) } returns emptyList()
        every {
            executions.findByUser(workspaceId, any(), any(), co.datapipelines.executor.ExecutionStatus.FAILED, any(), any(), any(), any())
        } returns emptyList()
        every { pipelines.releasedAtFor(any(), any()) } returns emptyMap()

        val model = ExtendedModelMap()
        controller.list(model, null, "FAILED", "2026-09-01", null, 0)

        model["selectedStatus"] shouldBe "FAILED"
        model["selectedStartedAfter"] shouldBe "2026-09-01"
        verify(exactly = 1) {
            executions.findByUser(
                workspaceId,
                any(),
                null,
                co.datapipelines.executor.ExecutionStatus.FAILED,
                java.time.Instant.parse("2026-09-01T00:00:00Z"),
                null,
                any(),
                any(),
            )
        }
    }

    private fun pipeline(name: String) =
        PipelineRecord(
            id = UUID.randomUUID(),
            name = name,
            displayName = name,
            description = "",
            ownerId = UUID.randomUUID(),
            currentVersion = 1,
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
        )
}
