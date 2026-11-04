package co.datapipelines.web.config

import co.datapipelines.auth.UserService
import co.datapipelines.datasources.CredentialKind
import co.datapipelines.datasources.Datasource
import co.datapipelines.datasources.DatasourceGrantRepository
import co.datapipelines.datasources.DatasourceProperties
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.datasources.MANAGED_DIALECT_KEY
import co.datapipelines.datasources.ManagedLakeSync
import co.datapipelines.lake.LakeBootCheck
import co.datapipelines.lake.LakeProperties
import co.datapipelines.lake.definitions.LakeApplyRepository
import co.datapipelines.typesystem.Dialect
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.util.unit.DataSize
import java.util.UUID

/**
 * #818 (dp-lake P5, design record §6 "Relationship to the existing LAKE datasource") — the managed lake IS a LAKE
 * datasource, created at boot when `datapipelines.lake.enabled=true` and RE-SYNCED from configuration at every
 * boot (credential rotation = config change + restart). Unlike the bootstrap file's create-if-absent registrar,
 * this one rewrites a drifted row, because configuration owns it.
 *
 * ## Ordering
 * `@PostConstruct`, like [LakeCatalogStartupCheck]: it runs during singleton creation, after Flyway (the
 * registry's JDBC templates depend on it) and after the lake boot check (the [LakeCatalogStartupCheck]
 * constructor dependency guarantees that bean's `@PostConstruct` already ran). Every `SmartInitializingSingleton`,
 * the bootstrap datasource registration included, runs later, so a bootstrap file naming the lake finds the
 * managed row and skips it as already present. Definition applies run on requests, after the server starts.
 *
 * ## What it writes
 * One row named `lake.name`: dialect LAKE, `dialect.catalog.kind = s3`, `dialect.managed = true`, read-only,
 * the store's region, endpoint and URL style, and `memory_limit = reads.memory-limit`. The URL is the anonymous
 * in-memory engine, so the §15a lock on each generation reaches no other pool. The credential is the reader
 * pair, else the writer pair, through the registry's encrypted credential path, never `properties.dialect`.
 * Neither pair set means the instance's credential chain (`CredentialKind.NONE`).
 *
 * Then, every boot: the backfill of registry rows for tables defined while no datasource row existed, and the
 * grant to every workspace (record D3: the lake is server-level). [regrant] repeats the grant on the freshness
 * tick, so a workspace created after boot sees the lake within one tick.
 *
 * `enabled=false`: nothing is written, and an existing managed row stays where it is (record §6). The managed
 * catalog bean then reports every managed table unavailable (`lake.disabled`).
 */
