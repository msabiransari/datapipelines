package co.datapipelines.web.release

import co.datapipelines.pipeline.PipelineReleaseService
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.release.PipelineDependencySource
import co.datapipelines.pipeline.release.PipelineReleaseCoordination
import co.datapipelines.pipeline.release.ReleaseAuditSink
import co.datapipelines.pipeline.release.ReleaseBounds
import co.datapipelines.pipeline.release.ReleaseFamily
import co.datapipelines.pipeline.release.ReleaseMutationGuard
import co.datapipelines.templates.TemplateRepository
import co.datapipelines.web.config.ReleaseDependencyProperties
import co.datapipelines.web.templates.TemplateReleaseService
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * The release-dependencies assembly (record §4.2: contracts in `pipeline-contract`, adapters and the coordinator here):
 * the configured bounds (configuration §3.35), the workspace writer guard every pin-affecting writer acquires, the
 * strict release audit sink, the S1 dependency sources and participants, and the pipeline coordinator — all on the ONE
 * `metadataTransactionManager` (never a customer, staging or new manager; never REQUIRES_NEW).
 */
@Configuration
@EnableConfigurationProperties(ReleaseDependencyProperties::class)
class ReleaseConfiguration {
    @Bean
    fun releaseBounds(properties: ReleaseDependencyProperties): ReleaseBounds = properties.toBounds()

    @Bean
    fun releaseMutationGuard(
        jdbc: NamedParameterJdbcTemplate,
        bounds: ReleaseBounds,
    ): ReleaseMutationGuard = JdbcReleaseMutationGuard(jdbc.jdbcTemplate, bounds.lockWaitSeconds)

    /** The same JSON the general audit logger writes, so every `audit_log` reader parses a release row unchanged. */
    @Bean
    fun releaseAuditSink(
        jdbc: NamedParameterJdbcTemplate,
        objectMapper: ObjectMapper,
    ): ReleaseAuditSink = StrictReleaseAuditSink(jdbc, objectMapper)

    @Bean
    fun releaseAuthorization(): ReleaseAuthorization = MatrixReleaseAuthorization()

    @Bean
    @Suppress("LongParameterList") // the coordinator's collaborators, one per step of the record's algorithm
    fun pipelineReleaseCoordinator(
        releases: PipelineReleaseService,
        pipelines: PipelineRepository,
        templates: TemplateRepository,
        templateReleases: TemplateReleaseService,
        authorization: ReleaseAuthorization,
        guard: ReleaseMutationGuard,
        audit: ReleaseAuditSink,
        metadataTransactionManager: PlatformTransactionManager,
        bounds: ReleaseBounds,
    ): PipelineReleaseCoordination =
        ReleaseCoordinator(
            releases = releases,
            pipelines = pipelines,
            sources =
                mapOf(
                    ReleaseFamily.PIPELINE to PipelineDependencySource(pipelines),
                    ReleaseFamily.TEMPLATE to TemplateDependencySource(templates),
                ),
            participants = listOf(PipelineReleaseParticipant(releases, pipelines), TemplateReleaseParticipant(templateReleases)),
            authorization = authorization,
            guard = guard,
            audit = audit,
            // A template of its own, never a bean: a TransactionOperations bean would switch off Boot's
            // auto-configured `transactionTemplate` for every other consumer.
            transactions = TransactionTemplate(metadataTransactionManager),
            bounds = bounds,
        )
}
