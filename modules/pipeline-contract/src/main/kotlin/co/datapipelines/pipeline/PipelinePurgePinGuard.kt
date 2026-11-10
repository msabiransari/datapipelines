package co.datapipelines.pipeline

import co.datapipelines.typesystem.DatapipelinesException
import java.util.UUID

/**
 * The shared pin preflight for pipeline discard and purge, before any destructive write: parent pipelines, dashboards
 * and — since #786 B.4 (record §14, A39) — live API versions and live schedules.
 *
 * Every blocker is named. Native visibility admits them all here: the verbs that reach this guard
 * (`pipeline.version.manage`, `pipeline.delete`) are held only by roles that read API versions and schedules unlensed
 * (auth.md §7.6), and both lenses that do exist admit an API version or a schedule exactly when they admit the pipeline
 * it pins — the pipeline the caller is acting on. A hidden pinner, which would take the safe protected-reference
 * wording, therefore cannot reach this refusal.
 */
class PipelinePurgePinGuard(
    private val pipelines: PipelineRepository,
    private val dashboards: PipelineVersionConsumers,
    private val endpointVersions: EndpointVersionConsumers,
    private val schedules: ScheduleConsumers,
) {
    /** Everything that pins one pipeline version, by consumer family. */
    data class Pinners(
        val pipelines: List<TemplatePin> = emptyList(),
        val dashboards: List<DashboardPin> = emptyList(),
        val endpointVersions: List<EndpointVersionPin> = emptyList(),
        val schedules: List<SchedulePin> = emptyList(),
    ) {
        val isEmpty: Boolean
            get() = pipelines.isEmpty() && dashboards.isEmpty() && endpointVersions.isEmpty() && schedules.isEmpty()
    }

    /** A sole draft takes the entity: dashboards, API versions and schedules are checked across all stored versions. */
    fun refuseIfDraftPinned(
        workspaceId: UUID,
        record: PipelineRecord,
        version: Int,
    ) {
        refuseIfPinned(workspaceId, record, version, entityPurge = pipelines.listVersions(workspaceId, record.id).size == 1)
    }

    /** Parent pipelines always use live exact pins; only the other families' scope changes for entity purge. */
    fun refuseIfPinned(
        workspaceId: UUID,
        record: PipelineRecord,
        version: Int,
        entityPurge: Boolean = false,
    ) {
        val pinners = pinnersOf(workspaceId, record, version, entityPurge)
        if (!pinners.isEmpty) throw pinned(record, version, pinners)
    }

    /** The four reads [refuseIfPinned] judges, also the discard concurrency recovery's re-read. */
    fun pinnersOf(
        workspaceId: UUID,
        record: PipelineRecord,
        version: Int,
        entityPurge: Boolean = false,
    ): Pinners =
        Pinners(
            pipelines = pipelines.findLiveParentsPinningVersion(workspaceId, record.name, version),
            dashboards =
                if (entityPurge) {
                    dashboards.anyVersionPins(workspaceId, record.name)
                } else {
                    dashboards.liveVersionPins(workspaceId, record.name, version)
                },
            endpointVersions =
                if (entityPurge) {
                    endpointVersions.anyVersionPins(workspaceId, record.name)
                } else {
                    endpointVersions.liveVersionPins(workspaceId, record.name, version)
                },
            schedules =
                if (entityPurge) {
                    schedules.anyVersionPins(workspaceId, record.name)
                } else {
                    schedules.liveVersionPins(workspaceId, record.name, version)
                },
        )

    /** The existing pinned refusal shape, shared with the discard concurrency recovery. */
    fun pinned(
        record: PipelineRecord,
        version: Int,
        pinners: Pinners,
    ): DatapipelinesException =
        DatapipelinesException(
            code = PipelineErrorCodes.Versioning.PINNED,
            message =
                "Version $version of '${record.name.truncateForError()}' is pinned by " +
                    listOfNotNull(
                        pinners.pipelines.takeIf { it.isNotEmpty() }?.let { "${it.size} live pipeline version(s)" },
                        pinners.dashboards.takeIf { it.isNotEmpty() }?.let { "${it.size} dashboard version(s)" },
                        pinners.endpointVersions.takeIf { it.isNotEmpty() }?.let { "${it.size} API version(s)" },
                        pinners.schedules.takeIf { it.isNotEmpty() }?.let { "${it.size} schedule(s)" },
                    ).joinToString(" and ") +
                    "; discard or repoint them first.",
            details =
                buildMap {
                    put("pipeline_id", record.id.toString())
                    put("version", version)
                    put(
                        "pinned_by",
                        pinners.pipelines.map { mapOf("pipeline" to it.pipelineName, "version" to it.pipelineVersion, "node" to it.nodeId) },
                    )
                    if (pinners.dashboards.isNotEmpty()) {
                        put(
                            "referencing_dashboards",
                            pinners.dashboards.map { mapOf("dashboard" to it.name, "version" to it.version, "status" to it.status.name) },
                        )
                    }
                    if (pinners.endpointVersions.isNotEmpty()) {
                        put(
                            "referencing_api_versions",
                            pinners.endpointVersions.map {
                                mapOf("path" to it.path, "version" to it.version, "status" to it.status.name, "pipeline_version" to it.pipelineVersion)
                            },
                        )
                    }
                    if (pinners.schedules.isNotEmpty()) {
                        put(
                            "referencing_schedules",
                            pinners.schedules.map { mapOf("schedule" to it.name, "pipeline_version" to it.pipelineVersion) },
                        )
                    }
                },
        )
}
