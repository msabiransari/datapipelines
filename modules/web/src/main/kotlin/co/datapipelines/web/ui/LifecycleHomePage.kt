package co.datapipelines.web.ui

import co.datapipelines.pipeline.Inventory
import co.datapipelines.pipeline.LifecycleBucket
import java.time.Instant
import java.util.Locale

/**
 * #670 — the lifecycle home's builders, moved out of [OverviewPage] under the 300-line convention:
 * [OverviewPage.of], [OverviewPage.tasks], [OverviewPage.iconFor] and [OverviewPage.width] are
 * one-line delegates here, so the frame's face keeps its signatures.
 */
internal object LifecycleHomePage {
    /**
     * #598a — the page for a [OverviewStacked.ChartSet.LIFECYCLE] [family] from one chart read
     * (the lifecycle and maturity charts, one snapshot) and one recent read, at [now]. An
     * unknown chart group is the frame's unavailable sentence, never a zero.
     */
    fun of(
        family: OverviewPage.Family,
        lensed: Boolean,
        charts: ChartsResult,
        recent: RecentResult?,
        now: Instant,
    ): OverviewPage {
        require(family.charts == OverviewStacked.ChartSet.LIFECYCLE) { "$family carries the four counts, not the charts" }
        val available = (charts as? ChartsResult.Available)?.charts
        return page(
            family,
            lensed,
            available?.let { OverviewStacked.panel(family, it, lensed) },
            (charts as? ChartsResult.Unavailable)?.reason,
            recent?.let {
                recentPanel(family, it, now).copy(
                    review = null,
                    tasks = tasks(family, available?.lifecycle),
                    description = "A clear next step for your ${family.plural}",
                )
            },
        )
    }

    /**
     * #638 — Continue your work's two rows for a lifecycle family: the drafts awaiting
     * their first release ([inventory]'s In development count worded; no number when the
     * inventory is unknown) and the live objects to run. A family whose catalog offers no such
     * filter gets no row for it.
     */
    fun tasks(
        family: OverviewPage.Family,
        inventory: Inventory?,
    ): List<OverviewPage.TaskRow> =
        listOfNotNull(
            family.filtered(LifecycleBucket.DRAFT_ONLY)?.let { href ->
                OverviewPage.TaskRow("drafts", "file", "Review your drafts", OverviewWords.drafts(family, inventory?.draftOnly), href, "draft")
            },
            family.filtered(LifecycleBucket.RELEASED_ONLY)?.let { href ->
                OverviewPage.TaskRow(
                    "live",
                    "play",
                    "Explore live ${family.plural}",
                    "Run a released ${family.singular} with your own inputs.",
                    href,
                    "released",
                )
            },
        )

    /** The heading eyebrow's icon for a family route: the [Family]'s own, else the sibling homes' rail icons. */
    fun iconFor(family: String): String =
        OverviewPage.Family.entries.firstOrNull { it.navigation.route == family }?.icon ?: SIBLING_ICONS[family] ?: DEFAULT_ICON

    private fun page(
        family: OverviewPage.Family,
        lensed: Boolean,
        inventory: OverviewPage.InventoryPanel?,
        unavailable: String?,
        recent: OverviewPage.RecentPanel?,
    ): OverviewPage =
        OverviewPage(
            family = family.navigation.route,
            title = family.title,
            catalogHref = family.catalogHref,
            lensed = lensed,
            inventory = inventory,
            unavailable = unavailable,
            recent = recent,
            guided = family.guided,
        )

    private fun recentPanel(
        family: OverviewPage.Family,
        recent: RecentResult,
        now: Instant,
    ): OverviewPage.RecentPanel {
        val review =
            family.filtered(LifecycleBucket.WITH_DRAFTS)?.let { href -> OverviewPage.Link(href, REVIEW_DRAFTS) }
        val section =
            OverviewPage.RecentSection(
                heading = RECENTLY_UPDATED,
                note = RECENT_NOTE,
                entries =
                    when (recent) {
                        is RecentResult.Available -> {
                            recent.entries.map {
                                OverviewPage.RecentItem(
                                    name = it.name,
                                    href = it.href,
                                    datetime = it.modifiedAt.toString(),
                                    ago = RelativeTime.since(it.modifiedAt, now),
                                    absolute = RelativeTime.absolute(it.modifiedAt),
                                    hasDraft = it.hasDraft,
                                )
                            }
                        }

                        is RecentResult.Unavailable -> {
                            null
                        }
                    },
                unavailable = (recent as? RecentResult.Unavailable)?.reason,
                emptyNote = RECENT_EMPTY,
            )
        return OverviewPage.RecentPanel(review, listOf(section))
    }

    /** [value] as a share of [tallest] in the chart's 0–100 viewBox; an all-zero chart is all zero. */
    internal fun width(
        value: Long,
        tallest: Long,
    ): String = if (tallest == 0L) "0" else String.format(Locale.ROOT, "%.1f", value * PERCENT / tallest)

    private const val PERCENT = 100.0
    private const val DEFAULT_ICON = "layout-grid"

    /** The three homes whose controllers build their page directly (rail icons, shell record §3). */
    private val SIBLING_ICONS = mapOf("datasources" to "database", "schedules" to "calendar-clock", "api-console" to "activity")

    /** The recent card's words, model-owned since #583 — the template renders what it is given. */
    const val REVIEW_DRAFTS = "Review drafts"
    const val RECENTLY_UPDATED = "Recently updated"
    const val RECENT_NOTE = "The latest changes in this workspace, newest first. Not a personal history."
    const val RECENT_EMPTY = "Nothing here yet. The starter prompt above is a good first step."
}
