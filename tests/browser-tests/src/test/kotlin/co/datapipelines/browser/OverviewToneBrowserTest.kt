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
 * #638: the v2 Pipelines home carries the four tones as the strip's dots (In development,
 * Live) and the foot's (Live with changes, Retired); the activity columns paint created and
 * released, the release-history bars No releases and the released bins. Every site of a tone must
 * read one colour (strip dot = legend dot = column fill = bar fill), and the columns' and bars'
 * two tones are judged against the bar track, the panel and the text as well.
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
 * | minimal | in-development/text | 0 | #631 |
 * | minimal | live/text | 0 | #631 |
 *
 * Professional's info and link roles are both indigo. Minimal intentionally paints warning,
 * success and info black; its discarded, track and panel pairs pass and are never excepted.
 * The last two rows came with #638's text pairs and were recorded on the owner's ruling
 * (2026-10-20): the same cause as #631's other rows, so the same issue.
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
        SITES.forEach { (part, sites) ->
            sites.forEach { site ->
                if (colours.getValue(part) != colours.getValue("$site-$part")) {
                    failures += "$theme $part dot=${colours.getValue(part)} != $site=${colours.getValue("$site-$part")}"
                }
            }
        }
        val pairs =
            listOf("in-development" to "live", "in-development" to "retired", "live" to "retired") +
                listOf("in-development", "live", "retired", "link", "text", "track").map { "live-changes" to it } +
                listOf("in-development", "live").flatMap { tone -> listOf("track", "panel", "text").map { tone to it } } +
                listOf("success" to "warning", "success" to "danger", "warning" to "danger", "warning" to "live") +
                listOf("success", "warning", "danger").flatMap { tone -> listOf("track", "panel", "text").map { tone to it } } +
                listOf("success", "danger").flatMap { tone -> listOf("live-changes", "retired").map { tone to it } }
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

    /**
     * TemplateWorkspaceBrowserTest's REST sequence: release v1, then open a distinct v2 draft. #599: the
     * Templates home is a lifecycle home, so the mixed tone it paints is the foot's Live with changes dot.
     */
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
        page.waitForSelector("[data-count='live-changes'] .app-overview-dot[data-tone='mixed']")
        page.emulateMedia(Page.EmulateMediaOptions().setColorScheme(ColorScheme.LIGHT))
        page.evaluate(SWAP_THEME, "light") shouldBe true
        page.locator("[data-count='live-changes'] .app-overview-count-v").innerText().trim() shouldBe "1"
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
        page.waitForSelector("[data-overview-columns]")
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

        /** The tones painted beyond the strip's dot: the legend dot, the column fill and the bar fill. */
        val SITES =
            mapOf(
                "in-development" to listOf("legend", "fill", "bar"),
                "live" to listOf("legend", "fill", "bar"),
                "success" to listOf("bar"),
                "warning" to listOf("bar"),
                "danger" to listOf("bar"),
            )
        val GAPS =
            listOf(
                Gap(setOf("professional", "auto"), "live-changes/link", 0.073491, 633),
                Gap(setOf("minimal"), "in-development/live", 0.0, 631),
                Gap(setOf("minimal"), "live-changes/in-development", 0.0, 631),
                Gap(setOf("minimal"), "live-changes/live", 0.0, 631),
                Gap(setOf("minimal"), "live-changes/link", 0.0, 631),
                Gap(setOf("minimal"), "live-changes/text", 0.0, 631),
                Gap(setOf("minimal"), "in-development/text", 0.0, 631),
                Gap(setOf("minimal"), "live/text", 0.0, 631),
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
              const dot = (key) => document.querySelector('[data-count="' + key + '"] .app-overview-dot');
              document.querySelectorAll('[data-semantic-probe]').forEach(e => e.remove());
              const semanticDots = {};
              const semanticBars = {};
              for (const tone of ['success', 'warning', 'danger']) {
                const probe = document.createElement('span');
                probe.className = 'app-overview-dot';
                probe.dataset.tone = tone;
                probe.dataset.semanticProbe = tone;
                document.querySelector('[data-overview-summary] dl').append(probe);
                semanticDots[tone] = probe;
                const bar = document.querySelector('.app-overview-bar:not(.is-zero)').cloneNode(true);
                bar.dataset.tone = tone;
                bar.dataset.semanticProbe = tone;
                document.querySelector('.app-overview-chart').append(bar);
                semanticBars[tone] = bar.querySelector('.app-overview-bar-fill');
              }
              const sites = {
                'success': [semanticDots.success, 'backgroundColor'],
                'warning': [semanticDots.warning, 'backgroundColor'],
                'danger': [semanticDots.danger, 'backgroundColor'],
                'bar-success': [semanticBars.success, 'fill'],
                'bar-warning': [semanticBars.warning, 'fill'],
                'bar-danger': [semanticBars.danger, 'fill'],
                'in-development': [dot('in-development'), 'backgroundColor'],
                'live': [dot('live'), 'backgroundColor'],
                'live-changes': [dot('live-changes'), 'backgroundColor'],
                'retired': [dot('retired'), 'backgroundColor'],
                'legend-in-development': [document.querySelector('[data-legend="created"] .app-overview-dot'), 'backgroundColor'],
                'legend-live': [document.querySelector('[data-legend="released"] .app-overview-dot'), 'backgroundColor'],
                'fill-in-development': [document.querySelector('[data-overview-columns] rect[data-part="created"]'), 'fill'],
                'fill-live': [document.querySelector('[data-overview-columns] rect[data-part="released"]'), 'fill'],
                'bar-in-development': [document.querySelector('.app-overview-bar[data-tone="draft"] .app-overview-bar-fill'), 'fill'],
                'bar-live': [document.querySelector('.app-overview-bar[data-tone="released"] .app-overview-bar-fill'), 'fill'],
                'link': [document.querySelector('[data-count="live"] a'), 'color'],
                'text': [document.body, 'color'],
                'track': [document.querySelector('.app-overview-bar:not(.is-zero) .app-overview-bar-track'), 'backgroundColor'],
                'panel': [document.querySelector('.app-overview-panel'), 'backgroundColor'],
              };
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
