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
}
