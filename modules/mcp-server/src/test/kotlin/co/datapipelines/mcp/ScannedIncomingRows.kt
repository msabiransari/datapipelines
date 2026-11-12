package co.datapipelines.mcp

import co.datapipelines.application.dependencies.IncomingRows
import co.datapipelines.application.dependencies.InverseArm
import co.datapipelines.parameters.ParameterSetTemplatePins
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.inverse.InverseRow
import co.datapipelines.pipeline.inverse.InverseScope
import co.datapipelines.visualization.ArtifactDependents
import co.datapipelines.visualization.PinScope

/**
 * #877 §C — the template arms' ANY-scope, entity-wide question (the purge guards' "pinned anywhere, ever") answered
 * from the domain scans a test stubs, so a tool suite keeps scripting pins as [co.datapipelines.pipeline.TemplatePin] /
 * [co.datapipelines.parameters.ParameterSetPin] / [co.datapipelines.visualization.ArtifactPin], and — #877 §D, the
 * importer arm — the importing template versions as the stored rows [importers] answers per target. Any other question is
 * refused loudly: a tool that starts asking something new must get a fixture that answers it, never a silent empty list.
 * Named without the `Test` suffix so `verifyTestsExecuted` counts only real test classes.
 */
class ScannedIncomingRows(
    private val pipelines: PipelineRepository,
    private val parameterPins: ParameterSetTemplatePins,
    private val visualizationPins: ArtifactDependents,
    /** The importer arm's stored IMPORT rows of the target key (the template family's native query's shape). */
    private val importers: (String) -> List<InverseRow>,
) : IncomingRows {
    override fun rows(
        workspaceId: java.util.UUID,
        arm: InverseArm,
        key: String,
        version: Int?,
        scope: InverseScope,
    ): List<InverseRow> {
        check(scope == InverseScope.ANY && version == null) { "unscripted question: $arm $scope v$version" }
        return when (arm) {
            InverseArm.TEMPLATE_FROM_PIPELINE -> {
                pipelines.findAnyVersionTemplatePins(workspaceId, key).map {
                    InverseRow(
                        it.pipelineId.toString(),
                        it.pipelineName,
                        it.pipelineVersion,
                        it.versionStatus,
                        "nodes[0].template",
                        it.pinnedVersion,
                        it.nodeId,
                    )
                }
            }

            InverseArm.TEMPLATE_FROM_PARAMETER_SET -> {
                parameterPins.anyVersionPins(workspaceId, key).map {
                    InverseRow(
                        it.setId.toString(),
                        it.setName,
                        it.setVersion,
                        it.versionStatus,
                        "parameters[0].source.template",
                        it.pinnedVersion,
                        it.parameter,
                    )
                }
            }

            InverseArm.TEMPLATE_FROM_VISUALIZATION -> {
                visualizationPins.visualizationsPinningTemplate(workspaceId, key, null, PinScope.ANY).map {
                    InverseRow(it.artifactId.toString(), it.name, it.version, it.status, "transform.template", it.pinnedVersion, null)
                }
            }

            InverseArm.TEMPLATE_FROM_TEMPLATE -> {
                importers(key)
            }

            else -> {
                error("unscripted arm: $arm")
            }
        }
    }
}
