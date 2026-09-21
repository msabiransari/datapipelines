package co.datapipelines.web.pipelines

import co.datapipelines.application.lens.LensedView
import co.datapipelines.application.lens.PromoterLens
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.ReadLens
import co.datapipelines.templates.TemplateRepository
import java.util.UUID

/**
 * The [PromoterLens] port's implementation — [PromotableView] built per request for a lensed
 * principal, and the "every other role sees everything, at no cost" short-circuit.
 *
 * ## The fail-closed branch (roles design §3.1)
 * A lensed principal whose target cannot be read — unreachable, refusing, malformed,
 * unconfigured — gets a view that admits NOTHING and says why ([LensedView.unavailable]).
 * Never an exception: the promoter asked to list, and a 502 for a listing would turn every
 * screen and every MCP read tool into an error page for as long as the target is down. The
 * page's one sentence and the WARN the client logs once per window are the operator's signal.
 *
 * ## Cost
 * A non-lensed principal: zero queries, zero target calls — [LensedView.EVERYTHING] is a
 * constant. A lensed principal: the two current-version reads (one join each) plus the
 * cached inventory, per request that asks. Promoters are ops people; the read surfaces they
 * reach are the listing screens, not the execution hot path.
 */
class PromotableViews(
    private val pipelines: PipelineRepository,
    private val templates: TemplateRepository,
    private val client: PromotionTargetClient,
) : PromoterLens {
    override fun viewFor(principal: AuthenticatedPrincipal): LensedView {
        if (!principal.isLensed) return LensedView.EVERYTHING
        // A lensed principal holds a workspace role, hence a workspace; the fallback is the
        // fail-closed answer rather than a 403 from a read that never named a workspace.
        val workspace = principal.workspace ?: return unavailable("no_workspace")
        return when (val computed = compute(workspace.id, workspace.name)) {
            is Computed.Ready -> LensedView(computed.view.pipelineLens, computed.view.templateLens)
            is Computed.Unavailable -> unavailable(computed.reason)
        }
    }

    /**
     * §10.2 for [workspaceId], against the cached inventory — the promotion page's plan and
     * the lens share this call, so the listing a promoter sees on `/promotion` and the set the
     * lens admits everywhere else are the same object.
     */
    fun compute(
        workspaceId: UUID,
        workspaceName: String,
    ): Computed =
        when (val cached = client.cachedInventory(workspaceName)) {
            is PromotionTargetClient.CachedInventory.Present -> Computed.Ready(compute(workspaceId, cached.inventory), cached.inventory)
            is PromotionTargetClient.CachedInventory.Unreachable -> Computed.Unavailable(cached.reason, cached.code)
        }

    /** The rule over a given inventory — the promote path passes the FRESH one it just read (§10.3). */
    fun compute(
        workspaceId: UUID,
        inventory: PromotionWire.Inventory,
    ): PromotableView = PromotableView.of(pipelines.findCurrentVersions(workspaceId), templates.findCurrentVersions(workspaceId), inventory)

    private fun unavailable(reason: String): LensedView =
        LensedView(ReadLens.NOTHING, ReadLens.NOTHING, LensedView.Unavailable(client.targetBaseUrl, reason))

    /** [compute]'s two outcomes: the view against the inventory it was computed from, or why there is none. */
    sealed interface Computed {
        data class Ready(
            val view: PromotableView,
            val inventory: PromotionWire.Inventory,
        ) : Computed

        data class Unavailable(
            val reason: String,
            val code: String,
        ) : Computed
    }
}
