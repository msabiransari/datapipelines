package co.datapipelines.web.config

import co.datapipelines.application.datasources.LakeTableMutationGate
import co.datapipelines.application.datasources.LakeTableOrigin
import co.datapipelines.application.datasources.LakeTableRegistryService
import co.datapipelines.application.datasources.LakeTableRepository
import co.datapipelines.application.datasources.ManagedLakeTableRepository
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.datasources.LakeIntrospectionCache
import co.datapipelines.datasources.LakeRegisteredTable
import co.datapipelines.datasources.LakeTableCatalog
import co.datapipelines.datasources.LakeViewOutcomeRecorder
import co.datapipelines.datasources.ManagedLakeCatalog
import co.datapipelines.datasources.ManagedLakeTable
import co.datapipelines.datasources.PoolInvalidationPublisher
import co.datapipelines.lake.LakeProperties
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

/**
 * The dp-lake catalog's beans (metadata-db §4.15, round 089 §A) — a sibling of
 * [DomainConfiguration]'s datasource wiring, kept apart because that class is at the
 * house function-density ceiling.
 */
@Configuration
class LakeConfiguration {
    /** The dp-lake catalog's rows. */
    @Bean
    fun lakeTableRepository(jdbc: NamedParameterJdbcTemplate): LakeTableRepository = LakeTableRepository(jdbc)

    /**
     * The `datasources`-module port over the registry (089 §B/§C — the [datasourceReferences]
     * precedent: the module declares the question, this layer answers it). ONE bean serves both
     * readers: the pool factory's per-table view generation and the introspector's
     * registry-backed listings. The mapping is a dumb projection — validation already happened
     * at registration, and the view generator re-refuses a bad location at the SQL boundary.
     * `lastError` (109 §A, V22) crosses the port so the pool build can record TRANSITIONS only
     * and the executor can refuse a node that references a broken table.
     */
    @Bean
    fun lakeTableCatalog(
        tables: LakeTableRepository,
        managedLake: ManagedLakeCatalog,
    ): LakeTableCatalog =
        LakeTableCatalog { datasourceName ->
            val rows = tables.findByDatasource(datasourceName)
            // #818: a managed row is enriched from the managed read (catalog location, lifecycle), so every
            // reader of this port (introspection, stats, the probe's listing root) sees what the generation
            // serves. The read runs only when the datasource HAS managed rows, and a failure propagates.
            val managed =
                if (rows.none { it.origin == LakeTableOrigin.MANAGED }) {
                    emptyMap()
                } else {
                    managedLake.managedTables(datasourceName).associateBy { it.qualifiedName }
                }
            rows.map { row ->
                val current = managed[(row.namespace + row.name).joinToString(".")]
                val isManaged = row.origin == LakeTableOrigin.MANAGED
                LakeRegisteredTable(
                    namespace = row.namespace,
                    name = row.name,
                    format = row.format.wire,
                    location = if (isManaged) current?.metadataLocation ?: row.location else row.location,
                    partitionColumn = row.partitionColumn,
                    lastError = row.lastError,
                    managed = isManaged,
                    notice = if (isManaged) current?.deprecationMessage else null,
                    // A served table's reason is null; only a managed row the managed read did not return at all
                    // (it raced a delete between the two reads) falls back to "no lifecycle row".
                    unavailableReason =
                        when {
                            !isManaged -> null
                            current == null -> ManagedLakeTable.REASON_NO_LIFECYCLE_ROW
                            else -> current.unavailableReason
                        },
                )
            }
        }

    /** #818 (record §6) — the managed lake datasource at boot; see [ManagedLakeDatasourceRegistrar] for the ordering. */
    @Bean
    @Suppress("LongParameterList") // each collaborator is one write the registrar owns
    fun managedLakeDatasourceRegistrar(
        lakeProperties: ObjectProvider<LakeProperties>,
        datasources: DatasourceRegistry,
        grants: co.datapipelines.datasources.DatasourceGrantRepository,
        applyRepository: ObjectProvider<co.datapipelines.lake.definitions.LakeApplyRepository>,
        userService: co.datapipelines.auth.UserService,
        lakeCatalogStartupCheck: LakeCatalogStartupCheck,
    ): ManagedLakeDatasourceRegistrar =
        ManagedLakeDatasourceRegistrar(lakeProperties, datasources, grants, applyRepository, userService, lakeCatalogStartupCheck)

    /** #818 — the managed read's SQL (a sibling of [LakeTableRepository], see its KDoc). */
    @Bean
    fun managedLakeTableRepository(jdbc: NamedParameterJdbcTemplate): ManagedLakeTableRepository = ManagedLakeTableRepository(jdbc)

    /**
     * #818 (record §10) — the `datasources`-module port the managed generation is built from and the freshness
     * tick compares against. Bound whether or not the lake is enabled: with `datapipelines.lake.enabled=false`
     * ([LakeProperties] is then not bound) every managed table is reported unavailable (`lake.disabled`), so a
     * managed row left over from an enabled past serves no view instead of an unbounded one.
     */
    @Bean
    fun managedLakeCatalog(
        repository: ManagedLakeTableRepository,
        lakeProperties: ObjectProvider<LakeProperties>,
    ): ManagedLakeCatalog {
        val enabled = lakeProperties.ifAvailable?.enabled == true
        return ManagedLakeCatalog { lake ->
            repository.managedTables(lake).let { rows -> if (enabled) rows else rows.map { it.copy(lakeDisabled = true) } }
        }
    }

    /**
     * 109 §A — the pool build's outcome write, over the same repository: the per-connection
     * view applier calls it on transitions only, so this stays a rare UPDATE, never one per
     * physical connection.
     */
    @Bean
    fun lakeViewOutcomeRecorder(tables: LakeTableRepository): LakeViewOutcomeRecorder =
        LakeViewOutcomeRecorder { datasourceName, namespace, table, error ->
            tables.recordViewOutcome(datasourceName, namespace, table, error)
        }

    /**
     * ONE instance shared by the introspector (which serves from it) and the registry service
     * (which invalidates it on every mutation) — two instances would silently never invalidate
     * the one being read.
     */
    @Bean
    fun lakeIntrospectionCache(): LakeIntrospectionCache = LakeIntrospectionCache()

    /**
     * The ONE validated lake-table path, shared by the REST `/tables` endpoints and the
     * `lake_tables_*` MCP tools (049's principle). The D8 mutation gate is the same
     * [co.datapipelines.web.datasources.DatasourceWorkspaceRules] instance the datasource CUD
     * endpoints consult, passed as a method reference because `application` sits below `web`
     * and cannot import the rules — mutating a global datasource's registry is admin-only,
     * exactly as mutating the datasource itself is. The invalidation publisher is the same
     * §5.7 channel the datasource registry publishes pool evictions on.
     */
    @Bean
    fun lakeTableRegistryService(
        datasources: DatasourceRegistry,
        lakeTables: LakeTableRepository,
        invalidation: PoolInvalidationPublisher,
        rules: co.datapipelines.web.datasources.DatasourceWorkspaceRules,
        introspectionCache: LakeIntrospectionCache,
    ): LakeTableRegistryService =
        LakeTableRegistryService(
            datasources = datasources,
            tables = lakeTables,
            invalidation = invalidation,
            mutationGate =
                LakeTableMutationGate { principal, datasource ->
                    rules.requireGlobalMutationAllowed(principal, datasource, datasource.name)
                },
            introspectionCache = introspectionCache,
        )
}
