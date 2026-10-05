package co.datapipelines.application.dashboards

import co.datapipelines.executor.ExecutedByKeyKind
import co.datapipelines.executor.SlotReservation
import co.datapipelines.pipeline.Pipeline
import co.datapipelines.visualization.ActionScope
import co.datapipelines.visualization.ArtifactRef
import co.datapipelines.visualization.RefreshStatus
import co.datapipelines.visualization.VisualizationBody
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.time.Instant
import java.util.UUID

/** One visualization occurrence a refresh produces: its pinned body, the input→source map, and its own deadline. */
class TargetSpec(
    val name: String,
    val visualization: ArtifactRef,
    val body: VisualizationBody,
    /** Visualization input name → the source that feeds it (the occurrence's `inputs`). */
    val sourceOfInput: Map<String, String>,
    /** The occurrence's `timeout_seconds`, or null for the refresh's. */
    val timeoutSeconds: Int?,
)

/** One distinct execution to run: its resolved pipeline release and the parameters it launches with. */
class InvocationSpec(
    val id: String,
    val pipelineId: UUID,
    val pipelineVersion: Int,
    val pipeline: Pipeline,
    val parameters: Map<String, JsonNode>,
)

/**
 * Everything the engine needs to run ONE admitted refresh — resolved by the caller, so the engine touches no
 * repository: the plan, the pinned bodies, the resolved executables, the reserved slots and the deadline.
 * [reservation] holds exactly one slot per invocation; the engine hands one to each and closes the rest. It is null
 * only when the plan has no invocation (every target reads no source), where nothing is reserved.
 */
@Suppress("LongParameterList") // a record of everything one admitted refresh needs, resolved by the caller
class RefreshJob(
    val refreshId: UUID,
    val workspaceId: UUID,
    val userId: UUID,
    val executedByKeyKind: ExecutedByKeyKind?,
    /** The served dashboard and version, the scope asked for and the client instance — attribution for the audit row. */
    val dashboardId: UUID,
    val dashboardVersion: Int,
    val scope: ActionScope,
    val instanceId: UUID,
    val plan: RefreshPlan,
    val targets: Map<String, TargetSpec>,
    val invocations: Map<String, InvocationSpec>,
    val deadlineSeconds: Int,
    val reservation: SlotReservation?,
    val startedAt: Instant,
    /** Only a session's explicitly selected draft dashboard may execute draft dependencies. */
    val allowDraftDependencies: Boolean = false,
)

/** How one target ended. */
sealed interface TargetOutcome {
    val wire: String

    data class Ok(
        val rows: Int,
        val bytes: Long,
    ) : TargetOutcome {
        override val wire get() = "ok"
    }

    data object NoData : TargetOutcome {
        override val wire get() = "no-data"
    }

    data class Error(
        val stage: String,
        val code: String,
        /** A safe, author-named fact (a contract column) — never a driver message. */
        val detail: String? = null,
    ) : TargetOutcome {
        override val wire get() = "error"
    }

    data object Aborted : TargetOutcome {
        override val wire get() = "abort"
    }
}

/** What the engine hands back: the terminal status, each target's outcome and the summary written to the row. */
class RefreshResult(
    val status: RefreshStatus,
    val targets: Map<String, TargetOutcome>,
    val summary: ObjectNode,
)

/** The table-shaped output of a transform, or the code it refused with. */
sealed interface TransformOutcome {
    data class Rows(
        val rows: List<Map<String, Any?>>,
    ) : TransformOutcome

    data class Refused(
        val code: String,
    ) : TransformOutcome
}

/**
 * The dashboard's transform seam (spec §9 step 6): [tables] maps each CONTRACT input name to the rows of the source
 * feeding it. Blocking — the engine calls it on its blocking dispatcher, never a request thread. `web` implements it
 * over `TemplateEvaluateService` (the same bounded pool and type gate as authoring).
 */
fun interface DashboardTransformer {
    fun transform(
        workspaceId: UUID,
        template: ArtifactRef,
        tables: Map<String, List<Map<String, Any?>>>,
        now: Instant,
    ): TransformOutcome

    /** Draft preview keeps the same transform contract; implementations may admit live draft templates. */
    fun transformDraft(
        workspaceId: UUID,
        template: ArtifactRef,
        tables: Map<String, List<Map<String, Any?>>>,
        now: Instant,
    ): TransformOutcome = transform(workspaceId, template, tables, now)
}

/** The refresh's persistence: the execution link and the terminal write. Blocking, called by the engine on its dispatcher. */
interface RefreshLedger {
    /** Links [source] to [executionId] — called from the launcher's `onRecorded`, before the execution's first event. */
    fun link(
        refreshId: UUID,
        source: String,
        executionId: UUID,
        shared: Boolean,
    )

    /** Writes the terminal state of a RUNNING refresh; false when it was no longer RUNNING. */
    fun finish(
        refreshId: UUID,
        status: RefreshStatus,
        summary: ObjectNode,
    ): Boolean
}

/** The one `dashboard.refresh` audit row per refresh (spec §18 premise 1) — awaited, at the refresh's end. */
fun interface RefreshAudit {
    fun record(
        job: RefreshJob,
        result: RefreshResult,
    )
}

/** Has an abort of the refresh been asked for — by the local trigger or by another instance's flag? */
fun interface AbortSignal {
    fun requested(refreshId: UUID): Boolean
}

/** Cancels one running execution (the executor's own cancel path); used when an abort or a deadline stops a source. */
fun interface ExecutionCanceller {
    fun cancel(executionId: UUID)
}

/** The engine's five collaborators, bundled. */
class RefreshPorts(
    val events: RefreshEvents,
    val ledger: RefreshLedger,
    val audit: RefreshAudit,
    val abort: AbortSignal,
    val canceller: ExecutionCanceller,
)
