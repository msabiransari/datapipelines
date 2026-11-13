package co.datapipelines.lake.delivery

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import co.datapipelines.lake.LakeTestDb
import co.datapipelines.lake.settle.LakeSettlementSweep
import co.datapipelines.lake.settle.SettleBudget
import co.datapipelines.lake.settle.SettleProgress
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainOnly
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.jdbc.datasource.DelegatingDataSource
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Statement
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import javax.sql.DataSource

/**
 * Record §8b "Per-tick work bound" (lane 799b; the lander's refusal 6671398448 of #799 at `e70dd30b3`): a settlement
 * tick consults its budget before every row and statement group, settles at most one page of rows per table, and a
 * per-table cursor reaches rows that live, busy or failing rows ahead of them would otherwise hide. Real PostgreSQL, real
 * fence, real ledger with a recording audit sink; the sweep is the production class over the same harness. Every
 * statement the settlement sends is recorded, because a spent budget is about work started, not only rows changed.
 */
class SettlementWorkBoundIntegrationTest {
    private val sql = RecordingDataSource(LakeTestDb.dataSource)
    private val h = DeliverySettlementHarness(dataSource = sql)
    private val lines = ListAppender<ILoggingEvent>() // raw-list-appender: synchronous — every sweep tick runs on the test thread
    private val sweepLog = LoggerFactory.getLogger(LakeSettlementSweep::class.java) as Logger

    @BeforeEach
    fun clean() {
        h.reset()
        lines.start()
        sweepLog.addAppender(lines)
    }

    @AfterEach
    fun detach() {
        sweepLog.detachAppender(lines)
    }

