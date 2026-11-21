package co.datapipelines.application.compatibility

import co.datapipelines.application.endpoints.EndpointPath
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.util.UUID

/** One published API route and the exact pipeline version its current API version pins. */
data class PublishedApiPin(
    val method: String,
    val pathPattern: String,
    val pipelineName: String,
    val pipelineVersion: Int,
)

/**
 * The published-API half of the report's inventory, read through a SCHEMA-COMPATIBLE, read-only
 * projection (dag-executor.md §16.7, deployment.md §8.3 step 5).
 *
 * The report runs BEFORE an upgrade, against the metadata store the OLD release still owns, so it
 * must not assume the new schema. `published_endpoints.method` arrives with V65 (#1037): before
 * it, every route is a GET route — V65's own backfill labels every existing row `GET` ("no row
 * moves, no row's meaning changes") — so a store without the column is read with the literal
 * `GET`, and a store with it is read as stored. Nothing is migrated, nothing is dropped, and no
 * stored value is reinterpreted: the same rows, the same pins, the method each route would have.
 *
 * The column's presence is read from the catalog by the name the query itself resolves through
 * `search_path` (`to_regclass`), so the check and the projection can never look at different
 * tables. A store that has the table but refuses this role's SELECT still fails loudly on the
 * query. Row semantics are the application's read of a listing: a route whose stored path no
 * longer meets the path grammar is retired (never served), so it is not a pin that can run; a
 * route with no current version pins nothing.
 */
class PublishedApiPins(
    private val jdbc: NamedParameterJdbcTemplate,
) {
    /** Every pin of [workspaceId]'s routes, in route order. */
    fun findByWorkspace(workspaceId: UUID): List<PublishedApiPin> {
        val sql = if (hasMethodColumn()) PROJECTION.format(METHOD_COLUMN) else PROJECTION.format(LEGACY_METHOD)
        return jdbc.query(sql, mapOf("workspaceId" to workspaceId), MAPPER).filterNotNull()
    }

    /** True once V65 has run on the store this connection reads. */
    fun hasMethodColumn(): Boolean =
        jdbc.jdbcTemplate.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM pg_attribute WHERE attrelid = to_regclass('published_endpoints') " +
                "AND attname = 'method' AND NOT attisdropped)",
            Boolean::class.java,
        ) == true

    private companion object {
        /** V65's backfill value, written as a constant here so a pre-V65 store reads exactly what V65 would label it. */
        const val LEGACY_METHOD = "'GET'"
        const val METHOD_COLUMN = "e.method"

        val PROJECTION =
            """
            SELECT e.path_pattern, %s AS method, v.pipeline_name, v.pipeline_version
              FROM published_endpoints e
              JOIN published_endpoint_versions v ON v.endpoint_id = e.id AND v.version = e.current_version
             WHERE e.workspace_id = :workspaceId
             ORDER BY e.path_pattern, method
            """.trimIndent()

        val MAPPER =
            RowMapper<PublishedApiPin?> { rs, _ ->
                val path = rs.getString("path_pattern")
                if (EndpointPath.parse(path).isFailure) {
                    null
                } else {
                    PublishedApiPin(
                        method = rs.getString("method"),
                        pathPattern = path,
                        pipelineName = rs.getString("pipeline_name"),
                        pipelineVersion = rs.getInt("pipeline_version"),
                    )
                }
            }
    }
}
