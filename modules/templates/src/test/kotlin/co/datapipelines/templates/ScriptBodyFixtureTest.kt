package co.datapipelines.templates

import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.TemplateType
import co.datapipelines.scripting.CompiledScript
import co.datapipelines.scripting.ConsoleLevel
import co.datapipelines.scripting.ConsoleLine
import co.datapipelines.scripting.EngineCapabilities
import co.datapipelines.scripting.EvaluationLimits
import co.datapipelines.scripting.JsonataEngine
import co.datapipelines.scripting.ScriptEngine
import co.datapipelines.scripting.ScriptEvaluationPool
import co.datapipelines.scripting.ScriptLanguage
import co.datapipelines.typesystem.LogicalType
import co.datapipelines.typesystem.body.StructuredBodyLimits
import com.fasterxml.jackson.module.kotlin.readValue
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Duration

/**
 * Authored body fixtures read from TEXT (#1038, review C2 and C3): the body is decoded from the
 * exact text [FixtureInputs] kept — exact fractions, duplicate members refused, the canonical byte
 * budget — and a body case's outcome and suite verdict follow the default diagnostics policy, while
 * table and value inputs and bodyless cases keep the behaviour they always had.
 */
class ScriptBodyFixtureTest {
    /** A real JSONata engine that also prints the input's text to the console, as a script's `console.log` would. */
    private class ConsoleEchoEngine : ScriptEngine {
        private val delegate = JsonataEngine()
        override val type: ScriptLanguage get() = delegate.type
        override val capabilities: EngineCapabilities get() = delegate.capabilities

        override fun compile(body: String): CompiledScript = delegate.compile(body)

        override fun evaluate(
            script: CompiledScript,
            input: Any?,
            limits: EvaluationLimits,
        ): Any? {
            limits.console?.accept(ConsoleLine(ConsoleLevel.LOG, "saw $input"))
            return delegate.evaluate(script, input, limits)
        }
    }

    private val engine = ConsoleEchoEngine()
    private val pool = ScriptEvaluationPool(2, 2, Duration.ofSeconds(5), ScriptEvaluationPool.SYSTEM)

    private fun runner(limits: StructuredBodyLimits = StructuredBodyLimits()) =
        ScriptTestRunner(
            mapOf(ScriptLanguage.JSONATA to engine),
            pool,
            Duration.ofSeconds(10),
            Duration.ofSeconds(30),
            bodyLimits = limits,
        )

    private val contract =
        ScriptContract(
            mode = ScriptMode.TABLE,
            inputs = mapOf("order" to ScriptInput.Body(), "channel" to ScriptInput.Value(LogicalType.STRING)),
            output = ScriptOutput.Table(listOf(ContractColumn("price_text", LogicalType.STRING, nullable = true))),
        )

    /** The price as text, an engine error quoting the body, or the channel when the order carries neither. */
    private val program =
        """
        ${'$'}exists(inputs.order.boom) ? ${'$'}error("engine says " & inputs.order.secret) :
        ${'$'}exists(inputs.order.price) ? [{"price_text": ${'$'}string(inputs.order.price)}] :
        [{"price_text": ${'$'}exists(inputs.channel) ? inputs.channel : null}]
        """.trimIndent()

    private val invariants = listOf(ScriptInvariant("one_row", "\$count(rows) = 1", "exactly one row $CANARY_AUTHOR"))

    /** The test input as a surface binds it: from JSON text. */
    private fun input(json: String): ScriptTestInput = ScriptBlocks.mapper.readValue(json, ScriptTestInput::class.java)

    private fun run(
        input: ScriptTestInput,
        limits: StructuredBodyLimits = StructuredBodyLimits(),
    ): ScriptTestRunner.RunOutcome {
        val compiledInvariants = invariants.map { it to engine.compile(it.expr) }
        return runner(limits).runCase("case", engine, engine.compile(program), contract, compiledInvariants, input)
    }

    private fun priceOf(outcome: ScriptTestRunner.RunOutcome): Any? =
        (outcome.shouldBeInstanceOf<ScriptTestRunner.RunOutcome.Evaluated>().output as List<*>)
            .single()
            .let { (it as Map<*, *>)["price_text"] }

    @Test
    fun `fixture inputs keep each member's exact text beside the ordinary value, and write it back unchanged`() {
        val read = input("""{"inputs":{"order":{"price":$LONG_FRACTION,"n":1.50},"channel":"web"}}""")
        val inputs = read.inputs.shouldBeInstanceOf<FixtureInputs>()
        inputs.exactText("order") shouldBe """{"price":$LONG_FRACTION,"n":1.50}"""
        withClue("the ordinary value is what an untyped bind always gave a table or value input") {
            (inputs["order"] as Map<*, *>)["price"] shouldBe LONG_FRACTION.toDouble()
            inputs["channel"] shouldBe "web"
        }
        val written = ScriptBlocks.mapper.writeValueAsString(read)
        written shouldContain """"order":{"price":$LONG_FRACTION,"n":1.50}"""
        input(written) shouldBe read
        withClue("a map built in code has no exact text and writes its ordinary values") {
            ScriptBlocks.mapper.writeValueAsString(ScriptTestInput(inputs = mapOf("channel" to "web"))) shouldContain """"channel":"web""""
        }
    }

