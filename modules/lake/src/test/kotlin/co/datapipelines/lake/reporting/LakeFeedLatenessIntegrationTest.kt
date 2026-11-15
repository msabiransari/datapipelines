package co.datapipelines.lake.reporting

import co.datapipelines.lake.LakeTestDb
import co.datapipelines.lake.definitions.LakeTableStatus
import co.datapipelines.lake.profiles.LakeProfileFixtures.document
import co.datapipelines.lake.profiles.LakeProfilesHarness
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * The read over the real Postgres and the shipped migrations (brief 961 B, record K6): F5 each feed judged on its own,
 * every feed the ledger knows returned, non-published deliveries counting for nothing; F6 a deprecated, archived or
 * purged table never flagged, the profile reached through `LakeProfiles.forTable` and followed at the next read, and
 * V62's index serving the read over a table with 10,000 deliveries. #992: the bounded statement returns what the
 * previous window statement returned on every row shape (A1), and its plan finds the feeds by probes of the unique key
 * and stays inside the brief's work bound at 10,000 and at 100,000 deliveries (A2).
 */
class LakeFeedLatenessIntegrationTest {
    private val h = LakeProfilesHarness()
    private val lateness = LakeFeedLateness(h.definitions.named, h.profiles)

    /** The catalog's clock read once per case: every fixture time is this minus its age, so gaps are exact. */
    private lateinit var anchor: Instant

    @BeforeEach
    fun reset() {
        h.reset()
        anchor = LakeTestDb.leases.catalogNowPlus(Duration.ZERO)
    }

    @Test
    fun `F5 - two feeds with different rhythms on one table are judged separately at the same now`() {
        val table = h.table("orders")
        // hourly: published every hour, the last 3 h ago → late at factor 2. weekly: every 7 days, the last 3 h ago.
        rhythm(table, "hourly", every = Duration.ofHours(1), lastAgo = Duration.ofHours(3), count = 8)
        rhythm(table, "weekly", every = Duration.ofDays(7), lastAgo = Duration.ofHours(3), count = 8)
        // FAILED and UNKNOWN deliveries, recent ones, beside the hourly feed: they add no interval and no "last".
        deliver(table, "hourly", "FAILED", publishedAgo = null, id = "failed-1")
        deliver(table, "hourly", "UNKNOWN", publishedAgo = null, id = "unknown-1")
        deliver(table, "hourly", "FAILED", publishedAgo = Duration.ofMinutes(1), id = "failed-stamped")
        // A feed with FAILED rows only, and one whose one PUBLISHED row has no published_at.
        deliver(table, "failed_only", "FAILED", publishedAgo = null, id = "f-1")
        deliver(table, "failed_only", "FAILED", publishedAgo = null, id = "f-2")
        deliver(table, "unstamped", "PUBLISHED", publishedAgo = null, id = "u-1")

        val before = LakeTestDb.leases.catalogNowPlus(Duration.ZERO)
        val result = lateness.forTable(table)
        val after = LakeTestDb.leases.catalogNowPlus(Duration.ZERO)

        result.status shouldBe LakeTableStatus.ACTIVE
        (!result.computedAt.isBefore(before) && !result.computedAt.isAfter(after)) shouldBe true
        result.feeds.map { it.feed } shouldContainExactly listOf("failed_only", "hourly", "unstamped", "weekly")
        val byFeed = result.feeds.associate { it.feed to it.verdict }
        byFeed.getValue("hourly").shouldBeInstanceOf<FeedVerdict.Late>().usualInterval shouldBe Duration.ofHours(1)
        byFeed.getValue("weekly").shouldBeInstanceOf<FeedVerdict.OnTime>().usualInterval shouldBe Duration.ofDays(7)
        byFeed.getValue("failed_only") shouldBe FeedVerdict.NotEnoughDeliveries(0)
        byFeed.getValue("unstamped") shouldBe FeedVerdict.NotEnoughDeliveries(0)
    }

    @Test
    fun `F5 - the hourly feed's since-last is measured from its last PUBLISHED delivery, not a later failed one`() {
        val table = h.table("orders")
        rhythm(table, "hourly", every = Duration.ofHours(1), lastAgo = Duration.ofHours(3), count = 8)
        deliver(table, "hourly", "FAILED", publishedAgo = Duration.ofMinutes(1), id = "failed-stamped")
        val late = onlyVerdict(table).shouldBeInstanceOf<FeedVerdict.Late>()
        withClue("since-last ${late.sinceLast}") { (late.sinceLast >= Duration.ofHours(3)) shouldBe true }
    }

