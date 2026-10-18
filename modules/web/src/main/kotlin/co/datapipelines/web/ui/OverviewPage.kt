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
 * bars are read from the same [Inventory] value and cannot disagree.
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
    /** The four summary counts and the four bars, every number from ONE [Inventory]. */
    data class InventoryPanel(
        val counts: List<Count>,
        val bars: List<Bar>,
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

    /** Continue your work: the newest entries (or why they are unknown) and the Review drafts link. */
    data class RecentPanel(
        val entries: List<RecentItem>?,
        val unavailable: String?,
        val reviewHref: String,
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

    /** The family facts an overview names. Parameter sets' route is `parameter-sets`. */
    enum class Family(
        val navigation: NavigationFamily,
        val title: String,
        val singular: String,
        val plural: String,
        val offered: List<LifecycleBucket>,
        val guided: Boolean,
    ) {
        PIPELINES(NavigationFamily.PIPELINES, "Pipelines", "pipeline", "pipelines", CatalogLifecycle.PIPELINES, guided = true),
        TEMPLATES(NavigationFamily.TEMPLATES, "Templates", "template", "templates", emptyList(), guided = false),
        DASHBOARDS(NavigationFamily.DASHBOARDS, "Dashboards", "dashboard", "dashboards", emptyList(), guided = false),
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
            emptyList(),
            guided = false,
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
        /** The page for [family] from one inventory read and one recent read, at [now]. */
        fun of(
            family: Family,
            lensed: Boolean,
            inventory: InventoryResult,
            recent: RecentResult?,
            now: Instant,
        ): OverviewPage =
            OverviewPage(
                family = family.navigation.route,
                title = family.title,
                catalogHref = family.catalogHref,
                lensed = lensed,
                inventory = (inventory as? InventoryResult.Available)?.let { panel(family, it.inventory) },
                unavailable = (inventory as? InventoryResult.Unavailable)?.reason,
                recent = recent?.let { recentPanel(family, it, now) },
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
            return InventoryPanel(counts, bars)
        }

        private fun recentPanel(
            family: Family,
            recent: RecentResult,
            now: Instant,
        ): RecentPanel {
            val review = family.filtered(LifecycleBucket.WITH_DRAFTS) ?: family.catalogHref
            return when (recent) {
                is RecentResult.Available -> {
                    RecentPanel(
                        recent.entries.map {
                            RecentItem(
                                name = it.name,
                                href = it.href,
                                datetime = it.modifiedAt.toString(),
                                ago = RelativeTime.since(it.modifiedAt, now),
                                absolute = RelativeTime.absolute(it.modifiedAt),
                                hasDraft = it.hasDraft,
                            )
                        },
                        null,
                        review,
                    )
                }

                is RecentResult.Unavailable -> {
                    RecentPanel(null, recent.reason, review)
                }
            }
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
    }
}
