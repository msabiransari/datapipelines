package co.datapipelines.lake.append

import co.datapipelines.auth.AuditEventSink
import co.datapipelines.lake.LakeCatalogs
import co.datapipelines.lake.LakeLeases
import co.datapipelines.lake.LakeProperties
import co.datapipelines.lake.definitions.LakeApplyLeases
import co.datapipelines.lake.definitions.LakeApplyRepository
import co.datapipelines.lake.definitions.LakeDefinitionApplier
import co.datapipelines.lake.definitions.LakeDefinitionRepository
import co.datapipelines.lake.definitions.LakeDefinitionsAutoConfiguration
import co.datapipelines.lake.delivery.DeliveryAdmission
import co.datapipelines.lake.delivery.DeliveryLanding
import co.datapipelines.lake.delivery.DeliveryLedger
import co.datapipelines.lake.delivery.DeliveryLedgerRepository
import co.datapipelines.lake.delivery.LakeProjectionInvalidation
import co.datapipelines.lake.delivery.LakeStagingStorage
import co.datapipelines.lake.delivery.TrustedDeliveryListing
import co.datapipelines.lake.settle.DeliverySettlementRepository
import co.datapipelines.lake.settle.DeliverySettler
import co.datapipelines.lake.settle.LakeMetadataReads
import co.datapipelines.lake.settle.LakeProjectionRepair
import co.datapipelines.lake.settle.LakeSemanticsRepair
import co.datapipelines.lake.settle.LakeSettlementSweep
import co.datapipelines.pipeline.DatasourceRegistry
import co.datapipelines.pipeline.LakeAppendPort
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import software.amazon.awssdk.services.s3.S3Client
import java.time.Duration
import javax.sql.DataSource

/** Execution and recovery share dedicated bounded statements; no shared template is reconfigured. */
@Suppress("LongParameterList")
class LakeAppendRuntime(
    dataSource: DataSource,
    transactionManager: PlatformTransactionManager,
    properties: LakeProperties,
    catalogs: LakeCatalogs,
    client: S3Client,
    storage: LakeStagingStorage,
    heartbeat: LakeAppendHeartbeat,
    invalidation: LakeProjectionInvalidation,
    registry: DatasourceRegistry,
    audit: AuditEventSink,
) : AutoCloseable {
    private val jdbc = statements(dataSource)
    private val named = NamedParameterJdbcTemplate(jdbc)
    private val transaction = TransactionTemplate(transactionManager).apply { timeout = STATEMENT_TIMEOUT_SECONDS }
    private val metadata = LakeMetadataReads(named, properties.name, catalogs::metadataFileIo)
    private val leases = LakeLeases(jdbc, lockTimeout = Duration.ofSeconds(properties.catalog.lockTimeoutSeconds))
    private val definitions = LakeDefinitionRepository(named)
    private val deliveries = DeliveryLedgerRepository(named)
    private val ledger = DeliveryLedger(deliveries, transaction, audit)
    val settler =
        DeliverySettler(
            DeliverySettlementRepository(named),
            deliveries,
            ledger,
            leases,
            LakeProjectionRepair(named, transaction, properties.name, invalidation::changed),
        )
    val sweep =
        LakeSettlementSweep(
            named,
            leases,
            settler,
            LakeDefinitionApplier(
                definitions,
                LakeApplyRepository(named),
                LakeApplyLeases(leases, Duration.ZERO),
                catalogs,
                properties.name,
                transaction,
                audit,
            ),
            LakeSemanticsRepair(named, transaction, DeliverySettlementRepository(named), definitions, leases, metadata),
            metadata,
        )
    private val publisher =
        LakeWritebackRunner(
            properties,
            leases,
            catalogs,
            definitions,
            deliveries,
            ledger,
            RecipeWriterFactory { execution ->
                RecipeWriter(client, properties.storage, RecipeDocument(RecipeRedactor { execution.lineage.childParameters(it) }))
            },
            heartbeat,
            settler,
        )
    val port: LakeAppendPort =
        ManagedLakeAppendPort(
            properties,
            DeliveryLanding(
                DeliveryAdmission(properties.admission, leases::catalogNowPlus),
                TrustedDeliveryListing(client, properties.storage, properties.admission.maxDeliveryBytes.toBytes()),
                definitions,
                ledger,
                settler,
            ),
            storage,
            publisher,
            registry,
        )

    /**
     * Destroys the tick's metadata thread with the context. Spring calls this after the scheduler that runs the tick has
     * stopped, so no tick is left to submit to it; a read still stuck on its socket is its daemon thread's alone.
     */
    override fun close() = metadata.close()

    companion object {
        const val STATEMENT_TIMEOUT_SECONDS = 5

        /** One dedicated template for every admission and recovery collaborator in this runtime. */
        fun statements(dataSource: DataSource): JdbcTemplate = JdbcTemplate(dataSource).apply { queryTimeout = STATEMENT_TIMEOUT_SECONDS }
    }
}

/** Inert with the lake disabled, just like the catalog configuration. */
@AutoConfiguration(after = [LakeDefinitionsAutoConfiguration::class])
@ConditionalOnProperty(prefix = LakeProperties.PREFIX, name = ["enabled"], havingValue = "true")
class LakeAppendAutoConfiguration {
    @Suppress("LongParameterList")
    @Bean
    fun lakeAppendRuntime(
        dataSource: DataSource,
        transactionManager: PlatformTransactionManager,
        properties: LakeProperties,
        catalogs: LakeCatalogs,
        lakeS3Client: S3Client,
        storage: LakeStagingStorage,
        heartbeat: LakeAppendHeartbeat,
        invalidation: LakeProjectionInvalidation,
        registry: DatasourceRegistry,
        audit: AuditEventSink,
    ): LakeAppendRuntime =
        LakeAppendRuntime(
            dataSource,
            transactionManager,
            properties,
            catalogs,
            lakeS3Client,
            storage,
            heartbeat,
            invalidation,
            registry,
            audit,
        )

    @Bean
    fun lakeAppendPort(runtime: LakeAppendRuntime): LakeAppendPort = runtime.port
}
