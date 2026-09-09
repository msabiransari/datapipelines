package co.datapipelines.web.ui

import co.datapipelines.auth.UserRepository
import co.datapipelines.templates.TemplateRepository
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository

/**
 * UI configuration — enables [UiProperties] property binding and declares the UI
 * collaborators explicitly (015, module-structure.md §8.4).
 * Thymeleaf is auto-configured by Spring Boot; the `error/` templates
 * in `templates/error/` are picked up by [org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration].
 */
@Configuration
@EnableConfigurationProperties(UiProperties::class)
class UiConfig {
    @Bean
    fun oidcRegistrations(repository: ClientRegistrationRepository): OidcRegistrations = OidcRegistrations(repository)

    /** Bean name pinned: it is the name the scanned stereotype carried (see [ThemeResolver]). */
    @Bean(name = ["uiThemeResolver"])
    fun themeResolver(
        userRepository: UserRepository,
        uiProperties: UiProperties,
    ): ThemeResolver = ThemeResolver(userRepository, uiProperties)

    /** 033: the memoized in-product spec set (renders once at startup; see [DocsCatalog]). */
    @Bean
    fun docsCatalog(): DocsCatalog = DocsCatalog(javaClass.classLoader)

    /**
     * 091: the API-key table's row model, shared by the page and BOTH partial responses —
     * the post-create refresh and the rows a revoke swaps in (see [ApiKeyRows]).
     */
    @Bean
    fun apiKeyRows(bindings: co.datapipelines.application.endpoints.EndpointKeyBindingRepository): ApiKeyRows = ApiKeyRows(bindings)

    /** 047: the templates screen's one model, shared by the page and the partial controllers. */
    @Bean
    fun templateBrowseModel(templates: TemplateRepository): TemplateBrowseModel = TemplateBrowseModel(templates)

    /** 089 §A: the LAKE datasource detail's read-only tree model — stateless, one shared instance. */
    @Bean
    fun lakeTableBrowseModel(): LakeTableBrowseModel = LakeTableBrowseModel()

    /** 079 §A: the rail's Pipelines/Templates badges, behind a 60s TTL (see [NavCounts]). */
    @Bean
    fun navCounts(
        pipelines: co.datapipelines.pipeline.PipelineRepository,
        templates: TemplateRepository,
    ): NavCounts = NavCounts(pipelines, templates)

    /**
     * 097 §A: the datasources screen's one model, shared by the page and the partial
     * controllers — the projection this screen had been building twice (see
     * [DatasourceBrowseModel]).
     */
    @Bean
    fun datasourceBrowseModel(datasources: co.datapipelines.datasources.DatasourceRegistry): DatasourceBrowseModel =
        DatasourceBrowseModel(datasources)

    /**
     * 097 §B: the execution-history screen's one model, shared by the page and the partial —
     * which is what let `/executions` render its first fragment instead of a spinner.
     */
    @Bean
    fun executionHistoryBrowseModel(
        executions: co.datapipelines.executor.ExecutionRepository,
        pipelineNames: PipelineNames,
        pipelines: co.datapipelines.pipeline.PipelineRepository,
    ): ExecutionHistoryBrowseModel = ExecutionHistoryBrowseModel(executions, pipelineNames, pipelines)

    /** 067: the pipelines explorer's one model, shared by the page and the partial controllers. */
    @Bean
    fun pipelineBrowseModel(
        pipelines: co.datapipelines.pipeline.PipelineService,
        repository: co.datapipelines.pipeline.PipelineRepository,
    ): PipelineBrowseModel = PipelineBrowseModel(pipelines, repository)
}
