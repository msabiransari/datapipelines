package co.datapipelines.mcp

import co.datapipelines.application.mcp.McpCallAudit
import co.datapipelines.executor.AbortReason
import co.datapipelines.executor.ExecutionCancellationService
import co.datapipelines.executor.ExecutionRepository
import co.datapipelines.executor.ExecutionStatus
import co.datapipelines.executor.ExecutionTrigger
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll

/**
 * `executions_cancel` (107) — the guard ladder: not-found for what the caller may not see, the
 * REST twin's not-RUNNING refusal, and the MCP-only same-credential rule (triggered via MCP, by
 * this key's user, with the audit row pairing THIS key to the execution's correlation id).
 */
class ExecutionsCancelToolTest {
    private val executions = mockk<ExecutionRepository>()
    private val cancellation = mockk<ExecutionCancellationService>()
    private val mcpCalls = mockk<McpCallAudit>()
    private val tool = ExecutionsCancelTool(executions, cancellation, mcpCalls)
    private val ctx = McpFixtures.ctx(co.datapipelines.auth.Scope.EXECUTE)

    private fun running(
        triggeredBy: java.util.UUID = McpFixtures.USER,
        triggeredVia: ExecutionTrigger = ExecutionTrigger.MCP,
    ) = McpFixtures
        .executionRecord(status = ExecutionStatus.RUNNING, triggeredBy = triggeredBy)
        .let { it.copy(triggeredVia = triggeredVia) }

    @Test
    fun `an execution this key's own MCP call started is cancelled`() {
        every { executions.findById(McpFixtures.WORKSPACE_ID, McpFixtures.EXECUTION_ID) } returns running()
        every { mcpCalls.calledByKey(McpFixtures.KEY_ID, McpFixtures.CORRELATION_ID) } returns true
        every { cancellation.cancel(McpFixtures.EXECUTION_ID, AbortReason.CANCELLED) } returns true

        val payload =
            tool.call(
                McpArguments(mapOf("execution_id" to McpFixtures.EXECUTION_ID.toString())),
                ctx,
            ) as Map<*, *>

        assertAll(
            { payload["execution_id"] shouldBe McpFixtures.EXECUTION_ID.toString() },
            { payload["status"] shouldBe "cancellation_requested" },
        )
        verify(exactly = 1) { cancellation.cancel(McpFixtures.EXECUTION_ID, AbortReason.CANCELLED) }
    }

    @Test
    fun `another user's execution is not-found, never a disclosure`() {
        every { executions.findById(McpFixtures.WORKSPACE_ID, McpFixtures.EXECUTION_ID) } returns
            running(triggeredBy = McpFixtures.OTHER_USER)

        val thrown =
            shouldThrow<DatapipelinesException> {
                tool.call(McpArguments(mapOf("execution_id" to McpFixtures.EXECUTION_ID.toString())), ctx)
            }

        thrown.code shouldBe PipelineErrorCodes.Result.EXECUTION_NOT_FOUND
        verify(exactly = 0) { cancellation.cancel(any(), any()) }
    }

    @Test
    fun `the same user's OTHER key is refused - the credential rule, not the owner rule`() {
        every { executions.findById(McpFixtures.WORKSPACE_ID, McpFixtures.EXECUTION_ID) } returns running()
        every { mcpCalls.calledByKey(McpFixtures.OTHER_KEY_ID, McpFixtures.CORRELATION_ID) } returns false

        val thrown =
            shouldThrow<DatapipelinesException> {
                tool.call(
                    McpArguments(mapOf("execution_id" to McpFixtures.EXECUTION_ID.toString())),
                    McpFixtures.ctx(co.datapipelines.auth.Scope.EXECUTE, keyId = McpFixtures.OTHER_KEY_ID),
                )
            }

        assertAll(
            { thrown.code shouldBe PipelineErrorCodes.Auth.SCOPE_INSUFFICIENT },
            { thrown.details["reason"] shouldBe "different_credential" },
        )
        verify(exactly = 0) { cancellation.cancel(any(), any()) }
    }

    @Test
    fun `a non-RUNNING execution is refused with its current status`() {
        every { executions.findById(McpFixtures.WORKSPACE_ID, McpFixtures.EXECUTION_ID) } returns
            McpFixtures.executionRecord(status = ExecutionStatus.SUCCESS)

        val thrown =
            shouldThrow<DatapipelinesException> {
                tool.call(McpArguments(mapOf("execution_id" to McpFixtures.EXECUTION_ID.toString())), ctx)
            }

        assertAll(
            { thrown.code shouldBe PipelineErrorCodes.Execution.NOT_RUNNING },
            { thrown.details["status"] shouldBe "SUCCESS" },
        )
        verify(exactly = 0) { cancellation.cancel(any(), any()) }
    }

    @Test
    fun `a REST-triggered execution of the same user is refused as started outside MCP`() {
        every { executions.findById(McpFixtures.WORKSPACE_ID, McpFixtures.EXECUTION_ID) } returns
            running(triggeredVia = ExecutionTrigger.REST)

        val thrown =
            shouldThrow<DatapipelinesException> {
                tool.call(McpArguments(mapOf("execution_id" to McpFixtures.EXECUTION_ID.toString())), ctx)
            }

        assertAll(
            { thrown.code shouldBe PipelineErrorCodes.Auth.SCOPE_INSUFFICIENT },
            { thrown.details["reason"] shouldBe "started_outside_mcp" },
        )
        verify(exactly = 0) { mcpCalls.calledByKey(any(), any()) }
        verify(exactly = 0) { cancellation.cancel(any(), any()) }
    }
}
