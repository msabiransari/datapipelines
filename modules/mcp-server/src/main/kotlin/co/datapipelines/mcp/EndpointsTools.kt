package co.datapipelines.mcp

import co.datapipelines.application.endpoints.EndpointPath
import co.datapipelines.application.endpoints.EndpointPublishService
import co.datapipelines.application.endpoints.EndpointRow
import co.datapipelines.application.endpoints.PublishedEndpoint
import co.datapipelines.pipeline.PipelineErrorCodes
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
 * through a tool call transits the agent's context, its transcript and whatever the client logs.
 * 094 turned that reasoning into the surface's standing rule — **no credential travels through an
 * agent** — and removed the one tool that had accepted one. Endpoint keys are minted over REST or
 * in the UI and bound there; these tools publish, read and unpublish.
 */
object EndpointsTools {
    /** The four tools, in `tools/list` order. */
    fun all(publishing: EndpointPublishService): List<McpTool> =
        listOf(
            CreateTool(publishing),
            ListTool(publishing),
            GetTool(publishing),
            DeleteTool(publishing),
        )

    /**
     * The wire shape of one endpoint, unchanged by #786 (no get/list field is added — record §24): `pipeline`,
     * `timeout_seconds` and `description` are the API's CURRENT version's, the pipeline by NAME — the portable identity
     * (pipeline-contract §11.1) — so an agent reading this can act on it in any environment. An API with no current
     * version answers them null.
     */
    @Suppress("UnusedParameter") // the receiver shape every caller passes; the pin is the version's own name now
    internal fun PublishedEndpoint.toResponse(): Map<String, Any?> =
        mapOf(
            "path" to pathPattern,
            "pipeline" to current?.body?.pipelineName,
            "timeout_seconds" to current?.body?.timeoutSeconds,
            "description" to current?.body?.description,
            "enabled" to isEnabled,
            "path_variables" to pathVariables,
            "url" to "/api$pathPattern",
        )

    /**
     * The wire shape of one LEGACY row (#274) — a stored path today's grammar refuses, retired
     * rather than fatal. It carries `legacy: true`, its `reason` (the grammar's own refusal,
     * bounded) and the keys a caller needs to act on it; no `path_variables`, because there is
     * no parse. Unpublishing it (`endpoints_delete`) is the fix. The path is echoed bounded too
     * (#286, [EndpointPath.echoBounded]).
     */
    internal fun EndpointRow.Legacy.toLegacyResponse(): Map<String, Any?> {
        val echoed = EndpointPath.echoBounded(pathPattern)
        return mapOf(
            "path" to echoed,
            "pipeline" to pipelineName,
            "enabled" to enabled,
            "legacy" to true,
            "reason" to reason,
            "url" to "/api$echoed",
        )
    }

    /** `endpoints_create` (mcp-server.md §6.2.23). Permission: `endpoint.publish`. Mutating. */
    class CreateTool(
        private val publishing: EndpointPublishService,
    ) : McpTool {
        override val definition: McpSchema.Tool =
            McpTools.tool(
                name = "endpoints_create",
                description =
                    "Create a GET endpoint at /api/<category>/<version>/<path>: this creates the API's first DRAFT version " +
                        "pinned to the exact pipeline_version given. Creation is an authoring operation: a deployment " +
                        "with authoring disabled refuses it (endpoint.authoring_disabled); there is no release to perform " +
                        "there. In an authoring-enabled deployment the draft is the entity's current version and serves " +
                        "under the normal runtime checks — the entity is enabled, the caller's key is bound, and the " +
                        "pinned pipeline version is eligible. The pin never moves when the pipeline's current version " +
                        "moves. The category " +
                        "is your namespace (a business domain, a team); categories matching v<number> and the literal " +
                        "'api' are reserved to the product and refused with endpoint.path_reserved. The version is one " +
                        "free-form segment (v1 by convention); path variables come after it. The pinned pipeline version " +
                        "must be eligible here (RELEASED; a DRAFT only where authoring is enabled — else " +
                        "endpoint.pipeline_version.not_eligible) and must be side-effect-free: " +
                        "every node DQL into tempdb or the caller, a " +
                        "DML/DDL node whose source is tempdb, transitively through PIPELINE nodes. A DML/DDL node against a " +
                        "registered datasource, or a DQL node writing back to a datasource, is refused with " +
                        "endpoint.pipeline_not_readonly naming the node and the datasource — that rule is what makes " +
                        "serving over GET safe, since GET is retried, preloaded and crawled. " +
                        "path is 3-10 segments, each a literal [a-z0-9][a-z0-9_.-]{0,63} or a {variable} naming a " +
                        "declared parameter; remaining parameters come from the query string. A leading /api prefix " +
                        "is stripped, not refused. A path that could match " +
                        "the same URL as an existing one is refused (endpoint.path_conflict) rather than resolved by " +
                        "precedence. Calling the endpoint needs an API key bound to it — create one with the " +
                        "api_caller role and bind it over REST or in the UI (auth.md §7.7); an endpoint bound to no " +
                        "key is served to no one, and an MCP key never reaches REST at all (it acts over /mcp only).",
                schema = CREATE_SCHEMA,
            )

        override fun call(
            args: McpArguments,
            ctx: McpToolContext,
        ): Any =
            publishing
                .create(
                    principal = ctx.principal,
                    pathPattern = args.requiredString("path"),
                    pipelineName = args.requiredString("pipeline"),
                    pipelineVersion =
                        args.version("pipeline_version")
                            ?: throw McpArguments.invalidParams("Missing required argument 'pipeline_version'."),
                    // Absent means "the configured default", which the service applies — so the
                    // sentinel is null, not a number this layer would have to keep in step with
                    // datapipelines.endpoints.timeout-default-seconds.
                    timeoutSeconds =
                        args.rawMap()["timeout_seconds"]?.let { args.int("timeout_seconds", MIN_TIMEOUT, MIN_TIMEOUT, MAX_TIMEOUT) },
                    description = args.string("description").orEmpty(),
                ).toResponse()

        private companion object {
            const val MIN_TIMEOUT = 1
            const val MAX_TIMEOUT = 3600

            val CREATE_SCHEMA =
                """
                {
                  "type": "object",
                  "required": ["path", "pipeline", "pipeline_version"],
                  "additionalProperties": false,
                  "properties": {
                    "path": {"type": "string", "description": "e.g. /finance/v1/revenue/{region} — category, version, then the path; no /api prefix (one is stripped, not refused), no trailing slash."},
                    "pipeline": {"type": "string", "description": "The pipeline NAME."},
                    "pipeline_version": {"type": "integer", "minimum": 1, "description": "The EXACT pipeline version the API's first draft pins. It never moves when the pipeline's current version moves."},
                    "timeout_seconds": {"type": "integer", "description": "Clamped by datapipelines.endpoints.timeout-min-seconds/max-seconds. On timeout the endpoint answers 202 and the execution keeps running."},
                    "description": {"type": "string"}
                  }
                }
                """.trimIndent()
        }
    }

