package co.datapipelines.web.config

import co.datapipelines.datasources.DatasourceAuditSink
import co.datapipelines.datasources.DatasourceMetadataCache
import co.datapipelines.datasources.DatasourceReferences
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.datasources.DatasourceRepository
import co.datapipelines.datasources.DatasourceValidator
import co.datapipelines.datasources.DefaultDatasourceRegistry
import co.datapipelines.datasources.SchemaIntrospector
import co.datapipelines.datasources.crypto.CredentialEncryptor
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.PipelineResolver
import co.datapipelines.pipeline.PipelineValidator
import co.datapipelines.pipeline.TemplateDryRenderer
import co.datapipelines.staging.H2StagingFactory
import co.datapipelines.staging.H2StagingProperties
import co.datapipelines.staging.StagingFactory
import co.datapipelines.web.pipelines.PipelineBodies
import co.datapipelines.web.pipelines.repositoryPipelineResolver
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import co.datapipelines.pipeline.DatasourceRegistry as ContractDatasourceRegistry

/**
 * The domain collaborators the surface assembles (module-structure §5.9 — `web` is the aggregation
 * layer).
 *
 * `datasources` and `staging` ship no Spring configuration of their own, deliberately: they are
 * libraries whose construction depends on values only the assembling layer holds (the encryption
 * key, the resolved staging properties, the pipeline-name lookup a datasource delete needs). This
 * is that layer.
 *
 * `templates` and `pipeline-contract` are different — `templates` ships `TemplatesConfiguration`
 * and both repositories are `@Repository`, so those beans are already in the context and are
 * injected here rather than rebuilt.
 */
@Configuration
@EnableConfigurationProperties(
    SseProperties::class,
    RateLimitProperties::class,
    ResultProperties::class,
    ExecutorProperties::class,
    StagingH2Properties::class,
    IdempotencyProperties::class,
    PipelineProperties::class,
)
class DomainConfiguration {
    /**
     * The AES-256-GCM encryptor for stored datasource passwords (datasources §6).
     *
     * Built from `datapipelines.db.encryption-key`, which configuration.md §2 lists as **required
     * with no fallback**; [CredentialEncryptor.fromBase64Key] fails startup with a precise message
     * when it is missing or not exactly 32 decoded bytes.
     */
    @Bean
    fun credentialEncryptor(
        @Value("\${datapipelines.db.encryption-key:}") key: String,
    ): CredentialEncryptor = CredentialEncryptor.fromBase64Key(key)

    /**
     * The pipeline-name lookup a datasource delete needs (datasources §9, `datasource.in_use`).
     *
     * `datasources` cannot depend on `pipeline-contract` (§4.2), so it declares this port and the
     * aggregation layer supplies it. The scan is bounded by [PipelineBodies], which pushes the
     * datasource filter to SQL via [PipelineRepository.findAllByDatasource].
     */
    @Bean
    fun datasourceReferences(bodies: PipelineBodies): DatasourceReferences =
        DatasourceReferences { name -> bodies.pipelinesReferencing(name) }

    @Bean
    fun datasourceRegistry(
        repository: DatasourceRepository,
        encryptor: CredentialEncryptor,
        references: DatasourceReferences,
    ): DatasourceRegistry =
        DefaultDatasourceRegistry(
            repository = repository,
            encryptor = encryptor,
            validator = DatasourceValidator(),
            references = references,
            auditSink = DatasourceAuditSink.NONE,
            cache = DatasourceMetadataCache(),
        )

    /**
     * The `pipeline-contract` port for "what dialect is this datasource" — the same registry,
     * narrowed. Declared explicitly because the two interfaces share a name across two packages
     * and Spring would otherwise have no bean of the contract-side type at all.
     */
    @Bean
    fun contractDatasourceRegistry(registry: DatasourceRegistry): ContractDatasourceRegistry =
        ContractDatasourceRegistry { name -> registry.dialectOf(name) }

    /** The §7A introspector — reads JDBC metadata through the same registry pool (§5.2). */
    @Bean
    fun schemaIntrospector(registry: DatasourceRegistry): SchemaIntrospector = SchemaIntrospector(registry)

    /**
     * The repository-backed [PipelineResolver] composition validation resolves pinned references
     * through (design 2026-08-13-pipeline-node-type §3, D5). The resolution rules live in
     * [repositoryPipelineResolver]; this bean is the assembly.
     */
    @Bean
    fun pipelineResolver(repository: PipelineRepository): PipelineResolver = repositoryPipelineResolver(repository)

    @Bean
    fun pipelineValidator(
        datasources: ContractDatasourceRegistry,
        dryRenderer: TemplateDryRenderer,
        pipelines: PipelineResolver,
        properties: PipelineProperties,
    ): PipelineValidator =
        PipelineValidator(
            datasources,
            dryRenderer,
            pipelines,
            properties.maxCompositionDepth,
        )

    /**
     * The per-execution tempdb factory (staging §3).
     *
     * The properties are the **already-resolved** effective values; the per-pipeline
     * `max_memory_mb` override is applied by the executor, clamped to this ceiling
     * (configuration.md §3.3).
     */
    @Bean
    fun stagingFactory(properties: StagingH2Properties): StagingFactory =
        H2StagingFactory(
            H2StagingProperties(
                mode = properties.mode,
                maxMemoryMb = properties.maxMemoryMb,
                insertBatchSize = properties.insertBatchSize,
                resultBatchSize = properties.resultBatchSize,
                queryTimeoutSeconds = properties.queryTimeoutSeconds,
            ),
        )

    /** Exposed so [PipelineBodies] and the controllers share one repository instance. */
    @Bean
    fun pipelineBodies(repository: PipelineRepository): PipelineBodies = PipelineBodies(repository)
}
