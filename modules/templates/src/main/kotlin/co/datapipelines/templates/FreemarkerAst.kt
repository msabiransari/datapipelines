package co.datapipelines.templates

import freemarker.core.TemplateElement
import freemarker.core._CoreAPI
import java.text.Normalizer

/**
 * The one place this module touches Freemarker's parse tree — templates.md §4.2's "the scan
 * operates on the PARSED template, not on regex-stripped source".
 *
 * ## Why an AST walk replaced the regex scanner
 *
 * A scanner that strips comments with a regex and then matches text is bypassable, and was
 * bypassed: `<#assign a="<#--">${"x"?eval}<#assign b="-->">` makes the stripper delete an
 * `?eval` that Freemarker happily executes, and a leading `[#ftl]` switches the parser to
 * square-bracket syntax an angle-bracket regex never sees. Walking the tree the parser actually
 * built removes the whole class of evasion: whatever the parser decided a token is, is what the
 * scan sees.
 *
 * ## The API used, and why (verified against the pinned org.freemarker:freemarker 2.3.34 jar)
 *
 * Freemarker 2.3.x publishes no supported AST-visitor API — `TemplateObject.getParameterCount()`
 * / `getParameterValue(int)` (the expression-level accessors) are **package-private**, so an
 * expression subtree cannot be walked from outside `freemarker.core`. What *is* public is:
 *  - [freemarker.template.Template.getRootTreeNode] → [TemplateElement];
 *  - [TemplateElement.getChildCount] and [_CoreAPI.getChildElement] — the typed child accessor
 *    (`getChildAt` returns a `javax.swing.tree.TreeNode`, which would need a cast);
 *  - [TemplateElement.getDescription] — the element's **own** rendering, children excluded, with
 *    every expression it carries spelled out (`#if x?eval`, `${"1+1"?eval}`, `#include "y"`).
 *
 * [ownText] is therefore where an expression-level construct is detected: the element that owns
 * the expression prints it. This is exact for the property that matters, because Freemarker's
 * canonical rendering **escapes** a string literal's angle brackets (`"<#--"` prints as
 * `"\l#--"`, `"-->"` as `"--\g"`), so no string literal can re-inject tag syntax into the
 * scanned text — the bypass is structurally impossible, not merely patched.
 *
 * Node identity is by fully-qualified class name because Freemarker declares several of these
 * classes package-private (`Include`, `Comment`, `TextBlock`, `MixedContent` are not `public`),
 * so `is Include` will not compile. `FreemarkerAstDriftTest` pins each name against the pinned
 * jar by parsing a body and asserting the node it produces, so a jar bump that renames a node
 * fails the build instead of silently disarming the scan.
 *
 * ## The deprecation, stated plainly
 *
 * [TemplateElement] is annotated `@Deprecated` — Freemarker's own words: *"internal FreeMarker
 * API with no backward compatibility guarantees, so you shouldn't depend on it"*. This module
 * depends on it anyway, deliberately, because templates.md §4.2 makes an AST-based scan
 * **normative** and 2.3.x publishes no supported alternative: the expression-level accessors are
 * package-private and `Environment.getCurrentDirectiveCallPlace()` only exists during a render,
 * which save-time validation must not perform (§7.1). A regex over source was the supported
 * alternative, and it was bypassable end to end.
 *
 * The dependency is made safe the only way an internal API can be: the artifact version is a
 * literal ([FreemarkerConfigFactory.PINNED_VERSION]) asserted against the classpath, and
 * `FreemarkerAstDriftTest` re-derives every node name and the `getDescription()` contract from
 * the jar on each build. A jar bump therefore turns into a red build, never into a silently
 * disarmed security control. Every `@Suppress("DEPRECATION")` in this module — here, in
 * [ForbiddenConstructScanner], [LibraryBodyCheck] and [TemplateValidator] — is this one
 * decision, and points at this paragraph for its justification.
 */
@Suppress("DEPRECATION")
internal object FreemarkerAst {
    /** `<#-- … -->` — inert, and deliberately the one node the scan skips. */
    const val COMMENT = "freemarker.core.Comment"

    /** `<#include …>` — templates.md §4.2 forbids it in a body. */
    const val INCLUDE = "freemarker.core.Include"

    /** `<#import … as …>` — templates.md §4.2 forbids it in a body (§6.3 synthesizes it instead). */
    const val LIBRARY_LOAD = "freemarker.core.LibraryLoad"

    /** The synthetic root Freemarker creates when a body has more than one top-level element. */
    const val MIXED_CONTENT = "freemarker.core.MixedContent"

    /** Literal template text. Its [TemplateElement.getCanonicalForm] is the text itself. */
    const val TEXT_BLOCK = "freemarker.core.TextBlock"

    /** `<#macro>` **and** `<#function>` — one node type; `Macro.isFunction()` separates them. */
    const val MACRO = "freemarker.core.Macro"

    /** [element]'s own class name — the node-identity check every caller uses. */
    fun typeOf(element: TemplateElement): String = element.javaClass.name

    /** [element]'s direct children, in source order. */
    fun childrenOf(element: TemplateElement): List<TemplateElement> =
        (0 until element.childCount).map { _CoreAPI.getChildElement(element, it) }

    /**
     * The top-level elements of a parsed body: the root's children when the parser wrapped them
     * in a [MIXED_CONTENT] node, the root itself when a body has exactly one, and nothing at all
     * for an empty body.
     *
     * Note what is *absent*: a `<#ftl …>` header is consumed by the parser and produces no node,
     * so nothing here can see one. That blindness is exactly why templates.md §4.2/§6.2 (v1.6)
     * disallow the header at the **source** level ([ForbiddenConstructScanner.scanSource]) rather
     * than leaving it to any AST check.
     */
    fun topLevelOf(root: TemplateElement?): List<TemplateElement> =
        when {
            root == null -> emptyList()
            typeOf(root) == MIXED_CONTENT -> childrenOf(root)
            else -> listOf(root)
        }

    /**
     * [element]'s own text — its directive or interpolation with every expression spelled out,
     * **excluding** its children, NFKC-normalized so a Unicode compatibility form of a built-in
     * name (`?ｅｖａｌ`) collapses to the ASCII spelling before any matching happens.
     */
    fun ownText(element: TemplateElement): String = Normalizer.normalize(element.description, Normalizer.Form.NFKC)

    /**
     * Visits every element of the tree rooted at [root] except comment subtrees.
     *
     * A construct inside `<#-- … -->` never executes, so skipping [COMMENT] nodes keeps a
     * commented-out example from being reported — the property the old comment-stripping regex
     * was reaching for, obtained here without a stripper that can be lied to.
     */
    fun visitExcludingComments(
        root: TemplateElement?,
        visit: (TemplateElement) -> Unit,
    ) {
        if (root == null || typeOf(root) == COMMENT) return
        visit(root)
        childrenOf(root).forEach { visitExcludingComments(it, visit) }
    }
}
