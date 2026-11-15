package co.datapipelines.lake.reporting

import co.datapipelines.lake.definitions.LakeDefinitionErrors
import co.datapipelines.lake.definitions.LakeTableStatus
import co.datapipelines.lake.profiles.LakeProfiles
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/** One managed table's feeds, judged at [computedAt] (the catalog's clock); [feeds] in feed-name order. */
data class TableLateness(
    val tableId: UUID,
    val status: LakeTableStatus,
    val computedAt: Instant,
    val feeds: List<FeedLatenessResult>,
)

/** One feed of the table, as the ledger names it, and its verdict. */
data class FeedLatenessResult(
    val feed: String,
    val verdict: FeedVerdict,
)

/**
 * Lateness per feed of one managed table, read from the delivery ledger (lake.md "Profiles"; record §11b call 46;
 * brief 961 D2–D5). Two reads, in this order: the table's profile through [LakeProfiles.forTable] (the only way this
 * class reaches a profile), then ONE statement that returns the table's status, the catalog's clock, every feed the
 * ledger knows for the table in any state, and each feed's last `reporting.lateness_sample` published deliveries
 * ([READ_SQL]: one index probe per feed to find the feeds, one index scan per feed that stops at the sample, never a
 * scan of the table's whole ledger).
 * Each feed is judged on its own by [FeedLateness]; a `deprecated`, `archived` or `purged` table returns every feed
 * [FeedVerdict.NotJudged].
 *
 * The two reads are not one snapshot and need not be: a table assigned to another profile between them is judged by
 * the profile the first read returned, and the next call uses the new one. Nothing is stored. No route, tool or caller
 * reaches this class before P8, which gives the read its route and its permission (and wires it: pass [jdbc] the
 * caller's bounded template, as the append runtime does for `LakeProfiles`).
 */
class LakeFeedLateness(
    private val jdbc: NamedParameterJdbcTemplate,
    private val profiles: LakeProfiles,
) {
    /** The table's feeds judged now; `lake.definition.not_found` when there is no such managed table. */
    fun forTable(tableId: UUID): TableLateness {
        val profile = profiles.forTable(tableId)
        val rows =
            jdbc.query(READ_SQL, mapOf("table" to tableId, "sample" to profile.latenessSample)) { rs, _ -> row(rs) }
        val head = rows.firstOrNull() ?: throw LakeDefinitionErrors.notFound(tableId)
        val status = LakeTableStatus.fromWire(head.status)
        val feeds =
            rows
                .filter { it.feed != null }
                .groupBy({ requireNotNull(it.feed) }, { it.publishedAt })
                .map { (feed, times) ->
                    val verdict =
                        if (status in NEVER_JUDGED) {
                            FeedVerdict.NotJudged(status.wire)
                        } else {
                            FeedLateness.judge(
                                publishedTimes = times.filterNotNull(),
                                now = head.computedAt,
                                sample = profile.latenessSample,
                                overdueFactor = profile.latenessOverdueFactor,
                                minDeliveries = profile.latenessMinDeliveries,
                            )
                        }
                    FeedLatenessResult(feed, verdict)
                }.sortedBy { it.feed }
        return TableLateness(tableId, status, head.computedAt, feeds)
    }

    private fun row(rs: ResultSet): Row =
        Row(
            status = rs.getString("status"),
            computedAt = rs.getObject("computed_at", OffsetDateTime::class.java).toInstant(),
            feed = rs.getString("feed"),
            publishedAt = rs.getObject("published_at", OffsetDateTime::class.java)?.toInstant(),
        )

    private data class Row(
        val status: String,
        val computedAt: Instant,
        val feed: String?,
        val publishedAt: Instant?,
    )

    companion object {
        /** A table in one of these statuses is never flagged (brief 961 D3; record call 46). */
        val NEVER_JUDGED: Set<LakeTableStatus> = setOf(LakeTableStatus.DEPRECATED, LakeTableStatus.ARCHIVED, LakeTableStatus.PURGED)

        /**
         * The one statement (D2 step 2). `t` answers the status and the clock for any existing table, also one with no
         * delivery. `walk` finds every feed of the table in ANY state (a feed with only FAILED rows, or a PUBLISHED row
         * with a null `published_at`, is still found) one probe of the unique key `uq_lake_deliveries_key` at a time:
         * the smallest feed, then the smallest feed greater than the last, until none is left. Each feed's samples are
         * a lateral scan of V62's `idx_lake_deliveries_published` in `published_at DESC` order that stops at `:sample`
         * entries. So the plan's shape is one probe per feed to find the feeds and one scan per feed that stops at
         * `:sample` entries, with no scan of the table's whole ledger; the work behind that shape also depends on the
         * indexes' depth, on visibility and on dead entries. One row per sample, or one row with a null `published_at`
         * for a feed with none, or one row with a null feed for a table with no delivery; no row for an unknown table.
         */
        internal val READ_SQL =
            """
            WITH RECURSIVE t AS (
                SELECT status, now() AS computed_at FROM lake_tables_managed WHERE id = :table
            ),
            walk AS (
                (SELECT feed FROM lake_deliveries WHERE table_id = :table ORDER BY feed LIMIT 1)
                UNION ALL
                SELECT (
                    SELECT d.feed FROM lake_deliveries d
                    WHERE d.table_id = :table AND d.feed > w.feed
                    ORDER BY d.feed LIMIT 1
                )
                FROM walk w
                WHERE w.feed IS NOT NULL
            ),
            feeds AS (
                SELECT feed FROM walk WHERE feed IS NOT NULL
            )
            SELECT t.status, t.computed_at, f.feed, s.published_at
            FROM t
            LEFT JOIN feeds f ON TRUE
            LEFT JOIN LATERAL (
                SELECT d.published_at FROM lake_deliveries d
                WHERE d.table_id = :table AND d.feed = f.feed AND d.state = 'PUBLISHED' AND d.published_at IS NOT NULL
                ORDER BY d.published_at DESC
                LIMIT :sample
            ) s ON TRUE
            ORDER BY f.feed, s.published_at DESC
            """.trimIndent()
    }
}
