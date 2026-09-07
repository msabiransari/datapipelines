package co.datapipelines.pipeline

import com.fasterxml.jackson.databind.node.ObjectNode
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * 078 A5-composition — [DerivedInputs], the read-surface merge that lists every calculator
 * `context_key` under `parameters` as a derived optional input.
 *
 * The mcp-server and web surfaces assert the merged shape end to end; these are the unit pins
 * for the merge itself — including the two edges the surfaces cannot see: the collision skip
 * (a declared parameter owns its name, §12.10) and the no-calculators no-op that must leave
 * the body byte-identical (derived on read, never stored).
 */
class DerivedInputsTest {
    private fun bodyOf(pipeline: Pipeline): ObjectNode = PipelineJson.objectMapper().valueToTree(pipeline)

    @Test
    fun `a calculator key merges as a derived optional input typed by the kind's output`() {
        val body = bodyOf(Fixtures.pipeline(nodes = listOf(Fixtures.calculatorNode(), Fixtures.node())))

        DerivedInputs.mergeInto(body)

        val derived = body.withObject("/parameters").get("run_fiscal_quarter")
        derived.get("type").asText() shouldBe "INTEGER"
        derived.get("required").asBoolean() shouldBe false
        derived.get("derived").asBoolean() shouldBe true
    }

    @Test
    fun `an ANY-output kind spells its type ANY`() {
        val body =
            bodyOf(
                Fixtures.pipeline(
                    nodes =
                        listOf(
                            Fixtures.calculatorNode(kind = "if_null", inputs = emptyMap(), contextKey = "run_region"),
                        ),
                ),
            )

        DerivedInputs.mergeInto(body)

        body
            .withObject("/parameters")
            .get("run_region")
            .get("type")
            .asText() shouldBe "ANY"
    }

    @Test
    fun `a declared parameter keeps its own entry and wins its name - no derived flag, no overwrite`() {
        val body =
            bodyOf(
                Fixtures.pipeline(
                    nodes = listOf(Fixtures.calculatorNode(), Fixtures.node()),
                    parameters = mapOf("run_fiscal_quarter" to Parameter(type = co.datapipelines.typesystem.LogicalType.STRING)),
                ),
            )

        DerivedInputs.mergeInto(body)

        val entry = body.withObject("/parameters").get("run_fiscal_quarter")
        entry.get("type").asText() shouldBe "STRING"
        entry.has("derived") shouldBe false
    }

    @Test
    fun `a pipeline without calculators is returned byte-identical`() {
        val body = bodyOf(Fixtures.pipeline())
        val before = body.toString()

        DerivedInputs.mergeInto(body)

        body.toString() shouldBe before
    }

    @Test
    fun `a body whose parameters block is missing gains one`() {
        val pipeline = Fixtures.pipeline(nodes = listOf(Fixtures.calculatorNode(), Fixtures.node()))
        val body = bodyOf(pipeline)
        body.remove("parameters")

        DerivedInputs.mergeInto(body)

        body
            .withObject("/parameters")
            .get("run_fiscal_quarter")
            .get("derived")
            .asBoolean() shouldBe true
    }
}
