package co.datapipelines.lake

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * §8a's lease protocol on the real V53 `lake_leases` table: acquire on a free name, refusal on a busy one,
 * takeover after expiry or deadline with a NEW token, heartbeat capped at the deadline, release by operation —
 * and the E11 (#777) F2 gate: a deadline already past issues NO token. Expiry is staged by UPDATE, never by
 * sleeping.
 */
class LakeLeasesIntegrationTest {
    private val leases = LakeTestDb.leases
    private val jdbc = LakeTestDb.jdbc

    @BeforeEach
    fun clean() {
        LakeTestDb.reset()
    }

    @Test
    fun `a free name is acquired with a random token, and a busy one answers Busy`() {
        val first = LakeTestDb.lease(LeaseKind.TABLE, "sales.orders")
        val row = jdbc.queryForMap("SELECT kind, token, operation_id, holder_instance, operation FROM lake_leases")
        row["kind"] shouldBe "table"
        row["token"] shouldBe first.token
        row["operation_id"] shouldBe first.operationId
        row["holder_instance"] shouldBe "lake-test"
        row["operation"] shouldBe "test"

        val busy = acquire("sales.orders", UUID.randomUUID())
        busy shouldBe LeaseOutcome.Busy
        // The holder is untouched by the refused attempt.
        jdbc.queryForObject("SELECT token FROM lake_leases", UUID::class.java) shouldBe first.token
    }

    @Test
    fun `a deadline already past issues no token on the insert path - the SELECT WHERE form`() {
        val outcome = leases.acquire(LeaseKind.TABLE, "sales.orders", UUID.randomUUID(), MINUTE, leases.catalogNowPlus(PAST), "test")
        outcome shouldBe LeaseOutcome.DeadlinePassed
        count() shouldBe 0
    }

    @Test
    fun `RED - the INSERT VALUES form issues a dead-on-arrival token for a past deadline`() {
        // §8a's earlier statement (E11 F2): the same acquire with VALUES instead of SELECT … WHERE deadline > now().
        val valuesForm =
            LakeLeases.ACQUIRE_SQL
                .replace(") SELECT ", ") VALUES (")
                .replace(" WHERE ?::timestamptz > now() ON CONFLICT", ") ON CONFLICT")
        valuesForm shouldNotBe LakeLeases.ACQUIRE_SQL
        valuesForm shouldContain "VALUES ("
        val deadline = OffsetDateTime.ofInstant(leases.catalogNowPlus(PAST), ZoneOffset.UTC)
        val token =
            jdbc.query(
                valuesForm,
                { rs, _ -> rs.getObject(1, UUID::class.java) },
                "table",
                "sales.orders",
                UUID.randomUUID(),
                MINUTE.seconds.toDouble(),
                deadline,
                deadline,
                "test",
                "lake-test",
                MINUTE.seconds.toDouble(),
            )
        // Red: a token is issued and a row exists whose deadline had passed before it was written.
        token.size shouldBe 1
        jdbc.queryForObject("SELECT count(*) FROM lake_leases WHERE deadline_at <= now()", Long::class.java) shouldBe 1L
    }

    @Test
    fun `an expired lease is taken over with a new token, and the old holder can no longer heartbeat`() {
        val first = LakeTestDb.lease(LeaseKind.TABLE, "sales.orders")
        jdbc.update("UPDATE lake_leases SET expires_at = now() - interval '1 second'")

        val second = acquire("sales.orders", UUID.randomUUID()).shouldBeInstanceOf<LeaseOutcome.Acquired>().lease
        second.token shouldNotBe first.token
        leases.heartbeat(first, MINUTE) shouldBe false
        leases.heartbeat(second, MINUTE) shouldBe true
        count() shouldBe 1
    }

    @Test
    fun `a lease past its deadline is taken over even while unexpired`() {
        val first = LakeTestDb.lease(LeaseKind.NAMESPACE, "sales")
        jdbc.update("UPDATE lake_leases SET deadline_at = now() - interval '1 second'")

        val second =
            leases
                .acquire(LeaseKind.NAMESPACE, "sales", UUID.randomUUID(), MINUTE, leases.catalogNowPlus(HOUR), "test")
                .shouldBeInstanceOf<LeaseOutcome.Acquired>()
                .lease
        second.token shouldNotBe first.token
        leases.heartbeat(first, MINUTE) shouldBe false
    }

    @Test
    fun `a heartbeat never extends past the deadline`() {
        val deadline = leases.catalogNowPlus(MINUTE)
        val lease =
            leases
                .acquire(LeaseKind.TABLE, "sales.orders", UUID.randomUUID(), Duration.ofSeconds(10), deadline, "test")
                .shouldBeInstanceOf<LeaseOutcome.Acquired>()
                .lease
        jdbc.queryForObject("SELECT expires_at < deadline_at FROM lake_leases", Boolean::class.java) shouldBe true

        leases.heartbeat(lease, HOUR) shouldBe true
        jdbc.queryForObject("SELECT expires_at = deadline_at FROM lake_leases", Boolean::class.java) shouldBe true
    }

    @Test
    fun `release removes every lease of the operation and only those`() {
        val operation = UUID.randomUUID()
        LakeTestDb.lease(LeaseKind.NAMESPACE, "sales", operationId = operation)
        LakeTestDb.lease(LeaseKind.TABLE, "sales.orders", operationId = operation)
        val other = LakeTestDb.lease(LeaseKind.TABLE, "sales.items")

        leases.release(operation) shouldBe 2
        jdbc.queryForObject("SELECT operation_id FROM lake_leases", UUID::class.java) shouldBe other.operationId
        leases.release(operation) shouldBe 0
    }

    @Test
    fun `every lease statement is refused inside a Spring transaction`() {
        val tx = TransactionTemplate(DataSourceTransactionManager(LakeTestDb.dataSource))
        val refused =
            shouldThrow<IllegalStateException> {
                tx.executeWithoutResult { acquire("sales.orders", UUID.randomUUID()) }
            }
        refused.message shouldContain "inside a transaction"
        shouldThrow<IllegalStateException> { tx.executeWithoutResult { leases.release(UUID.randomUUID()) } }
        count() shouldBe 0
    }

    @Test
    fun `a non-positive ttl and an unnamed operation are refused before any statement`() {
        shouldThrow<IllegalArgumentException> {
            leases.acquire(LeaseKind.TABLE, "t.t", UUID.randomUUID(), Duration.ZERO, leases.catalogNowPlus(HOUR), "test")
        }
        shouldThrow<IllegalArgumentException> {
            leases.acquire(LeaseKind.TABLE, "t.t", UUID.randomUUID(), MINUTE, leases.catalogNowPlus(HOUR), " ")
        }
        count() shouldBe 0
    }

    @Test
    fun `the handle prints only the token's first eight characters`() {
        val lease = LakeTestDb.lease(LeaseKind.TABLE, "sales.orders")
        lease.toString() shouldContain lease.token.toString().take(8)
        lease.toString().contains(lease.token.toString()) shouldBe false
    }

    private fun acquire(
        name: String,
        operation: UUID,
    ): LeaseOutcome = leases.acquire(LeaseKind.TABLE, name, operation, MINUTE, leases.catalogNowPlus(HOUR), "test")

    private fun count(): Long = jdbc.queryForObject("SELECT count(*) FROM lake_leases", Long::class.java) ?: -1L

    private companion object {
        val MINUTE: Duration = Duration.ofMinutes(1)
        val HOUR: Duration = Duration.ofHours(1)
        val PAST: Duration = Duration.ofSeconds(-10)
    }
}
