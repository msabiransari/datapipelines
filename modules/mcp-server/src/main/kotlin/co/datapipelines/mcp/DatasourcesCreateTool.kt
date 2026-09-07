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
 * ## The credential caveat is a documented trade-off, not a bug
 *
 * A secret passed through an agent transits the agent's context, its transcript, and whatever
 * logging the client does. That is a property of handing a secret to an agent, and no server
 * change can undo it — so the tool does not pretend to, and it does not refuse. It SAYS so, in
 * the description an agent reads before calling it and in `.agents/skills/datapipelines/
 * SKILL.md`: prefer the UI or REST for a real credential, and use this tool only with one the
 * user is willing to have in that transcript — a read-only role, or a short-lived password they
 * will rotate afterwards.
 *
 * The secret never comes BACK: the result is the §3.2 shape, which carries `credential.kind`
 * and `password_set` and no secret field at all ([toCreatedResponse]).
 */
class DatasourcesCreateTool(
    private val registrations: DatasourceCreateService,
) : McpTool {
    override val definition: McpSchema.Tool =
        McpTools.tool(
            name = "datasources_create",
            description =
                "Register a new datasource connection in the key's pinned workspace. Mirrors POST /api/v1/datasources: " +
                    "name, dialect and jdbc_url are required, plus a credential — either credential: " +
                    "{kind, username, secret} (kind = password | token | private_key | service_account_json | none) " +
                    "or the legacy username/password pair, which means kind: password. kind: none is for a file " +
                    "database or an IAM role and carries neither field. global (admin only) or workspace select the " +
                    "binding, readonly forbids write-shaped use. Returns the stored metadata with credential.kind and " +
                    "password_set — the secret is never returned. " +
                    "SECURITY: a secret sent through this tool transits the agent's context, its transcript and any " +
                    "logging the client does. Prefer registering a datasource with a real credential in the UI or over " +
                    "REST; use this tool only with a credential the user is willing to have in that transcript — a " +
                    "read-only role, or a short-lived token they will rotate. " +
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
                // Exactly ONE credential shape reaches the binder — the tool's schema declares
                // both and the binder refuses a body carrying both, so the choice is made here
                // rather than by a precedence rule nobody can see (§3.4).
                args.rawMap()["credential"]?.let { put("credential", it) }
                args.string("username")?.let { put("username", it) }
                args.string("password")?.let { put("password", it) }
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
              "required": ["name", "dialect", "jdbc_url"],
              "additionalProperties": false,
              "properties": {
                "name": {"type": "string"},
                "display_name": {"type": "string"},
                "description": {"type": "string"},
                "dialect": {"type": "string", "enum": ["POSTGRES", "MYSQL", "MSSQL", "ORACLE", "H2", "DUCKDB", "SQLITE", "LAKE"]},
                "jdbc_url": {"type": "string"},
                "credential": {
                  "type": "object",
                  "additionalProperties": false,
                  "description": "The credential (datasources.md §3.4). Use this OR the legacy username/password pair, never both.",
                  "required": ["kind"],
                  "properties": {
                    "kind": {
                      "type": "string",
                      "enum": ["password", "token", "private_key", "service_account_json", "none"],
                      "description": "password needs username+secret; token needs secret and may name a username; none needs neither."
                    },
                    "username": {"type": "string"},
                    "secret": {
                      "type": "string",
                      "description": "Write-only. It transits this agent's context and transcript — use a read-only or short-lived credential."
                    }
                  }
                },
                "username": {"type": "string", "description": "Legacy shape, with password: means credential kind 'password'."},
                "password": {
                  "type": "string",
                  "description": "Legacy shape, with username. Write-only. It transits this agent's context and transcript — use a read-only or short-lived credential."
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
 * derived from the credential KIND (§3.4): every kind but `none` has a stored secret, and V13's
 * CHECK is what makes those two statements the same.
 */
internal fun Datasource.toCreatedResponse(): Map<String, Any?> =
    buildMap {
        put("name", name)
        put("display_name", displayName)
        put("description", description)
        put("dialect", dialect.wire)
        put("jdbc_url", jdbcUrl)
        put("username", username)
        put("credential", buildMap { put("kind", credentialKind.wire) })
        put("password_set", credentialSet)
        put("query_timeout_seconds", queryTimeoutSeconds)
        if (introspectionIncludeSchemas.isNotEmpty()) put("introspection_include_schemas", introspectionIncludeSchemas)
        put("properties", mapOf("hikari" to properties.hikari, "jdbc" to properties.jdbc))
        put("workspace", workspaceName)
        put("readonly", isReadonly)
        // §8.1B — never tested at the moment of creation; NULL, not absent, is what "we have
        // never checked" means on this field. The suggested next call is datasources_test.
        put("last_test", null)
    }
