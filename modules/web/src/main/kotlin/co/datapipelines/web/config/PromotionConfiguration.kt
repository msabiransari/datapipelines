package co.datapipelines.web.config

import co.datapipelines.auth.AuditLogger
import co.datapipelines.auth.PromotionProperties
import co.datapipelines.auth.UserService
import co.datapipelines.auth.WorkspaceRepository
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.pipeline.AuthoringGuard
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.templates.TemplateRepository
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
 * The deployment's own posture is read once, here, off the `Environment`, through the two
 * constants that own those keys ([AuthoringStartupCheck.DEPLOYMENT_NAME_KEY] and
 * [AuthoringGuard.CONFIG_KEY]) rather than as fresh string literals. That is not tidiness: the
 * name key has exactly ONE spelling in production sources and `DeploymentNameBranchingGuardTest`
 * pins it there. Promotion carries the name as DATA — the `source_env` a receiver records — and
 * never branches on it, which is the invariant that guard exists to protect.
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
     * **`PlatformTransactionManager`** — Spring Boot's auto-configured
     * `DataSourceTransactionManager` for the metadata `DataSource`, which is the manager both
     * import services' `NamedParameterJdbcTemplate` writes through. Named here because §10.4's
     * all-or-nothing rule is only true if this is the right manager.
     *
     * **056 (R6) pointer:** when `PipelineService` lands, this demarcation moves onto it and
     * this bean should take the service instead of a transaction template.
     */
    @Bean
    fun promotionReceiveService(
        environment: Environment,
        inventory: PromotionInventoryService,
        pipelineImportService: PipelineImportService,
        templateImportService: TemplateImportService,
        userService: UserService,
        auditLogger: AuditLogger,
        transactionManager: PlatformTransactionManager,
    ): PromotionReceiveService =
        PromotionReceiveService(
            inventory,
            pipelineImportService,
            templateImportService,
            userService,
            auditLogger,
            TransactionTemplate(transactionManager),
            authoringEnabled(environment),
        )

    @Bean
    fun promotionTargetClient(promotionProperties: PromotionProperties): PromotionTargetClient =
        PromotionTargetClient(promotionProperties)

    @Bean
    fun promotionService(
        environment: Environment,
        pipelines: PipelineRepository,
        templates: TemplateRepository,
        client: PromotionTargetClient,
        promotionProperties: PromotionProperties,
    ): PromotionService = PromotionService(pipelines, templates, client, promotionProperties, deploymentName(environment))

    /** The deployment LABEL, carried as data (never branched on) — see the class KDoc. */
    private fun deploymentName(environment: Environment): String =
        environment.getProperty(AuthoringStartupCheck.DEPLOYMENT_NAME_KEY)?.trim().orEmpty()

    private fun authoringEnabled(environment: Environment): Boolean =
        environment.getProperty(AuthoringGuard.CONFIG_KEY, Boolean::class.java) ?: true
}
