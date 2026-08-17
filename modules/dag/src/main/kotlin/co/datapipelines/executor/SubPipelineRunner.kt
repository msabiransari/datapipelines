package co.datapipelines.executor

/**
 * Executes the child pipeline a PIPELINE node points at (design 2026-08-13-pipeline-node-type
 * §4.1).
 *
 * The child runs as a real, separate execution — own execution record, own tempdb, own stats —
 * started through the internal execution service, never HTTP. The port lives in `dag` so
 * [NodeRunner] can dispatch PIPELINE nodes before render/source resolution; the implementation
 * belongs to the assembling layers (web/app), which own the execution service and the
 * repository the pinned child body is loaded from.
 */
fun interface SubPipelineRunner {
    /** Runs [node]'s pinned child as a separate child execution and returns the node's result. */
    suspend fun run(
        node: ExecutableNode,
        ctx: NodeExecutionContext,
    ): NodeResult
}
