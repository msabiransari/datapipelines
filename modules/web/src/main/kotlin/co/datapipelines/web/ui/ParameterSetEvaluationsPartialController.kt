package co.datapipelines.web.ui

import co.datapipelines.application.lens.PromoterLens
import co.datapipelines.auth.Permission
import co.datapipelines.auth.RequiredScope
import co.datapipelines.web.api.currentPrincipal
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import java.util.UUID

/**
 * The History tab's two fragments (#376; the workspace spec §6.4) — the house table paged by `offset`, and one record's
 * detail. Both are `parameter_set.read` (the owner's §11.2 ruling: no new permission; whoever may read the set may read
 * what ran against it), both render the caller's lens through [ParameterSetEvaluationsBrowseModel], which decides
 * everything: a set the lens hides is the workspace page's own 404. No REST or MCP twin (§11.6).
 *
 * The routes live under `/partials/`, where every htmx fragment of this app lives — the spec's §5 row spells the history
 * route `GET /parameter-sets/{id}/evaluations`; the handback records the deviation.
 */
@Controller
class ParameterSetEvaluationsPartialController(
    private val history: ParameterSetEvaluationsBrowseModel,
    /** 178 — the promoter lens: every fragment here renders the caller's view. */
    private val lens: PromoterLens,
) {
    @GetMapping("/partials/parameter-sets/{id}/evaluations")
    @RequiredScope(Permission.PARAMETER_SET_READ)
    fun list(
        @PathVariable id: UUID,
        @RequestParam(required = false) offset: Int?,
        model: Model,
    ): String {
        val principal = currentPrincipal()
        return history.fillHistory(model, principal.requireWorkspace().id, lens.viewFor(principal), id, offset ?: 0)
    }

    @GetMapping("/partials/parameter-sets/{id}/evaluations/{evaluationId}")
    @RequiredScope(Permission.PARAMETER_SET_READ)
    fun detail(
        @PathVariable id: UUID,
        @PathVariable evaluationId: UUID,
        model: Model,
    ): String {
        val principal = currentPrincipal()
        return history.fillDetail(model, principal.requireWorkspace().id, lens.viewFor(principal), id, evaluationId)
    }
}
