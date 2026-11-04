package co.datapipelines.lake.definitions

import co.datapipelines.auth.AuditEventSink
import co.datapipelines.lake.LakeCatalogs
import co.datapipelines.lake.LakeMinio
import co.datapipelines.lake.LakeProperties
import co.datapipelines.lake.LakeTestDb
import co.datapipelines.pipeline.AuthoringGuard
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

/** A recording sink (never a strict mock: a missing call must be observable as an absent record). */
internal class RecordingAuditSink : AuditEventSink {
    val events = mutableListOf<RecordedAudit>()

    override fun log(
        event: String,
        userId: UUID?,
        keyId: String?,
        sourceIp: String?,
        userAgent: String?,
        details: Map<String, Any?>,
    ) {
        events += RecordedAudit(event, userId, details, TransactionSynchronizationManager.isActualTransactionActive())
    }
}

/**
 * The definitions wired as production wires them ([LakeDefinitionsAutoConfiguration]'s graph) over the module's one
 * Postgres ([LakeTestDb], V53 + V54 applied) and MinIO ([LakeMinio]), with a short lease wait and an injectable
 * after-commit hook. [reset] empties the catalog (LakeTestDb.reset) and every P3 table, and seeds one user.
 * [fileIoFactory], when given, replaces the catalog's object-store client factory (a fault seam, #811); null keeps
 * production's `S3FileIO`. It is a function-typed parameter: pass it by name, never as a trailing lambda.
 */
internal class LakeDefinitionsHarness(
    leaseWait: Duration = Duration.ofSeconds(1),
    hooks: LakeApplyHooks = LakeApplyHooks.NONE,
    authoringEnabled: Boolean = true,
    fileIoFactory: ((Map<String, String>) -> FileIO)? = null,
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
    val applyLeases = LakeApplyLeases(LakeTestDb.leases, leaseWait, Duration.ofMillis(POLL_MILLIS))
    val applier = LakeDefinitionApplier(definitions, applies, applyLeases, catalogs, properties.name, tx, audit, hooks)
    val service =
        LakeDefinitionService(
            definitions = definitions,
            applier = applier,
            leases = LakeTestDb.leases,
            jobTimeout = properties.admission.jobTimeout,
            policyJson = LakeTablePolicy.defaultsJson(properties),
            tx = tx,
            audit = audit,
            authoring = AuthoringGuard(authoringEnabled),
        )

    val actor: UUID = UUID.fromString("00000000-0000-0000-0000-000000000798")

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

    companion object {
        const val BUCKET = "dp-lake-798"
        private const val POLL_MILLIS = 100L
    }
}
