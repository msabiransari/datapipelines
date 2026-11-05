package co.datapipelines.web.config

import co.datapipelines.auth.AuditLogRetention
import co.datapipelines.auth.AuditProperties
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.datasources.ManagedLakeCatalog
import co.datapipelines.executor.ExecutorConfig
import co.datapipelines.parameters.ParametersConfig
import co.datapipelines.visualization.DashboardRuntimeConfig
import co.datapipelines.web.dashboards.runtime.DashboardRefreshSweepScheduler
import co.datapipelines.web.dashboards.runtime.DashboardRuntimeConfiguration
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition
import org.springframework.beans.factory.support.StaticListableBeanFactory
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import org.springframework.context.annotation.ScannedGenericBeanDefinition
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.scheduling.annotation.Scheduled

/**
 * #818c (dp-lake record §10 RULING 4 amended 2026-11-05, item 4) — [ReaperDelay.SECONDS], the constant `LakeRules`
 * adds twice to the reads rule, equals its derivation from the production jobs, so neither a job's bound changing
 * nor a typed duplicate of the number goes unnoticed.
 *
 * The derivation is recomputed, never restated: every sibling `@Scheduled` job is built by its production `@Bean`
 * methods over a [RecordingTimeoutDataSource] and its scheduled method is run once, and its hold is the sum of the
 * `queryTimeout` bounds its statements carried. The reaper delay is the reaper's cadence plus the sum of every
 * sibling's hold ([ReaperDelay]'s KDoc says why a sum and not the longest). One run per job is its worst case:
 * each step issues a fixed statement list, except the audit retention's batch loop, whose extra batches only start
 * inside its tick budget (the third case pins that the budget does not exceed one statement bound), and the
 * freshness tick's catalog read, one per managed generation (one managed lake per environment).
 *
 * Non-vacuity: the classpath scan finds every `@Scheduled` method of the application's own main classes, and it
 * must equal the reaper plus the siblings counted here — the same five `ScheduledJobsSchedulerE2eTest` pins on the
 * running application. A sixth job is red here until it is counted.
 */
class ReaperDelayDerivationTest {
    @Test
    fun `the reaper delay is the reaper's cadence plus one run of every sibling job's statement bounds`() {
        val holds = SIBLINGS.mapValues { (_, run) -> holdOf(run) }
        val cadence = DatasourcePoolReaperScheduler.REAP_INTERVAL_MILLIS / MILLIS_PER_SECOND

        withClue("cadence $cadence s, holds per job (s): $holds") {
            cadence + holds.values.sum() shouldBe ReaperDelay.SECONDS
        }
    }

    @Test
    fun `every scheduled job is counted - the reaper and the siblings the derivation runs, no other`() {
        val scanned = scheduledMethodsOnTheClasspath()

        withClue("@Scheduled methods found on the main classpath: $scanned") {
            scanned.size shouldBe SIBLINGS.size + 1
            scanned shouldBe SIBLINGS.keys + REAPER
        }
    }

    @Test
    fun `the audit retention's tick budget does not exceed one retention statement bound`() {
        // A batch starts only while less than the budget has elapsed, so a run is at most max(cutoff read, budget)
        // plus one batch: two statement bounds while the budget does not exceed one.
        AuditLogRetention.DEFAULT_TICK_BUDGET.seconds shouldBeLessThanOrEqual
            ExecutionEventRetentionScheduler.RETENTION_STATEMENT_TIMEOUT_SECONDS.toLong()
    }

    /** One run of [job] over a fresh recorder: the sum of its statements' bounds, every one of them bounded. */
    private fun holdOf(job: (NamedParameterJdbcTemplate) -> Unit): Long {
        val recording = RecordingTimeoutDataSource()
        job(NamedParameterJdbcTemplate(recording))
        val timeouts = recording.timeouts()
        withClue("statements sent: ${recording.firstLines()} with bounds $timeouts") {
            timeouts.shouldNotBeEmpty()
            timeouts shouldNotContain RecordingTimeoutDataSource.UNBOUNDED
        }
        return timeouts.sumOf { it.toLong() }
    }

