package co.datapipelines.lake.maintenance

import co.datapipelines.auth.AuditEventSink
import co.datapipelines.auth.SystemActorPrincipals
import co.datapipelines.auth.UserService
import co.datapipelines.lake.LakeCatalogs
import co.datapipelines.lake.LakeLeases
import co.datapipelines.lake.LakeProperties
import co.datapipelines.lake.append.LakeAppendAutoConfiguration
import co.datapipelines.lake.append.LakeAppendRuntime
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
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
 * The gate and the scratch are ports with defaults: [MaintenanceGate.ADMIT_ALL] until 959's gate, [MaintenanceScratch.NONE]
 * until 969's reservation over staging's budget. A bean of either type replaces the default.
 */
@AutoConfiguration(after = [LakeAppendAutoConfiguration::class])
@ConditionalOnProperty(prefix = LakeProperties.PREFIX, name = ["enabled"], havingValue = "true")
class LakeMaintenanceAutoConfiguration {
    @Bean
    fun lakeMaintenanceRunRepository(dataSource: DataSource): MaintenanceRunRepository =
        MaintenanceRunRepository(NamedParameterJdbcTemplate(LakeAppendRuntime.statements(dataSource)))

    @Bean
    @ConditionalOnMissingBean
    fun lakeMaintenanceGate(): MaintenanceGate = MaintenanceGate.ADMIT_ALL

    @Bean
    @ConditionalOnMissingBean
    fun lakeMaintenanceScratch(): MaintenanceScratch = MaintenanceScratch.NONE

    /**
     * The heartbeat of a lease a maintenance part holds across several steps (D5). Daemon; shut down with the context.
     * Held in its own type, never published as a `ScheduledExecutorService` bean: the web surface injects the one
     * executor of that type by type, and a second one would make its context refuse to load.
     */
    @Bean
    fun lakeMaintenanceHeartbeats(): MaintenanceHeartbeats =
        MaintenanceHeartbeats(Executors.newSingleThreadScheduledExecutor { task -> Thread(task, HEARTBEAT_THREAD).apply { isDaemon = true } })

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
        val leases = LakeLeases(LakeAppendRuntime.statements(dataSource), lockTimeout = Duration.ofSeconds(properties.catalog.lockTimeoutSeconds))
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
