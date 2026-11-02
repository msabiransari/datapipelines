package co.datapipelines.lake

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import org.apache.iceberg.PartitionSpec
import org.apache.iceberg.Schema
import org.apache.iceberg.Table
import org.apache.iceberg.catalog.Namespace
import org.apache.iceberg.catalog.TableIdentifier
import org.apache.iceberg.exceptions.CommitFailedException
import org.apache.iceberg.inmemory.InMemoryFileIO
import org.apache.iceberg.io.FileIO
import org.apache.iceberg.jdbc.JdbcCatalog
import org.apache.iceberg.jdbc.JdbcClientPool
import org.apache.iceberg.types.Types
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * The fenced pool under the owner's record-form ruling (2026-11-02: session-scoped settings, one pool per lease
 * holder, closed at release) and §8a's no-replay rule:
 *
 * 1. every catalog connection carries the lease, the operation id and both timeouts;
 * 2. DP's own Hikari pool never carries them;
 * 3. no catalog connection outlives its session;
 * 4. a second holder — or a fabricated handle — cannot write under the first's lease;
 * 5. the E1 F3 gate: a dropped commit response makes the STOCK pool reconnect and replay the commit, which reports
 *    a false `CommitFailedException` for a write that landed (red); the fenced pool sends the commit once and
 *    surfaces `lake.catalog.outcome_unknown` with exactly one revision row (green).
 *
 * The data files live in Iceberg's `InMemoryFileIO` (the object store is not under test here; the MinIO suites
 * cover it).
 */
class FencedPoolIntegrationTest {
    private val jdbc = LakeTestDb.jdbc

    @BeforeEach
    fun clean() {
        LakeTestDb.reset()
    }

    @Test
    fun `every catalog connection carries the lease, the operation id and both timeouts`() {
        val lease = LakeTestDb.lease(LeaseKind.TABLE, "sales.orders")
        pool(lease).use { pool ->
            val settings =
                pool.run { connection ->
                    connection.single(
                        "SELECT current_setting('dp.lease_token'), current_setting('dp.operation_id')," +
                            " current_setting('lock_timeout'), current_setting('statement_timeout'), current_setting('application_name')",
                    )
                }
            settings shouldBe
                listOf(lease.token.toString(), lease.operationId.toString(), "5s", "30s", CatalogDatabase.APPLICATION_NAME)
        }
    }

    @Test
    fun `DP's Hikari pool never carries a lease setting`() {
        val lease = LakeTestDb.lease(LeaseKind.TABLE, "sales.orders")
        pool(lease).use { pool ->
            pool.run { it.single("SELECT 1") }
            // Every connection Hikari can hand out, held at once, so none is skipped by reuse.
            val borrowed = (1..HIKARI_POOL_SIZE).map { LakeTestDb.dataSource.connection }
            try {
                borrowed.forEach { connection ->
                    val (token, operation, application) =
                        connection.single(
                            "SELECT coalesce(current_setting('dp.lease_token', true), ''), coalesce(current_setting('dp.operation_id', true), '')," +
                                " current_setting('application_name')",
                        )
                    token shouldBe ""
                    operation shouldBe ""
                    application shouldNotBe CatalogDatabase.APPLICATION_NAME
                }
            } finally {
                borrowed.forEach { it.close() }
            }
        }
    }

    @Test
    fun `no catalog connection outlives its session`() {
        val lease = LakeTestDb.lease(LeaseKind.NAMESPACE, "sales")
        val session = catalogs().open(lease)
        session.createNamespace("sales")
        catalogConnections() shouldBeGreaterThanOrEqual 1
        session.close()
        awaitTrue("the session's connections are gone") { catalogConnections() == 0 }
    }

