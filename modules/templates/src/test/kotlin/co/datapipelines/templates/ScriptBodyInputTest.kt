package co.datapipelines.templates

import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.TemplateType
import co.datapipelines.scripting.JsonataEngine
import co.datapipelines.scripting.ScriptEngine
import co.datapipelines.scripting.ScriptEvaluationPool
import co.datapipelines.scripting.ScriptLanguage
import co.datapipelines.typesystem.LogicalType
import co.datapipelines.typesystem.body.StructuredBodyDecoder
import co.datapipelines.typesystem.body.StructuredBodyDecoding
import co.datapipelines.typesystem.body.StructuredBodyLimits
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID

/**
 * The `body` script input (#1038; published-mutation requirement §3.3): its contract shape, the
 * one-body rule, and a JSONata transformer turning a decoded order — an object with nested lines,
 * a heterogeneous root array, `{}`, `[]` — into the declared typed rows through the real test
 * runner. Only the transformer's output has a field schema; the body has none.
 *
 * The JavaScript twin of these programs runs against the real isolate in scripting's
 * `StructuredBodyEngineTest` (this module's test classpath carries no isolate binary); the runner
 * path below is engine-agnostic.
 */
class ScriptBodyInputTest {
    private val engines: Map<ScriptLanguage, ScriptEngine> = mapOf(ScriptLanguage.JSONATA to JsonataEngine())
    private val pool = ScriptEvaluationPool(2, 2, Duration.ofSeconds(5), ScriptEvaluationPool.SYSTEM)
    private val runner = ScriptTestRunner(engines, pool, Duration.ofSeconds(10), Duration.ofSeconds(30))
    private val languages = listOf(ScriptLanguage.JSONATA)

    private val lineColumns =
        listOf(
            ContractColumn("sku", LogicalType.STRING),
            ContractColumn("qty", LogicalType.INTEGER),
            ContractColumn("note", LogicalType.STRING, nullable = true),
        )

    private val contract =
        ScriptContract(
            mode = ScriptMode.TABLE,
            inputs = mapOf("order" to ScriptInput.Body(), "channel" to ScriptInput.Value(LogicalType.STRING)),
            output = ScriptOutput.Table(lineColumns),
        )

    /** Lines from an order object, or every element of a root array that carries a sku. */
    private val programs =
        mapOf(
            ScriptLanguage.JSONATA to
                """
                (${'$'}o := inputs.order;
                 ${'$'}items := ${'$'}type(${'$'}o) = "array" ? ${'$'}o[${'$'}exists(sku)] : ${'$'}o.lines;
                 [${'$'}items.{"sku": sku, "qty": qty, "note": ${'$'}exists(note) ? note : null}])
                """.trimIndent(),
        )

    private fun decoded(json: String): Any? =
        (StructuredBodyDecoder().decode(json.toByteArray()) as StructuredBodyDecoding.Decoded).body.value

    private fun run(
        language: ScriptLanguage,
        inputs: Map<String, Any?>,
    ): ScriptTestRunner.RunOutcome {
        val engine = engines.getValue(language)
        return runner.runCase(
            "case",
            engine,
            engine.compile(programs.getValue(language)),
            contract,
            emptyList(),
            ScriptTestInput(inputs = inputs),
        )
    }

    @Test
    fun `the body input binds and writes as kind body, and an unknown key inside it is refused`() {
        val json = ScriptBlocks.writeContract(contract)
        json!! shouldContain """"order":{"kind":"body"}"""
        ScriptBlocks.readContract(json) shouldBe contract
        val outcome =
            TemplateDeserializer().read(
                """{"type":"jsonata","display_name":"X","description":"Y","body":"inputs.order",
                    "contract":{"mode":"value","inputs":{"order":{"kind":"body","type":"STRING"}},"output":{"kind":"object"}}}""",
            )
        (outcome as TemplateDeserializationOutcome.Rejected)
            .result.failures
            .single()
            .details["rule"] shouldBe "unknown_field"
    }

