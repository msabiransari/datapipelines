package co.datapipelines.templates

import com.fasterxml.jackson.databind.JsonNode
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * A template payload's `tests` block is bound from the payload TEXT (#1038, review C2): an authored
 * body fixture keeps its exact decimals and a repeated member for the save-time runner, through the
 * save payload and the stored `tests_json` round trip, while the rest of the payload binds as before.
 */
class TemplateExactTestsBindingTest {
    private val payload =
        """
        {"type":"jsonata","display_name":"X","description":"Y","body":"inputs.order",
         "contract":{"mode":"value","inputs":{"order":{"kind":"body"}},"output":{"kind":"object"}},
         "tests":[
           {"name":"exact","input":{"inputs":{"order":{"price":$LONG_FRACTION}}},"expect":{"output":{"price":1}}},
           {"name":"repeated","input":{"inputs":{"order":{"a":1},"order":{"a":2}}},
            "expect":{"refusal":"pipeline.script.input_contract_violation"}},
           {"name":"nested","input":{"inputs":{"order":{"a":1,"a":2}}},
            "expect":{"refusal":"pipeline.script.input_contract_violation"}}
         ]}
        """.trimIndent()

    private fun inputsOf(
        tests: List<ScriptTestCase>?,
        name: String,
    ): FixtureInputs =
        tests!!
            .single { it.name == name }
            .input.inputs
            .shouldBeInstanceOf<FixtureInputs>()

    @Test
    fun `a saved payload's body fixtures keep their exact text and repeated members`() {
        val draft = TemplateDeserializer().read(payload).shouldBeInstanceOf<TemplateDeserializationOutcome.Parsed>().draft
        inputsOf(draft.tests, "exact").exactText("order") shouldBe """{"price":$LONG_FRACTION}"""
        inputsOf(draft.tests, "repeated").isRepeated("order") shouldBe true
        inputsOf(draft.tests, "nested").exactText("order") shouldBe """{"a":1,"a":2}"""
        withClue("the ordinary value is unchanged for every other reader") {
            (inputsOf(draft.tests, "exact")["order"] as Map<*, *>)["price"] shouldBe LONG_FRACTION.toDouble()
        }
    }

    @Test
    fun `the stored tests round trip keeps the exact text`() {
        val draft = TemplateDeserializer().read(payload).shouldBeInstanceOf<TemplateDeserializationOutcome.Parsed>().draft
        val stored = ScriptBlocks.writeTests(draft.tests, draft.contract)!!
        stored shouldContain """{"price":$LONG_FRACTION}"""
        stored shouldContain """{"a":1,"a":2}"""
        val reread = ScriptBlocks.readTests(stored)
        inputsOf(reread, "exact").exactText("order") shouldBe """{"price":$LONG_FRACTION}"""
        reread shouldBe draft.tests
    }

    @Test
    fun `a read shape built as a tree keeps a fixture's trailing zeros`() {
        val draft = TemplateDeserializer().read(payload).shouldBeInstanceOf<TemplateDeserializationOutcome.Parsed>().draft
        val mapper = TemplateJson.objectMapper()
        mapper.writeValueAsString(mapper.valueToTree<JsonNode>(draft)) shouldContain """{"price":$LONG_FRACTION}"""
    }

    @Test
    fun `a tree with no text binds its tests as it always did`() {
        val tree = ScriptBlocks.mapper.readTree(payload)
        val draft = TemplateDeserializer().fromTree(tree).shouldBeInstanceOf<TemplateDeserializationOutcome.Parsed>().draft
        val exact =
            draft.tests!!
                .single { it.name == "exact" }
                .input.inputs!!
        (exact["order"] as Map<*, *>)["price"] shouldBe LONG_FRACTION.toDouble()
    }

    private companion object {
        const val LONG_FRACTION = "0.123456789012345678901234567890"
    }
}
