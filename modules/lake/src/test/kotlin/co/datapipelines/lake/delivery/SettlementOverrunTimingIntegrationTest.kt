package co.datapipelines.lake.delivery

import co.datapipelines.lake.LakeLeases
import co.datapipelines.lake.LakeTestDb
import co.datapipelines.lake.LeaseKind
import io.kotest.assertions.withClue
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/**
 * Record §8b's permitted overrun, measured (lane 799c B.7). The budget is spent the moment the statement group under test
 * starts, so that group is the one in flight at the deadline; every statement then runs under production's bounds, a
 * five-second statement timeout and a five-second transaction timeout. A gate that sleeps models a statement taking
 * nearly its bound; a row lock on another connection makes the real statement wait on the server, under its real timer.
 * Each case prints its overrun on the database clock, from the group's start to the tick's end.
 */
class SettlementOverrunTimingIntegrationTest {
    private val sql = GatedDataSource(LakeTestDb.dataSource)
    private val h =
        DeliverySettlementHarness(
            dataSource = sql,
            statementTimeoutSeconds = STATEMENT_SECONDS,
            transactionTimeoutSeconds = STATEMENT_SECONDS,
        )
    private val holds = CopyOnWriteArrayList<Connection>()
    private val inFlightFrom = AtomicReference<Instant?>()

    @BeforeEach
    fun clean() {
        h.reset()
    }

    @AfterEach
    fun releaseHolds() {
        holds.forEach { hold ->
            hold.rollback()
            hold.close()
        }
    }

    @Test
    fun `an acquire in flight at the deadline ends at its transaction bound however long the lock wait it was given`() {
        publishing()
        val holder = LakeTestDb.lease(LeaseKind.TABLE, LEASE)
        LakeTestDb.jdbc.update("UPDATE lake_leases SET expires_at = now() - interval '1 second' WHERE operation_id = ?", holder.operationId)
        hold("SELECT 1 FROM lake_leases WHERE name = '$LEASE' FOR UPDATE")
        var heartbeatAt: Instant? = null
        sql.gate(SET_LOCK_TIMEOUT) {
            spend()
            Thread.sleep(NEAR_BOUND_MILLIS)
        }
        sql.gate(HEARTBEAT) { heartbeatAt = catalogClock() }

        val overrun = tick(h.leases(lockTimeout = Duration.ofSeconds(LONG_LOCK_WAIT_SECONDS)))

        val unit = Duration.between(inFlightFrom.get(), heartbeatAt)
        println("case=B7-acquire unit_ms=${unit.toMillis()} overrun_ms=${overrun.toMillis()}")
        withClue("the acquire's transaction, not its lock wait and not two statement timeouts, ends it") {
            unit.toMillis() shouldBeLessThanOrEqual ONE_TRANSACTION.toMillis()
        }
        overrun.toMillis() shouldBeLessThanOrEqual PERMITTED_OVERRUN.toMillis()
    }

    @Test
    fun `the largest refused acquire plus its comparison and the heartbeat stays inside the overrun`() {
        publishing()
        LakeTestDb.lease(LeaseKind.TABLE, LEASE)
        hold("SELECT id FROM lake_control WHERE id = 1 FOR UPDATE")
        sql.gate(SET_LOCK_TIMEOUT) {
            spend()
            Thread.sleep(NEAR_BOUND_MILLIS)
        }
        sql.gate(TAKEOVER) { Thread.sleep(ROUNDING_MILLIS) }
        sql.gate(DEADLINE_COMPARISON) { Thread.sleep(NEAR_BOUND_MILLIS) }

        val overrun = tick(h.leases())

        println("case=B7-refused-acquire overrun_ms=${overrun.toMillis()} bound_ms=${PERMITTED_OVERRUN.toMillis()}")
        overrun.toMillis() shouldBeGreaterThanOrEqual FAR_INSIDE.toMillis()
        overrun.toMillis() shouldBeLessThanOrEqual PERMITTED_OVERRUN.toMillis()
    }

    @Test
    fun `a conclusion in flight at the deadline plus the release and the heartbeat stays inside the overrun`() {
        val row = publishing()
        hold("SELECT id FROM lake_control WHERE id = 1 FOR UPDATE")
        sql.gate(CONCLUDE) {
            spend()
            hold("SELECT 1 FROM lake_delivery_attempts WHERE operation_id = '${row.operationId}' FOR UPDATE")
            Thread.sleep(NEAR_BOUND_MILLIS)
        }
        sql.gate(RELEASE) { Thread.sleep(NEAR_BOUND_MILLIS) }

        val overrun = tick(h.leases())

        println("case=B7-conclude overrun_ms=${overrun.toMillis()} bound_ms=${PERMITTED_OVERRUN.toMillis()}")
        h.repository.find(row.id)?.state shouldBe LakeDeliveryState.PUBLISHING
        overrun.toMillis() shouldBeGreaterThanOrEqual FAR_INSIDE.toMillis()
        overrun.toMillis() shouldBeLessThanOrEqual PERMITTED_OVERRUN.toMillis()
    }

