package co.datapipelines.web.config

import co.datapipelines.application.ExecutionLauncher
import co.datapipelines.auth.LastUsedWorkspaceStore
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.executor.CancellationFlags
import co.datapipelines.executor.CancellationRegistry
import co.datapipelines.executor.ExecutionCancellationService
import co.datapipelines.executor.ExecutionEventRepository
import co.datapipelines.executor.ExecutionRepository
import co.datapipelines.executor.ExecutionSlots
import co.datapipelines.executor.ExecutorConfig
import co.datapipelines.executor.ExecutorDispatcher
import co.datapipelines.executor.ExecutorJson
import co.datapipelines.executor.ExecutorMetrics
import co.datapipelines.executor.IdempotencyStore
import co.datapipelines.executor.ResultConfig
import co.datapipelines.executor.ResultStore
import co.datapipelines.executor.ResultUrlFactory
import co.datapipelines.executor.SubPipelineRunner
import co.datapipelines.executor.WritebackRunner
import co.datapipelines.mcp.McpExecutionRunner
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.staging.StagingFactory
import co.datapipelines.templates.WorkspaceTemplateEngines
import co.datapipelines.web.executions.ResultCursor
import co.datapipelines.web.health.StagingHealthIndicator
import co.datapipelines.web.metrics.WebMetrics
import co.datapipelines.web.pipelines.ExecutionStreamLauncher
import co.datapipelines.web.pipelines.McpRecordingExecutionRunner
import co.datapipelines.web.pipelines.SubPipelineExecutionRunner
import co.datapipelines.web.pipelines.WebIdempotencyMetrics
import co.datapipelines.web.ratelimit.RateLimiter
import co.datapipelines.web.ratelimit.RedisRateLimiter
import co.datapipelines.web.sse.ExecutionStreamRegistry
import co.datapipelines.web.sse.SseEventLog
import co.datapipelines.web.sse.SseJson
import co.datapipelines.web.sse.SseLogStreamer
import co.datapipelines.web.workspace.RedisLastUsedWorkspaceStore
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.core.StringRedisTemplate
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService

/**
 * The web surface's own beans: SSE stream machinery, the per-user limiter, the result cursor and
 * the execution launcher (module-structure §5.9 — `web` is the aggregation layer).
 *
 * The SSE payload mapper is `dag`'s [ExecutorJson.mapper] rather than the servlet mapper: the
 * payloads are built from executor data (`Instant`, `ColumnSchema`, `TypeMappingWarning`) and
 * `ExecutorJson` is the mapper whose module set already covers them.
 */
@Configuration
class WebSurfaceConfiguration {
    /** The per-user limiter the [co.datapipelines.web.ratelimit.RateLimitFilter] enforces. */
    @Bean
    fun rateLimiter(
        redis: StringRedisTemplate,
        properties: RateLimitProperties,
    ): RateLimiter = RedisRateLimiter(redis, properties)

    /**
     * The last-used-workspace store `auth`'s resolution reads at login and writes on
     * `DP-Workspace` switches (design §5.1). The port is `auth`'s; the Redis implementation
     * lives here (module-structure §3.1 rule 3).
     */
    @Bean
    fun lastUsedWorkspaceStore(redis: StringRedisTemplate): LastUsedWorkspaceStore = RedisLastUsedWorkspaceStore(redis)

    /**
     * Actuator resolves `HealthIndicator` beans by name; `h2_factory` is the health-path
     * key rest-api.md §11.1's contract promises (and `HealthController` probes), so the
     * name is pinned explicitly rather than left to the method name.
     */
    @Bean(name = ["h2_factory"])
    fun stagingHealthIndicator(stagingFactory: StagingFactory): StagingHealthIndicator = StagingHealthIndicator(stagingFactory)

