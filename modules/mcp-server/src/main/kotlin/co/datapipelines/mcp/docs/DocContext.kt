package co.datapipelines.mcp.docs

/**
 * The values the narrative prose names that live in configuration — the typed render context
 * (record §3.3). A narrative resource writes `${key}` where a deployment-configurable number
 * would otherwise be hand-typed, and [DocRenderer] substitutes from this class; an unknown key
 * or an unsubstituted placeholder fails the render, so a renamed key is a compile error here
 * rather than a stale number in the served manual.
 *
 * Every field is sourced from the [executor] configuration at boot and carries the shipped
 * default, so the rendered text is a pure function of the build and the boot configuration
 * (P1: a deployment serves the manual it runs). The numbers below are exactly the ones the
 * prose quotes whose authority is `docs/configuration.md`; numbers whose authority is code
 * (the name grammar, the citation cap, the preview-row cap) stay literal in the prose.
 */
data class DocContext(
    val executionTimeoutSeconds: Long = 600,
    val nodeTimeoutSeconds: Long = 300,
    val nodeTimeoutMaxSeconds: Int = 900,
    val nodeQueryTimeoutSeconds: Int = 60,
) {
    /** The `${key}` substitution table — one entry per field, spelled as the prose writes it. */
    val placeholders: Map<String, String> =
        mapOf(
            "execution_timeout_seconds" to executionTimeoutSeconds.toString(),
            "node_timeout_seconds" to nodeTimeoutSeconds.toString(),
            "node_timeout_max_seconds" to nodeTimeoutMaxSeconds.toString(),
            "node_query_timeout_seconds" to nodeQueryTimeoutSeconds.toString(),
        )

    companion object {
        /** The context of the shipped defaults — the golden tests' fixed render context. */
        val DEFAULTS: DocContext = DocContext()

        /** Builds the boot context from the executor configuration the server runs with. */
        fun of(executor: co.datapipelines.executor.ExecutorConfig): DocContext =
            DocContext(
                executionTimeoutSeconds = executor.executionTimeoutSeconds,
                nodeTimeoutSeconds = executor.nodeTimeoutSeconds,
                nodeTimeoutMaxSeconds = executor.nodeTimeoutMaxSeconds,
                nodeQueryTimeoutSeconds = executor.nodeQueryTimeoutSeconds,
            )
    }
}