    @Test
    fun `F5 - a table with no delivery returns no feed, its status and the computed-at time`() {
        val table = h.table("orders")
        val result = lateness.forTable(table)
        result.tableId shouldBe table
        result.status shouldBe LakeTableStatus.ACTIVE
        result.feeds.shouldBeEmpty()
        (Duration.between(result.computedAt, LakeTestDb.leases.catalogNowPlus(Duration.ZERO)) < Duration.ofMinutes(1)) shouldBe true
    }

    @Test
    fun `F6 - a deprecated, an archived and a purged table are never flagged`() {
        for (status in LakeFeedLateness.NEVER_JUDGED) {
            val table = h.table("t_${status.wire}")
            rhythm(table, "hourly", every = Duration.ofHours(1), lastAgo = Duration.ofDays(30), count = 8)
            deliver(table, "failed_only", "FAILED", publishedAgo = null, id = "f-1")
            setStatus(table, status)
            val result = lateness.forTable(table)
            withClue(status) {
                result.status shouldBe status
                result.feeds.map { it.feed } shouldContainExactly listOf("failed_only", "hourly")
                result.feeds.forEach { it.verdict shouldBe FeedVerdict.NotJudged(status.wire) }
            }
        }
    }

    @Test
    fun `F6 - a table moved to a profile with another factor is judged by the new factor at the next read`() {
        val table = h.table("orders")
        rhythm(table, "hourly", every = Duration.ofHours(1), lastAgo = Duration.ofHours(3), count = 8)
        onlyVerdict(table).shouldBeInstanceOf<FeedVerdict.Late>()

        h.profiles.create("patient", document("reporting.lateness_overdue_factor" to "4"), h.actor)
        h.profiles.assign(table, "patient", h.actor)
        onlyVerdict(table).shouldBeInstanceOf<FeedVerdict.OnTime>()

        h.profiles.assign(table, "production", h.actor)
        onlyVerdict(table).shouldBeInstanceOf<FeedVerdict.Late>()
    }

    @Test
    fun `F6 - the sample and the minimum come from the table's profile`() {
        val table = h.table("orders")
        // 20 daily deliveries, then 3 hourly ones, the last 3 h ago: a sample of 4 sees only the hourly rhythm.
        rhythm(table, "feed", every = Duration.ofDays(1), lastAgo = Duration.ofDays(1).plusHours(5), count = 20)
        rhythm(table, "feed", every = Duration.ofHours(1), lastAgo = Duration.ofHours(3), count = 3, prefix = "h")
        onlyVerdict(table).shouldBeInstanceOf<FeedVerdict.OnTime>().usualInterval shouldBe Duration.ofDays(1)

        h.profiles.create(
            "short",
            document("reporting.lateness_sample" to "4", "reporting.lateness_min_deliveries" to "4"),
            h.actor,
        )
        h.profiles.assign(table, "short", h.actor)
        onlyVerdict(table).shouldBeInstanceOf<FeedVerdict.Late>().usualInterval shouldBe Duration.ofHours(1)

        h.profiles.create("demanding", document("reporting.lateness_sample" to "200", "reporting.lateness_min_deliveries" to "50"), h.actor)
        h.profiles.assign(table, "demanding", h.actor)
        lateness
            .forTable(table)
            .feeds
            .single()
            .verdict shouldBe FeedVerdict.NotEnoughDeliveries(23)
    }

    @Test
    fun `F6 - the profile is reached through LakeProfiles alone and an unknown table is not found`() {
        shouldThrow<DatapipelinesException> { lateness.forTable(UUID.randomUUID()) }
            .code shouldBe PipelineErrorCodes.Lake.DEFINITION_NOT_FOUND
        val source = File(LakeTestDb.repoFile("modules/lake/src/main/kotlin/co/datapipelines/lake/reporting"), "LakeFeedLateness.kt")
        val text = source.readText()
        text shouldContain "profiles.forTable(tableId)"
        text shouldNotContain "values_json"
        text shouldNotContain "LakeProfileDocument"
        text shouldNotContain "lake_profiles"
    }

