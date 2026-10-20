package co.datapipelines.browser

import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.options.ColorScheme
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.nio.file.Paths

/**
 * #618 — success and warning TEXT reach the 4.5:1 text floor on every surface they paint on, on
 * every vendored theme, through one app token per role, `--app-success-ink` and
 * `--app-warning-ink` (ui-screens §2.8, §3.5) — `DangerInkContrastBrowserTest`'s sweep for the
 * other two status roles.
 *
 * The defect: `--accent-success` / `--accent-warning` are FILL colours, and as text they miss the
 * floor on light inset and hover surfaces (professional and auto's light half, forest's
 * warning); the vendored `--text-success` / `--text-warning` roles miss it too on light's and
 * healthcare's warning. Each ink is its theme's text role at 3% lower oklch lightness in light
 * and the role as vendored in dark.
 *
 * Measured, never derived from the stylesheet text: for each theme the running app offers (plus
 * `auto` under an emulated dark preference — its dark half exists only there), probes are
 * appended to the signed-in page and their computed colour is read against the background the
 * browser actually paints behind them, composited through every translucent ancestor:
 *
 *  - `.u-success` / `.u-warning` spans and the ink itself on each surface a status-text site
 *    paints on (page, default, raised, inset, hover);
 *  - `.app-chip-ok` / `.app-chip-warn` and `.app-alert-warning` on their own tints, and each
 *    ink on its role's tint (the editor's and template workspace's version chips, the login
 *    status line and the connection-lost banner paint there);
 *  - a ghost `.u-success` / `.u-warning` button HOVERED, where its rule keeps the role's colour
 *    on `--surface-hover`.
 *
 * And every app.css status-text class this page can construct paints EXACTLY its role's ink, so
 * a site that silently kept the accent is a failure even on a theme where the accent passes.
 * Sites in sheets this page does not load are held at the source by `StatusInkSourceAuditTest`.
 * The full table lands in `build/reports/618-status-ink/matrix.txt` and on stdout, one
 * `618-measure|theme|scheme|role|surface|ratio` line per pair.
 */
class StatusInkContrastBrowserTest : BrowserSuite() {
    private fun ready() {
        val user =
            seedLocalUser(
                uniqueEmail("ink618-" + generatedPassword("u").take(8)),
                generatedPassword("pw"),
                mustChange = false,
            )
        login(user.email, user.oneTimePassword)
        page.waitForURL("**/dashboard")
        createWorkspace("ink618ws-" + generatedPassword("w").take(8).lowercase())
        page.navigate("$baseUrl/dashboard")
        page.waitForSelector("#app-main")
    }

    private fun reportDir(): Path = Paths.get("build", "reports", "618-status-ink").also { it.toFile().mkdirs() }

    /** DangerInkContrastBrowserTest's sweep: the modes and palettes the top bar offers, plus `auto` under dark. */
    private fun sweep(): List<Pair<String, ColorScheme>> {
        @Suppress("UNCHECKED_CAST")
        val offered =
            (
                page.evaluate(
                    "() => [...document.querySelectorAll('#app-appearance [data-mode]')].map((e) => e.dataset.mode)" +
                        ".concat([...document.querySelectorAll('.app-swatch[data-swatch]')].map((e) => e.dataset.swatch))",
                ) as List<*>
            ).map { it as String }
        offered shouldContainAll listOf("light", "dark", "auto")
        return offered.map { it to ColorScheme.LIGHT } + ("auto" to ColorScheme.DARK)
    }

    /** One theme: swap it in, plant the probes, measure at rest, hover each ghost button and measure it, clear the probes. */
    @Suppress("UNCHECKED_CAST")
    private fun measure(
        theme: String,
        scheme: ColorScheme,
    ): Pair<String, Map<String, Any>> {
        page.emulateMedia(Page.EmulateMediaOptions().setColorScheme(scheme))
        page.evaluate(SWAP_THEME, theme) shouldBe true
        page.mouse().move(0.0, 0.0)
        val rest = page.evaluate(MEASURE) as Map<String, Any>
        val hovered =
            ROLES.map { role ->
                page.locator("[data-ink618-hover='$role']").hover()
                page.evaluate(MEASURE_HOVER, role) as Map<String, Any>
            }
        page.mouse().move(0.0, 0.0)
        page.locator("#ink618-host").screenshot(
            Locator.ScreenshotOptions().setPath(reportDir().resolve("$theme-${scheme.name.lowercase()}.png")),
        )
        page.evaluate("() => document.getElementById('ink618-host').remove()")
        return "$theme|${scheme.name.lowercase()}" to (rest + mapOf("hovered" to hovered))
    }

