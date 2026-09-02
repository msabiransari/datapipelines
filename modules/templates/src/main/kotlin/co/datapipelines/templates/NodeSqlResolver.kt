package co.datapipelines.templates

import co.datapipelines.pipeline.Node
import co.datapipelines.pipeline.NodeType
import co.datapipelines.pipeline.ParameterBinder
import co.datapipelines.pipeline.ParameterBindingResult
import co.datapipelines.pipeline.PipelineDeserializer
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.PipelineVersionDetail
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.pipeline.ValidationFailure
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.Dialect
import com.fasterxml.jackson.databind.JsonNode
import org.springframework.dao.InvalidDataAccessApiUsageException
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterUtils
import java.util.UUID

/** The outcome of one [NodeSqlResolver.resolve] call — 032's six states, carried as data. */
sealed interface NodeSqlResolution {
    /** The version the resolution read — E5: the caller always states version and status. */
    val version: PipelineVersionDetail

    /** The node resolved and its SQL rendered — the state the editor's panel shows. */
    data class Rendered(
        override val version: PipelineVersionDetail,
        val node: Node,
        val templateId: String,
        val templateVersion: Int,
        val dialect: Dialect,
        /** The SQL exactly as the template produced it (`:name` form) — what a human reads. */
        val sql: String,
        /** The positional `?` form with [bindValues] in order — what actually executes. */
        val positionalSql: String,
        val bindValues: List<Any?>,
        /** The bound context the render used (bound values, or the sample fallback). */
        val contextValues: Map<String, Any?>,
        /** Required parameters that were unsupplied and rendered from §12.6 sample values. */
        val sampledParameters: List<String>,
    ) : NodeSqlResolution

    /** No node with this id in the resolved version's body. */
    data class NodeMissing(
        override val version: PipelineVersionDetail,
        val nodeId: String,
    ) : NodeSqlResolution

    /** A PIPELINE node (or a node with no template pin) — it runs a child pipeline, not SQL. */
    data class ChildPipeline(
        override val version: PipelineVersionDetail,
        val childName: String,
        val childVersion: Int,
    ) : NodeSqlResolution

    /** A supplied override failed §6.3 coercion — named, and NOTHING is rendered. */
    data class ParameterRejected(
        override val version: PipelineVersionDetail,
        val failures: List<RejectedParameter>,
    ) : NodeSqlResolution {
        /** The §8.3 failure shape both surfaces report. */
        data class RejectedParameter(
            val parameter: String,
            val message: String,
        )
    }

    /** The pinned {id, version} does not resolve in this workspace. */
    data class TemplateMissing(
        override val version: PipelineVersionDetail,
        val templateId: String,
        val templateVersion: Int,
    ) : NodeSqlResolution

    /** The render itself failed (undefined variable, expression error). */
    data class RenderFailed(
        override val version: PipelineVersionDetail,
        val message: String,
    ) : NodeSqlResolution
}

