package co.datapipelines.web.ui

import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.Scope
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.executor.AbortReason
import co.datapipelines.executor.ExecutionCancellationService
import co.datapipelines.executor.ExecutionRecord
import co.datapipelines.executor.ExecutionRepository
import co.datapipelines.executor.ExecutionStatus
import co.datapipelines.executor.ExecutionTrigger
import co.datapipelines.executor.ResultPage
import co.datapipelines.executor.ResultStore
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.ColumnSchema
import co.datapipelines.web.api.ApiException
import co.datapipelines.web.executions.ResultCursor
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.ui.ExtendedModelMap
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.UUID

/**
 * [ExecutionDetailPartialController] — the result fragment's cursor/paging/error contract
 * and the cancel gate, not the templates (ExecutionsPartialRenderTest renders them).
 *
 * The behaviors pinned: the 057/T85 failure branch (a FAILED execution's result area shows
 * the structured failure record, not a bare code), the expired-result card, the cancel
 * scope gate (execute, not merely read), the not-found-that-is-really-invisible (404 for
 * another user's execution), and the conflict on a non-running row.
 */
class ExecutionDetailPartialControllerTest {
    private val executions = mockk<ExecutionRepository>()
    private val resultStore = mockk<ResultStore>()
    private val cursor = mockk<ResultCursor>()
    private val cancellation = mockk<ExecutionCancellationService>(relaxed = true)
    private val controller = ExecutionDetailPartialController(executions, resultStore, cursor, cancellation)

    private val userId = UUID.randomUUID()
    private val workspaceId = UUID.randomUUID()
    private val executionId = UUID.randomUUID()
    private val model = ExtendedModelMap()

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    private fun authenticate(
        scopes: Set<Scope>,
        asUser: UUID = userId,
    ) {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(
                AuthenticatedPrincipal(
                    asUser,
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

    private fun record(status: ExecutionStatus) =
        ExecutionRecord(
            executionId = executionId,
            pipelineId = UUID.randomUUID(),
            pipelineVersion = 1,
            status = status,
            parametersJson = "{}",
            triggeredBy = userId,
            triggeredVia = ExecutionTrigger.UI,
            failedNodeId = "extract",
            errorJson = """{"code":"node.failed","message":"boom"}""",
        )

    private fun page(
        rows: Int,
        total: Long,
    ) = ResultPage(
        executionId = executionId,
        schema = listOf(ColumnSchema("id", co.datapipelines.typesystem.LogicalType.INTEGER)),
        rows = List(rows) { listOf<Any?>(it) },
        offset = 0,
        limit = 20,
        totalRows = total,
        expiresAt = Instant.now().plusSeconds(60),
    )

    // ------------------------------------------------------------ result

    @Test
    fun `a readable execution pages the stored result with next and prev offsets`() {
        authenticate(setOf(Scope.READ))
        every { cursor.readable(executionId, any()) } returns record(ExecutionStatus.SUCCESS)
        every { resultStore.keyFor(executionId) } returns "key"
        every { resultStore.page("key", 0, 20) } returns page(20, 45)

        controller.result(executionId, 0, model)

        model["totalRows"] shouldBe 45L
        model["hasMore"] shouldBe true
        model["nextOffset"] shouldBe 20L
        model["prevOffset"] shouldBe null
        model["executionId"] shouldBe executionId
    }

    @Test
    fun `an expired result renders the expired card, not an error page`() {
        authenticate(setOf(Scope.READ))
        every { cursor.readable(executionId, any()) } returns record(ExecutionStatus.SUCCESS)
        every { resultStore.keyFor(executionId) } returns "key"
        every { resultStore.page("key", any(), any()) } returns null

        controller.result(executionId, 0, model) shouldBe "partials/execution-result-error"
        model["error"] shouldBe "result.expired"
    }

    @Test
    fun `a FAILED execution renders its structured failure record - not a bare code`() {
        authenticate(setOf(Scope.ADMIN))
        every { cursor.readable(executionId, any()) } throws
            ApiException(PipelineErrorCodes.Result.EXECUTION_FAILED, "failed", mapOf("execution_id" to executionId))
        every { executions.findById(workspaceId, executionId) } returns record(ExecutionStatus.FAILED)

        controller.result(executionId, 0, model) shouldBe "partials/execution-result-error"
        model["failedNodeId"] shouldBe "extract"
        model["error"] shouldNotBe "result.expired"
    }

    @Test
    fun `a non-FAILED refusal keeps the bare code card`() {
        authenticate(setOf(Scope.READ))
        every { cursor.readable(executionId, any()) } throws
            ApiException(PipelineErrorCodes.Result.EXECUTION_NOT_FOUND, "nope")

        controller.result(executionId, 0, model) shouldBe "partials/execution-result-error"
        model["error"] shouldBe PipelineErrorCodes.Result.EXECUTION_NOT_FOUND
        model["failedNodeId"] shouldBe null
    }

    // ------------------------------------------------------------ cancel

    @Test
    fun `cancel without execute scope is forbidden even with read`() {
        authenticate(setOf(Scope.READ))
        val error =
            io.kotest.assertions.throwables.shouldThrow<ResponseStatusException> {
                controller.cancel(executionId, model)
            }
        error.statusCode shouldBe org.springframework.http.HttpStatus.FORBIDDEN
        verify(exactly = 0) { cancellation.cancel(any(), any()) }
    }

    @Test
    fun `cancel on an invisible execution is a 404 - never a hint it exists`() {
        authenticate(setOf(Scope.EXECUTE), asUser = UUID.randomUUID())
        every { executions.findById(workspaceId, executionId) } returns record(ExecutionStatus.RUNNING)

        val error =
            io.kotest.assertions.throwables.shouldThrow<ResponseStatusException> {
                controller.cancel(executionId, model)
            }
        error.statusCode shouldBe org.springframework.http.HttpStatus.NOT_FOUND
    }

    @Test
    fun `cancel on a finished execution is a conflict`() {
        authenticate(setOf(Scope.EXECUTE))
        every { executions.findById(workspaceId, executionId) } returns record(ExecutionStatus.SUCCESS)

        val error =
            io.kotest.assertions.throwables.shouldThrow<ResponseStatusException> {
                controller.cancel(executionId, model)
            }
        error.statusCode shouldBe org.springframework.http.HttpStatus.CONFLICT
        verify(exactly = 0) { cancellation.cancel(any(), any()) }
    }

    @Test
    fun `cancel on a running execution cancels with CLIENT-chosen reason and renders the fragment`() {
        authenticate(setOf(Scope.EXECUTE))
        every { executions.findById(workspaceId, executionId) } returns record(ExecutionStatus.RUNNING)

        controller.cancel(executionId, model) shouldBe "partials/execution-cancelled"

        verify(exactly = 1) { cancellation.cancel(executionId, AbortReason.CANCELLED) }
        model["cancelled"] shouldBe true
    }
}
