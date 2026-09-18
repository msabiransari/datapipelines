package co.datapipelines.web.ui

import co.datapipelines.auth.RequiredScope
import co.datapipelines.auth.ScopeMatrix
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam

/**
 * The shell search palette's fragment (#155, ui-screens.md §3.4) — the one route behind the
 * ⌘K control in `layouts/default.html`.
 *
 * It is a fragment under the /partials prefix with no page twin, like
 * `PipelineNodeSqlPartialController`: an SPA island the shell owns, which is why
 * [co.datapipelines.web.ui.BrowseModelConventionTest]'s page/partial pairing does not name
 * it. Everything it answers is a READ — the results are links the session's role already
 * governs at their destinations (a viewer's links open the same read-only pages the
 * explorer's rows do) — and the /partials prefix is default-deny, so the scope below is what
 * makes the palette exactly as wide as the screens it jumps to. It is not a new API surface:
 * the brief is explicit that a UI partial route is not an API contract.
 */
@Controller
class SearchController(
    private val search: SearchBrowseModel,
) {
    @GetMapping("/partials/search")
    @RequiredScope(ScopeMatrix.RestOperation.READ_RESOURCES)
    fun results(
        model: Model,
        @RequestParam(required = false) q: String?,
    ): String = search.fill(model, RoleModel.currentPrincipalOrNull(), q ?: "")
}
