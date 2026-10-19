package co.datapipelines.browser

import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.options.AriaRole
import com.microsoft.playwright.options.ColorScheme
import com.microsoft.playwright.options.LoadState
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.nio.file.Paths

/**
 * #509 — danger TEXT reaches the 4.5:1 text floor on every surface it paints on, on every
 * vendored theme, through ONE app token, `--app-danger-ink` (ui-screens §2.8).
 *
 * The defect: `--accent-danger` is a FILL colour (#dc2626 in dark, the solid button's slab) and
 * tops out at 3.89:1 on the dark surfaces, 3.60 on the #502 chip tint. In light it is the text
 * red itself, and three light themes miss the floor on the inset and hover surfaces too
 * (light 4.34, saas 4.39, ocean 4.09 — ocean also on its own chip tint, 4.42). The token is the
 * design system's `--text-danger` in dark (#fca5a5) and the theme's red at 3% lower oklch
 * lightness in light (owner ruling on #509, 2026-10-19).
 *
 * Measured, never derived from the stylesheet text: for each theme the running app offers
 * (plus `auto` under an emulated dark preference — its dark half exists only there), the
 * computed colour of real elements against the background the browser actually paints behind
 * them, composited through every translucent ancestor:
 *
 *  - the Fail chip (`.app-chip-bad`, the deleted key's row) on its own tint;
 *  - the ghost Delete button (`.u-danger`) at rest on the card, and HOVERED on
 *    `--surface-hover`, where `.ds-button-ghost.u-danger:hover` keeps it red;
 *  - the token itself on each surface a danger-text site paints on (page, default, raised,
 *    inset, hover, the chip tint) — the matrix that covers the sites this page cannot render
 *    (the editor's card foot, the dock count, the schedule callout …), which all paint on one
 *    of those surfaces.
 *
 * And the non-text side stays where it was: the danger BORDERS keep `--accent-danger` (a 3:1
 * component boundary, not text), and the vendored `--accent-danger-text` (the WHITE on a red
 * button) is not redeclared by the app.
 *
 * Every read waits (bounded) for the measured elements' CSS transitions to finish (a swap or a hover
 * transitions the button's colour, and a mid-flight read is a blend of two themes). Colours
 * are resolved through a 1×1 canvas, so `oklch(…)`, `color(srgb …)` and `rgb(…)`
 * computed values all reduce to the sRGB bytes the browser paints. The full matrix lands in
 * `build/reports/509-danger-ink/matrix.txt`, with a chip-and-button shot per theme.
 */
class DangerInkContrastBrowserTest : BrowserSuite() {
    private fun ready() {
        val user =
            seedLocalUser(
                uniqueEmail("ink509-" + generatedPassword("u").take(8)),
                generatedPassword("pw"),
                mustChange = false,
            )
        login(user.email, user.oneTimePassword)
        page.waitForURL("**/dashboard")
        createWorkspace("ink509ws-" + generatedPassword("w").take(8).lowercase())
    }

    /** ApiKeysGoldenPathBrowserTest's mint: the modal's form, waiting on the mint's own response. */
    private fun mint(name: String) {
        page.click("text=New API key")
        page.check("#createKeyForm input[name=kind][value=endpoint]")
        page.fill("#key-name", name)
        val response = page.waitForResponse("**/partials/api-keys") { page.click("#createKeyForm button[type=submit]") }
        response.status() shouldBe 200
        page.waitForSelector("#keyCreated")
    }

    private fun rowFor(name: String): Locator = page.locator("tr", Page.LocatorOptions().setHasText(name)).first()

    private fun shotDir(): Path = Paths.get("build", "reports", "509-danger-ink").also { it.toFile().mkdirs() }

    /** The two real sites on one page: a live key's ghost Delete and a deleted key's Fail chip. */
    private fun seedSites(): Pair<Locator, Locator> {
        page.navigate("$baseUrl/api-keys")
        page.waitForURL("**/api-keys")
        mint("ink-live")
        mint("ink-dead")
        page.onDialog { it.accept() }
        page.waitForResponse("**/partials/api-keys/*") {
            rowFor("ink-dead").getByRole(AriaRole.BUTTON, Locator.GetByRoleOptions().setName("Delete")).click()
        }
        rowFor("ink-dead").locator(".app-chip-bad").waitFor()
        page.reload()
        page.waitForLoadState(LoadState.NETWORKIDLE)
        val chip = rowFor("ink-dead").locator(".app-chip-bad")
        val button = rowFor("ink-live").locator("button.u-danger[data-verb='key-revoke']")
        chip.waitFor()
        button.waitFor()
        return chip to button
    }

