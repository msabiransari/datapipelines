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
private const val DISCARDED_STATUS = "DISCARDED"

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
     * The pipeline version each published endpoint SERVES and that pipeline's name, keyed by ENDPOINT id (#786): the
     * API's current version's EXACT pin and whether either half is a DRAFT — the serve path's chain
     * (`PublishedEndpointServeService`), never the pipeline's own pointer. A DRAFT is served only under the development
     * posture (Versioning D63), so when either names a draft the console must SAY draft. An API with no current version,
     * or whose pin names a missing or DISCARDED version, serves nothing and is absent from [Pins.served]; its pinned
     * name still appears in [Pins.names] when the pipeline exists.
     *
     * ONE query for the whole page, never one per row (T114).
     */
    fun endpointPins(
        workspaceId: UUID,
        endpointIds: Collection<UUID>,
    ): Pins {
        if (endpointIds.isEmpty()) return Pins(emptyMap(), emptyMap())
        val names = HashMap<UUID, PipelineName>()
        val served = HashMap<UUID, ServedVersion>()
        jdbc.query(
            "SELECT e.id, av.status AS api_status, av.pipeline_version, p.name, p.display_name, v.status AS pipeline_status" +
                " FROM published_endpoints e" +
                " JOIN published_endpoint_versions av ON av.endpoint_id = e.id AND av.version = e.current_version" +
                " JOIN pipelines p ON p.workspace_id = e.workspace_id AND p.name = av.pipeline_name" +
                " LEFT JOIN pipeline_versions v ON v.pipeline_id = p.id AND v.version = av.pipeline_version" +
                " WHERE e.workspace_id = :workspaceId AND e.id IN (:ids)",
            mapOf("workspaceId" to workspaceId, "ids" to endpointIds.toSet()),
        ) { rs ->
            val id = rs.getObject("id", UUID::class.java)
            names[id] = PipelineName(name = rs.getString("name"), displayName = rs.getString("display_name"))
            val pipelineStatus = rs.getString("pipeline_status")
            if (pipelineStatus != null && pipelineStatus != DISCARDED_STATUS && rs.getString("api_status") != DISCARDED_STATUS) {
                val draft = rs.getString("api_status") == DRAFT_STATUS || pipelineStatus == DRAFT_STATUS
                served[id] = ServedVersion(rs.getInt("pipeline_version"), draft)
            }
        }
        return Pins(names, served)
    }

    /** [endpointPins]' answer: each endpoint's pinned pipeline name and, when it serves, the served version. */
    data class Pins(
        val names: Map<UUID, PipelineName>,
        val served: Map<UUID, ServedVersion>,
    )
}
