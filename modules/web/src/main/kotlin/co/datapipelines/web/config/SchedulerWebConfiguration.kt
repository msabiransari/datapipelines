package co.datapipelines.web.config

import co.datapipelines.application.lens.PromoterLens
import co.datapipelines.auth.AuthProperties
import co.datapipelines.auth.MailProperties
import co.datapipelines.auth.MailSender
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
import co.datapipelines.scheduler.NotificationDeployment
import co.datapipelines.scheduler.NotificationMailer
import co.datapipelines.scheduler.SchedulerProperties
import co.datapipelines.scheduler.SystemActorSource
import co.datapipelines.web.pipelines.RecordingExecutionRunner
import co.datapipelines.web.schedules.PipelineJobExecutor
import co.datapipelines.web.schedules.SlotCapacityGate
import co.datapipelines.web.schedules.SmtpNotificationMailer
import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment

/**
 * The scheduler's composition root (#9; scheduler design revision §6.2, A3 — `web/config`, since
 * `app` depends only on `web`). The scheduler module owns its own beans (its auto-configuration);
 * this class supplies what only the pipeline side can: its three ports.
 *
 * - [SystemActorSource] — the system identity's row id (R2), provisioned at boot by
 *   `SystemActorSeeder`; its presence is also what switches the scheduler's auto-configuration on.
 * - the pipeline [JobExecutor] — [PipelineJobExecutor], registered as executor `pipeline`.
 * - the [CapacityGate] — [SlotCapacityGate] over `ExecutionSlots`, budget `max-concurrent-runs` (R4).
 * - #442: the [NotificationDeployment] facts the scheduler's mail policy reads — whether auth's shared
 *   SMTP transport is configured, the base URL and the effective posture.
 * - #442b: the [NotificationMailer] — [SmtpNotificationMailer] over auth's one shared [MailSender].
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

    /**
     * #442 — the deployment facts behind scheduler mail: auth's transport rule (host AND from,
     * [MailProperties.enabled]), `datapipelines.auth.base-url` (#207) and the effective posture
     * ([DeploymentEnv.resolvePosture]); anything but `hardened` reads as development, the stricter.
     */
    @Bean
    fun schedulerNotificationDeployment(
        mail: MailProperties,
        auth: AuthProperties,
        environment: Environment,
    ): NotificationDeployment {
        val env =
            DeploymentEnv.resolveEnv(environment.getProperty(DeploymentEnv.ENV_KEY), environment.getProperty(DeploymentEnv.LEGACY_ENV_KEY))
        val posture = DeploymentEnv.resolvePosture(environment.getProperty(DeploymentEnv.POSTURE_KEY), env)
        return NotificationDeployment(
            mailConfigured = mail.enabled,
            baseUrl = auth.baseUrl?.takeIf { it.isNotBlank() },
            developmentPosture = posture != DeploymentEnv.HARDENED,
        )
    }

    /** #442b — the scheduler's mail port over the account notices' transport; no second SMTP configuration. */
    @Bean
    fun schedulerNotificationMailer(
        sender: MailSender,
        mail: MailProperties,
    ): NotificationMailer = SmtpNotificationMailer(sender, mail)

    @Bean
    fun schedulerCapacityGate(
        slots: ExecutionSlots,
        systemActor: SystemActorSource,
        properties: SchedulerProperties,
        registry: ObjectProvider<MeterRegistry>,
    ): CapacityGate = SlotCapacityGate(slots, systemActor::userId, properties.maxConcurrentRuns, registry.ifAvailable)
}
