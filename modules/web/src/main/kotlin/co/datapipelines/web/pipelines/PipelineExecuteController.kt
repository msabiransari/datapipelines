package co.datapipelines.web.pipelines

import co.datapipelines.auth.RequiredScope
import co.datapipelines.auth.ScopeMatrix
import co.datapipelines.executor.ExecutorJson
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineService
import co.datapipelines.web.api.ApiErrors
import co.datapipelines.web.api.ApiException
import co.datapipelines.web.api.CorrelationId
import co.datapipelines.web.api.currentPrincipal
import co.datapipelines.web.config.idempotencyKey
import co.datapipelines.web.config.requestedResultTtlSeconds
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.UUID

/**
 * `POST /pipelines/{id}/execute` (rest-api.md §6.1) — the SSE execution stream.
 *
 * The handler itself only resolves and checks: pipeline (404 `pipeline.execution.not_found` for
 * unknown **or soft-deleted**, §5.6), optional `version`, `parameters`, the
 * `DP-Result-TTL-Seconds` and `Idempotency-Key` headers, and the request's correlation id. The
 * orchestration — stream, emitter, executor, idempotency — is [ExecutionStreamLauncher]'s.
 *
 * The body is read as a tree rather than bound to a DTO: `parameters` is an open map whose values
 * must reach `ParameterBinder` as `JsonNode`s exactly as sent (numbers stay numbers, strings stay
 * strings), which a typed DTO cannot express.
 */
@RestController
@RequestMapping("/api/v1/pipelines")
class PipelineExecuteController(
    private val pipelines: PipelineService,
    private val launcher: ExecutionStreamLauncher,
) {
    /**
     * §6.1 — returns the live event stream. Empty body means "latest version, no parameters".
     *
     * `produces` lists `application/json` alongside the stream type (gate C, B6): without it a
     * pre-stream error (404, 400 parameter binding) toward a client sending only
     * `Accept: text/event-stream` cannot render the §4.2 envelope and falls out as a 406.
     */
    @PostMapping(
        "/{id}/execute",
        produces = [MediaType.TEXT_EVENT_STREAM_VALUE, MediaType.APPLICATION_JSON_VALUE],
    )
    @RequiredScope(ScopeMatrix.RestOperation.EXECUTE_PIPELINE)
    fun execute(
        @PathVariable id: UUID,
        @RequestBody(required = false) body: String?,
        request: HttpServletRequest,
    ): SseEmitter {
        val principal = currentPrincipal()
        val workspaceId = principal.requireWorkspace().id
        val record = pipelines.findRecord(workspaceId, id) ?: throw ApiErrors.pipelineNotFound(id.toString())

        val tree = parseBody(body)
        val version = versionOf(tree, record.currentVersion)
        val parametersNode = parametersOf(tree)

        // D6: the version resolution is the aggregate's, shared with `pipelines_execute`.
        val executable =
            pipelines.findExecutable(workspaceId, record, version)
                ?: throw ApiErrors.pipelineVersionNotFound(id.toString(), version)
        val parameters: Map<String, JsonNode> = parametersNode.properties().associate { it.key to it.value }

        return launcher.launch(
            ExecuteLaunch(
                pipelineId = id,
                pipelineVersion = version,
                pipeline = executable.pipeline,
                principal = principal,
                parameters = parameters,
                parametersJson = MAPPER.writeValueAsString(parametersNode),
                correlationId = CorrelationId.currentUuid() ?: UUID.randomUUID(),
                resultTtlSeconds = request.requestedResultTtlSeconds(),
                idempotencyKey = request.idempotencyKey(),
            ),
        )
    }

    private fun parseBody(body: String?): ObjectNode {
        if (body.isNullOrBlank()) return MAPPER.createObjectNode()
        return MAPPER.readTree(body) as? ObjectNode
            ?: throw ApiException(
                PipelineErrorCodes.Execution.INVALID_PARAMETER_TYPE,
                "The execute request body must be a JSON object.",
                mapOf(ApiErrors.REASON to ApiErrors.MALFORMED_JSON),
            )
    }

    /** Optional `version` (§6.1); must be a positive integer — never silently clamped to latest. */
    private fun versionOf(
        tree: ObjectNode,
        current: Int,
    ): Int {
        val node = tree.get("version") ?: return current
        if (!node.isInt || node.asInt() < 1) {
            throw ApiException(
                PipelineErrorCodes.Execution.INVALID_PARAMETER_TYPE,
                "'version' must be a positive integer.",
                mapOf("version" to node.asText().take(MAX_ECHOED_VALUE_CHARS)),
            )
        }
        return node.asInt()
    }

    /** Optional `parameters` object; a non-object value is a type error, never coerced. */
    private fun parametersOf(tree: ObjectNode): ObjectNode {
        val node = tree.get("parameters") ?: return MAPPER.createObjectNode()
        return node as? ObjectNode
            ?: throw ApiException(
                PipelineErrorCodes.Execution.INVALID_PARAMETER_TYPE,
                "'parameters' must be a JSON object.",
                mapOf(ApiErrors.REASON to "parameters_not_an_object"),
            )
    }

    private companion object {
        val MAPPER = ExecutorJson.mapper

        /** Reflected client input is bounded before it reaches an error message. */
        const val MAX_ECHOED_VALUE_CHARS = 64
    }
}
