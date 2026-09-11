package co.datapipelines.mcp

import co.datapipelines.datasources.Datasource
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.datasources.DialectAdapters
import co.datapipelines.datasources.pooling.PoolSettings
import co.datapipelines.typesystem.Dialect
import io.modelcontextprotocol.spec.McpSchema

/**
 * The datasource projection every tool and resource in this module emits.
 *
 * Built field by field rather than by serializing [Datasource]: that type carries the decrypted
 * `password` on the paths that need it, and "credentials are never returned" (§6.2.10, §13
 * checklist) must be a property of the code, not of whichever mapper happens to serialize it.
 *
 * `readonly` is a workspaces design §9 additive field — machine-readable feedback (D6) so an
 * agent can see BEFORE authoring that a DML/DDL/output-datasource use of this connection will
 * be refused.
 *
 * ## `workspace` and `granted` (114 §D — the shape 112 made false)
 * Until RBAC round 1 this projection emitted `workspace: <name> | null = global`, and "global"
 * meant "visible everywhere". D-R7 deleted that concept: **visibility is the grant**
 * (`datasource_workspaces`), so a datasource is visible in the key's workspace if and only if
 * it has been granted there, and a null `workspace` no longer says anything about who can see
 * it.
 *
 * So the field now means only what it can honestly mean — WHICH WORKSPACE REGISTERED IT — and
 * it is **omitted entirely** when the answer is "none": a super admin registered it at the
 * instance level. A null would be read as the old "global" by every agent that learned the old
 * contract, and by every transcript of one. `granted: true` rides alongside, stating the thing
 * that is now load-bearing and was previously implicit: you are seeing this row BECAUSE your
 * workspace holds a grant on it.
 *
 * There is deliberately no `granted_workspaces` field. Listing every workspace a datasource is
 * granted to names workspaces the caller may not know exist — the cross-workspace disclosure
 * D-R5 withholds from everyone below super admin, and the reason `DatasourceGrantsController`
 * is `MANAGE_DATASOURCE_GRANTS` (super admin) on its READ as well as its writes. No key may
 * hold `admin` scope (O-2), so no MCP caller can be a super admin: the field would have no
 * correct audience on this surface.
 */
internal fun Datasource.toMcpMetadata(): Map<String, Any?> =
    buildMap {
        put("name", name)
        put("display_name", displayName)
        put("description", description)
        put("dialect", dialect.wire)
        put("jdbc_url", jdbcUrl)
        put("username", username)
        // §3.4 additive: WHAT the stored credential is, never the secret itself. An agent
        // reading this can tell a token-authenticated warehouse from a password login and a
        // file database with no credential at all — the fact it needs to author or to explain
        // an authentication failure.
        put("credential", buildMap { put("kind", credentialKind.wire) })
        put("query_timeout_seconds", queryTimeoutSeconds)
        // The §3.3 allowlist, so an agent debugging why a schema is or isn't visible can see
        // that one is active — omitted when empty, the same envelope convention as REST §3.2.
        if (introspectionIncludeSchemas.isNotEmpty()) put("introspection_include_schemas", introspectionIncludeSchemas)
        put("readonly", isReadonly)
        // Omitted, never null: see the KDoc. An absent key is a shape an agent has to look at;
        // a null is one it will read as the retired "global".
        workspaceName?.let { put("workspace", it) }
        // The row is in this projection because the key's workspace holds a grant on it — the
        // registry's reads carry the grant predicate in their SQL. Stated rather than implied,
        // because "why can I see this?" is now a different question from "who owns it?".
        put("granted", true)
        // §5 (094): the EFFECTIVE pool settings, not the row's usually-empty `properties.hikari`
        // map — each value with its unit and the layer that supplied it, so an agent explaining
        // a pool-timeout failure can read the number the pool actually runs with.
        put("pool", PoolSettings.wire(this@toMcpMetadata, DialectAdapters.forDialect(dialect)))
    }

/** `datasources_list` (mcp-server.md §6.2.10). Scope: `read`. */
class DatasourcesListTool(
    private val datasources: DatasourceRegistry,
) : McpTool {
    override val definition: McpSchema.Tool =
        McpTools.tool(
            name = "datasources_list",
            description =
                "List the datasources GRANTED to the key's pinned workspace. Visibility is the grant: a " +
                    "datasource registered elsewhere and not granted to this workspace is ABSENT, not hidden, " +
                    "and there is no such thing as a global datasource. Returns name, dialect, the workspace " +
                    "that REGISTERED it (omitted for an instance-level one), granted:true, and connection " +
                    "metadata — never passwords.",
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
     * Visibility (RBAC design §4, D-R7): the key's pinned workspace, through the GRANT — the
     * same `EXISTS(datasource_workspaces …)` predicate the REST §9.2 listing carries in its SQL.
     * The `bound-to-this-workspace OR global` predicate this KDoc used to name went with the
     * concept of a global datasource at round 1.
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
                "Get metadata for a single datasource GRANTED to the key's pinned workspace: name, dialect, " +
                    "JDBC URL, the workspace that REGISTERED it (omitted for an instance-level one), " +
                    "granted:true, readonly flag, pool settings. Credentials are never returned. A datasource " +
                    "that is not granted to this workspace resolves as not-found — the same answer a name that " +
                    "exists nowhere gets, so nothing about it can be probed.",
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
