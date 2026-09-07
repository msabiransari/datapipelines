package co.datapipelines.pipeline

import co.datapipelines.typesystem.LogicalType
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

/**
 * pipeline-contract §7 — Context construction from supplied parameter values, and the §6.3
 * failures raised while doing it.
 */
class ParameterBinderTest {
    private val parameters =
        mapOf(
            "start_date" to Parameter(LogicalType.DATE, required = true),
            "min_total" to
                Parameter(
                    LogicalType.BIGDECIMAL,
                    precision = 12,
                    scale = 2,
                    default = Fixtures.json("\"0.00\""),
                ),
            "include_cancelled" to Parameter(LogicalType.BOOLEAN, default = Fixtures.json("false")),
            "region" to Parameter(LogicalType.STRING),
        )
    private val binder = ParameterBinder(parameters)

    @Test
    fun `supplied values become typed Kotlin objects`() {
        val context = bind("start_date" to "\"2026-08-01\"", "min_total" to "\"250.00\"")

        context["start_date"] shouldBe LocalDate.of(2026, 8, 1)
        context["min_total"] shouldBe BigDecimal("250.00")
    }

    @Test
    fun `defaults are applied for optional parameters that were not supplied`() {
        val context = bind("start_date" to "\"2026-08-01\"")

        context["min_total"] shouldBe BigDecimal("0.00")
        context["include_cancelled"] shouldBe false
    }

    @Test
    fun `an optional parameter with no default is present in the Context as null`() {
        // §7.2 defines the Context as "all declared pipeline parameters"; a key that is absent
        // entirely is an undefined variable at render time (§7.4), which is a different failure.
        val context = bind("start_date" to "\"2026-08-01\"")

        ("region" in context) shouldBe true
        context["region"].shouldBeNull()
    }

    @Test
    fun `a missing required parameter is rejected with parameter_required`() {
        val result = binder.bind(emptyMap())

        result.shouldBeInstanceOf<ParameterBindingResult.Rejected>()
        result.failures.map { it.code } shouldContainExactly listOf(PipelineErrorCodes.Execution.PARAMETER_REQUIRED)
    }

    @Test
    fun `a wrong wire form is rejected with invalid_parameter_type`() {
        val result = binder.bind(mapOf("start_date" to Fixtures.json("20260801")))

        result.shouldBeInstanceOf<ParameterBindingResult.Rejected>()
        result.failures.single().code shouldBe PipelineErrorCodes.Execution.INVALID_PARAMETER_TYPE
        result.failures.single().details["declared_type"] shouldBe "DATE"
    }

    @Test
    fun `binding is exhaustive - every bad value comes back at once`() {
        val result =
            binder.bind(
                mapOf(
                    "min_total" to Fixtures.json("250.00"),
                    "include_cancelled" to Fixtures.json("\"yes\""),
                ),
            )

        result.shouldBeInstanceOf<ParameterBindingResult.Rejected>()
        result.failures.map { it.path } shouldContainExactlyInAnyOrder
            listOf("parameters.start_date", "parameters.min_total", "parameters.include_cancelled")
    }

    @Test
    fun `undeclared inputs are ignored rather than rejected`() {
        // §7.2 defines the Context by the pipeline's declarations, and §13 has no code for an
        // extra. Ignoring also keeps a client that upgrades before the pipeline from breaking.
        val context = bind("start_date" to "\"2026-08-01\"", "not_declared" to "\"x\"")

        context.keys shouldContainExactlyInAnyOrder parameters.keys
    }

    @Test
    fun `an explicit JSON null falls back to the default`() {
        val context = bind("start_date" to "\"2026-08-01\"", "min_total" to "null")

        context["min_total"] shouldBe BigDecimal("0.00")
    }

    @Test
    fun `the sample context never contains a null, and prefers declared defaults`() {
        // §7.4's dry-render context. A null here would fail a template on a value the author
        // cannot supply at save time — reporting a template defect that does not exist.
        val sample = binder.sampleContext()

        sample.keys shouldContainExactlyInAnyOrder parameters.keys
        sample.values.none { it == null } shouldBe true
        sample["min_total"] shouldBe BigDecimal("0.00")
        sample["start_date"] shouldBe LocalDate.EPOCH
    }

    @Test
    fun `bindOrThrow raises PipelineValidationException carrying the execution code`() {
        val thrown = shouldThrow<PipelineValidationException> { binder.bindOrThrow(emptyMap()) }

        thrown.code shouldBe PipelineErrorCodes.Execution.PARAMETER_REQUIRED
    }

    @Test
    fun `the Context is mutable for v2 calculators and reports its keys`() {
        val context = bind("start_date" to "\"2026-08-01\"")

        context.put("quarter", "2026-Q3")

        context["quarter"] shouldBe "2026-Q3"
        context.asMap()["quarter"] shouldBe "2026-Q3"
        context.toString() shouldBe "ExecutionContext(keys=${context.keys})"
    }

