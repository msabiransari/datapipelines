package co.datapipelines.scheduler

import com.github.kagkarlsson.scheduler.Scheduler
import com.github.kagkarlsson.scheduler.boot.config.DbSchedulerStarter
import com.github.kagkarlsson.scheduler.task.CompletionHandler
import com.github.kagkarlsson.scheduler.task.TaskDescriptor
import com.github.kagkarlsson.scheduler.task.helper.CustomTask
import com.github.kagkarlsson.scheduler.task.helper.RecurringTask
import com.github.kagkarlsson.scheduler.task.helper.Tasks
import com.github.kagkarlsson.scheduler.task.schedule.FixedDelay
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.ApplicationListener
import java.util.UUID

/**
 * The scheduler's three db-scheduler tasks (scheduler design revision §2.2) — the only place a
 * db-scheduler task is defined. Task data is a run id string at most; the JSON serializer
 * ([SchedulerAutoConfiguration]) writes it, never Java serialization (A8).
 */
object SchedulerTasks {
    const val DISPATCHER = "schedule-dispatcher"
    const val RECONCILER = "schedule-reconciler"
    const val RUN = "schedule-run"

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
