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
import kotlin.math.abs

/**
 * #625 — the template workspace's version selector carries the menu's selection expression
 * (ui-screens §3.1 menu bullet, §2.8): the viewed row is the `--brand-soft` tint with
 * `--text-primary` ink (Minimal: `--text-inverted`) plus a `--border-focus` left bar exactly
 * `--app-nav-indicator-w` wide, and every row reserves that bar so a version switch moves nothing.
 *
 * Measured on the running page, per theme the top bar offers plus `auto` under an emulated dark
 * preference (DangerInkContrastBrowserTest's sweep; MenuScaleBrowserTest's probe): the viewed
 * link and one unselected sibling — bar width and colour, ink, painted background, WCAG contrast
 * at rest and hovered — and the viewed link's own box with `aria-current` lifted, so the
 * "nothing shifts" comparison is between the same label in its two states (the rows' labels
 * differ: the current release carries `· current`). One `625-selection|…` line per theme;
 * the table lands in `build/reports/625-version-selector/table.txt` with three 1512 shots.
 */
class TemplateVersionSelectorBrowserTest : BrowserSuite() {
    private fun ready() {
        val user =
            seedLocalUser(
                uniqueEmail("vsel625-" + generatedPassword("u").take(8)),
                generatedPassword("pw"),
                mustChange = false,
            )
        login(user.email, user.oneTimePassword)
        page.waitForURL("**/dashboard")
        createWorkspace("vsel625ws-" + generatedPassword("w").take(8).lowercase())
    }

    /** TemplateWorkspaceBrowserTest's in-page REST call, with the session's CSRF pair. */
    private fun must(
        method: String,
        path: String,
        body: String?,
        ifMatch: String? = null,
    ): String {
        val answer = page.evaluate(API, arrayOf(method, path, body, ifMatch)) as List<*>
        val status = (answer[0] as Number).toInt()
        val text = answer[1] as String
        check(status == 200 || status == 201) { "$method $path -> $status: ${text.take(300)}" }
        return text
    }

