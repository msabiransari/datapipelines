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
import co.datapipelines.executor.BoundedJdbc
import co.datapipelines.lake.LakeProperties
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.support.TransactionSynchronizationManager
import javax.sql.DataSource

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
     * precedent: the module declares the question, this layer answers it), for the introspector's
     * registry-backed listings, the stats and the probe's listing root. The mapping is the
     * [projectionCatalog] — validation already happened at registration, and the view generator
     * re-refuses a bad location at the SQL boundary.
     *
     * #818b B: NOT the registry's port. The registry (pool builds, the executor's broken-view
     * check) takes the plain [projectionCatalog] (`DomainConfiguration.datasourceRegistry`), so
     * neither a build nor a node's CONNECT reads the managed catalog through here.
     *
     * #867: the managed enrichment is chosen PER CALL between two ports over the same metadata DataSource
     * ([transactionAwareManagedCatalog]). Outside a metadata transaction (the introspection and stats listings) it reads
     * through a dedicated [BoundedJdbc] template at [MANAGED_BUILD_READ_TIMEOUT_SECONDS] — a 5 s statement bound and a
     * 10 s socket bound — so a metadata server that holds a lock or stops answering cannot hold the listing's managed read
     * forever. Inside one (a release's, an import's and a promotion's: the lake reference scan, the promotion check) it
     * reads on the plain shared template and so inherits the transaction's connection and its policy: a bounded statement
     * leaves its socket bound on the transaction's connection until the transaction ends (`BoundedJdbc`), which would cut
     * that transaction's later, longer statements (#833 merge review F2). The generation build's
     * [managedLakeTableRepository] is a separate bean and unchanged.
     */
    @Bean
    fun lakeTableCatalog(
        tables: LakeTableRepository,
        jdbc: NamedParameterJdbcTemplate,
        lakeProperties: ObjectProvider<LakeProperties>,
    ): LakeTableCatalog {
        val enabled = lakeProperties.ifAvailable?.enabled == true
        val listingRead =
            BoundedJdbc.bounded(jdbc, MANAGED_BUILD_READ_TIMEOUT_SECONDS, "MANAGED_BUILD_READ_TIMEOUT_SECONDS")
        return enrichedCatalog(
            tables,
            transactionAwareManagedCatalog(
                metadata = requireNotNull(jdbc.jdbcTemplate.dataSource) { "the listing enrichment requires a DataSource-backed template" },
                inTransaction = managedLakeCatalogOver(ManagedLakeTableRepository(jdbc), enabled),
                outsideTransaction = managedLakeCatalogOver(ManagedLakeTableRepository(listingRead), enabled),
            ),
        )
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

    /**
     * #818 — the managed read's SQL (a sibling of [LakeTableRepository], see its KDoc).
     *
     * #833: over its own template bounded at [MANAGED_BUILD_READ_TIMEOUT_SECONDS]. This repository's one statement is
     * the catalog read a managed generation BUILD runs first (`DefaultDatasourceRegistry.managedLakeViewInit`), on the
     * lease's thread inside `poolFor`; unbounded, a held lock on the catalog tables would stall every lease that needs
     * a new generation for as long as the lock is held. A cancelled read is that build's `lake.unavailable`, and the
     * live generation (if any) serves until its age bound, as for any failed catalog read. The shared template the
     * bound is built over is unchanged, so no other statement on it gains a bound. The listings' managed enrichment
     * ([lakeTableCatalog]) does NOT use this bean: it chooses its own port per call (#867), because it also runs inside
     * metadata transactions (#833 merge review F2).
     */
    @Bean
    fun managedLakeTableRepository(jdbc: NamedParameterJdbcTemplate): ManagedLakeTableRepository =
        ManagedLakeTableRepository(
            BoundedJdbc.bounded(jdbc, MANAGED_BUILD_READ_TIMEOUT_SECONDS, "MANAGED_BUILD_READ_TIMEOUT_SECONDS"),
        )

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
    ): ManagedLakeCatalog = managedLakeCatalogOver(repository, enabled = lakeProperties.ifAvailable?.enabled == true)

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

    companion object {
        /**
         * #833 — the managed generation build's catalog read bound, in seconds: the freshness tick's own
         * `ManagedLakeFreshnessScheduler.FRESHNESS_STATEMENT_TIMEOUT_SECONDS` value, because both bound the same
         * one-row-per-table catalog read on the metadata database. The listings' managed enrichment outside a metadata
         * transaction takes the same bound (#867): it is that read too. A constant, not a key (datasources.md §8C.10).
         */
        const val MANAGED_BUILD_READ_TIMEOUT_SECONDS = 5

        /**
         * The introspection port over the registry [tables]: [projectionCatalog]'s rows, with every managed row
         * enriched from [managedLake] (catalog location, lifecycle). The bean, [LakeConfiguration.lakeTableCatalog],
         * passes the transaction-aware port (#867).
         */
        fun enrichedCatalog(
            tables: LakeTableRepository,
            managedLake: ManagedLakeCatalog,
        ): LakeTableCatalog {
            val projection = projectionCatalog(tables)
            return LakeTableCatalog { datasourceName ->
                val rows = projection.registeredTables(datasourceName)
                // #818: a managed row is enriched from the managed read (catalog location, lifecycle), so every
                // reader of this port (introspection, stats, the probe's listing root) sees the catalog's current
                // facts. The read runs only when the datasource HAS managed rows, and a failure propagates.
                if (rows.none { it.managed }) return@LakeTableCatalog rows
                val managed = managedLake.managedTables(datasourceName).associateBy { it.qualifiedName }
                rows.map { row ->
                    if (!row.managed) return@map row
                    val current = managed[(row.namespace + row.name).joinToString(".")]
                    row.copy(
                        location = current?.metadataLocation ?: row.location,
                        notice = current?.deprecationMessage,
                        // A served table's reason is null; only a managed row the managed read did not return at all
                        // (it raced a delete between the two reads) falls back to "no lifecycle row".
                        unavailableReason = if (current == null) ManagedLakeTable.REASON_NO_LIFECYCLE_ROW else current.unavailableReason,
                    )
                }
            }
        }

        /**
         * #867 — [inTransaction] when Spring has a connection bound for [metadata] on the calling thread (a metadata
         * transaction, or a nested participant in one), else [outsideTransaction]; decided at every call, never when the
         * bean is built. The test is the resource binding for THIS DataSource (`TransactionSynchronizationManager`, the
         * key `DataSourceUtils` hands a transaction's connection out by). An active synchronization or a transaction on
         * another DataSource selects [outsideTransaction] only when no metadata connection is already bound. The
         * listing's preceding projection read can bind that connection to the scope; enrichment then preserves its
         * policy through [inTransaction]. Nothing wraps the DataSource and no transaction is opened: both ports run on
         * whatever connection `DataSourceUtils` gives the same DataSource.
         */
        fun transactionAwareManagedCatalog(
            metadata: DataSource,
            inTransaction: ManagedLakeCatalog,
            outsideTransaction: ManagedLakeCatalog,
        ): ManagedLakeCatalog =
            ManagedLakeCatalog { lake ->
                val port = if (TransactionSynchronizationManager.getResource(metadata) != null) inTransaction else outsideTransaction
                port.managedTables(lake)
            }

        /**
         * `lake_tables` as stored, one [LakeRegisteredTable] per row: the location is the registry column, a managed
         * row is only MARKED managed (no catalog read, no reason, no notice). `lastError` (109 §A, V22) crosses the
         * port so the pool build records TRANSITIONS only and the executor refuses a node naming a broken table.
         * The registry's port (#818b B): one managed generation reads the catalog exactly once, in its build.
         */
        fun projectionCatalog(tables: LakeTableRepository): LakeTableCatalog =
            LakeTableCatalog { datasourceName ->
                tables.findByDatasource(datasourceName).map { row ->
                    LakeRegisteredTable(
                        namespace = row.namespace,
                        name = row.name,
                        format = row.format.wire,
                        location = row.location,
                        partitionColumn = row.partitionColumn,
                        lastError = row.lastError,
                        managed = row.origin == LakeTableOrigin.MANAGED,
                    )
                }
            }

        /**
         * The managed catalog port over [repository]: its rows as read, or, when the lake is not [enabled], every row
         * marked `lakeDisabled`. One definition for both readers, the bean above and the freshness tick's bounded
         * one ([PoolReaperSchedulingConfiguration.managedLakeFreshnessScheduler]), so the two cannot disagree on
         * what a disabled lake reports.
         */
        fun managedLakeCatalogOver(
            repository: ManagedLakeTableRepository,
            enabled: Boolean,
        ): ManagedLakeCatalog =
            ManagedLakeCatalog { lake ->
                repository.managedTables(lake).let { rows -> if (enabled) rows else rows.map { it.copy(lakeDisabled = true) } }
            }
    }
}
