package co.datapipelines.web.ui

import co.datapipelines.jsrendered.JsRenderedEngineFiles
import jakarta.servlet.http.HttpServletRequest
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.ByteArrayResource
import org.springframework.core.io.Resource
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import org.springframework.web.servlet.resource.ResourceResolver
import org.springframework.web.servlet.resource.ResourceResolverChain
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandler

/**
 * The client library (#1064; js-rendered record §4.11, O-10, §8 A6 and A12): ONE script and ONE stylesheet for a page
 * of another origin that embeds a dashboard, [ClientLibrary.SCRIPT_PATH] and [ClientLibrary.STYLESHEET_PATH].
 *
 * Each is the concatenation, in a listed order, of the SAME classpath sources the product's own pages load one by one,
 * each followed by one newline and nothing else ([ClientLibrary.join]): the script is the chart engine's files in the
 * engine's own load order, then the runtime's five files ([ClientLibrary.RUNTIME_FILES]); the stylesheet is the six
 * sheets ([ClientLibrary.STYLESHEETS]), the light theme among them. Nothing is committed and nothing is written by
 * the build: the running application joins the texts once, on the first request, and keeps them ([library]). The
 * product's own pages keep loading the source files, and every per-file path stays served.
 *
 * Each name is a resource handler of its own, answering that name only ([OneFile], the pattern of
 * [JsRenderedAssetConfig.EngineFilesOnly]): the pattern's capture admits exactly the one file name, so every other name
 * under the two prefixes stays the existing handlers' answer, and the near misses (a source map, a minified name) are
 * their 404. Its cache rule, content validator and last-modified setting are every script's
 * ([StaticAssetCacheConfig.withCacheRule], [StaticAssetCacheConfig.RECHECK_RULE]); its content type is the one the
 * framework gives every script and stylesheet, from the file name. No public path is added: both prefixes are public
 * already (`PublicPaths`). The paths are fixed by two facts: those prefixes, and the stylesheet's font URLs, which are
 * relative to a file one directory below the root (`../vendor/fonts/…`, as from the application stylesheet).
 */
@Configuration
class ClientLibraryConfig : WebMvcConfigurer {
    override fun addResourceHandlers(registry: ResourceHandlerRegistry) {
        serve(registry, ClientLibrary.SCRIPT_PATH) { library.script }
        serve(registry, ClientLibrary.STYLESHEET_PATH) { library.stylesheet }
    }

    private fun serve(
        registry: ResourceHandlerRegistry,
        path: String,
        text: () -> ByteArray,
    ) {
        StaticAssetCacheConfig
            .withCacheRule(registry.addResourceHandler(pattern(path)), StaticAssetCacheConfig.RECHECK_RULE)
            .resourceChain(false)
            .addResolver(OneFile(path, text))
    }

    /**
     * A resolver that answers [path]'s file name only, with [text] — the joined bytes as a resource ([JoinedFile]);
     * any other name is null, so the handler answers 404.
     */
    internal class OneFile(
        private val path: String,
        private val text: () -> ByteArray,
    ) : ResourceResolver {
        private val name = path.substringAfterLast('/')

        override fun resolveResource(
            request: HttpServletRequest?,
            requestPath: String,
            locations: List<Resource>,
            chain: ResourceResolverChain,
        ): Resource? = if (requestPath == name) JoinedFile.of(path, text()) else null

        override fun resolveUrlPath(
            resourcePath: String,
            locations: List<Resource>,
            chain: ResourceResolverChain,
        ): String? = if (resourcePath == name) resourcePath else null
    }

    companion object {
        /**
         * The library the application serves: the ONE call that hands the builder the engine's order and texts (D2), so
         * an engine file a type lane adds reaches the script with no edit here. Built on first use, then kept.
         */
        val library: ClientLibrary by lazy {
            ClientLibrary(JsRenderedEngineFiles.names, JsRenderedEngineFiles.sources::getValue, ClientLibrary::staticText)
        }

        /**
         * The handler pattern of one library path: its directory, then a capture whose expression is the file name
         * itself (a literal pattern leaves the handler an empty path, which it refuses). `/js/datapipelines.js` gives
         * `/js/{name:datapipelines\.js}`.
         */
        fun pattern(path: String): String {
            val directory = path.substringBeforeLast('/')
            val name = path.substringAfterLast('/')
            return "$directory/{name:${name.replace(".", "\\.")}}"
        }
    }
}

/**
 * The two joined texts and the lists they are joined from (#1064 D2). The builder takes the engine's part as
 * ARGUMENTS — [engineNames] in load order and [engineSource], a reader of one engine file's text by its name — so no
 * engine file is named in this module's code; [staticSource] reads one file under the classpath's `static/` by its
 * path there. Each text is built once, on first read, and the same bytes are returned on every later read.
 */
