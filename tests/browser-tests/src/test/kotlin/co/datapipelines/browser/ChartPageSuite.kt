package co.datapipelines.browser

import co.datapipelines.web.ui.JsRenderedAssetConfig
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.Page
import com.microsoft.playwright.Route

/**
 * #987 (js-rendered F3) — the shared ground of the page proofs of the chart engine (record §8 A4, A6, A8, A10; look
 * contract §10 LA4, LA6, LA11): a chart document is created through the REST route as a DRAFT with its one test case,
 * a session is started, and its capability preview is opened in a cookie-less context under the product's live policy
 * (the suite's collector). Everything asserted is read off the page: what the engine drew in `svg.dp-chart`, the
 * adapter's own resolver (`DatapipelinesDashboardChart._internal`) and the result of the engine's draw.
 *
 * **Falsification copies.** [copies] maps a served path's end (`/js/js-rendered/30-write-dom.js`) to a function of the
 * file's text; every context this suite opens serves the product's file through it. The product's map is empty. A
 * gate's plant is ONE entry in a subclass's override, taken out again by the inverse edit, so the plant is a copy of
 * the file as the page receives it and the product's file is never touched.
 */
abstract class ChartPageSuite : VisualizationBrowserSuite() {
    /** The falsification copies (above): empty in the product. */
    protected open val copies: Map<String, (String) -> String> = emptyMap()

    /** A cookie-less context at [width] × [height], each path of [copies] served through its function. */
    protected fun chartContext(
        width: Int = DEFAULT_WIDTH,
        height: Int = DEFAULT_HEIGHT,
    ): BrowserContext {
        val context = newBrowserContext(Browser.NewContextOptions().setViewportSize(width, height))
        servingCopies(context)
        return context
    }

    /** Serves each path of [copies] in [target] through its function (the signed-in context too, for the pages). */
    protected fun servingCopies(target: BrowserContext) {
        for ((suffix, rewrite) in copies) {
            target.route("**$suffix") { route ->
                val response = route.fetch()
                route.fulfill(Route.FulfillOptions().setResponse(response).setBody(rewrite(response.text())))
            }
        }
    }

    /**
     * The preview of [document] (a REST create body with one test case) in [context] at [theme], once its case is
     * mounted and the card's status is one of [states] (the runtime's `data-dp-state`; null: the caller waits itself).
     */
    protected fun preview(
        context: BrowserContext,
        document: String,
        theme: String = "light",
        states: List<String>? = listOf("success", "ready"),
    ): Page {
        val created = api("POST", "/api/v1/visualizations", document)
        val id = created.at("data", "id") as String
        val started = api("POST", "/api/v1/visualizations/$id/tests/sessions", "")
        val previewUrl = started.at("data", "preview_url") as String
        val preview = context.newPage()
        preview.setDefaultTimeout(PREVIEW_TIMEOUT_MS)
        preview.navigate("$baseUrl$previewUrl&theme=$theme")
        preview.waitForSelector("$CASE[data-dp-ready='true']")
        if (states != null) {
            preview.waitForFunction(
                "args => { const c = document.querySelector(args.sel + ' .dp-dashboard-status');" +
                    " return !!c && args.states.indexOf(c.getAttribute('data-dp-state')) >= 0; }",
                mapOf("sel" to CASE, "states" to states),
            )
        }
        preview.evaluate("() => document.fonts.ready.then(() => true)")
        return preview
    }

    /** The REST body of the chart fixture [fixture] at [name]. */
    protected fun documentOf(
        name: String,
        fixture: ChartBrowserFixtures.Fixture,
    ): String = ChartBrowserFixtures.document(name, fixture)

    /**
     * The engine's own hostile fixture (record A8: `<script>`, `onload=`, `&`, `"`, a 200-character title, a category
     * named `__proto__`), read from the input Node rendered (the engine module's emitted data), as a fixture of this
     * suite: its document (column types in the product's names, [PRODUCT_TYPES]; its category columns not nullable;
     * the keys the product's schema requires and the engine defaults filled), its rows, four bars. The page's drawing is compared with the page's
     * own engine result (G5), never with Node's, so the filled keys change nothing a test reads.
     */
    protected fun hostileFixture(): ChartBrowserFixtures.Fixture {
        val input = JsonParser.parseString(emitted("bar/hostile/960x540.input.json")).asJsonObject
        val document = input.getAsJsonObject("document")
        val columns = input.getAsJsonObject("columns")
        val count = columns.entrySet().maxOf { it.value.asJsonArray.size() }
        val rows =
            (0 until count).map { i ->
                JsonObject().apply { columns.entrySet().forEach { (field, values) -> add(field, values.asJsonArray[i]) } }
            }
        val spec = JsonObject()
        listOf("display_name", "renderer", "inputs", "config").forEach { spec.add(it, document.get(it).deepCopy()) }
        val encoding = spec.getAsJsonObject("config").getAsJsonObject("encoding")
        val categories = listOf("x", "color").map { encoding.getAsJsonObject(it).get("field").asString }.toSet()
        spec.getAsJsonObject("inputs").entrySet().forEach { (_, input) ->
            input.asJsonObject.getAsJsonArray("columns").forEach { element ->
                val column = element.asJsonObject
                val type = column.get("type").asString
                column.addProperty("type", PRODUCT_TYPES[type] ?: type)
                // The product's save rules refuse a nullable category column (`chart.category.nullable`); the
                // fixture's two category columns hold no null.
                if (column.get("name").asString in categories) column.addProperty("nullable", false)
            }
        }
        // The engine's document leaves out what its renderer defaults and the product's v1 schema requires (`sort` on
        // the two category channels, `stack` on the value): each is filled with the schema's plainest value.
        listOf("x", "color").forEach { channel ->
            encoding.getAsJsonObject(channel).run { if (!has("sort")) addProperty("sort", "ascending") }
        }
        encoding.getAsJsonObject("y").run { if (!has("stack")) add("stack", JsonNull.INSTANCE) }
        val expect = JsonObject().apply { addProperty("bars", HOSTILE_BARS) }
        return ChartBrowserFixtures.Fixture("hostile", spec, rows, expect)
    }

