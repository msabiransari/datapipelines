package co.datapipelines.web.dashboards.runtime

import co.datapipelines.application.dashboards.DashboardTransformer
import co.datapipelines.application.dashboards.RefreshAdmission
import co.datapipelines.application.dashboards.RefreshEngine
import co.datapipelines.application.dashboards.SourceStarter
import co.datapipelines.application.lens.PromoterLens
import co.datapipelines.application.templates.TemplateEvaluateService
import co.datapipelines.auth.AuditEventSink
import co.datapipelines.auth.PrincipalLiveness
import co.datapipelines.auth.UserService
import co.datapipelines.auth.WorkspaceService
import co.datapipelines.executor.ExecutionCancellationService
import co.datapipelines.executor.ExecutionSlots
import co.datapipelines.executor.ExecutorConfig
import co.datapipelines.executor.ExecutorDispatcher
import co.datapipelines.executor.RedisRefreshAbortFlags
import co.datapipelines.executor.RedisRefreshStartMarkers
import co.datapipelines.executor.RefreshAbortFlags
import co.datapipelines.executor.RefreshStartMarkers
import co.datapipelines.parameters.ParameterEvaluator
import co.datapipelines.parameters.ParameterSetRepository
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.PipelineService
import co.datapipelines.pipeline.TemplateDryRenderer
import co.datapipelines.templates.TemplateService
import co.datapipelines.visualization.DashboardRefreshHistory
import co.datapipelines.visualization.DashboardRefreshRepository
import co.datapipelines.visualization.DashboardRuntimeConfig
import co.datapipelines.visualization.DashboardRuntimeProperties
import co.datapipelines.visualization.DashboardService
import co.datapipelines.visualization.PipelineReleaseFacts
import co.datapipelines.visualization.VisualizationService
import co.datapipelines.web.config.SseProperties
import co.datapipelines.web.config.WebSurfaceConfiguration
import co.datapipelines.web.pipelines.RecordingExecutionRunner
import co.datapipelines.web.sse.ExecutionStreamRegistry
import co.datapipelines.web.sse.SseJson
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import java.time.Duration

/**
 * The dashboard runtime's wiring (#10 L2; the implementation spec's §9) — its OWN configuration class, so
 * `VisualizationConfiguration` stays L1's and L1c's to extend (the one line it changes here is the validator's two
 * limits, read from [DashboardRuntimeConfig]).
 *
 * ONE [DashboardRuntimeConfig], bound from `datapipelines.dashboards.*` ([DashboardRuntimeProperties] — `toConfig()`
 * enforces every bound and relation, naming the key), feeds admission, the engine, the resolver's views and the validator.
 *
 * The launch seam is the product's existing one ([RecordingExecutionRunner], `triggered_via = DASHBOARD`); the
 * transform is the authoring evaluation ([TemplateEvaluateService]); the refresh stream has its OWN registry, sharing
 * only the per-user stream cap with the execution registry; the abort flag is dag's ([RedisRefreshAbortFlags], the one
 * module besides persistence allowed to talk to Redis).
 *
 * ## The jobs
 * [DashboardRefreshSweepScheduler] is one of the approved `@Scheduled` homes (`ArchitectureGuardTest`); it rides the same
 * `dp-scheduled` thread as the stale-execution sweep, and `@EnableScheduling` is the sweep configuration's — never a second.
 */
@Configuration
@EnableConfigurationProperties(DashboardRuntimeProperties::class)
@Suppress("LongParameterList") // an assembling layer: one bean method per collaborator, each with its own ports
class DashboardRuntimeConfiguration {
    @Bean
    fun dashboardRuntimeConfig(properties: DashboardRuntimeProperties): DashboardRuntimeConfig = properties.toConfig()

    @Bean
    fun dashboardRefreshRepository(jdbc: NamedParameterJdbcTemplate): DashboardRefreshRepository = DashboardRefreshRepository(jdbc)

    /** The read a transport may hold — `dashboards_get`'s `last_refresh`. */
    @Bean
    fun dashboardRefreshHistory(refreshes: DashboardRefreshRepository): DashboardRefreshHistory = DashboardRefreshHistory(refreshes)

    /** The admission places — JVM-LOCAL, like [ExecutionSlots] (configuration.md §3.34). */
    @Bean
    fun refreshAdmission(
        slots: ExecutionSlots,
        config: DashboardRuntimeConfig,
    ): RefreshAdmission = RefreshAdmission(slots, config)

    /** The runtime's instruments; binds its gauges to the live admission at construction. */
    @Bean
    fun dashboardMetrics(
        registry: MeterRegistry,
        admission: RefreshAdmission,
    ): DashboardMetrics = DashboardMetrics(registry).also { it.bind(admission) }

    @Bean
    fun refreshAbortFlags(redis: StringRedisTemplate): RefreshAbortFlags = RedisRefreshAbortFlags(redis)

    /** The in-flight starts an abort can honour before the row exists (#356) — the abort flag's transient twin. */
    @Bean
    fun refreshStartMarkers(redis: StringRedisTemplate): RefreshStartMarkers = RedisRefreshStartMarkers(redis)

