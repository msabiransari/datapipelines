package co.datapipelines.lake.append

import co.datapipelines.lake.delivery.DeliveryManifest
import co.datapipelines.pipeline.Pipeline
import co.datapipelines.pipeline.PipelineJson
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.time.Instant
import java.util.UUID

/** A pinned dependency as actually resolved for this execution, never a mutable name-only reference. */
data class RecipeReference(
    val name: String,
    val version: Int,
    val bodyHash: String,
)

/** The execution snapshot and lineage; [resolvedParameters] uses canonical parameter wire values. */
data class RecipePipeline(
    val id: UUID,
    val version: Int,
    val draft: Boolean,
    val bodyHash: String,
    val pipeline: Pipeline,
    val executedBody: JsonNode,
    val resolvedParameters: Map<String, JsonNode>,
    val templates: List<RecipeReference>,
    val parameterSets: List<RecipeReference>,
)

/** All provenance required before commit; no storage configuration, credentials or ambient SQL context. */
data class RecipePublication(
    val delivery: DeliveryManifest,
    val pipeline: RecipePipeline,
    val appliedDefinitionVersion: Int,
    val appliedDefinitionBody: JsonNode,
    val operationId: UUID,
    val worker: String,
    val recordedAt: Instant,
    val restoreEpoch: UUID,
)

/** A safe copy of the recovery recipe. Hashes refer to the executed body; redaction disables exact replay. */
class RecipeDocument(
    private val redactor: RecipeRedactor,
) {
    private val mapper = PipelineJson.objectMapper()

    fun build(publication: RecipePublication): ObjectNode {
        val execution = publication.pipeline
        require(execution.version > 0 && publication.appliedDefinitionVersion > 0)
        require(SHA256.matches(execution.bodyHash)) { "Recipe pipeline hash must be SHA-256." }
        require(publication.worker.isNotBlank())
        val safe = redactor.redact(execution.pipeline, execution.executedBody, execution.resolvedParameters)
        val root = mapper.createObjectNode()
        root.put("format_version", 1)
        root.put("operation_id", publication.operationId.toString())
        root.put("worker", publication.worker)
        root.put("recorded_at", publication.recordedAt.toString())
        root.put("restore_epoch", publication.restoreEpoch.toString())
        root.put("exact_replay_possible", safe.exactReplayPossible)
        root.set<JsonNode>("redacted_paths", mapper.valueToTree(safe.redactedPaths))
        root.putObject("delivery").apply {
            put("feed", publication.delivery.key.feed)
            put("id", publication.delivery.key.id)
            put("fingerprint", publication.delivery.fingerprint)
            set<JsonNode>("manifest", mapper.valueToTree(publication.delivery.objects))
        }
        root.putObject("pipeline").apply {
            put("id", execution.id.toString())
            put("version", execution.version)
            put("draft", execution.draft)
            put("body_hash", execution.bodyHash)
            put("body_redacted", safe.body != execution.executedBody)
            set<JsonNode>("body", safe.body)
        }
        root.set<JsonNode>("resolved_parameters", safe.parameters)
        root.set<JsonNode>("templates", references(execution.templates))
        root.set<JsonNode>("parameter_sets", references(execution.parameterSets))
        root.putObject("applied_definition").apply {
            put("version", publication.appliedDefinitionVersion)
            set<JsonNode>("body", publication.appliedDefinitionBody.deepCopy())
        }
        return root
    }

    private fun references(references: List<RecipeReference>): JsonNode {
        val array = mapper.createArrayNode()
        references.forEach { reference ->
            require(reference.name.isNotBlank() && reference.version > 0 && SHA256.matches(reference.bodyHash))
            val item = array.addObject()
            item.put("name", reference.name)
            item.put("version", reference.version)
            item.put("body_hash", reference.bodyHash)
        }
        return array
    }

    private companion object {
        val SHA256 = Regex("[a-f0-9]{64}")
    }
}
