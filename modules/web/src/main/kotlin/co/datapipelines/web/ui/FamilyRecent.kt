package co.datapipelines.web.ui

import co.datapipelines.application.lens.LensedView
import co.datapipelines.pipeline.NavigationFamily
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.RecentRow
import co.datapipelines.templates.TemplateRepository
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
 * renders no Continue your work section for them. Pipelines and templates have one (#556, #579);
 * the dashboards, visualizations and parameter-set adapters belong to #580–#582 (templates link
 * by NAME — their workspace route is `/templates/{*name}`).
 */
class FamilyRecent(
    private val pipelines: PipelineRepository,
    private val templates: TemplateRepository,
) {
    private val log = LoggerFactory.getLogger(FamilyRecent::class.java)

    /** True when [family] has an adapter, so its overview renders the section. */
    fun supports(family: NavigationFamily): Boolean = family == NavigationFamily.PIPELINES || family == NavigationFamily.TEMPLATES

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
            RecentResult.Available(
                when (family) {
                    NavigationFamily.PIPELINES -> {
                        pipelines.recent(workspaceId, view.pipelines).map { it.entry("/pipelines/${it.id}") }
                    }

                    NavigationFamily.TEMPLATES -> {
                        templates.recent(workspaceId, view.templates).map { it.entry(templateHref(it.name)) }
                    }

                    else -> {
                        emptyList()
                    }
                },
            )
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
}
