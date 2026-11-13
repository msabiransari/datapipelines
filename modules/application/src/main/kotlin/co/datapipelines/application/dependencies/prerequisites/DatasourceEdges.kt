package co.datapipelines.application.dependencies.prerequisites

import co.datapipelines.dependencies.AccessMode
import co.datapipelines.dependencies.Constraint
import co.datapipelines.dependencies.FamilyKey
import co.datapipelines.dependencies.LookupScope
import co.datapipelines.dependencies.ObjectAddress
import co.datapipelines.dependencies.ObjectRequest
import co.datapipelines.dependencies.Reference
import co.datapipelines.dependencies.Relationship
import co.datapipelines.dependencies.Selector
import co.datapipelines.parameters.ParameterSetBody
import co.datapipelines.pipeline.NodeOutput
import co.datapipelines.pipeline.NodeSource
import co.datapipelines.pipeline.NodeType
import co.datapipelines.pipeline.Pipeline
import java.util.UUID

/**
 * The ONE datasource-reference extractor (#873 §F.1, §F.2, §F.3 c: "one extractor, two callers") — the pipeline and
 * parameter-set providers' `DATASOURCE` / `PARAMETER_SOURCE_DATASOURCE` edges and the apply-time [RegistryPrerequisiteCheck]
 * read the same names from the same body through these functions, so the plan and the apply can never disagree on which
 * datasources a closure needs.
 *
 * A datasource is named by its registry NAME (`ENTITY` selector, record §5.1); G3's provider resolves the name within the
 * caller's workspace grants. Nothing here reads, describes or connects to a datasource (record §8: a graph read does not
 * test connectivity).
 *
 * The pipeline dispatch is `ReferenceRules`' (pipeline-contract §12.5), mirrored because that object is internal to its
 * module: a CALCULATOR node has no source and no output; a PIPELINE node has only its §4.7 output block; a SCRIPT node has
 * no source (`ScriptRules` refuses one) and its pin is a script template; a DQL, DML or DDL node has its `source` and its
 * datasource `output`. The `tempdb` literal (`NodeSource.Tempdb`) is the per-execution staging database, never a datasource.
 */
object DatasourceEdges {
    /**
     * Every datasource occurrence of [pipeline] in node order — a node's source before its output — as an outgoing
     * `DATASOURCE` reference: `nodes[i].source` with `datasource_access: read` for a DQL source and `write` for a DML or
     * DDL source (workspaces §6 shapes 1/2: a DML/DDL node sourcing a datasource is a write-shaped use), and
     * `nodes[i].output.datasource` with `write` (§6 shape 3). The paths keep the native spelling, as every pin path does.
     */
    fun ofPipeline(
        workspaceId: UUID,
        pipeline: Pipeline,
    ): List<Reference> =
        pipeline.nodes.withIndex().flatMap { (index, node) ->
            when (node.type) {
                NodeType.CALCULATOR, NodeType.SCRIPT -> {
                    emptyList()
                }

                NodeType.PIPELINE -> {
                    listOfNotNull(output(workspaceId, index, node.output))
                }

                NodeType.DQL, NodeType.DML, NodeType.DDL -> {
                    val access = if (node.type == NodeType.DQL) AccessMode.READ else AccessMode.WRITE
                    val source =
                        (node.resolvedSource as? NodeSource.Datasource)?.let { datasource ->
                            val mode = Constraint.DatasourceAccess(access)
                            reference(workspaceId, datasource.name, Relationship.DATASOURCE, "nodes[$index].source", mode)
                        }
                    listOfNotNull(source, output(workspaceId, index, node.output))
                }
            }
        }

    /**
     * Every selector datasource of [body] in parameter order as an outgoing `PARAMETER_SOURCE_DATASOURCE` reference at
     * `parameters[i].source.datasource`, carrying `datasource_dialect` when the selector requires one. The requirement is
     * `PinRules`' dialect rule (`TEMPLATE_DIALECT_MISMATCH`): a selector's template runs against its datasource, so the
     * datasource must speak the pinned template's dialect. [selectorDialect] answers that dialect's wire token for a
     * parameter index — read from the template's stored row, never by executing the selector — or null when the selector
     * pins no template or the pinned version is not stored (its own `PARAMETER_SOURCE_TEMPLATE` edge is then MISSING).
     */
    fun ofParameterSet(
        workspaceId: UUID,
        body: ParameterSetBody,
        selectorDialect: (Int) -> String?,
    ): List<Reference> =
        body.parameters.withIndex().mapNotNull { (index, parameter) ->
            parameter.source?.datasource?.let { name ->
                val dialect = selectorDialect(index)?.let(Constraint::DatasourceDialect)
                reference(workspaceId, name, Relationship.PARAMETER_SOURCE_DATASOURCE, "parameters[$index].source.datasource", dialect)
            }
        }

    /** The distinct datasource names [references] request, in first-occurrence order. */
    fun namesOf(references: List<Reference>): List<String> =
        references.filter { it.request.family == FamilyKey.DATASOURCE }.map { it.request.address.lookupKey }.distinct()

    /** The `ENTITY` request for the datasource [name] in [workspaceId]'s lookup scope. */
    fun request(
        workspaceId: UUID,
        name: String,
    ): ObjectRequest = ObjectRequest(ObjectAddress(FamilyKey.DATASOURCE, LookupScope(workspaceId), name), Selector.Entity)

    private fun output(
        workspaceId: UUID,
        index: Int,
        output: NodeOutput?,
    ): Reference? {
        val datasource = (output as? NodeOutput.Datasource)?.datasource?.takeIf { it.isNotBlank() } ?: return null
        return reference(
            workspaceId,
            datasource,
            Relationship.DATASOURCE,
            "nodes[$index].output.datasource",
            Constraint.DatasourceAccess(AccessMode.WRITE),
        )
    }

    private fun reference(
        workspaceId: UUID,
        name: String,
        relationship: Relationship,
        path: String,
        constraint: Constraint?,
    ): Reference =
        Reference(
            request = request(workspaceId, name),
            relationship = relationship,
            sourcePath = path,
            constraints = listOfNotNull(constraint),
            spelledName = name,
        )
}
