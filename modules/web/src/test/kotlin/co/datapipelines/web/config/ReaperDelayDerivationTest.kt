package co.datapipelines.web.config

import co.datapipelines.datasources.CredentialKind
import co.datapipelines.datasources.Datasource
import co.datapipelines.datasources.DatasourceAuditSink
import co.datapipelines.datasources.DatasourceProperties
import co.datapipelines.datasources.DatasourceReferences
import co.datapipelines.datasources.DatasourceRepository
import co.datapipelines.datasources.DatasourceRow
import co.datapipelines.datasources.DefaultDatasourceRegistry
import co.datapipelines.datasources.LakeTableCatalog
import co.datapipelines.datasources.LakeViewOutcomeRecorder
import co.datapipelines.datasources.ManagedLakeCatalog
import co.datapipelines.datasources.PoolInvalidationPublisher
import co.datapipelines.datasources.crypto.CredentialEncryptor
import co.datapipelines.datasources.pooling.ConnectionPool
import co.datapipelines.datasources.pooling.ConnectionPoolManager
import co.datapipelines.datasources.pooling.ManagedLakeGeneration
import co.datapipelines.datasources.pooling.PoolCloser
import co.datapipelines.datasources.pooling.PoolLifecycleMetrics
import co.datapipelines.typesystem.Dialect
import com.zaxxer.hikari.HikariConfig
import io.kotest.assertions.withClue
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import org.springframework.context.annotation.ScannedGenericBeanDefinition
import org.springframework.scheduling.annotation.Scheduled
import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * #818d (dp-lake record §10 RULING 4, second amendment, items 5 and 6) — [ReaperDelay.SECONDS], the constant
 * `LakeRules` adds twice to the reads rule, is the reaper's cadence plus twice its own pass bound, and every premise
 * of that sum is pinned here, each by a case that goes red on its own plant:
 *
 * - (a) the sum, recomputed from the reaper's `@Scheduled` cadence and [ReaperDelay.PASS_BOUND_SECONDS];
 * - (b) the reaper is ALONE on `dp-reaper`: its `@Scheduled` names [PoolReaperTaskScheduler.BEAN_NAME], no other job
 *   names any scheduler, and the bean is that name with one daemon `dp-reaper` thread (the premise that removed the
 *   siblings' holds from the sum);
 * - (c) the pass issues no statement: the production reaper over a real [DefaultDatasourceRegistry] reaps drained
 *   and hard-closed pools without one call on any port the registry reaches the metadata database through;
 * - (d) [PoolCloser.DEADLINE_SECONDS] is a fresh `HikariConfig`'s `connectionTimeout` plus Hikari's three fixed
 *   shutdown waits plus the owner-release margin.
 *
 * The pass bound itself is measured (the fifth case): the production pass over 1 000 live pools and 100 retiring
 * entries handed to the production closer, its slowest of several rounds printed for the handback and asserted an
 * order of magnitude inside the bound. The classpath case keeps the job count honest: five `@Scheduled` methods, the
 * same five `ScheduledJobsSchedulerE2eTest` pins on the running application.
 */
class ReaperDelayDerivationTest {
    @Test
    fun `(a) the reaper delay is its cadence plus twice its own pass bound`() {
        val cadence = DatasourcePoolReaperScheduler.REAP_INTERVAL_MILLIS / MILLIS_PER_SECOND
        val reap = DatasourcePoolReaperScheduler::class.java.getMethod(DatasourcePoolReaperScheduler::reap.name)
        val annotated = requireNotNull(reap.getAnnotation(Scheduled::class.java)) { "the reaper's pass is @Scheduled" }

        withClue("cadence $cadence s, pass bound ${ReaperDelay.PASS_BOUND_SECONDS} s") {
            annotated.fixedDelay shouldBe DatasourcePoolReaperScheduler.REAP_INTERVAL_MILLIS
            ReaperDelay.CADENCE_SECONDS shouldBe cadence
            cadence + 2 * ReaperDelay.PASS_BOUND_SECONDS shouldBe ReaperDelay.SECONDS
            ReaperDelay.SECONDS shouldBe EXPECTED_DELAY_SECONDS
        }
    }

    @Test
    fun `(b) only the reaper names the dp-reaper scheduler, and that bean is one daemon dp-reaper thread`() {
        val schedulers = scheduledMethodsOnTheClasspath()
        val beanMethod = PoolReaperSchedulingConfiguration::class.java.getMethod("poolReaperTaskScheduler")
        val scheduler = PoolReaperSchedulingConfiguration().poolReaperTaskScheduler()
        scheduler.initialize()
        try {
            val thread = scheduler.scheduledExecutor.submit<Thread> { Thread.currentThread() }.get(AWAIT_SECONDS, TimeUnit.SECONDS)

            withClue("@Scheduled methods and the scheduler each names: $schedulers") {
                schedulers.size shouldBe JOB_COUNT
                schedulers[REAPER] shouldBe PoolReaperTaskScheduler.BEAN_NAME
                schedulers.filterKeys { it != REAPER }.values.toSet() shouldBe setOf("")
            }
            beanMethod.getAnnotation(Bean::class.java).name.toList() shouldBe listOf(PoolReaperTaskScheduler.BEAN_NAME)
            (PoolReaperTaskScheduler.BEAN_NAME == DEFAULT_SCHEDULER_BEAN) shouldBe false
            scheduler.scheduledThreadPoolExecutor.corePoolSize shouldBe 1
            thread.name shouldBe PoolReaperTaskScheduler.THREAD_NAME
            thread.isDaemon shouldBe true
            scheduler.phase shouldBe ScheduledJobsTaskScheduler.SHUTDOWN_PHASE
        } finally {
            scheduler.shutdown()
        }
    }

    @Test
    fun `(c) the reaper's pass issues no statement - drained and hard-closed pools alike`() {
        val ports = MetadataPorts()
        val registry = ports.registry()
        // Two pools built the production way (the build reads the row: those calls happen BEFORE the pass).
        val drained = registry.poolFor(h2(DRAINED))
        val held = registry.poolFor(h2(HELD))
        val lease: Connection = held.leaseConnection()
        try {
            registry.retirePool(DRAINED) shouldBe true
            registry.retirePool(HELD) shouldBe true
            ports.forgetCalls()

            val reaper = DatasourcePoolReaperScheduler(registry)
            reaper.reap()
            reaper.reap()

            withClue("the drained pool closed on the pass, the held one hard-closed at its zero ceiling") {
                drained.activeConnections shouldBe 0
                registry.reapRetiredPools().drained shouldBe 0
            }
            ports.confirmNoCalls()
        } finally {
            lease.close()
            registry.shutdown()
        }
    }

    @Test
    fun `(d) the close deadline is Hikari's own shutdown bound plus the owner-release margin`() {
        val connectionTimeoutSeconds = TimeUnit.MILLISECONDS.toSeconds(HikariConfig().connectionTimeout)

        withClue("connectionTimeout $connectionTimeoutSeconds s") {
            connectionTimeoutSeconds shouldBe HIKARI_DEFAULT_CONNECTION_TIMEOUT_SECONDS
            connectionTimeoutSeconds +
                PoolCloser.HIKARI_SHUTDOWN_WAITS * PoolCloser.HIKARI_SHUTDOWN_WAIT_SECONDS +
                PoolCloser.OWNER_RELEASE_MARGIN_SECONDS shouldBe PoolCloser.DEADLINE_SECONDS
            PoolCloser.DEADLINE_SECONDS shouldBe EXPECTED_DEADLINE_SECONDS
        }
    }

    @Test
    fun `the pass bound is measured - 1 000 live pools and 100 retiring entries take far less than it`() {
        val rounds = (1..MEASURE_ROUNDS).map { measuredPassMillis() }
        val slowest = rounds.max()
        println("event=test.reaper_pass_measured live=$LIVE_POOLS retiring=$RETIRING_POOLS rounds_ms=$rounds slowest_ms=$slowest")

        withClue("pass per round (ms): $rounds") {
            slowest shouldBeLessThan TimeUnit.SECONDS.toMillis(ReaperDelay.PASS_BOUND_SECONDS) / MEASURED_MARGIN
        }
    }

    /**
     * One production pass ([ConnectionPoolManager.reapRetiring], what the reaper's registry call runs) over
     * [LIVE_POOLS] live managed generations, every one judged against its age bound and none expired, and
     * [RETIRING_POOLS] retiring entries past a zero ceiling with a connection out, each hard-closed: one WARN, one
     * metric, one hand-off to the production [DeadlinePoolCloser]. The worst shape of the pass at that size.
     */
    private fun measuredPassMillis(): Long {
        val closer = DeadlinePoolCloser(PoolLifecycleMetrics.NONE)
        val manager =
            ConnectionPoolManager(
                poolFactory = { ds -> MeasuredPool(ds.name, ManagedLakeGeneration(ds.name, emptyList())) },
                retireCeiling = Duration.ZERO,
                maxGenerationAge = Duration.ofDays(1),
                closer = closer,
            )
        try {
            (0 until LIVE_POOLS + RETIRING_POOLS).forEach { manager.poolFor(h2("measured_$it")) }
            (LIVE_POOLS until LIVE_POOLS + RETIRING_POOLS).forEach { manager.retire("measured_$it") }
            manager.retiringCount() shouldBe RETIRING_POOLS

            val started = System.nanoTime()
            val outcome = manager.reapRetiring()
            val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

            outcome.hardClosed shouldBe RETIRING_POOLS
            outcome.expired shouldBe 0
            return elapsed
        } finally {
            closer.destroy()
        }
    }

    /** `Class.method` → the `scheduler` attribute of every `@Scheduled` method of a main class under `co.datapipelines`. */
    private fun scheduledMethodsOnTheClasspath(): Map<String, String> {
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
                definition.metadata.getAnnotatedMethods(Scheduled::class.java.name).map { method ->
                    val attributes = requireNotNull(method.getAnnotationAttributes(Scheduled::class.java.name))
                    "${method.declaringClassName}.${method.methodName}" to attributes.getValue("scheduler") as String
                }
            }.toMap()
    }

    /**
     * Every port [DefaultDatasourceRegistry] reaches the metadata database (or anything outside the JVM) through, as
     * recording doubles: a relaxed mock answers every call, so a statement the pass started to issue would be
     * RECORDED and fail [confirmNoCalls], never be hidden by a strict mock's throw inside the reaper.
     */
    private class MetadataPorts {
        val repository = mockk<DatasourceRepository>(relaxed = true)
        val encryptor = mockk<CredentialEncryptor>(relaxed = true)
        val references = mockk<DatasourceReferences>(relaxed = true)
        val auditSink = mockk<DatasourceAuditSink>(relaxed = true)
        val invalidation = mockk<PoolInvalidationPublisher>(relaxed = true)
        val lakeTables = mockk<LakeTableCatalog>(relaxed = true)
        val lakeViewRecorder = mockk<LakeViewOutcomeRecorder>(relaxed = true)
        val managedLake = mockk<ManagedLakeCatalog>(relaxed = true)
        private val all = arrayOf(repository, encryptor, references, auditSink, invalidation, lakeTables, lakeViewRecorder, managedLake)

        init {
            every { repository.findByName(any()) } answers { row(firstArg()) }
        }

        fun registry(): DefaultDatasourceRegistry =
            DefaultDatasourceRegistry(
                repository = repository,
                encryptor = encryptor,
                references = references,
                auditSink = auditSink,
                invalidation = invalidation,
                lakeTables = lakeTables,
                lakeViewRecorder = lakeViewRecorder,
                retireCeiling = Duration.ZERO,
                managedLake = managedLake,
                maxGenerationAge = Duration.ofDays(1),
            )

        fun forgetCalls() =
            clearMocks(
                all.first(),
                *all.drop(1).toTypedArray(),
                answers = false,
                recordedCalls = true,
                childMocks = false,
                exclusionRules = false,
            )

        fun confirmNoCalls() = confirmVerified(*all)
    }

    /** A pool of the measurement: a managed generation with one connection out, whose close returns at once. */
    private class MeasuredPool(
        override val name: String,
        override val managedGeneration: ManagedLakeGeneration,
    ) : ConnectionPool {
        override val activeConnections: Int = 1

        override fun leaseConnection(): Connection = error("not used")

        override fun close() = Unit
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L
        const val BASE_PACKAGE = "co.datapipelines"

        /** A compiled test class lives under a `test` output directory; a main class never does. */
        const val TEST_OUTPUT = "/test/"

        /** Spring's registrar resolves an unnamed `@Scheduled` to the bean of this name (`ScheduledAnnotationBeanPostProcessor`). */
        const val DEFAULT_SCHEDULER_BEAN = "taskScheduler"
        const val JOB_COUNT = 5
        const val EXPECTED_DELAY_SECONDS = 7L
        const val EXPECTED_DEADLINE_SECONDS = 65L
        const val HIKARI_DEFAULT_CONNECTION_TIMEOUT_SECONDS = 30L
        const val AWAIT_SECONDS = 10L
        const val DRAINED = "reap_drained"
        const val HELD = "reap_held"
        const val LIVE_POOLS = 1_000
        const val RETIRING_POOLS = 100
        const val MEASURE_ROUNDS = 5

        /** The assertion keeps an order of magnitude below the bound; the printed rounds are the measurement. */
        const val MEASURED_MARGIN = 10L

        /** The reaper; the other four (stale sweep, freshness tick, retention, dashboard refresh) name no scheduler. */
        val REAPER = "${DatasourcePoolReaperScheduler::class.java.name}.${DatasourcePoolReaperScheduler::reap.name}"

        fun h2(name: String): Datasource = Datasource(name = name, displayName = name, dialect = Dialect.H2, jdbcUrl = "jdbc:h2:mem:$name")

        fun row(name: String) =
            DatasourceRow(
                name = name,
                displayName = name,
                description = null,
                dialect = Dialect.H2,
                jdbcUrl = "jdbc:h2:mem:$name",
                username = null,
                credentialKind = CredentialKind.NONE,
                credentialEncrypted = null,
                properties = DatasourceProperties(),
                queryTimeoutSeconds = null,
                introspectionIncludeSchemas = emptyList(),
                isReadonly = false,
                ownerWorkspaceId = null,
                workspaceName = null,
                isDeleted = false,
                createdAt = Instant.EPOCH,
                updatedAt = Instant.EPOCH,
                createdBy = UUID.randomUUID(),
            )
    }
}
