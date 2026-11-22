package co.datapipelines.application.charts

import com.fasterxml.jackson.databind.json.JsonMapper
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * The colour scale across the runtimes (brief 1050 A.3, G4; design record 2026-11-14-js-rendered-charts §4.4 `shade`):
 * Node's `colour.json` (written by emit.mjs) holds, per row, a scale's inputs and what `JsRendered.colour` made of them
 * — the step and the token of a value on a range, the end texts of a set of aggregates. The same inputs go through the
 * product's engine in the sandbox, under the product's limits, and the text that comes back is Node's, byte for byte.
 * A floor on each kind of row keeps the comparison from passing on an empty table. Brief 1053 A.3 adds the two-sided
 * scale's rows (`dsteps`: the drawn values, the middle, a value, its step and token; `dends`: aggregates, the middle and
 * the two end texts), recomputed and floored the same way.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JsRenderedColourScaleTest {
    private val sandbox = JsRenderedSandbox()

    @AfterAll
    fun close() = sandbox.close()

    @Test
    fun `the colour scale's steps, tokens and end texts in the sandbox are Node's, byte for byte`() {
        val node = ChartGuardFiles.resource("${ChartGuardFiles.EMITTED}/colour.json")
        val table = JSON.readTree(node)
        val inputs =
            JSON.writeValueAsString(
                mapOf(
                    "steps" to table["steps"].map { listOf(it[0], it[1], it[2]) },
                    "ends" to table["ends"].map { it[0] },
                    "dsteps" to table["dsteps"].map { listOf(it[0], it[1], it[2]) },
                    "dends" to table["dends"].map { listOf(it[0], it[1]) },
                ),
            )
        val sandboxText = sandbox.evaluate(COLOUR, mapOf("table" to inputs)) as String
        println(
            "JsRenderedColourScaleTest: ${table["steps"].size()} steps, ${table["ends"].size()} end texts; " +
                "two-sided ${table["dsteps"].size()} steps, ${table["dends"].size()} end texts",
        )
        sandboxText shouldBe node
        table["steps"].size() shouldBeGreaterThanOrEqual STEP_FLOOR
        table["ends"].size() shouldBeGreaterThanOrEqual END_FLOOR
        table["dsteps"].size() shouldBeGreaterThanOrEqual DIVERGING_STEP_FLOOR
        table["dends"].size() shouldBeGreaterThanOrEqual DIVERGING_END_FLOOR
        // Every step is a whole number from 1 to 7 or, for a null value, none (the null fill).
        for (row in table["steps"]) {
            if (row[2].isNull) {
                row[3].isNull shouldBe true
                row[4].asText() shouldBe "--chart-grid"
            } else {
                (row[3].isInt && row[3].asInt() in 1..7) shouldBe true
                row[4].asText() shouldBe "--chart-seq-${row[3].asInt()}"
            }
        }
        // The same for the two-sided ramp: a whole step from 1 to 7 and its --chart-div- token, or the null fill.
        for (row in table["dsteps"]) {
            if (row[2].isNull) {
                row[3].isNull shouldBe true
                row[4].asText() shouldBe "--chart-grid"
            } else {
                (row[3].isInt && row[3].asInt() in 1..7) shouldBe true
                row[4].asText() shouldBe "--chart-div-${row[3].asInt()}"
            }
        }
    }

    @Test
    fun `a BIGINT shade beyond 2 to the 53 is written from its exact text in the legend and the card by the product's entry`() {
        val fixture = "${ChartGuardFiles.EMITTED}/bar/shade-bigint/960x540"
        val answer = ChartEngines.engine.renderText(ChartGuardFiles.resource("$fixture.input.json"))
        answer shouldBe ChartGuardFiles.resource("$fixture.output.json")
        val primitives = JSON.readTree(answer)["primitives"]
        val legendTexts = primitives.filter { it["role"].asText() == "legend" && it["k"].asText() == "text" }.map { it["text"].asText() }
        legendTexts shouldBe listOf("5", BIG)
        val cardA = primitives.first { it["role"].asText() == "mark" && it["k"].asText() != "group" && it["tip"][0].asText() == "A" }["tip"]
        cardA.map { it.asText() } shouldBe listOf("A", "15", "units: $BIG")
    }

    private companion object {
        /** The sum of the BIGINT column's 9007199254740992 and 1, which a double writes as ...992. */
        const val BIG = "9,007,199,254,740,993"

        val JSON: JsonMapper = JsonMapper()

        /** emit.mjs writes 10 ranges x 23 values; a smaller table means the emit lost rows. */
        const val STEP_FLOOR = 230

        /** emit.mjs writes 6 sets of aggregates: beyond 2^53, a tie at each end, an exact text beside a float, floats. */
        const val END_FLOOR = 6

        /** emit.mjs writes 15 two-sided sets (403 step rows: values, sweep, borders, a null each). */
        const val DIVERGING_STEP_FLOOR = 403

        /** emit.mjs writes 15 two-sided end rows: exact mirrors, ties, an exponent middle, ends not finite, m of zero. */
        const val DIVERGING_END_FLOOR = 15

        /** emit.mjs's COLOUR over the inputs it wrote, as JSON text (the sandbox's copy). */
        val COLOUR =
            """
            var C = JsRendered.colour, t = JSON.parse(input.table);
            return JSON.stringify({
              steps: t.steps.map(function (r) {
                var s = C.sequential([{ num: r[0], exact: null }, { num: r[1], exact: null }]);
                return [r[0], r[1], r[2], C.step(s, r[2]), C.token(s, r[2])];
              }),
              ends: t.ends.map(function (e) {
                var s = C.sequential(e);
                return [e, s.loText, s.hiText];
              }),
              dsteps: t.dsteps.map(function (r) {
                var s = C.diverging(r[0].map(function (n) { return { num: n, exact: null }; }), r[1]);
                return [r[0], r[1], r[2], C.step(s, r[2]), C.token(s, r[2])];
              }),
              dends: t.dends.map(function (e) {
                var s = C.diverging(e[0], e[1]);
                return [e[0], e[1], s.loText, s.hiText];
              })
            });
            """.trimIndent()
    }
}