/**
 * Resolves ONE pipeline node to its rendered SQL — the shared engine under the editor's SQL
 * panel (032, pipeline-editor.md §8) and `pipelines_execute_node` (mcp-server.md §6.2.20,
 * 037 B/E).
 *
 * Born as `PipelineNodeSqlPartialController`'s private flow; extracted (037 B) when the node-run
 * tool needed the identical steps 1–2 of the owner's definition — *"convert Freemarker into
 * SQL, pass parameters needed in the SQL"* — without forking them. The controller stays the
 * human surface (Thymeleaf model states, `@RequiredScope`); this service is the version- and
 * parameter-aware resolution both surfaces call. It lives in `templates` because rendering is
 * the heavy collaborator: the module already depends on `pipeline-contract` (repository,
 * binder, node model) and owns [WorkspaceTemplateEngines].
 *
 * ## Which version (037 E5)
 *
 * A pipeline may hold a RELEASED `current_version` and a DRAFT at `current_version + 1`
 * (versioning §3). An agent debugging is, by 035's own D4, almost always working on the draft
 * it just wrote — so [resolve] with no requested version takes **the DRAFT if one exists, else
 * the current released version**, and every outcome carries the [PipelineVersionDetail] it
 * resolved so the caller can always state which version and status it looked at. Defaulting to
 * the released version would have the agent debug stale code and be told nothing — a silent
 * wrong answer. The editor's panel takes the same default through this service, so the two
 * surfaces cannot disagree about which body an agent is looking at.
 *
 * ## The `:name` bind gate (042)
 *
 * A rendered template references declared parameters as `:name` (interpolating parameter
 * VALUES is refused at save). [NodeSqlResolution.Rendered] carries the translation of that
 * form into positional `?` + ordered values — the same translation `dag`'s internal
 * `SqlBindTranslator` performs for the executor. It is re-implemented here, not shared: that
 * object is `internal` to `dag` and the 037 fence froze `dag` to the `ResultRowReader` move —
 * the duplication is deliberate, fenced, and rides the same Spring [NamedParameterUtils], so
 * the semantics cannot fork. A `:name` the context does not declare raises the catalogued
 * `pipeline.node.sql_parameter_missing` BEFORE anything executes — a missing value bound as
 * null would be wrong data, not an error (042 C2).
 */
