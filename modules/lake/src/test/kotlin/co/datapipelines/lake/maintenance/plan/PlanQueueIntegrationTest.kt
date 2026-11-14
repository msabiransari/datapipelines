package co.datapipelines.lake.maintenance.plan

import co.datapipelines.lake.LakeTestDb
import co.datapipelines.lake.maintenance.MaintenanceTable
import co.datapipelines.lake.maintenance.plan.MaintenancePartNames.CLEANUP
import co.datapipelines.lake.maintenance.plan.MaintenancePartNames.COMPACTION
import co.datapipelines.lake.maintenance.plan.PlanHarness.FileSpec
import co.datapipelines.scheduler.DbSchedulerSystemJobQueue
import co.datapipelines.scheduler.KeyedSystemJob
import co.datapipelines.scheduler.RecurringSystemJob
import co.datapipelines.scheduler.SchedulerAutoConfiguration
import co.datapipelines.scheduler.SchedulerTasks
import co.datapipelines.scheduler.SystemJobOutcome
import co.datapipelines.scheduler.SystemJobQueue
import com.github.kagkarlsson.scheduler.testhelper.ManualScheduler
import com.github.kagkarlsson.scheduler.testhelper.SettableClock
import com.github.kagkarlsson.scheduler.testhelper.TestHelper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * #959 C over the pinned db-scheduler (the library's manual scheduler on the module's Postgres, built as 957's
 * `SystemJobQueueIntegrationTest` builds it): N9 (re-rank, M4), N10 (a running task is left), N11 (first cleanup, M4),
 * N12 (a planner that dies half-way), N14 (the plan row's falling-behind count; one planner per period across two
 * scheduler instances).
 */
class PlanQueueIntegrationTest {
    private val h = PlanHarness()
    private val worker = RecordingWorker()
    private val clock = SettableClock().also { it.set(Instant.parse("2026-11-14T12:00:00Z")) }
    private val scheduler: ManualScheduler by lazy { manual(listOf(SchedulerTasks.keyedTask(worker))) }
    private val queue: SystemJobQueue by lazy { DbSchedulerSystemJobQueue { scheduler } }
    private val registered = setOf(CLEANUP, COMPACTION)

    @BeforeEach
    fun clean() {
        h.reset()
        LakeTestDb.jdbc.execute("TRUNCATE scheduled_tasks")
    }

    @AfterEach
    fun close() = h.close()

    private fun planner(queue: SystemJobQueue = this.queue): MaintenancePlanner =
        h.planner(registered = registered, queue = queue, worker = worker, clock = javaClock(clock))

    /** A finished cleanup [hoursAgo] before the catalog's now, as 957 records one. */
    private fun cleaned(
        table: MaintenanceTable,
        hoursAgo: Long,
    ) {
        LakeTestDb.jdbc.update(
            "INSERT INTO lake_maintenance_runs (id, table_id, kind, state, deadline_at, parts_json, started_at, ended_at) " +
                "VALUES (gen_random_uuid(), ?, 'maintenance', 'finished', now(), " +
                "CAST('[{\"name\":\"cleanup\",\"outcome\":\"done\",\"attempted\":true}]' AS jsonb), " +
                "now() - make_interval(hours => ?) - interval '1 minute', now() - make_interval(hours => ?))",
            table.id,
            hoursAgo.toInt(),
            hoursAgo.toInt(),
        )
    }

    private fun executionTime(key: String): Instant =
        requireNotNull(
            LakeTestDb.jdbc.queryForObject(
                "SELECT execution_time FROM scheduled_tasks WHERE task_name = ? AND task_instance = ?",
                Timestamp::class.java,
                worker.name,
                key,
            ),
        ).toInstant()

    private fun instances(): List<String> =
        LakeTestDb.jdbc.queryForList(
            "SELECT task_instance FROM scheduled_tasks WHERE task_name = ? ORDER BY task_instance",
            String::class.java,
            worker.name,
        )

    @Test
    fun `N9 - a rewrite queued by an earlier plan runs AFTER a cleanup that became due later`() {
        val rewrite = h.table("rewrite")
        val cleanup = h.table("cleanup")
        h.assign(rewrite, "development") // the whole-day window: its compaction is never held
        h.append(rewrite, *Array(5) { FileSpec("2026-11-01", 1_048_576) })
        cleaned(rewrite, 1)
        cleaned(cleanup, 1)
        val planner = planner()
        h.pass(planner)
        instances() shouldContainExactly listOf(rewrite.id.toString())
        val firstPlan = executionTime(rewrite.id.toString())

        // the cleanup becomes due: its last finished cleanup is now 30 hours old
        LakeTestDb.jdbc.update(
            "UPDATE lake_maintenance_runs SET ended_at = ended_at - interval '29 hours', started_at = started_at - interval '29 hours' " +
                "WHERE table_id = ?",
            cleanup.id,
        )
        clock.set(clock.now().plusSeconds(60))
        h.pass(planner)

        executionTime(cleanup.id.toString()) shouldBe clock.now()
        executionTime(rewrite.id.toString()) shouldBe clock.now().plusMillis(1)
        (executionTime(rewrite.id.toString()) > firstPlan) shouldBe true
        clock.set(clock.now().plusSeconds(1))
        scheduler.runAnyDueExecutions()
        worker.ran shouldContainExactly listOf(cleanup.id.toString(), rewrite.id.toString())
        h.repository.lastPlan()!!.second.let {
            it.tasksEnqueued shouldBe 1
            it.tasksMoved shouldBe 1
            it.tasksStillQueued shouldBe 1
        }
    }

