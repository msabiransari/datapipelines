package co.datapipelines.pipeline

import java.util.UUID

/**
 * One dashboard version that pins something — a pipeline release through a source, or a parameter-set release
 * through the dashboard's `parameter_set` (#320). What a refusal names: [name]@[version] and its [status].
 */
data class DashboardPin(
    val name: String,
    val version: Int,
    val status: PipelineVersionStatus,
)

/**
 * "Which dashboards pin this pipeline release?" — the one reverse arrow INTO the pipeline family that no
 * `pipeline_versions` row can answer (#320, versioning §3.5's graph rule 1: a dashboard source pins
 * `pipeline {name, version}` exactly, the design record §4.2).
 *
 * Declared here as a port for the reason [ExclusiveDraftTemplates] is: the module arrow runs
 * `visualization → pipeline-contract`, never the reverse (module-structure §4.2), so the aggregate asks and the
 * aggregation layer answers — over `ArtifactDependents`, workspace-scoped, in `application`. No statement of this
 * module names a dashboard table.
 *
 * There is deliberately NO default implementation and no "none" object: a `PipelineService` built without an
 * answer would refuse nothing and say nothing, the silent hole this port exists to close. A test that has no
 * dashboards says so with its own explicit double.
 */
interface PipelineVersionConsumers {
    /**
     * The LIVE (DRAFT or RELEASED) dashboard versions with a source pinning exactly `pipelineName@version` —
     * the discard's evidence (graph rule 1).
     */
    fun liveVersionPins(
        workspaceId: UUID,
        pipelineName: String,
        version: Int,
    ): List<DashboardPin>

    /**
     * EVERY stored version — DISCARDED included — of a dashboard that still has a live version and pins ANY version
     * of `pipelineName` — the entity purge's evidence (graph rule 3; R12: a restore would resurrect a dangling pin).
     */
    fun anyVersionPins(
        workspaceId: UUID,
        pipelineName: String,
    ): List<DashboardPin>
}
