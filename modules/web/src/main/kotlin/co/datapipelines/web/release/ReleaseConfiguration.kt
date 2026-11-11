package co.datapipelines.web.release

import co.datapipelines.application.dependencies.PostgresStatementCeiling
import co.datapipelines.application.dependencies.ReleaseDependencyFamilies
import co.datapipelines.application.dependencies.ReleasePlanningDeadline
import co.datapipelines.application.dependencies.ReleasePlanningWalk
import co.datapipelines.application.endpoints.EndpointReleaseSteps
import co.datapipelines.dependencies.FamilyDescriptor
import co.datapipelines.dependencies.FamilyRegistry
import co.datapipelines.pipeline.PipelineReleaseService
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.release.PipelineDependencySource
import co.datapipelines.pipeline.release.ReleaseAuditSink
import co.datapipelines.pipeline.release.ReleaseBounds
import co.datapipelines.pipeline.release.ReleaseFamily
import co.datapipelines.pipeline.release.ReleaseMutationGuard
import co.datapipelines.pipeline.release.ReleasedVersionObserver
import co.datapipelines.pipeline.release.ResolvedNode
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
 * `metadataTransactionManager` (never a customer, staging or new manager). The release write transaction never runs
 * REQUIRES_NEW; the plan's first closure read is its own read-only REPEATABLE READ transaction, REQUIRES_NEW on the same
 * manager so it never joins a weaker caller (#868 G1, record §7.2 step 2).
 *
 * The neutral dependency registry (#868 G1, record §3.2) is assembled here by DI from the descriptor beans: in G1 the
 * pipeline and template release sources, plus the API family (#786, its descriptor in `EndpointsConfiguration`).
 * [FamilyRegistry.production] refuses a duplicate key and a key outside `FamilyKey.PRODUCTION`, so either fails the
 * context at startup.
 */
@Configuration
@EnableConfigurationProperties(ReleaseDependencyProperties::class)
class ReleaseConfiguration {
    @Bean
    fun releaseBounds(properties: ReleaseDependencyProperties): ReleaseBounds = properties.toBounds()

    /**
     * The writer guard, declared as its concrete type so ONE bean answers both faces: the release families'
     * [ReleaseMutationGuard] and the family-neutral `MetadataWriterGuard` the endpoint, datasource and schedule writers
     * take (#841 G0) — one lock order, one per-transaction bookkeeping, one cumulative deadline.
     */
    @Bean
    fun releaseMutationGuard(
        jdbc: NamedParameterJdbcTemplate,
        bounds: ReleaseBounds,
    ): JdbcReleaseMutationGuard = JdbcReleaseMutationGuard(jdbc.jdbcTemplate, bounds.lockWaitSeconds)

    /** The same JSON the general audit logger writes, so every `audit_log` reader parses a release row unchanged. */
    @Bean
    fun releaseAuditSink(
        jdbc: NamedParameterJdbcTemplate,
        objectMapper: ObjectMapper,
    ): ReleaseAuditSink = StrictReleaseAuditSink(jdbc, objectMapper)

    @Bean
    fun releaseAuthorization(): ReleaseAuthorization = MatrixReleaseAuthorization()

    /**
     * #786 R3 (record §25.4) — the ONE statement bound every release source read runs under: `SET LOCAL statement_timeout`
     * = `min(statement-timeout-seconds, remaining deadline)`, set on the metadata connection the walk's transaction holds
     * immediately before EACH statement a source issues through the ceiling's own template (`PostgresStatementCeiling.jdbc`,
     * over the same metadata `DataSource` the repository beans use). Every release family descriptor takes it and builds
     * its source's repositories over that template; `ReleaseStatementCeilingWiringTest` asserts both for each one.
     */
    @Bean
    fun releaseStatementCeiling(jdbc: NamedParameterJdbcTemplate): PostgresStatementCeiling =
        PostgresStatementCeiling(checkNotNull(jdbc.jdbcTemplate.dataSource) { "the metadata JdbcTemplate has a DataSource" })

    /**
     * The pipeline family over its release source (record §4; G2 adds the other artifact families). The source reads
     * through a repository over the ceiling's template, never the shared repository bean: only that template bounds each
     * statement (#786 R3).
     */
    @Bean
    fun pipelineDependencyFamily(ceiling: PostgresStatementCeiling): FamilyDescriptor<ResolvedNode> =
        ReleaseDependencyFamilies.descriptor(ReleaseFamily.PIPELINE, PipelineDependencySource(PipelineRepository(ceiling.jdbc)), ceiling)

    /** The template family over its release source (no pins today: #518 adds the import arm), over the ceiling's template. */
    @Bean
    fun templateDependencyFamily(ceiling: PostgresStatementCeiling): FamilyDescriptor<ResolvedNode> =
        ReleaseDependencyFamilies.descriptor(ReleaseFamily.TEMPLATE, TemplateDependencySource(TemplateRepository(ceiling.jdbc)), ceiling)

    /** Every descriptor bean, one registry; a duplicate or non-production key fails the context here. */
    @Bean
    fun dependencyFamilyRegistry(descriptors: List<FamilyDescriptor<*>>): FamilyRegistry = FamilyRegistry.production(descriptors)

    /**
     * The release planning deadline (#786 §E.3; owner ruling 2026-11-08, 868 review R1-4 option 1): every plan opens a
     * fresh deadline of `metadata-timeout-seconds`; an expired plan is refused `<family>.release.metadata_unavailable`.
     * G1 pinned this bean to `Deadline.none()`; `ReleasePlanningDeadlineWiringTest` asserts the finite one.
     */
    @Bean
    fun releasePlanningDeadline(properties: ReleaseDependencyProperties): ReleasePlanningDeadline =
        ReleasePlanningDeadline.after(properties.toPlanningLimits().metadataTimeout)

    /**
     * G1's planner walk as ONE bean (#786): the coordinator plans with it, and an API restore rechecks its pin's closure
     * through it in the restore's own transaction — one walk, one set of bounds, one deadline.
     */
    @Bean
    fun releasePlanningWalk(
        registry: FamilyRegistry,
        deadline: ReleasePlanningDeadline,
        metadataTransactionManager: PlatformTransactionManager,
        bounds: ReleaseBounds,
        properties: ReleaseDependencyProperties,
    ): ReleasePlanningWalk =
        ReleasePlanningWalk(
            registry = registry,
            bounds = bounds,
            planningTransactions = ReleasePlanningWalk.planningTransaction(metadataTransactionManager),
            // #868 R1: a failed speculative read rolls back to its savepoint and never aborts the walk's transaction.
            speculativeReads = ReleasePlanningWalk.speculativeReads(metadataTransactionManager),
            deadline = deadline,
            planning = properties.toPlanningLimits(),
        )

    @Bean
    @Suppress("LongParameterList") // the coordinator's collaborators, one per step of the record's algorithm
    fun pipelineReleaseCoordinator(
        releases: PipelineReleaseService,
        pipelines: PipelineRepository,
        templateReleases: TemplateReleaseService,
        planning: ReleasePlanningWalk,
        authorization: ReleaseAuthorization,
        guard: ReleaseMutationGuard,
        audit: ReleaseAuditSink,
        metadataTransactionManager: PlatformTransactionManager,
        bounds: ReleaseBounds,
        /** #778 — the lake reference scanner, told of every pipeline version the coordinator flips. */
        observer: ReleasedVersionObserver,
        /** #786 — the API family's release steps: the coordinator's second root. */
        endpointSteps: EndpointReleaseSteps,
    ): ReleaseCoordinator =
        ReleaseCoordinator(
            releases = releases,
            pipelines = pipelines,
            planning = planning,
            participants =
                listOf(
                    PipelineReleaseParticipant(releases, pipelines),
                    TemplateReleaseParticipant(templateReleases),
                    EndpointReleaseParticipant(endpointSteps),
                ),
            authorization = authorization,
            guard = guard,
            audit = audit,
            // A template of its own, never a bean: a TransactionOperations bean would switch off Boot's
            // auto-configured `transactionTemplate` for every other consumer.
            transactions = TransactionTemplate(metadataTransactionManager),
            bounds = bounds,
            observer = observer,
        )
}
