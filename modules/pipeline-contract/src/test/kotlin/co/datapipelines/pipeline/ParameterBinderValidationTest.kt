package co.datapipelines.pipeline

import co.datapipelines.typesystem.LogicalType
import co.datapipelines.typesystem.ParameterConstraints
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * The binder judges every value by the shared validator (#194, parameter-engine record P28) —
 * the execute API, `pipelines_execute`, release checks, schedules and published endpoints all
 * bind here — with ONE null policy: `null` and absent are unsupplied, and the binder resolves
 * them exactly as before (default, then `parameter_required`, then null for an optional one).
 * `ParameterBinderTest` pins the pre-#194 behaviour; this suite pins what #194 added.
 */
class ParameterBinderValidationTest {
    private val amount =
        Parameter(
            LogicalType.DECIMAL,
            precision = 12,
            scale = 2,
            default = Fixtures.json("0"),
            constraints = ParameterConstraints(min = Fixtures.json("0")),
        )
    private val binder =
        ParameterBinder(
            mapOf(
                "region" to Parameter(LogicalType.STRING, required = true),
                "min_amount" to amount,
                "code" to Parameter(LogicalType.STRING, constraints = ParameterConstraints(maxLength = 3, pattern = "[A-Z]+")),
            ),
        )

    @Test
    fun `a defaulted parameter sent as JSON null binds its default, judged by the same rules`() {
        val result = binder.bind(mapOf("region" to Fixtures.json("\"EU\""), "min_amount" to Fixtures.json("null")))

        val bound = result.shouldBeInstanceOf<ParameterBindingResult.Bound>().context
        bound["min_amount"] shouldBe BigDecimal("0")
        bound["code"].shouldBeNull()
    }

    @Test
    fun `a required parameter sent as JSON null is parameter_required, exactly as when it is absent`() {
        val sentNull = binder.bind(mapOf("region" to Fixtures.json("null")))
        val absent = binder.bind(emptyMap())

        listOf(sentNull, absent).forEach { result ->
            val failure = result.shouldBeInstanceOf<ParameterBindingResult.Rejected>().failures.single()
            failure.code shouldBe PipelineErrorCodes.Execution.PARAMETER_REQUIRED
            failure.path shouldBe "parameters.region"
        }
    }

    @Test
    fun `a value breaking a declared constraint is parameter_constraint_violation naming the rule`() {
        val result = bind("min_amount" to "-1", "code" to "\"abc\"")

        val failures = result.shouldBeInstanceOf<ParameterBindingResult.Rejected>().failures
        failures.map { it.code }.toSet() shouldBe setOf(PipelineErrorCodes.Execution.PARAMETER_CONSTRAINT_VIOLATION)
        failures.map { it.path to it.details["reason"] } shouldContainExactlyInAnyOrder
            listOf("parameters.min_amount" to "min", "parameters.code" to "pattern")
        failures.first { it.path == "parameters.min_amount" }.message shouldBe
            "Parameter 'min_amount': value is below the declared minimum 0."
    }

    @Test
    fun `more decimal places than the declared scale is refused, never rounded - the second deliberate break`() {
        val result = bind("min_amount" to "12.345")

        val failure = result.shouldBeInstanceOf<ParameterBindingResult.Rejected>().failures.single()
        failure.code shouldBe PipelineErrorCodes.Execution.PARAMETER_CONSTRAINT_VIOLATION
        failure.details["reason"] shouldBe "scale"
        failure.details["declared_type"] shouldBe "DECIMAL"
    }

    @Test
    fun `a wrong form and a broken rule come back together - binding stays exhaustive`() {
        val result = bind("min_amount" to "\"12\"", "code" to "\"ABCD\"")

        result.shouldBeInstanceOf<ParameterBindingResult.Rejected>().failures.map { it.code } shouldContainExactlyInAnyOrder
            listOf(PipelineErrorCodes.Execution.INVALID_PARAMETER_TYPE, PipelineErrorCodes.Execution.PARAMETER_CONSTRAINT_VIOLATION)
    }

    @Test
    fun `a stored default that breaks its rules is refused when applied, never bound`() {
        // Save refuses this declaration (default_invalid); a body that bypassed save must still
        // not bind a value execute would refuse had the caller sent it.
        val bypassed =
            ParameterBinder(
                mapOf(
                    "p" to
                        Parameter(
                            LogicalType.INTEGER,
                            default = Fixtures.json("-1"),
                            constraints = ParameterConstraints(min = Fixtures.json("0")),
                        ),
                ),
            )

        val failure =
            bypassed
                .bind(emptyMap())
                .shouldBeInstanceOf<ParameterBindingResult.Rejected>()
                .failures
                .single()
        failure.code shouldBe PipelineErrorCodes.Execution.PARAMETER_CONSTRAINT_VIOLATION
        failure.details["reason"] shouldBe "min"
    }

    private fun bind(vararg inputs: Pair<String, String>): ParameterBindingResult =
        binder.bind(mapOf("region" to Fixtures.json("\"EU\"")) + inputs.associate { (name, json) -> name to Fixtures.json(json) })
}
