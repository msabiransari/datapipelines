package co.datapipelines.web.config

import co.datapipelines.auth.AuditProperties
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.datasources.ManagedLakeCatalog
import co.datapipelines.executor.ExecutorConfig
import co.datapipelines.parameters.ParametersConfig
import co.datapipelines.visualization.DashboardRuntimeConfig
import co.datapipelines.web.dashboards.runtime.DashboardRuntimeConfiguration
import co.datapipelines.web.release.JdbcReleaseMutationGuard
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.longs.shouldBeInRange
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.support.StaticListableBeanFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID

/**
 * #326 — the four `@Scheduled` jobs' metadata statements carry their bounds AS THE APPLICATION BUILDS
 * THEM: every job is constructed by its production `@Bean` method over a DataSource that records the
 * `queryTimeout` each statement is given (Spring applies a template's `queryTimeout` with
 * `Statement.setQueryTimeout` outside a transaction). The numbers are the issue's, literals — never read
 * back from the constants under test — so a constant reverted to 0, or a statement sent on the unbounded
 * template, is red here. That the driver then cancels at the bound is the E2E's
 * (`ScheduledJobsSchedulerE2eTest`, the sweep on a real Postgres) and #311's
 * (`LifecycleWriteBoundIntegrationTest`).
 *
 * The negative half matters as much: the bound rides a DEDICATED template, so the same repositories'
 * other statements — the heartbeat, the event reads — carry none, and the lifecycle writes keep their own.
 *
 * #636 — the four housekeeping statements on the same `dp-scheduled` thread: the dashboard refresh
 * sweep's UPDATE at the sweep bound, the dashboard refreshes' retention DELETE and the parameter
 * evaluations' sweep UPDATE and retention DELETE at the retention bound; the runtime's SHARED
 * `DashboardRefreshRepository` bean stays unbounded.
 *
 * #818b A — the managed lake's freshness tick, the fifth job on that thread: the catalog read carries the freshness
 * bound through the production scheduler bean, while the registrar's boot-time grant stays on the shared template.
 * #841 R4 — the guarded regrant's key, workspace-row, discovery and INSERT waits share one freshness budget; its
 * first statement applies the socket bound to their transaction connection. Cancellation under held locks on the
 * real scheduler is `ManagedLakeReadsE2eTest`'s.
 *
 * #833 — the build's catalog read (the generation build inside `poolFor`) carries its own bound through the production
 * repository bean, on a dedicated template; the shared template stays unbounded.
 *
 * #837 — the same statements carry a SOCKET bound too: the connection a bounded statement runs on has its network
 * timeout set to the statement's bound plus five seconds — 15 000, 30 000, 15 000 and 10 000
 * milliseconds for the sweep, the retention, the dashboard refresh sweep and the freshness tick, and 10 000 for the
 * build read — while shared-template statements outside a transaction carrying a bound have none. The numbers are
 * literals, like the statement bounds. That a paused server then ends the statement at the socket bound is
 * `ScheduledJobsMetadataBoundsE2eTest`'s.
 *
 * #858 — the lake settlement tick, built in `modules/lake` over the runtime's own template, carries both bounds too:
 * its statements' 5 s bound and a socket bound of 10 000 milliseconds, set once per statement. That a paused server
 * then ends the tick's statement at 15 s on the real scheduler is `LakeSettlementMetadataBoundsE2eTest`'s.
 */
class ScheduledJobsStatementBoundsTest {
    private val recording = RecordingTimeoutDataSource(LAKE)
    private val jdbc = NamedParameterJdbcTemplate(recording)

    @Test
    fun `lake settlement mode observation and protection finalization carry their five second bound and its socket bound`() {
        val sweep =
            co.datapipelines.lake.settle.LakeSettlementSweep(
                NamedParameterJdbcTemplate(
                    co.datapipelines.lake.append.LakeAppendRuntime
                        .statements(recording),
                ),
                mockk(),
                mockk(),
                mockk(),
                mockk(),
                mockk(),
            )
        sweep.finalizeProtection()
        sweep.runOnce() // Recording result says non-normal: the mode guard itself still executes SQL.
        recording.timeoutOf("UPDATE lake_table_revisions r SET protected_until") shouldBe SETTLEMENT_BOUND
        recording.timeoutOf("SELECT mode =") shouldBe SETTLEMENT_BOUND
        recording.everyTimeout() shouldBe setOf(SETTLEMENT_BOUND)
        recording.socketTimeoutOf("UPDATE lake_table_revisions r SET protected_until") shouldBe SETTLEMENT_SOCKET
        recording.socketTimeoutOf("SELECT mode =") shouldBe SETTLEMENT_SOCKET
        recording.everySocketTimeout() shouldBe setOf(SETTLEMENT_SOCKET)
        // Set per statement, not once per connection: every statement's own call.
        recording.networkTimeoutCalls() shouldBe List(recording.statementCount()) { SETTLEMENT_SOCKET }
        recording.statementCount() shouldBe 2
    }

