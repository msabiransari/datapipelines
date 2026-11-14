package co.datapipelines.lake.maintenance.cleanup

import co.datapipelines.lake.maintenance.MaintenanceOutcome
import co.datapipelines.lake.maintenance.remove.LeftReason
import co.datapipelines.lake.maintenance.remove.MarkerFate
import co.datapipelines.lake.maintenance.remove.MarkerLeftReason
import co.datapipelines.lake.maintenance.remove.RefusalReason
import co.datapipelines.lake.maintenance.remove.RemovalResult
import co.datapipelines.lake.maintenance.remove.StopReason
import co.datapipelines.pipeline.PipelineErrorCodes
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * Brief 962 D2a as a table: every shape of 958's result maps to exactly one row (complete, deadline, or a throw with its
 * code and reason), and the part's figures leave its own clock marker out of the deleted count. Every enum value of
 * 958's refusal, stop and marker-left reasons is visited, so a reason added later without a row here is red.
 */
class RemovalVerdictTest {
    @Test
    fun `every refusal reason has its row`() {
        val rows =
            RefusalReason.entries.associateWith { reason ->
                RemovalVerdict.of(RemovalResult.Refused(reason, MarkerFate.NotWritten))
            }
        rows.getValue(RefusalReason.LEASE_LOST) shouldBe RemovalVerdict.Deadline
        rows
            .getValue(RefusalReason.MODE_NOT_NORMAL)
            .failure()
            .shouldBeInstanceOf<CleanupIncomplete>()
            .reason shouldBe IncompleteReason.MODE
        rows
            .getValue(RefusalReason.CLOCK_SKEW)
            .failure()
            .shouldBeInstanceOf<CleanupClockSkew>()
            .code shouldBe
            PipelineErrorCodes.Lake.MAINTENANCE_CLOCK_SKEW
        rows.getValue(RefusalReason.NOT_PURGED).failure().shouldBeInstanceOf<IllegalStateException>()
        rows.keys shouldBe RefusalReason.entries.toSet()
    }

    @Test
    fun `every stop reason has its row, whatever the marker`() {
        StopReason.entries.forEach { stop ->
            val verdict = RemovalVerdict.of(completed(stopped = stop))
            withClue(stop) {
                when (stop) {
                    StopReason.LEASE_LOST -> verdict shouldBe RemovalVerdict.Deadline
                    StopReason.MODE_CHANGED -> verdict.incomplete() shouldBe IncompleteReason.MODE
                    StopReason.DELETE_FAILED -> verdict.incomplete() shouldBe IncompleteReason.DELETE_FAILED
                }
            }
        }
    }

    @Test
    fun `a complete pass with one delete_failed key is the delete_failed row, and ordinary left reasons are complete`() {
        RemovalVerdict.of(completed(left = mapOf(LeftReason.DELETE_FAILED to 1))).incomplete() shouldBe IncompleteReason.DELETE_FAILED
        val ordinary = LeftReason.entries.filter { it !in UNFINISHED }.associateWith { 1 }
        RemovalVerdict.of(completed(left = ordinary)) shouldBe RemovalVerdict.Complete
    }

    @Test
    fun `the pass's own marker - removed or never written is complete, every left reason has its row`() {
        RemovalVerdict.of(completed(marker = MarkerFate.Removed)) shouldBe RemovalVerdict.Complete
        RemovalVerdict.of(completed(marker = MarkerFate.NotWritten)) shouldBe RemovalVerdict.Complete
        MarkerLeftReason.entries.forEach { reason ->
            val verdict = RemovalVerdict.of(completed(marker = MarkerFate.Left(reason)))
            withClue(reason) {
                when (reason) {
                    MarkerLeftReason.LEASE_LOST -> {
                        verdict shouldBe RemovalVerdict.Deadline
                    }

                    MarkerLeftReason.MODE_CHANGED -> {
                        verdict.incomplete() shouldBe IncompleteReason.MODE
                    }

                    MarkerLeftReason.CHECK_FAILED, MarkerLeftReason.DELETE_UNCONFIRMED -> {
                        verdict.incomplete() shouldBe IncompleteReason.DELETE_FAILED
                    }
                }
            }
        }
    }

    @Test
    fun `the answer - complete is done only when work happened, the lease lost is deadline, a failure throws unchanged`() {
        CleanupProgress().answer(RemovalVerdict.Complete, didWork = true).outcome shouldBe MaintenanceOutcome.DONE
        CleanupProgress().answer(RemovalVerdict.Complete, didWork = false).outcome shouldBe MaintenanceOutcome.NOTHING_TO_DO
        CleanupProgress().answer(RemovalVerdict.Deadline, didWork = true).outcome shouldBe MaintenanceOutcome.DEADLINE
        val failure = CleanupIncomplete(IncompleteReason.MODE)
        shouldThrow<CleanupIncomplete> { CleanupProgress().answer(RemovalVerdict.Fail(failure), didWork = true) } shouldBe failure
    }

    @Test
    fun `the figures leave the pass's own removed marker out of the deleted count, never below zero`() {
        val removed = CleanupProgress().apply { record(completed(deleted = 3, marker = MarkerFate.Removed)) }
        removed.figures.deleted shouldBe 2
        val left = CleanupProgress().apply { record(completed(deleted = 3, marker = MarkerFate.Left(MarkerLeftReason.CHECK_FAILED))) }
        left.figures.deleted shouldBe 3
        val onlyMarker = CleanupProgress().apply { record(completed(deleted = 1, marker = MarkerFate.Removed)) }
        onlyMarker.figures.deleted shouldBe 0
        onlyMarker.figures.didWork shouldBe false
    }

    private fun RemovalVerdict.failure(): RuntimeException = this.shouldBeInstanceOf<RemovalVerdict.Fail>().failure

    private fun RemovalVerdict.incomplete(): IncompleteReason =
        this
            .shouldBeInstanceOf<RemovalVerdict.Fail>()
            .failure
            .shouldBeInstanceOf<CleanupIncomplete>()
            .reason

    private fun completed(
        stopped: StopReason? = null,
        left: Map<LeftReason, Int> = emptyMap(),
        marker: MarkerFate = MarkerFate.Removed,
        deleted: Int = 0,
    ) = RemovalResult.Completed(
        listed = deleted,
        reachedKeys = 0,
        deleted = deleted,
        bytesDeleted = 0,
        left = left,
        deferredHints = emptyMap(),
        stopped = stopped,
        marker = marker,
        purge = null,
        inventory = emptyList(),
        visits = emptyList(),
    )

    private companion object {
        /**
         * The left reason that makes a pass that otherwise completed the `delete_failed` row (D2a row 6). `outcome_unknown`
         * and `not_attempted` come only with a stop, whose own row decides.
         */
        val UNFINISHED = setOf(LeftReason.DELETE_FAILED)
    }
}
