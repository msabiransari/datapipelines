package co.datapipelines.browser

import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * #245 — the unit-shaped check of [ShellFeelBrowserTest]'s DERIVED entrance floor: no
 * browser, just the arithmetic a gate verdict now rides on.
 *
 * The recorded red (orchestrator gate `2c3016a8`, 2026-09-25, one red in 36 gates):
 * 115.3ms measured between `animationstart` and `animationend` of a stylesheet-declared
 * 150ms entrance, against a hand-written 120ms floor. The fixed floor sat below the
 * declared value with no principled slack, and frame-quantised event delivery under gate
 * load lands a PLAYED animation about two frames short of declared. The floor is derived
 * now: declared duration minus [ENTRANCE_SLACK_MILLIS] (three frames). The recorded
 * measurement passes with ~15ms of margin; a genuinely cut entrance — the ~15ms defect the
 * guard was born for — still fails it by ~85ms, and the browser test's `animationcancel`
 * assertion names a cut outright.
 */
class ShellFeelEntranceFloorTest {
    @Test
    fun `the floor is the declared duration minus three frames of slack`() {
        entranceFloorMillis(DECLARED_MILLIS) shouldBe DERIVED_FLOOR_MILLIS
    }

    @Test
    fun `a zero or near-zero declared duration clamps the floor to zero`() {
        entranceFloorMillis(0.0) shouldBe 0.0
        entranceFloorMillis(30.0) shouldBe 0.0
    }

    @Test
    fun `the declared duration parses in the shapes computed style emits`() {
        parseDeclaredDurationMillis("0.15s") shouldBe DECLARED_MILLIS
        parseDeclaredDurationMillis("150ms") shouldBe DECLARED_MILLIS
        parseDeclaredDurationMillis("0s") shouldBe 0.0
        parseDeclaredDurationMillis("") shouldBe 0.0
        parseDeclaredDurationMillis("0.15s, 0.2s") shouldBe DECLARED_MILLIS
    }

    @Test
    fun `the recorded gate red passes the derived floor and would have failed the old fixed one`() {
        RECORDED_MEASURED_MILLIS shouldBeGreaterThanOrEqual entranceFloorMillis(DECLARED_MILLIS)
        RECORDED_MEASURED_MILLIS shouldBeLessThan OLD_FIXED_FLOOR_MILLIS
    }

    @Test
    fun `a one-frame-short measurement fails a floor at the full declared value and passes the derived one`() {
        val oneFrameShort = DECLARED_MILLIS - ONE_FRAME_MILLIS
        oneFrameShort shouldBeLessThan DECLARED_MILLIS
        oneFrameShort shouldBeGreaterThanOrEqual entranceFloorMillis(DECLARED_MILLIS)
    }

    @Test
    fun `a genuinely cut entrance still fails the derived floor`() {
        val cut = 15.0
        cut shouldBeLessThan entranceFloorMillis(DECLARED_MILLIS)
    }

    private companion object {
        const val DECLARED_MILLIS = 150.0
        const val DERIVED_FLOOR_MILLIS = 100.0
        const val RECORDED_MEASURED_MILLIS = 115.3

        /** The retired hand-written floor — kept only so this test can show why it left. */
        const val OLD_FIXED_FLOOR_MILLIS = 120.0
        const val ONE_FRAME_MILLIS = 16.7
    }
}
