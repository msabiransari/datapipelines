package co.datapipelines.web.lake

import co.datapipelines.application.lake.LakePromotionCheck
import co.datapipelines.application.lake.LakeReferenceIndexRepository
import co.datapipelines.application.lake.LakeReferenceScanService
import co.datapipelines.application.lake.LakeTableAvailability
import co.datapipelines.application.lake.RegisteredLakeTableAvailability
import co.datapipelines.datasources.LakeTableCatalog
import co.datapipelines.datasources.SqlTableReferenceExtractor
import co.datapipelines.parameters.ParameterSetRepository
import co.datapipelines.parameters.SelectorProbe
import co.datapipelines.pipeline.DatasourceRegistry
import co.datapipelines.pipeline.OrgContext
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.templates.NodeSqlResolver
import co.datapipelines.templates.TemplateRepository
import co.datapipelines.templates.WorkspaceTemplateEngines
import co.datapipelines.web.config.LakeReferenceBackfill
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * The lake reference scanner's beans (#778, slice D-A…D-F; dp-lake design record §7): the parse-only extractor, the
 * index, the scanner as THE [co.datapipelines.pipeline.release.ReleasedVersionObserver] the release and import writers
 * call inside their transactions (the owner's 2026-11-02 seam ruling), the promotion check and its availability
 * predicate, and the boot backfill. A file of its own so no existing configuration class grows.
 */
@Configuration
class LakeReferenceConfiguration {
    /**
     * The ONE parse-only DuckDB connection (`enable_external_access=false`, nothing attached), shared by every scan.
     * Spring's inferred destroy method calls [SqlTableReferenceExtractor.close] at shutdown.
     */
    @Bean
    fun sqlTableReferenceExtractor(): SqlTableReferenceExtractor = SqlTableReferenceExtractor()

    @Bean
    fun lakeReferenceIndexRepository(jdbc: NamedParameterJdbcTemplate): LakeReferenceIndexRepository = LakeReferenceIndexRepository(jdbc)

    /**
     * The scanner. The SQL a node runs comes from the SAME [NodeSqlResolver] the node-SQL view uses (constructed here,
     * as that controller does, over the workspace engines); a selector's from the [SelectorProbe] the receive
     * validation renders through. Both render only — no customer datasource is opened.
     */
    @Bean
    @Suppress("LongParameterList") // one collaborator per seam the scan reads
    fun lakeReferenceScanService(
        pipelines: PipelineRepository,
        templates: TemplateRepository,
        engines: WorkspaceTemplateEngines,
        parameterSets: ParameterSetRepository,
        selectors: SelectorProbe,
        datasources: DatasourceRegistry,
        registry: LakeTableCatalog,
        extractor: SqlTableReferenceExtractor,
        index: LakeReferenceIndexRepository,
        org: OrgContext,
    ): LakeReferenceScanService =
        LakeReferenceScanService(
            pipelines = pipelines,
            nodeSql = NodeSqlResolver(pipelines, templates, engines),
            templates = templates,
            parameterSets = parameterSets,
            selectors = selectors,
            datasources = datasources,
            registry = registry,
            extractor = extractor,
            index = index,
            org = org,
        )

    /** "Row present" today; dp-lake P3 swaps in the lifecycle-aware predicate behind the same port. */
    @Bean
    fun lakeTableAvailability(registry: LakeTableCatalog): LakeTableAvailability = RegisteredLakeTableAvailability(registry)

    @Bean
    fun lakePromotionCheck(
        index: LakeReferenceIndexRepository,
        availability: LakeTableAvailability,
    ): LakePromotionCheck = LakePromotionCheck(index, availability)

    /** Indexes the RELEASED versions that predate V52 (or an older scanner) once, at boot, after Flyway. */
    @Bean
    fun lakeReferenceBackfill(
        index: LakeReferenceIndexRepository,
        scanner: LakeReferenceScanService,
        metadataTransactionManager: PlatformTransactionManager,
    ): LakeReferenceBackfill = LakeReferenceBackfill(index, scanner, TransactionTemplate(metadataTransactionManager))
}
