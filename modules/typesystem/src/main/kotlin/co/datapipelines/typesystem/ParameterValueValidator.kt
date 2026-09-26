package co.datapipelines.typesystem

import com.fasterxml.jackson.databind.JsonNode
import java.math.BigDecimal

/**
 * The ONE strict judge of a parameter value, at every place one arrives (parameter-engine record
 * P28): a declaration ([ParameterDeclaration]) and a wire value in, the accepted typed value or a
 * refusal naming the rule out. Callers today: `pipeline-contract`'s `ParameterBinder` — the
 * execute API, `pipelines_execute`, release checks, schedules — and, through that binder, the
 * published endpoints; the parameter engine's evaluate joins in #194's later lanes.
 *
 * ## What it judges, in order
 *
 * 1. **Nothing chosen.** `null`, absent, and `[]` for a `MULTI` are [ParameterValueOutcome.Unsupplied]
 *    (P25) — never validated, never refused; the caller resolves them.
 * 2. **The wire form**, through [ParameterCoercion] — strict, nothing trimmed (§6.3).
 * 3. **The declared precision and scale** of a `DECIMAL`/`BIGDECIMAL` — the value-level twin of
 *    [TypeWidening]: `12.345` into `DECIMAL(12,2)` is `scale`, `99999.5` into `DECIMAL(6,2)` is
 *    `precision`. Trailing zeros carry no digits (`12.50` and `12.500` are the same two places),
 *    and an approximate `DECIMAL` (no scale — a float) has no places to check.
 * 4. **The constraints** (record §3.5): `min`/`max` inclusive, `min_length`/`max_length`
 *    (characters for `STRING`, counted as code points; decoded bytes for `BINARY`), then
 *    `pattern`, anchored and read-budgeted ([PatternGuard]).
 *
 * Nothing is rounded, trimmed or clamped (P19): every rule refuses. A `MULTI` is a JSON array of
 * distinct values, each judged like a `SINGLE`; a null or duplicate member is `invalid_value_type`.
 *
 * ## A declaration is checked once, at save
 *
 * [checkDeclaration] reports what the declaration itself got wrong; a caller saves nothing it
 * reported. [validate] therefore trusts the declaration and throws [IllegalArgumentException] on
 * one that would not have saved — reaching that means an unvalidated body ran, the
 * `CalculatorInputResolver` convention.
 */
