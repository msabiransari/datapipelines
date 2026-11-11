package co.datapipelines.config

import co.datapipelines.web.endpoints.StartupConversion
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment

/**
 * The app module's own bean wiring (015, module-structure.md §8.4): `app` is the
 * composition root, and its one collaborator is declared here explicitly like every
 * other module's beans.
 */
@Configuration
class AppConfiguration {
    @Bean
    fun configValidator(environment: Environment): ConfigValidator = ConfigValidator(environment)

    /** #786 §E.1 — the upgrade's in-place conversion steps, run after Flyway and before serving or dispatch. */
    @Bean
    fun conversionStartup(steps: List<StartupConversion>): ConversionStartup = ConversionStartup(steps)
}
