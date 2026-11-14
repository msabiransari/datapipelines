package co.datapipelines.lake.profiles

import co.datapipelines.lake.definitions.AuditSinkFailure
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * P8 (brief 956 D6; record call 38, M6): each pause key set and cleared on its own; 7 days after the catalog clock
 * admitted, 7 days and one second refused naming the key; a `purged` table takes no pause; the other key untouched.
 * The clock is pinned to one instant read from the catalog, so the boundary is exact. P9's pause half: one
 * `lake.table.pause_changed` per change, in its transaction.
 */
class LakeTablePausesIntegrationTest {
    private val h = LakeProfilesHarness()
    private lateinit var now: Instant

    @BeforeEach
    fun reset() {
        h.reset()
        now = h.clock().truncatedTo(ChronoUnit.MICROS)
        h.clock = { now }
    }

    @Test
    fun `each key is set and cleared on its own and the other key is untouched`() {
        val table = h.table("orders")
        val compaction = now.plus(Duration.ofDays(2))
        val cleanup = now.plus(Duration.ofDays(3))
        h.pauses.set(table, LakePauseKind.COMPACTION, compaction, h.actor)
        h.pauses.read(table) shouldBe LakeTablePause(compaction, null)
        h.pauses.set(table, LakePauseKind.CLEANUP, cleanup, h.actor)
        h.pauses.read(table) shouldBe LakeTablePause(compaction, cleanup)
        h.pauses.clear(table, LakePauseKind.COMPACTION, h.actor)
        h.pauses.read(table) shouldBe LakeTablePause(null, cleanup)
        h.pauses.clear(table, LakePauseKind.CLEANUP, h.actor)
        h.pauses.read(table) shouldBe LakeTablePause(null, null)
        h.policyJson(table) shouldBe "{}"
        // The profile reference never moves with a pause.
        h.profiles.profileNameOf(table) shouldBe "production"
    }

    @Test
    fun `7 days ahead is admitted and 7 days and one second is refused naming the key`() {
        val table = h.table("orders")
        LakePauseKind.entries.forEach { kind ->
            val refusal =
                shouldThrow<DatapipelinesException> { h.pauses.set(table, kind, now.plus(Duration.ofDays(7)).plusSeconds(1), h.actor) }
            refusal.code shouldBe PipelineErrorCodes.Lake.PROFILE_INVALID
            refusal.details shouldBe mapOf("path" to kind.key, "reason" to "beyond_7_days")
            h.pauses.read(table).until(kind) shouldBe null
            h.pauses.set(table, kind, now.plus(Duration.ofDays(7)), h.actor)
            h.pauses.read(table).until(kind) shouldBe now.plus(Duration.ofDays(7))
        }
    }

    @Test
    fun `a purged table takes no pause but may still be cleared`() {
        val table = h.table("orders")
        h.pauses.set(table, LakePauseKind.CLEANUP, now.plus(Duration.ofDays(1)), h.actor)
        h.jdbc.update(
            "UPDATE lake_tables_managed SET status = 'purged', purged_at = NOW(), purged_by = ? WHERE id = ?",
            h.actor,
            table,
        )
        val refusal =
            shouldThrow<DatapipelinesException> { h.pauses.set(table, LakePauseKind.COMPACTION, now.plus(Duration.ofDays(1)), h.actor) }
        refusal.details shouldBe mapOf("path" to "pause.compaction_until", "reason" to "purged_table")
        h.pauses.clear(table, LakePauseKind.CLEANUP, h.actor)
        h.pauses.read(table) shouldBe LakeTablePause(null, null)
    }

    @Test
    fun `each change is audited once inside its transaction, a no-op and a failed change write nothing`() {
        val table = h.table("orders")
        h.audit.events.clear()
        val until = now.plus(Duration.ofHours(6))
        h.pauses.set(table, LakePauseKind.COMPACTION, until, h.actor)
        h.pauses.set(table, LakePauseKind.COMPACTION, until, h.actor)
        h.pauses.clear(table, LakePauseKind.CLEANUP, h.actor)
        h.pauses.clear(table, LakePauseKind.COMPACTION, h.actor)
        val events = h.profileEvents()
        events.map { it.event } shouldBe List(2) { LakeProfileAuditEvents.PAUSE_CHANGED }
        events.all { it.inTransaction && it.userId == h.actor } shouldBe true
        (events[0].details["key"] to events[0].details["until"]) shouldBe ("pause.compaction_until" to until.toString())
        (events[1].details["until"] to events[1].details["previous"]) shouldBe (null to until.toString())

        h.audit.failOn = LakeProfileAuditEvents.PAUSE_CHANGED
        shouldThrow<AuditSinkFailure> { h.pauses.set(table, LakePauseKind.CLEANUP, until, h.actor) }
        h.audit.failOn = null
        h.pauses.read(table) shouldBe LakeTablePause(null, null)
        h.profileEvents().size shouldBe 2
    }
}
