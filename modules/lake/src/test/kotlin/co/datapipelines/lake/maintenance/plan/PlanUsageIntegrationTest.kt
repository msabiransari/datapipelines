package co.datapipelines.lake.maintenance.plan

import co.datapipelines.lake.LakeTestDb
import co.datapipelines.lake.maintenance.MaintenanceTable
import co.datapipelines.lake.maintenance.plan.PlanHarness.FileSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Timestamp
import java.time.Instant
import java.time.ZoneOffset

/**
 * N4 (usage rows) and N13a (growth, the cut's L8): one row per table per UTC day holding the day's latest measurement;
 * rows measured more than 35 days ago go; growth per week reads the newest row measured at least 7 days ago, returned
 * with that row's real time, and is NULL (never zero) without one.
 */
class PlanUsageIntegrationTest {
    private val h = PlanHarness()

    @BeforeEach
    fun clean() = h.reset()

    @AfterEach
    fun close() = h.close()

    private data class Usage(
        val day: String,
        val measuredAt: Instant,
        val bytes: Long,
        val rows: Long,
        val files: Long,
    )

    private fun usage(table: MaintenanceTable): List<Usage> =
        LakeTestDb.jdbc.query(
            "SELECT usage_day::text, measured_at, current_bytes, current_rows, current_files FROM lake_storage_usage " +
                "WHERE table_id = ? ORDER BY usage_day",
            { rs, _ -> Usage(rs.getString(1), rs.getTimestamp(2).toInstant(), rs.getLong(3), rs.getLong(4), rs.getLong(5)) },
            table.id,
        )

    /** A usage row measured [ago] (a Postgres interval) before the catalog's now, with [bytes]. */
    private fun seed(
        table: MaintenanceTable,
        ago: String,
        bytes: Long,
    ): Instant =
        requireNotNull(
            LakeTestDb.jdbc.queryForObject(
                "INSERT INTO lake_storage_usage (table_id, usage_day, measured_at, current_bytes, current_rows, current_files) " +
                    "SELECT ?, ((now() - CAST(? AS interval)) AT TIME ZONE 'UTC')::date, now() - CAST(? AS interval), ?, 1, 1 " +
                    "RETURNING measured_at",
                Timestamp::class.java,
                table.id,
                ago,
                ago,
                bytes,
            ),
        ).toInstant()

    @Test
    fun `N4 N13a - two measurements in one UTC day leave one row with the later one's figures and time`() {
        val table = h.table("daily")
        h.append(table, FileSpec("2026-11-01", 1_000))
        val planner = h.planner()
        h.pass(planner)
        val first = h.measurement(table)!!.measuredAt!!
        h.append(table, FileSpec("2026-11-02", 500))
        h.pass(planner)
        val second = h.measurement(table)!!.measuredAt!!

        val rows = usage(table)
        val days = setOf(first, second).map { it.atOffset(ZoneOffset.UTC).toLocalDate() }.toSet()
        rows.size shouldBe days.size // one per UTC day (two only when the passes straddle midnight UTC)
        rows.last() shouldBe Usage(second.atOffset(ZoneOffset.UTC).toLocalDate().toString(), second, 1_500, 20, 2)
    }

    @Test
    fun `N4 - a measurement with no snapshot writes no usage row`() {
        val table = h.table("nosnapshot")
        h.pass(h.planner())
        h.measurement(table).shouldNotBeNull()
        usage(table).size shouldBe 0
    }

    @Test
    fun `N4 - rows measured more than 35 days ago are deleted by the pass, younger ones kept`() {
        val table = h.table("retention")
        seed(table, "36 days", 10)
        val kept = seed(table, "34 days", 20)

        h.pass(h.planner(registered = emptySet()))

        usage(table).map { it.measuredAt } shouldBe listOf(kept)
    }

    @Test
    fun `N13a - the baseline is the newest row measured at least 7 days ago, with its real time`() {
        val table = h.table("growth")
        h.append(table, FileSpec("2026-11-01", 5_000))
        seed(table, "20 days", 1_000)
        val baseline = seed(table, "8 days", 3_000)
        seed(table, "2 days", 4_000)
        h.pass(h.planner())

        val figures = h.figures.figures(table.id).shouldNotBeNull()
        figures.currentBytes shouldBe 5_000
        figures.growthPerWeek shouldBe 2_000
        figures.growthBaselineAt shouldBe baseline
    }

    @Test
    fun `N13a - a sparse history - rows 10 days ago and 7 days ago at a later hour - returns the 10-day row with its time`() {
        // read at noon, the 7-day row was measured at 18:00: six hours short of seven days old
        val table = h.table("sparse")
        h.append(table, FileSpec("2026-11-01", 5_000))
        val tenDays = seed(table, "10 days", 1_000)
        seed(table, "6 days 18 hours", 2_000)
        h.pass(h.planner())

        val figures = h.figures.figures(table.id).shouldNotBeNull()
        figures.growthPerWeek shouldBe 4_000
        figures.growthBaselineAt shouldBe tenDays
    }

    @Test
    fun `N13a - with every row younger than 7 days growth is NULL, never zero`() {
        val table = h.table("young")
        h.append(table, FileSpec("2026-11-01", 5_000))
        seed(table, "6 days", 1_000)
        seed(table, "3 days", 2_000)
        h.pass(h.planner())

        val figures = h.figures.figures(table.id).shouldNotBeNull()
        figures.currentBytes shouldBe 5_000
        figures.growthPerWeek.shouldBeNull()
        figures.growthBaselineAt.shouldBeNull()
    }

    @Test
    fun `N13a - with every row pruned (older than 35 days) growth is NULL, never zero`() {
        val table = h.table("pruned")
        h.append(table, FileSpec("2026-11-01", 5_000))
        seed(table, "40 days", 1_000)
        seed(table, "36 days", 2_000)
        h.pass(h.planner())

        usage(table).size shouldBe 1 // only today's
        val figures = h.figures.figures(table.id).shouldNotBeNull()
        figures.growthPerWeek.shouldBeNull()
        figures.growthBaselineAt.shouldBeNull()
    }

    @Test
    fun `D8 - a table never measured reads never_measured with every figure NULL`() {
        val table = h.table("never")
        val figures = h.figures.figures(table.id).shouldNotBeNull()
        figures.figuresState shouldBe FiguresState.NEVER_MEASURED
        figures.measuredAt.shouldBeNull()
        figures.currentBytes.shouldBeNull()
        figures.currentFiles.shouldBeNull()
        figures.growthPerWeek.shouldBeNull()
    }
}
