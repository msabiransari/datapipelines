package co.datapipelines.lake.definitions

import co.datapipelines.auth.AuditEventSink
import co.datapipelines.lake.LakeCatalogs
import co.datapipelines.lake.LakeLeases
import co.datapipelines.lake.LakeMinio
import co.datapipelines.lake.LakeProperties
import co.datapipelines.lake.LakeTestDb
import co.datapipelines.lake.settle.LakeMetadataReads
import co.datapipelines.lake.settle.SettleBudget
import co.datapipelines.pipeline.AuthoringGuard
import co.datapipelines.pipeline.WriteSurface
import org.apache.iceberg.io.FileIO
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.util.UUID

/** One audit call as the sink saw it, with whether a transaction was open on the calling thread at that moment. */
internal data class RecordedAudit(
    val event: String,
    val userId: UUID?,
    val details: Map<String, Any?>,
    val inTransaction: Boolean,
)

/** What [RecordingAuditSink] throws for the event it was told to fail ([RecordingAuditSink.failOn]). */
internal class AuditSinkFailure(
    event: String,
) : RuntimeException("the audit sink refused $event")

/**
 * A recording sink (never a strict mock: a missing call must be observable as an absent record). [failOn], when set,
 * makes the call for that event throw [AuditSinkFailure] and record nothing, as a failed `audit_log` insert would: the
 * seam that shows an audited write rolls back with its audit row (#814).
 */
internal class RecordingAuditSink : AuditEventSink {
    val events = mutableListOf<RecordedAudit>()

    @Volatile
    var failOn: String? = null

    override fun log(
        event: String,
        userId: UUID?,
        keyId: String?,
        sourceIp: String?,
        userAgent: String?,
        details: Map<String, Any?>,
    ) {
        if (event == failOn) throw AuditSinkFailure(event)
        events += RecordedAudit(event, userId, details, TransactionSynchronizationManager.isActualTransactionActive())
    }
}

/**
 * The definitions wired as production wires them ([LakeDefinitionsAutoConfiguration]'s graph) over the module's one
 * Postgres ([LakeTestDb], V53 + V54 applied) and MinIO ([LakeMinio]), with a short lease wait and an injectable
 * after-commit hook. [reset] empties the catalog (LakeTestDb.reset) and every P3 table, and seeds one user.
 * [fileIoFactory], when given, replaces the catalog's object-store client factory (a fault seam, #811); null keeps
 * production's `S3FileIO`. It is a function-typed parameter: pass it by name, never as a trailing lambda. [leases]
 * replaces the module's shared lease protocol (a shorter lock timeout, #812).
 */
