package co.datapipelines.scheduler

import com.github.kagkarlsson.scheduler.Scheduler
import com.github.kagkarlsson.scheduler.boot.config.DbSchedulerStarter
import com.github.kagkarlsson.scheduler.task.CompletionHandler
import com.github.kagkarlsson.scheduler.task.DeadExecutionHandler
import com.github.kagkarlsson.scheduler.task.ExecutionContext
import com.github.kagkarlsson.scheduler.task.FailureHandler
import com.github.kagkarlsson.scheduler.task.TaskDescriptor
import com.github.kagkarlsson.scheduler.task.TaskInstance
import com.github.kagkarlsson.scheduler.task.helper.CustomTask
import com.github.kagkarlsson.scheduler.task.helper.RecurringTask
import com.github.kagkarlsson.scheduler.task.helper.Tasks
import com.github.kagkarlsson.scheduler.task.schedule.FixedDelay
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.ApplicationListener
import java.util.UUID
import java.util.function.Function

/**
 * The scheduler's db-scheduler tasks (scheduler design revision §2.2; the notifier since #442b; the system jobs'
 * tasks since #957) — the only place a db-scheduler task is defined. Task data is a run id or a system job's key
 * string at most; the JSON serializer ([SchedulerAutoConfiguration]) writes it, never Java serialization (A8).
 */
object SchedulerTasks {
    const val DISPATCHER = "schedule-dispatcher"
    const val RECONCILER = "schedule-reconciler"
    const val RUN = "schedule-run"
    const val NOTIFIER = "schedule-notifier"

    /** The run task's descriptor: instance id and data are both the run id. */
    val RUN_TASK: TaskDescriptor<String> = TaskDescriptor.of(RUN, String::class.java)

    /**
     * `schedule-run` — one delivery of one run ([ScheduledRunWorker]). A capacity wait or a closed
     * gate reschedules the SAME task instance (the run stays `queued`); everything else completes
     * it. The library's failure handler (retry after 5 minutes) and dead-execution revival stay
     * the defaults: both are safe because the worker's claim is conditional (record §2.2).
     */
    fun runTask(worker: ScheduledRunWorker): CustomTask<String> =
        Tasks.custom(RUN_TASK).execute { instance, _ ->
            when (val decision = worker.handle(UUID.fromString(instance.data ?: instance.id))) {
                WorkerDecision.Done -> CompletionHandler.OnCompleteRemove()
                is WorkerDecision.RetryAt -> CompletionHandler { complete, operations -> operations.reschedule(complete, decision.at) }
            }
        }

    /** `schedule-dispatcher` — R1's ONE dispatcher; one instance cluster-wide, fixed delay. */
    fun dispatcherTask(
        dispatcher: ScheduleDispatcher,
        properties: SchedulerProperties,
    ): RecurringTask<Void> =
        Tasks.recurring(DISPATCHER, FixedDelay.of(properties.tickInterval)).execute { _, context ->
            // The client db-scheduler hands the task — the enqueue joins the tick's transaction
            // through the starter's TransactionAwareDataSourceProxy (record §2.1, spike 1).
            dispatcher.tick(DbSchedulerRunQueue(context.schedulerClient, RUN_TASK))
        }

    /** `schedule-reconciler` — maps execution terminals onto runs (R6); fixed delay. */
    fun reconcilerTask(
        reconciler: RunReconciler,
        properties: SchedulerProperties,
    ): RecurringTask<Void> = Tasks.recurring(RECONCILER, FixedDelay.of(properties.tickInterval)).execute { _, _ -> reconciler.tick() }

    /**
     * `schedule-notifier` — drains the notification outbox ([ScheduleNotifier], #442b); fixed delay,
     * one instance cluster-wide. It runs only where db-scheduler runs: an API-mode instance sends nothing.
     */
    fun notifierTask(
        notifier: ScheduleNotifier,
        properties: SchedulerProperties,
    ): RecurringTask<Void> = Tasks.recurring(NOTIFIER, FixedDelay.of(properties.tickInterval)).execute { _, _ -> notifier.tick() }

    /** #957 D1 — a [KeyedSystemJob]'s task: one instance per key, the key as both the instance id and the data. */
    fun keyedTask(job: KeyedSystemJob): KeyedSystemTask = KeyedSystemTask(job)

    /**
     * #957 D1 — a [RecurringSystemJob]'s task: one durable row, so one instance at a time cluster-wide; the next run is
     * due [RecurringSystemJob.fixedDelay] after the previous one ended, so a run still going skips the next.
     */
    fun recurringTask(job: RecurringSystemJob): RecurringTask<Void> =
        Tasks.recurring(job.name, FixedDelay.of(job.fixedDelay)).execute { _, _ -> job.run() }
}

/**
 * #957 D1 — the task of one [KeyedSystemJob]. The library's defaults of `Tasks.custom`, as the run task has them: no
 * start-up instance, failure retried after `Tasks.DEFAULT_RETRY_INTERVAL`, a dead execution revived, the default
 * priority. [instances] is the job's own sentence for the entry inventory, read there by reflection.
 */
class KeyedSystemTask internal constructor(
    private val job: KeyedSystemJob,
) : CustomTask<String>(
        job.name,
        String::class.java,
        null,
        Function.identity(),
        FailureHandler.OnFailureRetryLater(Tasks.DEFAULT_RETRY_INTERVAL),
        DeadExecutionHandler.ReviveDeadExecution(),
        DEFAULT_PRIORITY,
    ) {
    /** The entry inventory's schedule cell for this one-time task (auth.md §8.6.1). */
    val instances: String
        get() = job.instances

    override fun execute(
        taskInstance: TaskInstance<String>,
        executionContext: ExecutionContext,
    ): CompletionHandler<String> =
        when (val outcome = job.run(taskInstance.data ?: taskInstance.id)) {
            SystemJobOutcome.Done -> CompletionHandler.OnCompleteRemove()
            is SystemJobOutcome.AgainAt -> CompletionHandler { complete, operations -> operations.reschedule(complete, outcome.at) }
        }
}

/**
 * Starts db-scheduler when — and only where — this instance dispatches (record §1, A19). Replaces
 * the starter's own starter bean (it is `@ConditionalOnMissingBean`): the starter's default,
 * `ImmediateStart`, would start polling the moment the bean exists; this one waits for the
 * application to be ready, and in API mode (`datapipelines.scheduler.enabled=false`) never starts
 * it at all — the `Scheduler` bean stays, because it is also the client REST enqueues through.
 */
class SchedulerStartGate(
    private val scheduler: Scheduler,
    private val properties: SchedulerProperties,
) : DbSchedulerStarter,
    ApplicationListener<ApplicationReadyEvent> {
    override fun onApplicationEvent(event: ApplicationReadyEvent) {
        doStart()
    }

    override fun doStart() {
        if (!properties.enabled) {
            LOG.info("event=scheduler.api_mode message=\"datapipelines.scheduler.enabled=false: this instance never dispatches\"")
            return
        }
        val state = scheduler.schedulerState
        if (state.isStarted || state.isShuttingDown) return
        scheduler.start()
        LOG.info("event=scheduler.started threads={} tick_seconds={}", properties.threads, properties.tickIntervalSeconds)
    }

    private companion object {
        private val LOG = LoggerFactory.getLogger(SchedulerStartGate::class.java)
    }
}
