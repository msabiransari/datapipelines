package co.datapipelines.parameters

import co.datapipelines.pipeline.DashboardPin
import java.util.UUID

/**
 * "Which dashboards pin this parameter-set release?" — the consumer binding the record reserved `parameter.in_use`
 * for (record §13; pipeline-contract §13.20's intro), asked of the set aggregate by its own purge and discard verbs
 * (#320, versioning §3.5's graph rule 1: a dashboard pins `parameter_set {name, version}` exactly, the design
 * record §4.2).
 *
 * A port for the reason [co.datapipelines.pipeline.PipelineVersionConsumers] is one: `parameters` may not depend on
 * `visualization` (build map), the aggregation layer answers over `ArtifactDependents`, workspace-scoped, and no
 * statement of this module names a dashboard table. The same rule about defaults: there is none, and no "none"
 * object — a service built without an answer would refuse nothing and say nothing.
 */
interface ParameterSetConsumers {
    /**
     * The LIVE (DRAFT or RELEASED) dashboard versions pinning exactly `setName@version` — the discard's and the draft
     * purge's evidence (graph rule 1). A DRAFT dashboard may pin a DRAFT set (dashboards §3.1), so the draft purges
     * are guarded too.
     */
    fun liveVersionPins(
        workspaceId: UUID,
        setName: String,
        version: Int,
    ): List<DashboardPin>

    /**
     * EVERY stored version — DISCARDED included — of a dashboard that still has a live version and pins ANY version of
     * `setName` — the entity purge's evidence, and the evidence of a draft purge that takes the entity with it (graph
     * rule 3; R12: a restore would resurrect a dangling pin).
     */
    fun anyVersionPins(
        workspaceId: UUID,
        setName: String,
    ): List<DashboardPin>
}
