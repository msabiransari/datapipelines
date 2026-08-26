package co.datapipelines.web.pipelines

import co.datapipelines.auth.RequiredScope
import co.datapipelines.auth.ScopeMatrix
import co.datapipelines.pipeline.NewPipeline
import co.datapipelines.pipeline.PipelineDeserializer
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineJson
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.PipelineSerializer
import co.datapipelines.pipeline.PipelineValidator
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.pipeline.ValidationResult
import co.datapipelines.templates.Template
import co.datapipelines.templates.TemplateRepository
import co.datapipelines.web.api.ApiErrors
import co.datapipelines.web.api.ApiException
import co.datapipelines.web.api.ApiResponse
import co.datapipelines.web.api.currentPrincipal
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.springframework.dao.DuplicateKeyException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

/**
 * Pipeline import and export (rest-api.md §5.8/§5.9) — the environment-promotion pair.
 *
 * ## Import
 * The body is the portable pipeline JSON, optionally carrying `id` (pipeline-contract §11.3: a
 * promoted pipeline keeps its identity). Any server-assigned fields present are stripped before
 * validation — the portable body does not include them and the deserializer is under no obligation
 * to tolerate them. An `id` that already exists updates that pipeline (a new version, `200`);
 * otherwise the pipeline is created under that id (`201`). An id colliding with a **soft-deleted**
 * pipeline is `pipeline.import.version_conflict` (§13.2) — the name/id stay taken by design
 * (metadata-db §4.4).
 *
 * Dependency failures are reported with the §13.2 import codes, not the §13.1 save-time codes:
 * `unknown_datasource` / `template_not_found` / `template_version_not_found` from the validator map
 * to `pipeline.import.missing_datasource` / `pipeline.import.missing_template` (rest-api §5.8).
 *
 * ## Export
 * `include_templates=true` (the default) bundles the full template JSON of every referenced
 * template version — the pipeline's direct node references **plus** the transitive library-import
 * closure, so the bundle actually renders in the target environment.
 */
@RestController
@RequestMapping("/api/v1/pipelines")
class PipelineTransferController(
    private val pipelines: PipelineRepository,
    private val templates: TemplateRepository,
    private val validator: PipelineValidator,
    private val deserializer: PipelineDeserializer = PipelineDeserializer(),
    private val serializer: PipelineSerializer = PipelineSerializer(),
) {
    /** §5.8 — import. `201` for a new pipeline, `200` when an existing id gained a version. */
    @Suppress("ThrowsCount") // a boundary maps each distinct failure to its own catalogued 4xx
    @PostMapping("/import")
    @RequiredScope(ScopeMatrix.RestOperation.MUTATE_PIPELINES_TEMPLATES)
    fun import(
        @RequestBody body: String,
    ): ResponseEntity<ApiResponse<JsonNode>> {
        val principal = currentPrincipal()
        val workspaceId = principal.requireWorkspace().id
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
                pipelines.update(workspaceId, existing.id, pipeline, canonical, principal.userId)
                    ?: throw ApiErrors.pipelineNotFound(existing.id.toString())
            } else {
                try {
                    pipelines.create(
                        workspaceId,
                        NewPipeline.from(pipeline, ownerId = principal.userId, id = requestedId ?: UUID.randomUUID()),
                        canonical,
                        principal.userId,
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
        val status = if (existing != null) HttpStatus.OK else HttpStatus.CREATED
        return ResponseEntity.status(status).body(ApiResponse.of(PipelineResponses.full(record, canonical)))
    }

    /** §5.9 — export bundle: pipeline, referenced template versions, manifest. */
    @GetMapping("/{id}/export")
    @RequiredScope(ScopeMatrix.RestOperation.READ_RESOURCES)
    fun export(
        @PathVariable id: UUID,
        @RequestParam(name = "include_templates", required = false) includeTemplates: Boolean = true,
    ): ApiResponse<Map<String, Any?>> {
        val workspaceId = currentPrincipal().requireWorkspace().id
        val record = pipelines.findById(workspaceId, id) ?: throw ApiErrors.pipelineNotFound(id.toString())
        val body =
            pipelines.findVersionBody(workspaceId, id, record.currentVersion)
                ?: throw ApiErrors.pipelineNotFound(id.toString())
        val pipeline = deserializer.readOrThrow(body)

        val bundled = if (includeTemplates) referencedTemplates(workspaceId, pipeline.nodes.map { it.template }) else emptyList()
        val data =
            mapOf(
                "pipeline" to PipelineResponses.full(record, body),
                "templates" to bundled,
                "manifest" to
                    mapOf(
                        "pipeline_id" to record.id.toString(),
                        "pipeline_version" to record.currentVersion,
                        "template_count" to bundled.size,
                        "exported_at" to Instant.now().toString(),
                    ),
            )
        return ApiResponse.of(data)
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

    /** Direct node references plus the transitive library-import closure, deduplicated. */
    private fun referencedTemplates(
        workspaceId: UUID,
        refs: List<TemplateRef>,
    ): List<Template> {
        val seen = mutableSetOf<String>()
        val queue = ArrayDeque(refs)
        val found = mutableListOf<Template>()
        while (queue.isNotEmpty()) {
            collect(workspaceId, queue.removeFirst(), seen, queue, found)
        }
        return found
    }

    private fun collect(
        workspaceId: UUID,
        ref: TemplateRef,
        seen: MutableSet<String>,
        queue: ArrayDeque<TemplateRef>,
        found: MutableList<Template>,
    ) {
        if (!seen.add(ref.key)) return
        val version = templates.lookupVersion(workspaceId, ref.id, ref.version) ?: return
        templates.findVersion(workspaceId, ref.id, ref.version)?.let(found::add)
        version.imports.forEach { queue.addLast(TemplateRef(it.id, it.version)) }
    }

    private companion object {
        val MAPPER = PipelineJson.objectMapper()

        /** Server-assigned fields an import payload may carry from an export; never authoritative. */
        val SERVER_FIELDS = listOf("version", "owner", "created_at", "updated_at")

        /** Reflected client input is bounded before it reaches an error message. */
        const val MAX_ECHOED_ID_CHARS = 64
    }
}
