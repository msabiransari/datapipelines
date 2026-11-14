package co.datapipelines.lake.append

import co.datapipelines.lake.LakeLeases
import co.datapipelines.lake.LakeMinio
import co.datapipelines.lake.LakeProperties
import co.datapipelines.lake.LakeTestDb
import co.datapipelines.lake.LeaseKind
import co.datapipelines.lake.definitions.LakeDefinitionFixtures
import co.datapipelines.lake.definitions.LakeDefinitionsHarness
import co.datapipelines.lake.delivery.DeliveryLedger
import co.datapipelines.lake.delivery.DeliveryLedgerRepository
import co.datapipelines.lake.delivery.DeliveryTarget
import co.datapipelines.lake.delivery.ReceiveDelivery
import co.datapipelines.lake.settle.DeliverySettlementRepository
import co.datapipelines.lake.settle.DeliverySettler
import co.datapipelines.lake.settle.LakeProjectionRepair
import co.datapipelines.pipeline.LakeAppendGrant
import co.datapipelines.pipeline.LakeExecutionInput
import co.datapipelines.pipeline.LakeExecutionLineage
import co.datapipelines.pipeline.LakeExecutionMetadata
import co.datapipelines.pipeline.PipelineJson
import co.datapipelines.pipeline.WriteSurface
import org.apache.iceberg.Table
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Real publication from an already-materialized result; entry-point/materialization acceptance is separate. */
internal class AppendPublicationHarness {
    val jdbc = JdbcTemplate(LakeTestDb.dataSource).apply { queryTimeout = 2 }
    val leases = LakeLeases(jdbc, lockTimeout = Duration.ofMillis(100))
    val h = LakeDefinitionsHarness(leaseWait = Duration.ZERO, leases = leases)
    val properties =
        LakeProperties(enabled = true, storage = h.properties.storage, admission = LakeProperties.Admission(leaseWaitSeconds = 0))
    val named = NamedParameterJdbcTemplate(jdbc)
    val deliveries = DeliveryLedgerRepository(named)
    val ledger = DeliveryLedger(deliveries, h.tx, h.audit)
    val repaired = mutableListOf<String>()
    val settler =
        DeliverySettler(
            DeliverySettlementRepository(named),
            deliveries,
            ledger,
            leases,
            LakeProjectionRepair(named, h.tx, properties.name) { repaired += it },
        )
    val native = ParquetWriterHarness()
    var watchesStarted = 0
    var watchesClosed = 0
    private var managedId: UUID? = null

    /** Releases `publish799.orders` with [body]; the default is one required `id` and no partition. */
    fun reset(body: String = DEFAULT_BODY) {
        h.reset()
        jdbc.execute("TRUNCATE lake_delivery_attempts, lake_deliveries")
        val draft = h.service.createDraft(NAMESPACE, TABLE, body, h.actor, WriteSurface.SESSION)
        h.service.release(draft.tableId, draft.version, h.actor, WriteSurface.SESSION)
        managedId = draft.tableId
        h.registerLakeDatasource()
        repaired.clear()
        watchesStarted = 0
        watchesClosed = 0
    }

    fun runner(
        client: software.amazon.awssdk.services.s3.S3Client,
        catalogs: co.datapipelines.lake.LakeCatalogs = h.catalogs,
    ): LakeWritebackRunner =
        LakeWritebackRunner(
            properties,
            leases,
            catalogs,
            h.definitions,
            deliveries,
            ledger,
            RecipeWriterFactory { execution ->
                RecipeWriter(client, properties.storage, RecipeDocument(RecipeRedactor { execution.lineage.childParameters(it) }))
            },
            LakeAppendHeartbeat { _, _, renew ->
                check(renew())
                watchesStarted++
                object : LakeAppendWatch {
                    override fun checkActive() {
                        check(renew())
                    }

                    override fun close() {
                        watchesClosed++
                    }
                }
            },
            settler,
        )

    fun withRequest(
        sql: String = "SELECT i::BIGINT AS id FROM range(3) t(i)",
        grant: Boolean = true,
        block: (AppendPublicationInput, ParquetWriterHarness.NativeInput) -> Unit,
    ) {
        native.withInput(sql) { rows ->
            val recipe = RecipeFixtures.publication()
            val id = UUID.randomUUID()
            val execution =
                LakeExecutionInput(
                    id,
                    recipe.pipeline.id,
                    recipe.pipeline.version,
                    UUID.randomUUID(),
                    recipe.pipeline.pipeline,
                    LakeExecutionMetadata(
                        PipelineJson.objectMapper().writeValueAsString(recipe.pipeline.executedBody),
                        recipe.pipeline.bodyHash,
                        true,
                    ),
                    recipe.pipeline.resolvedParameters,
                    if (grant) LakeAppendGrant(h.actor, "session", Instant.now()) else null,
                    LakeExecutionLineage(),
                )
            val received =
                ledger.receive(
                    ReceiveDelivery(requireNotNull(managedId), recipe.delivery, UUID.randomUUID(), id, execution.pipelineId),
                )
            block(
                AppendPublicationInput(
                    execution,
                    received.delivery,
                    recipe.delivery,
                    DeliveryTarget(NAMESPACE, TABLE),
                    rows,
                    leases.catalogNowPlus(Duration.ofMinutes(2)),
                ),
                rows,
            )
        }
    }

    fun <T> withTable(block: (Table) -> T): T {
        val lease = LakeTestDb.lease(LeaseKind.TABLE, "$NAMESPACE.$TABLE")
        try {
            return h.catalogs.open(lease).use { session -> block(session.loadTable(NAMESPACE, TABLE)) }
        } finally {
            leases.release(lease.operationId)
        }
    }

    fun keys(prefix: String): List<String> =
        LakeMinio.client().use { client ->
            client.listObjectsV2 { it.bucket(properties.storage.bucket).prefix(prefix) }.contents().map { it.key() }
        }

    fun dataKeys(): List<String> =
        withTable { table ->
            keys(table.location().removePrefix("s3://${properties.storage.bucket}/") + "/data/")
        }

    fun recipePrefix(): String = withTable { table -> table.location().removePrefix("s3://${properties.storage.bucket}/") + "/dp/recipes/" }

    companion object {
        const val NAMESPACE = "publish799"
        const val TABLE = "orders"
        val DEFAULT_BODY = LakeDefinitionFixtures.body(listOf(LakeDefinitionFixtures.field(1, "id", "BIGINTEGER", "required" to true)))
    }
}