    @Bean
    fun refreshAbortSignal(
        flags: RefreshAbortFlags,
        executor: ExecutorConfig,
    ): RefreshAbortSignal = RefreshAbortSignal(flags, remotePollMillis = Duration.ofSeconds(executor.cancelPollIntervalSeconds).toMillis())

    @Bean
    fun refreshStreamAuthority(
        liveness: PrincipalLiveness,
        workspaces: WorkspaceService,
        users: UserService,
    ): RefreshStreamAuthority = RefreshStreamAuthority(liveness, workspaces, users)

    @Bean
    fun refreshStreamRegistry(
        properties: SseProperties,
        executionStreams: ExecutionStreamRegistry,
        abort: RefreshAbortSignal,
        // #375 D7: the one per-user cap counts the observed evaluation's streams too.
        evaluationStreams: co.datapipelines.web.parameters.stream.ParameterEvaluationStreamRegistry,
    ): RefreshStreamRegistry =
        RefreshStreamRegistry(properties, executionStreams, abort, SseJson.mapper, evaluationStreams = evaluationStreams::activeStreamsFor)

    @Bean
    fun dashboardSourceStarter(runner: RecordingExecutionRunner): SourceStarter = RecordingSourceStarter(runner)

    @Bean
    fun dashboardTransformer(
        evaluate: TemplateEvaluateService,
        contracts: TemplateDryRenderer,
        templates: TemplateService,
    ): DashboardTransformer = WebDashboardTransformer(evaluate, contracts, templates)

    /** The engine runs its blocking work (transform, ledger, audit) on the executor's own dispatcher, never a request thread. */
    @Bean
    fun refreshEngine(
        starter: SourceStarter,
        transformer: DashboardTransformer,
        config: DashboardRuntimeConfig,
        dispatcher: ExecutorDispatcher,
    ): RefreshEngine = RefreshEngine(starter, transformer, config, dispatcher.context)

    @Bean
    fun dashboardRuntimeResolver(
        dashboards: DashboardService,
        visualizations: VisualizationService,
        sets: ParameterSetRepository,
        releaseFacts: PipelineReleaseFacts,
        pipelineRepository: PipelineRepository,
        pipelines: PipelineService,
        templates: TemplateService,
    ): DashboardRuntimeResolver =
        DashboardRuntimeResolver(dashboards, visualizations, sets, releaseFacts, pipelineRepository, pipelines, templates)

    @Bean
    fun dashboardRuntime(
        resolver: DashboardRuntimeResolver,
        evaluator: ParameterEvaluator,
        refreshes: DashboardRefreshRepository,
        admission: RefreshAdmission,
        engine: RefreshEngine,
        streams: RefreshStreamRegistry,
        streamAuthority: RefreshStreamAuthority,
        abortSignal: RefreshAbortSignal,
        abortFlags: RefreshAbortFlags,
        startMarkers: RefreshStartMarkers,
        cancellation: ExecutionCancellationService,
        audit: AuditEventSink,
        dashboards: DashboardService,
        lens: PromoterLens,
        config: DashboardRuntimeConfig,
        scope: WebSurfaceConfiguration.ExecutionCoroutineScope,
        metrics: DashboardMetrics,
    ): DashboardRuntime =
        DashboardRuntime(
            resolver,
            evaluator,
            refreshes,
            admission,
            engine,
            streams,
            streamAuthority,
            abortSignal,
            abortFlags,
            startMarkers,
            cancellation,
            audit,
            dashboards,
            lens,
            config,
            scope,
            ServiceExecutionCanceller(cancellation),
            metrics,
        )

    /** Closes a RUNNING refresh once it is past its longest possible deadline plus one abort poll (metadata-db §8.4). */
    @Bean
    fun dashboardRefreshSweeper(
        refreshes: DashboardRefreshRepository,
        config: DashboardRuntimeConfig,
        executor: ExecutorConfig,
    ): DashboardRefreshSweeper = DashboardRefreshSweeper(refreshes, config.maxRefreshSeconds + executor.cancelPollIntervalSeconds)

    @Bean
    fun dashboardRefreshSweepScheduler(sweeper: DashboardRefreshSweeper): DashboardRefreshSweepScheduler =
        DashboardRefreshSweepScheduler(sweeper)
}

/**
 * The `@Scheduled` adapter over [DashboardRefreshSweeper] — the stale-execution sweep's twin. `fixedDelay`, not
 * `fixedRate`: a slow tick delays the next instead of piling on; the cadence is a code constant, not a key (a refresh's
 * deadline is minutes, the sweep only has to be prompt against that).
 */
class DashboardRefreshSweepScheduler(
    private val sweeper: DashboardRefreshSweeper,
) {
    @Scheduled(fixedDelay = SWEEP_INTERVAL_MILLIS)
    fun sweep() {
        sweeper.sweepOnce()
    }

    companion object {
        const val SWEEP_INTERVAL_MILLIS = 60_000L
    }
}
