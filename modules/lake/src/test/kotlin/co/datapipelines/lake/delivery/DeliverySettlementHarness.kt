package co.datapipelines.lake.delivery

import co.datapipelines.lake.LakeLeases
import co.datapipelines.lake.LakeTestDb
import co.datapipelines.lake.LeaseHandle
import co.datapipelines.lake.definitions.LakeDefinitionRepository
import co.datapipelines.lake.settle.DeliverySettlementRepository
import co.datapipelines.lake.settle.DeliverySettler
import co.datapipelines.lake.settle.LakeProjectionRepair
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Connection
import java.time.Duration
import java.util.UUID
import javax.sql.DataSource

/** Real ledger, real fence and separate bounded JDBC statements; no MinIO needed to move the catalog pointer. */
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
    val ledger = DeliveryLedger(repository, transaction)
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

    fun receive(): LakeDelivery =
        ledger
            .receive(
                ReceiveDelivery(
                    tableId,
                    DeliveryManifest(
                        DeliveryKey("orders", "one"),
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
