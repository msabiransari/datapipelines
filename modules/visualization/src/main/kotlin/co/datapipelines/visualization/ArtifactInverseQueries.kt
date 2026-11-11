package co.datapipelines.visualization

import co.datapipelines.pipeline.inverse.InverseRequest
import co.datapipelines.pipeline.inverse.InverseRow
import co.datapipelines.pipeline.inverse.InverseSql
import com.fasterxml.jackson.databind.JsonNode
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

/**
 * The two artifact families' native bounded inverse statements (#877 §B.3) — the stored references FROM visualization
 * versions (`transform.template`) and dashboard versions (`parameter_set`, `sources[i].pipeline`,
 * `visualizations[i].visualization`). The same stored fields `VisualizationReleaseProvider.transformPin` and
 * `DashboardReleaseProvider.pinsOf` extract; every ref is `{name, version}`. [VisualizationRepository] and
 * [DashboardRepository] own the entry points; [ArtifactDependents] and `DashboardRepository.livePinsOf` page them.
 *
 * Workspace-scoped; only closed constants (table names, the fixed JSON paths below) are interpolated, every value is a
 * bind; `limit + 1` rows per statement ([InverseSql.page]).
 */
internal class ArtifactInverseQueries(
    private val jdbc: NamedParameterJdbcTemplate,
    private val kind: ArtifactKind,
) {
    /** A single-object pin at [field] (a JSON path of `->` steps from the body root), spelled [path] in the body. */
    fun objectPin(
        request: InverseRequest,
        field: String,
        path: String,
    ): List<InverseRow> = occurrences(request, "", "v.body_json$field", "'$path'")

    /** A pin at `<array>[i].<element>` for every element of the body's [array]. */
    fun arrayPin(
        request: InverseRequest,
        array: String,
        element: String,
    ): List<InverseRow> {
        val lateral =
            "CROSS JOIN LATERAL jsonb_array_elements(CASE WHEN jsonb_typeof(v.body_json->'$array') = 'array'" +
                " THEN v.body_json->'$array' ELSE '[]'::jsonb END) WITH ORDINALITY AS e(value, ord)"
        // The containment pre-filter on the whole array — `v.body_json -> '<array>' @> [{<element>: {name}}]`, the
        // expression V46's GIN index serves for `visualizations` (PinnedByBatchedStatementsTest) — narrows the versions
        // before the lateral expands them; the lateral alone decides the occurrences, so it adds no row and drops none.
        val probe =
            ArtifactJson.mapper.createArrayNode().add(
                ArtifactJson.mapper.createObjectNode().set<JsonNode>(
                    element,
                    ArtifactJson.mapper.createObjectNode().put("name", request.targetKey),
                ),
            )
        val prefilter = "AND v.body_json -> '$array' @> CAST(:inverseProbe AS jsonb)"
        return occurrences(
            request,
            lateral,
            "e.value->'$element'",
            InverseSql.path(array, "e.ord", ".$element"),
            prefilter,
            mapOf("inverseProbe" to ArtifactJson.mapper.writeValueAsString(probe)),
        )
    }

    @Suppress("LongParameterList") // one statement shape; the arms differ only in these fragments
    private fun occurrences(
        request: InverseRequest,
        lateral: String,
        ref: String,
        path: String,
        prefilter: String = "",
        extra: Map<String, Any?> = emptyMap(),
    ): List<InverseRow> {
        val stored = InverseSql.jsonInt("$ref->'version'")
        val exact = if (request.targetVersion == null) "" else "AND $stored = :targetVersion"
        val sql =
            """
            SELECT s.id::text AS dependent_id, s.name AS dependent_name, v.version AS dependent_version,
                   v.status AS dependent_status, $path AS source_path, $stored AS stored_version, NULL::text AS element_id
              FROM ${kind.versions} v
              JOIN ${kind.index} s ON s.id = v.${kind.fk}
              $lateral
             WHERE s.workspace_id = :workspaceId AND ${InverseSql.scope(request.scope, kind.versions, kind.fk)}
               $prefilter
               AND $ref->>'name' = :targetKey
               $exact
            """.trimIndent()
        val binds =
            mapOf("workspaceId" to request.workspaceId, "targetKey" to request.targetKey, "targetVersion" to request.targetVersion) + extra
        return InverseSql.page(jdbc, sql, binds, request)
    }

    companion object {
        const val TRANSFORM_TEMPLATE = "->'transform'->'template'"
        const val TRANSFORM_TEMPLATE_PATH = "transform.template"
        const val PARAMETER_SET = "->'parameter_set'"
        const val PARAMETER_SET_PATH = "parameter_set"
        const val SOURCES = "sources"
        const val SOURCE_PIPELINE = "pipeline"
        const val VISUALIZATIONS = "visualizations"
        const val VISUALIZATION = "visualization"
    }
}
