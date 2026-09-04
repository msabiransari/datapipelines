package co.datapipelines.web.ui

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
    private val controller = ExecutionHistoryController(pipelines)

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

    @Test
    fun `the page carries the workspace's pipelines and every status for the filter bar`() {
        val rows = listOf(pipeline("monthly_revenue"))
        every { pipelines.findAll(workspaceId) } returns rows

        val model = ExtendedModelMap()
        controller.list(model) shouldBe "executions/list"

        model["pipelines"] shouldBe rows
        model["statuses"] shouldBe co.datapipelines.executor.ExecutionStatus.entries
        verify(exactly = 1) { pipelines.findAll(workspaceId) }
    }

    private fun pipeline(name: String) =
        PipelineRecord(
            id = UUID.randomUUID(),
            name = name,
            displayName = name,
            description = "",
            ownerId = UUID.randomUUID(),
            currentVersion = 1,
            isDeleted = false,
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
        )
}