    @Test
    fun `F6 - EXPLAIN shows the feeds probed on the unique key and V62's index serving the samples, inside the work bound`() {
        val table = h.table("orders")
        val other = h.table("returns")
        bulk(other, rows = 30_000)

        val indexDef =
            h.jdbc.queryForObject("SELECT indexdef FROM pg_indexes WHERE indexname = 'idx_lake_deliveries_published'", String::class.java)
        indexDef shouldBe
            "CREATE INDEX idx_lake_deliveries_published ON public.lake_deliveries USING btree (table_id, feed, published_at DESC) " +
            "WHERE (state = 'PUBLISHED'::text)"

        // The same table at two sizes, 30,000 deliveries of another table beside it both times (brief 992 A2).
        bulk(table, rows = 10_000)
        val small = measure(table, "10,000 deliveries on the table, 30,000 on another")
        bulk(table, rows = 90_000, first = 10_001)
        val large = measure(table, "100,000 deliveries on the table, 30,000 on another")

        assertSoftly {
            for (work in listOf(small, large)) {
                withClue(work.report) {
                    work.feeds shouldBe BULK_FEEDS.toLong()
                    work.rows shouldBeLessThanOrEqual rowsLimit(work.feeds, SAMPLE.toLong())
                    work.buffers shouldBeLessThanOrEqual buffersLimit(work.feeds, SAMPLE.toLong())
                    work.seqScans shouldBe 0
                    work.indexes shouldContain "uq_lake_deliveries_key"
                    work.indexes shouldContain "idx_lake_deliveries_published"
                }
            }
        }
        val result = lateness.forTable(table)
        result.feeds.map { it.feed } shouldContainExactly (0 until BULK_FEEDS).map { "feed_$it" }
    }

    @Test
    fun `992 - the work bound's formulas and counters classify the orchestrator's three probe plans as the brief does`() {
        rowsLimit(5, 20) shouldBe 106
        buffersLimit(5, 20) shouldBe 293
        rowsLimit(6, 20) shouldBe 127
        buffersLimit(6, 20) shouldBe 346
        // The relation nodes and the top node's buffers of recursive.out, current.out and lateral.out, transcribed.
        val recursive = probePlan(37 to 17, scan(1, 0, 1), scan(1, 0, 6), scan(17, 0, 6))
        val current = probePlan(6528 to 37797, scan(1_000_000, 0, 1), scan(335_000, 66_667, 3))
        val lateral = probePlan(3914 to 30529, scan(335_000, 66_667, 3), scan(17, 0, 6))
        (rowsOf(recursive) to buffersOf(recursive)) shouldBe (109L to 54L)
        (rowsOf(current) to buffersOf(current)) shouldBe (2_205_001L to 44_325L)
        (rowsOf(lateral) to buffersOf(lateral)) shouldBe (1_205_103L to 34_443L)
        rowsOf(recursive) shouldBeLessThanOrEqual rowsLimit(6, 20)
        buffersOf(recursive) shouldBeLessThanOrEqual buffersLimit(6, 20)
        for (outside in listOf(current, lateral)) {
            rowsOf(outside) shouldBeGreaterThan rowsLimit(6, 20)
            buffersOf(outside) shouldBeGreaterThan buffersLimit(6, 20)
        }
    }

