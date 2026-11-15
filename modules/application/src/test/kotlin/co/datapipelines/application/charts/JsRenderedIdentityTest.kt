package co.datapipelines.application.charts

import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.TestInstance

/**
 * I6 / A3 (design record 2026-11-14-js-rendered-charts §8; brief 985 A.5, G3): for every chart fixture at every frame
 * `jsRenderedEmit` drew, the sandbox's `JsRendered.render` of the SAME input text returns the same canonical JSON text
 * as Node, byte for byte; and the same primitives meet I1, I2, I4 and I5 in the sandbox too (A2's second half), checked
 * by the one checker the Node suite runs (`geometry.js`). LA11's helper chooses the same ink in both runtimes (G13).
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
    fun `the sandbox draws each chart fixture byte for byte as Node did, and the picture meets I1 I2 I4 I5`(): List<DynamicTest> {
        check(pictures.size >= FIXTURE_FLOOR) { "only ${pictures.size} chart fixtures emitted (floor $FIXTURE_FLOOR)" }
        return pictures.map { (fixture, frames) -> DynamicTest.dynamicTest(fixture) { assertPicturesOf(fixture, frames) } }
    }

    private fun assertPicturesOf(
        fixture: String,
        frames: List<String>,
    ) {
        val base = "${ChartGuardFiles.EMITTED}/$fixture"
        val inputs = frames.map { ChartGuardFiles.resource("$base/$it.input.json") }

        @Suppress("UNCHECKED_CAST")
        val results = sandbox.evaluate(DRAW, mapOf("inputs" to inputs), withInvariants = true) as List<Map<String, Any?>>
        results.size shouldBe frames.size
        val different = mutableListOf<String>()
        val violations = mutableListOf<String>()
        frames.forEachIndexed { i, frame ->
            val r = results[i]
            if (r["text"] != ChartGuardFiles.resource("$base/$frame.output.json")) different += frame
            @Suppress("UNCHECKED_CAST")
            (r["violations"] as List<Map<String, Any?>>).forEach { v -> violations += "$frame ${v["inv"]}: ${v["what"]}" }
        }
        different shouldBe emptyList()
        violations shouldBe emptyList()
    }

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

        @Suppress("UNCHECKED_CAST")
        val r = sandbox.evaluate(DRAW, mapOf("inputs" to listOf(ChartGuardFiles.resource("${ChartGuardFiles.EMITTED}/$fixture/$frame.input.json"))), withInvariants = true) as List<Map<String, Any?>>
        (r.single()["texts"] as Number).toInt() shouldBeGreaterThan 0
        (r.single()["marks"] as Number).toInt() shouldBeGreaterThan 0
    }

    private companion object {
        /** The chart fixtures the emitter must have drawn, at least (lane 985 delivers 40). */
        const val FIXTURE_FLOOR = 40

        /** emit.mjs's frames: one per level and one below the smallest. */
        const val FRAME_FLOOR = 5

        const val ON_FILL_CHOICES = 31

        /** Per input text: the canonical JSON of the result (Node's `canonical`: JSON.stringify) and its I1 I2 I4 I5. */
        val DRAW =
            """
            var out = [];
            for (var i = 0; i < input.inputs.length; i++) {
              var given = JSON.parse(input.inputs[i]);
              var r = JsRendered.render(given);
              var v = G.violations(r.primitives, given.frame.w, given.frame.h);
              out.push({ text: JSON.stringify(r), violations: v.violations, texts: v.texts, marks: v.marks });
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
