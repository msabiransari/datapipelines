package co.datapipelines.web.ui

import co.datapipelines.auth.RequiredScope
import co.datapipelines.auth.ScopeMatrix
import co.datapipelines.pipeline.PipelineJson
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.templates.NodeSqlResolution
import co.datapipelines.templates.NodeSqlResolver
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
 * Since 037 B the resolution itself lives in [NodeSqlResolver], shared with the
 * `pipelines_execute_node` tool; this controller owns the human half only — the principal's
 * workspace, the `parameters` query-string parsing, and the Thymeleaf model states. Its
 * render tests are the proof the editor's SQL panel kept working through the extraction.
 *
 * Read-scoped and pipeline-aware on purpose: `/partials/templates/{id}/versions/{v}/render`
 * requires MUTATE_PIPELINES_TEMPLATES and takes a free-form context, so it would both
 * refuse a viewer and bypass the pipeline's parameter declarations.
 *
 * `parameters` is §6.3 WIRE JSON, produced by the page's own `coerceValue` — the same
 * function the execute path uses. `ParameterCoercion` is strict (a string for INTEGER is
 * a rejection, not a conversion), so raw form strings would fail here by design.
 *
 * The resolver is constructed here rather than injected: it is a stateless service over the
 * three collaborators this controller already takes, and no wiring change may accompany the
 * 037 fence.
 */
@Controller
class PipelineNodeSqlPartialController(
    pipelines: PipelineRepository,
    templateEngines: WorkspaceTemplateEngines,
    templates: TemplateRepository,
) {
    private val resolver = NodeSqlResolver(pipelines, templates, templateEngines)

    // The tree mapper for the `parameters` query value — the contract's own (the stored-body
    // deserializer lives in the resolver now, through the same PipelineDeserializer every
    // production reader uses).
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

        // The E5 version default (draft-if-exists) applies here exactly as it does for the
        // node-run tool, so the two surfaces cannot disagree about which body the panel and
        // the agent are each looking at.
        when (val inputs = parseOverrides(parameters)) {
            is Overrides.Malformed -> {
                rejectParameters(model, "parameters", "The parameters document is not valid §6.3 wire JSON: ${inputs.reason}")
                return VIEW
            }

            is Overrides.Parsed -> {
                val resolution = resolver.resolve(workspaceId, id, nodeId, requestedVersion = null, parameterInputs = inputs.inputs)
                render(resolution, model)
            }
        }
        return VIEW
    }

    private fun render(
        resolution: NodeSqlResolution,
        model: Model,
    ) {
        when (resolution) {
            is NodeSqlResolution.NodeMissing -> {
                model.addAttribute("state", "node-missing")
                model.addAttribute("nodeId", resolution.nodeId)
            }

            is NodeSqlResolution.ChildPipeline -> {
                model.addAttribute("state", "child-pipeline")
                model.addAttribute("childName", resolution.childName)
                model.addAttribute("childVersion", resolution.childVersion)
            }

            is NodeSqlResolution.ParameterRejected -> {
                val first = resolution.failures.first()
                rejectParameters(model, first.parameter, first.message)
            }

            is NodeSqlResolution.TemplateMissing -> {
                model.addAttribute("state", "template-missing")
                model.addAttribute("templateId", resolution.templateId)
                model.addAttribute("templateVersion", resolution.templateVersion)
            }

            is NodeSqlResolution.RenderFailed -> {
                model.addAttribute("state", "render-failed")
                model.addAttribute("message", resolution.message)
            }

            is NodeSqlResolution.Rendered -> {
                model.addAttribute("state", "rendered")
                model.addAttribute("sql", resolution.sql)
                model.addAttribute("dialect", resolution.dialect.wire)
                model.addAttribute("templateId", resolution.templateId)
                model.addAttribute("templateVersion", resolution.templateVersion)
                model.addAttribute("sampledParameters", resolution.sampledParameters)
            }
        }
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

    private companion object {
        const val VIEW = "partials/pipeline-node-sql"
    }
}
