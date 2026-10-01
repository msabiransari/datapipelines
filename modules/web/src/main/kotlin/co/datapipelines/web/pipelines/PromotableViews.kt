package co.datapipelines.web.pipelines

import co.datapipelines.application.dashboards.DashboardKeyAuthorizer
import co.datapipelines.application.dashboards.DashboardKeyBindingRepository
import co.datapipelines.application.lens.LensedView
import co.datapipelines.application.lens.PromoterLens
import co.datapipelines.auth.ApiKeyKind
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.KeyRole
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.ReadLens
import co.datapipelines.templates.TemplateRepository
import co.datapipelines.visualization.DashboardService
import co.datapipelines.visualization.VisualizationService
import java.util.UUID

/**
 * The [PromoterLens] port's implementation — [PromotableView] built per request for a lensed
 * principal, the "every other role sees everything, at no cost" short-circuit, and — L5 — the
 * `dashboard` key's arm: its BINDINGS are the lens.
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
 * constant. A lensed principal: the current-version reads (one join each) plus the cached
 * inventory, per request that asks. Promoters are ops people; the read surfaces they
 * reach are the listing screens, not the execution hot path. The dashboard derivation reads
 * each current released dashboard's BODY once per lensed request (#330, filed LOW): the pins
 * and the source names live inside the version body, and a pins-only projection would be a new
 * repository statement outside the L1c fence — left for a follow-up with the number that
 * justifies it.
 *
 * ## The dashboard key's lens (L5)
 * A `dashboard_viewer` principal is confined to the runtime routes by its KIND, and the runtime
 * consults `viewFor(principal).dashboards` exactly where it consults a promoter's; the key's
 * answer is an EXPANSION to names ([co.datapipelines.pipeline.ReadLens.Only]): the workspace's
 * dashboard names run through the R-EP2 walk ([DashboardKeyAuthorizer]) over the workspace's
 * `dashboard_key_bindings`, and the surviving names are the lens. An unbound key answers the
 * EMPTY set — the lens admitting nothing, fail closed — and the runtime's 404 follows. The
 * other arms stay [ReadLens.Everything] on purpose: the kind's confinement means no other read
 * can reach this principal, and a narrowed arm here would only lie about what it narrows.
 */
class PromotableViews(
    private val pipelines: PipelineRepository,
    private val templates: TemplateRepository,
    private val client: PromotionTargetClient,
    /** #194 lane D — the set arm of §10.2. Required: the lens's set arm is never silently empty (#300). */
    private val parameterSets: co.datapipelines.parameters.ParameterSetRepository,
    /** #10 L1b — the dashboards the dashboard and visualization lenses derive from. Required, the #300 rule. */
    private val dashboards: DashboardService,
    /** #10 L1c — the visualizations the lens's pins filter against (and the page's visualization rows). */
    private val visualizations: VisualizationService,
    /** L5 — the `dashboard` key's bindings; the walk's only read. */
    private val dashboardKeyBindings: DashboardKeyBindingRepository,
) : PromoterLens {
    override fun viewFor(principal: AuthenticatedPrincipal): LensedView {
        if (principal.keyKind == ApiKeyKind.DASHBOARD && principal.keyRole == KeyRole.DASHBOARD_VIEWER) {
            return dashboardKeyView(principal)
        }
        if (!principal.isLensed) return LensedView.EVERYTHING
        // A lensed principal holds a workspace role, hence a workspace; the fallback is the
        // fail-closed answer rather than a 403 from a read that never named a workspace.
        val workspace = principal.workspace ?: return unavailable("no_workspace")
        return when (val computed = compute(workspace.id, workspace.name)) {
            is Computed.Ready -> {
                LensedView(
                    computed.view.pipelineLens,
                    computed.view.templateLens,
                    parameterSets = computed.view.parameterSetLens,
                    visualizations = computed.view.visualizationsLens,
                    dashboards = computed.view.dashboardsLens,
                )
            }

            is Computed.Unavailable -> {
                unavailable(computed.reason)
            }
        }
    }

    /**
     * The dashboard key's view: the R-EP2 walk over the workspace's bindings and names. No
     * workspace resolved (a principal without its pin) is the fail-closed EMPTY lens — the
     * same answer an unbound key gets, never an error: the runtime turns a hidden dashboard
     * into the family's 404 (auth.md §7.7).
     */
    private fun dashboardKeyView(principal: AuthenticatedPrincipal): LensedView {
        val workspace = principal.workspace
        val keyId = principal.keyId
        val admitted =
            if (workspace == null || keyId == null) {
                emptySet()
            } else {
                val bindings = dashboardKeyBindings.findByWorkspace(workspace.id)
                val authorizer = DashboardKeyAuthorizer()
                val names = dashboards.currentVersions(workspace.id).map { it.name }
                names
                    .filter { name ->
                        authorizer.authorize(name, workspace.id, keyId, bindings) == DashboardKeyAuthorizer.Decision.Allowed
                    }.toSet()
            }
        return LensedView(ReadLens.Everything, ReadLens.Everything, dashboards = ReadLens.Only(admitted))
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
    ): PromotableView {
        val currentDashboards = dashboards.currentVersions(workspaceId)
        val bodiesByName =
            currentDashboards
                .mapNotNull { current ->
                    dashboards
                        .findVersion(workspaceId, ReadLens.Everything, current.id, current.version)
                        ?.let { current.name to it.body }
                }.toMap()
        return PromotableView.of(
            pipelines.findCurrentVersions(workspaceId),
            templates.findCurrentVersions(workspaceId),
            inventory,
            parameterSets.findCurrentVersions(workspaceId),
            visualizations.currentVersions(workspaceId),
            currentDashboards,
            bodiesByName,
        )
    }

    /** Fail closed: every lens — the dashboard and visualization arms included — admits NOTHING. */
    private fun unavailable(reason: String): LensedView =
        LensedView(
            ReadLens.NOTHING,
            ReadLens.NOTHING,
            LensedView.Unavailable(client.targetBaseUrl, reason),
            ReadLens.NOTHING,
            visualizations = ReadLens.NOTHING,
            dashboards = ReadLens.NOTHING,
        )

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
