package co.datapipelines.web.config

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import co.datapipelines.auth.AuditProperties
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.datasources.pooling.ReapOutcome
import co.datapipelines.executor.CancellationFlags
import co.datapipelines.executor.ExecutionCancellationService
import co.datapipelines.executor.ExecutionEventRepository
import co.datapipelines.executor.ExecutionRepository
import co.datapipelines.executor.ExecutorConfig
import co.datapipelines.executor.ExecutorMetrics
import co.datapipelines.executor.InMemoryCancellationRegistry
import co.datapipelines.persistence.BatchSink
import co.datapipelines.persistence.BatchingConfig
import co.datapipelines.persistence.BatchingWriter
import co.datapipelines.web.SnapshotListAppender
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.config.BeanDefinitionCustomizer
import org.springframework.context.ApplicationListener
import org.springframework.context.SmartLifecycle
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.event.ContextClosedEvent
import org.springframework.core.env.MapPropertySource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.scheduling.config.ScheduledTaskRegistrar
import org.springframework.scheduling.config.TaskManagementConfigUtils
import org.springframework.scheduling.config.TaskSchedulerRouter
import org.springframework.test.util.ReflectionTestUtils
import java.sql.SQLException
import java.time.OffsetDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.function.Supplier
import javax.sql.DataSource

/**
 * #316 — the `@Scheduled` jobs run on a scheduler of their own, never on the SSE log streamer's
 * thread, and stop in the right place in the shutdown sequence.
 *
 * A real Spring context of the three REAL scheduling configurations (`@EnableScheduling` lives on
 * [SweepSchedulingConfiguration]; since #818 the pool reaper's configuration also holds the managed
 * lake's freshness tick, the fourth job, run here at a one-second cadence), the SSE log executor
 * exactly as [WebSurfaceConfiguration] builds it, and the two REAL shutdown lifecycles — so Spring's
 * own registrar decides which scheduler each job gets, exactly as it does in the application. Only
 * the jobs' stores are stand-ins, and each one logs which thread its job's tick arrived on: the
 * startup tick every `fixedDelay` job takes at refresh is the observation, never a direct call of
 * the adapter (which would run on the test's own thread and prove nothing). The assembled
 * application's twin is `ScheduledJobsSchedulerE2eTest`.
 *
 * #818d (record §10 RULING 4, second amendment, item 5) — the pool reaper is the exception: its `@Scheduled` names
 * its own one-thread scheduler (`poolReaperTaskScheduler`, thread `dp-reaper`), so a sibling's tick can never delay
 * a reap. The shutdown case holds a tick on each thread and pins that both schedulers drain before the close returns.
 *
 * Until #316 there was no `TaskScheduler` bean, Spring resolved the context's unique
 * `ScheduledExecutorService` — `sseLogScheduler` — and every tick below arrived on `dp-sse-log`.
 */
@Timeout(value = 1, unit = TimeUnit.MINUTES)
class ScheduledJobsSchedulerTest {
    // A snapshot (#484): the ticking and draining threads log while this thread reads.
    private val logs = SnapshotListAppender()
    private val watched =
        listOf(
            ExecutionDrainLifecycle::class.java,
            PersistenceDrainLifecycle::class.java,
            ScheduledJobsSchedulerTest::class.java,
        ).map { LoggerFactory.getLogger(it) as Logger } +
            // By name: the scheduler's class is what #316 adds, and this suite had to run red without it.
            (LoggerFactory.getLogger(SCHEDULER_LOGGER) as Logger)

    /** Holds the stale sweep's and the pool reaper's startup ticks in flight until released (the shutdown case only). */
    private val tickGate = CountDownLatch(0)

    @BeforeEach
    fun listen() {
        logs.start()
        watched.forEach { it.addAppender(logs) }
    }

    @AfterEach
    fun unlisten() {
        watched.forEach { it.detachAppender(logs) }
    }

