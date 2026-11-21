package co.datapipelines.browser

import co.datapipelines.jsrendered.JsRenderedEngineFiles
import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs

/**
 * A mark found by its own shape, in a real Chromium (lane 1052, js-rendered G4; brief 1052 D11, A.8, A.9, G13 to G15).
 *
 * No application is booted. The test serves, by request interception on one origin of its own, a closed set of files:
 * from the assembled reference directory (`dp.reference.dir`) the engine's files in the order of
 * [JsRenderedEngineFiles.names], the interaction layer and `css/app.css` with the font files it names; from the test
 * classpath the test-only type `test_marks.js` and the emitted inputs of its fixtures; from `chart-marks/` one page (the
 * reference page's policy, word for word, as a `meta` element) and its script. Any other request is aborted and fails
 * the test; the class listens itself, from the first request on, for console errors, page errors, dialogs and policy
 * violations, and every test ends with all of them, the aborted requests and the `style` attributes at 0.
 * The serving code is [ChartTestPage]'s, shared with [ChartLegendFormsBrowserTest] (brief 1054 D11).
 *
 * The oracle is the test's own (the in-page checks below): which mark a place finds (`Math.hypot`, `Math.atan2`), the
 * card's lines from the FIXTURE the test holds (never the served copy, never the layer's `cardLines`), measured with the
 * engine's measurer and the look table's card fields as the Node tests measure them, the ring, the move and the focus
 * ring from the index's shapes and the look table.
 */
class ChartMarkLookupBrowserTest {
    @Test
    fun `A8 the pointer at every mark's anchor shows the card of the mark the test's own rule names`() {
        open().use { s ->
            var cards = 0
            for (frame in listOf("960x540", "480x360")) {
                val exceptions = mutableListOf<String>()
                for (fixture in WITH_MARKS) {
                    val result = pointerAtEveryAnchor(s, fixture, frame, fixtureText(fixture, frame))
                    withClue("$fixture $frame: cards that are not the test's own") { result.problems.shouldBeEmpty() }
                    exceptions += result.exceptions
                    cards += result.cards
                }
                withClue("$frame: the anchors D4 gives to another mark") { exceptions.sorted() shouldContainExactly EXCEPTIONS }
            }
            println("A.8 pointer: $cards cards opened at the anchors of ${WITH_MARKS.size} fixtures x 2 frames; exceptions $EXCEPTIONS")
            cards shouldBeGreaterThanOrEqual MIN_CARDS
            s.clean("after the pointer walk")
        }
    }

    @Test
    fun `A8 the keys walk every key target in the real page and the card and focus ring follow them, whatever the pointer's place`() {
        open().use { s ->
            var visited = 0
            for (fixture in listOf("cells", "box-and-outside", "slices")) visited += keysWalk(s, fixture)
            visited shouldBeGreaterThanOrEqual MIN_KEY_TARGETS
            s.clean("after the keys' walk")
        }
    }

    @Test
    fun `A8 the box's card at a k of 3 shows +n more with the test's n, at a k of 1 the kept row alone`() {
        open().use { s ->
            val text = fixtureText("box-and-outside", "960x540")
            for ((h, k) in listOf(K3_HEIGHT to 3, K1_HEIGHT to 1)) {
                val info = s.draw("box-and-outside/960x540.input.json", 960 to h)
                info["state"] shouldBe "rendered"
                val n = marksOf(info).indexOfFirst { it["datum"] == "box-north" }
                s.hover(anchorOf(marksOf(info)[n]))
                @Suppress("UNCHECKED_CAST")
                val r = s.inPage(MarkChecks.CARD_JS, listOf(text, n)) as Map<String, Any?>
                withClue("960x$h: the test's own k") { (r["k"] as Number).toInt() shouldBe k }
                withClue("960x$h: the box's card") { r["shown"] shouldBe r["expected"] }
                @Suppress("UNCHECKED_CAST")
                val shown = r["shown"] as List<String>
                if (k == 1) shown shouldBe listOf("Middle", "60")
                if (k == 3) {
                    // heading, the kept row, then "+n more": n = every line of the card (heading + 6 rows) less the 2 shown.
                    shown.first() shouldBe "North"
                    shown.subList(1, 3) shouldBe listOf("Middle", "60")
                    shown.last() shouldBe "+5 more"
                }
                println("A.8 card at 960x$h (k=$k): $shown")
            }
            s.clean("after the box's cards")
        }
    }

    @Test
    fun `A8 the ring of a point and of a cell, the move of a slice, the dimming, and what a pointer leave takes away`() {
        open().use { s ->
            // A point and a cell: one ring each, at the test's place and size, in the mark's colour.
            for ((fixture, datum) in listOf("points" to "big", "cells" to "r1c1")) {
                val info = s.draw("$fixture/960x540.input.json")
                val n = marksOf(info).indexOfFirst { it["datum"] == datum }
                s.hover(anchorOf(marksOf(info)[n]))
                val r = s.state(n, MARK_RING, MARK_MOVE)
                withClue("$fixture $datum under the pointer") { r.problems.shouldBeEmpty() }
                println("A.8 ring $fixture $datum: ${r.raw["ring"]}")
                s.leave()
                withClue("$fixture: after the pointer left while it owned the card") { s.nothing().shouldBeEmpty() }
            }
            // A slice: its elements moved by markMove along its own direction, on the screen too; the dimming.
            val info = s.draw("slices/960x540.input.json")
            val marks = marksOf(info)
            for (n in marks.indices) {
                s.hover(anchorOf(marks[n]))
                val r = s.state(n, MARK_RING, MARK_MOVE)
                withClue("slices ${marks[n]["datum"]} under the pointer") { r.problems.shouldBeEmpty() }
                println("A.8 move slices ${marks[n]["datum"]}: ${r.raw["move"]}")
            }
            s.leave()
            withClue("slices: after the pointer left while it owned the card") { s.nothing().shouldBeEmpty() }
            // The keys own the card: the pointer rests on slice 2, the keys reach slice 0, the pointer leaves.
            for ((fixture, rest) in listOf("slices" to 2, "points" to 1)) {
                val drawn = s.draw("$fixture/960x540.input.json")
                s.hover(anchorOf(marksOf(drawn)[rest]))
                s.page.locator("#chart").focus()
                s.page.keyboard().press("ArrowRight")
                s.leave()
                val r = s.state(0, MARK_RING, MARK_MOVE)
                withClue("$fixture: the keys' mark after the pointer left") { r.problems.shouldBeEmpty() }
                s.page.keyboard().press("Escape")
                s.page.locator("#chart").blur()
                withClue("$fixture: after Escape and blur") { s.nothing().shouldBeEmpty() }
            }
            s.clean("after the highlights")
        }
    }