    /** One theme's report lines and its failures: the text floor on every pair, and every probe painting its ink. */
    @Suppress("UNCHECKED_CAST")
    private fun judge(
        label: String,
        m: Map<String, Any>,
        lines: MutableList<String>,
        failures: MutableList<String>,
    ) {
        val pairs = (m["pairs"] as List<Map<String, Any>>) + (m["hovered"] as List<Map<String, Any>>)
        pairs.forEach { p ->
            val ratio = (p["ratio"] as Number).toDouble()
            val row =
                "618-measure|$label|${p["role"]}|${p["surface"]}|${"%.2f".format(ratio)}|text=${p["text"]} on ${p["background"]}"
            lines += row
            if (ratio < TEXT_FLOOR) failures += row
        }
        val inks = m["inks"] as Map<String, String>
        lines += "618-ink|$label|success=${inks["success"]}|warning=${inks["warning"]}"
        pairs.forEach { p ->
            val ink = inks.getValue(p["role"] as String)
            if (p["text"] != ink) failures += "$label: ${p["surface"]} paints ${p["text"]}, not --app-${p["role"]}-ink $ink"
        }
        (m["consumers"] as List<Map<String, String>>).forEach { c ->
            val ink = inks.getValue(c.getValue("role"))
            lines += "618-consumer|$label|${c["site"]}|${c["text"]}"
            if (c["text"] != ink) failures += "$label: ${c["site"]} paints ${c["text"]}, not --app-${c["role"]}-ink $ink"
        }
    }

    @Test
    fun `success and warning text keep the 4_5 to 1 floor on every surface of every vendored theme`() {
        startTrace()
        ready()
        page.setViewportSize(1440, 900)
        page.emulateMedia(Page.EmulateMediaOptions().setColorScheme(ColorScheme.LIGHT))

        val measured = sweep().map { (theme, scheme) -> measure(theme, scheme) }

        val failures = mutableListOf<String>()
        val lines = mutableListOf("#618 status inks — theme|scheme|role|surface|ratio (floor $TEXT_FLOOR:1)")
        measured.forEach { (label, m) -> judge(label, m, lines, failures) }
        val report = lines.joinToString("\n")
        reportDir().resolve("matrix.txt").toFile().writeText(report + "\n")
        println(report)

        // Non-vacuity: nine vendored themes plus auto's dark half, each with every probe.
        measured.size shouldBeGreaterThanOrEqual MIN_SWEEP
        withClue(report) { failures shouldBe emptyList() }
    }

