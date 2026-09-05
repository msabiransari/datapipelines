package co.datapipelines.web.pipelines

import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.executor.ExecuteRequest
import co.datapipelines.executor.ExecutionResult
import co.datapipelines.executor.ExecutionTrigger
import co.datapipelines.mcp.McpExecutionRunner

/**
 * `mcp-server`'s [McpExecutionRunner] (P7) — an agent-initiated execution, run through
 * [RecordingExecutionRunner] with `triggered_via = MCP`.
 *
 * The whole body used to live here; 074 needed the identical thing under a different trigger for
 * published endpoints, so the trigger became a parameter and the body moved. What is left is the
 * adapter between `mcp-server`'s port (which speaks [WorkspaceContext]) and the shared runner
 * (which takes a workspace id and a trigger) — deliberately thin, so "how an in-process execution
 * is recorded" has exactly one implementation.
 */
class McpRecordingExecutionRunner(
    private val runner: RecordingExecutionRunner,
) : McpExecutionRunner {
    override suspend fun run(
        request: ExecuteRequest,
        workspace: WorkspaceContext,
    ): ExecutionResult = runner.run(request, workspace.id, ExecutionTrigger.MCP)
}
