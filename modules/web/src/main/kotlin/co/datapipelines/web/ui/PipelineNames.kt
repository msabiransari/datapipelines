package co.datapipelines.web.ui

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.util.UUID

/**
 * The display identity of one pipeline row: the machine `name` (a folder path like
 * `nyc/mobility/revenue_by_borough`, §9.4's identifier) and the optional prose
 * `display_name`.
 */
data class PipelineName(
    val name: String,
    val displayName: String?,
)

/**
 * T114: the execution lists render the pipeline's DISPLAY name, but [ExecutionRecord]
 * carries only `pipelineId` — and `dag`/`pipeline-contract` are outside this change's
 * fence, so the join happens web-side. ONE batch query per page (never one per row):
 * the caller hands the page's distinct pipeline ids and gets them all back in a single
 * `IN` lookup.
 *
 * A deleted pipeline leaves its executions behind; those ids simply do not appear in
 * the result and the template falls back to the truncated id — no crash, no lie.
 *
 * A plain class wired in `DomainConfiguration` — this project forbids stereotype
 * annotations in production code (ArchitectureGuardTest).
 */
class PipelineNames(
    private val jdbc: NamedParameterJdbcTemplate,
) {
    fun lookup(
        workspaceId: UUID,
        pipelineIds: Collection<UUID>,
    ): Map<UUID, PipelineName> {
        if (pipelineIds.isEmpty()) return emptyMap()
        return jdbc
            .query(
                "SELECT id, name, display_name FROM pipelines" +
                    " WHERE workspace_id = :workspaceId AND id IN (:ids)",
                mapOf("workspaceId" to workspaceId, "ids" to pipelineIds.toSet()),
            ) { rs, _ ->
                rs.getObject("id", UUID::class.java) to
                    PipelineName(name = rs.getString("name"), displayName = rs.getString("display_name"))
            }.toMap()
    }

    /**
     * 079 §C: the RELEASED version of each pipeline, for the published-endpoints table.
     *
     * A published endpoint pins a pipeline, never a version — the latest RELEASED version is
     * resolved per request (`EndpointPublishService`). So `pipelines.current_version` is the
     * wrong number to show: it is the newest version of any status, and rendering a DRAFT
     * number next to a live endpoint would state that the endpoint serves something it does
     * not. This asks for the highest version whose status is RELEASED, which is exactly what
     * a call to that endpoint will run.
     *
     * A pipeline with no released version yields no entry, and the template renders "—" —
     * an endpoint can outlive the release it was published against (the pipeline's only
     * release can be discarded), and that is worth seeing rather than papering over.
     *
     * ONE batch query, same rule as [lookup]: the table has as many rows as the workspace has
     * endpoints, and a query per row is how a list page becomes slow without anyone noticing.
     */
    fun releasedVersions(
        workspaceId: UUID,
        pipelineIds: Collection<UUID>,
    ): Map<UUID, Int> {
        if (pipelineIds.isEmpty()) return emptyMap()
        return jdbc
            .query(
                "SELECT v.pipeline_id, MAX(v.version) AS released" +
                    " FROM pipeline_versions v JOIN pipelines p ON p.id = v.pipeline_id" +
                    " WHERE p.workspace_id = :workspaceId AND v.pipeline_id IN (:ids)" +
                    " AND v.status = 'RELEASED' GROUP BY v.pipeline_id",
                mapOf("workspaceId" to workspaceId, "ids" to pipelineIds.toSet()),
            ) { rs, _ ->
                rs.getObject("pipeline_id", UUID::class.java) to rs.getInt("released")
            }.toMap()
    }
}