    @Test
    fun `A8 a card with lt and amp holds both characters as text and has its twin's element count`() {
        open().use { s ->
            val info = s.draw("long-card/960x540.input.json")
            val marks = marksOf(info)
            val counts = mutableMapOf<String, Int>()
            val texts = mutableMapOf<String, String>()
            for (datum in listOf("special", "twin")) {
                s.hover(anchorOf(marks.first { it["datum"] == datum }))
                @Suppress("UNCHECKED_CAST")
                val r = s.inPage(MarkChecks.CARD_ELEMENTS_JS, null) as Map<String, Any?>
                counts[datum] = (r["elements"] as Number).toInt()
                texts[datum] = r["text"] as String
            }
            println("A.8 special card: ${counts["special"]} elements, twin ${counts["twin"]}; text ${texts["special"]}")
            texts.getValue("special") shouldContain "Heading < and &"
            texts.getValue("special") shouldContain "Less < more &"
            counts.getValue("special") shouldBeGreaterThanOrEqual 3
            counts["special"] shouldBe counts["twin"]
            s.clean("after the card with < and &")
        }
    }

    @Test
    fun `A9 each of the three look fields, changed alone in the served look table, changes what the page does`() {
        val problems = lookFields(layerPlant = null)
        withClue("A.9: the three look fields") { problems.shouldBeEmpty() }
    }

    @Test
    fun `G13 the browser half - each field written as a constant in a copy of the layer turns its A9 check red`() {
        val reds = mutableListOf<String>()
        for ((field, constant) in listOf("markReach" to "24", "markMove" to "5", "markRing" to "0.25")) {
            val problems = lookFields(layerPlant = "R.tables.look(\"$field\")" to constant, only = field)
            println("G13 red ($field as $constant): ${problems.firstOrNull()}")
            withClue("G13: $field written as $constant in the layer's copy goes red") { problems shouldNotBe emptyList<String>() }
            reds += field
        }
        reds shouldBe listOf("markReach", "markMove", "markRing")
    }

    @Test
    fun `G7 the browser half - a layer copy whose clear keeps the move leaves the slice moved after the pointer left`() {
        val layerText = Files.readString(dir.resolve(LAYER))
        val needle = "        el.removeAttribute(\"transform\");\n"
        withClue("the plant's anchor occurs once in the layer") { layerText.split(needle).size - 1 shouldBe 1 }

        fun leftBehind(files: Map<String, ByteArray>): List<String> =
            open(files).use { s ->
                val info = s.draw("slices/960x540.input.json")
                s.hover(anchorOf(marksOf(info)[0]))
                s.leave()
                s.nothing().also { s.clean("G7, after the leave") }
            }
        val red = leftBehind(mapOf(LAYER to layerText.replace(needle, "").toByteArray()))
        println("G7 red (clear keeps the move): $red")
        withClue("G7: the planted layer leaves the slice moved") { red.joinToString() shouldContain "moved elements" }
        val green = leftBehind(emptyMap())
        println("G7 green: $green")
        green.shouldBeEmpty()
    }

    @Test
    fun `G14 the page's cleanliness can fail - a style attribute and a request outside the served set are seen`() {
        val anchor =
            "        R.writeDom(drawn ? out.primitives : [], svg, input.theme, " +
                "drawn ? R.write.describe(out, input.document) : undefined);\n"
        val script = resourceText("chart-marks/chart-marks.js")
        withClue("the plant's anchor occurs once") { script.split(anchor).size - 1 shouldBe 1 }
        val styled = script.replace(anchor, anchor + "        svg.setAttribute(\"style\", \"outline: none\");\n")
        open(mapOf("chart-marks.js" to styled.toByteArray())).use { s ->
            s.draw("cells/960x540.input.json")
            val seen = s.seen()
            println("G14 red (style attribute): $seen")
            withClue("G14: the style plant is a policy violation") { seen.getValue("policy violations") shouldNotBe emptyList<String>() }
            withClue("G14: the style plant is a style attribute") { seen.getValue("style attributes") shouldNotBe emptyList<String>() }
        }
        val fetching = script.replace(anchor, anchor + "        fetch(\"not-served.json\").catch(function () {});\n")
        open(mapOf("chart-marks.js" to fetching.toByteArray())).use { s ->
            s.draw("cells/960x540.input.json")
            s.until("the aborted request is seen") { s.seen().getValue("requests aborted").isNotEmpty() }
            val seen = s.seen()
            println("G14 red (request outside the served set): ${seen["requests aborted"]}")
            seen.getValue("requests aborted").joinToString() shouldContain "not-served.json"
        }
        open().use { s ->
            s.draw("cells/960x540.input.json")
            println("G14 green (the page as committed): ${s.seen()}")
            s.clean("the page as committed")
        }
    }

    @Test
    fun `G15 the card is the test's own - one row's value changed in the served copy turns the pointer check red naming the mark`() {
        val file = "box-and-outside/960x540.input.json"
        val original = fixtureText("box-and-outside", "960x540")
        val needle = "\"name\":\"Middle\",\"value\":\"60\""
        withClue("the planted row occurs in the fixture") { original.contains(needle) shouldBe true }
        val planted = original.replaceFirst(needle, "\"name\":\"Middle\",\"value\":\"61\"")
        open(mapOf("emitted/$file" to planted.toByteArray())).use { s ->
            val red = pointerAtEveryAnchor(s, "box-and-outside", "960x540", original)
            println("G15 red: ${red.problems.firstOrNull()}")
            withClue("G15: the planted copy is red") { red.problems shouldNotBe emptyList<String>() }
            red.problems.first() shouldContain "box-north"
        }
        open().use { s ->
            val green = pointerAtEveryAnchor(s, "box-and-outside", "960x540", original)
            println("G15 green: ${green.cards} cards, ${green.problems.size} problems")
            green.problems.shouldBeEmpty()
            s.clean("G15 green")
        }
    }

    // ------------------------------------------------------------------------------------------------ the checks

