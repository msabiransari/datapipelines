package co.datapipelines.web.ui

import co.datapipelines.auth.RequiredScope
import co.datapipelines.auth.ScopeMatrix
import co.datapipelines.pipeline.Node
import co.datapipelines.pipeline.NodeType
import co.datapipelines.pipeline.ParameterBinder
import co.datapipelines.pipeline.ParameterBindingResult
import co.datapipelines.pipeline.Pipeline
import co.datapipelines.pipeline.PipelineDeserializer
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineJson
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.pipeline.ValidationFailure
import co.datapipelines.templates.TemplateRenderException
import co.datapipelines.templates.TemplateRepository
import co.datapipelines.templates.WorkspaceTemplateEngines
import co.datapipelines.web.api.currentPrincipal
import com.fasterxml.jackson.core.JsonProcessingException
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
) {
    // NOT constructor parameters: Spring injects the app's servlet ObjectMapper into an
    // ObjectMapper-typed parameter even when it has a default, and that mapper lacks
    // NodeOutputModule — reading a stored body with it 500s on `output` (caught by the
    // demo smoke test). The body goes through the same deserializer every production
    // reader uses; the tree mapper (for the `parameters` query value) is the contract's.
    private val deserializer = PipelineDeserializer()
    private val mapper: ObjectMapper = PipelineJson.objectMapper()

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
        val pipeline = deserializer.readOrThrow(bodyJson)
        val node = pipeline.node(nodeId)

        when {
            node == null -> {
                model.addAttribute("state", "node-missing")
                model.addAttribute("nodeId", nodeId)
            }

            // Node.template is NEVER null — Node.fromJson binds `template ?: TemplateRef()`,
            // whose defaults are id = "", version = 0 — so a null check cannot fire and the
            // "no template" state is PIPELINE nodes only (contract §4.6 requires template
            // everywhere else). Branch on the type / the blank id, never on null.
            node.type == NodeType.PIPELINE || node.template.id.isBlank() -> {
                model.addAttribute("state", "child-pipeline")
                model.addAttribute("childName", node.pipeline?.name ?: "")
                model.addAttribute("childVersion", node.pipeline?.version ?: 0)
            }

            else -> {
                renderWithContext(workspaceId, pipeline, node, parameters, model)
            }
        }
        return VIEW
    }

    /** Parse the overrides, bind them, and render — the three context outcomes of §8.3. */
    private fun renderWithContext(
        workspaceId: UUID,
        pipeline: Pipeline,
        node: Node,
        parameters: String?,
        model: Model,
    ) {
        val inputs =
            when (val parsed = parseOverrides(parameters)) {
                is Overrides.Malformed -> {
                    rejectParameters(model, "parameters", "The parameters document is not valid §6.3 wire JSON: ${parsed.reason}")
                    return
                }

                is Overrides.Parsed -> {
                    parsed.inputs
                }
            }

        val binder = ParameterBinder(pipeline.parameters)
        when (val binding = binder.bind(inputs)) {
            is ParameterBindingResult.Bound -> {
                renderInto(workspaceId, node.template, binding.context.asMap(), emptyList(), model)
            }

            is ParameterBindingResult.Rejected -> {
                handleRejected(workspaceId, node, binder, binding.failures, model)
            }
        }
    }

    private fun handleRejected(
        workspaceId: UUID,
        node: Node,
        binder: ParameterBinder,
        failures: List<ValidationFailure>,
        model: Model,
    ) {
        val coercionFailures =
            failures.filter { it.code == PipelineErrorCodes.Execution.INVALID_PARAMETER_TYPE }
        if (coercionFailures.isNotEmpty()) {
            // A supplied override failed §6.3 coercion: name it and render NOTHING —
            // SQL built from a value the executor would refuse is worse than no SQL.
            val first = coercionFailures.first()
            rejectParameters(model, first.details["parameter"]?.toString() ?: "", first.message)
            return
        }
        // Every failure is an unsupplied REQUIRED parameter: fall back to the
        // §12.6 dry-render context (defaults where present, type-appropriate
        // samples otherwise) and label which parameters were sampled.
        val sampled = failures.mapNotNull { it.details["parameter"]?.toString() }
        renderInto(workspaceId, node.template, binder.sampleContext(), sampled, model)
    }

    private fun rejectParameters(
        model: Model,
        parameter: String,
        message: String,
    ) {
        model.addAttribute("state", "parameter-rejected")
        model.addAttribute("failures", listOf(mapOf("parameter" to parameter, "message" to message)))
    }

    /** The `parameters` query value as binder inputs; [Overrides.Malformed] carries the parse reason. */
    private fun parseOverrides(parameters: String?): Overrides {
        if (parameters.isNullOrBlank()) return Overrides.Parsed(emptyMap())
        val tree =
            try {
                mapper.readTree(parameters)
            } catch (e: JsonProcessingException) {
                return Overrides.Malformed(e.originalMessage ?: "unparseable JSON")
            }
        if (!tree.isObject) return Overrides.Malformed("expected a JSON object of parameter values")
        return Overrides.Parsed(tree.properties().associate { it.key to it.value })
    }

    private sealed interface Overrides {
        data class Parsed(
            val inputs: Map<String, JsonNode>,
        ) : Overrides

        data class Malformed(
            val reason: String,
        ) : Overrides
    }

    private fun renderInto(
        workspaceId: UUID,
        ref: TemplateRef,
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
