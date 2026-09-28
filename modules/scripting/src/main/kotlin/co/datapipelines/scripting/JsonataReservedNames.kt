package co.datapipelines.scripting

import com.dashjoin.jsonata.Jsonata
import com.dashjoin.jsonata.Parser
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.ArrayDeque
import java.util.Collections
import java.util.IdentityHashMap

/**
 * The reserved prefix `__` (#272): refuses a body that BINDS a name the engine owns.
 *
 * The library installs the engine's depth and wall-clock hooks as the frame variables
 * `__evaluate_entry`/`__evaluate_exit` and looks them up through the frame chain at every
 * step (jsonata 0.9.10 `Jsonata.java:155, 230`); `Frame.lookup` returns a bound null. A body
 * that binds either name therefore switches both checks off for every deeper step. Those
 * hooks are a RUNAWAY guard — the sandbox bound against a hostile body is the evaluation
 * pool's abandonment (owner ruling 2026-09-28, transform-nodes record §4.5) — but a guard a
 * body can switch off is no guard, so every name under the prefix is the engine's.
 *
 * The check reads the library's PARSED AST (`Parser.Symbol`), never the source text: a
 * regex cannot tell a bind from a string literal, a comment or a read. The binding forms
 * the parser produces, each pinned by `JsonataReservedNamesTest`:
 *
 *  - `bind` — `$x := …`, the name on `lhs.value`;
 *  - `lambda` — a parameter, `function($x){…}`, the names on `arguments[*].value`;
 *  - `focus` — `path@$x`, the name on the step;
 *  - `index` — `path#$i`, the name on the step, or an `index` stage after a predicate.
 *
 * The AST's fields are package-private, so they are read reflectively. Every field is
 * resolved when this object initialises: a library upgrade that renames one fails every
 * compile LOUDLY (fail closed) instead of letting a bind slip past a walk that can no
 * longer see it. At compile the walk reads the tree the library already parsed
 * (`Jsonata.ast`, the one that evaluates — one parse, not two: a 500-deep body sits near
 * the thread's stack limit in the library's recursive `processAST`). For `$eval`, whose
 * string the library parses privately, it parses with `Parser.parse` rather than
 * `Jsonata.jsonata`, because the latter resets the thread's current evaluation
 * (`Jsonata.current`) — which the library's `$eval` then reads.
 */
internal object JsonataReservedNames {
    /** Names under this prefix belong to the engine. */
    const val PREFIX = "__"

    /** A bind of a reserved [name] (without its `$`) at the library's 0-based [position]. */
    data class Bind(
        val name: String,
        val position: Int,
    )

    private val symbolClass: Class<Parser.Symbol> = Parser.Symbol::class.java

    private val typeField = field("type")
    private val valueField = field("value")
    private val lhsField = field("lhs")
    private val argumentsField = field("arguments")
    private val focusField = field("focus")
    private val indexField = field("index")
    private val positionField = field("position")

    /** The parsed tree a compiled expression holds (package-private `Jsonata.ast`). */
    private val astField: Field = declared(Jsonata::class.java, "ast")

    /** Every instance field of a node (`this$0`, the parser, is synthetic and skipped). */
    private val childFields: List<Field> =
        symbolClass.declaredFields
            .filter { !Modifier.isStatic(it.modifiers) && !it.isSynthetic }
            .onEach { it.isAccessible = true }

    /** The first reserved bind in the tree [expr] already holds, if any. */
    fun firstReservedBind(expr: Jsonata): Bind? {
        val ast = astField.get(expr) as? Parser.Symbol
        checkNotNull(ast) { "a compiled JSONata expression holds no parsed tree — the reserved-name check cannot run" }
        return firstReservedBind(ast)
    }

    /** Parses [source] with the library's own parser and returns its first reserved bind, if any. */
    fun firstReservedBind(source: String): Bind? = firstReservedBind(Parser().parse(source))

    /** The reserved bind with the smallest position in [ast], or null when there is none. */
    fun firstReservedBind(ast: Parser.Symbol): Bind? =
        bindsIn(ast)
            .filter { it.name.startsWith(PREFIX) }
            .minByOrNull { it.position }

    /** The refusal sentence every surface shows — one wording for compile and for `$eval`. */
    fun refusal(bind: Bind): String =
        "the name '\$${bind.name}' is reserved: names starting with '$PREFIX' belong to the engine " +
            "and a body may not bind them"

    private fun bindsIn(ast: Parser.Symbol): List<Bind> {
        val seen = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        val pending = ArrayDeque<Parser.Symbol>().apply { push(ast) }
        val binds = mutableListOf<Bind>()
        while (pending.isNotEmpty()) {
            val node = pending.pop()
            if (!seen.add(node)) continue
            binds += bindsAt(node)
            childFields.forEach { f -> children(f.get(node)).forEach(pending::push) }
        }
        return binds
    }

    private fun bindsAt(node: Parser.Symbol): List<Bind> {
        val at = positionField.getInt(node)
        val names = mutableListOf<Bind>()
        when (typeField.get(node)) {
            "bind" -> {
                (lhsField.get(node) as? Parser.Symbol)?.let { names += Bind(nameOf(it), positionField.getInt(it)) }
            }

            "lambda" -> {
                (argumentsField.get(node) as? List<*>)
                    ?.filterIsInstance<Parser.Symbol>()
                    ?.forEach { names += Bind(nameOf(it), positionField.getInt(it)) }
            }

            "index" -> {
                (valueField.get(node) as? String)?.let { names += Bind(it, at) }
            }
        }
        (focusField.get(node) as? String)?.let { names += Bind(it, at) }
        (indexField.get(node) as? String)?.let { names += Bind(it, at) }
        return names
    }

    private fun nameOf(variable: Parser.Symbol): String = valueField.get(variable)?.toString() ?: ""

    /** The nodes a field value holds: a node, a list of nodes, or a list of node pairs (object constructors). */
    private fun children(value: Any?): List<Parser.Symbol> =
        when (value) {
            is Parser.Symbol -> listOf(value)
            is Collection<*> -> value.flatMap(::children)
            is Array<*> -> value.flatMap(::children)
            else -> emptyList()
        }

    private fun field(name: String): Field = declared(symbolClass, name)

    private fun declared(
        owner: Class<*>,
        name: String,
    ): Field =
        try {
            owner.getDeclaredField(name).apply { isAccessible = true }
        } catch (err: NoSuchFieldException) {
            throw IllegalStateException(
                "the JSONata library's ${owner.simpleName} no longer has the field '$name' — the reserved-name " +
                    "check cannot run, so no body compiles until JsonataReservedNames is re-derived for this " +
                    "library version",
                err,
            )
        }
}
