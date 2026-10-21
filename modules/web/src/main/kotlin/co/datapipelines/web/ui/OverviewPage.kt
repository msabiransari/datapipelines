package co.datapipelines.web.ui

import co.datapipelines.application.lens.LensedView
import co.datapipelines.pipeline.Activity
import co.datapipelines.pipeline.Inventory
import co.datapipelines.pipeline.LifecycleBucket
import co.datapipelines.pipeline.NavigationFamily
import co.datapipelines.pipeline.ReadLens
import java.time.Instant
import java.util.Locale

/**
 * #556 — what one family overview renders (`overview/family.html`, shell design §8), built in ONE
 * place from one [ChartsResult] and one [RecentResult], so the page's summary counts and its
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
    val icon: String = iconFor(family),
) {
    /**
     * #638 — the summary strip: the panel's [InventoryPanel.metrics] when it carries them,
     * otherwise its counts, one metric each (the sibling homes' fallback).
     * Empty when the inventory is unknown: the strip then says so, no digit.
     */
    val metrics: List<Metric>
        get() = inventory?.let { panel -> panel.metrics.ifEmpty { panel.counts.map { Metric.of(it, lensed) } } }.orEmpty()

    /** The strip's foot (Live with changes, Retired); empty for a family whose panel has none. */
    val foot: List<Metric> get() = inventory?.foot.orEmpty()

    /**
     * #621 — the strip's heading says "Admitted inventory" (and the heading's sentence names the
     * lens) only when EVERY metric passed the lens; otherwise each lensed metric says so itself
     * ([lensNote]). A page with no metrics keeps the page's own flag.
     */
    val admitted: Boolean get() = if (metrics.isEmpty()) lensed else metrics.all { it.lensed }

    /** Whether [metric] carries the admitted-inventory words itself: lensed, on a strip that is not admitted as a whole. */
    fun lensNote(metric: Metric): Boolean = metric.lensed && !admitted

    /**
     * The summary, the charts and their dialogs, every number from the one model its page built.
     * [counts] and [charts] are the #556 shape the siblings build; [metrics] and [foot] the
     * strip (#638), [panels] the analytics grid (by default the charts as bars panels),
     * [viewAll] the View all badge (null: no badge) and [explanations] the panels' and strip's
     * dialog sections.
     */
    data class InventoryPanel(
        val counts: List<Count>,
        val charts: List<Chart>,
        val metrics: List<Metric> = emptyList(),
        val foot: List<Metric> = emptyList(),
        val panels: List<Panel> = charts.map { Panel.Bars.of(it) },
        val viewAll: Long? = null,
        val explanations: List<Explanation> = emptyList(),
    )

    /**
     * #638 — one cell of the summary strip (or its foot): [note] the line under the number,
     * [href] its filtered catalog (null: unlinked), [tone] the status dot (null: none), [lensed]
     * whether the number passed the promoter lens (#621), [title] the hover sentence (Retired's
     * "have no page"). [value] is null only for a number a sibling cannot state.
     */
    data class Metric(
        val key: String,
        val label: String,
        val value: Long?,
        val note: String?,
        val href: String?,
        val tone: String? = null,
        val lensed: Boolean = false,
        val title: String? = null,
    ) {
        companion object {
            /** A #556 [Count] as a metric: its help is the note. */
            fun of(
                count: Count,
                lensed: Boolean,
            ): Metric = Metric(count.key, count.label, count.value, count.help, count.href, lensed = lensed)
        }
    }

    /**
     * #638 — one dialog section the page's `<dialog>` shows by [key]: [paragraphs] the
     * server-written sentences, [terms] an optional definition list, [table] the panel key whose
     * hidden table moves into the section while it is open (null: none).
     */
    data class Explanation(
        val key: String,
        val title: String,
        val paragraphs: List<String>,
        val terms: List<Term> = emptyList(),
        val table: String? = null,
    )

    /** One definition: the owner's [name], the catalog's word for it ([catalog], #626), its count and sentence. */
    data class Term(
        val name: String,
        val catalog: String?,
        val value: Long,
        val text: String,
    )

    /**
     * #638 — one Continue your work row: a tinted [icon] in [tone], the [title], the
     * sentence, and the catalog it opens.
     */
    data class TaskRow(
        val key: String,
        val icon: String,
        val title: String,
        val text: String,
        val href: String,
        val tone: String,
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
     * its own heading, caveat and list. #638: [tasks] are the rows above the sections and
     * [description] the line under the card's title.
     */
    data class RecentPanel(
        val review: Link?,
        val sections: List<RecentSection>,
        val tasks: List<TaskRow> = emptyList(),
        val description: String? = null,
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
     * the inventory card the family carries: since #599 all five are lifecycle homes (#598a/b,
     * #638: the strip, the activity columns and the release history from one chart read).
     */
    enum class Family(
        val navigation: NavigationFamily,
        val title: String,
        val singular: String,
        val plural: String,
        val offered: List<LifecycleBucket>,
        val guided: Boolean,
        val icon: String,
        val charts: OverviewStacked.ChartSet,
    ) {
        PIPELINES(
            NavigationFamily.PIPELINES,
            "Pipelines",
            "pipeline",
            "pipelines",
            CatalogLifecycle.PIPELINES,
            guided = true,
            icon = "workflow",
            charts = OverviewStacked.ChartSet.LIFECYCLE,
        ),
        TEMPLATES(
            NavigationFamily.TEMPLATES,
            "Templates",
            "template",
            "templates",
            CatalogLifecycle.TEMPLATES,
            guided = true,
            icon = "code",
            charts = OverviewStacked.ChartSet.LIFECYCLE,
        ),
        DASHBOARDS(
            NavigationFamily.DASHBOARDS,
            "Dashboards",
            "dashboard",
            "dashboards",
            CatalogLifecycle.DASHBOARDS,
            guided = true,
            icon = "boxes",
            charts = OverviewStacked.ChartSet.LIFECYCLE,
        ),
        VISUALIZATIONS(
            NavigationFamily.VISUALIZATIONS,
            "Visualizations",
            "visualization",
            "visualizations",
            CatalogLifecycle.VISUALIZATIONS,
            guided = true,
            icon = "chart-line",
            charts = OverviewStacked.ChartSet.LIFECYCLE,
        ),
        PARAMETER_SETS(
            NavigationFamily.PARAMETER_SETS,
            "Parameter Sets",
            "parameter set",
            "parameter sets",
            CatalogLifecycle.PARAMETER_SETS,
            guided = true,
            icon = "sliders-horizontal",
            charts = OverviewStacked.ChartSet.LIFECYCLE,
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
            family: Family,
            inventory: Inventory?,
        ): List<TaskRow> =
            listOfNotNull(
                family.filtered(LifecycleBucket.DRAFT_ONLY)?.let { href ->
                    TaskRow("drafts", "file", "Review your drafts", OverviewWords.drafts(family, inventory?.draftOnly), href, "draft")
                },
                family.filtered(LifecycleBucket.RELEASED_ONLY)?.let { href ->
                    TaskRow(
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
            Family.entries.firstOrNull { it.navigation.route == family }?.icon ?: SIBLING_ICONS[family] ?: DEFAULT_ICON

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
        private const val DEFAULT_ICON = "layout-grid"

        /** The three homes whose controllers build their page directly (rail icons, shell record §3). */
        private val SIBLING_ICONS = mapOf("datasources" to "database", "schedules" to "calendar-clock", "api-console" to "activity")

        /** The recent card's words, model-owned since #583 — the template renders what it is given. */
        const val REVIEW_DRAFTS = "Review drafts"
        const val RECENTLY_UPDATED = "Recently updated"
        const val RECENT_NOTE = "The latest changes in this workspace, newest first. Not a personal history."
        const val RECENT_EMPTY = "Nothing here yet. The starter prompt above is a good first step."
    }
}

/**
 * #638 — the dialog sections' and rows' sentences, in the reader's words (ui-screens §4.25,
 * the counting model), each built from the numbers the page already shows.
 */
object OverviewWords {
    const val DEFINITIONS = "definitions"
    const val RELEASES = "releases"
    const val ACTIVITY = "activity"

    /** "14 days", "1 day" — the actively developed window as configured. */
    fun days(value: Int): String = "$value ${if (value == 1) "day" else "days"}"

    /** Review your drafts' sentence: the In development count worded, or none when it is unknown. */
    fun drafts(
        family: OverviewPage.Family,
        draftOnly: Long?,
    ): String =
        when (draftOnly) {
            null -> "${family.title} working toward their first release."
            0L -> "No ${family.singular} is waiting for its first release."
            1L -> "1 ${family.singular} is working toward its first release."
            else -> "$draftOnly ${family.plural} are working toward their first release."
        }

    /**
     * "All 4 unreleased pipelines have a draft." / "3 of the 10 released pipelines have open
     * changes." — [part] of [whole] objects in the [group] have [what]; null when [whole] is 0.
     */
    fun share(
        part: Long,
        whole: Long,
        group: String,
        family: OverviewPage.Family,
        what: String,
    ): String? =
        when {
            whole == 0L -> null
            whole == 1L -> "The 1 $group ${family.singular} ${if (part == 1L) "has $what" else "has no ${what.removePrefix("a ")}"}."
            part == whole -> "All $whole $group ${family.plural} have $what."
            part == 0L -> "None of the $whole $group ${family.plural} have $what."
            else -> "$part of the $whole $group ${family.plural} ${if (part == 1L) "has" else "have"} $what."
        }

    /** How the summary is counted: the four exclusive groups, the overlapping measure, the browser's count (#593, #626). */
    fun definitions(
        family: OverviewPage.Family,
        inventory: Inventory,
        windowDays: Int,
    ): OverviewPage.Explanation {
        val names = OverviewStacked.LIFECYCLE_NAMES
        val texts =
            mapOf(
                LifecycleBucket.DRAFT_ONLY to "A draft, and no release yet.",
                LifecycleBucket.RELEASED_ONLY to "A release, and no open draft.",
                LifecycleBucket.RELEASED_WITH_DRAFT to "A release, and a draft in progress.",
                LifecycleBucket.DISCARDED_ONLY to "Every version discarded: no draft or release remains.",
            )
        return OverviewPage.Explanation(
            key = DEFINITIONS,
            title = "How the summary is counted",
            paragraphs =
                listOf(
                    "Each ${family.singular} belongs to one lifecycle group. These four groups total ${inventory.objects}.",
                    "Actively developed is a separate measure: a ${family.singular} with a draft edited in the last " +
                        "${days(windowDays)}. It overlaps the lifecycle groups and is not an additional group. A draft " +
                        "keeps only its last write, so this is a count today, not a history.",
                    "The resource browser and View all count the three groups that are not retired: " +
                        "${OverviewStacked.live(inventory)}. Retired ${family.plural} have no page.",
                ),
            terms =
                names.map { (bucket, name) ->
                    OverviewPage.Term(name, CatalogLifecycle.label(bucket), bucket.countIn(inventory), texts.getValue(bucket))
                },
        )
    }

    /** Understanding release history: the maturity rule, then the panel's own two sentences. */
    fun releases(
        family: OverviewPage.Family,
        note: List<String>,
    ): OverviewPage.Explanation =
        OverviewPage.Explanation(
            key = RELEASES,
            title = "Understanding release history",
            paragraphs =
                listOf(
                    "Each ${family.singular} appears in one release-count bucket. The bars compare the number of " +
                        "${family.plural}, not their number of executions.",
                    "A release that has since been discarded is not counted. A retired ${family.singular} has zero releases.",
                ) + note,
        )

    /** The activity dialog: the events rule above the table twin it receives. */
    fun activity(family: OverviewPage.Family): OverviewPage.Explanation =
        OverviewPage.Explanation(
            key = ACTIVITY,
            title = "Development activity · ${Activity.WEEKS} weeks",
            paragraphs =
                listOf(
                    "Events, not distinct ${family.plural}. A ${family.singular} created and released in the same week " +
                        "contributes to both counts. Weeks begin Monday (UTC); discarded releases are excluded.",
                ),
            table = ACTIVITY,
        )
}
