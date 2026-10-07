package co.datapipelines.pipeline

import co.datapipelines.typesystem.DatapipelinesException
import java.util.UUID

/** The shared pin preflight for pipeline discard and purge, before any destructive write. */
class PipelinePurgePinGuard(
    private val pipelines: PipelineRepository,
    private val dashboards: PipelineVersionConsumers,
) {
    /** A sole draft takes the entity: dashboards must be checked across all stored versions. */
    fun refuseIfDraftPinned(
        workspaceId: UUID,
        record: PipelineRecord,
        version: Int,
    ) {
        refuseIfPinned(workspaceId, record, version, entityPurge = pipelines.listVersions(workspaceId, record.id).size == 1)
    }

    /** Parent pipelines always use live exact pins; only dashboard scope changes for entity purge. */
    fun refuseIfPinned(
        workspaceId: UUID,
        record: PipelineRecord,
        version: Int,
        entityPurge: Boolean = false,
    ) {
        val pinners = pipelines.findLiveParentsPinningVersion(workspaceId, record.name, version)
        val dashboardPins =
            if (entityPurge) {
                dashboards.anyVersionPins(workspaceId, record.name)
            } else {
                dashboards.liveVersionPins(workspaceId, record.name, version)
            }
        if (pinners.isNotEmpty() || dashboardPins.isNotEmpty()) {
            throw pinned(record, version, pinners, dashboardPins)
        }
    }

    /** The existing pinned refusal shape, shared with the discard concurrency recovery. */
    fun pinned(
        record: PipelineRecord,
        version: Int,
        pinners: List<TemplatePin>,
        dashboardPins: List<DashboardPin> = emptyList(),
    ): DatapipelinesException =
        DatapipelinesException(
            code = PipelineErrorCodes.Versioning.PINNED,
            message =
                "Version $version of '${record.name.truncateForError()}' is pinned by " +
                    listOfNotNull(
                        pinners.takeIf { it.isNotEmpty() }?.let { "${it.size} live pipeline version(s)" },
                        dashboardPins.takeIf { it.isNotEmpty() }?.let { "${it.size} dashboard version(s)" },
                    ).joinToString(" and ") +
                    "; discard or repoint them first.",
            details =
                buildMap {
                    put("pipeline_id", record.id.toString())
                    put("version", version)
                    put(
                        "pinned_by",
                        pinners.map { mapOf("pipeline" to it.pipelineName, "version" to it.pipelineVersion, "node" to it.nodeId) },
                    )
                    if (dashboardPins.isNotEmpty()) {
                        put(
                            "referencing_dashboards",
                            dashboardPins.map { mapOf("dashboard" to it.name, "version" to it.version, "status" to it.status.name) },
                        )
                    }
                },
        )
}
