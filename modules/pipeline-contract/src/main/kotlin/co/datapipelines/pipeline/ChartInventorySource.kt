package co.datapipelines.pipeline

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.sql.Timestamp
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.TemporalAdjusters
import java.util.UUID

/**
 * #598a — the release-count bins of the overview's maturity chart. An object's releases are its
 * ADMITTED versions whose status is RELEASED: a release since discarded is not a release any more
 * (it is what a retired object is made of), and a narrowed lens admits RELEASED rows only, so one
 * definition serves both arms. [min] and [max] (null: unbounded) are the ONE definition of a bin;
 * the SQL [predicate] and [contains] are both derived from them.
 */
enum class ReleaseBin(
    val label: String,
    val column: String,
    val min: Long,
    val max: Long?,
) {
    ZERO(label = "0", column = "r0", min = 0, max = 0),
    ONE(label = "1", column = "r1", min = 1, max = 1),
    TWO_TO_THREE(label = "2–3", column = "r2_3", min = 2, max = 3),
    FOUR_TO_NINE(label = "4–9", column = "r4_9", min = 4, max = 9),
    TEN_PLUS(label = "10+", column = "r10", min = 10, max = null),
    ;

    /** This bin's range over `admitted.releases`, as SQL. */
    val predicate: String
        get() =
            when (max) {
                null -> "releases >= $min"
                min -> "releases = $min"
                else -> "releases BETWEEN $min AND $max"
            }

    /** Whether an object with [releases] releases falls in this bin — [predicate]'s Kotlin twin. */
    fun contains(releases: Long): Boolean = releases >= min && (max == null || releases <= max)

    companion object {
        /** The one bin [releases] falls in; the bins partition every count from 0 up. */
        fun of(releases: Long): ReleaseBin = entries.first { it.contains(releases) }
    }
}

/** One maturity bin: how many objects with that many releases hold an admitted draft, and how many do not. */
data class MaturityBin(
    val releases: ReleaseBin,
    val draftOpen: Long,
    val noDraft: Long,
) {
    val total: Long get() = draftOpen + noDraft
}

/** #598b — one UTC calendar week of the activity chart, starting on the Monday [weekStart]. */
data class WeekActivity(
    val weekStart: LocalDate,
    val released: Long,
    val created: Long,
) {
    val events: Long get() = released + created
}

/**
 * #598b — the activity chart's twelve weeks, oldest first, and the actively developed count at the
 * moment of the read. Only a draft's LAST write is stored, so [activelyDeveloped] is a count now,
 * never a history; [windowDays] is the window it was counted over. A value whose weeks are not
 * twelve consecutive Mondays, or whose counts are negative, is refused at construction.
 */
data class Activity(
    val weeks: List<WeekActivity>,
    val activelyDeveloped: Long,
    val windowDays: Int,
) {
    init {
        require(weeks.size == WEEKS) { "the activity lists $WEEKS weeks, not ${weeks.size}" }
        require(weeks.first().weekStart.dayOfWeek == DayOfWeek.MONDAY) { "a week starts on a Monday" }
        require(weeks.zipWithNext().all { (older, newer) -> newer.weekStart == older.weekStart.plusDays(DAYS_PER_WEEK) }) {
            "the weeks are consecutive, oldest first"
        }
        require(weeks.all { it.released >= 0 && it.created >= 0 } && activelyDeveloped >= 0) { "a count is never negative" }
        require(windowDays >= 1) { "the actively developed window is at least one day" }
    }

    companion object {
        const val WEEKS = 12
        private const val DAYS_PER_WEEK = 7L

        /** The Mondays of the [WEEKS] UTC weeks ending with the one that contains [now], oldest first. */
        fun weekStarts(now: Instant): List<LocalDate> {
            val newest = now.atOffset(ZoneOffset.UTC).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            return (WEEKS - 1 downTo 0).map { ago -> newest.minusWeeks(ago.toLong()) }
        }

        /** Twelve empty weeks ending with [now]'s and nobody actively developing. */
        fun empty(
            now: Instant,
            windowDays: Int,
        ): Activity = Activity(weekStarts(now).map { WeekActivity(it, 0, 0) }, activelyDeveloped = 0, windowDays = windowDays)
    }
}