    @Test
    fun `a contract admits one body input and refuses a second`() {
        val validator =
            TemplateValidator(
                LibraryResolver { _ -> InMemoryTemplateRegistry() },
                scriptEngines =
                    mapOf(ScriptLanguage.JSONATA to JsonataEngine()),
            )

        fun draftWith(inputs: Map<String, ScriptInput>) =
            TemplateFixtures.draft(
                id = "test/body.jsonata",
                type = TemplateType.JSONATA,
                engine = Template.NONE_ENGINE,
                dialect = null,
                body = "inputs.order",
                contract = ScriptContract(ScriptMode.VALUE, inputs, ScriptOutput.Obj()),
                invariants = emptyList(),
                tests =
                    listOf(
                        ScriptTestCase(
                            "empty",
                            ScriptTestInput(inputs = emptyMap()),
                            ScriptTestExpect(refusal = "pipeline.script.evaluation_failed"),
                        ),
                    ),
            )

        fun rules(inputs: Map<String, ScriptInput>) =
            validator.validate(draftWith(inputs), UUID.randomUUID()).failures.mapNotNull { it.details["rule"] }
        withClue("one body") { (rules(mapOf("order" to ScriptInput.Body())).contains("body_inputs")) shouldBe false }
        rules(mapOf("a" to ScriptInput.Body(), "b" to ScriptInput.Body())).contains("body_inputs") shouldBe true
    }

    @Test
    fun `the transformer turns an order object with nested lines into the declared rows`() {
        val order =
            decoded(
                """{"customer":{"name":"Zoë","extra":{"x":[1]}},""" +
                    """"lines":[{"sku":"A","qty":2},{"sku":"B","qty":1,"note":"gift 🎁","unknown":true}]}""",
            )
        languages.forEach { language ->
            withClue(language) {
                val evaluated =
                    run(
                        language,
                        mapOf("order" to order, "channel" to "web"),
                    ).shouldBeInstanceOf<ScriptTestRunner.RunOutcome.Evaluated>()
                evaluated.output shouldBe
                    listOf(
                        mapOf("sku" to "A", "qty" to 2, "note" to null),
                        mapOf("sku" to "B", "qty" to 1, "note" to "gift 🎁"),
                    )
            }
        }
    }

    @Test
    fun `the transformer reads a heterogeneous root array, a singleton array and the empty containers`() {
        val cases =
            mapOf(
                """[{"sku":"A","qty":3},{"kind":"comment","text":"ignored"},{"sku":"C","qty":4,"note":null}]""" to
                    listOf(mapOf("sku" to "A", "qty" to 3, "note" to null), mapOf("sku" to "C", "qty" to 4, "note" to null)),
                """[{"sku":"S","qty":1}]""" to listOf(mapOf("sku" to "S", "qty" to 1, "note" to null)),
                "[]" to emptyList(),
                "{}" to emptyList(),
            )
        languages.forEach { language ->
            cases.forEach { (json, rows) ->
                withClue("$language $json") {
                    val evaluated =
                        run(
                            language,
                            mapOf("order" to decoded(json), "channel" to "web"),
                        ).shouldBeInstanceOf<ScriptTestRunner.RunOutcome.Evaluated>()
                    evaluated.output shouldBe rows
                }
            }
        }
    }

    @Test
    fun `the typed output still gates the transformer's rows`() {
        val badQty = decoded("""{"lines":[{"sku":"A","qty":"two"}]}""")
        languages.forEach { language ->
            withClue(language) {
                val refused =
                    run(
                        language,
                        mapOf("order" to badQty, "channel" to "web"),
                    ).shouldBeInstanceOf<ScriptTestRunner.RunOutcome.Refused>()
                refused.message shouldNotContain "two"
            }
        }
    }

    @Test
    fun `a supplied test input that is not an object or array of objects is an input contract violation naming no value`() {
        listOf<Any?>(null, "secret-text", 42, listOf(mapOf("sku" to "A"), "secret-text")).forEach { bad ->
            withClue(bad) {
                val refused =
                    run(
                        ScriptLanguage.JSONATA,
                        mapOf("order" to bad, "channel" to "web"),
                    ).shouldBeInstanceOf<ScriptTestRunner.RunOutcome.Refused>()
                refused.code shouldBe PipelineErrorCodes.Script.INPUT_CONTRACT_VIOLATION
                refused.message shouldNotContain "secret-text"
            }
        }
    }

