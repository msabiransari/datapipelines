package co.datapipelines.web.config

import co.datapipelines.auth.AuthProperties
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.executor.CancellationFlags
import co.datapipelines.executor.CancellationRegistry
import co.datapipelines.executor.ExecutionCancellationService
import co.datapipelines.executor.ExecutionSlots
import co.datapipelines.executor.ExecutorConfig
import co.datapipelines.executor.ExecutorDispatcher
import co.datapipelines.executor.ExecutorMetrics
import co.datapipelines.executor.IdempotencyStore
import co.datapipelines.executor.InMemoryCancellationRegistry
import co.datapipelines.executor.JdbcWritebackRunner
import co.datapipelines.executor.PipelineExecutor
import co.datapipelines.executor.RedisCancellationFlags
import co.datapipelines.executor.RedisIdempotencyStore
import co.datapipelines.executor.RedisResultStore
import co.datapipelines.executor.ResultConfig
import co.datapipelines.executor.ResultStore
import co.datapipelines.executor.ResultUrlFactory
import co.datapipelines.executor.WritebackRunner
import co.datapipelines.executor.pipelineExecutor
import co.datapipelines.staging.StagingFactory
import co.datapipelines.templates.TemplateEngine
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.core.StringRedisTemplate
import java.net.URI
import java.util.UUID
import java.util.concurrent.Executors

/**
 * The execution engine, assembled (module-structure §5.9, dag-executor §5.2).
 *
 * `dag` ships **no** Spring configuration — the two repositories are `@Repository` and everything
 * else is constructed by whoever assembles the application, which is this module. That is also why
 * `mcp-server`'s autoconfiguration is `@ConditionalOnBean(PipelineExecutor::class)`: the MCP
 * surface appears precisely because the beans below exist, and would silently vanish if they did
 * not.
 */
@Configuration
class EngineConfiguration {
    @Bean
    fun resultConfig(properties: ResultProperties): ResultConfig =
        ResultConfig(
            ttlDefaultSeconds = properties.ttlDefaultSeconds,
            ttlMinSeconds = properties.ttlMinSeconds,
            ttlMaxSeconds = properties.ttlMaxSeconds,
            maxSizeBytes = properties.maxSizeBytes,
            pageSizeRows = properties.pageSizeRows,
            pageMaxRows = properties.pageMaxRows,
        )

    @Bean
    fun executorConfig(
        executor: ExecutorProperties,
        staging: StagingH2Properties,
        sse: SseProperties,
        result: ResultConfig,
    ): ExecutorConfig =
        ExecutorConfig(
            maxParallelNodes = executor.maxParallelNodes,
            maxConcurrentExecutionsPerUser = executor.maxConcurrentExecutionsPerUser,
            maxConcurrentExecutionsGlobal = executor.maxConcurrentExecutionsGlobal,
            nodeQueryTimeoutSeconds = executor.nodeQueryTimeoutSeconds,
            executionTimeoutSeconds = executor.executionTimeoutSeconds,
            stagingMaxMemoryMb = staging.maxMemoryMb,
            // dag polls the cross-instance cancel flag on this cadence, and §10.4 promises a
            // cancellation lands "within ~one heartbeat interval" — so it IS the heartbeat.
            cancelPollIntervalSeconds = sse.heartbeatIntervalSeconds,
            result = result,
        )

    /** Closed on shutdown: the pool's threads are daemons, but the drain should still be orderly. */
    @Bean(destroyMethod = "close")
    fun executorDispatcher(config: ExecutorConfig): ExecutorDispatcher = ExecutorDispatcher.forConfig(config)

    @Bean
    fun executionSlots(config: ExecutorConfig): ExecutionSlots =
        ExecutionSlots(
            maxPerUser = config.maxConcurrentExecutionsPerUser,
            maxGlobal = config.maxConcurrentExecutionsGlobal,
        )

    @Bean
    @ConditionalOnMissingBean(MeterRegistry::class)
    fun meterRegistry(): MeterRegistry = SimpleMeterRegistry()

    @Bean
    fun executorMetrics(registry: MeterRegistry): ExecutorMetrics = ExecutorMetrics(registry)

    @Bean
    fun resultStore(
        redis: StringRedisTemplate,
        config: ResultConfig,
        metrics: ExecutorMetrics,
    ): ResultStore = RedisResultStore(redis, config, metrics)

    @Bean
    fun cancellationFlags(redis: StringRedisTemplate): CancellationFlags = RedisCancellationFlags(redis)

    @Bean
    fun cancellationRegistry(): CancellationRegistry = InMemoryCancellationRegistry()

    @Bean
    fun executionCancellationService(
        registry: CancellationRegistry,
        flags: CancellationFlags,
        config: ExecutorConfig,
    ): ExecutionCancellationService = ExecutionCancellationService(registry, flags, config)

