package co.datapipelines.web.config

import co.datapipelines.auth.AuditLogger
import co.datapipelines.auth.PromotionProperties
import co.datapipelines.auth.UserService
import co.datapipelines.auth.WorkspaceRepository
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.pipeline.AuthoringGuard
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.templates.TemplateRepository
import co.datapipelines.web.pipelines.EndpointPromotion
import co.datapipelines.web.pipelines.PipelineImportService
import co.datapipelines.web.pipelines.PromotionInventoryService
import co.datapipelines.web.pipelines.PromotionReceiveService
import co.datapipelines.web.pipelines.PromotionService
import co.datapipelines.web.pipelines.PromotionTargetClient
import co.datapipelines.web.templates.TemplateImportService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * Promotion's beans (versioning §10) — the sender, the receiver and the credential-derived
 * client, wired explicitly like every other module here (module-structure §8.4: no component
 * scanning anywhere).
 *
 * The deployment's own posture is read once, here, off the `Environment`, through the
 * constants that own those keys ([DeploymentEnv] and [AuthoringGuard.CONFIG_KEY]) rather than
 * as fresh string literals. That is not tidiness: the env key has exactly ONE spelling in
 * production sources and `DeploymentNameBranchingGuardTest` pins it there. Promotion carries
 * the env label as DATA — the `source_env` a receiver records — and never branches on it,
 * which is the invariant that guard exists to protect. The 039 spelling
 * ([DeploymentEnv.LEGACY_ENV_KEY]) is resolved as that object's one-release alias, so a
 * deployment that has not yet renamed the variable keeps promoting under its own name.
 */
@Configuration
class PromotionConfiguration {
    @Bean
    fun promotionInventoryService(
        environment: Environment,
        workspaces: WorkspaceRepository,
        pipelines: PipelineRepository,
        templates: TemplateRepository,
        datasources: DatasourceRegistry,
    ): PromotionInventoryService =
        PromotionInventoryService(
            workspaces,
            pipelines,
            templates,
            datasources,
            deploymentName(environment),
            authoringEnabled(environment),
        )

    /**
     * The receiver. Its `TransactionTemplate` is built over the injected
     * **`PlatformTransactionManager`** — since 056 the explicitly declared
     * `metadataTransactionManager` (`app`'s `TransactionConfiguration`), the manager both
     * import services' `NamedParameterJdbcTemplate` writes through. Named here because §10.4's
     * all-or-nothing rule is only true if this is the right manager.
     *
     * **056 (R6) pointer:** when `PipelineService` lands, this demarcation moves onto it and
     * this bean should take the service instead of a transaction template.
     */
    @Bean
    // A DI factory's arity is the container's business, not a design smell: every parameter is a
    // bean this service genuinely needs, and collapsing them into a holder purely to satisfy the
    // rule would add a type that exists only to be counted.
    @Suppress("LongParameterList")
    fun promotionReceiveService(
        environment: Environment,
        inventory: PromotionInventoryService,
        pipelineImportService: PipelineImportService,
        templateImportService: TemplateImportService,
        userService: UserService,
        auditLogger: AuditLogger,
        transactionManager: PlatformTransactionManager,
        // 074 — endpoints ride the batch, through the same publish path REST and MCP use.
        endpointPromotion: EndpointPromotion,
    ): PromotionReceiveService =
        PromotionReceiveService(
            inventory,
            pipelineImportService,
            templateImportService,
            userService,
            auditLogger,
            TransactionTemplate(transactionManager),
            authoringEnabled(environment),
            endpointPromotion,
        )

    /** 074 — the endpoint half of a promotion batch, sender and receiver rules in one place. */
    @Bean
    fun endpointPromotion(
        publishing: co.datapipelines.application.endpoints.EndpointPublishService,
        keys: co.datapipelines.application.endpoints.EndpointKeyService,
        endpoints: co.datapipelines.application.endpoints.PublishedEndpointRepository,
        bindings: co.datapipelines.application.endpoints.EndpointKeyBindingRepository,
        apiKeys: co.datapipelines.auth.ApiKeyRepository,
        pipelines: PipelineRepository,
    ): EndpointPromotion = EndpointPromotion(publishing, keys, endpoints, bindings, apiKeys, pipelines)

    @Bean
    fun promotionTargetClient(promotionProperties: PromotionProperties): PromotionTargetClient = PromotionTargetClient(promotionProperties)

    @Bean
    fun promotionService(
        environment: Environment,
        pipelines: PipelineRepository,
        templates: TemplateRepository,
        client: PromotionTargetClient,
        promotionProperties: PromotionProperties,
        // 074 — the endpoints published over the promoted pipelines.
        endpointPromotion: EndpointPromotion,
    ): PromotionService =
        PromotionService(
            pipelines = pipelines,
            templates = templates,
            client = client,
            promotionProperties = promotionProperties,
            deploymentName = deploymentName(environment),
            endpointPromotion = endpointPromotion,
        )

    /** The deployment LABEL, carried as data (never branched on) — see the class KDoc. */
    private fun deploymentName(environment: Environment): String =
        DeploymentEnv.resolveEnv(
            environment.getProperty(DeploymentEnv.ENV_KEY),
            environment.getProperty(DeploymentEnv.LEGACY_ENV_KEY),
        )

    private fun authoringEnabled(environment: Environment): Boolean =
        environment.getProperty(AuthoringGuard.CONFIG_KEY, Boolean::class.java) ?: true
}
