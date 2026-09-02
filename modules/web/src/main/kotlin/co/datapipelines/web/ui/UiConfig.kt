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

    /** 047: the templates screen's one model, shared by the page and the partial controllers. */
    @Bean
    fun templateBrowseModel(templates: TemplateRepository): TemplateBrowseModel = TemplateBrowseModel(templates)
}