    @Test
    fun `992 - the bounded statement returns what the window statement returned, on every row shape`() {
        val table = h.table("orders")
        val other = h.table("returns")
        val hour = Duration.ofHours(1)
        rhythm(table, "more", every = hour, lastAgo = hour, count = SAMPLE + 5)
        deliver(table, "more", "FAILED", publishedAgo = Duration.ofMinutes(1), id = "more-failed")
        deliver(table, "more", "UNKNOWN", publishedAgo = null, id = "more-unknown")
        rhythm(table, "fewer", every = hour, lastAgo = hour, count = SAMPLE - 7)
        rhythm(table, "exact", every = hour, lastAgo = hour, count = SAMPLE)
        deliver(table, "failed_only", "FAILED", publishedAgo = null, id = "f-1")
        deliver(table, "failed_only", "FAILED", publishedAgo = Duration.ofMinutes(5), id = "f-2")
        deliver(table, "unstamped", "PUBLISHED", publishedAgo = null, id = "u-1")
        // The mixed feed: more than SAMPLE stamped PUBLISHED rows and one unstamped one, which sorts first DESC.
        rhythm(table, "mixed", every = hour, lastAgo = hour, count = SAMPLE + 3)
        deliver(table, "mixed", "PUBLISHED", publishedAgo = null, id = "mixed-unstamped")
        rhythm(other, "more", every = hour, lastAgo = Duration.ofMinutes(30), count = SAMPLE + 2)
        rhythm(other, "other_only", every = hour, lastAgo = hour, count = 3)
        val empty = h.table("empty")

        // Non-vacuity: the ledger really holds every shape.
        val shapes = shapeCounts(table)
        shapes.getValue("more").stamped shouldBeGreaterThan SAMPLE.toLong()
        shapes.getValue("fewer").stamped shouldBeLessThan SAMPLE.toLong()
        shapes.getValue("exact").stamped shouldBe SAMPLE.toLong()
        shapes.getValue("failed_only") shouldBe Shape(total = 2, stamped = 0, unstamped = 0)
        shapes.getValue("unstamped") shouldBe Shape(total = 1, stamped = 0, unstamped = 1)
        shapes.getValue("mixed").stamped shouldBeGreaterThan SAMPLE.toLong()
        shapes.getValue("mixed").unstamped shouldBe 1
        shapeCounts(other).keys shouldBe setOf("more", "other_only")

        for (id in listOf(table, other, empty)) {
            val previous = statementRows(PREVIOUS_READ_SQL, id)
            val bounded = statementRows(LakeFeedLateness.READ_SQL, id)
            withClue("table $id: only previous ${previous - bounded.toSet()}; only bounded ${bounded - previous.toSet()}") {
                bounded shouldBe previous
            }
        }
        statementRows(LakeFeedLateness.READ_SQL, table).size shouldBe 3 * SAMPLE + (SAMPLE - 7) + 2
        statementRows(LakeFeedLateness.READ_SQL, empty) shouldBe listOf("active|null|null")
        statementRows(LakeFeedLateness.READ_SQL, UUID.randomUUID()).shouldBeEmpty()
    }

    /** The verdict of [table]'s one feed, read now. */
    private fun onlyVerdict(table: UUID): FeedVerdict {
        val feeds = lateness.forTable(table).feeds
        return feeds.single().verdict
    }

    /** [count] PUBLISHED deliveries of [feed], [every] apart, the newest [lastAgo] before [anchor]. */
    private fun rhythm(
        table: UUID,
        feed: String,
        every: Duration,
        lastAgo: Duration,
        count: Int,
        prefix: String = "d",
    ) = repeat(count) { n -> deliver(table, feed, "PUBLISHED", lastAgo.plus(every.multipliedBy(n.toLong())), "$prefix-$n") }

    private fun deliver(
        table: UUID,
        feed: String,
        state: String,
        publishedAgo: Duration?,
        id: String,
    ) {
        h.jdbc.update(
            "INSERT INTO lake_deliveries (id, table_id, feed, delivery_id, fingerprint, state, operation_id, execution_id, " +
                "pipeline_id, published_at) VALUES (gen_random_uuid(), ?, ?, ?, repeat('a', 64), ?, gen_random_uuid(), " +
                "gen_random_uuid(), gen_random_uuid(), ?)",
            table,
            feed,
            id,
            state,
            publishedAgo?.let { java.sql.Timestamp.from(anchor.minus(it)) },
        )
    }

    /** [rows] deliveries (delivery ids from [first]) over [BULK_FEEDS] feeds, nine in ten PUBLISHED an hour apart, the rest FAILED. */
    private fun bulk(
        table: UUID,
        rows: Int,
        first: Int = 1,
    ) {
        h.jdbc.update(
            "INSERT INTO lake_deliveries (id, table_id, feed, delivery_id, fingerprint, state, operation_id, execution_id, " +
                "pipeline_id, published_at) SELECT gen_random_uuid(), ?, 'feed_' || (g % $BULK_FEEDS), 'd-' || g, repeat('a', 64), " +
                "CASE WHEN g % 10 = 0 THEN 'FAILED' ELSE 'PUBLISHED' END, gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), " +
                "CASE WHEN g % 10 = 0 THEN NULL ELSE now() - make_interval(hours => g) END FROM generate_series(?, ?) g",
            table,
            first,
            first + rows - 1,
        )
    }