    private fun hashOf(text: String): String = Regex(""""body_hash"\s*:\s*"([^"]+)"""").find(text)!!.groupValues[1]

    private fun templateJson(
        name: String,
        marker: String,
    ): String =
        """{"id":"$name","type":"sql","dialect":"POSTGRES","display_name":"${name.substringAfterLast('/')}",""" +
            """"description":"625 selector fixture","body":"SELECT '$marker' AS body"}"""

    /** TemplateWorkspaceBrowserTest's seed: released v1, released v2 (current), DRAFT v3. */
    private fun seed(name: String) {
        val created = must("POST", "/api/v1/templates", templateJson(name, "v1"))
        must("POST", "/api/v1/templates/release", """{"name":"$name"}""", ifMatch = hashOf(created))
        val v1 = must("GET", "/api/v1/templates?name=$name", null)
        val v2Draft = must("PUT", "/api/v1/templates", templateJson(name, "v2"), ifMatch = hashOf(v1))
        must("POST", "/api/v1/templates/release", """{"name":"$name"}""", ifMatch = hashOf(v2Draft))
        val v2 = must("GET", "/api/v1/templates?name=$name", null)
        must("PUT", "/api/v1/templates", templateJson(name, "v3"), ifMatch = hashOf(v2))
    }

    /** The modes and palettes the running top bar offers, plus `auto`'s dark half. */
    private fun sweep(): List<Pair<String, ColorScheme>> {
        @Suppress("UNCHECKED_CAST")
        val offered =
            (
                page.evaluate(
                    "() => [...document.querySelectorAll('#app-appearance [data-mode]')].map((e) => e.dataset.mode)" +
                        ".concat([...document.querySelectorAll('.app-swatch[data-swatch]')].map((e) => e.dataset.swatch))",
                ) as List<*>
            ).map { it as String }
        offered shouldContainAll listOf("light", "dark", "auto", "minimal")
        return offered.map { it to ColorScheme.LIGHT } + ("auto" to ColorScheme.DARK)
    }

    private fun shotDir(): Path = Paths.get("build", "reports", "625-version-selector").also { it.toFile().mkdirs() }

    @Suppress("UNCHECKED_CAST")
    private fun measure(
        theme: String,
        scheme: ColorScheme,
        selected: Locator,
    ): Pair<String, Map<String, Any>> {
        val label = if (scheme == ColorScheme.DARK) "$theme(dark)" else theme
        page.emulateMedia(Page.EmulateMediaOptions().setColorScheme(scheme))
        page.evaluate(SWAP_THEME, theme) shouldBe true
        page.mouse().move(0.0, 0.0)
        val rest = page.evaluate(MEASURE) as Map<String, Any>
        selected.hover()
        val hovered = page.evaluate(HOVER_RATIO) as Number
        page.mouse().move(0.0, 0.0)
        if (scheme == ColorScheme.LIGHT && theme in SHOT_THEMES) {
            page.locator(".tw-topbar").screenshot(Locator.ScreenshotOptions().setPath(shotDir().resolve("$theme-1512.png")))
        }
        return label to (rest + mapOf("hoverRatio" to hovered.toDouble()))
    }

    /** Assertions (1) and (2): the bar is the indicator token in `--border-focus`; every selected text reaches the floor. */
    private fun judgeInk(
        m: Map<String, Any>,
        fail: (String) -> Unit,
    ) {
        val sel = m.sub("selected")
        val tokens = m.sub("tokens")
        val indicator = tokens.num("indicator")
        if (indicator != BAR) fail("--app-nav-indicator-w resolves to ${indicator}px, not ${BAR}px")
        if (sel.num("barWidth") != indicator) fail("(1) selected bar ${sel.num("barWidth")}px, not the indicator's ${indicator}px")
        if (sel["barColour"] != tokens["focus"]) fail("(1) selected bar ${sel["barColour"]}, not --border-focus ${tokens["focus"]}")
        if (m.num("ratio") < TEXT_CONTRAST) fail("(2) selected text %.2f:1 < $TEXT_CONTRAST".format(m.num("ratio")))
        if (m.num("hoverRatio") < TEXT_CONTRAST) fail("(2) hovered selected text %.2f:1 < $TEXT_CONTRAST".format(m.num("hoverRatio")))
        val suffixRatio = m.num("suffixRatio")
        if (suffixRatio < 0) fail("(2) the viewed current release carries no `· current` suffix to measure")
        if (suffixRatio in 0.0..<TEXT_CONTRAST) fail("(2) selected suffix text %.2f:1 < $TEXT_CONTRAST".format(suffixRatio))
    }

    /** Assertions (3)–(5): nothing shifts, the unselected bar is reserved and transparent, one viewed row. */
    private fun judgeGeometry(
        m: Map<String, Any>,
        fail: (String) -> Unit,
    ) {
        val sel = m.sub("selected")
        val sib = m.sub("sibling")
        val lifted = m.sub("lifted")
        val indicator = m.sub("tokens").num("indicator")
        for (key in listOf("width", "height")) {
            if (abs(sel.num(key) - lifted.num(key)) > GEOMETRY_TOLERANCE) {
                fail("(3) selected $key ${sel.num(key)} != the same link unselected ${lifted.num(key)}")
            }
        }
        if (abs(sel.num("height") - sib.num("height")) > GEOMETRY_TOLERANCE) {
            fail("(3) selected height ${sel.num("height")} != sibling ${sib.num("height")}")
        }
        if (sib.num("barWidth") != indicator) fail("(3) the unselected sibling reserves ${sib.num("barWidth")}px, not ${indicator}px")
        if (sib["barColourRaw"] != TRANSPARENT) fail("(4) unselected bar is ${sib["barColourRaw"]}, not $TRANSPARENT")
        if (m.num("current") != 1.0 || m["viewed"] != VIEWED) fail("(5) aria-current on ${m["current"]} link(s), viewed ${m["viewed"]}")
    }

    /** One theme's `625-selection|…` line; its failures go to [failures]. */
    private fun judge(
        label: String,
        m: Map<String, Any>,
        failures: MutableList<String>,
    ): String {
        val fail: (String) -> Unit = { failures += "$label: $it" }
        judgeInk(m, fail)
        judgeGeometry(m, fail)
        val sel = m.sub("selected")
        val sib = m.sub("sibling")
        val lifted = m.sub("lifted")
        val tokens = m.sub("tokens")
        return "625-selection|$label|bar=${sel.num("barWidth")}px/${tokens.num("indicator")}px colour=${sel["barColour"]}" +
            " focus=${tokens["focus"]}|ink=${sel["ink"]} bg=${sel["background"]} brandSoft=${tokens["brandSoft"]}" +
            " selectedSurface=${tokens["surfaceSelected"]}|primary=${tokens["primary"]} inverted=${tokens["inverted"]}" +
            "|ratio=%.2f hover=%.2f suffix=%.2f".format(m.num("ratio"), m.num("hoverRatio"), m.num("suffixRatio")) +
            "|box=${sel.num("width")}x${sel.num("height")} lifted=${lifted.num("width")}x${lifted.num("height")}" +
            "|sibling v${sib["version"]} bar=${sib.num("barWidth")}px ${sib["barColourRaw"]} h=${sib.num("height")}" +
            "|current=${m["current"]} viewed=v${m["viewed"]}"
    }

    @Test
    fun `the viewed version carries the menu's selection expression on every bundled theme and both halves of auto`() {
        startTrace()
        ready()
        page.setViewportSize(DESKTOP_W, DESKTOP_H)
        page.emulateMedia(Page.EmulateMediaOptions().setColorScheme(ColorScheme.LIGHT))
        val name = "test/vsel_${generatedPassword("n").take(6).lowercase()}.sql"
        seed(name)
        page.navigate("$baseUrl/templates/$name?version=$VIEWED&tab=overview")
        val selected = page.locator(".tw-version-link[aria-current='true'][data-version='$VIEWED']")
        selected.waitFor()
        page.evaluate("() => document.fonts.ready")

        val measured = sweep().map { (theme, scheme) -> measure(theme, scheme, selected) }

        val failures = mutableListOf<String>()
        val lines = measured.map { (label, m) -> judge(label, m, failures) }
        val report = (lines + failures.map { "FAIL $it" }).joinToString("\n")
        shotDir().resolve("table.txt").toFile().writeText(report + "\n")
        println(report)

        // Non-vacuity: nine vendored themes plus auto's dark half.
        measured.size shouldBeGreaterThanOrEqual MIN_SWEEP
        withClue(report) { failures shouldBe emptyList() }
    }

    private companion object {
        const val VIEWED = "2"
        const val MIN_SWEEP = 10
        const val TEXT_CONTRAST = 4.5
        const val BAR = 3.0
        const val GEOMETRY_TOLERANCE = 0.01
        const val TRANSPARENT = "rgba(0, 0, 0, 0)"
        const val DESKTOP_W = 1512
        const val DESKTOP_H = 982
        val SHOT_THEMES = setOf("light", "dark", "minimal")

        val API =
            """
            async ([method, path, body, ifMatch]) => {
              const csrf = document.cookie.match(/(?:^|;\s*)dp_csrf=([^;]*)/);
              const headers = { 'Content-Type': 'application/json' };
              if (ifMatch) headers['If-Match'] = ifMatch;
              if (method !== 'GET') headers['DP-CSRF-Token'] = csrf ? decodeURIComponent(csrf[1]) : '';
              const res = await fetch(path, { method, credentials: 'same-origin', headers, body });
              let text = '';
              try { text = await res.text(); } catch (e) {}
              return [res.status, text];
            }
            """.trimIndent()

        /**
         * DangerInkContrastBrowserTest's swap (replace the link, wait for its own `load`), plus
         * `<html data-theme>` the way shell.js's theme apply sets it — Minimal's ink rule keys on it.
         */
        val SWAP_THEME =
            """
            (t) => new Promise((resolve, reject) => {
              const old = document.getElementById('theme-link');
              const link = document.createElement('link');
              link.rel = 'stylesheet';
              link.onload = () => {
                old.remove(); link.id = 'theme-link';
                document.documentElement.setAttribute('data-theme', t); resolve(true);
              };
              link.onerror = () => reject(new Error('theme ' + t + ' did not load'));
              link.href = '/vendor/design-system/themes/' + t + '.css';
              old.insertAdjacentElement('afterend', link);
            })
            """.trimIndent()

        /** Shared helpers: settle a link's transitions, canvas-resolve colours, composite, WCAG ratio. */
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
                const bg = getComputedStyle(node).backgroundColor, c = rgba(bg);
                if (c[3] > 0) layers.push(bg);
                if (c[3] === 1) break;
              }
              if (!layers.length || rgba(layers[layers.length - 1])[3] !== 1) {
                throw new Error('no opaque ancestor behind ' + el.className);
              }
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
            const sel = document.querySelector('.tw-version-link[aria-current="true"]');
            """.trimIndent()

        /** The hovered viewed link's text contrast against what the browser paints behind it. */
        val HOVER_RATIO =
            """
            async () => {
              $HELPERS
              if (!sel.matches(':hover')) throw new Error('the viewed version link is not hovered');
              await settle(sel);
              return ratio(rgba(getComputedStyle(sel).color), paint(sel));
            }
            """.trimIndent()

        /** One theme at rest: the viewed link, an unselected sibling, the viewed link lifted, the tokens. */
        val MEASURE =
            """
            async () => {
              $HELPERS
              const links = [...document.querySelectorAll('.tw-version-link')];
              const current = links.filter(a => a.getAttribute('aria-current') === 'true');
              const sib = links.find(a => !a.hasAttribute('aria-current'));
              await settle(sel); await settle(sib);
              const read = el => { const cs = getComputedStyle(el), r = el.getBoundingClientRect();
                return { version: el.dataset.version, barWidth: parseFloat(cs.borderLeftWidth),
                  barColour: css(rgba(cs.borderLeftColor)), barColourRaw: cs.borderLeftColor,
                  ink: css(rgba(cs.color)), background: css(paint(el)), width: r.width, height: r.height }; };
              const selected = read(sel), sibling = read(sib);
              // The same label in its unselected state: geometry only (border widths are not transitioned).
              sel.removeAttribute('aria-current');
              const box = sel.getBoundingClientRect();
              const lifted = { width: box.width, height: box.height };
              sel.setAttribute('aria-current', 'true');
              await settle(sel);
              const host = document.createElement('div');
              document.querySelector('.tw-topbar').appendChild(host);
              const colour = (prop, name) => { const s = document.createElement('div'); s.style[prop] = 'var(' + name + ')';
                host.appendChild(s); return css(rgba(getComputedStyle(s)[prop])); };
              const bar = document.createElement('div');
              bar.style.borderLeftStyle = 'solid'; bar.style.borderLeftWidth = 'var(--app-nav-indicator-w)';
              host.appendChild(bar);
              const tokens = { focus: colour('color', '--border-focus'), primary: colour('color', '--text-primary'),
                inverted: colour('color', '--text-inverted'), brandSoft: colour('backgroundColor', '--brand-soft'),
                surfaceSelected: colour('backgroundColor', '--surface-selected'),
                indicator: parseFloat(getComputedStyle(bar).borderLeftWidth) };
              host.remove();
              // The viewed row's own `· current` suffix is text too, in its own (muted) ink.
              const suffix = sel.querySelector('.u-muted');
              return { selected, sibling, lifted, tokens, current: current.length, viewed: sel.dataset.version,
                ratio: ratio(rgba(getComputedStyle(sel).color), paint(sel)),
                suffixRatio: suffix ? ratio(rgba(getComputedStyle(suffix).color), paint(suffix)) : -1 };
            }
            """.trimIndent()
    }
}

private fun Map<String, Any>.num(key: String): Double = (getValue(key) as Number).toDouble()

@Suppress("UNCHECKED_CAST")
private fun Map<String, Any>.sub(key: String): Map<String, Any> = getValue(key) as Map<String, Any>
