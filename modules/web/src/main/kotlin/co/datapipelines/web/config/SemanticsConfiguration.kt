package co.datapipelines.web.config

import co.datapipelines.application.semantics.FactEnrichment
import co.datapipelines.application.semantics.LearnedFactsEnricher
import co.datapipelines.application.semantics.SemanticsService
import co.datapipelines.auth.AuditEventSink
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.datasources.SchemaIntrospector
import co.datapipelines.datasources.SqlProbe
import co.datapipelines.datasources.semantics.LearnedFactRecorder
import co.datapipelines.datasources.semantics.LearnedFactRepository
import co.datapipelines.pipeline.PipelineRepository
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

/**
 * The learned semantic layer's beans (metadata-db §4.18, round 118) — the [LakeConfiguration]
 * shape: the store, the recorder below the principal, the service above it, and the enrichment
 * the three introspection surfaces (MCP and REST alike) attach their facts through.
 */
@Configuration
class SemanticsConfiguration {
    /** The `learned_facts` rows. */
    @Bean
    fun learnedFactRepository(jdbc: NamedParameterJdbcTemplate): LearnedFactRepository = LearnedFactRepository(jdbc)

    /**
     * The recorder: ref validation through the SAME introspector the schema endpoints use, and
     * evidence through its own [SqlProbe] over the same registry — the probe class is stateless,
     * so a second instance beside the MCP tool's shares every pool and every rule.
     */
    @Bean
    fun learnedFactRecorder(
        repository: LearnedFactRepository,
        introspector: SchemaIntrospector,
        datasources: DatasourceRegistry,
    ): LearnedFactRecorder = LearnedFactRecorder(repository, introspector, SqlProbe(datasources))

    /** The three verbs above the principal, sharing the application's audit sink. */
    @Bean
    fun semanticsService(
        repository: LearnedFactRepository,
        recorder: LearnedFactRecorder,
        pipelines: PipelineRepository,
        auditSink: AuditEventSink,
    ): SemanticsService = SemanticsService(repository, recorder, pipelines, auditSink)

    /** D-S7 — the facts merged into every introspection response, with the D-S9 pipeline predicate. */
    @Bean
    fun factEnrichment(
        repository: LearnedFactRepository,
        pipelines: PipelineRepository,
    ): FactEnrichment = LearnedFactsEnricher(repository, pipelines)
}
