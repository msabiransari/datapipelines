package co.datapipelines.lake.maintenance

import co.datapipelines.auth.AuditEventSink
import co.datapipelines.auth.SystemActorPrincipals
import co.datapipelines.auth.UserService
import co.datapipelines.lake.LakeCatalogs
import co.datapipelines.lake.LakeLeases
import co.datapipelines.lake.LakeProperties
import co.datapipelines.lake.append.LakeAppendAutoConfiguration
import co.datapipelines.lake.append.LakeAppendRuntime
import co.datapipelines.lake.maintenance.compaction.CompactionPart
import co.datapipelines.lake.maintenance.compaction.CompactionScratchPort
import co.datapipelines.lake.maintenance.compaction.CompactionSettings
import co.datapipelines.lake.maintenance.compaction.CompactionTables
import co.datapipelines.lake.maintenance.manifests.ManifestRewritePart
import co.datapipelines.lake.maintenance.plan.MaintenanceDue
import co.datapipelines.lake.maintenance.plan.MaintenancePlanner
import co.datapipelines.lake.maintenance.plan.PlanBounds
import co.datapipelines.lake.maintenance.plan.PlanFiguresReader
import co.datapipelines.lake.maintenance.plan.PlanReaders
import co.datapipelines.lake.maintenance.plan.PlanRepository
import co.datapipelines.lake.maintenance.plan.ProfileMaintenanceGate
import co.datapipelines.lake.maintenance.plan.TableMeasurer
import co.datapipelines.lake.profiles.LakeProfiles
import co.datapipelines.scheduler.SystemJobQueue
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.time.Clock
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import javax.sql.DataSource

/**
 * The lake maintenance run's beans (#957 D2): they exist only with `datapipelines.lake.enabled=true`. The worker is a
 * `KeyedSystemJob`; the scheduler module turns it into its db-scheduler task (`SystemJobTaskRegistrar`), so nothing here
 * names a db-scheduler type. No request path constructs the worker: it is reached from the scheduler only.
 *
 * The gate and the scratch are ports with defaults: the gate is 959's [ProfileMaintenanceGate] (the window and the pauses),
 * the scratch [MaintenanceScratch.NONE]; the web module supplies 969's reservation over staging's budget (the staging
 * factory's `prepareDelivery`). A bean of either type replaces the default.
 *
 * #959: the planner (`lake-maintenance-planner`, a `RecurringSystemJob`) and its readers, repository and figures read
 * live here too; like the worker, it is reached from the scheduler only.
 */
@AutoConfiguration(after = [LakeAppendAutoConfiguration::class])
@ConditionalOnProperty(prefix = LakeProperties.PREFIX, name = ["enabled"], havingValue = "true")
class LakeMaintenanceAutoConfiguration {
    @Bean
    fun lakeMaintenanceRunRepository(dataSource: DataSource): MaintenanceRunRepository =
        MaintenanceRunRepository(NamedParameterJdbcTemplate(LakeAppendRuntime.statements(dataSource)))

    /** #959 D6 (the cut's L13): the window-and-pause gate, the same pure function the planner decides with. */
    @Bean
    @ConditionalOnMissingBean
    fun lakeMaintenanceGate(dataSource: DataSource): MaintenanceGate =
        ProfileMaintenanceGate(NamedParameterJdbcTemplate(LakeAppendRuntime.statements(dataSource)))

    @Bean
    fun lakeMaintenancePlanRepository(dataSource: DataSource): PlanRepository =
        PlanRepository(NamedParameterJdbcTemplate(LakeAppendRuntime.statements(dataSource)))

    /** #959 D8: the planner's figures with their times, one read per table (P8's table page). */
    @Bean
    fun lakeMaintenancePlanFigures(dataSource: DataSource): PlanFiguresReader =
        PlanFiguresReader(NamedParameterJdbcTemplate(LakeAppendRuntime.statements(dataSource)))

    /** #959 D2: the planner's own readers over the delete-refusing metadata FileIO; closing the context stops them. */
    @Bean
    fun lakeMaintenancePlanReaders(catalogs: LakeCatalogs): PlanReaders = PlanReaders(catalogs::metadataFileIo)

    @Suppress("LongParameterList")
    @Bean
    fun lakeMaintenancePlanner(
        dataSource: DataSource,
        properties: LakeProperties,
        lakeMaintenancePlanRepository: PlanRepository,
        lakeMaintenancePlanReaders: PlanReaders,
        lakeMaintenanceWorker: MaintenanceWorker,
        parts: ObjectProvider<MaintenancePart>,
        queue: ObjectProvider<SystemJobQueue>,
    ): MaintenancePlanner {
        // The four plan-* keys are deployment configuration, never a profile value (D1; LakeTuningSourceGuard admits them).
        val bounds =
            PlanBounds(
                interval = properties.maintenance.planInterval,
                pageSize = properties.maintenance.planPageSize,
                tableTimeout = properties.maintenance.planTableTimeout,
                maxAbandonedReads = properties.maintenance.planMaxAbandonedReads,
            )
        return MaintenancePlanner(
            bounds = bounds,
            repository = lakeMaintenancePlanRepository,
            measurer = TableMeasurer(NamedParameterJdbcTemplate(LakeAppendRuntime.statements(dataSource)), properties.name),
            readers = lakeMaintenancePlanReaders,
            due = MaintenanceDue(Duration.ofHours(properties.retention.orphanGraceHours.toLong())),
            // Looked up per plan: the queue is the scheduler's, built after the job beans its tasks wrap.
            queue = { queue.getObject() },
            worker = lakeMaintenanceWorker,
            registered = {
                parts
                    .orderedStream()
                    .map { it.name }
                    .toList()
                    .toSet()
            },
            clock = Clock.systemUTC(),
        )
    }

