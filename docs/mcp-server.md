# MCP Server Specification

**Status:** v1.3 (frozen contract — additive-only changes after this point)
**Owner:** datapipelines.co core
**Depends on:** [Type System spec](type-system.md), [Pipeline Contract spec](pipeline-contract.md), [REST API spec](rest-api.md), [Auth spec](auth.md), [Templates spec](templates.md)
**Last updated:** 2026-08-07

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
2. **Tools for actions, resources for inspection.** If an agent needs to *do* something (execute a pipeline, create a template), it calls a tool. If it needs to *read* something (look at a pipeline definition), it reads a resource. We avoid duplicating read operations as both.
3. **API key, not OAuth.** Self-hosted, internal-users-only deployment model makes OAuth overkill. The user grabs an API key from the UI, passes it to their agent, the agent uses it — in either the `DP-API-Key` header or an `Authorization: Bearer dpk_...` header. See [Auth §8.5](auth.md#85-mcp-endpoint-mcp).
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

> **Implementation gate — RESOLVED at P6b (2026-08-10).** This spec is authored against the durable shape of MCP; the concrete protocol version string is a build-time input, not a frozen contract term. The checklist below was completed against the official specification and the shipped MCP SDK when the module was implemented:
>
> - [x] **Current protocol version string.** Pinned to `2025-06-18`, returned in `initialize.protocolVersion` (§5.1) and accepted in the `MCP-Protocol-Version` header (§3.2); a `PinnedTransport` decorator advertises it as the sole supported version.
> - [x] **Version-negotiation rule.** The server negotiates **down** to its pinned version — a client offering a newer version is served `2025-06-18` (verified in-process).
> - [x] **Streamable HTTP requirements.** The v1 server is **stateless**: `POST /mcp` accepts `application/json` + `text/event-stream`; `GET /mcp` for a server-initiated SSE stream is **optional and NOT served** (answered `405`) — so there are no server-to-client notifications in v1 (§5.1, §10), no `MCP-Session-Id` issuance, and no resumability headers. A stateful transport (session ids, the notification stream) is a v2 item ([ROADMAP §3.7](ROADMAP.md#37-mcp-server)).
> - [x] **SDK coordinates.** `io.modelcontextprotocol:mcp-core` + `mcp-json-jackson2`, both `mcp-sdk = 2.0.0`, pinned in the version catalog ([Module Structure §8](module-structure.md)).
>
> These resolutions were additive corrections to §3 and §5.1 only — the tool, resource, and prompt surfaces did not depend on them.

### 3.2 Endpoint structure

```
POST {host}/mcp
Headers:
  Content-Type: application/json
  Accept: application/json, text/event-stream
  DP-API-Key: dpk_<id>.<secret>       # OR: Authorization: Bearer dpk_<id>.<secret>
  MCP-Protocol-Version: 2025-06-18    # placeholder — pinned by the §3.1 implementation gate
  MCP-Session-Id: {session-uuid}      # optional; server may issue for stateful sessions
```

Exactly one credential carrier is required, and the two are equivalent: `DP-API-Key` (the REST convention, [REST API §3.6](rest-api.md#36-custom-header-registry)) or `Authorization: Bearer dpk_...` for MCP clients that can only set the standard Authorization header. Both route through the identical API-key validation path — see §4.1 and [Auth §8.5](auth.md#85-mcp-endpoint-mcp). Session JWTs (`dp_session` cookie, or a Bearer token that is not a `dpk_` key) are **not** accepted on `/mcp`.

Server response: JSON for single-message exchanges, `text/event-stream` for streamed responses.

### 3.3 Session lifecycle

- **Stateless by default.** Each request carries full auth context. Server does not require session continuity.
- **Optional session.** Server MAY issue an `MCP-Session-Id` for clients that want one. Session state = nothing important (cached auth, nothing else).

---

## 4. Authentication

### 4.1 Auth model

`/mcp` is **API-key-only**. Every MCP request must carry a datapipelines API key in one of two equivalent headers:

- `DP-API-Key: dpk_<id>.<secret>` — the REST convention; the primary case.
- `Authorization: Bearer dpk_<id>.<secret>` — for MCP clients that can only set the standard Authorization header (Claude Desktop and several others). The filter recognizes the `dpk_` prefix and routes the token through the identical validation path.

Both are validated by [Auth §7.3](auth.md#73-validation-flow) — same lookup, same Argon2id verification, same 60s-TTL revocation/liveness re-check ([Auth §11.4](auth.md#114-api-key-validation-cache)). A revoked key or a deactivated owner stops working within ~1 minute.

**Session JWTs are not accepted on `/mcp`.** There is no cookie auth and no non-`dpk_` Bearer token path — a browser-embedded MCP client must use an API key like any other agent.

API keys are:
- Issued per-user-per-agent from the UI (e.g., "Claude Desktop key", "GLM key"); HTTP surface in [REST API §16.1](rest-api.md#161-api-keys-any-authenticated-principal--own-keys-only).
- Revocable, optionally expiring.
- Scoped `read` / `execute` / `author` / `admin` (hierarchical, [Auth §7.5](auth.md#75-scopes)). A key's scopes are a subset of its creator's scopes at issue time.

**Scope enforcement.** The minimum scope for every MCP tool is defined once in the [Auth §7.6 scope ↔ operation matrix](auth.md#76-scope--operation-matrix-authoritative) — this spec restates each tool's requirement in §6.2 for readability but the matrix is authoritative on any conflict. The `admin` scope exists (datasource management, user administration) but **no v1 MCP tool requires it**: creating, editing, and deleting datasources is UI/REST-only. An `admin` key still works everywhere, since scopes are hierarchical.

**Security chain.** `/mcp` (both `POST` and `GET`) is an explicit matcher in the Spring Security filter chain: CSRF-exempt (no cookie auth to forge against), no session cookies accepted, same scope enforcement as REST, same per-user rate limits ([REST API §12](rest-api.md#12-rate-limiting)). See [Auth §8.5](auth.md#85-mcp-endpoint-mcp).

### 4.2 Unauthorized behavior

Missing credential (no `DP-API-Key` header and no Bearer `dpk_` token):
- HTTP `401 Unauthorized` with JSON body:
  ```json
  {"error": {"code": "auth.api_key.missing", "message": "..."}}
  ```
- MCP session is not established.

Invalid, revoked, expired, or deactivated-owner key:
- HTTP `401 Unauthorized` with `auth.api_key.invalid` (or `auth.api_key.expired`).

Insufficient scope (e.g., a `read` key calling `pipelines_create`):
- The transport-level answer is HTTP `403 Forbidden` with `auth.scope.insufficient` when the credential is rejected before dispatch. Once a session is established and a tool is dispatched, a scope failure is returned as a tool result with `isError: true` carrying the same `auth.scope.insufficient` code (§9.2) — agents must handle both.

Codes follow the `{domain}.{entity}.{failure}` convention; the registry of record is [Pipeline Contract §13.7](pipeline-contract.md#137-authentication--authorization).

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
    "tools": {"listChanged": false},
    "resources": {"listChanged": false, "subscribe": false},
    "prompts": {"listChanged": false}
  }
}
```

- `tools.listChanged: false` — the v1.1 tool surface is **static**: the same 18 tools (§6.1) for every caller, for the lifetime of the server. Advertising `true` would promise `notifications/tools/list_changed` messages the v1 server never sends. Dynamic per-pipeline tools (`pipeline_execute_{name}`, which would make the list genuinely mutable) are a v2 item — [ROADMAP §3.7](ROADMAP.md#37-mcp-server). When they land, this flips to `true` together with the notification implementation.
- `resources.listChanged: false` — the *set of resource URIs* does change as pipelines and executions are created, but the v1 server sends no change notifications; clients re-fetch `resources/list` (§7.3) when they need a current view.
- `resources.subscribe: false` — no live subscriptions in v1. Clients re-fetch resources as needed.
- `prompts.listChanged: false` — the prompt surface (§8) is static in v1.
- **No `logging` capability in v1.** The v1 transport is stateless (§3.3): it answers `GET /mcp` with `405`, so there is no server-to-client stream to carry `notifications/message`. Advertising `logging` would promise notifications no client can receive — the same reasoning as `listChanged: false`. Live progress during a blocking `pipelines_execute` (§6.2.3) is therefore not available in v1; the authoritative per-node record is the `node_stats` array in the tool's final result. Logging/progress notifications return with the stateful transport in v2 ([ROADMAP §3.7](ROADMAP.md#37-mcp-server)).

`protocolVersion` is the placeholder pending the §3.1 implementation-gate check. `serverInfo.version` is the datapipelines.co release version.

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
- `datasources_get_schemas`
- `datasources_get_tables`
- `datasources_get_columns`
- `executions_list`
- `executions_get`
- `executions_get_result`

A future enhancement: dynamically-generated per-pipeline tools (e.g., `pipeline_execute_monthly_revenue_report`) for pipelines the user wants to expose as named tools to agents. Marked for v2 ([ROADMAP §3.7](ROADMAP.md#37-mcp-server)) — this is why `tools.listChanged` is `false` in v1 (§5.1).

### 6.2 Tool definitions

Every tool definition below carries a **Scope** row: the minimum scope the calling API key must hold. Those values are sourced from the [Auth §7.6 scope ↔ operation matrix](auth.md#76-scope--operation-matrix-authoritative), which is authoritative — if this doc and the matrix ever disagree, the matrix wins. Scopes are hierarchical (`author` ⊃ `execute` ⊃ `read`; `admin` ⊃ all), so a listed scope is a floor, not an exact match. No v1 MCP tool requires `admin` (§4.1).

Every tool's result envelope, including its error shape, is §6.3.

#### 6.2.1 `pipelines_list`

List pipelines the caller has access to.

```json
{
  "name": "pipelines_list",
  "description": "List pipelines registered on this datapipelines.co instance, filtered by owner, datasource, or text search. Returns metadata (id, name, display_name, description, version, updated_at) — not the full body. Use pipelines_get for the body.",
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

Returns: array of pipeline metadata objects. Datasource references are per-node and are read from the body via `pipelines_get` — the listing does not aggregate them.

**Scope:** `read`.

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

**Scope:** `read`.

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
- The **first page of rows** inline (up to `datapipelines.result.page-size-rows`), plus `total_rows`, `has_more`, `result_url`, and `expires_at`.
- Warnings array (if any).

The result shape mirrors the [REST `data_ready` event](rest-api.md#647-data_ready) exactly — same fields (`schema`, the inline `rows` first page, `row_count`, `total_rows`, `has_more`, `result_url`, `expires_at`, and **`ttl_seconds`** so the agent knows its paging window without diffing timestamps), same uniform delivery model. There is no inline-vs-claim-check split: every caller result is materialized in Redis before the tool returns ([REST API §7.1](rest-api.md#71-model)). For a result that fits in one page, the inline rows ARE the whole result and no follow-up call is needed; when `has_more` is `true`, page the remainder with `executions_get_result` (§6.2.15) within the TTL.

A pipeline with **no caller node** ([Pipeline Contract §9](pipeline-contract.md#9-the-caller-node-result-node)) is legal — a pure write-back/ETL pipeline. Such an execution returns metadata, `node_stats`, and no `schema`/`rows`; this is success, not an error.

**Long-running executions.** The tool call is a **single blocking request**: it returns when the execution reaches a terminal state (`SUCCESS`, `FAILED`, `ABORTED`) or when `datapipelines.executor.execution-timeout-seconds` (default 600) elapses and the execution is aborted. For a 3-minute pipeline, the agent experiences one tool call that takes ~3 minutes; the HTTP response for that call stays open for the duration and the server writes nothing to it until the result is ready. (The REST SSE heartbeat, [REST API §6.6](rest-api.md#66-heartbeat-keepalive), is an SSE-stream concept and does not apply here — an MCP tool call is not an event stream. Operators must therefore ensure proxy/load-balancer idle timeouts on `/mcp` exceed `execution-timeout-seconds`; see [Deployment](deployment.md).)

MCP **progress notifications** for in-flight nodes are deliberately not implemented in v1 — the tool returns progress only as the final `node_stats`. Streaming execution events through the MCP transport is a v2 item ([ROADMAP §3.7](ROADMAP.md#37-mcp-server)). The v1 stateless transport delivers **no** server-to-client notifications of any kind (§5.1), so `node_stats` in the tool's final result is the authoritative and only per-node record.

**If the agent abandons the call** (aborts the HTTP request, client crash): a blocking `POST /mcp` gives the servlet no disconnect callback, so the `datapipelines.sse.disconnect-grace-seconds` cancellation that a dropped **REST SSE** stream gets ([REST API §6.8](rest-api.md#68-client-disconnect)) does **not** apply to an abandoned tool call in v1 — the execution runs until it finishes or hits `datapipelines.executor.execution-timeout-seconds`. To stop an in-flight execution deterministically, cancel it out-of-band via `DELETE /api/v1/executions/{id}` ([REST API §10.4](rest-api.md#104-cancel-execution)) from any instance — in-flight statements are interrupted and the abandoned tool call returns an `ABORTED` result. There is no resumption path (a reconnecting agent must re-execute) and no MCP cancel *tool* in v1.

**Scope:** `execute`.

#### 6.2.4 `pipelines_create`

Create a new pipeline.

```json
{
  "name": "pipelines_create",
  "description": "Create a new pipeline. The body must satisfy the Pipeline Contract: nodes must form a DAG; at most one DQL node may resolve to output.target='caller' (a node that omits its output block resolves to 'caller' by default); zero caller nodes is legal for pure write-back pipelines; all datasource references must exist in this environment; all template references must exist and dry-render against the declared parameters. Returns the created pipeline with server-assigned id and version 1.",
  "inputSchema": {
    "type": "object",
    "required": ["name", "display_name", "nodes"],
    "properties": {
      "name": {"type": "string", "pattern": "^[a-z0-9_]+$"},
      "display_name": {"type": "string"},
      "description": {"type": "string"},
      "parameters": {"type": "object", "description": "Declared pipeline parameters (name -> {type, required, default, description}). This is the ONLY parameter declaration point: the full parameter map, defaults applied, is the render context for every template the pipeline references."},
      "settings": {"type": "object", "description": "Pipeline-level execution settings (e.g., tempdb engine)."},
      "nodes": {
        "type": "array",
        "description": "Pipeline nodes. Each node has type (DQL/DML/DDL), source, template ref, depends_on array, and — for DQL only — an optional output block. Omitting output on a DQL node means output.target='caller'; at most one node per pipeline may resolve to 'caller'. A node whose data downstream nodes query must declare output.target='tempdb' with a table name explicitly."
      }
    },
    "additionalProperties": false
  }
}
```

Returns: created pipeline (with id, version, etc.).

The whole pipeline is validated before it is stored — no invalid pipeline ever reaches the database ([Pipeline Contract §2](pipeline-contract.md#2-design-principles)). Validation failures come back as a tool result with `isError: true` carrying the pipeline validation code (§9.2); the agent should fix and retry rather than assume partial creation.

**Scope:** `author`.

#### 6.2.5 `pipelines_update`

Update an existing pipeline (creates new version).

Same input as `pipelines_create` plus required `id`. Returns the new version. Same save-time validation applies.

**Scope:** `author`.

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
      "is_library": {"type": "boolean", "description": "Filter to library templates (macro collections) or executable templates."},
      "limit": {"type": "integer", "default": 50, "maximum": 200}
    }
  }
}
```

Returns: array of template metadata (`id`, `version`, `dialect`, `display_name`, `description`, `is_library`). A template's `description` is the only place it can hint at the parameters it expects — templates declare none ([Templates §3.2](templates.md#32-field-reference)).

**Scope:** `read`.

#### 6.2.7 `templates_get`

Fetch a template body.

```json
{
  "name": "templates_get",
  "description": "Get the body and metadata of a specific template version, including its imports array (the library macros it can call). Defaults to the latest version.",
  "inputSchema": {
    "type": "object",
    "required": ["id"],
    "properties": {
      "id": {"type": "string"},
      "version": {"type": "integer", "description": "Defaults to latest."}
    }
  }
}
```

**Scope:** `read`.

#### 6.2.8 `templates_create`

Create a new template.

```json
{
  "name": "templates_create",
  "description": "Create a new SQL template. Templates use Freemarker syntax. A template declares NO parameters of its own: the variables its body may reference are exactly the parameters declared by the pipeline that calls it, with defaults applied. Describe the variables you expect in 'description' — that free text is how humans and agents discover them. Macros from library templates are made available by listing them in 'imports'; the body must NOT contain <#import> or <#include> directives, they are synthesized from the imports array.",
  "inputSchema": {
    "type": "object",
    "required": ["dialect", "display_name", "description", "body"],
    "properties": {
      "id": {"type": "string", "description": "Optional; auto-generated if omitted. Pattern [a-z0-9_.-]+."},
      "engine": {"type": "string", "enum": ["freemarker"], "default": "freemarker", "description": "Template engine. v1 supports freemarker only."},
      "dialect": {"type": "string", "enum": ["POSTGRES", "ORACLE", "MSSQL", "MYSQL", "H2", "DUCKDB", "SQLITE"]},
      "display_name": {"type": "string"},
      "description": {"type": "string", "description": "Free text. State the variables the body expects and their types — the template declares none."},
      "imports": {
        "type": "array",
        "description": "Library templates whose macros this body calls. Aliases must be unique within the template; each referenced template must exist at that exact version and be is_library=true.",
        "items": {
          "type": "object",
          "required": ["id", "version", "alias"],
          "properties": {
            "id": {"type": "string"},
            "version": {"type": "integer"},
            "alias": {"type": "string", "description": "Namespace the macros are bound to, e.g. 'dates' → <@dates.date_range .../>."}
          },
          "additionalProperties": false
        }
      },
      "is_library": {"type": "boolean", "default": false, "description": "true if this template exists to be imported by others. A library body contains only <#macro>/<#function> definitions — no output outside macro definitions. body is still required."},
      "body": {"type": "string", "description": "Template source. Must not contain <#import> or <#include>."}
    },
    "additionalProperties": false
  }
}
```

Save-time validation is **parse-only** — syntax, forbidden constructs, import resolution ([Templates §7.1](templates.md#71-save-time-validation-is-parse-only)). A template is never rendered against a sample context at save time, because it does not know its callers' parameters; the dry-render check happens when a *pipeline* referencing it is saved ([Templates §7.2](templates.md#72-the-dry-render-rule-owned-by-pipeline-validation)). An agent authoring a template should therefore call `templates_render` (§6.2.9) with a representative context to confirm the SQL it produces.

**Scope:** `author`.

#### 6.2.9 `templates_render`

Render a template against a supplied context (preview SQL).

```json
{
  "name": "templates_render",
  "description": "Render a template against the provided context values and return the SQL it produces. Use this to preview generated SQL before creating a pipeline that references the template. The context is a free-form map: supply the same keys the calling pipeline would declare as parameters. Referencing a key absent from the context fails the render — that is the same failure a pipeline save would report.",
  "inputSchema": {
    "type": "object",
    "required": ["id", "context"],
    "properties": {
      "id": {"type": "string"},
      "version": {"type": "integer", "description": "Defaults to latest."},
      "context": {
        "type": "object",
        "description": "Render context: the parameter map a calling pipeline would provide, defaults already applied. Values follow the wire conventions of the Type System (BIGINTEGER/BIGDECIMAL as strings, TIMESTAMP with Z or offset).",
        "additionalProperties": true
      }
    },
    "additionalProperties": false
  }
}
```

Returns: rendered SQL string. This is a preview only — nothing is executed and nothing is stored.

**Scope:** `author` (it is the authoring loop's preview step; see the Auth §7.6 matrix).

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

**Scope:** `read`. (Creating, editing, and deleting datasources requires `admin` and is UI/REST-only — there is no MCP tool for it in v1.)

#### 6.2.11 `datasources_get`

Fetch a single datasource (without password).

```json
{
  "name": "datasources_get",
  "description": "Get metadata for a single datasource: name, dialect, JDBC URL, pool settings. Credentials are never returned.",
  "inputSchema": {
    "type": "object",
    "required": ["name"],
    "properties": {
      "name": {"type": "string"}
    }
  }
}
```

**Scope:** `read`.

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

**Scope:** `author` — testing a connection opens a real pool against a production database, so it sits above plain `read` even though it mutates nothing.

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

**Scope:** `read`.

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

**Scope:** `read`.

#### 6.2.15 `executions_get_result`

Fetch result rows (paginated) for a completed execution.

```json
{
  "name": "executions_get_result",
  "description": "Fetch result rows for a completed execution, paginated via offset+limit. Returns schema + rows + pagination metadata. Works for ANY completed execution that produced a caller result, of any size, until its TTL expires (default 300s, set at execution time). Order is stable across pages. Reading pages does NOT extend the TTL — after expiry the result is gone and the pipeline must be re-run.",
  "inputSchema": {
    "type": "object",
    "required": ["execution_id"],
    "properties": {
      "execution_id": {"type": "string", "format": "uuid"},
      "offset": {"type": "integer", "default": 0, "minimum": 0},
      "limit": {"type": "integer", "default": 1000, "minimum": 1, "maximum": 100000, "description": "Rows per page. Defaults to the server's result page size."},
      "format": {"type": "string", "enum": ["json", "arrow", "csv"], "default": "json"}
    },
    "additionalProperties": false
  }
}
```

This tool is a thin adapter over the REST cursor, [REST API §7](rest-api.md#7-result-delivery) — **identical semantics, identical guarantees**:

- `offset` / `limit` / `format` map one-to-one onto the cursor's query parameters. `offset` + `limit` paging over a result fully materialized in Redis before the cursor exists, so ordering is stable across pages.
- Availability is uniform: every completed execution with a caller node has its result stored, regardless of size. There is no inline-vs-claim-check distinction to reason about (that split was removed in REST API v1.3).
- TTL is fixed at result-write time (`datapipelines.result.ttl-default-seconds`, clamped between the min/max keys; a client may request one on the *execute* call via `DP-Result-TTL-Seconds`). Page reads never extend it.
- Auth: `read` scope **plus ownership** of the execution — `admin` may read any. Same rule as the REST cursor; the `result_url` is not a capability URL.

**JSON format** returns `{schema, rows, row_count, offset, limit, total_rows, has_more, expires_at}` — same body as [REST §7.3](rest-api.md#73-response-json-format-default).

**Binary columns and non-JSON formats.** `BINARY` column values in JSON results are base64 per the Type System's egress rules. For `format: "arrow"` or `"csv"`, and for any result whose encoded payload would exceed **1 MB**, the tool does **not** inline the bytes: it returns `{"result_url": "...", "expires_at": "...", "format": "...", "total_rows": N, "reason": "payload_exceeds_inline_cap"}` — the REST cursor URL, which the agent fetches with the same API key. Rationale: MCP tool results are model context; megabytes of base64 in a tool result poison an agent's window for no benefit. Payloads at or under the cap are inlined as base64 with their content type named.

**Errors** mirror [REST §7.6](rest-api.md#76-endpoint-errors) exactly, returned as tool results with `isError: true` (§9.2) — registry of record [Pipeline Contract §13.10](pipeline-contract.md#1310-result-retrieval):

| Code | Meaning for the agent |
|---|---|
| `result.execution_not_found` | Unknown execution id — check `executions_list`. |
| `result.execution_incomplete` | Still running; wait or re-check with `executions_get`. |
| `result.execution_failed` | The execution failed; there is no result. Use `executions_get` for the failure. |
| `result.expired` | TTL elapsed. Re-run the pipeline — the result is unrecoverable. |
| `result.format_unsupported` | Unknown `format` value. |

**Scope:** `read` (+ ownership).

#### 6.2.16 `datasources_get_schemas`

List a datasource's schemas — the entry point of the introspection flow.

```json
{
  "name": "datasources_get_schemas",
  "description": "List the schemas of a registered datasource by reading its live JDBC metadata, excluding the engine's own system schemas. The entry point of schema discovery: call this first, then get_tables(schema), then get_columns for only the tables the SQL needs. An empty list on a schemaless datasource is a valid answer. Read-only, for pipeline authoring.",
  "inputSchema": {
    "type": "object",
    "required": ["name"],
    "properties": {
      "name": {"type": "string", "description": "Datasource name."}
    }
  }
}
```

Returns: array of schema names, exactly as the driver reported them. On MySQL the databases arrive as JDBC catalogs (Connector/J defaults), so the listing reads them from `getCatalogs()` — the same vocabulary `datasources_get_tables` routes through; system schemas/databases (`information_schema`, `mysql`, `performance_schema`, `sys` on MySQL) are excluded on every dialect. **An empty list is a valid result** — a schemaless datasource (SQLite, single-db DuckDB) has no schemas to list. See [Datasources §7A](datasources.md#7a-schema-introspection). A connection failure against the datasource is the catalogued `pipeline.execution.datasource_unreachable` `isError` envelope — the same rule applies to §6.2.17/§6.2.18.

**Scope:** `author` — introspection opens a live connection against the datasource, matching the `datasources_test` precedent.

#### 6.2.17 `datasources_get_tables`

List a datasource's tables and views.

```json
{
  "name": "datasources_get_tables",
  "description": "List the tables and views of a registered datasource by reading its live JDBC metadata. The listing spans schemas — pass each table's reported schema to datasources_get_columns. Without a schema argument the listing fails on a datasource that reports no current schema (call datasources_get_schemas and pass one). Read-only, for pipeline authoring.",
  "inputSchema": {
    "type": "object",
    "required": ["name"],
    "properties": {
      "name": {"type": "string", "description": "Datasource name."},
      "schema": {"type": "string", "description": "Optional schema filter. An unknown schema matches nothing."}
    }
  }
}
```

Returns: `{"tables": [{"schema", "name", "type", "remarks"?}], "truncated": bool}` — `type` is the driver's raw JDBC table type (`TABLE`, `VIEW`, `BASE TABLE`, ...); `remarks` is the engine-stored table comment, omitted when the driver/database has none. The listing is capped at **2000 tables**; `truncated: true` means the cap dropped some. The `schema` filter is exact-match, not a LIKE pattern. Without a `schema` argument the listing **spans schemas** — pass each table's reported `schema` to `datasources_get_columns` (there, no schema argument means the connection's current schema only); the one exception is a datasource that reports **no current schema** (e.g. a database-less MySQL URL, where unfiltered would span every database the server grants): that call fails with the catalogued `pipeline.execution.parameter_required` and the caller passes an explicit schema from `datasources_get_schemas` instead.

**Scope:** `author` — introspection opens a live connection against the datasource, matching the `datasources_test` precedent.

#### 6.2.18 `datasources_get_columns`

List one table's columns with canonical types.

```json
{
  "name": "datasources_get_columns",
  "description": "List one table's columns with canonical types, read from the datasource's live JDBC metadata. Pass the table name exactly as datasources_get_tables returned it. Without a schema argument only the connection's current schema is read; if the datasource reports no current schema, an explicit schema is required (list them with datasources_get_schemas). Read-only, for pipeline authoring.",
  "inputSchema": {
    "type": "object",
    "required": ["name", "table"],
    "properties": {
      "name": {"type": "string", "description": "Datasource name."},
      "table": {"type": "string", "description": "Table name as returned by datasources_get_tables."},
      "schema": {"type": "string", "description": "Optional schema filter. An unknown schema matches nothing."}
    }
  }
}
```

Returns: array of `{"name", "type", "precision", "scale", "nullable", "source_type", "warnings", "remarks"}` — `type` is the canonical Type System type, `source_type` the driver's own type name, `warnings` the ingress mapper's warning messages (empty when the mapping was clean), `remarks` the engine-stored column comment (omitted when there is none); `precision`/`scale`/`nullable`/`remarks` are omitted when the metadata does not report them. An unknown table matches nothing and returns an empty list. `table` and `schema` are exact-match identifiers — JDBC metadata name matching is case-sensitive, `_`/`%` are not wildcards; pass the name `datasources_get_tables` returned. System-schema rows are excluded; without a `schema` argument the read defaults to the connection's current schema (routed per dialect, [Datasources §7A](datasources.md#7a-schema-introspection)) so same-named tables in different schemas cannot merge their columns — and a datasource that reports **no current schema** makes that default impossible, so the call fails with the catalogued `pipeline.execution.parameter_required` instead of silently merging (`datasources_get_schemas` lists the schemas to pass; schemaless datasources such as SQLite are the exception — there is nothing to merge).

**Scope:** `author` — introspection opens a live connection against the datasource, matching the `datasources_test` precedent.

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

Every tool result — success or error — carries the request's `correlation_id` in its `_meta`, echoing the `DP-Correlation-Id` of the underlying request so a user can hand an agent's output straight to an operator and have it traced ([Observability §9](observability.md)).

---

## 7. Resource Surface

Resources are entities the agent can read as "files." Useful for agents that want to inspect definitions without calling tools.

### 7.1 Resource URI scheme

```
datapipelines://pipelines/{id}                                 → latest version, full body
datapipelines://pipelines/{id}/versions/{version}              → specific version
datapipelines://pipelines/{id}/parameters                      → the pipeline's parameter declarations only
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
    "cursor": "eyJrIjoicGlwZWxpbmVzIiwibyI6MTAwfQ"    // optional; omit for the first page
  }
}
```

Returns a page of resource descriptors (URI, name, description, MIME type) plus `nextCursor`.

**Pagination is mandatory and normative:**

- **Page size is fixed at 100** descriptors. It is not client-controllable — an agent asking for "everything" must page.
- `cursor` is an **opaque server-issued token**. Clients MUST treat it as an opaque string: do not parse, construct, or persist it across server restarts. A cursor the server cannot decode → JSON-RPC `-32602` invalid params.
- The response omits `nextCursor` on the last page. Presence of `nextCursor` is the only "there is more" signal.
- Enumeration order is stable within a paging run (pipelines, then templates, then datasources, then executions; each by id). Entities created mid-run may be missed — `resources/list` is a discovery aid, not a consistent snapshot.

**Scope filtering:** the listing is filtered to what the calling key may read (`read` scope; ownership rules apply to executions), so two agents can see different resource sets on the same server.

**Execution resources are windowed:** only executions from the **last 24 hours** are enumerated. Older executions remain readable by direct URI (`datapipelines://executions/{id}`) as long as their metadata exists in the Metadata DB — they are simply not listed, because an unbounded execution history would make `resources/list` useless (and enormous) on any busy instance. Result rows are governed by the much shorter result TTL regardless (§6.2.15).

### 7.4 No subscriptions in v1

We do not support `resources/subscribe` in v1. Resources change rarely enough that re-fetch on demand is sufficient. Subscription support is a v2 candidate (would let agents react to new pipeline versions, etc.).

---

## 8. Prompt Surface

Predefined prompts the agent can invoke via `prompts/get`. Useful for steering agents toward common workflows.

**Admission rule:** a prompt ships only if every step it instructs the agent to take is achievable with the 18 tools in §6.1 and the resources in §7. A prompt that depends on a tool we have not built is a scripted failure — it reads as a supported capability and dead-ends the agent partway through. All three prompts meet the bar (§8.1, §8.2, §8.3); §8.2 returned in v1.1 together with the introspection tools it depends on.

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

Returns a prompt instructing the agent to fetch the pipeline definition (`pipelines_get`), read each referenced template (`templates_get`), preview the generated SQL (`templates_render`) against representative parameter values, check the SQL against the node's target dialect, look for performance issues, and report findings. Read-only: the prompt never instructs the agent to modify anything. Every step uses a v1 tool.

### 8.2 `create_pipeline_for_question`

**Shipped in v1.1** — returned together with the introspection tools it depends on (§6.2.16–18), which is what satisfies §8's admission rule: its schema-grounding step has an implementation, so the walkthrough cannot dead-end the agent or tempt it into hallucinating tables. (In v1 it was deliberately withheld for exactly that reason — a sequencing decision, not a rejection.)

```json
{
  "name": "create_pipeline_for_question",
  "description": "Guide the agent through building a pipeline that answers a natural-language question: discover the datasource, introspect its real schema, author the SQL template, create and execute the pipeline.",
  "arguments": {
    "type": "object",
    "required": ["question"],
    "properties": {
      "question": {"type": "string", "description": "The natural-language question to build a pipeline for (max 2000 characters)."}
    }
  }
}
```

Returns a prompt that walks the agent through:

1. `datasources_list` to pick the datasource holding the data the question needs.
2. `datasources_get_schemas` to see the schemas, then `datasources_get_tables(schema)` to list that schema's tables, then `datasources_get_columns` for **only the tables the SQL needs** — **never reference a table or column these tools did not return**; if the data is not there, the agent stops and says so instead of guessing.
3. `templates_create` for the SQL template, describing its expected variables in its description.
4. `pipelines_create` to assemble the pipeline.
5. `pipelines_execute` to run it and report the result.

The question is embedded between sentinel lines — `<<<QUESTION` and `QUESTION>>>`, each on its own line — that the instructions tell the agent to treat as the question to answer, never as instructions to follow. Containment instead of prohibition: quotes and newlines in the question cannot close or extend the block (a question cannot smuggle a line the agent might read as a step, because the fence only ends at the exact sentinel line), and a `question` containing either sentinel is refused with `-32602` — the fence cannot be forged from inside. The `question` argument is also length-capped at 2000 characters and refused when missing or blank; unlike §8.1/§8.3's UUID arguments it is free text by design (carrying the user's question is the feature), and the sentinel fence is the injection guard.

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

Returns a prompt that walks the agent through reading the execution metadata and failed node's error (`executions_get`), comparing against recent executions of the same pipeline (`executions_list`), reading the pipeline and the failing node's template (`pipelines_get`, `templates_get`), re-rendering that template with the failed execution's parameters to see the exact SQL (`templates_render`), checking datasource reachability (`datasources_test`), and proposing a fix. Every step uses a v1 tool.

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

- HTTP 401 (`auth.api_key.missing` / `.invalid` / `.expired`) → the key is absent, revoked, expired, or its owner was deactivated. Retrying does not help; the user must supply a new key.
- HTTP 403 (`auth.scope.insufficient`) → the key lacks the tool's minimum scope (§6.2, [Auth §7.6](auth.md#76-scope--operation-matrix-authoritative)). Retrying does not help; the user must mint a key with a higher scope.
- HTTP 429 (`rate_limit.exceeded`) → rate limited. Limits are **per-user**, shared across REST and MCP ([REST API §12](rest-api.md#12-rate-limiting)); honor `Retry-After` and back off.
- HTTP 5xx → server error; agent should retry with backoff.

---

## 10. Logging

> **Not delivered in v1.** The v1 transport is stateless (§3.3) and answers `GET /mcp` with `405`, so there is no server-to-client stream — the server advertises no `logging` capability (§5.1) and emits none of the notifications below. This section defines their **shape** for the stateful transport that lands with v2 ([ROADMAP §3.7](ROADMAP.md#37-mcp-server)); until then the authoritative per-node record is the `node_stats` array in a tool's final result. The rest of this section is v2-forward.

When emitted (v2), the server sends `notifications/message` per the MCP logging spec:

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

Log notifications carry the `correlation_id` of the originating request.

Agents can use these for visibility into an execution while a `pipelines_execute` call is still blocking (§6.2.3) — they are the v1 stand-in for MCP progress notifications, which are deferred to v2 ([ROADMAP §3.7](ROADMAP.md#37-mcp-server)). They are advisory: delivery requires the client to have an open `GET /mcp` notification stream, and nothing in the execution contract depends on them. The authoritative per-node record is the `node_stats` array in the tool's final result.

---

## 11. Discovery

### 11.1 For end users

Users discover the MCP endpoint via the UI's "Connect an Agent" page, which exposes:

- The full MCP endpoint URL (`https://{host}/mcp`).
- API key creation/management ([UI Screens](ui-screens.md); REST surface in [REST API §16.1](rest-api.md#161-api-keys-any-authenticated-principal--own-keys-only)), including the scope picker — the page must state which scope an agent needs for what it will do (`read` to browse, `execute` to run pipelines, `author` to create them) and that a key's scopes cannot exceed the creator's.
- A copy-pasteable configuration snippet for common agents, using whichever header that client supports (`DP-API-Key` or `Authorization: Bearer dpk_...` — §3.2):
  - Claude Desktop: `mcpServers` JSON for `claude_desktop_config.json`.
  - Cursor: settings JSON.
  - Generic HTTP MCP client: connection details.

### 11.2 For agents

Agents discover the server's capabilities via the standard MCP `initialize` handshake. No out-of-band config required beyond endpoint URL + API key.

---

## 12. Open Questions / Future Additions

Out of scope for v1, tracked for future ([ROADMAP](ROADMAP.md) is the authoritative queue):

- **Dynamic per-pipeline tools**: register `pipeline_execute_{name}` tools for pipelines flagged as "agent-exposed," so an agent sees them by name rather than discovering them by listing. Flips `tools.listChanged` to `true` (§5.1). v2, [ROADMAP §3.7](ROADMAP.md#37-mcp-server).
- **Result streaming / progress notifications via MCP**: stream execution events through the MCP transport instead of returning them only in the final tool result — removes the blocking-call experience of §6.2.3. v2, [ROADMAP §3.7](ROADMAP.md#37-mcp-server).
- **Resource subscriptions**: `resources/subscribe` for live updates when pipelines/templates change. v2, [ROADMAP §3.7](ROADMAP.md#37-mcp-server).
- **An MCP cancel tool**: v1 cancellation is `DELETE /api/v1/executions/{id}` over REST, or abandoning the blocking call (§6.2.3).
- **Datasource management tools**: deliberate omission, not an oversight — datasource CRUD is `admin`-scoped and UI/REST-only in v1 (§4.1).
- **Sampling**: support server-initiated LLM completions (rare for this product; agents do their own LLM work).
- **OAuth support**: when multi-tenant SaaS deployment materializes.
- **MCP roots**: not applicable (we are not a filesystem tool).

---

## 13. Security Review Checklist

(This section is normative for the implementation.)

- [ ] Every MCP endpoint requires auth (no unauthenticated access).
- [ ] API key validated on every request, not just session establishment — via `DP-API-Key` **and** `Authorization: Bearer dpk_...`, both through the single [Auth §7.3](auth.md#73-validation-flow) path. No second, laxer code path for the Bearer form.
- [ ] Session JWTs (`dp_session` cookie, non-`dpk_` Bearer tokens) are **rejected** on `/mcp` — verify with a test that a valid browser session cannot call a tool.
- [ ] Key revocation and owner deactivation take effect within the cache TTL (~60s) on `/mcp`, not just on REST.
- [ ] Scope enforced per tool against the [Auth §7.6 matrix](auth.md#76-scope--operation-matrix-authoritative) — one test per tool asserting the next-lower scope is refused with `auth.scope.insufficient`.
- [ ] Execution ownership enforced on `executions_get`, `executions_get_result`, and execution resources — a valid `read` key cannot read another user's results.
- [ ] `resources/list` filtered by the caller's scope and ownership (§7.3), not just paginated.
- [ ] Datasource passwords never included in tool results or resources; `datasources_test` failures do not echo credentials or JDBC URLs.
- [ ] Error messages do not leak credentials or internal network topology.
- [ ] Rate limiting enforced at the MCP layer — the same **per-user** limits as REST, shared across both surfaces (a user cannot double their budget by splitting traffic).
- [ ] `/mcp` is CSRF-exempt *because* it accepts no cookies — assert both halves; exemption without the cookie ban is a CSRF hole.
- [ ] All MCP traffic over TLS (enforced by deployment, not just recommended).
- [ ] Audit log records every tool call (tool name, caller, target entity, timestamp, success/failure) with the `correlation_id`.

---

## Appendix A: Change Log

| Date | Version | Author | Change |
|---|---|---|---|
| 2026-08-05 | v1.0 | initial draft | Initial MCP server spec: streamable HTTP transport, API key auth, 15 tools, 8 resource types, 3 prompts, error model |
| 2026-08-05 | v1.1 | propagation | Updated `pipelines_create` tool to v1.1 Pipeline Contract shape (no `terminal_node_id`, no `datasources_used`; nodes carry `type`, `output`, `settings`). |
| 2026-08-10 | v1.3 | P6b build (Gate C) | Aligned the frozen spec with the merged `mcp-server` module. Additive/corrective only. **§3.1 implementation-gate RESOLVED**: protocol version pinned `2025-06-18` (negotiate-down), the v1 transport is **stateless** — `GET /mcp` optional and NOT served (405), no session ids, no resumability; SDK `mcp-sdk 2.0.0`. **§5.1**: `logging` capability **removed** — a stateless transport has no stream to deliver `notifications/message`, so advertising it promised notifications no client can receive. **§10**: marked not-delivered-in-v1 (defines the v2 shape only); `node_stats` in a tool's final result is the authoritative per-node record. **§6.2.3**: corrected the abandoned-call paragraph — a blocking `POST /mcp` has no disconnect callback, so `disconnect-grace` cancellation does **not** apply to an abandoned MCP tool call (only out-of-band `DELETE /executions/{id}` + the execution timeout do); result-shape enumeration now lists `ttl_seconds` (mirrors REST `data_ready`). §6.2.9 (bare rendered-SQL string) and §6.2.10 (`dialect` free `{"type":"string"}`, no enum) unchanged — the code was aligned to them. Rate limiting on `/mcp` (§13), repository limit/offset push-down, execution-record persistence and the admin all-executions listing are cross-surface carry-forwards to `web`/`app` (P6a/P7), not defects in this module. |
| 2026-08-07 | v1.2 | consistency campaign | Per [SPEC-REVIEW-2026-08](SPEC-REVIEW-2026-08.md) §2.11. **[D11]** §3.2/§4.1 auth rewritten: `DP-API-Key` **or** `Authorization: Bearer dpk_...` through one validation path; session JWTs explicitly rejected; security-chain note added (auth §8.5). **[D15]** Scope row on all 15 tools sourced from the auth §7.6 matrix; `read-only` → `read`; `admin` acknowledged as required by no v1 tool. **[D9]** §6.2.15 rewritten to the uniform REST §7 cursor (offset/limit/format, fixed TTL, stable order, ownership check), 1 MB inline cap with cursor-URL fallback, `result.*` error table; §6.2.3 returns first page + `result_url` (claim-check language gone). **[D3/D12]** `templates_create` drops `params_schema`, gains `imports [{id,version,alias}]`, `is_library`, `engine`; `templates_render` context is a free-form parameter map. **[D1]** `pipelines_create` node description: omitted `output` → `caller`, at most one caller node, zero legal. **[D7]** §6.2.3 documents blocking-call semantics, execution timeout, abandoned-call cancellation after grace, and deferral of MCP progress notifications. **[D10]** `X-API-Key` → `DP-API-Key`. **[M]** §5.1 `listChanged: false` across capabilities; §6.2.1 drops `datasources_used`; §7.3 `resources/list` pagination specified (opaque cursor, page size 100, 24h execution window, scope filtering); §8.2 `create_pipeline_for_question` removed from the v1 surface (ROADMAP §2); §3.1 verification marker reframed as an implementation-gate checklist; §12 futures re-tiered against ROADMAP; §13 checklist expanded. |
| 2026-08-14 | v1.4 | v1.1 introspection build | Tool surface 15 → **18**: new §6.2.16 `datasources_get_schema`, §6.2.17 `datasources_get_tables`, §6.2.18 `datasources_get_columns` — read-only JDBC metadata introspection (`author` scope, the `datasources_test` precedent), sourced from datasources §7A with canonical type mapping, 200-table snapshot cap, empty-list-for-unknown-filter. §6.1 lists the three; §5.1 static-surface count updated; §12 future-work bullet removed (shipped). |
| 2026-08-14 | v1.5 | v1.1 introspection build | §8 prompt surface 2 → **3**: `create_pipeline_for_question` (§8.2) returns with the introspection tools it depends on. Admission-rule paragraph rewritten (18 tools; all three prompts meet the bar). `question` argument: free text by design, length-capped at 2000 chars (`-32602` outside 1..2000), embedded in a delimited data-not-instructions block. |
| 2026-08-15 | v1.6 | surface restructure (part 1) | **`datasources_get_schema` removed** (§6.2.16 block deleted) together with its REST twin `GET /datasources/{name}/schema`: the bundled whole-schema snapshot bundled columns into the table listing; table listings stay lightweight so more tables fit in one response. Tool surface 18 → **17**; §6.1, §5.1, §8 admission-rule counts updated; the introspection flow remains `datasources_get_tables` → `datasources_get_columns` until the schemas listing lands. |
| 2026-08-15 | v1.7 | surface restructure (part 2) | New §6.2.16 `datasources_get_schemas` — the introspection flow's entry point (schemas → tables → columns). Tool surface 17 → **18**; §6.1, §5.1, §8 counts updated. §6.2.17 `datasources_get_tables` description + Returns now state the flow contract: the unfiltered listing spans schemas — pass each table's schema to `datasources_get_columns`; §6.2.18's description now states that without a schema argument only the connection's current schema is read. MySQL databases arrive as JDBC catalogs, so the schemas listing reads `getCatalogs()`; an empty list is valid on schemaless dialects. |
| 2026-08-15 | v1.8 | semantics via remarks | §6.2.17/§6.2.18: table and column descriptors gain `remarks` — the engine-stored comment from JDBC REMARKS, omitted when the driver/database has none. |
| 2026-08-15 | v1.9 | surface restructure (part 3) | §8.2 `create_pipeline_for_question` walkthrough rewritten to the three-step grounding flow: `datasources_get_schemas` → `datasources_get_tables(schema)` → `datasources_get_columns` for only the tables the SQL needs. The never-reference-unreturned-tables rule and the sentinel fence are unchanged. |
