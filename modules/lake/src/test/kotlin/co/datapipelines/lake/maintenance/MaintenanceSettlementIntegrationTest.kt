package co.datapipelines.lake.maintenance

import co.datapipelines.lake.LakeTestDb
import co.datapipelines.lake.LeaseKind
import co.datapipelines.lake.delivery.DeliveryConclusion
import co.datapipelines.lake.delivery.DeliveryFormat
import co.datapipelines.lake.delivery.DeliveryKey
import co.datapipelines.lake.delivery.DeliveryLedger
import co.datapipelines.lake.delivery.DeliveryLedgerRepository
import co.datapipelines.lake.delivery.DeliveryManifest
import co.datapipelines.lake.delivery.LakeDelivery
import co.datapipelines.lake.delivery.LakeDeliveryState
import co.datapipelines.lake.delivery.ReceiveDelivery
import co.datapipelines.lake.settle.DeliverySettlementRepository
import co.datapipelines.lake.settle.DeliverySettler
import co.datapipelines.lake.settle.LakeProjectionRepair
import co.datapipelines.pipeline.PipelineJson
import co.datapipelines.scheduler.SystemJobOutcome
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import javax.sql.DataSource

/**
 * **R13a of #957** (D6a; the cut's L21; the record's §8b "at every maintenance pass"), with no sweep bean: the run settles
 * its table's deliveries before the first part, through the production [DeliveryMaintenanceSettlement] over a real
 * [DeliverySettler]; a delivery it cannot settle and a settler that defers leave the parts running.
 */
class MaintenanceSettlementIntegrationTest {
    private val h = MaintenanceHarness()
    private val bounded = JdbcTemplate(LakeTestDb.dataSource).apply { queryTimeout = STATEMENT_TIMEOUT_SECONDS }
    private val named = NamedParameterJdbcTemplate(bounded)
    private val repository = DeliveryLedgerRepository(named)
    private val transaction = TransactionTemplate(DataSourceTransactionManager(LakeTestDb.dataSource))
    private val ledger = DeliveryLedger(repository, transaction, h.audit)
    private val json = PipelineJson.objectMapper()

    @BeforeEach
    fun clean() {
        h.reset()
        h.defs.registerLakeDatasource()
    }

    @AfterEach
    fun close() = h.close()

    @Test
    fun `a recoverable delivery is PUBLISHED before the first part runs, and the part's commit then succeeds`() {
        val table = h.table()
        val delivery = unknownWithRevision(table)
        val seen = AtomicReference<LakeDeliveryState>()
        val part = MarkerPart("cleanup", 1, before = { seen.set(state(delivery)) })

        h.worker(parts = listOf(part), settlement = settlement()).run(table.id.toString()) shouldBe SystemJobOutcome.Done

        seen.get() shouldBe LakeDeliveryState.PUBLISHED
        h.markers(table) shouldBe setOf("cleanup")
        val row = h.rows(table).single()
        row.state shouldBe "finished"
        val settled = json.readTree(row.settlementJson)
        println("(957 R13a) settlement=$settled")
        settled["settled"].asInt() shouldBe 1
        settled["deferred"].asBoolean() shouldBe false
        h.leaseRows() shouldBe 0
    }

    @Test
    fun `a delivery whose attempt's lease is live stays UNKNOWN and the parts still run`() {
        val table = h.table()
        val delivery = unknownWithRevision(table)
        val live = LakeTestDb.lease(LeaseKind.TABLE, table.leaseName, operationId = delivery.operationId)
        try {
            val part = MarkerPart("cleanup", 1, commits = false)

            h.worker(parts = listOf(part), settlement = settlement()).run(table.id.toString()) shouldBe SystemJobOutcome.Done

            part.started.get() shouldBe true
            state(delivery) shouldBe LakeDeliveryState.UNKNOWN
            val row = h.rows(table).single()
            row.state shouldBe "finished"
            println("(957 R13a) live-lease settlement=${row.settlementJson}")
            json.readTree(row.settlementJson)["settled"].asInt() shouldBe 0
        } finally {
            h.leases.release(live.operationId)
        }
    }

    @Test
    fun `a settler that defers is recorded in the run row and the parts still run`() {
        val table = h.table()
        unknownWithRevision(table)
        // The projection repair's transaction cannot get a connection: the settler throws SettlementDeferred at its repair.
        val refusing =
            object : DataSource by LakeTestDb.dataSource {
                override fun getConnection(): Connection = throw SQLException("connection refused", "08001")
            }
        val part = MarkerPart("cleanup", 1)

        h
            .worker(
                parts = listOf(part),
                settlement = settlement(repairTransaction = TransactionTemplate(DataSourceTransactionManager(refusing))),
            ).run(table.id.toString())

        h.markers(table) shouldBe setOf("cleanup")
        val row = h.rows(table).single()
        row.state shouldBe "finished"
        val settled = json.readTree(row.settlementJson)
        println("(957 R13a) deferred settlement=$settled")
        settled["deferred"].asBoolean() shouldBe true
        settled["left_unsettled"].asBoolean() shouldBe true
        settled["error_code"].asText() shouldBe MaintenanceErrors.UNCLASSIFIED
        h.leaseRows() shouldBe 0
    }

    private fun settlement(repairTransaction: TransactionTemplate = transaction): MaintenanceSettlement =
        DeliveryMaintenanceSettlement(
            DeliverySettler(
                DeliverySettlementRepository(named),
                repository,
                ledger,
                h.leases,
                LakeProjectionRepair(named, repairTransaction, h.defs.properties.name) {},
            ),
            h.leases,
        )

    private fun state(delivery: LakeDelivery): LakeDeliveryState = requireNotNull(repository.find(delivery.id)).state

    /**
     * One delivery of [table] concluded UNKNOWN whose attempt did commit: a revision row carries its operation id, and its
     * lease is gone. The shape settlement concludes PUBLISHED.
     */
    private fun unknownWithRevision(table: MaintenanceTable): LakeDelivery {
        val received =
            ledger
                .receive(
                    ReceiveDelivery(
                        table.id,
                        DeliveryManifest(
                            DeliveryKey("orders", "one"),
                            emptyList(),
                            emptyList(),
                            DeliveryFormat.Csv,
                            "a".repeat(FINGERPRINT),
                            1,
                        ),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                    ),
                ).delivery
        check(ledger.beginPublishing(received))
        check(ledger.conclude(requireNotNull(repository.find(received.id)), DeliveryConclusion.Unknown(null)))
        val lease = LakeTestDb.lease(LeaseKind.TABLE, table.leaseName, operationId = received.operationId)
        try {
            h.defs.catalogs.open(lease).use { session ->
                val loaded = session.loadTable(table.namespace, table.name)
                session.write { loaded.updateProperties().set("dp.test.delivery", received.id.toString()).commit() }
            }
        } finally {
            h.leases.release(received.operationId)
        }
        return requireNotNull(repository.find(received.id)).also { check(it.state == LakeDeliveryState.UNKNOWN) }
    }

    private companion object {
        const val STATEMENT_TIMEOUT_SECONDS = 2
        const val FINGERPRINT = 64
    }
}
