package co.datapipelines.browser

import com.microsoft.playwright.Page
import com.microsoft.playwright.options.ColorScheme
import com.microsoft.playwright.options.LoadState
import com.microsoft.playwright.options.ReducedMotion
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * #698 — the Atlas public site keeps its contrast floors on every surface it paints, measured in
 * a real browser (design record `698-atlas-public-website.md`, contrast floors):
 *
 *  - **body and secondary prose ≥ 7:1**; every other text (headings, labels and captions, data
 *    labels, controls, links, code) ≥ 4.5:1 with no large-text exemption; aria-hidden glyphs are
 *    decoration and hold the graphics floor;
 *  - **control boundaries, the selected tab's indicator, the example's bars, the DAG's lines and
 *    every keyboard focus ring ≥ 3:1** against the ground they sit on;
 *  - **no text is faded with opacity**, and no text colour is translucent.
 *
 * Measured, never derived from the stylesheet: each element with its own text is paired with the
 * ground the browser paints behind it (the background chain composited bottom-up; an SVG label
 * over the shapes painted beneath it), and filed by surface — paper, raised, soft, dark (the band
 * and its panels), forest (a primary button) — and by role. The walk covers six pages at 1440×900
 * and 390×844 under reduced motion: at rest, a full Tab walk, each of the worked example's three
 * tabs, and the open header menu. Non-vacuity: every cell in [REQUIRED] holds at least one pair.
 * Light lock: under a dark colour scheme and a saved dark theme, `/` and `/how-it-works` paint
 * exactly the pairs they paint at rest.
 * Every pair lands in `build/reports/698-atlas-contrast/matrix.txt`, one
 * `698-measure|route|width|state|surface|role|ratio|floor|ink on ground|element|sample` line each.
 *
 * Anonymous throughout: the site is public.
 */
class SiteAtlasContrastBrowserTest : BrowserSuite() {
    private class Shot(
        val label: String,
        val pairs: List<Map<String, Any>>,
        val graphics: List<Map<String, Any>>,
        val failures: List<String>,
    )

    @Test
    fun `every text, control, graphic and focus ring keeps its Atlas floor on every surface`() {
        startTrace()
        page.emulateMedia(Page.EmulateMediaOptions().setReducedMotion(ReducedMotion.REDUCE))
        val swept = VIEWPORTS.flatMap { (w, h) -> PAGES.flatMap { route -> measurePage(route, w, h) } }
        val locked = lightLock()
        val shots = swept + locked

        val lines = mutableListOf("#698 Atlas contrast — route|width|state|surface|role|ratio|floor|ink on ground|element|sample")
        val failures = mutableListOf<String>()
        val cells = sortedMapOf<String, Int>()
        shots.forEach { judge(it, lines, failures, cells) }
        REQUIRED.filter { (cells[it] ?: 0) == 0 }.forEach { failures += "no coverage: $it" }
        TABS.forEach { tab ->
            if (shots.none { it.label.endsWith("|$tab") && it.pairs.isNotEmpty() }) failures += "no pairs measured with $tab selected"
        }
        failures += lockDrift(swept, locked)
        val coverage = cells.map { (k, n) -> "698-cell|$k|$n" }
        val report = Paths.get("build", "reports", "698-atlas-contrast").toFile()
        report.mkdirs()
        report.resolve("matrix.txt").writeText((lines + coverage + failures.map { "698-fail|$it" }).joinToString("\n") + "\n")
        println((coverage + failures.map { "698-fail|$it" }).joinToString("\n"))

        withClue(failures.joinToString("\n")) { failures shouldBe emptyList() }
    }

    /** One page at one width: at rest, the Tab walk, each example tab selected, the header menu open. */
    @Suppress("UNCHECKED_CAST")
    private fun measurePage(
        route: String,
        w: Int,
        h: Int,
    ): List<Shot> {
        page.setViewportSize(w, h)
        load(route)
        val at = "$route|$w"
        val shots = mutableListOf(shot("$at|rest", page.evaluate(MEASURE, "body") as Map<String, Any>), tabWalk("$at|tab-walk"))
        if (page.locator("#tab-api").count() > 0) {
            TABS.forEach { tab ->
                page.click("#$tab")
                shots += shot("$at|$tab", page.evaluate(MEASURE, ".demo") as Map<String, Any>)
            }
        }
        val menu = if (w < NAV_WIDE_MIN) ".nav-mobile" else ".nav-menu"
        page.evaluate("(s) => { document.querySelector(s).open = true; }", menu)
        shots += shot("$at|menu-open", page.evaluate(MEASURE, "header") as Map<String, Any>)
        page.evaluate("(s) => { document.querySelector(s).open = false; }", menu)
        return shots
    }

