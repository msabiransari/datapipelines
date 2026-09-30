package co.datapipelines.application.dependencies

import co.datapipelines.parameters.ParameterSetConsumers
import co.datapipelines.pipeline.DashboardPin
import co.datapipelines.pipeline.PipelineVersionConsumers
import co.datapipelines.visualization.ArtifactDependents
import co.datapipelines.visualization.ArtifactPin
import co.datapipelines.visualization.PinScope
import java.util.UUID

/**
 * The pipeline aggregate's answer to "which dashboards pin this release?" (#320): the [PipelineVersionConsumers] port
 * bound to [ArtifactDependents], workspace-scoped, the cross-aggregate composition living where the design record puts
 * it — `application`, never in the owning module. A discard asks the LIVE exact-pin scan (graph rule 1); an entity
 * purge asks the ANY-version scan (rule 3, R12).
 */
class DashboardPipelineConsumers(
    private val dependents: ArtifactDependents,
) : PipelineVersionConsumers {
    override fun liveVersionPins(
        workspaceId: UUID,
        pipelineName: String,
        version: Int,
    ): List<DashboardPin> = dependents.dashboardsPinningPipeline(workspaceId, pipelineName, version, PinScope.LIVE).map(::dashboardPin)

    override fun anyVersionPins(
        workspaceId: UUID,
        pipelineName: String,
    ): List<DashboardPin> = dependents.dashboardsPinningPipeline(workspaceId, pipelineName, null, PinScope.ANY).map(::dashboardPin)
}

/** The parameter-set aggregate's answer to the same question: the [ParameterSetConsumers] port over the dashboards' `parameter_set` pin. */
class DashboardParameterSetConsumers(
    private val dependents: ArtifactDependents,
) : ParameterSetConsumers {
    override fun liveVersionPins(
        workspaceId: UUID,
        setName: String,
        version: Int,
    ): List<DashboardPin> = dependents.dashboardsPinningParameterSet(workspaceId, setName, version, PinScope.LIVE).map(::dashboardPin)

    override fun anyVersionPins(
        workspaceId: UUID,
        setName: String,
    ): List<DashboardPin> = dependents.dashboardsPinningParameterSet(workspaceId, setName, null, PinScope.ANY).map(::dashboardPin)
}

private fun dashboardPin(pin: ArtifactPin) = DashboardPin(pin.name, pin.version, pin.status)
