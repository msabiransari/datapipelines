package co.datapipelines.templates

import freemarker.core.TemplateElement

/**
 * Whether ONE render of a template body shows every table position the body can ever produce (reference-scanner
 * slice D-A, #778). The scanner renders a body once — with the caller's values or the §12.6 samples — and parses
 * that one text; this classifier says whether the one text is the whole story.
 *
 * - [EXACT] — the body's output does not depend on any value: literal text, comments, and the directives that print
 *   nothing (an `<#assign>`, a `<#macro>`/`<#function>` definition that is never called), and the template imports
 *   no library.
 * - [PARTIAL] — anything else: an interpolation (`${…}` — template-local values are legal and can build a name), an
 *   `<#if>` or `<#switch>` (the render took one branch), a `<#list>`, an `<#include>`, a macro call, an imported
 *   library, or any other directive. The conservative default (the slice's narrowing rule — "the directive's output
 *   region contains a table-position keyword" — is not taken in this lane, so a `<#if>` around a WHERE clause is
 *   PARTIAL too, a false positive the slice accepts).
 */
enum class TemplateCompleteness {
    EXACT,
    PARTIAL,
}

/** One reason a body is [TemplateCompleteness.PARTIAL]: the construct's [kind] word and its bounded source spelling. */
data class CompletenessFinding(
    val kind: String,
    val detail: String,
)

/** A body's verdict and every construct that made it PARTIAL, in source order. */
data class TemplateClassification(
    val completeness: TemplateCompleteness,
    val findings: List<CompletenessFinding>,
)

/** The FreeMarker AST walk behind [TemplateCompleteness] — the module's one parse-tree seam, [FreemarkerAst]. */
@Suppress("DEPRECATION") // FreemarkerAst's KDoc: the AST walk is normative and 2.3.x publishes no supported alternative
object TemplateCompletenessClassifier {
    /** The kind words a finding carries (stored in the scan's `reasons`; pinned by the classifier's tests). */
    object Kind {
        const val INTERPOLATION = "interpolation"
        const val IF = "if"
        const val SWITCH = "switch"
        const val LIST = "list"
        const val INCLUDE = "include"
        const val MACRO_CALL = "macro_call"
        const val IMPORT = "import"
        const val SYNTAX_ERROR = "syntax_error"
        const val DIRECTIVE = "directive"
    }

    /**
     * Classifies [body]. [importsLibraries] is true when the template version declares any `imports` (its library
     * macros can build text the body's own tree does not show).
     */
    fun classify(
        body: String,
        importsLibraries: Boolean,
    ): TemplateClassification {
        val findings = ArrayList<CompletenessFinding>()
        if (importsLibraries) findings += CompletenessFinding(Kind.IMPORT, "the template imports a library")
        when (val parsed = TemplateBodyParser.parse(body)) {
            is BodyParse.Parsed -> walk(parsed.template.rootTreeNode, findings)
            is BodyParse.SyntaxError -> findings += CompletenessFinding(Kind.SYNTAX_ERROR, "line ${parsed.line}, column ${parsed.column}")
        }
        val completeness = if (findings.isEmpty()) TemplateCompleteness.EXACT else TemplateCompleteness.PARTIAL
        return TemplateClassification(completeness, findings)
    }

    private fun walk(
        element: TemplateElement?,
        findings: MutableList<CompletenessFinding>,
    ) {
        if (element == null) return
        val type = FreemarkerAst.typeOf(element)
        // A comment never executes; a macro definition prints nothing — a CALL of it is a [Kind.MACRO_CALL] where it happens.
        if (type == FreemarkerAst.COMMENT || type == FreemarkerAst.MACRO) return
        if (type !in SILENT) findings += CompletenessFinding(kindOf(type), FreemarkerAst.ownText(element).truncateForError())
        FreemarkerAst.childrenOf(element).forEach { walk(it, findings) }
    }

    private fun kindOf(type: String): String =
        when (type) {
            FreemarkerAst.DOLLAR_VARIABLE -> Kind.INTERPOLATION
            FreemarkerAst.IF_BLOCK, FreemarkerAst.CONDITIONAL_BLOCK -> Kind.IF
            SWITCH_BLOCK, CASE -> Kind.SWITCH
            FreemarkerAst.ITERATOR_BLOCK, ITEMS, SEP -> Kind.LIST
            FreemarkerAst.INCLUDE -> Kind.INCLUDE
            FreemarkerAst.UNIFIED_CALL -> Kind.MACRO_CALL
            FreemarkerAst.LIBRARY_LOAD -> Kind.IMPORT
            else -> "${Kind.DIRECTIVE}:${type.substringAfterLast('.')}"
        }

    /** `<#switch>` and its `<#case>`/`<#default>` children (pinned by the classifier's tests, as [FreemarkerAst]'s are). */
    private const val SWITCH_BLOCK = "freemarker.core.SwitchBlock"
    private const val CASE = "freemarker.core.Case"

    /** `<#items>` / `<#sep>` inside a `<#list>`. */
    private const val ITEMS = "freemarker.core.Items"
    private const val SEP = "freemarker.core.Sep"

    /** `<#assign>`/`<#local>`/`<#global>` with a `scope`/`namespace` attribute — the container of [FreemarkerAst.ASSIGNMENT]s. */
    private const val ASSIGNMENT_INSTRUCTION = "freemarker.core.AssignmentInstruction"

    /** The nodes that print nothing a reference could depend on. */
    private val SILENT =
        setOf(
            FreemarkerAst.MIXED_CONTENT,
            FreemarkerAst.TEXT_BLOCK,
            FreemarkerAst.ASSIGNMENT,
            ASSIGNMENT_INSTRUCTION,
        )
}
