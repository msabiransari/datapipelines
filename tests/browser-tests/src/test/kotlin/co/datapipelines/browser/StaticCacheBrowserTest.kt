package co.datapipelines.browser

import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.options.LoadState
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.net.URI

/**
 * **A warm visit, measured by the browser** (#1023 D5): font files are taken from the browser's cache with no request
 * (kept a year, `immutable`), and the stylesheet and script are re-checked and not downloaded again (`no-cache` with a
 * content `ETag`, answered 304). The login page is public; it preloads three font files and links `app.css` and loads
 * `toast.js`.
 *
 * A context from [newBrowserContext] with no route anywhere in it (Playwright disables the HTTP cache in a context
 * that has one); the page is opened, closed, and opened again in the SAME context. The numbers are the page's own
 * resource-timing entries: `transferSize` 0 is a cache hit with no request; a 304 transfers its headers only, so its
 * `transferSize` is above 0 and below the body's `encodedBodySize`; a real download is at least its body.
 */
class StaticCacheBrowserTest : BrowserSuite() {
    @Test
    fun `a warm visit takes the fonts from the cache and re-checks the stylesheet and script`() {
        val context = newBrowserContext()
        val errors = mutableListOf<String>()
        try {
            val cold = visit(context, errors)
            val warm = visit(context, errors)
            print("visit-1", cold)
            print("visit-2", warm)

            val warmFonts = warm.filter { it.path.startsWith(FONTS) }
            withClue("visit 2 loads at least one font file") { warmFonts.shouldNotBeEmpty() }
            val rechecked = listOf(STYLESHEET, SCRIPT).map { path -> path to warm.filter { it.path == path } }
            assertSoftly {
                for (font in warmFonts) {
                    withClue("visit 2 ${font.path}: from the cache, no request answered") { font.transferSize shouldBe 0L }
                }
                for ((path, entries) in rechecked) {
                    withClue("visit 2 loads $path") { entries.shouldNotBeEmpty() }
                    for (entry in entries) {
                        withClue("visit 2 $path: re-checked (above 0) and not downloaded (below its body)") {
                            entry.transferSize shouldBeGreaterThan 0L
                            entry.transferSize shouldBeLessThan entry.encodedBodySize
                        }
                    }
                }
                val coldChecked = cold.filter { it.path.startsWith(FONTS) || it.path == STYLESHEET || it.path == SCRIPT }
                val warmChecked = (warmFonts + rechecked.flatMap { it.second }).map { it.path }.toSet()
                withClue("visit 1 loads the same files") { coldChecked.map { it.path }.toSet() shouldBe warmChecked }
                for (entry in coldChecked) {
                    withClue("visit 1 ${entry.path}: a real download") {
                        entry.encodedBodySize shouldBeGreaterThan 0L
                        entry.transferSize shouldBeGreaterThanOrEqual entry.encodedBodySize
                    }
                }
            }
            withClue("the console of both visits") { errors.shouldBeEmpty() }
        } finally {
            context.close()
        }
    }

    private fun visit(
        context: BrowserContext,
        errors: MutableList<String>,
    ): List<Timing> {
        val visitPage = context.newPage()
        try {
            visitPage.onConsoleMessage { if (it.type() == "error") errors.add("${visitPage.url()} — ${it.text()}") }
            visitPage.onPageError { errors.add("${visitPage.url()} — $it") }
            visitPage.navigate("$baseUrl/login")
            visitPage.waitForLoadState(LoadState.LOAD)
            visitPage.evaluate("() => document.fonts.ready.then(() => true)")

            @Suppress("UNCHECKED_CAST")
            val entries = visitPage.evaluate(RESOURCE_TIMING) as List<Map<String, Any?>>
            return entries.map {
                Timing(
                    path = URI.create(it["name"] as String).path,
                    transferSize = (it["transferSize"] as Number).toLong(),
                    encodedBodySize = (it["encodedBodySize"] as Number).toLong(),
                )
            }
        } finally {
            visitPage.close()
        }
    }

    private fun print(
        label: String,
        entries: List<Timing>,
    ) {
        entries
            .filter { it.path.startsWith(FONTS) || it.path.startsWith("/css/") || it.path.startsWith("/js/") }
            .forEach { println("1023|$label|${it.path}|transferSize=${it.transferSize}|encodedBodySize=${it.encodedBodySize}") }
    }

    private data class Timing(
        val path: String,
        val transferSize: Long,
        val encodedBodySize: Long,
    )

    private companion object {
        const val FONTS = "/vendor/fonts/"
        const val STYLESHEET = "/css/app.css"
        const val SCRIPT = "/js/toast.js"
        const val RESOURCE_TIMING =
            "() => performance.getEntriesByType('resource')" +
                ".map(e => ({ name: e.name, transferSize: e.transferSize, encodedBodySize: e.encodedBodySize }))"
    }
}
