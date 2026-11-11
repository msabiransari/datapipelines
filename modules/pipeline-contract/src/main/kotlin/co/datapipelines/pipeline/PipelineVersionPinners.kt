package co.datapipelines.pipeline

import java.util.UUID

/** One LIVE API version pinning a pipeline version (#786 B.4): what a `pipeline.version.pinned` refusal names. */
data class EndpointVersionPin(
    val path: String,
    val version: Int,
    val status: PipelineVersionStatus,
    /** The exact pipeline version this API version pins. */
    val pipelineVersion: Int,
)

/**
 * The pipeline aggregate's answer to "which API versions pin this release?" (#786 B.4; record §14, A39) — the
 * [PipelineVersionConsumers] shape with its own pin type, because an API version is not a dashboard.
 *
 * Every DRAFT or RELEASED API version protects its exact pin, the noncurrent ones and those of a disabled entity
 * included; a DISCARDED API version protects nothing (its restore revalidates the pin first). `application` binds it
 * over `published_endpoint_versions`; the port has no default, so an assembly that forgets it does not compile.
 */
interface EndpointVersionConsumers {
    /** The live API versions pinning exactly `pipelineName@version` — a version discard's or purge's blockers. */
    fun liveVersionPins(
        workspaceId: UUID,
        pipelineName: String,
        version: Int,
    ): List<EndpointVersionPin>

    /** The live API versions pinning ANY version of [pipelineName] — the entity purge's blockers. */
    fun anyVersionPins(
        workspaceId: UUID,
        pipelineName: String,
    ): List<EndpointVersionPin>
}

/** One live schedule pinning a pipeline version (#786 B.4): what a `pipeline.version.pinned` refusal names. */
data class SchedulePin(
    val name: String,
    /**
     * The pipeline version the schedule runs: its exact pin (schema 2, #786 D.1), or for a leftover schema-1 payload the
     * version CURRENT resolves to — null when that CURRENT is NULL (only the entity scope counts it).
     */
    val pipelineVersion: Int?,
)

/**
 * The pipeline aggregate's answer to "which schedules pin this release?" (#786 B.4; record §14, A39): every live
 * (not deleted) schedule protects the version it runs, paused and blocked ones included: the exact pin its schema-2
 * payload stores (#786 D.1); a leftover schema-1 payload protects the version CURRENT names at the time of the check.
 * `application` binds it over `schedules`; no default, as [EndpointVersionConsumers].
 */
interface ScheduleConsumers {
    /** The live schedules that run exactly `pipelineName@version`. */
    fun liveVersionPins(
        workspaceId: UUID,
        pipelineName: String,
        version: Int,
    ): List<SchedulePin>

    /** The live schedules that run ANY version of [pipelineName] — the entity purge's blockers. */
    fun anyVersionPins(
        workspaceId: UUID,
        pipelineName: String,
    ): List<SchedulePin>
}
