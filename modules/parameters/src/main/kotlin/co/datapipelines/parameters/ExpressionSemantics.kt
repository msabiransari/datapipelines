package co.datapipelines.parameters

import co.datapipelines.typesystem.LogicalType
import co.datapipelines.typesystem.ParameterCardinality
import co.datapipelines.typesystem.ParameterCoercion
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.time.ZonedDateTime

/**
 * The §7.2 static rules — applied at save, against the set the expression sits in:
 *
 * - every `ref` names a parameter in the CARRIER's `depends_on`, never the carrier itself
 *   (`ref_undeclared`) — an expression may never reference the parameter it sits on;
 * - `eq` / `neq` / `in` / `is_null` need a `SINGLE` ref, `contains` / `is_empty` a `MULTI` one
 *   (`expression_cardinality`);
 * - a `BINARY` ref may only be tested with `is_null` (`expression_type_unsupported`);
 * - every literal coerces to its ref's `LogicalType` through the ONE strict coercion
 *   (`expression_literal_type`) — nothing trimmed, nothing guessed.
 *
 * A ref naming no parameter of the set is not type-checked here: the graph step reports the
 * `depends_on` entry as `dependency_unknown`, and one mistake gets one code.
 */
object ExpressionChecker {
    fun check(
        expr: Expr,
        carrier: ParameterDefinition,
        parameters: Map<String, ParameterDefinition>,
    ): List<ExpressionProblem> {
        val problems = mutableListOf<ExpressionProblem>()
        Check(carrier, parameters, problems).expr(expr, "")
        return problems
    }

    private class Check(
        private val carrier: ParameterDefinition,
        private val parameters: Map<String, ParameterDefinition>,
        private val problems: MutableList<ExpressionProblem>,
    ) {
        /** Recursion bounded by the depth the parser already enforced (≤ `max-expression-depth`). */
        fun expr(
            expr: Expr,
            path: String,
        ) {
            when (expr) {
                is Expr.And -> {
                    expr.args.forEachIndexed { i, arg -> expr(arg, "${join(path, "args")}[$i]") }
                }

                is Expr.Or -> {
                    expr.args.forEachIndexed { i, arg -> expr(arg, "${join(path, "args")}[$i]") }
                }

                is Expr.Not -> {
                    expr(expr.arg, join(path, "arg"))
                }

                is Expr.IsNull -> {
                    operand(expr, expr.ref, join(path, "arg"), ParameterCardinality.SINGLE, binaryAllowed = true)
                }

                is Expr.IsEmpty -> {
                    operand(expr, expr.ref, join(path, "arg"), ParameterCardinality.MULTI)
                }

                is Expr.Compare -> {
                    operand(
                        expr,
                        expr.left,
                        join(path, "left"),
                        ParameterCardinality.SINGLE,
                    )?.let { literal(it, expr.right, join(path, "right")) }
                }

                is Expr.Contains -> {
                    operand(
                        expr,
                        expr.left,
                        join(path, "left"),
                        ParameterCardinality.MULTI,
                    )?.let { literal(it, expr.right, join(path, "right")) }
                }

                is Expr.In -> {
                    operand(expr, expr.left, join(path, "left"), ParameterCardinality.SINGLE)?.let { target ->
                        expr.right.forEachIndexed { i, literal -> literal(target, literal, "${join(path, "right")}[$i]") }
                    }
                }
            }
        }

        /** The ref's rules; answers the target when its literals should be typed against it, else null. */
        @Suppress("ReturnCount") // one early answer per rule; the target is the fall-through
        private fun operand(
            expr: Expr,
            ref: Ref,
            path: String,
            cardinality: ParameterCardinality,
            binaryAllowed: Boolean = false,
        ): ParameterDefinition? {
            if (ref.name == carrier.name) {
                problems +=
                    problem(
                        ParameterErrorCodes.REF_UNDECLARED,
                        path,
                        "An expression may not reference the parameter it sits on.",
                        ref,
                        "self",
                    )
                return null
            }
            if (ref.name !in carrier.dependsOn) {
                problems +=
                    problem(
                        ParameterErrorCodes.REF_UNDECLARED,
                        path,
                        "'${ref.name.safeEcho()}' is not in '${carrier.name.safeEcho()}''s depends_on; add it there first.",
                        ref,
                        "not_a_dependency",
                    )
                return null
            }
            val target = parameters[ref.name] ?: return null
            if (target.type == LogicalType.BINARY && !binaryAllowed) {
                problems +=
                    problem(
                        ParameterErrorCodes.EXPRESSION_TYPE_UNSUPPORTED,
                        path,
                        "A BINARY parameter may only be tested with is_null; '${expr.op}' was used.",
                        ref,
                        "binary",
                    )
                return null
            }
            if (target.cardinality != cardinality) {
                problems +=
                    problem(
                        ParameterErrorCodes.EXPRESSION_CARDINALITY,
                        path,
                        "'${expr.op}' needs a ${cardinality.wire} parameter; '${ref.name.safeEcho()}' is ${target.cardinality.wire}.",
                        ref,
                        target.cardinality.wire,
                    )
                return null
            }
            return target
        }

        private fun literal(
            target: ParameterDefinition,
            literal: Literal,
            path: String,
        ) {
            val outcome = ParameterCoercion.coerce(target.type, literal.wire)
            if (outcome is ParameterCoercion.Outcome.Rejected) {
                problems +=
                    ExpressionProblem(
                        ParameterErrorCodes.EXPRESSION_LITERAL_TYPE,
                        join(path, "literal"),
                        "The literal is not a ${target.type.wire} wire value (the type of '${target.name.safeEcho()}'): ${outcome.reason}.",
                        mapOf("parameter" to target.name.safeEcho(), "type" to target.type.wire),
                    )
            }
        }

        private fun problem(
            code: String,
            path: String,
            message: String,
            ref: Ref,
            reason: String,
        ) = ExpressionProblem(code, join(path, "ref"), message, mapOf("ref" to ref.name.safeEcho(), "reason" to reason))
    }