    /** The keys' walk of the test's plan over [fixture] at 960 x 540; returns the count of key targets visited. */
    private fun keysWalk(
        s: Session,
        fixture: String,
    ): Int {
        val info = s.draw("$fixture/960x540.input.json")
        val text = fixtureText(fixture, "960x540")
        // The pointer rests on the LAST mark: the keys act after it, so the card is the keys' mark (D7, LAYER).
        val marks = marksOf(info)
        s.hover(anchorOf(marks.last()))
        s.page.locator("#chart").focus()
        @Suppress("UNCHECKED_CAST")
        val steps = s.inPage(MarkChecks.WALK_PLAN_JS, null) as List<Map<String, Any?>>
        val problems = mutableListOf<String>()
        val seen = mutableSetOf<Int>()
        for ((i, step) in steps.withIndex()) {
            s.page.keyboard().press(step["key"] as String)
            @Suppress("UNCHECKED_CAST")
            val r = s.inPage(MarkChecks.KEY_STATE_JS, listOf(text, step["expect"])) as Map<String, Any?>
            @Suppress("UNCHECKED_CAST")
            problems += (r["problems"] as List<String>).map { "$fixture step ${i + 1} (${step["key"]}): $it" }
            (step["expect"] as? Number)?.let { seen += it.toInt() }
        }
        @Suppress("UNCHECKED_CAST")
        val targets = s.inPage(MarkChecks.TARGETS_JS, null) as List<Number>
        withClue("$fixture: the keys' walk") { problems.shouldBeEmpty() }
        withClue("$fixture: every key target visited (outside points with key false never)") {
            seen shouldBe targets.map { it.toInt() }.toSet()
        }
        println("A.8 keys $fixture: ${steps.size} key presses, ${seen.size} key targets of ${marks.size} marks visited")
        s.page.keyboard().press("Escape")
        return seen.size
    }

    data class Walked(
        val cards: Int,
        val problems: List<String>,
        val exceptions: List<String>,
    )

    /** The pointer at every mark's anchor of [fixture] at [frame]; the card against the test's own, from [text]. */
    private fun pointerAtEveryAnchor(
        s: Session,
        fixture: String,
        frame: String,
        text: String,
    ): Walked {
        val info = s.draw("$fixture/$frame.input.json")
        val marks = marksOf(info)
        var cards = 0
        val problems = mutableListOf<String>()
        val exceptions = mutableListOf<String>()
        for ((n, mark) in marks.withIndex()) {
            s.hover(anchorOf(mark))
            @Suppress("UNCHECKED_CAST")
            val r = s.inPage(MarkChecks.POINTER_JS, listOf(text, REACH)) as Map<String, Any?>
            val want = (r["want"] as Number).toInt()
            if (want != n && want >= 0) exceptions += "$fixture:${mark["datum"]}->${marks[want]["datum"]}"
            if (want < 0) problems += "$fixture $frame ${mark["datum"]}: its anchor finds no mark by the test's rule"
            if (r["shown"] != r["expected"]) {
                val named = "$fixture $frame ${mark["datum"]} (the mark the test names: ${r["datum"]})"
                problems += "$named: shown ${r["shown"]}, the test's own ${r["expected"]}"
            }
            @Suppress("UNCHECKED_CAST")
            problems += (r["invisible"] as List<String>).map { "$fixture $frame ${mark["datum"]}: $it" }
            if ((r["shown"] as List<*>).isNotEmpty()) cards++
        }
        return Walked(cards, problems, exceptions)
    }

    /** A look-field change: the served copy of 06-tables.js with [field] changed from [from] to [to], plus [layerPlant]. */
    private fun changed(
        field: String,
        from: String,
        to: String,
        layerPlant: Pair<String, String>?,
    ): Map<String, ByteArray> {
        val tables = Files.readString(dir.resolve("engine/06-tables.js"))
        val old = "$field: f($from, \"interaction\""
        withClue("06-tables.js holds `$old` exactly once") { tables.split(old).size - 1 shouldBe 1 }
        val out = mutableMapOf("engine/06-tables.js" to tables.replace(old, "$field: f($to, \"interaction\"").toByteArray())
        if (layerPlant != null) {
            val layerText = Files.readString(dir.resolve(LAYER))
            val (needle, constant) = layerPlant
            withClue("the layer holds `$needle` exactly once") { layerText.split(needle).size - 1 shouldBe 1 }
            out[LAYER] = layerText.replace(needle, constant).toByteArray()
        }
        return out
    }

    /**
     * A.9 (and G13 with [layerPlant]): one session per changed field and one at the defaults. [only] limits the run to
     * one field. Returns what differs from the change's expected effect; empty = the three changes took effect.
     */
    private fun lookFields(
        layerPlant: Pair<String, String>?,
        only: String? = null,
    ): List<String> {
        val problems = mutableListOf<String>()
        if (only == null || only == "markReach") problems += reachChange(changed("markReach", "24", "40", layerPlant))
        if (only == null || only == "markMove") problems += moveChange(changed("markMove", "5", "10", layerPlant))
        if (only == null || only == "markRing") problems += ringChange(changed("markRing", "0.25", "1", layerPlant))
        return problems
    }

    /** markReach 24 -> 40: 30 px from the near point `big` (inside the plot, no other mark within 60 px) no mark, then big. */
    private fun reachChange(files: Map<String, ByteArray>): List<String> {
        val problems = mutableListOf<String>()
        val found = mutableMapOf<String, Any?>()
        for ((label, served) in listOf("default" to emptyMap(), "40" to files)) {
            open(served).use { s ->
                s.draw("points/960x540.input.json")
                @Suppress("UNCHECKED_CAST")
                val place = s.inPage(MarkChecks.REACH_PLACE_JS, listOf("big", 30)) as Map<String, Any?>
                @Suppress("UNCHECKED_CAST")
                problems += place["problems"] as List<String>
                s.hover((place["x"] as Number).toDouble() to (place["y"] as Number).toDouble())
                @Suppress("UNCHECKED_CAST")
                found[label] = (s.inPage(MarkChecks.SHOWN_JS, null) as List<String>).firstOrNull()
                s.clean("A.9 markReach $label")
            }
        }
        println("A.9 markReach 24 -> 40: 30 px from `big` the card's first text is ${found["default"]} at 24, ${found["40"]} at 40")
        if (found["default"] != null) problems += "markReach 24: a place 30 px from `big` shows a card: ${found["default"]}"
        if (found["40"] != "A") problems += "markReach 40: a place 30 px from `big` shows ${found["40"]}, not big's card (series A)"
        return problems
    }