    @Test
    fun `a release held past its constant ends at the statement timeout, not at the hold`() {
        publishing()
        var heartbeatAt: Instant? = null
        sql.gate(RELEASE) {
            spend()
            hold("SELECT 1 FROM lake_leases WHERE name = '$LEASE' FOR UPDATE")
        }
        sql.gate(HEARTBEAT) { heartbeatAt = catalogClock() }

        val overrun = tick(h.leases())

        val unit = Duration.between(inFlightFrom.get(), heartbeatAt)
        println("case=B7-release-past-constant unit_ms=${unit.toMillis()} overrun_ms=${overrun.toMillis()} still_held=true")
        unit.toMillis() shouldBeGreaterThanOrEqual (ONE_STATEMENT - TIMER_SLACK).toMillis()
        unit.toMillis() shouldBeLessThanOrEqual (ONE_STATEMENT + TIMER_SLACK).toMillis()
        withClue("the hold outlived the tick; the lease row its release could not delete dies at its deadline") {
            LakeTestDb.jdbc.queryForObject(
                "SELECT count(*) FROM lake_leases WHERE name = ?",
                Int::class.java,
                LEASE,
            ) shouldBe 1
        }
    }

    @Test
    fun `a heartbeat held past its constant ends at the statement timeout, not at the hold`() {
        hold("SELECT id FROM lake_control WHERE id = 1 FOR UPDATE")
        sql.gate(HEARTBEAT) { spend() }

        val overrun = tick(h.leases())

        println("case=B7-heartbeat-past-constant overrun_ms=${overrun.toMillis()} still_held=true")
        overrun.toMillis() shouldBeGreaterThanOrEqual (ONE_STATEMENT - TIMER_SLACK).toMillis()
        overrun.toMillis() shouldBeLessThanOrEqual (ONE_STATEMENT + TIMER_SLACK).toMillis()
    }

    /** A delivery mid-publish whose attempt is gone: the tick's longest row, a takeover then a conclusion. */
    private fun publishing(): LakeDelivery {
        val received = h.receive()
        check(h.ledger.beginPublishing(received))
        return requireNotNull(h.repository.find(received.id))
    }

    /** The deadline: the group running now is the one in flight at it, and every later check finds the budget spent. */
    private fun spend() {
        inFlightFrom.set(catalogClock())
    }

    /**
     * One tick under a budget spent at [spend]; the heartbeat may throw, as production's scheduler expects. Answers the
     * time from the deadline to the tick's end on the database clock.
     */
    private fun tick(leases: LakeLeases): Duration {
        val sweep = h.sweep(leases = leases, budgets = { h.budget { inFlightFrom.get() != null } })
        runCatching { sweep.runOnce() }
        return Duration.between(inFlightFrom.get(), catalogClock())
    }

    /** Takes a row lock [statement] selects on a connection of its own, held until the case ends. */
    private fun hold(statement: String) {
        val connection = LakeTestDb.connect()
        connection.autoCommit = false
        connection.createStatement().use { it.executeQuery(statement).close() }
        holds += connection
    }

    private fun catalogClock(): Instant =
        requireNotNull(LakeTestDb.jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime::class.java)).toInstant()

    private companion object {
        const val STATEMENT_SECONDS = 5
        const val LEASE = "settle.orders"
        const val LONG_LOCK_WAIT_SECONDS = 12L

        /** A statement taking nearly its five-second bound; leaves the transaction's last, rounded-up second to the next. */
        const val NEAR_BOUND_MILLIS = 4_600L
        const val ROUNDING_MILLIS = 900L

        val ONE_STATEMENT: Duration = Duration.ofSeconds(5)
        val TIMER_SLACK: Duration = Duration.ofMillis(800)

        /** Spring rounds the transaction's remaining time UP to whole seconds per statement, so the bound is 6 s, not 5. */
        val ONE_TRANSACTION: Duration = Duration.ofSeconds(6)

        /** The number docs/lake.md §8 states, as a literal: `SettlementOverrunDerivationTest` derives it from the source. */
        val PERMITTED_OVERRUN: Duration = Duration.ofSeconds(16)

        /** A floor on the worst cases: the plants really held every component, or the case measured nothing. */
        val FAR_INSIDE: Duration = Duration.ofSeconds(14)

        const val SET_LOCK_TIMEOUT = "SELECT set_config('lock_timeout'"
        const val TAKEOVER = "INSERT INTO lake_leases"
        const val DEADLINE_COMPARISON = "SELECT ?::timestamptz <= now()"
        const val RELEASE = "DELETE FROM lake_leases"
        const val HEARTBEAT = "UPDATE lake_control SET last_sweep_at"
        const val CONCLUDE = "UPDATE lake_deliveries SET state = ?"
    }
}
