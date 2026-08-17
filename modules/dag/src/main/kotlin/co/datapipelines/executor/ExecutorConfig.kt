package co.datapipelines.executor

/**
 * The executor's resolved runtime settings (dag-executor.md §5.3).
 *
 * A plain immutable data class rather than a Spring `@ConfigurationProperties`, following the
 * pattern `H2StagingProperties` established: the assembling layer (`app`) binds
 * `datapipelines.executor.*`, `datapipelines.result.*`, `datapipelines.staging.h2.max-memory-mb`,
 * `datapipelines.sse.heartbeat-interval-seconds` and
 * `datapipelines.pipelines.max-composition-depth` and hands the executor **already-resolved**
 * effective values. Nothing here re-reads global config mid-execution, and no key is defined
 * here — [configuration.md](../../../../../../../docs/configuration.md) is the only authority
 * (D8). The defaults mirror §3.2 / §3.5 / §3.16 so a directly-constructed instance behaves as the
 * documented out-of-the-box configuration.
 *
 * @property maxParallelNodes `datapipelines.executor.max-parallel-nodes`.
 * @property maxConcurrentExecutionsPerUser `datapipelines.executor.max-concurrent-executions-per-user`.
 * @property maxConcurrentExecutionsGlobal `datapipelines.executor.max-concurrent-executions-global`.
 * @property nodeQueryTimeoutSeconds `datapipelines.executor.node-query-timeout-seconds`; a
 *   datasource's own `query_timeout_seconds` overrides it per [queryTimeoutSecondsFor].
 * @property executionTimeoutSeconds `datapipelines.executor.execution-timeout-seconds`.
 * @property stagingMaxMemoryMb the global `datapipelines.staging.h2.max-memory-mb`; a pipeline's
 *   `settings.tempdb.config.max_memory_mb` overrides it for that pipeline (D6).
 * @property cancelPollIntervalSeconds `datapipelines.sse.heartbeat-interval-seconds` — the
 *   cadence at which the executing instance re-reads the Redis cancel flag (§8.3.1).
 * @property maxCompositionDepth `datapipelines.pipelines.max-composition-depth` — the deepest
 *   PIPELINE-node composition chain admitted (checked at save time and again at run time).
 */
