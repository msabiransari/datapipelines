package co.datapipelines.lake.delivery

import co.datapipelines.lake.LakeCatalogs
import co.datapipelines.lake.LakeLeases
import co.datapipelines.lake.LakeTestDb
import co.datapipelines.lake.LeaseHandle
import co.datapipelines.lake.LeaseKind
import co.datapipelines.lake.definitions.LakeApplyLeases
import co.datapipelines.lake.definitions.LakeApplyRepository
import co.datapipelines.lake.definitions.LakeDefinitionApplier
import co.datapipelines.lake.definitions.LakeDefinitionRepository
import co.datapipelines.lake.definitions.RecordingAuditSink
import co.datapipelines.lake.settle.DeliverySettlementRepository
import co.datapipelines.lake.settle.DeliverySettler
import co.datapipelines.lake.settle.LakeProjectionRepair
import co.datapipelines.lake.settle.LakeSemanticsRepair
import co.datapipelines.lake.settle.LakeSettlementSweep
import io.mockk.mockk
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Connection
import java.time.Duration
import java.util.UUID
import javax.sql.DataSource

/**
 * Real ledger, real fence and separate bounded JDBC statements; no MinIO needed to move the catalog pointer.
 * The ledger carries a recording audit sink (799b): every settled conclusion's strict in-transaction audit is observable.
 */
internal class DeliverySettlementHarness(
    dataSource: DataSource = LakeTestDb.dataSource,
    private val resetDatabase: () -> Unit = LakeTestDb::reset,
    private val connect: () -> Connection = { LakeTestDb.connect() },
) {
    val jdbc = JdbcTemplate(dataSource).apply { queryTimeout = 2 }
    val named = NamedParameterJdbcTemplate(jdbc)
    val repository = DeliveryLedgerRepository(named)
    val observations = DeliverySettlementRepository(named)
    val transaction = TransactionTemplate(DataSourceTransactionManager(dataSource))
    val audit = RecordingAuditSink()
    val ledger = DeliveryLedger(repository, transaction, audit)
    val tableId: UUID = UUID.randomUUID()
    val actor: UUID = UUID.fromString("00000000-0000-0000-0000-000000000799")

    fun reset() {
        resetDatabase()
        jdbc.execute("TRUNCATE lake_delivery_attempts, lake_deliveries, lake_tables_managed, lake_namespaces CASCADE")
        jdbc.update(
            "INSERT INTO users (id, email, display_name, provider, provider_subject) VALUES (?, ?, 'Lane 799', 'google', ?) " +
                "ON CONFLICT (id) DO NOTHING",
            actor,
            "lane799@example.com",
            "sub-799",
        )
        val definitions = LakeDefinitionRepository(named)
        definitions.insertTable(tableId, definitions.namespaceId("settle", actor), "orders", "{}", actor)
    }

    fun leases(lockTimeout: Duration = Duration.ofMillis(100)): LakeLeases = LakeLeases(jdbc, lockTimeout = lockTimeout)

    fun settler(leases: LakeLeases = leases()): DeliverySettler =
        DeliverySettler(observations, repository, ledger, leases, LakeProjectionRepair(named, transaction, "lake799") {})

    /**
     * The production sweep over this harness's table. Its tables carry no applied definition and no APPLYING attempt, so
     * the applier and the semantics repair return before opening a catalog; the strict catalog double proves it.
     */
    fun sweep(leases: LakeLeases = leases()): LakeSettlementSweep {
        val definitions = LakeDefinitionRepository(named)
        val catalogs = mockk<LakeCatalogs>()
        return LakeSettlementSweep(
            named,
            leases,
            settler(leases),
            LakeDefinitionApplier(
                definitions,
                LakeApplyRepository(named),
                LakeApplyLeases(leases, Duration.ZERO),
                catalogs,
                "lake799",
                transaction,
                audit,
            ),
            LakeSemanticsRepair(named, transaction, observations, definitions, leases, catalogs),
        )
    }

    /** One RECEIVED delivery; its random execution id has no RUNNING execution row, so settlement sees it ended. */
    fun receive(key: DeliveryKey = DeliveryKey("orders", "one")): LakeDelivery =
        ledger
            .receive(
                ReceiveDelivery(
                    tableId,
                    DeliveryManifest(
                        key,
                        emptyList(),
                        emptyList(),
                        DeliveryFormat.Csv,
                        "a".repeat(64),
                        1,
                    ),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                ),
            ).delivery

    /** [n] RECEIVED deliveries with ended executions, distinct keys, returned in PostgreSQL's `ORDER BY id`. */
    fun backlog(n: Int): List<LakeDelivery> {
        repeat(n) { receive(DeliveryKey("orders", "backlog-$it")) }
        return byId()
    }

    /**
     * [n] PUBLISHING deliveries the sweep cannot settle: the first holds the table's one live lease as its own attempt
     * (`attemptLeaseLive`), every other row's takeover finds that live holder (`Busy`). Release the returned lease to
     * make them all eligible. Returned rows are in PostgreSQL's `ORDER BY id`.
     */
    fun livePublishing(rows: List<LakeDelivery>): LeaseHandle {
        rows.forEach { check(ledger.beginPublishing(it)) }
        return LakeTestDb.lease(LeaseKind.TABLE, "settle.orders", operationId = rows.first().operationId)
    }

    /** Every unsettled or settled delivery of this table in PostgreSQL's uuid order (JVM `UUID.compareTo` differs). */
    fun byId(): List<LakeDelivery> =
        jdbc
            .queryForList("SELECT id FROM lake_deliveries WHERE table_id = ? ORDER BY id", UUID::class.java, tableId)
            .map { requireNotNull(repository.find(it)) }

    fun unknown(): LakeDelivery {
        val received = receive()
        check(ledger.beginPublishing(received))
        check(ledger.conclude(requireNotNull(repository.find(received.id)), DeliveryConclusion.Unknown(null)))
        return requireNotNull(repository.find(received.id))
    }

    fun fencedConnection(lease: LeaseHandle): Connection =
        connect().also { connection ->
            connection
                .prepareStatement(
                    "SELECT set_config('dp.lease_token', ?, false), set_config('dp.operation_id', ?, false)",
                ).use { statement ->
                    statement.setString(1, lease.token.toString())
                    statement.setString(2, lease.operationId.toString())
                    statement.executeQuery().close()
                }
        }

    fun insertPointer(connection: Connection): Int =
        connection
            .prepareStatement(
                "INSERT INTO iceberg_tables (catalog_name, table_namespace, table_name, metadata_location, iceberg_type) " +
                    "VALUES ('lake', 'settle', 'orders', 's3://settle/metadata/one.json', 'TABLE')",
            ).use { it.executeUpdate() }

    fun attemptOutcome(row: LakeDelivery): String? =
        jdbc.queryForObject(
            "SELECT outcome FROM lake_delivery_attempts WHERE operation_id = ?",
            String::class.java,
            row.operationId,
        )
}