    @Test
    fun `a second holder or a fabricated handle cannot write under the first's lease`() {
        val first = LakeTestDb.lease(LeaseKind.NAMESPACE, "alpha")
        val second = LakeTestDb.lease(LeaseKind.NAMESPACE, "beta")
        // The second holder's connections carry ITS lease; the fence refuses its write on alpha.
        catalogs().open(second).use { session ->
            shouldThrow<LakeCatalogException.Fenced> { session.write { it.createNamespace(Namespace.of("alpha")) } }
        }
        // A handle that copies the first lease's name and operation but invents the token.
        catalogs().open(first.copy(token = UUID.randomUUID())).use { session ->
            shouldThrow<LakeCatalogException.Fenced> { session.createNamespace("alpha") }
        }
        // The real token under another operation id.
        catalogs().open(first.copy(operationId = UUID.randomUUID())).use { session ->
            shouldThrow<LakeCatalogException.Fenced> { session.createNamespace("alpha") }
        }
        // The first holder's open session after a takeover: its connections still carry the dead token.
        catalogs().open(first).use { session ->
            jdbc.update("UPDATE lake_leases SET expires_at = now() - interval '1 second' WHERE name = 'alpha'")
            val taker =
                LakeTestDb.leases.acquire(
                    LeaseKind.NAMESPACE,
                    "alpha",
                    UUID.randomUUID(),
                    Duration.ofMinutes(1),
                    LakeTestDb.leases.catalogNowPlus(Duration.ofHours(1)),
                    "takeover",
                )
            (taker is LeaseOutcome.Acquired) shouldBe true
            shouldThrow<LakeCatalogException.Fenced> { session.createNamespace("alpha") }
        }
        jdbc.queryForObject("SELECT count(*) FROM iceberg_namespace_properties", Long::class.java) shouldBe 0L
    }

    @Test
    fun `RED - the stock pool replays a commit whose response was dropped and reports a false CommitFailedException`() {
        val (lease, before) = tableWithOneCommit("replay_red")
        PgResponseDropProxy(LakeTestDb.host, LakeTestDb.port).use { proxy ->
            stockCatalog(proxy.url(LakeTestDb.DATABASE), lease).use { catalog ->
                val table = catalog.loadTable(TableIdentifier.of(Namespace.of("sales"), "replay_red"))
                proxy.arm()
                val failure = shouldThrow<CommitFailedException> { table.updateProperties().set("k", "v").commit() }
                proxy.awaitDropped(WAIT_SECONDS) shouldBe true
                // Red: the commit's response was withheld AFTER PostgreSQL committed it (UPDATE 1) …
                proxy.withheld.contains("C:UPDATE 1") shouldBe true
                // … the pool reconnected and re-sent the CAS, which matched nothing (the first one had landed) …
                proxy.acceptsSinceArm.get() shouldBeGreaterThanOrEqual 1
                proxy.casSent.get() shouldBeGreaterThanOrEqual 2
                failure.message.orEmpty() shouldNotContain lease.token.toString()
            }
        }
        // … and the "failed" commit is the catalog's current pointer.
        val current = currentRevision("replay_red")
        current shouldNotBe before
        revisionCount("replay_red") shouldBe 3
    }

    @Test
    fun `the fenced pool sends the commit once and surfaces outcome_unknown, with one revision row`() {
        val (lease, before) = tableWithOneCommit("replay_green")
        PgResponseDropProxy(LakeTestDb.host, LakeTestDb.port).use { proxy ->
            catalogs(LakeTestDb.catalogDatabase(proxy.url(LakeTestDb.DATABASE))).open(lease).use { session ->
                val table = session.loadTable("sales", "replay_green")
                proxy.arm()
                shouldThrow<LakeCatalogException.OutcomeUnknown> {
                    session.write { table.updateProperties().set("k", "v").commit() }
                }
                proxy.awaitDropped(WAIT_SECONDS) shouldBe true
                proxy.withheld.contains("C:UPDATE 1") shouldBe true
                proxy.acceptsSinceArm.get() shouldBe 0
                proxy.casSent.get() shouldBe 1
                // The session refuses further work until the outcome is settled (P4).
                shouldThrow<IllegalStateException> { session.loadTable("sales", "replay_green") }
            }
        }
        // Exactly one new revision row, by this operation; the committed metadata file still exists.
        revisionCount("replay_green") shouldBe 3
        val current = currentRevision("replay_green")
        current shouldNotBe before
        InMemoryFileIO().fileExists(current) shouldBe true
        jdbc.queryForObject(
            "SELECT operation_id FROM lake_table_revisions WHERE table_name = 'replay_green' AND superseded_at IS NULL",
            UUID::class.java,
        ) shouldBe lease.operationId
    }