    @Test
    fun `the stale sweep's UPDATE carries the sweep bound - the repository's other statements do not`() {
        val executions = EngineConfiguration().executionRepository(jdbc, ExecutorProperties(lifecycleWriteTimeoutSeconds = LIFECYCLE))

        executions.sweepStaleRunning(Instant.now(), Instant.now())
        executions.heartbeat(UUID.randomUUID())

        recording.timeoutOf("UPDATE pipeline_executions\n   SET status = 'ABORTED'") shouldBe SWEEP_BOUND
        recording.timeoutOf("UPDATE pipeline_executions SET heartbeat_at") shouldBe UNBOUNDED
        recording.socketTimeoutOf("UPDATE pipeline_executions\n   SET status = 'ABORTED'") shouldBe SWEEP_SOCKET
        recording.socketTimeoutOf("UPDATE pipeline_executions SET heartbeat_at") shouldBe NO_SOCKET
    }

    @Test
    fun `the event retention's DELETE carries the retention bound - the event reads do not`() {
        val events = EngineConfiguration().executionEventRepository(jdbc)

        events.deleteOlderThan(Instant.now())
        events.lastEventId(UUID.randomUUID())

        recording.timeoutOf("DELETE FROM execution_events") shouldBe RETENTION_BOUND
        recording.timeoutOf("SELECT COALESCE(MAX(event_id)") shouldBe UNBOUNDED
        recording.socketTimeoutOf("DELETE FROM execution_events") shouldBe RETENTION_SOCKET
        recording.socketTimeoutOf("SELECT COALESCE(MAX(event_id)") shouldBe NO_SOCKET
    }

    @Test
    fun `every statement of the audit retention carries the retention bound - the cutoff read and the batch`() {
        RetentionSchedulingConfiguration().auditLogRetention(jdbc, AuditProperties(), SimpleMeterRegistry()).purgeOnce()

        recording.timeoutOf("SELECT NOW() - make_interval") shouldBe RETENTION_BOUND
        recording.timeoutOf("DELETE FROM audit_log") shouldBe RETENTION_BOUND
        recording.everyTimeout() shouldBe setOf(RETENTION_BOUND)
        recording.everySocketTimeout() shouldBe setOf(RETENTION_SOCKET)
    }

    @Test
    fun `every statement of the keys purge carries the retention bound - the FK read and both DELETEs`() {
        RetentionSchedulingConfiguration().keyRetentionPurge(jdbc).purgeOnce()

        recording.timeoutOf("SELECT kcu.table_name") shouldBe RETENTION_BOUND
        recording.timeoutOf("DELETE FROM api_keys") shouldBe RETENTION_BOUND
        recording.timeoutOf("DELETE FROM users") shouldBe RETENTION_BOUND
        recording.everyTimeout() shouldBe setOf(RETENTION_BOUND)
        recording.everySocketTimeout() shouldBe setOf(RETENTION_SOCKET)
    }

    @Test
    fun `the dashboard refresh sweep's UPDATE carries the sweep bound - on the sweeper's own template`() {
        DashboardRuntimeConfiguration()
            .dashboardRefreshSweeper(jdbc, DashboardRuntimeConfig(), ExecutorConfig(), SimpleMeterRegistry())
            .sweepOnce()

        recording.timeoutOf("UPDATE dashboard_refreshes") shouldBe REFRESH_SWEEP_BOUND
        recording.everyTimeout() shouldBe setOf(REFRESH_SWEEP_BOUND)
        recording.everySocketTimeout() shouldBe setOf(REFRESH_SWEEP_SOCKET)
    }

    @Test
    fun `the runtime's shared dashboard refresh repository stays unbounded - its writes are not housekeeping`() {
        val shared = DashboardRuntimeConfiguration().dashboardRefreshRepository(jdbc)

        shared.sweepStale(STALE_AFTER_SECONDS)
        shared.deleteFinishedOlderThan(RETENTION_DAYS)

        recording.timeoutOf("UPDATE dashboard_refreshes") shouldBe UNBOUNDED
        recording.timeoutOf("DELETE FROM dashboard_refreshes") shouldBe UNBOUNDED
        recording.everySocketTimeout() shouldBe setOf(NO_SOCKET)
    }