    /**
     * DERIVED FROM THE RUNNING APP (ShellFeelBrowserTest's idiom): the modes and palettes the
     * top bar offers, so a tenth vendored theme joins the sweep by itself — plus `auto` again
     * under an emulated dark preference, the only way its dark half renders.
     */
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

    /** One theme: swap it in, measure at rest, hover the Delete and measure again, photograph both sites. */
    @Suppress("UNCHECKED_CAST")
    private fun measure(
        theme: String,
        scheme: ColorScheme,
        chip: Locator,
        button: Locator,
    ): Pair<String, Map<String, Any>> {
        val label = if (scheme == ColorScheme.DARK) "$theme(dark)" else theme
        page.emulateMedia(Page.EmulateMediaOptions().setColorScheme(scheme))
        page.evaluate(SWAP_THEME, theme) shouldBe true
        page.mouse().move(0.0, 0.0)
        val rest = page.evaluate(MEASURE, false) as Map<String, Any>
        button.hover()
        val hover = page.evaluate(MEASURE, true) as Map<String, Any>
        page.mouse().move(0.0, 0.0)
        chip.screenshot(Locator.ScreenshotOptions().setPath(shotDir().resolve("$label-chip.png")))
        button.screenshot(Locator.ScreenshotOptions().setPath(shotDir().resolve("$label-delete.png")))
        return label to (rest + mapOf("hover" to hover))
    }

    /** One theme's report lines and its failures: the text floor, the unchanged borders, the consumers, the vendored white. */
    @Suppress("UNCHECKED_CAST")
    private fun judge(
        label: String,
        m: Map<String, Any>,
        lines: MutableList<String>,
        failures: MutableList<String>,
    ) {
        val pairs = (m["pairs"] as List<Map<String, Any>>) + ((m["hover"] as Map<String, Any>)["pairs"] as List<Map<String, Any>>)
        pairs.forEach { p ->
            val ratio = (p["ratio"] as Number).toDouble()
            val row = "$label  ${p["site"]}  ${"%.2f".format(ratio)}:1  ink=${p["text"]} on ${p["background"]}"
            lines += row
            if (ratio < TEXT_FLOOR) failures += row
        }
        val borders = m["borders"] as Map<String, String>
        lines += "$label  borders ${borders.entries.joinToString(" ")} accent=${m["accent"]}"
        borders.forEach { (site, colour) ->
            if (colour != m["accent"]) failures += "$label: border $site paints $colour, not --accent-danger ${m["accent"]}"
        }
        (m["consumers"] as Map<String, String>).forEach { (site, colour) ->
            if (colour != m["ink"]) failures += "$label: $site paints $colour, not --app-danger-ink ${m["ink"]}"
        }
        if (m["onRed"] != m["onRedDeclared"]) {
            failures += "$label: --accent-danger-text is ${m["onRed"]}, the theme declares ${m["onRedDeclared"]}"
        }
    }

    @Test
    fun `danger text keeps the 4_5 to 1 floor on every surface of every vendored theme`() {
        startTrace()
        ready()
        page.setViewportSize(1440, 900)
        page.emulateMedia(Page.EmulateMediaOptions().setColorScheme(ColorScheme.LIGHT))
        val (chip, button) = seedSites()

        val measured = sweep().map { (theme, scheme) -> measure(theme, scheme, chip, button) }

        val failures = mutableListOf<String>()
        val lines = mutableListOf("#509 danger ink — theme × site, WCAG contrast (floor $TEXT_FLOOR:1)")
        measured.forEach { (label, m) -> judge(label, m, lines, failures) }
        val report = lines.joinToString("\n")
        shotDir().resolve("matrix.txt").toFile().writeText(report + "\n")
        println(report)

        // Non-vacuity: nine vendored themes plus auto's dark half, each with its six surfaces,
        // two real sites at rest and one hovered.
        measured.size shouldBeGreaterThanOrEqual MIN_SWEEP
        withClue(report) { failures shouldBe emptyList() }
    }