class NodeSqlResolver(
    private val pipelines: PipelineRepository,
    private val templates: TemplateRepository,
    private val engines: WorkspaceTemplateEngines,
) {
    private val deserializer = PipelineDeserializer()

    /**
     * Resolves [nodeId] of pipeline [pipelineId] in [workspaceId] to rendered SQL.
     *
     * @param requestedVersion the version to read; null means the E5 default (draft if one
     *   exists, else the current released version).
     * @param parameterInputs §6.3 wire-JSON overrides; null means none supplied (required
     *   parameters fall back to the §12.6 dry-render sample context, labelled).
     * @throws NoSuchElementException unknown pipeline or version — the caller's surface maps
     *   it (404 in REST, the not-found code in MCP).
     */
    fun resolve(
        workspaceId: UUID,
        pipelineId: UUID,
        nodeId: String,
        requestedVersion: Int?,
        parameterInputs: Map<String, JsonNode>?,
    ): NodeSqlResolution {
        val detail = resolveVersionDetail(workspaceId, pipelineId, requestedVersion)
        val body =
            pipelines.findVersionBody(workspaceId, pipelineId, detail.version)
                ?: throw NoSuchElementException("Pipeline $pipelineId version ${detail.version} body not found")
        val pipeline = deserializer.readOrThrow(body)
        val node = pipeline.node(nodeId) ?: return NodeSqlResolution.NodeMissing(detail, nodeId)

        // Node.template is NEVER null — Node.fromJson binds `template ?: TemplateRef()`, whose
        // defaults are id = "", version = 0 — so a null check cannot fire and the "no template"
        // state is PIPELINE nodes only (contract §4.6 requires template everywhere else).
        // Branch on the type / the blank id, never on null.
        if (node.type == NodeType.PIPELINE || node.template.id.isBlank()) {
            return NodeSqlResolution.ChildPipeline(detail, node.pipeline?.name ?: "", node.pipeline?.version ?: 0)
        }

        val binder = ParameterBinder(pipeline.parameters)
        return when (val binding = binder.bind(parameterInputs ?: emptyMap())) {
            is ParameterBindingResult.Bound -> render(workspaceId, detail, node, binding.context.asMap(), sampled = emptyList())

            is ParameterBindingResult.Rejected -> rejected(workspaceId, detail, node, binder, binding.failures)
        }
    }

    /** The E5 version pick: explicit → that version; absent → draft-if-exists, else current released. */
    private fun resolveVersionDetail(
        workspaceId: UUID,
        pipelineId: UUID,
        requestedVersion: Int?,
    ): PipelineVersionDetail =
        requestedVersion
            ?.let {
                pipelines.findVersionDetail(workspaceId, pipelineId, it)
                    ?: throw NoSuchElementException("Pipeline $pipelineId has no version $it")
            }
            ?: pipelines.findDraftDetail(workspaceId, pipelineId)
            ?: pipelines.findCurrentVersionDetail(workspaceId, pipelineId)
            ?: throw NoSuchElementException("Pipeline $pipelineId not found")

    /**
     * The context outcomes of §8.3: a supplied override that failed §6.3 coercion is named and
     * NOTHING is rendered (SQL built from a value the executor would refuse is worse than no
     * SQL); every remaining failure is an unsupplied REQUIRED parameter, so the §12.6
     * dry-render sample context renders instead, labelled with what was sampled.
     */
    private fun rejected(
        workspaceId: UUID,
        detail: PipelineVersionDetail,
        node: Node,
        binder: ParameterBinder,
        failures: List<ValidationFailure>,
    ): NodeSqlResolution {
        val coercionFailures = failures.filter { it.code == PipelineErrorCodes.Execution.INVALID_PARAMETER_TYPE }
        if (coercionFailures.isNotEmpty()) {
            return NodeSqlResolution.ParameterRejected(
                detail,
                coercionFailures.map {
                    NodeSqlResolution.ParameterRejected.RejectedParameter(
                        parameter = it.details["parameter"]?.toString() ?: "",
                        message = it.message,
                    )
                },
            )
        }
        val sampled = failures.mapNotNull { it.details["parameter"]?.toString() }
        return render(workspaceId, detail, node, binder.sampleContext(), sampled)
    }

    private fun render(
        workspaceId: UUID,
        detail: PipelineVersionDetail,
        node: Node,
        context: Map<String, Any?>,
        sampled: List<String>,
    ): NodeSqlResolution {
        val ref = node.template
        val templateVersion =
            templates.lookupVersion(workspaceId, ref.id, ref.version)
                ?: return NodeSqlResolution.TemplateMissing(detail, ref.id, ref.version)
        return try {
            val sql = engines.engineFor(workspaceId).render(ref, context)
            val (positionalSql, bindValues) = translateBinds(sql, context, ref)
            NodeSqlResolution.Rendered(
                version = detail,
                node = node,
                templateId = ref.id,
                templateVersion = ref.version,
                dialect = templateVersion.dialect,
                sql = sql,
                positionalSql = positionalSql,
                bindValues = bindValues,
                contextValues = context,
                sampledParameters = sampled,
            )
        } catch (e: TemplateRenderException) {
            NodeSqlResolution.RenderFailed(detail, e.message ?: "The template could not be rendered.")
        }
    }

    /**
     * `:name` → `?` + ordered values (042). The same Spring [NamedParameterUtils] translation
     * the executor's internal `SqlBindTranslator` performs — see the class KDoc for why it is
     * re-implemented rather than shared. A name with NO registered key throws
     * `InvalidDataAccessApiUsageException` instead of binding null; that is the 042 C2 gate and
     * becomes the catalogued `pipeline.node.sql_parameter_missing`.
     */
    private fun translateBinds(
        sql: String,
        values: Map<String, Any?>,
        ref: TemplateRef,
    ): Pair<String, List<Any?>> =
        try {
            NamedParameterUtils.substituteNamedParameters(sql, MapSqlParameterSource(values)) to
                NamedParameterUtils.buildValueArray(sql, values).toList()
        } catch (e: InvalidDataAccessApiUsageException) {
            throw DatapipelinesException(
                code = PipelineErrorCodes.Node.SQL_PARAMETER_MISSING,
                message = "The rendered SQL of template '${ref.id}' references a bind parameter the pipeline does not declare.",
                details = mapOf("template_id" to ref.id),
                cause = e,
            )
        }
}
