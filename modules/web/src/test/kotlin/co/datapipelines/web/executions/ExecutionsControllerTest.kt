package co.datapipelines.web.executions

import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.Scope
import co.datapipelines.executor.AbortReason
import co.datapipelines.executor.ExecutionCancellationService
import co.datapipelines.executor.ExecutionRecord
import co.datapipelines.executor.ExecutionRepository
import co.datapipelines.executor.ExecutionStatus
import co.datapipelines.executor.ExecutionTrigger
import co.datapipelines.web.api.ApiException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import java.time.Instant
import java.util.UUID

/**
 * The ownership rules of §10 (carry-forward #2): a non-owner's cancel/get is a 404, never a 403;
 * cancelling a terminal execution is `409 pipeline.execution.not_running`; the admin listing reads
 * `findAll`, a user's `findByUser`.
 */
class ExecutionsControllerTest {
    private val executions = mockk<ExecutionRepository>()
    private val cancellation = mockk<ExecutionCancellationService>()
    private val controller =
        ExecutionsController(
            executions = executions,
            cancellation = cancellation,
            cursor = mockk(),
            resultStore = mockk(),
            resultUrls = mockk(),
            streamer = mockk(),
        )

    private val owner = UUID.randomUUID()
    private val executionId = UUID.randomUUID()

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    private fun record(
        status: ExecutionStatus,
        triggeredBy: UUID = owner,
    ) = ExecutionRecord(
        executionId = executionId,
        pipelineId = UUID.randomUUID(),
        pipelineVersion = 1,
        status = status,
        parametersJson = "{}",
        triggeredBy = triggeredBy,
        triggeredVia = ExecutionTrigger.REST,
        startedAt = Instant.parse("2026-08-05T14:30:00Z"),
    )

    private fun authenticate(
        userId: UUID,
        scopes: Set<Scope>,
    ) {
        val principal = AuthenticatedPrincipal(userId, "a@b.c", "A", scopes, AuthMethod.API_KEY, "dpk_x")
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(principal, null, emptyList())
    }

    @Test
    fun `cancel by the owner requests cancellation and returns 204`() {
        authenticate(owner, setOf(Scope.EXECUTE))
        every { executions.findById(executionId) } returns record(ExecutionStatus.RUNNING)
        every { cancellation.cancel(executionId, AbortReason.CANCELLED) } returns true

        controller.cancel(executionId)

        verify(exactly = 1) { cancellation.cancel(executionId, AbortReason.CANCELLED) }
    }

    @Test
    fun `cancel by a non-owner is a 404 and never reaches the cancellation service`() {
        authenticate(UUID.randomUUID(), setOf(Scope.EXECUTE))
        every { executions.findById(executionId) } returns record(ExecutionStatus.RUNNING)

        shouldThrow<ApiException> { controller.cancel(executionId) }.code shouldBe "result.execution_not_found"
        verify(exactly = 0) { cancellation.cancel(any(), any()) }
    }

    @Test
    fun `admin may cancel any execution`() {
        authenticate(UUID.randomUUID(), setOf(Scope.ADMIN))
        every { executions.findById(executionId) } returns record(ExecutionStatus.RUNNING)
        every { cancellation.cancel(executionId, AbortReason.CANCELLED) } returns true

        controller.cancel(executionId)

        verify(exactly = 1) { cancellation.cancel(executionId, AbortReason.CANCELLED) }
    }

    @Test
    fun `cancelling a terminal execution is 409 not_running`() {
        authenticate(owner, setOf(Scope.EXECUTE))
        every { executions.findById(executionId) } returns record(ExecutionStatus.SUCCESS)

        val error = shouldThrow<ApiException> { controller.cancel(executionId) }
        error.code shouldBe "pipeline.execution.not_running"
        verify(exactly = 0) { cancellation.cancel(any(), any()) }
    }

    @Test
    fun `the user listing reads findByUser, the admin listing findAll`() {
        authenticate(owner, setOf(Scope.READ))
        every { executions.findByUser(owner, any(), any(), any(), any(), any(), any()) } returns emptyList()
        controller.list(null, null, null, null, null, null)
        verify(exactly = 1) { executions.findByUser(owner, null, null, null, null, any(), any()) }

        authenticate(UUID.randomUUID(), setOf(Scope.ADMIN))
        every { executions.findAll(any(), any(), any(), any(), any(), any()) } returns emptyList()
        controller.list(null, null, null, null, null, null)
        verify(exactly = 1) { executions.findAll(null, null, null, null, any(), any()) }
    }

    @Test
    fun `list filters are pushed into the repository query, not applied after the page cut`() {
        authenticate(owner, setOf(Scope.READ))
        val after = Instant.parse("2026-08-01T00:00:00Z")
        every {
            executions.findByUser(owner, any(), ExecutionStatus.SUCCESS, after, any(), any(), any())
        } returns listOf(record(ExecutionStatus.SUCCESS))

        val data = controller.list(null, "SUCCESS", after, null, 0, 50).data

        data.items.size shouldBe 1
        verify(exactly = 1) {
            executions.findByUser(owner, null, ExecutionStatus.SUCCESS, after, null, limit = 51, offset = 0)
        }
    }
}
