package co.datapipelines.mcp

import co.datapipelines.datasources.Datasource
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.typesystem.Dialect
import io.modelcontextprotocol.spec.McpSchema

/**
 * The datasource projection every tool and resource in this module emits.
 *
 * Built field by field rather than by serializing [Datasource]: that type carries the decrypted
 * `password` on the paths that need it, and "credentials are never returned" (§6.2.10, §13
 * checklist) must be a property of the code, not of whichever mapper happens to serialize it.
 *
 * `workspace` (the bound workspace's name, null = global) and `readonly` are the workspaces
 * design §9 additive fields — `readonly` is machine-readable feedback (D6) so an agent can
 * see BEFORE authoring that a DML/DDL/output-datasource use of this connection will be
 * refused.
 */
internal fun Datasource.toMcpMetadata(): Map<String, Any?> =
    buildMap {
        put("name", name)
        put("display_name", displayName)
        put("description", description)
        put("dialect", dialect.wire)
        put("jdbc_url", jdbcUrl)
        put("username", username)
        put("query_timeout_seconds", queryTimeoutSeconds)
        // The §3.3 allowlist, so an agent debugging why a schema is or isn't visible can see
        // that one is active — omitted when empty, the same envelope convention as REST §3.2.
        if (introspectionIncludeSchemas.isNotEmpty()) put("introspection_include_schemas", introspectionIncludeSchemas)
        put("readonly", isReadonly)
        put("workspace", workspaceName)
        put("pool", properties.hikari)
    }

/** `datasources_list` (mcp-server.md §6.2.10). Scope: `read`. */
class DatasourcesListTool(
    private val datasources: DatasourceRegistry,
) : McpTool {
    override val definition: McpSchema.Tool =
        McpTools.tool(
            name = "datasources_list",
            description =
                "List the datasource connections visible in the key's pinned workspace: its workspace-bound " +
                    "datasources plus every global one. Returns name, dialect, workspace and connection " +
                    "metadata — never passwords. Datasources bound to other workspaces are absent, not hidden.",
            schema =
                """
                {
                  "type": "object",
                  "properties": {
                    "dialect": {"type": "string"}
                  }
                }
                """.trimIndent(),
        )

    /**
     * §6.2.10 pins `dialect` as a bare `{"type": "string"}` — deliberately, since §6.2.6 and
     * §6.2.8 *do* carry the enum. So an unrecognized dialect filter is not a protocol error here:
     * it simply matches nothing, which is what a filter for something that does not exist means.
     *
     * Visibility (workspaces §5.3): the key's pinned workspace — the same
     * `visible = bound-to-this-workspace OR global` predicate the REST §9.2 listing applies.
     */
    override fun call(
        args: McpArguments,
        ctx: McpToolContext,
    ): Any {
        val filter = args.string("dialect")
        if (filter != null && Dialect.entries.none { it.wire == filter }) return emptyList<Map<String, Any?>>()
        val workspaceId = ctx.principal.requireWorkspace().id
        return datasources.listVisible(filter?.let { Dialect.fromWire(it) }, workspaceId).map { it.toMcpMetadata() }
    }
}

/** `datasources_get` (mcp-server.md §6.2.11). Scope: `read`. */
class DatasourcesGetTool(
    private val datasources: DatasourceRegistry,
) : McpTool {
    override val definition: McpSchema.Tool =
        McpTools.tool(
            name = "datasources_get",
            description =
                "Get metadata for a single datasource visible in the key's pinned workspace: name, dialect, JDBC " +
                    "URL, workspace, readonly flag, pool settings. Credentials are never returned. A datasource " +
                    "bound to another workspace resolves as not-found.",
            schema =
                """
                {
                  "type": "object",
                  "required": ["name"],
                  "properties": {
                    "name": {"type": "string"}
                  }
                }
                """.trimIndent(),
        )

    override fun call(
        args: McpArguments,
        ctx: McpToolContext,
    ): Any {
        val name = args.requiredString("name")
        return datasources.requireVisible(name, ctx).toMcpMetadata()
    }
}

/**
 * `datasources_test` (mcp-server.md §6.2.12). Scope: `author` — testing a connection opens a real
 * pool against a production database, so it sits above plain `read` even though it mutates
 * nothing.
 *
 * The payload is exactly §6.2.12's `{connected, server_version?, error?}`. The failure text is the
 * registry's own scrubbed message (datasources §6.1), which is where credential and URL redaction
 * is implemented — this tool adds nothing to it and echoes nothing else about the connection.
 *
 * Visibility (workspaces §5.3): the same [DatasourceRegistry.requireVisible] gate as
 * `datasources_get` — a datasource bound to another workspace is not-found, and the probe
 * never runs (022 review F3: this tool used to skip the gate its siblings got).
 */
class DatasourcesTestTool(
    private val datasources: DatasourceRegistry,
) : McpTool {
    override val definition: McpSchema.Tool =
        McpTools.tool(
            name = "datasources_test",
            description =
                "Test connectivity to a datasource. Returns success/failure and server version on success. Useful for " +
                    "diagnosing pipeline connection errors.",
            schema =
                """
                {
                  "type": "object",
                  "required": ["name"],
                  "properties": {
                    "name": {"type": "string"}
                  }
                }
                """.trimIndent(),
        )

    override fun call(
        args: McpArguments,
        ctx: McpToolContext,
    ): Any {
        val name = args.requiredString("name")
        // C3: the gate's snapshot is the datasource the probe runs against — no second,
        // unscoped name resolution between the visibility decision and the probe.
        val gated = datasources.requireVisible(name, ctx)
        val result = datasources.testConnection(gated) ?: throw McpNotFound.datasource(name)
        return mapOf(
            "connected" to result.connected,
            "server_version" to result.serverVersion,
            "error" to result.error,
        )
    }
}
