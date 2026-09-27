package co.datapipelines.parameters

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * The expression AST of a parameter's `hidden_expression` / `disabled_expression` (record §7, P12):
 * a server-evaluated boolean predicate over the parameter's PARENTS, stored as JSON — never a
 * string language (SpEL and GraalJS were rejected; a textual syntax may compile to this later).
 *
 * ```
 * expr    := logical | test | compare
 * logical := {"op": "and"|"or", "args": [expr, …]} | {"op": "not", "arg": expr}
 * test    := {"op": "is_null"|"is_empty", "arg": ref}
 * compare := {"op": "eq"|"neq"|"contains", "left": ref, "right": literal}
 *          | {"op": "in", "left": ref, "right": [literal, …]}
 * ref     := {"ref": "<parameter name in depends_on>"}
 * literal := {"literal": <wire value for the ref's type>}
 * ```
 *
 * [ExpressionParser] builds it (context-free, bounded by an explicit depth counter and a node
 * budget); [ExpressionChecker] applies the §7.2 static rules against the set; [ExpressionPrinter]
 * writes the canonical form the stored body carries; [ExpressionEvaluator] interprets it (§7.3).
 */
sealed interface Expr {
    /** The wire `op`. */
    val op: String

    /** `and` — true when every argument is (strict: every argument is evaluated; there are no side effects). */
    data class And(
        val args: List<Expr>,
    ) : Expr {
        override val op: String get() = AND
    }

    /** `or` — true when any argument is. */
    data class Or(
        val args: List<Expr>,
    ) : Expr {
        override val op: String get() = OR
    }

    /** `not`. */
    data class Not(
        val arg: Expr,
    ) : Expr {
        override val op: String get() = NOT
    }

    /** `is_null` — a `SINGLE` parent resolved to nothing. One of the two absence tests. */
    data class IsNull(
        val ref: Ref,
    ) : Expr {
        override val op: String get() = IS_NULL
    }

    /** `is_empty` — a `MULTI` parent resolved to no member. The other absence test. */
    data class IsEmpty(
        val ref: Ref,
    ) : Expr {
        override val op: String get() = IS_EMPTY
    }

    /** `eq` / `neq` against one literal — `SINGLE` refs; false whenever the ref is null. */
    data class Compare(
        override val op: String,
        val left: Ref,
        val right: Literal,
    ) : Expr

    /** `in` — the `SINGLE` ref is one of 1..256 literals. */
    data class In(
        val left: Ref,
        val right: List<Literal>,
    ) : Expr {
        override val op: String get() = IN
    }

    /** `contains` — the `MULTI` ref holds the literal. */
    data class Contains(
        val left: Ref,
        val right: Literal,
    ) : Expr {
        override val op: String get() = CONTAINS
    }

    companion object {
        const val AND = "and"
        const val OR = "or"
        const val NOT = "not"
        const val IS_NULL = "is_null"
        const val IS_EMPTY = "is_empty"
        const val EQ = "eq"
        const val NEQ = "neq"
        const val IN = "in"
        const val CONTAINS = "contains"

        /** Every operator of §7.1, in the grammar's order. */
        val OPERATORS: List<String> = listOf(AND, OR, NOT, IS_NULL, IS_EMPTY, EQ, NEQ, IN, CONTAINS)

        /** §7.1: an `in` list holds 1..256 literals. */
        const val MAX_IN_LITERALS: Int = 256
    }
}

/** A reference to a parent parameter by name. */
data class Ref(
    val name: String,
)

/** A literal, as the author wrote it — typed against its ref's `LogicalType` by the checker (and at evaluate). */
data class Literal(
    val wire: JsonNode,
)

/**
 * Writes an [Expr] in its canonical form — the grammar's keys only, in the grammar's order, literals
 * as the author wrote them (nothing is normalised, P19). The stored body carries exactly this, so
 * `ExpressionParser.parse(print(e)) == e` for every expression the parser accepts.
 */
object ExpressionPrinter {
    private val nodes = JsonNodeFactory.instance

    fun print(expr: Expr): ObjectNode {
        val out = nodes.objectNode().put("op", expr.op)
        when (expr) {
            is Expr.And -> out.set<JsonNode>("args", nodes.arrayNode().addAll(expr.args.map { print(it) }))
            is Expr.Or -> out.set<JsonNode>("args", nodes.arrayNode().addAll(expr.args.map { print(it) }))
            is Expr.Not -> out.set<JsonNode>("arg", print(expr.arg))
            is Expr.IsNull -> out.set<JsonNode>("arg", ref(expr.ref))
            is Expr.IsEmpty -> out.set<JsonNode>("arg", ref(expr.ref))
            is Expr.Compare -> out.compare(expr.left, literal(expr.right))
            is Expr.Contains -> out.compare(expr.left, literal(expr.right))
            is Expr.In -> out.compare(expr.left, nodes.arrayNode().addAll(expr.right.map { literal(it) }))
        }
        return out
    }

    private fun ObjectNode.compare(
        left: Ref,
        right: JsonNode,
    ) {
        set<JsonNode>("left", ref(left))
        set<JsonNode>("right", right)
    }

    private fun ref(ref: Ref): ObjectNode = nodes.objectNode().put("ref", ref.name)

    private fun literal(literal: Literal): ObjectNode = nodes.objectNode().set("literal", literal.wire)
}

/** Every `ref` in [expr], in document order (duplicates kept — callers dedupe). */
fun Expr.refs(): List<Ref> =
    when (this) {
        is Expr.And -> args.flatMap { it.refs() }
        is Expr.Or -> args.flatMap { it.refs() }
        is Expr.Not -> arg.refs()
        is Expr.IsNull -> listOf(ref)
        is Expr.IsEmpty -> listOf(ref)
        is Expr.Compare -> listOf(left)
        is Expr.In -> listOf(left)
        is Expr.Contains -> listOf(left)
    }