    /** A table `sales.<name>` created and committed once, directly (no proxy). Answers the lease and the pointer. */
    private fun tableWithOneCommit(name: String): Pair<LeaseHandle, String> {
        val operation = UUID.randomUUID()
        LakeTestDb.lease(LeaseKind.NAMESPACE, "sales", operationId = operation).let { ns ->
            catalogs().open(ns).use { it.createNamespace("sales") }
        }
        val lease = LakeTestDb.lease(LeaseKind.TABLE, "sales.$name", operationId = operation)
        catalogs().open(lease).use { session ->
            val table: Table = session.createTable("sales", name, UUID.randomUUID(), SCHEMA, PartitionSpec.unpartitioned(), 1L)
            table.updateProperties().set("seed", "1").commit()
        }
        revisionCount(name) shouldBe 2
        return lease to currentRevision(name)
    }

    /** The stock Iceberg pool (reconnect-and-replay ON) with the lease's session settings — E1's replay arm. */
    private fun stockCatalog(
        url: String,
        lease: LeaseHandle,
    ): JdbcCatalog {
        val database = LakeTestDb.catalogDatabase(url)
        val props = database.connectionProperties()
        val catalog =
            JdbcCatalog(
                { _ -> InMemoryFileIO() },
                { _ ->
                    object : JdbcClientPool(2, url, mapOf("jdbc.user" to props.getProperty("user"), "jdbc.password" to props.getProperty("password"))) {
                        override fun newClient(): Connection =
                            super.newClient().also { connection ->
                                connection.prepareStatement(FencedJdbcClientPool.SESSION_SQL).use { s ->
                                    s.setString(1, lease.token.toString())
                                    s.setString(2, lease.operationId.toString())
                                    s.setString(3, "5s")
                                    s.setString(4, "30s")
                                    s.executeQuery().close()
                                }
                            }
                    }
                },
                false,
            )
        catalog.initialize(PROPERTIES.name, catalogs(database).catalogProperties())
        return catalog
    }

    private fun pool(lease: LeaseHandle): FencedJdbcClientPool {
        val database = LakeTestDb.catalogDatabase()
        return FencedJdbcClientPool(database.url, database.connectionProperties(), lease, 5, 30)
    }

    private fun catalogs(database: CatalogDatabase = LakeTestDb.catalogDatabase()): LakeCatalogs =
        LakeCatalogs(PROPERTIES, database, fileIoFactory = ::inMemory)

    private fun currentRevision(table: String): String =
        jdbc.queryForObject("SELECT metadata_location FROM iceberg_tables WHERE table_name = ?", String::class.java, table)
            ?: error("no pointer for $table")

    private fun revisionCount(table: String): Long =
        jdbc.queryForObject("SELECT count(*) FROM lake_table_revisions WHERE table_name = ?", Long::class.java, table) ?: -1L

    private fun catalogConnections(): Int =
        LakeTestDb.connect().use {
            it.single("SELECT count(*) FROM pg_stat_activity WHERE application_name = '${CatalogDatabase.APPLICATION_NAME}'").single().toInt()
        }

    private companion object {
        const val HIKARI_POOL_SIZE = 8
        const val WAIT_SECONDS = 10L

        val PROPERTIES = LakeProperties(enabled = true, storage = LakeProperties.Storage(bucket = "in-memory"))
        val SCHEMA = Schema(Types.NestedField.required(1, "id", Types.LongType.get()))

        fun inMemory(props: Map<String, String>): FileIO = InMemoryFileIO().apply { initialize(props) }

        fun Connection.single(sql: String): List<String> =
            createStatement().use { s ->
                s.executeQuery(sql).use { rs ->
                    check(rs.next()) { "no row: $sql" }
                    (1..rs.metaData.columnCount).map { rs.getString(it) }
                }
            }

        fun awaitTrue(
            what: String,
            condition: () -> Boolean,
        ) {
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)
            while (!condition()) {
                check(System.nanoTime() < until) { "timed out waiting until $what" }
                Thread.sleep(50)
            }
        }
    }
}
