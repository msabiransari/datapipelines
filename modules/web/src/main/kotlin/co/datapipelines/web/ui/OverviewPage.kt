package co.datapipelines.web.ui

import co.datapipelines.application.lens.LensedView
import co.datapipelines.pipeline.Inventory
import co.datapipelines.pipeline.LifecycleBucket
import co.datapipelines.pipeline.NavigationFamily
import co.datapipelines.pipeline.ReadLens
import java.time.Instant
import java.util.Locale

/**
 * #556 — what one family overview renders (`overview/family.html`, shell design §8), built in ONE
 * place from one [InventoryResult] and one [RecentResult], so the page's summary counts and its
 * charts are read from the same model the page's controller built and cannot disagree.
 *
 * #583 widened the frame without changing these five families' page: the inventory panel carries
 * [OverviewPage.Chart]s (these families build one, the lifecycle chart) and the recent card
 * carries [OverviewPage.RecentSection]s (these families build one, Recently updated), so a family
 * whose inventory or history reads differently — the datasources home — renders through the same
 * template and tests.
 *
 * [inventory] is null exactly when the inventory is unknown ([unavailable] then says why): the
 * template renders that sentence and no number, never a zero. [recent] is null for a family whose
 * Recently updated adapter does not exist yet ([FamilyRecent.supports]); [guided] marks the
 * families whose introduction and agent guidance are written (`overview/guide-<family>.html`).
 */
