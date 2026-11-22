package co.datapipelines.browser

import co.datapipelines.jsrendered.JsRenderedEngineFiles
import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.Page
import com.microsoft.playwright.Route
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The page a chart class serves itself with no application (brief 1052 D11, moved here by brief 1054 D11 so that
 * [ChartMarkLookupBrowserTest] and [ChartLegendFormsBrowserTest] share one copy). By request interception on its own
 * [origin] it serves a closed set of files: from the assembled reference directory (`dp.reference.dir`) the engine's
 * files in the order of [JsRenderedEngineFiles.names], the interaction layer and `css/app.css` with the font files it
 * names; from the test classpath each of [testTypes] (`test/types/<name>.js`) and the emitted inputs [emitted] maps a
 * path under `emitted/` to; from the resource directory [resources] its `index.html` (the reference page's policy as a
 * `meta` element, and one marker line `<!-- <resources>: scripts -->` where the script tags go) and its one [script].
 * Any other request is aborted and recorded. [open] listens from the first request on for console errors, page errors,
 * dialogs and policy violations.
 */
internal class ChartTestPage(
    private val origin: String,
    private val resources: String,
    private val script: String,
    private val testTypes: List<String>,
    private val emitted: (String) -> String?,
) {
    /** One page of its own, with what its listeners record. */
    data class Opened(
        val context: BrowserContext,
        val page: Page,
        val errors: MutableList<String>,
        val violations: MutableList<String>,
        val off: MutableList<String>,
    )

    /** A page of its own at [origin]; [overrides] replaces a served file's bytes by its path (a plant, or a changed field). */
    fun open(
        browser: Browser,
        viewW: Int,
        viewH: Int,
        overrides: Map<String, ByteArray> = emptyMap(),
    ): Opened {
        val errors = CopyOnWriteArrayList<String>()
        val violations = CopyOnWriteArrayList<String>()
        val off = CopyOnWriteArrayList<String>()
        val context = browser.newContext(Browser.NewContextOptions().setViewportSize(viewW, viewH))
        context.route("**/*") { route -> serve(route, off, overrides) }
        val page = context.newPage()
        page.onConsoleMessage { m ->
            if (m.text().contains("Content Security Policy")) violations += m.text()
            if (m.type() == "error") errors += "console: ${m.text()}"
        }
        page.onPageError { e -> errors += "page error: $e" }
        page.onDialog { d ->
            errors += "dialog: ${d.message()}"
            d.dismiss()
        }
        page.navigate("$origin/index.html")
        return Opened(context, page, errors, violations, off)
    }

    private fun serve(
        route: Route,
        off: MutableList<String>,
        overrides: Map<String, ByteArray>,
    ) {
        val url = route.request().url()
        val rel = if (url.startsWith("$origin/")) url.removePrefix("$origin/").substringBefore('?').substringBefore('#') else null
        val bytes = rel?.let { overrides[it] ?: served(it) }
        if (rel == null || bytes == null) {
            off += "aborted: $url"
            route.abort()
            return
        }
        route.fulfill(
            Route
                .FulfillOptions()
                .setStatus(OK)
                .setBodyBytes(bytes)
                .setContentType(typeOf(rel)),
        )
    }

    /** The closed set of files the page may ask for; null for any other path. */
    private fun served(rel: String): ByteArray? =
        when {
            rel == "index.html" -> {
                page().toByteArray()
            }

            rel == script -> {
                resourceText("$resources/$script").toByteArray()
            }

            rel in typePaths -> {
                resourceBytes("co/datapipelines/jsrendered/$rel")
            }

            rel.startsWith("emitted/") -> {
                emitted(rel.removePrefix("emitted/"))?.let { resourceBytes(it) }
            }

            rel.startsWith("engine/") && rel.removePrefix("engine/") in JsRenderedEngineFiles.names -> {
                fromDir(rel)
            }

            rel == LAYER || rel == "css/app.css" || rel in fonts -> {
                fromDir(rel)
            }

            else -> {
                null
            }
        }

    private val typePaths: List<String> get() = testTypes.map { "test/types/$it.js" }

    /** The page with its one marker line replaced by the script tags: engine (load order), test types, layer, script. */
    private fun page(): String {
        val text = resourceText("$resources/index.html")
        val marker = "<!-- $resources: scripts -->"
        require(text.split(marker).size - 1 == 1) { "$resources/index.html carries the marker other than once" }
        val tags =
            (JsRenderedEngineFiles.names.map { "engine/$it" } + typePaths + LAYER + script)
                .joinToString("\n") { "  <script src=\"$it\"></script>" }
        return text.replace(marker, tags)
    }

    private fun fromDir(rel: String): ByteArray? {
        val file = dir.resolve(rel).normalize()
        return if (file.startsWith(dir) && Files.isRegularFile(file)) Files.readAllBytes(file) else null
    }

    companion object {
        const val LAYER = "js/datapipelines-chart-interactions.js"
        private const val OK = 200

        /** The assembled reference directory the engine, the layer and the stylesheet are served from. */
        val dir: Path by lazy {
            val p = System.getProperty("dp.reference.dir") ?: error("dp.reference.dir is not set: the test serves the assembled directory")
            Paths
                .get(p)
                .toAbsolutePath()
                .normalize()
                .also { require(Files.isRegularFile(it.resolve(LAYER))) { "no layer in $it" } }
        }

        /** The font files `css/app.css` names by url(), relative to the directory. */
        private val fonts: Set<String> by lazy {
            val css = Files.readString(dir.resolve("css/app.css"))
            Regex("""url\(\s*(['"]?)([^'")]+)\1\s*\)""")
                .findAll(css)
                .map { it.groupValues[2].trim() }
                .filter { !it.startsWith("data:") && !it.startsWith("#") }
                .map { dir.resolve("css").resolve(it.substringBefore('?').substringBefore('#')).normalize() }
                .filter { it.startsWith(dir) }
                .map { dir.relativize(it).toString().replace('\\', '/') }
                .toSet()
        }

        fun resourceBytes(path: String): ByteArray? =
            ChartTestPage::class.java.classLoader
                .getResourceAsStream(path)
                ?.use { it.readBytes() }

        fun resourceText(path: String): String =
            checkNotNull(resourceBytes(path)) { "$path is not on the test classpath" }.toString(Charsets.UTF_8)

        private fun typeOf(rel: String): String =
            when (rel.substringAfterLast('.')) {
                "html" -> "text/html; charset=utf-8"
                "js" -> "text/javascript; charset=utf-8"
                "css" -> "text/css; charset=utf-8"
                "json" -> "application/json"
                "woff2" -> "font/woff2"
                else -> "application/octet-stream"
            }
    }
}