class ParameterValueValidator(
    private val limits: ParameterValueLimits = ParameterValueLimits(),
) {
    /** Judges [value] against [declaration]; see the class KDoc for the order. */
    fun validate(
        declaration: ParameterDeclaration,
        value: JsonNode?,
    ): ParameterValueOutcome {
        if (value == null || value.isNull || value.isMissingNode) return ParameterValueOutcome.Unsupplied
        val compiled = DeclarationCompiler.compile(declaration, limits)
        require(compiled.problems.isEmpty()) {
            "declaration refused by checkDeclaration reached validate: ${compiled.problems.joinToString { it.message }}"
        }
        return when (declaration.cardinality) {
            ParameterCardinality.SINGLE -> single(declaration, compiled.rules, value)
            ParameterCardinality.MULTI -> multi(declaration, compiled.rules, value)
        }
    }

    /** [validate] applied to the declaration's own `default` — the save-time check and the bind-time read. */
    fun resolveDefault(declaration: ParameterDeclaration): ParameterValueOutcome = validate(declaration, declaration.default)

    /**
     * The refusal for a required parameter nothing resolved for, or null for an optional one. The
     * caller asks AFTER its own resolution (a default, the selection priority) came up empty.
     */
    fun requiredMissing(declaration: ParameterDeclaration): ParameterValueRefusal? =
        if (declaration.required) {
            ParameterValueRefusal(ParameterValueRule.REQUIRED_MISSING, "a value is required and none was supplied or resolved")
        } else {
            null
        }

    /** Everything wrong with [declaration]'s constraints — empty when [validate] may be given it. */
    fun checkDeclaration(declaration: ParameterDeclaration): List<DeclarationProblem> =
        DeclarationCompiler.compile(declaration, limits).problems

    private fun single(
        declaration: ParameterDeclaration,
        rules: CompiledRules,
        node: JsonNode,
    ): ParameterValueOutcome =
        when (val coerced = ParameterCoercion.coerce(declaration.type, node)) {
            is ParameterCoercion.Outcome.Rejected -> {
                refused(ParameterValueRule.INVALID_VALUE_TYPE, coerced.reason)
            }

            is ParameterCoercion.Outcome.Coerced -> {
                val violation = descriptorViolation(declaration, coerced.value) ?: constraintViolation(declaration, rules, coerced.value)
                violation?.let { ParameterValueOutcome.Refused(it) } ?: ParameterValueOutcome.Accepted(coerced.value)
            }
        }

    @Suppress("ReturnCount") // each refusal is its own early answer; the accepted list is the fall-through
    private fun multi(
        declaration: ParameterDeclaration,
        rules: CompiledRules,
        node: JsonNode,
    ): ParameterValueOutcome {
        if (!node.isArray) {
            return refused(ParameterValueRule.INVALID_VALUE_TYPE, "MULTI takes a JSON array; got ${node.nodeType.name.lowercase()}")
        }
        if (node.isEmpty) return ParameterValueOutcome.Unsupplied
        val accepted = mutableListOf<Any>()
        node.forEachIndexed { index, member ->
            if (member.isNull) return refused(ParameterValueRule.INVALID_VALUE_TYPE, "MULTI member $index is null")
            when (val outcome = single(declaration, rules, member)) {
                is ParameterValueOutcome.Accepted -> {
                    if (accepted.any { sameValue(it, outcome.value) }) {
                        return refused(ParameterValueRule.INVALID_VALUE_TYPE, "MULTI member $index repeats an earlier member")
                    }
                    accepted += outcome.value
                }

                is ParameterValueOutcome.Refused -> {
                    return ParameterValueOutcome.Refused(outcome.refusal.copy(message = "MULTI member $index: ${outcome.refusal.message}"))
                }

                ParameterValueOutcome.Unsupplied -> {
                    return refused(ParameterValueRule.INVALID_VALUE_TYPE, "MULTI member $index is null")
                }
            }
        }
        return ParameterValueOutcome.Accepted(accepted.toList())
    }

    /** Step 3 — the declared scale, then the integer digits `precision − scale` leaves. */
    @Suppress("ReturnCount")
    private fun descriptorViolation(
        declaration: ParameterDeclaration,
        value: Any,
    ): ParameterValueRefusal? {
        if (value !is BigDecimal) return null
        val scale = declaration.scale ?: return null
        val stripped = value.stripTrailingZeros()
        val places = if (stripped.signum() == 0) 0 else maxOf(0, stripped.scale())
        val shape = describe(declaration)
        if (places > scale) {
            return violation("scale", "$shape takes at most $scale decimal place(s); got $places — nothing is rounded")
        }
        val precision = declaration.precision ?: return null
        // In Long: scale() can be -2147483647, and precision() - scale() in Int overflowed to accept
        // exactly the values with the most digits (the 194a security pass, finding 2).
        val integerDigits = if (stripped.signum() == 0) 0L else maxOf(0L, stripped.precision().toLong() - stripped.scale().toLong())
        if (integerDigits > (precision - scale).toLong()) {
            return violation("precision", "$shape holds at most ${precision - scale} integer digit(s); got $integerDigits")
        }
        return null
    }

    /** Step 4 — bounds, then lengths, then the pattern (lengths first: they bound the pattern's input). */
    @Suppress("ReturnCount", "CyclomaticComplexMethod")
    private fun constraintViolation(
        declaration: ParameterDeclaration,
        rules: CompiledRules,
        value: Any,
    ): ParameterValueRefusal? {
        rules.min?.let {
            if (compareCoerced(value, it) <
                0
            ) {
                return violation("min", "value is below the declared minimum ${renderCoerced(declaration.type, it)}")
            }
        }
        rules.max?.let {
            if (compareCoerced(value, it) >
                0
            ) {
                return violation("max", "value is above the declared maximum ${renderCoerced(declaration.type, it)}")
            }
        }
        val length =
            when (value) {
                is String -> value.codePointCount(0, value.length)
                is ByteArray -> value.size
                else -> return null
            }
        val unit = if (value is ByteArray) "byte(s)" else "character(s)"
        rules.minLength?.let { if (length < it) return violation("min_length", "value is $length $unit long; at least $it required") }
        rules.maxLength?.let { if (length > it) return violation("max_length", "value is $length $unit long; at most $it allowed") }
        val pattern = rules.pattern ?: return null
        val matched =
            try {
                PatternGuard.matches(pattern, value as String, limits.maxRegexReads)
            } catch (_: PatternGuard.BudgetExceeded) {
                return violation(
                    "pattern_budget",
                    "value could not be matched within the pattern's budget of ${limits.maxRegexReads} reads",
                )
            }
        return if (matched) null else violation("pattern", "value does not match the declared pattern")
    }

    private companion object {
        /** Two coerced values are the same member: numerically for decimals (`1.0` = `1.00`), by content for bytes. */
        fun sameValue(
            a: Any,
            b: Any,
        ): Boolean =
            when {
                a is BigDecimal && b is BigDecimal -> a.compareTo(b) == 0
                a is ByteArray && b is ByteArray -> a.contentEquals(b)
                else -> a == b
            }

        fun describe(declaration: ParameterDeclaration): String =
            declaration.precision?.let { "${declaration.type.wire}($it,${declaration.scale})" }
                ?: "${declaration.type.wire}(scale ${declaration.scale})"

        fun refused(
            rule: ParameterValueRule,
            message: String,
        ): ParameterValueOutcome = ParameterValueOutcome.Refused(ParameterValueRefusal(rule, message))

        fun violation(
            reason: String,
            message: String,
        ) = ParameterValueRefusal(ParameterValueRule.CONSTRAINT_VIOLATION, message, reason)
    }
}
