package co.datapipelines.templates

import freemarker.core.TemplateElement

/**
 * The save-time scan for forbidden Freemarker constructs (templates.md §4.2, §7).
 *
 * ## Why this scan is normative, not belt-and-braces
 *
 * `?eval` has **no configuration switch** in Freemarker 2.3.x (verified against the
 * `Configurable` setting list, templates.md §4.3), so this scan is the *only* guard against it.
 * The same is true of `?interpret` and `?eval_json`: they compile a *string* — which can be a
 * render-context value, i.e. a pipeline parameter supplied by an API caller — into a template
 * and execute it. That turns data into source and defeats "a context value is never
 * re-evaluated", so they are scanned for on the same footing.
 *
 * `?api` / `?new` / class instantiation are additionally blocked at render by the §4.3
 * configuration (`ALLOWS_NOTHING_RESOLVER`, `isAPIBuiltinEnabled = false`,
 * [SimpleObjectWrapper][freemarker.template.SimpleObjectWrapper]) — that is the independent
 * second layer the SSTI tests exercise separately. This scan is the first.
 *
 * ## The scan is AST-based (templates.md §4.2, normative)
 *
 * Two evasions defeated the regex-over-stripped-source scanner this replaces, both verified
 * against the pinned jar:
 *  - **A comment marker hidden in a string literal.** `<#assign a="<#--">${"1+1"?eval}<#assign
 *    b="-->">` — a comment-stripping regex deletes the middle, so the scan sees nothing while
 *    Freemarker parses and executes the `?eval`. Every construct §4.2 forbids could be smuggled
 *    this way, which made the sole normative save-time gate bypassable end to end.
 *  - **A leading `[#ftl]`.** It switches the parser to square-bracket tag syntax, so `[#include
 *    "…"]` is a live directive that an angle-bracket regex never matches. Empirically this is
 *    *not* fixed by pinning `tagSyntax` — a `[#ftl]` header overrides that setting — which is
 *    why [scanSource] rejects square-bracket syntax outright as well.
 *
 * The AST move then opened one of its own, closed here: an `<#ftl …>` header **produces no AST
 * node**, so it is invisible to a tree walk by construction. §4.2/§6.2 (v1.6) disallow the header
 * outright and [scanSource] refuses it before the parse — see [LEADING_FTL_HEADER] for why the
 * ordering is the whole point.
 *
 * [scanAst] walks the tree the parser built ([FreemarkerAst]), so parser and scanner agree by
 * construction: a string literal is a string literal, a comment is a comment, and Freemarker's
 * canonical rendering escapes `<`/`>` inside literals so no literal can re-inject tag syntax.
 *
 * ## What the AST buys in precision, and where over-rejection remains
 *
 * Two whole classes of false positive disappear, because the parser tells them apart from real
 * constructs: literal template text (`SELECT '… ?eval …'` is a `TextBlock`, never executed, so
 * it is not scanned) and anything inside a comment. What remains deliberately over-rejecting is
 * a **string literal inside an expression** — `<#assign a = "x?eval">` is flagged, because the
 * element that owns the literal prints it. That is the safe direction for an SSTI guard: a rare
 * false positive an author resolves by rephrasing, versus an evasion that ships.
 *
 * ## Case and Unicode evasions are the parser's business now
 *
 * `?EVAL` and `?ｅｖａｌ` are **parse errors** in Freemarker 2.3.34 (built-in names are
 * case-sensitive and ASCII), so such a body is rejected by `template.validation.syntax_error`
 * before this scan is reached — verified against the pinned jar. The scan no longer needs to
 * anticipate a spelling the parser will not accept; the NFKC normalization in
 * [FreemarkerAst.ownText] and the `IGNORE_CASE` below remain as the belt behind that brace.
 */
@Suppress("DEPRECATION") // freemarker.core.TemplateElement — see FreemarkerAst
internal object ForbiddenConstructScanner {
    /**
     * `?eval`, `?eval_json` (and its camel-case alias `?evalJson`), `?interpret`, `?api`, `?new`
     * — matched as a complete built-in token so `?evaluated` is not caught by prefix alone.
     *
     * Both spellings of `?eval_json` are listed because Freemarker's canonical rendering
     * preserves the alias the author wrote rather than collapsing it to the snake-case key
     * (verified against 2.3.34: `${x?evalJson}` renders back as `${x?evalJson}`), and the
     * built-in's canonical key is not reachable through any public API. Longest alternatives
     * come first so `eval` cannot shadow `eval_json`.
     */
    private val FORBIDDEN_BUILTIN =
        Regex("""\?\s*(eval_json|evaljson|eval|interpret|api|new)(?![a-z0-9_])""", RegexOption.IGNORE_CASE)

    /**
     * The `freemarker.template.utility` package — home of `Execute`, `ObjectConstructor`,
     * `JythonRuntime`. Referenced only by fully-qualified name in an SSTI payload, so matching
     * the package prefix catches all three (and any gadget the package gains) without the false
     * positives a bare `Execute` word would produce against legitimate SQL (`GRANT EXECUTE`).
     */
    private val UTILITY_PACKAGE = Regex("""freemarker\s*\.\s*template\s*\.\s*utility""", RegexOption.IGNORE_CASE)

