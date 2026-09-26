package co.datapipelines.web.config

import co.datapipelines.mcp.docs.DocErrorCatalog
import co.datapipelines.web.api.ApiErrorDocCatalog
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * 242a — `web`'s contribution to the served manual: the [DocErrorCatalog] port's
 * implementation, answered from `ApiErrorCatalog` (the module's own §13 projection). The
 * MCP-side `docSet` bean takes it as a plain constructor parameter — the 068/074 pattern the
 * other cross-module collaborators follow (`mcp-server` must not depend on `web`, §5.8).
 */
@Configuration
class DocsConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun docErrorCatalog(): DocErrorCatalog = ApiErrorDocCatalog()
}
