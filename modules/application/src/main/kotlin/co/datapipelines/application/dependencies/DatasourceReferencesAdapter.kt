package co.datapipelines.application.dependencies

import co.datapipelines.datasources.DatasourceReference
import co.datapipelines.datasources.DatasourceReferences
import co.datapipelines.datasources.ParameterSetDatasourceReference
import co.datapipelines.parameters.ParameterSetRepository
import co.datapipelines.pipeline.inverse.InverseScope
import co.datapipelines.pipeline.inverse.PipelinePinRows
import java.util.UUID

/**
 * The datasource delete guard's name lock (#873 U2, erratum (7); datasources §9 `datasource.in_use`) as an adapter over
 * the datasource family's two inverse arms (#877 §C — `findIncoming` paged to exhaustion): the pipeline arm (`source`
 * and `output.datasource` of every node type the rule admits) and the parameter-set arm (`parameters[i].source
 * .datasource`). A datasource is deployment-scoped and referenced by NAME, so the lock asks every workspace [workspaces]
 * lists, entity-wide at the ANY scope — every stored version (DISCARDED included) of every entity that still has a live
 * version, the rule the replaced scans kept.
 *
 * Every reference carries the workspace it was found in: the LOCK counts all of them, a refusal NAMES only those its
 * caller may read (`DatasourceUsages`). Rows map and order through the functions the replaced scans used
 * ([PipelinePinRows.datasourceRefs] — one per node — and [ParameterSetRepository.datasourceRefsOf]).
 */
class DatasourceReferencesAdapter(
    private val incoming: IncomingRows,
    private val workspaces: () -> List<UUID>,
) : DatasourceReferences {
    override fun referencesTo(datasourceName: String): List<DatasourceReference> =
        workspaces().flatMap { workspaceId ->
            PipelinePinRows
                .datasourceRefs(incoming.rows(workspaceId, InverseArm.DATASOURCE_FROM_PIPELINE, datasourceName, null, InverseScope.ANY))
                .map { ref ->
                    DatasourceReference(
                        pipelineName = ref.pipelineName,
                        pipelineVersion = ref.pipelineVersion,
                        versionStatus = ref.versionStatus.name,
                        nodeId = ref.nodeId,
                        workspaceId = workspaceId,
                    )
                }
        }

    override fun parameterSetReferencesTo(datasourceName: String): List<ParameterSetDatasourceReference> =
        workspaces().flatMap { workspaceId ->
            val rows = incoming.rows(workspaceId, InverseArm.DATASOURCE_FROM_PARAMETER_SET, datasourceName, null, InverseScope.ANY)
            ParameterSetRepository.datasourceRefsOf(rows).map { ref ->
                ParameterSetDatasourceReference(
                    parameterSetName = ref.parameterSetName,
                    parameterSetVersion = ref.version,
                    versionStatus = ref.status.name,
                    path = ref.path,
                    workspaceId = workspaceId,
                )
            }
        }
}
