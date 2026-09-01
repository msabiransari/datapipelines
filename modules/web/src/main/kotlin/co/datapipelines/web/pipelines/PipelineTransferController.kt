package co.datapipelines.web.pipelines

import co.datapipelines.auth.RequiredScope
import co.datapipelines.auth.ScopeMatrix
import co.datapipelines.pipeline.PipelineDeserializer
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.templates.Template
import co.datapipelines.templates.TemplateRepository
import co.datapipelines.web.api.ApiErrors
import co.datapipelines.web.api.ApiResponse
import co.datapipelines.web.api.currentPrincipal
import com.fasterxml.jackson.databind.JsonNode
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
    private val importService: PipelineImportService,
    private val deserializer: PipelineDeserializer = PipelineDeserializer(),
) {
    /**
     * §5.8 — import. `201` for a new pipeline, `200` when an existing id gained a version.
     *
     * The act itself lives in [PipelineImportService] so the D9 workspace seeder performs the
     * SAME import; everything left here is the HTTP edge — principal → (workspace, actor), and
     * the created/updated distinction → status code.
     */
    @PostMapping("/import")
    @RequiredScope(ScopeMatrix.RestOperation.MUTATE_PIPELINES_TEMPLATES)
    fun import(
        @RequestBody body: String,
    ): ResponseEntity<ApiResponse<JsonNode>> {
        val principal = currentPrincipal()
        val imported = importService.import(body, principal.requireWorkspace().id, principal.userId)
        val status = if (imported.created) HttpStatus.CREATED else HttpStatus.OK
        return ResponseEntity.status(status).body(ApiResponse.of(PipelineResponses.full(imported.record, imported.canonical)))
    }

    /** §5.9 — export bundle: pipeline, referenced template versions, manifest. */
    @Suppress("ThrowsCount") // the misses are the same catalogued 404 for different absent reads
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
        // The lifecycle fields an import honors (versioning §9.2): the version's number,
        // hash and release timestamp ride the exported pipeline object so a preserved-version
        // import on the target can verify and re-stamp them.
        val version =
            pipelines.findCurrentVersionDetail(workspaceId, id)
                ?: throw ApiErrors.pipelineNotFound(id.toString())

        val bundled = if (includeTemplates) referencedTemplates(workspaceId, pipeline.nodes.map { it.template }) else emptyList()
        val data =
            mapOf(
                "pipeline" to PipelineResponses.full(record, body, version),
                "templates" to bundled,
                "manifest" to
                    mapOf(
                        "pipeline_id" to record.id.toString(),
                        "pipeline_version" to record.currentVersion,
                        "pipeline_body_hash" to version.bodyHash,
                        "template_count" to bundled.size,
                        "exported_at" to Instant.now().toString(),
                    ),
            )
        return ApiResponse.of(data)
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
}
