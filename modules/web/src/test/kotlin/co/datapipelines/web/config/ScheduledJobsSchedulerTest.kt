package co.datapipelines.web.config

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import co.datapipelines.auth.AuditProperties
import co.datapipelines.auth.KeyRetentionPurge
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
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.scheduling.config.ScheduledTaskRegistrar
import org.springframework.scheduling.config.TaskManagementConfigUtils
import org.springframework.scheduling.config.TaskSchedulerRouter
import org.springframework.test.util.ReflectionTestUtils
import java.time.OffsetDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.function.Supplier

/**
 * #316 — the `@Scheduled` jobs run on a scheduler of their own, never on the SSE log streamer's
 * thread, and stop in the right place in the shutdown sequence.
 *
 * A real Spring context of the three REAL scheduling configurations (`@EnableScheduling` lives on
 * [SweepSchedulingConfiguration]), the SSE log executor exactly as [WebSurfaceConfiguration] builds
 * it, and the two REAL shutdown lifecycles — so Spring's own registrar decides which scheduler each
 * job gets, exactly as it does in the application. Only the jobs' stores are stand-ins, and each
 * one logs which thread its job's tick arrived on: the startup tick every `fixedDelay` job takes at
 * refresh is the observation, never a direct call of the adapter (which would run on the test's own
 * thread and prove nothing). The assembled application's twin is `ScheduledJobsSchedulerE2eTest`.
 *
 * Until #316 there was no `TaskScheduler` bean, Spring resolved the context's unique
 * `ScheduledExecutorService` — `sseLogScheduler` — and every tick below arrived on `dp-sse-log`.
 */
@Timeout(value = 1, unit = TimeUnit.MINUTES)
class ScheduledJobsSchedulerTest {
    private val logs = ListAppender<ILoggingEvent>()
    private val watched =
        listOf(
            ExecutionDrainLifecycle::class.java,
            PersistenceDrainLifecycle::class.java,
            ScheduledJobsSchedulerTest::class.java,
        ).map { LoggerFactory.getLogger(it) as Logger } +
            // By name: the scheduler's class is what #316 adds, and this suite had to run red without it.
            (LoggerFactory.getLogger(SCHEDULER_LOGGER) as Logger)

    /** Holds the pool reaper's startup tick in flight until released (the shutdown case only). */
    private val reapGate = CountDownLatch(0)

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
    fun `every scheduled job ticks on the dp-scheduled thread, never on the SSE log thread`() {
        schedulingContext().use {
            awaitCondition { JOBS.all { job -> ticks(job).isNotEmpty() } }
            val observed = JOBS.associateWith { job -> ticks(job).map { tick -> tick.threadName }.toSet() }
            // Non-vacuity: the clue names the thread each job was actually seen on.
            withClue("the thread each job's startup tick arrived on: $observed") {
                observed shouldBe JOBS.associateWith { setOf(JOBS_THREAD) }
                observed.values
                    .flatten()
                    .filter { it == SSE_LOG_THREAD }
                    .shouldBeEmpty()
            }
        }
    }

