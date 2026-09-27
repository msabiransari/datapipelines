package co.datapipelines.parameters

import com.fasterxml.jackson.databind.JsonNode

/** One refusal found in an expression — [path] is relative to the expression's root (`args[1].left`). */
data class ExpressionProblem(
    val code: String,
    val path: String,
    val message: String,
    val details: Map<String, Any?> = emptyMap(),
)

/** What [ExpressionParser.parse] produced. */
sealed interface ExpressionParse {
    data class Parsed(
        val expr: Expr,
    ) : ExpressionParse

    data class Refused(
        val problems: List<ExpressionProblem>,
    ) : ExpressionParse
}

/**
 * Parses a §7 expression from its JSON — a recursive descent whose bound is an EXPLICIT depth counter
 * checked BEFORE every descent, never the host stack (the 260 lesson): nesting past
 * [maxDepth] (`max-expression-depth`, ≤ 64 by configuration) is refused with
 * `parameter.validation.expression_depth_exceeded` and not walked further, so the recursion is never
 * deeper than the configured bound whatever the document holds. A budget of [maxNodes]
 * (`max-expression-nodes`) counts OPERATOR nodes — a ref and its literals belong to their operator,
 * whose `in` list is separately capped at 256 — and the walk stops at the first node past it
 * (`expression_too_large`), so the work is bounded by the configuration, not the document.
 *
 * Context-free: whether a ref names a dependency, and whether a literal fits its ref's type, is
 * [ExpressionChecker]'s. Everything malformed is `expression_invalid` with a `details.reason`
 * (`not_an_object`, `unknown_op`, `unknown_key`, `missing`, `wrong_type`, `empty_args`) — the AST
 * is its own grammar, and that is its catch-all, including an unknown key inside it.
 */
