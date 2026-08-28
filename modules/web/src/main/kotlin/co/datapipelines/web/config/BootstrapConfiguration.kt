package co.datapipelines.web.config

import co.datapipelines.auth.PersonalWorkspaceSeeder
import co.datapipelines.auth.UserService
import co.datapipelines.datasources.BootstrapDatasourceRegistrar
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.datasources.DatasourceRepository
import co.datapipelines.web.bootstrap.BootstrapDatasourceStartup
import co.datapipelines.web.bootstrap.BootstrapProperties
import co.datapipelines.web.bootstrap.ExampleContentSeeder
import co.datapipelines.web.pipelines.PipelineImportService
import co.datapipelines.web.templates.TemplateImportService
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Wiring for config-declared bootstrap content (sample-data design §6/§6.1) — explicit `@Bean`s,
 * like every other module's (015, module-structure §8.4).
 *
 * All four beans exist unconditionally and are inert when their key is unset: `unset = off` is a
 * property check inside the bean, never a conditional bean definition. That keeps the bean graph
 * identical in every deployment — the same graph the smoke tests assert — and puts the "is this
 * feature on?" answer in exactly one place per feature.
 */
@Configuration
@EnableConfigurationProperties(BootstrapProperties::class)
class BootstrapConfiguration {
    @Bean
    fun bootstrapDatasourceRegistrar(
        registry: DatasourceRegistry,
        repository: DatasourceRepository,
    ): BootstrapDatasourceRegistrar = BootstrapDatasourceRegistrar(registry, repository)

    @Bean
    fun bootstrapDatasourceStartup(
        properties: BootstrapProperties,
        userService: UserService,
        registrar: BootstrapDatasourceRegistrar,
    ): BootstrapDatasourceStartup = BootstrapDatasourceStartup(properties, userService, registrar)

    /**
     * The D9 hook `auth` consumes through an `ObjectProvider`. Constructing it reads and checks
     * the examples file, so a broken file fails startup here rather than at someone's first login.
     */
    @Bean
    fun personalWorkspaceSeeder(
        properties: BootstrapProperties,
        pipelineImportService: PipelineImportService,
        templateImportService: TemplateImportService,
    ): PersonalWorkspaceSeeder = ExampleContentSeeder(properties, pipelineImportService, templateImportService)
}
