package co.datapipelines.pipeline

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.random.Random

/**
 * #598a — the chart group's ONE statement, pinned at its text: the same `admitted` CTE as the
 * inventory's (bound workspace and lens, no body), the four lifecycle columns, and one pair of
 * columns per release bin whose range appears exactly once. #598b: the twelve week pairs and the
 * actively developed count, bucketed in UTC, bound to `:now` and `:active_days` only.
 */
class ChartInventorySourceTest {
    @ParameterizedTest
    @EnumSource(NavigationFamily::class)
    fun `the statement extends the inventory's admitted set and binds only the workspace, lens and clock`(family: NavigationFamily) {
        listOf(false, true).forEach { narrowed ->
            val sql = ChartInventorySource.sql(family, narrowed)
            println("family=$family narrowed=$narrowed\n$sql")
            sql shouldContain
                InventorySource.admitted(
                    family,
                    narrowed,
                    ", count(*) FILTER (WHERE v.status = 'RELEASED') AS releases" + ChartInventorySource.ACTIVITY,
                )
            sql shouldContain InventorySource.LIFECYCLE_COUNTS
            sql shouldContain "s.workspace_id = :workspace"
            sql shouldContain "GROUP BY s.id"
            listOf("body_json", "current_version", "LIMIT", "s.name,", "created_by", "email").forEach { sql shouldNotContain it }
            val bound = Regex(":[a-z_]+").findAll(sql).map { it.value }.toSet()
            bound shouldBe setOf(":workspace", ":now", ":active_days") + if (narrowed) setOf(":names") else emptySet()
            if (narrowed) sql shouldContain "AND s.name IN (:names) AND v.status = 'RELEASED'" else sql shouldNotContain "AND v.status"
            ReleaseBin.entries.forEach { bin ->
                Regex(Regex.escape("(WHERE ${bin.predicate} AND ")).findAll(sql).count() shouldBe 2
                sql shouldContain "${bin.predicate} AND d) AS ${bin.column}_draft"
                sql shouldContain "${bin.predicate} AND NOT d) AS ${bin.column}_no_draft"
            }
            withClue("every week bucket is taken in UTC, never in the session's time zone") {
                Regex(Regex.escape("date_trunc('week', ")).findAll(sql).count() shouldBe WEEK_BUCKETS
                Regex(Regex.escape(" AT TIME ZONE 'UTC')")).findAll(sql).count() shouldBe WEEK_BUCKETS
            }
            (0 until Activity.WEEKS).forEach { ago ->
                listOf("AS w${ago}_rel\\b", "AS w${ago}_released\\b", "AS w${ago}_created\\b", "sum\\(w${ago}_rel\\)").forEach { column ->
                    Regex(column).findAll(sql).count() shouldBe 1
                }
            }
            Regex("\\bAS active\\b").findAll(sql).count() shouldBe 1
            sql shouldContain "draft_written >= CAST(:now AS timestamptz) - make_interval(days => :active_days)"
            sql shouldContain "max(COALESCE(v.updated_at, v.created_at)) FILTER (WHERE v.status = 'DRAFT') AS draft_written"
        }
    }

    /**
     * #599 — four more families chart from this statement, so each one's text names ITS tables and
     * no other family's: the object table, its versions table and the join on its own foreign key.
     */
    @ParameterizedTest
    @EnumSource(NavigationFamily::class)
    fun `each family's statement reads its own object and version tables only`(family: NavigationFamily) {
        listOf(false, true).forEach { narrowed ->
            val sql = ChartInventorySource.sql(family, narrowed)
            val versions = family.table.removeSuffix("s") + "_versions"
            sql shouldContain "FROM ${family.table} s\n"
            sql shouldContain "JOIN $versions v ON v.${family.foreignKey} = s.id"
            NavigationFamily.entries.filter { it != family }.forEach { other ->
                withClue("$family's statement must not read $other's tables") {
                    Regex("\\b${other.table}\\b").containsMatchIn(sql) shouldBe false
                    sql shouldNotContain other.foreignKey
                }
            }
        }
    }

