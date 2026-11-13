package co.datapipelines.pipeline

import co.datapipelines.pipeline.inverse.InverseRequest
import co.datapipelines.pipeline.inverse.InverseRow
import co.datapipelines.pipeline.inverse.InverseScope
import co.datapipelines.pipeline.inverse.InverseSql
import co.datapipelines.pipeline.inverse.PinNodeRules
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

/**
 * The pipeline family's three native bounded inverse queries (#877 §B.1) — the stored references FROM pipeline
 * versions into templates (`nodes[i].template`), child pipelines (`nodes[i].pipeline`) and datasources
 * (`nodes[i].source`, `nodes[i].output.datasource`). [PipelineRepository] owns the entry points; this class owns the
 * statements, the way [PipelineTreeQueries] owns the tree's.
 *
 * Each statement applies [PinNodeRules] — the ONE node-type rule the outgoing extractors (`PipelineDependencySource
 * .pinsOf`, `DatasourceEdges.ofPipeline`) follow — so a node that cannot carry a reference (a CALCULATOR with a stray
 * `template` key, a SCRIPT with a `source`) is a reference in neither direction. Workspace-scoped, every value a bind,
 * `limit + 1` rows per statement ([InverseSql.page]).
 */
internal class PipelineInverseQueries(
    private val jdbc: NamedParameterJdbcTemplate,
) {
    fun templatePins(request: InverseRequest): List<InverseRow> =
        page(
            request,
            field = "template",
            keyField = "id",
            types = PinNodeRules.TEMPLATE,
        )

    fun childPins(request: InverseRequest): List<InverseRow> =
        page(
            request,
            field = "pipeline",
            keyField = "name",
            types = PinNodeRules.CHILD,
        )

    fun datasourceRefs(request: InverseRequest): List<InverseRow> {
        require(request.targetVersion == null) { "a datasource is referenced by name, never at a version" }
        val occurrences =
            """
            SELECT p.id::text AS dependent_id, p.name AS dependent_name, v.version AS dependent_version,
                   v.status AS dependent_status,
                   ${InverseSql.path("nodes", "n.ord", ".")} || r.field AS source_path,
                   NULL::int AS stored_version, n.node->>'id' AS element_id
              FROM pipelines p
              JOIN pipeline_versions v ON v.pipeline_id = p.id
             CROSS JOIN LATERAL jsonb_array_elements(v.body_json->'nodes') WITH ORDINALITY AS n(node, ord)
             CROSS JOIN LATERAL (VALUES
                   ('source', CASE WHEN ${PinNodeRules.typeIn("n.node", PinNodeRules.DATASOURCE_SOURCE)}
                                    AND n.node->>'source' <> '${NodeSource.TEMPDB_LITERAL}'
                               THEN n.node->>'source' END),
                   ('output.datasource', CASE WHEN ${PinNodeRules.typeIn("n.node", PinNodeRules.DATASOURCE_OUTPUT)}
                                               AND n.node->'output'->>'target' = '${OutputTarget.DATASOURCE.wire}'
                                          THEN n.node->'output'->>'datasource' END)
                 ) AS r(field, name)
             WHERE p.workspace_id = :workspaceId AND ${scope(request.scope)}
               AND r.name = :targetKey
            """.trimIndent()
        return InverseSql.page(jdbc, occurrences, binds(request), request)
    }

    /** A node-level pin at `nodes[i].<field>` whose `<keyField>` names the target and whose `version` is stored. */
    private fun page(
        request: InverseRequest,
        field: String,
        keyField: String,
        types: Set<NodeType>,
    ): List<InverseRow> {
        val stored = InverseSql.jsonInt("n.node->'$field'->'version'")
        val exact = if (request.targetVersion == null) "" else "AND $stored = :targetVersion"
        val occurrences =
            """
            SELECT p.id::text AS dependent_id, p.name AS dependent_name, v.version AS dependent_version,
                   v.status AS dependent_status, ${InverseSql.path("nodes", "n.ord", ".$field")} AS source_path,
                   $stored AS stored_version, n.node->>'id' AS element_id
              FROM pipelines p
              JOIN pipeline_versions v ON v.pipeline_id = p.id
             CROSS JOIN LATERAL jsonb_array_elements(v.body_json->'nodes') WITH ORDINALITY AS n(node, ord)
             WHERE p.workspace_id = :workspaceId AND ${scope(request.scope)}
               AND ${PinNodeRules.typeIn("n.node", types)}
               AND n.node->'$field'->>'$keyField' = :targetKey
               $exact
            """.trimIndent()
        return InverseSql.page(jdbc, occurrences, binds(request), request)
    }

    private fun scope(scope: InverseScope): String = InverseSql.scope(scope, "pipeline_versions", "pipeline_id", entity = "p")

    private fun binds(request: InverseRequest): Map<String, Any?> =
        mapOf("workspaceId" to request.workspaceId, "targetKey" to request.targetKey, "targetVersion" to request.targetVersion)

    companion object {
        /**
         * The child-pin predicate of the discard statement's embedded guard ([PipelineRepository.discardVersion]): a
         * PIPELINE node of `pv`'s body (lateral `pnode`) pinning `:pipelineName@:version` — the same node-type rule and the
         * same stored-version read as [childPins], so the in-statement guard and the inverse cannot drift.
         */
        val DISCARD_CHILD_PIN_P: String =
            "${PinNodeRules.typeIn("pnode", PinNodeRules.CHILD)}" +
                " AND pnode->'pipeline'->>'name' = :pipelineName" +
                " AND ${InverseSql.jsonInt("pnode->'pipeline'->'version'")} = :version"
    }
}
