package co.datapipelines.lake.delivery

import co.datapipelines.lake.LakeTestDb
import co.datapipelines.lake.settle.LakeSettlementSweep
import io.kotest.matchers.collections.shouldContainOnly
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime

/**
 * Record §8b "Per-tick work bound" (lane 799b; the lander's refusal 6671398448 of #799 at `e70dd30b3`): a settlement
 * tick consults its budget before every row, settles at most one page of rows per table, and a per-table cursor reaches
 * rows that live, busy or failing rows ahead of them would otherwise hide. Real PostgreSQL, real fence, real ledger
 * with a recording audit sink; the sweep is the production class over the same harness.
 */
class SettlementWorkBoundIntegrationTest {
    private val h = DeliverySettlementHarness()

    @BeforeEach
    fun clean() {
        h.reset()
    }

    @Test
    fun `B1 an expired budget settles nothing and a fresh one settles with its strict audit`() {
        val row = h.receive()
        settleUntil(Instant.EPOCH) shouldBe 0
        h.repository.find(row.id)?.state shouldBe LakeDeliveryState.RECEIVED
        h.attemptOutcome(row) shouldBe null
        h.audit.events shouldBe emptyList()

        settleUntil(h.leases().catalogNowPlus(Duration.ofMinutes(1))) shouldBe 1
        h.repository.find(row.id)?.state shouldBe LakeDeliveryState.FAILED
        h.jdbc.queryForObject("SELECT failure_phase FROM lake_deliveries WHERE id = ?", String::class.java, row.id) shouldBe "validation"
        h.audit.events.map { it.event } shouldBe listOf(LakeDeliveryAuditEvents.FAILED, LakeDeliveryAuditEvents.SETTLED)
        h.audit.events.map { it.inTransaction } shouldContainOnly listOf(true)
        h.audit.events.map { it.details["delivery_id"] } shouldContainOnly listOf(row.id.toString())
    }

    @Test
    fun `B2 a backlog drains at most one page per tick within the budget and completely across ticks`() {
        val total = BACKLOG_PAGES * PAGE
        h.backlog(total)
        val sweep = h.sweep()
        val ticks = (total + PAGE - 1) / PAGE + 1
        val perTick = mutableListOf<Int>()
        repeat(ticks) { index ->
            val started = catalogClock()
            val changed = tick(sweep)
            val elapsed = Duration.between(started, catalogClock())
            println("case=B2 tick=${index + 1} changed=$changed elapsed_ms=${elapsed.toMillis()} page=$PAGE backlog=$total")
            changed shouldBeLessThanOrEqual PAGE
            elapsed.toMillis() shouldBeLessThanOrEqual (SWEEP_BUDGET + PERMITTED_OVERRUN).toMillis()
            perTick += changed
        }
        println("case=B2 per_tick=$perTick")
        settled() shouldBe total
        h.audit.events.count { it.event == LakeDeliveryAuditEvents.SETTLED } shouldBe total
    }

    @Test
    fun `B3 a page of live attempts does not hide later eligible rows and the cursor wraps back to them`() {
        val rows = h.backlog(PAGE + PAGE / 2)
        val live = rows.take(PAGE)
        val eligible = rows.drop(PAGE)
        val lease = h.livePublishing(live)
        val sweep = h.sweep()

        tick(sweep) shouldBe 0
        tick(sweep) shouldBe PAGE / 2
        eligible.map { h.repository.find(it.id)?.state } shouldContainOnly listOf(LakeDeliveryState.FAILED)
        live.map { h.repository.find(it.id)?.state } shouldContainOnly listOf(LakeDeliveryState.PUBLISHING)

        LakeTestDb.leases.release(lease.operationId)
        tick(sweep) shouldBe PAGE
        live.map { h.repository.find(it.id)?.state } shouldContainOnly listOf(LakeDeliveryState.FAILED)
        h.jdbc.queryForObject(
            "SELECT count(*) FROM lake_deliveries WHERE table_id = ? AND failure_phase = 'publish'",
            Int::class.java,
            h.tableId,
        ) shouldBe PAGE
    }

    @Test
    fun `B3 a row whose conclusion keeps failing does not hide the rows behind it`() {
        val rows = h.backlog(POISON_BACKLOG)
        val poison = rows.first()
        // The attempt is already terminal, so the ledger's conclusion refuses and rolls back on every visit.
        h.jdbc.update("UPDATE lake_delivery_attempts SET outcome = 'FAILED', ended_at = now() WHERE operation_id = ?", poison.operationId)
        val sweep = h.sweep()

        tick(sweep) shouldBe 0
        tick(sweep) shouldBe POISON_BACKLOG - 1
        h.repository.find(poison.id)?.state shouldBe LakeDeliveryState.RECEIVED
        rows.drop(1).map { h.repository.find(it.id)?.state } shouldContainOnly listOf(LakeDeliveryState.FAILED)
    }

    /** The settlement call under test; its budget ends at the database-clock [deadline]. */
    private fun settleUntil(deadline: Instant): Int = h.settler().settle(h.tableId, deadline)

    /** One sweep tick; answers how many of this table's deliveries it moved to a terminal state. */
    private fun tick(sweep: LakeSettlementSweep): Int {
        val before = settled()
        sweep.runOnce()
        return settled() - before
    }

    private fun settled(): Int =
        requireNotNull(
            h.jdbc.queryForObject(
                "SELECT count(*) FROM lake_deliveries WHERE table_id = ? AND state IN ('PUBLISHED', 'FAILED')",
                Int::class.java,
                h.tableId,
            ),
        )

    private fun catalogClock(): Instant =
        requireNotNull(h.jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime::class.java)).toInstant()

    private companion object {
        /** Production's per-table row page (D1). */
        const val PAGE = 50
        const val BACKLOG_PAGES = 3
        const val POISON_BACKLOG = 3

        /** Production's tick budget and the permitted overrun past it: one statement timeout plus one transaction. */
        val SWEEP_BUDGET: Duration = Duration.ofSeconds(10)
        val PERMITTED_OVERRUN: Duration = Duration.ofSeconds(10)
    }
}
