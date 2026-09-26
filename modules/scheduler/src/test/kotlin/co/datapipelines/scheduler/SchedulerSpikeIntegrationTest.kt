package co.datapipelines.scheduler

import com.github.kagkarlsson.scheduler.boot.config.DbSchedulerConfigurationSupport
import com.github.kagkarlsson.scheduler.serializer.JavaSerializer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * **R1's first spike proof, the serializer rule, and the two-instance guarantee** (scheduler design
 * revision §2, §2.1, §2.2, §8) — against the real V38 schema and db-scheduler 16.12.0 itself.
 *
 * ## Spike 1 — `scheduleIfNotExists` joins our transaction
 * The dispatcher records a run and enqueues its task in ONE transaction. That is only true if the
 * client's insert runs on the transaction's connection, which the starter arranges by wrapping the
 * DataSource in `TransactionAwareDataSourceProxy`. Proven both ways: a rolled-back transaction
 * leaves NO task (and no run), a committed control leaves one of each — so the assertion cannot pass
 * vacuously.
 *
 * ## Two instances, one occurrence, at most one launch
 * Two harnesses over one database and one clock ARE two instances; every step is invoked
 * explicitly, so nothing depends on a race or on fair picking (record §8).
 */
class SchedulerSpikeIntegrationTest {
    @BeforeEach
    fun clean() = SchedulerTestDb.reset()

    @Test
    fun `spike 1 - a rolled-back transaction leaves neither the run nor its task, a committed control leaves both`() {
        val h = SchedulerHarness()
        val schedule = h.create()
        val rolledBack = UUID.randomUUID()
        val committed = UUID.randomUUID()

        shouldThrow<IllegalStateException> {
            h.transactions.execute {
                h.runs.insertOccurrence(manualRun(schedule, rolledBack, h))
                h.queue.enqueue(rolledBack, h.clock.instant())
                // The client's insert ran on the TRANSACTION's connection: this thread's JdbcTemplate
                // (which joins the transaction) sees the task, an independent connection does not.
                inTaskTable(rolledBack) shouldBe true
                seenByAnotherConnection(rolledBack) shouldBe false
                error("roll it back")
            }
        }
        h.transactions.execute {
            h.runs.insertOccurrence(manualRun(schedule, committed, h))
            h.queue.enqueue(committed, h.clock.instant())
        }

        h.runTasks() shouldContainExactly listOf(committed.toString())
        h.runs.find(rolledBack) shouldBe null
        h.runs.find(committed) shouldNotBe null
        println(
            "event=spike1.transaction_participation rolled_back_task=${inTaskTable(rolledBack)} committed_task=${inTaskTable(committed)}",
        )
    }

    /**
     * The spike's falsification, kept as a test: the SAME rollback with a client built over the RAW
     * DataSource (no `TransactionAwareDataSourceProxy`) leaks the task — its insert took its own
     * connection and autocommitted. So the green above is the proxy's doing, not luck.
     */
    @Test
    fun `spike 1 falsified - without the transaction-aware proxy the enqueue escapes the rollback`() {
        val h = SchedulerHarness()
        val schedule = h.create()
        val leaked = UUID.randomUUID()
        val rawClient =
            com.github.kagkarlsson.scheduler.testhelper.TestHelper
                .createManualScheduler(SchedulerTestDb.dataSource, listOf(SchedulerTasks.runTask(h.worker)))
                .clock(h.settable)
                .also { it.serializer(SchedulerAutoConfiguration.JSON_SERIALIZER) }
                .build()
        shouldThrow<IllegalStateException> {
            h.transactions.execute {
                h.runs.insertOccurrence(manualRun(schedule, leaked, h))
                DbSchedulerRunQueue(rawClient, SchedulerTasks.RUN_TASK).enqueue(leaked, h.clock.instant())
                error("roll it back")
            }
        }
        h.runs.find(leaked) shouldBe null // the run row rolled back …
        h.runTasks() shouldContainExactly listOf(leaked.toString()) // … the task did not: an orphan.
        println("event=spike1.falsified raw_datasource_task_after_rollback=${inTaskTable(leaked)}")
    }

    @Test
    fun `task data is JSON - never Java serialization (A8)`() {
        val h = SchedulerHarness()
        h.create()
        h.setTime(h.clock.instant().plus(Duration.ofHours(1)))
        h.tick()
        val data =
            SchedulerTestDb.jdbc.queryForObject(
                "SELECT task_data FROM scheduled_tasks WHERE task_name = :t",
                mapOf("t" to SchedulerTasks.RUN),
                ByteArray::class.java,
            )!!
        val text = String(data)
        // A JSON string of the run id; Java serialization would begin with the 0xACED magic.
        (data[0].toInt() and 0xFF) shouldNotBe JAVA_SERIALIZATION_MAGIC
        text.startsWith("\"") shouldBe true
        UUID.fromString(text.trim('"')) shouldNotBe null
    }

    @Test
    fun `the configured serializer is the JSON one, never the starter's Java default`() {
        SchedulerAutoConfiguration.JSON_SERIALIZER.shouldBeInstanceOf<TaskDataJsonSerializer>()
        (SchedulerAutoConfiguration.JSON_SERIALIZER === DbSchedulerConfigurationSupport.SPRING_JAVA_SERIALIZER) shouldBe false
        SchedulerAutoConfiguration.JSON_SERIALIZER.javaClass.name shouldNotBe JavaSerializer::class.java.name
        SchedulerAutoConfiguration().schedulerCustomizer(SchedulerWorkerName("w")).serializer().get() shouldBe
            SchedulerAutoConfiguration.JSON_SERIALIZER
    }

