package co.datapipelines.lake

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.SQLException
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * V53's fence (`dp_fence`) and revision log (`dp_revlog`, `dp_revlog_drop`), driven by plain SQL on a connection
 * of their own — the statements Iceberg's `JdbcUtil` issues, without the library in the way. Each refusal is
 * SQLSTATE `DP001` (no live lease of this operation on the written name) or `DP002` (restoring). The red cases
 * weaken the shipped DDL INSIDE a transaction that is rolled back, so the shared database never loses its fence.
 */
class LakeFenceIntegrationTest {
    private val jdbc = LakeTestDb.jdbc

    @BeforeEach
    fun clean() {
        LakeTestDb.reset()
    }

    @Test
    fun `a write with no lease token is refused on both catalog tables`() {
        LakeTestDb.connect().use { connection ->
            sqlState { connection.execute(INSERT_TABLE, "sales", "orders", "s3://b/m0.json") } shouldBe DP001
            sqlState { connection.execute(INSERT_NAMESPACE, "sales") } shouldBe DP001
        }
        catalogRows() shouldBe 0L
    }

    @Test
    fun `a leased write lands and writes its revision row with the operation id`() {
        val lease = LakeTestDb.lease(LeaseKind.TABLE, "sales.orders")
        session(lease).use { connection ->
            connection.execute(INSERT_TABLE, "sales", "orders", "s3://b/m0.json")
            connection.execute(UPDATE_POINTER, "s3://b/m1.json", "sales", "orders", "s3://b/m0.json") shouldBe 1
        }
        val revisions = revisions()
        revisions shouldHaveSize 2
        revisions[0]["metadata_location"] shouldBe "s3://b/m0.json"
        revisions[0]["superseded_at"].shouldNotBeNull()
        revisions[0]["protected_until"].shouldBeNull()
        revisions[1]["metadata_location"] shouldBe "s3://b/m1.json"
        revisions[1]["previous_metadata_location"] shouldBe "s3://b/m0.json"
        revisions[1]["superseded_at"].shouldBeNull()
        revisions.forEach { it["operation_id"] shouldBe lease.operationId }
    }

    @Test
    fun `a namespace write needs the namespace lease`() {
        val lease = LakeTestDb.lease(LeaseKind.NAMESPACE, "sales")
        session(lease).use { connection ->
            connection.execute(INSERT_NAMESPACE, "sales")
            sqlState { connection.execute(INSERT_NAMESPACE, "other") } shouldBe DP001
        }
        jdbc.queryForObject("SELECT count(*) FROM iceberg_namespace_properties", Long::class.java) shouldBe 1L
    }

    @Test
    fun `a stale token, another operation's id, the wrong name and an expired lease are all refused`() {
        val lease = LakeTestDb.lease(LeaseKind.TABLE, "sales.orders")
        // Wrong name: the lease is on sales.orders.
        session(lease).use { sqlState { it.execute(INSERT_TABLE, "sales", "items", "s3://b/x.json") } shouldBe DP001 }
        // The right token under another operation id.
        session(lease.copy(operationId = UUID.randomUUID())).use {
            sqlState { it.execute(INSERT_TABLE, "sales", "orders", "s3://b/x.json") } shouldBe DP001
        }
        // A fabricated token for the right operation.
        session(lease.copy(token = UUID.randomUUID())).use {
            sqlState { it.execute(INSERT_TABLE, "sales", "orders", "s3://b/x.json") } shouldBe DP001
        }
        // Expired.
        jdbc.update("UPDATE lake_leases SET expires_at = now() - interval '1 second'")
        session(lease).use { sqlState { it.execute(INSERT_TABLE, "sales", "orders", "s3://b/x.json") } shouldBe DP001 }
        // Taken over: the holder's connection still carries the old token.
        jdbc.update("UPDATE lake_leases SET expires_at = deadline_at, token = gen_random_uuid()")
        session(lease).use { sqlState { it.execute(INSERT_TABLE, "sales", "orders", "s3://b/x.json") } shouldBe DP001 }
        catalogRows() shouldBe 0L
    }

    @Test
    fun `restoring refuses every catalog write with DP002, even under a live lease`() {
        val lease = LakeTestDb.lease(LeaseKind.TABLE, "sales.orders")
        jdbc.update("UPDATE lake_control SET mode = 'restoring'")
        session(lease).use { sqlState { it.execute(INSERT_TABLE, "sales", "orders", "s3://b/m0.json") } shouldBe DP002 }
        catalogRows() shouldBe 0L
    }

