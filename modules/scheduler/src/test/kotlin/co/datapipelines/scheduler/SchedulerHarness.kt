package co.datapipelines.scheduler

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.github.kagkarlsson.scheduler.testhelper.ManualScheduler
import com.github.kagkarlsson.scheduler.testhelper.SettableClock
import com.github.kagkarlsson.scheduler.testhelper.TestHelper
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * One scheduler "instance" wired exactly as `SchedulerAutoConfiguration` wires it — the real
 * repositories, ledger, dispatcher, worker, reconciler and service over the shared database — with
 * three things controlled: the CLOCK (db-scheduler's `SettableClock`, which [ManualScheduler] also
 * reads, adapted to `java.time.Clock` for ours), the EXECUTOR ([FakeExecutor], a job that is NOT a
 * pipeline — record §8's proof that the scheduler needs no pipeline class) and the CAPACITY
 * ([FakeCapacityGate]).
 *
 * Two harnesses over one database and one clock are two instances: the record's two-instance
 * proofs run exactly that, with no race — every step is invoked explicitly.
 */
internal class SchedulerHarness(
    val settable: SettableClock = SettableClock().also { it.set(Instant.parse("2026-09-25T12:00:00Z")) },
    val executor: FakeExecutor = FakeExecutor(),
    val capacity: FakeCapacityGate = FakeCapacityGate(),
    val properties: SchedulerProperties = SchedulerProperties(minIntervalSeconds = 60),
    workerName: String = "instance-" + UUID.randomUUID().toString().take(4),
) {
    val clock: Clock = SettableJavaClock(settable)
    val registry = SimpleMeterRegistry()
    val metrics = SchedulerMetrics(registry)
    val schedules = ScheduleRepository(SchedulerTestDb.jdbc, SchedulerAutoConfiguration.JSON)
    val runs = ScheduleRunRepository(SchedulerTestDb.jdbc, SchedulerAutoConfiguration.JSON)
    val transactions = TransactionTemplate(DataSourceTransactionManager(SchedulerTestDb.dataSource))
    val ledger = RunLedger(runs, schedules, transactions, clock, metrics, workerName)
    val executors = JobExecutors(listOf(executor))
    val admission = SchedulerAdmission(properties.shutdownWait).also { it.start() }
    val dispatcher = ScheduleDispatcher(schedules, runs, ledger, transactions, clock, properties, metrics) { SchedulerTestDb.SYSTEM_ACTOR }
    val worker = ScheduledRunWorker(runs, schedules, executors, capacity, admission, ledger, transactions, clock, metrics)
    val reconciler = RunReconciler(runs, executors, ledger, clock, properties)

    /** db-scheduler over the SAME database, the SAME clock and our JSON serializer — the starter's shape. */
    val manual: ManualScheduler =
        TestHelper
            .createManualScheduler(
                TransactionAwareDataSourceProxy(SchedulerTestDb.dataSource),
                listOf(SchedulerTasks.runTask(worker), SchedulerTasks.dispatcherTask(dispatcher, properties)),
            ).clock(settable)
            .also { it.serializer(SchedulerAutoConfiguration.JSON_SERIALIZER) }
            .build()

    val queue = DbSchedulerRunQueue(manual, SchedulerTasks.RUN_TASK)

    val service =
        ScheduleService(schedules, runs, executors, ledger, transactions, clock, properties, queue, SchedulerAutoConfiguration.JSON) {
            SchedulerTestDb.SYSTEM_ACTOR
        }

    /** A valid create request for the fake executor. */
    fun request(
        name: String = "reports/daily",
        cron: String = "0 * * * *",
        timezone: String = "UTC",
        policy: String = "skip",
        payload: JsonNode = FakeExecutor.payload("job-a"),
    ) = ScheduleRequest(name, FakeExecutor.ID, payload, JsonNodeFactory.instance.objectNode(), cron, timezone, policy)

    fun create(request: ScheduleRequest = request()): Schedule =
        service.create(SchedulerTestDb.WORKSPACE, SchedulerTestDb.CREATOR, request, null).value

    fun setTime(at: Instant) = settable.set(at)

    /** The one dispatcher tick with the harness's own queue (the task body passes the task's client instead). */
    fun tick(): DispatchReport = dispatcher.tick(queue)

    /** Every `scheduled_tasks` row of the run task. */
    fun runTasks(): List<String> =
        SchedulerTestDb.jdbc.queryForList(
            "SELECT task_instance FROM scheduled_tasks WHERE task_name = :t ORDER BY task_instance",
            mapOf("t" to SchedulerTasks.RUN),
            String::class.java,
        )

    fun runsOf(scheduleId: UUID): List<ScheduleRun> = runs.listForSchedule(SchedulerTestDb.WORKSPACE, scheduleId, LIMIT, 0)

    private companion object {
        const val LIMIT = 1000
    }
}

/** db-scheduler's settable clock, read through `java.time.Clock` by the scheduler's own classes. */
internal class SettableJavaClock(
    private val settable: SettableClock,
) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId?): Clock = this

    override fun instant(): Instant = settable.now()
}

