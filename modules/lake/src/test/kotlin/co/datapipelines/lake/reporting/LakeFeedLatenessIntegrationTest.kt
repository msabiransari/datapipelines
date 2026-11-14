package co.datapipelines.lake.reporting

import co.datapipelines.lake.LakeTestDb
import co.datapipelines.lake.definitions.LakeTableStatus
import co.datapipelines.lake.profiles.LakeProfileFixtures.document
import co.datapipelines.lake.profiles.LakeProfilesHarness
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
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
 * V62's index serving the read over a table with 10,000 deliveries.
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
    fun `F6 - EXPLAIN shows V62's index serving the read for a table with 10,000 deliveries`() {
        val table = h.table("orders")
        val other = h.table("returns")
        bulk(table, rows = 10_000)
        bulk(other, rows = 30_000)
        h.jdbc.execute("VACUUM ANALYZE lake_deliveries")

        val indexDef =
            h.jdbc.queryForObject("SELECT indexdef FROM pg_indexes WHERE indexname = 'idx_lake_deliveries_published'", String::class.java)
        indexDef shouldBe
            "CREATE INDEX idx_lake_deliveries_published ON public.lake_deliveries USING btree (table_id, feed, published_at DESC) " +
            "WHERE (state = 'PUBLISHED'::text)"

        val plan =
            h.definitions.named
                .query("EXPLAIN " + LakeFeedLateness.READ_SQL, mapOf("table" to table, "sample" to SAMPLE)) { rs, _ -> rs.getString(1) }
                .joinToString("\n")
        println("EXPLAIN LakeFeedLateness.READ_SQL (10,000 deliveries on the table, 30,000 on another)\n$plan")
        withClue(plan) {
            plan shouldContain "idx_lake_deliveries_published"
            plan shouldNotContain "Seq Scan on lake_deliveries"
        }
        val result = lateness.forTable(table)
        result.feeds.map { it.feed } shouldContainExactly (0 until BULK_FEEDS).map { "feed_$it" }
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

    /** [rows] deliveries over [BULK_FEEDS] feeds, nine in ten PUBLISHED an hour apart, the rest FAILED. */
    private fun bulk(
        table: UUID,
        rows: Int,
    ) {
        h.jdbc.update(
            "INSERT INTO lake_deliveries (id, table_id, feed, delivery_id, fingerprint, state, operation_id, execution_id, " +
                "pipeline_id, published_at) SELECT gen_random_uuid(), ?, 'feed_' || (g % $BULK_FEEDS), 'd-' || g, repeat('a', 64), " +
                "CASE WHEN g % 10 = 0 THEN 'FAILED' ELSE 'PUBLISHED' END, gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), " +
                "CASE WHEN g % 10 = 0 THEN NULL ELSE now() - make_interval(hours => g) END FROM generate_series(1, ?) g",
            table,
            rows,
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
    }
}