    @Test
    fun `a rename needs the lease on the OLD name and the NEW name`() {
        val operation = UUID.randomUUID()
        val old = LakeTestDb.lease(LeaseKind.TABLE, "sales.orders", operationId = operation)
        session(old).use { it.execute(INSERT_TABLE, "sales", "orders", "s3://b/m0.json") }
        // NEW only: refused — the E1 F5 case, the shipped OLD-name check.
        val renamer = UUID.randomUUID()
        val newOnly = LakeTestDb.lease(LeaseKind.TABLE, "sales.orders_v2", operationId = renamer)
        session(newOnly).use { sqlState { it.execute(RENAME, "orders_v2", "sales", "orders") } shouldBe DP001 }
        // OLD only (the old holder renames to a name it has no lease on): refused by the NEW-name check.
        session(old).use { sqlState { it.execute(RENAME, "orders_v3", "sales", "orders") } shouldBe DP001 }
        // OLD + NEW, one operation: admitted.
        LakeTestDb.leases.release(renamer) shouldBe 1
        val both = LakeTestDb.lease(LeaseKind.TABLE, "sales.orders_v2", operationId = operation)
        session(both).use { it.execute(RENAME, "orders_v2", "sales", "orders") shouldBe 1 }
        jdbc.queryForObject("SELECT table_name FROM iceberg_tables", String::class.java) shouldBe "orders_v2"
    }

    @Test
    fun `RED - without the OLD-name check a rename under the NEW lease alone lands`() {
        val old = LakeTestDb.lease(LeaseKind.TABLE, "sales.orders")
        session(old).use { it.execute(INSERT_TABLE, "sales", "orders", "s3://b/m0.json") }
        val newOnly = LakeTestDb.lease(LeaseKind.TABLE, "sales.orders_v2")
        val weakened = fenceWithoutOldNameCheck()
        session(newOnly).use { connection ->
            connection.autoCommit = false
            try {
                connection.createStatement().use { it.execute(weakened) }
                // Red: the hijacking rename is admitted once step 3's OLD-name check is gone.
                connection.execute(RENAME, "orders_v2", "sales", "orders") shouldBe 1
            } finally {
                connection.rollback()
            }
        }
        // Green again on the shipped function (the transaction rolled the weakened one back).
        session(newOnly).use { sqlState { it.execute(RENAME, "orders_v2", "sales", "orders") } shouldBe DP001 }
    }

    @Test
    fun `RED - with the fence trigger dropped an unleased UPDATE lands, and the shipped trigger refuses it`() {
        val lease = LakeTestDb.lease(LeaseKind.TABLE, "sales.orders")
        session(lease).use { it.execute(INSERT_TABLE, "sales", "orders", "s3://b/m0.json") }
        LakeTestDb.connect().use { connection ->
            connection.autoCommit = false
            try {
                connection.createStatement().use { it.execute("DROP TRIGGER dp_fence ON iceberg_tables") }
                connection.execute(UPDATE_POINTER, "s3://b/hijack.json", "sales", "orders", "s3://b/m0.json") shouldBe 1
            } finally {
                connection.rollback()
            }
            sqlState { connection.execute(UPDATE_POINTER, "s3://b/hijack.json", "sales", "orders", "s3://b/m0.json") } shouldBe
                DP001
        }
        jdbc.queryForObject("SELECT metadata_location FROM iceberg_tables", String::class.java) shouldBe "s3://b/m0.json"
    }

    @Test
    fun `a leased DELETE stamps the final revision superseded and dropped`() {
        val lease = LakeTestDb.lease(LeaseKind.TABLE, "sales.orders")
        session(lease).use { connection ->
            connection.execute(INSERT_TABLE, "sales", "orders", "s3://b/m0.json")
            connection.execute("DELETE FROM iceberg_tables WHERE table_namespace = ? AND table_name = ?", "sales", "orders") shouldBe 1
        }
        val revision = revisions().single()
        revision["dropped_at"].shouldNotBeNull()
        revision["superseded_at"].shouldNotBeNull()
        // Unleased, the same DELETE is refused (the row is gone, so re-create it under the lease first).
        session(lease).use { it.execute(INSERT_TABLE, "sales", "orders", "s3://b/m2.json") }
        LakeTestDb.connect().use { connection ->
            sqlState { connection.execute("DELETE FROM iceberg_tables WHERE table_name = ?", "orders") } shouldBe DP001
        }
        catalogRows() shouldBe 1L
    }

    @Test
    fun `a takeover waits for an in-flight fenced write, then the old holder is fenced`() {
        val first =
            (
                LakeTestDb.leases.acquire(
                    LeaseKind.TABLE,
                    "sales.orders",
                    UUID.randomUUID(),
                    Duration.ofSeconds(1),
                    LakeTestDb.leases.catalogNowPlus(Duration.ofMinutes(5)),
                    "test",
                ) as LeaseOutcome.Acquired
            ).lease
        session(first).use { holder ->
            holder.autoCommit = false
            holder.execute(INSERT_TABLE, "sales", "orders", "s3://b/m0.json")
            // The write holds FOR SHARE on its lease row. Let the lease expire by the catalog clock (bounded poll).
            awaitTrue("the 1 s lease expires") {
                jdbc.queryForObject("SELECT expires_at < now() FROM lake_leases", Boolean::class.java) == true
            }
            val takeover =
                CompletableFuture.supplyAsync {
                    LakeTestDb.leases.acquire(
                        LeaseKind.TABLE,
                        "sales.orders",
                        UUID.randomUUID(),
                        Duration.ofMinutes(1),
                        LakeTestDb.leases.catalogNowPlus(Duration.ofMinutes(5)),
                        "takeover",
                    )
                }
            // On its OWN connection: pg_stat_activity inside a transaction is a frozen snapshot.
            LakeTestDb.connect().use { probe ->
                awaitTrue("the takeover blocks on the holder's lock") {
                    probe.queryLong(
                        "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND query LIKE 'INSERT INTO lake_leases%'",
                    ) == 1L
                }
            }
            takeover.isDone shouldBe false
            holder.commit()
            val second = takeover.get(WAIT_SECONDS, TimeUnit.SECONDS).shouldBeInstanceOf<LeaseOutcome.Acquired>().lease
            second.token shouldNotBe first.token
            sqlState { holder.execute(UPDATE_POINTER, "s3://b/m1.json", "sales", "orders", "s3://b/m0.json") } shouldBe DP001
            holder.rollback()
        }
        jdbc.queryForObject("SELECT metadata_location FROM iceberg_tables", String::class.java) shouldBe "s3://b/m0.json"
    }

