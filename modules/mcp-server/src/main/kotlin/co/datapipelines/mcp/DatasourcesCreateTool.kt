package co.datapipelines.mcp

import co.datapipelines.application.datasources.DatasourceCreateService
import co.datapipelines.datasources.Datasource
import co.datapipelines.executor.ExecutorJson
import io.modelcontextprotocol.spec.McpSchema

/**
 * `datasources_create` (mcp-server.md §6.2.22). Scope: `author` — the same floor
 * `datasources_test` sits on, since registering a connection opens a real pool against a
 * production database. `global: true` additionally requires admin and is refused with the REST
 * code, because the gate is the same object (see below).
 *
 * ## One validated path
 *
 * The tool assembles the datasources.md §3.1 body from its arguments and hands it to
 * [DatasourceCreateService] — the same call `POST /api/v1/datasources` makes, with the same
 * payload binder, the same D8 workspace rules instance and the same duplicate-name refusal.
 * That is 049's rule (`PipelineImportService`) applied to datasources: two entry points, one
 * validated path. Nothing about registration is re-implemented here; what lives on this side of
 * the boundary is the tool's schema, its argument-to-body assembly and its result shape, exactly
 * as `PipelineToolPayloads` does for the pipeline write tools.
 *
 * ## The password caveat is a documented trade-off, not a bug
 *
 * A password passed through an agent transits the agent's context, its transcript, and whatever
 * logging the client does. That is a property of handing a secret to an agent, and no server
 * change can undo it — so the tool does not pretend to, and it does not refuse. It SAYS so, in
 * the description an agent reads before calling it and in `.agents/skills/datapipelines/
 * SKILL.md`: prefer the UI or REST for a real credential, and use this tool only with one the
 * user is willing to have in that transcript — a read-only role, or a short-lived password they
 * will rotate afterwards.
 *
 * The password never comes BACK: the result is the §3.2 shape, which carries `password_set:
 * true` and no password field at all ([toCreatedResponse]).
 */
class DatasourcesCreateTool(
    private val registrations: DatasourceCreateService,
) : McpTool {
    override val definition: McpSchema.Tool =
        McpTools.tool(
            name = "datasources_create",
            description =
                "Register a new datasource connection in the key's pinned workspace. Mirrors POST /api/v1/datasources: " +
                    "name, dialect, jdbc_url, username and password are required; global (admin only) or workspace " +
                    "select the binding, readonly forbids write-shaped use. Returns the stored metadata with " +
                    "password_set: true — the password is never returned. " +
                    "SECURITY: a password sent through this tool transits the agent's context, its transcript and any " +
                    "logging the client does. Prefer registering a datasource with a real credential in the UI or over " +
                    "REST; use this tool only with a credential the user is willing to have in that transcript — a " +
                    "read-only role, or a short-lived password they will rotate. " +
                    "Call datasources_test on the new name afterwards to confirm it connects.",
            schema = SCHEMA,
        )

    override fun call(
        args: McpArguments,
        ctx: McpToolContext,
    ): Any {
        // The §3.1 body, assembled server-side from the declared arguments — the same shape the
        // REST surface binds, so the two entry points cannot diverge on what a field means.
        val body =
            buildMap<String, Any?> {
                put("name", args.requiredString("name"))
                put("dialect", args.requiredString("dialect"))
                put("jdbc_url", args.requiredString("jdbc_url"))
                put("username", args.requiredString("username"))
                put("password", args.requiredString("password"))
                args.string("display_name")?.let { put("display_name", it) }
                args.string("description")?.let { put("description", it) }
                args.rawMap()["query_timeout_seconds"]?.let { put("query_timeout_seconds", it) }
                args.boolean("global")?.let { put("global", it) }
                args.string("workspace")?.let { put("workspace", it) }
                args.boolean("readonly")?.let { put("readonly", it) }
                args.rawMap()["introspection_include_schemas"]?.let { put("introspection_include_schemas", it) }
                args.rawMap()["properties"]?.let { put("properties", it) }
            }
        return registrations.create(McpTools.readTree(ExecutorJson.write(body)), ctx.principal).toCreatedResponse()
    }

    private companion object {
        val SCHEMA =
            """
            {
              "type": "object",
              "required": ["name", "dialect", "jdbc_url", "username", "password"],
              "additionalProperties": false,
              "properties": {
                "name": {"type": "string"},
                "display_name": {"type": "string"},
                "description": {"type": "string"},
                "dialect": {"type": "string", "enum": ["POSTGRES", "MYSQL", "MSSQL", "ORACLE", "H2", "DUCKDB", "SQLITE"]},
                "jdbc_url": {"type": "string"},
                "username": {"type": "string"},
                "password": {
                  "type": "string",
                  "description": "Write-only. It transits this agent's context and transcript — use a read-only or short-lived credential."
                },
                "query_timeout_seconds": {"type": "integer"},
                "global": {"type": "boolean", "description": "Admin only. true = shared infrastructure, bound to no workspace."},
                "workspace": {"type": "string", "description": "A workspace the caller can access; default = the key's pinned workspace."},
                "readonly": {"type": "boolean"},
                "introspection_include_schemas": {"type": "array", "items": {"type": "string"}},
                "properties": {"type": "object"}
              }
            }
            """.trimIndent()
    }
}

/**
 * The datasources.md §3.2 response shape for a freshly registered datasource.
 *
 * Built field by field for exactly the reason [toMcpMetadata] is: [Datasource] carries the
 * decrypted `password` on the paths that need it, and "credentials are never returned" has to be
 * a property of the CODE, not of whichever mapper happens to serialize it. `password_set` is
 * `true` because §9.1 requires a password to register — there is no other outcome to report.
 */
internal fun Datasource.toCreatedResponse(): Map<String, Any?> =
    buildMap {
        put("name", name)
        put("display_name", displayName)
        put("description", description)
        put("dialect", dialect.wire)
        put("jdbc_url", jdbcUrl)
        put("username", username)
        put("password_set", true)
        put("query_timeout_seconds", queryTimeoutSeconds)
        if (introspectionIncludeSchemas.isNotEmpty()) put("introspection_include_schemas", introspectionIncludeSchemas)
        put("properties", mapOf("hikari" to properties.hikari, "jdbc" to properties.jdbc))
        put("workspace", workspaceName)
        put("readonly", isReadonly)
        // §8.1B — never tested at the moment of creation; NULL, not absent, is what "we have
        // never checked" means on this field. The suggested next call is datasources_test.
        put("last_test", null)
    }