    @Test
    fun `a long fraction read from text reaches the transformer exactly, where the ordinary double would round it`() {
        priceOf(run(input("""{"inputs":{"channel":"web","order":{"price":$LONG_FRACTION}}}"""))) shouldBe LONG_FRACTION
        withClue("non-vacuity: the same fixture bound without its text is the double every authored path used to pass") {
            val asDouble = mapOf("order" to mapOf("price" to LONG_FRACTION.toDouble()), "channel" to "web")
            priceOf(run(ScriptTestInput(inputs = asDouble))) shouldBe "0.123456789012346"
        }
        withClue("a body fixture value is the decoder's exact number") {
            val price = priceOf(run(input("""{"inputs":{"channel":"web","order":{"price":12.50}}}"""))) as String
            BigDecimal(price).compareTo(BigDecimal("12.5")) shouldBe 0
        }
    }

    @Test
    fun `a repeated body member, top-level or nested, is refused as duplicate_member naming only the input`() {
        listOf(
            """{"inputs":{"channel":"web","order":{"price":1},"order":{"price":2}}}""",
            """{"inputs":{"channel":"web","order":{"price":1,"price":2}}}""",
            """{"inputs":{"channel":"web","order":{"lines":[{"sku":"$CANARY_BODY","sku":"B"}]}}}""",
            """{"inputs":{"channel":"web","order":[{"price":1},{"x":{"k":1,"k":2}}]}}""",
        ).forEach { json ->
            withClue(json) {
                val refused = run(input(json)).shouldBeInstanceOf<ScriptTestRunner.RunOutcome.Refused>()
                refused.code shouldBe PipelineErrorCodes.Script.INPUT_CONTRACT_VIOLATION
                refused.message shouldBe PipelineErrorCodes.Script.INPUT_CONTRACT_VIOLATION
                refused.details shouldBe mapOf("input" to "order", "reason" to "duplicate_member")
                refused.toString() shouldNotContain CANARY_BODY
            }
        }
    }

    @Test
    fun `a repeated value input keeps its last copy as it always did`() {
        priceOf(run(input("""{"inputs":{"channel":"first","channel":"second"}}"""))) shouldBe "second"
    }

    @Test
    fun `the authored budget counts canonical bytes, the same budget a full run applies`() {
        val limits = StructuredBodyLimits(maxBytes = BUDGET)
        // {"order":…} is not counted; the body {"p":"x…","price":1.000…} is 4076 canonical bytes and 4197 written.
        val pad = CANONICAL_BODY - CANONICAL_OVERHEAD
        val body = """{"p":"${"x".repeat(pad)}","price":1.${"0".repeat(TRAILING_ZEROS)}}"""
        body.toByteArray().size shouldBe WRITTEN_BODY
        run(input("""{"inputs":{"channel":"web","order":$body}}"""), limits).shouldBeInstanceOf<ScriptTestRunner.RunOutcome.Evaluated>()
        val over = """{"p":"${"x".repeat(BUDGET.toInt() - CANONICAL_OVERHEAD + 1)}","price":1}"""
        run(input("""{"inputs":{"channel":"web","order":$over}}"""), limits)
            .shouldBeInstanceOf<ScriptTestRunner.RunOutcome.Refused>()
            .details["reason"] shouldBe "too_many_bytes"
    }

    @Test
    fun `a body case's refusal discloses its code and safe details only, and its console text is withheld`() {
        val refused =
            run(input("""{"inputs":{"channel":"web","order":{"boom":true,"secret":"$CANARY_BODY"}}}"""))
                .shouldBeInstanceOf<ScriptTestRunner.RunOutcome.Refused>()
        refused.code shouldBe PipelineErrorCodes.Script.EVALUATION_FAILED
        refused.message shouldBe PipelineErrorCodes.Script.EVALUATION_FAILED
        refused.details.keys.all { it in co.datapipelines.typesystem.body.StructuredBodyDiagnostics.SAFE_DETAILS } shouldBe true
        refused.console shouldHaveSize 1
        refused.console.single() shouldBe ConsoleLine(ConsoleLevel.LOG, BodyFixtureDiagnostics.WITHHELD_TEXT)
        refused.toString() shouldNotContain CANARY_BODY
    }

