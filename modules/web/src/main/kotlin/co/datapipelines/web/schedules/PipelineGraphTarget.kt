package co.datapipelines.web.schedules

import co.datapipelines.application.dependencies.SchedulePipelineConsumers
import co.datapipelines.application.dependencies.schedule.ExecutorTarget
import co.datapipelines.dependencies.Selector
import co.datapipelines.pipeline.PipelineNameGrammar
import co.datapipelines.scheduler.ScheduleException
import com.fasterxml.jackson.databind.JsonNode

/**
 * The pipeline executor's half of the executor-target extractor port (#874 G3; dependency-graph record §6 row
 * "Schedule → pipeline `EXECUTOR_TARGET`", §5.1): what a stored `pipeline` payload targets, read with the executor's
 * OWN grammar so the graph and a run can never disagree about a payload.
 *
 * - Schema 2 (#786 §D.1) — [PipelineJobExecutor.target] decides: an accepted payload is the pipeline name at
 *   `EXACT_VERSION(n)`; a payload it refuses is [ExecutorTarget.Unreadable].
 * - Schema 1 (pointer-following, before #786; after its startup conversion only a planted row) — the name at `CURRENT`,
 *   when the payload is `{"pipeline": <valid name>, "version": "current"}`; anything else is unreadable.
 * - Any other schema — unreadable.
 *
 * Total and pure: no I/O, no exception. [ExecutorTarget.Unreadable] keeps the payload's own spelled name and, when the
 * version alone is a valid pin, that version.
 */
internal object PipelineGraphTarget {
    /** The schema-1 payload version (`"version": "current"`), read here and refused by every run path (#786 D.2). */
    const val LEGACY_SCHEMA_VERSION = 1

    /** The payload field naming the target — the edge's `source_path` (a JSON Pointer into the payload). */
    const val PIPELINE_POINTER = "/pipeline"

    fun of(
        payloadSchemaVersion: Int,
        payload: JsonNode,
    ): ExecutorTarget =
        when (payloadSchemaVersion) {
            PipelineJobExecutor.PAYLOAD_SCHEMA_VERSION -> exact(payload)
            LEGACY_SCHEMA_VERSION -> current(payload)
            else -> unreadable(payload)
        }

    /** The executor's refusal IS the answer: its `details.reason` is the run's diagnostic, the graph's is MISSING. */
    @Suppress("SwallowedException")
    private fun exact(payload: JsonNode): ExecutorTarget =
        try {
            val target = PipelineJobExecutor.target(payload)
            ExecutorTarget.Pipeline(target.pipeline, Selector.ExactVersion(target.version), PIPELINE_POINTER)
        } catch (refused: ScheduleException) {
            unreadable(payload)
        }

    private fun current(payload: JsonNode): ExecutorTarget {
        val name = spelledName(payload)
        val follows = payload.path("version").let { it.isTextual && it.asText() == PipelineJobExecutor.VERSION_CURRENT }
        return if (name != null && PipelineNameGrammar.matches(name) && follows) {
            ExecutorTarget.Pipeline(name, Selector.Current, PIPELINE_POINTER)
        } else {
            unreadable(payload)
        }
    }

    private fun unreadable(payload: JsonNode): ExecutorTarget =
        ExecutorTarget.Unreadable(
            spelledName = spelledName(payload),
            spelledVersion =
                payload
                    .path(
                        "version",
                    ).takeIf { it.isIntegralNumber }
                    ?.let { SchedulePipelineConsumers.exactPin(it.asText()) },
            sourcePath = PIPELINE_POINTER,
        )

    private fun spelledName(payload: JsonNode): String? =
        payload
            .path("pipeline")
            .takeIf { it.isTextual }
            ?.asText()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
}