    @Bean
    fun idempotencyStore(redis: StringRedisTemplate): IdempotencyStore = RedisIdempotencyStore(redis)

    @Bean
    fun writebackRunner(registry: DatasourceRegistry): WritebackRunner = JdbcWritebackRunner(registry)

    /**
     * **Carry-forward #3 — the absolute `result_url` builder.**
     *
     * rest-api §6.4.7 requires `data_ready.result_url` to be absolute, and `PipelineExecutor` takes
     * this with **no default** precisely so "forgot to wire the base URL" is a startup failure
     * rather than a wire-invalid payload nobody notices.
     *
     * The host comes from `datapipelines.auth.base-url` and from nowhere else — never from a
     * request header. Deriving it from `Host`/`X-Forwarded-Host` is exactly the hole that key
     * exists to close (auth.md §5.2): an attacker-supplied header would put an attacker's host in
     * a URL we hand to a client, and the SSE payload is emitted from a background coroutine where
     * there is no request to read anyway.
     *
     * With no base URL configured the factory stays relative — correct, safe and obviously so,
     * rather than a plausible-looking absolute URL built from a guess.
     */
    @Bean
    fun resultUrlFactory(authProperties: AuthProperties): ResultUrlFactory {
        val base =
            authProperties.baseUrl
                ?.trim()
                ?.trimEnd('/')
                ?.takeIf { it.isNotEmpty() }
                ?: return ResultUrlFactory.RELATIVE
        // Parsed once at startup: a malformed base-url must fail fast, not produce a broken URL
        // on every completed execution.
        val origin = URI.create(base)
        require(origin.scheme != null && origin.host != null) {
            "datapipelines.auth.base-url must be an absolute origin (scheme + host), was '$base'"
        }
        return ResultUrlFactory { executionId: UUID -> "$base$RESULT_PATH_PREFIX$executionId/result" }
    }

    @Suppress("LongParameterList")
    @Bean
    fun pipelineExecutorBean(
        templateEngine: TemplateEngine,
        datasourceRegistry: DatasourceRegistry,
        stagingFactory: StagingFactory,
        writebackRunner: WritebackRunner,
        resultStore: ResultStore,
        cancellationRegistry: CancellationRegistry,
        cancellationFlags: CancellationFlags,
        executionSlots: ExecutionSlots,
        dispatcher: ExecutorDispatcher,
        config: ExecutorConfig,
        resultUrls: ResultUrlFactory,
        metrics: ExecutorMetrics,
    ): PipelineExecutor =
        pipelineExecutor(
            templateEngine = templateEngine,
            datasourceRegistry = datasourceRegistry,
            stagingFactory = stagingFactory,
            writebackRunner = writebackRunner,
            resultStore = resultStore,
            // Per-execution: the real emitter is constructed by the SSE layer for each run, because
            // it captures that run's stream, correlation id and `triggered_via`. The executor is
            // built once, so the bean-level emitter must be inert — a shared stateful emitter here
            // would cross-wire two concurrent executions' streams.
            eventEmitter = co.datapipelines.events.EventEmitter.NONE,
            cancellationRegistry = cancellationRegistry,
            cancellationFlags = cancellationFlags,
            executionSlots = executionSlots,
            dispatcher = dispatcher,
            config = config,
            resultUrls = resultUrls,
            metrics = metrics,
        )

    /**
     * Where the event emitter's blocking JDBC/Redis writes run.
     *
     * A pool `web` owns, not the executor's: dag-executor §15.2 sizes `ExecutorDispatcher` for SQL
     * work against source databases, and putting the surface's bookkeeping on it would make
     * execution throughput a function of metadata-DB latency. Not `Dispatchers.IO` either — the
     * same argument the executor makes against sharing a JVM-wide pool applies here.
     */
    @Bean(destroyMethod = "shutdown")
    fun eventPersistenceExecutor(): java.util.concurrent.ExecutorService =
        Executors.newFixedThreadPool(EVENT_PERSISTENCE_THREADS) { runnable ->
            Thread(runnable, "dp-event-persist").apply { isDaemon = true }
        }

    @Bean
    fun eventPersistenceDispatcher(
        // @Qualifier-pinned: `sseLogScheduler` is also an ExecutorService (ScheduledExecutorService
        // extends it), so an unqualified by-type injection is ambiguous once both beans share one
        // context — which only the assembled application does; module slice tests never saw it.
        @Qualifier("eventPersistenceExecutor") executor: java.util.concurrent.ExecutorService,
    ): CoroutineDispatcher = executor.asCoroutineDispatcher()

    private companion object {
        const val RESULT_PATH_PREFIX = "/api/v1/executions/"

        /**
         * Small on purpose: these writes are short, and `emit` awaits each one, so the pool bounds
         * how many executions can be mid-bookkeeping rather than how fast any one of them is.
         */
        const val EVENT_PERSISTENCE_THREADS = 4
    }
}
