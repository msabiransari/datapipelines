package co.datapipelines.lake.delivery

import ch.qos.logback.classic.Logger
import co.datapipelines.lake.LakeTestDb
import co.datapipelines.lake.definitions.LakeApplyState
import co.datapipelines.lake.definitions.LakeAuditEvents
import co.datapipelines.lake.settle.LakeSettlementSweep
import co.datapipelines.lake.settle.SettleBudget
import com.zaxxer.hikari.HikariDataSource
import io.kotest.assertions.fail
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Record §8b, owner ruling D3 (i), lane 799c: the settlement tick is bounded THROUGH verification and repair. The budget
 * is checked before every statement group inside them; the Iceberg metadata read leaves the scheduler thread, so a stalled
 * or trickling object store costs the tick nothing past its deadline, leaves one thread behind and is refused a successor
 * until it ends. Real PostgreSQL, real fence, real sweep; only the object store is scripted. On the sweep path the catalog
 * is a strict double: a `catalogs.open` there fails the case.
 */
class SettlementWholeTickBoundIntegrationTest {
    private val sql = GatedDataSource(LakeTestDb.dataSource)
    private val h =
        DeliverySettlementHarness(
            dataSource = sql,
            statementTimeoutSeconds = STATEMENT_SECONDS,
            transactionTimeoutSeconds = STATEMENT_SECONDS,
        )
    private val lines = SnapshotListAppender()
    private val settleLog = LoggerFactory.getLogger(LakeSettlementSweep::class.java.packageName) as Logger
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "test-scheduled-tick") }

    @BeforeEach
    fun clean() {
        h.reset()
        settleLog.addAppender(lines)
    }

    @AfterEach
    fun detach() {
        settleLog.detachAppender(lines)
        worker.shutdownNow()
        h.close()
    }

    @Test
    fun `B6 a stalled read on verification ends the tick within the bound and refuses a successor until it ends`() {
        val attempt = h.applyingWithEvidence()
        val io = ScriptedFileIo(h.metadataJson(), ScriptedFileIo.Mode.STALL)
        val sweep = h.stallableSweep(io, budgets = productionBudget())

        val first = tick(sweep, io, LakeSettlementSweep.SWEEP_BUDGET)
        println("case=B6-stall tick=1 elapsed_ms=${first.toMillis()} streams=${io.streamsEntered.get()}")
        io.streamsEntered.get() shouldBe 1
        readerLeftTheSchedulerThread(io)
        stuckLines().single() shouldContain "table_id=${h.tableId}"
        stuckLines().single() shouldContain "elapsed_ms="
        sweepLines().single() shouldContain "deferred_stuck=1"
        nothingHeldAndNothingStamped(attempt, LakeApplyState.APPLYING)

        val second = tick(sweep, io, Duration.ZERO)
        println("case=B6-stall tick=2 elapsed_ms=${second.toMillis()} streams=${io.streamsEntered.get()}")
        withClue("the successor tick may not submit a second read while the first is running") { io.streamsEntered.get() shouldBe 1 }
        sweepLines()[1] shouldContain "deferred_stuck=1"
        second.toMillis() shouldBeLessThanOrEqual LakeSettlementSweep.SWEEP_BUDGET.toMillis()
        nothingHeldAndNothingStamped(attempt, LakeApplyState.APPLYING)

        io.mode = ScriptedFileIo.Mode.HEALTHY
        io.release()
        withClue("the abandoned read ends once the store answers") { io.awaitReadsEnded(1, WAIT_MILLIS).shouldBeTrue() }
        nothingHeldAndNothingStamped(attempt, LakeApplyState.APPLYING)

        val third = tick(sweep, io, LakeSettlementSweep.SWEEP_BUDGET)
        println("case=B6-stall tick=3 elapsed_ms=${third.toMillis()} streams=${io.streamsEntered.get()}")
        h.applyState() shouldBe LakeApplyState.APPLIED
        h.audit.events.map { it.event } shouldBe listOf(LakeAuditEvents.APPLIED)
        lines.messages().count { it.startsWith("event=lake.settle.metadata_read_ended ") } shouldBe 1
    }

    @Test
    fun `B6 a stalled read on the semantics repair ends the tick within the bound and rebuilds on a later tick`() {
        h.staleSemantics()
        val io = ScriptedFileIo(h.metadataJson(), ScriptedFileIo.Mode.STALL)
        val sweep = h.stallableSweep(io, budgets = shortBudget())

        val first = tick(sweep, io, SHORT_BUDGET)
        println("case=B6-repair tick=1 elapsed_ms=${first.toMillis()} streams=${io.streamsEntered.get()}")
        io.streamsEntered.get() shouldBe 1
        readerLeftTheSchedulerThread(io)
        sweepLines().single() shouldContain "deferred_stuck=1"
        semanticsRows() shouldBe 0
        leases() shouldBe 0
        activeConnections() shouldBe 0

        tick(sweep, io, SHORT_BUDGET)
        io.streamsEntered.get() shouldBe 1

        io.mode = ScriptedFileIo.Mode.HEALTHY
        io.release()
        io.awaitReadsEnded(1, WAIT_MILLIS).shouldBeTrue()
        semanticsRows() shouldBe 0
        tick(sweep, io, SHORT_BUDGET)
        semanticsRows() shouldBeGreaterThan 0
        leases() shouldBe 0
    }

    @Test
    fun `while a read is stuck no table takes a verification lease and deliveries still settle`() {
        val other = h.addTable("returns")
        h.staleSemantics()
        h.staleSemantics(other, "returns")
        val io = ScriptedFileIo(h.metadataJson(), ScriptedFileIo.Mode.STALL)
        val sweep = h.stallableSweep(io, budgets = shortBudget())

        tick(sweep, io, SHORT_BUDGET)
        io.streamsEntered.get() shouldBe 1

        val row = h.receive(table = other)
        sql.reset()
        repeat(2) { tick(sweep, io, SHORT_BUDGET) }
        io.streamsEntered.get() shouldBe 1
        sql.executed.count { it.startsWith(TAKEOVER) } shouldBe 0
        h.repository.find(row.id)?.state shouldBe LakeDeliveryState.FAILED
        leases() shouldBe 0
    }

    @Test
    fun `B6 a trickling store ends the tick within the bound and its residual is one thread and one read`() {
        val attempt = h.applyingWithEvidence()
        val io = ScriptedFileIo(h.metadataJson(), ScriptedFileIo.Mode.TRICKLE)
        val sweep = h.stallableSweep(io, budgets = shortBudget())

        val first = tick(sweep, io, SHORT_BUDGET)
        val atTickEnd = io.bytesServed.get()
        Thread.sleep(RESIDUAL_MILLIS)
        val after = io.bytesServed.get()
        val total = h.metadataJson().size
        println(
            "case=B6-trickle tick=1 elapsed_ms=${first.toMillis()} bytes_at_tick_end=$atTickEnd " +
                "bytes_${RESIDUAL_MILLIS}ms_later=$after of=$total reader_alive=${io.readerThread?.isAlive} " +
                "reader_name=${io.readerThread?.name} ended=${io.readsEnded.get()} leases=${leases()} " +
                "active_connections=${activeConnections()} whole_file_at_100ms_per_byte_ms=${total * TRICKLE_MILLIS_PER_BYTE}",
        )
        withClue("the abandoned read is still running after the tick ended") {
            after shouldBeGreaterThan atTickEnd
            io.readsEnded.get() shouldBe 0
        }
        readerLeftTheSchedulerThread(io)
        nothingHeldAndNothingStamped(attempt, LakeApplyState.APPLYING)

        tick(sweep, io, SHORT_BUDGET)
        io.streamsEntered.get() shouldBe 1

        io.mode = ScriptedFileIo.Mode.HEALTHY
        io.release()
        io.awaitReadsEnded(1, WAIT_MILLIS).shouldBeTrue()
        nothingHeldAndNothingStamped(attempt, LakeApplyState.APPLYING)
        tick(sweep, io, SHORT_BUDGET)
        h.applyState() shouldBe LakeApplyState.APPLIED
    }

    @Test
    fun `a healthy store verifies through the app template and the store with no catalog session`() {
        h.applyingWithEvidence()
        val io = ScriptedFileIo(h.metadataJson())
        val sweep = h.stallableSweep(io, budgets = productionBudget())

        tick(sweep, io, LakeSettlementSweep.SWEEP_BUDGET)

        h.applyState() shouldBe LakeApplyState.APPLIED
        sql.executed.count { it.startsWith(CATALOG_ROW) } shouldBe 2
        withClue("verification and repair each read the store once") { io.streamsEntered.get() shouldBe 2 }
        semanticsRows() shouldBeGreaterThan 0
        leases() shouldBe 0
    }

    @Test
    fun `B8 a budget spent exactly at the verification gate runs nothing inside verification or repair`() {
        val attempt = h.applyingWithEvidence()
        val io = ScriptedFileIo(h.metadataJson())
        val sweep = h.stallableSweep(io, budgets = spentOnce { sql.executed.any { it.startsWith(PROJECTION) } })

        tick(sweep, io, Duration.ZERO)

        afterProjection() shouldBe emptyList()
        io.streamsEntered.get() shouldBe 0
        nothingHeldAndNothingStamped(attempt, LakeApplyState.APPLYING)
    }

    @Test
    fun `B8 a budget spent exactly at the repair gate runs nothing inside the repair`() {
        h.staleSemantics()
        val io = ScriptedFileIo(h.metadataJson())
        val sweep = h.stallableSweep(io, budgets = spentOnce { sql.executed.any { it.startsWith(FIND_TABLE) } })

        tick(sweep, io, Duration.ZERO)

        withClue("verification found nothing to verify and the repair's gate stopped it") {
            afterProjection() shouldBe listOf("find_table")
        }
        io.streamsEntered.get() shouldBe 0
        semanticsRows() shouldBe 0
    }

    @Test
    fun `every statement group inside verification checks the budget and a spent budget leaves no verdict and no lease`() {
        val found = mutableMapOf<String, List<String>>()
        val streams = mutableMapOf<String, Int>()
        for ((name, spentAt) in VERIFY_CHECKS) {
            h.reset()
            val attempt = h.applyingWithEvidence()
            val io = ScriptedFileIo(h.metadataJson())
            val sweep = h.stallableSweep(io, budgets = spentAtCheck(spentAt))
            tick(sweep, io, Duration.ZERO)
            found[name] = afterProjection()
            streams[name] = io.streamsEntered.get()
            withClue("verification spent at '$name'") { nothingHeldAndNothingStamped(attempt, LakeApplyState.APPLYING) }
        }
        found shouldBe
            mapOf(
                "before find_table" to emptyList(),
                "before acquire" to listOf("find_table"),
                "before evidence" to LEASED + "release",
                "before catalog_row" to LEASED + "evidence" + "release",
                "before submit" to LEASED + "evidence" + "catalog_row" + "release",
                "before find_version" to LEASED + "evidence" + "catalog_row" + "release",
                "before stamp" to LEASED + "evidence" + "catalog_row" + "find_version" + "release",
            )
        streams shouldBe
            mapOf(
                "before find_table" to 0,
                "before acquire" to 0,
                "before evidence" to 0,
                "before catalog_row" to 0,
                "before submit" to 0,
                "before find_version" to 1,
                "before stamp" to 1,
            )
    }

    @Test
    fun `a verification with no evidence checks the budget before it stamps pending`() {
        h.releaseDefinition()
        val attempt = UUID.randomUUID()
        h.jdbc.update("UPDATE lake_tables_managed SET apply_state = 'applying', apply_operation_id = ? WHERE id = ?", attempt, h.tableId)
        val io = ScriptedFileIo(h.metadataJson())

        tick(h.stallableSweep(io, budgets = spentAtCheck(FIRST_VERIFY_CHECK + 3)), io, Duration.ZERO)
        afterProjection() shouldBe LEASED + "evidence" + "release"
        nothingHeldAndNothingStamped(attempt, LakeApplyState.APPLYING)

        tick(h.stallableSweep(io, budgets = { h.budget { false } }), io, Duration.ZERO)
        h.applyState() shouldBe LakeApplyState.PENDING
        io.streamsEntered.get() shouldBe 0
    }

    @Test
    fun `every statement group inside the repair checks the budget and a spent budget rebuilds nothing and holds no lease`() {
        val found = mutableMapOf<String, List<String>>()
        val streams = mutableMapOf<String, Int>()
        for ((name, spentAt) in REPAIR_CHECKS) {
            h.reset()
            h.staleSemantics()
            val io = ScriptedFileIo(h.metadataJson())
            tick(h.stallableSweep(io, budgets = spentAtCheck(spentAt)), io, Duration.ZERO)
            // The verification's own read of the row comes first, on every entry.
            found[name] = afterProjection().drop(1)
            streams[name] = io.streamsEntered.get()
            withClue("repair spent at '$name'") {
                semanticsRows() shouldBe 0
                leases() shouldBe 0
            }
        }
        val repairLease = listOf("find_table", "target", "lock_timeout", "takeover")
        found shouldBe
            mapOf(
                "before find_table" to emptyList(),
                "before target" to listOf("find_table"),
                "before acquire" to listOf("find_table", "target"),
                "before catalog_row" to repairLease + "release",
                "before submit" to repairLease + "catalog_row" + "release",
                "before previous" to repairLease + "catalog_row" + "release",
                "before find_version" to repairLease + "catalog_row" + "previous" + "release",
                "before rebuild" to repairLease + "catalog_row" + "previous" + "find_version" + "release",
            )
        streams["before submit"] shouldBe 0
        streams["before previous"] shouldBe 1
    }

    /** The store was entered on the read's own daemon thread, never on the thread that runs the tick. */
    private fun readerLeftTheSchedulerThread(io: ScriptedFileIo) {
        val reader = requireNotNull(io.readerThread) { "no read was submitted" }
        reader.name shouldBe "dp-lake-metadata"
        reader.isDaemon shouldBe true
    }

    private fun nothingHeldAndNothingStamped(
        attempt: UUID,
        state: LakeApplyState,
    ) {
        h.applyState() shouldBe state
        h.jdbc.queryForObject(
            "SELECT apply_operation_id FROM lake_tables_managed WHERE id = ?",
            UUID::class.java,
            h.tableId,
        ) shouldBe attempt
        h.audit.events shouldBe emptyList()
        leases() shouldBe 0
        activeConnections() shouldBe 0
    }

    /**
     * One tick on a separate thread (the scheduler's, in production), bounded on the test side by [budget] plus the
     * stated overrun: a tick that blocks on the store exits the case red, its latch released so the thread can end.
     */
    private fun tick(
        sweep: LakeSettlementSweep,
        io: ScriptedFileIo,
        budget: Duration,
    ): Duration {
        val limit = budget + PERMITTED_OVERRUN
        val started = catalogClock()
        val run = CompletableFuture.runAsync({ sweep.runOnce() }, worker)
        try {
            run.get(limit.toMillis(), TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            io.release()
            runCatching { run.get(limit.toMillis(), TimeUnit.MILLISECONDS) }
            fail("the tick was still running ${limit.toSeconds()} s after it started (budget + overrun): it waited on the store")
        }
        val elapsed = Duration.between(started, catalogClock())
        elapsed.toMillis() shouldBeLessThanOrEqual limit.toMillis()
        return elapsed
    }

    private fun productionBudget(): () -> SettleBudget =
        {
            SettleBudget.startingNow(h.leases(), LakeSettlementSweep.SWEEP_BUDGET)
        }

    /** A short real budget: the deadline, the monotonic mirror and the read's wait are all production's. */
    private fun shortBudget(): () -> SettleBudget = { SettleBudget.startingNow(h.leases(), SHORT_BUDGET) }

    /** Spent from the [n]th work check of the tick on. */
    private fun spentAtCheck(n: Int): () -> SettleBudget =
        {
            var checks = 0
            sql.reset()
            h.budget { ++checks >= n }
        }

    /** Spent as soon as [condition] holds, with recording restarted at the tick. */
    private fun spentOnce(condition: () -> Boolean): () -> SettleBudget =
        {
            val budget = h.budget(condition)
            sql.reset()
            budget
        }

    /** The named statement groups after the tick's projection repair and before its heartbeat. */
    private fun afterProjection(): List<String> =
        sql.executed
            .toList()
            .dropWhile { !it.startsWith(PROJECTION) }
            .drop(1)
            .takeWhile { !it.startsWith(HEARTBEAT) }
            .map(::groupOf)

    private fun groupOf(statement: String): String =
        when {
            statement.startsWith(FIND_TABLE) -> "find_table"
            statement.startsWith("SELECT t.id, n.name AS namespace") -> "target"
            statement.startsWith("SELECT set_config('lock_timeout'") -> "lock_timeout"
            statement.startsWith(TAKEOVER) -> "takeover"
            statement.startsWith("DELETE FROM lake_leases") -> "release"
            statement.startsWith("SELECT EXISTS (SELECT 1 FROM lake_table_revisions") -> "evidence"
            statement.startsWith(CATALOG_ROW) -> "catalog_row"
            statement.startsWith("SELECT DISTINCT applied_version") -> "previous"
            statement.contains("FROM lake_table_definition_versions") -> "find_version"
            else -> statement
        }

    private fun stuckLines(): List<String> = lines.messages().filter { it.startsWith("event=lake.settle.metadata_read_stuck ") }

    private fun sweepLines(): List<String> = lines.messages().filter { it.startsWith("event=lake.settle.sweep ") }

    private fun leases(): Int = requireNotNull(h.jdbc.queryForObject("SELECT count(*) FROM lake_leases", Int::class.java))

    private fun semanticsRows(): Int =
        requireNotNull(h.jdbc.queryForObject("SELECT count(*) FROM lake_column_semantics WHERE table_id = ?", Int::class.java, h.tableId))

    private fun activeConnections(): Int = (LakeTestDb.dataSource as HikariDataSource).hikariPoolMXBean.activeConnections

    private fun catalogClock(): Instant =
        requireNotNull(LakeTestDb.jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime::class.java)).toInstant()

    private companion object {
        const val STATEMENT_SECONDS = 5
        const val WAIT_MILLIS = 5_000L
        const val RESIDUAL_MILLIS = 1_000L
        const val TRICKLE_MILLIS_PER_BYTE = 100

        /** The number docs/lake.md §8 states, as a literal: `SettlementOverrunDerivationTest` derives it from the source. */
        val PERMITTED_OVERRUN: Duration = Duration.ofSeconds(16)
        val SHORT_BUDGET: Duration = Duration.ofSeconds(1)

        const val FIND_TABLE = "SELECT t.id, t.namespace_id"
        const val TAKEOVER = "INSERT INTO lake_leases"
        const val CATALOG_ROW = "SELECT metadata_location FROM iceberg_tables"
        const val PROJECTION = "INSERT INTO lake_tables (datasource_id"
        const val HEARTBEAT = "UPDATE lake_control SET last_sweep_at"

        /**
         * The tick's work checks before verification's first, for a table with no deliveries: the protection page, the
         * table page, before and after the table's mode read, before the target, before the row page, the projection.
         */
        const val FIRST_VERIFY_CHECK = 8

        val LEASED = listOf("find_table", "lock_timeout", "takeover")

        val VERIFY_CHECKS =
            listOf(
                "before find_table",
                "before acquire",
                "before evidence",
                "before catalog_row",
                "before submit",
                "before find_version",
                "before stamp",
            ).withIndex().associate { (index, name) -> name to FIRST_VERIFY_CHECK + index }

        /** Verification's gate, then the repair's checks in order. */
        val REPAIR_CHECKS =
            listOf(
                "before find_table",
                "before target",
                "before acquire",
                "before catalog_row",
                "before submit",
                "before previous",
                "before find_version",
                "before rebuild",
            ).withIndex().associate { (index, name) -> name to FIRST_VERIFY_CHECK + 1 + index }
    }
}
