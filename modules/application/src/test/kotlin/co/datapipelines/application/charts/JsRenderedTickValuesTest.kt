package co.datapipelines.application.charts

import com.fasterxml.jackson.databind.json.JsonMapper
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * The ranges, ticks and places a value axis chooses at the ends of the number range, across the runtimes (brief 1063
 * A.8; design record 2026-11-14-js-rendered-charts §4.6, I6, and its note of 2026-11-22 "Axis numbers at the ends of the
 * number range"). A tick there is the double its decimal text reads as, so the sandbox must read every such text to the
 * same double as Node: no fixture and no probe of `second-axis-probes.js` reaches those paths, so this class is their
 * only comparison. `tick-value-probes.js` is ONE script both runtimes evaluate; Node's half (`tick-values.test.mjs`)
 * wrote `tick-value-probes.json`, and the sandbox's text is that snapshot byte for byte.
 *
 * The control: the same file's `tickValueProbesPlanted` evaluates the probes over a namespace whose `scales.nice` gives
 * one tick of 0 … 1e30 as the product the engine wrote before lane 1063 (2.4999999999999997e29). Its text must differ
 * from the snapshot in that one number alone, so a comparison that could not go red on one tick in one runtime is red.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JsRenderedTickValuesTest {
    private val sandbox = JsRenderedSandbox()

    @AfterAll
    fun close() = sandbox.close()

    private val probes: String by lazy {
        ChartGuardFiles.repoFile("${ChartGuardFiles.MODULE}/src/test/js/tick-value-probes.js").readText()
    }

    private val node: String by lazy {
        ChartGuardFiles.repoFile("${ChartGuardFiles.MODULE}/src/test/js/tick-value-probes.json").readText().removeSuffix("\n")
    }

    @Test
    fun `the ranges, ticks and places at the ends of the number range in the sandbox are Node's, byte for byte`() {
        val sandboxText = sandbox.evaluate(probes + "\nreturn tickValueProbes(JsRendered);", null) as String
        val count = JSON.readTree(node).size()
        println("JsRenderedTickValuesTest: $count probes, ${node.length} characters")
        sandboxText shouldBe node
        count shouldBeGreaterThanOrEqual PROBE_FLOOR
    }

    @Test
    fun `the control - one tick written as the old product in the sandbox makes the text differ from the snapshot in that number alone`() {
        val planted = sandbox.evaluate(probes + "\nreturn tickValueProbesPlanted(JsRendered);", null) as String
        planted shouldNotBe node
        planted.split(WAS).size - 1 shouldBe 1
        node.split(WAS).size - 1 shouldBe 0
        planted.replace(WAS, IS) shouldBe node
    }

    private companion object {
        val JSON: JsonMapper = JsonMapper()

        /**
         * The probes of `tick-value-probes.js` (brief 1063 A.8): 13 decimal ticks, 4 spans beyond the largest double, 8 one
         * value, 13 two values, 13 log ranges, 13 whole axes, 3 log placings, 12 second axes (79); the brief's floor is 78.
         */
        const val PROBE_FLOOR = 78

        /** The tick 2.5e29 of 0 … 1e30 in four intervals as the engine wrote it before lane 1063, and as it writes it now. */
        const val WAS = "2.4999999999999997e+29"
        const val IS = "2.5e+29"
    }
}