    /** `Class.method` of every `@Scheduled` method of a main (not test) class under `co.datapipelines`. */
    private fun scheduledMethodsOnTheClasspath(): Set<String> {
        val scanner =
            object : ClassPathScanningCandidateComponentProvider(false) {
                override fun isCandidateComponent(beanDefinition: AnnotatedBeanDefinition): Boolean = true
            }
        scanner.addIncludeFilter { reader, _ -> reader.annotationMetadata.hasAnnotatedMethods(Scheduled::class.java.name) }
        return scanner
            .findCandidateComponents(BASE_PACKAGE)
            .map { it as ScannedGenericBeanDefinition }
            .filterNot { TEST_OUTPUT in requireNotNull(it.resource) { "a scanned class has a resource" }.url.path }
            .flatMap { definition ->
                definition.metadata
                    .getAnnotatedMethods(Scheduled::class.java.name)
                    .map { "${it.declaringClassName}.${it.methodName}" }
            }.toSet()
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L
        const val BASE_PACKAGE = "co.datapipelines"

        /** A compiled test class lives under a `test` output directory; a main class never does. */
        const val TEST_OUTPUT = "/test/"

        /** The reaper: the subject of the delay, so its cadence starts the sum and its pass is not a sibling's hold. */
        val REAPER = scheduled(DatasourcePoolReaperScheduler::class.java, DatasourcePoolReaperScheduler::reap.name)

        /** Every other job on the `dp-scheduled` thread, each built by its production `@Bean` methods and run once. */
        val SIBLINGS: Map<String, (NamedParameterJdbcTemplate) -> Unit> =
            mapOf(
                scheduled(StaleExecutionSweepScheduler::class.java, StaleExecutionSweepScheduler::sweep.name) to ::staleSweep,
                scheduled(DashboardRefreshSweepScheduler::class.java, DashboardRefreshSweepScheduler::sweep.name) to ::refreshSweep,
                scheduled(ExecutionEventRetentionScheduler::class.java, ExecutionEventRetentionScheduler::retain.name) to ::retention,
                scheduled(ManagedLakeFreshnessScheduler::class.java, ManagedLakeFreshnessScheduler::tick.name) to ::freshnessTick,
            )

        fun scheduled(
            owner: Class<*>,
            method: String,
        ): String = "${owner.name}.$method"

        fun staleSweep(jdbc: NamedParameterJdbcTemplate) {
            val sweeping = SweepSchedulingConfiguration()
            val executions = EngineConfiguration().executionRepository(jdbc, ExecutorProperties())
            sweeping
                .staleExecutionSweepScheduler(
                    sweeping.staleExecutionSweeper(executions, ExecutionsProperties(), ExecutorProperties(), SimpleMeterRegistry()),
                ).sweep()
        }

        fun refreshSweep(jdbc: NamedParameterJdbcTemplate) {
            val runtime = DashboardRuntimeConfiguration()
            runtime
                .dashboardRefreshSweepScheduler(
                    runtime.dashboardRefreshSweeper(jdbc, DashboardRuntimeConfig(), ExecutorConfig(), SimpleMeterRegistry()),
                ).sweep()
        }

        fun retention(jdbc: NamedParameterJdbcTemplate) {
            val registry = SimpleMeterRegistry()
            val retaining = RetentionSchedulingConfiguration()
            retaining
                .executionEventRetentionScheduler(
                    retention =
                        retaining.executionEventRetention(
                            EngineConfiguration().executionEventRepository(jdbc),
                            ExecutionsProperties(),
                            registry,
                        ),
                    keyPurge = retaining.keyRetentionPurge(jdbc),
                    auditRetention = retaining.auditLogRetention(jdbc, AuditProperties(), registry),
                    dashboardRefreshRetention = retaining.dashboardRefreshRetention(jdbc, ExecutionsProperties(), registry),
                    evaluationSweeper =
                        retaining.parameterEvaluationSweeper(
                            jdbc,
                            StaticListableBeanFactory().getBeanProvider(ParametersConfig::class.java),
                            registry,
                        ),
                    evaluationRetention = retaining.parameterEvaluationRetention(jdbc, ExecutionsProperties(), registry),
                    meterRegistry = registry,
                ).retain()
        }

        /**
         * The tick over one managed generation: the registry reads the catalog it is handed once, as
         * `DefaultDatasourceRegistry` does per live managed generation. The registrar boots first, on a recorder of its
         * own, so only the tick's statements are counted.
         */
        fun freshnessTick(jdbc: NamedParameterJdbcTemplate) {
            val registrar = scheduledJobsRegistrar(NamedParameterJdbcTemplate(RecordingTimeoutDataSource()))
            registrar.register()
            val datasources =
                mockk<DatasourceRegistry> {
                    every { refreshManagedGenerations(any()) } answers {
                        firstArg<ManagedLakeCatalog>().managedTables(SCHEDULED_JOBS_TEST_LAKE)
                        0
                    }
                }
            PoolReaperSchedulingConfiguration()
                .managedLakeFreshnessScheduler(datasources, registrar, jdbc, beanProviderOf(SCHEDULED_JOBS_TEST_LAKE_PROPERTIES))
                .tick()
        }
    }
}