/**
 * #598a — the overview's chart group from ONE statement: the four lifecycle buckets
 * ([lifecycle], exactly [InventorySource]'s) and the five maturity bins in [ReleaseBin] order.
 * Every object the lifecycle counts sits in exactly one bin (a retired object has 0 releases and
 * no draft), so both charts sum to the same total; a value that does not is refused at
 * construction, never drawn. #598b adds the [activity]: an actively developed object holds an
 * admitted draft, and a created object is one of the admitted ones, so neither count can exceed
 * the lifecycle's — a theorem of the definitions, checked here as one.
 */
data class ChartInventory(
    val lifecycle: Inventory,
    val maturity: List<MaturityBin>,
    val activity: Activity,
) {
    init {
        require(maturity.map { it.releases } == ReleaseBin.entries) { "maturity must list every release bin once, in order" }
        require(lifecycle.objects == maturity.sumOf { it.total }) {
            "the maturity bins sum to ${maturity.sumOf { it.total }}, the lifecycle to ${lifecycle.objects}"
        }
        require(activity.activelyDeveloped <= lifecycle.withDrafts) {
            "${activity.activelyDeveloped} actively developed, but only ${lifecycle.withDrafts} hold a draft"
        }
        require(activity.weeks.sumOf { it.created } <= lifecycle.objects) {
            "${activity.weeks.sumOf { it.created }} created in the weeks, but only ${lifecycle.objects} objects"
        }
    }

    companion object {
        /** The empty chart group at [now]: every bucket, bin and week zero. */
        fun empty(
            now: Instant,
            windowDays: Int,
        ): ChartInventory =
            ChartInventory(Inventory(0, 0, 0, 0), ReleaseBin.entries.map { MaturityBin(it, 0, 0) }, Activity.empty(now, windowDays))
    }
}

/**
 * #598a — ONE SQL statement supplies the lifecycle buckets and the maturity bins from one
 * PostgreSQL read snapshot, so the two charts cannot disagree with each other or with the total.
 * The `admitted` CTE is [InventorySource.admitted] itself, extended by the per-object release
 * count, so the two sources share one definition of an object and of the versions admitted to it.
 * Counts only: no name, body or user column is selected. Identifiers come exclusively from
 * [NavigationFamily]; workspace and names are bound parameters.
 *
 * #598b — the same statement counts the activity chart, against `:now` and `:active_days`:
 * - **Weeks** are UTC calendar weeks, Monday 00:00:00Z to Sunday 23:59:59.999999Z
 *   (`date_trunc('week', …)` is ISO, Monday-first), bucketed on `… AT TIME ZONE 'UTC'` so the
 *   session time zone never moves a boundary; week 0 contains `:now`, week 11 is eleven before it.
 * - **Released in a week:** admitted versions with status RELEASED whose `released_at` falls in it.
 *   A release since discarded is not counted (the maturity chart's rule); a narrowed lens admits
 *   RELEASED rows only anyway.
 * - **Created in a week:** admitted objects whose `created_at` falls in it. Through a narrowed lens
 *   an object with no admitted version is not admitted, so a draft-only object is never new to a
 *   promoter — as every other number on the page.
 * - **Actively developed:** admitted objects holding an admitted DRAFT whose write stamp,
 *   `COALESCE(v.updated_at, v.created_at)`, is at or after `:now - :active_days` days (inclusive).
 *   Every draft write stamps `updated_at` (`PipelineRepository`'s draft INSERTs and UPDATEs), so the
 *   fallback guards only a row written outside the repository. Through a narrowed lens no DRAFT is
 *   admitted: 0. A draft keeps only its last write, so this is a count at `:now`, never a history.
 */