    /** The statement's rows for [table], each `status|feed|published_at`, sorted: a multiset as a list. */
    private fun statementRows(
        sql: String,
        table: UUID,
    ): List<String> =
        h.definitions.named
            .query(sql, mapOf("table" to table, "sample" to SAMPLE)) { rs, _ ->
                "${rs.getString("status")}|${rs.getString("feed")}|${rs.getString("published_at")}"
            }.sorted()

    private data class Shape(
        val total: Long,
        val stamped: Long,
        val unstamped: Long,
    )

    /** Per feed of [table]: every delivery, the PUBLISHED ones with a `published_at`, and the PUBLISHED ones without. */
    private fun shapeCounts(table: UUID): Map<String, Shape> =
        h.jdbc
            .query(
                "SELECT feed, count(*) AS total, " +
                    "count(*) FILTER (WHERE state = 'PUBLISHED' AND published_at IS NOT NULL) AS stamped, " +
                    "count(*) FILTER (WHERE state = 'PUBLISHED' AND published_at IS NULL) AS unstamped " +
                    "FROM lake_deliveries WHERE table_id = ? GROUP BY feed",
                { rs, _ -> rs.getString("feed") to Shape(rs.getLong("total"), rs.getLong("stamped"), rs.getLong("unstamped")) },
                table,
            ).toMap()

    /** A2's counters of one plan; [feeds] is F counted on the seed, [report] every figure they came from. */
    private data class PlanWork(
        val feeds: Long,
        val rows: Long,
        val buffers: Long,
        val seqScans: Int,
        val indexes: Set<String>,
        val report: String,
    )

    /** `VACUUM ANALYZE`, then the product statement's plan through the named-parameter template, as `forTable` runs it. */
    private fun measure(
        table: UUID,
        label: String,
    ): PlanWork {
        h.jdbc.execute("VACUUM ANALYZE lake_deliveries")
        val feeds =
            h.jdbc.queryForObject(
                "SELECT count(DISTINCT feed) FROM lake_deliveries WHERE table_id = ?",
                Long::class.javaObjectType,
                table,
            )
        val json =
            h.definitions.named.queryForObject(
                "EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) " + LakeFeedLateness.READ_SQL,
                mapOf("table" to table, "sample" to SAMPLE),
                String::class.java,
            )
        val plan = MAPPER.readTree(json)[0]["Plan"]
        val relations = nodes(plan).filter { it.path("Relation Name").asText() == "lake_deliveries" }
        val f = requireNotNull(feeds)
        val s = SAMPLE.toLong()
        val report =
            buildString {
                appendLine("EXPLAIN LakeFeedLateness.READ_SQL ($label): F=$f S=$s")
                for (node in relations) {
                    appendLine(
                        "  ${node.path("Node Type").asText()} ${node.path("Index Name").asText("-")}: rows=${node.num("Actual Rows")} " +
                            "removedByFilter=${node.num("Rows Removed by Filter")} " +
                            "removedByRecheck=${node.num("Rows Removed by Index Recheck")} loops=${node.num("Actual Loops")} " +
                            "heapFetches=${node.num("Heap Fetches")} -> ${nodeRows(node)}",
                    )
                }
                appendLine("  R=${rowsOf(plan)} limit F*S+F+1=${rowsLimit(f, s)}")
                appendLine("  B=${buffersOf(plan)} limit (2F+1)(H+2)+F*S+(F*S+F+1)+(H+1)+16=${buffersLimit(f, s)} (H=$H)")
                append(json)
            }
        println(report)
        return PlanWork(
            feeds = f,
            rows = rowsOf(plan),
            buffers = buffersOf(plan),
            seqScans = relations.count { it.path("Node Type").asText().contains("Seq Scan") },
            indexes = relations.mapNotNull { it.get("Index Name")?.asText() }.toSet(),
            report = report,
        )
    }

