package co.datapipelines.web.ui

import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.RequiredScope
import co.datapipelines.auth.ScopeMatrix
import co.datapipelines.auth.WorkspaceSessionRequiredException
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.web.pipelines.PromotionService
import co.datapipelines.web.pipelines.PromotionTargetClient
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam

/**
 * The promotion screen (ui-screens.md §4.17, versioning §10.1 D8).
 *
 * Promotion is **triggered by a human, from the UI, and only from there**: §10.1 forbids an
 * MCP tool and forbids a schedule, and this round's fence excludes `modules/mcp-server` so
 * that is mechanical rather than remembered. This controller and the REST pair it calls are
 * the whole surface.
 *
 * The screen renders exactly [PromotionService.plan] — §10.2's set and nothing else, because
 * the listing rule lives in that function rather than in this template. The Promote action
 * re-runs every §10.3 guard against a fresh inventory server-side, so what the screen showed
 * is a convenience, never the authority.
 *
 * Session-only, for the same reason the workspace mutations are: this is a browser form post
 * that acts on a whole environment, and the programmatic surface is the REST pair with its own
 * credential. An API-key principal driving it would be a second, unintended path into a
 * deployment-to-deployment channel.
 */
@Controller
class PromotionUiController(
    private val promotionService: PromotionService,
    private val client: PromotionTargetClient,
    private val themeResolver: ThemeResolver,
) {
    private val log = LoggerFactory.getLogger(PromotionUiController::class.java)

    /**
     * §10.2's listing. Three states the screen must tell apart, because they need different
     * actions from the operator:
     *
     * - **no target configured** — this deployment promotes nowhere; a config change, not a
     *   promotion;
     * - **target reachable, nothing to promote** — everything released here is already there;
     * - **target unreachable / refusing** — the error is rendered in place with its code,
     *   rather than a generic error page, because the operator's next step depends on WHICH
     *   refusal it was.
     */
    @GetMapping("/promotion")
    @RequiredScope(ScopeMatrix.RestOperation.READ_RESOURCES)
    fun screen(
        model: Model,
        request: HttpServletRequest,
    ): String {
        val principal = requirePrincipal()
        model.addAttribute("activeTheme", themeResolver.resolve(request))
        model.addAttribute("hasTarget", client.hasTarget)
        model.addAttribute("targetBaseUrl", client.targetBaseUrl)
        if (!client.hasTarget) return VIEW

        val workspace = principal.requireWorkspace()
        try {
            val plan = promotionService.plan(workspace.id, workspace.name)
            model.addAttribute("plan", plan)
        } catch (e: DatapipelinesException) {
            // The target's own refusal, or a transport failure. Both are states of the
            // SCREEN — an operator needs to see which, on the page they were on.
            log.info("event=ui.promotion.plan_failed code={} target={}", e.code, client.targetBaseUrl)
            model.addAttribute("planError", e.code)
            model.addAttribute("planErrorMessage", e.message)
        }
        return VIEW
    }

    /**
     * The Promote action. [names] is the selection the human ticked; every one of them is
     * re-guarded server-side (§10.3) and the dependency closure is recomputed from scratch.
     *
     * Outcomes bounce back to the screen as `?ok=` / `?error=` flashes — the layout's toast
     * contract (ui-screens §5.1) — so a refusal lands on the listing the operator can act on
     * rather than on a generic error page.
     */
    @PostMapping("/promotion/promote")
    @RequiredScope(ScopeMatrix.RestOperation.MUTATE_PIPELINES_TEMPLATES)
    fun promote(
        @RequestParam(name = "name", required = false) names: List<String>?,
    ): String {
        val selected = names?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        if (selected.isEmpty()) return "redirect:/promotion?error=nothing_selected"
        return try {
            val principal = requireSessionPrincipal()
            val workspace = principal.requireWorkspace()
            val applied = promotionService.promote(workspace.id, workspace.name, selected)
            "redirect:/promotion?ok=promoted&pipelines=${applied.pipelines}&templates=${applied.templates}"
        } catch (e: DatapipelinesException) {
            // The code's LAST segment is the flash key the template renders — the same idiom
            // the workspaces screen uses. The full code and message are already logged and,
            // for a target refusal, were logged by the client with the target named.
            log.info("event=ui.promotion.refused code={}", e.code)
            "redirect:/promotion?error=${e.code.substringAfterLast('.')}"
        }
    }

    private fun requirePrincipal(): AuthenticatedPrincipal =
        SecurityContextHolder.getContext().authentication?.principal as? AuthenticatedPrincipal
            ?: error("No authenticated principal")

    /**
     * Session-only (see the class KDoc). An API-key principal gets the catalogued
     * `workspace.session_required` refusal, which the action's own catch turns into the
     * screen's `session_required` flash.
     */
    private fun requireSessionPrincipal(): AuthenticatedPrincipal =
        requirePrincipal().also {
            if (it.authMethod != AuthMethod.OIDC) throw WorkspaceSessionRequiredException()
        }

    private companion object {
        const val VIEW = "promotion/index"
    }
}