    @Test
    fun `every scheduled job ticks on dp-scheduled - the reaper alone on dp-reaper - never on the SSE log thread`() {
        schedulingContext().use {
            awaitCondition { JOBS.all { job -> ticks(job).isNotEmpty() } }
            val observed = JOBS.associateWith { job -> ticks(job).map { tick -> tick.threadName }.toSet() }
            // Non-vacuity: the clue names the thread each job was actually seen on.
            withClue("the thread each job's startup tick arrived on: $observed") {
                // #818d (record §10 RULING 4, second amendment, item 5): the reaper on its own thread, the rest shared.
                observed shouldBe JOBS.associateWith { job -> setOf(if (job == REAP) REAPER_THREAD else JOBS_THREAD) }
                observed.values
                    .flatten()
                    .filter { it == SSE_LOG_THREAD }
                    .shouldBeEmpty()
            }
        }
    }

    @Test
    fun `the jobs' scheduler is theirs alone - Spring's default, one thread, four jobs, no bean wired to it`() {
        schedulingContext().use { context ->
            val scheduler = context.getBean(SCHEDULER_BEAN).shouldBeInstanceOf<ThreadPoolTaskScheduler>()
            // The registrar's router resolves its default lazily — the bean, not the SSE executor.
            val processor =
                context
                    .getBean(TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME)
                    .shouldBeInstanceOf<ScheduledAnnotationBeanPostProcessor>()
            val registrar = ReflectionTestUtils.getField(processor, "registrar").shouldBeInstanceOf<ScheduledTaskRegistrar>()
            val router = registrar.scheduler.shouldBeInstanceOf<TaskSchedulerRouter>()
            val resolved = ReflectionTestUtils.getField(router, "defaultScheduler").shouldBeInstanceOf<Supplier<*>>().get()
            resolved shouldBeSameInstanceAs scheduler
            scheduler.poolSize shouldBe 1
            // Who else is on the thread: the @Scheduled tasks (exactly the four jobs) and the beans
            // wired to the scheduler — only Spring's @Scheduled processor, whose router registers
            // itself as a dependent when it resolves the default (and is destroyed first because of it).
            processor.scheduledTasks.map { it.task.toString() }.toSet() shouldBe SCHEDULED_METHODS
            // #818d: the reaper's task resolves its OWN scheduler by name — a second one-thread scheduler, not the
            // default, so the router's default stays the jobs' scheduler above.
            val reaperScheduler = context.getBean(REAPER_SCHEDULER_BEAN).shouldBeInstanceOf<ThreadPoolTaskScheduler>()
            (reaperScheduler === scheduler) shouldBe false
            reaperScheduler.poolSize shouldBe 1
            reaperScheduler.phase shouldBe scheduler.phase
            context.beanFactory.getDependentBeans(SCHEDULER_BEAN).toList() shouldBe
                listOf(TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME)
            // And the SSE log executor is still its own single-thread executor, untouched.
            context.getBean(SSE_LOG_BEAN).shouldBeInstanceOf<ScheduledExecutorService>()
        }
    }

