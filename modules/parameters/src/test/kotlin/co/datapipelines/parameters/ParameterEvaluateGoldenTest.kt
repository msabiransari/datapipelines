package co.datapipelines.parameters

import co.datapipelines.parameters.EvaluatorFixtures.templateSelect
import co.datapipelines.parameters.EvaluatorFixtures.version
import co.datapipelines.pipeline.OrgContext
import co.datapipelines.typesystem.LogicalType
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The ordinary evaluate's bytes, pinned (the parameter-set workspace spec §4.5, R1: "the completed
 * response and its clients are preserved"). `EvaluateResponseJson.bytes` of one deterministic
 * evaluate — a scripted selector runtime, a fixed clock, a fixed org tier — over a set that carries
 * every shape the writer has a branch for: a constants `SELECT` (the default), a template `SELECT`
 * whose submitted value no longer fits (a reset), a template `SELECT` on it, a database-fed `INPUT`,
 * a constrained `INPUT` with its default, an expression-HIDDEN input and an expression-DISABLED
 * select. The golden was recorded from the code BEFORE the observed evaluation (#375) touched the
 * evaluator; a byte that moves is a change every `/evaluate`, MCP and dashboard client sees.
 *
 * Regenerate ONLY deliberately: `REWRITE_GOLDEN=1 ./gradlew :modules:parameters:test --tests '*ParameterEvaluateGoldenTest*'`,
 * and review the diff like a behaviour change.
 */
class ParameterEvaluateGoldenTest {
    private val selectors =
        ScriptedSelectors().apply {
            this["acme/sales/state.sql"] = { request ->
                if (request.binds["country"] ==
                    "USA"
                ) {
                    ScriptedSelectors.options("NY", "NJ", default = "NY")
                } else {
                    ScriptedSelectors.options()
                }
            }
            this["acme/sales/city.sql"] = { request ->
                if (request.binds["state"] == "NY") ScriptedSelectors.options("NYC", "BUF") else ScriptedSelectors.options("NWK")
            }
            this["acme/sales/start_date.sql"] = { ScriptedSelectors.inputRows(LogicalType.DATE, LocalDate.parse("2026-01-01")) }
        }

    private val set =
        version(
            ParameterSetFixtures.countryJson(),
            templateSelect("state", dependsOn = listOf("country"), required = true),
            templateSelect("city", dependsOn = listOf("state")),
            EvaluatorFixtures.startDate(),
            ParameterSetFixtures.amountJson(),
            """
            { "name": "note", "label": "Note", "type": "STRING", "kind": "INPUT", "depends_on": ["country"],
              "hidden_expression": { "op": "not", "arg": { "op": "is_null", "arg": { "ref": "country" } } } }
            """.trimIndent(),
            """
            { "name": "channel", "label": "Channel", "type": "STRING", "kind": "SELECT", "cardinality": "SINGLE",
              "source": { "constants": [
                { "value": "web", "display_value": "Web", "is_default": false },
                { "value": "store", "display_value": "Store", "is_default": true } ] },
              "depends_on": ["country"],
              "disabled_expression": { "op": "eq", "left": { "ref": "country" }, "right": { "literal": "USA" } } }
            """.trimIndent(),
        )

    private val evaluator =
        ParameterEvaluator(
            selectors,
            SelectorPool(4, 64),
            ParametersConfig(),
            OrgContext.of("Dollar", "$", "01-01", "monday", "UTC"),
            Clock.fixed(Instant.parse("2026-10-02T00:00:00Z"), ZoneOffset.UTC),
        )

    /** The bytes `/evaluate` wraps in its envelope — the evaluate's one writer, compact, as served. */
    private fun actual(): String {
        val response =
            evaluator.evaluateBlocking(EvaluatorFixtures.WORKSPACE, set, EvaluatorFixtures.selections("country" to "USA", "state" to "XX"))
        return String(EvaluateResponseJson.bytes(response), Charsets.UTF_8) + "\n"
    }

    @Test
    fun `the fixture exercises every branch the golden claims - a reset, a hidden, a disabled, a sourced input, a default`() {
        val response =
            evaluator.evaluateBlocking(EvaluatorFixtures.WORKSPACE, set, EvaluatorFixtures.selections("country" to "USA", "state" to "XX"))
        val states = response.parameters.associate { it.definition.name to it.state }

        states.getValue("state").reset shouldBe true
        states.getValue("note").hidden shouldBe true
        states.getValue("channel").disabled shouldBe true
        states.getValue("start_date").origin shouldBe ValueOrigin.SOURCE
        states.getValue("min_order_amount").origin shouldBe ValueOrigin.DEFAULT
        states.getValue("city").origin shouldBe ValueOrigin.FIRST
    }

    @Test
    fun `the evaluate response matches the committed golden byte for byte`() {
        val golden = File("src/test/resources/golden/parameter-evaluate-response.json")
        val actual = actual()
        if (System.getenv("REWRITE_GOLDEN") != null) {
            golden.parentFile.mkdirs()
            golden.writeText(actual)
            return
        }
        golden.isFile shouldBe true
        val expected = golden.readText()
        if (expected != actual) {
            throw AssertionError(
                "parameter-evaluate-response golden drifted (regenerate deliberately with REWRITE_GOLDEN=1, and review the " +
                    "diff like a behaviour change every /evaluate client sees):\nEXPECTED:\n$expected\nACTUAL:\n$actual",
            )
        }
    }
}
