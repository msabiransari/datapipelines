package co.datapipelines.web.ui

import co.datapipelines.jsrendered.JsRenderedEngineFiles
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.Resource
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import org.springframework.web.servlet.resource.PathResourceResolver

/**
 * The js-rendered chart engine's files on the page (#987 D2; js-rendered record §5.1, §7): ONE source, served. The
 * page loads the SAME files the server's sandbox loads, from the engine module's resources ([JsRenderedEngineFiles]),
 * in the order that object gives, under [PREFIX]. The pattern is [SiteAssetConfig]'s: a Spring MVC resource handler
 * over a classpath location. It adds no public-path line: the product's script prefix is already public at
 * `PublicPaths` (the engine is the product's own JavaScript, the same as every other page script).
 *
 * Only the engine's listed files are served ([EngineFilesOnly]): the order file and any other name under the engine's
 * directory answer 404, so the page can reach exactly what [scripts] names. Its cache rule, content validator and
 * last-modified setting are every other page script's (#1023): kept and re-checked on every use, from
 * [StaticAssetCacheConfig.withCacheRule].
 */
@Configuration
class JsRenderedAssetConfig : WebMvcConfigurer {
    override fun addResourceHandlers(registry: ResourceHandlerRegistry) {
        StaticAssetCacheConfig
            .withCacheRule(
                registry.addResourceHandler("$PREFIX**").addResourceLocations("classpath:/${JsRenderedEngineFiles.ROOT}/"),
                StaticAssetCacheConfig.RECHECK_RULE,
            ).resourceChain(false)
            .addResolver(EngineFilesOnly())
    }

    /** The default path resolver, answering only for a name the engine's load order lists. */
    internal class EngineFilesOnly : PathResourceResolver() {
        override fun getResource(
            resourcePath: String,
            location: Resource,
        ): Resource? = if (resourcePath in JsRenderedEngineFiles.names) super.getResource(resourcePath, location) else null
    }

    companion object {
        /** Where the page finds the engine's files: one path per name of the engine's load order. */
        const val PREFIX: String = "/js/js-rendered/"

        /**
         * The page's script list for the engine, in load order: what a template that draws a chart loads, from this
         * list only, never a hand-written run of tags (D2).
         */
        val scripts: List<String> by lazy { JsRenderedEngineFiles.names.map { PREFIX + it } }
    }
}