class ClientLibrary(
    private val engineNames: List<String>,
    private val engineSource: (String) -> String,
    private val staticSource: (String) -> String,
) {
    /** The script's bytes (UTF-8): the engine's files, then [RUNTIME_FILES]. */
    val script: ByteArray by lazy { script(engineNames, engineSource, staticSource).toByteArray(Charsets.UTF_8) }

    /** The stylesheet's bytes (UTF-8): [STYLESHEETS]. */
    val stylesheet: ByteArray by lazy { stylesheet(staticSource).toByteArray(Charsets.UTF_8) }

    companion object {
        /** Where a page finds the script. */
        const val SCRIPT_PATH: String = "/js/datapipelines.js"

        /** Where a page finds the stylesheet. */
        const val STYLESHEET_PATH: String = "/css/datapipelines.css"

        /**
         * The runtime's five files after the engine, paths under `static/`: the runtime, the charts' interaction layer,
         * the chart adapter, the table and KPI adapters — the vendor manifest's `datapipelines-dashboard-runtime` block,
         * in its order (a test holds the two equal).
         */
        val RUNTIME_FILES: List<String> =
            listOf(
                "js/datapipelines-dashboard.js",
                "js/datapipelines-chart-interactions.js",
                "js/datapipelines-dashboard-chart.js",
                "js/datapipelines-dashboard-table.js",
                "js/datapipelines-dashboard-kpi.js",
            )

        /**
         * The six sheets, paths under `static/`: the design tokens, the light theme (the owner, 2026-11-21: a host that
         * wants another theme loads that theme's sheet after this file), the base layers, then the application and
         * dashboard styles that read them.
         */
        val STYLESHEETS: List<String> =
            listOf(
                "vendor/design-system/tokens.css",
                "vendor/design-system/themes/light.css",
                "vendor/design-system/base.css",
                "vendor/design-system/primitives.css",
                "css/app.css",
                "css/dashboards.css",
            )

        /** The script's text: [engineNames] read by [engineSource], then [RUNTIME_FILES] read by [staticSource]. */
        fun script(
            engineNames: List<String>,
            engineSource: (String) -> String,
            staticSource: (String) -> String,
        ): String = join(engineNames, engineSource) + join(RUNTIME_FILES, staticSource)

        /** The stylesheet's text: [STYLESHEETS] read by [staticSource]. */
        fun stylesheet(staticSource: (String) -> String): String = join(STYLESHEETS, staticSource)

        /** Every name's text in order, each followed by one newline (`JsRenderedEngineFiles.concatenation`'s rule). */
        fun join(
            names: List<String>,
            read: (String) -> String,
        ): String = names.joinToString(separator = "") { read(it) + "\n" }

        /** One file under the classpath's `static/` as UTF-8 text; a missing file fails, naming its path. */
        fun staticText(path: String): String {
            val stream =
                checkNotNull(ClientLibrary::class.java.classLoader.getResourceAsStream("static/$path")) {
                    "client library source missing from the classpath: static/$path"
                }
            return stream.use { it.readBytes().toString(Charsets.UTF_8) }
        }
    }
}

/**
 * One joined text as a resource the framework's handler can serve: its file name gives the content type, its bytes
 * the length and the body. Its URL is the library's own (scheme [SCHEME], the request path), which the content
 * validator keys its kept hash by ([ContentEtag]: a resource that is not a file on disk is hashed once and kept), and
 * which opens on the same bytes.
 */
class JoinedFile private constructor(
    private val path: String,
    private val bytes: ByteArray,
) : ByteArrayResource(bytes, "client library $path") {
    override fun getFilename(): String = path.substringAfterLast('/')

    override fun getURL(): URL = URL.of(URI(SCHEME, null, path, null), Bytes(bytes))

    /** A URL handler that opens on the joined bytes and connects to nothing. */
    private class Bytes(
        private val bytes: ByteArray,
    ) : URLStreamHandler() {
        override fun openConnection(u: URL): URLConnection =
            object : URLConnection(u) {
                override fun connect() = Unit

                override fun getInputStream() = ByteArrayInputStream(bytes)

                override fun getContentLengthLong(): Long = bytes.size.toLong()
            }
    }

    companion object {
        /** The scheme of the library's resource URLs; never a network scheme. */
        const val SCHEME: String = "datapipelines-client-library"

        /** The resource of [path] answering [bytes]. */
        fun of(
            path: String,
            bytes: ByteArray,
        ): JoinedFile = JoinedFile(path, bytes)
    }
}
