package co.datapipelines.pipeline

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.random.Random

/**
 * #598a — the chart group's ONE statement against the shared migrated Postgres: `OverviewE2eTest`'s
 * lifecycle fixture plus two mature pipelines, counted unlensed and through a narrowed lens, and a
 * seeded random workspace checked against a Kotlin oracle. A discarded release is not a release:
 * `b/released_discarded` is Live with ONE release, though its discarded row carries a `released_at`
 * (this fixture's insert stamps every non-draft row, as a release later discarded does).
 *
 * #598b — the activity chart at a fixed mid-week [NOW] (a Wednesday): the week boundaries at the
 * Monday's midnight UTC, the twelve-week edge, the discarded release, the inclusive 14-day edge of
 * actively developed, the narrowed lens, a session time zone far from UTC, and the random oracle
 * with timestamps across fourteen weeks.
 */
class ChartInventorySourceIntegrationTest {
    private val pool = SharedPostgres.pooledDataSource()
    private val jdbc = NamedParameterJdbcTemplate(pool)
    private val workspace = UUID.randomUUID()
    private val otherWorkspace = UUID.randomUUID()
    private val actor = UUID.randomUUID()

    @BeforeEach
    fun seedContext() {
        jdbc.update(
            "INSERT INTO users (id, email, display_name, provider, provider_subject) VALUES (:id, :email, 'Charts', 'google', :email)",
            mapOf("id" to actor, "email" to "$actor@charts.test"),
        )
        listOf(workspace, otherWorkspace).forEach { id ->
            jdbc.update(
                "INSERT INTO workspaces (id, name, display_name) VALUES (:id, :name, 'Charts')",
                mapOf("id" to id, "name" to "charts_$id"),
            )
        }
    }

    @AfterEach
    fun cleanOwnRows() {
        val params = mapOf("ids" to listOf(workspace, otherWorkspace))
        jdbc.update("DELETE FROM pipelines WHERE workspace_id IN (:ids)", params)
        jdbc.update("DELETE FROM workspaces WHERE id IN (:ids)", params)
        jdbc.update("DELETE FROM users WHERE id = :id", mapOf("id" to actor))
    }

    private fun seedFixture() {
        insert("a/draft", listOf("DRAFT"))
        insert("a/discarded_draft", listOf("DISCARDED", "DRAFT"))
        insert("b/released", listOf("RELEASED"))
        insert("b/released_twice", listOf("RELEASED", "RELEASED"))
        insert("b/released_discarded", listOf("RELEASED", "DISCARDED"))
        insert("c/both", listOf("RELEASED", "DRAFT"))
        insert("c/both_hidden_draft", listOf("RELEASED", "DRAFT"))
        insert("d/discarded", listOf("DISCARDED"))
        insert("d/discarded_twice", listOf("DISCARDED", "DISCARDED"))
        insert("m/four", List(FOUR) { "RELEASED" })
        insert("m/ten_draft", List(TEN) { "RELEASED" } + "DRAFT")
        insert("b/released", List(TEN) { "RELEASED" } + "DRAFT", otherWorkspace)
    }

    @Test
    fun `unlensed, every object is in one lifecycle part and one release bin`() {
        seedFixture()
        val charts = aggregate(ReadLens.Everything, Instant.now())
        println("event=598a.charts lens=everything $charts")

        charts.lifecycle shouldBe Inventory(draftOnly = 2, releasedOnly = 4, releasedWithDraft = 3, discardedOnly = 2)
        charts.maturity.associate { it.releases to (it.draftOpen to it.noDraft) } shouldBe
            mapOf(
                ReleaseBin.ZERO to (2L to 2L),
                ReleaseBin.ONE to (2L to 2L),
                ReleaseBin.TWO_TO_THREE to (0L to 1L),
                ReleaseBin.FOUR_TO_NINE to (0L to 1L),
                ReleaseBin.TEN_PLUS to (1L to 0L),
            )
        charts.lifecycle.objects shouldBe 11L
        charts.maturity.sumOf { it.total } shouldBe charts.lifecycle.objects
        withClue("the chart's lifecycle is the four-bucket inventory's, bucket for bucket") {
            charts.lifecycle shouldBe InventorySource.aggregate(jdbc, NavigationFamily.PIPELINES, workspace, ReadLens.Everything)
        }
        withClue("every row was stamped just now: 20 standing releases, 11 objects created, 5 drafts written") {
            charts.activity.weeks.sumOf { it.released } shouldBe 20L
            charts.activity.weeks.sumOf { it.created } shouldBe 11L
            charts.activity.activelyDeveloped shouldBe charts.lifecycle.withDrafts
        }
    }

