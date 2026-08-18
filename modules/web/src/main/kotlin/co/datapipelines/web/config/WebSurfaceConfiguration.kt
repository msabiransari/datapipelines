package co.datapipelines.web.config

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
import co.datapipelines.templates.TemplateEngine
import co.datapipelines.web.executions.ResultCursor
import co.datapipelines.web.health.StagingHealthIndicator
import co.datapipelines.web.metrics.WebMetrics
import co.datapipelines.web.pipelines.ExecutionLauncher
import co.datapipelines.web.pipelines.McpRecordingExecutionRunner
import co.datapipelines.web.pipelines.SubPipelineExecutionRunner
import co.datapipelines.web.ratelimit.RateLimiter
import co.datapipelines.web.ratelimit.RedisRateLimiter
import co.datapipelines.web.sse.ExecutionStreamRegistry
import co.datapipelines.web.sse.SseEventLog
import co.datapipelines.web.sse.SseLogStreamer
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
    fun sseEventLog(redis: StringRedisTemplate): SseEventLog = SseEventLog(redis, ExecutorJson.mapper)

    @Bean
    fun sseLogStreamer(
        eventLog: SseEventLog,
        scheduler: ScheduledExecutorService,
    ): SseLogStreamer = SseLogStreamer(eventLog, ExecutorJson.mapper, scheduler)

    @Bean
    fun executionStreamRegistry(
        properties: SseProperties,
        cancellationService: ExecutionCancellationService,
    ): ExecutionStreamRegistry = ExecutionStreamRegistry(properties, cancellationService, ExecutorJson.mapper)

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
        templateEngine: TemplateEngine,
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
            templateEngine = templateEngine,
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

    @Suppress("LongParameterList")
    @Bean
    fun executionLauncher(
        templateEngine: TemplateEngine,
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
        idempotencyStore: IdempotencyStore,
        idempotency: IdempotencyProperties,
        metrics: WebMetrics,
        scope: CoroutineScope,
        subPipelineRunner: SubPipelineRunner,
    ): ExecutionLauncher =
        ExecutionLauncher(
            templateEngine = templateEngine,
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
            idempotencyStore = idempotencyStore,
            idempotency = idempotency,
            mapper = ExecutorJson.mapper,
            metrics = metrics,
            scope = scope,
            subPipelineRunner = subPipelineRunner,
        )

    /**
     * The recording execution path for MCP-originated runs (P7). `mcp-server`'s autoconfiguration
     * picks this up through its own [McpExecutionRunner] port — `web` depends on `mcp-server`,
     * never the reverse, so the port lives there and the implementation here. Shares every engine
     * collaborator with [executionLauncher]; what it deliberately lacks is anything stream-shaped.
     */
    @Suppress("LongParameterList")
    @Bean
    fun mcpExecutionRunner(
        templateEngine: TemplateEngine,
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
            templateEngine = templateEngine,
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
