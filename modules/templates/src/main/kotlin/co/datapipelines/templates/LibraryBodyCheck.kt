package co.datapipelines.templates

import freemarker.core.Macro
import freemarker.core.TemplateElement

/**
 * The `is_library` structural check (templates.md §6.2, §7 `is_library_without_macros`).
 *
 * A library exists to be imported: its body holds `<#macro>` / `<#function>` definitions and
 * **nothing that produces output of its own**. Text, `${...}` interpolation, or a top-level
 * directive outside a definition would be injected into every importer (templates.md §6.2), so
 * a library that carries any is rejected.
 *
 * The check is deliberately a source analysis, not a render: a library declares no parameters,
 * so there is no context to render it against (templates.md §7.1).
 *
 * ## On the parsed tree, for the same reason the scan is (§4.2)
 *
 * This check used to strip comments and definition blocks with regexes — the same
 * comment-stripping bug [ForbiddenConstructScanner] carried, plus a lazy `.*?` across the whole
 * body that made it quadratic on an adversarial input. Both are gone: it now reads the top-level
 * elements of the tree the parser built. Blank text between definitions is what Freemarker's own
 * whitespace handling leaves behind, never author-visible output.
 *
 * A `<#ftl …>` header never reaches this check: templates.md §6.2 (v1.6) disallows it for
 * libraries and templates alike, and [ForbiddenConstructScanner.scanSource] refuses it before the
 * body is parsed at all.
 */
@Suppress("DEPRECATION") // freemarker.core.TemplateElement — see FreemarkerAst
internal object LibraryBodyCheck {
    /** The outcome of the check — see [TemplateValidator] for how it maps to a §7 code. */
    enum class Result {
        /** At least one definition, nothing outside definitions — a well-formed library body. */
        OK,

        /** No `<#macro>` / `<#function>` definition at all. */
        NO_MACROS,

        /** Output or logic exists outside the definitions — it would leak into importers. */
        OUTPUT_OUTSIDE_MACROS,
    }

    /**
     * Checks the parsed library body rooted at [root].
     *
     * A `<#function>`-only library is accepted, matching templates.md §6.2 ("everything at the
     * top level is `<#macro>` / `<#function>` / comments") — §7's summary table says "at least
     * one `<#macro>`", which reads as the shorthand for a definition rather than a narrower
     * rule; Freemarker itself represents both with one node type ([Macro], distinguished by
     * `isFunction()`), so the two readings differ only in what an author is allowed to write.
     */
    fun validate(root: TemplateElement?): Result {
        val topLevel = FreemarkerAst.topLevelOf(root)
        if (topLevel.none { FreemarkerAst.typeOf(it) == FreemarkerAst.MACRO }) return Result.NO_MACROS
        return if (topLevel.all { isAllowedAtTopLevel(it) }) Result.OK else Result.OUTPUT_OUTSIDE_MACROS
    }

    private fun isAllowedAtTopLevel(element: TemplateElement): Boolean =
        when (FreemarkerAst.typeOf(element)) {
            // A definition produces no output where it stands; it is the point of a library.
            FreemarkerAst.MACRO -> true

            // Comments are inert.
            FreemarkerAst.COMMENT -> true

            // Whitespace left between definitions is not output an importer would ever see.
            FreemarkerAst.TEXT_BLOCK -> element.canonicalForm.isBlank()

            // Anything else at the top level runs on import: `<#assign>`, `${...}`, `<#if>`, a
            // macro *call*. Each would inject its effect into every importing template.
            else -> false
        }
}
