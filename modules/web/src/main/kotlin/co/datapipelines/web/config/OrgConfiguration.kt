package co.datapipelines.web.config

import co.datapipelines.pipeline.OrgContext
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * The organisation tier of every execution Context (072, calculators design §0.1/§0.2;
 * [Configuration §3.21](../../../../../../../docs/configuration.md)).
 *
 * One bean, its own class. It could have gone in `DomainConfiguration` beside the validator or in
 * `EngineConfiguration` beside the executor — both consume it — and detekt's `TooManyFunctions`
 * said no to each, which is the check doing exactly its job: the two are already at the threshold
 * and a config class that keeps absorbing one more bean is how a wiring file becomes unreadable.
 *
 * The projection matters more than the placement. `OrgProperties` is Spring's shape; [OrgContext]
 * is the domain's, declared in `pipeline-contract` with no Spring on its classpath. Doing the
 * conversion HERE is what keeps the promise the executor's KDoc makes — that it takes
 * already-resolved values and never reads configuration itself.
 */
@Configuration
@EnableConfigurationProperties(OrgProperties::class)
class OrgConfiguration {
    @Bean
    fun orgContext(properties: OrgProperties): OrgContext = properties.toContext()
}
