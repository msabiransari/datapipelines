package co.datapipelines.web.ui

import co.datapipelines.pipeline.Inventory
import java.time.Instant

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
 *
 * The lifecycle home's builders live in [LifecycleHomePage]; the companion's `of`, `tasks`,
 * `iconFor` and `width` are one-line delegates there (#670). The family facts live in the
 * top-level [OverviewFamily] (#670 round 2).
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
     * [tone] uses draft, released, mixed, discarded (lifecycle) or success, warning, danger
     * (ops status); see the overview token block in `app.css`.
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
     * the accessible sentence. [tone] uses draft, released, mixed, discarded (lifecycle) or
     * success, warning, danger (ops status); see the overview token block in `app.css`.
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

    companion object {
        /** The builders live in [LifecycleHomePage]; these delegates keep the frame's face (#670). */
        fun of(
            family: OverviewFamily,
            lensed: Boolean,
            charts: ChartsResult,
            recent: RecentResult?,
            now: Instant,
        ): OverviewPage = LifecycleHomePage.of(family, lensed, charts, recent, now)

        fun tasks(
            family: OverviewFamily,
            inventory: Inventory?,
        ): List<TaskRow> = LifecycleHomePage.tasks(family, inventory)

        fun iconFor(family: String): String = LifecycleHomePage.iconFor(family)

        internal fun width(
            value: Long,
            tallest: Long,
        ): String = LifecycleHomePage.width(value, tallest)
    }
}