    private companion object {
        const val TEXT_FLOOR = 4.5
        const val MIN_SWEEP = 10

        /**
         * ShellFeelBrowserTest's theme swap, copied (its companion is private): REPLACE the
         * stylesheet link and wait for its own `load` — mutating the live link's href measures
         * the old theme for an unbounded moment, and a nine-theme sweep then measures one theme
         * nine times. `onerror` rejecting makes a missing stylesheet a failure.
         */
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
         * One theme's measurement. The canvas resolves any computed colour syntax to the sRGB
         * bytes painted; `paint` composites an element's background chain bottom-up (the first
         * opaque ancestor, then every translucent layer above it). Probes stay attached until
         * every read is done (a detached element computes `""`).
         */
        val MEASURE =
            """
            async (hovered) => {
              // A theme swap or a hover starts colour TRANSITIONS on the measured elements
              // (.ds-button transitions its colour and background): reading mid-flight measures
              // a blend of two themes. Wait for THEIR transitions only, bounded, and refuse to
              // measure if one is still running.
              const settle = async el => {
                const running = () => el.getAnimations().filter(a => a instanceof CSSTransition && a.playState === 'running');
                const deadline = performance.now() + 3000;
                while (running().length && performance.now() < deadline) await new Promise(r => setTimeout(r, 25));
                if (running().length) throw new Error('transition still running on ' + el.className);
              };
              const button = document.querySelector("button.u-danger[data-verb='key-revoke']");
              const chip = [...document.querySelectorAll('.app-chip-bad')].find(e => e.textContent.trim() === 'deleted');
              await settle(button);
              await settle(chip);
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
              const pair = (site, el) => { const t = rgba(getComputedStyle(el).color), b = paint(el);
                if (t[3] !== 1) throw new Error(site + ' text is translucent');
                return { site, ratio: ratio(t, b), text: css(t), background: css(b) }; };

              if (hovered) {
                if (!button.matches(':hover')) throw new Error('the Delete button is not hovered');
                return { pairs: [pair('delete-button:hover', button)] };
              }
              const main = document.querySelector('#app-main');
              const host = document.createElement('div');
              main.appendChild(host);
              const token = name => { const s = document.createElement('span'); s.style.color = 'var(' + name + ')';
                host.appendChild(s); return css(rgba(getComputedStyle(s).color)); };
              const pairs = [pair('chip', chip), pair('delete-button', button)];
              for (const surface of ['--surface-page', '--surface-default', '--surface-raised', '--surface-inset',
                                     '--surface-hover', '--accent-danger-bg']) {
                const box = document.createElement('div');
                box.style.background = 'var(' + surface + ')';
                const ink = document.createElement('span');
                ink.style.color = 'var(--app-danger-ink)';
                ink.textContent = 'x';
                box.appendChild(ink);
                host.appendChild(box);
                pairs.push(pair('ink on ' + surface, ink));
              }
              // The non-text side: the four app.css danger BORDERS read --accent-danger …
              const borders = {};
              for (const cls of ['app-card-danger', 'app-error-card', 'app-inline-refusal', 'tf-case-fail']) {
                const el = document.createElement('div');
                el.className = cls;
                host.appendChild(el);
                borders[cls] = css(rgba(getComputedStyle(el).borderTopColor));
              }
              // … and app.css's danger TEXT rules this page does not render read the ink.
              const consumers = {};
              const outcome = document.createElement('p');
              outcome.className = 'tf-outcome tf-outcome-refused';
              host.appendChild(outcome);
              consumers['tf-outcome-refused'] = css(rgba(getComputedStyle(outcome).color));
              const nest = (parent, cls) => { const el = document.createElement('div'); el.className = cls;
                parent.appendChild(el); return el; };
              const caseMark = nest(nest(nest(host, 'tf-case-fail'), 'tf-case-head'), 'tf-mark');
              consumers['tf-case-fail mark'] = css(rgba(getComputedStyle(caseMark).color));
              const invMark = nest(nest(host, 'tf-inv-fail'), 'tf-mark');
              consumers['tf-inv-fail mark'] = css(rgba(getComputedStyle(invMark).color));
              consumers['u-danger'] = css(rgba(getComputedStyle(button).color));
              consumers['app-chip-bad'] = css(rgba(getComputedStyle(chip).color));

              const root = getComputedStyle(document.documentElement);
              const result = { pairs, borders, consumers,
                accent: token('--accent-danger'), ink: token('--app-danger-ink'),
                onRed: root.getPropertyValue('--accent-danger-text').trim(),
                onRedDeclared: root.getPropertyValue('--_accent-danger-text').trim() };
              host.remove();
              return result;
            }
            """.trimIndent()
    }
}