    /** A promoter's lens: the admitted names' RELEASED rows only, so A, C and D are zero and no bin has a draft. */
    @Test
    fun `through a narrowed lens, only released rows are admitted and no draft is counted`() {
        seedFixture()
        val lens = ReadLens.Only(setOf("b/released", "b/released_twice", "c/both_hidden_draft"))
        val charts = aggregate(lens, Instant.now())
        println("event=598a.charts lens=narrowed $charts")

        charts.lifecycle shouldBe Inventory(draftOnly = 0, releasedOnly = 3, releasedWithDraft = 0, discardedOnly = 0)
        charts.maturity.associate { it.releases to (it.draftOpen to it.noDraft) } shouldBe
            mapOf(
                ReleaseBin.ZERO to (0L to 0L),
                ReleaseBin.ONE to (0L to 2L),
                ReleaseBin.TWO_TO_THREE to (0L to 1L),
                ReleaseBin.FOUR_TO_NINE to (0L to 0L),
                ReleaseBin.TEN_PLUS to (0L to 0L),
            )
        charts.maturity.sumOf { it.total } shouldBe charts.lifecycle.objects
        charts.lifecycle shouldBe InventorySource.aggregate(jdbc, NavigationFamily.PIPELINES, workspace, lens)
        charts.activity.weeks.sumOf { it.released } shouldBe 4L
        charts.activity.weeks.sumOf { it.created } shouldBe 3L
        charts.activity.activelyDeveloped shouldBe 0L
    }

    @Test
    fun `an empty workspace is all zeros and the foreign workspace is never counted`() {
        insert("b/released", listOf("RELEASED", "DRAFT"), otherWorkspace)

        aggregate(ReadLens.Everything, NOW) shouldBe ChartInventory.empty(NOW, ACTIVE_DAYS)
        aggregate(ReadLens.Only(setOf("b/released")), NOW) shouldBe ChartInventory.empty(NOW, ACTIVE_DAYS)
    }

    /** (1) and (2): a week runs from its Monday's midnight UTC; the oldest week is the eleventh before now's, and no earlier. */
    @Test
    fun `releases land in the UTC week of their released_at, and only the twelve weeks are counted`() {
        val created = NOW.minus(Duration.ofDays(OUT_OF_RANGE_DAYS))
        insert("w/monday_midnight", listOf("RELEASED"), createdAt = created, releasedAt = listOf(at("2026-10-12T00:00:00Z")))
        insert("w/sunday_before", listOf("RELEASED"), createdAt = created, releasedAt = listOf(at("2026-10-11T23:59:59Z")))
        insert("w/twelve_weeks_one_day", listOf("RELEASED"), createdAt = created, releasedAt = listOf(NOW.minus(Duration.ofDays(85))))
        insert("w/before_oldest", listOf("RELEASED"), createdAt = created, releasedAt = listOf(at("2026-07-26T23:59:59Z")))
        insert("w/oldest_monday", listOf("RELEASED"), createdAt = created, releasedAt = listOf(at("2026-07-27T00:00:00Z")))
        insert("w/oldest_sunday", listOf("RELEASED"), createdAt = created, releasedAt = listOf(at("2026-08-02T23:59:59Z")))

        val activity = aggregate(ReadLens.Everything, NOW).activity
        println("event=598b.activity.boundaries ${activity.weeks}")

        activity.weeks.first().weekStart shouldBe LocalDate.parse("2026-07-27")
        activity.weeks.last().weekStart shouldBe LocalDate.parse("2026-10-12")
        activity.released() shouldBe
            mapOf(LocalDate.parse("2026-07-27") to 2L, LocalDate.parse("2026-10-05") to 1L, LocalDate.parse("2026-10-12") to 1L)
        activity.weeks.sumOf { it.created } shouldBe 0L
    }

    /** (3): a release since discarded is not counted, in its week or anywhere. */
    @Test
    fun `a discarded release is not counted`() {
        insert("w/discarded", listOf("DISCARDED"), createdAt = at("2026-10-12T09:00:00Z"), releasedAt = listOf(at("2026-10-13T09:00:00Z")))
        insert("w/kept", listOf("RELEASED", "DISCARDED"), releasedAt = listOf(at("2026-10-13T09:00:00Z"), at("2026-10-13T10:00:00Z")))

        val activity = aggregate(ReadLens.Everything, NOW).activity

        activity.released() shouldBe mapOf(LocalDate.parse("2026-10-12") to 1L)
        activity.weeks.last().created shouldBe 1L
    }