    @Test
    fun `an absent body reaches the transformer as absent, not as null or an empty container`() {
        val probe =
            mapOf(
                ScriptLanguage.JSONATA to "\$exists(inputs.order) ? \"present\" : \"absent\"",
            )
        val valueContract = contract.copy(mode = ScriptMode.VALUE, output = ScriptOutput.Value(LogicalType.STRING))
        languages.forEach { language ->
            val engine = engines.getValue(language)
            val script = engine.compile(probe.getValue(language))
            withClue(language) {
                (
                    runner.runCase(
                        "absent",
                        engine,
                        script,
                        valueContract,
                        emptyList(),
                        ScriptTestInput(inputs = mapOf("channel" to "web")),
                    )
                        as ScriptTestRunner.RunOutcome.Evaluated
                ).output shouldBe "absent"
                (
                    runner.runCase(
                        "empty",
                        engine,
                        script,
                        valueContract,
                        emptyList(),
                        ScriptTestInput(
                            inputs =
                                mapOf("order" to emptyList<Any?>(), "channel" to "web"),
                        ),
                    )
                        as ScriptTestRunner.RunOutcome.Evaluated
                ).output shouldBe "present"
            }
        }
    }

    /** An object nested [depth] containers deep (the root counts 1), with one string leaf at the bottom. */
    private fun nested(depth: Int): Any? {
        var value: Any? = mapOf("leaf" to "x")
        repeat(depth - 1) { value = mapOf("a" to value) }
        return value
    }

    private fun runWith(
        limits: StructuredBodyLimits,
        body: Any?,
    ): ScriptTestRunner.RunOutcome {
        val engine = engines.getValue(ScriptLanguage.JSONATA)
        return ScriptTestRunner(engines, pool, Duration.ofSeconds(10), Duration.ofSeconds(30), bodyLimits = limits)
            .runCase(
                "budget",
                engine,
                engine.compile(programs.getValue(ScriptLanguage.JSONATA)),
                contract,
                emptyList(),
                ScriptTestInput(inputs = mapOf("order" to body, "channel" to "web")),
            )
    }

    @Test
    fun `an authored body fixture meets the shared depth cap exactly at 32 and refuses at 33`() {
        runWith(StructuredBodyLimits(), nested(StructuredBodyLimits.DEFAULT_MAX_DEPTH))
            .shouldBeInstanceOf<ScriptTestRunner.RunOutcome.Evaluated>()
        val refused =
            runWith(StructuredBodyLimits(), nested(StructuredBodyLimits.DEFAULT_MAX_DEPTH + 1))
                .shouldBeInstanceOf<ScriptTestRunner.RunOutcome.Refused>()
        refused.code shouldBe PipelineErrorCodes.Script.INPUT_CONTRACT_VIOLATION
        refused.details["reason"] shouldBe "too_deep"
        refused.details["input"] shouldBe "order"
        refused.message shouldBe PipelineErrorCodes.Script.INPUT_CONTRACT_VIOLATION
    }

    @Test
    fun `an operator-adjusted depth and byte budget moves the authored fixture boundary with it`() {
        val shallow = StructuredBodyLimits(maxDepth = 2)
        runWith(shallow, nested(2)).shouldBeInstanceOf<ScriptTestRunner.RunOutcome.Evaluated>()
        runWith(shallow, nested(3)).shouldBeInstanceOf<ScriptTestRunner.RunOutcome.Refused>().details["reason"] shouldBe "too_deep"

        val deep = StructuredBodyLimits(maxDepth = StructuredBodyLimits.MAX_DEPTH)
        runWith(deep, nested(StructuredBodyLimits.DEFAULT_MAX_DEPTH + 1))
            .shouldBeInstanceOf<ScriptTestRunner.RunOutcome.Evaluated>()

        val body = mapOf("lines" to listOf(mapOf("sku" to "A".repeat(200), "qty" to 1)))
        val canonicalBytes =
            co.datapipelines.typesystem.body.StructuredBody
                .ObjectRoot(body)
                .canonical()!!
                .toByteArray()
                .size
                .toLong()
        runWith(StructuredBodyLimits(maxBytes = canonicalBytes), body).shouldBeInstanceOf<ScriptTestRunner.RunOutcome.Evaluated>()
        val over =
            runWith(StructuredBodyLimits(maxBytes = canonicalBytes - 1), body)
                .shouldBeInstanceOf<ScriptTestRunner.RunOutcome.Refused>()
        over.code shouldBe PipelineErrorCodes.Script.INPUT_CONTRACT_VIOLATION
        over.details["reason"] shouldBe "too_many_bytes"
        over.message shouldBe PipelineErrorCodes.Script.INPUT_CONTRACT_VIOLATION
    }
}