    @Test
    fun `on close no tick starts once the context is closing, a tick in flight finishes, and the scheduler stops after both drains`() {
        val gate = CountDownLatch(1)
        val context = schedulingContext(tickGate = gate, withProbe = true)
        awaitCondition { ticks(SWEEP).isNotEmpty() && ticks(REAP).isNotEmpty() }
        // The sweep's startup tick now holds dp-scheduled, and the reaper's holds dp-reaper (#818d: its own thread).
        val closer =
            Thread {
                context.close()
                TEST_LOG.info(CONTEXT_CLOSED)
            }.apply { start() }
        awaitCondition { logged("event=shutdown.persistence_drained") }
        gate.countDown()
        closer.join(TimeUnit.SECONDS.toMillis(CLOSE_JOIN_SECONDS))

        val order = logged().map { it.formattedMessage }
        withClue("the order off the log: $order") {
            closer.isAlive shouldBe false
            // At the FIRST lifecycle stop — before the scheduler admission gate, the execution drain,
            // the web server and the writers' drain would run — the jobs' executor had already stopped
            // taking ticks: a job's next tick cannot start against a draining store.
            probeState shouldBe ProbeState(shutdown = true, queued = 0, reaperShutdown = true, reaperQueued = 0)
            val closing = order.indexOf(CONTEXT_CLOSING)
            (closing >= 0) shouldBe true
            order.drop(closing + 1).filter { it.startsWith(TICK_STARTED) }.shouldBeEmpty()
            // The in-flight ticks ran to their end, and each scheduler stopped only after both drains and its tick.
            val executionDrain = order.indexOfFirst { it.startsWith("event=shutdown.drain_complete") }
            val writersDrained = order.indexOfFirst { it.startsWith("event=shutdown.persistence_drained") }
            val sweepEnded = order.indexOf("$TICK_ENDED job=$SWEEP")
            val reapEnded = order.indexOf("$TICK_ENDED job=$REAP")
            val stopped = order.indexOfFirst { it.startsWith("event=shutdown.scheduled_jobs_stopped") }
            val closed = order.indexOf(CONTEXT_CLOSED)
            (executionDrain in (closing + 1) until writersDrained) shouldBe true
            (writersDrained < sweepEnded) shouldBe true
            (sweepEnded < stopped) shouldBe true
            // #818d: the reaper's scheduler has the same phase and await, so the close returns only after its tick.
            (writersDrained < reapEnded) shouldBe true
            (reapEnded < closed) shouldBe true
        }
    }

    // ------------------------------------------------------------------ the context

    @Volatile
    private var probeState: ProbeState? = null

    private data class ProbeState(
        val shutdown: Boolean,
        val queued: Int,
        val reaperShutdown: Boolean,
        val reaperQueued: Int,
    )

    /**
     * Stops FIRST (the highest phase — where the application's scheduler admission gate sits) and
     * records whether the jobs' executor still takes ticks. Looked up by name when it stops, so it
     * never becomes a bean wired to the scheduler.
     */
    private inner class Probe(
        private val context: AnnotationConfigApplicationContext,
    ) : SmartLifecycle {
        @Volatile private var running = false

        override fun start() {
            running = true
        }

        override fun isRunning(): Boolean = running

        override fun getPhase(): Int = SmartLifecycle.DEFAULT_PHASE

        override fun stop() {
            val executor = context.getBean(SCHEDULER_BEAN, ThreadPoolTaskScheduler::class.java).scheduledThreadPoolExecutor
            val reaper = context.getBean(REAPER_SCHEDULER_BEAN, ThreadPoolTaskScheduler::class.java).scheduledThreadPoolExecutor
            probeState =
                ProbeState(
                    shutdown = executor.isShutdown,
                    queued = executor.queue.size,
                    reaperShutdown = reaper.isShutdown,
                    reaperQueued = reaper.queue.size,
                )
            running = false
        }
    }

    /**
     * #326 — the audit retention and the keys purge, and since #636 the dashboard refreshes' retention and the two
     * evaluation steps, are built over a BOUNDED template on this template's DataSource
     * (RetentionSchedulingConfiguration). The slice's refuses every connection, so those five steps fail fast and
     * are logged; the slice is about the thread every job ticks on, not their SQL.
     */
    private fun sliceJdbc(): NamedParameterJdbcTemplate {
        val jdbc = mockk<NamedParameterJdbcTemplate>()
        every { jdbc.queryForObject(any<String>(), any<Map<String, *>>(), OffsetDateTime::class.java) } returns OffsetDateTime.now()
        every { jdbc.update(any<String>(), any<Map<String, *>>()) } returns 0
        val noDatabase = mockk<DataSource> { every { connection } throws SQLException("the scheduling slice has no database") }
        every { jdbc.jdbcTemplate } returns JdbcTemplate(noDatabase)
        return jdbc
    }

