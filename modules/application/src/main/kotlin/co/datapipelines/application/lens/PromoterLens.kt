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
 */
data class LensedView(
    val pipelines: ReadLens,
    val templates: ReadLens,
    val unavailable: Unavailable? = null,
) {
    /** True when this view narrows anything — the surfaces that pay for a view ask this first. */
    val isLensed: Boolean get() = !pipelines.isEverything || !templates.isEverything

    /**
     * Why a lensed view is empty: the target's base URL (never its key) and the transport or
     * configuration reason, the same `details` the promotion page's error state already shows.
     */
    data class Unavailable(
        val target: String,
        val reason: String,
    )

    companion object {
        /** Every non-promoter's view: no narrowing, no target call, no cost. */
        val EVERYTHING = LensedView(ReadLens.Everything, ReadLens.Everything)
    }
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
