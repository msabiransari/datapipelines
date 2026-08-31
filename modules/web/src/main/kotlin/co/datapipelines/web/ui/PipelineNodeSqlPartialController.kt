package co.datapipelines.web.ui

import co.datapipelines.auth.RequiredScope
import co.datapipelines.auth.ScopeMatrix
import co.datapipelines.pipeline.NodeType
import co.datapipelines.pipeline.ParameterBinder
import co.datapipelines.pipeline.ParameterBindingResult
import co.datapipelines.pipeline.Pipeline
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineJson
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.templates.TemplateRenderException
import co.datapipelines.templates.TemplateRepository
import co.datapipelines.templates.WorkspaceTemplateEngines
import co.datapipelines.web.api.currentPrincipal
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import java.util.UUID

/**
 * The rendered SQL for ONE node (pipeline-editor.md §8). SQL is not stored in the
 * pipeline — contract §2.3 puts it in template entities — so this resolves the node's
 * PINNED {id, version} and renders it against the pipeline's own parameter context.
 *
 * Read-scoped and pipeline-aware on purpose: `/partials/templates/{id}/versions/{v}/render`
 * requires MUTATE_PIPELINES_TEMPLATES and takes a free-form context, so it would both
 * refuse a viewer and bypass the pipeline's parameter declarations.
 *
 * `parameters` is §6.3 WIRE JSON, produced by the page's own `coerceValue` — the same
 * function the execute path uses. `ParameterCoercion` is strict (a string for INTEGER is
 * a rejection, not a conversion), so raw form strings would fail here by design.
 */
@Controller
class PipelineNodeSqlPartialController(
    private val pipelines: PipelineRepository,
    private val templateEngines: WorkspaceTemplateEngines,
    private val templates: TemplateRepository,
    private val mapper: ObjectMapper = PipelineJson.objectMapper(),
) {
    @GetMapping("/partials/pipelines/{id}/nodes/{nodeId}/sql")
    @RequiredScope(ScopeMatrix.RestOperation.READ_RESOURCES)
    fun nodeSql(
        @PathVariable id: UUID,
        @PathVariable nodeId: String,
        @RequestParam(required = false) parameters: String?,
        model: Model,
    ): String {
        val workspaceId = currentPrincipal().requireWorkspace().id
        val record =
            pipelines.findById(workspaceId, id)
                ?: throw NoSuchElementException("Pipeline $id not found")
        val bodyJson =
            pipelines.findVersionBody(workspaceId, record.id, record.currentVersion)
                ?: throw NoSuchElementException("Pipeline $id version ${record.currentVersion} body not found")
        val pipeline = mapper.readValue(bodyJson, Pipeline::class.java)

        val node =
            pipeline.node(nodeId) ?: run {
                model.addAttribute("state", "node-missing")
                model.addAttribute("nodeId", nodeId)
                return VIEW
            }

        // Node.template is NEVER null — Node.fromJson binds `template ?: TemplateRef()`,
        // whose defaults are id = "", version = 0 — so a null check cannot fire and the
        // "no template" state is PIPELINE nodes only (contract §4.6 requires template
        // everywhere else). Branch on the type / the blank id, never on null.
        if (node.type == NodeType.PIPELINE || node.template.id.isBlank()) {
            model.addAttribute("state", "child-pipeline")
            model.addAttribute("childName", node.pipeline?.name ?: "")
            model.addAttribute("childVersion", node.pipeline?.version ?: 0)
            return VIEW
        }

        val inputs =
            parseOverrides(parameters) ?: run {
                model.addAttribute("state", "parameter-rejected")
                model.addAttribute(
                    "failures",
                    listOf(
                        mapOf(
                            "parameter" to "parameters",
                            "message" to "The parameters document is not a JSON object of §6.3 wire values.",
                        ),
                    ),
                )
                return VIEW
            }

        val binder = ParameterBinder(pipeline.parameters)
        when (val binding = binder.bind(inputs)) {
            is ParameterBindingResult.Bound -> {
                renderInto(workspaceId, node.template, binding.context.asMap(), emptyList(), model)
            }

            is ParameterBindingResult.Rejected -> {
                val coercionFailures =
                    binding.failures.filter { it.code == PipelineErrorCodes.Execution.INVALID_PARAMETER_TYPE }
                if (coercionFailures.isNotEmpty()) {
                    // A supplied override failed §6.3 coercion: name it and render NOTHING —
                    // SQL built from a value the executor would refuse is worse than no SQL.
                    model.addAttribute("state", "parameter-rejected")
                    model.addAttribute(
                        "failures",
                        coercionFailures.map {
                            mapOf(
                                "parameter" to (it.details["parameter"]?.toString() ?: ""),
                                "message" to it.message,
                            )
                        },
                    )
                    return VIEW
                }
                // Every failure is an unsupplied REQUIRED parameter: fall back to the
                // §12.6 dry-render context (defaults where present, type-appropriate
                // samples otherwise) and label which parameters were sampled.
                val sampled = binding.failures.mapNotNull { it.details["parameter"]?.toString() }
                renderInto(workspaceId, node.template, binder.sampleContext(), sampled, model)
            }
        }
        return VIEW
    }

    /** Null when the document is unparseable or not a JSON object. */
    private fun parseOverrides(parameters: String?): Map<String, JsonNode>? {
        if (parameters.isNullOrBlank()) return emptyMap()
        val tree =
            try {
                mapper.readTree(parameters)
            } catch (e: com.fasterxml.jackson.core.JsonProcessingException) {
                return null
            }
        if (!tree.isObject) return null
        return tree.properties().associate { it.key to it.value }
    }

    private fun renderInto(
        workspaceId: UUID,
        ref: co.datapipelines.pipeline.TemplateRef,
        context: Map<String, Any?>,
        sampledParameters: List<String>,
        model: Model,
    ) {
        val version =
            templates.lookupVersion(workspaceId, ref.id, ref.version) ?: run {
                model.addAttribute("state", "template-missing")
                model.addAttribute("templateId", ref.id)
                model.addAttribute("templateVersion", ref.version)
                return
            }
        try {
            val sql = templateEngines.engineFor(workspaceId).render(ref, context)
            model.addAttribute("state", "rendered")
            model.addAttribute("sql", sql)
            model.addAttribute("dialect", version.dialect.wire)
            model.addAttribute("templateId", ref.id)
            model.addAttribute("templateVersion", ref.version)
            model.addAttribute("sampledParameters", sampledParameters)
        } catch (e: TemplateRenderException) {
            model.addAttribute("state", "render-failed")
            model.addAttribute("message", e.message ?: "The template could not be rendered.")
        }
    }

    private companion object {
        const val VIEW = "partials/pipeline-node-sql"
    }
}