    @Test
    fun `N10 - a running task is left - its due time is unchanged by a plan`() {
        val table = h.table("running")
        val planner = planner()
        h.pass(planner)
        val key = table.id.toString()
        val seen = mutableListOf<Any>()
        worker.during = {
            val before = executionTime(key)
            planner.run()
            seen.add(executionTime(key) == before)
            seen.add(h.repository.lastPlan()!!.second.let { listOf(it.tasksEnqueued, it.tasksMoved) })
        }
        clock.set(clock.now().plusSeconds(1))
        scheduler.runAnyDueExecutions()

        seen shouldContainExactly listOf(true, listOf(0, 0))
        worker.ran shouldContainExactly listOf(key)
    }

    @Test
    fun `N11 - a table with no finished cleanup is due in the first plan, overdue since its row was created`() {
        val table = h.table("first")
        val created =
            requireNotNull(
                LakeTestDb.jdbc.queryForObject("SELECT created_at FROM lake_tables_managed WHERE id = ?", Timestamp::class.java, table.id),
            ).toInstant()

        h.pass(planner())

        instances() shouldContainExactly listOf(table.id.toString())
        val answer = h.decide(registered).getValue(table.id)
        answer.due shouldBe setOf(CLEANUP)
        answer.overdueSince shouldBe created
    }

    @Test
    fun `N12 - a planner that dies half-way has enqueued a prefix, the next plan enqueues the rest and duplicates none`() {
        val tables = (1..4).map { h.table("t$it") }
        val calls = AtomicInteger()
        val dying =
            object : SystemJobQueue by queue {
                override fun enqueueIfAbsent(
                    job: KeyedSystemJob,
                    key: String,
                    at: Instant,
                ): Boolean {
                    if (calls.incrementAndGet() == 3) throw IllegalStateException("the planner's instance died")
                    return queue.enqueueIfAbsent(job, key, at)
                }
            }

        shouldThrow<IllegalStateException> { planner(dying).run() }
        instances().size shouldBe 2

        h.pass(planner())

        // compared as sets: the database's text collation and Kotlin's sort order need not agree
        instances().size shouldBe 4
        instances().toSet() shouldBe tables.map { it.id.toString() }.toSet()
        h.repository.lastPlan()!!.second.let {
            it.tasksEnqueued shouldBe 2
            it.tasksMoved shouldBe 2
        }
    }

    @Test
    fun `N14 - the falling-behind count equals the tasks still queued from the earlier plan`() {
        h.table("a")
        h.table("b")
        val planner = planner()
        h.pass(planner)
        h.repository.lastPlan()!!.second.tasksStillQueued shouldBe 0

        h.table("c")
        h.pass(planner)

        h.repository.lastPlan()!!.second.let {
            it.tablesWithWork shouldBe 3
            it.tasksEnqueued shouldBe 1
            it.tasksStillQueued shouldBe 2
        }
    }

    @Test
    fun `N14 - one planner runs per period across two scheduler instances`() {
        h.table("a")
        val runs = AtomicInteger()
        fun counted(planner: MaintenancePlanner): RecurringSystemJob =
            object : RecurringSystemJob by planner {
                override fun run() {
                    runs.incrementAndGet()
                    planner.run()
                }
            }
        val a = manualRecurring(counted(planner()))
        val b = manualRecurring(counted(planner()))
        a.start()
        b.start()

        clock.set(clock.now().plus(PERIOD).plusSeconds(1))
        a.runAnyDueExecutions()
        b.runAnyDueExecutions()

        runs.get() shouldBe 1
        clock.set(clock.now().plus(PERIOD).plusSeconds(1))
        b.runAnyDueExecutions()
        a.runAnyDueExecutions()
        runs.get() shouldBe 2
        a.stop()
        b.stop()
    }

    private fun manual(tasks: List<com.github.kagkarlsson.scheduler.task.Task<*>>): ManualScheduler =
        TestHelper
            .createManualScheduler(TransactionAwareDataSourceProxy(LakeTestDb.dataSource), tasks)
            .clock(clock)
            .also { it.serializer(SchedulerAutoConfiguration.JSON_SERIALIZER) }
            .build()

    private fun manualRecurring(job: RecurringSystemJob): ManualScheduler =
        TestHelper
            .createManualScheduler(TransactionAwareDataSourceProxy(LakeTestDb.dataSource), listOf(SchedulerTasks.keyedTask(worker)))
            .clock(clock)
            .startTasks(listOf(SchedulerTasks.recurringTask(job)))
            .also { it.serializer(SchedulerAutoConfiguration.JSON_SERIALIZER) }
            .build()

    /** The task the planner enqueues, recording the keys the scheduler hands out, in order. */
    private class RecordingWorker : KeyedSystemJob {
        override val name = "lake-maintenance"
        override val instances = "one instance per table"
        val ran = CopyOnWriteArrayList<String>()

        @Volatile
        var during: (String) -> Unit = {}

        override fun run(key: String): SystemJobOutcome {
            during(key)
            ran += key
            return SystemJobOutcome.Done
        }
    }

    private companion object {
        val PERIOD: Duration = Duration.ofMinutes(15)

        fun javaClock(settable: SettableClock): Clock =
            object : Clock() {
                override fun getZone(): ZoneId = ZoneOffset.UTC

                override fun withZone(zone: ZoneId?): Clock = this

                override fun instant(): Instant = settable.now()
            }
    }
}
