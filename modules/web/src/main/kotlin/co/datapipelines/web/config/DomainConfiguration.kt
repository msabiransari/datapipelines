package co.datapipelines.web.config

import co.datapipelines.application.datasources.DatasourceCreateService
import co.datapipelines.auth.PromotionProperties
import co.datapipelines.auth.UserService
import co.datapipelines.auth.WorkspaceContentCheck
import co.datapipelines.auth.WorkspaceRepository
import co.datapipelines.datasources.DatasourceAuditSink
import co.datapipelines.datasources.DatasourceMetadataCache
import co.datapipelines.datasources.DatasourceReference
import co.datapipelines.datasources.DatasourceReferences
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.datasources.DatasourceRepository
import co.datapipelines.datasources.DatasourceValidator
import co.datapipelines.datasources.DefaultDatasourceRegistry
import co.datapipelines.datasources.PoolInvalidationPublisher
import co.datapipelines.datasources.SchemaIntrospector
import co.datapipelines.datasources.crypto.CredentialEncryptor
import co.datapipelines.datasources.crypto.KeyProviderConfig
import co.datapipelines.datasources.crypto.KeyProviders
import co.datapipelines.pipeline.AuthoringGuard
import co.datapipelines.pipeline.DatasourceFacts
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.PipelineResolver
import co.datapipelines.pipeline.PipelineValidator
import co.datapipelines.pipeline.TemplateDryRenderer
import co.datapipelines.staging.H2StagingFactory
import co.datapipelines.staging.H2StagingProperties
import co.datapipelines.staging.StagingFactory
import co.datapipelines.templates.TemplateRepository
import co.datapipelines.templates.TemplateValidator
import co.datapipelines.web.api.currentPrincipal
import co.datapipelines.web.pipelines.PipelineBodies
import co.datapipelines.web.pipelines.PipelineImportService
import co.datapipelines.web.pipelines.repositoryPipelineResolver
import co.datapipelines.web.templates.TemplateImportService
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
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
 * `pipeline-contract` ships no Spring configuration either, so its [PipelineRepository] is
 * declared here alongside [DatasourceRepository]; `templates` ships `TemplatesConfiguration`,
 * which declares its own `TemplateRepository` (015, module-structure.md §8.4).
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
    ExecutionsProperties::class,
)
class DomainConfiguration {
    @Bean
    fun datasourceRepository(jdbc: NamedParameterJdbcTemplate): DatasourceRepository = DatasourceRepository(jdbc)

    /** The D8 rules the REST datasource surface and the UI's form partial share (workspaces design §8). */
    @Bean
    fun datasourceWorkspaceRules(
        workspaceService: co.datapipelines.auth.WorkspaceService,
        workspacesProperties: co.datapipelines.auth.WorkspacesProperties,
    ): co.datapipelines.web.datasources.DatasourceWorkspaceRules =
        co.datapipelines.web.datasources
            .DatasourceWorkspaceRules(workspaceService, workspacesProperties)

    /**
     * The ONE validated datasource-registration path, shared by `POST /api/v1/datasources` and
     * the `datasources_create` MCP tool (049's principle: two entry points, one path).
     *
     * The D8 binding rule is passed as a method reference rather than the whole component: the
     * service lives in `application`, which sits below `web` and cannot import
     * [co.datapipelines.web.datasources.DatasourceWorkspaceRules]. Both surfaces therefore run
     * the SAME rules instance — there is no second copy of the permission matrix to drift.
     */
    @Bean
    fun datasourceCreateService(
        datasources: DatasourceRegistry,
        rules: co.datapipelines.web.datasources.DatasourceWorkspaceRules,
    ): DatasourceCreateService = DatasourceCreateService(datasources, rules::resolveCreateBinding)

    @Bean
    fun pipelineRepository(jdbc: NamedParameterJdbcTemplate): PipelineRepository = PipelineRepository(jdbc)

    /**
     * The authoring capability (versioning §5.5, configuration.md §3.19) — the one object
     * every pipeline/template write path consults. Built from the Environment here and
     * consumed by both write surfaces; `mcp-server` builds its own instance from the same
     * property (immutable config — two instances cannot disagree).
     */
    @Bean
    fun authoringGuard(environment: Environment): AuthoringGuard = AuthoringGuard.from(environment)

    /**
     * The §7 boot checks around that capability (configuration.md §3.19): the deployment
     * posture line (the `name` label's ONLY consumer — no code branches on it, pinned by
     * [DeploymentNameBranchingGuardTest]), the receiver-also-authors WARN — currently
     * now BOTH-SIDED (055 wired the promotion half through [PromotionProperties]) — and the refusal
     * when an authoring-disabled deployment still holds drafts, naming them.
     */
    @Bean
    fun authoringStartupCheck(
        environment: Environment,
        pipelines: PipelineRepository,
        templates: TemplateRepository,
        promotionProperties: PromotionProperties,
    ): AuthoringStartupCheck = AuthoringStartupCheck(environment, pipelines, templates) { promotionProperties.receives }

