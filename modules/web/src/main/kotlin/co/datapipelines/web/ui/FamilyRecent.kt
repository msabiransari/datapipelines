package co.datapipelines.web.ui

import co.datapipelines.application.lens.LensedView
import co.datapipelines.pipeline.NavigationFamily
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.RecentRow
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID

/** One Recently updated entry: what the overview names, where it links, and its modification facts. */
data class RecentEntry(
    val name: String,
    val href: String,
    val modifiedAt: Instant,
    val hasDraft: Boolean,
)

/** A failed read is distinct from a family with nothing recent — [InventoryResult]'s shape. */
sealed interface RecentResult {
    data class Available(
        val entries: List<RecentEntry>,
    ) : RecentResult

    data class Unavailable(
        val reason: String,
    ) : RecentResult
}

/**
 * #556 — the overview's "Recently updated" list per family, the sibling of [FamilyInventory]: the
 * same [LensedView] entry, the same fail-closed and read-failure rules, one statement per call
 * (`RecentSource`) and no cache.
 *
 * Only families with an adapter answer; [forFamily] returns null for the rest and the overview
 * renders no Continue your work section for them. Pipelines has one; the templates, dashboards,
 * visualizations and parameter-set adapters belong to #579–#582 (templates link by NAME — their
 * workspace route is `/templates/{*name}`).
 */
class FamilyRecent(
    private val pipelines: PipelineRepository,
) {
    private val log = LoggerFactory.getLogger(FamilyRecent::class.java)

    /** True when [family] has an adapter, so its overview renders the section. */
    fun supports(family: NavigationFamily): Boolean = family == NavigationFamily.PIPELINES

    /** Null for a family with no adapter; otherwise the newest admitted objects or why they are unknown. */
    @Suppress("TooGenericExceptionCaught") // the read boundary FamilyInventory draws, lazy lens derivation included
    fun forFamily(
        family: NavigationFamily,
        workspaceId: UUID,
        view: LensedView,
    ): RecentResult? {
        if (!supports(family)) return null
        view.unavailable?.let { return RecentResult.Unavailable(it.reason) }
        return try {
            RecentResult.Available(pipelines.recent(workspaceId, view.pipelines).map { it.entry("/pipelines/${it.id}") })
        } catch (failure: Exception) {
            log.warn("recent list unavailable for workspace {} family {}: {}", workspaceId, family, failure.toString())
            RecentResult.Unavailable("Recently updated could not be read.")
        }
    }

    private fun RecentRow.entry(href: String) = RecentEntry(name, href, modifiedAt, hasDraft)
}
