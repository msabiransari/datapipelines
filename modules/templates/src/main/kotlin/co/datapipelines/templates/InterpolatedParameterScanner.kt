package co.datapipelines.templates

import freemarker.core.TemplateElement

/**
 * The save-time scan behind 042 B2: **which declared pipeline parameters does a template body
 * reference inside `${}` interpolations?**
 *
 * The rule it enforces: a parameter DECLARED in the pipeline's `parameters` block is a value,
 * referenced as `:name` and bound as a SQL parameter. `${}` interpolation is for *structure*
 * (table names, dynamic fragments, `ORDER BY`), which stays the author's responsibility.
 * Interpolating a declared parameter puts a caller-supplied value straight into the SQL
 * string — the injection path 042 closes — so a body that does it is refused at pipeline save
 * with `template.validation.parameter_interpolated`.
 *
 * ## Why an AST scan, and what it sees
 *
 * The same reasoning as [ForbiddenConstructScanner]: a regex over source text can be lied to,
 * the parse tree cannot. The scan walks the tree and reports a declared name only when it
 * appears in a [FreemarkerAst.DOLLAR_VARIABLE]'s expression — which is exactly a value about
 * to be written into the output. Deliberately NOT reported:
 *
 *  - `<#if customer_id>` and friends — a directive *test* gates structure, it writes no value
 *    into the SQL, and 042 B1 leaves structure to the author;
 *  - `` `${"customer_id"}` `` — hmm, this one IS reported: the parser cannot tell a string
 *    literal from a variable at the description level, so a literal equal to a declared name
 *    is the accepted rare false positive (refuse, don't miss — the same direction §4.2 takes);
 *  - `\${customer_id}` — pinned against 2.3.34: the backslash is literal text and the
 *    interpolation is LIVE (renders the value), so the scan flags it. There is no spelling
 *    that hides a live interpolation from the tree.
 *
 * ## Scope tracking — where a shadowed name is not the parameter
 *
 * A body may bind a local that happens to share a declared name: `<#macro m customer_id>` or
 * `<#list rows as customer_id>`. Inside those scopes the interpolation writes the LOCAL's
 * value, not the caller-supplied parameter, so the scan must not refuse it. Binding extraction
 * is description-based (pinned in [FreemarkerAstDriftTest]): `#macro m customer_id x=1` /
 * `#function f(customer_id)` and `#list rows as customer_id`.
 *
 * `<#assign>` / `<#local>` / `<#global>` targets are deliberately **not** exempted: the only
 * interpolation that can read an assigned name is a *sibling* of the assignment, and the
 * assigned value can itself have been copied from the parameter (`<#assign x = customer_id>`),
 * so exempting it would admit an indirection the rule exists to close. The refusal message
 * tells the author to rename the local or reference the parameter as `:name` — over-refusal
 * costs a rename; a miss re-opens the hole.
 */
@Suppress("DEPRECATION") // freemarker.core.TemplateElement — see FreemarkerAst
internal object InterpolatedParameterScanner {
    /** Every [declared] name the body references inside a `${}` interpolation, in first-use order. */
    fun scan(
        body: String,
        declared: Set<String>,
    ): List<String> {
        if (declared.isEmpty()) return emptyList()
        val parsed = TemplateBodyParser.parse(body) as? BodyParse.Parsed ?: return emptyList()
        val found = LinkedHashSet<String>()
        walk(parsed.template.rootTreeNode, declared, emptySet(), found)
        return found.toList()
    }

    private fun walk(
        element: TemplateElement?,
        declared: Set<String>,
        shadowed: Set<String>,
        found: MutableSet<String>,
    ) {
        if (element == null) return
        val type = FreemarkerAst.typeOf(element)
        when (type) {
            FreemarkerAst.DOLLAR_VARIABLE -> {
                val text = FreemarkerAst.ownText(element)
                declared.forEach { name ->
                    if (name !in shadowed && isReferencedIn(text, name)) found += name
                }
                return // the interpolation's expression subtree is not template elements
            }

            FreemarkerAst.MACRO -> {
                val inner = shadowed + macroParameters(FreemarkerAst.ownText(element))
                FreemarkerAst.childrenOf(element).forEach { walk(it, declared, inner, found) }
                return
            }

            FreemarkerAst.ITERATOR_BLOCK -> {
                val inner = shadowed + loopVariableOf(FreemarkerAst.ownText(element))
                FreemarkerAst.childrenOf(element).forEach { walk(it, declared, inner, found) }
                return
            }
        }
        FreemarkerAst.childrenOf(element).forEach { walk(it, declared, shadowed, found) }
    }

    /**
     * [name] used as a variable in an interpolation expression — identifier-bounded, and not
     * after a `.`, because declared parameters are flat scalars and `x.customer_id` can never
     * resolve to the parameter named `customer_id`.
     */
    private fun isReferencedIn(
        expression: String,
        name: String,
    ): Boolean = Regex("(?<![A-Za-z0-9_.])${Regex.escape(name)}(?![A-Za-z0-9_])").containsMatchIn(expression)

    /**
     * The parameter names of a `<#macro>`/`<#function>` element. Both spellings print through
     * [FreemarkerAst.ownText] as `#macro m customer_id x=1` and `#function f(customer_id)` —
     * pinned in [FreemarkerAstDriftTest].
     */
    private fun macroParameters(description: String): Set<String> {
        val afterKeyword =
            description
                .substringAfter("#macro ")
                .ifEmpty { description.substringAfter("#function ") }
        val tokens =
            afterKeyword
                .replace('(', ' ')
                .replace(')', ' ')
                .trim()
                .split(TOKEN_SPLIT)
        return tokens
            .drop(1)
            .map { it.substringBefore('=') }
            .filter { IDENTIFIER.matches(it) }
            .toSet()
    }

    /** The loop variable of `<#list rows as x>` — prints as `#list rows as x`. */
    private fun loopVariableOf(description: String): Set<String> =
        LOOP_VARIABLE.find(description)?.let { setOf(it.groupValues[1]) } ?: emptySet()

    private val IDENTIFIER = Regex("""[A-Za-z_][A-Za-z0-9_]*""")

    private val TOKEN_SPLIT = Regex("""[\s,]+""")

    private val LOOP_VARIABLE = Regex("""\bas\s+([A-Za-z_][A-Za-z0-9_]*)""")
}
