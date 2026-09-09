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

/** What a published endpoint serves right now: the pointer's number, and whether it is a draft (D63). */
data class ServedVersion(
    val version: Int,
    val draft: Boolean,
)

private const val DRAFT_STATUS = "DRAFT"

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
     * The version each published endpoint SERVES, keyed by pipeline id — the pointer
     * (`pipelines.current_version`) and its status.
     *
     * A published endpoint pins a pipeline, never a version, and the serve path resolves the
     * pointer per request (`PublishedEndpointServeService`): a RELEASED version always, a DRAFT
     * only under development posture (Versioning D63). The pointer is sticky and event-driven
     * (D60), so it is exactly what a call to that endpoint will run — and when it names a draft
     * the console must SAY draft, not report "no release" about an endpoint that is answering.
     *
     * A pipeline whose pointer is NULL (nothing eligible: never released, or every release
     * discarded) yields no entry, and the template renders that — an endpoint can outlive the
     * release it was published against, and that is worth seeing rather than papering over.
     *
     * ONE batch query, same rule as [lookup]: the table has as many rows as the workspace has
     * endpoints, and a query per row is how a list page becomes slow without anyone noticing.
     */
    fun servedVersions(
        workspaceId: UUID,
        pipelineIds: Collection<UUID>,
    ): Map<UUID, ServedVersion> {
        if (pipelineIds.isEmpty()) return emptyMap()
        return jdbc
            .query(
                "SELECT p.id, p.current_version, v.status" +
                    " FROM pipelines p JOIN pipeline_versions v" +
                    " ON v.pipeline_id = p.id AND v.version = p.current_version" +
                    " WHERE p.workspace_id = :workspaceId AND p.id IN (:ids)",
                mapOf("workspaceId" to workspaceId, "ids" to pipelineIds.toSet()),
            ) { rs, _ ->
                rs.getObject("id", UUID::class.java) to
                    ServedVersion(rs.getInt("current_version"), rs.getString("status") == DRAFT_STATUS)
            }.toMap()
    }
}