data class OverviewPage(
    val family: String,
    val title: String,
    val catalogHref: String,
    val lensed: Boolean,
    val inventory: InventoryPanel?,
    val unavailable: String?,
    val recent: RecentPanel?,
    val guided: Boolean,
) {
    /**
     * The summary counts and the charts, every number of a chart from the one model its page built.
     * #598a: [stacked] holds the stacked-bar charts (`OverviewStacked`) — a
     * [OverviewStacked.ChartSet.LIFECYCLE] family carries those alone; every other panel none.
     */
    data class InventoryPanel(
        val counts: List<Count>,
        val charts: List<Chart>,
        val stacked: List<StackedChart> = emptyList(),
    )

    /**
     * #583 — one labelled bar chart: [caption] names it everywhere words go (the list's
     * `aria-label`, the hidden table's `caption`), [note] the caveat sentence under the bars
     * when the chart needs one said, null when it does not, and [headings] the hidden table's
     * two column words (the lifecycle chart's own are the default).
     */
    data class Chart(
        val key: String,
        val caption: String,
        val bars: List<Bar>,
        val note: String? = null,
        val headings: List<String> = listOf("Lifecycle", "Objects"),
    )

    /** One summary count: its words, its number, the overlap it states, and its filtered catalog. */
    data class Count(
        val key: String,
        val label: String,
        val value: Long,
        val help: String,
        val href: String?,
    )

    /**
     * One chart bar: [width] is the SVG rect's width in a 0–100 viewBox (the tallest bar is 100,
     * a zero is 0 — never a visible minimum), [tone] the token family that paints it, [label]
     * the accessible sentence.
     */
    data class Bar(
        val key: String,
        val name: String,
        val value: Long,
        val width: String,
        val tone: String,
        val label: String,
        val href: String?,
    )

    /**
     * Continue your work: the [Link] beside the card's title (null hides it — a family whose
     * catalog offers no draft filter has nothing for it to open, #583) and the sections, each
     * its own heading, caveat and list.
     */
    data class RecentPanel(
        val review: Link?,
        val sections: List<RecentSection>,
    )

    /** A labelled link the template renders as-is; the label lives with the model that knows it. */
    data class Link(
        val href: String,
        val label: String,
    )

    /**
     * #583 — one titled block inside the Continue your work card: [heading] the `h3`; [note]
     * the sentence under it; [entries] the rows (null = the read failed and [unavailable] says
     * why); [emptyNote] what an empty list says instead of nothing.
     */
    data class RecentSection(
        val heading: String,
        val note: String?,
        val entries: List<RecentItem>?,
        val unavailable: String?,
        val emptyNote: String?,
    )

    /** One Recently updated row, its time both machine-readable and relative. */
    data class RecentItem(
        val name: String,
        val href: String,
        val datetime: String,
        val ago: String,
        val absolute: String,
        val hasDraft: Boolean,
    )

    /**
     * The family facts an overview names. Parameter sets' route is `parameter-sets`. [charts] is
     * the inventory card the family carries (#598a: Pipelines the stacked charts; the four
     * siblings the four counts and bars until #599).
     */
    enum class Family(
        val navigation: NavigationFamily,
        val title: String,
        val singular: String,
        val plural: String,
        val offered: List<LifecycleBucket>,
        val guided: Boolean,
        val charts: OverviewStacked.ChartSet = OverviewStacked.ChartSet.STANDARD,
    ) {
        PIPELINES(
            NavigationFamily.PIPELINES,
            "Pipelines",
            "pipeline",
            "pipelines",
            CatalogLifecycle.PIPELINES,
            guided = true,
            charts = OverviewStacked.ChartSet.LIFECYCLE,
        ),
        TEMPLATES(NavigationFamily.TEMPLATES, "Templates", "template", "templates", CatalogLifecycle.TEMPLATES, guided = true),
        DASHBOARDS(NavigationFamily.DASHBOARDS, "Dashboards", "dashboard", "dashboards", CatalogLifecycle.DASHBOARDS, guided = true),
        VISUALIZATIONS(
            NavigationFamily.VISUALIZATIONS,
            "Visualizations",
            "visualization",
            "visualizations",
            CatalogLifecycle.VISUALIZATIONS,
            guided = true,
        ),
        PARAMETER_SETS(
            NavigationFamily.PARAMETER_SETS,
            "Parameter Sets",
            "parameter set",
            "parameter sets",
            CatalogLifecycle.PARAMETER_SETS,
            guided = true,
        ),
        ;

        val catalogHref: String get() = "/${navigation.route}"

        /** This family's arm of the caller's view — the lens [FamilyInventory] reads it through. */
        fun lens(view: LensedView): ReadLens =
            when (this) {
                PIPELINES -> view.pipelines
                TEMPLATES -> view.templates
                DASHBOARDS -> view.dashboards
                VISUALIZATIONS -> view.visualizations
                PARAMETER_SETS -> view.parameterSets
            }

        /** The catalog filtered to [bucket], or null when this family's catalog does not offer it. */
        fun filtered(bucket: LifecycleBucket): String? = if (bucket in offered) "$catalogHref?lifecycle=${bucket.wire}" else null

        fun count(value: Long): String = "$value ${if (value == 1L) singular else plural}"
    }

    companion object {
        /** The page for a [OverviewStacked.ChartSet.STANDARD] [family] from one inventory read and one recent read, at [now]. */
        fun of(
            family: Family,
            lensed: Boolean,
            inventory: InventoryResult,
            recent: RecentResult?,
            now: Instant,
        ): OverviewPage {
            require(family.charts == OverviewStacked.ChartSet.STANDARD) { "$family reads its charts, not the four counts" }
            return page(
                family,
                lensed,
                (inventory as? InventoryResult.Available)?.let { panel(family, it.inventory) },
                (inventory as? InventoryResult.Unavailable)?.reason,
                recent?.let { recentPanel(family, it, now) },
            )
        }

        /**
         * #598a — the page for a [OverviewStacked.ChartSet.LIFECYCLE] [family] from one chart read
         * (the lifecycle and maturity charts, one snapshot) and one recent read, at [now]. An
         * unknown chart group is the frame's unavailable sentence, never a zero.
         */
        fun of(
            family: Family,
            lensed: Boolean,
            charts: ChartsResult,
            recent: RecentResult?,
            now: Instant,
        ): OverviewPage {
            require(family.charts == OverviewStacked.ChartSet.LIFECYCLE) { "$family carries the four counts, not the charts" }
            return page(
                family,
                lensed,
                (charts as? ChartsResult.Available)?.let { OverviewStacked.panel(family, it.charts) },
                (charts as? ChartsResult.Unavailable)?.reason,
                recent?.let { recentPanel(family, it, now) },
            )
        }

        private fun page(
            family: Family,
            lensed: Boolean,
            inventory: InventoryPanel?,
            unavailable: String?,
            recent: RecentPanel?,
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

        /** Every number of the panel from [inventory] alone — the chart never disagrees with its totals. */
        fun panel(
            family: Family,
            inventory: Inventory,
        ): InventoryPanel {
            val counts =
                listOf(
                    Count("objects", "Objects", inventory.objects, OBJECTS_HELP, null),
                    Count(
                        "with-drafts",
                        "With drafts",
                        inventory.withDrafts,
                        WITH_DRAFTS_HELP,
                        family.filtered(LifecycleBucket.WITH_DRAFTS),
                    ),
                    Count(
                        "with-releases",
                        "With releases",
                        inventory.withReleases,
                        WITH_RELEASES_HELP,
                        family.filtered(LifecycleBucket.WITH_RELEASES),
                    ),
                    Count(
                        "discarded",
                        "Discarded only",
                        inventory.discardedOnly,
                        DISCARDED_HELP,
                        family.filtered(LifecycleBucket.DISCARDED_ONLY),
                    ),
                )
            val buckets = BAR_ORDER.map { it to it.countIn(inventory) }
            val tallest = buckets.maxOf { it.second }
            val bars =
                buckets.map { (bucket, value) ->
                    val name = CatalogLifecycle.label(bucket)
                    Bar(
                        key = bucket.wire,
                        name = name,
                        value = value,
                        width = width(value, tallest),
                        tone = TONES.getValue(bucket),
                        label = "$name: ${family.count(value)}",
                        href = family.filtered(bucket),
                    )
                }
            // #583: the lifecycle chart, the one chart these five families carry — its caption
            // is the words the hidden table has always had, now shared with the list's label.
            val chart = Chart("lifecycle", "${family.title} by lifecycle", bars)
            return InventoryPanel(counts, listOf(chart))
        }

        private fun recentPanel(
            family: Family,
            recent: RecentResult,
            now: Instant,
        ): RecentPanel {
            val review =
                family.filtered(LifecycleBucket.WITH_DRAFTS)?.let { href -> Link(href, REVIEW_DRAFTS) }
            val section =
                RecentSection(
                    heading = RECENTLY_UPDATED,
                    note = RECENT_NOTE,
                    entries =
                        when (recent) {
                            is RecentResult.Available -> {
                                recent.entries.map {
                                    RecentItem(
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
            return RecentPanel(review, listOf(section))
        }

        /** [value] as a share of [tallest] in the chart's 0–100 viewBox; an all-zero chart is all zero. */
        internal fun width(
            value: Long,
            tallest: Long,
        ): String = if (tallest == 0L) "0" else String.format(Locale.ROOT, "%.1f", value * PERCENT / tallest)

        private const val PERCENT = 100.0

        /** The chart's rows, top to bottom: the inventory's exclusive buckets A–D (shell design §8). */
        private val BAR_ORDER =
            listOf(
                LifecycleBucket.DRAFT_ONLY,
                LifecycleBucket.RELEASED_ONLY,
                LifecycleBucket.RELEASED_WITH_DRAFT,
                LifecycleBucket.DISCARDED_ONLY,
            )

        /** Warning, success, accent and muted (shell design §8: no danger red on an ordinary draft). */
        private val TONES =
            mapOf(
                LifecycleBucket.DRAFT_ONLY to "draft",
                LifecycleBucket.RELEASED_ONLY to "released",
                LifecycleBucket.RELEASED_WITH_DRAFT to "mixed",
                LifecycleBucket.DISCARDED_ONLY to "discarded",
            )

        const val OBJECTS_HELP = "Each counted once, however many versions it has: the four bars below added together."
        const val WITH_DRAFTS_HELP = "Draft only plus released with draft changes, so it overlaps With releases."
        const val WITH_RELEASES_HELP = "Released only plus released with draft changes, so it overlaps With drafts."
        const val DISCARDED_HELP = "Every version discarded: no draft or release remains."

        /** The recent card's words, model-owned since #583 — the template renders what it is given. */
        const val REVIEW_DRAFTS = "Review drafts"
        const val RECENTLY_UPDATED = "Recently updated"
        const val RECENT_NOTE = "The latest changes in this workspace, newest first. Not a personal history."
        const val RECENT_EMPTY = "Nothing here yet. The starter prompt above is a good first step."
    }
}