    /**
     * The grouped bar fixture over [series] channels (three regions each): one more than the engine's series cap (8,
     * O-15) is refused with `chart.too_many_series` (record A10).
     */
    protected fun seriesFixture(series: Int): ChartBrowserFixtures.Fixture {
        val grouped = ChartBrowserFixtures.fixture("bar-grouped")
        val rows =
            (1..series).flatMap { s ->
                REGIONS.map { region ->
                    JsonObject().apply {
                        addProperty("region", region)
                        addProperty("channel", "c$s")
                        addProperty("amount", s)
                    }
                }
            }
        val expect = JsonObject().apply { addProperty("bars", series * REGIONS.size) }
        return ChartBrowserFixtures.Fixture("series-$series", grouped.spec.deepCopy(), rows, expect)
    }

    /** One file of the engine module's emitted data (Node's inputs and results, `jsRenderedTestData`). */
    protected fun emitted(path: String): String =
        checkNotNull(ChartPageSuite::class.java.classLoader.getResourceAsStream("$EMITTED/$path")) {
            "no emitted file $path on the test classpath (the engine module's jsRenderedTestData)"
        }.use { it.readBytes().toString(Charsets.UTF_8) }

    protected companion object {
        const val CASE = "section[data-dp-case-index='0']"
        const val EMITTED = "co/datapipelines/jsrendered/emitted"
        const val DEFAULT_WIDTH = 1280
        const val DEFAULT_HEIGHT = 800
        const val PREVIEW_TIMEOUT_MS = 30_000.0
        const val HOSTILE_BARS = 4

        /** The animation frames [REDRAW] waits for the adapter's draw to reach the engine (about two seconds). */
        const val REDRAW_FRAME_LIMIT = 120
        val REGIONS = listOf("West", "North", "South")

        /**
         * The engine's hostile fixture names its number column `DOUBLE`, which a document's input contract refuses;
         * the product's name is `DECIMAL`. The page hands the engine the product's names, and the engine reads every
         * type but `BIGINTEGER` and `BIGDECIMAL` as a plain number (`09-data.js`), so the drawing is the same. A type
         * not in this map is the product's already, or the create refuses it and the test fails there, loudly.
         */
        val PRODUCT_TYPES = mapOf("DOUBLE" to "DECIMAL")

        /** The engine's script paths on the page, in load order. */
        val ENGINE_SCRIPTS: List<String> get() = JsRenderedAssetConfig.scripts

        /** The case's chart handle (the preview's registry, its one occurrence), as a JavaScript expression. */
        const val HANDLE =
            "(function () { var o = window.DatapipelinesPreview.instances[0]._occurrences; return o[Object.keys(o)[0]].renderer; })()"

        /**
         * One redraw of the case's chart through the adapter (`resize`, the next animation frame), the engine's result
         * of that draw captured by wrapping `JsRendered.render` for that one call, then the page settles one frame more.
         * Resolves to `window.__drawn` = the result (`{error}` when the engine threw: the adapter keeps its last picture).
         * A draw that never reaches `JsRendered.render` within [REDRAW_FRAME_LIMIT] frames (the adapter threw before the
         * call) resolves to `{error}` too, so the case goes red on its assertion instead of waiting forever.
         */
        const val REDRAW =
            """() => new Promise(function (resolve) {
              var R = window.JsRendered, original = R.render, last = null, frames = 0;
              R.render = function () {
                try { var out = original.apply(this, arguments); last = out; return out; } catch (e) { last = { error: String(e), primitives: [] }; throw e; }
              };
              $HANDLE.resize();
              var wait = function () {
                if (last === null && ++frames > $REDRAW_FRAME_LIMIT) last = { error: 'no draw reached the engine in $REDRAW_FRAME_LIMIT frames', primitives: [] };
                if (last === null) { requestAnimationFrame(wait); return; }
                requestAnimationFrame(function () { R.render = original; window.__drawn = last; resolve(true); });
              };
              requestAnimationFrame(wait);
            })"""
    }
}
