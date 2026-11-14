package co.datapipelines.lake.maintenance.cleanup

import co.datapipelines.lake.LakeTestDb
import co.datapipelines.lake.maintenance.MaintenanceDeclaration
import co.datapipelines.lake.maintenance.MaintenanceOutcome
import co.datapipelines.lake.maintenance.MaintenancePart
import co.datapipelines.lake.maintenance.MaintenanceRun
import co.datapipelines.lake.maintenance.MaintenanceTable
import co.datapipelines.lake.maintenance.MaintenanceWorker
import co.datapipelines.lake.maintenance.PartResult
import co.datapipelines.lake.maintenance.plan.MaintenancePartNames
import co.datapipelines.scheduler.DbSchedulerSystemJobQueue
import co.datapipelines.scheduler.SchedulerAutoConfiguration
import co.datapipelines.scheduler.SchedulerTasks
import com.github.kagkarlsson.scheduler.testhelper.SettableClock
import com.github.kagkarlsson.scheduler.testhelper.TestHelper
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/**
 * #962 D (C14, C14a, C14b): the fixtures the wiring cases share — a table whose cleanup commits keep tags and an expiry
 * (C6's shape), a part ordered after the cleanup that records whether it ran, and the scheduler seam (957's db-scheduler
 * queue over the module's Postgres, with the library's manual scheduler, as 959's `PlanQueueIntegrationTest` builds it).
 */

/** The keep rule of C5 and C6: the end of each of the last two days kept for two days. */
internal const val TWO_DAY_KEEP = """[{"period":"day","keep_for":{"n":2,"unit":"days"}}]"""

private const val OLD_DAYS = 20L
private const val MID_DAYS = 10L
private const val CREATED_DAYS = 3L

/** The three snapshots of a table whose next cleanup commits two keep tags and expires the oldest (C6's figures). */
internal data class KeptAndExpirable(
    val expired: Long,
    val kept: Long,
    val current: Long,
)

/** [table] under the keep profile [profile]: s0 twenty days old (expires), s1 ten days old (tagged), s2 now. */
internal fun CleanupHarness.keptAndExpirable(
    table: MaintenanceTable,
    profile: String,
): KeptAndExpirable {
    val now = m.dbNow()
    createdAt(table, now - Duration.ofDays(CREATED_DAYS))
    keepProfile(table, profile, TWO_DAY_KEEP)
    val (s0, _) = appendAt(table, now - Duration.ofDays(OLD_DAYS))
    val (s1, _) = appendAt(table, now - Duration.ofDays(MID_DAYS))
    val (s2, _) = append(table)
    finalise(table)
    return KeptAndExpirable(s0, s1, s2)
}

/** The tag names of [table] as it stands. */
internal fun CleanupHarness.tags(table: MaintenanceTable): Set<String> =
    metadata(table)
        .refs()
        .filterValues { it.isTag }
        .keys

/** A finished cleanup [hoursAgo] before the catalog's now, as 957 records one (959's `PlanQueueIntegrationTest`). */
internal fun CleanupHarness.cleaned(
    table: MaintenanceTable,
    hoursAgo: Long,
) = jdbc.update(
    "INSERT INTO lake_maintenance_runs (id, table_id, kind, state, deadline_at, parts_json, started_at, ended_at) " +
        "VALUES (gen_random_uuid(), ?, 'maintenance', 'finished', now(), " +
        "CAST('[{\"name\":\"cleanup\",\"outcome\":\"done\",\"attempted\":true}]' AS jsonb), " +
        "now() - make_interval(hours => ?) - interval '1 minute', now() - make_interval(hours => ?))",
    table.id,
    hoursAgo.toInt(),
    hoursAgo.toInt(),
)

/** The table lease of [table] lapses under its holder (a takeover is the same for the remover: its check answers no). */
internal fun CleanupHarness.loseTableLease(table: MaintenanceTable) =
    check(
        jdbc.update(
            "UPDATE lake_leases SET expires_at = now() - interval '1 second' WHERE kind = 'table' AND name = ?",
            table.leaseName,
        ) == 1,
    )

/**
 * The scheduler seam: one real plan enqueues into 957's db-scheduler queue, then the manual scheduler hands every due
 * instance to [worker], the production `lake-maintenance` task. Answers whether [table]'s task was enqueued.
 */
internal fun CleanupHarness.throughScheduler(
    table: MaintenanceTable,
    worker: MaintenanceWorker,
): Boolean {
    LakeTestDb.jdbc.execute("TRUNCATE scheduled_tasks")
    val clock = SettableClock().also { it.set(Instant.now()) }
    val scheduler =
        TestHelper
            .createManualScheduler(TransactionAwareDataSourceProxy(LakeTestDb.dataSource), listOf(SchedulerTasks.keyedTask(worker)))
            .clock(clock)
            .also { it.serializer(SchedulerAutoConfiguration.JSON_SERIALIZER) }
            .build()
    val queue = DbSchedulerSystemJobQueue { scheduler }
    p.queue.enqueues.clear()
    p.pass(p.planner(registered = CleanupHarness.REGISTERED, queue = queue, worker = worker, clock = Clock.systemUTC()))
    val enqueued =
        LakeTestDb.jdbc.queryForObject(
            "SELECT count(*) FROM scheduled_tasks WHERE task_name = ? AND task_instance = ?",
            Int::class.java,
            worker.name,
            table.id.toString(),
        ) == 1
    clock.set(Instant.now().plus(Duration.ofMinutes(1)))
    scheduler.runAnyDueExecutions()
    return enqueued
}

/** A part ordered after the cleanup, expected due: it records whether the loop started it (D2a "later parts"). */
internal class LaterPart : MaintenancePart {
    override val name: String = MaintenancePartNames.MANIFEST_REWRITE
    override val order: Int = MaintenancePartNames.ALL.indexOf(name)
    val ran = AtomicInteger()

    override fun declare(table: MaintenanceTable) = MaintenanceDeclaration(expectsDue = true, scratchBytes = 0)

    override fun dueNow(run: MaintenanceRun): Boolean = true

    override fun run(run: MaintenanceRun): PartResult {
        ran.incrementAndGet()
        return PartResult(MaintenanceOutcome.NOTHING_TO_DO)
    }
}