    @Bean
    @ConditionalOnMissingBean
    fun lakeMaintenanceScratch(): MaintenanceScratch = MaintenanceScratch.NONE

    /**
     * #963: the part `manifest_rewrite`, second in a run's order. It reads the manifest list with no lease through the
     * delete-refusing metadata FileIO, and its threshold from the table's profile.
     */
    @Bean
    fun lakeMaintenanceManifestRewrite(
        dataSource: DataSource,
        properties: LakeProperties,
        catalogs: LakeCatalogs,
        profiles: LakeProfiles,
    ): MaintenancePart =
        ManifestRewritePart.over(NamedParameterJdbcTemplate(LakeAppendRuntime.statements(dataSource)), properties.name, catalogs, profiles)

    /**
     * #969: the `compaction` part, registered wherever the application supplies its scratch database port (the web
     * module, beside the staging factory; D5 step 5). Its scratch is declared from database rows and reserved by the
     * worker through [MaintenanceScratch]; the part hands the run's reservation to the port.
     */
    @Bean
    @ConditionalOnBean(CompactionScratchPort::class)
    fun lakeMaintenanceCompaction(
        dataSource: DataSource,
        properties: LakeProperties,
        catalogs: LakeCatalogs,
        lakeMaintenancePlanRepository: PlanRepository,
        port: CompactionScratchPort,
    ): CompactionPart {
        val statements = LakeAppendRuntime.statements(dataSource)
        val leases = LakeLeases(statements, lockTimeout = Duration.ofSeconds(properties.catalog.lockTimeoutSeconds))
        val admission = properties.admission
        return CompactionPart(
            tables = CompactionTables(NamedParameterJdbcTemplate(statements), properties.name),
            measurements = lakeMaintenancePlanRepository::measurement,
            io = catalogs::metadataFileIo,
            scratch = port,
            settings =
                CompactionSettings(
                    scratchFactor = properties.maintenance.compactionScratchFactor,
                    bytesPerSecond = properties.maintenance.compactionBytesPerSecond.toBytes(),
                    jobTimeout = admission.jobTimeout,
                    declarationCeilingBytes =
                        CompactionSettings.ceiling(
                            admission.scratchBytes.toBytes(),
                            admission.maxDeliveryBytes.toBytes(),
                            admission.stagingExpansionFactor,
                        ),
                ),
            catalogNow = { leases.catalogNowPlus(Duration.ZERO) },
            clock = Clock.systemUTC(),
        )
    }

    /**
     * The heartbeat of a lease a maintenance part holds across several steps (D5). Daemon; shut down with the context.
     * Held in its own type, never published as a `ScheduledExecutorService` bean: the web surface injects the one
     * executor of that type by type, and a second one would make its context refuse to load.
     */
    @Bean
    fun lakeMaintenanceHeartbeats(): MaintenanceHeartbeats =
        MaintenanceHeartbeats(
            Executors.newSingleThreadScheduledExecutor { task ->
                Thread(task, HEARTBEAT_THREAD).apply { isDaemon = true }
            },
        )

    @Suppress("LongParameterList")
    @Bean
    fun lakeMaintenanceWorker(
        dataSource: DataSource,
        properties: LakeProperties,
        catalogs: LakeCatalogs,
        runtime: LakeAppendRuntime,
        runs: MaintenanceRunRepository,
        gate: MaintenanceGate,
        scratch: MaintenanceScratch,
        parts: ObjectProvider<MaintenancePart>,
        lakeMaintenanceHeartbeats: MaintenanceHeartbeats,
        audit: AuditEventSink,
        users: ObjectProvider<UserService>,
    ): MaintenanceWorker {
        val leases =
            LakeLeases(LakeAppendRuntime.statements(dataSource), lockTimeout = Duration.ofSeconds(properties.catalog.lockTimeoutSeconds))
        // The provisioned system row, read on the first run (the seeder writes it at boot, after this bean exists).
        val principal by lazy { SystemActorPrincipals.forInstance(users.getObject().systemActor()) }
        return MaintenanceWorker(
            principal = { principal },
            store = runs,
            slots = SemaphoreMaintenanceSlots(properties.admission.maxConcurrentMaintenance),
            scratch = scratch,
            parts = parts.orderedStream().toList(),
            gate = gate,
            settlement = DeliveryMaintenanceSettlement(runtime.settler, leases),
            context = MaintenanceRunContext(leases, catalogs, lakeMaintenanceHeartbeats.executor),
            audit = audit,
            jobTimeout = properties.admission.jobTimeout,
            clock = Clock.systemUTC(),
        )
    }

    private companion object {
        const val HEARTBEAT_THREAD = "lake-maintenance-heartbeat"
    }
}

/** The maintenance heartbeat executor as a bean of its own type; closing the context stops it. */
class MaintenanceHeartbeats(
    val executor: ScheduledExecutorService,
) : AutoCloseable {
    override fun close() {
        executor.shutdownNow()
    }
}