    /** Week 0 contains now; the Monday 00:00:00Z starts a week, the Sunday 23:59:59.999Z before it ends the last. */
    @Test
    fun `the twelve weeks are consecutive UTC Mondays ending with the one that contains now`() {
        val saturday = Instant.parse("2026-10-17T12:00:00Z")
        Activity.weekStarts(saturday).first() shouldBe LocalDate.parse("2026-07-27")
        Activity.weekStarts(saturday).last() shouldBe LocalDate.parse("2026-10-12")
        Activity.weekStarts(Instant.parse("2026-10-12T00:00:00Z")).last() shouldBe LocalDate.parse("2026-10-12")
        Activity.weekStarts(Instant.parse("2026-10-11T23:59:59.999Z")).last() shouldBe LocalDate.parse("2026-10-05")
        val random = Random(SEED)
        repeat(RANDOM_INSTANTS) {
            val now = Instant.ofEpochSecond(random.nextLong(0, EPOCH_BOUND))
            val mondays = Activity.weekStarts(now)
            Activity.empty(now, windowDays = 1).weeks.map { it.weekStart } shouldBe mondays
            mondays.last().atStartOfDay().toInstant(ZoneOffset.UTC) shouldBeLessThanOrEqualTo now
            mondays
                .last()
                .plusDays(DAYS_PER_WEEK)
                .atStartOfDay()
                .toInstant(ZoneOffset.UTC) shouldBeGreaterThan now
        }
    }

    @Test
    fun `the inventory statement is the shared CTE and the four lifecycle columns`() {
        val sql = InventorySource.sql(NavigationFamily.PIPELINES, narrowed = false)
        sql shouldBe
            "${InventorySource.admitted(NavigationFamily.PIPELINES, false)}\nSELECT ${InventorySource.LIFECYCLE_COUNTS}\n  FROM admitted"
        sql shouldNotContain "releases"
    }

    @Test
    fun `the release bins partition every count from zero up, one definition each`() {
        ReleaseBin.entries.map { it.predicate } shouldBe
            listOf("releases = 0", "releases = 1", "releases BETWEEN 2 AND 3", "releases BETWEEN 4 AND 9", "releases >= 10")
        ReleaseBin.entries.map { it.label } shouldBe listOf("0", "1", "2–3", "4–9", "10+")
        (0L..LARGE).forEach { releases -> ReleaseBin.entries.count { it.contains(releases) } shouldBe 1 }
        listOf(0L, 1L, 2L, 3L, 4L, 9L, 10L, LARGE).map { ReleaseBin.of(it) } shouldBe
            listOf(
                ReleaseBin.ZERO,
                ReleaseBin.ONE,
                ReleaseBin.TWO_TO_THREE,
                ReleaseBin.TWO_TO_THREE,
                ReleaseBin.FOUR_TO_NINE,
                ReleaseBin.FOUR_TO_NINE,
                ReleaseBin.TEN_PLUS,
                ReleaseBin.TEN_PLUS,
            )
    }

    /**
     * The invariant both charts share: the maturity bins sum to the lifecycle total, or the value is
     * refused. #598b: the activity's two theorems — actively developed never exceeds the objects
     * holding a draft, the weeks' new objects never exceed the objects — refused when broken.
     */
    @Test
    fun `random chart groups sum to one total and a disagreeing one is refused`() {
        val random = Random(SEED)
        repeat(10_000) {
            val bins = ReleaseBin.entries.map { MaturityBin(it, random.nextLong(0, BOUND), random.nextLong(0, BOUND)) }
            val total = bins.sumOf { it.total }
            val draftOnly = random.nextLong(0, total + 1)
            val releasedOnly = random.nextLong(0, total - draftOnly + 1)
            val releasedWithDraft = random.nextLong(0, total - draftOnly - releasedOnly + 1)
            val lifecycle = Inventory(draftOnly, releasedOnly, releasedWithDraft, total - draftOnly - releasedOnly - releasedWithDraft)
            val activity = randomActivity(random, lifecycle)
            ChartInventory(lifecycle, bins, activity).lifecycle.objects shouldBe bins.sumOf { it.draftOpen + it.noDraft }
            shouldThrow<IllegalArgumentException> {
                ChartInventory(lifecycle.copy(discardedOnly = lifecycle.discardedOnly + 1), bins, activity)
            }
            shouldThrow<IllegalArgumentException> {
                ChartInventory(lifecycle, bins, activity.copy(activelyDeveloped = lifecycle.withDrafts + 1))
            }
            val crowded = activity.weeks.mapIndexed { index, week -> if (index == 0) week.copy(created = lifecycle.objects + 1) else week }
            shouldThrow<IllegalArgumentException> { ChartInventory(lifecycle, bins, activity.copy(weeks = crowded)) }
        }
        val empty = ChartInventory.empty(NOW, windowDays = 14)
        shouldThrow<IllegalArgumentException> { ChartInventory(Inventory(0, 0, 0, 0), empty.maturity.drop(1), empty.activity) }
        empty.lifecycle.objects shouldBe 0L
        empty.activity.weeks.sumOf { it.events } shouldBe 0L
    }

