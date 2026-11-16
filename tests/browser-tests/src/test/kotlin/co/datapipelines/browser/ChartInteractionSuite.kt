package co.datapipelines.browser

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.ConsoleMessage
import com.microsoft.playwright.Page
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import org.junit.jupiter.api.AfterEach
import java.nio.file.Files
import java.nio.file.Paths

/**
 * #990 (js-rendered F6) — the shared ground of the interaction layer's page proofs. Every chart these classes read is
 * mounted on a capability preview (the product's page: the engine's files, the layer, the adapter and the live page
 * policy, [ChartPageSuite]) through the adapter's own `create`, into a host the harness sizes to an exact frame, so a
 * test chooses the frame (80 × 24, a frame that cuts a subtitle, ...) and the data, and refreshes the same mounted chart
 * with `renderData`. Everything asserted is read off the page: the picture's DOM (the layer's `g.dp-tooltip` card, the
 * `dp-focus` ring, the `dp-guide` crosshair, the legend's `aria-pressed` and `dp-off`), the adapter's `hidden()` list
 * and `index()`, and `document.activeElement`.
 *
 * **D11, the lane's own clean console.** Each context this suite opens listens on every page for console messages of
 * type `error` and for page errors ([consoleErrors]); [assertCleanConsole] runs after every test, so a test with one
 * fails by name. The shared CSP collector is not widened.
 *
 * **The listener ledger (D7).** The harness wraps `EventTarget.prototype.addEventListener` and `removeEventListener`
 * on the page before it mounts, keeping the live listeners of every `svg.dp-chart`, so `dispose` is read as the count
 * left on the picture (0), not as the layer's own word.
 */
abstract class ChartInteractionSuite : ChartPageSuite() {
    /** The console errors and page errors of the current test's contexts (D11). */
    protected val consoleErrors = mutableListOf<String>()

    /** The falsification copies of the current test (a gate's plant sets one before its context opens). */
    protected var plantCopies: Map<String, (String) -> String> = emptyMap()

    override val copies: Map<String, (String) -> String> get() = plantCopies

    /** The collector of D11 on [context]: both listeners on every page it opens. */
    protected fun listening(context: BrowserContext): BrowserContext {
        context.onPage { page ->
            page.onConsoleMessage { m: ConsoleMessage -> if (m.type() == "error") consoleErrors += "${page.url()}: console ${m.text()}" }
            page.onPageError { e -> consoleErrors += "${page.url()}: page error $e" }
        }
        return context
    }

    @AfterEach
    fun assertCleanConsole() {
        val seen = consoleErrors.toList()
        consoleErrors.clear()
        withClue("D11: the test's pages wrote no console error and threw no page error") { seen.shouldBeEmpty() }
    }

    /** A signed-in workspace and one preview page (the bar fixture) in a listening context; the harness installed. */
    protected fun harnessPage(
        slug: String,
        width: Int = DEFAULT_WIDTH,
        height: Int = DEFAULT_HEIGHT,
        theme: String = "light",
    ): Pair<BrowserContext, Page> = openHarness(ready(slug), "harness", width, height, theme)

    /**
     * One more harness page in the workspace [root] (a new listening context, [copies] served through it as they stand
     * now; [touch]: a touch screen), its preview created at `charts/[name]`.
     */
    protected fun openHarness(
        root: String,
        name: String,
        width: Int = DEFAULT_WIDTH,
        height: Int = DEFAULT_HEIGHT,
        theme: String = "light",
        touch: Boolean = false,
    ): Pair<BrowserContext, Page> {
        val context =
            if (touch) {
                listening(
                    newBrowserContext(Browser.NewContextOptions().setViewportSize(width, height).setHasTouch(true)),
                ).also(::servingCopies)
            } else {
                listening(chartContext(width, height))
            }
        val page = preview(context, documentOf("$root/charts/$name", ChartBrowserFixtures.fixture("bar-vertical")), theme)
        page.evaluate(HARNESS)
        return context to page
    }

