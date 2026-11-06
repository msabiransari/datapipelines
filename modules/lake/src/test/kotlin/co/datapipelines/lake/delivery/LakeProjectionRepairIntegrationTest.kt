package co.datapipelines.lake.delivery

import co.datapipelines.lake.LakeTestDb
import co.datapipelines.lake.LeaseKind
import co.datapipelines.lake.definitions.LakeApplyRepository
import co.datapipelines.lake.settle.LakeProjectionRepair
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.SQLException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** §8b repairs use the real P3 writer, revision trigger and PostgreSQL row-lock interleavings. */
class LakeProjectionRepairIntegrationTest {
    private val h = DeliverySettlementHarness()
    private val changes = mutableListOf<String>()

    @BeforeEach
    fun clean() {
        removeDatasource()
        h.reset()
    }

    @AfterEach
    fun removeDatasource() {
        h.jdbc.execute("ALTER TABLE datasources DROP CONSTRAINT IF EXISTS chk_799_projection_bump")
        h.jdbc.update("DELETE FROM lake_tables WHERE datasource_id = ?", LAKE)
        h.jdbc.update("DELETE FROM datasources WHERE name = ?", LAKE)
    }

    @Test
    fun `a changed repair commits its datasource bump before invalidating and a repeat changes nothing`() {
        register()
        pointer("one")
        val before = stamp()
        val repair =
            repair {
                TransactionSynchronizationManager.isActualTransactionActive() shouldBe false
                location() shouldBe path("one")
                (stamp() != before) shouldBe true
                changes += it
            }

        repair.repair(target()) shouldBe 1
        val changedStamp = stamp()
        repair.repair(target()) shouldBe 0
        stamp() shouldBe changedStamp
        changes shouldBe listOf(LAKE)
    }

    @Test
    fun `an unregistered datasource or an absent revision leaves the projection alone`() {
        pointer("one")
        repair().repair(target()) shouldBe 0
        changes shouldBe emptyList()
        LakeTestDb.reset()
        register()
        val before = stamp()
        repair().repair(target()) shouldBe 0
        stamp() shouldBe before
        count() shouldBe 0
    }

    @Test
    fun `an external registration cannot be adopted or invalidated by a managed repair`() {
        register()
        pointer("one")
        h.jdbc.update(
            "INSERT INTO lake_tables (datasource_id, namespace, name, format, location, registered_by) " +
                "VALUES (?, ARRAY[?, 'settle'], 'orders', 'parquet', 's3://external/kept', ?)",
            LAKE,
            LAKE,
            h.actor,
        )
        val before = stamp()
        repair().repair(target()) shouldBe 0
        location() shouldBe "s3://external/kept"
        stamp() shouldBe before
        changes shouldBe emptyList()
    }

    @Test
    fun `a refused datasource bump rolls back the registry insert and publishes no invalidation`() {
        register()
        pointer("one")
        val before = stamp()
        h.jdbc.execute(
            "ALTER TABLE datasources ADD CONSTRAINT chk_799_projection_bump " +
                "CHECK (name <> 'lake799' OR updated_at < '2001-01-01'::timestamptz)",
        )
        try {
            shouldThrow<DataAccessException> { repair().repair(target()) }
            count() shouldBe 0
            stamp() shouldBe before
            changes shouldBe emptyList()
        } finally {
            h.jdbc.execute("ALTER TABLE datasources DROP CONSTRAINT chk_799_projection_bump")
        }
    }

    @Test
    fun `settle repairs a committed projection even with no unsettled delivery rows`() {
        register()
        pointer("one")
        h.settler().settlePage(h.leases(), h.tableId, h.leases().catalogNowPlus(java.time.Duration.ofMinutes(1))) shouldBe 0
        location() shouldBe path("one")
        count() shouldBe 1
    }

    @Test
    fun `the repair refuses a surrounding transaction so invalidation cannot precede durability`() {
        h.transaction.executeWithoutResult {
            shouldThrow<IllegalStateException> { repair().repair(target()) }
        }
        changes shouldBe emptyList()
    }

    @Test
    fun `an unbounded template is refused without changing its shared timeout`() {
        val shared = NamedParameterJdbcTemplate(LakeTestDb.dataSource)
        shared.jdbcTemplate.queryTimeout shouldBe -1
        shouldThrow<IllegalArgumentException> { repair(jdbc = shared) }
        shared.jdbcTemplate.queryTimeout shouldBe -1
    }