    /** (4): the draft written exactly [ACTIVE_DAYS] days before now counts; one second earlier does not. */
    @Test
    fun `actively developed is inclusive at the window's edge`() {
        val edge = NOW.minus(Duration.ofDays(ACTIVE_DAYS.toLong()))
        insert("a/at_edge", listOf("DRAFT"), draftWrittenAt = edge)
        insert("a/before_edge", listOf("DRAFT"), draftWrittenAt = edge.minusSeconds(1))
        insert("a/released_before_edge", listOf("RELEASED", "DRAFT"), draftWrittenAt = edge.minusSeconds(1))
        insert("a/never_restamped", listOf("DRAFT"), versionCreatedAt = edge.plusSeconds(1))
        insert("a/never_restamped_old", listOf("DRAFT"), versionCreatedAt = edge.minusSeconds(1))

        val charts = aggregate(ReadLens.Everything, NOW)

        withClue("at_edge and never_restamped (its write stamp is the row's created_at) count; the rest are a second too old") {
            charts.activity.activelyDeveloped shouldBe 2L
        }
        aggregate(ReadLens.Everything, NOW, activeDays = 1).activity.activelyDeveloped shouldBe 0L
        aggregate(ReadLens.Everything, edge.minusSeconds(1).plus(Duration.ofDays(ACTIVE_DAYS.toLong())))
            .activity.activelyDeveloped shouldBe 5L
    }

    /** (5): through a narrowed lens no draft is admitted, and a draft-only object is not a new one. */
    @Test
    fun `through a narrowed lens nobody is actively developing and only admitted objects are new`() {
        val thisWeek = at("2026-10-13T09:00:00Z")
        insert("n/draft_only", listOf("DRAFT"), createdAt = thisWeek, draftWrittenAt = thisWeek)
        insert("n/released", listOf("RELEASED", "DRAFT"), createdAt = thisWeek, releasedAt = listOf(thisWeek), draftWrittenAt = thisWeek)
        insert("n/hidden", listOf("RELEASED"), createdAt = thisWeek, releasedAt = listOf(thisWeek))

        val everything = aggregate(ReadLens.Everything, NOW).activity
        val narrowed = aggregate(ReadLens.Only(setOf("n/draft_only", "n/released")), NOW).activity

        everything.weeks.last() shouldBe WeekActivity(LocalDate.parse("2026-10-12"), released = 2, created = 3)
        everything.activelyDeveloped shouldBe 2L
        narrowed.weeks.last() shouldBe WeekActivity(LocalDate.parse("2026-10-12"), released = 1, created = 1)
        narrowed.activelyDeveloped shouldBe 0L
    }

    /** (6): the session's time zone never moves a week boundary — the Monday-00:00Z release stays in the newest week. */
    @Test
    fun `a session in Los Angeles buckets the same UTC weeks`() {
        val monday = at("2026-10-12T00:00:00Z")
        val sunday = at("2026-10-11T23:59:59Z")
        insert("z/monday_midnight", listOf("RELEASED"), createdAt = monday, releasedAt = listOf(monday))
        insert("z/sunday_before", listOf("RELEASED"), createdAt = sunday, releasedAt = listOf(sunday))
        val utc = aggregate(ReadLens.Everything, NOW)

        val connection = pool.connection
        try {
            val session = NamedParameterJdbcTemplate(SingleConnectionDataSource(connection, true))
            session.jdbcTemplate.execute("SET TIME ZONE 'America/Los_Angeles'")
            session.jdbcTemplate.queryForObject("SHOW TIME ZONE", String::class.java) shouldBe "America/Los_Angeles"
            val losAngeles =
                ChartInventorySource.aggregate(session, NavigationFamily.PIPELINES, workspace, ReadLens.Everything, NOW, ACTIVE_DAYS)
            println("event=598b.activity.session_zone zone=America/Los_Angeles ${losAngeles.activity.weeks.takeLast(2)}")

            losAngeles.activity.weeks.last() shouldBe WeekActivity(LocalDate.parse("2026-10-12"), released = 1, created = 1)
            losAngeles.activity.weeks[Activity.WEEKS - 2] shouldBe WeekActivity(LocalDate.parse("2026-10-05"), released = 1, created = 1)
            losAngeles shouldBe utc
        } finally {
            connection.createStatement().use { it.execute("RESET TIME ZONE") }
            connection.close()
        }
    }

