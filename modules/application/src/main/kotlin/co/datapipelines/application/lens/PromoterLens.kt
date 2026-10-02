package co.datapipelines.application.lens

import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.pipeline.ReadLens

/**
 * What a principal may SEE of the active workspace's pipelines and templates — the promoter
 * lens (roles design §3.1, D5; 178) as one answer per request, for both kinds.
 *
 * [pipelines] and [templates] are the lenses every read surface passes to `PipelineService`
 * and `TemplateService`; for every role but the promoter both are [ReadLens.Everything] and
 * nothing was computed. [unavailable] is non-null exactly when a lensed principal's view is
 * empty BECAUSE the higher environment could not be read (fail closed) — the one sentence a
 * screen shows in place of its ordinary empty state. A lensed principal whose target is
 * reachable and simply has nothing newer gets the ordinary empty state: `unavailable` is
 * null and the lenses admit nothing.
 *
 * Not a data class since #330: the two dashboard arms can be derived LAZILY — [withLazyDashboardArms]
 * builds a view whose [visualizations]/[dashboards] run their derivation once, on the FIRST read of
 * either (one thunk, one computation shared by both arms). A view built the ordinary way holds both
 * lenses as the values it was given. [isLensed] asks the three eager arms FIRST and short-circuits,
 * so on a lazy-armed view whose pipeline, template or set arm narrows, the question "does this view
 * narrow anything?" never derives the dashboard arms; only a view whose eager arms are all
 * everything pays for the derivation (the review of #330's merge, F1).
 */
open class LensedView(
    val pipelines: ReadLens,
    val templates: ReadLens,
    val unavailable: Unavailable? = null,
    /**
     * #194 lane D — the parameter-set lens (`parameter_set.read`'s promoter cell). Appended
     * LAST and defaulted so every positional construction keeps compiling: an unnamed view is
     * everything, never a silent narrowing.
     */
    val parameterSets: ReadLens = ReadLens.Everything,
    /**
     * #10 L1b — the visualization lens (`visualization.read`'s promoter cell): the visualizations an admitted
     * dashboard pins. Appended after the set lens and defaulted, the same compatibility rule. The lazy-armed
     * subclass overrides the getter — its derivation runs once, on the first read of either dashboard arm.
     */
    open val visualizations: ReadLens = ReadLens.Everything,
    /**
     * #10 L1b — the dashboard lens (`dashboard.read`'s promoter cell): RELEASED dashboards newer than the promotion
     * target's whose EVERY source pipeline the [pipelines] lens admits. Same override rule as [visualizations].
     */
    open val dashboards: ReadLens = ReadLens.Everything,
) {
    /**
     * True when this view narrows anything — the surfaces that pay for a view ask this first. The eager
     * arms are asked first and the `||` short-circuits: the lazy dashboard arms are read only when all
     * three are everything (see the class KDoc).
     */
    val isLensed: Boolean
        get() =
            !pipelines.isEverything ||
                !templates.isEverything ||
                !parameterSets.isEverything ||
                !visualizations.isEverything ||
                !dashboards.isEverything

    /**
     * Why a lensed view is empty: the target's base URL (never its key) and the transport or configuration
     * reason, the same `details` the promotion page's error state already shows.
     */
    data class Unavailable(
        val target: String,
        val reason: String,
    )

    companion object {
        /** Every non-promoter's view: no narrowing, no target call, no cost. */
        val EVERYTHING = LensedView(ReadLens.Everything, ReadLens.Everything)

        /**
         * #330 — a view whose two dashboard arms derive on FIRST USE of either: a read that never asks
         * for visualizations or dashboards never runs their derivation (zero dashboard reads), and one
         * that does runs it ONCE for both arms. [arms] returns the visualization lens first, the
         * dashboard lens second. The fail-closed unavailable view is built eagerly and never lazy.
         */
        fun withLazyDashboardArms(
            pipelines: ReadLens,
            templates: ReadLens,
            parameterSets: ReadLens,
            arms: () -> Pair<ReadLens, ReadLens>,
        ): LensedView = LazyDashboardArms(pipelines, templates, parameterSets, arms)
    }
}

/** The lazy-armed [LensedView]: both dashboard arms answer from ONE derivation, computed at most once. */
private class LazyDashboardArms(
    pipelines: ReadLens,
    templates: ReadLens,
    parameterSets: ReadLens,
    val armsThunk: () -> Pair<ReadLens, ReadLens>,
) : LensedView(pipelines, templates, parameterSets = parameterSets) {
    private val arms: Pair<ReadLens, ReadLens> by lazy(armsThunk)

    override val visualizations: ReadLens get() = arms.first

    override val dashboards: ReadLens get() = arms.second
}

/**
 * The port every read surface resolves its [LensedView] through — REST and UI from the
 * security context's principal, an MCP tool from `McpToolContext.principal` (the SDK's
 * `boundedElastic` thread has no security context, which is why the principal is an argument
 * here and not read off a thread-local — the 134 lesson).
 *
 * Declared in `application` because the implementation is cross-aggregate (the promotion
 * client, both repositories) and lives in `web`, while `mcp-server` — which `web` depends on,
 * not the reverse — must call it. The contract: [viewFor] never throws for a read; an
 * unreachable or unconfigured target for a lensed principal is an EMPTY view with
 * [LensedView.unavailable] set, never a 502 handed to a promoter who only asked to list.
 */
fun interface PromoterLens {
    fun viewFor(principal: AuthenticatedPrincipal): LensedView
}
