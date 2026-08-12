package co.datapipelines.mcp

import co.datapipelines.executor.ExecuteRequest
import co.datapipelines.executor.ExecutionResult

/**
 * The recording-execution port (P7 carry-forward, resolved).
 *
 * `mcp-server` cannot build a per-run executor with a recording emitter itself: the emitter and
 * the persistence machinery live in `web`, and `web` depends on this module — never the reverse
 * (module-structure §4.2). So the port is declared here and the implementation bean is supplied
 * by the assembling layer, exactly the pattern `datasources`' `DatasourceReferences` already uses.
 *
 * The contract an implementation must keep:
 * - the execution is emitted through an emitter that **records** (`pipeline_executions` +
 *   `execution_events` rows and the 1-hour Redis event log), with **no SSE stream** attached;
 * - `pipeline_executions.triggered_via` is written as `MCP` (enums.md §18);
 * - the call returns the terminal [ExecutionResult], same as `PipelineExecutor.execute`.
 *
 * When no implementation bean exists (a slice test, a docs build), [PipelineExecuteTool] falls
 * back to the shared executor bean and the execution records nothing — the pre-P7 behavior,
 * acceptable only outside the assembled application.
 */
fun interface McpExecutionRunner {
    /** Runs [request] to its terminal state through the recording emitter. */
    suspend fun run(request: ExecuteRequest): ExecutionResult
}
