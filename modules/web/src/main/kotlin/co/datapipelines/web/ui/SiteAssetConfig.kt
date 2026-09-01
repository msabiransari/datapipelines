package co.datapipelines.web.ui

import org.springframework.context.annotation.Configuration
import org.springframework.http.CacheControl
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import java.util.concurrent.TimeUnit

/**
 * Cache headers for the marketing site's own assets (033/D1) — the same defence the page
 * itself gets from [SiteController]: these files are fingerprint-free but change only with
 * a deploy, so a public shared-cache TTL is the correct (and free) control. Spring's
 * resource handler also emits `Last-Modified`, so conditional revalidation works past the TTL.
 * No application rate limiter — see the T46 note on [SiteController].
 */
@Configuration
class SiteAssetConfig : WebMvcConfigurer {
    override fun addResourceHandlers(registry: ResourceHandlerRegistry) {
        registry
            .addResourceHandler("/site/**")
            .addResourceLocations("classpath:/static/site/")
            .setCacheControl(CacheControl.maxAge(1, TimeUnit.HOURS).cachePublic())
    }
}
