package co.datapipelines.lake.maintenance.plan

import co.datapipelines.lake.maintenance.MaintenanceOutcome
import co.datapipelines.lake.profiles.LakeTablePause
import co.datapipelines.lake.profiles.MaintenanceWindow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalTime

/** N6: the gate (brief 959 D6; the cut's L13; acceptance M7 for `00:00-00:00`). */
class MaintenanceHoldsTest {
    private val day = "2026-11-14"
    private val open = LakeTablePause(null, null)

    private fun at(time: String): Instant = Instant.parse("${day}T$time:00Z")

    private fun window(text: String): MaintenanceWindow {
        val (start, end) = text.split('-').map { LocalTime.parse(it) }
        return MaintenanceWindow(start, end)
    }

    private fun compaction(
        window: String,
        time: String,
        pause: LakeTablePause = open,
    ): PartHold? = MaintenanceHolds.hold(MaintenancePartNames.COMPACTION, window(window), pause, at(time))

    @Test
    fun `02-00 to 05-00 admits compaction from its start up to, not including, its end`() {
        compaction("02:00-05:00", "02:00").shouldBeNull()
        compaction("02:00-05:00", "04:59").shouldBeNull()
        compaction("02:00-05:00", "05:00") shouldBe PartHold(MaintenanceOutcome.OUTSIDE_WINDOW, "outside window")
        compaction("02:00-05:00", "01:59") shouldBe PartHold(MaintenanceOutcome.OUTSIDE_WINDOW, "outside window")
        compaction("02:00-05:00", "12:00") shouldBe PartHold(MaintenanceOutcome.OUTSIDE_WINDOW, "outside window")
    }

    @Test
    fun `a window crossing midnight admits both sides of midnight and holds the middle of the day`() {
        compaction("22:00-03:00", "22:00").shouldBeNull()
        compaction("22:00-03:00", "23:59").shouldBeNull()
        compaction("22:00-03:00", "00:00").shouldBeNull()
        compaction("22:00-03:00", "02:59").shouldBeNull()
        compaction("22:00-03:00", "03:00")?.reason shouldBe "outside window"
        compaction("22:00-03:00", "12:00")?.reason shouldBe "outside window"
        compaction("22:00-03:00", "21:59")?.reason shouldBe "outside window"
    }

    @Test
    fun `00-00 to 00-00 is the whole day at every hour (M7)`() {
        listOf("00:00", "00:01", "06:00", "12:00", "18:30", "23:59").forEach { compaction("00:00-00:00", it).shouldBeNull() }
        listOf("00:00", "11:00", "23:59").forEach { compaction("13:15-13:15", it).shouldBeNull() }
    }

    @Test
    fun `the window holds compaction only, never manifest rewrite, cleanup or purge deletion`() {
        listOf(MaintenancePartNames.MANIFEST_REWRITE, MaintenancePartNames.CLEANUP, MaintenancePartNames.PURGE_DELETION)
            .forEach { MaintenanceHolds.hold(it, window("02:00-05:00"), open, at("12:00")).shouldBeNull() }
    }

    @Test
    fun `the compaction pause holds compaction and manifest rewrite, not cleanup`() {
        val until = at("20:00")
        val pause = LakeTablePause(compactionUntil = until, cleanupUntil = null)
        val expected = PartHold(MaintenanceOutcome.PAUSED_UNTIL, "paused until 2026-11-14T20:00:00Z", until)
        MaintenanceHolds.hold(MaintenancePartNames.COMPACTION, window("00:00-00:00"), pause, at("12:00")) shouldBe expected
        MaintenanceHolds.hold(MaintenancePartNames.MANIFEST_REWRITE, window("00:00-00:00"), pause, at("12:00")) shouldBe expected
        MaintenanceHolds.hold(MaintenancePartNames.CLEANUP, window("00:00-00:00"), pause, at("12:00")).shouldBeNull()
        MaintenanceHolds.hold(MaintenancePartNames.PURGE_DELETION, window("00:00-00:00"), pause, at("12:00")).shouldBeNull()
    }

    @Test
    fun `the cleanup pause holds cleanup only`() {
        val until = at("20:00")
        val pause = LakeTablePause(compactionUntil = null, cleanupUntil = until)
        MaintenanceHolds.hold(MaintenancePartNames.CLEANUP, window("00:00-00:00"), pause, at("12:00")) shouldBe
            PartHold(MaintenanceOutcome.PAUSED_UNTIL, "paused until 2026-11-14T20:00:00Z", until)
        MaintenanceHolds.hold(MaintenancePartNames.COMPACTION, window("00:00-00:00"), pause, at("12:00")).shouldBeNull()
        MaintenanceHolds.hold(MaintenancePartNames.MANIFEST_REWRITE, window("00:00-00:00"), pause, at("12:00")).shouldBeNull()
        MaintenanceHolds.hold(MaintenancePartNames.PURGE_DELETION, window("00:00-00:00"), pause, at("12:00")).shouldBeNull()
    }

    @Test
    fun `both pauses hold their own halves, and a pause outranks the window in the reason`() {
        val pause = LakeTablePause(compactionUntil = at("20:00"), cleanupUntil = at("21:00"))
        compaction("02:00-05:00", "12:00", pause)?.reason shouldBe "paused until 2026-11-14T20:00:00Z"
        MaintenanceHolds.hold(MaintenancePartNames.CLEANUP, window("02:00-05:00"), pause, at("12:00"))?.reason shouldBe
            "paused until 2026-11-14T21:00:00Z"
        MaintenanceHolds.hold(MaintenancePartNames.PURGE_DELETION, window("02:00-05:00"), pause, at("12:00")).shouldBeNull()
    }

    @Test
    fun `an expired pause holds nothing, and a pause ends at its instant`() {
        val pause = LakeTablePause(compactionUntil = at("11:00"), cleanupUntil = at("12:00"))
        compaction("00:00-00:00", "12:00", pause).shouldBeNull()
        MaintenanceHolds.hold(MaintenancePartNames.CLEANUP, window("00:00-00:00"), pause, at("12:00")).shouldBeNull()
        MaintenanceHolds.hold(MaintenancePartNames.CLEANUP, window("00:00-00:00"), pause, at("11:59"))?.outcome shouldBe
            MaintenanceOutcome.PAUSED_UNTIL
    }

    @Test
    fun `a part name the planner does not know is never held`() {
        val pause = LakeTablePause(compactionUntil = at("20:00"), cleanupUntil = at("20:00"))
        MaintenanceHolds.hold("snapshot_keep", window("02:00-05:00"), pause, at("12:00")).shouldBeNull()
    }
}
