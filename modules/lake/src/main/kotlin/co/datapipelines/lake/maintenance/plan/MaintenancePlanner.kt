package co.datapipelines.lake.maintenance.plan

import co.datapipelines.persistence.FailureShape
import co.datapipelines.scheduler.KeyedSystemJob
import co.datapipelines.scheduler.RecurringSystemJob
import co.datapipelines.scheduler.SystemJobQueue
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** The planner's four bounds (brief 959 D1; `datapipelines.lake.maintenance.plan-*`). */
data class PlanBounds(
    val interval: Duration,
    val pageSize: Int,
    val tableTimeout: Duration,
    val maxAbandonedReads: Int,
)

/**
 * `lake-maintenance-planner` (brief 959; record §11a call 34): a recurring system job, every `plan-interval` after the
 * previous plan ENDED, on one instance at a time cluster-wide. One plan:
 *
 * 1. deletes the usage rows older than the retention (one statement);
 * 2. measures the page (D2): the `plan-page-size` tables whose last attempt is oldest, one at a time on the planner's
 *    own readers, each under `plan-table-timeout`; at `plan-max-abandoned-reads` outstanding it measures nothing more;
 * 3. decides from the stored rows in one statement (D4, D5) with [MaintenanceDue], on the catalog's clock;
 * 4. enqueues or moves one `lake-maintenance` task per table with work, rank reaching the scheduler as the due time
 *    `plan time + rank ms` (D7): a new task is enqueued, a queued one moved, a running one left; each its own statement;
 * 5. overwrites the plan row.
 *
 * It reads catalog rows and manifests only, takes no lease and no maintenance slot, and writes only its three tables
 * and the scheduler's queue.
 */