    private fun join(
        path: String,
        key: String,
    ): String = if (path.isEmpty()) key else "$path.$key"
}

/**
 * The §7.3 runtime semantics — a recursive interpreter over a tree the save-time rules already
 * accepted (its depth is bounded by `max-expression-depth`; nothing is parsed from text here).
 *
 * - The values compared are the parents' RESOLVED canonical values (post-reset, post-default):
 *   a `SINGLE` parent's value or null, a `MULTI` parent's list (empty or null = nothing).
 * - Any compare whose ref is null (`SINGLE`) or empty (`MULTI`) is false — `neq` included;
 *   `is_null` / `is_empty` are the only tests for absence. A null expression is false (§3.2).
 * - `and` / `or` are strict: every argument is evaluated (there are no side effects to observe).
 * - Equality is the canonical value's: `BigDecimal` by `compareTo` (`1.0` = `1.00`), an instant by
 *   its instant, a `STRING` exactly (case-sensitive, never trimmed), bytes by content.
 */
object ExpressionEvaluator {
    /**
     * Evaluates [expr] against [values] (parent name → resolved canonical value). [types] gives each
     * referenced parent's type, which types the literals exactly as the save-time checker did.
     *
     * @throws IllegalStateException when a literal does not coerce — an expression that did not pass
     *   [ExpressionChecker] reached the runtime (the `CalculatorInputResolver` convention).
     */
    fun evaluate(
        expr: Expr?,
        values: Map<String, Any?>,
        types: Map<String, LogicalType>,
    ): Boolean = expr != null && Evaluation(values, types).holds(expr)

    private class Evaluation(
        private val values: Map<String, Any?>,
        private val types: Map<String, LogicalType>,
    ) {
        /** Whether [expr] holds — an interpreter over the closed §7 grammar; nothing here executes author text. */
        fun holds(expr: Expr): Boolean =
            when (expr) {
                is Expr.And -> {
                    expr.args.map(::holds).all { it }
                }

                is Expr.Or -> {
                    expr.args.map(::holds).any { it }
                }

                is Expr.Not -> {
                    !holds(expr.arg)
                }

                is Expr.IsNull -> {
                    values[expr.ref.name] == null
                }

                is Expr.IsEmpty -> {
                    members(expr.ref).isEmpty()
                }

                is Expr.Compare -> {
                    compare(expr)
                }

                is Expr.In -> {
                    single(expr.left)?.let { value -> expr.right.any { canonicalEquals(value, typed(expr.left, it)) } } ?: false
                }

                is Expr.Contains -> {
                    members(expr.left).let { list ->
                        list.isNotEmpty() &&
                            list.any { canonicalEquals(it, typed(expr.left, expr.right)) }
                    }
                }
            }

        private fun compare(expr: Expr.Compare): Boolean {
            val value = single(expr.left) ?: return false
            val equal = canonicalEquals(value, typed(expr.left, expr.right))
            return if (expr.op == Expr.EQ) equal else !equal
        }

        private fun single(ref: Ref): Any? = values[ref.name]

        private fun members(ref: Ref): List<Any?> =
            when (val value = values[ref.name]) {
                null -> emptyList()
                is Collection<*> -> value.toList()
                else -> listOf(value)
            }

        private fun typed(
            ref: Ref,
            literal: Literal,
        ): Any {
            val type =
                checkNotNull(
                    types[ref.name],
                ) { "no type for '${ref.name}' — an expression that did not pass save-time checks reached evaluate" }
            return when (val outcome = ParameterCoercion.coerce(type, literal.wire)) {
                is ParameterCoercion.Outcome.Coerced -> {
                    outcome.value
                }

                is ParameterCoercion.Outcome.Rejected -> {
                    error(
                        "literal for '${ref.name}' does not coerce to ${type.wire} — an unchecked expression reached evaluate",
                    )
                }
            }
        }
    }

    /** The §7.3 equality of two canonical values. */
    fun canonicalEquals(
        a: Any?,
        b: Any?,
    ): Boolean =
        when {
            a == null || b == null -> false
            a is BigDecimal && b is BigDecimal -> a.compareTo(b) == 0
            a is ByteArray && b is ByteArray -> a.contentEquals(b)
            a is OffsetDateTime && b is OffsetDateTime -> a.isEqual(b)
            a is ZonedDateTime && b is ZonedDateTime -> a.isEqual(b)
            else -> a == b
        }
}