    @Test
    fun `B1 an expired budget settles nothing and a fresh one settles with its strict audit`() {
        val row = h.receive()
        settleUntil(Instant.EPOCH) shouldBe 0
        // The check before the target read: a spent budget sends nothing at all, so no row's observation either.
        sql.executed shouldBe emptyList()
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
    fun `B1 a budget spent after the first row stops the page there and the next page resumes after it`() {
        val rows = h.backlog(POISON_BACKLOG)
        h.settler().settle(h.tableId, spentAfterSettled(1), null, PAGE) shouldBe SettleProgress(1, 1, rows[0].id)
        rows.map { h.repository.find(it.id)?.state } shouldBe
            listOf(LakeDeliveryState.FAILED, LakeDeliveryState.RECEIVED, LakeDeliveryState.RECEIVED)

        h.settler().settle(h.tableId, fresh(), rows[0].id, PAGE) shouldBe SettleProgress(2, 2, null)
        rows.map { h.repository.find(it.id)?.state } shouldContainOnly listOf(LakeDeliveryState.FAILED)
    }

    @Test
    fun `B2 a backlog drains at most one page per tick within the budget and completely across ticks`() {
        LakeSettlementSweep.SETTLE_PAGE_SIZE shouldBe PAGE
        LakeSettlementSweep.SWEEP_BUDGET shouldBe SWEEP_BUDGET
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
        perTick shouldBe listOf(PAGE, PAGE, PAGE, 0)
        settled() shouldBe total
        h.audit.events.count { it.event == LakeDeliveryAuditEvents.SETTLED } shouldBe total
        sweepLines() shouldBe
            listOf(
                "tables=1 rows=$PAGE changed=$PAGE exhausted=false",
                "tables=1 rows=$PAGE changed=$PAGE exhausted=false",
                "tables=1 rows=$PAGE changed=$PAGE exhausted=false",
                "tables=1 rows=0 changed=0 exhausted=false",
            )
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

    @Test
    fun `B4 a table whose page spends the budget does not keep the next table from its turn`() {
        val other = h.addTable("returns")
        h.backlog(2)
        h.backlog(2, table = other)
        val (first, second) = h.jdbc.queryForList("SELECT id FROM lake_tables_managed ORDER BY id", UUID::class.java)
        val sweep = h.sweep(budgets = { spentAfterSettled(1) })

        sweep.runOnce()
        listOf(settled(first), settled(second)) shouldBe listOf(1, 0)
        sweep.runOnce()
        listOf(settled(first), settled(second)) shouldBe listOf(1, 1)
        sweep.runOnce()
        sweep.runOnce()
        listOf(settled(first), settled(second)) shouldBe listOf(2, 2)
        sweepLines() shouldContainOnly listOf("tables=1 rows=1 changed=1 exhausted=true")
    }

    @Test
    fun `every sweep step checks the budget and a spent budget starts no later step`() {
        val row = h.receive()
        val sent = mutableMapOf<String, List<String>>()
        for ((step, spentAt) in STEPS_TO_THE_ROW) {
            h.sweep(budgets = { spentAtCheck(spentAt) }).runOnce()
            sent[step] = sql.executed.map(::groupOf)
            h.repository.find(row.id)?.state shouldBe LakeDeliveryState.RECEIVED
        }
        sent shouldBe
            mapOf(
                "before protection" to listOf("heartbeat"),
                "before stamp" to listOf("alignment", "heartbeat"),
                "before tables" to listOf("alignment", "protection", "heartbeat"),
                "before mode" to listOf("alignment", "protection", "tables", "heartbeat"),
                "after mode" to listOf("alignment", "protection", "tables", "mode", "heartbeat"),
                "before target" to listOf("alignment", "protection", "tables", "mode", "heartbeat"),
                "before page" to listOf("alignment", "protection", "tables", "mode", "target", "heartbeat"),
                "before row" to listOf("alignment", "protection", "tables", "mode", "target", "page", "heartbeat"),
            )
        sweepLines() shouldBe List(STEPS_BEFORE_THE_TABLE) { "tables=0 rows=0 changed=0 exhausted=true" } +
            List(STEPS_TO_THE_ROW.size - STEPS_BEFORE_THE_TABLE) { "tables=1 rows=0 changed=0 exhausted=true" }
        h.audit.events shouldBe emptyList()
    }

    @Test
    fun `the steps after a table's rows check the budget too and the tick has no unnamed check`() {
        val sent = mutableMapOf<String, List<String>>()
        for ((step, spentAt) in STEPS_AFTER_THE_ROWS) {
            h.sweep(budgets = { spentAtCheck(spentAt) }).runOnce()
            sent[step] = sql.executed.map(::groupOf)
        }
        val toThePage = listOf("alignment", "protection", "tables", "mode", "target", "page")
        sent shouldBe
            mapOf(
                "before projection" to toThePage + "heartbeat",
                "before verification" to toThePage + "projection" + "heartbeat",
                "before repair" to toThePage + "projection" + "find_table" + "heartbeat",
            )

        var checks = 0
        h
            .sweep(
                budgets = {
                    recorded {
                        checks++
                        false
                    }
                },
            ).runOnce()
        withClue("every check is named above; the last one is the tick's exhausted flag") { checks shouldBe SWEEP_CHECKS + 1 }
    }

    @Test
    fun `a tick whose budget is spent before its table page keeps every row cursor`() {
        val rows = h.backlog(PAGE + PAGE / 2)
        val lease = h.livePublishing(rows.take(PAGE))
        var budget: () -> SettleBudget = { fresh() }
        val sweep = h.sweep(budgets = { budget() })

        tick(sweep) shouldBe 0
        budget = { spentAtCheck(STEPS_TO_THE_ROW.getValue("before tables")) }
        tick(sweep) shouldBe 0
        sql.executed.none { groupOf(it) == "tables" } shouldBe true
        budget = { fresh() }
        // Resumes after the live page; a forgotten cursor would revisit it and change nothing.
        tick(sweep) shouldBe PAGE / 2
        LakeTestDb.leases.release(lease.operationId)
    }

    @Test
    fun `a RECEIVED row whose budget is spent after its observation is not concluded`() {
        val row = h.receive()
        h.settler().settle(h.tableId, spentAtCheck(CONCLUSION_CHECK), null, PAGE) shouldBe SettleProgress(0, 0, null)
        sql.executed.any { "pipeline_executions" in it } shouldBe true
        sql.executed.none { it.startsWith("UPDATE lake_deliveries") } shouldBe true
        h.repository.find(row.id)?.state shouldBe LakeDeliveryState.RECEIVED
        h.audit.events shouldBe emptyList()
    }

    @Test
    fun `every PUBLISHING statement group checks the budget and a spent budget leaves no verdict and no lease`() {
        val received = h.receive()
        check(h.ledger.beginPublishing(received))
        val row = requireNotNull(h.repository.find(received.id))
        val sent = mutableMapOf<Int, List<String>>()
        for (spentAt in 1..PUBLISHING_CHECKS) {
            h.settler().settle(h.tableId, spentAtCheck(spentAt), null, PAGE).changed shouldBe 0
            sent[spentAt] = sql.executed.toList()
            h.repository.find(row.id)?.state shouldBe LakeDeliveryState.PUBLISHING
            h.jdbc.queryForObject("SELECT count(*) FROM lake_leases", Int::class.java) shouldBe 0
        }
        // 1 before the target, 2 before the page, 3 before the row: no observation. 4 before the takeover. 5 before the
        // evidence read. 6 before the conclusion.
        sent.mapValues { (_, statements) -> statements.map(::groupOf) } shouldBe
            mapOf(
                1 to emptyList(),
                2 to listOf("target"),
                3 to listOf("target", "page"),
                4 to listOf("target", "page", "observation"),
                5 to listOf("target", "page", "observation", "lock_timeout", "takeover", "release"),
                6 to listOf("target", "page", "observation", "lock_timeout", "takeover", "evidence", "release"),
            )
        h.audit.events shouldBe emptyList()

        h.settler().settle(h.tableId, fresh(), null, PAGE).changed shouldBe 1
        h.repository.find(row.id)?.state shouldBe LakeDeliveryState.FAILED
        h.audit.events.map { it.event } shouldBe listOf(LakeDeliveryAuditEvents.FAILED, LakeDeliveryAuditEvents.SETTLED)
    }

    @Test
    fun `the projection repair does not start once the budget is spent`() {
        h.settler().settle(h.tableId, spentAtCheck(PROJECTION_CHECK), null, PAGE) shouldBe SettleProgress(0, 0, null)
        sql.executed.map(::groupOf) shouldBe listOf("target", "page")
        h.settler().settle(h.tableId, fresh(), null, PAGE) shouldBe SettleProgress(0, 0, null)
        sql.executed.count { it.startsWith(REPAIR) } shouldBe 1
    }

    @Test
    fun `a request path settles its own delivery and leaves the table's other rows to the sweep`() {
        val own = h.receive(DeliveryKey("orders", "own"))
        val other = h.receive(DeliveryKey("orders", "other"))
        h.settler().settleDelivery(own.id, h.leases().catalogNowPlus(Duration.ofMinutes(1))) shouldBe 1
        h.repository.find(own.id)?.state shouldBe LakeDeliveryState.FAILED
        h.repository.find(other.id)?.state shouldBe LakeDeliveryState.RECEIVED
        h.audit.events.map { it.details["delivery_id"] } shouldContainOnly listOf(own.id.toString())
    }

    @Test
    fun `a request path past its deadline starts no row`() {
        val own = h.receive()
        sql.reset()
        h.settler().settleDelivery(own.id, Instant.EPOCH) shouldBe 0
        sql.executed.none { "pipeline_executions" in it || "lake_deliveries" in it || "lake_leases" in it } shouldBe true
        h.repository.find(own.id)?.state shouldBe LakeDeliveryState.RECEIVED
        h.audit.events shouldBe emptyList()
    }

    /** The settlement call under test, one page from the first row; its budget ends at the database-clock [deadline]. */
    private fun settleUntil(deadline: Instant): Int {
        val budget = SettleBudget.until(h.leases(), deadline)
        sql.reset()
        return h.settler().settle(h.tableId, budget, null, PAGE).changed
    }

    /** A budget whose deadline is a minute away and whose work checks never expire; recording starts with it. */
    private fun fresh(): SettleBudget = recorded { false }

    /** Spent from its [n]th work check on: pins where each check sits in the row path. */
    private fun spentAtCheck(n: Int): SettleBudget {
        var checks = 0
        return recorded { ++checks >= n }
    }

    /** Spent once [n] more deliveries have been settled (their SETTLED audit row written) than when it was minted. */
    private fun spentAfterSettled(n: Int): SettleBudget {
        val base = settledEvents()
        return recorded { settledEvents() >= base + n }
    }

    private fun recorded(expired: () -> Boolean): SettleBudget {
        val budget = h.budget(expired)
        sql.reset()
        return budget
    }

    private fun settledEvents(): Int = h.audit.events.count { it.event == LakeDeliveryAuditEvents.SETTLED }

    /** One sweep tick; answers how many of this table's deliveries it moved to a terminal state. */
    private fun tick(sweep: LakeSettlementSweep): Int {
        val before = settled()
        sweep.runOnce()
        return settled() - before
    }

    private fun settled(table: UUID = h.tableId): Int =
        requireNotNull(
            h.jdbc.queryForObject(
                "SELECT count(*) FROM lake_deliveries WHERE table_id = ? AND state IN ('PUBLISHED', 'FAILED')",
                Int::class.java,
                table,
            ),
        )

    /** The counted part of every `lake.settle.sweep` line, in order. */
    private fun sweepLines(): List<String> =
        lines.list
            .map { it.formattedMessage }
            .filter { it.startsWith("event=lake.settle.sweep ") }
            .map { it.substringAfter("event=lake.settle.sweep ").substringBefore(" deferred_") }

    private fun catalogClock(): Instant =
        requireNotNull(h.jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime::class.java)).toInstant()

    /** Names a sweep or settlement statement by the step or statement group it belongs to. */
    @Suppress("CyclomaticComplexMethod") // One flat lookup: a branch per group, in match order.
    private fun groupOf(statement: String): String =
        when {
            statement.startsWith("UPDATE lake_control SET protection_interval") -> "alignment"
            statement.startsWith("UPDATE lake_table_revisions r SET protected_until") -> "protection"
            statement.startsWith("SELECT id FROM lake_tables_managed") -> "tables"
            statement.startsWith("SELECT mode = ") -> "mode"
            statement.startsWith("UPDATE lake_control SET last_sweep_at") -> "heartbeat"
            statement.startsWith("SELECT t.id, t.namespace_id") -> "find_table"
            statement.contains("FROM lake_tables_managed t") -> "target"
            statement.contains("FROM lake_deliveries WHERE table_id") -> "page"
            statement.startsWith("SELECT EXISTS (SELECT 1 FROM lake_leases") -> "observation"
            statement.startsWith("INSERT INTO lake_leases") -> "takeover"
            statement.startsWith("DELETE FROM lake_leases") -> "release"
            statement.contains("FROM lake_table_revisions r") -> "evidence"
            statement.startsWith("SELECT set_config('lock_timeout'") -> "lock_timeout"
            statement.startsWith(REPAIR) -> "projection"
            else -> statement
        }

    /** Records the SQL text of every statement executed through this source, in order (exemplar: #331's counting source). */
    private class RecordingDataSource(
        private val target: DataSource,
    ) : DelegatingDataSource(target) {
        val executed = mutableListOf<String>()

        fun reset() = executed.clear()

        override fun getConnection(): Connection = connection(target.connection)

        private fun connection(raw: Connection): Connection =
            proxy(Connection::class.java) { method, args ->
                val result = invoke(raw, method, args)
                when (method.name) {
                    "prepareStatement" -> statement(result as PreparedStatement, PreparedStatement::class.java, args?.get(0) as String)
                    "createStatement" -> statement(result as Statement, Statement::class.java, null)
                    else -> result
                }
            }

        private fun <S : Statement> statement(
            raw: S,
            type: Class<S>,
            prepared: String?,
        ): S =
            proxy(type) { method, args ->
                if (method.name.startsWith("execute")) executed += prepared ?: (args?.firstOrNull() as? String ?: method.name)
                invoke(raw, method, args)
            }

        private fun <T> proxy(
            type: Class<T>,
            handler: (java.lang.reflect.Method, Array<Any?>?) -> Any?,
        ): T = type.cast(Proxy.newProxyInstance(javaClass.classLoader, arrayOf(type)) { _, method, args -> handler(method, args) })

        private fun invoke(
            raw: Any,
            method: java.lang.reflect.Method,
            args: Array<Any?>?,
        ): Any? =
            try {
                method.invoke(raw, *(args ?: emptyArray()))
            } catch (failure: InvocationTargetException) {
                throw failure.targetException
            }
    }

    private companion object {
        /** Production's per-table row page (D1), pinned as a literal so a changed page re-opens its derivation. */
        const val PAGE = 50
        const val BACKLOG_PAGES = 3
        const val POISON_BACKLOG = 3
        const val PUBLISHING_CHECKS = 6

        /** In one settlement call: 1 before the target, 2 before the page, then the row, then its conclusion. */
        const val CONCLUSION_CHECK = 4

        /** In one settlement call over an empty page: 1 before the target, 2 before the page, 3 before the projection. */
        const val PROJECTION_CHECK = 3

        /**
         * Every check of a tick over one table with no rows, in the order the tick meets them. #939: the protection step
         * is the window alignment, then the stamp, with a check before each.
         */
        const val SWEEP_CHECKS = 10
        const val STEPS_BEFORE_THE_TABLE = 5
        val STEPS_TO_THE_ROW =
            listOf(
                "before protection",
                "before stamp",
                "before tables",
                "before mode",
                "after mode",
                "before target",
                "before page",
                "before row",
            )
                .withIndex()
                .associate { (index, step) -> step to index + 1 }
        val STEPS_AFTER_THE_ROWS = mapOf("before projection" to 8, "before verification" to 9, "before repair" to 10)
        const val REPAIR = "INSERT INTO lake_tables (datasource_id"

        /**
         * Production's tick budget and docs/lake.md §8's permitted overrun past it, as literals so a changed number
         * re-opens this file; `SettlementOverrunDerivationTest` derives the overrun from the source.
         */
        val SWEEP_BUDGET: Duration = Duration.ofSeconds(10)
        val PERMITTED_OVERRUN: Duration = Duration.ofSeconds(16)
    }
}
