package co.datapipelines.application.charts

import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.TestInstance
import java.math.BigDecimal

/**
 * I6 / A3 (design record 2026-11-14-js-rendered-charts §8; brief 985 A.5, G3; on the product's entry since brief 986
 * D10, G5): for every chart fixture at every frame `jsRenderedEmit` drew, the PRODUCT's chart entry
 * ([JsRenderedChartEngine.renderText]: the counted program, `JavaScriptEngine.call`, the script pool and the product's
 * limits) answers the SAME input text with the same canonical JSON text as Node, byte for byte; and the primitives of
 * that answer meet I1, I2, I4 and I5 (A2's second half), checked in the sandbox by the one checker the Node suite runs
 * (`geometry.js`). LA11's helper chooses the same ink in both runtimes (G13).
 *
 * The `_layout` fixture is not part of it: the label row's identity across the runtimes is carried by every chart
 * fixture, whose `render` runs it.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JsRenderedIdentityTest {
    private val sandbox = JsRenderedSandbox()

    @AfterAll
    fun close() = sandbox.close()

    /** `bar/<name>/<w>x<h>` → grouped by `bar/<name>`, frames in emitted order. */
    private val pictures: Map<String, List<String>> by lazy {
        ChartGuardFiles
            .resource("${ChartGuardFiles.EMITTED}/index.txt")
            .lines()
            .filter(String::isNotBlank)
            .groupBy({ it.substringBeforeLast('/') }, { it.substringAfterLast('/') })
    }

    @Test
    fun `Node emitted every chart fixture of the source tree, each at the same frames, and nothing else`() {
        val expected = ChartGuardFiles.fixtureTypes().flatMap { t -> ChartGuardFiles.chartFixtures(t).map { "$t/$it" } }.toSet()
        expected.size shouldBeGreaterThanOrEqual FIXTURE_FLOOR
        pictures.keys shouldBe expected
        val frames = pictures.values.first()
        frames.size shouldBeGreaterThanOrEqual FRAME_FLOOR
        pictures.filterValues { it != frames }.keys.toList() shouldBe emptyList()
    }

    @TestFactory
    fun `the product's entry draws each chart fixture byte for byte as Node did, and the picture meets I1 I2 I4 I5`(): List<DynamicTest> {
        check(pictures.size >= FIXTURE_FLOOR) { "only ${pictures.size} chart fixtures emitted (floor $FIXTURE_FLOOR)" }
        return pictures.map { (fixture, frames) -> DynamicTest.dynamicTest(fixture) { assertPicturesOf(fixture, frames) } }
    }

    private fun assertPicturesOf(
        fixture: String,
        frames: List<String>,
    ) {
        val base = "${ChartGuardFiles.EMITTED}/$fixture"
        val inputs = frames.map { ChartGuardFiles.resource("$base/$it.input.json") }
        val answers = inputs.map { ChartEngines.engine.renderText(it) }
        val different = frames.filterIndexed { i, frame -> answers[i] != ChartGuardFiles.resource("$base/$frame.output.json") }
        val results = checked(inputs, answers)
        results.size shouldBe frames.size
        val violations = mutableListOf<String>()
        frames.forEachIndexed { i, frame ->
            @Suppress("UNCHECKED_CAST")
            (results[i]["violations"] as List<Map<String, Any?>>).forEach { v -> violations += "$frame ${v["inv"]}: ${v["what"]}" }
        }
        different shouldBe emptyList()
        violations shouldBe emptyList()
    }

    /** The checker over the entry's [answers], each at its input's frame. */
    @Suppress("UNCHECKED_CAST")
    private fun checked(
        inputs: List<String>,
        answers: List<String>,
    ): List<Map<String, Any?>> =
        sandbox.evaluate(CHECK, mapOf("inputs" to inputs, "answers" to answers), withInvariants = true) as List<Map<String, Any?>>

    @Test
    fun `the ink on a fill is chosen the same in the sandbox as in Node (LA11)`() {
        val node = ChartGuardFiles.resource("${ChartGuardFiles.EMITTED}/on-fill.json")
        val sandboxText = sandbox.evaluate(ON_FILL, null) as String
        sandboxText shouldBe node
        // 8 series + 7 ramp steps, two themes, and the grey: the comparison saw every choice.
        Regex("\"token\"").findAll(node).count() shouldBe ON_FILL_CHOICES
    }

    @Test
    fun `the checker the sandbox runs sees texts and marks (non-vacuity)`() {
        val fixture = pictures.keys.first { it.startsWith("bar/") }
        val frame = pictures.getValue(fixture).first()
        val input = ChartGuardFiles.resource("${ChartGuardFiles.EMITTED}/$fixture/$frame.input.json")
        val r = checked(listOf(input), listOf(ChartEngines.engine.renderText(input)))
        (r.single()["texts"] as Number).toInt() shouldBeGreaterThan 0
        (r.single()["marks"] as Number).toInt() shouldBeGreaterThan 0
    }

    @Test
    fun `tick text keeps each tick's value in the sandbox, read back by this test's own decimal reader (R2)`() {
        val direct =
            sandbox.evaluate(
                """
                var cases = [[[0, 0.0001, 0.0002], null], [[0, 0.00001, 0.00002, 0.00003], null],
                  [[0, 0.0000025, 0.000005, 0.0000075, 0.00001], null], [[0, 0.0001, 0.0002], "percent"],
                  [[0, 2.5e17, 5e17, 7.5e17, 1e18], null], [[1e-9], null],
                  [[-1.7976931348623157e308, 1.7976931348623157e308], null], [[0, 2.5e-9, 5e-9], "percent"], [[0.0000105], null],
                  [[0, 250000000, 500000000, 750000000, 1000000000, 1250000000], null], [[0, 1250, 2500], null],
                  [[-1250000000, -1000000000, 0], null], [[-2790.9621305348655, 2311.2831141308657], "decimal(2)"]];
                return cases.map(function (c) { return JsRendered.text.ticks(c[0], c[1]).join("|"); }).join("\n");
                """.trimIndent(),
                null,
            ) as String
        direct.lines() shouldBe
            listOf(
                "0|0.0001|0.0002",
                "0|0.00001|0.00002|0.00003",
                // From 10^15 up and below 10^-5 an axis writes the e form, the power's minus U+2212 (#1059, owner 2026-11-21).
                "0|2.5e\u22126|5e\u22126|7.5e\u22126|1e\u22125",
                "0%|0.01%|0.02%",
                "0|2.5e17|5e17|7.5e17|1e18",
                "1e\u22129",
                "\u22121.797693e308|1.797693e308",
                "0%|2.5e\u22127%|5e\u22127%",
                "0.0000105",
                // The short form carries up to two digits after the point (#1089, owner 2026-11-22); plain digits at two
                // digits are tried before any third digit, so the unrounded ends below keep two digits.
                "0|250M|500M|750M|1B|1.25B",
                "0|1.25K|2.5K",
                "\u22121.25B|\u22121B|0",
                "\u22122,790.96|2,311.28",
            )
        // This test's reader reads every one of those labels, the e form's exponent as written.
        direct.lines().flatMap { it.split("|") }.forEach { readLabel(it) }
        readLabel("1e\u22129") shouldBe BigDecimal("1E-9")
        readLabel("5e\u22127%") shouldBe BigDecimal("5E-9")
        readLabel("1.25B") shouldBe BigDecimal("1250000000")
        readLabel("\u22121.25K") shouldBe BigDecimal("-1250")
        for ((fixture, most) in SMALL_VALUE_FIXTURES) {
            for (frame in listOf("960x540", "300x180")) {
                val input = ChartGuardFiles.resource("${ChartGuardFiles.EMITTED}/$fixture/$frame.input.json")
                val texts = sandbox.evaluate(VALUE_TICKS, mapOf("inputs" to listOf(input))) as String
                val values = texts.split("|").map(::readLabel)
                val where = "$fixture $frame: $texts"
                check(values.size >= 2) { where }
                values.first().signum() shouldBe 0
                val step = values[1] - values[0]
                (step.signum() > 0) shouldBe true
                values.zipWithNext { a, b -> (b - a).compareTo(step) }.all { it == 0 }.let { check(it) { "unevenly spaced: $where" } }
                check(values.last() >= BigDecimal(most)) { "the axis does not cover $most: $where" }
            }
        }
    }

    /**
     * A tick label's exact value, by this test alone: grouping commas dropped, U+2212 read as minus, % moved two places,
     * a unit letter of the short form (K, M, B, T) moved three places each (#1089).
     */
    private fun readLabel(label: String): BigDecimal {
        val t = label.replace(",", "").replace('\u2212', '-')
        val unit = SHORT_UNITS.indexOf(t.last())
        return when {
            t.endsWith("%") -> BigDecimal(t.dropLast(1)).movePointLeft(2)
            unit >= 0 -> BigDecimal(t.dropLast(1)).movePointRight(UNIT_PLACES * (unit + 1))
            else -> BigDecimal(t)
        }
    }

    private companion object {
        /** The short form's unit letters, each a thousand times the one before it, and the places one letter moves. */
        const val SHORT_UNITS = "KMBT"
        const val UNIT_PLACES = 3

        /** #985 R2's rendered fixtures and the largest value each delivers (read from the fixture files). */
        val SMALL_VALUE_FIXTURES = listOf("bar/ticks-small-decimal" to "0.0002", "bar/ticks-small-percent" to "0.00003")

        /** The value axis's tick texts of a vertical bar, bottom to top, joined by "|". */
        val VALUE_TICKS =
            """
            var given = JSON.parse(input.inputs[0]), r = JsRendered.render(given), plot = r.index.plot;
            return r.primitives.filter(function (p) {
              return p.k === "text" && p.role === "tick" && p.anchor === "end" && !p.rotate && p.x < plot.x;
            }).sort(function (a, b) { return b.y - a.y; }).map(function (p) { return p.shown === null || p.shown === undefined ? p.text : p.shown; }).join("|");
            """.trimIndent()

        /**
         * The chart fixtures the emitter must have drawn, at least: the bar count (lane 985's 43, brief 1050's twelve shaded
         * bars, brief 1053's fifteen two-sided bars and one sequential bar of seven steps).
         */
        const val FIXTURE_FLOOR = 71

        /** emit.mjs's frames: one per level, one below the smallest, and the full and compact boundaries (#985 R1). */
        const val FRAME_FLOOR = 7

        const val ON_FILL_CHOICES = 31

        /** Per entry answer: I1 I2 I4 I5 over its primitives at its input's frame, and what the checker saw. */
        val CHECK =
            """
            var out = [];
            for (var i = 0; i < input.inputs.length; i++) {
              var given = JSON.parse(input.inputs[i]);
              var r = JSON.parse(input.answers[i]);
              var v = G.violations(r.primitives, given.frame.w, given.frame.h);
              out.push({ violations: v.violations, texts: v.texts, marks: v.marks });
            }
            return out;
            """.trimIndent()

        /** emit.mjs's on-fill loop, verbatim in meaning: the same choices in the same order, as JSON text. */
        val ON_FILL =
            """
            var T = JsRendered.theme, choices = [];
            ["light", "dark"].forEach(function (theme) {
              var d = T.defaults(theme), light = d["--chart-on-fill-light"].slice(), dark = d["--chart-on-fill-dark"].slice();
              [["--chart-series-", 8], ["--chart-seq-", 7]].forEach(function (p) {
                for (var i = 1; i <= p[1]; i++) {
                  var fill = d[p[0] + i].slice();
                  choices.push({ theme: theme, token: p[0] + i, fill: fill, light: light, dark: dark, ink: T.onFill(fill, light, dark), swapped: T.onFill(fill, dark, light) });
                }
              });
            });
            var d = T.defaults("light"), light = d["--chart-on-fill-light"].slice(), dark = d["--chart-on-fill-dark"].slice(), grey = [128, 128, 128];
            choices.push({ theme: "light", token: "grey-128", fill: grey, light: light, dark: dark, ink: T.onFill(grey, light, dark), swapped: T.onFill(grey, dark, light) });
            return JSON.stringify(choices);
            """.trimIndent()
    }
}
