package co.datapipelines.scheduler

import com.github.kagkarlsson.scheduler.testhelper.ManualScheduler
import com.github.kagkarlsson.scheduler.testhelper.SettableClock
import com.github.kagkarlsson.scheduler.testhelper.TestHelper
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * **R1–R4 of #957: the system-job seam over the pinned db-scheduler** (the P6 lane cut's L2), against the real
 * `scheduled_tasks` table. Every scheduler here is the library's manual scheduler over the shared database and one
 * settable clock, built the way the starter builds it (the transaction-aware proxy, the JSON serializer); two of them
 * are two instances.
 */
class SystemJobQueueIntegrationTest {
    @BeforeEach
    fun clean() = SchedulerTestDb.reset()

    @Test
    fun `R1 - two enqueues of one key leave one execution and a different key gets its own`() {
        val job = RecordingJob()
        val a = manual(job)
        val b = manual(job, a.clock)
        val queueA = DbSchedulerSystemJobQueue { a }
        val queueB = DbSchedulerSystemJobQueue { b }
        val at = a.clock.now().plusSeconds(60)

        queueA.enqueueIfAbsent(job, "key-1", at) shouldBe true
        queueB.enqueueIfAbsent(job, "key-1", at.plusSeconds(5)) shouldBe false // a second instance enqueues nothing
        queueA.enqueueIfAbsent(job, "key-2", at) shouldBe true

        instances(job) shouldContainExactly listOf("key-1", "key-2")
        executionTime(job, "key-1") shouldBe at // the second enqueue changed nothing
    }

    @Test
    fun `R2 - three keys enqueued out of order are handed out in due-time order`() {
        val job = RecordingJob()
        val scheduler = manual(job)
        val queue = DbSchedulerSystemJobQueue { scheduler }
        val now = scheduler.clock.now()
        queue.enqueueIfAbsent(job, "third", now.plusSeconds(3))
        queue.enqueueIfAbsent(job, "first", now.plusSeconds(1))
        queue.enqueueIfAbsent(job, "second", now.plusSeconds(2))

        scheduler.setTime(now.plusSeconds(10))
        scheduler.runAnyDueExecutions()

        job.ran shouldContainExactly listOf("first", "second", "third")
        instances(job) shouldBe emptyList()
    }

    @Test
    fun `R3 - a queued instance moved earlier runs earlier, a running or absent one answers false and changes nothing`() {
        val job = RecordingJob()
        val scheduler = manual(job)
        val queue = DbSchedulerSystemJobQueue { scheduler }
        val now = scheduler.clock.now()
        queue.enqueueIfAbsent(job, "late", now.plusSeconds(600))
        queue.enqueueIfAbsent(job, "running", now.plusSeconds(1))

        queue.move(job, "late", now.plusSeconds(2)) shouldBe true
        executionTime(job, "late") shouldBe now.plusSeconds(2)
        queue.move(job, "absent", now.plusSeconds(2)) shouldBe false
        instances(job) shouldContainExactly listOf("late", "running")

        // While "running" runs, it is picked: moving it answers false and leaves its row as it was.
        val whileRunning = mutableListOf<Any>()
        job.during = { key ->
            if (key == "running") {
                val before = executionTime(job, key)
                whileRunning += queue.move(job, key, now.plusSeconds(3600))
                whileRunning += (executionTime(job, key) == before)
            }
        }
        scheduler.setTime(now.plusSeconds(1))
        scheduler.runAnyDueExecutions()
        whileRunning shouldContainExactly listOf(false, true)
        job.ran shouldContainExactly listOf("running")

        scheduler.setTime(now.plusSeconds(2)) // the moved instance is due at its new time, not at +600 s
        scheduler.runAnyDueExecutions()
        job.ran shouldContainExactly listOf("running", "late")
        instances(job) shouldBe emptyList()
    }

    @Test
    fun `a keyed job answering again-at reschedules the same instance`() {
        val job = RecordingJob()
        val scheduler = manual(job)
        val queue = DbSchedulerSystemJobQueue { scheduler }
        val now = scheduler.clock.now()
        job.answer = { SystemJobOutcome.AgainAt(now.plusSeconds(30)) }
        queue.enqueueIfAbsent(job, "k", now)

        scheduler.runAnyDueExecutions()

        job.ran shouldContainExactly listOf("k")
        instances(job) shouldContainExactly listOf("k")
        executionTime(job, "k") shouldBe now.plusSeconds(30)
    }