    @Test
    fun `an older repair waiting on the registry row cannot overwrite a newer repair`() {
        register()
        pointer("one")
        repair().repair(target()) shouldBe 1
        changes.clear()
        pointer("two")
        LakeTestDb.connect().use { blocker ->
            blocker.autoCommit = false
            val blocking = JdbcTemplate(SingleConnectionDataSource(blocker, true)).apply { queryTimeout = 2 }
            blocking.queryForList("SELECT id FROM lake_tables WHERE datasource_id = 'lake799' FOR UPDATE")
            val bounded = NamedParameterJdbcTemplate(JdbcTemplate(LakeTestDb.dataSource).apply { queryTimeout = 8 })
            val pending = CompletableFuture.supplyAsync { repair(jdbc = bounded).repair(target()) }
            try {
                awaitBlocked(pending)
                pointer("three")
                LakeApplyRepository(NamedParameterJdbcTemplate(blocking)).upsertRegistryRow(LAKE, "settle", "orders", h.actor) shouldBe 1
                blocker.commit()
                val staleChanges = pending.get(WAIT_SECONDS, TimeUnit.SECONDS)
                location() shouldBe path("three")
                staleChanges shouldBe 0
            } finally {
                blocker.rollback()
                pending.get(WAIT_SECONDS, TimeUnit.SECONDS)
            }
        }
        location() shouldBe path("three")
        val authoritative =
            h.jdbc.queryForObject(
                "SELECT seq FROM lake_table_revisions WHERE namespace = 'settle' AND table_name = 'orders' AND superseded_at IS NULL",
                Long::class.java,
            )
        h.jdbc.queryForObject("SELECT revision_seq FROM lake_tables WHERE datasource_id = ?", Long::class.java, LAKE) shouldBe authoritative
        changes shouldBe emptyList()
        println("(799 monotone repair) registry revision=$authoritative, location=${location()}, stale changes=${changes.size}")
    }

    @Test
    fun `a held registry lock is bounded and a timed out repair changes neither timestamp nor invalidation`() {
        register()
        pointer("one")
        repair().repair(target()) shouldBe 1
        changes.clear()
        pointer("two")
        val before = stamp()
        LakeTestDb.connect().use { blocker ->
            blocker.autoCommit = false
            blocker.createStatement().use {
                it
                    .executeQuery(
                        "SELECT id FROM lake_tables WHERE datasource_id = 'lake799' FOR UPDATE",
                    ).close()
            }
            try {
                val started = System.nanoTime()
                val failure = shouldThrow<DataAccessException> { repair().repair(target()) }
                (failure.mostSpecificCause as SQLException).sqlState shouldBe "57014"
                (System.nanoTime() - started < TimeUnit.SECONDS.toNanos(WAIT_SECONDS)) shouldBe true
            } finally {
                blocker.rollback()
            }
        }
        location() shouldBe path("one")
        stamp() shouldBe before
        changes shouldBe emptyList()
    }

    private fun repair(
        jdbc: NamedParameterJdbcTemplate = h.named,
        changed: (String) -> Unit = { changes += it },
    ) = LakeProjectionRepair(jdbc, h.transaction, LAKE, changed)

    private fun target() = requireNotNull(h.observations.target(h.tableId))

    private fun register() {
        h.jdbc.update(
            "INSERT INTO datasources (name, display_name, dialect, jdbc_url, credential_kind, created_by, updated_at) " +
                "VALUES (?, 'Managed lake', 'LAKE', 'jdbc:duckdb:', 'none', ?, '2000-01-01'::timestamptz)",
            LAKE,
            h.actor,
        )
    }

    private fun pointer(version: String) {
        val lease = LakeTestDb.lease(LeaseKind.TABLE, "settle.orders")
        try {
            h.fencedConnection(lease).use { connection ->
                connection
                    .prepareStatement(
                        "INSERT INTO iceberg_tables (catalog_name, table_namespace, table_name, metadata_location, iceberg_type) " +
                            "VALUES ('lake', 'settle', 'orders', ?, 'TABLE') ON CONFLICT (catalog_name, table_namespace, table_name) " +
                            "DO UPDATE SET metadata_location = EXCLUDED.metadata_location",
                    ).use { statement ->
                        statement.setString(1, path(version))
                        statement.executeUpdate() shouldBe 1
                    }
            }
        } finally {
            LakeTestDb.leases.release(lease.operationId)
        }
    }

    private fun awaitBlocked(pending: CompletableFuture<Int>) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)
        while (h.jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND query LIKE '%INSERT INTO lake_tables%')",
                Boolean::class.java,
            ) != true
        ) {
            check(!pending.isDone && System.nanoTime() < deadline) { "The old repair never waited on the registry lock." }
            Thread.sleep(POLL_MILLIS)
        }
        pending.isDone shouldBe false
    }

    private fun path(version: String) = "s3://settle/metadata/$version.json"

    private fun stamp(): String? =
        h.jdbc.queryForObject("SELECT updated_at::text FROM datasources WHERE name = ?", String::class.java, LAKE)

    private fun location(): String? =
        h.jdbc.queryForObject("SELECT location FROM lake_tables WHERE datasource_id = ?", String::class.java, LAKE)

    private fun count(): Int? = h.jdbc.queryForObject("SELECT count(*) FROM lake_tables WHERE datasource_id = ?", Int::class.java, LAKE)

    private companion object {
        const val LAKE = "lake799"
        const val WAIT_SECONDS = 10L
        const val POLL_MILLIS = 20L
    }
}
