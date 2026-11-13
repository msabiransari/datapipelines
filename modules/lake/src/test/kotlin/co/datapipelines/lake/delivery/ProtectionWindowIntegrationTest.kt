package co.datapipelines.lake.delivery

import ch.qos.logback.classic.Logger
import co.datapipelines.lake.LakeTestDb
import co.datapipelines.lake.LeaseKind
import co.datapipelines.lake.settle.LakeSettlementSweep
import co.datapipelines.logcapture.capturingLogEvents
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.shouldBeBetween
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

/**
 * #939 — the protection window comes from configuration (record §11a call 35, one value per deployment). The stamp is
 * `now()` plus the sweep's configured days; `lake_control.protection_interval` is a mirror the sweep keeps equal to it in
 * normal mode, writing only when it differs; a deadline already stamped is never touched (§9). Every case calls the
 * protection step directly, on the test's thread, over the real catalog trigger.
 */
class ProtectionWindowIntegrationTest {
    private val h = DeliverySettlementHarness()
    private val sweepLog = LoggerFactory.getLogger(LakeSettlementSweep::class.java) as Logger
    private var pointers = 0

    @BeforeEach
    fun reset() {
        h.reset()
        h.jdbc.update("UPDATE lake_control SET protection_interval = INTERVAL '7 days' WHERE id = 1")
        val lease = LakeTestDb.lease(LeaseKind.TABLE, TABLE)
        try {
            h.fencedConnection(lease).use { connection -> h.insertPointer(connection) shouldBe 1 }
        } finally {
            LakeTestDb.leases.release(lease.operationId)
        }
    }

    /** `LakeTestDb.reset` puts the mode back but not the window: the next class must not inherit this one's. */
    @AfterEach
    fun restore() {
        h.jdbc.update("UPDATE lake_control SET mode = 'normal', protection_interval = INTERVAL '7 days' WHERE id = 1")
    }

    @Test
    fun `T1 a non-default window stamps from configuration and the control row mirrors it`() {
        supersede()
        h.sweep(protectionDays = 30).finalizeProtection() shouldBe 1

        withClue("the stamp is now() plus the configured 30 days") { secondsShortOf(30, seq = 1).shouldBeBetween(0.0, SLACK, 0.0) }
        window() shouldBe "30 days"
    }

    @Test
    fun `T2 a lowered window never lowers a stamped deadline and applies to later supersessions`() {
        supersede()
        h.sweep(protectionDays = 30).finalizeProtection() shouldBe 1
        val stamped = deadline(seq = 1)
        supersede()

        h.sweep(protectionDays = 3).finalizeProtection() shouldBe 1

        withClue("the revision stamped under 30 days is byte-for-byte unchanged") { deadline(seq = 1) shouldBe stamped }
        withClue("the revision superseded afterwards gets the new window") { secondsShortOf(3, seq = 2).shouldBeBetween(0.0, SLACK, 0.0) }
        window() shouldBe "3 days"
    }

    @Test
    fun `T3 restoring and replaying leave the window and every deadline as the restore put them`() {
        supersede()
        for (mode in listOf("restoring", "replaying")) {
            h.jdbc.update("UPDATE lake_control SET mode = ?, protection_interval = INTERVAL '2 days' WHERE id = 1", mode)
            val events = capturingLogEvents(sweepLog) { h.sweep(protectionDays = 30).finalizeProtection() shouldBe 0 }

            withClue("mode $mode: protection_interval") { window() shouldBe "2 days" }
            withClue("mode $mode: every deadline") { deadlines() shouldBe mapOf(1L to null) }
            withClue("mode $mode: no alignment logged") { aligned(events) shouldBe emptyList() }
        }
    }

    @Test
    fun `T4 a steady-state tick writes nothing to the control row and logs nothing`() {
        val sweep = h.sweep(protectionDays = 30)
        val first = capturingLogEvents(sweepLog) { sweep.finalizeProtection() }
        aligned(first) shouldBe listOf("event=lake.settle.protection_window_aligned days=30")
        val version = controlVersion()

        val second = capturingLogEvents(sweepLog) { sweep.finalizeProtection() }

        withClue("the control row's xmin: the alignment did not write") { controlVersion() shouldBe version }
        aligned(second) shouldBe emptyList()
        window() shouldBe "30 days"
    }

