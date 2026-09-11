package co.datapipelines.web.ui

import co.datapipelines.application.semantics.SemanticsService
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.RequiredScope
import co.datapipelines.auth.ScopeMatrix
import co.datapipelines.datasources.Datasource
import co.datapipelines.datasources.DatasourceRegistry
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.servlet.ModelAndView

/**
 * The datasource LEARNED FACTS dialog (ui-screens.md §4.5b, round 118; the learned-semantic-layer
 * design §7.3) — round 1 is READ-ONLY: what agents recorded about this datasource, per object,
 * with trust badges. Recording, verifying and retiring from the UI are round 2.
 *
 * ## Where it lives
 * A DIALOG opened from the datasources list row (the grants dialog's home, 114 §C.2), because
 * `datasources/detail.html` is LAKE-ONLY and a section there would be unreachable for every
 * Postgres, MySQL and SQLite datasource — which is where most facts will be recorded. The lake
 * detail page ALSO renders the same table inline (the design's "lake detail" half), through the
 * same fragment, so the two never drift.
 *
 * ## Visibility
 * The §5.3 gate (`getVisible`): a datasource this workspace is not granted answers the same
 * refusal an unknown name does. The facts themselves come through [SemanticsService.list] —
 * the store's one visibility predicate (every DATASOURCE fact, this workspace's WORKSPACE
 * facts) and the D-S9 pipeline link, exactly as the MCP listing renders them.
 */
@Controller
class DatasourceFactsPartialController(
    private val datasources: DatasourceRegistry,
    private val semantics: SemanticsService,
) {
    /** The dialog: every live fact on the datasource this workspace may see, oldest first. */
    @GetMapping("/partials/datasources/{name}/facts")
    @RequiredScope(ScopeMatrix.RestOperation.READ_RESOURCES)
    fun dialog(
        model: Model,
        @PathVariable name: String,
    ): Any {
        val principal = requirePrincipal()
        val datasource = datasources.getVisible(name, principal.requireWorkspace().id) ?: return notFound(name)
        DatasourceFactsModel.fill(model, datasource, semantics.list(principal, datasource, SemanticsService.ListQuery()))
        return "partials/datasource-facts :: dialog"
    }

    /** The dialog's refusal shape, matching the datasource dialogs beside it (094 §B). */
    private fun notFound(name: String): ModelAndView =
        ModelAndView(
            "partials/inline-refusal",
            mapOf("message" to "Datasource '$name' does not exist on this instance."),
            org.springframework.http.HttpStatus.BAD_REQUEST,
        )

    private fun requirePrincipal(): AuthenticatedPrincipal =
        SecurityContextHolder.getContext().authentication?.principal as? AuthenticatedPrincipal
            ?: error("No authenticated principal")
}

/**
 * The facts table's model — ONE home for both renders (the list's dialog and the lake detail's
 * inline section), so the two screens show the same rows the same way.
 *
 * `facts` is [SemanticsService.list]'s wire rows as they are (the MCP listing's shape), plus a
 * derived `trustBadge` per row: the `ds-badge-*` class the trust maps to. Deriving it here rather
 * than in the template keeps the mapping testable and the template free of a six-way `th:class`.
 */
object DatasourceFactsModel {
    fun fill(
        model: Model,
        datasource: Datasource,
        facts: List<Map<String, Any?>>,
    ) {
        model.addAttribute("datasource", datasource)
        model.addAttribute(
            "facts",
            facts.map { fact ->
                fact +
                    mapOf(
                        "trustBadge" to trustBadge(fact["trust"] as? String),
                        "object" to objectOf(fact["refs"]),
                    )
            },
        )
    }

    /** Design §5's six states onto the design system's four badge tones. */
    fun trustBadge(trust: String?): String =
        when (trust) {
            "verified", "observed" -> "ds-badge-success"
            "needs_review" -> "ds-badge-warning"
            "stale" -> "ds-badge-danger"
            else -> "ds-badge-default"
        }

    /** The refs as one readable `schema.table.column` list — what the fact is about. */
    private fun objectOf(refs: Any?): String =
        (refs as? List<*>)
            .orEmpty()
            .mapNotNull { it as? Map<*, *> }
            .joinToString(", ") { ref ->
                listOfNotNull(ref["schema"] as? String, ref["table"] as? String, ref["column"] as? String).joinToString(".")
            }
}
