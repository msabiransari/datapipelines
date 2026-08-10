package co.datapipelines.mcp

import io.modelcontextprotocol.spec.McpSchema
import java.util.UUID

/**
 * The prompt surface (mcp-server.md §8) — exactly two prompts.
 *
 * **Admission rule (§8):** a prompt ships only if every step it instructs the agent to take is
 * achievable with the 15 tools of §6.1 and the resources of §7. `create_pipeline_for_question`
 * (§8.2) is therefore **deliberately absent**: its step 2 needs schema introspection
 * (`datasources_get_schema` / `_get_tables` / `_get_columns`), which v1 does not have, so the
 * prompt would advertise a capability the server lacks and dead-end the agent — worse than
 * offering nothing. It returns as a v1.1 item together with those tools (ROADMAP §2).
 *
 * Every step of both prompts below names a v1 tool, and `analyze_pipeline` is read-only: it never
 * instructs the agent to modify anything.
 */
class McpPromptCatalog {
    /** The two admissible prompts, in `prompts/list` order. */
    val prompts: List<McpSchema.Prompt> = listOf(ANALYZE_PIPELINE, DEBUG_FAILED_EXECUTION)

    /** Renders `prompts/get` for [name], or null when the prompt is not one of the two. */
    fun get(
        name: String,
        arguments: Map<String, Any?>,
    ): McpSchema.GetPromptResult? =
        when (name) {
            ANALYZE_PIPELINE_NAME -> result(ANALYZE_PIPELINE, analyzeText(argument(arguments, "pipeline_id")))
            DEBUG_FAILED_EXECUTION_NAME -> result(DEBUG_FAILED_EXECUTION, debugText(argument(arguments, "execution_id")))
            else -> null
        }

    /**
     * A prompt argument, parsed as the UUID both §8.1 and §8.3 declare it to be.
     *
     * `prompts/get` arguments get no schema validation from the protocol, and the value is
     * interpolated into a `PromptMessage` the agent consumes **as instructions** — so an unvalidated
     * string is a prompt-injection carrier whenever a client lets a non-operator fill it in. A UUID
     * cannot carry an instruction; anything that is not one is `-32602`, mirroring
     * [McpArguments.requiredUuid].
     */
    private fun argument(
        arguments: Map<String, Any?>,
        name: String,
    ): UUID {
        val raw = arguments[name]?.toString()?.takeIf { it.isNotBlank() } ?: throw McpArguments.invalidParams("Missing argument '$name'.")
        return runCatching { UUID.fromString(raw) }.getOrElse { throw McpArguments.invalidParams("Argument '$name' must be a UUID.") }
    }

    private fun result(
        prompt: McpSchema.Prompt,
        text: String,
    ): McpSchema.GetPromptResult =
        McpSchema.GetPromptResult
            .builder(listOf(McpSchema.PromptMessage(McpSchema.Role.USER, McpSchema.TextContent.builder(text).build())))
            .description(prompt.description())
            .build()

    /** §8.1 — every step uses a v1 tool, and nothing in it modifies anything. */
    private fun analyzeText(pipelineId: UUID): String =
        """
        Analyze the datapipelines.co pipeline $pipelineId. This is a READ-ONLY review: do not
        create, update or execute anything.

        1. Call pipelines_get with id "$pipelineId" to read the full pipeline body.
        2. For every node, call templates_get with the node's template {id, version} to read the
           SQL template it references, including its imports array.
        3. For each template, call templates_render with a representative context built from the
           pipeline's declared parameters (defaults applied) to see the SQL that will actually run.
        4. Check the rendered SQL against the target dialect of the node's datasource — call
           datasources_get with the node's source name to confirm which dialect that is.
        5. Look for structural and performance problems: nodes that could run in parallel but are
           serialized by an unnecessary depends_on edge, tempdb tables read by several downstream
           nodes without an index, SELECT * over wide tables, missing filters on the parameters the
           pipeline declares, and a caller node returning far more rows than a caller needs.
        6. Report: what the pipeline does, node by node; the issues found, most severe first, each
           with the node id and the evidence; and the concrete change you would make for each.
        """.trimIndent()

    /** §8.3 — every step uses a v1 tool. */
    private fun debugText(executionId: UUID): String =
        """
        Diagnose why datapipelines.co execution $executionId failed.

        1. Call executions_get with execution_id "$executionId" to read its status, node_stats, the
           failed node id, the error object, and the parameters the run was given.
        2. Call executions_list filtered to the same pipeline_id to see whether recent executions of
           this pipeline succeeded — a first-ever failure and a newly-broken pipeline are different
           problems.
        3. Call pipelines_get for that pipeline to read the failing node's definition, then
           templates_get for the template that node references.
        4. Call templates_render with the failing execution's parameters to see the exact SQL that
           node produced.
        5. If the error points at connectivity, call datasources_test with the node's source name to
           check the datasource is reachable.
        6. Report the root cause, the evidence for it (error code, node id, rendered SQL), and the
           fix — a template change, a parameter change, or an environment problem. Do not modify
           anything without being asked.
        """.trimIndent()

    companion object {
        const val ANALYZE_PIPELINE_NAME: String = "analyze_pipeline"
        const val DEBUG_FAILED_EXECUTION_NAME: String = "debug_failed_execution"

        /** §8.1. */
        val ANALYZE_PIPELINE: McpSchema.Prompt =
            McpSchema.Prompt
                .builder(ANALYZE_PIPELINE_NAME)
                .description(
                    "Guide the agent through analyzing a pipeline's structure, identifying potential issues, and " +
                        "suggesting improvements.",
                ).arguments(listOf(McpSchema.PromptArgument("pipeline_id", null, "The pipeline to analyze (UUID).", true)))
                .build()

        /** §8.3. */
        val DEBUG_FAILED_EXECUTION: McpSchema.Prompt =
            McpSchema.Prompt
                .builder(DEBUG_FAILED_EXECUTION_NAME)
                .description("Guide the agent through diagnosing why an execution failed.")
                .arguments(
                    listOf(McpSchema.PromptArgument("execution_id", null, "The failed execution to diagnose (UUID).", true)),
                ).build()
    }
}