class ExpressionParser(
    private val maxDepth: Int = ParametersKey.MAX_EXPRESSION_DEPTH.default.toInt(),
    private val maxNodes: Int = ParametersKey.MAX_EXPRESSION_NODES.default.toInt(),
) {
    constructor(config: ParametersConfig) : this(config.maxExpressionDepth, config.maxExpressionNodes)

    fun parse(node: JsonNode): ExpressionParse {
        val walk = Walk()
        val expr = walk.expr(node, "", depth = 1)
        return if (walk.problems.isEmpty() &&
            expr != null
        ) {
            ExpressionParse.Parsed(expr)
        } else {
            ExpressionParse.Refused(walk.problems.toList())
        }
    }

    private inner class Walk {
        val problems = mutableListOf<ExpressionProblem>()
        private var operators = 0
        private var budgetSpent = false

        @Suppress("ReturnCount", "CyclomaticComplexMethod") // one early answer per malformed shape; one arm per operator
        fun expr(
            node: JsonNode,
            path: String,
            depth: Int,
        ): Expr? {
            if (budgetSpent) return null
            if (depth > maxDepth) {
                // One refusal per expression, like the node budget: an `and` at the bound with N scalar
                // args produced N of these (the 194b security pass, F1 — ~1 KB of heap per 2 input bytes).
                budgetSpent = true
                problems +=
                    ExpressionProblem(
                        ParameterErrorCodes.EXPRESSION_DEPTH_EXCEEDED,
                        path,
                        "The expression is nested deeper than $maxDepth (datapipelines.parameters.max-expression-depth).",
                        mapOf("max" to maxDepth),
                    )
                return null
            }
            if (++operators > maxNodes) {
                budgetSpent = true
                problems +=
                    ExpressionProblem(
                        ParameterErrorCodes.EXPRESSION_TOO_LARGE,
                        path,
                        "The expression has more than $maxNodes operators (datapipelines.parameters.max-expression-nodes).",
                        mapOf("max" to maxNodes, "reason" to "nodes"),
                    )
                return null
            }
            if (!node.isObject) return invalid(path, "not_an_object", "An expression is a JSON object with an \"op\".")
            val opNode = node.get("op")
            if (opNode == null || !opNode.isTextual) return invalid(path, "missing", "An expression needs a string \"op\".", "op")
            val op = opNode.asText()
            val keys =
                KEYS[op] ?: return invalid(path, "unknown_op", "'${op.safeEcho()}' is not an operator; allowed: ${Expr.OPERATORS}.", "op")
            val unknown =
                node
                    .fieldNames()
                    .asSequence()
                    .filter { it !in keys }
                    .toList()
            unknown.forEach {
                invalid<Unit>(
                    join(path, it),
                    "unknown_key",
                    "'${it.safeEcho()}' is not a key of '$op'; allowed: ${keys.sorted()}.",
                )
            }
            if (unknown.isNotEmpty()) return null
            return when (op) {
                Expr.AND, Expr.OR -> logical(op, node, path, depth)
                Expr.NOT -> child(node, "arg", path)?.let { expr(it, join(path, "arg"), depth + 1) }?.let(Expr::Not)
                Expr.IS_NULL -> child(node, "arg", path)?.let { ref(it, join(path, "arg")) }?.let(Expr::IsNull)
                Expr.IS_EMPTY -> child(node, "arg", path)?.let { ref(it, join(path, "arg")) }?.let(Expr::IsEmpty)
                Expr.IN -> compareIn(node, path)
                else -> compare(op, node, path)
            }
        }

        @Suppress("ReturnCount") // one early refusal per malformed shape, collected rather than thrown
        private fun logical(
            op: String,
            node: JsonNode,
            path: String,
            depth: Int,
        ): Expr? {
            val args = child(node, "args", path) ?: return null
            if (!args.isArray) return invalid(join(path, "args"), "wrong_type", "'$op' takes an array of expressions.")
            if (args.isEmpty) return invalid(join(path, "args"), "empty_args", "'$op' takes at least one expression.")
            val parsed = args.mapIndexed { index, arg -> expr(arg, "${join(path, "args")}[$index]", depth + 1) }
            if (parsed.any { it == null }) return null
            val list = parsed.filterNotNull()
            return if (op == Expr.AND) Expr.And(list) else Expr.Or(list)
        }

        private fun compare(
            op: String,
            node: JsonNode,
            path: String,
        ): Expr? {
            val left = child(node, "left", path)?.let { ref(it, join(path, "left")) }
            val right = child(node, "right", path)?.let { literal(it, join(path, "right")) }
            if (left == null || right == null) return null
            return if (op == Expr.CONTAINS) Expr.Contains(left, right) else Expr.Compare(op, left, right)
        }

        @Suppress("ReturnCount") // one early refusal per malformed shape, collected rather than thrown
        private fun compareIn(
            node: JsonNode,
            path: String,
        ): Expr? {
            val left = child(node, "left", path)?.let { ref(it, join(path, "left")) }
            val right = child(node, "right", path) ?: return null
            val rightPath = join(path, "right")
            if (!right.isArray) return invalid(rightPath, "wrong_type", "'in' takes an array of literals.")
            if (right.isEmpty) return invalid(rightPath, "empty_args", "'in' takes at least one literal.")
            if (right.size() > Expr.MAX_IN_LITERALS) {
                problems +=
                    ExpressionProblem(
                        ParameterErrorCodes.EXPRESSION_TOO_LARGE,
                        rightPath,
                        "An 'in' list holds at most ${Expr.MAX_IN_LITERALS} literals; got ${right.size()}.",
                        mapOf("max" to Expr.MAX_IN_LITERALS, "reason" to "in_list"),
                    )
                return null
            }
            val literals = right.mapIndexed { index, item -> literal(item, "$rightPath[$index]") }
            if (left == null || literals.any { it == null }) return null
            return Expr.In(left, literals.filterNotNull())
        }

        private fun ref(
            node: JsonNode,
            path: String,
        ): Ref? {
            if (!node.isObject || node.size() != 1 || !node.has("ref")) {
                return invalid(path, "ref_shape", "A ref is exactly {\"ref\": \"<parameter name>\"}.")
            }
            val name = node.get("ref")
            if (!name.isTextual) return invalid(join(path, "ref"), "wrong_type", "A ref names a parameter as a string.")
            return Ref(name.asText())
        }

        private fun literal(
            node: JsonNode,
            path: String,
        ): Literal? {
            if (!node.isObject || node.size() != 1 || !node.has("literal")) {
                return invalid(path, "literal_shape", "A literal is exactly {\"literal\": <a wire value>}.")
            }
            return Literal(node.get("literal"))
        }

        private fun child(
            node: JsonNode,
            key: String,
            path: String,
        ): JsonNode? = node.get(key) ?: invalid(join(path, key), "missing", "The operator needs '$key'.")

        private fun <T> invalid(
            path: String,
            reason: String,
            message: String,
            key: String? = null,
        ): T? {
            problems +=
                ExpressionProblem(
                    ParameterErrorCodes.EXPRESSION_INVALID,
                    if (key == null) path else join(path, key),
                    message,
                    mapOf("reason" to reason),
                )
            return null
        }
    }

    private companion object {
        /** The keys each operator admits (§7.1) — anything else is `expression_invalid` / `unknown_key`. */
        val KEYS: Map<String, Set<String>> =
            mapOf(
                Expr.AND to setOf("op", "args"),
                Expr.OR to setOf("op", "args"),
                Expr.NOT to setOf("op", "arg"),
                Expr.IS_NULL to setOf("op", "arg"),
                Expr.IS_EMPTY to setOf("op", "arg"),
                Expr.EQ to setOf("op", "left", "right"),
                Expr.NEQ to setOf("op", "left", "right"),
                Expr.IN to setOf("op", "left", "right"),
                Expr.CONTAINS to setOf("op", "left", "right"),
            )

        fun join(
            path: String,
            key: String,
        ): String = if (path.isEmpty()) key else "$path.$key"
    }
}
