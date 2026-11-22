package co.datapipelines.mcp

import co.datapipelines.application.endpoints.EndpointBodyContract
import co.datapipelines.application.endpoints.EndpointMethod
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
     * A `method` argument as an [EndpointMethod] (#1037): absent meaning GET — the pre-method era's call creates the
     * pre-method era's route — and anything the surface does not publish (PATCH, a typo) is an invalid parameter, not
     * a stored unreachable value.
     */
    internal fun methodOf(args: McpArguments): EndpointMethod =
        args.string("method")?.let { raw ->
            EndpointMethod.of(raw.trim().uppercase())
                ?: throw McpArguments.invalidParams(
                    "Invalid 'method': a published route answers GET, POST, PUT or DELETE — '$raw' does not.",
                )
        } ?: EndpointMethod.GET

    /**
     * The wire shape of one endpoint, unchanged by #786 (no get/list field is added — record §24) and carrying its
     * `method` since #1037 (the route's identity half): `pipeline`, `timeout_seconds` and `description` are the API's
     * CURRENT version's, the pipeline by NAME — the portable identity (pipeline-contract §11.1) — so an agent reading
     * this can act on it in any environment. An API with no current version answers them null.
     *
     * #1040 adds what an author must see before acting on a route: `pipeline_version` (the current version's EXACT pin)
     * and `request_body` — the pinned version's body [EndpointBodyContract.Mode] (`none`, `optional`, `required`) and the
     * SCRIPT transformers that read the body (`node_id`, `template` as `id@version`); null when there is no current
     * version or its pin no longer resolves.
     */
    internal fun PublishedEndpoint.toResponse(contract: EndpointBodyContract?): Map<String, Any?> =
        mapOf(
            "path" to pathPattern,
            "method" to method.name,
            "pipeline" to current?.body?.pipelineName,
            "pipeline_version" to current?.body?.pipelineVersion,
            "timeout_seconds" to current?.body?.timeoutSeconds,
            "description" to current?.body?.description,
            "request_body" to contract?.wire(),
            "enabled" to isEnabled,
            "path_variables" to pathVariables,
            "url" to "/api$pathPattern",
        )

    /** One endpoint's wire shape with its body contract read through [publishing] (#1040). */
    internal fun PublishedEndpoint.toResponse(publishing: EndpointPublishService): Map<String, Any?> =
        toResponse(publishing.bodyContract(this))

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
                    "Create an endpoint at /api/<category>/<version>/<path> for one HTTP method — GET by default, or " +
                        "POST/PUT/DELETE for a mutation route (#1037): the method is half of the route's identity and " +
                        "immutable like the path, so a GET and a POST at one pattern are two routes. This creates the " +
                        "API's first DRAFT version pinned to the exact pipeline_version given; the pin never moves. " +
                        "Authoring-disabled deployments refuse it (endpoint.authoring_disabled). The category is your " +
                        "namespace; categories matching v<number> " +
                        "and the literal 'api' are reserved (endpoint.path_reserved), as is a path that could match an " +
                        "executions/{id} or executions/{id}/result polling suffix. The version is one free-form segment " +
                        "(v1 by convention); path variables come after it; path is 3-10 segments, each a literal " +
                        "[a-z0-9][a-z0-9_.-]{0,63} or a {variable} naming a declared parameter; remaining parameters " +
                        "come from the query string; a leading /api prefix is stripped. The pinned version must be " +
                        "eligible here (RELEASED; a DRAFT only where authoring is enabled — " +
                        "endpoint.pipeline_version.not_eligible) and, for GET, side-effect-free: every node DQL into " +
                        "tempdb or the caller, a DML/DDL node whose source is tempdb, transitively through PIPELINE " +
                        "nodes; a DML/DDL node against a registered datasource, or a DQL node writing back to one, is " +
                        "refused endpoint.pipeline_not_readonly naming node and datasource — what makes GET safe to " +
                        "retry and crawl; a mutation route's pin admits a writing pipeline by design. A route of the " +
                        "SAME method that could match the same URL is refused " +
                        "(endpoint.path_conflict), never resolved by precedence; another workspace's route at a " +
                        "matching pattern is refused the same way with a redacted body naming nothing but your own " +
                        "pattern. Calling the endpoint needs an API key granted its method — a GET grant is a tree " +
                        "node bound over REST or in the UI (auth.md §7.7); a mutation grant names this one route and " +
                        "is bound the same way with a method. Granted to no key, served to no one; an MCP key never " +
                        "reaches REST (it acts over /mcp only).",
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
                    method = methodOf(args),
                ).toResponse(publishing)

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
                    "method": {"type": "string", "enum": ["GET", "POST", "PUT", "DELETE"], "description": "The route's HTTP method, immutable like the path. Absent means GET."},
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
                        publishing.list(ctx.principal).map { it.toResponse(publishing) } +
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
                    "One published endpoint by its path (the pattern, not a request URL — '/finance/v1/revenue/{region}') " +
                        "and method (absent meaning GET, the pre-#1037 shape). A GET and a POST at one pattern are two " +
                        "routes, each with its own versions. For a promoter's key an endpoint over a pipeline it cannot " +
                        "see resolves as not-found.",
                schema = PATH_SCHEMA,
            )

        override fun call(
            args: McpArguments,
            ctx: McpToolContext,
        ): Any {
            val path = args.requiredString("path")
            val endpoint =
                publishing.get(ctx.principal, path, methodOf(args))
                    ?: throw DatapipelinesException(
                        code = PipelineErrorCodes.Endpoint.NOT_FOUND,
                        message = "No endpoint published at '$path'.",
                        details = mapOf("path" to path),
                    )
            return endpoint.toResponse(publishing)
        }
    }

    /**
     * `endpoints_delete` (mcp-server.md §6.2.26). Permission: `endpoint.unpublish`. Mutating.
     * Unpublish is BY the full stored path (and method, absent meaning GET), so a legacy row
     * stored past `EndpointPath.MAX_LENGTH`, whose echo is cut (#286), is deleted only by that
     * path, and this tool refuses nothing by length (#304). Deleting one method's route never
     * touches a sibling method's route at the same pattern; exact mutation grants on the route
     * go with it (the grant names the entity).
     */
    class DeleteTool(
        private val publishing: EndpointPublishService,
    ) : McpTool {
        override val definition: McpSchema.Tool =
            McpTools.tool(
                name = "endpoints_delete",
                description =
                    "Unpublish an endpoint by its path and method (absent meaning GET). The pipeline is untouched — " +
                        "only the URL stops answering that method. " +
                        "GET key bindings on that path are NOT removed: they describe a node of the tree, which may still " +
                        "carry other endpoints beneath it; a mutation grant named this one route and goes with it. This is " +
                        "also the fix for a LEGACY row (#274) — a retired " +
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
            val method = methodOf(args)
            if (!publishing.unpublish(ctx.principal, path, method)) {
                throw DatapipelinesException(
                    code = PipelineErrorCodes.Endpoint.NOT_FOUND,
                    message = "No endpoint published at '$path'.",
                    details = mapOf("path" to path, "method" to method.name),
                )
            }
            return mapOf("path" to path, "method" to method.name, "deleted" to true)
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
            "path": {"type": "string", "description": "The published path PATTERN, e.g. /finance/v1/revenue/{region}."},
            "method": {"type": "string", "enum": ["GET", "POST", "PUT", "DELETE"], "description": "The route's method. Absent means GET."}
          }
        }
        """.trimIndent()
}