    @Test
    fun `the jobs' scheduler is theirs alone - Spring's default, one thread, three jobs, no bean wired to it`() {
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
            // Who else is on the thread: the @Scheduled tasks (exactly the three jobs) and the beans
            // wired to the scheduler — only Spring's @Scheduled processor, whose router registers
            // itself as a dependent when it resolves the default (and is destroyed first because of it).
            processor.scheduledTasks.map { it.task.toString() }.toSet() shouldBe SCHEDULED_METHODS
            context.beanFactory.getDependentBeans(SCHEDULER_BEAN).toList() shouldBe
                listOf(TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME)
            // And the SSE log executor is still its own single-thread executor, untouched.
            context.getBean(SSE_LOG_BEAN).shouldBeInstanceOf<ScheduledExecutorService>()
        }
    }

    @Test
    fun `on close no tick starts once the context is closing, a tick in flight finishes, and the scheduler stops after both drains`() {
        val gate = CountDownLatch(1)
        val context = schedulingContext(reapGate = gate, withProbe = true)
        awaitCondition { ticks(REAP).isNotEmpty() }
        // The reaper's startup tick is now in flight, holding the one thread.
        val closer = Thread { context.close() }.apply { start() }
        awaitCondition { logged("event=shutdown.persistence_drained") }
        gate.countDown()
        closer.join(TimeUnit.SECONDS.toMillis(CLOSE_JOIN_SECONDS))

        val order = logged().map { it.formattedMessage }
        withClue("the order off the log: $order") {
            closer.isAlive shouldBe false
            // At the FIRST lifecycle stop — before the scheduler admission gate, the execution drain,
            // the web server and the writers' drain would run — the jobs' executor had already stopped
            // taking ticks: a job's next tick cannot start against a draining store.
            probeState shouldBe ProbeState(shutdown = true, queued = 0)
            val closing = order.indexOf(CONTEXT_CLOSING)
            (closing >= 0) shouldBe true
            order.drop(closing + 1).filter { it.startsWith(TICK_STARTED) }.shouldBeEmpty()
            // The in-flight tick ran to its end, and the scheduler stopped only after both drains and it.
            val executionDrain = order.indexOfFirst { it.startsWith("event=shutdown.drain_complete") }
            val writersDrained = order.indexOfFirst { it.startsWith("event=shutdown.persistence_drained") }
            val reapEnded = order.indexOf("$TICK_ENDED job=$REAP")
            val stopped = order.indexOfFirst { it.startsWith("event=shutdown.scheduled_jobs_stopped") }
            (executionDrain in (closing + 1) until writersDrained) shouldBe true
            (writersDrained < reapEnded) shouldBe true
            (reapEnded < stopped) shouldBe true
        }
    }

    // ------------------------------------------------------------------ the context

    @Volatile
    private var probeState: ProbeState? = null

    private data class ProbeState(
        val shutdown: Boolean,
        val queued: Int,
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
            probeState = ProbeState(shutdown = executor.isShutdown, queued = executor.queue.size)
            running = false
        }
    }

    private fun schedulingContext(
        reapGate: CountDownLatch = this.reapGate,
        withProbe: Boolean = false,
    ): AnnotationConfigApplicationContext {
        val executions = mockk<ExecutionRepository>()
        every { executions.sweepStaleRunning(any(), any()) } answers { tick(SWEEP) { 0 } }
        val datasources = mockk<DatasourceRegistry>()
        every { datasources.reapRetiredPools() } answers {
            tick(REAP) {
                reapGate.await(GATE_SECONDS, TimeUnit.SECONDS)
                ReapOutcome.NOTHING
            }
        }
        val events = mockk<ExecutionEventRepository>()
        every { events.deleteOlderThan(any()) } answers { tick(RETAIN) { 0 } }
        val jdbc = mockk<NamedParameterJdbcTemplate>()
        every { jdbc.queryForObject(any<String>(), any<Map<String, *>>(), OffsetDateTime::class.java) } returns OffsetDateTime.now()
        every { jdbc.update(any<String>(), any<Map<String, *>>()) } returns 0
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
            registerBean(MeterRegistry::class.java, Supplier { SimpleMeterRegistry() })
            registerBean(ExecutionEventRepository::class.java, Supplier { events })
            registerBean(NamedParameterJdbcTemplate::class.java, Supplier { jdbc })
            registerBean(AuditProperties::class.java, Supplier { AuditProperties() })
            registerBean(KeyRetentionPurge::class.java, Supplier { mockk<KeyRetentionPurge>(relaxed = true) })
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
     * A copy of what was logged so far. `ListAppender.list` is a plain `ArrayList` that the ticking and
     * draining threads append to while this thread reads it; logback appends under the appender's own
     * lock (`AppenderBase.doAppend` is synchronized), so the copy is taken under that lock.
     */
    private fun logged(): List<ILoggingEvent> = synchronized(logs) { logs.list.toList() }

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
        const val SCHEDULER_LOGGER = "co.datapipelines.web.config.ScheduledJobsTaskScheduler"
        const val SWEEP = "sweep"
        const val REAP = "reap"
        const val RETAIN = "retain"
        val JOBS = listOf(SWEEP, REAP, RETAIN)
        val SCHEDULED_METHODS =
            setOf(
                "co.datapipelines.web.config.StaleExecutionSweepScheduler.sweep",
                "co.datapipelines.web.config.DatasourcePoolReaperScheduler.reap",
                "co.datapipelines.web.config.ExecutionEventRetentionScheduler.retain",
            )
        const val TICK_STARTED = "test.tick_started"
        const val TICK_ENDED = "test.tick_ended"
        const val CONTEXT_CLOSING = "test.context_closing"
        const val AWAIT_SECONDS = 10L
        const val GATE_SECONDS = 20L
        const val CLOSE_JOIN_SECONDS = 30L
        const val POLL_MS = 20L
    }
}
