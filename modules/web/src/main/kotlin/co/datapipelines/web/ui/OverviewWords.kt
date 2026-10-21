package co.datapipelines.web.ui

import co.datapipelines.pipeline.Activity
import co.datapipelines.pipeline.Inventory
import co.datapipelines.pipeline.LifecycleBucket

/**
 * #643 — sentences for [OverviewController] and the family homes.
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
