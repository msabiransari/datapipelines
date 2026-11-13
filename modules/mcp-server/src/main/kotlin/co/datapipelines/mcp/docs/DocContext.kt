package co.datapipelines.mcp.docs

import co.datapipelines.datasources.DuckDbVersion

/**
 * The values the narrative prose names that live in configuration — the typed render context
 * (record §3.3). A narrative resource writes `${key}` where a deployment-configurable number
 * would otherwise be hand-typed, and [DocRenderer] substitutes from this class; an unknown key
 * or an unsubstituted placeholder fails the render, so a renamed key is a compile error here
 * rather than a stale number in the served manual.
 *
 * Every field is sourced from the configuration the server runs with at boot and carries the
 * shipped default, so the rendered text is a pure function of the build and the boot
 * configuration (P1: a deployment serves the manual it runs). The numbers below are exactly
 * the ones the prose quotes whose authority is `docs/configuration.md`; numbers whose
 * authority is code (the name grammar, the citation cap, the preview-row cap) stay literal
 * in the prose. The executor timeouts come from [co.datapipelines.executor.ExecutorConfig],
 * the script budgets from [ScriptDocValues] (780) — both REQUIRED of whoever assembles the
 * `docSet` bean. The driver versions are the one code-sourced substitution (from
 * [DuckDbVersion], the packaged build input, not boot configuration): the prose names the
 * engine version the build ships, and the version drift guard's re-probe list
 * (deployment §3.1) is what keeps that prose true across a bump.
 */
data class DocContext(
    val executionTimeoutSeconds: Long = 600,
    val nodeTimeoutSeconds: Long = 300,
    val nodeTimeoutMaxSeconds: Int = 900,
    val nodeQueryTimeoutSeconds: Int = 60,
    val scriptMaxInputRows: Long = 100_000,
    val scriptPoolSize: Int = 4,
    val scriptPoolQueue: Int = 64,
    val scriptMaxDepth: Int = 100,
    val duckdbCore: String = DuckDbVersion.CURRENT.core,
) {
    /** The `${key}` substitution table — one entry per field, spelled as the prose writes it. */
    val placeholders: Map<String, String> =
        mapOf(
            "execution_timeout_seconds" to executionTimeoutSeconds.toString(),
            "node_timeout_seconds" to nodeTimeoutSeconds.toString(),
            "node_timeout_max_seconds" to nodeTimeoutMaxSeconds.toString(),
            "node_query_timeout_seconds" to nodeQueryTimeoutSeconds.toString(),
            "script_max_input_rows" to scriptMaxInputRows.toString(),
            "script_pool_size" to scriptPoolSize.toString(),
            "script_pool_queue" to scriptPoolQueue.toString(),
            "script_max_depth" to scriptMaxDepth.toString(),
            "duckdb_core" to duckdbCore,
        )

    companion object {
        /** The context of the shipped defaults — the golden tests' fixed render context. */
        val DEFAULTS: DocContext = DocContext()

        /** Builds the boot context from the executor and script configuration the server runs with. */
        fun of(
            executor: co.datapipelines.executor.ExecutorConfig,
            script: ScriptDocValues,
        ): DocContext =
            DocContext(
                executionTimeoutSeconds = executor.executionTimeoutSeconds,
                nodeTimeoutSeconds = executor.nodeTimeoutSeconds,
                nodeTimeoutMaxSeconds = executor.nodeTimeoutMaxSeconds,
                nodeQueryTimeoutSeconds = executor.nodeQueryTimeoutSeconds,
                scriptMaxInputRows = script.maxInputRows,
                scriptPoolSize = script.poolSize,
                scriptPoolQueue = script.poolQueue,
                scriptMaxDepth = script.maxDepth,
            )
    }
}