    private fun setStatus(
        table: UUID,
        status: LakeTableStatus,
    ) {
        val stamps =
            when (status) {
                LakeTableStatus.DEPRECATED -> "deprecated_at = now(), deprecated_by = ?"
                LakeTableStatus.ARCHIVED -> "archived_at = now(), archived_by = ?"
                LakeTableStatus.PURGED -> "purged_at = now(), purged_by = ?"
                LakeTableStatus.ACTIVE -> error("not a never-judged status")
            }
        h.jdbc.update("UPDATE lake_tables_managed SET status = ?, $stamps WHERE id = ?", status.wire, h.actor, table) shouldBe 1
    }

    private companion object {
        const val SAMPLE = 20
        const val BULK_FEEDS = 5

        /** The allowance for one index descent in A2's buffer budget: the orchestrator's choice, never adjusted. */
        const val H = 4L

        val MAPPER = ObjectMapper()

        /** A2's row limit: F × S sample entries, and F + 1 probes of the feed walk. */
        fun rowsLimit(
            f: Long,
            s: Long,
        ): Long = f * s + f + 1

        /** A2's buffer limit, the brief's formula term by term (allowances with slack, not proven maxima). */
        fun buffersLimit(
            f: Long,
            s: Long,
        ): Long = (2 * f + 1) * (H + 2) + f * s + rowsLimit(f, s) + (H + 1) + 16

        /** Every node of the plan, at any depth (subplans, CTEs and init plans are children under "Plans"). */
        fun nodes(node: JsonNode): List<JsonNode> = listOf(node) + node.path("Plans").flatMap { nodes(it) }

        fun JsonNode.num(key: String): Double = path(key).asDouble(0.0)

        /** R of one node: (actual + removed by filter + removed by recheck) × loops, all three per-loop figures. */
        fun nodeRows(node: JsonNode): Long =
            if (node.path("Relation Name").asText() != "lake_deliveries") {
                0
            } else {
                Math.round(
                    (node.num("Actual Rows") + node.num("Rows Removed by Filter") + node.num("Rows Removed by Index Recheck")) *
                        node.num("Actual Loops"),
                )
            }

        /** R of a plan: the sum over every `lake_deliveries` node at any depth. */
        fun rowsOf(plan: JsonNode): Long = nodes(plan).sumOf { nodeRows(it) }

        /** B of a plan: the TOP node's shared hit + read (it includes its children's). */
        fun buffersOf(plan: JsonNode): Long = plan.path("Shared Hit Blocks").asLong() + plan.path("Shared Read Blocks").asLong()

        /** A `lake_deliveries` scan node as EXPLAIN JSON reports it. */
        fun scan(
            rows: Long,
            removedByFilter: Long,
            loops: Long,
        ): String =
            """{"Node Type": "Scan", "Relation Name": "lake_deliveries", "Actual Rows": $rows, """ +
                """"Rows Removed by Filter": $removedByFilter, "Actual Loops": $loops}"""

        /** A plan whose top node has [buffers] (hit to read) and the [scans] nested one level down each. */
        fun probePlan(
            buffers: Pair<Int, Int>,
            vararg scans: String,
        ): JsonNode =
            MAPPER.readTree(
                """{"Node Type": "Sort", "Shared Hit Blocks": ${buffers.first}, "Shared Read Blocks": ${buffers.second}, """ +
                    """"Plans": [{"Node Type": "Nested Loop", "Plans": [${scans.joinToString()}]}]}""",
            )

        /** The statement before #992, kept to prove the bounded one returns the same rows (brief 992 A1). */
        val PREVIOUS_READ_SQL =
            """
            WITH t AS (
                SELECT status, now() AS computed_at FROM lake_tables_managed WHERE id = :table
            ),
            feeds AS (
                SELECT DISTINCT feed FROM lake_deliveries WHERE table_id = :table
            ),
            samples AS (
                SELECT feed, published_at FROM (
                    SELECT feed, published_at,
                           row_number() OVER (PARTITION BY feed ORDER BY published_at DESC) AS n
                    FROM lake_deliveries
                    WHERE table_id = :table AND state = 'PUBLISHED' AND published_at IS NOT NULL
                ) ranked
                WHERE n <= :sample
            )
            SELECT t.status, t.computed_at, f.feed, s.published_at
            FROM t
            LEFT JOIN feeds f ON TRUE
            LEFT JOIN samples s ON s.feed = f.feed
            ORDER BY f.feed, s.published_at DESC
            """.trimIndent()
    }
}
