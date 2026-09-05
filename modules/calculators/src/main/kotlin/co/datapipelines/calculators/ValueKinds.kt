package co.datapipelines.calculators

import co.datapipelines.calculators.CalculatorInput.Arity
import co.datapipelines.calculators.CalculatorValues.decimal
import co.datapipelines.calculators.CalculatorValues.intOr
import co.datapipelines.calculators.CalculatorValues.list
import co.datapipelines.calculators.CalculatorValues.stringOr
import co.datapipelines.typesystem.LogicalType.DECIMAL
import co.datapipelines.typesystem.LogicalType.INTEGER
import co.datapipelines.typesystem.LogicalType.STRING
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * The numeric and value kinds (calculators design §0.4).
 *
 * The three at the bottom — `coalesce`, `if_null`, `map` — declare a null input and output type,
 * which reads as `ANY`. That is the honest declaration: none of them looks at the value, so
 * pinning a canonical type would be a claim the implementation does not make. Everything else
 * here is `DECIMAL` in and `DECIMAL` out, which is `BigDecimal` end to end — a money calculation
 * that went through a `double` on the way is the defect this type choice exists to prevent.
 */
internal object ValueKinds {
    private val ROUNDING =
        mapOf(
            "half_up" to RoundingMode.HALF_UP,
            "half_even" to RoundingMode.HALF_EVEN,
            "floor" to RoundingMode.FLOOR,
            "ceil" to RoundingMode.CEILING,
        )

    val ALL: List<CalculatorKind> =
        listOf(
            SimpleKind(
                kind = "round",
                displayName = "Round",
                description = "A decimal rounded to a number of places under an explicit rounding mode.",
                inputs =
                    listOf(
                        input("value", DECIMAL, "The number to round."),
                        input("places", INTEGER, "Decimal places; 0 rounds to a whole number.", required = false, default = "0"),
                        input(
                            "mode",
                            STRING,
                            "One of ${ROUNDING.keys.joinToString(" | ") { "`$it`" }}. `half_even` is banker's rounding.",
                            required = false,
                            default = "half_up",
                        ),
                    ),
                output = DECIMAL,
                example = example("value" to "2.345", "places" to "2", output = "2.35"),
            ) { values ->
                decimal(values, "value").setScale(intOr(values, "places", 0), roundingMode(stringOr(values, "mode", "half_up")))
            },
            SimpleKind(
                kind = "percent_change",
                displayName = "Percent change",
                description =
                    "The change from `previous` to `current` as a percentage: (current − previous) ÷ previous × 100. " +
                        "A zero `previous` is refused rather than reported as infinity.",
                inputs =
                    listOf(
                        input("current", DECIMAL, "The current value."),
                        input("previous", DECIMAL, "The value being compared against."),
                        input("places", INTEGER, "Decimal places in the result.", required = false, default = "6"),
                    ),
                output = DECIMAL,
                example = example("current" to "125", "previous" to "100", "places" to "2", output = "25.00"),
            ) { values -> percentChange(values) },
            SimpleKind(
                kind = "coalesce",
                displayName = "Coalesce",
                description = "The first value that is not null; null when every one of them is.",
                inputs =
                    listOf(
                        input(
                            "values",
                            null,
                            "The candidate values, in preference order — a JSON array mixing `\$references` and literals.",
                            arity = Arity.LIST,
                        ),
                    ),
                output = null,
                example = example("values" to "[\"\$requested_region\", \"GLOBAL\"]", output = "GLOBAL"),
            ) { values -> list(values, "values").firstOrNull { it != null } },
            SimpleKind(
                kind = "if_null",
                displayName = "Default if null",
                description = "The value, or `default` when the value is null. `coalesce` for exactly two candidates.",
                inputs =
                    listOf(
                        input("value", null, "The value to test. Present in the node, and null at run time is the point."),
                        input("default", null, "What to use when `value` is null."),
                    ),
                output = null,
                example = example("value" to "\$requested_region", "default" to "GLOBAL", output = "GLOBAL"),
            ) { values -> values["value"] ?: values["default"] },
            SimpleKind(
                kind = "map",
                displayName = "Map value",
                description =
                    "Translates a value through a lookup carried in the node itself: the value at the position " +
                        "in `from` where it matches, or `default`. The pairs are parallel arrays rather than a " +
                        "list of objects so each element types like any other literal.",
                inputs =
                    listOf(
                        input("value", null, "The value to translate."),
                        input("from", null, "The values to match, in order.", arity = Arity.LIST),
                        input("to", null, "What each matching value becomes; same length as `from`.", arity = Arity.LIST),
                        input("default", null, "Used when nothing matches.", required = false, default = "null"),
                    ),
                output = null,
                example =
                    example(
                        "value" to "GB",
                        "from" to "[\"GB\", \"US\"]",
                        "to" to "[\"United Kingdom\", \"United States\"]",
                        output = "United Kingdom",
                    ),
            ) { values -> mapValue(values) },
        )

    private fun roundingMode(mode: String): RoundingMode =
        ROUNDING[mode.lowercase()]
            ?: throw CalculatorEvaluationException(
                "mode",
                "Input 'mode' must be one of ${ROUNDING.keys.joinToString(" | ")}, but was '$mode'.",
            )

    private fun percentChange(values: Map<String, Any?>): BigDecimal {
        val previous = decimal(values, "previous")
        if (previous.signum() == 0) {
            throw CalculatorEvaluationException(
                "previous",
                "percent_change cannot divide by a zero 'previous' — there is no percentage change from nothing.",
            )
        }
        val places = intOr(values, "places", DEFAULT_PERCENT_PLACES)
        return decimal(values, "current")
            .subtract(previous)
            .divide(previous, places + SCALE_HEADROOM, RoundingMode.HALF_UP)
            .multiply(HUNDRED)
            .setScale(places, RoundingMode.HALF_UP)
    }

    private fun mapValue(values: Map<String, Any?>): Any? {
        val from = list(values, "from")
        val to = list(values, "to")
        if (from.size != to.size) {
            throw CalculatorEvaluationException(
                "to",
                "map needs 'from' and 'to' to be the same length; got ${from.size} and ${to.size}.",
            )
        }
        val index = from.indexOfFirst { it == values["value"] }
        return if (index >= 0) to[index] else values["default"]
    }

    /**
     * Extra digits carried through the division so the final `setScale` rounds the PERCENTAGE,
     * not an already-rounded ratio. Rounding at the ratio would make 1/3 of 100 come out as
     * 33.333300 at six places instead of 33.333333.
     */
    private const val SCALE_HEADROOM = 4

    /** `percent_change`'s documented default precision — six places, as the catalog row says. */
    private const val DEFAULT_PERCENT_PLACES = 6

    private val HUNDRED = BigDecimal("100")
}
