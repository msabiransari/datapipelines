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
    fun templateBrowseModel(
        templates: TemplateRepository,
        usage: co.datapipelines.templates.TemplateUsageService,
        executions: co.datapipelines.executor.ExecutionRepository,
        actorNames: ActorNames,
    ): TemplateBrowseModel = TemplateBrowseModel(templates, usage, executions, actorNames)

    /** 089 §A: the LAKE datasource detail's read-only tree model — stateless, one shared instance. */
    @Bean
    fun lakeTableBrowseModel(): LakeTableBrowseModel = LakeTableBrowseModel()

    /** 079 §A: the rail's Pipelines/Templates badges, behind a 60s TTL (see [NavCounts]). */
    @Bean
    fun navCounts(
        pipelines: co.datapipelines.pipeline.PipelineRepository,
        templates: TemplateRepository,
    ): NavCounts = NavCounts(pipelines, templates)

    /** 106: the version/execution actor lookup both explorer details render. */
    @Bean
    fun actorNames(jdbc: org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate): ActorNames = ActorNames(jdbc)

    /** 106: per-version run counts for the acting column's Versions tab. */
    @Bean
    fun pipelineRunStats(jdbc: org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate): PipelineRunStats =
        PipelineRunStats(jdbc)

    /** 067: the pipelines explorer's one model, shared by the page and the partial controllers. */
    @Bean
    @Suppress("LongParameterList") // 106: the detail's three regions in one call need their sources
    fun pipelineBrowseModel(
        pipelines: co.datapipelines.pipeline.PipelineService,
        repository: co.datapipelines.pipeline.PipelineRepository,
        executions: co.datapipelines.executor.ExecutionRepository,
        endpoints: co.datapipelines.application.endpoints.PublishedEndpointRepository,
        datasources: co.datapipelines.pipeline.DatasourceRegistry,
        actorNames: ActorNames,
        runStats: PipelineRunStats,
        authoring: co.datapipelines.pipeline.AuthoringGuard,
    ): PipelineBrowseModel = PipelineBrowseModel(pipelines, repository, executions, endpoints, datasources, actorNames, runStats, authoring)

    /** 102: the lifecycle dialogs' facts — the same scans the services' own guards read. */
    @Bean
    @Suppress("LongParameterList") // one collaborator per §4.3d fact, exactly like the browse models
    fun pipelineLifecycleDialogModel(
        pipelines: co.datapipelines.pipeline.PipelineService,
        repository: co.datapipelines.pipeline.PipelineRepository,
        templates: co.datapipelines.pipeline.TemplateVersionStatuses,
        exclusiveTemplates: co.datapipelines.pipeline.ExclusiveDraftTemplates,
        runStats: PipelineRunStats,
        actorNames: ActorNames,
        authoring: co.datapipelines.pipeline.AuthoringGuard,
    ): PipelineLifecycleDialogModel =
        PipelineLifecycleDialogModel(pipelines, repository, templates, exclusiveTemplates, runStats, actorNames, authoring)

    /** 102: the template twin of [pipelineLifecycleDialogModel]. */
    @Bean
    fun templateLifecycleDialogModel(
        templates: TemplateRepository,
        pipelines: co.datapipelines.pipeline.PipelineRepository,
        actorNames: ActorNames,
        authoring: co.datapipelines.pipeline.AuthoringGuard,
    ): TemplateLifecycleDialogModel = TemplateLifecycleDialogModel(templates, pipelines, actorNames, authoring)
}
