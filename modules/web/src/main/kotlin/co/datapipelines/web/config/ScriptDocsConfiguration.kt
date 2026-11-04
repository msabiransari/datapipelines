package co.datapipelines.web.config

import co.datapipelines.mcp.docs.ScriptDocValues
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * 780 — `web`'s script contribution to the served manual: the `datapipelines.script.*`
 * numbers the `scripts-evaluation` narrative quotes, built from [ScriptProperties]. The
 * MCP-side `docSet` bean takes it as a plain REQUIRED constructor parameter — the 068/074
 * pattern the other cross-module collaborators follow (`mcp-server` must not depend on
 * `web`, §5.8), and no silent fallback to defaults (a deployment serves the numbers it
 * runs). Kept out of `ScriptConfiguration` (one concern, one file — the per-class
 * bean-count guard's rule this module's config files follow).
 */
@Configuration
class ScriptDocsConfiguration {
    @Bean
    fun scriptDocValues(properties: ScriptProperties): ScriptDocValues =
        ScriptDocValues(
            maxInputRows = properties.maxInputRows,
            poolSize = properties.poolSize,
            poolQueue = properties.poolQueue,
            maxDepth = properties.maxDepth,
        )
}
