package co.datapipelines.web.pipelines

import co.datapipelines.pipeline.NewPipeline
import co.datapipelines.pipeline.PipelineDeserializer
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineJson
import co.datapipelines.pipeline.PipelineRecord
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.PipelineSerializer
import co.datapipelines.pipeline.PipelineValidator
import co.datapipelines.pipeline.ValidationResult
import co.datapipelines.web.api.ApiErrors
import co.datapipelines.web.api.ApiException
import com.fasterxml.jackson.databind.node.ObjectNode
import org.springframework.dao.DuplicateKeyException
import java.util.UUID

/**
 * The pipeline **import** act (rest-api.md §5.8), lifted out of [PipelineTransferController] so a
 * second caller can perform it.
 *
 * The logic is unchanged and this is the only copy: the controller now supplies the workspace and
 * actor it reads off the request principal, and the D9 example seeder
 * (`ExampleContentSeeder`) supplies the freshly provisioned personal workspace and its owner.
 * Extracting it — rather than letting the seeder re-implement an import — is what keeps the §12
 * validation, the id-collision handling and the §13.2 error re-labelling identical on both paths.
 * The extraction is why `modules/web` is in this slice's write-set at all.
 *
 * Everything HTTP stays in the controller: this returns [Imported.created] and lets the caller
 * decide between `201` and `200`.
 */
class PipelineImportService(
    private val pipelines: PipelineRepository,
    private val validator: PipelineValidator,
    private val deserializer: PipelineDeserializer = PipelineDeserializer(),
    private val serializer: PipelineSerializer = PipelineSerializer(),
) {
    /** What the import stored, plus whether it was a create (`201`) or a new version (`200`). */
    data class Imported(
        val record: PipelineRecord,
        val canonical: String,
        val created: Boolean,
    )

    /**
     * Imports [body] — the portable pipeline JSON — into [workspaceId] on behalf of [actorId].
     *
     * @throws ApiException / `PipelineValidationException` exactly as the endpoint always has.
     */
    @Suppress("ThrowsCount") // a boundary maps each distinct failure to its own catalogued 4xx
    fun import(
        body: String,
        workspaceId: UUID,
        actorId: UUID,
    ): Imported {
        val tree = MAPPER.readTree(body) as? ObjectNode ?: throw ApiErrors.malformedPipelineBody(IllegalArgumentException("not an object"))
        val requestedId =
            tree.remove("id")?.takeIf { it.isTextual }?.let { raw ->
                runCatching { UUID.fromString(raw.asText()) }.getOrNull()
                    ?: throw ApiException(
                        PipelineErrorCodes.Execution.INVALID_PARAMETER_TYPE,
                        "The import payload's 'id' is not a UUID.",
                        mapOf("id" to raw.asText().take(MAX_ECHOED_ID_CHARS)),
                    )
            }
        SERVER_FIELDS.forEach(tree::remove)

        val pipeline = deserializer.readOrThrow(MAPPER.writeValueAsString(tree))
        importValidation(pipeline.name, validator.validate(pipeline, workspaceId)).orThrow()
        val canonical = serializer.write(pipeline)

        val existing = requestedId?.let { pipelines.findById(workspaceId, it) }
        val record =
            if (existing != null) {
                pipelines.update(workspaceId, existing.id, pipeline, canonical, actorId)
                    ?: throw ApiErrors.pipelineNotFound(existing.id.toString())
            } else {
                try {
                    pipelines.create(
                        workspaceId,
                        NewPipeline.from(pipeline, ownerId = actorId, id = requestedId ?: UUID.randomUUID()),
                        canonical,
                        actorId,
                    )
                } catch (e: DuplicateKeyException) {
                    throw ApiException(
                        PipelineErrorCodes.Import.VERSION_CONFLICT,
                        "Pipeline id '$requestedId' collides with a deleted pipeline's retained id.",
                        mapOf("pipeline_id" to requestedId.toString()),
                        e,
                    )
                }
            }
        return Imported(record = record, canonical = canonical, created = existing == null)
    }

    /**
     * Re-labels environment-dependency failures with the §13.2 import codes; every other failure
     * keeps its §13.1 code and the exhaustive list (§17.2).
     *
     * ## Mixed failures (gate C, F10)
     * When missing-datasource AND missing-template failures occur together, the primary code is
     * `pipeline.import.missing_datasource` and `details` carries **both** sets —
     * `missing_datasources` and `missing_templates` — so one response tells the author everything
     * the environment lacks. The all-same-kind case keeps the single mapped code with its set in
     * details under the matching key.
     */
    private fun importValidation(
        name: String,
        result: ValidationResult,
    ): ValidationResult {
        if (result.isValid) return result
        val missingDatasource = result.withCode(PipelineErrorCodes.Validation.UNKNOWN_DATASOURCE)
        val missingTemplate =
            result.withCode(PipelineErrorCodes.Validation.TEMPLATE_NOT_FOUND) +
                result.withCode(PipelineErrorCodes.Validation.TEMPLATE_VERSION_NOT_FOUND)
        val rest = result.failures - missingDatasource.toSet() - missingTemplate.toSet()
        if (rest.isNotEmpty()) return result
        val primaryCode =
            if (missingDatasource.isNotEmpty()) {
                PipelineErrorCodes.Import.MISSING_DATASOURCE
            } else {
                PipelineErrorCodes.Import.MISSING_TEMPLATE
            }
        throw ApiException(
            primaryCode,
            "Imported pipeline '$name' has unmet dependencies in this environment.",
            buildMap {
                // `path` is the one field every ValidationFailure guarantees; detail keys vary by rule.
                if (missingDatasource.isNotEmpty()) put("missing_datasources", missingDatasource.map { it.path })
                if (missingTemplate.isNotEmpty()) put("missing_templates", missingTemplate.map { it.path })
                put(
                    "failures",
                    (missingDatasource + missingTemplate).map { mapOf("code" to it.code, "path" to it.path, "message" to it.message) },
                )
            },
        )
    }

    private companion object {
        val MAPPER = PipelineJson.objectMapper()

        /** Server-assigned fields an import payload may carry from an export; never authoritative. */
        val SERVER_FIELDS = listOf("version", "owner", "created_at", "updated_at")

        /** Reflected client input is bounded before it reaches an error message. */
        const val MAX_ECHOED_ID_CHARS = 64
    }
}
