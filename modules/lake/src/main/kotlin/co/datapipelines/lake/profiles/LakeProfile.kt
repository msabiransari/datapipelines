package co.datapipelines.lake.profiles

import java.time.LocalTime
import java.time.ZoneId

/**
 * A lake profile's values, every one present and already validated (lake.md "Profiles"; design record §11a call 35,
 * §11b calls 42 and 46). Only [LakeProfileDocument] builds one, from a stored or submitted `values_json`; nothing
 * else reads that JSON. Append and maintenance code get a tuning value from a property of this class and nowhere
 * else (`LakeTuningSourceGuardTest`).
 */
data class LakeProfile(
    /** `maintenance.window`: when rewrites may start, in UTC; a start equal to the end is the whole day. */
    val maintenanceWindow: MaintenanceWindow,
    /** `maintenance.max_bytes_rewritten_per_run`: at least twice [targetFileBytes]. */
    val maxBytesRewrittenPerRun: Long,
    /** `maintenance.target_file_bytes`: the size the append writer rolls a data file at, and compaction aims for. */
    val targetFileBytes: Long,
    /** `maintenance.compact_min_small_files`: small files one partition must hold before compaction is due. */
    val compactMinSmallFiles: Int,
    /** `maintenance.compact_small_file_ratio`: a file is small below this share of [targetFileBytes]. */
    val compactSmallFileRatio: Double,
    /** `maintenance.rewrite_manifests_min_count`: manifests a table must hold before a manifest rewrite is due. */
    val rewriteManifestsMinCount: Int,
    /** `maintenance.cleanup_interval_hours`: the longest a table goes between cleanups. */
    val cleanupIntervalHours: Int,
    /** `write.row_group_bytes`: the Parquet row group size; at most [targetFileBytes]. */
    val rowGroupBytes: Long,
    /** `retention.keep`: the computed keep rules, at most one per period. */
    val keep: List<KeepRule>,
    /** `retention.keep_time_zone`: the IANA zone period ends and keep ends are computed in. */
    val keepTimeZone: ZoneId,
    /** `reporting.lateness_sample`: how many recent published deliveries the lateness interval is taken from. */
    val latenessSample: Int,
    /** `reporting.lateness_overdue_factor`: overdue after this many typical intervals. */
    val latenessOverdueFactor: Double,
    /** `reporting.lateness_min_deliveries`: fewer published deliveries are "not enough to say". */
    val latenessMinDeliveries: Int,
)

/** `HH:MM-HH:MM` in UTC (record call 35). [isWholeDay] when the start equals the end. */
data class MaintenanceWindow(
    val start: LocalTime,
    val end: LocalTime,
) {
    val isWholeDay: Boolean get() = start == end

    /** The stored spelling, `HH:MM-HH:MM`. */
    val text: String get() = "$start-$end"
}

/** One `retention.keep` rule: the table as it stood at the end of every [period], kept for [keepFor] (record call 42). */
data class KeepRule(
    val period: KeepPeriod,
    val keepFor: KeepDuration,
)

/** `keep_for`: [n] whole [unit]s. */
data class KeepDuration(
    val n: Int,
    val unit: KeepUnit,
)

/**
 * A keep rule's period, with its LONGEST and SHORTEST length in calendar days and, for the calendar periods, its
 * length in months (the fixed tables of brief 956 D2). A week is the ISO week.
 */
enum class KeepPeriod(
    val wire: String,
    val longestDays: Int,
    val shortestDays: Int,
    val months: Int?,
) {
    DAY("day", longestDays = 1, shortestDays = 1, months = null),
    WEEK("week", longestDays = 7, shortestDays = 7, months = null),
    MONTH("month", longestDays = 31, shortestDays = 28, months = 1),
    QUARTER("quarter", longestDays = 92, shortestDays = 90, months = 3),
    YEAR("year", longestDays = 366, shortestDays = 365, months = 12),
    ;

    companion object {
        fun fromWire(value: String): KeepPeriod? = entries.firstOrNull { it.wire == value }
    }
}

/** A `keep_for` unit, with the largest `n` it admits (brief 956 D2: 400 years in each unit). */
enum class KeepUnit(
    val wire: String,
    val maxN: Int,
) {
    DAYS("days", maxN = 146_100),
    MONTHS("months", maxN = 4_800),
    YEARS("years", maxN = 400),
    ;

    companion object {
        fun fromWire(value: String): KeepUnit? = entries.firstOrNull { it.wire == value }
    }
}
