package co.datapipelines.web.dependencies

import co.datapipelines.application.dependencies.ReleaseGraphLimits
import co.datapipelines.application.dependencies.ReleasePlanningWalk
import co.datapipelines.application.endpoints.EndpointPublishService
import co.datapipelines.application.endpoints.EndpointVersionService
import co.datapipelines.application.lens.PromoterLens
import co.datapipelines.dependencies.FamilyRegistry
import co.datapipelines.dependencies.GraphLimits
import co.datapipelines.parameters.ParameterSetService
import co.datapipelines.pipeline.PipelineService
import co.datapipelines.pipeline.release.ReleaseBounds
import co.datapipelines.scheduler.ScheduleService
import co.datapipelines.templates.TemplateService
import co.datapipelines.visualization.DashboardService
import co.datapipelines.visualization.VisualizationService
import co.datapipelines.web.config.ReleaseDependencyProperties
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate

/**
 * The dependency tree routes' assembly (#875 G4). The walk reads through the ONE neutral registry the release planner
 * uses (`dependencyFamilyRegistry`), under the configured `datapipelines.release.dependencies.*` bounds — the same
 * [GraphLimits] the planner builds ([ReleaseGraphLimits.of]), so `max-resolve-batch-size`, `metadata-timeout-seconds`
 * and `statement-timeout-seconds` (#786 U1, record §25.4) reach the interactive walk as configured, never as constants.
 *
 * Transactions: the tree reads in its OWN read-only READ COMMITTED transaction on `metadataTransactionManager`
 * (record §11.1: an interactive read of live metadata — not the planner's REPEATABLE READ snapshot), REQUIRES_NEW so
 * it never joins a caller's; a failed speculative read rolls back to a savepoint in it ([ReleasePlanningWalk.speculativeReads],
 * the landed mold) and never aborts the walk's transaction.
 */
@Configuration
class DependenciesConfiguration {
    /** The eight production rows, each lensed family's root check asking that family's own lensed read. */
    @Bean
    @Suppress("LongParameterList") // one native service per lensed family
    fun dependencyFamilyTransport(
        pipelines: PipelineService,
        templates: TemplateService,
        parameterSets: ParameterSetService,
        visualizations: VisualizationService,
        dashboards: DashboardService,
        endpoints: EndpointPublishService,
        endpointVersions: EndpointVersionService,
        schedules: ScheduleService,
    ): DependencyFamilyTransport =
        DependencyFamilyTransport.production(
            ProductionRootLenses.of(
                pipelines,
                templates,
                parameterSets,
                visualizations,
                dashboards,
                endpoints,
                endpointVersions,
                schedules,
            ),
        )

    @Bean
    @Suppress("LongParameterList") // the service's collaborators, each named in its KDoc
    fun dependencyTreeService(
        registry: FamilyRegistry,
        transport: DependencyFamilyTransport,
        bounds: ReleaseBounds,
        properties: ReleaseDependencyProperties,
        metadataTransactionManager: PlatformTransactionManager,
        lens: PromoterLens,
        mapper: ObjectMapper,
    ): DependencyTreeService =
        DependencyTreeService(
            registry = registry,
            transport = transport,
            limits = treeLimits(bounds, properties),
            transactions = treeTransaction(metadataTransactionManager),
            readIsolation = ReleasePlanningWalk.speculativeReads(metadataTransactionManager),
            lens = lens,
            mapper = mapper,
        )

    companion object {
        /** The tree's transaction's name, for a reader of the transaction log. */
        const val TREE_TRANSACTION_NAME = "dependency-tree-read"

        /** The configured bounds as the walk's limits — the planner's derivation, one source. */
        fun treeLimits(
            bounds: ReleaseBounds,
            properties: ReleaseDependencyProperties,
        ): GraphLimits = ReleaseGraphLimits.of(bounds, properties.toPlanningLimits())

        /**
         * READ COMMITTED, read-only, REQUIRES_NEW on [metadataTransactionManager]. A template of its own, never a bean —
         * a `TransactionOperations` bean would switch off Boot's auto-configured template.
         */
        fun treeTransaction(metadataTransactionManager: PlatformTransactionManager): TransactionTemplate =
            TransactionTemplate(metadataTransactionManager).apply {
                isolationLevel = TransactionDefinition.ISOLATION_READ_COMMITTED
                isReadOnly = true
                propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
                setName(TREE_TRANSACTION_NAME)
            }
    }
}