    /** Each light-lock shot against the same page at rest: any pair whose ink, ground or element differs. */
    private fun lockDrift(
        swept: List<Shot>,
        locked: List<Shot>,
    ): List<String> =
        locked.mapNotNull { dark ->
            val rest = swept.single { it.label == dark.label.replace("|dark-scheme", "|rest") }
            val changed = fingerprint(dark) - fingerprint(rest).toSet()
            val same = rest.pairs.isNotEmpty() && dark.pairs.size == rest.pairs.size && changed.isEmpty()
            val counts = "${dark.pairs.size} vs ${rest.pairs.size} pairs"
            if (same) null else "${dark.label}: the dark preference changed the page, $counts ${changed.take(3)}"
        }

    /**
     * Atlas light lock: under a dark colour scheme AND the app's saved dark theme, the pages
     * paint exactly the pairs they paint at rest — same ink, same ground, same element.
     */
    @Suppress("UNCHECKED_CAST")
    private fun lightLock(): List<Shot> {
        page.setViewportSize(LOCK_VIEWPORT.first, LOCK_VIEWPORT.second)
        page.emulateMedia(Page.EmulateMediaOptions().setColorScheme(ColorScheme.DARK))
        page.evaluate("() => localStorage.setItem('dp-site-theme', 'dark')")
        return LOCK_PAGES.map { route ->
            load(route)
            shot("$route|${LOCK_VIEWPORT.first}|dark-scheme", page.evaluate(MEASURE, "body") as Map<String, Any>)
        }
    }

    private fun fingerprint(s: Shot) = s.pairs.map { "${it["surface"]}|${it["role"]}|${it["text"]}|${it["background"]}|${it["at"]}" }

    private fun load(route: String) {
        page.navigate("$baseUrl$route")
        page.waitForLoadState(LoadState.NETWORKIDLE)
        page.evaluate("() => document.fonts.ready.then(() => true)")
    }

    /** Tab from the top until focus leaves the document: each stop's ring against its ground, and that it is `:focus-visible`. */
    @Suppress("UNCHECKED_CAST")
    private fun tabWalk(label: String): Shot {
        val rings = mutableListOf<Map<String, Any>>()
        var presses = 0
        var done = false
        while (!done && presses++ < MAX_TAB_STOPS) {
            page.keyboard().press("Tab")
            val ring = page.evaluate(FOCUS) as Map<String, Any>?
            val first = rings.firstOrNull()
            val wrapped = ring != null && first != null && ring["at"] == first["at"] && ring["sample"] == first["sample"]
            done = wrapped || (ring == null && first != null)
            if (ring != null && !wrapped) rings += ring
        }
        val notVisible = rings.filter { it["focusVisible"] != true }.map { "focus is not :focus-visible on ${it["at"]}" }
        return Shot(label, emptyList(), rings, if (rings.isEmpty()) listOf("the Tab walk reached nothing") else notVisible)
    }

    @Suppress("UNCHECKED_CAST")
    private fun shot(
        label: String,
        m: Map<String, Any>,
    ) = Shot(label, m["pairs"] as List<Map<String, Any>>, m["graphics"] as List<Map<String, Any>>, m["failures"] as List<String>)

