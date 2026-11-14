package co.datapipelines.scheduler

import java.time.Duration
import java.time.Instant

/**
 * A system job with one durable task instance per KEY (#957 D1; the P6 lane cut's L2): another module's work that runs
 * on this module's queue without knowing db-scheduler. Declare a bean of this type and the scheduler's auto-configuration
 * builds its task ([SchedulerTasks.keyedTask]); enqueue an instance through [SystemJobQueue].
 *
 * The job runs on the scheduler thread that took the instance. [run] answers what happens to the instance: removed
 * ([SystemJobOutcome.Done]) or rescheduled, the SAME instance, to an instant ([SystemJobOutcome.AgainAt]). A throw is
 * the library's failure path (retried after its default interval), so a job that must not be retried catches its own
 * failures and answers [SystemJobOutcome.Done].
 */
interface KeyedSystemJob {
    /** The task name: unique among the scheduler's tasks, stable across releases (it is a row's `task_name`). */
    val name: String

    /** How many instances exist, as the entry inventory's schedule cell says it (auth.md §8.6.1), e.g. "one instance per table". */
    val instances: String

    /** Runs the instance for [key]. */
    fun run(key: String): SystemJobOutcome
}

/** What a [KeyedSystemJob] answers for its instance. */
sealed interface SystemJobOutcome {
    /** The instance is complete and removed. */
    data object Done : SystemJobOutcome

    /** The same instance comes again at [at]. */
    data class AgainAt(
        val at: Instant,
    ) : SystemJobOutcome
}

/**
 * A system job that runs every [fixedDelay] after its previous run ENDED (#957 D1), on one instance at a time
 * cluster-wide: the task has one durable row, and a run still going when the next would be due makes that one skip.
 */
interface RecurringSystemJob {
    /** The task name: unique among the scheduler's tasks, stable across releases. */
    val name: String

    /** The wait between the end of one run and the start of the next. */
    val fixedDelay: Duration

    fun run()
}

/**
 * The durable queue of [KeyedSystemJob] instances (#957 D1). The ONE way another module enqueues or moves an
 * instance; db-scheduler stays behind it (scheduler design revision §6.2).
 */
interface SystemJobQueue {
    /** Enqueues [job]'s instance for [key] at [at] unless one exists; answers whether this call enqueued it. */
    fun enqueueIfAbsent(
        job: KeyedSystemJob,
        key: String,
        at: Instant,
    ): Boolean

    /**
     * Moves the QUEUED instance of [job] for [key] to [at]; answers false and changes nothing when the instance is
     * running (picked by a scheduler) or absent.
     */
    fun move(
        job: KeyedSystemJob,
        key: String,
        at: Instant,
    ): Boolean
}
