package co.datapipelines.templates

import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.TemplateType
import co.datapipelines.scripting.CompiledScript
import co.datapipelines.scripting.ConsoleLevel
import co.datapipelines.scripting.ConsoleLine
import co.datapipelines.scripting.EvaluationLimits
import co.datapipelines.scripting.JsonataEngine
import co.datapipelines.scripting.ScriptEngine
import co.datapipelines.scripting.ScriptEvaluationException
import co.datapipelines.scripting.ScriptEvaluationPool
import co.datapipelines.scripting.ScriptLanguage
import co.datapipelines.scripting.ScriptSyntaxException
import co.datapipelines.typesystem.LogicalType
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID

/** Tests the template adapter with a recording engine; the real isolate is exercised by ScriptNodeE2eTest. */
class JavaScriptTemplateWiringTest {
    private val contract =
        ScriptContract(
            mode = ScriptMode.ROW,
            inputs = mapOf("source" to ScriptInput.Table(listOf(ContractColumn("id", LogicalType.INTEGER)))),
            output = ScriptOutput.Table(listOf(ContractColumn("id", LogicalType.INTEGER))),
        )
    private val input = ScriptTestInput(rows = emptyList(), inputs = emptyMap())
    private val emptyCase = ScriptTestCase("empty input", input, ScriptTestExpect(output = ScriptBlocks.mapper.readTree("[]")))

    @Test
    fun `the body uses its registered engine, author-relative syntax and the common availability refusal`() {
        val engine = RecordingEngine()
        val engines = mapOf(ScriptLanguage.JAVASCRIPT to engine)
        val draft = draft("return input.rows;")
        val trace = ValidationTrace()
        TemplateTypeBehaviour.Javascript.validateBody(draft, engines, trace) shouldBe emptyList()
        engine.evaluated shouldBe 0
        val syntax = TemplateTypeBehaviour.Javascript.validateBody(draft.copy(body = "if (x {"), engines, trace).single()
        syntax.code shouldBe PipelineErrorCodes.Template.SYNTAX_ERROR
        syntax.details["line"] shouldBe 1
        val depth = TemplateTypeBehaviour.Javascript.validateBody(draft.copy(body = "deep"), engines, trace).single()
        depth.code shouldBe PipelineErrorCodes.Template.SYNTAX_ERROR
        depth.details["kind"] shouldBe "DEPTH"
        val unavailable = TemplateTypeBehaviour.Javascript.validateBody(draft, emptyMap(), trace).single()
        unavailable.code shouldBe PipelineErrorCodes.Script.JS_UNAVAILABLE
        unavailable.message shouldBe co.datapipelines.pipeline.JAVASCRIPT_UNAVAILABLE_MESSAGE
    }

    @Test
    fun `save evaluates the mandatory empty case on the pool, compares expect, and refuses a body with no return`() {
        val engine = RecordingEngine()
        val runner = runner(engine)
        val engines = mapOf(ScriptLanguage.JAVASCRIPT to engine, ScriptLanguage.JSONATA to JsonataEngine())
        val validator = TemplateValidator(LibraryResolver { InMemoryTemplateRegistry() }, scriptEngines = engines, suiteRunner = runner)
        validator.validate(draft("return input.rows;"), UUID.randomUUID()).isValid shouldBe true
        engine.evaluated shouldBe 1
        engine.thread shouldContain "script-eval-"
        validator
            .validate(draft("const a = 1;", cases = emptyList()), UUID.randomUUID())
            .failures
            .any { it.details["rule"] == "empty_case_missing" } shouldBe true
        val refused = validator.validate(draft("const a = 1;"), UUID.randomUUID())
        refused.isValid shouldBe false
        refused.failures.joinToString { it.message } shouldContain "main returned nothing"
        val wrong = emptyCase.copy(expect = ScriptTestExpect(output = ScriptBlocks.mapper.readTree("[{\"id\":1}]")))
        validator
            .validate(draft("return input.rows;", listOf(wrong)), UUID.randomUUID())
            .failures
            .any { it.code == PipelineErrorCodes.Template.TEST_FAILED } shouldBe true
    }

    @Test
    fun `console is retained on both success and failure and configured ceilings reach the engine`() {
        val engine = RecordingEngine()
        val runner = runner(engine)
        val success = runner.runCase("success", engine, engine.compile("return input.rows;"), contract, emptyList(), input)
        (success as ScriptTestRunner.RunOutcome.Evaluated).console.single().text shouldBe "before evaluation"
        engine.limits?.maxHeapBytes shouldBe 100
        engine.limits?.maxStatements shouldBe 200
        engine.limits?.maxResultBytes shouldBe 300
        engine.limits?.maxInputBytes shouldBe 400
        val failure = runner.runCase("failure", engine, engine.compile("const a = 1;"), contract, emptyList(), input)
        (failure as ScriptTestRunner.RunOutcome.Refused).console.single().text shouldBe "before evaluation"
    }

    private fun draft(
        body: String,
        cases: List<ScriptTestCase> = listOf(emptyCase),
    ) = TemplateDraft(
        id = "test/js_wiring",
        displayName = "JavaScript",
        description = "Wiring",
        type = TemplateType.JAVASCRIPT,
        engine = Template.NONE_ENGINE,
        body = body,
        contract = contract,
        invariants = emptyList(),
        tests = cases,
    )

    private fun runner(engine: ScriptEngine) =
        ScriptTestRunner(
            mapOf(ScriptLanguage.JSONATA to JsonataEngine(), ScriptLanguage.JAVASCRIPT to engine),
            ScriptEvaluationPool(1, 1, Duration.ofMillis(200), ScriptEvaluationPool.SYSTEM),
            Duration.ofSeconds(2),
            Duration.ofSeconds(10),
            maxHeapBytes = 100,
            maxStatements = 200,
            maxResultBytes = 300,
            maxInputBytes = 400,
        )

    private class Body(
        val text: String,
    ) : CompiledScript

    private class RecordingEngine : ScriptEngine {
        override val type = ScriptLanguage.JAVASCRIPT
        override val capabilities = JsonataEngine().capabilities
        var evaluated = 0
        var thread = ""
        var limits: EvaluationLimits? = null

        override fun compile(body: String): CompiledScript {
            if (body == "if (x {") throw ScriptSyntaxException(line = 1, column = 7, message = "syntax witness")
            if (body == "deep") {
                throw co.datapipelines.scripting.ScriptResourceLimitException(
                    co.datapipelines.scripting.ScriptResourceLimitException.Kind.DEPTH,
                    "parse-depth witness",
                )
            }
            return Body(body)
        }

        override fun evaluate(
            script: CompiledScript,
            input: Any?,
            limits: EvaluationLimits,
        ): Any? {
            evaluated++
            thread = Thread.currentThread().name
            this.limits = limits
            limits.console?.accept(ConsoleLine(ConsoleLevel.LOG, "before evaluation"))
            if ((script as Body).text == "const a = 1;") throw ScriptEvaluationException("main returned nothing")
            return emptyList<Any>()
        }
    }
}
