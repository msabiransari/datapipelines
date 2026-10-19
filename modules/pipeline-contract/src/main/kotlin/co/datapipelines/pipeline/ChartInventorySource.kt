package co.datapipelines.pipeline

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
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

/**
 * #598a — the overview's chart group from ONE statement: the four lifecycle buckets
 * ([lifecycle], exactly [InventorySource]'s) and the five maturity bins in [ReleaseBin] order.
 * Every object the lifecycle counts sits in exactly one bin (a retired object has 0 releases and
 * no draft), so both charts sum to the same total; a value that does not is refused at
 * construction, never drawn.
 */
data class ChartInventory(
    val lifecycle: Inventory,
    val maturity: List<MaturityBin>,
) {
    init {
        require(maturity.map { it.releases } == ReleaseBin.entries) { "maturity must list every release bin once, in order" }
        require(lifecycle.objects == maturity.sumOf { it.total }) {
            "the maturity bins sum to ${maturity.sumOf { it.total }}, the lifecycle to ${lifecycle.objects}"
        }
    }

    companion object {
        /** The empty chart group: every bucket and every bin zero. */
        val EMPTY = ChartInventory(Inventory(0, 0, 0, 0), ReleaseBin.entries.map { MaturityBin(it, 0, 0) })
    }
}

/**
 * #598a — ONE SQL statement supplies the lifecycle buckets and the maturity bins from one
 * PostgreSQL read snapshot, so the two charts cannot disagree with each other or with the total.
 * The `admitted` CTE is [InventorySource.admitted] itself, extended by the per-object release
 * count, so the two sources share one definition of an object and of the versions admitted to it.
 * Counts only: no name, body or user column is selected. Identifiers come exclusively from
 * [NavigationFamily]; workspace and names are bound parameters.
 */
object ChartInventorySource {
    /** Aggregates the complete admitted set; an empty lens performs no database access. */
    fun aggregate(
        jdbc: NamedParameterJdbcTemplate,
        family: NavigationFamily,
        workspaceId: UUID,
        lens: ReadLens,
    ): ChartInventory {
        val names = (lens as? ReadLens.Only)?.names
        if (names != null && names.isEmpty()) return ChartInventory.EMPTY
        return jdbc
            .query(
                sql(family, narrowed = names != null),
                mapOf("workspace" to workspaceId, "names" to names),
            ) { rs, _ ->
                ChartInventory(
                    lifecycle = InventorySource.lifecycle(rs),
                    maturity =
                        ReleaseBin.entries.map { bin ->
                            MaturityBin(bin, draftOpen = rs.getLong("${bin.column}_draft"), noDraft = rs.getLong("${bin.column}_no_draft"))
                        },
                )
            }.single()
    }

    /** The per-object release count the bins read: admitted RELEASED versions only. */
    private const val RELEASES = ", count(*) FILTER (WHERE v.status = 'RELEASED') AS releases"

    internal fun sql(
        family: NavigationFamily,
        narrowed: Boolean,
    ): String {
        val bins =
            ReleaseBin.entries.joinToString(",\n       ") { bin ->
                "count(*) FILTER (WHERE ${bin.predicate} AND d) AS ${bin.column}_draft,\n       " +
                    "count(*) FILTER (WHERE ${bin.predicate} AND NOT d) AS ${bin.column}_no_draft"
            }
        return "${InventorySource.admitted(family, narrowed, RELEASES)}\n" +
            "SELECT ${InventorySource.LIFECYCLE_COUNTS},\n       $bins\n  FROM admitted"
    }
}