    @Bean(destroyMethod = "shutdown")
    fun sseLogScheduler(): ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "dp-sse-log").apply { isDaemon = true }
        }

    @Bean
    fun sseEventLog(redis: StringRedisTemplate): SseEventLog =
        // SseJson, not ExecutorJson: SSE payloads carry resolved java.time parameter
        // values that dag's mapper deliberately cannot serialize (T36, SseJson KDoc).
        SseEventLog(redis, SseJson.mapper)

    @Bean
    fun sseLogStreamer(
        eventLog: SseEventLog,
        scheduler: ScheduledExecutorService,
    ): SseLogStreamer = SseLogStreamer(eventLog, SseJson.mapper, scheduler)

    @Bean
    fun executionStreamRegistry(
        properties: SseProperties,
        cancellationService: ExecutionCancellationService,
        // SseJson, not ExecutorJson: this mapper is the SSE WIRE encoder (ExecutionStream.send).
        // A DATE/TIME parameter reaches execution_started as java.time, and ExecutorJson has no
        // jsr310 — worse, InvalidDefinitionException IS an IOException, which send() catches as
        // "client disconnected", so the stream dies AND cancel-on-disconnect aborts a healthy
        // execution ~30s later. T36, second path (the f659f4a fix covered only the replay log).
    ): ExecutionStreamRegistry = ExecutionStreamRegistry(properties, cancellationService, SseJson.mapper)

    @Bean
    fun webMetrics(
        registry: MeterRegistry,
        streams: ExecutionStreamRegistry,
    ): WebMetrics = WebMetrics(registry).also { it.bindStreams(streams) }

    @Bean
    fun resultCursor(
        executions: ExecutionRepository,
        resultStore: ResultStore,
        resultConfig: ResultConfig,
        metrics: WebMetrics,
    ): ResultCursor = ResultCursor(executions, resultStore, resultConfig, metrics)

    /**
     * The scope execute coroutines launch into. `SupervisorJob`: one execution's failure must not
     * cancel another's. The executor does its own dispatcher switching for blocking work
     * (dag-executor §15.2), so the launch context only ever orchestrates.
     *
     * The holder class exists because `CoroutineScope.cancel` is an extension, not a member —
     * `destroyMethod` cannot see it.
     */
    @Bean(destroyMethod = "close")
    fun executionScope(): ExecutionCoroutineScope = ExecutionCoroutineScope()

    /** A [CoroutineScope] with a real `close()` for the container's shutdown drain. */
    class ExecutionCoroutineScope :
        CoroutineScope,
        java.io.Closeable {
        private val job = SupervisorJob()
        override val coroutineContext = job + Dispatchers.Default

        override fun close() {
            job.cancel()
        }
    }

    /**
     * The composition runtime (design 2026-08-13-pipeline-node-type §4.1): dag's `SubPipelineRunner`
     * port implemented over the shared engine collaborators plus the pipeline registry — the one
     * collaborator [McpRecordingExecutionRunner] does not need and this one cannot work without.
     * Both execution entry points (the SSE launcher, the MCP runner) take it so a PIPELINE node
     * behaves identically however the parent was started.
     */
    @Suppress("LongParameterList")
    @Bean
    fun subPipelineRunner(
        pipelines: PipelineRepository,
        templateEngines: WorkspaceTemplateEngines,
        datasourceRegistry: DatasourceRegistry,
        stagingFactory: StagingFactory,
        writebackRunner: WritebackRunner,
        resultStore: ResultStore,
        cancellationRegistry: CancellationRegistry,
        cancellationFlags: CancellationFlags,
        executionSlots: ExecutionSlots,
        executorDispatcher: ExecutorDispatcher,
        executorConfig: ExecutorConfig,
        resultUrls: ResultUrlFactory,
        executorMetrics: ExecutorMetrics,
        persistenceDispatcher: CoroutineDispatcher,
        streams: ExecutionStreamRegistry,
        eventLog: SseEventLog,
        eventRepository: ExecutionEventRepository,
        executionRepository: ExecutionRepository,
    ): SubPipelineRunner =
        SubPipelineExecutionRunner(
            pipelines = pipelines,
            templateEngines = templateEngines,
            datasourceRegistry = datasourceRegistry,
            stagingFactory = stagingFactory,
            writebackRunner = writebackRunner,
            resultStore = resultStore,
            cancellationRegistry = cancellationRegistry,
            cancellationFlags = cancellationFlags,
            executionSlots = executionSlots,
            executorDispatcher = executorDispatcher,
            executorConfig = executorConfig,
            resultUrls = resultUrls,
            executorMetrics = executorMetrics,
            persistenceDispatcher = persistenceDispatcher,
            streams = streams,
            eventLog = eventLog,
            eventRepository = eventRepository,
            executionRepository = executionRepository,
        )

    /**
     * The cross-aggregate launch decision (056/D6): resolve the version, bind the parameters,
     * settle the idempotency reservation. Declared here because `modules/application` ships no
     * Spring configuration of its own — `web` is the aggregation layer (§5.9) — and consumed by
     * BOTH surfaces: [executionStreamLauncher] below and, through `mcp-server`'s
     * autoconfiguration, the `pipelines_execute` tool. That shared bean is what makes MCP execute
     * idempotent; before 056 the tool had no reservation at all (ARCH-AUDIT S2/D6).
     */
    @Bean
    fun executionLauncher(
        idempotencyStore: IdempotencyStore,
        idempotency: IdempotencyProperties,
        metrics: WebMetrics,
    ): ExecutionLauncher =
        ExecutionLauncher(
            idempotencyStore = idempotencyStore,
            idempotencyTtlSeconds = idempotency.ttlSeconds,
            metrics = WebIdempotencyMetrics(metrics),
        )

    @Suppress("LongParameterList")
    @Bean
    fun executionStreamLauncher(
        templateEngines: WorkspaceTemplateEngines,
        datasourceRegistry: DatasourceRegistry,
        stagingFactory: StagingFactory,
        writebackRunner: WritebackRunner,
        resultStore: ResultStore,
        cancellationRegistry: CancellationRegistry,
        cancellationFlags: CancellationFlags,
        executionSlots: ExecutionSlots,
        executorDispatcher: ExecutorDispatcher,
        executorConfig: ExecutorConfig,
        resultUrls: ResultUrlFactory,
        executorMetrics: ExecutorMetrics,
        persistenceDispatcher: CoroutineDispatcher,
        streams: ExecutionStreamRegistry,
        eventLog: SseEventLog,
        streamer: SseLogStreamer,
        eventRepository: ExecutionEventRepository,
        executionRepository: ExecutionRepository,
        launcher: ExecutionLauncher,
        scope: CoroutineScope,
        subPipelineRunner: SubPipelineRunner,
    ): ExecutionStreamLauncher =
        ExecutionStreamLauncher(
            templateEngines = templateEngines,
            datasourceRegistry = datasourceRegistry,
            stagingFactory = stagingFactory,
            writebackRunner = writebackRunner,
            resultStore = resultStore,
            cancellationRegistry = cancellationRegistry,
            cancellationFlags = cancellationFlags,
            executionSlots = executionSlots,
            executorDispatcher = executorDispatcher,
            executorConfig = executorConfig,
            resultUrls = resultUrls,
            executorMetrics = executorMetrics,
            persistenceDispatcher = persistenceDispatcher,
            streams = streams,
            eventLog = eventLog,
            streamer = streamer,
            eventRepository = eventRepository,
            executionRepository = executionRepository,
            launcher = launcher,
            // Feeds ExecutionStream too — same reason as above.
            mapper = SseJson.mapper,
            scope = scope,
            subPipelineRunner = subPipelineRunner,
        )

    /**
     * The recording execution path for MCP-originated runs (P7). `mcp-server`'s autoconfiguration
     * picks this up through its own [McpExecutionRunner] port — `web` depends on `mcp-server`,
     * never the reverse, so the port lives there and the implementation here. Shares every engine
     * collaborator with [executionStreamLauncher]; what it deliberately lacks is anything stream-shaped.
     */
    @Suppress("LongParameterList")
    @Bean
    fun mcpExecutionRunner(
        templateEngines: WorkspaceTemplateEngines,
        datasourceRegistry: DatasourceRegistry,
        stagingFactory: StagingFactory,
        writebackRunner: WritebackRunner,
        resultStore: ResultStore,
        cancellationRegistry: CancellationRegistry,
        cancellationFlags: CancellationFlags,
        executionSlots: ExecutionSlots,
        executorDispatcher: ExecutorDispatcher,
        executorConfig: ExecutorConfig,
        resultUrls: ResultUrlFactory,
        executorMetrics: ExecutorMetrics,
        persistenceDispatcher: CoroutineDispatcher,
        streams: ExecutionStreamRegistry,
        eventLog: SseEventLog,
        eventRepository: ExecutionEventRepository,
        executionRepository: ExecutionRepository,
        subPipelineRunner: SubPipelineRunner,
    ): McpExecutionRunner =
        McpRecordingExecutionRunner(
            templateEngines = templateEngines,
            datasourceRegistry = datasourceRegistry,
            stagingFactory = stagingFactory,
            writebackRunner = writebackRunner,
            resultStore = resultStore,
            cancellationRegistry = cancellationRegistry,
            cancellationFlags = cancellationFlags,
            executionSlots = executionSlots,
            executorDispatcher = executorDispatcher,
            executorConfig = executorConfig,
            resultUrls = resultUrls,
            executorMetrics = executorMetrics,
            persistenceDispatcher = persistenceDispatcher,
            streams = streams,
            eventLog = eventLog,
            eventRepository = eventRepository,
            executionRepository = executionRepository,
            subPipelineRunner = subPipelineRunner,
        )
}