    /**
     * The system service account (auth.md §4.5, R7), provisioned at boot. Unconditional: it
     * is a referential precondition of the schema — `created_by` / `triggered_by` are NOT NULL
     * — not a feature an operator opts into, and its absence would surface as a foreign-key
     * violation inside a promotion or a scheduled job.
     */
    @Bean
    fun systemActorSeeder(userService: UserService): SystemActorSeeder = SystemActorSeeder(userService)

    /**
     * The AES-256-GCM encryptor for stored datasource passwords (datasources §7.1), over the
     * `KeyProvider` its data keys come from.
     *
     * The provider is selected by `datapipelines.db.key-provider`, which defaults to `env` — so a
     * deployment that predates the provider seam is unchanged and needs no config edit.
     * `datapipelines.db.encryption-key` remains configuration.md §2's **required with no
     * fallback** value and is version 1 under `env`.
     *
     * Building both HERE, at startup wiring, is what makes an unreachable key service a STARTUP
     * failure rather than a first-password-write failure (`docs/key-providers.md` §2, invariant
     * 3): [KeyProviders.create] and every provider's own factory throw, [CredentialEncryptor]
     * reads `current()` once in its constructor, and a bean factory method that throws stops the
     * context.
     *
     * The provider is deliberately NOT a bean of its own: nothing else injects it, and it is an
     * implementation detail of this encryptor. A future KMS provider changes which class
     * [KeyProviders] returns, not this wiring.
     */
    @Bean
    fun credentialEncryptor(environment: Environment): CredentialEncryptor =
        CredentialEncryptor(
            KeyProviders.create(environment.getProperty(KeyProviders.PROPERTY), SpringKeyProviderConfig(environment)),
        )

    /**
     * The pipeline-name lookup a datasource delete needs (datasources §9, `datasource.in_use`).
     *
     * `datasources` cannot depend on `pipeline-contract` (§4.2), so it declares this port and the
     * aggregation layer supplies it. The scan is bounded by [PipelineBodies], which pushes the
     * datasource filter to SQL via [PipelineRepository.findAnyVersionDatasourceRefs] — the
     * ANY-VERSION scan (061/T79). The working-version scan it replaced here joined
     * `current_version` only, so a released pipeline whose OLDER version pinned the datasource
     * was invisible: the delete succeeded and that version's next execution failed at connect.
     * A delete guard reads every version ever stored; the pipelines LISTING keeps the
     * working-version scan, because "what am I looking at" and "what would I break" are
     * different questions (040's split, applied to datasources).
     *
     * The count ALWAYS aggregates across every workspace (023 verified, 025 A4): §6.2's
     * "any non-deleted pipeline" is unconditional, and a binding-scoped branch — however
     * appealing as loop avoidance — is a two-step bypass of it (`PUT {"global": false,
     * "workspace": "x"}` re-binds a referenced global datasource with no cross-workspace
     * check, and the bound branch would then count only the new workspace, orphaning every
     * other workspace's references). A bound row CAN be referenced from other workspaces:
     * references saved while the datasource was global survive the re-bind. Reading the
     * row's binding here serves nothing anymore; the caller's workspace never mattered
     * (022 review F5) and the row's own binding no longer does either.
     */
    @Bean
    fun datasourceReferences(
        bodies: PipelineBodies,
        workspaces: WorkspaceRepository,
    ): DatasourceReferences =
        DatasourceReferences { name ->
            workspaces.findAll().flatMap { workspace ->
                bodies.anyVersionReferences(workspace.id, name).map { ref ->
                    DatasourceReference(
                        pipelineName = ref.pipelineName,
                        pipelineVersion = ref.pipelineVersion,
                        versionStatus = ref.versionStatus.name,
                        nodeId = ref.nodeId,
                    )
                }
            }
        }

    @Bean
    fun datasourceRegistry(
        repository: DatasourceRepository,
        encryptor: CredentialEncryptor,
        references: DatasourceReferences,
        invalidation: PoolInvalidationPublisher,
    ): DatasourceRegistry =
        DefaultDatasourceRegistry(
            repository = repository,
            encryptor = encryptor,
            validator = DatasourceValidator(),
            references = references,
            auditSink = DatasourceAuditSink.NONE,
            cache = DatasourceMetadataCache(),
            invalidation = invalidation,
        )

