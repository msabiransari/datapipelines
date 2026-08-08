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
}