    /**
     * A seeded random workspace against a Kotlin oracle computed from the same status lists — the
     * oracle is the rule written out (admitted rows; d/r/x flags; releases = RELEASED rows), never
     * the statement under test. Both arms; the sum invariant holds on each. #598b: every row carries
     * a random stamp across fourteen weeks before [NOW], and the oracle re-counts the three activity
     * definitions over instants (`[Monday 00:00Z, next Monday)`), not over the statement's buckets.
     */
    @Test
    fun `a random workspace counts what the rule says, unlensed and narrowed`() {
        val random = Random(RANDOM_SEED)
        val objects =
            List(RANDOM_OBJECTS) { index ->
                val statuses =
                    List(random.nextInt(0, MAX_RELEASES + 1)) { "RELEASED" } +
                        List(random.nextInt(0, MAX_DISCARDS + 1)) { "DISCARDED" } +
                        (if (random.nextBoolean()) listOf("DRAFT") else emptyList())
                Seeded(
                    name = "r/p$index",
                    statuses = statuses.ifEmpty { listOf("DRAFT") }.shuffled(random),
                    createdAt = random.instant(),
                    releasedAt = List(statuses.size.coerceAtLeast(1)) { random.instant() },
                    draftWrittenAt = random.instant(),
                )
            }
        objects.forEach {
            insert(it.name, it.statuses, createdAt = it.createdAt, releasedAt = it.releasedAt, draftWrittenAt = it.draftWrittenAt)
        }
        val narrowed = objects.map { it.name }.filter { random.nextBoolean() }.toSet()

        listOf<ReadLens>(ReadLens.Everything, ReadLens.Only(narrowed)).forEach { lens ->
            val admitted =
                objects.mapNotNull { seeded ->
                    when (lens) {
                        is ReadLens.Only -> {
                            seeded.admitting { it == "RELEASED" }.takeIf { seeded.name in lens.names && it.statuses.isNotEmpty() }
                        }

                        else -> {
                            seeded
                        }
                    }
                }
            val expected = oracle(admitted)
            val actual = aggregate(lens, NOW)
            println("event=598a.charts.random lens=${if (lens is ReadLens.Only) "narrowed" else "everything"} $actual")
            actual shouldBe expected
            actual.maturity.sumOf { it.total } shouldBe actual.lifecycle.objects
        }
    }

    private fun oracle(admitted: List<Seeded>): ChartInventory {
        fun flags(statuses: List<String>) = Triple("DRAFT" in statuses, "RELEASED" in statuses, "DISCARDED" in statuses)
        val statuses = admitted.map { it.statuses }
        val lifecycle =
            Inventory(
                draftOnly = statuses.count { flags(it).let { (d, r, _) -> d && !r } }.toLong(),
                releasedOnly = statuses.count { flags(it).let { (d, r, _) -> r && !d } }.toLong(),
                releasedWithDraft = statuses.count { flags(it).let { (d, r, _) -> d && r } }.toLong(),
                discardedOnly = statuses.count { flags(it).let { (d, r, x) -> x && !d && !r } }.toLong(),
            )
        val bins =
            ReleaseBin.entries.map { bin ->
                val inBin = statuses.filter { bin.contains(it.count { status -> status == "RELEASED" }.toLong()) }
                MaturityBin(bin, draftOpen = inBin.count { "DRAFT" in it }.toLong(), noDraft = inBin.count { "DRAFT" !in it }.toLong())
            }
        val windowStart = NOW.minus(Duration.ofDays(ACTIVE_DAYS.toLong()))
        val weeks =
            Activity.weekStarts(NOW).map { monday ->
                val from = monday.atStartOfDay().toInstant(ZoneOffset.UTC)
                val until = from.plus(Duration.ofDays(DAYS_PER_WEEK))

                fun inWeek(at: Instant) = !at.isBefore(from) && at.isBefore(until)
                WeekActivity(
                    monday,
                    released = admitted.sumOf { seeded -> seeded.releases().count(::inWeek).toLong() },
                    created = admitted.count { inWeek(it.createdAt) }.toLong(),
                )
            }
        val active = admitted.count { "DRAFT" in it.statuses && !it.draftWrittenAt.isBefore(windowStart) }.toLong()
        return ChartInventory(lifecycle, bins, Activity(weeks, active, ACTIVE_DAYS))
    }

    /** One random object: its version statuses, its creation, each version's release stamp and its draft's last write. */
    private data class Seeded(
        val name: String,
        val statuses: List<String>,
        val createdAt: Instant,
        val releasedAt: List<Instant>,
        val draftWrittenAt: Instant,
    ) {
        /** This object as a lens admitting only the versions [keep] holds sees it, stamps kept in step. */
        fun admitting(keep: (String) -> Boolean): Seeded {
            val kept = statuses.indices.filter { keep(statuses[it]) }
            return copy(statuses = kept.map { statuses[it] }, releasedAt = kept.map { releasedAt[it] })
        }

        fun releases(): List<Instant> = statuses.indices.filter { statuses[it] == "RELEASED" }.map { releasedAt[it] }
    }

