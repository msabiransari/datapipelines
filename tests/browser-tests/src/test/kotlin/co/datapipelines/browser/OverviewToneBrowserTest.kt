package co.datapipelines.browser

import com.microsoft.playwright.Page
import com.microsoft.playwright.options.ColorScheme
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.nio.file.Paths
import java.util.Locale
import kotlin.math.cbrt
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * #627: real lifecycle fills and legend swatches remain distinct from each other, links, text
 * and the empty track across every offered theme, including both auto schemes.
 * The matrix is written before assertions so a failing candidate retains every measured pair.
 *
 * The 0.08 floor stays unchanged. Exactly these vendored palette gaps are measured and tracked;
 * a listed row that starts passing is red, so an exception cannot outlive its reason (#609's discipline).
 *
 * | Themes | Pair | Measured distance | Issue |
 * |---|---|---|---|
 * | professional, auto light | live-changes/link | 0.073491 | #633 |
 * | minimal | in-development/live | 0 | #631 |
 * | minimal | live-changes/in-development | 0 | #631 |
 * | minimal | live-changes/live | 0 | #631 |
 * | minimal | live-changes/link | 0 | #631 |
 * | minimal | live-changes/text | 0 | #631 |
 *
 * Professional's info and link roles are both indigo. Minimal intentionally paints warning,
 * success and info black; its discarded and track pairs pass and are never excepted.
 */
class OverviewToneBrowserTest : RestTreeBrowserFixture() {
    private data class Gap(
        val themes: Set<String>,
        val pair: String,
        val measured: Double,
        val issue: Int,
    )

    private fun sweep(): List<Pair<String, ColorScheme>> {
        val offered =
            (
                page.evaluate(
                    "() => [...document.querySelectorAll('#app-appearance [data-mode]')].map(e => e.dataset.mode)" +
                        ".concat([...document.querySelectorAll('.app-swatch[data-swatch]')].map(e => e.dataset.swatch))",
                ) as List<*>
            ).map { it as String }
        offered shouldContainAll listOf("light", "dark", "auto")
        return offered.map { it to ColorScheme.LIGHT } + ("auto" to ColorScheme.DARK)
    }

    @Suppress("UNCHECKED_CAST")
    private fun measure(
        theme: String,
        scheme: ColorScheme,
    ): Map<String, String> {
        page.emulateMedia(Page.EmulateMediaOptions().setColorScheme(scheme))
        page.evaluate(SWAP_THEME, theme) shouldBe true
        return page.evaluate(MEASURE) as Map<String, String>
    }

    private fun judge(
        theme: String,
        colours: Map<String, String>,
        lines: MutableList<String>,
        failures: MutableList<String>,
    ) {
        PARTS.forEach { part ->
            if (colours.getValue(part) != colours.getValue("fill-$part")) {
                failures += "$theme $part swatch=${colours.getValue(part)} != fill=${colours.getValue("fill-$part")}"
            }
        }
        val pairs =
            listOf("in-development" to "live", "in-development" to "retired", "live" to "retired") +
                listOf("in-development", "live", "retired", "link", "text", "track").map { "live-changes" to it }
        pairs.forEach { (first, second) ->
            val a = colours.getValue(first)
            val b = colours.getValue(second)
            val distance = distance(a, b)
            val row = "$theme  $first/$second  ΔE=${String.format(Locale.ROOT, "%.6f", distance)}  a=$a b=$b"
            lines += row
            val gap = GAPS.firstOrNull { theme in it.themes && it.pair == "$first/$second" }
            when {
                gap == null -> if (distance < FLOOR) failures += row
                distance < FLOOR -> lines += "    exception #${gap.issue}; recorded ΔE=${gap.measured}"
                else -> failures += "$row: stale GAPS entry #${gap.issue} now passes — remove it"
            }
        }
    }

    /** TemplateWorkspaceBrowserTest's REST sequence: release v1, then open a distinct v2 draft. */
    private fun siblingScreenshot() {
        val name = "tone627/card.sql"

        fun body(marker: String) =
            """{"id":"$name","type":"sql","dialect":"POSTGRES","display_name":"Tone card",""" +
                """"description":"627 mixed tone fixture","body":"SELECT '$marker' AS body"}"""
        val created = api("POST", "/api/v1/templates", body("v1"))
        api("POST", "/api/v1/templates/release", """{"name":"$name"}""", field(created, "body_hash"))
        val released = api("GET", "/api/v1/templates?name=$name", null)
        api("PUT", "/api/v1/templates", body("v2"), field(released, "body_hash"))
        page.navigate("$baseUrl/overview/templates")
        page.waitForSelector(".app-overview-bar[data-tone='mixed'] .app-overview-bar-fill")
        page.emulateMedia(Page.EmulateMediaOptions().setColorScheme(ColorScheme.LIGHT))
        page.evaluate(SWAP_THEME, "light") shouldBe true
        page.locator(".app-overview-bar[data-tone='mixed'] .app-overview-bar-fill").getAttribute("width") shouldBe "100.0"
        page.evaluate("() => document.fonts.ready")
        page.screenshot(Page.ScreenshotOptions().setPath(shotDir().resolve("overview-templates-light-1512.png")))
    }

    private fun shotDir() = Paths.get("build", "reports", "627-overview-tone").also { it.toFile().mkdirs() }

    @Test
    fun `overview tones are distinct and legends match fills across all themes`() {
        // Independent sanity checks: black/white spans unit lightness and identical colours coincide.
        distance("#000000", "#ffffff") shouldBe (1.0 plusOrMinus 0.000001)
        distance("#7dd3fc", "#7dd3fc") shouldBe 0.0
        startTrace()
        readyTree()
        seedPipeline("tone627/one")
        seedPipeline("tone627/two")
        page.setViewportSize(1512, 900)
        page.navigate("$baseUrl/overview/pipelines")
        page.waitForSelector("[data-stacked='lifecycle']")
        val themes = sweep()
        val lines = mutableListOf<String>()
        val failures = mutableListOf<String>()
        val dir = shotDir()
        themes.forEach { (theme, scheme) ->
            val label = if (scheme == ColorScheme.DARK) "$theme(dark)" else theme
            judge(label, measure(theme, scheme), lines, failures)
            if (theme in setOf("light", "dark")) {
                page.evaluate("() => document.fonts.ready")
                page.screenshot(Page.ScreenshotOptions().setPath(dir.resolve("overview-$theme-1512.png")))
            }
        }
        val report = lines.joinToString("\n") + "\n"
        dir.resolve("matrix.txt").toFile().writeText(report)
        println(report)
        withClue("MIN_SWEEP: all offered themes plus auto dark must be measured") {
            themes.size shouldBeGreaterThanOrEqual MIN_SWEEP
        }
        withClue("OKLab floor $FLOOR; swatch/fill equality; failures:\n${failures.joinToString("\n")}") {
            failures shouldBe emptyList()
        }
        siblingScreenshot()
    }

    private companion object {
        const val FLOOR = 0.08
        const val MIN_SWEEP = 10
        val PARTS = listOf("in-development", "live", "live-changes", "retired")
        val GAPS =
            listOf(
                Gap(setOf("professional", "auto"), "live-changes/link", 0.073491, 633),
                Gap(setOf("minimal"), "in-development/live", 0.0, 631),
                Gap(setOf("minimal"), "live-changes/in-development", 0.0, 631),
                Gap(setOf("minimal"), "live-changes/live", 0.0, 631),
                Gap(setOf("minimal"), "live-changes/link", 0.0, 631),
                Gap(setOf("minimal"), "live-changes/text", 0.0, 631),
            )

        fun distance(
            a: String,
            b: String,
        ): Double = sqrt(oklab(a).zip(oklab(b)).sumOf { (x, y) -> (x - y).pow(2) })

        /** Ottosson's public-domain linear-sRGB matrices: https://bottosson.github.io/posts/oklab/. */
        fun oklab(hex: String): List<Double> {
            val (r, g, b) =
                hex.removePrefix("#").chunked(2).map {
                    val s = it.toInt(16) / 255.0
                    if (s <= 0.04045) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
                }
            val l = cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
            val m = cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
            val s = cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
            return listOf(
                0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
                1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
                0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s,
            )
        }

        // DangerInkContrastBrowserTest's link replacement waits for the NEW stylesheet to load.
        val SWAP_THEME =
            """
            (t) => new Promise((resolve, reject) => {
              const old = document.getElementById('theme-link');
              const link = document.createElement('link');
              link.rel = 'stylesheet';
              link.onload = () => { old.remove(); link.id = 'theme-link'; resolve(true); };
              link.onerror = () => reject(new Error('theme ' + t + ' did not load'));
              link.href = '/vendor/design-system/themes/' + t + '.css';
              old.insertAdjacentElement('afterend', link);
            })
            """.trimIndent()

        val MEASURE =
            """
            async () => {
              const parts = ['in-development', 'live', 'live-changes', 'retired'];
              const sites = Object.fromEntries(parts.flatMap(p => [
                [p, [document.querySelector('[data-legend="' + p + '"] .app-overview-legend-swatch'), 'backgroundColor']],
                ['fill-' + p, [document.querySelector('[data-stacked="lifecycle"] rect[data-part="' + p + '"]'), 'fill']]
              ]));
              sites.link = [document.querySelector('[data-legend="live"] a'), 'color'];
              sites.text = [document.body, 'color'];
              sites.track = [document.querySelector('[data-stacked="lifecycle"] .app-overview-stack-track'), 'backgroundColor'];
              const canvas = document.createElement('canvas');
              canvas.width = canvas.height = 1;
              const ctx = canvas.getContext('2d', {willReadFrequently: true});
              const result = {};
              for (const [name, [el, property]] of Object.entries(sites)) {
                if (!el) throw new Error('missing ' + name);
                getComputedStyle(el)[property];
                const running = () => el.getAnimations().filter(a => a instanceof CSSTransition && a.playState === 'running');
                const deadline = performance.now() + 3000;
                while (running().length && performance.now() < deadline) await new Promise(r => setTimeout(r, 25));
                if (running().length) throw new Error('transition still running: ' + name);
                const colour = getComputedStyle(el)[property];
                if (!colour || !CSS.supports('color', colour)) throw new Error('invalid colour: ' + name);
                ctx.clearRect(0, 0, 1, 1);
                ctx.fillStyle = colour;
                ctx.fillRect(0, 0, 1, 1);
                const bytes = [...ctx.getImageData(0, 0, 1, 1).data];
                if (bytes[3] !== 255) throw new Error('translucent ' + name + ': ' + colour);
                result[name] = '#' + bytes.slice(0, 3).map(v => v.toString(16).padStart(2, '0')).join('');
              }
              return result;
            }
            """.trimIndent()
    }
}
