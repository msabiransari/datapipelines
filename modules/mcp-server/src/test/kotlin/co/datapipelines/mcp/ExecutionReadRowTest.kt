package co.datapipelines.mcp

import co.datapipelines.auth.Permission
import co.datapipelines.auth.PermissionResolver
import co.datapipelines.auth.PermissionResolverInstallation
import co.datapipelines.auth.RolePermissionsResolver
import co.datapipelines.auth.WorkspaceRole
import co.datapipelines.executor.ExecutionRepository
import co.datapipelines.executor.ExecutionTrigger
import co.datapipelines.executor.ResultStore
import co.datapipelines.executor.ResultUrlFactory
import co.datapipelines.typesystem.DatapipelinesException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * #293 item 2: each execution tool's visibility asks the tool's OWN catalogue row.
 *
 * `visibleTo`'s scheduled-run branch asked `execution.read` for all three execution tools, while
 * `executions_get_result`'s row is `execution.result.read`. No role today holds one without the
 * other, so nothing was exposed — which is exactly why no role walk can see the drift. The
 * permission seam can: a grant holding the author's column minus `execution.result.read` (the
 * isolated-permission witness shape, security-assurance record §7.1) reads a scheduled run's
 * metadata and must NOT read its result.
 */
class ExecutionReadRowTest {
    /** The author's column without `execution.result.read` — the grant no role gives. */
    private class AuthorWithoutResultRead : PermissionResolver {
        override fun holds(
            workspaceId: UUID?,
            role: WorkspaceRole?,
            superAdmin: Boolean,
            permission: Permission,
        ): Boolean =
            permission != Permission.EXECUTION_RESULT_READ && RolePermissionsResolver.holds(workspaceId, role, superAdmin, permission)
    }

    private val executions = mockk<ExecutionRepository>()
    private val ctx = McpFixtures.ctx()

    /** Another member's run that a schedule fired, with no caller result (the empty-result path needs no store). */
    private val scheduled =
        McpFixtures
            .executionRecord(executedBy = McpFixtures.OTHER_USER)
            .copy(triggeredVia = ExecutionTrigger.SCHEDULE, resultRowCount = null)

    private val args = McpArguments(mapOf("execution_id" to McpFixtures.EXECUTION_ID.toString()))

    @Test
    fun `get_result asks execution result read - a grant without it finds a scheduled run not found`() {
        every { executions.findById(any(), McpFixtures.EXECUTION_ID) } returns scheduled
        val tool = ExecutionsGetResultTool(executions, mockk<ResultStore>(), mockk<ResultUrlFactory>())

        PermissionResolverInstallation(AuthorWithoutResultRead()).use {
            // Pre-fix: the branch asked execution.read, so this returned the (empty) result.
            shouldThrow<DatapipelinesException> { tool.call(args, ctx) }.code shouldContain "not_found"
        }
    }

    @Test
    fun `the same grant still reads the scheduled run's metadata - executions_get's row is execution read`() {
        every { executions.findById(any(), McpFixtures.EXECUTION_ID) } returns scheduled

        PermissionResolverInstallation(AuthorWithoutResultRead()).use {
            val metadata = ExecutionsGetTool(executions).call(args, ctx) as Map<*, *>
            metadata["execution_id"] shouldBe McpFixtures.EXECUTION_ID.toString()
        }
    }

    @Test
    fun `with the production resolver the result is readable - the witness changes nothing for real roles`() {
        every { executions.findById(any(), McpFixtures.EXECUTION_ID) } returns scheduled
        val tool = ExecutionsGetResultTool(executions, mockk<ResultStore>(), mockk<ResultUrlFactory>())
        (tool.call(args, ctx) as Map<*, *>)["total_rows"] shouldBe 0
    }
}