    @Test
    fun `two instances tick the same due instant - one occurrence, one task`() {
        val a = SchedulerHarness()
        val b = SchedulerHarness(settable = a.settable)
        val schedule = a.create()
        a.setTime(schedule.nextDueAt!!.plusSeconds(3))

        a.tick().queued shouldBe 1
        // B computed the same instant: ON CONFLICT DO NOTHING records nothing, scheduleIfNotExists enqueues nothing.
        b.tick().total shouldBe 0

        a.runsOf(schedule.id) shouldHaveSize 1
        a.runTasks() shouldHaveSize 1
    }

    @Test
    fun `two instances deliver the same run - at most one launch that may have begun work`() {
        val a = SchedulerHarness()
        val b = SchedulerHarness(settable = a.settable, executor = a.executor)
        val schedule = a.create()
        a.setTime(schedule.nextDueAt!!.plusSeconds(3))
        a.tick()
        val run = a.runsOf(schedule.id).single()

        // Both instances handle the one run — as a duplicate delivery or a revived one would.
        a.worker.handle(run.id) shouldBe WorkerDecision.Done
        b.worker.handle(run.id) shouldBe WorkerDecision.Done

        a.executor.startCount.get() shouldBe 1
        a.runs.find(run.id)!!.state shouldBe RunState.RUNNING
        a.runs.trail(run.id).map { it.kind } shouldContainExactly
            listOf(TrailKind.RECORDED, TrailKind.CLAIMED, TrailKind.EXECUTION_STARTED)
    }

    /**
     * The same guarantee with the race FORCED: both workers read the run `queued` and prepare it;
     * the second worker's whole delivery runs inside the first one's preparation, so the first one
     * reaches its claim only after the second has claimed and launched. The conditional claim
     * (`WHERE state = 'queued'`) is the only thing left between that and a second launch — the
     * sequential test above cannot see it (its second worker stops at the state read).
     * Falsified: dropping the claim's state condition launches twice here.
     */
    @Test
    fun `two workers that both read the run queued - the conditional claim lets exactly one launch`() {
        val a = SchedulerHarness()
        val b = SchedulerHarness(settable = a.settable, executor = a.executor)
        val schedule = a.create()
        a.setTime(schedule.nextDueAt!!.plusSeconds(3))
        a.tick()
        val run = a.runsOf(schedule.id).single()
        a.executor.duringPrepare = { b.worker.handle(run.id) shouldBe WorkerDecision.Done }

        a.worker.handle(run.id) shouldBe WorkerDecision.Done

        a.executor.prepared.count { it == run.id } shouldBe 2 // both passed the state read
        a.executor.startCount.get() shouldBe 1
        a.runs.find(run.id)!!.state shouldBe RunState.RUNNING
        a.runs.trail(run.id).map { it.kind } shouldContainExactly
            listOf(TrailKind.RECORDED, TrailKind.CLAIMED, TrailKind.EXECUTION_STARTED)
    }

    @Test
    fun `db-scheduler itself delivers the run task once - the task row is gone after it completed`() {
        val h = SchedulerHarness()
        val schedule = h.create()
        h.setTime(schedule.nextDueAt!!.plusSeconds(3))
        h.tick()
        h.manual.runAnyDueExecutions()
        h.manual.runAnyDueExecutions()

        h.executor.startCount.get() shouldBe 1
        h.runTasks().shouldBeEmpty()
    }

    /** A connection taken straight from the pool — NOT bound to the thread's transaction. */
    private fun seenByAnotherConnection(runId: UUID): Boolean =
        SchedulerTestDb.dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT count(*) FROM scheduled_tasks WHERE task_instance = ?").use { statement ->
                statement.setString(1, runId.toString())
                statement.executeQuery().use { rows -> rows.next() && rows.getInt(1) > 0 }
            }
        }

    private fun inTaskTable(runId: UUID): Boolean =
        SchedulerTestDb.jdbc.queryForObject(
            "SELECT count(*) FROM scheduled_tasks WHERE task_instance = :id",
            mapOf("id" to runId.toString()),
            Int::class.java,
        )!! > 0

    private fun manualRun(
        schedule: Schedule,
        id: UUID,
        h: SchedulerHarness,
    ) = NewRun(
        id = id,
        scheduleId = schedule.id,
        workspaceId = schedule.workspaceId,
        origin = RunOrigin.CRON,
        scheduledAt = Instant.parse("2026-01-01T00:00:00Z").plusSeconds(id.leastSignificantBits.mod(100_000L) * 60),
        referenceAt = h.clock.instant(),
        referenceTimezone = "UTC",
        admitBy = h.clock.instant().plusSeconds(600),
        scheduleRevision = 1,
        executorId = FakeExecutor.ID,
        payloadSchemaVersion = 1,
        payload = schedule.payload,
        parameters = schedule.parameters,
        actorUserId = SchedulerTestDb.SYSTEM_ACTOR,
        requestedBy = null,
        state = RunState.QUEUED,
        reason = null,
        createdAt = h.clock.instant(),
    )

    private companion object {
        const val JAVA_SERIALIZATION_MAGIC = 0xAC
    }
}