    @Test
    fun `the dashboard refreshes' retention DELETE carries the retention bound`() {
        RetentionSchedulingConfiguration()
            .dashboardRefreshRetention(jdbc, ExecutionsProperties(), SimpleMeterRegistry())
            .retainOnce()

        recording.timeoutOf("DELETE FROM dashboard_refreshes") shouldBe RETENTION_BOUND
        recording.everyTimeout() shouldBe setOf(RETENTION_BOUND)
        recording.everySocketTimeout() shouldBe setOf(RETENTION_SOCKET)
    }

    @Test
    fun `the parameter evaluations' sweep UPDATE carries the retention bound`() {
        RetentionSchedulingConfiguration()
            .parameterEvaluationSweeper(jdbc, NO_PARAMETERS_CONFIG, SimpleMeterRegistry())
            .sweepOnce()

        recording.timeoutOf("UPDATE parameter_evaluations") shouldBe RETENTION_BOUND
        recording.everyTimeout() shouldBe setOf(RETENTION_BOUND)
        recording.everySocketTimeout() shouldBe setOf(RETENTION_SOCKET)
    }

    @Test
    fun `the parameter evaluations' retention DELETE carries the retention bound`() {
        RetentionSchedulingConfiguration()
            .parameterEvaluationRetention(jdbc, ExecutionsProperties(), SimpleMeterRegistry())
            .retainOnce()

        recording.timeoutOf("DELETE FROM parameter_evaluations") shouldBe RETENTION_BOUND
        recording.everyTimeout() shouldBe setOf(RETENTION_BOUND)
        recording.everySocketTimeout() shouldBe setOf(RETENTION_SOCKET)
    }

    @Test
    fun `the freshness tick's catalog read and regrant INSERT carry the freshness bound - the boot grant does not`() {
        val registrar = registrar()
        registrar.register()
        recording.timeoutOf("INSERT INTO datasource_workspaces") shouldBe UNBOUNDED
        recording.socketTimeoutOf("INSERT INTO datasource_workspaces") shouldBe NO_SOCKET
        recording.clear()
        // A registry that reads the catalog it is handed, as DefaultDatasourceRegistry does (its own test pins that).
        val datasources =
            mockk<DatasourceRegistry> {
                every { refreshManagedGenerations(any()) } answers {
                    firstArg<ManagedLakeCatalog>().managedTables(LAKE).size shouldBe 1
                    0
                }
            }

        PoolReaperSchedulingConfiguration()
            // UNGUARDED: this pins the tick's OWN statements' bounds; the writer guard's lock statements are G0's
            // (JdbcReleaseMutationGuard tests) and would only add rows to the count.
            .managedLakeFreshnessScheduler(
                datasources,
                registrar,
                jdbc,
                providerOf(LAKE_PROPERTIES),
                co.datapipelines.datasources.DatasourceWriteGuarding.UNGUARDED,
            ).tick()

        recording.timeoutOf("SELECT t.namespace, t.name") shouldBe FRESHNESS_BOUND
        recording.timeoutOf("INSERT INTO datasource_workspaces") shouldBe FRESHNESS_BOUND
        recording.everyTimeout() shouldBe setOf(FRESHNESS_BOUND)
        recording.everySocketTimeout() shouldBe setOf(FRESHNESS_SOCKET)
        recording.statementCount() shouldBe 2
    }