    /** V53's `dp_fence` with step 3 (the rename's OLD-name check) cut out — derived from the shipped text. */
    private fun fenceWithoutOldNameCheck(): String {
        val v53 = LakeTestDb.v53()
        val function = v53.substring(v53.indexOf("CREATE FUNCTION dp_fence()"), v53.indexOf("END \$\$;") + "END \$\$;".length)
        // The nested block: the outer IF on (UPDATE, iceberg_tables), its inner IF, and the outer END IF.
        val start = function.indexOf("  IF TG_OP = 'UPDATE' AND TG_TABLE_NAME = 'iceberg_tables' THEN")
        val inner = function.indexOf("    END IF;", start)
        val end = function.indexOf("  END IF;", inner + "    END IF;".length) + "  END IF;".length
        check(start > 0 && inner > start && end > inner) { "the OLD-name check was not found in V53's dp_fence" }
        val weakened = function.removeRange(start, end).replace("CREATE FUNCTION", "CREATE OR REPLACE FUNCTION")
        weakened shouldNotBe function
        weakened.contains("rename of") shouldBe false
        weakened shouldContain "FOR SHARE"
        return weakened
    }

    private fun session(lease: LeaseHandle): Connection =
        LakeTestDb.connect().also { connection ->
            connection.prepareStatement(FencedJdbcClientPool.SESSION_SQL).use { statement ->
                statement.setString(1, lease.token.toString())
                statement.setString(2, lease.operationId.toString())
                statement.setString(3, "5s")
                statement.setString(4, "30s")
                statement.executeQuery().close()
            }
        }

    private fun revisions(): List<Map<String, Any?>> = jdbc.queryForList("SELECT * FROM lake_table_revisions ORDER BY seq")

    private fun catalogRows(): Long =
        jdbc.queryForObject(
            "SELECT (SELECT count(*) FROM iceberg_tables) + (SELECT count(*) FROM iceberg_namespace_properties)",
            Long::class.java,
        ) ?: -1L

    private companion object {
        const val DP001 = LakeCatalogException.SQLSTATE_FENCED
        const val DP002 = LakeCatalogException.SQLSTATE_RESTORING
        const val WAIT_SECONDS = 10L
        const val POLL_MILLIS = 50L

        // The statements Iceberg 1.12.0's JdbcUtil issues (V1 shape), parameterised the same way.
        const val INSERT_TABLE =
            "INSERT INTO iceberg_tables (catalog_name, table_namespace, table_name, metadata_location, previous_metadata_location," +
                " iceberg_type) VALUES ('lake', ?, ?, ?, NULL, 'TABLE')"
        const val UPDATE_POINTER =
            "UPDATE iceberg_tables SET metadata_location = ?, previous_metadata_location = metadata_location" +
                " WHERE catalog_name = 'lake' AND table_namespace = ? AND table_name = ? AND metadata_location = ?"
        const val RENAME =
            "UPDATE iceberg_tables SET table_name = ? WHERE catalog_name = 'lake' AND table_namespace = ? AND table_name = ?"
        const val INSERT_NAMESPACE =
            "INSERT INTO iceberg_namespace_properties (catalog_name, namespace, property_key, property_value)" +
                " VALUES ('lake', ?, 'exists', 'true')"

        fun Connection.execute(
            sql: String,
            vararg args: Any,
        ): Int =
            prepareStatement(sql).use { statement ->
                args.forEachIndexed { i, arg -> statement.setObject(i + 1, arg) }
                statement.executeUpdate()
            }

        fun Connection.queryLong(sql: String): Long =
            createStatement().use { s -> s.executeQuery(sql).use { rs -> rs.next().let { rs.getLong(1) } } }

        fun sqlState(block: () -> Unit): String? = shouldThrow<SQLException> { block() }.sqlState

        fun awaitTrue(
            what: String,
            condition: () -> Boolean,
        ) {
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)
            while (!condition()) {
                check(System.nanoTime() < until) { "timed out waiting until $what" }
                Thread.sleep(POLL_MILLIS)
            }
        }
    }
}