    private fun schedulingContext(
        tickGate: CountDownLatch = this.tickGate,
        withProbe: Boolean = false,
    ): AnnotationConfigApplicationContext {
        val executions = mockk<ExecutionRepository>()
        every { executions.sweepStaleRunning(any(), any()) } answers {
            tick(SWEEP) {
                tickGate.await(GATE_SECONDS, TimeUnit.SECONDS)
                0
            }
        }
        val datasources = mockk<DatasourceRegistry>()
        every { datasources.reapRetiredPools() } answers {
            tick(REAP) {
                tickGate.await(GATE_SECONDS, TimeUnit.SECONDS)
                ReapOutcome.NOTHING
            }
        }
        every { datasources.refreshManagedGenerations(any()) } answers { tick(FRESHNESS) { 0 } }
        val events = mockk<ExecutionEventRepository>()
        every { events.deleteOlderThan(any()) } answers { tick(RETAIN) { 0 } }
        val jdbc = sliceJdbc()
        val cancellations = InMemoryCancellationRegistry()
        val writer = BatchingWriter("audit", BatchingConfig(), NoopSink)
        return AnnotationConfigApplicationContext().apply {
            registerBean(ExecutionRepository::class.java, Supplier { executions })
            registerBean(ExecutorProperties::class.java, Supplier { ExecutorProperties() })
            registerBean(ExecutionsProperties::class.java, Supplier { ExecutionsProperties() })
            // #336 D7 — the progress sink's outage counter. A real in-memory registry: the
            // contract under test is "the counter is READ when a write fails", and a strict
            // mock would make the missing increment unobservable.
            registerBean(ExecutorMetrics::class.java, Supplier { ExecutorMetrics(SimpleMeterRegistry()) })
            registerBean(DatasourceRegistry::class.java, Supplier { datasources })
            // #818 — the freshness tick's regrant half; the lake is not under test here.
            registerBean(ManagedLakeDatasourceRegistrar::class.java, Supplier { mockk<ManagedLakeDatasourceRegistrar>(relaxed = true) })
            // #841 — the regrant's writer guarding (DomainConfiguration's bean in production); no lock in this slice.
            registerBean(
                co.datapipelines.datasources.DatasourceWriteGuarding::class.java,
                Supplier { co.datapipelines.datasources.DatasourceWriteGuarding.UNGUARDED },
            )
            environment.propertySources.addFirst(MapPropertySource("freshness", mapOf(FRESHNESS_TICK_KEY to "1")))
            registerBean(MeterRegistry::class.java, Supplier { SimpleMeterRegistry() })
            registerBean(ExecutionEventRepository::class.java, Supplier { events })
            registerBean(NamedParameterJdbcTemplate::class.java, Supplier { jdbc })
            registerBean(AuditProperties::class.java, Supplier { AuditProperties() })
            // The SSE log executor, built by the production method: the bean Spring fell back to.
            registerBean(
                SSE_LOG_BEAN,
                ScheduledExecutorService::class.java,
                Supplier { WebSurfaceConfiguration().sseLogScheduler() },
                BeanDefinitionCustomizer { it.destroyMethodName = "shutdown" },
            )
            register(
                SweepSchedulingConfiguration::class.java,
                PoolReaperSchedulingConfiguration::class.java,
                RetentionSchedulingConfiguration::class.java,
            )
            val cancellation = ExecutionCancellationService(cancellations, mockk<CancellationFlags>(relaxed = true), ExecutorConfig())
            registerBean(
                "executionDrain",
                ExecutionDrainLifecycle::class.java,
                Supplier { ExecutionDrainLifecycle(cancellation, cancellations, this) },
            )
            registerBean("persistenceDrain", PersistenceDrainLifecycle::class.java, Supplier { PersistenceDrainLifecycle(listOf(writer)) })
            if (withProbe) registerBean("probe", Probe::class.java, Supplier { Probe(this) })
            addApplicationListener(ApplicationListener<ContextClosedEvent> { TEST_LOG.info(CONTEXT_CLOSING) })
            refresh()
            start()
        }
    }