    /**
     * #841 R4 — the same production bean with the REAL writer guard (configured at the 60 s maximum
     * `lock-wait-seconds` admits) and a real transaction manager over the recording DataSource: the regrant's whole
     * transaction runs on ONE connection that carries the tick's socket bound from its FIRST statement, the guard's
     * statements included, and every wait it can start is capped at the 5 s budget — the guard's `lock_timeout` grants
     * and the budget's `statement_timeout` settings alike — though the guard alone would grant 60 000 ms.
     */
    @Test
    fun `the guarded regrant carries the freshness socket bound from its first statement and caps every wait at 5 s - #841 R4`() {
        val recording = RecordingTimeoutDataSource(LAKE, recordPlainStatements = true)
        val jdbc = NamedParameterJdbcTemplate(recording)
        val registrar = scheduledJobsRegistrar(jdbc, LAKE_PROPERTIES).also { it.register() }
        recording.clear()
        val datasources = mockk<DatasourceRegistry> { every { refreshManagedGenerations(any()) } returns 0 }
        val writes =
            co.datapipelines.datasources.DatasourceWriteGuarding(
                jdbc,
                TransactionTemplate(DataSourceTransactionManager(recording)),
                JdbcReleaseMutationGuard(JdbcTemplate(recording), MAX_LOCK_WAIT_SECONDS),
            )

        PoolReaperSchedulingConfiguration()
            .managedLakeFreshnessScheduler(datasources, registrar, jdbc, providerOf(LAKE_PROPERTIES), writes)
            .tick()

        val trace = recording.trace()
        withClue("the regrant's statements, in order: $trace") {
            trace.first().first shouldBe "SELECT current_setting('statement_timeout')"
            trace.map { it.third }.toSet() shouldBe setOf(FRESHNESS_SOCKET)
            trace
                .map { it.first }
                .filter {
                    it.startsWith(
                        "SELECT pg_advisory_xact_lock",
                    ) || it.contains("FOR NO KEY UPDATE")
                }.size shouldBe
                2
            trace.count { it.first.startsWith("INSERT INTO datasource_workspaces") } shouldBe 1
            val waits =
                trace
                    .filter {
                        it.first.startsWith("SELECT set_config('lock_timeout'") ||
                            it.first.startsWith("SELECT set_config('statement_timeout'")
                    }.map { it.second.single() }
                    .filter { it.endsWith("ms") }
                    .map { it.removeSuffix("ms").toLong() }
            // Key, workspace row (lock_timeout), discovery and INSERT (statement_timeout): four budgeted waits.
            waits.size shouldBe 4
            waits.forEach { it shouldBeInRange 1L..FRESHNESS_BUDGET_MILLIS }
        }
    }

    /**
     * #833 — the read that BUILDS a managed generation runs on the lease's thread inside `poolFor`, so it carries a bound
     * of its own: the production bean wraps the shared template in a dedicated bounded one. The negative half: the
     * shared template the bean was built over is unchanged, so a sibling statement on it (the registrar's boot grant)
     * still carries none.
     */
    @Test
    fun `the build's managed catalog read carries its own five second bound - the shared template's other statements none (#833)`() {
        val lake = LakeConfiguration()
        lake
            .managedLakeCatalog(lake.managedLakeTableRepository(jdbc), providerOf(LAKE_PROPERTIES))
            .managedTables(LAKE)
        registrar().register()

        recording.timeoutOf("SELECT t.namespace, t.name") shouldBe BUILD_READ_BOUND
        recording.socketTimeoutOf("SELECT t.namespace, t.name") shouldBe BUILD_READ_SOCKET
        recording.timeoutOf("INSERT INTO datasource_workspaces") shouldBe UNBOUNDED
        recording.socketTimeoutOf("INSERT INTO datasource_workspaces") shouldBe NO_SOCKET
        recording.everyTimeout() shouldBe setOf(BUILD_READ_BOUND, UNBOUNDED)
        recording.everySocketTimeout() shouldBe setOf(BUILD_READ_SOCKET, NO_SOCKET)
    }

    /**
     * Non-vacuity: one recording across the four #636 statements and the shared bean's unbounded one
     * holds every value the cases above distinguish — a recorder that saw no `setQueryTimeout`, or saw
     * one value for all, could not pass them, and cannot pass this.
     */
    @Test
    fun `one recording of the four housekeeping statements and the shared bean holds 10, 25 and 0`() {
        val registry = SimpleMeterRegistry()
        DashboardRuntimeConfiguration().dashboardRefreshSweeper(jdbc, DashboardRuntimeConfig(), ExecutorConfig(), registry).sweepOnce()
        RetentionSchedulingConfiguration().dashboardRefreshRetention(jdbc, ExecutionsProperties(), registry).retainOnce()
        RetentionSchedulingConfiguration().parameterEvaluationSweeper(jdbc, NO_PARAMETERS_CONFIG, registry).sweepOnce()
        RetentionSchedulingConfiguration().parameterEvaluationRetention(jdbc, ExecutionsProperties(), registry).retainOnce()
        DashboardRuntimeConfiguration().dashboardRefreshRepository(jdbc).deleteFinishedOlderThan(RETENTION_DAYS)

        recording.everyTimeout() shouldContainAll setOf(REFRESH_SWEEP_BOUND, RETENTION_BOUND, UNBOUNDED)
        recording.everyTimeout().size shouldBe 3
    }

