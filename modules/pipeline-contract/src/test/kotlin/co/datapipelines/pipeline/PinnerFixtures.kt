package co.datapipelines.pipeline

import java.util.UUID

/** #786 B.4 — no API version pins anything: the constructions whose subject is not the API-version guard. */
object NoApiVersionPins : EndpointVersionConsumers {
    override fun liveVersionPins(
        workspaceId: UUID,
        pipelineName: String,
        version: Int,
    ): List<EndpointVersionPin> = emptyList()

    override fun anyVersionPins(
        workspaceId: UUID,
        pipelineName: String,
    ): List<EndpointVersionPin> = emptyList()
}

/** #786 B.4 — no schedule runs anything: the constructions whose subject is not the schedule guard. */
object NoSchedulePins : ScheduleConsumers {
    override fun liveVersionPins(
        workspaceId: UUID,
        pipelineName: String,
        version: Int,
    ): List<SchedulePin> = emptyList()

    override fun anyVersionPins(
        workspaceId: UUID,
        pipelineName: String,
    ): List<SchedulePin> = emptyList()
}