    /**
     * Mounts [chart] at [w] × [h] through the adapter's `create` (replacing the harness's previous chart); answers
     * `{ok, ack}` or `{ok: false, error}`.
     */
    protected fun mount(
        page: Page,
        chart: Chart,
        w: Int,
        h: Int,
        description: String? = null,
        tokens: Map<String, String> = emptyMap(),
    ): Map<*, *> =
        page.evaluate(
            "a => window.__h.mount(a)",
            mapOf(
                "w" to w,
                "h" to h,
                "display_name" to chart.name,
                "description" to description,
                "config" to jsonValue(chart.config),
                "types" to chart.types,
                "columns" to chart.columns,
                "tokens" to tokens,
            ),
        ) as Map<*, *>

    /** The same mounted chart handed [columns] by the runtime's `renderData` (a data refresh). */
    protected fun refresh(
        page: Page,
        columns: Map<String, List<Any?>>,
    ): Map<*, *> = page.evaluate("c => window.__h.refresh(c)", columns) as Map<*, *>

    /** The picture as the page shows it now (see [READ]). */
    protected fun read(page: Page): Map<*, *> = page.evaluate("() => window.__h.read()") as Map<*, *>

    /** The client point of series [id]'s mark at datum [datum] (the layer's own anchor through the screen matrix). */
    protected fun pointOf(
        page: Page,
        id: String,
        datum: Int,
    ): Pair<Double, Double> {
        val at = page.evaluate("a => window.__h.point(a.id, a.datum)", mapOf("id" to id, "datum" to datum)) as Map<*, *>
        return (at["x"] as Number).toDouble() to (at["y"] as Number).toDouble()
    }

    /** Moves the mouse to the client point [at] and lets the page settle one frame. */
    protected fun hover(
        page: Page,
        at: Pair<Double, Double>,
    ) {
        page.mouse().move(at.first, at.second)
        page.evaluate(FRAME)
    }

    /** One key on the focused element, then one frame. */
    protected fun press(
        page: Page,
        key: String,
    ) {
        page.keyboard().press(key)
        page.evaluate(FRAME)
    }

    /** Focus on the button before the picture, then Tab: the picture takes focus (the chart's one tab stop). */
    protected fun tabIn(page: Page) {
        page.evaluate("() => { document.querySelector('button.dp-h990-before').focus(); return true; }")
        press(page, "Tab")
    }

    /** Writes [lines] to `build/reports/js-rendered-interactions/[name]`. */
    protected fun report(
        name: String,
        lines: List<String>,
    ) {
        Files.createDirectories(REPORTS)
        Files.write(REPORTS.resolve(name), lines)
    }

    /** A chart the harness mounts: its display name, config (JSON text), column types and columns. */
    data class Chart(
        val name: String,
        val config: String,
        val types: Map<String, String>,
        val columns: Map<String, List<Any?>>,
    )

    /** An engine fixture's emitted input (`<wire>/<name>`, 960 × 540) as a chart the harness mounts. */
    protected fun engineChart(path: String): Chart {
        val input =
            com.google.gson.JsonParser
                .parseString(emitted("$path/960x540.input.json"))
                .asJsonObject
        val document = input.getAsJsonObject("document")
        val gson = com.google.gson.Gson()

        @Suppress("UNCHECKED_CAST")
        val columns = gson.fromJson(input.get("columns"), Map::class.java) as Map<String, List<Any?>>

        @Suppress("UNCHECKED_CAST")
        val types = gson.fromJson(input.get("types"), Map::class.java) as Map<String, String>
        return Chart(document.get("display_name").asString, document.get("config").toString(), types, columns)
    }

    /** [text] with its ONE [anchor] replaced (a plant's anchor occurs exactly once). */
    protected fun once(
        text: String,
        anchor: String,
        replacement: String,
    ): String {
        check(text.split(anchor).size == 2) { "the plant's anchor $anchor must occur exactly once" }
        return text.replace(anchor, replacement)
    }

    private fun jsonValue(json: String): Any? =
        com.google.gson
            .Gson()
            .fromJson(json, Any::class.java)

