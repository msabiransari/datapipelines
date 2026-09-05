package co.datapipelines.mcp

import co.datapipelines.application.endpoints.EndpointPublishService
import co.datapipelines.application.endpoints.PublishedEndpoint
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.typesystem.DatapipelinesException
import io.modelcontextprotocol.spec.McpSchema

/**
 * The published-endpoint tools (mcp-server.md §6.2, published-endpoints design §6): `endpoints_
 * create`, `endpoints_list`, `endpoints_get`, `endpoints_delete`.
 *
 * ## One validated path
 *
 * Every one of these calls [EndpointPublishService] — the same object `POST /api/v1/endpoints`
 * calls, with the same read-only rule, the same ambiguity refusal and the same audit. That is
 * 049's rule again: two entry points, one validated path. Publishing is the surface where it
 * matters most, because the read-only rule is what makes serving a pipeline over `GET` safe, and
 * a publish that skipped it because it arrived over MCP would be a hole in exactly that.
 *
 * What lives on this side of the boundary is the tool schema, the argument binding and the result
 * shape — nothing about what a publish MEANS.
 *
 * ## Why there is no `api_keys_create`
 *
 * The design assumed one exists and gains `kind`/`bindings`; the tree has no such tool, and this
 * round does not add one. Minting an API key returns a live credential, and a credential returned
 * through a tool call transits the agent's context, its transcript and whatever the client logs —
 * the hazard `datasources_create` documents for a password it merely ACCEPTS. Creating an
 * agent-facing credential mint is a decision the design did not actually take, so endpoint keys
 * are minted over REST or in the UI and bound there; these tools publish, read and unpublish.
 */
object EndpointsTools {
    /** The four tools, in `tools/list` order. */
    fun all(
        publishing: EndpointPublishService,
        pipelines: PipelineRepository,
    ): List<McpTool> =
        listOf(
            CreateTool(publishing, pipelines),
            ListTool(publishing, pipelines),
            GetTool(publishing, pipelines),
            DeleteTool(publishing),
        )

    /**
     * The wire shape of one endpoint. The pipeline travels by NAME — the portable identity
     * (pipeline-contract §11.1) — so an agent reading this can act on it in any environment.
     */
    internal fun PublishedEndpoint.toResponse(pipelines: PipelineRepository): Map<String, Any?> =
        mapOf(
            "path" to pathPattern,
            "pipeline" to pipelines.findById(workspaceId, pipelineId)?.name,
            "timeout_seconds" to timeoutSeconds,
            "description" to description,
            "enabled" to isEnabled,
            "path_variables" to pathVariables,
            "url" to "/api/x$pathPattern",
        )

    class CreateTool(
        private val publishing: EndpointPublishService,
        private val pipelines: PipelineRepository,
    ) : McpTool {
        override val definition: McpSchema.Tool =
            McpTools.tool(
                name = "endpoints_create",
                description =
                    "Publish a released pipeline as a GET endpoint under /api/x. The pipeline must have a RELEASED " +
                        "version and must be side-effect-free: every node DQL into tempdb or the caller, transitively " +
                        "through PIPELINE nodes. A DML/DDL node, or a DQL node writing back to a datasource, is refused " +
                        "with endpoint.pipeline_not_readonly naming the node — that rule is what makes serving over GET " +
                        "safe, since GET is retried, preloaded and crawled. " +
                        "path is 1-10 segments, each a literal [a-z0-9][a-z0-9_.-]{0,63} or a {variable} naming a " +
                        "declared parameter; remaining parameters come from the query string. A path that could match " +
                        "the same URL as an existing one is refused (endpoint.path_conflict) rather than resolved by " +
                        "precedence. Calling the endpoint needs an API key bound to it — mint and bind one over REST or " +
                        "in the UI (auth.md §7.7); an unbound endpoint accepts user keys with the execute scope.",
                schema = CREATE_SCHEMA,
            )

        override fun call(
            args: McpArguments,
            ctx: McpToolContext,
        ): Any =
            publishing
                .publish(
                    principal = ctx.principal,
                    pathPattern = args.requiredString("path"),
                    pipelineName = args.requiredString("pipeline"),
                    // Absent means "the configured default", which the service applies — so the
                    // sentinel is null, not a number this layer would have to keep in step with
                    // datapipelines.endpoints.timeout-default-seconds.
                    timeoutSeconds =
                        args.rawMap()["timeout_seconds"]?.let { args.int("timeout_seconds", MIN_TIMEOUT, MIN_TIMEOUT, MAX_TIMEOUT) },
                    description = args.string("description").orEmpty(),
                ).toResponse(pipelines)

        private companion object {
            const val MIN_TIMEOUT = 1
            const val MAX_TIMEOUT = 3600

            val CREATE_SCHEMA =
                """
                {
                  "type": "object",
                  "required": ["path", "pipeline"],
                  "additionalProperties": false,
                  "properties": {
                    "path": {"type": "string", "description": "e.g. /nyc/revenue/{borough} — no /api/x prefix, no trailing slash."},
                    "pipeline": {"type": "string", "description": "The pipeline NAME. It must have a released version."},
                    "timeout_seconds": {"type": "integer", "description": "Clamped by datapipelines.endpoints.timeout-min-seconds/max-seconds. On timeout the endpoint answers 202 and the execution keeps running."},
                    "description": {"type": "string"}
                  }
                }
                """.trimIndent()
        }
    }

