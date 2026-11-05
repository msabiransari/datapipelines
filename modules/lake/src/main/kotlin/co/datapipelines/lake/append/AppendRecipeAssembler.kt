package co.datapipelines.lake.append

import co.datapipelines.lake.LakeLeases
import co.datapipelines.lake.definitions.LakeDefinitionRepository
import co.datapipelines.lake.delivery.DeliveryLedgerRepository
import co.datapipelines.pipeline.PipelineErrorCodes.Lake
import co.datapipelines.pipeline.PipelineJson
import co.datapipelines.typesystem.DatapipelinesException
import org.apache.iceberg.Table
import java.time.Duration

/** The body actually executed, its observed artifact inputs, and the definition actually applied to this table. */
internal class AppendRecipeAssembler(
    private val definitions: LakeDefinitionRepository,
    private val deliveries: DeliveryLedgerRepository,
    private val leases: LakeLeases,
) {
    private val json = PipelineJson.objectMapper()

    fun build(
        request: AppendPublicationInput,
        table: Table,
    ): RecipePublication {
        val execution = request.execution
        val metadata = required(execution.metadata, "The execution has no frozen recipe provenance.")
        val version =
            required(
                table.properties()["dp.definition.version"]?.toIntOrNull()?.takeIf { it > 0 },
                "The applied table has no definition version.",
            )
        val definition = required(definitions.findVersion(request.delivery.tableId, version), "The applied definition body is absent.")
        // Observe pending nodes FIRST: a sibling resolving between snapshots can only make us conservative.
        val unresolvedNodes = execution.lineage.unresolvedNodes(execution.pipeline)
        val templates = execution.lineage.templateReferences()
        return RecipePublication(
            request.manifest,
            RecipePipeline(
                execution.pipelineId,
                execution.pipelineVersion,
                metadata.draft,
                metadata.bodyHash,
                execution.pipeline,
                json.readTree(metadata.bodyJson),
                execution.parameters,
                templates.map { RecipeReference(it.name, it.version, it.bodyHash) },
                emptyList(),
                execution.lineage.pipelineReferences().map { RecipeReference(it.name, it.version, it.bodyHash) },
                unresolvedNodes,
            ),
            version,
            json.readTree(definition.bodyJson),
            request.delivery.operationId,
            leases.holderInstance,
            leases.catalogNowPlus(Duration.ZERO),
            deliveries.restoreEpoch(),
        )
    }

    private fun <T : Any> required(
        value: T?,
        message: String,
    ): T = value ?: throw DatapipelinesException(Lake.APPEND_PROVENANCE_MISSING, message)
}
