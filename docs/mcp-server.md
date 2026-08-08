# MCP Server Specification

**Status:** v1 (frozen contract — additive-only changes after this point)
**Owner:** datapipelines.co core
**Depends on:** [Type System spec](type-system.md), [Pipeline Contract spec](pipeline-contract.md), [REST API spec](rest-api.md), [Auth spec](auth.md)
**Last updated:** 2026-08-05

---

## 1. Purpose

datapipelines.co is **MCP-native**. Agentic tools (Claude Desktop, GLM, Copilot, custom LangChain/LlamaIndex agents, etc.) connect to a datapipelines.co instance via the [Model Context Protocol](https://modelcontextprotocol.io) to discover pipelines, execute them, and read results — without writing custom integration code.

This spec defines:
- The **MCP transport** (Streamable HTTP) and how clients connect.
- The **authentication model** (API key issued per-user-per-agent from the UI).
- The **tool surface** (functions the agent can call).
- The **resource surface** (entities the agent can read as files).
- The **prompt surface** (predefined workflows the agent can invoke).
- The **error model** (how datapipelines errors map to MCP errors).

---

## 2. Design Principles

1. **MCP is a thin adapter over REST.** Every MCP tool maps to one or more REST endpoints defined in [REST API spec](rest-api.md). No business logic in the MCP layer — it's translation only.
2. **Tools for actions, resources for inspection.** If an agent needs to *do* something (execute a pipeline, create a template), it calls a tool. If it needs to *read* something (look at a pipeline definition), it reads a resource. We avoid duplicating read-only operations as both.
3. **API key, not OAuth.** Self-hosted, internal-users-only deployment model makes OAuth overkill. The user grabs an API key from the UI, passes it to their agent, the agent uses it. See [Auth spec](auth.md).
4. **MCP versioning follows the protocol.** We commit to a specific MCP protocol version per datapipelines.co release, and document upgrade paths when the protocol evolves.
5. **Fail loudly, never silently.** MCP-level errors (transport, auth) and application errors (pipeline validation, datasource unreachable) both surface as structured errors the agent can act on. No silent fallbacks.

---

## 3. Transport

### 3.1 Transport choice: Streamable HTTP

We expose MCP via the protocol's **Streamable HTTP** transport:
- Single endpoint: `POST /mcp` (and `GET /mcp` for server-to-client notifications/SSE).
- Content types: `application/json` for single requests/responses, `text/event-stream` for streamed responses.
- Works through standard HTTP infrastructure (proxies, load balancers, TLS terminators).
- No WebSocket requirement (which would need custom proxy config).

This is the protocol's network-native transport, appropriate for our self-hosted, network-resident deployment model. The stdio transport (used for local-tools) is not supported — our product is a server, not a local process.

> **Verification needed before implementation:** Confirm the exact MCP protocol version against the [current specification](https://spec.modelcontextprotocol.io/) and the streamable HTTP transport details. This spec was authored against the durable shape of MCP; protocol minor versions may have introduced adjustments.

### 3.2 Endpoint structure

```
POST {host}/mcp
Headers:
  Content-Type: application/json
  Accept: application/json, text/event-stream
  X-API-Key: {api-key}                # or Authorization: Bearer {session-token}
  MCP-Protocol-Version: 2025-06-18    # specific protocol version
  MCP-Session-Id: {session-uuid}      # optional; server may issue for stateful sessions
```

Server response: JSON for single-message exchanges, `text/event-stream` for streamed responses (e.g., pipeline execution events streamed directly through MCP).

### 3.3 Session lifecycle

- **Stateless by default.** Each request carries full auth context. Server does not require session continuity.
- **Optional session.** Server MAY issue an `MCP-Session-Id` for clients that want one. Session state = nothing important (cached auth, nothing else).

---

## 4. Authentication

### 4.1 Auth model

Every MCP request must include either:
- `X-API-Key: {api-key}` — for programmatic agents (the primary case).
- `Authorization: Bearer {session-token}` — for browser-embedded MCP clients (rare).

API keys are:
- Issued per-user-per-agent from the UI (e.g., "Claude Desktop key", "GLM key").
- Revocable.
- Scoped (read-only / execute / author). See [Auth spec](auth.md).
- Sent in the `X-API-Key` header (matching the REST API convention).

### 4.2 Unauthorized behavior

Missing or invalid API key:
- HTTP `401 Unauthorized` with JSON body:
  ```json
  {"error": {"code": "auth.api_key_missing", "message": "..."}}
  ```
- MCP session is not established.

Insufficient scope (e.g., read-only key trying to call `pipelines.create`):
- HTTP `403 Forbidden` with `auth.scope_insufficient`.

### 4.3 Why not OAuth

OAuth adds:
- Authorization server (to build/maintain)
- Redirect flows (impossible for non-browser agents like Claude Desktop)
- Token refresh logic (per agent)
- Client registration (per agent)

For self-hosted, internal-users-only deployment, API keys are simpler and sufficient. Future multi-tenant SaaS deployment would revisit this.

---

## 5. Server Metadata & Capabilities

### 5.1 `initialize` response

```json
{
  "protocolVersion": "2025-06-18",
  "serverInfo": {
    "name": "datapipelines",
    "version": "1.0.0"
  },
  "capabilities": {
    "tools": {"listChanged": true},
    "resources": {"listChanged": true, "subscribe": false},
    "prompts": {"listChanged": true},
    "logging": {}
  }
}
```

- `tools.listChanged: true` — server notifies clients when tool list changes (e.g., new pipeline published → new `pipeline_execute_*` tool appears).
- `resources.subscribe: false` — no live subscriptions in v1. Clients re-fetch resources as needed.
- `logging` — server can emit log notifications.

---

## 6. Tool Surface

### 6.1 Tool naming convention

Tools are named `{domain}_{action}`:

- `pipelines_list`
- `pipelines_get`
- `pipelines_execute`
- `pipelines_create`
- `pipelines_update`
- `templates_list`
- `templates_get`
- `templates_create`
- `templates_render`
- `datasources_list`
- `datasources_get`
- `datasources_test`
- `executions_list`
- `executions_get`
- `executions_get_result`

A future enhancement: dynamically-generated per-pipeline tools (e.g., `pipeline_execute_monthly_revenue_report`) for pipelines the user wants to expose as named tools to agents. Marked for v2.

### 6.2 Tool definitions

#### 6.2.1 `pipelines_list`

List pipelines the caller has access to.

```json
{
  "name": "pipelines_list",
  "description": "List pipelines registered on this datapipelines.co instance, filtered by owner, datasource, or text search. Returns metadata (id, name, description, version, datasources_used) — not the full body.",
  "inputSchema": {
    "type": "object",
    "properties": {
      "owner": {"type": "string", "description": "Filter by owner user ID."},
      "datasource": {"type": "string", "description": "Filter by datasource name."},
      "q": {"type": "string", "description": "Full-text search on name and description."},
      "limit": {"type": "integer", "default": 50, "maximum": 200}
    }
  }
}
```

Returns: array of pipeline metadata objects.

#### 6.2.2 `pipelines_get`

Fetch a full pipeline definition.

```json
{
  "name": "pipelines_get",
  "description": "Get the full definition of a pipeline (latest version, or a specific version). Use this to read the pipeline body before executing or modifying it.",
  "inputSchema": {
    "type": "object",
    "required": ["id"],
    "properties": {
      "id": {"type": "string", "format": "uuid", "description": "Pipeline ID."},
      "version": {"type": "integer", "description": "Specific version. Defaults to latest."}
    }
  }
}
```

Returns: full pipeline JSON body (per [Pipeline Contract §3](pipeline-contract.md#3-top-level-pipeline-schema)).

#### 6.2.3 `pipelines_execute`

Execute a pipeline.

```json
{
  "name": "pipelines_execute",
  "description": "Execute a pipeline with the given input parameters. Returns execution events (node start/complete/fail) and the final result data. The result's schema describes column types; BIGINTEGER and BIGDECIMAL columns serialize as JSON strings — preserve them as strings when displaying or persisting to avoid precision loss.",
  "inputSchema": {
    "type": "object",
    "required": ["id", "parameters"],
    "properties": {
      "id": {"type": "string", "format": "uuid"},
      "version": {"type": "integer"},
      "parameters": {
        "type": "object",
        "description": "Object whose keys match the pipeline's declared parameters. Values must match the declared types (BIGINTEGER and BIGDECIMAL as strings, others as JSON native types).",
        "additionalProperties": true
      }
    }
  }
}
```

Returns: an **execution result object** containing:
- Execution metadata (`execution_id`, `pipeline_id`, `status`, `duration_ms`, `node_stats`).
- Schema (array of column descriptors per [Type System §7](type-system.md#7-schema-envelope-structure)).
- Rows (array-of-arrays; or `result_url` + `expires_at` if claim-check).
- Warnings array (if any).

The result shape mirrors the [REST `data_ready` event](rest-api.md#647-data_ready) — agents consuming SSE consume the same structure via this tool's return value.

For very large results, the tool returns `result_url` instead of inline rows; the agent can then call `executions_get_result` with paging to retrieve the data.

#### 6.2.4 `pipelines_create`

Create a new pipeline.

```json
{
  "name": "pipelines_create",
  "description": "Create a new pipeline. The body must satisfy the Pipeline Contract: nodes must form a DAG, exactly one DQL sink must have output.target='caller' (the auto-detected terminal), all datasource references must exist in this environment, all template references must exist. Returns the created pipeline with server-assigned id and version 1.",
  "inputSchema": {
    "type": "object",
    "required": ["name", "display_name", "nodes"],
    "properties": {
      "name": {"type": "string", "pattern": "^[a-z0-9_]+$"},
      "display_name": {"type": "string"},
      "description": {"type": "string"},
      "parameters": {"type": "object"},
      "settings": {"type": "object", "description": "Pipeline-level execution settings (e.g., tempdb engine)."},
      "nodes": {
        "type": "array",
        "description": "Pipeline nodes. Each node has type (DQL/DML/DDL), source, template ref, output block (for DQL), and depends_on array. The framework auto-detects the terminal as the single DQL sink with output.target='caller'."
      }
    },
    "additionalProperties": false
  }
}
```

Returns: created pipeline (with id, version, etc.).

Scope required: `author`.

#### 6.2.5 `pipelines_update`

Update an existing pipeline (creates new version).

Same input as `pipelines_create` plus required `id`. Returns the new version.

Scope required: `author`.

#### 6.2.6 `templates_list`

List templates.

```json
{
  "name": "templates_list",
  "description": "List SQL templates registered on this instance. Templates are reusable SQL generators authored in Freemarker; pipelines reference them by id+version.",
  "inputSchema": {
    "type": "object",
    "properties": {
      "dialect": {"type": "string", "enum": ["POSTGRES", "ORACLE", "MSSQL", "MYSQL", "H2", "DUCKDB", "SQLITE"]},
      "q": {"type": "string"},
      "limit": {"type": "integer", "default": 50, "maximum": 200}
    }
  }
}
```

#### 6.2.7 `templates_get`

Fetch a template body.

```json
{
  "name": "templates_get",
  "description": "Get the body and metadata of a specific template version.",
  "inputSchema": {
    "type": "object",
    "required": ["id"],
    "properties": {
      "id": {"type": "string"},
      "version": {"type": "integer"}
    }
  }
}
```

#### 6.2.8 `templates_create`

Create a new template.

```json
{
  "name": "templates_create",
  "description": "Create a new SQL template. Templates use Freemarker syntax. Variables referenced in the template body must be declared in params_schema (or supplied by a calculator in the executing pipeline).",
  "inputSchema": {
    "type": "object",
    "required": ["dialect", "body"],
    "properties": {
      "id": {"type": "string", "description": "Optional; auto-generated if omitted."},
      "dialect": {"type": "string", "enum": ["POSTGRES", "ORACLE", "MSSQL", "MYSQL", "H2", "DUCKDB", "SQLITE"]},
      "description": {"type": "string"},
      "params_schema": {"type": "object"},
      "body": {"type": "string"}
    }
  }
}
```

Scope required: `author`.

#### 6.2.9 `templates_render`

Render a template against a sample context (preview SQL).

```json
{
  "name": "templates_render",
  "description": "Render a template against the provided context values. Use this to preview the SQL that would be generated before executing a pipeline.",
  "inputSchema": {
    "type": "object",
    "required": ["id", "context"],
    "properties": {
      "id": {"type": "string"},
      "version": {"type": "integer"},
      "context": {"type": "object", "additionalProperties": true}
    }
  }
}
```

Returns: rendered SQL string.

#### 6.2.10 `datasources_list`

List registered datasources (without credentials).

```json
{
  "name": "datasources_list",
  "description": "List datasource connections registered on this instance. Returns name, dialect, and connection metadata — never passwords.",
  "inputSchema": {
    "type": "object",
    "properties": {
      "dialect": {"type": "string"}
    }
  }
}
```

#### 6.2.11 `datasources_get`

Fetch a single datasource (without password).

```json
{
  "name": "datasources_get",
  "description": "Get metadata for a single datasource.",
  "inputSchema": {
    "type": "object",
    "required": ["name"],
    "properties": {
      "name": {"type": "string"}
    }
  }
}
```

#### 6.2.12 `datasources_test`

Test that a datasource connection can be established.

```json
{
  "name": "datasources_test",
  "description": "Test connectivity to a datasource. Returns success/failure and server version on success. Useful for diagnosing pipeline connection errors.",
  "inputSchema": {
    "type": "object",
    "required": ["name"],
    "properties": {
      "name": {"type": "string"}
    }
  }
}
```

Returns: `{connected: bool, server_version: string?, error: string?}`.

#### 6.2.13 `executions_list`

List recent executions.

```json
{
  "name": "executions_list",
  "description": "List recent pipeline executions, optionally filtered by pipeline or status.",
  "inputSchema": {
    "type": "object",
    "properties": {
      "pipeline_id": {"type": "string", "format": "uuid"},
      "status": {"type": "string", "enum": ["RUNNING", "SUCCESS", "FAILED", "ABORTED"]},
      "limit": {"type": "integer", "default": 50, "maximum": 200}
    }
  }
}
```

#### 6.2.14 `executions_get`

Fetch metadata for a specific execution (no rows).

```json
{
  "name": "executions_get",
  "description": "Get metadata for a specific execution: status, timing, node_stats, parameters used. To get the result rows, use executions_get_result.",
  "inputSchema": {
    "type": "object",
    "required": ["execution_id"],
    "properties": {
      "execution_id": {"type": "string", "format": "uuid"}
    }
  }
}
```

#### 6.2.15 `executions_get_result`

Fetch result rows (paginated) for a completed execution.

```json
{
  "name": "executions_get_result",
  "description": "Fetch result rows for a completed execution. Paginated via offset+limit. Returns schema + rows. Available until the result TTL expires (default 5 minutes for claim-checked results; inline results from recent executions are also available for a short window).",
  "inputSchema": {
    "type": "object",
    "required": ["execution_id"],
    "properties": {
      "execution_id": {"type": "string", "format": "uuid"},
      "offset": {"type": "integer", "default": 0, "minimum": 0},
      "limit": {"type": "integer", "default": 10000, "maximum": 100000},
      "format": {"type": "string", "enum": ["json", "arrow", "csv"], "default": "json"}
    }
  }
}
```

Returns: schema + rows + pagination metadata. For `format: arrow` or `csv`, returns binary payload encoded as base64 in the result.

### 6.3 Tool result schema

All tool results follow this envelope:

```json
{
  "content": [
    {
      "type": "text",
      "text": "..."        // JSON-stringified payload for tools returning JSON
    }
  ],
  "isError": false
}
```

On error:

```json
{
  "content": [
    {
      "type": "text",
      "text": "{\"error\": {\"code\": \"...\", \"message\": \"...\", ...}}"
    }
  ],
  "isError": true
}
```

The inner JSON matches the [REST API error envelope's `error` object](rest-api.md#42-error-envelope) — same codes, same shape, so agents see consistent errors whether they come via REST or MCP.

---

## 7. Resource Surface

Resources are entities the agent can read as "files." Useful for agents that want to inspect definitions without calling tools.

### 7.1 Resource URI scheme

```
datapipelines://pipelines/{id}                                 → latest version, full body
datapipelines://pipelines/{id}/versions/{version}              → specific version
datapipelines://pipelines/{id}/parameters                      → parameter schema only
datapipelines://templates/{id}                                 → latest version
datapipelines://templates/{id}/versions/{version}
datapipelines://datasources/{name}                             → metadata, no password
datapipelines://datasources                                    → list
datapipelines://executions/{execution_id}                      → execution metadata
datapipelines://executions/{execution_id}/events               → SSE event replay as text
```

### 7.2 Resource examples

#### 7.2.1 `datapipelines://pipelines/{id}`

Returns the pipeline JSON body, content-type `application/json`.

#### 7.2.2 `datapipelines://templates/{id}/versions/{version}`

Returns the template body (Freemarker SQL), content-type `text/x-freemarker-sql`.

#### 7.2.3 `datapipelines://datasources/{name}`

Returns datasource metadata as JSON, with the password field redacted.

### 7.3 Resource discovery

Agents use `resources/list` to discover URIs:

```json
{
  "method": "resources/list",
  "params": {
    "cursor": "..."     // optional pagination cursor
  }
}
```

Returns a list of resource descriptors with URIs, names, descriptions, and MIME types.

### 7.4 No subscriptions in v1

We do not support `resources/subscribe` in v1. Resources change rarely enough that re-fetch on demand is sufficient. Subscription support is a v2 candidate (would let agents react to new pipeline versions, etc.).

---

## 8. Prompt Surface

Predefined prompts the agent can invoke via `prompts/get`. Useful for steering agents toward common workflows.

### 8.1 `analyze_pipeline`

```json
{
  "name": "analyze_pipeline",
  "description": "Guide the agent through analyzing a pipeline's structure, identifying potential issues, and suggesting improvements.",
  "arguments": {
    "type": "object",
    "required": ["pipeline_id"],
    "properties": {
      "pipeline_id": {"type": "string", "format": "uuid"}
    }
  }
}
```

Returns a prompt instructing the agent to fetch the pipeline definition, examine each node's template, validate SQL against the target dialect, check for performance issues, and report findings.

### 8.2 `create_pipeline_for_question`

```json
{
  "name": "create_pipeline_for_question",
  "description": "Guide the agent through creating a new pipeline to answer a natural-language question against the available datasources.",
  "arguments": {
    "type": "object",
    "required": ["question"],
    "properties": {
      "question": {"type": "string", "description": "The business question to answer."},
      "datasource_hint": {"type": "string", "description": "Optional: which datasource likely has the data."}
    }
  }
}
```

Returns a prompt instructing the agent to:
1. List available datasources.
2. Inspect schemas (via future `datasources_get_schema` tool — v2).
3. Design a pipeline.
4. Author required templates.
5. Validate and create the pipeline.
6. Execute and report results.

### 8.3 `debug_failed_execution`

```json
{
  "name": "debug_failed_execution",
  "description": "Guide the agent through diagnosing why an execution failed.",
  "arguments": {
    "type": "object",
    "required": ["execution_id"],
    "properties": {
      "execution_id": {"type": "string", "format": "uuid"}
    }
  }
}
```

Returns a prompt that walks the agent through reading the execution metadata, the failed node's error, the underlying datasource state, and proposing a fix.

---

## 9. Error Handling

### 9.1 MCP-level errors

Protocol violations (malformed JSON-RPC, missing required fields, unsupported method):

```json
{
  "jsonrpc": "2.0",
  "id": "...",
  "error": {
    "code": -32602,
    "message": "Invalid params: missing required field 'id'."
  }
}
```

Standard JSON-RPC error codes (`-32700` parse error, `-32600` invalid request, `-32601` method not found, `-32602` invalid params, `-32603` internal error).

### 9.2 Application errors

All datapipelines.co application errors (auth failures, validation errors, execution failures) are returned as **tool-call results with `isError: true`**, not as JSON-RPC errors. This is the MCP convention — domain errors are content, not protocol errors.

The error payload inside the tool result matches the [REST API `error` object](rest-api.md#42-error-envelope) exactly:

```json
{
  "content": [
    {
      "type": "text",
      "text": "{\"error\":{\"code\":\"pipeline.node.datasource_connection_failed\",\"message\":\"Could not acquire connection to 'pg-prod'.\",\"user_message\":\"...\",\"details\":{...},\"doc_url\":\"...\"}}"
    }
  ],
  "isError": true
}
```

### 9.3 Transport errors

- HTTP 401 → agent must re-authenticate.
- HTTP 403 → agent's key lacks scope.
- HTTP 429 → rate limited; agent should back off.
- HTTP 5xx → server error; agent should retry with backoff.

---

## 10. Logging

The server emits `notifications/message` per the MCP logging spec:

```json
{
  "method": "notifications/message",
  "params": {
    "level": "info",
    "logger": "datapipelines.executor",
    "data": {
      "message": "Node fetch_orders completed",
      "execution_id": "exec-uuid",
      "node_id": "fetch_orders",
      "duration_ms": 1266
    }
  }
}
```

Levels: `debug`, `info`, `notice`, `warning`, `error`, `critical`, `alert`, `emergency`.

Agents can use these for visibility into execution progress (alternative to polling `executions_get`). For full structured progress, agents should call `pipelines_execute` (which returns full progress).

---

## 11. Discovery

### 11.1 For end users

Users discover the MCP endpoint via the UI's "Connect an Agent" page, which exposes:

- The full MCP endpoint URL (`https://{host}/mcp`).
- API key creation/management.
- A copy-pasteable configuration snippet for common agents:
  - Claude Desktop: `mcpServers` JSON for `claude_desktop_config.json`.
  - Cursor: settings JSON.
  - Generic HTTP MCP client: connection details.

### 11.2 For agents

Agents discover the server's capabilities via the standard MCP `initialize` handshake. No out-of-band config required beyond endpoint URL + API key.

---

## 12. Open Questions / Future Additions

Out of scope for v1, tracked for future:

- **Dynamic per-pipeline tools**: register `pipeline_execute_{name}` tools for pipelines flagged as "agent-exposed," so an agent sees them by name rather than discovering them by listing.
- **Resource subscriptions**: `resources/subscribe` for live updates when pipelines/templates change.
- **Schema introspection tools**: `datasources_get_schema`, `datasources_get_tables`, `datasources_get_columns` — for agents that need to author SQL templates. Likely v1.1 — needed for the `create_pipeline_for_question` prompt to actually work.
- **Sampling**: support server-initiated LLM completions (rare for this product; agents do their own LLM work).
- **OAuth support**: when multi-tenant SaaS deployment materializes.
- **MCP roots**: not applicable (we are not a filesystem tool).
- **Result streaming via MCP**: stream pipeline execution events directly through the MCP transport as notifications, instead of returning them as a single tool result. Cleaner UX for long-running pipelines.

---

## 13. Security Review Checklist

(This section is normative for the implementation.)

- [ ] Every MCP endpoint requires auth (no unauthenticated access).
- [ ] API key validated on every request, not just session establishment.
- [ ] Scope enforced per tool (e.g., `pipelines_create` requires `author` scope).
- [ ] Datasource passwords never included in tool results or resources.
- [ ] Error messages do not leak credentials or internal network topology.
- [ ] Rate limiting enforced at the MCP layer (same limits as REST).
- [ ] All MCP traffic over TLS (enforced by deployment, not just recommended).
- [ ] Audit log records every tool call (tool name, caller, target pipeline, timestamp, success/failure).

---

## Appendix A: Change Log

| Date | Version | Author | Change |
|---|---|---|---|
| 2026-08-05 | v1.0 | initial draft | Initial MCP server spec: streamable HTTP transport, API key auth, 15 tools, 8 resource types, 3 prompts, error model |
| 2026-08-05 | v1.1 | propagation | Updated `pipelines_create` tool to v1.1 Pipeline Contract shape (no `terminal_node_id`, no `datasources_used`; nodes carry `type`, `output`, `settings`). |