    private fun judge(
        s: Shot,
        lines: MutableList<String>,
        failures: MutableList<String>,
        cells: MutableMap<String, Int>,
    ) {
        val rows =
            s.pairs.map { p -> Triple(p, p["role"] as String, (p["floor"] as Number).toDouble()) } +
                s.graphics.map { g -> Triple(g, g["kind"] as String, GRAPHICS_FLOOR) }
        rows.forEach { (p, role, floor) ->
            val ratio = (p["ratio"] as Number).toDouble()
            val inks = p["text"]?.let { "$it on ${p["background"]}" } ?: "-"
            val row = "${s.label}|${p["surface"]}|$role|${"%.2f".format(ratio)}|$floor|$inks|${p["at"]}|${p["sample"]}"
            lines += "698-measure|$row"
            cells.merge("${p["surface"]} × $role", 1, Int::plus)
            if (ratio < floor) failures += row
        }
        s.failures.forEach { failures += "${s.label}|$it" }
    }

    private companion object {
        const val GRAPHICS_FLOOR = 3.0
        const val MAX_TAB_STOPS = 400

        /** `--site-nav-wide-min` (64rem): below it the header is the phone menu. */
        const val NAV_WIDE_MIN = 1024
        val VIEWPORTS = listOf(1440 to 900, 390 to 844)
        val PAGES = listOf("/", "/how-it-works", "/pricing", "/pipeline-scheduling", "/semantic-layer", "/docs/scheduler")
        val TABS = listOf("tab-api", "tab-evidence", "tab-chart")
        val LOCK_VIEWPORT = 1440 to 900
        val LOCK_PAGES = listOf("/", "/how-it-works")

        /** The cells the walk must reach: each surface with the roles the record names on it. */
        val REQUIRED =
            listOf(
                "paper" to listOf("body", "heading", "label/caption", "link", "control", "control boundary", "selected tab indicator"),
                "raised" to listOf("body", "heading", "label/caption", "link", "code", "control"),
                "soft" to listOf("body", "heading", "label/caption", "link", "code", "bar fill", "control boundary"),
                "dark" to listOf("body", "heading", "label/caption", "link", "code", "data label", "control", "dag line"),
                "forest" to listOf("control"),
            ).flatMap { (surface, roles) -> roles.map { "$surface × $it" } } +
                listOf("paper", "raised", "soft", "dark").map { "$it × focus ring" }

        /**
         * StatusInkContrastBrowserTest's canvas helpers (`rgba` resolves any computed colour to painted
         * sRGB, `paint` composites an element's background chain), plus `svgGround`, the surface and
         * role classifiers and the element path the report prints.
         */
        val HELPERS =
            """
            const canvas = document.createElement('canvas');
            canvas.width = canvas.height = 1;
            const ctx = canvas.getContext('2d', { willReadFrequently: true });
            const rgba = c => { ctx.clearRect(0, 0, 1, 1); ctx.fillStyle = '#000'; ctx.fillStyle = c;
              ctx.fillRect(0, 0, 1, 1); const d = ctx.getImageData(0, 0, 1, 1).data; return [d[0], d[1], d[2], d[3] / 255]; };
            const css = a => 'rgb(' + a[0] + ', ' + a[1] + ', ' + a[2] + ')';
            const over = (top, alpha, below) => { ctx.clearRect(0, 0, 1, 1); ctx.fillStyle = css(below); ctx.fillRect(0, 0, 1, 1);
              ctx.globalAlpha = alpha; ctx.fillStyle = css(top); ctx.fillRect(0, 0, 1, 1); ctx.globalAlpha = 1;
              const d = ctx.getImageData(0, 0, 1, 1).data; return [d[0], d[1], d[2], 1]; };
            // The opaque colour an HTML element paints behind its content: its background chain composited bottom-up.
            const paint = el => {
              const layers = [];
              for (let node = el; node; node = node.parentElement) {
                const c = rgba(getComputedStyle(node).backgroundColor);
                if (c[3] > 0) layers.push(c);
                if (c[3] === 1) break;
              }
              if (!layers.length || layers[layers.length - 1][3] !== 1) throw new Error('no opaque ground behind ' + el.tagName);
              let out = layers[layers.length - 1];
              for (let i = layers.length - 2; i >= 0; i--) out = over(layers[i], layers[i][3], out);
              return out;
            };
            const lum = c => { const f = v => { const s = v / 255;
                return s <= 0.04045 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4); };
              return 0.2126 * f(c[0]) + 0.7152 * f(c[1]) + 0.0722 * f(c[2]); };
            const ratio = (a, b) => { const l1 = lum(a), l2 = lum(b);
              return (Math.max(l1, l2) + 0.05) / (Math.min(l1, l2) + 0.05); };
            // The palette's grounds (design record, palette), as the surfaces a pair is filed under.
            const SURFACES = { 'rgb(243, 241, 233)': 'paper', 'rgb(250, 248, 241)': 'raised', 'rgb(228, 232, 220)': 'soft',
              'rgb(32, 60, 49)': 'dark', 'rgb(42, 74, 61)': 'dark', 'rgb(40, 99, 78)': 'forest', 'rgb(31, 79, 62)': 'forest' };
            const surface = b => SURFACES[css(b)] || 'other ' + css(b);
            const SVG = 'http://www.w3.org/2000/svg';
            // An SVG text's ground: every shape painted before it whose fill covers its centre, in paint order.
            const svgGround = t => {
              const svg = t.ownerSVGElement, r = t.getBoundingClientRect();
              const centre = new DOMPoint(r.left + r.width / 2, r.top + r.height / 2);
              let ground = paint(svg.parentElement);
              for (const g of svg.querySelectorAll('rect, circle, ellipse, path, polygon')) {
                if (!(t.compareDocumentPosition(g) & Node.DOCUMENT_POSITION_PRECEDING)) continue;
                const cs = getComputedStyle(g), m = g.getScreenCTM();
                if (cs.fill === 'none' || cs.display === 'none' || cs.visibility !== 'visible' || !m) continue;
                if (!g.isPointInFill(centre.matrixTransform(m.inverse()))) continue;
                const c = rgba(cs.fill);
                ground = over(c, c[3] * parseFloat(cs.fillOpacity) * parseFloat(cs.opacity), ground);
              }
              return ground;
            };
            // Rendered and bigger than the 1px visually-hidden pattern.
            const visible = el => { if (!el.checkVisibility({ visibilityProperty: true, contentVisibilityAuto: true })) return false;
              const r = el.getBoundingClientRect(); return r.width >= 2 && r.height >= 2; };
            const faded = el => { for (let n = el; n && n !== document.documentElement; n = n.parentElement)
                if (parseFloat(getComputedStyle(n).opacity) < 1) return n; return null; };
            const name = n => n.tagName.toLowerCase() + (n.id ? '#' + n.id : '') +
              (typeof n.className === 'string' && n.className.trim() ? '.' + n.className.trim().split(/\s+/).join('.') : '');
            const path = el => { const parts = [];
              for (let n = el; n && parts.length < 4 && n !== document.body; n = n.parentElement) parts.unshift(name(n));
              return parts.join(' > '); };
            const sample = el => (el.getAttribute('aria-label') || el.textContent).trim().replace(/\s+/g, ' ').slice(0, 40);
            const role = el => {
              if (el.namespaceURI === SVG) return 'data label';
              if (el.closest('[aria-hidden=true]')) return 'decorative';
              if (el.closest('pre, code, kbd, samp')) return 'code';
              if (el.closest('button, summary, [role=tab], .btn, input, select, textarea')) return 'control';
              if (el.closest('a[href]')) return 'link';
              if (el.closest('h1, h2, h3, h4, h5, h6, .ds-display, .ds-headline')) return 'heading';
              const cs = getComputedStyle(el);
              const small = /mono/i.test(cs.fontFamily) || parseFloat(cs.fontSize) < 15;
              return small || el.closest('figcaption, small, th, dt, label, .breadcrumbs') ? 'label/caption' : 'body';
            };
            """.trimIndent()

        /** Every own-text element and every boundary graphic under `scope`, paired with its ground. */
        val MEASURE =
            """
            async (scope) => {
              $HELPERS
              const FLOOR = { decorative: 3, body: 7, heading: 4.5, 'label/caption': 4.5, control: 4.5, link: 4.5,
                code: 4.5, 'data label': 4.5 };
              const root = document.querySelector(scope);
              const pairs = [], graphics = [], failures = [];
              const ownText = el => [...el.childNodes].some(n => n.nodeType === 3 && n.textContent.trim());
              for (const el of root.querySelectorAll('*')) {
                if (['SCRIPT', 'STYLE', 'TEMPLATE', 'NOSCRIPT'].includes(el.tagName) || !ownText(el)) continue;
                const isSvg = el.namespaceURI === SVG;
                const drawn = el => !el.closest('[hidden]') && el.getBoundingClientRect().width > 0 && visible(el.ownerSVGElement);
                const shown = isSvg ? drawn(el) : visible(el);
                if (!shown) continue;
                const cs = getComputedStyle(el), fg = rgba(isSvg ? cs.fill : cs.color), r = role(el);
                const bg = isSvg ? svgGround(el) : paint(el);
                const p = { role: r, surface: surface(bg), ratio: ratio(fg, bg), floor: FLOOR[r], text: css(fg),
                  background: css(bg), at: path(el), sample: sample(el) };
                pairs.push(p);
                if (fg[3] !== 1) failures.push('translucent text ' + cs.color + ' at ' + p.at);
                const fade = faded(el);
                if (fade) failures.push('text faded by opacity ' + getComputedStyle(fade).opacity + ' on '
                  + path(fade) + ' at ' + p.at);
              }
              const graphic = (kind, el, ink, ground) =>
                graphics.push({ kind, surface: surface(ground), ratio: ratio(ink, ground), at: path(el), sample: sample(el) });
              // A link inside running text is told apart by an underline, or by 3:1 against the text around it.
              for (const a of root.querySelectorAll('a[href]:not(.btn)')) {
                const block = a.parentElement;
                if (!visible(a) || !block.matches('p, li, dd, td, figcaption, blockquote') || !ownText(block)) continue;
                if (getComputedStyle(a).textDecorationLine.includes('underline')) continue;
                const link = rgba(getComputedStyle(a).color), text = rgba(getComputedStyle(block).color);
                graphics.push({ kind: 'in-text link', surface: surface(paint(a)), ratio: ratio(link, text), at: path(a),
                  sample: sample(a) });
              }
              // A control's boundary is its fill or its border against what is behind it, whichever is stronger.
              for (const el of root.querySelectorAll('a.btn, button:not([role=tab]), .nav-mobile > summary, input, select')) {
                if (!visible(el)) continue;
                const cs = getComputedStyle(el), out = paint(el.parentElement), fill = rgba(cs.backgroundColor);
                const own = fill[3] > 0 ? over(fill, fill[3], out) : out;
                const bordered = parseFloat(cs.borderTopWidth) >= 1 && cs.borderTopStyle !== 'none';
                const border = bordered ? rgba(cs.borderTopColor) : out;
                graphic('control boundary', el, ratio(own, out) >= ratio(border, out) ? own : border, out);
              }
              for (const el of root.querySelectorAll('[role=tab][aria-selected=true]')) {
                const ink = rgba(getComputedStyle(el).borderBottomColor);
                if (visible(el)) graphic('selected tab indicator', el, ink, paint(el.parentElement));
              }
              for (const f of root.querySelectorAll('.bar-fill')) {
                if (visible(f.ownerSVGElement)) graphic('bar fill', f, rgba(getComputedStyle(f).fill), paint(f.ownerSVGElement));
              }
              for (const g of root.querySelectorAll('.dag .node, .dag .edge')) {
                const stroke = getComputedStyle(g).stroke;
                if (!visible(g.ownerSVGElement) || stroke === 'none') continue;
                graphic('dag line', g, rgba(stroke), paint(g.ownerSVGElement.parentElement));
              }
              return { pairs, graphics, failures };
            }
            """.trimIndent()

        /** The focused element's ring against the ground it is drawn on (the outline sits outside the element). */
        val FOCUS =
            """
            () => {
              $HELPERS
              const el = document.activeElement;
              if (!el || el === document.body) return null;
              const cs = getComputedStyle(el), out = paint(el.parentElement);
              const none = cs.outlineStyle === 'none' || parseFloat(cs.outlineWidth) < 1;
              return { kind: 'focus ring', surface: surface(out), focusVisible: el.matches(':focus-visible'),
                ratio: none ? 0 : ratio(rgba(cs.outlineColor), out), at: path(el), sample: sample(el) };
            }
            """.trimIndent()
    }
}