    @Test
    fun `T5 two instances configured alike align once and stamp alike`() {
        val a = h.sweep(protectionDays = 30)
        val b = h.sweep(protectionDays = 30)
        supersede()
        val events =
            capturingLogEvents(sweepLog) {
                a.finalizeProtection() shouldBe 1
                val version = controlVersion()
                b.finalizeProtection() shouldBe 0
                supersede()
                b.finalizeProtection() shouldBe 1
                a.finalizeProtection() shouldBe 0
                withClue("no further write after the one alignment") { controlVersion() shouldBe version }
            }

        aligned(events) shouldBe listOf("event=lake.settle.protection_window_aligned days=30")
        secondsShortOf(30, seq = 1).shouldBeBetween(0.0, SLACK, 0.0)
        secondsShortOf(30, seq = 2).shouldBeBetween(0.0, SLACK, 0.0)
        window() shouldBe "30 days"
    }

    @Test
    fun `T6 an instance stamps with its own window even when another aligned the row in between`() {
        val a = h.sweep(protectionDays = 30)
        val b = h.sweep(protectionDays = 3)
        supersede()
        val events =
            capturingLogEvents(sweepLog) {
                a.alignProtectionWindow() shouldBe 1
                b.alignProtectionWindow() shouldBe 1
                a.stampProtection() shouldBe 1
            }

        withClue("A stamps its configured 30 days, not the row's 3") { secondsShortOf(30, seq = 1).shouldBeBetween(0.0, SLACK, 0.0) }
        window() shouldBe "3 days"
        aligned(events) shouldBe
            listOf("event=lake.settle.protection_window_aligned days=30", "event=lake.settle.protection_window_aligned days=3")
    }

    @Test
    fun `T9 a budget the alignment spent starts no stamp and a later tick stamps`() {
        supersede()
        var checks = 0
        // The tick's first check passes; the one between the alignment and the stamp finds the budget spent.
        h.sweep(budgets = { h.budget { checks++ >= 1 } }, protectionDays = 30).runOnce()

        window() shouldBe "30 days"
        withClue("the stamp did not start after the budget was spent") { deadlines() shouldBe mapOf(1L to null) }
        h.sweep(protectionDays = 30).runOnce()
        secondsShortOf(30, seq = 1).shouldBeBetween(0.0, SLACK, 0.0)
    }

    /** Moves the table's pointer under a fenced lease: the trigger supersedes the current revision, leaving it NULL. */
    private fun supersede() {
        val lease = LakeTestDb.lease(LeaseKind.TABLE, TABLE)
        try {
            h.fencedConnection(lease).use { connection ->
                connection
                    .prepareStatement(
                        "UPDATE iceberg_tables SET metadata_location = ? WHERE table_namespace = 'settle' AND table_name = 'orders'",
                    ).use { statement ->
                        statement.setString(1, "s3://settle/metadata/moved-${++pointers}.json")
                        statement.executeUpdate() shouldBe 1
                    }
            }
        } finally {
            LakeTestDb.leases.release(lease.operationId)
        }
    }

    /** How far the revision's deadline sits before `clock_timestamp()` plus [days]: small and non-negative when stamped from [days]. */
    private fun secondsShortOf(
        days: Int,
        seq: Long,
    ): Double =
        requireNotNull(
            h.jdbc.queryForObject(
                "SELECT EXTRACT(EPOCH FROM (clock_timestamp() + make_interval(days => ?) - protected_until))::float8 " +
                    "FROM lake_table_revisions WHERE seq = ?",
                Double::class.java,
                days,
                seq,
            ),
        ) { "revision $seq is not stamped" }

    /** The deadline as PostgreSQL renders it, so an unchanged one compares byte for byte. */
    private fun deadline(seq: Long): String? =
        h.jdbc.queryForObject("SELECT protected_until::text FROM lake_table_revisions WHERE seq = ?", String::class.java, seq)

    /** Every superseded revision's deadline by `seq`, rendered as [deadline] renders one. */
    private fun deadlines(): Map<Long, String?> =
        h.jdbc
            .query(DEADLINES) { rows, _ -> rows.getLong("seq") to rows.getString("deadline") }
            .toMap()

    private fun window(): String =
        requireNotNull(h.jdbc.queryForObject("SELECT protection_interval::text FROM lake_control WHERE id = 1", String::class.java))

    private fun controlVersion(): String =
        requireNotNull(h.jdbc.queryForObject("SELECT xmin::text FROM lake_control WHERE id = 1", String::class.java))

    private fun aligned(events: List<ch.qos.logback.classic.spi.ILoggingEvent>): List<String> =
        events.map { it.formattedMessage }.filter { it.startsWith("event=lake.settle.protection_window_aligned") }

    private companion object {
        const val TABLE = "settle.orders"
        const val DEADLINES =
            "SELECT seq, protected_until::text AS deadline FROM lake_table_revisions WHERE superseded_at IS NOT NULL ORDER BY seq"

        /** The stamp's `now()` precedes the check's `clock_timestamp()` by the statements between them. */
        const val SLACK = 10.0
    }
}