    @Test
    fun `R4 - a recurring job with two instances runs once per period, and a run still going makes the next skip`() {
        val job = CountingRecurringJob(Duration.ofSeconds(PERIOD_SECONDS))
        val a = manualRecurring(job)
        val b = manualRecurring(job, a.clock)
        a.start()
        b.start()
        val start = a.clock.now()

        a.setTime(start.plusSeconds(PERIOD_SECONDS + 1))
        a.runAnyDueExecutions()
        b.runAnyDueExecutions()
        job.runs.get() shouldBe 1 // one period, two instances, one run

        // The next period: A's run is still going when B looks — and later, when the following period is due too.
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        job.during = {
            entered.countDown()
            release.await(WAIT_SECONDS, TimeUnit.SECONDS)
        }
        a.setTime(a.clock.now().plusSeconds(PERIOD_SECONDS + 1))
        val running = CompletableFuture.runAsync { a.runAnyDueExecutions() }
        entered.await(WAIT_SECONDS, TimeUnit.SECONDS) shouldBe true
        a.setTime(a.clock.now().plusSeconds(PERIOD_SECONDS * 3))
        b.runAnyDueExecutions()
        job.runs.get() shouldBe 2 // B did not start a second, overlapping run
        job.during = {}
        release.countDown()
        running.get(WAIT_SECONDS, TimeUnit.SECONDS)

        // The skipped periods are not made up: the next run is one delay after the long run ended.
        b.runAnyDueExecutions()
        a.runAnyDueExecutions()
        job.runs.get() shouldBe 2
        a.setTime(a.clock.now().plusSeconds(PERIOD_SECONDS + 1))
        b.runAnyDueExecutions()
        a.runAnyDueExecutions()
        job.runs.get() shouldBe 3
    }

    private fun manual(
        job: KeyedSystemJob,
        clock: SettableClock = SettableClock().also { it.set(Instant.parse("2026-11-13T12:00:00Z")) },
    ): ManualScheduler =
        TestHelper
            .createManualScheduler(TransactionAwareDataSourceProxy(SchedulerTestDb.dataSource), listOf(SchedulerTasks.keyedTask(job)))
            .clock(clock)
            .also { it.serializer(SchedulerAutoConfiguration.JSON_SERIALIZER) }
            .build()

    private fun manualRecurring(
        job: RecurringSystemJob,
        clock: SettableClock = SettableClock().also { it.set(Instant.parse("2026-11-13T12:00:00Z")) },
    ): ManualScheduler {
        val task = SchedulerTasks.recurringTask(job)
        // A start task is a known task too: the library's resolver refuses the same name listed twice.
        return TestHelper
            .createManualScheduler(TransactionAwareDataSourceProxy(SchedulerTestDb.dataSource), emptyList())
            .clock(clock)
            .startTasks(listOf(task))
            .also { it.serializer(SchedulerAutoConfiguration.JSON_SERIALIZER) }
            .build()
    }

    private fun instances(job: KeyedSystemJob): List<String> =
        SchedulerTestDb.jdbc.queryForList(
            "SELECT task_instance FROM scheduled_tasks WHERE task_name = :t ORDER BY task_instance",
            mapOf("t" to job.name),
            String::class.java,
        )

    private fun executionTime(
        job: KeyedSystemJob,
        key: String,
    ): Instant =
        SchedulerTestDb.jdbc
            .queryForObject(
                "SELECT execution_time FROM scheduled_tasks WHERE task_name = :t AND task_instance = :k",
                mapOf("t" to job.name, "k" to key),
                Timestamp::class.java,
            )!!
            .toInstant()

    private class RecordingJob : KeyedSystemJob {
        override val name = "system-job-test"
        override val instances = "one instance per test key"
        val ran = CopyOnWriteArrayList<String>()

        @Volatile
        var during: (String) -> Unit = {}

        @Volatile
        var answer: (String) -> SystemJobOutcome = { SystemJobOutcome.Done }

        override fun run(key: String): SystemJobOutcome {
            during(key)
            ran += key
            return answer(key)
        }
    }

    private class CountingRecurringJob(
        override val fixedDelay: Duration,
    ) : RecurringSystemJob {
        override val name = "recurring-system-job-test"
        val runs = AtomicInteger()

        @Volatile
        var during: () -> Unit = {}

        override fun run() {
            runs.incrementAndGet()
            during()
        }
    }

    private companion object {
        const val PERIOD_SECONDS = 60L
        const val WAIT_SECONDS = 10L
    }
}
