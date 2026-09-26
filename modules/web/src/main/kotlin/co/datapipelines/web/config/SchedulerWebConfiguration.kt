package co.datapipelines.web.config

import co.datapipelines.application.lens.PromoterLens
import co.datapipelines.auth.UserService
import co.datapipelines.auth.WorkspaceRepository
import co.datapipelines.executor.ExecutionEventRepository
import co.datapipelines.executor.ExecutionRepository
import co.datapipelines.executor.ExecutionSlots
import co.datapipelines.executor.ExecutorConfig
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.PipelineService
import co.datapipelines.scheduler.CapacityGate
import co.datapipelines.scheduler.JobExecutor
import co.datapipelines.scheduler.SchedulerProperties
import co.datapipelines.scheduler.SystemActorSource
import co.datapipelines.web.pipelines.RecordingExecutionRunner
import co.datapipelines.web.schedules.PipelineJobExecutor
import co.datapipelines.web.schedules.SlotCapacityGate
import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * The scheduler's composition root (#9; scheduler design revision §6.2, A3 — `web/config`, since
 * `app` depends only on `web`). The scheduler module owns its own beans (its auto-configuration);
 * this class supplies what only the pipeline side can: its three ports.
 *
 * - [SystemActorSource] — the system identity's row id (R2), provisioned at boot by
 *   `SystemActorSeeder`; its presence is also what switches the scheduler's auto-configuration on.
 * - the pipeline [JobExecutor] — [PipelineJobExecutor], registered as executor `pipeline`.
 * - the [CapacityGate] — [SlotCapacityGate] over `ExecutionSlots`, budget `max-concurrent-runs` (R4).
 */
@Configuration
class SchedulerWebConfiguration {
    /** The system identity's id — read once: the row never changes (auth.md §4.5). */
    @Bean
    fun schedulerSystemActor(users: UserService): SystemActorSource {
        val id by lazy { users.systemActor().id }
        return SystemActorSource { id }
    }

    @Bean
    @Suppress("LongParameterList")
    fun pipelineJobExecutor(
        pipelines: PipelineRepository,
        pipelineService: PipelineService,
        workspaces: WorkspaceRepository,
        users: UserService,
        runner: RecordingExecutionRunner,
        executions: ExecutionRepository,
        events: ExecutionEventRepository,
        lens: PromoterLens,
        executorConfig: ExecutorConfig,
        executionScope: WebSurfaceConfiguration.ExecutionCoroutineScope,
        mapper: ObjectMapper,
    ): JobExecutor =
        PipelineJobExecutor(
            pipelines = pipelines,
            pipelineService = pipelineService,
            workspaces = workspaces,
            users = users,
            runner = runner,
            executions = executions,
            events = events,
            lens = lens,
            executorConfig = executorConfig,
            scope = executionScope,
            mapper = mapper,
        )

    @Bean
    fun schedulerCapacityGate(
        slots: ExecutionSlots,
        systemActor: SystemActorSource,
        properties: SchedulerProperties,
        registry: ObjectProvider<MeterRegistry>,
    ): CapacityGate = SlotCapacityGate(slots, systemActor::userId, properties.maxConcurrentRuns, registry.ifAvailable)
}