@Suppress("LongParameterList")
class MaintenancePlanner(
    private val bounds: PlanBounds,
    private val repository: PlanRepository,
    private val measurer: TableMeasurer,
    private val readers: PlanReaders,
    private val due: MaintenanceDue,
    private val queue: () -> SystemJobQueue,
    private val worker: KeyedSystemJob,
    private val registered: () -> Set<String>,
    private val clock: Clock,
    private val hooks: PlanRaceHooks = PlanRaceHooks.NONE,
) : RecurringSystemJob {
    override val name: String = NAME
    override val fixedDelay: Duration = bounds.interval

    override fun run() {
        repository.pruneUsage()
        val measured = measurePage()
        val plan = due.plan(repository.decisionInputs(), registered(), repository.catalogNow())
        val plannedAt = clock.instant()
        var enqueued = 0
        var moved = 0
        plan.order.forEachIndexed { rank, tableId ->
            val at = plannedAt.plusMillis(rank.toLong())
            when {
                queue().enqueueIfAbsent(worker, tableId.toString(), at) -> enqueued++
                queue().move(worker, tableId.toString(), at) -> moved++
                else -> Unit // running (or just finished): left as it is
            }
        }
        val counts =
            PlanCounts(
                tablesWithWork = plan.order.size,
                tasksEnqueued = enqueued,
                tasksMoved = moved,
                tasksStillQueued = moved,
                tablesMeasured = measured.measured,
                tablesNotMeasured = measured.notMeasured,
                tablesStale = plan.tables.count { it.stale },
                tablesRuleSilent = plan.tables.count { it.silent },
            )
        repository.writePlan(plannedAt, counts)
        LOG.info(
            "event=lake.maintenance.planned tables_with_work={} tasks_enqueued={} tasks_moved={} tasks_still_queued={} " +
                "tables_measured={} tables_not_measured={} tables_stale={} tables_rule_silent={}",
            counts.tablesWithWork,
            counts.tasksEnqueued,
            counts.tasksMoved,
            counts.tasksStillQueued,
            counts.tablesMeasured,
            counts.tablesNotMeasured,
            counts.tablesStale,
            counts.tablesRuleSilent,
        )
    }

    private data class PageResult(
        val measured: Int,
        val notMeasured: Int,
    )

    private enum class Measured { YES, NO }

    private fun measurePage(): PageResult {
        val page = repository.page(bounds.pageSize, readers.outstandingTables())
        var measured = 0
        var notMeasured = 0
        for ((index, target) in page.withIndex()) {
            // At the abandoned-read limit (D2) or when the pass is interrupted, the rest of the page is not attempted.
            val exhausted = readers.outstanding() >= bounds.maxAbandonedReads
            if (exhausted || Thread.currentThread().isInterrupted) {
                val rest = page.size - index
                notMeasured += rest
                if (exhausted) {
                    LOG.warn("event=lake.maintenance.plan_reads_exhausted not_measured={} abandoned_reads={}", rest, readers.outstanding())
                }
                break
            }
            if (measureOne(target) == Measured.YES) measured++ else notMeasured++
        }
        return PageResult(measured, notMeasured)
    }

    /** One table's attempt: stamped, read on a reader under the bound, published by exactly one winner (D2). */
    private fun measureOne(target: PlanTarget): Measured {
        val attempt = PlanAttempt(target.tableId, UUID.randomUUID())
        val profile = repository.startAttempt(target.tableId, attempt.id) ?: return Measured.NO
        val reader = readers.reader()
        val started = System.nanoTime()
        val future = reader.submit { read(reader, target, profile, attempt) }
        return try {
            if (future.get(bounds.tableTimeout.toNanos(), TimeUnit.NANOSECONDS)) Measured.YES else Measured.NO
        } catch (_: TimeoutException) {
            timedOut(target, reader, attempt, future, started)
        } catch (failure: ExecutionException) {
            // The reader won and its database write threw (a failed read or client construction is stamped by the
            // reader, never thrown): the attempt keeps whatever the row held.
            LOG.warn(
                "event=lake.maintenance.plan_write_failed table_id={} cause={}",
                target.tableId,
                FailureShape.cause(failure.cause ?: failure),
            )
            Measured.NO
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            timedOut(target, reader, attempt, future, started)
        }
    }

    private fun timedOut(
        target: PlanTarget,
        reader: PlanReaders.Reader,
        attempt: PlanAttempt,
        future: Future<Boolean>,
        started: Long,
    ): Measured {
        hooks.beforePassSet(target.tableId)
        val won = attempt.state.compareAndSet(AttemptState.RUNNING, AttemptState.ABANDONED)
        hooks.afterPassSet(target.tableId)
        if (!won) {
            // The reader set `publishing` a moment before the bound: wait for its one bounded write.
            return try {
                if (future.get()) Measured.YES else Measured.NO
            } catch (failure: ExecutionException) {
                LOG.warn(
                    "event=lake.maintenance.plan_write_failed table_id={} cause={}",
                    target.tableId,
                    FailureShape.cause(failure.cause ?: failure),
                )
                Measured.NO
            }
        }
        // Retired and tracked FIRST: the abandoned reader stays counted, its table excluded and the reader never reused,
        // whatever the stamp below does. A stamp that throws reaches the scheduler boundary as it was thrown.
        readers.retire(reader, target.tableId)
        LOG.warn(
            "event=lake.maintenance.plan_read_abandoned table_id={} elapsed_ms={} abandoned_reads={}",
            target.tableId,
            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
            readers.outstanding(),
        )
        repository.fail(target.tableId, attempt.id, PlanErrors.TIMEOUT)
        return Measured.NO
    }

    /**
     * On the reader's thread: the client (built on the reader's first use) and the measurement, then `running` →
     * `publishing` and only then the write (figures, or the failure's code). A client that cannot be built is a failed
     * read like any other. Finding `abandoned`, it writes nothing, closes whatever client it holds and lets its thread
     * end. The compare-and-set runs in a `finally`: even a throwable the measurement does not catch settles the attempt,
     * so an abandoned reader always ends and frees its place.
     */
    @Suppress("TooGenericExceptionCaught") // a failed read is stamped with its code; nothing is rethrown into the pass
    private fun read(
        reader: PlanReaders.Reader,
        target: PlanTarget,
        profile: ProfileCopy,
        attempt: PlanAttempt,
    ): Boolean {
        var result: Result<TableFigures>? = null
        var won = false
        try {
            result =
                try {
                    Result.success(measurer.measure(reader.client(), target, profile.profile))
                } catch (failure: Exception) {
                    Result.failure(failure)
                }
        } finally {
            hooks.beforeReaderSet(target.tableId)
            won = attempt.state.compareAndSet(AttemptState.RUNNING, AttemptState.PUBLISHING)
            hooks.afterReaderSet(target.tableId)
            if (!won) {
                reader.endAbandoned()
                LOG.info("event=lake.maintenance.plan_read_ended table_id={} published=false", target.tableId)
            }
        }
        if (!won) return false
        val failure = checkNotNull(result).exceptionOrNull()
        if (failure != null) {
            LOG.warn("event=lake.maintenance.plan_measure_failed table_id={} cause={}", target.tableId, FailureShape.cause(failure))
            repository.fail(target.tableId, attempt.id, PlanErrors.codeOf(failure))
            return false
        }
        return repository.publish(target.tableId, attempt.id, result.getOrThrow(), profile)
    }

    companion object {
        const val NAME = "lake-maintenance-planner"
        private val LOG = LoggerFactory.getLogger(MaintenancePlanner::class.java)
    }
}
