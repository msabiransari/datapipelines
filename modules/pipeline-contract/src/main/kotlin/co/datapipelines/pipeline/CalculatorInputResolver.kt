package co.datapipelines.pipeline

import co.datapipelines.calculators.CalculatorEvaluationException
import co.datapipelines.calculators.CalculatorInput
import co.datapipelines.calculators.CalculatorKind
import com.fasterxml.jackson.databind.JsonNode
import java.math.BigDecimal

/**
 * Turns a `CALCULATOR` node's authored `inputs` into the typed values [CalculatorKind.evaluate]
 * takes (§4.10, calculators design §0.3).
 *
 * ## Why this lives here and not in `dag`
 *
 * It is the run-time twin of [CalculatorRules]' save-time type check, and the two must agree
 * exactly: a literal the validator accepted must be a literal this resolves, and both must read
 * `"$name"` as a reference. Splitting them across modules is how they drift. It also keeps
 * [ParameterCoercion] internal — the §6.3 wire encoding has one implementation, and a calculator
 * literal obeys the same one a parameter default does.
 *
 * ## What a reference resolves to
 *
 * `$name` reads the Context. A key the validator proved present can still hold **null** — an
 * optional parameter nobody supplied — and that null is passed through rather than refused here:
 * `coalesce` and `if_null` exist for exactly that value, and a kind that cannot take it raises
 * its own refusal naming the input. A key that is absent entirely resolves to null too: save-time
 * validation makes that unreachable, and inventing an error for it at run time would report a
 * different failure than the one that actually happened.
 */
object CalculatorInputResolver {
    /**
     * Resolves every input [kind] declares. An input the author omitted resolves to null, which
     * is what tells the kind to apply its documented default.
     *
     * @throws CalculatorEvaluationException a literal cannot be read as its declared type. Save-time
     *   validation refuses this shape, so reaching it means an unvalidated body ran.
     */
    fun resolve(
        kind: CalculatorKind,
        inputs: Map<String, JsonNode>,
        context: Map<String, Any?>,
    ): Map<String, Any?> =
        kind.inputs.associate { input ->
            input.name to inputs[input.name]?.let { value(input, it, context) }
        }

    private fun value(
        input: CalculatorInput,
        node: JsonNode,
        context: Map<String, Any?>,
    ): Any? =
        when {
            input.isList && node.isArray -> node.map { element -> element(input, element, context) }
            else -> element(input, node, context)
        }

    private fun element(
        input: CalculatorInput,
        node: JsonNode,
        context: Map<String, Any?>,
    ): Any? {
        val reference = reference(node)
        if (reference != null) return context[reference]
        if (node.isNull) return null
        val type =
            input.type ?: return natural(node)
                ?: throw CalculatorEvaluationException(
                    input.name,
                    "Input '${input.name}' takes a scalar value; a JSON ${node.nodeType} has no canonical reading.",
                )
        return when (val outcome = ParameterCoercion.coerce(type, node)) {
            is ParameterCoercion.Outcome.Coerced -> outcome.value

            is ParameterCoercion.Outcome.Rejected ->
                throw CalculatorEvaluationException(
                    input.name,
                    "Input '${input.name}' must be a ${type.wire} value: ${outcome.reason}.",
                )
        }
    }

    /**
     * The Kotlin reading of a JSON scalar for an `ANY`-typed input.
     *
     * `BigDecimal` for every JSON number, not `Int` for the integral ones: the three ANY kinds
     * (`coalesce`, `if_null`, `map`) compare and return values, and `map`'s `from` list matching
     * on `1` but not on `1.0` would be a lookup that depends on how the author typed a number.
     */
    private fun natural(node: JsonNode): Any? =
        when {
            node.isTextual -> node.asText()
            node.isBoolean -> node.asBoolean()
            node.isNumber -> BigDecimal(node.asText())
            else -> null
        }

    /** `"$name"` is a reference to a Context key; every other JSON value is a literal (§0.3). */
    private fun reference(node: JsonNode): String? =
        node
            .takeIf { it.isTextual }
            ?.asText()
            ?.takeIf { it.startsWith("$") && it.length > 1 }
            ?.substring(1)
}
