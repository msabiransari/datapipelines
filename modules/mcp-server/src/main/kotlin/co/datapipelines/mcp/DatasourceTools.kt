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
 */
internal fun Datasource.toMcpMetadata(): Map<String, Any?> =
    mapOf(
        "name" to name,
        "display_name" to displayName,
        "description" to description,
        "dialect" to dialect.wire,
        "jdbc_url" to jdbcUrl,
        "username" to username,
        "query_timeout_seconds" to queryTimeoutSeconds,
        "pool" to properties.hikari,
    )

/** `datasources_list` (mcp-server.md §6.2.10). Scope: `read`. */
class DatasourcesListTool(
    private val datasources: DatasourceRegistry,
) : McpTool {
    override val definition: McpSchema.Tool =
        McpTools.tool(
            name = "datasources_list",
            description =
                "List datasource connections registered on this instance. Returns name, dialect, and connection " +
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
     */
    override fun call(
        args: McpArguments,
        ctx: McpToolContext,
    ): Any {
        val filter = args.string("dialect")
        if (filter != null && Dialect.entries.none { it.wire == filter }) return emptyList<Map<String, Any?>>()
        return datasources.list(filter?.let { Dialect.fromWire(it) }).map { it.toMcpMetadata() }
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
                "Get metadata for a single datasource: name, dialect, JDBC URL, pool settings. Credentials are never " +
                    "returned.",
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
        return (datasources.get(name) ?: throw McpNotFound.datasource(name)).toMcpMetadata()
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
        val result = datasources.testConnection(name) ?: throw McpNotFound.datasource(name)
        return mapOf(
            "connected" to result.connected,
            "server_version" to result.serverVersion,
            "error" to result.error,
        )
    }
}
