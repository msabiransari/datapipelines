package co.datapipelines.web.pipelines

import co.datapipelines.auth.RequiredScope
import co.datapipelines.auth.ScopeMatrix
import co.datapipelines.pipeline.NewPipeline
import co.datapipelines.pipeline.PipelineDeserializer
import co.datapipelines.pipeline.PipelineRecord
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.PipelineSerializer
import co.datapipelines.pipeline.PipelineValidator
import co.datapipelines.web.api.ApiErrors
import co.datapipelines.web.api.ApiResponse
import co.datapipelines.web.api.PagedData
import co.datapipelines.web.api.Pagination
import co.datapipelines.web.api.currentPrincipal
import com.fasterxml.jackson.databind.JsonNode
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * The pipeline CRUD and versioning endpoints (rest-api.md §5).
 *
 * Every write runs the **universal save-time validation** (pipeline-contract §2.8): deserialize →
 * §12 validate → store. Nothing invalid reaches the database, and a validation failure surfaces as
 * `400` with the §13.1 code the validator chose ([PipelineValidationException][co.datapipelines.pipeline.PipelineValidationException]
 * carries the full failure list in `details.failures`).
 *
 * Bodies are accepted as raw JSON (`String`) and bound by [PipelineDeserializer] rather than by a
 * Spring DTO: the pipeline body is the frozen pipeline-contract shape, and its deserializer is the
 * single place the wire rules (unknown fields, `type`/`output` enums, schema version) live.
 */
@RestController
@RequestMapping("/api/v1/pipelines")
class PipelinesController(
    private val pipelines: PipelineRepository,
    private val validator: PipelineValidator,
    private val bodies: PipelineBodies,
    private val deserializer: PipelineDeserializer = PipelineDeserializer(),
    private val serializer: PipelineSerializer = PipelineSerializer(),
) {
    /** §5.1 — create; the server assigns id, version 1, owner and timestamps. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiredScope(ScopeMatrix.RestOperation.MUTATE_PIPELINES_TEMPLATES)
    fun create(
        @RequestBody body: String,
    ): ApiResponse<JsonNode> {
        val principal = currentPrincipal()
        val workspaceId = principal.requireWorkspace().id
        val (pipeline, canonical) = validated(body, workspaceId)
        val record =
            pipelines.create(workspaceId, NewPipeline.from(pipeline, ownerId = principal.userId), canonical, principal.userId)
        return ApiResponse.of(PipelineResponses.full(record, canonical))
    }

    /** §5.2 — the latest version, full JSON including the server-assigned fields. */
    @GetMapping("/{id}")
    @RequiredScope(ScopeMatrix.RestOperation.READ_RESOURCES)
    fun get(
        @PathVariable id: UUID,
    ): ApiResponse<JsonNode> {
        val workspaceId = currentPrincipal().requireWorkspace().id
        val record = pipelines.findById(workspaceId, id) ?: throw ApiErrors.pipelineNotFound(id.toString())
        val body =
            pipelines.findVersionBody(workspaceId, record.id, record.currentVersion)
                ?: throw ApiErrors.pipelineNotFound(id.toString())
        return ApiResponse.of(PipelineResponses.full(record, body))
    }

    /** §5.3 — a specific version. */
    @GetMapping("/{id}/versions/{version}")
    @RequiredScope(ScopeMatrix.RestOperation.READ_RESOURCES)
    fun getVersion(
        @PathVariable id: UUID,
        @PathVariable version: Int,
    ): ApiResponse<JsonNode> {
        val workspaceId = currentPrincipal().requireWorkspace().id
        val record = pipelines.findById(workspaceId, id) ?: throw ApiErrors.pipelineNotFound(id.toString())
        val body =
            pipelines.findVersionBody(workspaceId, id, version)
                ?: throw ApiErrors.pipelineVersionNotFound(id.toString(), version)
        return ApiResponse.of(PipelineResponses.full(record.copy(currentVersion = version), body))
    }

    /** §5.4 — version metadata, newest first; no bodies. */
    @GetMapping("/{id}/versions")
    @RequiredScope(ScopeMatrix.RestOperation.READ_RESOURCES)
    fun versions(
        @PathVariable id: UUID,
    ): ApiResponse<List<Map<String, Any?>>> {
        val workspaceId = currentPrincipal().requireWorkspace().id
        pipelines.findById(workspaceId, id) ?: throw ApiErrors.pipelineNotFound(id.toString())
        return ApiResponse.of(pipelines.listVersions(workspaceId, id).map(PipelineResponses::versionSummary))
    }

    /** §5.5 — update, creating the next version. */
    @PutMapping("/{id}")
    @RequiredScope(ScopeMatrix.RestOperation.MUTATE_PIPELINES_TEMPLATES)
    fun update(
        @PathVariable id: UUID,
        @RequestBody body: String,
    ): ApiResponse<JsonNode> {
        val principal = currentPrincipal()
        val workspaceId = principal.requireWorkspace().id
        val (pipeline, canonical) = validated(body, workspaceId)
        val record =
            pipelines.update(workspaceId, id, pipeline, canonical, principal.userId)
                ?: throw ApiErrors.pipelineNotFound(id.toString())
        return ApiResponse.of(PipelineResponses.full(record, canonical))
    }

    /** §5.6 — soft delete. Historical executions remain queryable. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiredScope(ScopeMatrix.RestOperation.MUTATE_PIPELINES_TEMPLATES)
    fun delete(
        @PathVariable id: UUID,
    ) {
        val workspaceId = currentPrincipal().requireWorkspace().id
        if (!pipelines.softDelete(workspaceId, id)) throw ApiErrors.pipelineNotFound(id.toString())
    }

    /**
     * §5.7 — the listing, with the `owner` / `datasource` / `q` filters.
     *
     * Datasource filtering is pushed down to SQL via [PipelineBodies]; the `q` search remains
     * in-memory. [PipelineRepository.findAllByDatasource] is the root fix (carry-forward #6).
     */
    @GetMapping
    @RequiredScope(ScopeMatrix.RestOperation.READ_RESOURCES)
    fun list(
        @RequestParam(required = false) owner: UUID?,
        @RequestParam(required = false) datasource: String?,
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) offset: Int?,
        @RequestParam(required = false) limit: Int?,
    ): ApiResponse<PagedData<Map<String, Any?>>> {
        val page = Pagination.clampOffset(offset)
        val size = Pagination.clampLimit(limit)
        val workspaceId = currentPrincipal().requireWorkspace().id
        val scan = bodies.scan(workspaceId, owner, datasource)
        val filtered =
            scan.records
                .filter { q == null || scan.matchesQuery(it, q) }
        val items = filtered.drop(page).take(size).map(PipelineResponses::listEntry)
        val pagination = Pagination.of(page, size, filtered.size.toLong(), items.size)
        return ApiResponse.of(PagedData(items, pagination))
    }

    /** Deserialize → §12 validate → canonical JSON; the one save path (pipeline-contract §17.2). */
    private fun validated(
        body: String,
        workspaceId: UUID,
    ): Pair<co.datapipelines.pipeline.Pipeline, String> {
        val pipeline = validator.validateOrThrow(deserializer.readOrThrow(body), workspaceId)
        return pipeline to serializer.write(pipeline)
    }
}
