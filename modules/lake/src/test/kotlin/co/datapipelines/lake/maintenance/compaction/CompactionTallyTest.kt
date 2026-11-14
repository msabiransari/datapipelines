package co.datapipelines.lake.maintenance.compaction

import co.datapipelines.lake.maintenance.MaintenanceOutcome
import co.datapipelines.lake.maintenance.PartRecord
import co.datapipelines.lake.maintenance.PartResult
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration

/** #969 D8 (the outcome word, its precedence, the fields 959 reads) and D4 (the settings' arithmetic). */
class CompactionTallyTest {
    private val figures = GroupFigures(2, 20, 1, 19, 100, 0.5, 0.1, 0)

    @Test
    fun `precedence - busy, then capacity, then deadline, then done, else nothing to do`() {
        CompactionTally().outcome() shouldBe MaintenanceOutcome.NOTHING_TO_DO
        CompactionTally().apply { groups += figures }.outcome() shouldBe MaintenanceOutcome.DONE
        CompactionTally().apply { groups += figures }.apply { leftForFreshRun = 3 }.outcome() shouldBe MaintenanceOutcome.DEADLINE
        CompactionTally().apply { deadlineReached = true }.outcome() shouldBe MaintenanceOutcome.DEADLINE
        val both =
            CompactionTally().apply {
                leftForFreshRun = 1
                deadlineReached = true
                block(1, 64, GroupLimit(32, LimitSource.CAP))
            }
        both.outcome() shouldBe MaintenanceOutcome.CAPACITY
        both.apply { busy = true }.outcome() shouldBe MaintenanceOutcome.BUSY
    }

    @Test
    fun `every result carries committed and left_for_fresh_run, a capacity result its blocked diagnostics, never a common key`() {
        val capacity =
            CompactionTally()
                .apply {
                    groups += figures
                    leftForFreshRun = 2
                    block(2, 40, GroupLimit(32, LimitSource.CAP))
                    block(1, 70, GroupLimit(32, LimitSource.CAP))
                }.result()
        capacity.outcome shouldBe MaintenanceOutcome.CAPACITY
        capacity.details["committed"] shouldBe true
        capacity.details["left_for_fresh_run"] shouldBe 2L
        capacity.details["blocked"] shouldBe 3
        capacity.details["largest_need_bytes"] shouldBe 70L
        capacity.details["limit_bytes"] shouldBe 32L
        capacity.details["limit_source"] shouldBe "cap"
        capacity.figures.files shouldBe 2
        capacity.figures.bytes shouldBe 20

        val nothing = CompactionTally().result()
        nothing.details.keys shouldContainAll listOf("committed", "left_for_fresh_run", "blocked", "groups")
        nothing.details["committed"] shouldBe false
        (nothing.details.keys intersect PartRecord.KEYS) shouldBe emptySet()
    }

    @Test
    fun `a part's details admit the groups list of flat objects, and refuse a nested object or a top-level fraction`() {
        val done = CompactionTally().apply { groups += figures }.result()
        done.details["groups"] shouldBe listOf(figures.wire())
        val nested = listOf(mapOf("input_files" to mapOf("x" to 1)))
        shouldThrow<IllegalArgumentException> { PartResult(MaintenanceOutcome.DONE, details = mapOf("groups" to nested)) }
        shouldThrow<IllegalArgumentException> { PartResult(MaintenanceOutcome.DONE, details = mapOf("ratio" to 0.5)) }
        shouldThrow<IllegalArgumentException> { PartResult(MaintenanceOutcome.DONE, details = mapOf("outcome" to "done")) }
    }

    @Test
    fun `D4 settings - the ceiling leaves one delivery's reservation, rates and demands saturate`() {
        CompactionSettings.ceiling(20L shl 30, 1L shl 30, 4) shouldBe (16L shl 30)
        CompactionSettings.ceiling(1L shl 30, 1L shl 30, 4) shouldBe 0
        val settings = CompactionSettings(3, 4L shl 20, Duration.ofHours(1), Long.MAX_VALUE)
        settings.wholeRunBytes shouldBe (4L shl 20) * 3600
        settings.bytesIn(Duration.ofMillis(500)) shouldBe (2L shl 20)
        settings.bytesIn(Duration.ofSeconds(-5)) shouldBe 0
        settings.scratchFor(Long.MAX_VALUE / 2) shouldBe Long.MAX_VALUE
        CompactionSettings(1, Long.MAX_VALUE, Duration.ofDays(1), 0).wholeRunBytes shouldBe Long.MAX_VALUE
    }
}
