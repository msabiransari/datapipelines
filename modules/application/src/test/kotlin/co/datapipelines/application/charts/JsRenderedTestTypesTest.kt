package co.datapipelines.application.charts

import com.fasterxml.jackson.databind.json.JsonMapper
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * The types for tests only across the runtimes (brief 1049 D5; design record 2026-11-14-js-rendered-charts §8 A2, A3,
 * O-17). A test type is a classic script under `modules/js-rendered/src/test/js/types/` that registers a type (and
 * possibly a frame of its own) after the product's program; it reaches the test classpath only
 * (`co/datapipelines/jsrendered/test/types/`), never a jar. Each part is counted on its own and floored:
 *
 * (i) identity — every picture of `types-index.txt`, evaluated in the sandbox under the product's limits as the product's
 * program, the test type's file and one render of Node's emitted input, has Node's canonical text byte for byte;
 * (ii) the sweep — every fixture of every test type at the invariant sweep's widths and heights (`sweep.json`, written by
 * emit.mjs from the lists the Node sweep reads) through the checker for I1, I2, I4 and I5, and I3 against the
 * fixture's hand-written `expect.marks`;
 * (iii) the engine's own sine and cosine (`trig.json`, 5,761 angles) recomputed in the sandbox, byte for byte.
 *
 * Also in the sandbox: the checker's arc bounds (A.3) and G7's planted rectangle 2 px beyond the frame, on test_plain
 * (brief 1049) and on test_grid (brief 1051); and the second value axis on the inputs of brief 1051's round-3, round-4
 * and round-5 corrections (`second-axis-probes.js`, compared with Node's `second-axis-probes.json`). Since #1059,
 * their extreme numbers fit in pictures too; `tick-texts.test.mjs` A.7 checks whole charts at those ends. A
 * process without the isolate fails at [JsRenderedSandbox]'s construction; nothing here skips.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JsRenderedTestTypesTest {
    private val sandbox = JsRenderedSandbox()

    @AfterAll
    fun close() = sandbox.close()

    /** `test_plain/<fixture>/<w>x<h>` lines, grouped by `test_plain/<fixture>`, frames in emitted order. */
    private val pictures: Map<String, List<String>> by lazy {
        ChartGuardFiles
            .resource("${ChartGuardFiles.EMITTED}/types-index.txt")
            .lines()
            .filter(String::isNotBlank)
            .groupBy({ it.substringBeforeLast('/') }, { it.substringAfterLast('/') })
    }

    private fun typeSource(name: String): String = ChartGuardFiles.resource("$TYPES/$name.js")

    private fun emitted(path: String): String = ChartGuardFiles.resource("${ChartGuardFiles.EMITTED}/_types/$path")

    @Test
    fun `Node emitted every fixture of every test type at the same frames, and the product's guards do not see them`() {
        val names = typeNames()
        names.size shouldBeGreaterThanOrEqual TYPE_FLOOR
        val expected = names.flatMap { n -> typeFixtures(n).map { "$n/$it" } }.toSet()
        pictures.keys shouldBe expected
        val frames = pictures.values.first()
        frames.size shouldBe FRAMES
        pictures.filterValues { it != frames }.keys.toList() shouldBe emptyList()
        // G6: the underscore directory keeps them out of the product's fixture types and its identity index.
        ChartGuardFiles.fixtureTypes().filter { it.startsWith("test_") || it.startsWith("_") } shouldBe emptyList()
        ChartGuardFiles
            .resource("${ChartGuardFiles.EMITTED}/index.txt")
            .lines()
            .count { it.startsWith("test_") || it.startsWith("_") } shouldBe 0
    }

    @Test
    fun `(i) each test type's picture in the sandbox is Node's, byte for byte`() {
        var compared = 0
        val different = mutableListOf<String>()
        for ((fixture, frames) in pictures) {
            val name = fixture.substringBefore('/')
            val inputs = frames.map { emitted("$fixture/$it.input.json") }

            @Suppress("UNCHECKED_CAST")
            val answers = sandbox.evaluate(typeSource(name) + "\n" + RENDER_ALL, mapOf("inputs" to inputs)) as List<String>
            answers.size shouldBe frames.size
            frames.forEachIndexed { i, frame ->
                compared++
                if (answers[i] != emitted("$fixture/$frame.output.json")) different += "$fixture/$frame"
            }
        }
        println(
            "JsRenderedTestTypesTest identity: ${typeNames().size} test types, " +
                "${pictures.size} fixtures x $FRAMES frames = $compared pictures",
        )
        different shouldBe emptyList()
        compared shouldBeGreaterThanOrEqual IDENTITY_FLOOR
    }

    @Test
    fun `(ii) every test-type fixture at the sweep's 35 widths and 5 heights meets I1 to I5 in the sandbox`() {
        val sweepText = ChartGuardFiles.resource("${ChartGuardFiles.EMITTED}/sweep.json")

        @Suppress("UNCHECKED_CAST")
        val sweep = JSON.readValue(sweepText, Map::class.java) as Map<String, List<Int>>
        val widths = sweep.getValue("widths")
        val heights = sweep.getValue("heights")
        // The Node sweep's lists (invariants.test.mjs, types.test.mjs through geometry.mjs), as emit.mjs wrote them.
        widths shouldBe (240..1600 step 40).toList()
        heights shouldBe listOf(100, 140, 200, 240, 480)
        val seen = IntArray(4)
        val violations = mutableListOf<String>()
        for (fixture in pictures.keys) {
            val name = fixture.substringBefore('/')
            val base = emitted("$fixture/960x540.input.json")
            val expect = expectedMarks(name, fixture.substringAfter('/'))
            for (h in heights) {
                @Suppress("UNCHECKED_CAST")
                val r =
                    sandbox.evaluate(
                        typeSource(name) + "\n" + SWEEP,
                        mapOf("base" to base, "widths" to widths, "h" to h, "expect" to expect),
                        withInvariants = true,
                    ) as Map<String, Any?>
                listOf("pictures", "rendered", "texts", "marks").forEachIndexed { i, k -> seen[i] += (r[k] as Number).toInt() }
                (r["violations"] as List<*>).forEach { violations += "$fixture $it" }
            }
        }
        println(
            "JsRenderedTestTypesTest sweep: ${pictures.size} fixtures x ${widths.size * heights.size} frames = ${seen[0]} pictures " +
                "(${seen[1]} rendered), ${seen[2]} texts, ${seen[3]} marks",
        )
        violations.take(12) shouldBe emptyList()
        seen[0] shouldBe pictures.size * widths.size * heights.size
        seen[0] shouldBeGreaterThanOrEqual SWEEP_FLOOR
        seen[1] shouldBeGreaterThanOrEqual RENDERED_FLOOR
        seen[2] shouldBeGreaterThanOrEqual TEXT_FLOOR
        seen[3] shouldBeGreaterThanOrEqual MARK_FLOOR
    }

    @Test
    fun `(iii) the engine's sine and cosine in the sandbox are Node's, byte for byte, at 5761 angles`() {
        val node = ChartGuardFiles.resource("${ChartGuardFiles.EMITTED}/trig.json")
        val sandboxText = sandbox.evaluate(TRIG, null) as String
        val angles = JSON.readTree(node).size()
        println("JsRenderedTestTypesTest trig: $angles angles")
        sandboxText shouldBe node
        angles shouldBe ANGLES
    }

    @Test
    fun `the checker's arc bounds in the sandbox - an arc bulging out of the frame is I2, the boxes are the hand's`() {
        @Suppress("UNCHECKED_CAST")
        val r = sandbox.evaluate(ARCS, null, withInvariants = true) as Map<String, Any?>
        r["escaping"] shouldBe listOf(60, 10, 80, 90)
        r["narrow"] shouldBe listOf("I2")
        r["wide"] shouldBe 0
        r["halfRight"] shouldBe listOf(50, 10, 90, 90)
        r["halfLeft"] shouldBe listOf(10, 10, 50, 90)
        r["quarter"] shouldBe listOf(10, 10, 50, 50)
    }

    @Test
    fun `G7 in the sandbox - test_plain's last rectangle planted 2 px beyond a 300 wide frame is one I2 naming the mark`() {
        val source = typeSource("test_plain")
        source.split(G7_ANCHOR).size shouldBe 2
        val input = emitted("test_plain/legend-top/300x180.input.json")
        val given = mapOf("input" to input)
        val planted = sandbox.evaluate(source.replace(G7_ANCHOR, G7_PLANT) + "\n" + CHECK_ONE, given, withInvariants = true)
        val clean = sandbox.evaluate(source + "\n" + CHECK_ONE, given, withInvariants = true)

        @Suppress("UNCHECKED_CAST")
        val bad = planted as List<Map<String, Any?>>
        bad.size shouldBe 1
        bad.single()["inv"] shouldBe "I2"
        (bad.single()["what"] as String).matches(Regex("""^mark of string:\w+ leaves the frame: [\d.]+,[\d.]+,302,[\d.]+$""")) shouldBe true
        (clean as List<*>).size shouldBe 0
    }

    @Test
    fun `G7 in the sandbox - test_grid's last cell planted 2 px beyond a 300 wide frame is one I2 naming the mark`() {
        val source = typeSource("test_grid")
        source.split(G7_ANCHOR).size shouldBe 2
        val given = mapOf("input" to emitted("test_grid/legend-top/300x180.input.json"))
        val planted = sandbox.evaluate(source.replace(G7_ANCHOR, G7_GRID_PLANT) + "\n" + CHECK_ONE, given, withInvariants = true)
        val clean = sandbox.evaluate(source + "\n" + CHECK_ONE, given, withInvariants = true)

        @Suppress("UNCHECKED_CAST")
        val bad = planted as List<Map<String, Any?>>
        bad.size shouldBe 1
        bad.single()["inv"] shouldBe "I2"
        (bad.single()["what"] as String).matches(Regex("""^mark of string:\w+ leaves the frame: [\d.]+,[\d.]+,302,[\d.]+$""")) shouldBe true
        (clean as List<*>).size shouldBe 0
    }

    @Test
    fun `rounds 3 to 5 - the second value axis on R1 to R6's inputs in the sandbox is Node's, byte for byte`() {
        val probes = ChartGuardFiles.repoFile("${ChartGuardFiles.MODULE}/src/test/js/second-axis-probes.js").readText()
        val node = ChartGuardFiles.repoFile("${ChartGuardFiles.MODULE}/src/test/js/second-axis-probes.json").readText().removeSuffix("\n")
        val sandboxText = sandbox.evaluate(probes + "\nreturn secondAxisProbes(JsRendered);", null) as String
        val count = JSON.readTree(node).size()
        println("JsRenderedTestTypesTest second-axis probes: $count probes, ${node.length} characters")
        sandboxText shouldBe node
        count shouldBeGreaterThanOrEqual PROBE_FLOOR
    }

    /** The test types in the repository (their files, without `.js`), sorted: what the index must cover. */
    private fun typeNames(): List<String> =
        ChartGuardFiles
            .repoFile("${ChartGuardFiles.MODULE}/src/test/js/types")
            .listFiles { f -> f.isFile && f.name.endsWith(".js") }
            .orEmpty()
            .map { it.name.removeSuffix(".js") }
            .sorted()

    private fun typeFixtures(name: String): List<String> = ChartGuardFiles.chartFixtures("_types/$name")

    private fun expectedMarks(
        name: String,
        fixture: String,
    ): Int {
        val file = ChartGuardFiles.repoFile("${ChartGuardFiles.MODULE}/src/test/fixtures/_types/$name/$fixture.json")
        return JSON
            .readTree(file)
            .path("expect")
            .path("marks")
            .asInt(-1)
    }

    private companion object {
        const val TYPES = "co/datapipelines/jsrendered/test/types"

        /**
         * Brief 1049's three test types, brief 1052's test_marks, brief 1051's five (test_points, test_grid, test_two_axes,
         * test_bins, test_bare), brief 1055's test_web, brief 1053's test_tones, brief 1054's two (test_switch, test_keys) and
         * brief 1066's test_bubbles.
         */
        const val TYPE_FLOOR = 14
        const val FRAMES = 7

        /**
         * 23 fixtures × 7 frames, and × 35 × 5; the rendered, text and mark floors as the Node sweep's (types.test.mjs).
         *
         * Brief 1052 adds the type test_marks and its 11 fixtures; brief 1051 adds five types and their 45 fixtures (44,
         * and round 3's signed-two-ticks); brief 1055 adds test_web and its 10 fixtures; brief 1053 adds test_tones and its
         * one fixture; brief 1054 adds test_switch (9 fixtures) and test_keys (13); brief 1066 adds test_bubbles and its 9
         * fixtures: 121 fixtures in all.
         */
        const val IDENTITY_FLOOR = (23 + 11 + 45 + 10 + 1 + 22 + 9) * 7
        const val SWEEP_FLOOR = (23 + 11 + 45 + 10 + 1 + 22 + 9) * 35 * 5
        const val RENDERED_FLOOR = 3000
        const val TEXT_FLOOR = 9000
        const val MARK_FLOOR = 15000
        const val ANGLES = 5761

        /**
         * The probes of `second-axis-probes.js`: five of R1, eight of R2, five of R3 (brief 1051 round 3), and eight of
         * round 4 (R4's log domains at both ends of the number range, and the boundary bank's own-code fixes), and ten of
         * round 5 (R6: left axes made by the real producers whose ticks stand a hair past an edge of their domain).
         */
        const val PROBE_FLOOR = 36

        val JSON = JsonMapper()

        /** Node's canonical text (engine.mjs `canonical`) of each emitted input. */
        val RENDER_ALL =
            """
            return input.inputs.map(function (t) { return JSON.stringify(JsRendered.render(JSON.parse(t))); });
            """.trimIndent()

        /** One height of the sweep: the fixture's emitted input at every width, through the checker, with I3. */
        val SWEEP =
            """
            var out = { pictures: 0, rendered: 0, texts: 0, marks: 0, violations: [] };
            for (var i = 0; i < input.widths.length; i++) {
              var w = input.widths[i], h = input.h, given = JSON.parse(input.base);
              given.frame = { w: w, h: h };
              var r = JsRendered.render(given), v = G.violations(r.primitives, w, h);
              out.pictures++; out.texts += v.texts; out.marks += v.marks;
              var bad = v.violations.map(function (x) { return x.inv + " at " + w + "x" + h + ": " + x.what; });
              if (r.state === "rendered") {
                out.rendered++;
                if (r.summary.marks !== input.expect) bad.push("I3 at " + w + "x" + h + ": summary.marks " + r.summary.marks + ", the fixture expects " + input.expect);
                if (v.marks !== r.summary.marks) bad.push("I3 at " + w + "x" + h + ": summary.marks " + r.summary.marks + ", the primitives hold " + v.marks);
              }
              out.violations = out.violations.concat(bad);
            }
            return out;
            """.trimIndent()

        /** emit.mjs's trig loop: [deg, sin, cos] for every multiple of 0.25° from −720° to 720°, as JSON text. */
        val TRIG =
            """
            var out = [];
            for (var i = 0; i <= 5760; i++) { var a = -720 + i / 4; out.push([a, JsRendered.trig.sin(a), JsRendered.trig.cos(a)]); }
            return JSON.stringify(out);
            """.trimIndent()

        /** trig.test.mjs's arc-bounds cases (D5, A.3). */
        val ARCS =
            """
            var esc = { k: "path", role: "mark", d: ["M", 60, 10, "A", 50, 60, 90], series: "s" };
            function box(d) { return G.markBox({ k: "path", d: d }).slice(); }
            return {
              escaping: G.markBox(esc).slice(),
              narrow: G.violations([esc], 70, 100).violations.map(function (v) { return v.inv; }),
              wide: G.violations([esc], 100, 100).violations.length,
              halfRight: box(["M", 50, 10, "A", 40, 50, 90]),
              halfLeft: box(["M", 50, 90, "A", 40, 50, 10]),
              quarter: box(["M", 10, 50, "A", 40, 50, 10])
            };
            """.trimIndent()

        /** The checker's violations over one render of `input.input`. */
        val CHECK_ONE =
            """
            var given = JSON.parse(input.input), r = JsRendered.render(given);
            return G.violations(r.primitives, given.frame.w, given.frame.h).violations;
            """.trimIndent()

        /** types.test.mjs's G7 plant, the same text. */
        const val G7_ANCHOR = "    return out;\n  }\n\n  R.type("
        const val G7_PLANT =
            "    if (out.length > 0 && plot.x + plot.w === 294) { var q = out.pop(); out.push(P.rect({ role: \"mark\", x: q.x, y: q.y, " +
                "w: 302 - q.x, h: q.h, fill: q.fill, color: q.color, series: q.series })); }\n" + G7_ANCHOR

        /** forms.test.mjs's G7 plant on test_grid (brief 1051), the same text: its marks read the plot from the hand. */
        const val G7_GRID_PLANT =
            "    if (out.length > 0 && hand.plot.x + hand.plot.w === 294) { var q = out.pop(); out.push(P.rect({ role: \"mark\", " +
                "x: q.x, y: q.y, w: 302 - q.x, h: q.h, fill: q.fill, color: q.color, series: q.series })); }\n" + G7_ANCHOR
    }
}
