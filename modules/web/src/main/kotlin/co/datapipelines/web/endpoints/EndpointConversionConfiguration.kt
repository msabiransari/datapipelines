package co.datapipelines.web.endpoints

import co.datapipelines.application.endpoints.EndpointRegistry
import co.datapipelines.application.endpoints.EndpointVersionConversion
import co.datapipelines.application.endpoints.PublishedEndpointVersionRepository
import co.datapipelines.typesystem.guard.MetadataWriterGuard
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * One step the upgrade runs at startup, after Flyway and before the server serves or the scheduler dispatches (#786
 * §E.1, §D.5). The port is web's so the composition root (`modules/app`, which lists `web` only) can order the steps
 * without compiling against `application`; a step that refuses throws, and the throw refuses the startup.
 */
fun interface StartupConversion {
    /** Runs the step; idempotent, a no-op once nothing is left to convert. */
    fun convert()
}

/** #786 §E — the in-place API conversion's beans; `modules/app`'s startup component runs it. */
@Configuration
class EndpointConversionConfiguration {
    @Bean
    fun endpointVersionConversion(
        jdbc: NamedParameterJdbcTemplate,
        versions: PublishedEndpointVersionRepository,
        registry: EndpointRegistry,
        transactionManager: PlatformTransactionManager,
        writeGuard: MetadataWriterGuard,
    ): EndpointVersionConversion = EndpointVersionConversion(jdbc, versions, registry, TransactionTemplate(transactionManager), writeGuard)

    /** The API step (E.1). The schedule step (D.5, unit U2) joins it as a second [StartupConversion]. */
    @Bean
    fun endpointStartupConversion(conversion: EndpointVersionConversion): StartupConversion = StartupConversion { conversion.run() }
}
