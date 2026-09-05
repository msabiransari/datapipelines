package co.datapipelines.calculators

import co.datapipelines.typesystem.LogicalType
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * Turns a kind's declared [CalculatorExample] back into the typed input map `evaluate` takes, so
 * the drift guard can prove the documented answer is not just *consistent* with the declaration
 * but **true**.
 *
 * The coercion here is the same one the executor performs from JSON literals, done from the
 * example's display strings instead. A value written as `$name` is a Context REFERENCE, and this
 * has no Context — so it resolves to null, which is exactly the shape `coalesce` and `if_null`'s
 * examples are demonstrating ("the reference was absent, so the fallback won").
 *
 * Deliberately named without the `Test` suffix so the module's `verifyTestsExecuted` guard counts
 * only real test classes.
 */
object ExampleInputs {
    fun of(kind: CalculatorKind): Map<String, Any?> =
        kind.inputs.associate { input ->
            input.name to kind.example.inputs[input.name]?.let { value(it, input) }
        }

    private fun value(
        raw: String,
        input: CalculatorInput,
    ): Any? =
        when {
            raw.startsWith("$") -> null

            // Per ELEMENT, not per cell: `coalesce`'s example is a list whose FIRST entry is the
            // absent reference and whose second is the literal that therefore wins.
            input.isList -> elements(raw).map { if (it.startsWith("$")) null else scalar(it, input.type) }

            else -> scalar(raw, input.type)
        }

    private fun elements(raw: String): List<String> =
        raw
            .removeSurrounding("[", "]")
            .split(",")
            .map { it.trim().removeSurrounding("\"") }
            .filter { it.isNotEmpty() }

    private fun scalar(
        raw: String,
        type: LogicalType?,
    ): Any =
        when (type) {
            LogicalType.DATE -> LocalDate.parse(raw)

            LogicalType.TIMESTAMP -> Instant.parse(raw)

            LogicalType.INTEGER -> raw.toInt()

            LogicalType.DECIMAL -> BigDecimal(raw)

            LogicalType.BOOLEAN -> raw.toBooleanStrict()

            // STRING, and the ANY inputs whose examples are all strings.
            else -> raw
        }
}
