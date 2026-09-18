package co.datapipelines.executor

import co.datapipelines.pipeline.OrgContext
import co.datapipelines.typesystem.Dialect

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
 * @property maxConcurrentExecutionsPerInstance `datapipelines.executor.max-concurrent-executions-per-instance`
 *   — the INSTANCE-WIDE ceiling (050/R2: the counter has always been per JVM; the old
 *   `-global` name was false at N replicas, which admit N × this in total).
 * @property nodeQueryTimeoutSeconds `datapipelines.executor.node-query-timeout-seconds`; a
 *   datasource's own `query_timeout_seconds` overrides it per [queryTimeoutSecondsFor].
 * @property executionTimeoutSeconds `datapipelines.executor.execution-timeout-seconds`.
 * @property nodeTimeoutSeconds `datapipelines.executor.node-timeout-seconds` (108) — the
 *   per-node WALL-CLOCK deadline the executor owns, spanning RENDER → CONNECT → EXECUTE →
 *   STAGE → MATERIALIZE. [nodeQueryTimeoutSeconds] bounds one `execute*` call and is enforced by
 *   the DRIVER; this bounds the node and is enforced by the executor, so a driver that honours
 *   neither `queryTimeout` nor `cancel()` still cannot hold a node past its budget. A node may
 *   lower or raise it within [nodeTimeoutMaxSeconds] through `node.settings.timeout_seconds`.
 * @property nodeTimeoutMaxSeconds `datapipelines.executor.node-timeout-max-seconds` (108) — the
 *   ceiling a node's own override may not exceed; save-time validation refuses past it
 *   (`pipeline.validation.node_timeout_invalid`).
 * @property nodeQueryTimeoutMaxSeconds `datapipelines.executor.node-query-timeout-max-seconds`
 *   (156, #2) — the ceiling a pipeline's or a node's own `settings.query_timeout_seconds` may
 *   not exceed; save-time validation refuses past it (`pipeline.validation.*query_timeout_invalid`).
 * @property nodeQueryTimeoutSecondsByDialect
 *   `datapipelines.executor.node-query-timeout-seconds-by-dialect.<DIALECT>` (156, #2) — the
 *   operator's per-dialect default statement timeout, consulted when neither the node, the
 *   pipeline nor the datasource declares one ([queryTimeoutSecondsFor]). Ships with `LAKE: 180`
 *   (configuration.md §3.2): a LAKE engine over object storage re-reads cold data the flat
 *   60s application default was never sized for (#136/#141); every other dialect is unset and
 *   falls through to [nodeQueryTimeoutSeconds].
 * @property sourceFetchSize `datapipelines.executor.source-fetch-size` (108) — the JDBC
 *   `fetchSize` set on every DQL source statement, and the reason the staging memory budget is
 *   not a fiction. pgjdbc buffers the WHOLE result set in the driver unless the connection is
 *   `autoCommit=false` AND `fetchSize > 0`; before 108 the executor set neither, so a 2M-row
 *   source node's rows were all in the JVM before `stage()` saw one of them and no per-batch
 *   budget check could ever have caught it.
 * @property progressWriteIntervalSeconds `datapipelines.executor.progress-write-interval-seconds`
 *   (108 §D) — the floor between two THROTTLED progress writes for one execution. Node boundaries
 *   are not throttled; only the staging drain, which reports per batch.
 * @property heartbeatSeconds `datapipelines.executor.heartbeat-seconds` (108 §D) — how often the
 *   owning instance stamps `pipeline_executions.heartbeat_at`. The crash sweep reaps a RUNNING row
 *   whose stamp is older than three of these, so this is also what sets how fast a dead instance's
 *   rows are reaped: ~45 s, against the sixty MINUTES the age backstop alone gave.
 * @property cancelGraceSeconds `datapipelines.executor.cancel-grace-seconds` (108) — how long the
 *   executor waits, AFTER cancelling the node's statements, for a driver to actually return.
 *   Past it the node fails on schedule and the abandoned statement is logged once with the
 *   execution id (§8.3.2's residual overshoot, now bounded). Never a wait on the query itself.
 * @property stagingMaxMemoryMb the global `datapipelines.staging.h2.max-memory-mb`; a pipeline's
 *   `settings.tempdb.config.max_memory_mb` overrides it for that pipeline (D6).
 * @property cancelPollIntervalSeconds `datapipelines.sse.heartbeat-interval-seconds` — the
 *   cadence at which the executing instance re-reads the Redis cancel flag (§8.3.1).
 * @property maxCompositionDepth `datapipelines.pipelines.max-composition-depth` — the deepest
 *   PIPELINE-node composition chain admitted (checked at save time and again at run time).
 * @property errorDetail `datapipelines.executions.error-detail` (Configuration §3.11) — how
 *   much of the failure record travels: [ErrorDetail.FULL] carries the exception chain and
 *   the rendered SQL, [ErrorDetail.STRUCTURED] omits both and keeps the catalogued code,
 *   message, details and node context. Default FULL: a self-hosted product whose users are
 *   engineers (057/T85).
 * @property orgContext `datapipelines.org.*` (Configuration §3.21, calculators design §0.1) —
 *   tier 1 of every Context this executor builds. It rides here, with the executor's other
 *   already-resolved settings, rather than as a constructor argument of [PipelineExecutor]:
 *   four call sites build executors (the bean-of-record and three per-run ones) and every one
 *   of them already threads an [ExecutorConfig], so this is the seam that reaches all four
 *   without adding a field to three `web` classes that have no other interest in it.
 */
data class ExecutorConfig(
    val maxParallelNodes: Int = 4,
    val maxConcurrentExecutionsPerUser: Int = 10,
    val maxConcurrentExecutionsPerInstance: Int = 100,
    val nodeQueryTimeoutSeconds: Int = 60,
    val executionTimeoutSeconds: Long = 600,
    val nodeTimeoutSeconds: Long = 300,
    val nodeTimeoutMaxSeconds: Int = 900,
    val nodeQueryTimeoutMaxSeconds: Int = 900,
    val nodeQueryTimeoutSecondsByDialect: Map<Dialect, Int> = mapOf(Dialect.LAKE to DEFAULT_LAKE_QUERY_TIMEOUT_SECONDS),
    val cancelGraceSeconds: Long = 5,
    val sourceFetchSize: Int = 1000,
    val progressWriteIntervalSeconds: Long = 5,
    /**
     * `progress-sample-interval-seconds` (149) — the floor between two PERIODIC `node_progress`
     * samples of one operation. First entries into a state and the terminal sample are never
     * throttled by it; it bounds only the cumulative-count refresh.
     */
    val progressSampleIntervalSeconds: Long = 1,
    val heartbeatSeconds: Long = 15,
    val stagingMaxMemoryMb: Long = 1024,
    val cancelPollIntervalSeconds: Long = 15,
    val maxCompositionDepth: Int = 5,
    val errorDetail: ErrorDetail = ErrorDetail.FULL,
    val result: ResultConfig = ResultConfig(),
    val orgContext: OrgContext = OrgContext.DEFAULTS,
) {
    init {
        require(maxParallelNodes > 0) { "maxParallelNodes must be positive, was $maxParallelNodes" }
        require(maxConcurrentExecutionsPerUser > 0) { "maxConcurrentExecutionsPerUser must be positive" }
        require(maxConcurrentExecutionsPerInstance > 0) { "maxConcurrentExecutionsPerInstance must be positive" }
        // Strictly positive, unlike a *datasource's* own override where 0 legitimately means "no
        // limit" (F16). The executor-wide default is the backstop that bounds every node with no
        // datasource setting — and it is the only thing bounding the timeout overshoot of §5.3 —
        // so a 0 here silently removes the last per-statement limit in the system.
        require(nodeQueryTimeoutSeconds > 0) { "nodeQueryTimeoutSeconds must be positive, was $nodeQueryTimeoutSeconds" }
        require(executionTimeoutSeconds > 0) { "executionTimeoutSeconds must be positive" }
        // §5.3's precedence is documented, NOT enforced across keys, and the reason is worth
        // stating because the first draft of this class did enforce it and had to be reverted.
        // Every ordering a cross-key `require` would forbid is harmless: a node deadline above the
        // execution's is simply never reached (the outer bound fires first and says so), and one
        // below the statement timeout is stronger, not broken — the executor stops the node before
        // the driver would have, which is the entire point of owning a bound above the driver's.
        // What a cross-key require DOES reliably do is turn "I lowered execution-timeout-seconds
        // for this deployment" into a startup crash, and break every caller that constructs a
        // short-timeout config for a test. A constraint that refuses correct configurations to
        // prevent harmless ones is not a guard.
        require(nodeTimeoutSeconds > 0) { "nodeTimeoutSeconds must be positive, was $nodeTimeoutSeconds" }
        require(nodeTimeoutMaxSeconds > 0) { "nodeTimeoutMaxSeconds must be positive, was $nodeTimeoutMaxSeconds" }
        require(nodeQueryTimeoutMaxSeconds > 0) { "nodeQueryTimeoutMaxSeconds must be positive, was $nodeQueryTimeoutMaxSeconds" }
        nodeQueryTimeoutSecondsByDialect.forEach { (dialect, seconds) ->
            require(seconds in 1..nodeQueryTimeoutMaxSeconds) {
                "nodeQueryTimeoutSecondsByDialect[$dialect] must be a positive integer no greater than " +
                    "nodeQueryTimeoutMaxSeconds ($nodeQueryTimeoutMaxSeconds), was $seconds"
            }
        }
        require(cancelGraceSeconds > 0) { "cancelGraceSeconds must be positive, was $cancelGraceSeconds" }
        // ZERO IS LEGAL AND MEANS "DO NOT STREAM" — the operator's escape hatch (108 §B). A DQL
        // node's author SQL may legitimately be multi-statement, and pgjdbc's server-side cursor
        // path uses the extended query protocol, which does not carry multiple statements. Every
        // pipeline we know of is a single query, but a deployment that discovers otherwise on a
        // release weekend needs one env var, not a patch.
        require(sourceFetchSize >= 0) { "sourceFetchSize must not be negative, was $sourceFetchSize" }
        require(progressWriteIntervalSeconds > 0) { "progressWriteIntervalSeconds must be positive" }
        require(progressSampleIntervalSeconds > 0) { "progressSampleIntervalSeconds must be positive" }
        require(heartbeatSeconds > 0) { "heartbeatSeconds must be positive, was $heartbeatSeconds" }
        require(stagingMaxMemoryMb > 0) { "stagingMaxMemoryMb must be positive" }
        require(cancelPollIntervalSeconds > 0) { "cancelPollIntervalSeconds must be positive" }
        require(maxCompositionDepth >= 1) { "maxCompositionDepth must be >= 1, was $maxCompositionDepth" }
    }

    /**
     * The per-statement timeout for one node (156, #2; pipeline-contract §4.11/§5.3;
     * [datasources §5.5](../../../../../../../docs/datasources.md)), in precedence order:
     *
     * 1. the node's own `settings.query_timeout_seconds` ([nodeQueryTimeoutSecondsOverride])
     * 2. the pipeline's `settings.query_timeout_seconds` ([pipelineQueryTimeoutSecondsOverride])
     * 3. the datasource's own `query_timeout_seconds` ([datasourceQueryTimeoutSeconds]) — `0` is
     *    a legitimate "no timeout" datasource setting and is distinct from absent
     * 4. the operator's per-dialect default ([nodeQueryTimeoutSecondsByDialect])
     * 5. the flat application default ([nodeQueryTimeoutSeconds])
     *
     * @param datasourceQueryTimeoutSeconds the node's datasource setting, or null for a `tempdb`
     *   node (tempdb is not a datasource and has no per-datasource override; tempdb nodes skip
     *   tier 3 entirely and fall through tiers 1/2 straight to 4/5, per the caller passing null).
     * @param dialect the node's execution dialect — the datasource's, or the tempdb engine's
     *   ([co.datapipelines.pipeline.TempdbSettings.engine]'s dialect) for a tempdb node.
     * @param nodeQueryTimeoutSecondsOverride `node.settings.query_timeout_seconds`, or null.
     * @param pipelineQueryTimeoutSecondsOverride `pipeline.settings.query_timeout_seconds`, or null.
     */
    fun queryTimeoutSecondsFor(
        datasourceQueryTimeoutSeconds: Int?,
        dialect: Dialect,
        nodeQueryTimeoutSecondsOverride: Int? = null,
        pipelineQueryTimeoutSecondsOverride: Int? = null,
    ): ResolvedQueryTimeout =
        when {
            nodeQueryTimeoutSecondsOverride != null ->
                ResolvedQueryTimeout(nodeQueryTimeoutSecondsOverride, QueryTimeoutSource.NODE)

            pipelineQueryTimeoutSecondsOverride != null ->
                ResolvedQueryTimeout(pipelineQueryTimeoutSecondsOverride, QueryTimeoutSource.PIPELINE)

            datasourceQueryTimeoutSeconds != null ->
                ResolvedQueryTimeout(datasourceQueryTimeoutSeconds, QueryTimeoutSource.DATASOURCE)

            nodeQueryTimeoutSecondsByDialect[dialect] != null ->
                ResolvedQueryTimeout(nodeQueryTimeoutSecondsByDialect.getValue(dialect), QueryTimeoutSource.DIALECT)

            else -> ResolvedQueryTimeout(nodeQueryTimeoutSeconds, QueryTimeoutSource.APPLICATION)
        }

    /**
     * The wall-clock deadline for one node (§5.3, 108): the node's own
     * `settings.timeout_seconds` when it declared one, otherwise [nodeTimeoutSeconds].
     *
     * Clamped at [nodeTimeoutMaxSeconds] as a run-time backstop only. Save-time validation has
     * already refused anything above the ceiling (`pipeline.validation.node_timeout_invalid`), so
     * reaching the clamp means a body saved before the ceiling was lowered — and lowering an
     * operator ceiling has to bind the pipelines already stored, or it is not a ceiling.
     *
     * @param nodeTimeoutSecondsOverride `node.settings.timeout_seconds`, or null.
     */
    fun nodeTimeoutSecondsFor(nodeTimeoutSecondsOverride: Int?): Long =
        (nodeTimeoutSecondsOverride?.toLong() ?: nodeTimeoutSeconds).coerceIn(1, nodeTimeoutMaxSeconds.toLong())

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

        /** configuration.md §3.2's shipped LAKE default (156, #2) — see the class's own property KDoc. */
        const val DEFAULT_LAKE_QUERY_TIMEOUT_SECONDS = 180
    }
}

/**
 * Which tier resolved a node's effective statement timeout (156, #2) — carried in
 * `pipeline.node.query_timeout`'s failure detail (`source`) so an author can tell whether their
 * own override took effect or an operator default did.
 */
enum class QueryTimeoutSource(
    /** The wire spelling — `pipeline.node.query_timeout`'s `details.source`. */
    val wire: String,
) {
    NODE("node"),
    PIPELINE("pipeline"),
    DATASOURCE("datasource"),
    DIALECT("dialect"),
    APPLICATION("application"),
}

/** The result of [ExecutorConfig.queryTimeoutSecondsFor]: the effective seconds and their tier. */
data class ResolvedQueryTimeout(
    val seconds: Int,
    val source: QueryTimeoutSource,
)

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