    class ListTool(
        private val publishing: EndpointPublishService,
        private val pipelines: PipelineRepository,
    ) : McpTool {
        override val definition: McpSchema.Tool =
            McpTools.tool(
                name = "endpoints_list",
                description =
                    "List the published endpoints of the key's workspace: path, pipeline name, timeout, whether it is " +
                        "enabled, and the path variables it binds. A disabled endpoint answers 404 exactly like an " +
                        "unpublished one, so this listing is the only way to see that it exists.",
                schema = EMPTY_SCHEMA,
            )

        override fun call(
            args: McpArguments,
            ctx: McpToolContext,
        ): Any = mapOf("endpoints" to publishing.list(ctx.principal).map { it.toResponse(pipelines) })
    }

    class GetTool(
        private val publishing: EndpointPublishService,
        private val pipelines: PipelineRepository,
    ) : McpTool {
        override val definition: McpSchema.Tool =
            McpTools.tool(
                name = "endpoints_get",
                description = "One published endpoint by its path (the pattern, not a request URL — '/nyc/revenue/{borough}').",
                schema = PATH_SCHEMA,
            )

        override fun call(
            args: McpArguments,
            ctx: McpToolContext,
        ): Any {
            val path = args.requiredString("path")
            val endpoint =
                publishing.get(ctx.principal, path)
                    ?: throw DatapipelinesException(
                        code = PipelineErrorCodes.Endpoint.NOT_FOUND,
                        message = "No endpoint published at '$path'.",
                        details = mapOf("path" to path),
                    )
            return endpoint.toResponse(pipelines)
        }
    }

    class DeleteTool(
        private val publishing: EndpointPublishService,
    ) : McpTool {
        override val definition: McpSchema.Tool =
            McpTools.tool(
                name = "endpoints_delete",
                description =
                    "Unpublish an endpoint by its path. The pipeline is untouched — only the URL stops answering. " +
                        "Key bindings on that path are NOT removed: they describe a node of the tree, which may still " +
                        "carry other endpoints beneath it.",
                schema = PATH_SCHEMA,
            )

        override fun call(
            args: McpArguments,
            ctx: McpToolContext,
        ): Any {
            val path = args.requiredString("path")
            if (!publishing.unpublish(ctx.principal, path)) {
                throw DatapipelinesException(
                    code = PipelineErrorCodes.Endpoint.NOT_FOUND,
                    message = "No endpoint published at '$path'.",
                    details = mapOf("path" to path),
                )
            }
            return mapOf("path" to path, "deleted" to true)
        }
    }

    private val EMPTY_SCHEMA =
        """
        {"type": "object", "additionalProperties": false, "properties": {}}
        """.trimIndent()

    private val PATH_SCHEMA =
        """
        {
          "type": "object",
          "required": ["path"],
          "additionalProperties": false,
          "properties": {
            "path": {"type": "string", "description": "The published path PATTERN, e.g. /nyc/revenue/{borough}."}
          }
        }
        """.trimIndent()
}