class ManagedLakeDatasourceRegistrar(
    private val lakeProperties: ObjectProvider<LakeProperties>,
    private val registry: DatasourceRegistry,
    private val grants: DatasourceGrantRepository,
    private val applyRepository: ObjectProvider<LakeApplyRepository>,
    private val userService: UserService,
    /** Held only for ordering: Spring finishes this bean, its boot check included, before constructing the registrar. */
    @Suppress("unused") private val bootCheck: LakeCatalogStartupCheck,
) {
    private val log = LoggerFactory.getLogger(ManagedLakeDatasourceRegistrar::class.java)

    @PostConstruct
    fun register() {
        val properties = lakeProperties.ifAvailable?.takeIf { it.enabled } ?: return
        val actor = userService.provisionSystemActor().id
        val desired = desiredDatasource(properties)
        when (registry.syncManagedLake(desired, actor)) {
            ManagedLakeSync.NAME_TAKEN -> {
                error(
                    "${NAME_TAKEN.id}: ${NAME_TAKEN.summary} — '${desired.name}' is held by another datasource (live or " +
                        "deleted); rename it, or set datapipelines.lake.name to a free name",
                )
            }

            ManagedLakeSync.CREATED -> {
                log.info(
                    "event=lake.managed_datasource_registered datasource={} credential_kind={} " +
                        "message=\"managed lake datasource created from configuration\"",
                    desired.name,
                    desired.credentialKind.wire,
                )
            }

            ManagedLakeSync.RESYNCED -> {
                log.info(
                    "event=lake.managed_datasource_resynced datasource={} credential_kind={} " +
                        "message=\"managed lake datasource rewritten from configuration; its pools retire\"",
                    desired.name,
                    desired.credentialKind.wire,
                )
            }

            ManagedLakeSync.UNCHANGED -> {
                // Configuration and the stored row agree: nothing to write, nothing to log.
            }
        }
        val backfilled = applyRepository.ifAvailable?.backfillRegistryRows(desired.name, actor) ?: 0
        if (backfilled > 0) {
            log.info(
                "event=lake.managed_registry_backfilled datasource={} rows={} " +
                    "message=\"registry rows written for tables defined before the managed datasource existed\"",
                desired.name,
                backfilled,
            )
        }
        grantEveryWorkspace(desired.name, actor)
    }

    /** The freshness tick's half (record §10, D3): grant the managed lake to workspaces created since the last pass. */
    fun regrant() {
        val properties = lakeProperties.ifAvailable?.takeIf { it.enabled } ?: return
        grantEveryWorkspace(properties.name, userService.provisionSystemActor().id)
    }

    private fun grantEveryWorkspace(
        name: String,
        actor: UUID,
    ) {
        val granted = grants.grantToEveryWorkspace(name, actor)
        if (granted > 0) {
            log.info(
                "event=lake.managed_datasource_granted datasource={} workspaces={} " +
                    "message=\"managed lake granted to workspaces that did not see it\"",
                name,
                granted,
            )
        }
    }

    companion object {
        /** The anonymous in-memory DuckDB engine: every owner instance is its own engine (LakeInstanceOwner). */
        const val MANAGED_JDBC_URL = "jdbc:duckdb::memory:"

        private const val BYTES_PER_MB = 1024L * 1024L

        /** The catalogued boot refusal this registrar raises (configuration.md §7). */
        private val NAME_TAKEN = LakeBootCheck.Refusal.DATASOURCE_NAME_TAKEN

        /** The row configuration owns, credential included (plaintext here; the registry encrypts it). */
        internal fun desiredDatasource(properties: LakeProperties): Datasource {
            val storage = properties.storage
            val reader = storage.readerAccessKey.isNotBlank() && storage.readerSecretKey.isNotBlank()
            val (username, secret) =
                when {
                    reader -> storage.readerAccessKey to storage.readerSecretKey
                    storage.hasStaticCredentials -> storage.accessKey to storage.secretKey
                    else -> null to null
                }
            val dialect =
                buildMap<String, Any?> {
                    put("catalog.kind", LakeProperties.KIND_S3)
                    put(MANAGED_DIALECT_KEY, "true")
                    storage.region.takeIf { it.isNotBlank() }?.let { put("region", it) }
                    storage.endpoint.takeIf { it.isNotBlank() }?.let { put("endpoint", it) }
                    put("url_style", if (storage.pathStyle) "path" else "vhost")
                    put("memory_limit", duckdbSize(properties.reads.memoryLimit))
                }
            return Datasource(
                name = properties.name,
                displayName = "Managed lake",
                description = "The server's managed lake (datapipelines.lake.*): created and re-synced at boot, not editable.",
                dialect = Dialect.LAKE,
                jdbcUrl = MANAGED_JDBC_URL,
                username = username,
                credentialKind = if (secret == null) CredentialKind.NONE else CredentialKind.PASSWORD,
                secret = secret,
                properties = DatasourceProperties(dialect = dialect),
                isReadonly = true,
                ownerWorkspaceId = null,
            )
        }

        /** A DuckDB `memory_limit` literal the adapter's grammar admits: whole MB when exact, else KB. */
        private fun duckdbSize(size: DataSize): String =
            if (size.toBytes() % BYTES_PER_MB == 0L) "${size.toMegabytes()}MB" else "${size.toKilobytes()}KB"
    }
}
