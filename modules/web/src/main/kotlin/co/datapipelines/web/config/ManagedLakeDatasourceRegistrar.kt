package co.datapipelines.web.config

import co.datapipelines.auth.UserService
import co.datapipelines.datasources.CredentialKind
import co.datapipelines.datasources.Datasource
import co.datapipelines.datasources.DatasourceGrantRepository
import co.datapipelines.datasources.DatasourceProperties
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.datasources.LakeHttpBounds
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
 * the store's region and endpoint as [EgressStoreTarget] derives them (#862: declared always, so the engine dials
 * exactly the authority the boot probe checks and the proxy allowlists), the store's URL style,
 * `memory_limit = reads.memory-limit`, and — when
 * `reads.egress-proxy` is set — `egress_proxy` (#845), which the adapter turns into `SET http_proxy` ahead of the
 * §15a lockdown. Adding or changing that key RESYNCs the row on the next boot. The URL is the anonymous
 * in-memory engine, so the §15a lock on each generation reaches no other pool. The credential is the reader
 * pair, else the writer pair, through the registry's encrypted credential path, never `properties.dialect`.
 * Neither pair set means the instance's credential chain (`CredentialKind.NONE`).
 *
 * Then, every boot: the backfill of registry rows for tables defined while no datasource row existed, and the
 * grant to every workspace (record D3: the lake is server-level). A workspace created through the product is
 * granted in its own creation transaction ([grantTo], #823), so it never exists without the lake; [regrant]
 * repeats the grant on the freshness tick as the REPAIR path (a workspace inserted by SQL, a grant an operator
 * deleted by hand, a grant that predates this registrar). The tick's grant runs over the repository the tick
 * passes in (its statement-bounded one, #818b A) and as the system actor this registrar provisioned at boot, so
 * the tick issues exactly one statement and provisions nothing. Neither path writes an audit row: the server
 * granting its own datasource is not a person's act, and a creation is covered by `auth.workspace.created`.
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

    /** The system actor [register] provisioned; null until it ran with the lake enabled (then [regrant] does nothing). */
    @Volatile private var systemActor: UUID? = null

    @PostConstruct
    fun register() {
        val properties = lakeProperties.ifAvailable?.takeIf { it.enabled } ?: return
        // #845 D4: the egress proxy answers, declares the configured max-age and reaches the store, or the boot stops
        // here (lake.boot.egress_proxy) before anything is written.
        ManagedLakeEgressCheck.verify(properties)
        val actor = userService.provisionSystemActor().id
        systemActor = actor
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

    /**
     * #823 (owner ruling 2026-11-07) — the managed lake granted to ONE workspace, as the boot-provisioned system actor,
     * through this registrar's own repository so the INSERT joins the transaction the caller is in (workspace creation's).
     * Idempotent: returns true when a row was created, false when the grant existed, the lake is disabled, or [register]
     * has not provisioned the actor. It takes no datasource name and no actor — the one thing it can grant is the managed
     * lake, and the grantor is the server, never the principal creating the workspace. Not logged: a creation that
     * grants is the norm (`WorkspaceService.create` logs `managed_lake_granted`); the event below reports repairs.
     */
    fun grantTo(workspaceId: UUID): Boolean {
        val properties = lakeProperties.ifAvailable?.takeIf { it.enabled } ?: return false
        val actor = systemActor ?: return false
        return grants.grant(properties.name, workspaceId, actor)
    }

    /**
     * The freshness tick's half (record §10, D3): grant the managed lake to workspaces created since the last
     * pass, through [tickGrants] (the tick's bounded repository), as the boot-provisioned system actor. One
     * INSERT; nothing when the lake is disabled or [register] has not provisioned the actor.
     */
    fun regrant(tickGrants: DatasourceGrantRepository) {
        val properties = lakeProperties.ifAvailable?.takeIf { it.enabled } ?: return
        val actor = systemActor ?: return
        grantEveryWorkspace(properties.name, actor, tickGrants)
    }

    private fun grantEveryWorkspace(
        name: String,
        actor: UUID,
        through: DatasourceGrantRepository = grants,
    ) {
        val granted = through.grantToEveryWorkspace(name, actor)
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
            // #862: the secret carries the DERIVED endpoint and the EFFECTIVE region, always — one form covers
            // engine, boot probe and proxy renderer ([EgressStoreTarget], probe A: the engine would resolve a
            // blank endpoint itself to s3.<region>.amazonaws.com; declaring it keeps every consumer on the same
            // authority the proxy allowlists). Blank endpoint ⇒ the regional AWS https form.
            val target =
                EgressStoreTarget.derive(
                    endpoint = storage.endpoint,
                    region = storage.region,
                    bucket = storage.bucket,
                    pathStyle = storage.pathStyle,
                )
            val dialect =
                buildMap<String, Any?> {
                    put("catalog.kind", LakeProperties.KIND_S3)
                    put(MANAGED_DIALECT_KEY, "true")
                    put("region", target.region)
                    put("endpoint", target.duckdbEndpoint)
                    put("url_style", if (storage.pathStyle) "path" else "vhost")
                    put("memory_limit", duckdbSize(properties.reads.memoryLimit))
                    // #845: the egress proxy the adapter points `http_proxy` at; absent when configuration sets none.
                    properties.reads.egressProxy
                        .trim()
                        .takeIf { it.isNotEmpty() }
                        ?.let { put(LakeHttpBounds.EGRESS_PROXY_KEY, it) }
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