    /**
     * The `pipeline-contract` port for "what do we know about this datasource" — the same
     * registry, narrowed. Declared explicitly because the two interfaces share a name across two
     * packages and Spring would otherwise have no bean of the contract-side type at all.
     *
     * Supplies BOTH facts the validator asks for (workspaces design §6): the dialect AND the
     * readonly flag, from the same registry lookup — so `pipeline.validation.datasource_readonly`
     * fires at save time on every write-shaped use of a flagged datasource.
     *
     * ## Workspace-scoped since the surfaces slice (design §5.3)
     *
     * Save-time validation resolves the datasource through the CALLER'S ACTIVE WORKSPACE:
     * `getVisibleLive(name, activeWorkspace)` — a pipeline in workspace A cannot silently
     * reference a datasource bound to workspace B. The D9 example seeder runs at login on a
     * thread whose principal is not yet [AuthenticatedPrincipal]; for that principal-less
     * path the resolver falls back to GLOBAL-ONLY visibility, which is exactly the seeder's
     * world (D9: seeded example datasources are global). A future bound-datasource example
     * would fail loudly at seeding rather than pass validation invisibly.
     *
     * ## Live reads, not cached (044 F4)
     *
     * Both resolution paths read the LIVE row, past the §6.3 metadata cache — the same row
     * the executor's live backstop answers from. A row-level flag flip (manual SQL or a
     * restore, the D10 channel) never crosses the registry save boundary that invalidates the
     * cache, so a cached read here opened a window where saves validated against a stale flag
     * in BOTH directions — the un-flip direction refused VALID saves with a wrong 400 that no
     * layer covered. Live reads cost one indexed PK read per referenced datasource per save;
     * the REST GET hot path keeps the cache.
     */
    @Bean
    fun contractDatasourceRegistry(registry: DatasourceRegistry): ContractDatasourceRegistry =
        ContractDatasourceRegistry { name ->
            val principal =
                runCatching { currentPrincipal() }.getOrNull()
            val facts =
                when (val workspaceId = principal?.workspace?.id) {
                    null -> registry.getLive(name)?.takeIf { it.workspaceId == null }
                    else -> registry.getVisibleLive(name, workspaceId)
                }
            facts?.let { DatasourceFacts(it.dialect, it.isReadonly) }
        }

    /**
     * The `workspace.in_use` port (auth's `WorkspaceService.delete`): the non-deleted content
     * counts of a workspace, by kind. Auth cannot query these tables (module-structure §4.2),
     * so the aggregation layer answers — `countAll` for pipelines, the active-page listing
     * without its LIMIT for templates (no count API exists and the templates module's write
     * window is T23-only), and the registry's rows filtered to the workspace for bound
     * datasources. Counts are bounded by what a workspace owns; exact beats a UNION across
     * three modules' private schemas.
     */
    @Bean
    fun workspaceContentCheck(
        pipelines: PipelineRepository,
        templates: TemplateRepository,
        datasources: DatasourceRegistry,
    ): WorkspaceContentCheck =
        WorkspaceContentCheck { workspaceId ->
            buildMap {
                pipelines.countAll(workspaceId).takeIf { it > 0 }?.let { put("pipelines", it) }
                templates.list(workspaceId, offset = 0, limit = UNBOUNDED).takeIf { it.isNotEmpty() }?.let { put("templates", it.size) }
                datasources
                    .list()
                    .count { it.workspaceId == workspaceId }
                    .takeIf { it > 0 }
                    ?.let { put("datasources", it) }
            }
        }

    private companion object {
        /** No LIMIT — the listing becomes the exact active count (bounded by workspace content). */
        const val UNBOUNDED = Int.MAX_VALUE
    }

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
        orgContext: co.datapipelines.pipeline.OrgContext,
    ): PipelineValidator =
        PipelineValidator(
            datasources,
            dryRenderer,
            pipelines,
            properties.maxCompositionDepth,
            orgContext,
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

    /**
     * The import acts, extracted from their controllers so the D9 example seeder performs the
     * same import the REST endpoints do (see each service's KDoc). The controllers now take
     * these as collaborators.
     */
    @Bean
    fun pipelineImportService(
        pipelines: PipelineRepository,
        validator: PipelineValidator,
        orgContext: co.datapipelines.pipeline.OrgContext,
        dryRenderer: TemplateDryRenderer,
    ): PipelineImportService =
        PipelineImportService(
            pipelines = pipelines,
            validator = validator,
            // 072 §0.5: the RECEIVER's org tier and its template bodies, so an imported body that
            // reads an `org_*` key this deployment does not define is refused here rather than
            // producing plausible wrong numbers on its first run.
            orgContext = orgContext,
            templates = dryRenderer,
        )

    @Bean
    fun templateImportService(
        templates: TemplateRepository,
        validator: TemplateValidator,
    ): TemplateImportService = TemplateImportService(templates, validator)
}

/**
 * [KeyProviderConfig] over Spring's [Environment] — the aggregation layer supplying the
 * configuration port `datasources` declares, exactly as it supplies `DatasourceReferences`.
 *
 * The map flavour goes through [Binder] rather than [Environment.getProperty] because a map's
 * ENTRIES cannot be enumerated through a property lookup, and `datapipelines.db.encryption-keys`
 * is a map whose keys the operator chooses.
 */
private class SpringKeyProviderConfig(
    private val environment: Environment,
) : KeyProviderConfig {
    override fun string(key: String): String? = environment.getProperty(key)

    override fun map(key: String): Map<String, String> =
        Binder
            .get(environment)
            .bind(key, Bindable.mapOf(String::class.java, String::class.java))
            .orElse(emptyMap())
}