object ChartInventorySource {
    /** Aggregates the complete admitted set at [now]; an empty lens performs no database access. */
    fun aggregate(
        jdbc: NamedParameterJdbcTemplate,
        family: NavigationFamily,
        workspaceId: UUID,
        lens: ReadLens,
        now: Instant,
        activeDays: Int,
    ): ChartInventory {
        require(activeDays >= 1) { "the actively developed window is at least one day, not $activeDays" }
        val names = (lens as? ReadLens.Only)?.names
        if (names != null && names.isEmpty()) return ChartInventory.empty(now, activeDays)
        val mondays = Activity.weekStarts(now)
        return jdbc
            .query(
                sql(family, narrowed = names != null),
                mapOf("workspace" to workspaceId, "names" to names, "now" to Timestamp.from(now), "active_days" to activeDays),
            ) { rs, _ ->
                ChartInventory(
                    lifecycle = InventorySource.lifecycle(rs),
                    maturity =
                        ReleaseBin.entries.map { bin ->
                            MaturityBin(bin, draftOpen = rs.getLong("${bin.column}_draft"), noDraft = rs.getLong("${bin.column}_no_draft"))
                        },
                    activity =
                        Activity(
                            weeks =
                                mondays.mapIndexed { index, monday ->
                                    val ago = Activity.WEEKS - 1 - index
                                    WeekActivity(monday, released = rs.getLong("w${ago}_released"), created = rs.getLong("w${ago}_created"))
                                },
                            activelyDeveloped = rs.getLong("active"),
                            windowDays = activeDays,
                        ),
                )
            }.single()
    }

    /** The per-object release count the bins read: admitted RELEASED versions only. */
    internal const val RELEASES = ", count(*) FILTER (WHERE v.status = 'RELEASED') AS releases"

    /** The UTC week `:now` falls in, as the Monday's midnight (a `timestamp` in UTC). */
    private const val NOW_WEEK = "date_trunc('week', CAST(:now AS timestamptz) AT TIME ZONE 'UTC')"

    /** Whether the `timestamptz` [expression] falls in the UTC week [ago] weeks before `:now`'s. */
    private fun inWeek(
        expression: String,
        ago: Int,
    ): String = "date_trunc('week', $expression AT TIME ZONE 'UTC') = $NOW_WEEK - INTERVAL '${ago * DAYS_PER_WEEK} days'"

    private const val DAYS_PER_WEEK = 7

    /**
     * #598b — the per-object activity aggregates over the SAME admitted rows: each week's release
     * count, the object's creation and its draft's last write.
     */
    internal val ACTIVITY: String =
        (0 until Activity.WEEKS).joinToString("") { ago ->
            ", count(*) FILTER (WHERE v.status = 'RELEASED' AND ${inWeek("v.released_at", ago)}) AS w${ago}_rel"
        } + ", min(s.created_at) AS created_at" +
            ", max(COALESCE(v.updated_at, v.created_at)) FILTER (WHERE v.status = 'DRAFT') AS draft_written"

    internal fun sql(
        family: NavigationFamily,
        narrowed: Boolean,
    ): String {
        val bins =
            ReleaseBin.entries.joinToString(",\n       ") { bin ->
                "count(*) FILTER (WHERE ${bin.predicate} AND d) AS ${bin.column}_draft,\n       " +
                    "count(*) FILTER (WHERE ${bin.predicate} AND NOT d) AS ${bin.column}_no_draft"
            }
        val weeks =
            (0 until Activity.WEEKS).joinToString(",\n       ") { ago ->
                "COALESCE(sum(w${ago}_rel), 0) AS w${ago}_released,\n       " +
                    "count(*) FILTER (WHERE ${inWeek("created_at", ago)}) AS w${ago}_created"
            }
        val active = "count(*) FILTER (WHERE draft_written >= CAST(:now AS timestamptz) - make_interval(days => :active_days)) AS active"
        return "${InventorySource.admitted(family, narrowed, RELEASES + ACTIVITY)}\n" +
            "SELECT ${InventorySource.LIFECYCLE_COUNTS},\n       $bins,\n       $weeks,\n       $active\n  FROM admitted"
    }
}