    /** An activity is twelve consecutive Mondays, oldest first, with no negative count and a window of a day or more. */
    @Test
    fun `an activity whose weeks are not twelve consecutive Mondays is refused`() {
        val weeks = Activity.empty(NOW, windowDays = 14).weeks
        Activity(weeks, activelyDeveloped = 0, windowDays = 1).weeks shouldBe weeks
        listOf(
            weeks.drop(1),
            weeks + weeks.last().copy(weekStart = weeks.last().weekStart.plusDays(DAYS_PER_WEEK)),
            weeks.reversed(),
            weeks.map { it.copy(weekStart = it.weekStart.plusDays(1)) },
            weeks.mapIndexed { index, week -> if (index == WEEK_SKIPPED) week.copy(weekStart = week.weekStart.plusDays(1)) else week },
            weeks.mapIndexed { index, week -> if (index == WEEK_SKIPPED) week.copy(released = -1) else week },
            weeks.mapIndexed { index, week -> if (index == WEEK_SKIPPED) week.copy(created = -1) else week },
        ).forEach { broken -> shouldThrow<IllegalArgumentException> { Activity(broken, activelyDeveloped = 0, windowDays = 14) } }
        shouldThrow<IllegalArgumentException> { Activity(weeks, activelyDeveloped = -1, windowDays = 14) }
        shouldThrow<IllegalArgumentException> { Activity(weeks, activelyDeveloped = 0, windowDays = 0) }
    }

    @Test
    fun `an empty lens performs no database access`() {
        val jdbc = mockk<NamedParameterJdbcTemplate>()

        ChartInventorySource.aggregate(jdbc, NavigationFamily.PIPELINES, UUID.randomUUID(), ReadLens.NOTHING, NOW, 14) shouldBe
            ChartInventory.empty(NOW, windowDays = 14)
        verify(exactly = 0) { jdbc.query(any<String>(), any<Map<String, *>>(), any<RowMapper<*>>()) }
    }

    @Test
    fun `a window under one day is refused before any database access`() {
        val jdbc = mockk<NamedParameterJdbcTemplate>()

        listOf(0, -1).forEach { days ->
            shouldThrow<IllegalArgumentException> {
                ChartInventorySource.aggregate(jdbc, NavigationFamily.PIPELINES, UUID.randomUUID(), ReadLens.Everything, NOW, days)
            }
        }
        verify(exactly = 0) { jdbc.query(any<String>(), any<Map<String, *>>(), any<RowMapper<*>>()) }
    }

    /** Twelve weeks at [NOW] whose new objects sum to at most the objects and whose active count is at most those with drafts. */
    private fun randomActivity(
        random: Random,
        lifecycle: Inventory,
    ): Activity {
        var left = lifecycle.objects
        val weeks =
            Activity.weekStarts(NOW).shuffled(random).map { monday ->
                val created = random.nextLong(0, left + 1).also { left -= it }
                WeekActivity(monday, released = random.nextLong(0, BOUND), created = created)
            }
        return Activity(
            weeks.sortedBy { it.weekStart },
            activelyDeveloped = random.nextLong(0, lifecycle.withDrafts + 1),
            windowDays = random.nextInt(1, Int.MAX_VALUE),
        )
    }

    private companion object {
        const val LARGE = 1_000L
        const val BOUND = 1_000_000L
        const val SEED = 598
        const val RANDOM_INSTANTS = 10_000
        const val EPOCH_BOUND = 4_102_444_800L
        const val DAYS_PER_WEEK = 7L
        const val WEEK_SKIPPED = 5

        /** Twelve per-object release weeks, twelve created weeks, and the `:now` week each one compares against. */
        const val WEEK_BUCKETS = 48
        val NOW: Instant = Instant.parse("2026-10-14T12:00:00Z")
    }
}