    /** markMove 5 -> 10: the hovered slice's move and its place on the screen go twice as far. */
    private fun moveChange(files: Map<String, ByteArray>): List<String> {
        val problems = mutableListOf<String>()
        val moved = mutableMapOf<String, Double>()
        for ((label, by, served) in listOf(Triple("5", MARK_MOVE, emptyMap()), Triple("10", 10.0, files))) {
            open(served).use { s ->
                val info = s.draw("slices/960x540.input.json")
                s.hover(anchorOf(marksOf(info)[0]))
                val r = s.state(0, MARK_RING, by)
                problems += r.problems.map { "markMove $label: $it" }
                @Suppress("UNCHECKED_CAST")
                moved[label] = ((r.raw["move"] as Map<String, Any?>?)?.get("screen") as Number?)?.toDouble() ?: -1.0
                s.clean("A.9 markMove $label")
            }
        }
        println("A.9 markMove 5 -> 10: the slice moved ${moved["5"]} px on the screen at 5, ${moved["10"]} px at 10")
        if (abs(moved.getValue("10") - 2 * moved.getValue("5")) > SCREEN_TOLERANCE) {
            problems += "markMove 10: the slice moved ${moved["10"]}, not twice ${moved["5"]}"
        }
        return problems
    }

    /** markRing 0.25 -> 1: the ring's radius grows by 0.75 x s. */
    private fun ringChange(files: Map<String, ByteArray>): List<String> {
        val problems = mutableListOf<String>()
        val radius = mutableMapOf<String, Double>()
        for ((label, em, served) in listOf(Triple("0.25", MARK_RING, emptyMap()), Triple("1", 1.0, files))) {
            open(served).use { s ->
                val info = s.draw("points/960x540.input.json")
                val n = marksOf(info).indexOfFirst { it["datum"] == "big" }
                s.hover(anchorOf(marksOf(info)[n]))
                val r = s.state(n, em, MARK_MOVE)
                problems += r.problems.map { "markRing $label: $it" }
                @Suppress("UNCHECKED_CAST")
                radius[label] = ((r.raw["ring"] as Map<String, Any?>?)?.get("r") as Number?)?.toDouble() ?: -1.0
                s.clean("A.9 markRing $label")
            }
        }
        val size = textSize()
        val grown = radius.getValue("1") - radius.getValue("0.25")
        println("A.9 markRing 0.25 -> 1: the ring's radius ${radius["0.25"]} at 0.25, ${radius["1"]} at 1 (0.75 x s = ${0.75 * size})")
        if (abs(grown - 0.75 * size) > RING_TOLERANCE) problems += "markRing 1: the radius grew by $grown, not 0.75 x s = ${0.75 * size}"
        return problems
    }

    /** The draw's text size s, read in a page at the defaults (the look table's card fields are the test's oracle). */
    private fun textSize(): Double =
        open().use { s ->
            s.draw("cells/960x540.input.json")
            (s.inPage("() => window.JsRendered.theme.resolve('light').n('--chart-text')", null) as Number).toDouble()
        }

    // ------------------------------------------------------------------------------------------------ the session

    data class State(
        val problems: List<String>,
        val raw: Map<String, Any?>,
    )

    inner class Session(
        private val context: BrowserContext,
        val page: Page,
        private val errors: MutableList<String>,
        private val violations: MutableList<String>,
        private val off: MutableList<String>,
    ) : AutoCloseable {
        private var origin = 0.0 to 0.0
        private var scale = 1.0 to 1.0

        /** Draws an emitted input through the page's script and readies the test's probes; returns the draw. */
        fun draw(
            file: String,
            frame: Pair<Int, Int>? = null,
        ): Map<String, Any?> {
            @Suppress("UNCHECKED_CAST")
            val info = page.evaluate(MarkChecks.DRAW_JS, listOf(file, frame?.first, frame?.second)) as Map<String, Any?>

            @Suppress("UNCHECKED_CAST")
            val rect = page.evaluate(MarkChecks.PREPARE_JS) as Map<String, Any?>
            origin = (rect["left"] as Number).toDouble() to (rect["top"] as Number).toDouble()
            scale = (rect["sx"] as Number).toDouble() to (rect["sy"] as Number).toDouble()
            return info
        }

        /** The pointer at a place of the frame. */
        fun hover(at: Pair<Double, Double>) {
            page.mouse().move(origin.first + at.first * scale.first, origin.second + at.second * scale.second)
        }

        /** The pointer leaves the picture (to a corner of the viewport the picture does not cover). */
        fun leave() {
            page.mouse().move((VIEW_W - 4).toDouble(), (VIEW_H - 4).toDouble())
        }

        fun inPage(
            js: String,
            arg: Any?,
        ): Any? = page.evaluate(js, arg)

        /** The highlight of mark [n] as D7 asks for it (the card, the dimming, the ring or the move, the focus ring). */
        fun state(
            n: Int,
            ringEm: Double,
            moveBy: Double,
        ): State {
            // The dimming eases over --chart-ease (app.css): the computed opacity is read once every transition has ended.
            until("the transitions of the dimming have ended") { page.evaluate("() => document.getAnimations().length === 0") == true }
            @Suppress("UNCHECKED_CAST")
            val r = page.evaluate(MarkChecks.STATE_JS, listOf(n, ringEm, moveBy)) as Map<String, Any?>
            @Suppress("UNCHECKED_CAST")
            return State(r["problems"] as List<String>, r)
        }

        /** What a picture with no highlight still carries; empty = as the writer wrote it. */
        fun nothing(): List<String> {
            @Suppress("UNCHECKED_CAST")
            return page.evaluate(MarkChecks.NOTHING_JS) as List<String>
        }

        fun seen(): Map<String, List<String>> {
            @Suppress("UNCHECKED_CAST")
            val styled = page.evaluate(MarkChecks.STYLED_JS) as List<String>
            return mapOf(
                "console errors, page errors and dialogs" to errors.toList(),
                "policy violations" to violations.toList(),
                "requests aborted" to off.toList(),
                "style attributes" to styled,
            )
        }

        /** D11: every list empty, compared in ONE assertion so a red shows them all. */
        fun clean(point: String) {
            val seen = seen()
            withClue("$point: D11, every list empty") { seen shouldBe seen.mapValues { emptyList<String>() } }
        }

        fun until(
            what: String,
            condition: () -> Boolean,
        ) {
            val deadline = System.nanoTime() + WAIT_NANOS
            while (!condition()) {
                if (System.nanoTime() > deadline) throw AssertionError("$what: not reached; ${seen()}")
                page.waitForTimeout(POLL_MILLIS)
            }
        }

        override fun close() = context.close()
    }

