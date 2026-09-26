package co.datapipelines.typesystem

import com.fasterxml.jackson.databind.JsonNode
import java.math.BigDecimal
import java.util.regex.Pattern

/** A declaration's constraints in typed form: bounds coerced to the parameter's own type, the pattern compiled. */
internal class CompiledRules(
    val min: Any? = null,
    val max: Any? = null,
    val minLength: Int? = null,
    val maxLength: Int? = null,
    val pattern: Pattern? = null,
)

/** The typed rules, and what stopped a constraint from compiling ([ParameterValueValidator.checkDeclaration]). */
internal class CompiledDeclaration(
    val rules: CompiledRules,
    val problems: List<DeclarationProblem>,
)

/**
 * Turns a [ParameterDeclaration]'s constraints into [CompiledRules], collecting every problem
 * rather than stopping at the first (the house rule for save-time validation: exhaustive, so an
 * author fixes a declaration once).
 *
 * The applicability table is record §3.5's: `min`/`max` for the ordered types, the lengths for
 * `STRING` and `BINARY`, `pattern` for `STRING` alone. A bound is wire-encoded in the parameter's
 * own type and coerced by [ParameterCoercion] — the same code a supplied value goes through — so
 * a `BIGDECIMAL` minimum is `"0"` and an `INTEGER` one is `0`, and nothing is inferred.
 */
internal object DeclarationCompiler {
    fun compile(
        declaration: ParameterDeclaration,
        limits: ParameterValueLimits,
    ): CompiledDeclaration {
        val type = declaration.type
        val defaultMaxLength = limits.defaultMaxLength.takeIf { type in LENGTH_TYPES }
        val constraints = declaration.constraints ?: return CompiledDeclaration(CompiledRules(maxLength = defaultMaxLength), emptyList())
        val problems = mutableListOf<DeclarationProblem>()
        val min = bound("min", constraints.min, type, problems)
        val max = bound("max", constraints.max, type, problems)
        if (min != null && max != null && compareCoerced(min, max) > 0) {
            problems +=
                invalid(
                    "min",
                    "min_greater_than_max",
                    "min ${renderCoerced(type, min)} is greater than max ${renderCoerced(type, max)}",
                )
        }
        val minLength = length("min_length", constraints.minLength, type, problems)
        val maxLength = length("max_length", constraints.maxLength, type, problems)
        if (minLength != null && maxLength != null && minLength > maxLength) {
            problems +=
                invalid("min_length", "min_length_greater_than_max_length", "min_length $minLength is greater than max_length $maxLength")
        }
        val pattern = pattern(constraints.pattern, type, problems)
        return CompiledDeclaration(CompiledRules(min, max, minLength, maxLength ?: defaultMaxLength, pattern), problems.toList())
    }

    private fun bound(
        key: String,
        node: JsonNode?,
        type: LogicalType,
        problems: MutableList<DeclarationProblem>,
    ): Any? {
        if (node == null || node.isNull) return null
        if (type !in ORDERED_TYPES) {
            problems += notApplicable(key, type)
            return null
        }
        return when (val coerced = ParameterCoercion.coerce(type, node)) {
            is ParameterCoercion.Outcome.Coerced -> {
                coerced.value
            }

            is ParameterCoercion.Outcome.Rejected -> {
                problems += invalid(key, "bound_type", "$key must be a ${type.wire} wire value: ${coerced.reason}")
                null
            }
        }
    }

    private fun length(
        key: String,
        value: Int?,
        type: LogicalType,
        problems: MutableList<DeclarationProblem>,
    ): Int? {
        if (value == null) return null
        if (type !in LENGTH_TYPES) {
            problems += notApplicable(key, type)
            return null
        }
        if (value < 0) {
            problems += invalid(key, "negative_length", "$key must be zero or more; got $value")
            return null
        }
        return value
    }

    private fun pattern(
        source: String?,
        type: LogicalType,
        problems: MutableList<DeclarationProblem>,
    ): Pattern? {
        if (source == null) return null
        if (type != LogicalType.STRING) {
            problems += notApplicable("pattern", type)
            return null
        }
        return when (val compiled = PatternGuard.compile(source)) {
            is PatternGuard.Compiled.Safe -> {
                compiled.pattern
            }

            is PatternGuard.Compiled.Refused -> {
                problems += DeclarationProblem(DeclarationRule.PATTERN_INVALID, "pattern", compiled.message, compiled.reason)
                null
            }
        }
    }

    /** Record §3.5: the types `min`/`max` apply to — ordered, and coerced to one Comparable class each. */
    private val ORDERED_TYPES =
        setOf(
            LogicalType.INTEGER,
            LogicalType.BIGINTEGER,
            LogicalType.DECIMAL,
            LogicalType.BIGDECIMAL,
            LogicalType.DATE,
            LogicalType.TIME,
            LogicalType.TIMESTAMP,
        )

    /** Record §3.5: the types `min_length`/`max_length` apply to. */
    private val LENGTH_TYPES = setOf(LogicalType.STRING, LogicalType.BINARY)

    private fun notApplicable(
        key: String,
        type: LogicalType,
    ) = DeclarationProblem(DeclarationRule.CONSTRAINT_NOT_APPLICABLE, key, "$key does not apply to a ${type.wire} parameter")

    private fun invalid(
        key: String,
        reason: String,
        message: String,
    ) = DeclarationProblem(DeclarationRule.CONSTRAINT_INVALID, key, message, reason)
}

/**
 * Orders two values [ParameterCoercion] produced for the same ordered type — `Int`, `BigInteger`,
 * `BigDecimal` (numerically: `1.0` equals `1.00`), `LocalDate`, `LocalTime` or `Instant`, each
 * Comparable to itself.
 */
@Suppress("UNCHECKED_CAST")
internal fun compareCoerced(
    a: Any,
    b: Any,
): Int = (a as Comparable<Any>).compareTo(b)

/**
 * A coerced value back in a message-safe spelling, bounded for a message. A [BigDecimal] renders
 * through `toString()` (scientific past a few places), never the plain wire spelling: an author's
 * bound of `1e2147483647` would otherwise be an `OutOfMemoryError` while building the refusal (the
 * 194a security pass, observation 1).
 */
internal fun renderCoerced(
    type: LogicalType,
    value: Any,
): String =
    when (value) {
        is BigDecimal -> value.toString().truncateForError()
        else -> ParameterWireEncoder.encode(type, value).asText().truncateForError()
    }