    /**
     * Square-bracket tag (`[#…`) or interpolation (`[=…`) syntax, rejected outright by §4.2.
     *
     * This is a **source** check, not an AST check, and it has to be: under the pinned
     * `tagSyntax` a stray `[#include "y"]` parses as inert text and leaves no node to find,
     * while a leading `[#ftl]` overrides the pin and makes every following `[#…]` a live
     * directive. Refusing the spelling entirely is the only rule that holds in both cases.
     */
    private val SQUARE_BRACKET_SYNTAX = Regex("""\[[#=]""")

    /**
     * A leading `<#ftl …>` header, disallowed outright by §4.2/§6.2 (v1.6).
     *
     * Two independent reasons, both verified against the pinned 2.3.34 jar:
     *  - **The AST scan is blind to it.** The parser *consumes* the header and emits no node, so
     *    `<#ftl attributes={"k":"1+1"?eval}>OK` parses to a bare `TextBlock` — a forbidden built-in
     *    with nowhere for [scanAst] to find it.
     *  - **It evaluates expressions at parse time, on the save thread.** `<#ftl attributes={…}>`
     *    is evaluated while parsing, so a 65-byte body burns CPU uninterruptibly *before* any
     *    render guard exists: measured 75 ms / 216 ms / **1634 ms** for
     *    `{"k":(1..N)?seq_index_of(-1)}` at N = 2e6 / 2e7 / 2e8 — linear in a literal the attacker
     *    picks, so unbounded. `max-body-chars` cannot bound it; the body is tiny.
     *
     * This is why the refusal lives in [scanSource] and not on the AST: it has to happen **before
     * the parse**, because the parse *is* the attack. Matching `^\s*<#ftl` is exact rather than
     * over-broad — the header is only legal as the leading token, and a `<#ftl` anywhere else is
     * already a parse error (`syntax_error`).
     */
    private val LEADING_FTL_HEADER = Regex("""^\s*<#ftl\b""", RegexOption.IGNORE_CASE)

    /** One matched forbidden construct — [construct] names it, [snippet] is the safe evidence. */
    data class Finding(
        val construct: String,
        val snippet: String,
    )

    /**
     * Source-level checks that must run **before** the parse.
     *
     * Neither of these can be an AST check, and for the same underlying reason in both cases:
     * the construct leaves no node to find. Square-bracket syntax parses to inert text (or, after
     * a `[#ftl]`, to a live directive the pinned `tagSyntax` does not prevent), and the `<#ftl`
     * header is consumed by the parser entirely. The `<#ftl` refusal additionally has to run
     * before the parse because parsing the header is itself the denial-of-service — see
     * [LEADING_FTL_HEADER].
     */
    fun scanSource(body: String): List<Finding> =
        listOfNotNull(
            LEADING_FTL_HEADER.find(body)?.let { Finding(FTL_HEADER_CONSTRUCT, it.value.trim()) },
            SQUARE_BRACKET_SYNTAX.find(body)?.let { Finding(SQUARE_BRACKET_CONSTRUCT, it.value) },
        )

    /** Every forbidden construct in the parsed tree rooted at [root]; empty when it is clean. */
    fun scanAst(root: TemplateElement?): List<Finding> {
        val findings = mutableListOf<Finding>()
        FreemarkerAst.visitExcludingComments(root) { element ->
            when (FreemarkerAst.typeOf(element)) {
                FreemarkerAst.INCLUDE -> findings += Finding("<#include>", FreemarkerAst.ownText(element).truncateForError())
                FreemarkerAst.LIBRARY_LOAD -> findings += Finding("<#import>", FreemarkerAst.ownText(element).truncateForError())
                else -> Unit
            }
            // Literal text is emitted verbatim into the SQL and is never re-parsed by Freemarker,
            // so it cannot carry a construct — scanning it would only reject an author who wrote
            // the words `?eval` inside a SQL comment or string.
            if (FreemarkerAst.typeOf(element) != FreemarkerAst.TEXT_BLOCK) {
                scanText(FreemarkerAst.ownText(element), findings)
            }
        }
        return findings
    }

    /**
     * Parses [body] and returns every forbidden construct in it — the entry point for callers
     * that do not already hold a parse. [TemplateValidator] uses [scanSource] + [scanAst]
     * directly instead, so a save parses the body exactly once.
     *
     * A body that does not parse yields only the source-level findings: there is no tree to
     * walk, and such a body is rejected by `template.validation.syntax_error` regardless.
     */
    fun scan(body: String): List<Finding> =
        scanSource(body) +
            when (val parse = TemplateBodyParser.parse(body)) {
                is BodyParse.Parsed -> scanAst(parse.template.rootTreeNode)
                is BodyParse.SyntaxError -> emptyList()
            }

    private fun scanText(
        text: String,
        findings: MutableList<Finding>,
    ) {
        FORBIDDEN_BUILTIN.findAll(text).forEach {
            findings += Finding("?${it.groupValues[1].lowercase().replace("evaljson", "eval_json")}", it.value.truncateForError())
        }
        UTILITY_PACKAGE.findAll(text).forEach {
            findings += Finding("freemarker.template.utility", it.value.truncateForError())
        }
    }

    /** The construct name reported for a square-bracket tag or interpolation. */
    const val SQUARE_BRACKET_CONSTRUCT = "[# or [= (square-bracket syntax)"

    /** The construct name reported for a leading `<#ftl …>` header. */
    const val FTL_HEADER_CONSTRUCT = "<#ftl> header"
}
