package co.datapipelines.application.dependencies

import co.datapipelines.application.endpoints.PublishedEndpointVersionRepository
import co.datapipelines.pipeline.EndpointVersionConsumers
import co.datapipelines.pipeline.EndpointVersionPin
import co.datapipelines.pipeline.ScheduleConsumers
import co.datapipelines.pipeline.SchedulePin
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.util.UUID

/**
 * The pipeline aggregate's answer to "which API versions pin this release?" (#786 B.4; record §14, A39): the
 * [EndpointVersionConsumers] port over `published_endpoint_versions`, composed in `application` as
 * [DashboardPipelineConsumers] is. Live = DRAFT or RELEASED, the noncurrent versions and a disabled entity's included;
 * DISCARDED protects nothing.
 */
class EndpointVersionPipelineConsumers(
    private val versions: PublishedEndpointVersionRepository,
) : EndpointVersionConsumers {
    override fun liveVersionPins(
        workspaceId: UUID,
        pipelineName: String,
        version: Int,
    ): List<EndpointVersionPin> = versions.livePins(workspaceId, pipelineName, version).map(::endpointPin)

    override fun anyVersionPins(
        workspaceId: UUID,
        pipelineName: String,
    ): List<EndpointVersionPin> = versions.livePinsAnyVersion(workspaceId, pipelineName).map(::endpointPin)

    private fun endpointPin(pin: PublishedEndpointVersionRepository.Pin) =
        EndpointVersionPin(path = pin.pathPattern, version = pin.version, status = pin.status, pipelineVersion = pin.pipelineVersion)
}

/**
 * The pipeline aggregate's answer to "which schedules run this release?" (#786 B.4; record §14, A39): the
 * [ScheduleConsumers] port over `schedules`, read by SQL here so `application` takes no dependency on `scheduler`
 * (module-structure's one-scheduler-type rule) — the same indexed `(workspace_id, target_ref)` read the scheduler's own
 * by-target lookup makes.
 *
 * Every live (not deleted) schedule counts: paused and blocked ones too, since a resume or an unblock would run the
 * version again. A schema-1 payload names no version — it follows the pipeline's CURRENT — so until U2 stores exact
 * pins it protects the version CURRENT names now, and nothing while CURRENT is NULL; the entity scope counts it
 * regardless, because the entity purge removes the name it targets.
 *
 * [targetPrefix] is the pipeline executor's `target_ref` prefix, supplied by the assembling layer that owns it.
 */
class SchedulePipelineConsumers(
    private val jdbc: NamedParameterJdbcTemplate,
    private val targetPrefix: String,
) : ScheduleConsumers {
    override fun liveVersionPins(
        workspaceId: UUID,
        pipelineName: String,
        version: Int,
    ): List<SchedulePin> = resolved(workspaceId, pipelineName).filter { it.pipelineVersion == version }

    override fun anyVersionPins(
        workspaceId: UUID,
        pipelineName: String,
    ): List<SchedulePin> = resolved(workspaceId, pipelineName)

    /** Each live schedule targeting [pipelineName] with the version it runs; null when it follows a NULL CURRENT. */
    private fun resolved(
        workspaceId: UUID,
        pipelineName: String,
    ): List<SchedulePin> =
        jdbc.query(
            """
            SELECT s.name, p.current_version AS pipeline_version
              FROM schedules s
              LEFT JOIN pipelines p ON p.workspace_id = s.workspace_id AND p.name = :name
             WHERE s.workspace_id = :ws AND s.target_ref = :ref AND s.deleted_at IS NULL
             ORDER BY s.name
            """.trimIndent(),
            mapOf("ws" to workspaceId, "name" to pipelineName, "ref" to targetPrefix + pipelineName),
        ) { rs, _ -> SchedulePin(rs.getString("name"), rs.getInt("pipeline_version").takeUnless { rs.wasNull() }) }
}
