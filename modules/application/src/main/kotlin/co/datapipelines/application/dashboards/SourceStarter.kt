package co.datapipelines.application.dashboards

import co.datapipelines.executor.DirectResultSink
import co.datapipelines.executor.ExecutedByKeyKind
import co.datapipelines.executor.SlotLease
import co.datapipelines.pipeline.Pipeline
import com.fasterxml.jackson.databind.JsonNode
import java.util.UUID

/**
 * The launch seam of a dashboard refresh (the implementation spec's §9 step 4, §18 premise 4): the runtime decides WHAT
 * to start and reads the outcome; `web` implements HOW — over `RecordingExecutionRunner.run(…, ExecutionTrigger.DASHBOARD,
 * failClosed = true)`, the seam MCP, endpoints and schedules already use, so the emitter and the persistence path are
 * reused and never copied. Declared here because `application` may not depend on `web`.
 *
 * One call runs ONE source's execution to its end. [run] invokes `onRecorded` with the execution id right after the
 * RUNNING row is inserted and BEFORE the first frame or event row, which is where the runtime writes the
 * `dashboard_refresh_executions` link: "linked before its first event" then holds by construction.
 */
interface SourceStarter {
    /**
     * Runs [launch] to completion and answers how it ended. Cancellation of the calling coroutine cancels the execution;
     * a failure is an outcome, never an exception (the runtime turns each into `source_failed`).
     */
    suspend fun run(
        launch: SourceLaunch,
        onRecorded: (UUID) -> Unit,
    ): SourceOutcome
}

/**
 * One source's execution, fully formed. The delegated act (D50): [userId] is the refreshing principal — the run's
 * `executed_by` — and nothing here consults that principal's `pipeline.execute`; `dashboard.execute` was the one
 * authorization event, judged at the route.
 */
data class SourceLaunch(
    val refreshId: UUID,
    val sourceName: String,
    val workspaceId: UUID,
    val userId: UUID,
    val executedByKeyKind: ExecutedByKeyKind?,
    val pipelineId: UUID,
    val pipelineVersion: Int,
    val pipeline: Pipeline,
    /** The resolved inputs after outgoing overrides, as the pipeline's own binder will read them. */
    val parameters: Map<String, JsonNode>,
    /** The bounded collector: the caller node's rows stream here and never reach the result store. */
    val sink: DirectResultSink,
    /** One of the refresh's reserved instance slots; the execution releases it at its end. */
    val slot: SlotLease,
    /** Lifecycle policy inherited from the explicitly selected dashboard version. */
    val allowDraftDependencies: Boolean = false,
)

/** How one source's execution ended. [executionId] is null only when the run was refused before any execution existed. */
sealed interface SourceOutcome {
    val executionId: UUID?

    /** The execution succeeded; its rows are in the sink. */
    data class Succeeded(
        override val executionId: UUID,
    ) : SourceOutcome

    /**
     * The execution (or its launch) failed. [code] is a catalogued code; [detail] is safe to stream to a viewer: it never
     * carries a driver or datasource message (those stay on the execution's own events, which only its owner and admins
     * read).
     */
    data class Failed(
        override val executionId: UUID?,
        val code: String,
    ) : SourceOutcome

    /** The execution was cancelled — by the refresh's abort, its deadline, or shutdown. */
    data class Aborted(
        override val executionId: UUID?,
    ) : SourceOutcome
}
