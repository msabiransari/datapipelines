package co.datapipelines.pipeline

import io.kotest.assertions.throwables.shouldThrow
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
import java.util.UUID
import kotlin.random.Random

/**
 * #598a — the chart group's ONE statement, pinned at its text: the same `admitted` CTE as the
 * inventory's (bound workspace and lens, no body), the four lifecycle columns, and one pair of
 * columns per release bin whose range appears exactly once.
 */
class ChartInventorySourceTest {
    @ParameterizedTest
    @EnumSource(NavigationFamily::class)
    fun `the statement extends the inventory's admitted set and binds only the workspace and the lens`(family: NavigationFamily) {
        listOf(false, true).forEach { narrowed ->
            val sql = ChartInventorySource.sql(family, narrowed)
            println("family=$family narrowed=$narrowed\n$sql")
            sql shouldContain InventorySource.admitted(family, narrowed, ", count(*) FILTER (WHERE v.status = 'RELEASED') AS releases")
            sql shouldContain InventorySource.LIFECYCLE_COUNTS
            sql shouldContain "s.workspace_id = :workspace"
            sql shouldContain "GROUP BY s.id"
            listOf("body_json", "current_version", "released_at", "LIMIT", "s.name,", "created_by").forEach { sql shouldNotContain it }
            val bound = Regex(":[a-z_]+").findAll(sql).map { it.value }.toSet()
            bound shouldBe if (narrowed) setOf(":workspace", ":names") else setOf(":workspace")
            if (narrowed) sql shouldContain "AND s.name IN (:names) AND v.status = 'RELEASED'" else sql shouldNotContain "AND v.status"
            ReleaseBin.entries.forEach { bin ->
                Regex(Regex.escape("(WHERE ${bin.predicate} AND ")).findAll(sql).count() shouldBe 2
                sql shouldContain "${bin.predicate} AND d) AS ${bin.column}_draft"
                sql shouldContain "${bin.predicate} AND NOT d) AS ${bin.column}_no_draft"
            }
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

    /** The invariant both charts share: the maturity bins sum to the lifecycle total, or the value is refused. */
    @Test
    fun `random chart groups sum to one total and a disagreeing one is refused`() {
        val random = Random(598)
        repeat(10_000) {
            val bins = ReleaseBin.entries.map { MaturityBin(it, random.nextLong(0, BOUND), random.nextLong(0, BOUND)) }
            val total = bins.sumOf { it.total }
            val draftOnly = random.nextLong(0, total + 1)
            val releasedOnly = random.nextLong(0, total - draftOnly + 1)
            val releasedWithDraft = random.nextLong(0, total - draftOnly - releasedOnly + 1)
            val lifecycle = Inventory(draftOnly, releasedOnly, releasedWithDraft, total - draftOnly - releasedOnly - releasedWithDraft)
            ChartInventory(lifecycle, bins).lifecycle.objects shouldBe bins.sumOf { it.draftOpen + it.noDraft }
            shouldThrow<IllegalArgumentException> { ChartInventory(lifecycle.copy(discardedOnly = lifecycle.discardedOnly + 1), bins) }
        }
        shouldThrow<IllegalArgumentException> { ChartInventory(Inventory(0, 0, 0, 0), ChartInventory.EMPTY.maturity.drop(1)) }
        ChartInventory.EMPTY.lifecycle.objects shouldBe 0L
    }

    @Test
    fun `an empty lens performs no database access`() {
        val jdbc = mockk<NamedParameterJdbcTemplate>()

        ChartInventorySource.aggregate(jdbc, NavigationFamily.PIPELINES, UUID.randomUUID(), ReadLens.NOTHING) shouldBe
            ChartInventory.EMPTY
        verify(exactly = 0) { jdbc.query(any<String>(), any<Map<String, *>>(), any<RowMapper<*>>()) }
    }

    private companion object {
        const val LARGE = 1_000L
        const val BOUND = 1_000_000L
    }
}
