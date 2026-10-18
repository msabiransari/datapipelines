package co.datapipelines.web.ui

import co.datapipelines.application.lens.LensedView
import co.datapipelines.parameters.ParameterSetRepository
import co.datapipelines.pipeline.NavigationFamily
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.RecentRow
import co.datapipelines.templates.TemplateRepository
import co.datapipelines.visualization.DashboardRepository
import co.datapipelines.visualization.VisualizationRepository
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
 * renders no Continue your work section for them. Every family has one since #579–#582: pipelines
 * (#556), templates, dashboards, visualizations and parameter sets (`/parameter-sets/<id>`); templates
 * link by NAME — their workspace route is `/templates/{*name}`.
 */
class FamilyRecent(
    private val pipelines: PipelineRepository,
    private val templates: TemplateRepository,
    private val dashboards: DashboardRepository,
    private val visualizations: VisualizationRepository,
    private val parameterSets: ParameterSetRepository,
) {
    private val log = LoggerFactory.getLogger(FamilyRecent::class.java)

    /** True when [family] has an adapter, so its overview renders the section. */
    fun supports(family: NavigationFamily): Boolean = family in ADAPTED

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
            val entries =
                when (family) {
                    NavigationFamily.PIPELINES -> {
                        pipelines.recent(workspaceId, view.pipelines).map { it.entry("/pipelines/${it.id}") }
                    }

                    NavigationFamily.TEMPLATES -> {
                        templates.recent(workspaceId, view.templates).map { it.entry(templateHref(it.name)) }
                    }

                    NavigationFamily.DASHBOARDS -> {
                        dashboards.recent(workspaceId, view.dashboards).map { it.entry("/dashboards/${it.id}") }
                    }

                    NavigationFamily.VISUALIZATIONS -> {
                        visualizations.recent(workspaceId, view.visualizations).map { it.entry("/visualizations/${it.id}") }
                    }

                    NavigationFamily.PARAMETER_SETS -> {
                        parameterSets.recent(workspaceId, view.parameterSets).map { it.entry("/parameter-sets/${it.id}") }
                    }
                }
            RecentResult.Available(entries)
        } catch (failure: Exception) {
            log.warn("recent list unavailable for workspace {} family {}: {}", workspaceId, family, failure.toString())
            RecentResult.Unavailable("Recently updated could not be read.")
        }
    }

    /** Templates link by NAME — the tree's form, every path segment URL-encoded (TreeProjection). */
    private fun templateHref(name: String): String =
        "/templates/" +
            name
                .split('/')
                .joinToString("/") { segment ->
                    java.net.URLEncoder
                        .encode(segment, java.nio.charset.StandardCharsets.UTF_8)
                        .replace("+", "%20")
                }

    private fun RecentRow.entry(href: String) = RecentEntry(name, href, modifiedAt, hasDraft)

    private companion object {
        /** The families with an adapter — one branch of [forFamily] each. */
        val ADAPTED =
            setOf(
                NavigationFamily.PIPELINES,
                NavigationFamily.TEMPLATES,
                NavigationFamily.DASHBOARDS,
                NavigationFamily.VISUALIZATIONS,
                NavigationFamily.PARAMETER_SETS,
            )
    }
}
