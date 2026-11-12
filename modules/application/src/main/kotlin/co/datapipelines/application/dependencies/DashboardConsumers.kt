package co.datapipelines.application.dependencies

import co.datapipelines.parameters.ParameterSetConsumers
import co.datapipelines.pipeline.DashboardPin
import co.datapipelines.pipeline.PipelineVersionConsumers
import co.datapipelines.pipeline.inverse.InverseRow
import co.datapipelines.pipeline.inverse.InverseScope
import co.datapipelines.visualization.ArtifactDependents
import java.util.UUID

/**
 * The pipeline aggregate's answer to "which dashboards pin this release?" (#320): the [PipelineVersionConsumers] port
 * as an adapter over the pipeline family's dashboard arm (#877 §C — `findIncoming` paged to exhaustion), workspace-scoped,
 * the cross-aggregate composition living where the design record puts it — `application`, never in the owning module. A
 * discard asks the LIVE exact-pin question (graph rule 1); an entity purge asks the ANY-version one (rule 3, R12). Rows
 * map and order through [ArtifactDependents.pinsOf], the scan this replaces.
 */
class DashboardPipelineConsumers(
    private val incoming: IncomingRows,
) : PipelineVersionConsumers {
    override fun liveVersionPins(
        workspaceId: UUID,
        pipelineName: String,
        version: Int,
    ): List<DashboardPin> =
        dashboards(incoming.rows(workspaceId, InverseArm.PIPELINE_FROM_DASHBOARD, pipelineName, version, InverseScope.LIVE))

    override fun anyVersionPins(
        workspaceId: UUID,
        pipelineName: String,
    ): List<DashboardPin> = dashboards(incoming.rows(workspaceId, InverseArm.PIPELINE_FROM_DASHBOARD, pipelineName, null, InverseScope.ANY))
}

/** The parameter-set aggregate's answer to the same question: the [ParameterSetConsumers] port over the set family's dashboard arm. */
class DashboardParameterSetConsumers(
    private val incoming: IncomingRows,
) : ParameterSetConsumers {
    override fun liveVersionPins(
        workspaceId: UUID,
        setName: String,
        version: Int,
    ): List<DashboardPin> =
        dashboards(incoming.rows(workspaceId, InverseArm.PARAMETER_SET_FROM_DASHBOARD, setName, version, InverseScope.LIVE))

    override fun anyVersionPins(
        workspaceId: UUID,
        setName: String,
    ): List<DashboardPin> = dashboards(incoming.rows(workspaceId, InverseArm.PARAMETER_SET_FROM_DASHBOARD, setName, null, InverseScope.ANY))
}

private fun dashboards(rows: List<InverseRow>): List<DashboardPin> =
    ArtifactDependents.pinsOf(rows).map { pin -> DashboardPin(pin.name, pin.version, pin.status) }
