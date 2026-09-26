package co.datapipelines.scheduler

import com.fasterxml.jackson.databind.JsonNode
import java.time.Instant
import java.util.UUID

/**
 * **The executor port** (scheduler design revision §5.3, §5.4). The scheduler dispatches a run to
 * the executor registered under the schedule's `executor_id` and never interprets what it does:
 * payloads, parameters and prepared snapshots are opaque JSON, and every outcome comes back as one
 * of the standardized values below — the scheduler never parses an executor's error-code strings
 * to decide policy (§5.3).
 *
 * The first and only production executor is the pipeline adapter in `web` (id `pipeline`);
 * reports, and any later job kind, register their own. A FAKE implementation in this module's
 * own suite proves the scheduler needs no pipeline class (record §8).
 *
 * ## Threading and transactions
 * [validate], [prepare] and [inspect] are pure reads. [start] runs AFTER the claim committed and
 * outside any transaction; it may block only until the execution's own record exists (the
 * fail-closed rule, A14) and must return before the execution finishes (the run task never holds a
 * thread for a pipeline's runtime, record §2.2).
 */
interface JobExecutor {
    /** The registered id — `schedules.executor_id`. Allowlisted: nothing else can be named (record §5). */
    val id: String

    /** The payload schema version this executor writes and accepts. */
    val payloadSchemaVersion: Int

    /**
     * At save (and at unblock): is this payload + parameters runnable in [workspaceId]? Returns the
     * generic target reference to store (B15), or throws a catalogued `DatapipelinesException` the
     * surface renders as the refusal.
     */
    fun validate(
        workspaceId: UUID,
        payload: JsonNode,
        parameters: JsonNode,
    ): String

    /**
     * At admission, before the claim: resolve what will run and freeze it. Executes nothing and
     * touches no datasource. A [Preparation.Refused] is a definitive not-started (record §2).
     */
    fun prepare(admission: Admission): Preparation

    /**
     * After the committed claim: start the execution under [Launch.executionId] and return once it
     * is durably recorded. [Launch.capacity] passes to the execution on [StartOutcome.Started]; on
     * [StartOutcome.NotStarted] or a thrown exception the scheduler closes it.
     *
     * Throwing means "the scheduler cannot know whether work began" — the run becomes `unknown`.
     * Return [StartOutcome.NotStarted] only when no node can have run (§2.1, A14).
     */
    fun start(launch: Launch): StartOutcome

    /** The reconciler's batch read: where each execution reference stands (R6's table, record §7.1). */
    fun inspect(
        workspaceId: UUID,
        executionIds: Collection<UUID>,
    ): Map<UUID, ExecutionOutcome>

    /**
     * Which of [targetRefs] the [viewer] may see (the promoter lens on `schedule.read`, record §4).
     * The default admits everything — an executor with no lens concept narrows nothing.
     */
    fun visibleTargets(
        viewer: TargetViewer,
        workspaceId: UUID,
        targetRefs: Collection<String>,
    ): Set<String> = targetRefs.toSet()
}

/**
 * Who is reading, as far as an executor's visibility rules need to know — opaque to the scheduler.
 * The surface supplies it (in `web`, the signed-in principal); [EVERYONE] is the unnarrowed view.
 */
interface TargetViewer {
    /** False when no executor narrowing can apply to this viewer — the scheduler then skips the call. */
    val narrowed: Boolean

    companion object {
        val EVERYONE: TargetViewer =
            object : TargetViewer {
                override val narrowed: Boolean = false
            }
    }
}

/** What an executor is asked to admit: the run's frozen context (record §5.1), never a live schedule read. */
data class Admission(
    val runId: UUID,
    val workspaceId: UUID,
    val scheduleId: UUID?,
    val origin: RunOrigin,
    val scheduledAt: Instant?,
    val referenceAt: Instant,
    val referenceTimezone: String,
    val payload: JsonNode,
    val parameters: JsonNode,
)

/** The outcome of [JobExecutor.prepare]. */
sealed interface Preparation {
    /** Frozen: the executor-owned snapshot stored on the run row in the claim (R5), never read back by the scheduler. */
    data class Prepared(
        val snapshot: JsonNode,
    ) : Preparation

    /** Definitively not started; [block] asks the scheduler to block the schedule under [reason]. */
    data class Refused(
        val reason: String,
        val block: Boolean,
        val message: String,
    ) : Preparation
}

/** What [JobExecutor.start] launches: the admission, the minted reference, the snapshot and the held capacity. */
data class Launch(
    val admission: Admission,
    val executionId: UUID,
    val snapshot: JsonNode,
    val capacity: CapacityLease,
)

/** The outcome of [JobExecutor.start]. */
sealed interface StartOutcome {
    /** The execution's record exists under [executionId]; it runs on without the scheduler. */
    data class Started(
        val executionId: UUID,
    ) : StartOutcome

    /** No node ran and none will. [block] asks the scheduler to block the schedule under [reason]. */
    data class NotStarted(
        val reason: String,
        val block: Boolean,
        val message: String,
    ) : StartOutcome
}

/** Where one execution stands, for the reconciler (R6). */
sealed interface ExecutionOutcome {
    /** Still running (the executor's own liveness — heartbeat and sweeper — decides when it is not). */
    data object Running : ExecutionOutcome

    /** Terminal, mapped by the executor onto a run state and reason (record §7.1). */
    data class Finished(
        val state: RunState,
        val reason: String?,
    ) : ExecutionOutcome

    /** No record under that reference. */
    data object Absent : ExecutionOutcome
}

/**
 * The allowlist of executors (record §5): a schedule may name only a registered id, and two
 * executors cannot share one.
 */
class JobExecutors(
    executors: List<JobExecutor>,
) {
    private val byId: Map<String, JobExecutor> =
        executors.associateBy { it.id }.also {
            require(it.size == executors.size) { "two executors registered under one id: ${executors.map(JobExecutor::id)}" }
        }

    /** The registered ids, sorted. */
    val ids: List<String> get() = byId.keys.sorted()

    /** The executor registered under [id], or `schedule.validation.executor_unknown`. */
    fun require(id: String): JobExecutor =
        byId[id] ?: throw ScheduleException(
            ScheduleErrorCodes.EXECUTOR_UNKNOWN,
            "No executor is registered as '${id.take(MAX_ECHO)}'.",
            mapOf("executor" to id.take(MAX_ECHO), "supported" to ids),
        )

    /** The executor registered under [id], or null — the worker's view, where a missing one is a refusal, not a 400. */
    fun find(id: String): JobExecutor? = byId[id]

    private companion object {
        const val MAX_ECHO = 64
    }
}

/**
 * The acquire-before-claim port (R4, A6): the scheduler takes capacity BEFORE it claims a start, so
 * a refusal is a definitive "never started" and retries within the lateness window. `web`
 * implements it over `dag`'s `ExecutionSlots` with the system identity's budget
 * (`max-concurrent-runs`) — the lease it hands out is the slot the execution runs under.
 */
interface CapacityGate {
    /** A held lease, or null when this instance has no scheduled-run capacity left right now. */
    fun tryAcquire(): CapacityLease?
}

/**
 * One held unit of capacity. [close] releases it and is idempotent: the execution that took it
 * over releases it at its end, and a scheduler path that did not hand it over closes it too.
 */
interface CapacityLease : AutoCloseable {
    override fun close()
}
