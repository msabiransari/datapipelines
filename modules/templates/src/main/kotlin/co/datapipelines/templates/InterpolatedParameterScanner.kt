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
 *    into the SQL, and 042 B1 leaves structure to the author. The one exception is [scan]'s
 *    `guarded` set (078 A1): a CALCULATOR output key is refused there too, because a derived
 *    value gating SQL structure is the interpolation hole one directive earlier;
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
    /**
     * Every [declared] name the body references inside a `${}` interpolation, in first-use order.
     *
     * [guarded] names are refused in one more position: a conditional's test (`<#if x??>`,
     * `<#elseif x>`). A declared parameter in a directive *test* stays legal (042 B1 — the test
     * gates structure, it writes no value), but a CALCULATOR output key is a *derived* value —
     * one that can originate from a caller STRING through `coalesce`/`if_null` — and gating SQL
     * structure on it is the same hole as interpolating it, one directive earlier (078 A1).
     * Guarded names are therefore reported from BOTH positions; [declared]-only names only from
     * interpolations.
     *
     * ## The assignment taint (#285, option (a) of the issue)
     *
     * An indirection — `<#assign x = region>${x}` — writes the caller's value into the SQL text
     * through a name the scan did not report, so the walk follows it: an `<#assign>`/`<#local>`/
     * `<#global>` whose VALUE expression references a declared, guarded or already-tainted name
     * makes its TARGET a reference from that point on. The taint is transitive (y = x carries it),
     * order-respecting (a `${}` is judged against the taints created before it in source order)
     * and cleared by shadowing exactly as the direct references are (a macro parameter or loop
     * variable shadowing a tainted name is clean). A literal value taints nothing:
     * `<#assign x = 1>${x}` stays legal. Option (c) of the issue — rendering selectors without
     * the parents' values — is refused by the parameter-engine record (§5.2 renders parents'
     * values on purpose); option (b), refusing `.vars` access wholesale, would break the
     * special-variable spellings `ef372199` deliberately reports.
     */
    fun scan(
        body: String,
        declared: Set<String>,
        guarded: Set<String> = emptySet(),
    ): List<String> {
        if (declared.isEmpty() && guarded.isEmpty()) return emptyList()
        val parsed = TemplateBodyParser.parse(body) as? BodyParse.Parsed ?: return emptyList()
        val found = LinkedHashSet<String>()
        walk(parsed.template.rootTreeNode, declared, guarded, emptySet(), mutableMapOf(), found)
        return found.toList()
    }

    private fun walk(
        element: TemplateElement?,
        declared: Set<String>,
        guarded: Set<String>,
        shadowed: Set<String>,
        tainted: MutableMap<String, Set<String>>,
        found: MutableSet<String>,
    ) {
        if (element == null) return
        when (FreemarkerAst.typeOf(element)) {
            FreemarkerAst.DOLLAR_VARIABLE -> {
                val expression = FreemarkerAst.ownText(element)
                reportMatches(expression, declared + guarded, shadowed, found)
                // An alias of a declared value — `x` assigned from `region` — reports the
                // DECLARED sources it carries, never the alias: the caller refuses the
                // parameter whose value would land in the SQL text.
                tainted.forEach { (alias, sources) ->
                    if (alias !in shadowed && isReferencedIn(expression, alias)) found += sources
                }
                return // the interpolation's expression subtree is not template elements
            }

            FreemarkerAst.IF_BLOCK, FreemarkerAst.CONDITIONAL_BLOCK -> {
                // The branch's own text prints its condition (`#if x??`, `#elseif x`); children
                // are walked normally below, so nested conditionals report for themselves.
                reportMatches(FreemarkerAst.ownText(element), guarded, shadowed, found)
            }

            FreemarkerAst.ASSIGNMENT -> {
                taintAssignment(FreemarkerAst.ownText(element), declared + guarded, shadowed, tainted)
            }

            FreemarkerAst.MACRO -> {
                walkChildren(element, declared, guarded, shadowed + macroParameters(FreemarkerAst.ownText(element)), tainted, found)
                return
            }

            FreemarkerAst.ITERATOR_BLOCK -> {
                walkIterator(element, declared, guarded, shadowed, tainted, found)
                return
            }

            FreemarkerAst.UNIFIED_CALL -> {
                reportCallArguments(FreemarkerAst.ownText(element), declared + guarded, shadowed, tainted, found)
            }
        }
        walkChildren(element, declared, guarded, shadowed, tainted, found)
    }

    /**
     * `<#list region?split(",") as r>${r}` carries the parameter's value through the loop
     * variable (the 279 pass, finding 3): the variables are TAINTED with the list expression's
     * sources when it has any, and shadow an outer meaning otherwise.
     */
    @Suppress("LongParameterList") // the walk's state, passed in — as walk() and walkChildren() take it
    private fun walkIterator(
        element: TemplateElement,
        declared: Set<String>,
        guarded: Set<String>,
        shadowed: Set<String>,
        tainted: MutableMap<String, Set<String>>,
        found: MutableSet<String>,
    ) {
        val description = FreemarkerAst.ownText(element)
        val loopVariables = loopVariablesOf(description)
        val sources = taintSources(listExpressionOf(description), declared + guarded, shadowed, tainted)
        if (sources.isEmpty()) {
            walkChildren(element, declared, guarded, shadowed + loopVariables, tainted, found)
        } else {
            val scoped = LinkedHashMap(tainted)
            loopVariables.forEach { scoped[it] = sources }
            walkChildren(element, declared, guarded, shadowed - loopVariables, scoped, found)
        }
    }

    /**
     * `<@where v=region/>` hands the value to a macro whose body interpolates it: every declared
     * or tainted name among the call's ARGUMENT expressions is reported (an over-refusal in the
     * accepted direction — the macro's own parameters are shadowed inside its body, so the call
     * is the only place the value is visible). String literals are blanked first: `col="region"`
     * names a column, not the parameter.
     */
    private fun reportCallArguments(
        description: String,
        watched: Set<String>,
        shadowed: Set<String>,
        tainted: Map<String, Set<String>>,
        found: MutableSet<String>,
    ) {
        val arguments = withoutStringLiterals(description)
        reportMatches(arguments, watched, shadowed, found)
        tainted.forEach { (alias, sources) ->
            if (alias !in shadowed && isReferencedIn(arguments, alias)) found += sources
        }
    }

    private fun walkChildren(
        element: TemplateElement,
        declared: Set<String>,
        guarded: Set<String>,
        shadowed: Set<String>,
        tainted: MutableMap<String, Set<String>>,
        found: MutableSet<String>,
    ): Unit = FreemarkerAst.childrenOf(element).forEach { walk(it, declared, guarded, shadowed, tainted, found) }

    /** Every [names] entry used as a variable in [expression] and not shadowed here. */
    private fun reportMatches(
        expression: String,
        names: Set<String>,
        shadowed: Set<String>,
        found: MutableSet<String>,
    ) = names.forEach { name ->
        if (name !in shadowed && isReferencedIn(expression, name)) found += name
    }

    /**
     * The taint rule for one assignment node's description (the shapes pinned in
     * [FreemarkerAstDriftTest]): a freestanding `<#assign x = e>` prints `#assign x = e`
     * (`#local`/`#global` likewise), and a `scope`/`namespace` attribute inside an
     * `AssignmentInstruction` container prints `scope = "global"` — the two words reserved for
     * the directive's own parameters are skipped so they are never read as targets. When the
     * value expression references a watched name (a declared or guarded parameter directly, or
     * an already-tainted alias) that is not [shadowed] here, the target becomes tainted with
     * that name's SOURCES — so `y = x` carries `region` itself to wherever `y` interpolates.
     */
    private fun taintAssignment(
        description: String,
        watched: Set<String>,
        shadowed: Set<String>,
        tainted: MutableMap<String, Set<String>>,
    ) {
        val body =
            when {
                description.startsWith(ASSIGN_KEYWORD) -> description.removePrefix(ASSIGN_KEYWORD)
                description.startsWith(LOCAL_KEYWORD) -> description.removePrefix(LOCAL_KEYWORD)
                description.startsWith(GLOBAL_KEYWORD) -> description.removePrefix(GLOBAL_KEYWORD)
                else -> description
            }
        val operator = ASSIGNMENT_OPERATOR.find(body) ?: return
        val target = operator.groupValues[GROUP_TARGET]
        val value = operator.groupValues[GROUP_VALUE]
        if (target in KEYWORD_TARGETS) return
        val sources = taintSources(value, watched, shadowed, tainted)
        if (sources.isNotEmpty()) tainted[target] = (tainted[target] ?: emptySet()) + sources
    }

    /** The parameter names [value] carries: watched names it references directly, and what tainted aliases carry. */
    private fun taintSources(
        value: String,
        watched: Set<String>,
        shadowed: Set<String>,
        tainted: Map<String, Set<String>>,
    ): Set<String> {
        val sources = mutableSetOf<String>()
        watched.forEach { name ->
            if (name !in shadowed && isReferencedIn(value, name)) sources += name
        }
        tainted.forEach { (alias, carried) ->
            if (alias !in shadowed && isReferencedIn(value, alias)) sources += carried
        }
        return sources
    }

    /**
     * [name] used as a variable in an interpolation expression — identifier-bounded, and not
     * after a `.`, because declared parameters are flat scalars and `x.customer_id` can never
     * resolve to the parameter named `customer_id` — EXCEPT after one of FreeMarker's special
     * variables that expose the data model: `${.vars.customer_id}`, `${.data_model.customer_id}`
     * (and `.globals`, `.main`, `.namespace`, `.locals`) resolve exactly as `${customer_id}` does,
     * so a caller's value would land in SQL text past this guard (the 194c security pass; verified
     * on the pinned FreeMarker — the interpolation's description prints the dotted path verbatim).
     * The bracket forms `${.vars["customer_id"]}` were always caught: a quote precedes the name.
     */
    private fun isReferencedIn(
        expression: String,
        name: String,
    ): Boolean {
        val bare = Regex("(?<![A-Za-z0-9_.])${Regex.escape(name)}(?![A-Za-z0-9_])")
        val throughSpecialVariable = Regex("(?<![A-Za-z0-9_])\\.(?:$SPECIAL_VARIABLES)\\.${Regex.escape(name)}(?![A-Za-z0-9_])")
        return bare.containsMatchIn(expression) || throughSpecialVariable.containsMatchIn(expression)
    }

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
    private fun loopVariablesOf(description: String): Set<String> =
        LOOP_VARIABLES
            .find(description)
            ?.groupValues
            ?.drop(1)
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            ?: emptySet()

    /** The listed expression of `#list <expr> as k[, v]` — what the loop variables carry. */
    private fun listExpressionOf(description: String): String = description.removePrefix(LIST_KEYWORD).substringBefore(" as ")

    /** [text] with every string literal blanked, so a quoted column name never reads as a parameter reference. */
    private fun withoutStringLiterals(text: String): String = STRING_LITERAL.replace(text, "\"\"")

    /** FreeMarker's special variables that resolve a name against the data model (`.vars.x` is `x`). */
    private const val SPECIAL_VARIABLES = "vars|data_model|globals|main|namespace|locals"

    /** The assignment descriptions' three directive keywords (verified on the pinned 2.3.34 jar). */
    private const val ASSIGN_KEYWORD = "#assign "
    private const val LOCAL_KEYWORD = "#local "
    private const val GLOBAL_KEYWORD = "#global "

    /**
     * The directive's own parameters print as assignment children of an `AssignmentInstruction`
     * container (`scope = "global"`, `namespace = .namespace`) — targets that are never
     * variables, so they are never tainted.
     */
    private val KEYWORD_TARGETS = setOf("scope", "namespace")

    /**
     * `TARGET (op) VALUE` of one assignment description: the target identifier, a simple or
     * compound assignment operator, and the value expression text. An increment (`#assign x++`)
     * carries no operator and taints nothing — it reads and writes an existing variable, which
     * the taint of that variable already covers.
     */
    private val ASSIGNMENT_OPERATOR = Regex("""^\s*([A-Za-z_][A-Za-z0-9_]*)\s*(\+=|-=|\*=|/=|%=|=)\s*(.*)$""")

    /** The regex groups of [ASSIGNMENT_OPERATOR] (detekt's magic-number rule, honoured at the source). */
    private const val GROUP_TARGET = 1
    private const val GROUP_VALUE = 3

    private val IDENTIFIER = Regex("""[A-Za-z_][A-Za-z0-9_]*""")

    private val TOKEN_SPLIT = Regex("""[\s,]+""")

    /** `as k` or `as k, v` (a hash listing) — one or two loop variables. */
    private val LOOP_VARIABLES = Regex("""\bas\s+([A-Za-z_][A-Za-z0-9_]*)(?:\s*,\s*([A-Za-z_][A-Za-z0-9_]*))?""")
    private const val LIST_KEYWORD = "#list "
    private val STRING_LITERAL = Regex(""""(?:[^"\\]|\\.)*"|'(?:[^'\\]|\\.)*'""")
}
