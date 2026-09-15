package co.datapipelines.web.config

import co.datapipelines.application.checks.PipelineCheckRunRepository
import co.datapipelines.application.checks.PipelineCheckRunner
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.datasources.SqlProbe
import co.datapipelines.pipeline.PipelineService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

/**
 * The release-check run's beans (140, metadata-db §4.20) — a sibling of [LakeConfiguration]'s
 * shape, kept apart from [DomainConfiguration] because that class is at the house
 * function-density ceiling.
 *
 * ONE [PipelineCheckRunner] serves every surface: MCP, REST and the UI call it directly, and the
 * release gate's `ReleaseCheckGate` port (wired by the lane that owns `PipelineReleaseService`)
 * rides this same instance — which is what makes "the gate's fresh run" and "the UI's latest-run
 * list" two reads of one history rather than two runners that could drift. The probe is
 * stateless, so it is constructed inline exactly as [SemanticsConfiguration] already does.
 */
@Configuration
class ChecksConfiguration {
    /** The `pipeline_check_runs` rows. */
    @Bean
    fun pipelineCheckRunRepository(jdbc: NamedParameterJdbcTemplate): PipelineCheckRunRepository = PipelineCheckRunRepository(jdbc)

    /**
     * The one entry point every surface's check run rides.
     *
     * [pipelines] is `@Lazy` — and must be: `PipelineService` composes `PipelineReleaseService`,
     * which composes the `ReleaseCheckGate`, which rides THIS bean, so an eager reference is a
     * construction cycle (`pipelineService → … → pipelineCheckRunner → pipelineService`). The
     * lazy proxy resolves on first use, by which time the aggregate is fully built; only the
     * surfaces' convenience overload ever touches it (the release gate's core overload takes
     * the body it is handed and never resolves one).
     */
    @Bean
    fun pipelineCheckRunner(
        @org.springframework.context.annotation.Lazy pipelines: PipelineService,
        datasources: DatasourceRegistry,
        runs: PipelineCheckRunRepository,
    ): PipelineCheckRunner = PipelineCheckRunner(pipelines, datasources, SqlProbe(datasources), runs)

    /**
     * The release gate (versioning §5.3 precondition 4) as the `ReleaseCheckGate` port
     * `pipeline-contract` declares: the body's checks run fresh, `via = release`, on the one
     * shared runner. `PipelineReleaseService` calls it OUTSIDE the metadata transaction (its
     * probes open customer-datasource connections — `ConnectionLease` refuses exactly that
     * with `datasource.lease_in_transaction` while one is open on the thread), and the run
     * rows it persists are what the refusal's details and the UI's latest-run list read.
     */
    @Bean
    fun releaseCheckGate(runner: PipelineCheckRunner): co.datapipelines.pipeline.ReleaseCheckGate =
        co.datapipelines.pipeline.ReleaseCheckGate { workspaceId, pipelineId, version, pipeline, actor ->
            runner.run(
                workspaceId = workspaceId,
                pipelineId = pipelineId,
                version = version,
                pipeline = pipeline,
                parameters = emptyMap(),
                via = co.datapipelines.pipeline.CheckRunVia.RELEASE,
                actor = actor,
            )
        }
}