internal class LakeDefinitionsHarness(
    leaseWait: Duration = Duration.ofSeconds(1),
    hooks: LakeApplyHooks = LakeApplyHooks.NONE,
    authoringEnabled: Boolean = true,
    fileIoFactory: ((Map<String, String>) -> FileIO)? = null,
    val leases: LakeLeases = LakeTestDb.leases,
) {
    val jdbc = LakeTestDb.jdbc
    val named = NamedParameterJdbcTemplate(LakeTestDb.dataSource)
    val properties = LakeProperties(enabled = true, storage = LakeMinio.storage(bucket = BUCKET))
    val catalogs =
        fileIoFactory?.let { LakeCatalogs(properties, LakeTestDb.catalogDatabase(), it) }
            ?: LakeCatalogs(properties, LakeTestDb.catalogDatabase())
    val audit = RecordingAuditSink()
    val tx = TransactionTemplate(DataSourceTransactionManager(LakeTestDb.dataSource))
    val definitions = LakeDefinitionRepository(named)
    val applies = LakeApplyRepository(named)
    val applyLeases = LakeApplyLeases(leases, leaseWait, Duration.ofMillis(POLL_MILLIS))
    val applier = LakeDefinitionApplier(definitions, applies, applyLeases, catalogs, properties.name, tx, audit, hooks)
    private val metadataReads = LakeMetadataReads(named, properties.name, catalogs::metadataFileIo)
    private val policyJson = LakeTablePolicy.defaultsJson(properties)
    val service =
        LakeDefinitionService(
            definitions = definitions,
            applier = applier,
            leases = leases,
            jobTimeout = properties.admission.jobTimeout,
            policyJson = policyJson,
            tx = tx,
            audit = audit,
            authoring = AuthoringGuard(authoringEnabled),
        )

    val actor: UUID = UUID.fromString("00000000-0000-0000-0000-000000000798")

    /** The sweep's verification under a budget a case never spends (spent budgets: `SettlementWholeTickBoundIntegrationTest`). */
    fun verifyApplying(id: UUID): LakeApplyOutcome =
        applier.verifyApplying(id, SettleBudget.startingNow(leases, Duration.ofMinutes(1)), metadataReads)

    fun reset() {
        LakeTestDb.reset()
        jdbc.execute(
            "TRUNCATE lake_column_semantics, lake_table_definition_versions, lake_tables_managed, lake_namespaces, lake_tables CASCADE",
        )
        jdbc.update(
            "INSERT INTO users (id, email, display_name, provider, provider_subject) VALUES (?, ?, 'Lane 798', 'google', ?) " +
                "ON CONFLICT (id) DO NOTHING",
            actor,
            "lane798@example.com",
            "sub-798",
        )
        jdbc.update("DELETE FROM datasources WHERE name = ?", properties.name)
    }

    /** Registers the managed lake's datasource row (P5/P8 create it at boot; absent at this lane's base). */
    fun registerLakeDatasource() {
        jdbc.update(
            "INSERT INTO datasources (name, display_name, dialect, jdbc_url, credential_kind, created_by) " +
                "VALUES (?, 'Managed lake', 'LAKE', 'jdbc:duckdb:', 'none', ?)",
            properties.name,
            actor,
        )
    }

    fun revisions(
        namespace: String,
        table: String,
    ): Int =
        requireNotNull(
            jdbc.queryForObject(
                "SELECT count(*) FROM lake_table_revisions WHERE namespace = ? AND table_name = ?",
                Int::class.java,
                namespace,
                table,
            ),
        )

    fun table(id: UUID): LakeManagedTable = requireNotNull(definitions.findTable(id)) { "no managed table $id" }

    /** Releases [version] at the repository level only — the landing without its apply (a promotion-like `pending`). */
    fun landWithoutApply(
        tableId: UUID,
        version: Int,
    ) {
        tx.executeWithoutResult {
            check(definitions.releaseDraft(tableId, version, null, actor) == 1)
            check(definitions.pointAtRelease(tableId, version) == 1)
        }
    }

    /**
     * Stores table `<namespace>.<table>` with draft version 1 holding [bodyJson] as it is, through the repository and
     * without the service's rules: a row written before a rule it would now break, such as a legacy body without
     * sort columns (lake.md §1). Its canonical text is what [LakeDefinitionBodies.toJson] writes.
     */
    fun insertUnvalidatedDraft(
        namespace: String,
        table: String,
        bodyJson: String,
    ): UUID =
        requireNotNull(
            tx.execute {
                val tableId = UUID.randomUUID()
                check(definitions.insertTable(tableId, definitions.namespaceId(namespace, actor), table, policyJson, actor))
                val canonical = LakeDefinitionBodies.toJson(LakeDefinitionBodies.read(bodyJson))
                checkNotNull(definitions.insertDraft(tableId, 1, canonical, actor, WriteSurface.SESSION))
                tableId
            },
        )

    /** Overwrites [tableId]'s draft with [bodyJson] as it is, through the repository and without the service's rules. */
    fun overwriteUnvalidatedDraft(
        tableId: UUID,
        bodyJson: String,
    ) {
        val draft = requireNotNull(definitions.findDraft(tableId)) { "table $tableId has no draft" }
        val canonical = LakeDefinitionBodies.toJson(LakeDefinitionBodies.read(bodyJson))
        checkNotNull(definitions.overwriteDraft(tableId, canonical, draft.bodyHash, actor, WriteSurface.SESSION))
    }

    companion object {
        const val BUCKET = "dp-lake-798"
        private const val POLL_MILLIS = 100L
    }
}