    /** A page of its own; [overrides] replaces a served file's bytes by its path (a plant, or A.9's changed field). */
    private fun open(overrides: Map<String, ByteArray> = emptyMap()): Session =
        PAGE.open(browser, VIEW_W, VIEW_H, overrides).let { Session(it.context, it.page, it.errors, it.violations, it.off) }

    companion object {
        private const val VIEW_W = 1280
        private const val VIEW_H = 900
        private const val WAIT_NANOS = 10_000_000_000L
        private const val POLL_MILLIS = 50.0
        private const val LAYER = ChartTestPage.LAYER
        private val EMITTED = Regex("""[a-z-]+/\d+x\d+\.input\.json""")

        /** The page this class serves itself (D11): the shared serving code, with G4's test type, page and inputs. */
        private val PAGE =
            ChartTestPage(
                origin = "https://chart-marks.test",
                resources = "chart-marks",
                script = "chart-marks.js",
                testTypes = listOf("test_marks"),
                emitted = { r -> if (EMITTED.matches(r)) "co/datapipelines/jsrendered/emitted/_types/test_marks/$r" else null },
            )

        /** The look table's three fields at their defaults, written here from brief D9 (the oracle is not the table). */
        private const val REACH = 24
        private const val MARK_MOVE = 5.0
        private const val MARK_RING = 0.25

        /** A frame with a k of 3 and one with a k of 1 for the card (k = floor((h - 2P) / H), light: P 3.96, H 15.6). */
        private const val K3_HEIGHT = 64
        private const val K1_HEIGHT = 36

        private const val SCREEN_TOLERANCE = 1.2
        private const val RING_TOLERANCE = 0.011

        /** The fixtures that draw marks (empty and all-null are no-data). */
        private val WITH_MARKS =
            listOf("box-and-outside", "cells", "full-ring", "long-card", "over", "points", "ring", "slices", "spokes")

        /** `fixture:datum->datum` where D4 gives a mark's anchor to another mark (A.3's list). */
        private val EXCEPTIONS = listOf("over:large->small", "points:same-2->same-1")

        /** Floors (A.8 non-vacuity), under what a run measured (2026-11-21: 114 cards, 17 key targets). */
        private const val MIN_CARDS = 60
        private const val MIN_KEY_TARGETS = 15

        private val dir: Path get() = ChartTestPage.dir

        private fun resourceText(path: String): String = ChartTestPage.resourceText(path)

        /** The fixture's emitted input as the TEST holds it (the oracle's source; a plant changes the served copy only). */
        private fun fixtureText(
            fixture: String,
            frame: String,
        ): String = resourceText("co/datapipelines/jsrendered/emitted/_types/test_marks/$fixture/$frame.input.json")

        @Suppress("UNCHECKED_CAST")
        private fun marksOf(info: Map<String, Any?>): List<Map<String, Any?>> =
            (info["index"] as Map<String, Any?>)["marks"] as List<Map<String, Any?>>

        @Suppress("UNCHECKED_CAST")
        private fun anchorOf(mark: Map<String, Any?>): Pair<Double, Double> {
            val a = mark["anchor"] as Map<String, Any?>
            return (a["x"] as Number).toDouble() to (a["y"] as Number).toDouble()
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
 * The in-page checks of [ChartMarkLookupBrowserTest], each an arrow function evaluated in the page with Playwright. The
 * expected mark, card, ring, move and focus ring are the test's own: the engine's measurer, theme and look table are
 * read as the Node tests read them, and the layer's own functions are never called.
 */
private object MarkChecks {
    private val HELPERS =
        """
        const R = window.JsRendered;
        const look = R.tables.look;
        const svg = document.getElementById("chart");
        const cm = window.__chartMarks;
        const out = cm.out;
        const TH = R.theme.resolve(out.theme);
        const S = TH.n("--chart-text");
        const H = look("tipLine") * S, P = look("tipPad") * S, KEY = look("tipKey") * S, GAP = look("tipGap") * S;
        const G = look("tipMax") * S, BOLD = look("tipValueWeight");
        const WIDTH = R.measure.measurer(null).fn;
        const DEG = Math.PI / 180;
        const r2 = (v) => Math.round(v * 100) / 100 + 0;
        const marks = out.index.marks;
        const plot = out.index.plot;
        const frame = out.frame;
        const holds = (m, x, y) => {
          const s = m.shape;
          if (s.kind === "box") return x >= s.x && x <= s.x + s.w && y >= s.y && y <= s.y + s.h;
          if (s.kind === "point") return Math.hypot(x - s.x, y - s.y) <= s.r;
          const dx = x - s.cx, dy = y - s.cy, d = Math.hypot(dx, dy);
          if (d < s.r0 || d > s.r1) return false;
          if (s.to - s.from >= 360 || d === 0) return true;
          return (((Math.atan2(dx, -dy) / DEG - s.from) % 360) + 360) % 360 <= s.to - s.from;
        };
        const oracle = (x, y, reach) => {
          if (marks.length === 0 || x < plot.x || x > plot.x + plot.w || y < plot.y || y > plot.y + plot.h) return -1;
          let found = -1;
          marks.forEach((m, i) => { if (!m.near && holds(m, x, y)) found = i; });
          if (found >= 0) return found;
          let best = Infinity;
          marks.forEach((m, i) => {
            if (!m.near) return;
            const d = Math.hypot(x - m.shape.x, y - m.shape.y);
            if (d <= reach && d < best) { best = d; found = i; }
          });
          return found;
        };
        const bounds = (f) => {
          const W = Math.min(f.w, G);
          return { W: W, w: W - 2 * P, k: f.h - 2 * P < H ? 0 : Math.floor((f.h - 2 * P) / H + 1e-9) };
        };
        const cardOf = (fixtureText, n) => {
          const cards = JSON.parse(fixtureText).document.config.cards || {};
          const m = marks[n];
          const c = cards[String(m.datum)];
          if (c) return { heading: c.heading === undefined ? null : c.heading, rows: c.rows.map((r) => ({ name: r.name, value: r.value, plain: r.colour === null, bold: r.bold === true })), keep: c.keep, extra: c.extra || [] };
          const title = m.series === null ? "Mark" : out.summary.series.find((s) => s.id === m.series).title;
          return { heading: null, rows: [{ name: title, value: String(m.datum), plain: false, bold: false }], keep: 0, extra: [] };
        };
        const fitOwn = (l, w) => {
          if (l.kind === "heading") { const t = R.measure.fit(WIDTH, l.text, S, 400, w); return { ok: t !== "", texts: [t] }; }
          const row = l.kind === "row";
          const vw = WIDTH(l.value, S, row ? BOLD : 400);
          const lead = row && !l.plain ? KEY + GAP : 0;
          if (lead + vw > w + 1e-9) return { ok: false };
          const nameWeight = row && l.bold ? BOLD : 400;
          const room = w - lead - vw - GAP;
          const shown = l.name === "" || room <= 0 ? "" : R.measure.fit(WIDTH, l.name, S, nameWeight, room);
          if ((!row || l.plain) && l.name !== "" && shown === "") return { ok: false };
          return { ok: true, texts: shown === "" ? [l.value] : [shown, l.value] };
        };
        const expectCard = (card, f) => {
          const b = bounds(f);
          if (b.k === 0 || b.w <= 0) return [];
          const lines = [];
          if (typeof card.heading === "string" && card.heading !== "") lines.push({ kind: "heading", text: card.heading });
          card.rows.forEach((r, i) => lines.push({ kind: "row", name: r.name, value: r.value, plain: r.plain, bold: r.bold, kept: i === card.keep }));
          for (const x of card.extra) {
            const i = x.indexOf(": ");
            lines.push({ kind: "field", name: i < 0 ? "" : x.slice(0, i), value: i < 0 ? x : x.slice(i + 2) });
          }
          const fit = lines.map((l) => fitOwn(l, b.w));
          const kept = lines.findIndex((l) => l.kept);
          if (!fit[kept].ok) return [];
          if (fit.every((x) => x.ok) && fit.length <= b.k) return fit.map((x) => x.texts).flat();
          if (b.k >= 3) {
            const o = [];
            if (lines[0].kind === "heading" && fit[0].ok) o.push(fit[0]);
            o.push(fit[kept]);
            for (let i = 0; i < lines.length && o.length < b.k - 1; i++) {
              if (i === kept || !fit[i].ok || lines[i].kind === "heading") continue;
              o.push(fit[i]);
            }
            const n = lines.length - o.length;
            let more = "+" + n + " more";
            if (WIDTH(more, S, 400) > b.w + 1e-9) more = "+" + n;
            if (WIDTH(more, S, 400) <= b.w + 1e-9) return o.map((x) => x.texts).flat().concat([more]);
          }
          if (b.k >= 2 && lines[0].kind === "heading" && fit[0].ok) return fit[0].texts.concat(fit[kept].texts);
          return fit[kept].texts;
        };
        const cls = (e) => e.getAttribute("class") || "";
        const shown = () => [...svg.querySelectorAll("text.dp-tooltip")].map((t) => t.textContent);
        const all = () => [...svg.querySelectorAll("*")];
        const added = () => all().filter((e) => !cm.written.has(e));
        const writtenEls = () => all().filter((e) => !["title", "desc", "linearGradient", "stop"].includes(e.tagName)).slice(0, out.primitives.length);
        const held = (n) => {
          const mk = marks[n];
          return out.primitives.map((p, i) => (p.role === "mark" && p.k !== "group" && p.k !== "text" && p.series === mk.series && p.datum === mk.datum ? i : -1)).filter((i) => i >= 0);
        };
        const moved = () => all().filter((e) => e.getAttribute("transform") !== null && !/^rotate\(/.test(e.getAttribute("transform")));
        const guides = () => added().filter((e) => /\bdp-guide\b/.test(cls(e)));
        const near = (a, b, tol) => Math.abs(a - b) <= tol;
        const invisible = () => {
          const p = [];
          for (const e of added()) {
            const st = getComputedStyle(e);
            if (e.getAttribute("opacity") === "0" || st.opacity === "0") p.push("an added " + e.tagName + " has opacity 0");
            if (e.getAttribute("fill") === "transparent" || st.fill === "transparent" || st.fill === "rgba(0, 0, 0, 0)") p.push("an added " + e.tagName + " has a transparent fill");
            if (e.hasAttribute("pointer-events")) p.push("an added " + e.tagName + " has a pointer-events attribute");
          }
          return p;
        };
        """.trimIndent()

    private fun fn(
        args: String,
        body: String,
    ): String = "($args) => {\n$HELPERS\n$body\n}"

    /** Draws through the page's script and keeps the draw for the checks. */
    val DRAW_JS =
        """
        ([file, w, h]) => window.ChartMarks.draw(file, w == null ? null : { w: w, h: h }).then((o) => {
          window.__chartMarks = { out: o };
          return o;
        })
        """.trimIndent()

    /** The writer's elements, kept to tell the layer's additions apart; the picture's place and scale; the pointer's place. */
    val PREPARE_JS =
        """
        () => {
          const svg = document.getElementById("chart");
          const cm = window.__chartMarks;
          cm.written = new Set(svg.querySelectorAll("*"));
          cm.count = cm.written.size;
          cm.rects = [...svg.querySelectorAll("*")].map((e) => e.getBoundingClientRect());
          const r = svg.getBoundingClientRect();
          const vb = svg.viewBox.baseVal;
          if (!window.__chartMarksListening) {
            window.__chartMarksListening = true;
            svg.addEventListener("pointermove", (e) => {
              const b = svg.getBoundingClientRect(), v = svg.viewBox.baseVal;
              window.__chartMarksPos = { x: ((e.clientX - b.left) * v.width) / b.width, y: ((e.clientY - b.top) * v.height) / b.height };
            });
          }
          return { left: r.left, top: r.top, sx: r.width / vb.width, sy: r.height / vb.height };
        }
        """.trimIndent()

    /** The card under the pointer against the test's own, at the place the pointer event carried. */
    val POINTER_JS =
        fn(
            "[fixtureText, reach]",
            """
            const pos = window.__chartMarksPos;
            const want = oracle(pos.x, pos.y, reach);
            return { want: want, datum: want < 0 ? null : marks[want].datum, expected: want < 0 ? [] : expectCard(cardOf(fixtureText, want), frame), shown: shown(), invisible: invisible() };
            """.trimIndent(),
        )

    /** The card of mark n against the test's own, with the test's k for this frame. */
    val CARD_JS =
        fn(
            "[fixtureText, n]",
            "return { k: bounds(frame).k, expected: expectCard(cardOf(fixtureText, n), frame), shown: shown() };",
        )

    val SHOWN_JS = fn("", "return shown();")

    /** The keys' walk the test plans from the index (D8): every row walked, Down and Up by the nearest anchor. */
    val WALK_PLAN_JS =
        fn(
            "",
            """
            const targets = marks.map((m, i) => i).filter((i) => marks[i].key);
            const nums = [...new Set(targets.map((i) => marks[i].row))].sort((a, b) => a - b);
            const row = (r) => targets.filter((i) => marks[i].row === r);
            const nearest = (from, list) => {
              let best = -1, bd = Infinity;
              for (const i of list) {
                const d = Math.hypot(marks[i].anchor.x - marks[from].anchor.x, marks[i].anchor.y - marks[from].anchor.y);
                if (d < bd) { bd = d; best = i; }
              }
              return best;
            };
            const legend = svg.querySelectorAll("g.dp-legend[data-series]").length > 0;
            const steps = [];
            let cur = row(nums[0])[0];
            steps.push({ key: "ArrowRight", expect: cur });
            nums.forEach((num, ri) => {
              const r = row(num);
              for (let j = r.indexOf(cur) + 1; j < r.length; j++) { cur = r[j]; steps.push({ key: "ArrowRight", expect: cur }); }
              if (ri === 0) {
                // Past the first row's last target: the legend entry of its series where one is drawn, else it stays.
                if (legend) {
                  // Escape leaves; the next arrow enters again at the first key target (D8, entering).
                  steps.push({ key: "ArrowRight", expect: "entry:" + marks[cur].series });
                  steps.push({ key: "Escape", expect: "none" });
                  cur = r[0];
                  steps.push({ key: "ArrowRight", expect: cur });
                }
                else steps.push({ key: "ArrowRight", expect: cur });
              }
              if (ri + 1 < nums.length) {
                const next = row(nums[ri + 1]);
                cur = nearest(cur, next); steps.push({ key: "ArrowDown", expect: cur });
                cur = next[0]; steps.push({ key: "Home", expect: cur });
              }
            });
            if (nums.length > 1) {
              const prev = row(nums[nums.length - 2]);
              cur = nearest(cur, prev); steps.push({ key: "ArrowUp", expect: cur });
              const at = prev.indexOf(cur);
              if (at > 0) { cur = prev[at - 1]; steps.push({ key: "ArrowLeft", expect: cur }); }
              steps.push({ key: "Home", expect: prev[0] });
              steps.push({ key: "ArrowLeft", expect: prev[0] });
            }
            return steps;
            """.trimIndent(),
        )

    val TARGETS_JS = fn("", "return marks.map((m, i) => i).filter((i) => marks[i].key);")

    /** After a key: the card is the expected mark's and the focus ring stands focusGap outside its box (or the entry's). */
    val KEY_STATE_JS =
        fn(
            "[fixtureText, expect]",
            """
            const p = [];
            const ring = added().filter((e) => /\bdp-focus\b/.test(cls(e)));
            if (expect === "none") {
              if (ring.length !== 0 || shown().length !== 0) p.push("after Escape a focus ring or a card is left");
              return { problems: p };
            }
            if (ring.length !== 1) { p.push(ring.length + " focus rings"); return { problems: p }; }
            const f = ["x", "y", "width", "height"].map((a) => Number(ring[0].getAttribute(a)));
            if (typeof expect === "string") {
              const id = expect.slice("entry:".length);
              const entry = svg.querySelector('g.dp-legend[data-series="' + id + '"]');
              const b = entry === null ? null : entry.getBBox();
              const cx = f[0] + f[2] / 2, cy = f[1] + f[3] / 2;
              if (b === null || cx < b.x - 1 || cx > b.x + b.width + 1 || cy < b.y - 1 || cy > b.y + b.height + 1) p.push("the focus ring " + f + " is not on the legend entry " + id);
              return { problems: p };
            }
            const want = expectCard(cardOf(fixtureText, expect), frame);
            const got = shown();
            if (JSON.stringify(got) !== JSON.stringify(want)) p.push(marks[expect].datum + ": card " + JSON.stringify(got) + ", the test's own " + JSON.stringify(want));
            const b = marks[expect].box, g = look("focusGap") * S, lw = TH.n("--chart-line");
            const x0 = Math.max(lw / 2, b.x - g), y0 = Math.max(lw / 2, b.y - g);
            const x1 = Math.min(frame.w - lw / 2, b.x + b.w + g), y1 = Math.min(frame.h - lw / 2, b.y + b.h + g);
            const e = [x0, y0, x1 - x0, y1 - y0];
            if (!e.every((v, i) => near(v, f[i], 0.011))) p.push(marks[expect].datum + ": focus ring " + f + ", the test's own " + e.map(r2));
            return { problems: p };
            """.trimIndent(),
        )

    /** The highlight of mark n (D7): its card, the dimming, its ring or its move (place on the screen too), nothing else. */
    val STATE_JS =
        fn(
            "[n, ringEm, moveBy]",
            """
            const p = [];
            const mk = marks[n];
            const raw = {};
            if (shown().length === 0) p.push(mk.datum + ": no card");
            const dimmed = svg.classList.contains("dp-dimmed");
            const active = all().filter((e) => /\bdp-active\b/.test(cls(e)));
            if (mk.series === null) {
              if (dimmed || active.length > 0) p.push(mk.datum + ": a mark without a series dims (dp-dimmed " + dimmed + ", dp-active " + active.length + ")");
            } else {
              if (!dimmed) p.push(mk.datum + ": the picture has no dp-dimmed");
              const group = svg.querySelector('g.dp-mark[data-series="' + mk.series + '"]');
              if (group === null || !group.classList.contains("dp-active")) p.push(mk.datum + ": its series' group has no dp-active");
              if (active.some((e) => e.getAttribute("data-series") !== mk.series)) p.push(mk.datum + ": dp-active on another series");
              const entry = svg.querySelector('g.dp-legend[data-series="' + mk.series + '"]');
              if (entry !== null && !entry.classList.contains("dp-active")) p.push(mk.datum + ": its legend entry has no dp-active");
              const other = [...svg.querySelectorAll("g.dp-mark[data-series]")].find((e) => e.getAttribute("data-series") !== mk.series);
              if (other) { raw.otherOpacity = getComputedStyle(other).opacity; if (raw.otherOpacity !== String(TH.n("--chart-dim"))) p.push(mk.datum + ": another series' opacity " + raw.otherOpacity); }
            }
            const rings = guides();
            const els = writtenEls();
            const own = held(n);
            if (mk.emphasis === "ring") {
              if (rings.length !== 1) p.push(mk.datum + ": " + rings.length + " rings");
              else {
                const g = rings[0], s = mk.shape, by = ringEm * S, markEl = els[own[0]];
                if (g.getAttribute("fill") !== "none") p.push(mk.datum + ": the ring's fill " + g.getAttribute("fill"));
                const colour = markEl.getAttribute("fill") !== "none" && markEl.getAttribute("fill") !== null ? markEl.getAttribute("fill") : markEl.getAttribute("stroke");
                if (g.getAttribute("stroke") !== colour) p.push(mk.datum + ": the ring's stroke " + g.getAttribute("stroke") + ", the mark's " + colour);
                if (Number(g.getAttribute("stroke-width")) !== TH.n("--chart-line")) p.push(mk.datum + ": the ring's width " + g.getAttribute("stroke-width"));
                if (s.kind === "point") {
                  raw.ring = { kind: "circle", cx: Number(g.getAttribute("cx")), cy: Number(g.getAttribute("cy")), r: Number(g.getAttribute("r")) };
                  if (g.tagName !== "circle" || !near(raw.ring.cx, s.x, 0.011) || !near(raw.ring.cy, s.y, 0.011) || !near(raw.ring.r, s.r + by, 0.011)) p.push(mk.datum + ": ring " + JSON.stringify(raw.ring) + ", the test's own r " + (s.r + by));
                } else {
                  const a = ["x", "y", "width", "height"].map((k) => Number(g.getAttribute(k)));
                  raw.ring = { kind: "rect", at: a, rx: Number(g.getAttribute("rx")) };
                  const e = [s.x - by, s.y - by, s.w + 2 * by, s.h + 2 * by];
                  if (g.tagName !== "rect" || !e.every((v, i) => near(v, a[i], 0.011))) p.push(mk.datum + ": ring " + a + ", the test's own " + e);
                  if (raw.ring.rx !== TH.n("--chart-bar-radius")) p.push(mk.datum + ": the ring's corner " + raw.ring.rx);
                }
              }
            } else if (rings.length !== 0) p.push(mk.datum + ": " + rings.length + " rings on a mark without one");
            const mv = moved();
            if (mk.emphasis === "move") {
              const mid = (mk.shape.from + mk.shape.to) / 2;
              const dx = Math.sin(mid * DEG) * moveBy, dy = -Math.cos(mid * DEG) * moveBy;
              const want = "translate(" + String(r2(dx)) + " " + String(r2(dy)) + ")";
              const mine = own.map((i) => els[i]);
              if (mv.length !== mine.length || mine.some((e) => !mv.includes(e))) p.push(mk.datum + ": " + mv.length + " moved elements, its own " + mine.length);
              mine.forEach((e) => { if (e.getAttribute("transform") !== want) p.push(mk.datum + ": move " + e.getAttribute("transform") + ", the test's own " + want); });
              const before = cm.rects[[...cm.written].indexOf(mine[0])], now = mine[0].getBoundingClientRect();
              const r = svg.getBoundingClientRect(), sx = r.width / frame.w, sy = r.height / frame.h;
              const sdx = now.left - before.left, sdy = now.top - before.top;
              raw.move = { value: mine[0].getAttribute("transform"), dx: sdx, dy: sdy, screen: Math.hypot(sdx, sdy) };
              if (!near(sdx, dx * sx, 0.6) || !near(sdy, dy * sy, 0.6)) p.push(mk.datum + ": moved on the screen by " + sdx + "," + sdy + ", the test's own " + dx * sx + "," + dy * sy);
            } else if (mv.length !== 0) p.push(mk.datum + ": " + mv.length + " moved elements on a mark without a move");
            p.push(...invisible());
            return { problems: p, ring: raw.ring || null, move: raw.move || null, otherOpacity: raw.otherOpacity || null };
            """.trimIndent(),
        )

    /** A picture without a highlight: no card, ring, focus ring, move or dimming, the writer's element count. */
    val NOTHING_JS =
        fn(
            "",
            """
            const p = [];
            if (shown().length !== 0) p.push("a card: " + shown());
            if (guides().length !== 0) p.push(guides().length + " rings");
            if (moved().length !== 0) p.push(moved().length + " moved elements");
            if (svg.classList.contains("dp-dimmed")) p.push("dp-dimmed");
            if (all().some((e) => /\bdp-active\b/.test(cls(e)))) p.push("dp-active");
            if (all().length !== cm.count) p.push(all().length + " elements, the writer wrote " + cm.count);
            return p;
            """.trimIndent(),
        )

    /** The card's elements and its text. */
    val CARD_ELEMENTS_JS =
        fn("", "return { elements: svg.querySelectorAll('.dp-tooltip').length, text: shown().join(' | ') };")

    /** A place `dist` px from the near point `datum`, inside the plot, with no other mark within 60 px of the point. */
    val REACH_PLACE_JS =
        fn(
            "[datum, dist]",
            """
            const p = [];
            const m = marks.find((k) => k.datum === datum);
            for (const k of marks) if (k !== m && Math.hypot(k.anchor.x - m.anchor.x, k.anchor.y - m.anchor.y) <= 60) p.push(k.datum + " lies within 60 px of " + datum);
            const dirs = [[1, 0], [-1, 0], [0, 1], [0, -1]];
            for (const [ux, uy] of dirs) {
              const x = m.shape.x + ux * dist, y = m.shape.y + uy * dist;
              if (x >= plot.x && x <= plot.x + plot.w && y >= plot.y && y <= plot.y + plot.h) return { x: x, y: y, problems: p };
            }
            p.push("no place " + dist + " px from " + datum + " inside the plot");
            return { x: 0, y: 0, problems: p };
            """.trimIndent(),
        )

    /** Every element of the document with a style attribute (D11, Security: 0). */
    const val STYLED_JS = "() => [...document.querySelectorAll('[style]')].map((e) => e.tagName + ' style=' + e.getAttribute('style'))"
}