    protected companion object {
        val REPORTS = Paths.get("build", "reports", "js-rendered-interactions")

        /** The next animation frame, twice (the adapter's scheduled redraw, then the layer's paint). */
        const val FRAME = "() => new Promise(r => requestAnimationFrame(() => requestAnimationFrame(() => r(true))))"

        /**
         * The harness (installed once per page): the adapter's `create` taken through its own `register`, a host sized by
         * the CSSOM (no style attribute is written; the page policy allows the CSSOM), a button before and after it for
         * Tab, the listener ledger, the engine's last output (its `render` wrapped), and the reads.
         */
        const val HARNESS =
            """() => {
              if (window.__h) return true;
              var create = null;
              window.DatapipelinesDashboardChart.register({ registerRenderer: function (r) { create = r.create; } });
              var live = [];
              var add = EventTarget.prototype.addEventListener, remove = EventTarget.prototype.removeEventListener;
              var cap = function (o) { return typeof o === 'boolean' ? o : !!(o && o.capture); };
              var isChart = function (el) { return el instanceof SVGSVGElement && el.classList.contains('dp-chart'); };
              EventTarget.prototype.addEventListener = function (type, fn, o) {
                var self = this, c = cap(o);
                if (isChart(self) && !live.some(function (l) { return l.el === self && l.type === type && l.fn === fn && l.c === c; })) live.push({ el: self, type: type, fn: fn, c: c });
                return add.call(this, type, fn, o);
              };
              EventTarget.prototype.removeEventListener = function (type, fn, o) {
                var self = this, c = cap(o);
                live = live.filter(function (l) { return !(l.el === self && l.type === type && l.fn === fn && l.c === c); });
                return remove.call(this, type, fn, o);
              };
              var R = window.JsRendered, original = R.render, outputs = 0;
              R.render = function () { var out = original.apply(this, arguments); window.__h.last = out; outputs++; return out; };
              var cur = null;
              // Every title the engine has named a series by, kept across draws: a hidden series a refresh dropped keeps its name.
              var known = {};
              var titles = function () { var out = window.__h.last; (out && out.summary ? out.summary.series : []).forEach(function (x) { known[x.id] = x.title; }); return known; };
              var titled = function (id) { var t = titles(); return id !== null && t[id] !== undefined ? t[id] : id; };
              var idOf = function (title) { var t = titles(); for (var k in t) if (t[k] === title) return k; return title; };
              var size = function (el, w, h) { el.style.width = w + 'px'; el.style.height = h + 'px'; };
              var settle = function () { return new Promise(function (r) { requestAnimationFrame(function () { requestAnimationFrame(function () { r(true); }); }); }); };
              var answer = function (p) { return p.then(function (ack) { return settle().then(function () { return { ok: true, ack: ack }; }); }, function (e) { return settle().then(function () { return { ok: false, error: String(e && e.message) }; }); }); };
              var rect = function (el) { var b = el.getBoundingClientRect(); return { x: b.left, y: b.top, w: b.width, h: b.height }; };
              window.__h = {
                last: null,
                mount: function (a) {
                  if (cur !== null) this.dispose();
                  var before = document.createElement('button'), after = document.createElement('button'), host = document.createElement('div');
                  before.className = 'dp-h990-before'; after.className = 'dp-h990-after';
                  before.textContent = 'before'; after.textContent = 'after';
                  host.className = 'dp-h990-host';
                  host.style.position = 'fixed'; host.style.left = '24px'; host.style.top = '24px'; host.style.zIndex = '2147483000';
                  host.style.padding = '0'; host.style.margin = '0'; host.style.border = '0'; host.style.boxSizing = 'content-box';
                  host.style.background = 'var(--chart-canvas)';
                  size(host, a.w, a.h);
                  Object.keys(a.tokens || {}).forEach(function (k) { host.style.setProperty(k, a.tokens[k]); });
                  document.body.appendChild(before); document.body.appendChild(host); document.body.appendChild(after);
                  var handle = create({ host: host, occurrence: { name: 'h990', display_name: a.display_name, description: a.description, config: a.config, columns: a.types } });
                  cur = { host: host, handle: handle, svg: host.querySelector('svg.dp-chart'), before: before, after: after };
                  return answer(handle.renderData(null, null, a.columns));
                },
                refresh: function (columns) { return answer(cur.handle.renderData(null, null, columns)); },
                resize: function (w, h) { var n = outputs; size(cur.host, w, h); return new Promise(function (r) { var f = 0; var wait = function () { if (outputs === n && ++f < 120) { requestAnimationFrame(wait); return; } settle().then(function () { r(outputs > n); }); }; requestAnimationFrame(wait); }); },
                redraw: function () { var n = outputs; cur.handle.resize(); return new Promise(function (r) { var f = 0; var wait = function () { if (outputs === n && ++f < 120) { requestAnimationFrame(wait); return; } settle().then(function () { r(outputs > n); }); }; requestAnimationFrame(wait); }); },
                token: function (name, value) { if (value === null) cur.host.style.removeProperty(name); else cur.host.style.setProperty(name, value); return true; },
                handle: function () { return cur.handle; },
                datums: function (id) { var ix = cur.handle.index(); var s = ix.series.filter(function (x) { return titled(x.id) === id; })[0]; return s ? s.datum : null; },
                svg: function () { return cur.svg; },
                listeners: function () { var svg = cur.svg; return live.filter(function (l) { return l.el === svg; }).length; },
                dispose: function () {
                  var svg = cur.svg;
                  cur.handle.dispose();
                  var out = { listeners: live.filter(function (l) { return l.el === svg; }).length, tooltips: svg.querySelectorAll('.dp-tooltip').length,
                    rings: svg.querySelectorAll('.dp-focus').length, inPage: document.contains(svg) };
                  cur.host.remove(); cur.before.remove(); cur.after.remove();
                  cur = null;
                  return out;
                },
                cuts: function () {
                  var prims = window.__h.last.primitives.filter(function (p) { return p.k === 'text'; });
                  var els = Array.from(cur.svg.querySelectorAll('text')).filter(function (t) { return !t.closest('g.dp-tooltip'); });
                  if (prims.length !== els.length) return { error: 'texts ' + els.length + ' != text primitives ' + prims.length };
                  var out = [];
                  prims.forEach(function (p, i) { if (p.shown !== null) out.push({ text: p.text, shown: p.shown, dom: els[i].textContent, role: p.role, box: rect(els[i]) }); });
                  var plain = [];
                  prims.forEach(function (p, i) { if (p.shown === null && p.text !== '') plain.push({ text: p.text, role: p.role, box: rect(els[i]) }); });
                  return { cuts: out, plain: plain };
                },
                width: function (t) {
                  var A = window.DatapipelinesDashboardChart._internal;
                  var theme = A.resolveTheme(cur.svg).theme;
                  return A.measurer(document).get(R.theme.resolve(theme).font)(t, R.theme.resolve(theme).n('--chart-text'), 400);
                },
                datum: function (heading) {
                  var ix = cur.handle.index();
                  for (var s = 0; s < ix.series.length; s++) for (var k = 0; k < ix.series[s].tip.length; k++) if (ix.series[s].tip[k][0] === heading) return { id: titled(ix.series[s].id), datum: ix.series[s].datum[k] };
                  return null;
                },
                point: function (id, datum) {
                  var I = window.DatapipelinesChartInteractions._internal;
                  var model = I.modelOf(R, window.__h.last, function () { return 0; });
                  var a = I.anchorOf(model, idOf(id), datum);
                  if (a === null) return null;
                  var m = cur.svg.getScreenCTM();
                  return { x: m.a * a.x + m.c * a.y + m.e, y: m.b * a.x + m.d * a.y + m.f };
                },
                read: function () {
                  var svg = cur.svg, ix = cur.handle.index(), out = window.__h.last;
                  var card = svg.querySelector('g.dp-tooltip');
                  var lines = card === null ? [] : Array.from(card.querySelectorAll('text')).map(function (t) {
                    return { text: t.textContent, anchor: t.getAttribute('text-anchor') || 'start', weight: parseFloat(t.getAttribute('font-weight') || '400'), fill: t.getAttribute('fill'), box: rect(t), y: parseFloat(t.getAttribute('y')) };
                  });
                  var surface = card === null ? null : card.querySelector('rect');
                  var ring = svg.querySelector('.dp-focus');
                  var entries = Array.from(svg.querySelectorAll('g.dp-legend[data-legend]')).map(function (g) {
                    return { id: titled(g.getAttribute('data-legend')), off: g.classList.contains('dp-off'), pressed: g.getAttribute('aria-pressed'), role: g.getAttribute('role'),
                      text: g.textContent, active: g.classList.contains('dp-active'), box: rect(g) };
                  });
                  var marks = Array.from(svg.querySelectorAll('g.dp-mark[data-series]')).map(function (g) {
                    var drawn = Array.from(g.children).filter(function (c) { if (typeof c.getBBox !== 'function') return false; var b = c.getBBox(); return b.width > 0 || b.height > 0; }).length;
                    return { id: titled(g.getAttribute('data-series')), drawn: drawn, active: g.classList.contains('dp-active'), fill: (g.firstElementChild || g).getAttribute('fill'),
                      stroke: (g.firstElementChild || g).getAttribute('stroke'), opacity: getComputedStyle(g).opacity, transition: getComputedStyle(g).transitionDuration };
                  });
                  var p = ix ? ix.plot : null;
                  var ticks = Array.from(svg.querySelectorAll('text.dp-tick')).map(function (t) { return { text: t.textContent, x: parseFloat(t.getAttribute('x')), y: parseFloat(t.getAttribute('y')) }; });
                  var values = p === null ? [] : ticks.filter(function (t) { return t.x < p.x; }).map(function (t) { return t.text; });
                  var tab = (svg.hasAttribute('tabindex') ? 1 : 0) + svg.querySelectorAll('[tabindex]').length;
                  var host = cur.host.querySelectorAll('[tabindex]').length + (cur.host.hasAttribute('tabindex') ? 1 : 0);
                  var ringOn = null;
                  if (ring !== null) {
                    var r = rect(ring);
                    var inRing = function (b) { var cx = b.x + b.w / 2, cy = b.y + b.h / 2; return cx >= r.x && cx <= r.x + r.w && cy >= r.y && cy <= r.y + r.h; };
                    entries.forEach(function (e) { if (ringOn === null && inRing(e.box)) ringOn = { kind: 'entry', id: e.id }; });
                  }
                  var plot = null;
                  if (p !== null) { var cm = svg.getScreenCTM(); plot = { x: cm.a * p.x + cm.e, y: cm.d * p.y + cm.f, w: cm.a * p.w, h: cm.d * p.h }; }
                  var place = entries.length === 0 ? 'none' : plot !== null && entries.every(function (e) { return e.box.y + e.box.h <= plot.y + 1; }) ? 'top' : 'lines';
                  return {
                    box: rect(svg), same: cur.host.querySelector('svg.dp-chart') === cur.svg, state: out ? out.state : null, plot: plot, place: place,
                    cards: svg.querySelectorAll('g.dp-tooltip').length,
                    card: card === null ? null : { box: rect(surface), lines: lines, rx: surface.getAttribute('rx'), fill: surface.getAttribute('fill'), stroke: surface.getAttribute('stroke'),
                      filter: getComputedStyle(card).filter, texts: lines.map(function (l) { return l.text; }) },
                    ring: ring === null ? null : rect(ring), ringOn: ringOn, entries: entries, marks: marks,
                    drawnMarks: marks.reduce(function (n, m) { return n + m.drawn; }, 0),
                    guides: { lines: svg.querySelectorAll('line.dp-guide').length, dots: Array.from(svg.querySelectorAll('circle.dp-guide')).map(function (c) { return c.getAttribute('data-series'); }) },
                    dimmed: svg.classList.contains('dp-dimmed'), hidden: cur.handle.hidden().map(titled), tabindex: tab, hostTabindex: host,
                    focused: document.activeElement === svg, values: values,
                    title: (svg.querySelector('title') || {}).textContent || null, desc: (svg.querySelector('desc') || {}).textContent || null, role: svg.getAttribute('role'),
                    summary: out ? out.summary.series.map(function (s) { return { id: s.title, hidden: s.hidden }; }) : [],
                    texts: Array.from(svg.querySelectorAll('text')).filter(function (t) { return !t.closest('g.dp-tooltip'); }).map(function (t) { return t.textContent; }),
                    scripts: document.querySelectorAll('svg.dp-chart script').length
                  };
                }
              };
              return true;
            }"""
    }
}