    private companion object {
        const val TEXT_FLOOR = 4.5
        const val MIN_SWEEP = 10
        val ROLES = listOf("success", "warning")

        /** ShellFeelBrowserTest's theme swap, as DangerInkContrastBrowserTest copies it: replace the link, await its `load`. */
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

        /**
         * The canvas and compositing helpers of DangerInkContrastBrowserTest's MEASURE, shared by
         * both scripts: `rgba` resolves any computed colour syntax to painted sRGB bytes, `paint`
         * composites an element's background chain bottom-up, `settle` waits (bounded) for an
         * element's running CSS transitions. Probes stay attached until every read is done.
         */
        val HELPERS =
            """
            const settle = async el => {
              const running = () => el.getAnimations().filter(a => a instanceof CSSTransition && a.playState === 'running');
              const deadline = performance.now() + 3000;
              while (running().length && performance.now() < deadline) await new Promise(r => setTimeout(r, 25));
              if (running().length) throw new Error('transition still running on ' + el.className);
            };
            const canvas = document.createElement('canvas');
            canvas.width = canvas.height = 1;
            const ctx = canvas.getContext('2d', { willReadFrequently: true });
            const rgba = c => { ctx.clearRect(0, 0, 1, 1); ctx.fillStyle = '#000'; ctx.fillStyle = c;
              ctx.fillRect(0, 0, 1, 1); const d = ctx.getImageData(0, 0, 1, 1).data; return [d[0], d[1], d[2], d[3] / 255]; };
            const css = a => 'rgb(' + a[0] + ', ' + a[1] + ', ' + a[2] + ')';
            const paint = el => {
              const layers = [];
              for (let node = el; node; node = node.parentElement) {
                const bg = getComputedStyle(node).backgroundColor;
                const c = rgba(bg);
                if (c[3] > 0) layers.push(bg);
                if (c[3] === 1) break;
              }
              if (!layers.length || rgba(layers[layers.length - 1])[3] !== 1) throw new Error('no opaque ancestor behind ' + el.className);
              ctx.clearRect(0, 0, 1, 1);
              for (let i = layers.length - 1; i >= 0; i--) { ctx.fillStyle = layers[i]; ctx.fillRect(0, 0, 1, 1); }
              const d = ctx.getImageData(0, 0, 1, 1).data;
              return [d[0], d[1], d[2], 1];
            };
            const lum = c => { const f = v => { const s = v / 255;
                return s <= 0.04045 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4); };
              return 0.2126 * f(c[0]) + 0.7152 * f(c[1]) + 0.0722 * f(c[2]); };
            const ratio = (a, b) => { const l1 = lum(a), l2 = lum(b);
              return (Math.max(l1, l2) + 0.05) / (Math.min(l1, l2) + 0.05); };
            const pair = (role, surface, el) => { const t = rgba(getComputedStyle(el).color), b = paint(el);
              if (t[3] !== 1) throw new Error(surface + ' text is translucent');
              return { role, surface, ratio: ratio(t, b), text: css(t), background: css(b) }; };
            """.trimIndent()

        /** Plants the probes in `#ink618-host` (left attached for the hover reads) and measures every pair at rest. */
        val MEASURE =
            """
            async () => {
              $HELPERS
              const host = document.createElement('div');
              host.id = 'ink618-host';
              document.querySelector('#app-main').appendChild(host);
              const el = (parent, tag, cls) => { const e = document.createElement(tag); if (cls) e.className = cls;
                e.textContent = 'x'; parent.appendChild(e); return e; };
              const ink = (parent, role) => { const s = el(parent, 'span'); s.style.color = 'var(--app-' + role + '-ink)'; return s; };
              const pairs = [];
              for (const surface of ['--surface-page', '--surface-default', '--surface-raised', '--surface-inset', '--surface-hover']) {
                const box = el(host, 'div');
                box.style.background = 'var(' + surface + ')';
                for (const role of ['success', 'warning']) {
                  pairs.push(pair(role, 'u-' + role + ' on ' + surface, el(box, 'span', 'u-' + role)));
                  pairs.push(pair(role, 'ink on ' + surface, ink(box, role)));
                }
              }
              for (const role of ['success', 'warning']) {
                const tint = el(host, 'div');
                tint.style.background = 'var(--accent-' + role + '-bg)';
                pairs.push(pair(role, 'ink on --accent-' + role + '-bg', ink(tint, role)));
              }
              pairs.push(pair('success', 'app-chip-ok on its tint', el(host, 'span', 'app-chip app-chip-ok')));
              pairs.push(pair('warning', 'app-chip-warn on its tint', el(host, 'span', 'app-chip app-chip-warn')));
              pairs.push(pair('warning', 'app-alert-warning on its tint', el(host, 'div', 'app-alert app-alert-warning')));
              for (const role of ['success', 'warning']) {
                const b = el(host, 'button', 'ds-button ds-button-ghost u-' + role);
                b.type = 'button';
                b.dataset.ink618Hover = role;
              }
              // app.css's other status-text rules: each must paint exactly its role's ink.
              const consumers = [];
              const read = (role, site, e) => consumers.push({ role, site, text: css(rgba(getComputedStyle(e).color)) });
              read('success', 'tf-outcome-ok', el(host, 'p', 'tf-outcome tf-outcome-ok'));
              read('success', 'tf-case-pass mark', el(el(el(host, 'div', 'tf-case-pass'), 'div', 'tf-case-head'), 'span', 'tf-mark'));
              read('success', 'tf-inv-pass mark', el(el(host, 'div', 'tf-inv-pass'), 'span', 'tf-mark'));
              read('warning', 'pe-card-review', el(host, 'span', 'pe-card-review'));
              const token = role => css(rgba(getComputedStyle(ink(host, role)).color));
              return { pairs, consumers, inks: { success: token('success'), warning: token('warning') } };
            }
            """.trimIndent()

        /** One hovered ghost button: the pair its `:hover` rule paints on `--surface-hover`. */
        val MEASURE_HOVER =
            """
            async (role) => {
              $HELPERS
              const b = document.querySelector("[data-ink618-hover='" + role + "']");
              if (!b.matches(':hover')) throw new Error('the ' + role + ' ghost button is not hovered');
              await settle(b);
              return pair(role, 'ghost u-' + role + ':hover', b);
            }
            """.trimIndent()
    }
}