    private fun Random.instant(): Instant = NOW.minusSeconds(nextLong(0, RANDOM_SPAN_SECONDS))

    private fun Activity.released(): Map<LocalDate, Long> = weeks.filter { it.released > 0 }.associate { it.weekStart to it.released }

    private fun aggregate(
        lens: ReadLens,
        now: Instant,
        activeDays: Int = ACTIVE_DAYS,
    ): ChartInventory = ChartInventorySource.aggregate(jdbc, NavigationFamily.PIPELINES, workspace, lens, now, activeDays)

    /**
     * `OverviewE2eTest`'s insert: every non-draft row is stamped released, a discarded one discarded
     * too. #598b: [createdAt] is the object's creation (default now), [releasedAt] each version's
     * release stamp by index (default now), [draftWrittenAt] the DRAFT row's `updated_at` (default
     * NULL, as the fixture had it) and [versionCreatedAt] every version's `created_at` (default now).
     */
    @Suppress("LongParameterList")
    private fun insert(
        name: String,
        statuses: List<String>,
        workspaceId: UUID = workspace,
        createdAt: Instant? = null,
        releasedAt: List<Instant?> = emptyList(),
        draftWrittenAt: Instant? = null,
        versionCreatedAt: Instant? = null,
    ) {
        val id = UUID.randomUUID()
        val current = statuses.indexOfLast { it == "RELEASED" }.takeIf { it >= 0 }?.plus(1)
        jdbc.update(
            "INSERT INTO pipelines (id, workspace_id, name, display_name, current_version, owner_id, created_at) " +
                "VALUES (:id, :workspace, :name, :name, :current, :actor, COALESCE(CAST(:createdAt AS timestamptz), NOW()))",
            mapOf(
                "id" to id,
                "workspace" to workspaceId,
                "name" to name,
                "current" to current,
                "actor" to actor,
                "createdAt" to createdAt?.let(Timestamp::from),
            ),
        )
        statuses.forEachIndexed { index, status ->
            jdbc.update(
                "INSERT INTO pipeline_versions (pipeline_id, version, body_json, status, body_hash, created_by, created_at, " +
                    "released_at, released_by, discarded_at, discarded_by, updated_at) " +
                    "VALUES (:id, :version, CAST('{}' AS jsonb), :status, :hash, :actor, " +
                    "COALESCE(CAST(:versionCreatedAt AS timestamptz), NOW()), " +
                    "CASE WHEN :status <> 'DRAFT' THEN COALESCE(CAST(:releasedAt AS timestamptz), NOW()) END, " +
                    "CASE WHEN :status <> 'DRAFT' THEN :actor END, " +
                    "CASE WHEN :status = 'DISCARDED' THEN NOW() END, " +
                    "CASE WHEN :status = 'DISCARDED' THEN :actor END, " +
                    "CASE WHEN :status = 'DRAFT' THEN CAST(:draftWrittenAt AS timestamptz) END)",
                mapOf(
                    "id" to id,
                    "version" to index + 1,
                    "status" to status,
                    "hash" to "a".repeat(HASH_LENGTH),
                    "actor" to actor,
                    "versionCreatedAt" to versionCreatedAt?.let(Timestamp::from),
                    "releasedAt" to releasedAt.getOrNull(index)?.let(Timestamp::from),
                    "draftWrittenAt" to draftWrittenAt?.let(Timestamp::from),
                ),
            )
        }
    }

    private fun at(text: String): Instant = Instant.parse(text)

    private companion object {
        const val FOUR = 4
        const val TEN = 10
        const val HASH_LENGTH = 64
        const val RANDOM_SEED = 598
        const val RANDOM_OBJECTS = 60
        const val MAX_RELEASES = 12
        const val MAX_DISCARDS = 2
        const val ACTIVE_DAYS = 14
        const val DAYS_PER_WEEK = 7L
        const val OUT_OF_RANGE_DAYS = 200L

        /** Fourteen weeks back from [NOW]: two weeks older than the chart, so both edges are exercised. */
        const val RANDOM_SPAN_SECONDS = 14L * 7 * 24 * 3600

        /** A Wednesday: the newest week is Monday 2026-10-12, the oldest Monday 2026-07-27. */
        val NOW: Instant = Instant.parse("2026-10-14T12:00:00Z")
    }
}