/**
 * A job executor that is NOT a pipeline (record §8): its payload is `{"job": "<name>"}`, it records
 * every call, and its outcomes are scripted per test. [inspect] answers from [outcomes], so a test
 * decides what the "execution" reports — running, a terminal, or absent.
 */
internal class FakeExecutor : JobExecutor {
    override val id: String = ID
    override val payloadSchemaVersion: Int = 1

    val prepared = CopyOnWriteArrayList<UUID>()
    val started = CopyOnWriteArrayList<UUID>()
    val startCount = AtomicInteger()
    val outcomes = ConcurrentHashMap<UUID, ExecutionOutcome>()

    /** Scripted refusal at prepare; null prepares. */
    @Volatile var refuseAtPrepare: Preparation.Refused? = null

    /** Scripted start outcome; null starts (and records the execution as Running). */
    @Volatile var startOutcome: ((Launch) -> StartOutcome)? = null

    /** Targets [visibleTargets] admits for a narrowed viewer; null admits everything. */
    @Volatile var lens: Set<String>? = null

    /**
     * Runs ONCE inside the next [prepare] — after the calling worker has read the run `queued` and
     * before it claims. A test uses it to put a second worker's whole delivery inside that window,
     * which is the race the conditional claim exists for, forced rather than hoped for.
     */
    @Volatile var duringPrepare: ((Admission) -> Unit)? = null

    override fun validate(
        workspaceId: UUID,
        payload: JsonNode,
        parameters: JsonNode,
    ): String {
        val job = payload.path("job").asText()
        if (job.isBlank()) throw ScheduleException(ScheduleErrorCodes.PAYLOAD_INVALID, "no job", mapOf("reason" to "job_missing"))
        if (job == "missing") throw ScheduleException(ScheduleErrorCodes.TARGET_NOT_FOUND, "no such job", mapOf("job" to job))
        return "job:$job"
    }

    override fun prepare(admission: Admission): Preparation {
        prepared += admission.runId
        duringPrepare?.also { duringPrepare = null }?.invoke(admission)
        refuseAtPrepare?.let { return it }
        return Preparation.Prepared(
            JsonNodeFactory.instance
                .objectNode()
                .put("job", admission.payload.path("job").asText())
                .put("version", 1),
        )
    }

    override fun start(launch: Launch): StartOutcome {
        startCount.incrementAndGet()
        started += launch.admission.runId
        val outcome = startOutcome?.invoke(launch) ?: StartOutcome.Started(launch.executionId)
        if (outcome is StartOutcome.Started) outcomes[launch.executionId] = ExecutionOutcome.Running
        return outcome
    }

    override fun inspect(
        workspaceId: UUID,
        executionIds: Collection<UUID>,
    ): Map<UUID, ExecutionOutcome> = executionIds.associateWith { outcomes[it] ?: ExecutionOutcome.Absent }

    override fun visibleTargets(
        viewer: TargetViewer,
        workspaceId: UUID,
        targetRefs: Collection<String>,
    ): Set<String> = lens?.let { admitted -> targetRefs.filter { it in admitted }.toSet() } ?: targetRefs.toSet()

    companion object {
        const val ID = "fake-job"

        fun payload(job: String): JsonNode = JsonNodeFactory.instance.objectNode().put("job", job)
    }
}

/** A capacity gate with a settable number of free slots; leases count what they hold. */
internal class FakeCapacityGate(
    @Volatile var free: Int = Int.MAX_VALUE,
) : CapacityGate {
    val held = AtomicInteger()

    override fun tryAcquire(): CapacityLease? {
        if (free <= held.get()) return null
        held.incrementAndGet()
        return object : CapacityLease {
            private var closed = false

            override fun close() {
                if (!closed) {
                    closed = true
                    held.decrementAndGet()
                }
            }
        }
    }
}

/** A narrowed viewer for the lens tests. */
internal object NarrowedViewer : TargetViewer {
    override val narrowed: Boolean = true
}
