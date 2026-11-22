package co.datapipelines.application.charts

import com.fasterxml.jackson.databind.json.JsonMapper
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * The text of a number a chart writes outside an axis (tooltip, value label, legend end, stack total), across the
 * runtimes (brief 1088 A.7; design record 2026-11-14-js-rendered-charts §4.7, I6, and its note of 2026-11-22 "Numbers
 * outside an axis, the short form with two digits, a stack beyond the number range"). The identity classes compare the
 * sandbox with Node at the frames of the fixtures, and no fixture reaches most of these values: the e form from 10^15 up,
 * the digits of a small value, exact BIGINT and DECIMAL texts, the values that are not finite. `value-text-probes.js` is
 * ONE script both runtimes evaluate; Node's half (`value-texts.test.mjs`) wrote `value-text-probes.json`, and the
 * sandbox's text is that snapshot byte for byte. The sweep of `value-texts.test.mjs` (A.2) stays Node's alone.
 *
 * The control: the same file's `valueTextProbesPlanted` evaluates the probes over a namespace whose `text.value` writes
 * one text (2.5e−9 at no format) with a hyphen for its true minus. Its text must differ from the snapshot in that one
 * character alone, so a comparison that could not go red on one text in one runtime is red.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JsRenderedValueTextsTest {
    private val sandbox = JsRenderedSandbox()

    @AfterAll
    fun close() = sandbox.close()

    private val probes: String by lazy {
        ChartGuardFiles.repoFile("${ChartGuardFiles.MODULE}/src/test/js/value-text-probes.js").readText()
    }

    private val node: String by lazy {
        ChartGuardFiles.repoFile("${ChartGuardFiles.MODULE}/src/test/js/value-text-probes.json").readText().removeSuffix("\n")
    }

    @Test
    fun `the texts of numbers outside an axis in the sandbox are Node's, byte for byte`() {
        val sandboxText = sandbox.evaluate(probes + "\nreturn valueTextProbes(JsRendered);", null) as String
        val count = JSON.readTree(node).size()
        println("JsRenderedValueTextsTest: $count probes, ${node.length} characters")
        sandboxText shouldBe node
        count shouldBeGreaterThanOrEqual PROBE_FLOOR
    }

    @Test
    fun `the control - one text written with a hyphen for its minus in the sandbox makes the text differ from the snapshot in that character alone`() {
        val planted = sandbox.evaluate(probes + "\nreturn valueTextProbesPlanted(JsRendered);", null) as String
        planted shouldNotBe node
        planted.split(WAS).size - 1 shouldBe 1
        node.split(WAS).size - 1 shouldBe 0
        planted.replace(WAS, IS) shouldBe node
    }

    private companion object {
        val JSON: JsonMapper = JsonMapper()

        /**
         * The probes of `value-text-probes.js` (brief 1088 A.7): 47 values at six formats (282), the prototype's 44 and
         * Infinity, minus Infinity and NaN; the brief's floor is the prototype's 264.
         */
        const val PROBE_FLOOR = 264

        /** The text of 2.5e-9 at no format as the control writes it (a hyphen), and as the engine writes it (U+2212). */
        const val WAS = "\"2.5e-9\""
        const val IS = "\"2.5e−9\""
    }
}