    /** `endpoints_list` (mcp-server.md §6.2.24). Permission: `endpoint.read`. */
    class ListTool(
        private val publishing: EndpointPublishService,
    ) : McpTool {
        override val definition: McpSchema.Tool =
            McpTools.tool(
                name = "endpoints_list",
                description =
                    "List the published endpoints of the key's workspace: path, pipeline name, timeout, whether it is " +
                        "enabled, and the path variables it binds. A disabled endpoint answers 404 exactly like an " +
                        "unpublished one, so this listing is the only way to see that it exists. A promoter's key " +
                        "lists only the endpoints of pipelines it can see (the promoter lens: released, newer than the " +
                        "promotion target's). Legacy rows (#274) are listed last with \"legacy\": true and their " +
                        "\"reason\": a stored path saved before the current grammar that is retired — never served, " +
                        "never a conflict partner; endpoints_delete removes one.",
                schema = EMPTY_SCHEMA,
            )

        override fun call(
            args: McpArguments,
            ctx: McpToolContext,
        ): Any =
            mapOf(
                "endpoints" to
                    (
                        publishing.list(ctx.principal).map { it.toResponse() } +
                            publishing.listLegacy(ctx.principal).map { it.toLegacyResponse() }
                    ),
            )
    }

    /** `endpoints_get` (mcp-server.md §6.2.25). Permission: `endpoint.read`. */
    class GetTool(
        private val publishing: EndpointPublishService,
    ) : McpTool {
        override val definition: McpSchema.Tool =
            McpTools.tool(
                name = "endpoints_get",
                description =
                    "One published endpoint by its path (the pattern, not a request URL — '/finance/v1/revenue/{region}'). " +
                        "For a promoter's key an endpoint over a pipeline it cannot see resolves as not-found.",
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
            return endpoint.toResponse()
        }
    }

    /** `endpoints_delete` (mcp-server.md §6.2.26). Permission: `endpoint.unpublish`. Mutating. */
    class DeleteTool(
        private val publishing: EndpointPublishService,
    ) : McpTool {
        override val definition: McpSchema.Tool =
            McpTools.tool(
                name = "endpoints_delete",
                description =
                    "Unpublish an endpoint by its path. The pipeline is untouched — only the URL stops answering. " +
                        "Key bindings on that path are NOT removed: they describe a node of the tree, which may still " +
                        "carry other endpoints beneath it. This is also the fix for a LEGACY row (#274) — a retired " +
                        "path listed with \"legacy\": true never serves under the current grammar, and deleting it " +
                        "removes the row. An API that holds a RELEASED or DISCARDED version is never deleted: the call is " +
                        "refused endpoint.version.last_release and nothing is removed — discard is per version, the API stays.",
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
            "path": {"type": "string", "description": "The published path PATTERN, e.g. /finance/v1/revenue/{region}."}
          }
        }
        """.trimIndent()
}
