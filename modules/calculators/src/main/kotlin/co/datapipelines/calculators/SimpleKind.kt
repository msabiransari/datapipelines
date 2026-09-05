package co.datapipelines.calculators

import co.datapipelines.calculators.CalculatorInput.Arity
import co.datapipelines.typesystem.LogicalType

/**
 * The one shape every catalog entry is built from: metadata plus a lambda.
 *
 * A class per kind would be 23 files of five overrides and one interesting line. This keeps the
 * interesting line next to the declaration it implements, which is how the catalog reads in
 * `calculators.md` too — and it makes the whole registry legible in one screen per family.
 */
@Suppress("LongParameterList")
internal class SimpleKind(
    override val kind: String,
    override val displayName: String,
    override val description: String,
    override val inputs: List<CalculatorInput>,
    override val output: LogicalType?,
    override val example: CalculatorExample,
    private val body: (Map<String, Any?>) -> Any?,
) : CalculatorKind {
    override fun evaluate(values: Map<String, Any?>): Any? = body(values)
}

/** Declares one input. Defaults to required and single-valued — the common case. */
internal fun input(
    name: String,
    type: LogicalType?,
    description: String,
    required: Boolean = true,
    arity: Arity = Arity.SINGLE,
    default: String? = null,
) = CalculatorInput(
    name = name,
    type = type,
    description = description,
    required = required,
    arity = arity,
    defaultDescription = default,
)

/** The worked example a catalog row prints. */
internal fun example(
    vararg inputs: Pair<String, String>,
    output: String,
) = CalculatorExample(inputs.toMap(), output)