data class ExecutorConfig(
    val maxParallelNodes: Int = 4,
    val maxConcurrentExecutionsPerUser: Int = 10,
    val maxConcurrentExecutionsGlobal: Int = 100,
    val nodeQueryTimeoutSeconds: Int = 60,
    val executionTimeoutSeconds: Long = 600,
    val stagingMaxMemoryMb: Long = 1024,
    val cancelPollIntervalSeconds: Long = 15,
    val maxCompositionDepth: Int = 5,
    val result: ResultConfig = ResultConfig(),
) {
    init {
        require(maxParallelNodes > 0) { "maxParallelNodes must be positive, was $maxParallelNodes" }
        require(maxConcurrentExecutionsPerUser > 0) { "maxConcurrentExecutionsPerUser must be positive" }
        require(maxConcurrentExecutionsGlobal > 0) { "maxConcurrentExecutionsGlobal must be positive" }
        // Strictly positive, unlike a *datasource's* own override where 0 legitimately means "no
        // limit" (F16). The executor-wide default is the backstop that bounds every node with no
        // datasource setting — and it is the only thing bounding the timeout overshoot of §5.3 —
        // so a 0 here silently removes the last per-statement limit in the system.
        require(nodeQueryTimeoutSeconds > 0) { "nodeQueryTimeoutSeconds must be positive, was $nodeQueryTimeoutSeconds" }
        require(executionTimeoutSeconds > 0) { "executionTimeoutSeconds must be positive" }
        require(stagingMaxMemoryMb > 0) { "stagingMaxMemoryMb must be positive" }
        require(cancelPollIntervalSeconds > 0) { "cancelPollIntervalSeconds must be positive" }
        require(maxCompositionDepth >= 1) { "maxCompositionDepth must be >= 1, was $maxCompositionDepth" }
    }

    /**
     * The per-statement timeout for one node, in the one order
     * [datasources §5.5](../../../../../../../docs/datasources.md) defines: the datasource's own
     * `query_timeout_seconds` when set, otherwise `node-query-timeout-seconds`.
     *
     * @param datasourceQueryTimeoutSeconds the node's datasource setting, or null for a `tempdb`
     *   node (tempdb is not a datasource and has no per-datasource override).
     */
    fun queryTimeoutSecondsFor(datasourceQueryTimeoutSeconds: Int?): Int = datasourceQueryTimeoutSeconds ?: nodeQueryTimeoutSeconds

    /**
     * The per-execution render output budget passed to `TemplateEngine.render(ref, ctx, budget)`.
     *
     * Rendered SQL that is larger than the execution's whole staging memory budget cannot be
     * usefully executed, so the staging budget (Staging §8) bounds it — expressed in `Char`s,
     * since that is what the engine's `BoundedWriter` counts.
     *
     * The result is additionally capped at [ENGINE_OUTPUT_BACKSTOP_CHARS], which mirrors the
     * engine-wide backstop `TemplatesConfiguration` constructs the engine with. Without the cap,
     * passing an explicit budget would *raise* the ceiling on every default deployment
     * (1024 MB ≈ 536M chars > the 64M backstop) — wiring a per-execution budget must never
     * weaken the global one.
     *
     * @param effectiveStagingMaxMemoryMb the pipeline's `max_memory_mb` override, or
     *   [stagingMaxMemoryMb].
     */
    fun renderOutputBudgetChars(effectiveStagingMaxMemoryMb: Long = stagingMaxMemoryMb): Long =
        minOf(effectiveStagingMaxMemoryMb * BYTES_PER_MB / Char.SIZE_BYTES, ENGINE_OUTPUT_BACKSTOP_CHARS)

    companion object {
        private const val BYTES_PER_MB = 1024L * 1024L

        /**
         * 64M characters — the engine-wide backstop `TemplatesConfiguration` passes to
         * `TemplateEngine`'s constructor. Mirrored (not imported: it is that class's private
         * constant) so the per-execution budget can never exceed it.
         */
        const val ENGINE_OUTPUT_BACKSTOP_CHARS: Long = 64L * 1024 * 1024
    }
}

/**
 * Result-delivery settings — `datapipelines.result.*`
 * ([Configuration §3.5](../../../../../../../docs/configuration.md)), read by the result store
 * (§6.4.2) and by `data_ready` construction.
 */
data class ResultConfig(
    val ttlDefaultSeconds: Long = 300,
    val ttlMinSeconds: Long = 60,
    val ttlMaxSeconds: Long = 3600,
    val maxSizeBytes: Long = 104_857_600,
    val pageSizeRows: Int = 1000,
    val pageMaxRows: Int = 100_000,
) {
    init {
        require(ttlMinSeconds > 0 && ttlMaxSeconds >= ttlMinSeconds) { "result TTL clamp is inverted or non-positive" }
        require(ttlDefaultSeconds > 0) { "ttlDefaultSeconds must be positive" }
        require(maxSizeBytes > 0) { "maxSizeBytes must be positive" }
        require(pageSizeRows in 1..pageMaxRows) { "pageSizeRows must be in 1..pageMaxRows" }
    }

    /**
     * `clamp(DP-Result-TTL-Seconds, ttl-min, ttl-max)`, defaulting to `ttl-default` when the
     * client sent no header ([REST API §7.4](../../../../../../../docs/rest-api.md)).
     *
     * The clamp is non-negotiable: an unbounded client-controlled TTL would let one caller pin
     * gigabytes in Redis.
     */
    fun effectiveTtlSeconds(requested: Long?): Long = (requested ?: ttlDefaultSeconds).coerceIn(ttlMinSeconds, ttlMaxSeconds)

    /** The cursor `limit` a request resolves to: default page size, capped at `page-max-rows`. */
    fun effectiveLimit(requested: Int?): Int = (requested ?: pageSizeRows).coerceIn(1, pageMaxRows)
}