    /**
     * #837 non-vacuity: the same recording holds three DISTINCT socket values, each tied to its statement bound
     * (+ 5 s) and the shared bean's none — a recorder that saw no `setNetworkTimeout`, or one value for every
     * connection, could not pass the cases above, and cannot pass this. The calls themselves are counted too:
     * one per bounded statement, none for the shared bean's.
     */
    @Test
    fun `one recording of the housekeeping statements and the shared bean holds socket bounds 15000, 30000 and none`() {
        val registry = SimpleMeterRegistry()
        DashboardRuntimeConfiguration().dashboardRefreshSweeper(jdbc, DashboardRuntimeConfig(), ExecutorConfig(), registry).sweepOnce()
        RetentionSchedulingConfiguration().dashboardRefreshRetention(jdbc, ExecutionsProperties(), registry).retainOnce()
        DashboardRuntimeConfiguration().dashboardRefreshRepository(jdbc).deleteFinishedOlderThan(RETENTION_DAYS)

        recording.everySocketTimeout() shouldBe setOf(REFRESH_SWEEP_SOCKET, RETENTION_SOCKET, NO_SOCKET)
        recording.networkTimeoutCalls() shouldBe listOf(REFRESH_SWEEP_SOCKET, RETENTION_SOCKET)
    }

    /** The production registrar over the unbounded template (the shared fixture). */
    private fun registrar(): ManagedLakeDatasourceRegistrar = scheduledJobsRegistrar(jdbc, LAKE_PROPERTIES)

    private inline fun <reified T : Any> providerOf(bean: T): ObjectProvider<T> = beanProviderOf(bean)

    private companion object {
        /** #326: the sweep's bound — two-thirds of its 15 s cadence. */
        const val SWEEP_BOUND = 10

        /** #326: the hourly retention's bound — under the 30 s shutdown phase. */
        const val RETENTION_BOUND = 25

        /** #636: the dashboard refresh sweep's bound — a sixth of its 60 s cadence, the stale sweep's value. */
        const val REFRESH_SWEEP_BOUND = 10

        const val STALE_AFTER_SECONDS = 915L
        const val RETENTION_DAYS = 7L

        /** No `ParametersConfig` bean: the sweeper takes the key's default deadline, as the scheduling slice does. */
        val NO_PARAMETERS_CONFIG: ObjectProvider<ParametersConfig> =
            StaticListableBeanFactory().getBeanProvider(ParametersConfig::class.java)

        /** A lifecycle bound unlike both, so a statement sent on the lifecycle template cannot pass for the sweep's. */
        const val LIFECYCLE = 3

        /** No `setQueryTimeout` call. */
        const val UNBOUNDED = 0

        /** #837: no `setNetworkTimeout` call — the driver's own unbounded socket read. */
        const val NO_SOCKET = 0

        /**
         * #837: a bounded statement's socket bound is its statement bound plus five seconds, in milliseconds — literals,
         * never computed from the constants under test: the sweep's 10 s, the retention's 25 s, the dashboard refresh
         * sweep's 10 s and the freshness tick's 5 s.
         */
        const val SWEEP_SOCKET = 15_000
        const val RETENTION_SOCKET = 30_000
        const val REFRESH_SWEEP_SOCKET = 15_000
        const val FRESHNESS_SOCKET = 10_000

        /** #818b A, #841 R4: each freshness step's held-lock bound; the reaper runs on its own thread. */
        const val FRESHNESS_BOUND = 5

        /** #841 R4: the regrant step's one budget (the freshness bound), and the largest lock wait the binder admits. */
        const val FRESHNESS_BUDGET_MILLIS = 5_000L
        const val MAX_LOCK_WAIT_SECONDS = 60

        /** #833: the build's catalog read, its own bound (the tick's twin value, on a template of its own). */
        const val BUILD_READ_BOUND = 5
        const val BUILD_READ_SOCKET = 10_000

        /** #858: the lake settlement tick's statement bound, and that plus five seconds in milliseconds — literals. */
        const val SETTLEMENT_BOUND = 5
        const val SETTLEMENT_SOCKET = 10_000

        const val LAKE = SCHEDULED_JOBS_TEST_LAKE

        val LAKE_PROPERTIES = SCHEDULED_JOBS_TEST_LAKE_PROPERTIES
    }
}
