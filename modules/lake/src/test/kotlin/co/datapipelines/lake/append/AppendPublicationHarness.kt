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
import co.datapipelines.lake.profiles.LakeProfiles
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

/**
 * Real publication from an already-materialized result; entry-point/materialization acceptance is separate.
 *
 * [renewInterval]: the stand-in heartbeat renews the lease at each `checkActive` once this long has passed since its
 * last renewal. The default, zero, renews at every check (every row of a scan). A test that appends millions of rows
 * passes a positive interval, as the production heartbeat renews on a timer and never per row (#956 P10).
 */
internal class AppendPublicationHarness(
    private val renewInterval: Duration = Duration.ZERO,
) {
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

    /** The profile service the runner reads each append's sizes from (#956 D9), over the harness's database. */
    val profiles = LakeProfiles.over(named, h.tx, h.audit)
    var watchesStarted = 0
    var watchesClosed = 0
    private val managed = mutableMapOf<String, UUID>()

    /** Releases `publish799.orders` with [body]; the default is one required `id` and no partition. */
    fun reset(body: String = DEFAULT_BODY) {
        h.reset()
        jdbc.execute("TRUNCATE lake_delivery_attempts, lake_deliveries")
        val draft = h.service.createDraft(NAMESPACE, TABLE, body, h.actor, WriteSurface.SESSION)
        h.service.release(draft.tableId, draft.version, h.actor, WriteSurface.SESSION)
        managed.clear()
        managed[TABLE] = draft.tableId
        h.registerLakeDatasource()
        repaired.clear()
        watchesStarted = 0
        watchesClosed = 0
    }

    /** Releases one more table `publish799.<name>` beside [TABLE] (#956 P10 appends the same rows to two tables). */
    fun addTable(
        name: String,
        body: String = DEFAULT_BODY,
    ): UUID {
        val draft = h.service.createDraft(NAMESPACE, name, body, h.actor, WriteSurface.SESSION)
        h.service.release(draft.tableId, draft.version, h.actor, WriteSurface.SESSION)
        managed[name] = draft.tableId
        return draft.tableId
    }

    /** The managed table id of `publish799.<table>`. */
    fun managedId(table: String = TABLE): UUID = requireNotNull(managed[table]) { "no managed table $table in this harness" }

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
                    private var renewedAt = System.nanoTime()

                    override fun checkActive() {
                        val now = System.nanoTime()
                        if (now - renewedAt >= renewInterval.toNanos()) {
                            check(renew())
                            renewedAt = now
                        }
                    }

                    override fun close() {
                        watchesClosed++
                    }
                }
            },
            settler,
            profiles,
        )

    fun withRequest(
        sql: String = "SELECT i::BIGINT AS id FROM range(3) t(i)",
        grant: Boolean = true,
        table: String = TABLE,
        deadline: Duration = Duration.ofMinutes(2),
        deliveryId: String = DEFAULT_DELIVERY_ID,
        block: (AppendPublicationInput, ParquetWriterHarness.NativeInput) -> Unit,
    ) {
        native.withInput(sql) { rows ->
            val fixture = RecipeFixtures.publication()
            // A second append to one table needs its own delivery: the ledger keys a delivery by (table, feed, id).
            val recipe = fixture.copy(delivery = fixture.delivery.copy(key = fixture.delivery.key.copy(id = deliveryId)))
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
                    ReceiveDelivery(managedId(table), recipe.delivery, UUID.randomUUID(), id, execution.pipelineId),
                )
            block(
                AppendPublicationInput(
                    execution,
                    received.delivery,
                    recipe.delivery,
                    DeliveryTarget(NAMESPACE, table),
                    rows,
                    leases.catalogNowPlus(deadline),
                ),
                rows,
            )
        }
    }

    fun <T> withTable(
        name: String = TABLE,
        block: (Table) -> T,
    ): T {
        val lease = LakeTestDb.lease(LeaseKind.TABLE, "$NAMESPACE.$name")
        try {
            return h.catalogs.open(lease).use { session -> block(session.loadTable(NAMESPACE, name)) }
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
        const val DEFAULT_DELIVERY_ID = "batch"
        val DEFAULT_BODY = LakeDefinitionFixtures.body(listOf(LakeDefinitionFixtures.field(1, "id", "BIGINTEGER", "required" to true)))
    }
}
