package co.datapipelines.browser

import co.datapipelines.jsrendered.JsRenderedEngineFiles
import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Frame
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.PlaywrightException
import com.microsoft.playwright.Route
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The look reference page (lane 991; design record 2026-11-14 §9 F7, look contract §10 LA9; brief 991 D8, D2a, D2b,
 * D11): the static page the task `jsRenderedReference` assembles, opened in a real Chromium at two screens (desktop
 * 1280 × 900, phone 390 × 844), in light and in dark.
 *
 * No application is booted: the page is no route (record O-11). The test serves ONLY the assembled directory
 * (`dp.reference.dir`, from the engine module's `jsRenderedReference` configuration) by request interception on one
 * origin of its own; a request off that directory is aborted and fails the test, and so does one for a file the
 * directory lacks. It listens itself for console errors, page errors, dialogs and policy violations from the first
 * request on (the shared collector fails on violations only and is not widened, D11).
 *
 * The oracle is each tile's `expect`, written by hand from the contract and the record (tiles/<type>.json), never the
 * engine; the levels' numbers are the record's §4.6, written here. Per tile (D8): the state; the DOM equals the
 * primitives; every text inside the frame and meeting no other with the real font (I1, I2); every colour, size,
 * width and radius one of the drawn theme's values or what §3.8 derives from them; then the legend, the end dots, the
 * value labels, the bars' rounded ends and gaps, and the card, each against the tile's expect.
 */
class LookReferencePageBrowserTest {
    /** The two screens the whole test runs at (brief D2b). */
    enum class Screen(
        val width: Int,
        val height: Int,
    ) {
        DESKTOP(1280, 900),
        PHONE(390, 844),
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Screen::class)
    fun `every tile draws what its expect says, in light and in dark, with a clean console`(screen: Screen) {
        open(screen).use { s ->
            s.clean("after loading")
            val counts = mutableMapOf<String, Int>()
            // Light, dark, and light again: the second light pass proves the switch back draws as the first (D2a).
            for ((pass, theme) in listOf("light", "dark", "light-again").map { it to it.removeSuffix("-again") }) {
                s.switchTo(theme)
                s.clean("after switching to $pass")
                s.cardsAre(theme)
                withClue("$screen: the page never scrolls sideways under $pass") { s.overflow() shouldBe 0 }
                val problems = everyTile(s, pass, counts)
                withClue("$screen $pass: tiles that differ from their expect") { problems.shouldBeEmpty() }
            }
            s.drag("bar:full-edge", listOf(300, 120, 520, 360))
            s.drag("line:compact-edge", listOf(150, 420, 200))
            s.clean("after a frame was dragged")
            println("LookReference $screen counts: ${counts.toSortedMap()}")
            floors(counts)
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Screen::class)
    fun `the card on every drawn tile - inside the picture, whole where it fits, one row at 80 x 24 and the keys walk the series`(
        screen: Screen,
    ) {
        open(screen).use { s ->
            var cards = 0
            var walks = 0
            for (theme in listOf("light", "dark")) {
                s.switchTo(theme)
                val (problems, opened, walked) = everyCard(s)
                cards += opened
                walks += walked
                withClue("$screen $theme: cards that differ from the contract's §4.4 (L8)") { problems.shouldBeEmpty() }
                s.clean("after the pointer and the keys under $theme")
            }
            println("LookReference $screen cards opened: $cards, key walks: $walks")
            withClue("non-vacuity: cards opened over both themes") { cards shouldBeGreaterThanOrEqual MIN_CARDS }
            withClue("non-vacuity: one key walk per type and theme") { walks shouldBe 6 }
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Screen::class)
    fun `forest stays in its frame and the dashboard row pads its chart card 16 and the others 8`(screen: Screen) {
        open(screen).use { s ->
            val forest = s.forestFrame()
            val expected = forestValues()

            @Suppress("UNCHECKED_CAST")
            val seen = forest.evaluate(LookReferenceChecks.FOREST_JS) as Map<String, Any?>
            println("LookReference $screen forest: $seen; expected from forest.css: $expected")
            withClue("the forest page's chart card") { seen["card"] shouldBe FOREST_CARD }
            withClue("the seven tokens printed beside their design-system tokens are forest's") { seen["tokens"] shouldBe expected }
            withClue("the seven tokens the forest tiles were drawn with are forest's") { seen["drawn"] shouldBe expected }
            withClue("every forest tile drawn with that theme") { seen["sameTheme"] shouldBe true }
            withClue("non-vacuity: the forest tiles") { (seen["tiles"] as Number).toInt() shouldBe 2 }
            val problems = mutableListOf<String>()
            @Suppress("UNCHECKED_CAST")
            for (key in forest.evaluate("DatapipelinesReference.keys()") as List<String>) {
                problems += s.check(forest, key, useExpect = true).problems
            }
            withClue("the forest tiles") { problems.shouldBeEmpty() }
            for (theme in listOf("light", "dark", "light")) {
                s.switchTo(theme)
                s.cardsAre(theme)
                @Suppress("UNCHECKED_CAST")
                val pads = s.page.evaluate(LookReferenceChecks.ROW_JS) as Map<String, String>
                withClue("$screen $theme: the row's paddings (chart, table, KPI)") {
                    pads shouldBe mapOf("chart" to "16px 16px 16px 16px", "table" to "8px 8px 8px 8px", "kpi" to "8px 8px 8px 8px")
                }
            }
            withClue("the forest card after the switches") {
                (forest.evaluate(LookReferenceChecks.FOREST_JS) as Map<*, *>)["card"] shouldBe
                    FOREST_CARD
            }
            s.clean("after the forest and row checks")
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Screen::class)
    fun `the hostile tile is text - no script element, no dialog, no violation`(screen: Screen) {
        open(screen).use { s ->
            for (theme in listOf("light", "dark")) {
                s.switchTo(theme)
                for (wire in listOf("bar", "line", "area", "scatter", "pie", "heatmap", "box", "waterfall", "funnel", "gauge")) {
                    @Suppress("UNCHECKED_CAST")
                    val r = s.page.evaluate(LookReferenceChecks.HOSTILE_JS, "$wire:hostile") as Map<String, Any?>
                    withClue("$screen $theme $wire hostile: $r") {
                        (r["scripts"] as Number).toInt() shouldBe 0
                        (r["foreign"] as Number).toInt() shouldBe 0
                        r["literal"] shouldBe true
                    }
                }
            }
            withClue("script elements in the document: exactly the generated list") {
                (s.page.evaluate("document.querySelectorAll('script').length") as Number).toInt() shouldBe
                    JsRenderedEngineFiles.names.size + 2
            }
            s.clean("after the hostile tiles")
        }
    }

    @Test
    fun `the page loads LOAD-ORDER line for line and the product's stylesheets in its order, each byte-equal`() {
        // The list is read before the page is asked to be ready: a page that loses a file never gets ready, and G1
        // is red on the list itself.
        open(Screen.DESKTOP, ready = false).use { s ->
            @Suppress("UNCHECKED_CAST")
            val scripts = s.page.evaluate("[...document.scripts].map(x => x.getAttribute('src'))") as List<String>
            withClue("G1: the script list is LOAD-ORDER.txt, then the layer, then the page's script") {
                scripts shouldContainExactly JsRenderedEngineFiles.names.map { "engine/$it" } + LAYER + "reference.js"
            }
            s.ready()
            JsRenderedEngineFiles.names.forEach { name ->
                withClue("engine/$name byte-equal to the engine's resource") {
                    Files.readString(dir.resolve("engine/$name")) shouldBe JsRenderedEngineFiles.sources.getValue(name)
                }
            }
            @Suppress("UNCHECKED_CAST")
            val links =
                s.page.evaluate(
                    "[...document.querySelectorAll('link[rel=stylesheet]')].map(x => x.getAttribute('href'))",
                ) as List<String>
            withClue(
                "D1a: the six of preview.html in its order, the page's own last",
            ) { links shouldContainExactly SHEETS + "reference.css" }
            (SHEETS + THEMES + LAYER).forEach { rel ->
                withClue("$rel byte-equal to the product's file") {
                    Files.readAllBytes(dir.resolve(rel)).contentEquals(productBytes(rel)) shouldBe true
                }
            }
            s.clean("after reading the assembly")
        }
    }

    /** Every tile of the page checked against its expect under the current theme; [counts] gathers per [pass]. */
    private fun everyTile(
        s: Session,
        pass: String,
        counts: MutableMap<String, Int>,
    ): List<String> {
        val problems = mutableListOf<String>()
        for (key in s.keys()) {
            val r = s.check(s.page.mainFrame(), key, useExpect = true)
            problems += r.problems
            counts.merge("$pass/${key.substringBefore(':')}", 1, Int::plus)
            r.counts.forEach { (k, v) -> counts.merge("$pass/$k", v, Int::plus) }
        }
        return problems
    }

    /** The card opened on every tile under the current theme, and the key walk on each 80 x 24 tile: problems, cards, walks. */
    private fun everyCard(s: Session): Triple<List<String>, Int, Int> {
        val problems = mutableListOf<String>()
        var cards = 0
        var walks = 0
        for (key in s.keys()) {
            val r = s.cardOf(key)
            problems += r.first
            cards += r.second
            if (key.endsWith(":minimal-edge")) {
                problems += s.walk(key)
                walks++
            }
        }
        return Triple(problems, cards, walks)
    }

    /** The floors of D8 (non-vacuity): per pass, every type's tiles, and the counts each check saw. */
    private fun floors(counts: Map<String, Int>) {
        for (theme in listOf("light", "dark", "light-again")) {
            withClue("non-vacuity under $theme: $counts") {
                (counts["$theme/bar"] ?: 0) shouldBeGreaterThanOrEqual MIN_TILES
                (counts["$theme/line"] ?: 0) shouldBeGreaterThanOrEqual MIN_TILES
                (counts["$theme/area"] ?: 0) shouldBeGreaterThanOrEqual MIN_TILES
                (counts["$theme/scatter"] ?: 0) shouldBeGreaterThanOrEqual MIN_SCATTER_TILES
                counts.getValue("$theme/gauge") shouldBeGreaterThanOrEqual MIN_GAUGE_TILES
                counts.getValue("$theme/arcs") shouldBeGreaterThanOrEqual MIN_ARCS
                counts.getValue("$theme/funnel") shouldBeGreaterThanOrEqual MIN_FUNNEL_TILES
                counts.getValue("$theme/stages") shouldBeGreaterThanOrEqual MIN_STAGES
                counts.getValue("$theme/waterfall") shouldBeGreaterThanOrEqual MIN_WATERFALL_TILES
                counts.getValue("$theme/waterfallBars") shouldBeGreaterThanOrEqual MIN_WATERFALL_BARS
                counts.getValue("$theme/box") shouldBeGreaterThanOrEqual MIN_BOX_TILES
                counts.getValue("$theme/boxPoints") shouldBeGreaterThanOrEqual MIN_BOX_POINTS
                counts.getValue("$theme/heatmap") shouldBeGreaterThanOrEqual MIN_HEATMAP_TILES
                counts.getValue("$theme/cells") shouldBeGreaterThanOrEqual MIN_CELLS
                counts.getValue("$theme/pie") shouldBeGreaterThanOrEqual MIN_PIE_TILES
                counts.getValue("$theme/slices") shouldBeGreaterThanOrEqual MIN_SLICES
                (counts["$theme/points"] ?: 0) shouldBeGreaterThanOrEqual MIN_POINTS
                (counts["$theme/texts"] ?: 0) shouldBeGreaterThanOrEqual MIN_TEXTS
                (counts["$theme/values"] ?: 0) shouldBeGreaterThanOrEqual MIN_VALUES
                (counts["$theme/elements"] ?: 0) shouldBeGreaterThanOrEqual MIN_ELEMENTS
                (counts["$theme/dots"] ?: 0) shouldBeGreaterThanOrEqual MIN_DOTS
                (counts["$theme/labels"] ?: 0) shouldBeGreaterThanOrEqual MIN_LABELS
                (counts["$theme/bars"] ?: 0) shouldBeGreaterThanOrEqual MIN_BARS
                (counts["$theme/legends"] ?: 0) shouldBeGreaterThanOrEqual MIN_LEGENDS
            }
        }
    }

    /** One opened page: the browser context, its own listeners, the assembled directory behind its route. */
    inner class Session(
        private val context: BrowserContext,
        val page: Page,
        private val errors: MutableList<String>,
        private val violations: MutableList<String>,
        private val off: MutableList<String>,
    ) : AutoCloseable {
        fun keys(): List<String> {
            @Suppress("UNCHECKED_CAST")
            return page.evaluate("DatapipelinesReference.keys()") as List<String>
        }

        /**
         * D11: 0 console errors, 0 page errors, 0 dialogs, 0 policy violations, 0 requests off or missing. The three
         * lists are compared in ONE assertion, so a red shows all three: a console error with the policy count at 0
         * (G8), and the file a missing request asked for (G7).
         */
        fun clean(point: String) {
            val seen = seen()
            withClue("$point: D11, every list empty") { seen shouldBe seen.mapValues { emptyList<String>() } }
        }

        private fun seen(): Map<String, List<String>> =
            mapOf(
                "console errors, page errors and dialogs" to errors.toList(),
                "policy violations" to violations.toList(),
                "requests off the directory or unanswered" to off.toList(),
            )

        /**
         * Waits for a condition the test reads with `evaluate`, polled from the test's side: Playwright's
         * `waitForFunction` polls with a compiled string inside the page, which the page's own policy (no
         * `unsafe-eval`) refuses whenever the first check is false. A condition never met fails naming D11's lists.
         * A check that meets a page between two documents (the one reload a cold font asks for) reads as not yet met.
         */
        fun until(
            what: String,
            condition: () -> Boolean,
        ) {
            val deadline = System.nanoTime() + WAIT_NANOS
            var lastError = ""
            while (true) {
                val met =
                    try {
                        condition()
                    } catch (e: PlaywrightException) {
                        lastError = "; the last check met: ${e.message?.lineSequence()?.firstOrNull()}"
                        false
                    }
                if (met) return
                if (System.nanoTime() > deadline) {
                    throw AssertionError("$what: not reached in ${WAIT_NANOS / NANOS_PER_SECOND} s$lastError; ${seen()}")
                }
                page.waitForTimeout(POLL_MILLIS)
            }
        }

        fun ready() {
            until("the page sets data-ready") { page.evaluate(READY_JS) == true }
            realFont(page.mainFrame(), "the reference page")
        }

        /**
         * D8's "with the real font": the product's faces are `font-display: optional`, so a loaded font can still be
         * unused for a whole document; I1 and I2 measured in the fallback would prove nothing about the real look.
         */
        private fun realFont(
            frame: Frame,
            where: String,
        ) {
            withClue("$where draws in the product's font, not the browser's fallback") { frame.evaluate(FONT_JS) shouldBe "real" }
        }

        fun switchTo(theme: String) {
            page.evaluate("t => DatapipelinesReference.setTheme(t)", theme)
            until("the switch to $theme") {
                page.evaluate(
                    "t => document.documentElement.dataset.theme === t && DatapipelinesReference.keys()" +
                        ".every(k => DatapipelinesReference.snapshot(k).theme === t)",
                    theme,
                ) == true
            }
        }

        /** D2a: every card of the reference page has the theme's card colour (forest never reaches it). */
        fun cardsAre(theme: String) {
            @Suppress("UNCHECKED_CAST")
            val colours = page.evaluate(LookReferenceChecks.CARDS_JS) as List<String>
            withClue("the reference page's cards under $theme") { colours shouldBe listOf(CARD.getValue(theme)) }
        }

        fun overflow(): Int =
            (page.evaluate("document.documentElement.scrollWidth - document.documentElement.clientWidth") as Number).toInt()

        fun check(
            frame: Frame,
            key: String,
            useExpect: Boolean,
        ): Checked {
            @Suppress("UNCHECKED_CAST")
            val r =
                frame.evaluate(
                    LookReferenceChecks.CHECK_JS,
                    mapOf("key" to key, "levels" to LEVELS, "useExpect" to useExpect),
                ) as Map<String, Any?>

            @Suppress("UNCHECKED_CAST")
            val counts = (r["counts"] as Map<String, Number>).mapValues { it.value.toInt() }
            @Suppress("UNCHECKED_CAST")
            return Checked(r["problems"] as List<String>, counts)
        }

        /** A fixed frame dragged to each width: still drawn by the rules (expect aside, the level by the numbers). */
        fun drag(
            key: String,
            widths: List<Int>,
        ) {
            val range = page.locator("input[data-tile='$key']")
            val start = range.inputValue()
            val problems = mutableListOf<String>()
            for (w in widths) {
                range.fill(w.toString())
                until("$key at $w px") { page.evaluate(FRAME_W_JS, listOf(key, w)) == true }
                problems += check(page.mainFrame(), key, useExpect = false).problems
            }
            range.fill(start)
            until("$key back at $start px") { page.evaluate(FRAME_W_JS, listOf(key, start.toInt())) == true }
            withClue("$key dragged through $widths") { problems.shouldBeEmpty() }
        }

        /** The card on one tile by the pointer (L8): (problems, 1 when a card was opened; 0 on a tile with no mark). */
        fun cardOf(key: String): Pair<List<String>, Int> {
            val figure = page.evaluate("k => DatapipelinesReference.snapshot(k).figure", key) as String
            val svg = page.locator("#$figure svg.dp-chart")
            svg.scrollIntoViewIfNeeded()
            @Suppress("UNCHECKED_CAST")
            val point = page.evaluate(LookReferenceChecks.POINT_JS, key) as Map<String, Any?>? ?: return Pair(emptyList(), 0)
            page.mouse().move((point["x"] as Number).toDouble(), (point["y"] as Number).toDouble())
            @Suppress("UNCHECKED_CAST")
            val card =
                page.evaluate(
                    LookReferenceChecks.CARD_JS,
                    mapOf("key" to key, "series" to point["series"], "j" to point["j"]),
                ) as Map<String, Any?>
            page.mouse().move(0.0, 0.0)
            svg.dispatchEvent("pointerleave")
            @Suppress("UNCHECKED_CAST")
            return Pair(card["problems"] as List<String>, 1)
        }

        /** O-10: on an 80 × 24 tile the arrow keys show each visible series' one-row card in turn (LA13). */
        fun walk(key: String): List<String> {
            @Suppress("UNCHECKED_CAST")
            val snap = page.evaluate("k => DatapipelinesReference.snapshot(k)", key) as Map<String, Any?>
            val svg = page.locator("#${snap["figure"]} svg.dp-chart")
            svg.focus()
            val shown = mutableListOf<List<String>>()

            @Suppress("UNCHECKED_CAST")
            val series = (snap["summary"] as Map<String, Any?>)["series"] as List<Map<String, Any?>>
            val visible = series.filter { it["hidden"] != true }.map { it["title"] as String }
            visible.indices.forEach { i ->
                page.keyboard().press(if (i == 0) "ArrowRight" else "ArrowDown")
                @Suppress("UNCHECKED_CAST")
                shown += page.evaluate(LookReferenceChecks.CARD_TEXTS_JS, snap["figure"]) as List<String>
            }
            page.keyboard().press("Escape")
            svg.blur()
            val problems = mutableListOf<String>()
            visible.forEachIndexed { i, name ->
                val head = shown[i].firstOrNull()?.removeSuffix("…") ?: ""
                if (head.isEmpty() || !name.startsWith(head)) problems += "$key: key step ${i + 1} shows ${shown[i]}, not the series $name"
                if (shown[i].size != 2) problems += "$key: key step ${i + 1} is not one row (name, value): ${shown[i]}"
            }
            return problems
        }

        fun forestFrame(): Frame {
            page.locator("iframe.ref-forest-frame").scrollIntoViewIfNeeded()
            var frame: Frame? = null
            until("the forest frame sets data-ready") {
                frame = page.frames().firstOrNull { it.url().endsWith("/forest.html") }
                frame?.evaluate(READY_JS) == true
            }
            return requireNotNull(frame) { "no forest frame" }.also { realFont(it, "the forest page") }
        }

        override fun close() = context.close()
    }

    data class Checked(
        val problems: List<String>,
        val counts: Map<String, Int>,
    )

    private fun open(
        screen: Screen,
        ready: Boolean = true,
    ): Session {
        val errors = CopyOnWriteArrayList<String>()
        val violations = CopyOnWriteArrayList<String>()
        val off = CopyOnWriteArrayList<String>()
        val context = browser.newContext(Browser.NewContextOptions().setViewportSize(screen.width, screen.height))
        context.route("**/*") { route -> serve(route, off) }
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
        page.navigate("$ORIGIN/index.html")
        val session = Session(context, page, errors, violations, off)
        if (ready) session.ready()
        return session
    }

    private fun serve(
        route: Route,
        off: MutableList<String>,
    ) {
        val url = route.request().url()
        if (!url.startsWith("$ORIGIN/")) {
            off += "off the directory: $url"
            route.abort()
            return
        }
        val rel = url.removePrefix("$ORIGIN/").substringBefore('?').substringBefore('#')
        val file = dir.resolve(rel).normalize()
        if (!file.startsWith(dir) || !Files.isRegularFile(file)) {
            off += "unanswered: $rel"
            route.fulfill(Route.FulfillOptions().setStatus(NOT_FOUND).setBody(""))
            return
        }
        route.fulfill(
            Route
                .FulfillOptions()
                .setStatus(OK)
                .setBodyBytes(Files.readAllBytes(file))
                .setContentType(typeOf(rel)),
        )
    }

    private fun forestValues(): Map<String, String> {
        val css = String(productBytes("vendor/design-system/themes/forest.css"))

        fun private(name: String): String =
            Regex("""--_$name:\s*(#[0-9a-fA-F]{6})""")
                .find(css)
                ?.groupValues
                ?.get(1)
                ?.lowercase()
                ?: error("forest.css declares no --_$name")
        return FOLLOWING.associate { (chart, ds) -> chart to private(ds.removePrefix("--")) }
    }

    companion object {
        private const val ORIGIN = "https://reference.test"
        private const val WAIT_NANOS = 30_000_000_000L
        private const val NANOS_PER_SECOND = 1_000_000_000L
        private const val POLL_MILLIS = 50.0
        private const val READY_JS = "() => document.body.dataset.ready === 'true'"
        private const val FONT_JS = "() => document.body.dataset.font"
        private const val FRAME_W_JS = "([k, w]) => DatapipelinesReference.snapshot(k).frame.w === w"
        private const val OK = 200
        private const val NOT_FOUND = 404
        private const val LAYER = "js/datapipelines-chart-interactions.js"
        private const val FOREST_CARD = "rgb(250, 248, 243)"

        // Floors per pass (D8 non-vacuity), set under what one pass measured at either screen (2026-11-16: texts
        // 920/936, values 6058/6120, elements 2926/2946, dots 118, labels 164, bars 257, legends 158; 61 cards).
        private const val MIN_TILES = 23

        /** Brief 1068: pie's seven tiles and the 29 sectors drawn across them. */
        private const val MIN_PIE_TILES = 7
        private const val MIN_SLICES = 29

        /** Brief 1062: scatter's seven tiles, and the 53 points of its five tiles that draw points (12 + 8 + 12 + 12 + 9). */
        private const val MIN_SCATTER_TILES = 7
        private const val MIN_POINTS = 53

        /** Brief 1075: eight gauge tiles, seven rendered arcs under each theme. */
        private const val MIN_GAUGE_TILES = 8
        private const val MIN_ARCS = 7

        /** Brief 1074: twelve tiles, 43 stages across the nine rendered tiles under each theme. */
        private const val MIN_FUNNEL_TILES = 12
        private const val MIN_STAGES = 43
        private const val MIN_WATERFALL_TILES = 9
        private const val MIN_WATERFALL_BARS = 83
        private const val MIN_BOX_TILES = 10
        private const val MIN_BOX_POINTS = 13

        /** Brief 1070: nine heatmap tiles, 145 cells across the seven rendered tiles. */
        private const val MIN_HEATMAP_TILES = 8
        private const val MIN_CELLS = 140
        private const val MIN_TEXTS = 850
        private const val MIN_VALUES = 5500
        private const val MIN_ELEMENTS = 2600
        private const val MIN_DOTS = 110
        private const val MIN_LABELS = 150
        private const val MIN_BARS = 240
        private const val MIN_LEGENDS = 150
        private const val MIN_CARDS = 120

        /** The record's §4.6 levels, each from its lower edge: written here from the record, never read from the engine (D5). */
        private val LEVELS = listOf(listOf("full", 360, 240), listOf("compact", 200, 140), listOf("minimal", 80, 24))

        /** The card's surface under each theme of the reference page (light.css, dark.css: `--_surface-default`). */
        private val CARD = mapOf("light" to "rgb(255, 255, 255)", "dark" to "rgb(23, 27, 33)")

        private val SHEETS =
            listOf(
                "vendor/design-system/tokens.css",
                "vendor/design-system/themes/light.css",
                "vendor/design-system/base.css",
                "vendor/design-system/primitives.css",
                "css/app.css",
                "css/dashboards.css",
            )
        private val THEMES = listOf("vendor/design-system/themes/dark.css", "vendor/design-system/themes/forest.css")

        /** The seven chart tokens that follow the theme and the design-system token each one is (contract §5.1). */
        private val FOLLOWING =
            listOf(
                "--chart-canvas" to "--surface-default",
                "--chart-ink" to "--text-primary",
                "--chart-ink-2" to "--text-secondary",
                "--chart-muted" to "--text-muted",
                "--chart-axis" to "--border-subtle",
                "--chart-tip-surface" to "--surface-raised",
                "--chart-tip-ink" to "--text-primary",
            )

        private val dir: Path by lazy {
            val p = System.getProperty("dp.reference.dir") ?: error("dp.reference.dir is not set: the test reads the assembled page")
            Paths
                .get(
                    p,
                ).toAbsolutePath()
                .normalize()
                .also { require(Files.isRegularFile(it.resolve("index.html"))) { "no index.html in $it" } }
        }

        private fun productBytes(rel: String): ByteArray =
            checkNotNull(LookReferencePageBrowserTest::class.java.classLoader.getResourceAsStream("static/$rel")) {
                "the product's static/$rel is not on the test classpath"
            }.use { it.readBytes() }

        private fun typeOf(rel: String): String =
            when (rel.substringAfterLast('.')) {
                "html" -> "text/html; charset=utf-8"
                "js" -> "text/javascript; charset=utf-8"
                "css" -> "text/css; charset=utf-8"
                "json" -> "application/json"
                "png" -> "image/png"
                "woff2" -> "font/woff2"
                else -> "application/octet-stream"
            }

        private var playwright: Playwright? = null
        private lateinit var browser: Browser

        @JvmStatic
        @BeforeAll
        fun launch() {
            val pw = Playwright.create().also { playwright = it }
            browser = pw.chromium().launch(BrowserType.LaunchOptions().setHeadless(true))
        }

        @JvmStatic
        @AfterAll
        fun shut() {
            if (::browser.isInitialized) browser.close()
            playwright?.close()
            playwright = null
        }
    }
}

/**
 * The in-page checks of [LookReferencePageBrowserTest], each an arrow function evaluated in the page (or the forest
 * frame) with Playwright. They read the drawn DOM and the page's read-only snapshot of each draw, and compare both with
 * the tile's hand-written `expect` and the look contract's rules; the engine's own layout code is never called.
 */
private object LookReferenceChecks {
    private val HELPERS_BODY =
        """
        const R = window.JsRendered;
        const look = R.tables.look;
        const ASC = look("textAscent");
        const DESC = look("textDescent");
        const TOL = 0.5;
        const EPS = 0.02;
        const near = (x, y, tol) => Math.abs(x - y) <= (tol === undefined ? EPS : tol);
        const themeName = (s) => (typeof s.theme === "string" ? s.theme : "forest");
        const boxOf = (p) => {
          if (p.k === "rect") return { x: p.x, y: p.y, w: p.w, h: p.h };
          const xs = [];
          const ys = [];
          for (let i = 0; i < p.d.length; ) {
            const c = p.d[i];
            const n = R.prim.ARITY[c];
            const a = p.d.slice(i + 1, i + 1 + n);
            if (c === "A") { xs.push(a[1]); ys.push(a[2]); }
            else for (let j = 0; j + 1 < a.length; j += 2) { xs.push(a[j]); ys.push(a[j + 1]); }
            i += 1 + n;
          }
          const x = Math.min(...xs);
          const y = Math.min(...ys);
          return { x: x, y: y, w: Math.max(...xs) - x, h: Math.max(...ys) - y };
        };
        const lastPoint = (p) => {
          let last = null;
          for (let i = 0; i < p.d.length; ) {
            const c = p.d[i];
            const n = R.prim.ARITY[c];
            const a = p.d.slice(i + 1, i + 1 + n);
            if (n >= 2) last = [a[n - 2], a[n - 1]];
            i += 1 + n;
          }
          return last;
        };
        const arcs = (p) => p.k === "path" && p.d.indexOf("A") >= 0;
        /** A value label in the record's one format, short form included: "2.8K", "−5", "+42", "1,200", "12%". */
        const parseShort = (text) => {
          const m = /^([+\-−]?)([\d,]*\.?\d+)([KMBT]?)%?$/.exec(String(text).trim());
          if (!m) return null;
          const digits = m[2].replace(/,/g, "");
          const decimals = digits.indexOf(".") < 0 ? 0 : digits.length - digits.indexOf(".") - 1;
          const mult = { "": 1, K: 1e3, M: 1e6, B: 1e9, T: 1e12 }[m[3]];
          const sign = m[1] === "" || m[1] === "+" ? 1 : -1;
          return { v: sign * Number(digits) * mult, tol: 0.5 * Math.pow(10, -decimals) * mult + 1e-9 };
        };
        const close = (u, label) => Math.abs(u - label.v) <= label.tol;
        /** The real font's box of a written text: its advance by the browser, its height by the look table's ascent and descent. */
        const textBox = (el) => {
          const size = Number(el.getAttribute("font-size"));
          const w = el.getComputedTextLength();
          const x = Number(el.getAttribute("x"));
          const y = Number(el.getAttribute("y"));
          const anchor = el.getAttribute("text-anchor") || "start";
          const x0 = anchor === "middle" ? x - w / 2 : anchor === "end" ? x - w : x;
          // The text's own coordinates carried into the chart's: the browser's matrix from the text to the screen,
          // composed with the inverse of the chart's (a rotated tick and any offset group are both in it).
          const toChart = el.ownerSVGElement.getScreenCTM().inverse().multiply(el.getScreenCTM());
          const pts = [[x0, y - ASC * size], [x0 + w, y - ASC * size], [x0 + w, y + DESC * size], [x0, y + DESC * size]]
            .map((q) => [toChart.a * q[0] + toChart.c * q[1] + toChart.e, toChart.b * q[0] + toChart.d * q[1] + toChart.f]);
          const xs = pts.map((q) => q[0]);
          const ys = pts.map((q) => q[1]);
          return { pts: pts, x0: Math.min(...xs), x1: Math.max(...xs), y0: Math.min(...ys), y1: Math.max(...ys), text: el.textContent };
        };
        /** Two convex quadrilaterals overlap by more than TOL (separating axes). */
        const overlap = (a, b) => {
          const axes = [];
          [a.pts, b.pts].forEach((p) => {
            for (let i = 0; i < 4; i++) {
              const q = p[i];
              const r = p[(i + 1) % 4];
              const len = Math.hypot(r[0] - q[0], r[1] - q[1]) || 1;
              axes.push([-(r[1] - q[1]) / len, (r[0] - q[0]) / len]);
            }
          });
          return axes.every((ax) => {
            const pa = a.pts.map((q) => q[0] * ax[0] + q[1] * ax[1]);
            const pb = b.pts.map((q) => q[0] * ax[0] + q[1] * ax[1]);
            return Math.min(Math.max(...pa), Math.max(...pb)) - Math.max(Math.min(...pa), Math.min(...pb)) > TOL;
          });
        };
        const round = (b) => [b.x0, b.y0, b.x1, b.y1].map((v) => Math.round(v * 100) / 100).join(",");
        const svgOf = (s) => document.getElementById(s.figure).querySelector("svg.dp-chart");
        """.trimIndent()

    private val CHECK_BODY =
        """
        const s = DatapipelinesReference.snapshot(a.key);
        const problems = [];
        const counts = { texts: 0, values: 0, elements: 0, dots: 0, arcs: 0, points: 0, stages: 0, waterfallBars: 0, boxPoints: 0, cells: 0, slices: 0, labels: 0, bars: 0, legends: 0 };
        const bad = (m) => problems.push(a.key + " (" + themeName(s) + ", " + s.frame.w + " x " + s.frame.h + "): " + m);
        const t = R.theme.resolve(s.theme);
        const f = s.frame;
        const lv = a.levels.find((l) => f.w >= l[1] && f.h >= l[2]);
        const byNumbers = lv ? lv[0] : "too-small";
        let e;
        if (!a.useExpect) e = byNumbers === "too-small" ? { state: "too-small" } : { state: "rendered", level: byNumbers };
        else e = s.fluid ? s.expect[byNumbers] : s.expect;
        if (!e) {
          bad("no expect for the level " + byNumbers);
          return { problems: problems, counts: counts };
        }
        // --- the state, and the level by the record's numbers
        if (s.state !== e.state) bad("state " + s.state + ", the expect says " + e.state);
        if (s.state === "rendered" && s.level !== byNumbers) bad("level " + s.level + "; the record's numbers give " + byNumbers);
        if (s.state === "rendered" && e.level && s.level !== e.level) bad("level " + s.level + ", the expect says " + e.level);
        const svg = svgOf(s);
        const drawn = s.state === "rendered" || s.state === "no-data";
        const prims = drawn ? s.primitives : [];
        // --- the DOM equals the primitives (the writer's order; description and gradients are not primitives)
        const els = [];
        (function walk(n) {
          for (let c = n.firstElementChild; c; c = c.nextElementSibling) {
            const tag = c.localName;
            if (tag === "title" || tag === "desc" || tag === "linearGradient" || tag === "defs") continue;
            els.push(c);
            walk(c);
          }
        })(svg);
        if (els.length !== prims.length) bad("the DOM has " + els.length + " elements, the primitives " + prims.length);
        const TAG = { rect: "rect", line: "line", path: "path", circle: "circle", text: "text", group: "g" };
        for (let i = 0; i < Math.min(els.length, prims.length); i++) {
          const p = prims[i];
          const el = els[i];
          if (el.localName !== TAG[p.k]) {
            bad("element " + i + " is a " + el.localName + " where the primitive is a " + p.k);
            break;
          }
          const cls = (el.getAttribute("class") || "").split(" ");
          if (p.role && cls.indexOf("dp-" + p.role) < 0) bad("element " + i + " lacks the class dp-" + p.role);
          if (p.k === "group" && p.on === false && cls.indexOf("dp-off") < 0) bad("the switched-off entry " + p.label + " lacks dp-off");
          const num = (n) => Number(el.getAttribute(n));
          if (p.k === "rect" && !(near(num("x"), p.x) && near(num("y"), p.y) && near(num("width"), p.w) && near(num("height"), p.h))) bad("rect " + i + " is not at its primitive's box");
          if (p.k === "line" && !(near(num("x1"), p.x1) && near(num("y1"), p.y1) && near(num("x2"), p.x2) && near(num("y2"), p.y2))) bad("line " + i + " is not where its primitive is");
          if (p.k === "circle" && !(near(num("cx"), p.cx) && near(num("cy"), p.cy) && near(num("r"), p.r))) bad("circle " + i + " is not where its primitive is");
          if (p.k === "text") {
            const want = p.shown === null || p.shown === undefined ? p.text : p.shown;
            if (el.textContent !== want) bad("text " + i + " reads '" + el.textContent + "', its primitive '" + want + "'");
            if (!(near(num("x"), p.x) && near(num("y"), p.y))) bad("text " + i + " is not where its primitive is");
          }
          if (p.k !== "group" && p.k !== "line" && p.fill !== undefined) {
            const got = el.getAttribute("fill");
            const want = p.fill === null ? "none" : p.color;
            if (got !== want && !(p.fade && got && got.indexOf("url(#") === 0)) bad(p.k + " " + i + " fill " + got + ", its primitive " + want);
          }
          if (p.stroke && el.getAttribute("stroke") !== p.strokeColor) bad(p.k + " " + i + " stroke " + el.getAttribute("stroke") + ", its primitive " + p.strokeColor);
        }
        // --- only the drawn theme's values (D8, G3): its colours, sizes, widths, radii and shares, and what §3.8 derives
        const colours = new Set(["none"]);
        Object.keys(R.theme.TOKENS).forEach((k) => {
          const kind = R.theme.TOKENS[k][0];
          if (kind === "colour" || kind === "opaque") colours.add(t.css(k));
        });
        const sizes = [t.n("--chart-text"), t.n("--chart-text-small"), t.n("--chart-title")];
        const weights = [t.n("--chart-title-weight")];
        // §3.8 "spacing at rest": the scatter's ring is 0.75 × --chart-gap; with a size channel, a point's radius is the
        // document's (§2 --chart-point, the record's §4.4: at most half the range's largest diameter, default 40).
        const scatter = s.wire === "scatter";
        const gauge = s.wire === "gauge";
        const track = gauge ? prims.find(p => p.k === "path" && p.role === "grid") : null;
        const gaugeRadius = track ? track.d[4] : 0;
        const gaugeWidth = track ? track.strokeWidth : 0;
        const funnel = s.wire === "funnel";
        const waterfall = s.wire === "waterfall";
        // Box outside points use their own at-rest radius, ring and opacity (brief 1072 D17).
        const isBox = s.wire === "box";
        const heatmap = s.wire === "heatmap";
        const pie = s.wire === "pie";
        const sizeEnc = scatter ? s.document.config.encoding.size || null : null;
        const widths = [t.n("--chart-line"), t.n("--chart-stroke"), t.n("--chart-gap")].concat(scatter ? [0.75 * t.n("--chart-gap")] : []).concat(isBox ? [R.tables.look("boxOutsideRing") * t.n("--chart-gap")] : []);
        const radius = t.n("--chart-bar-radius");
        const has = (list, v) => list.some((x) => Math.abs(x - v) < 1e-6);
        svg.querySelectorAll("*").forEach((el) => {
          if (el.closest(".dp-tooltip")) return;
          counts.elements++;
          for (const at of Array.from(el.attributes)) {
            const v = at.value;
            let ok = true;
            let checked = true;
            switch (at.name) {
              case "fill":
              case "stroke":
              case "stop-color":
                ok = colours.has(v) || (/^url\(#[\w-]+\)$/.test(v) && document.getElementById(v.slice(5, -1)) !== null);
                break;
              case "font-size": ok = has(sizes, Number(v)) || (gauge && Number(v) >= 1.5*t.n("--chart-text") && Number(v) <= 2.5*t.n("--chart-text")); break;
              case "font-weight": ok = has(weights, Number(v)); break;
              case "font-family": ok = v.replace(/["']/g, "").split(",")[0].trim() === t.font; break;
              case "stroke-width": ok = has(widths, Number(v)) || (gauge && has([R.tables.look("gaugeRingWidth"), R.tables.look("gaugeTargetBack"), gaugeWidth], Number(v))); break;
              case "opacity": ok = has([t.n("--chart-dim")], Number(v)) || (isBox && el.localName === "circle" && has([R.tables.look("boxOutsideOpacity")], Number(v))); break;
              case "stop-opacity": ok = has([t.n("--chart-area-top"), t.n("--chart-area-bottom")], Number(v)); break;
              case "r": ok = has([t.n("--chart-point")], Number(v)) || (isBox && has([Math.max(0, t.n("--chart-point") - R.tables.look("boxOutsideLess"))], Number(v))) || (sizeEnc !== null && Number(v) > 0 && Number(v) <= ((sizeEnc.range || [6, 40])[1]) / 2 + 1e-6); break;
              case "rx":
              case "ry": {
                // Heatmap corners use the cell token, capped by half either side.
                const box = el.getBBox();
                const half = Math.abs(2 * Number(v) - box.width) < 0.05 || Math.abs(2 * Number(v) - box.height) < 0.05;
                ok = has([radius], Number(v)) || (heatmap && (has([t.n("--chart-cell-radius")], Number(v)) || half)) || (funnel && Number(v) < radius && half);
                break;
              }
              case "d": {
                const re = /A\s*([\d.]+)[\s,]+([\d.]+)/g;
                let m;
                const box = el.getBBox();
                while ((m = re.exec(v)) !== null) {
                  // §3.8: the data end's radius is --chart-bar-radius, never more than half the bar's width (its band side)
                  const r = Number(m[1]);
                  const half = Math.abs(2 * r - box.width) < 0.05 || Math.abs(2 * r - box.height) < 0.05
                    || (waterfall && Math.abs(r - box.height) < 0.05);
                  const sectorRadius = pie && (s.index.marks || []).some((entry) => entry.shape.kind === "sector" && Math.abs(entry.shape.r1 - r) < 0.05);
                  const gaugeArc = gauge && [gaugeRadius, gaugeRadius + gaugeWidth/2 + R.tables.look("gaugeRingOffset")].some(v => Math.abs(v-r) < 0.05);
                  if (!(gaugeArc || sectorRadius || (r <= radius + 1e-6 && (has([radius], r) || half)))) ok = false;
                  counts.values++;
                }
                checked = false;
                break;
              }
              default: checked = false;
            }
            if (checked) counts.values++;
            if (!ok) bad("a value outside the drawn theme's set: " + el.localName + " " + at.name + "=" + v);
          }
        });
        // --- I1, I2 with the real font: every text inside the frame, no two meeting
        const boxes = [];
        svg.querySelectorAll("text").forEach((el) => {
          if (el.closest(".dp-tooltip")) return;
          const b = textBox(el);
          counts.texts++;
          if (b.x0 < -TOL || b.y0 < -TOL || b.x1 > f.w + TOL || b.y1 > f.h + TOL) bad("I1: the text '" + b.text + "' leaves the frame: " + round(b));
          boxes.push(b);
        });
        for (let i = 0; i < boxes.length; i++) for (let j = i + 1; j < boxes.length; j++) if (overlap(boxes[i], boxes[j])) bad("I2: '" + boxes[i].text + "' " + round(boxes[i]) + " meets '" + boxes[j].text + "' " + round(boxes[j]));
        const of = (role, k) => prims.filter((p) => p.role === role && (k === undefined || p.k === k));
        const marks = of("mark").filter((p) => p.k !== "group");
        const legend = of("legend").filter((p) => p.k !== "group");
        const labels = of("guide", "text");
        const dots = scatter || isBox ? [] : of("mark", "circle");
        const points = scatter || isBox ? of("mark", "circle") : [];
        counts.dots += dots.length;
        counts.points += points.length;
        const gaugeArcs = gauge ? of("mark", "path") : [];
        counts.arcs += gaugeArcs.length;
        if (waterfall) counts.waterfallBars += marks.length;
        if (isBox) counts.boxPoints += points.length;
        const slices = pie ? (s.index.marks || []).filter((entry) => entry.shape.kind === "sector") : [];
        const cells = heatmap ? marks.filter((p) => p.k === "rect") : [];
        const stages = funnel ? of("mark", "rect") : [];
        counts.stages += stages.length;
        counts.cells += cells.length;
        counts.slices += slices.length;
        counts.labels += labels.length;
        counts.legends += legend.filter((p) => p.k === "text").length;
        // --- by state
        if (s.state !== "rendered") {
          if (marks.length + legend.length + labels.length + dots.length > 0) bad("a " + s.state + " tile draws marks, a legend or labels");
          if (s.state === "no-data") {
            const empty = of("empty", "text");
            if (empty.length !== 1 || empty[0].text !== "No data") bad("the empty text is not 'No data' (D7)");
          } else if (els.length > 0) bad("a " + s.state + " tile draws " + els.length + " elements");
          if (s.state === "refused") {
            if (!s.summary.refusal || s.summary.refusal.code !== e.code) bad("refusal " + JSON.stringify(s.summary.refusal) + ", the expect says " + e.code);
            if (!s.message || s.message !== s.sentence) bad("the card does not show the code's sentence: '" + s.message + "'");
          }
          if (s.state === "too-small") {
            if (!s.summary.warnings.some((w) => w.code === "chart.frame-too-small")) bad("no warning chart.frame-too-small");
            if (s.message.indexOf("chart.frame-too-small") < 0) bad("the card does not say chart.frame-too-small");
          }
          return { problems: problems, counts: counts };
        }
        if (s.message !== "") bad("a rendered tile's card carries a message: " + s.message);
        if (!a.useExpect) return { problems: problems, counts: counts };
        // --- the legend (contract §4.2, §4.5)
        const plot = s.index.plot;
        const visible = s.index.series.filter((x) => x.on).length;
        const topOf = (p) => (p.k === "text" ? p.y - ASC * p.size : p.y);
        const bottomOf = (p) => (p.k === "text" ? p.y + DESC * p.size : p.y + p.h);
        if (e.legend === "none" && legend.length > 0) bad("a legend is drawn; the expect says none");
        if (e.legend === "lines") {
          const groups = of("legend", "group");
          if (groups.length !== visible) bad(groups.length + " names on the lines for " + visible + " drawn series");
          if (groups.some((g) => g.n !== 1)) bad("a name on the lines carries a key");
          if (legend.some((p) => p.k !== "text" || p.x < plot.x + plot.w - TOL)) bad("a name is not after the line's end");
        }
        if (e.legend === "top" && (legend.length === 0 || legend.some((p) => bottomOf(p) > plot.y + TOL))) bad("the legend is not above the plot");
        if (e.legend === "bottom" && (legend.length === 0 || legend.some((p) => topOf(p) < plot.y + plot.h - TOL))) bad("the legend is not below the plot");
        if (s.level === "compact" && legend.length > 0) {
          const ys = legend.filter((p) => p.k === "text").map((p) => p.y);
          if (Math.max(...ys) - Math.min(...ys) > EPS) bad("the compact legend is not one row");
        }
        const struck = legend.filter((p) => p.k === "text" && p.struck).map((p) => p.series).sort();
        if (JSON.stringify(struck) !== JSON.stringify((e.struck || []).slice().sort())) bad("struck " + JSON.stringify(struck) + ", the expect says " + JSON.stringify(e.struck));
        svg.querySelectorAll("text.dp-legend").forEach((el) => {
          const isStruck = el.getAttribute("text-decoration") === "line-through";
          if (isStruck !== struck.indexOf(el.closest("g.dp-legend") ? el.closest("g.dp-legend").getAttribute("data-series") : "") >= 0) bad("the entry '" + el.textContent + "' is struck in the DOM against its primitive");
        });
        // --- the end dot (contract §2 points): one per drawn line, on its last point
        if (e.dots !== undefined && dots.length !== e.dots) bad(dots.length + " end dots, the expect says " + e.dots);
        // --- scatter: one point per row drawn (brief 1062 D3), each inside the plot (D5, #1066's pad)
        if (pie && e.slices !== undefined && slices.length !== e.slices) bad(slices.length + " slices, the expect says " + e.slices);
        if (gauge && e.arcs !== undefined && gaugeArcs.length !== e.arcs) bad("gauge arc count differs from expect");
        if ((scatter || isBox) && e.points !== undefined && points.length !== e.points) bad(points.length + " points, the expect says " + e.points);
        if (funnel) {
          if (stages.length !== s.summary.marks) bad("funnel count differs from summary.marks");
          if (of("mark", "circle").length) bad("funnel draws a circle");
          stages.forEach((p, i) => {
            if (Math.abs(p.x + p.w / 2 - stages[0].x - stages[0].w / 2) > 0.02) bad("funnel centres differ");
            if (p.x < plot.x - TOL || p.y < plot.y - TOL || p.x + p.w > plot.x + plot.w + TOL || p.y + p.h > plot.y + plot.h + TOL) bad("funnel stage crosses the plot edge");
            if (p.w < Math.min(6, plot.w) - 0.02) bad("funnel stage below least width");
            if (i && p.y < stages[i - 1].y + stages[i - 1].h - 0.02) bad("funnel stages overlap");
          });
        }
        if (heatmap) {
          if (e.cells !== cells.length) bad(cells.length + " cells, the expect says " + e.cells);
          if (s.summary.marks !== cells.length) bad("heatmap count differs from summary.marks");
          cells.forEach((p) => {
            if (p.x < plot.x - TOL || p.y < plot.y - TOL || p.x + p.w > plot.x + plot.w + TOL || p.y + p.h > plot.y + plot.h + TOL) bad("heatmap cell crosses the plot edge");
          });
        }
        points.forEach((p) => {
          if (p.cx - p.r < plot.x - TOL || p.cx + p.r > plot.x + plot.w + TOL || p.cy - p.r < plot.y - TOL || p.cy + p.r > plot.y + plot.h + TOL) bad("the point " + p.series + "/" + p.datum + " crosses the plot's edge");
        });
        // Box points remain within their box column; the plot check above remains shared and unchanged.
        if (isBox) points.forEach((p) => {
          const entry = s.index.marks.find((m) => String(m.datum) === String(p.datum).split("/")[0]);
          if (!entry || p.cx - p.r < entry.shape.x - TOL || p.cx + p.r > entry.shape.x + entry.shape.w + TOL) bad("box outside point crosses its column");
        });
        dots.forEach((d) => {
          const line = marks.find((p) => p.k === "path" && p.series === d.series && p.stroke && p.fill === null);
          const last = line ? lastPoint(line) : null;
          if (!last || !near(last[0], d.cx) || !near(last[1], d.cy)) bad("the end dot of " + d.series + " is not on its line's last point");
        });
        // --- value labels (contract §4.3, §4.5)
        if (e.labels === "none" && labels.length > 0) bad(labels.length + " value labels; the expect says none");
        if (e.labels === "bars" || e.labels === "totals") {
          const enc = s.document.config.encoding;
          const horizontal = enc.y.type === "nominal";
          const vf = horizontal ? enc.x.field : enc.y.field;
          const cf = horizontal ? enc.y.field : enc.x.field;
          const sf = enc.color ? enc.color.field : null;
          const cols = s.columns;
          const rows = [];
          for (let i = 0; i < cols[vf].length; i++) {
            const id = sf ? "string:" + cols[sf][i] : null;
            if (id !== null && s.hidden.indexOf(id) >= 0) continue;
            if (cols[vf][i] !== null) rows.push({ c: cols[cf][i], v: cols[vf][i] });
          }
          if (labels.length === 0) bad("no value label is drawn; the expect says " + e.labels);
          const ser = s.index.series.find((x) => x.on);
          labels.forEach((p) => {
            const l = parseShort(p.text);
            if (l === null) return bad("the label '" + p.text + "' is not a number in the record's format");
            if (e.labels === "bars" && !rows.some((r) => close(r.v, l))) bad("the label '" + p.text + "' is no bar's value");
            if (e.labels === "totals") {
              let j = 0;
              ser.at.forEach((x, k) => { if (Math.abs(x - p.x) < Math.abs(ser.at[j] - p.x)) j = k; });
              const cat = ser.tip[j][0];
              const sum = rows.filter((r) => String(r.c) === String(cat)).reduce((acc, r) => acc + r.v, 0);
              if (!close(sum, l)) bad("the label '" + p.text + "' over " + cat + " is not the stack's total " + sum);
            }
          });
        }
        // --- bars: the rounded data end, and the gap
        if (s.wire === "bar") {
          const enc = s.document.config.encoding;
          const horizontal = enc.y.type === "nominal";
          const valueEnc = horizontal ? enc.x : enc.y;
          const stack = valueEnc.stack === "zero" || valueEnc.stack === "normalize";
          const gap = t.n("--chart-gap");
          const bars = marks.filter((p) => p.k === "rect" || p.k === "path");
          counts.bars += bars.length;
          const byDatum = {};
          bars.forEach((p) => (byDatum[p.datum] = byDatum[p.datum] || []).push(p));
          Object.keys(byDatum).forEach((d) => {
            const list = byDatum[d].map((p) => ({ p: p, b: boxOf(p) }));
            if (stack) {
              list.sort((u, v) => (horizontal ? v.b.x - u.b.x : u.b.y - v.b.y));
              if (!arcs(list[0].p)) bad("the stack at " + d + ": its outermost segment is not rounded");
              list.slice(1).forEach((x) => { if (arcs(x.p)) bad("the stack at " + d + ": an inner segment is rounded"); });
              for (let i = 1; i < list.length; i++) {
                const g = horizontal ? list[i - 1].b.x - (list[i].b.x + list[i].b.w) : list[i].b.y - (list[i - 1].b.y + list[i - 1].b.h);
                if (g < gap - EPS) bad("the stack at " + d + ": a gap of " + g.toFixed(2) + " between segments");
              }
            } else {
              list.forEach((x) => { if (!arcs(x.p)) bad("a bar at " + d + " has no rounded data end"); });
              list.sort((u, v) => (horizontal ? u.b.y - v.b.y : u.b.x - v.b.x));
              for (let i = 1; i < list.length; i++) {
                const g = horizontal ? list[i].b.y - (list[i - 1].b.y + list[i - 1].b.h) : list[i].b.x - (list[i - 1].b.x + list[i - 1].b.w);
                if (g < gap - EPS) bad("the group at " + d + ": a gap of " + g.toFixed(2) + " between bars");
              }
            }
          });
        }
        return { problems: problems, counts: counts };
        """.trimIndent()

    private val POINT_BODY =
        """
        const s = DatapipelinesReference.snapshot(key);
        if (s.state !== "rendered") return null;
        const ser = s.index.series.find((x) => x.on && x.at.length > 0);
        if (!ser) return null;
        let px;
        let py;
        let j;
        if (s.wire === "bar") {
          const p = s.primitives.find((q) => q.role === "mark" && q.series === ser.id && (q.k === "rect" || q.k === "path"));
          if (!p) return null;
          const b = boxOf(p);
          px = b.x + b.w / 2;
          py = b.y + b.h / 2;
          j = ser.datum.indexOf(p.datum);
        } else {
          const dot = s.primitives.find((q) => q.k === "circle" && q.role === "mark" && q.series === ser.id);
          if (!dot) return null;
          px = dot.cx;
          py = dot.cy;
          j = ser.at.length - 1;
        }
        const svg = svgOf(s);
        // A fixed frame wider than its column scrolls inside its own tile: bring the target into that scroller's view first.
        const scroller = svg.closest(".ref-scroll");
        if (scroller) {
          const r0 = svg.getBoundingClientRect();
          const box = scroller.getBoundingClientRect();
          scroller.scrollLeft += r0.left + (px * r0.width) / s.frame.w - (box.left + box.width / 2);
        }
        const r = svg.getBoundingClientRect();
        return { x: r.left + (px * r.width) / s.frame.w, y: r.top + (py * r.height) / s.frame.h, series: ser.id, j: j };
        """.trimIndent()

    private val CARD_BODY =
        """
        const s = DatapipelinesReference.snapshot(a.key);
        const problems = [];
        const bad = (m) => problems.push(a.key + " (" + themeName(s) + ", " + s.frame.w + " x " + s.frame.h + ", card): " + m);
        const t = R.theme.resolve(s.theme);
        const f = s.frame;
        const svg = svgOf(s);
        const g = svg.querySelector("g.dp-tooltip");
        if (!g) {
          bad("no card under the pointer");
          return { problems: problems, texts: [] };
        }
        const rects = Array.from(g.querySelectorAll("rect"));
        const texts = Array.from(g.querySelectorAll("text"));
        rects.forEach((r) => {
          const x = Number(r.getAttribute("x"));
          const y = Number(r.getAttribute("y"));
          const w = Number(r.getAttribute("width"));
          const h = Number(r.getAttribute("height"));
          if (x < -TOL || y < -TOL || x + w > f.w + TOL || y + h > f.h + TOL) bad("a rect of the card leaves the frame");
        });
        texts.forEach((el) => {
          const b = textBox(el);
          if (b.x0 < -TOL || b.y0 < -TOL || b.x1 > f.w + TOL || b.y1 > f.h + TOL) bad("the card's text '" + b.text + "' leaves the frame: " + round(b));
        });
        const size = t.n("--chart-text");
        const pad = look("tipPad") * size;
        const lh = look("tipLine") * size;
        const room = f.h - 2 * pad;
        const k = room < lh ? 0 : Math.floor(room / lh + 1e-9);
        const ser = s.index.series.find((x) => x.id === a.series);
        const datum = ser.datum[a.j];
        const tip = ser.tip[a.j];
        const rowsWanted = s.index.series.filter((x) => x.on && x.datum.indexOf(datum) >= 0).length;
        const fields = Math.max(0, tip.length - 2);
        const all = 1 + rowsWanted + fields;
        const keys = rects.length - 1;
        const lines = new Set(texts.map((el) => Math.round(Number(el.getAttribute("y")) * 10))).size;
        const more = texts.find((el) => /^\+\d+( more)?$/.test(el.textContent));
        let heading = null;
        if (all <= k) {
          if (keys !== rowsWanted || lines !== all) bad("the full card fits (" + all + " lines, k " + k + ") but it shows " + lines + " lines with " + keys + " keys");
          heading = texts[0].textContent;
        } else if (k >= 3) {
          if (!more) bad("the card does not fit (" + all + " lines, k " + k + ") and has no '+n more'");
          else if (Number(more.textContent.slice(1).split(" ")[0]) !== all - (lines - 1)) bad("'" + more.textContent + "' is not the " + (all - (lines - 1)) + " lines left out");
          heading = texts[0].textContent;
        } else if (k === 2) {
          if (keys !== 1 || lines !== 2) bad("k 2: not the heading and the hovered row (" + lines + " lines, " + keys + " keys)");
          heading = texts[0].textContent;
        } else if (k === 1) {
          if (keys !== 1 || lines !== 1) bad("k 1: not the hovered row alone (" + lines + " lines, " + keys + " keys)");
        }
        if (heading !== null) {
          const cat = String(tip[0]);
          const enc = s.document.config.encoding;
          const valueEnc = enc.y.type === "nominal" ? enc.x : enc.y;
          const join = look("tipTotalJoin");
          if (s.wire === "bar" && valueEnc.stack === "normalize") {
            if (heading !== cat) bad("a normalized stack's card heads with '" + heading + "', not the category '" + cat + "' alone");
          } else if (s.wire === "bar" && valueEnc.stack === "zero") {
            const cols = s.columns;
            const cf = enc.y.type === "nominal" ? enc.y.field : enc.x.field;
            let sum = 0;
            for (let i = 0; i < cols[valueEnc.field].length; i++) if (String(cols[cf][i]) === cat && cols[valueEnc.field][i] !== null) sum += cols[valueEnc.field][i];
            const rest = heading.indexOf(cat + join) === 0 ? heading.slice((cat + join).length) : null;
            const l = rest === null ? null : parseShort(rest);
            if (heading !== cat && (l === null || !close(sum, l))) bad("a stack's card heads with '" + heading + "': not the category, '" + join + "' and the whole total " + sum);
            if (f.w >= 200 && rest === null) bad("a stack's card heads with '" + heading + "' and no total, with room for it");
          } else if (heading !== cat && !(heading.slice(-1) === "…" && cat.indexOf(heading.slice(0, -1)) === 0)) bad("the card heads with '" + heading + "', not '" + cat + "'");
        }
        return { problems: problems, texts: texts.map((el) => el.textContent), heading: heading };
        """.trimIndent()

    private val HOSTILE_BODY =
        """
        const s = DatapipelinesReference.snapshot(key);
        const fig = document.getElementById(s.figure);
        const svg = svgOf(s);
        let foreign = fig.querySelectorAll("img, a, iframe, object, embed, foreignObject, b, i").length + Math.max(0, fig.querySelectorAll("svg").length - 1);
        fig.querySelectorAll("*").forEach((el) => {
          for (const at of Array.from(el.attributes)) if (/^on/i.test(at.name)) foreign++;
        });
        const texts = Array.from(svg.querySelectorAll("text")).map((el) => el.textContent);
        const title = fig.querySelector(".dp-dashboard-card-title").textContent;
        const literal = title === s.document.display_name && texts.some((x) => x.indexOf("<b>bold?") === 0);
        return { scripts: fig.querySelectorAll("script").length, foreign: foreign, literal: literal, texts: texts.length };
        """.trimIndent()

    private val FOREST_BODY =
        """
        const card = document.querySelector(".dp-dashboard-card");
        const tokens = {};
        document.querySelectorAll("#forest-tokens td[data-token]").forEach((td) => {
          tokens[td.getAttribute("data-token")] = td.textContent;
        });
        const th = DatapipelinesReference.theme();
        const drawn = {};
        Object.keys(th.tokens).forEach((k) => {
          drawn[k] = "#" + th.tokens[k].map((v) => (v < 16 ? "0" : "") + v.toString(16)).join("");
        });
        const keys = DatapipelinesReference.keys();
        const same = keys.every((k) => JSON.stringify(DatapipelinesReference.snapshot(k).theme) === JSON.stringify(th));
        return { card: getComputedStyle(card).backgroundColor, tokens: tokens, drawn: drawn, tiles: keys.length, sameTheme: same };
        """.trimIndent()

    private val CARDS_BODY =
        """
        return Array.from(new Set(Array.from(document.querySelectorAll(".dp-dashboard-card")).map((c) => getComputedStyle(c).backgroundColor)));
        """.trimIndent()

    private val ROW_BODY =
        """
        const out = {};
        document.querySelectorAll("[data-row-card]").forEach((el) => {
          const c = getComputedStyle(el);
          out[el.getAttribute("data-row-card")] = [c.paddingTop, c.paddingRight, c.paddingBottom, c.paddingLeft].join(" ");
        });
        return out;
        """.trimIndent()

    private val CARD_TEXTS_BODY =
        """
        return Array.from(document.getElementById(figure).querySelectorAll("svg.dp-chart g.dp-tooltip text")).map((el) => el.textContent);
        """.trimIndent()

    private fun fn(
        arg: String,
        body: String,
        helpers: Boolean = true,
    ): String = "(" + arg + ") => {\n" + (if (helpers) HELPERS_BODY + "\n" else "") + body + "\n}"

    /** One tile (D8): state, level, DOM = primitives, the theme's values, I1/I2, legend, end dots, labels, bars. */
    val CHECK_JS = fn("a", CHECK_BODY)

    /** Where the pointer goes on a tile: the first visible series' first bar, or its end dot; null on a tile with none. */
    val POINT_JS = fn("key", POINT_BODY)

    /** The open card (contract §4.4, L8): inside the frame; whole, "+n more", or the hovered row by k; the heading. */
    val CARD_JS = fn("a", CARD_BODY)

    /** The hostile tile: script elements, foreign elements or handlers, and whether its text is literal. */
    val HOSTILE_JS = fn("key", HOSTILE_BODY)

    /** The forest page: its card, the seven tokens printed, the seven the tiles were drawn with. */
    val FOREST_JS = fn("", FOREST_BODY, helpers = false)

    /** The distinct backgrounds of the reference page's own chart cards. */
    val CARDS_JS = fn("", CARDS_BODY, helpers = false)

    /** The dashboard row's three paddings, top right bottom left. */
    val ROW_JS = fn("", ROW_BODY, helpers = false)

    /** The texts of a tile's open card, in their order. */
    val CARD_TEXTS_JS = fn("figure", CARD_TEXTS_BODY, helpers = false)
}