    /** Logs the tick's start (on the job's own thread — the observation) and its end around [body]. */
    private fun <T> tick(
        job: String,
        body: () -> T,
    ): T {
        TEST_LOG.info("{} job={}", TICK_STARTED, job)
        try {
            return body()
        } finally {
            TEST_LOG.info("{} job={}", TICK_ENDED, job)
        }
    }

    private fun ticks(job: String): List<ILoggingEvent> = logged().filter { it.formattedMessage == "$TICK_STARTED job=$job" }

    private fun logged(prefix: String): Boolean = logged().any { it.formattedMessage.startsWith(prefix) }

    /**
     * A copy of what was logged so far. The ticking and draining threads append while this thread
     * reads, so the read is the module's [SnapshotListAppender] snapshot (#484), never the live list.
     */
    private fun logged(): List<ILoggingEvent> = logs.events()

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(AWAIT_SECONDS)
        while (!condition()) {
            check(System.nanoTime() < deadline) {
                val events = logged()
                val first = events.firstOrNull()?.timeStamp ?: 0L
                "condition not met within $AWAIT_SECONDS s; the log: ${events.map { "+${it.timeStamp - first}ms ${it.formattedMessage}" }}"
            }
            Thread.sleep(POLL_MS)
        }
    }

    /** The writers' drain needs a writer; this one is never given an item. */
    private object NoopSink : BatchSink<String> {
        override fun write(items: List<String>) = Unit

        override fun partitionKey(item: String): Any? = null

        override fun sizeOf(item: String): Int = item.length

        override fun describe(item: String): String = "noop"
    }

    private companion object {
        val TEST_LOG: org.slf4j.Logger = LoggerFactory.getLogger(ScheduledJobsSchedulerTest::class.java)

        const val SCHEDULER_BEAN = "taskScheduler"
        const val SSE_LOG_BEAN = "sseLogScheduler"
        const val SSE_LOG_THREAD = "dp-sse-log"

        /** The spec's name (#316), a literal — never read back from the class under test. */
        const val JOBS_THREAD = "dp-scheduled"

        /** #818d — the reaper's own thread and scheduler bean (record §10 RULING 4, second amendment, item 5), literals. */
        const val REAPER_THREAD = "dp-reaper"
        const val REAPER_SCHEDULER_BEAN = "poolReaperTaskScheduler"
        const val SCHEDULER_LOGGER = "co.datapipelines.web.config.ScheduledJobsTaskScheduler"
        const val SWEEP = "sweep"
        const val REAP = "reap"
        const val RETAIN = "retain"
        const val FRESHNESS = "freshness"

        /** #818 — one second, so the freshness tick's startup tick lands inside [AWAIT_SECONDS]. */
        const val FRESHNESS_TICK_KEY = "datapipelines.lake.reads.freshness-tick-seconds"
        val JOBS = listOf(SWEEP, REAP, RETAIN, FRESHNESS)
        val SCHEDULED_METHODS =
            setOf(
                "co.datapipelines.web.config.StaleExecutionSweepScheduler.sweep",
                "co.datapipelines.web.config.DatasourcePoolReaperScheduler.reap",
                "co.datapipelines.web.config.ExecutionEventRetentionScheduler.retain",
                "co.datapipelines.web.config.ManagedLakeFreshnessScheduler.tick",
            )
        const val TICK_STARTED = "test.tick_started"
        const val TICK_ENDED = "test.tick_ended"
        const val CONTEXT_CLOSING = "test.context_closing"
        const val CONTEXT_CLOSED = "test.context_closed"
        const val AWAIT_SECONDS = 10L
        const val GATE_SECONDS = 20L
        const val CLOSE_JOIN_SECONDS = 30L
        const val POLL_MS = 20L
    }
}
