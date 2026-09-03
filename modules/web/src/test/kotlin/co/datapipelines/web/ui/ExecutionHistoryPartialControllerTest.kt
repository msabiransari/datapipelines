package co.datapipelines.web.ui

import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.Scope
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.executor.ExecutionRecord
import co.datapipelines.executor.ExecutionRepository
import co.datapipelines.executor.ExecutionStatus
import co.datapipelines.executor.ExecutionTrigger
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
 * [ExecutionHistoryPartialController] — the filter parsing and the page+1 pagination
 * contract, not the template (ExecutionsPartialRenderTest renders the fragment). The
 * controller fetches one row MORE than the page so `hasMore` is a fact about the data,
 * not an estimate; an unparseable status degrades to no filter rather than a 500; and
 * the admin/user scoping fork mirrors the REST listing's authorization decision.
 */
class ExecutionHistoryPartialControllerTest {
    private val executions = mockk<ExecutionRepository>()
    private val controller = ExecutionHistoryPartialController(executions)

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

    private fun record() =
        ExecutionRecord(
            executionId = UUID.randomUUID(),
            pipelineId = UUID.randomUUID(),
            pipelineVersion = 1,
            status = ExecutionStatus.SUCCESS,
            parametersJson = "{}",
            triggeredBy = userId,
            triggeredVia = ExecutionTrigger.UI,
        )

    @Test
    fun `a user listing fetches page size plus one and computes hasMore from the overflow`() {
        authenticate(setOf(Scope.AUTHOR))
        val twentyOne = List(21) { record() }
        every {
            executions.findByUser(workspaceId, userId, null, null, null, null, any(), any())
        } returns twentyOne

        controller.listPartial(null, null, null, null, 0, model)

        verify {
            executions.findByUser(workspaceId, userId, null, null, null, null, limit = 21, offset = 0)
        }
        (model["executions"] as List<*>).size shouldBe 20
        model["hasMore"] shouldBe true
        model["nextOffset"] shouldBe 20
    }

    @Test
    fun `a full page exactly is the last page - nextOffset null`() {
        authenticate(setOf(Scope.AUTHOR))
        every {
            executions.findByUser(workspaceId, userId, null, null, null, null, any(), any())
        } returns List(20) { record() }

        controller.listPartial(null, null, null, null, 40, model)

        model["hasMore"] shouldBe false
        model["nextOffset"] shouldBe null
        model["offset"] shouldBe 40
    }

    @Test
    fun `status filters parse and an unknown status degrades to no filter`() {
        authenticate(setOf(Scope.ADMIN))
        every {
            executions.findAll(workspaceId, any(), any(), any(), any(), any(), any())
        } returns emptyList()

        controller.listPartial(null, " failed ", null, null, 0, model)
        verify { executions.findAll(workspaceId, null, ExecutionStatus.FAILED, null, null, limit = 21, offset = 0) }

        controller.listPartial(null, "NOT_A_STATUS", null, null, 0, model)
        verify { executions.findAll(workspaceId, null, null, null, null, limit = 21, offset = 0) }
    }

    @Test
    fun `time bounds are parsed as instants and forwarded`() {
        authenticate(setOf(Scope.ADMIN))
        every {
            executions.findAll(workspaceId, any(), any(), any(), any(), any(), any())
        } returns emptyList()
        val after = Instant.parse("2026-09-01T00:00:00Z")
        val before = Instant.parse("2026-09-02T00:00:00Z")

        controller.listPartial(null, null, after.toString(), before.toString(), 0, model)

        verify { executions.findAll(workspaceId, null, null, after, before, limit = 21, offset = 0) }
    }

    @Test
    fun `an admin listing goes through findAll with the pipeline filter`() {
        authenticate(setOf(Scope.ADMIN))
        val pipelineId = UUID.randomUUID()
        every {
            executions.findAll(workspaceId, any(), any(), any(), any(), any(), any())
        } returns emptyList()

        controller.listPartial(pipelineId, null, null, null, 0, model)

        verify {
            executions.findAll(workspaceId, pipelineId, null, null, null, limit = 21, offset = 0)
        }
    }

    @Test
    fun `the partial view name is returned`() {
        authenticate(setOf(Scope.AUTHOR))
        every {
            executions.findByUser(workspaceId, userId, null, null, null, null, any(), any())
        } returns emptyList()

        controller.listPartial(null, null, null, null, 0, model) shouldBe "partials/executions"
    }
}
