package co.datapipelines.web.ui

import co.datapipelines.auth.SecurityHeaders
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.Resource
import org.springframework.http.CacheControl
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistration
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import java.io.IOException
import java.io.OutputStream
import java.io.UncheckedIOException
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.function.Function

/**
 * How long a browser keeps the product's static files (#1023; the owner's ruling of 2026-11-16).
 *
 * - Font files (the fonts prefix under vendor) are kept for one year and never re-checked inside it ([FONT_RULE]).
 *   A font file's bytes never change under a name already served: an upgrade gets a new file name and a new line in
 *   the font ledger, which `FontPathLedgerTest` checks against the ledger's git history.
 * - Stylesheets, scripts (the chart engine's included, [JsRenderedAssetConfig]) and every other vendored file are
 *   kept and re-checked with the server on every use ([RECHECK_RULE]).
 *
 * Every handler here, and the engine's, takes its cache rule, its validator and its last-modified setting from
 * [withCacheRule], one function. The validator is the CONTENT ([ContentEtag]): the live `Last-Modified` was the
 * build's time for every file, so a build that ever stamped a fixed time would let a browser keep a changed stylesheet
 * for good, silently; a hash of the bytes cannot. `Last-Modified` is therefore off, so there is ONE validator.
 *
 * The pattern is [SiteAssetConfig]'s: a resource handler over a classpath location, its header replacing the security
 * chain's default `Cache-Control`. The chain's `Pragma: no-cache` and `Expires: 0` stay beside it on every file but a
 * font: Chromium reads that `Pragma` as `no-cache`, so the chain leaves all three off a font file
 * (`SecurityHeaders.cacheControlWriter`). The font prefix is ONE value, [SecurityHeaders.FONT_PATH_PREFIX], which
 * both the chain's matcher and the font handler here use. An error answer on the font path (a missing name: 404; a
 * range the file cannot satisfy: 416) keeps the chain's three not-storable headers all the same, never the font rule:
 * [FontErrorCacheFilter], registered here, sets them when the status is an error. No public path is added: the three prefixes are public
 * already. No configuration key: the durations are the ruling.
 * The more specific pattern wins (the fonts over the vendor prefix, the engine over the scripts prefix):
 * `StaticCacheHeadersE2eTest` proves both on every file.
 */
@Configuration
class StaticAssetCacheConfig : WebMvcConfigurer {
    override fun addResourceHandlers(registry: ResourceHandlerRegistry) {
        withCacheRule(registry.addResourceHandler(FONTS).addResourceLocations(FONTS_LOCATION), FONT_RULE)
        withCacheRule(registry.addResourceHandler(CSS).addResourceLocations("classpath:/static/css/"), RECHECK_RULE)
        withCacheRule(registry.addResourceHandler(JS).addResourceLocations("classpath:/static/js/"), RECHECK_RULE)
        withCacheRule(registry.addResourceHandler(VENDOR).addResourceLocations("classpath:/static/vendor/"), RECHECK_RULE)
    }

    /**
     * The font path's error answers stay not storable ([FontErrorCacheFilter]): container-wide, at its order, just
     * ahead of the security chain; async-capable like every filter on the chain, so an SSE request passes it.
     */
    @Bean
    fun fontErrorCacheFilterRegistration(): FilterRegistrationBean<FontErrorCacheFilter> =
        FilterRegistrationBean(FontErrorCacheFilter()).apply {
            order = FontErrorCacheFilter.ORDER
            isAsyncSupported = true
        }

    companion object {
        /** The font handler's pattern and location, from the one prefix the security chain's matcher also uses. */
        private const val FONTS = SecurityHeaders.FONT_PATH_PREFIX + "**"
        private const val FONTS_LOCATION = "classpath:/static" + SecurityHeaders.FONT_PATH_PREFIX
        private const val CSS = "/css/**"
        private const val JS = "/js/**"
        private const val VENDOR = "/vendor/**"
        private const val ONE_YEAR_IN_DAYS = 365L

        /** Font files: `max-age=31536000, public, immutable` — kept a year, by any cache, never re-checked inside it. */
        val FONT_RULE: CacheControl = CacheControl.maxAge(ONE_YEAR_IN_DAYS, TimeUnit.DAYS).cachePublic().immutable()

        /** Every other static file of this class and the engine's: `no-cache` — kept, re-checked on every use. */
        val RECHECK_RULE: CacheControl = CacheControl.noCache()

        /** The validator every real handler shares; it keeps one hash per packaged file (see [ContentEtag]). */
        private val PACKAGED_ETAGS = ContentEtag()

        /**
         * The ONE place a handler of #1023 gets its settings: [rule] as its `Cache-Control`, the content validator
         * [etags] as its `ETag`, and no `Last-Modified`. [etags] is a parameter only so a test can watch a fresh one.
         */
        fun withCacheRule(
            registration: ResourceHandlerRegistration,
            rule: CacheControl,
            etags: ContentEtag = PACKAGED_ETAGS,
        ): ResourceHandlerRegistration =
            registration
                .setCacheControl(rule)
                .setUseLastModified(false)
                .setEtagGenerator(etags)
    }
}

/**
 * A static file's validator: the SHA-256 of its bytes, in hexadecimal (the framework writes it in quotes).
 *
 * A file inside the packaged jar (`isFile()` false) cannot change while the application runs, so its hash is kept:
 * one entry per such file, keyed by its URL, written only for a resource a handler resolved and never replaced, so the
 * memory is bounded by the number of packaged files. A file on disk (a development instance, a run from a build
 * directory) is hashed on every request and nothing is kept, so a file edited under a running instance is answered by
 * its new bytes whatever its length and its time.
 */
class ContentEtag : Function<Resource, String> {
    private val packaged = ConcurrentHashMap<String, String>()

    /** How many packaged files' hashes are kept. */
    val keptEntries: Int get() = packaged.size

    override fun apply(resource: Resource): String =
        if (resource.isFile) {
            sha256(resource)
        } else {
            packaged.computeIfAbsent(resource.url.toExternalForm()) { sha256(resource) }
        }

    private fun sha256(resource: Resource): String {
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            DigestInputStream(resource.inputStream, digest).use { it.transferTo(OutputStream.nullOutputStream()) }
        } catch (e: IOException) {
            throw UncheckedIOException("could not read ${resource.description} to compute its ETag", e)
        }
        return HexFormat.of().formatHex(digest.digest())
    }
}
