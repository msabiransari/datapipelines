package co.datapipelines.application.dashboards

import java.util.UUID

/**
 * The frames of a refresh stream (the implementation spec's §8.3): `event:` name + `data:` JSON. The engine emits
 * these to a [RefreshEvents] sink and never learns whether anyone is listening — a vanished client is the stream
 * registry's business (the disconnect grace aborts the refresh), not the engine's.
 *
 * [payload] is built from plain values only. It never carries a row, a selection, a driver message or a SQL text:
 * `source_failed` names a CODE, and a visualization's data is the resolved bindings alone (spec §8.3).
 */
sealed interface RefreshEvent {
    /** The SSE `event:` name (the frame's, not a target's — two frames carry a `name` of their own). */
    val eventName: String

    fun payload(): Map<String, Any?>

    /** First frame: what the refresh will run and when it must end. */
    data class Started(
        val refreshId: UUID,
        val targets: List<String>,
        val sources: List<SourceRef>,
        val deadlineAt: String,
    ) : RefreshEvent {
        override val eventName get() = "refresh_started"

        override fun payload() =
            mapOf(
                "refresh_id" to refreshId.toString(),
                "targets" to targets,
                "sources" to sources.map { mapOf("name" to it.name, "shared" to it.shared) },
                "deadline_at" to deadlineAt,
            )
    }

    /** A source's execution exists (RECORDED and LINKED): the pane links [executionId] to the execution's own events. */
    data class SourceStarted(
        val refreshId: UUID,
        val source: String,
        val executionId: UUID,
    ) : RefreshEvent {
        override val eventName get() = "source_started"

        override fun payload() = mapOf("refresh_id" to refreshId.toString(), "source" to source, "execution_id" to executionId.toString())
    }

    data class SourceCompleted(
        val refreshId: UUID,
        val source: String,
        val executionId: UUID,
        val rows: Int,
        val bytes: Long,
    ) : RefreshEvent {
        override val eventName get() = "source_completed"

        override fun payload() =
            mapOf(
                "refresh_id" to refreshId.toString(),
                "source" to source,
                "execution_id" to executionId.toString(),
                "rows" to rows,
                "bytes" to bytes,
            )
    }

    data class SourceFailed(
        val refreshId: UUID,
        val source: String,
        val executionId: UUID?,
        val code: String,
        val message: String,
    ) : RefreshEvent {
        override val eventName get() = "source_failed"

        override fun payload() =
            mapOf(
                "refresh_id" to refreshId.toString(),
                "source" to source,
                "execution_id" to executionId?.toString(),
                "error" to mapOf("code" to code, "message" to message),
            )
    }

    /** A target's state. [state] is `in-progress`, `error`, `abort` or `no-data`; [stage] and [reason] describe an error. */
    data class VisualizationStatus(
        val refreshId: UUID,
        val name: String,
        val state: String,
        val stage: String? = null,
        val reasonCode: String? = null,
        val reasonMessage: String? = null,
    ) : RefreshEvent {
        override val eventName get() = "visualization_status"

        override fun payload() =
            buildMap<String, Any?> {
                put("refresh_id", refreshId.toString())
                put("name", name)
                put("type", "visualization")
                put("state", state)
                stage?.let { put("stage", it) }
                reasonCode?.let { put("reason", mapOf("code" to it, "message" to (reasonMessage ?: it))) }
            }
    }

    /** A target's resolved bindings — path to column values, never the configuration again. */
    data class VisualizationData(
        val refreshId: UUID,
        val name: String,
        val bindings: Map<String, List<Any?>>,
        val rows: Int,
        val bytes: Long,
    ) : RefreshEvent {
        override val eventName get() = "visualization_data"

        override fun payload() =
            mapOf(
                "refresh_id" to refreshId.toString(),
                "name" to name,
                "type" to "visualization",
                "bindings" to bindings,
                "rows" to rows,
                "bytes" to bytes,
            )
    }

    /** Always the last frame. [targets] maps each target to `{outcome, stage?, reason?}`. */
    data class Completed(
        val refreshId: UUID,
        val status: String,
        val targets: Map<String, Map<String, Any?>>,
    ) : RefreshEvent {
        override val eventName get() = "refresh_completed"

        override fun payload() = mapOf("refresh_id" to refreshId.toString(), "status" to status, "targets" to targets)
    }
}

/** A source on the wire of `refresh_started`: its name and whether its execution serves more than one target. */
data class SourceRef(
    val name: String,
    val shared: Boolean,
)

/** Where the engine writes frames. False means nobody took it (a vanished client); the engine carries on regardless. */
fun interface RefreshEvents {
    fun emit(event: RefreshEvent): Boolean
}
