package co.datapipelines.application.dependencies.schedule

import co.datapipelines.dependencies.Selector
import com.fasterxml.jackson.databind.JsonNode

/**
 * The executor-target extractor port (#874 G3; dependency-graph record §3.1, §6 row "Schedule → pipeline
 * `EXECUTOR_TARGET`"): what ONE scheduler executor's stored payload targets. The scheduler core is pipeline-agnostic
 * (scheduler design revision §3.1) and `modules/scheduler` gains no pipeline knowledge, so the executor that owns a
 * payload's grammar implements this port beside its [executorId] (the pipeline executor in `web`), and the schedule
 * family's provider reads the payload through it.
 *
 * [targetOf] is TOTAL over a stored payload: a payload it cannot read is [ExecutorTarget.Unreadable], never an exception
 * and never "no target" — the schedule provider turns it into an edge to a MISSING node, so a broken payload never reads
 * as a schedule without dependencies. It reads only the [payload] it is given (no I/O): the walk's `outgoing` is
 * deterministic over the loaded body (record §7.1).
 */
interface ExecutorTargetExtractor {
    /** The `schedules.executor_id` whose payloads this extractor reads. */
    val executorId: String

    fun targetOf(
        payloadSchemaVersion: Int,
        payload: JsonNode,
    ): ExecutorTarget
}

/** What a schedule's payload targets (§5.1, §6). */
sealed interface ExecutorTarget {
    /** The JSON Pointer of the payload field that names the target — the edge's `source_path`. */
    val sourcePath: String

    /**
     * A pipeline [name] with the payload's selector: an EXACT version for a schema-2 payload (#786 §D.1), CURRENT for a
     * schema-1 one (pointer-following; record §5.1 keeps `requested_selector = current` on the edge).
     */
    data class Pipeline(
        val name: String,
        val selector: Selector,
        override val sourcePath: String,
    ) : ExecutorTarget {
        init {
            require(selector is Selector.ExactVersion || selector == Selector.Current) {
                "a schedule targets an exact version or the current pointer, got ${selector.kind}"
            }
        }
    }

    /**
     * A payload no extractor can read — an unknown executor id, a schema the executor does not know, a missing or
     * invalid pipeline name or version. [spelledName] is the payload's own target text when it has one (what the
     * unresolved node shows, record §7.1 `spelledName`), [spelledVersion] its version when that alone is a valid pin.
     */
    data class Unreadable(
        val spelledName: String?,
        val spelledVersion: Int?,
        override val sourcePath: String,
    ) : ExecutorTarget
}