    @Test
    fun `a body case's evaluation keeps its declared output and verdicts and withholds console and invariant text`() {
        val evaluated =
            run(input("""{"inputs":{"channel":"web","order":{"price":"$CANARY_BODY"}}}"""))
                .shouldBeInstanceOf<ScriptTestRunner.RunOutcome.Evaluated>()
        evaluated.output shouldBe listOf(mapOf("price_text" to CANARY_BODY))
        evaluated.invariants shouldBe listOf(ScriptTestRunner.InvariantVerdict("one_row", true, BodyFixtureDiagnostics.WITHHELD_TEXT))
        // One line from the body's evaluation, one from the invariant's: each keeps its level, neither its text.
        evaluated.console.map { it.text } shouldBe listOf(BodyFixtureDiagnostics.WITHHELD_TEXT, BodyFixtureDiagnostics.WITHHELD_TEXT)
    }

    @Test
    fun `a bodyless case keeps every engine message, console line and invariant text it had`() {
        val refused =
            runner()
                .runCase(
                    "legacy",
                    engine,
                    engine.compile("\$error(\"engine says \" & inputs.channel)"),
                    contract,
                    emptyList(),
                    input("""{"inputs":{"channel":"$CANARY_BODY"}}"""),
                ).shouldBeInstanceOf<ScriptTestRunner.RunOutcome.Refused>()
        refused.message shouldContain CANARY_BODY
        refused.console.single().text shouldContain CANARY_BODY
        val evaluated =
            run(input("""{"inputs":{"channel":"$CANARY_BODY"}}""")).shouldBeInstanceOf<ScriptTestRunner.RunOutcome.Evaluated>()
        evaluated.invariants.single().message shouldContain CANARY_AUTHOR
    }

    @Test
    fun `a failing body case's suite verdict names the case and a rule word, never a value, a message or invariant text`() {
        val tests: List<ScriptTestCase> =
            ScriptBlocks.mapper.readValue(
                """
                [{"name":"body_succeeds","input":{"inputs":{"channel":"web","order":{"price":"$CANARY_BODY"}}},
                  "expect":{"refusal":"pipeline.script.evaluation_failed"}},
                 {"name":"body_boom","input":{"inputs":{"channel":"web","order":{"boom":true,"secret":"$CANARY_BODY"}}},
                  "expect":{"output":[]}},
                 {"name":"body_differs","input":{"inputs":{"channel":"web","order":{"price":"$CANARY_BODY"}}},
                  "expect":{"output":[{"price_text":"other"}]}},
                 {"name":"body_holds","input":{"inputs":{"channel":"web","order":{"price":"1"}}},"expect":{"output":[{"price_text":"1"}]}},
                 {"name":"legacy_succeeds","input":{"inputs":{"channel":"$CANARY_BODY"}},
                  "expect":{"refusal":"pipeline.script.evaluation_failed"}}]
                """.trimIndent(),
            )
        val failures = runner().runSuite("Test", program, TemplateType.JSONATA, contract, invariants, tests)
        val byCase = failures.associateBy { it.message.substringAfter("'").substringBefore("'") }
        byCase.keys shouldBe setOf("body_succeeds", "body_boom", "body_differs", "legacy_succeeds")
        mapOf(
            "body_succeeds" to BodyFixtureDiagnostics.RULE_UNEXPECTED_SUCCESS,
            "body_boom" to BodyFixtureDiagnostics.RULE_UNEXPECTED_REFUSAL,
            "body_differs" to BodyFixtureDiagnostics.RULE_OUTPUT_DIFFERS,
        ).forEach { (case, rule) ->
            withClue(case) {
                val failure = byCase.getValue(case)
                failure.code shouldBe PipelineErrorCodes.Template.TEST_FAILED
                failure.details["case"] shouldBe case
                failure.details["rule"] shouldBe rule
                (failure.details.keys - setOf("case", "rule", "reason")) shouldBe emptySet()
                failure.toString() shouldNotContain CANARY_BODY
                failure.toString() shouldNotContain CANARY_AUTHOR
                failure.toString() shouldNotContain "engine says"
            }
        }
        val duplicate: List<ScriptTestCase> =
            ScriptBlocks.mapper.readValue(
                """[{"name":"dup","input":{"inputs":{"channel":"web","order":{"price":"1","price":"2"}}},"expect":{"output":[]}}]""",
            )
        runner().runSuite("Test", program, TemplateType.JSONATA, contract, invariants, duplicate).single().details shouldBe
            mapOf("case" to "dup", "rule" to BodyFixtureDiagnostics.RULE_UNEXPECTED_REFUSAL, "reason" to "duplicate_member")
        withClue("non-vacuity: the legacy wording of the same failure quotes the output") {
            byCase.getValue("legacy_succeeds").message shouldContain CANARY_BODY
        }
    }

    private companion object {
        const val LONG_FRACTION = "0.123456789012345678901234567890"
        const val CANARY_BODY = "CANARY-fixture-3b8e21"
        const val CANARY_AUTHOR = "CANARY-invariant-9d40aa"
        const val BUDGET = 4096L
        const val CANONICAL_BODY = 4076
        const val WRITTEN_BODY = 4197
        const val TRAILING_ZEROS = 120

        /** The canonical bytes of `{"p":"","price":1}` around the padding. */
        const val CANONICAL_OVERHEAD = 18
    }
}