    private fun bind(vararg inputs: Pair<String, String>): ExecutionContext {
        val result = binder.bind(inputs.associate { (k, v) -> k to Fixtures.json(v) })
        result.shouldBeInstanceOf<ParameterBindingResult.Bound>()
        return result.context
    }

    // ---- 078 A5: a calculator context_key is an implicit optional execute input ----

    private val calculatorBinder =
        ParameterBinder(
            parameters,
            calculatorOutputs = mapOf("run_quarter" to LogicalType.INTEGER, "derived" to null),
        )

    @Test
    fun `a supplied calculator key is coerced by the kind's output type, like a parameter`() {
        val result =
            calculatorBinder.bind(
                mapOf("start_date" to Fixtures.json("\"2026-08-01\""), "run_quarter" to Fixtures.json("4")),
            )

        result.shouldBeInstanceOf<ParameterBindingResult.Bound>()
        result.context.asMap()["run_quarter"] shouldBe 4
    }

    @Test
    fun `a wrong-typed calculator key is refused with invalid_parameter_type on the parameter path`() {
        val result =
            calculatorBinder.bind(
                mapOf("start_date" to Fixtures.json("\"2026-08-01\""), "run_quarter" to Fixtures.json("\"four\"")),
            )

        result.shouldBeInstanceOf<ParameterBindingResult.Rejected>()
        val failure = result.failures.single()
        failure.code shouldBe PipelineErrorCodes.Execution.INVALID_PARAMETER_TYPE
        failure.path shouldBe "parameters.run_quarter"
        failure.details["declared_type"] shouldBe "INTEGER"
    }

    @Test
    fun `an ANY-typed calculator key accepts any JSON scalar`() {
        val context =
            bindCalculators("start_date" to "\"2026-08-01\"", "derived" to "20260801")

        context["derived"] shouldBe BigDecimal("20260801")
        bindCalculators("start_date" to "\"2026-08-01\"", "derived" to "\"text\"")["derived"] shouldBe "text"
        bindCalculators("start_date" to "\"2026-08-01\"", "derived" to "true")["derived"] shouldBe true
    }

    @Test
    fun `an ANY-typed calculator key refuses a container with the same code`() {
        val result =
            calculatorBinder.bind(
                mapOf("start_date" to Fixtures.json("\"2026-08-01\""), "derived" to Fixtures.json("""{"a":1}""")),
            )

        result.shouldBeInstanceOf<ParameterBindingResult.Rejected>()
        result.failures.single().code shouldBe PipelineErrorCodes.Execution.INVALID_PARAMETER_TYPE
        result.failures.single().path shouldBe "parameters.derived"
    }

    @Test
    fun `an unsupplied calculator key puts nothing in the bound map`() {
        // These are not §7.2 declared parameters: the key enters the Context from the caller
        // when supplied, or from the node when it runs — never from the binder's optional-null
        // rule, which would make "the caller supplied null" indistinguishable from "nobody did".
        val context = bindCalculators("start_date" to "\"2026-08-01\"")

        ("run_quarter" in context.asMap()) shouldBe false
        ("derived" in context.asMap()) shouldBe false
    }

    @Test
    fun `an explicit JSON null on a calculator key reads as unsupplied`() {
        val context = bindCalculators("start_date" to "\"2026-08-01\"", "run_quarter" to "null")

        ("run_quarter" in context.asMap()) shouldBe false
    }

    @Test
    fun `a declared parameter wins its own name over a calculator key`() {
        // The collision itself is refused at save by CalculatorRules (§12.10); the binder's job
        // is only to never let the calculator tier overwrite what the parameter tier bound.
        val colliding =
            ParameterBinder(
                mapOf("run_quarter" to Parameter(LogicalType.STRING)),
                calculatorOutputs = mapOf("run_quarter" to LogicalType.INTEGER),
            )

        val result = colliding.bind(mapOf("run_quarter" to Fixtures.json("\"4\"")))

        result.shouldBeInstanceOf<ParameterBindingResult.Bound>()
        result.context.asMap()["run_quarter"] shouldBe "4"
    }

    @Test
    fun `the sample context covers calculator outputs by output type, STRING for ANY`() {
        val sample = calculatorBinder.sampleContext()

        sample["run_quarter"] shouldBe 1
        sample["derived"] shouldBe "sample"
        sample.values.none { it == null } shouldBe true
    }

    private fun bindCalculators(vararg inputs: Pair<String, String>): ExecutionContext {
        val result = calculatorBinder.bind(inputs.associate { (k, v) -> k to Fixtures.json(v) })
        result.shouldBeInstanceOf<ParameterBindingResult.Bound>()
        return result.context
    }
}
