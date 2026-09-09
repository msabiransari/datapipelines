# MCP Server Specification

**Status:** v1.27 (frozen contract — additive-only changes after this point)
**Owner:** datapipelines.co core
**Depends on:** [Type System spec](type-system.md), [Pipeline Contract spec](pipeline-contract.md), [REST API spec](rest-api.md), [Auth spec](auth.md), [Templates spec](templates.md)
**Last updated:** 2026-09-05

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
6. **Workspace-scoped by the key (workspaces design §5.2/§9).** Every tool and resource operates inside the workspace the API key is PINNED to at issuance — `DP-Workspace` is refused on MCP requests (`400 workspace.header_forbidden`), because a header-switchable agent key would make every leaked key a skeleton key across the user's workspaces. Pipelines, templates and executions of other workspaces are ABSENT (not hidden): their ids resolve as not-found. Datasources visible here are exactly the pinned workspace's bound ones plus every global one. The `initialize` result's `instructions` field states this so an agent does not reason about invisible siblings.

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
- Issued per-user-per-agent from the UI's API screen (e.g., "Claude Desktop key", "GLM key"); HTTP surface in [REST API §16.1](rest-api.md#161-api-keys-any-authenticated-principal--own-keys-only).
- Revocable, optionally expiring.
- Scoped `read` / `execute` / `author` / `admin` (hierarchical, [Auth §7.5](auth.md#75-scopes)). A key's scopes are a subset of its creator's scopes at issue time.

**An agent's key is a `user` key — the same kind a program uses over REST.** The UI labels it "Agent / API key" for exactly that reason: one credential kind, two surfaces. The other two kinds ([Auth §7.7](auth.md#77-key-kinds-and-published-endpoint-bindings)) do not reach `/mcp` at all — an `endpoint` key authorises published endpoints and a `server` key the promotion routes, and each is refused here with `403 endpoint.key_kind_refused` by `McpAuthFilter` (the scope interceptor never sees `/mcp`, which is a servlet, so the refusal is made again at the transport). A scopeless key could otherwise read the whole tool catalogue through `tools/list` without being able to call any of it.

**Scope enforcement.** The minimum scope for every MCP tool is defined once in the [Auth §7.6 scope ↔ operation matrix](auth.md#76-scope--operation-matrix-authoritative) — this spec restates each tool's requirement in §6.2 for readability but the matrix is authoritative on any conflict. The `admin` scope exists (global datasource management, user administration) but **no MCP tool's minimum scope is `admin`**. `datasources_create` (§6.2.22) sits on `author` like the rest; binding a datasource `global: true` does require admin, but that is a workspaces D8 rule inside the shared create service, not a scope floor — the same rule REST applies. Editing and deleting datasources remain UI/REST-only. An `admin` key still works everywhere, since scopes are hierarchical.

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
  },
  "instructions": "This server is workspace-scoped: every tool and resource operates inside the workspace the API key is pinned to. ..."
}
```

- `instructions` (workspaces design §9) states the workspace context every agent reads first: content in other workspaces is absent (not hidden) — it resolves as not-found — and names are per-workspace for pipelines and templates while datasource names are globally unique. The full text ships as `McpServerFactory.SERVER_INSTRUCTIONS`.

- `tools.listChanged: false` — the tool surface is **static**: the same 30 tools (§6.1) for every caller, for the lifetime of the server. Advertising `true` would promise `notifications/tools/list_changed` messages the v1 server never sends. Dynamic per-pipeline tools (`pipeline_execute_{name}`, which would make the list genuinely mutable) are a v2 item — [ROADMAP §3.7](ROADMAP.md#37-mcp-server). When they land, this flips to `true` together with the notification implementation.
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
- `pipelines_execute_node`
- `pipelines_create`
- `pipelines_update`
- `templates_list`
- `templates_get`
- `templates_used_by`
- `templates_create`
- `templates_render`
- `datasources_list`
- `datasources_get`
- `datasources_test`
- `datasources_get_schemas`
- `datasources_get_tables`
- `datasources_get_columns`
- `datasources_preview_rows`
- `executions_list`
- `executions_get`
- `executions_get_result`
- `calculators_list`
- `calculators_get`
- `endpoints_create`
- `endpoints_list`
- `endpoints_get`
- `endpoints_delete`
- `lake_tables_register`
- `lake_tables_import`
- `lake_tables_unregister`

A future enhancement: dynamically-generated per-pipeline tools (e.g., `pipeline_execute_monthly_revenue_report`) for pipelines the user wants to expose as named tools to agents. Marked for v2 ([ROADMAP §3.7](ROADMAP.md#37-mcp-server)) — this is why `tools.listChanged` is `false` in v1 (§5.1).

### 6.2 Tool definitions

Every tool definition below carries a **Scope** row: the minimum scope the calling API key must hold. Those values are sourced from the [Auth §7.6 scope ↔ operation matrix](auth.md#76-scope--operation-matrix-authoritative), which is authoritative — if this doc and the matrix ever disagree, the matrix wins. Scopes are hierarchical (`author` ⊃ `execute` ⊃ `read`; `admin` ⊃ all), so a listed scope is a floor, not an exact match. No v1 MCP tool requires `admin` (§4.1).

Every tool's result envelope, including its error shape, is §6.3.

#### 6.2.1 `pipelines_list`

List pipelines the caller has access to.

```json
{
  "name": "pipelines_list",
  "description": "List the pipelines of the key's pinned workspace, filtered by owner, datasource, or text search. Returns metadata (id, name, display_name, description, version, status, updated_at) — version is the WORKING version and status says DRAFT or RELEASED, so an unreleased pipeline is visible as such. Not the full body. Use pipelines_get for the body; pipelines in other workspaces are absent from this listing and resolve as not-found by id. Pipeline names are FOLDER PATHS (finance/payments/daily_settlement): pass prefix to BROWSE one level of that tree — prefix:\"\" lists the roots, prefix:\"finance\" lists what is directly under finance — and q to SEARCH across full paths. Start with prefix:\"\" to see which roots this workspace already uses before creating a pipeline under a new one.",
  "inputSchema": {
    "type": "object",
    "properties": {
      "owner": {"type": "string", "description": "Filter by owner user ID."},
      "datasource": {"type": "string", "description": "Filter by datasource name."},
      "q": {"type": "string", "description": "Full-text search on name and description. Searches across full paths; use prefix to browse instead."},
      "prefix": {"type": "string", "description": "Browse ONE level of the folder tree instead of listing flat: returns that prefix's direct sub-folders (with counts) and its direct children. An empty string is the root. Use this to discover which roots and folders exist; use q to search across full paths."},
      "limit": {"type": "integer", "default": 50, "maximum": 200}
    }
  }
}
```

Returns: array of pipeline metadata objects. Datasource references are per-node and are read from the body via `pipelines_get` — the listing does not aggregate them.

**`version` is the working version, and `status` names it** (D55/D56, 099): the draft's number when the pipeline has a draft, else the latest released one, with `status` = `DRAFT` or `RELEASED`. Since creation lands a DRAFT, a listing that reported the released pointer alone would show nothing at all for every freshly authored pipeline, and `version: 1` on its own could not tell a reviewed release from a draft nobody has looked at. Both fields are also `null` in the one case where a pipeline has no version at all — its sole draft was purged, which deletes the entity with it ([versioning §3.2](versioning.md#32-entity-status-is-derived-names-are-unique-forever)).

**Two presentations, chosen by `prefix`** (067). Pipeline names are folder paths ([Pipeline Contract §3.2](pipeline-contract.md#32-field-reference), [Template Hierarchy §14](template-hierarchy-design.md)), so an agent needs to BROWSE as well as search:

- **`prefix` absent** — the flat listing above, under `owner`/`datasource`/`q`. Unchanged.
- **`prefix` present** (`""` is the ROOT) — **one level** of the tree, and the return shape is an object rather than an array:

  ```jsonc
  {
    "prefix": "nyc",
    "folders": [{"path": "nyc/mobility", "segment": "mobility", "pipeline_count": 6}],
    "pipelines": [ /* the level's own leaves, same metadata objects */ ],
    "total": 0,
    "has_more": false
  }
  ```

  `folders` are the prefix's DIRECT sub-folders with their whole-subtree counts; `pipelines` are its direct children only. Never a subtree, never the whole list. `owner`, `datasource` and `q` are ignored while `prefix` is present — browse and search are different presentations. A prefix is a FOLDER PATH — 1 to 9 segments, not the 2-to-10 a NAME takes since 077 — so `nyc` browses; one that is not a legal folder path answers an empty level, not an error.

**When to use which:** `prefix` to discover structure ("what roots exist? what is under `finance`?"), `q` to find something by name across full paths. Start a naming decision with `prefix: ""`.

**Scope:** `read`.

#### 6.2.2 `pipelines_get`

Fetch a full pipeline definition.

```json
{
  "name": "pipelines_get",
  "description": "Get the full definition of a pipeline (the working version by default — the draft when unreleased edits exist, else the latest released version — or a specific version). Use this to read the pipeline body before executing or modifying it. The result carries the version, its status and body_hash — echo body_hash back as expected_hash on pipelines_update; a draft pointer is present when unreleased edits exist. When a node pins a template version that a newer released version outdates, an upgrade_available array names the node, the template and both versions — an offer to re-pin via pipelines_update, never an automatic change.",
  "inputSchema": {
    "type": "object",
    "required": ["id"],
    "properties": {
      "id": {"type": "string", "format": "uuid", "description": "Pipeline ID."},
      "version": {"type": "integer", "description": "Specific version. Defaults to the working version: the draft when one exists, else the latest released."}
    }
  }
}
```

Returns: full pipeline JSON body (per [Pipeline Contract §3](pipeline-contract.md#3-top-level-pipeline-schema)) merged with the fields the version-lifecycle protocol needs (versioning §4.2, since 035): the version's `body_hash` and `status` — echo `body_hash` back as `expected_hash` on `pipelines_update` — plus `current_version` (the latest RELEASED version, what execute-default runs) and a `draft` pointer when unreleased edits exist. Since 039 the DEFAULT body is the **working version** (versioning §7): the DRAFT when one exists, else the latest released — an agent that read released while a draft was open would rebase on stale content and quietly discard the draft with its next write. The response always states which `version` and `status` it returned; an explicit `version` argument still wins.

Since 078, the body's `parameters` also lists the pipeline's **derived execute inputs**: one entry per CALCULATOR node's `context_key` — `{"type": <kind output wire type, or "ANY">, "required": false, "derived": true}` — because a calculator key is an implicit optional input of `pipelines_execute` (pipeline-contract §4.10: supply it and the node is skipped). Declared parameters carry no `derived` flag; absence is the false. Derived on read, never stored — the entries must not be echoed back on `pipelines_update`.

Since 040, the response also carries `upgrade_available` **whenever a node's pinned template has a newer RELEASED version** (040 D5): one `{node, template_id, pinned, latest_released}` row per outdating pin, computed from the very body being returned. Absent when no pin is outdated (omit-when-empty, the envelope convention). Surfaced, never applied — moving a pin is a pipeline edit (`pipelines_update`) and stays the caller's decision; a pin of a template DRAFT version is not an upgrade (the author is ahead of release, which is information, not a prompt). See [Templates §5.4](templates.md#54-used-by-the-reverse-arrow-v19-040).

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
      "version": {"type": "integer", "description": "Specific version to run. Defaults to the WORKING version: the draft when one exists, else the latest released. Never clamped — an unknown version is refused, not rounded to the latest."},
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

**With no `version`, this runs the WORKING version** (D56, 099): the draft when one exists, else the latest release — "always run the LAST version". On a development server that may well be a draft (drafts have been executable since 039); on a hardened server `authoring-enabled=false` refuses every draft-creating write, so the working version is a RELEASED version by construction ([versioning §7.2](versioning.md#72-execute-with-no-version-runs-the-working-version-d56-099)). The execution record pins the version that actually ran and the executions screen marks a draft run, so a result is never ambiguous about what produced it. An explicit `version` is exact and never clamped.

**Scope:** `execute`.

#### 6.2.4 `pipelines_create`

Create a new pipeline.

```json
{
  "name": "pipelines_create",
  "description": "Create a new pipeline. The body must satisfy the Pipeline Contract: nodes must form a DAG; at most one DQL node may resolve to output.target='caller' (a node that omits its output block resolves to 'caller' by default); zero caller nodes is legal for pure write-back pipelines; all datasource references must exist in this environment; all template references must exist and dry-render against the declared parameters. A node may also be type='CALCULATOR': it evaluates one catalog function and writes a typed value into the execution Context under context_key, which downstream nodes bind as :context_key — call calculators_list first for the kinds and their input names, and remember that a node referencing another node's context_key must depend_on it. A NEW top-level folder is refused until you confirm it: reuse an existing root, or ask the person first and then pass confirm_new_root: true. Returns the created pipeline with server-assigned id and version 1, which lands as a DRAFT: run it straight away, then STOP — a human releases it from the UI, and no tool releases anything.",
  "inputSchema": {
    "type": "object",
    "required": ["name", "display_name", "nodes"],
    "properties": {
      "name": {"type": "string", "pattern": "^[a-z0-9][a-z0-9_.-]{0,63}(/[a-z0-9][a-z0-9_.-]{0,63}){1,9}$", "description": "Machine name, and a FOLDER PATH: 2-10 lower-case '/'-separated segments (finance/payments/daily_settlement). A FOLDER IS REQUIRED — a bare 'daily_settlement' is refused with pipeline.validation.name_invalid and details.reason='folder_required'; put experiments under test/. The root segment says who owns it — list the existing roots with pipelines_list {prefix: ''} and reuse one; ASK before minting a new root. Keep a pipeline under the same prefix as the templates it uses. There is no rename: the name is the pipeline's identity, so choose the folder now."},
      "display_name": {"type": "string"},
      "description": {"type": "string"},
      "parameters": {"type": "object", "description": "Declared pipeline parameters (name -> {type, required, default, description}). This is the ONLY parameter declaration point: the full parameter map, defaults applied, is the render context for every template the pipeline references."},
      "settings": {"type": "object", "description": "Pipeline-level execution settings (e.g., tempdb engine)."},
      "nodes": {
        "type": "array",
        "description": "Pipeline nodes. Each node has type (DQL/DML/DDL/PIPELINE), source, template ref, depends_on array, and — for DQL only — an optional output block. Omitting output on a DQL node means output.target='caller'; at most one node per pipeline may resolve to 'caller'. A node whose data downstream nodes query must declare output.target='tempdb' with a table name explicitly. A PIPELINE node instead carries a pipeline ref {name, version} pinning an existing pipeline version to execute as a child execution, an optional parameters map (typed literals, or '${parent_param}' to pass a parent parameter through), and an optional output block allowed only when the pinned child has a caller node; it declares neither source nor template."
      },
      "confirm_new_root": {"type": "boolean", "description": "Set true ONLY after a person has agreed to a new top-level folder. A name whose root segment has no pipelines or templates under it yet is refused with details.existing_roots listing the roots that do exist — reuse one of those, or ask the person first and then pass this. 'test/' never needs it."}
    },
    "additionalProperties": false
  }
}
```

Returns: created pipeline — `id`, `version: 1`, **`status: "DRAFT"`**, `body_hash` (carry it into your next `pipelines_update`), `current_version: null` (nothing released yet) and the `draft` pointer.

**Creation lands a DRAFT (D55, 099).** `POST /pipelines` used to land version 1 RELEASED so that an MCP-authored pipeline was immediately executable; drafts have been executable since 039, so that justification bought nothing and cost a review — an agent following the old rule produced a released pipeline no human had looked at. The golden path for an agent is therefore: create → execute (no `version`, which runs your draft) → read the result → **stop and tell the person it is ready to review**. Releasing is a human action in the UI; there is no release tool and there will not be one (versioning D4).

**A new ROOT folder needs the person's say-so (094).** The root segment says who owns a thing and there is no rename, so this tool REFUSES a name whose first segment has nothing under it yet — `pipeline.validation.new_root_requires_confirmation`, with `details.root` and `details.existing_roots` (the same one-level query `pipelines_list {"prefix": ""}` serves). Reuse one of those roots, or ask the person and retry with `confirm_new_root: true`. `test/` is always allowed. This is an AGENT-surface rule only: REST, the UI and `pipelines_update` are unaffected — a person choosing a folder in a form has already decided, and an update cannot change a name. It replaces an INSTRUCTION with a GUARANTEE: the schema and the SKILL already told an agent to list the roots and ask, and a model that did not, did not.

The whole pipeline is validated before it is stored — no invalid pipeline ever reaches the database ([Pipeline Contract §2](pipeline-contract.md#2-design-principles)). Validation failures come back as a tool result with `isError: true` carrying the pipeline validation code (§9.2); the agent should fix and retry rather than assume partial creation.

**Scope:** `author`.

#### 6.2.5 `pipelines_update`

Update an existing pipeline by writing its DRAFT (versioning §3.2/§7, since 035).

Same input as `pipelines_create` plus required `id` and required `expected_hash` — the `body_hash` of the version this edit is based on (`pipelines_get` or the previous update's result). The first update after a release creates the draft (copy-on-write); later updates overwrite that same draft in place. Same save-time validation applies.

Returns: the draft version — `version`, `status: "DRAFT"`, `body_hash` (carry this into the next write), `current_version` (the unmoved released pointer), and the `draft` pointer. **The update does NOT release**: an agent leaves the draft for a human to review and release from the UI (versioning D4). On `pipeline.version.conflict` someone else modified it after you loaded it — re-read, rebase, retry; never retry blindly.

**Scope:** `author`.

#### 6.2.6 `templates_list`

List templates.

```json
{
  "name": "templates_list",
  "description": "List the templates of the key's pinned workspace. Templates are reusable generators authored in Freemarker, referenced by id+version; each has a fixed type — 'sql' renders SQL for pipeline nodes (and carries a dialect), 'html' renders escaped output and declares none. Template ids are unique per workspace — another workspace's template resolves as not-found.",
  "inputSchema": {
    "type": "object",
    "properties": {
      "dialect": {"type": "string", "enum": ["POSTGRES", "ORACLE", "MSSQL", "MYSQL", "H2", "DUCKDB", "SQLITE", "LAKE"]},
      "type": {"type": "string", "enum": ["sql", "html"], "description": "Filter by template kind: 'sql' (pipeline-referenced SQL) or 'html' (rendered output)."},
      "q": {"type": "string"},
      "prefix": {"type": "string", "description": "Browse ONE level of the folder tree instead of listing flat: returns that prefix's direct sub-folders (with counts) and its direct children. An empty string is the root. Use this to discover which roots and folders exist; use q to search across full paths."},
      "is_library": {"type": "boolean", "description": "Filter to library templates (macro collections) or executable templates."},
      "limit": {"type": "integer", "default": 50, "maximum": 200}
    }
  }
}
```

Returns: array of template metadata (`id`, `version`, `type`, `dialect`, `display_name`, `description`, `is_library`; `dialect` is null for `html` templates, since 046). A template's `description` is the only place it can hint at the parameters it expects — templates declare none ([Templates §3.2](templates.md#32-field-reference)).

**Two presentations, chosen by `prefix`** (067). Template ids have been paths since 043 and the templates browser has rendered them as a tree since 047, but this tool had no way to browse a folder at all until now:

- **`prefix` absent** — the flat listing above. Unchanged.
- **`prefix` present** (`""` is the ROOT) — **one level**, the same shape `pipelines_list` returns with `prefix`, keyed `templates` instead of `pipelines` and `template_count` instead of `pipeline_count`:

  ```jsonc
  {
    "prefix": "nyc",
    "folders": [{"path": "nyc/mobility", "segment": "mobility", "template_count": 7}],
    "templates": [ /* the level's own leaves, same metadata objects */ ],
    "total": 0,
    "has_more": false
  }
  ```

  `dialect` and `type` still narrow both halves, so a folder whose whole subtree is filtered out is absent rather than empty. `is_library` narrows the level's leaves only — a folder count is over the whole subtree, and quietly subtracting library templates from it would make the tree disagree with what expanding the folder shows. `q` is ignored while `prefix` is present.

**When to use which:** `prefix` to browse, `q` to search. A pipeline and the templates it uses should share a prefix — see [Template Hierarchy §15](template-hierarchy-design.md).

**Scope:** `read`.

#### 6.2.7 `templates_get`

Fetch a template body.

```json
{
  "name": "templates_get",
  "description": "Get the body and metadata of a template version, including its imports array (the library macros it can call). Defaults to the working version — the draft when unreleased edits exist, else the latest released.",
  "inputSchema": {
    "type": "object",
    "required": ["id"],
    "properties": {
      "id": {"type": "string"},
      "version": {"type": "integer", "description": "Specific version. Defaults to the working version: the draft when one exists, else the latest released."}
    }
  }
}
```

**Scope:** `read`. The returned projection states its `version` and `status` — since 039 the default is the **working version** (versioning §7: the DRAFT when one exists, else the latest released), the template mirror of `pipelines_get`.

#### 6.2.8 `templates_create`

Create a new template.

```json
{
  "name": "templates_create",
  "description": "Create a new template. Templates use Freemarker syntax. A template declares NO parameters of its own: the variables its body may reference are exactly the parameters declared by the pipeline that calls it, with defaults applied. Describe the variables you expect in 'description' — that free text is how humans and agents discover them. Macros from library templates are made available by listing them in 'imports'; the body must NOT contain import or include directives, they are synthesized from the imports array. The 'type' is chosen here and never changes afterwards: 'sql' (default) requires a dialect and is what pipeline nodes reference; 'html' takes no dialect and renders through an auto-escaping engine. A NEW top-level folder is refused until you confirm it: reuse an existing root, or ask the person first and then pass confirm_new_root: true. Version 1 lands as a DRAFT: a pipeline draft may pin it and render against it while you iterate, and a human releases it from the UI — a RELEASED pipeline may only pin RELEASED template versions, so the template is released first.",
  "inputSchema": {
    "type": "object",
    "required": ["display_name", "description", "body"],
    "properties": {
      "id": {"type": "string", "pattern": "^[a-z0-9][a-z0-9_.-]{0,63}(/[a-z0-9][a-z0-9_.-]{0,63}){1,9}$", "description": "Template id, and a FOLDER PATH: 2-10 lower-case '/'-separated segments (nyc/mobility/daily_by_zone.sql). A FOLDER IS REQUIRED — a bare 'daily_by_zone.sql' is refused with template.validation.id_invalid and details.reason='folder_required'; put experiments under test/, and shared macros under <owner>/lib/. Keep a template under the same prefix as the pipelines that read it. Optional; auto-generated if omitted. There is no rename, so choose the folder now."},
      "engine": {"type": "string", "enum": ["freemarker"], "default": "freemarker", "description": "Template engine. v1 supports freemarker only."},
      "type": {"type": "string", "enum": ["sql", "html"], "default": "sql", "description": "Template kind, fixed at creation and identical on every version: 'sql' renders SQL for pipeline nodes (requires 'dialect'); 'html' renders HTML through an auto-escaping engine (must have NO 'dialect')."},
      "dialect": {"type": "string", "enum": ["POSTGRES", "ORACLE", "MSSQL", "MYSQL", "H2", "DUCKDB", "SQLITE", "LAKE"], "description": "SQL execution target. Required when type is 'sql' (the default); forbidden when type is 'html' — an html template declares no dialect."},
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
      "body": {"type": "string", "description": "Template source. Must not contain <#import> or <#include>."},
      "confirm_new_root": {"type": "boolean", "description": "Set true ONLY after a person has agreed to a new top-level folder. A name whose root segment has no pipelines or templates under it yet is refused with details.existing_roots listing the roots that do exist — reuse one of those, or ask the person first and then pass this. 'test/' never needs it."}
    },
    "additionalProperties": false
  }
}
```

**A new ROOT folder needs the person's say-so (094).** Same rule as [§6.2.4](#624-pipelines_create), same `confirm_new_root` argument, same `details.root` / `details.existing_roots` shape — the code is `template.validation.new_root_requires_confirmation` and the roots come from `templates_list {"prefix": ""}`. An OMITTED `id` is generated under `test/` and needs no confirmation.

Save-time validation is **parse-only** — syntax, forbidden constructs, import resolution, and the type/dialect consistency rules (`sql` requires `dialect`, `html` forbids it; a payload trying to change an existing template's `type` is refused — [Templates §7.1](templates.md#71-save-time-validation-is-parse-only)). A template is never rendered against a sample context at save time, because it does not know its callers' parameters; the dry-render check happens when a *pipeline* referencing it is saved ([Templates §7.2](templates.md#72-the-dry-render-rule-owned-by-pipeline-validation)). An agent authoring a template should therefore call `templates_render` (§6.2.9) with a representative context to confirm the output it produces.

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
  "description": "List the datasource connections visible in the key's pinned workspace: its workspace-bound datasources plus every global one. Returns name, dialect, workspace and connection metadata — never passwords. Datasources bound to other workspaces are absent, not hidden.",
  "inputSchema": {
    "type": "object",
    "properties": {
      "dialect": {"type": "string"}
    }
  }
}
```

**Scope:** `read`. (Registering, editing and deleting a datasource are UI/REST-only — [§6.2.22](#6222-removed--no-datasource-writes-on-this-surface): no credential travels through an agent.) The listing is scoped to the key's pinned workspace exactly like REST §9.2.

#### 6.2.11 `datasources_get`

Fetch a single datasource (without password).

```json
{
  "name": "datasources_get",
  "description": "Get metadata for a single datasource visible in the key's pinned workspace: name, dialect, JDBC URL, workspace, readonly flag, pool settings. Credentials are never returned. A datasource bound to another workspace resolves as not-found.",
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

**Returns:** `name`, `display_name`, `description`, `dialect`, `jdbc_url`, `username`, `query_timeout_seconds`, `pool` (the hikari map), `readonly` (boolean — the §5.7 flag, machine-readable so an agent can see BEFORE authoring that DML/DDL/output-datasource uses will be refused), `workspace` (string or null — the bound workspace's name; null = global) — plus `introspection_include_schemas` ([Datasources §3.3](datasources.md#33-field-reference)) **when the allowlist is non-empty** (omitted when empty, the same envelope convention as REST §3.2), so an agent debugging why a schema is or isn't visible in the §6.2.16–18 introspection tools can see that an allowlist is active. Credentials are never returned. `datasources_list` (§6.2.10) emits the same per-datasource shape.

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
  "description": "Get metadata for a specific execution: status, timing, node_stats, parameters used. On a FAILED execution, error carries the full failure record: code, message, correlation_id, node context (datasource, dialect, pinned template), the rendered SQL (:name form, no bound values) and the exception chain with stack frames — read error.code first, then error.exception.caused_by (root cause LAST), then error.sql; quote error.correlation_id when escalating. To get the result rows, use executions_get_result.",
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

**Response (057, on a `FAILED` execution):** `error` is the full failure record — the same object the SSE stream carried and `error_json` stores. (Plain fence: an example, not a tool definition — §6.2's `json` fences are exactly the input schemas `McpToolSurfaceSpecDriftTest` pins.)

```
{
  "status": "FAILED",
  "failed_node_id": "stage_daily_trips",
  "error": {
    "code": "pipeline.node.datasource_connection_failed",
    "message": "Failed to initialize pool",
    "details": {"phase": "connect", "node_id": "stage_daily_trips"},
    "correlation_id": "1b0e6a52-…",
    "node": {"id": "stage_daily_trips", "type": "DQL", "datasource": "sample-trips", "dialect": "POSTGRES",
             "template": "nyc/mobility/sample_trips_daily.sql", "template_version": 1},
    "sql": "SELECT * FROM trips WHERE borough = :borough",
    "exception": {
      "class": "java.lang.RuntimeException", "message": "Failed to initialize pool",
      "frames": ["…capped at 40 per level…"],
      "caused_by": [
        {"class": "org.postgresql.util.PSQLException",
         "message": "FATAL: password authentication failed for user \"dp_demo_ro\"", "frames": ["…"]}
      ]
    }
  }
}
```

Read `error.code` first, then `error.exception.caused_by` (outermost first — the **root cause is the LAST entry**), then `error.sql`. Quote `error.correlation_id` when escalating to a human; it is the field that joins the page to the server log. Under `datapipelines.executions.error-detail=structured` (Configuration §3.11) the `exception` and `sql` keys are absent; everything else stays. `pipelines_execute`'s failure result (§6.3) carries the same record inline, so a failed run's error needs no second call.

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
  "description": "List the namespaces of a registered datasource by reading its live JDBC metadata, excluding the engine's own system schemas. The entry point of schema discovery: call this first, then get_tables(namespace), then get_columns for only the tables the SQL needs. Each entry carries an ordered `namespace` path and a `label`; on a two-level engine (catalog.schema, project.dataset) two entries can share a label and differ only by their outer segment, so pass the whole `namespace` back rather than the label. `schemas` repeats the labels for older clients. An empty list on a datasource with no namespaces is a valid answer. Read-only, for pipeline authoring.",
  "inputSchema": {
    "type": "object",
    "required": ["name"],
    "properties": {
      "name": {"type": "string", "description": "Datasource name."}
    }
  }
}
```

Returns: `{"schemas": ["label", ...], "entries": [{"namespace": [...], "label": "..."}], "truncated": bool}` — the namespaces exactly as the driver reported them, as a page. **Read `entries`**: `namespace` is the ordered path (outermost first) to pass back to `datasources_get_tables`/`_get_columns`, and `label` is its last segment. On a two-level engine (`catalog.schema`, `project.dataset`) two entries can share a label and differ only by their outer segment, which is what the array form exists for; `schemas` repeats the labels for pre-087 clients and is kept for one release. `truncated: true` means the 2000-entry cap dropped some (on MySQL catalog routing the walk would otherwise span every database the server grants). On MySQL the databases arrive as JDBC catalogs (Connector/J defaults), so the listing reads them from `getCatalogs()` — the same vocabulary `datasources_get_tables` routes through; system schemas/databases (`information_schema`, `mysql`, `performance_schema`, `sys` on MySQL) are excluded on every dialect. **An empty list is a valid result** — a datasource with no namespace dimension (SQLite) has none to list. See [Datasources §7A](datasources.md#7a-schema-introspection). A connection failure against the datasource is the catalogued `pipeline.execution.datasource_unreachable` `isError` envelope — the same rule applies to §6.2.17/§6.2.18.

**Scope:** `author` — introspection opens a live connection against the datasource, matching the `datasources_test` precedent.

#### 6.2.17 `datasources_get_tables`

List a datasource's tables and views.

```json
{
  "name": "datasources_get_tables",
  "description": "List the tables and views of a registered datasource by reading its live JDBC metadata. The listing spans namespaces — pass each table's reported `namespace` array to datasources_get_columns. Read-only, for pipeline authoring.",
  "inputSchema": {
    "type": "object",
    "required": ["name"],
    "properties": {
      "name": {"type": "string", "description": "Datasource name."},
      "namespace": {
        "type": "array",
        "items": {"type": "string"},
        "description": "Optional namespace filter, outermost first, as returned by datasources_get_schemas. An unknown namespace matches nothing."
      },
      "schema": {"type": "string", "description": "Optional single-level filter; accepts the dotted 'catalog.schema' form. Superseded by namespace."}
    }
  }
}
```

Returns: `{"tables": [{"namespace": [...], "schema", "name", "type", "remarks"?}], "truncated": bool}` — `namespace` is the containing path outermost-first and `schema` its last segment (kept for one release so a pre-087 client reads what it always read); `type` is the driver's raw JDBC table type (`TABLE`, `VIEW`, `BASE TABLE`, ...); `remarks` is the engine-stored table comment, omitted when the driver/database has none. The listing is capped at **2000 tables**; `truncated: true` means the cap dropped some. The `namespace` and `schema` filters are exact-match, not LIKE patterns, and a namespace deeper than the dialect's own matches nothing. Without a filter the listing **spans namespaces** — pass each table's reported `namespace` to `datasources_get_columns` (there, no namespace argument means the connection's current one only, and a datasource reporting **none** fails with the catalogued `pipeline.execution.parameter_required` rather than merging same-named tables' columns — the merge hazard lives in `datasources_get_columns` alone; a tables listing carries each row's own namespace and cannot merge, so it deliberately has no such guard and works unfiltered on those datasources too).

**Scope:** `author` — introspection opens a live connection against the datasource, matching the `datasources_test` precedent.

#### 6.2.18 `datasources_get_columns`

List one table's columns with canonical types.

```json
{
  "name": "datasources_get_columns",
  "description": "List one table's columns with canonical types, read from the datasource's live JDBC metadata. Pass the table name exactly as datasources_get_tables returned it, and its `namespace` array with it. Without a namespace only the connection's current one is read; if the datasource reports none, an explicit namespace is required (list them with datasources_get_schemas). On a two-level engine an unqualified read can merge same-named tables from different catalogs, which is why the namespace is worth passing. Read-only, for pipeline authoring.",
  "inputSchema": {
    "type": "object",
    "required": ["name", "table"],
    "properties": {
      "name": {"type": "string", "description": "Datasource name."},
      "table": {"type": "string", "description": "Table name as returned by datasources_get_tables."},
      "namespace": {
        "type": "array",
        "items": {"type": "string"},
        "description": "The table's namespace, outermost first, as datasources_get_tables reported it. An unknown namespace matches nothing."
      },
      "schema": {"type": "string", "description": "Optional single-level filter; accepts the dotted 'catalog.schema' form. Superseded by namespace."}
    }
  }
}
```

Returns: array of `{"name", "type", "precision", "scale", "nullable", "source_type", "warnings", "remarks"}` — `type` is the canonical Type System type, `source_type` the driver's own type name, `warnings` the ingress mapper's warning messages (empty when the mapping was clean), `remarks` the engine-stored column comment (omitted when there is none); `precision`/`scale`/`nullable`/`remarks` are omitted when the metadata does not report them. An unknown table matches nothing and returns an empty list. `table` and `schema` are exact-match identifiers — JDBC metadata name matching is case-sensitive, `_`/`%` are not wildcards; pass the name `datasources_get_tables` returned. System-schema rows are excluded; without a `schema` argument the read defaults to the connection's current schema (routed per dialect, [Datasources §7A](datasources.md#7a-schema-introspection)) so same-named tables in different schemas cannot merge their columns — and a datasource that reports **no current schema** makes that default impossible, so the call fails with the catalogued `pipeline.execution.parameter_required` instead of silently merging (`datasources_get_schemas` lists the schemas to pass; schemaless datasources such as SQLite are the exception — there is nothing to merge).

**Scope:** `author` — introspection opens a live connection against the datasource, matching the `datasources_test` precedent.

#### 6.2.19 `datasources_preview_rows`

Preview up to `limit` rows of one table's data — the counterpart to `datasources_get_columns`.

```json
{
  "name": "datasources_preview_rows",
  "description": "Preview up to `limit` rows of one table's data, read live from the datasource. The counterpart to datasources_get_columns: this shows the DATA, that shows the shape. Without order_by the top-N is engine-arbitrary; pass order_by to see a chosen end of the data, e.g. direction DESC for the newest or largest rows. Read-only (SELECT); readonly datasources are valid targets. Values arrive wire-encoded: BIGINTEGER and BIGDECIMAL as strings, temporal as fixed-width ISO forms.",
  "inputSchema": {
    "type": "object",
    "required": ["name", "table"],
    "properties": {
      "name": {"type": "string", "description": "Datasource name."},
      "table": {"type": "string", "description": "Table name exactly as datasources_get_tables returned it."},
      "schema": {"type": "string", "description": "Optional schema qualifier. Omitted means the connection's current schema."},
      "order_by": {
        "type": "array",
        "description": "Sort terms applied in order. Each is an object with a column and a direction, never a free SQL string.",
        "items": {
          "type": "object",
          "required": ["column"],
          "properties": {
            "column": {"type": "string", "description": "Column name exactly as datasources_get_columns returned it."},
            "direction": {"type": "string", "enum": ["ASC", "DESC"], "default": "ASC"}
          }
        }
      },
      "limit": {"type": "integer", "default": 50, "minimum": 1, "maximum": 50}
    }
  }
}
```

Returns: `{"datasource", "table", "schema"?, "columns": [{"name", "type"}], "rows": [{column: value, ...}], "row_count", "truncated"}` — values wire-encoded per the result-cursor rules (`BIGINTEGER`/`BIGDECIMAL` as strings, temporal fixed-width ISO, `BINARY` base64). The server builds the ENTIRE statement and quotes every identifier with the dialect's quote character (backtick on MySQL, `[...]` on MSSQL, doubled `"` elsewhere) — the agent supplies identifiers only, and a blank identifier is `-32602`. `order_by` entries are `{column, direction}` objects; a free `"col DESC"` string is refused, not parsed. Without `order_by` the top-N is engine-arbitrary. The cap is applied in the dialect's own syntax (`LIMIT`, Oracle `FETCH FIRST n ROWS ONLY`, MSSQL `TOP (n)`) AND as JDBC `maxRows`/`fetchSize`. Readonly datasources are valid targets — the readonly refusal covers write-shaped node uses, and this is a SELECT. `tempdb` can never be a target: a datasource of that name cannot be registered (contract §4.8). A connection failure is the catalogued `pipeline.execution.datasource_unreachable`; a refused statement is the catalogued `pipeline.node.query_execution_failed` carrying the bounded driver message.

**Scope:** `author` — this returns arbitrary customer ROW DATA, not metadata; a read-scoped key does not acquire that reach (037 F).

#### 6.2.20 `pipelines_execute_node`

Runs ONE pipeline node's rendered SQL against its own datasource — a debug query, not an execution.

```json
{
  "name": "pipelines_execute_node",
  "description": "Runs ONE pipeline node's rendered SQL against its own datasource and returns up to 50 decoded rows — a debug query for testing a node in isolation, NOT a pipeline execution. DML and DDL nodes execute FOR REAL against the datasource, leaving no execution history or trace. No ancestors run and no tempdb exists: a node whose source is tempdb is refused. Parameters bind through the pipeline's declarations; unsupplied required parameters fall back to sample values and the response names them in sampled_parameters. Absent version runs the DRAFT if one exists, else the current released version; the response states which version and status ran.",
  "inputSchema": {
    "type": "object",
    "required": ["pipeline_id", "node_id"],
    "properties": {
      "pipeline_id": {"type": "string", "format": "uuid"},
      "node_id": {"type": "string", "description": "Node id within the pipeline body."},
      "version": {"type": "integer", "minimum": 1, "description": "Pipeline version to read. Omitted: the DRAFT if one exists, else the current released version."},
      "parameters": {
        "type": "object",
        "description": "Values for the pipeline's declared parameters, keyed by name. Types follow the declarations (BIGINTEGER and BIGDECIMAL as strings).",
        "additionalProperties": true
      }
    }
  }
}
```

Returns: `{"node_id", "node_type", "datasource", "version", "status", "sql", "sampled_parameters"?, "elapsed_ms"}` plus, for DQL nodes, `{"columns": [{"name", "type"}], "rows": [{column: value, ...}], "row_count", "truncated"}` (capped at 50 rows like `datasources_preview_rows`), or for DML/DDL nodes `{"affected_rows"}` — **DML/DDL execute for real**, with no execution row, no SSE and no idempotency record (ratified, 037 §A). `sql` is the rendered template output in its `:name` form; execution binds those names as statement parameters. `status` is the run version's lifecycle status (`DRAFT`/`RELEASED`), so the agent never infers which body it ran.

Refusals, all before anything runs: an unknown node id → `pipeline.node.not_found` (404 semantics); a node whose `source` is `tempdb` → `pipeline.node.standalone_execution_refused` with `details.reason = tempdb_source` (the staging database exists only inside a full execution — use `pipelines_execute`); a PIPELINE node → the same code with `details.reason = pipeline_node` (it runs a child pipeline, not SQL); a missing pinned template → `pipeline.node.template_not_found`; a render failure → `pipeline.node.template_render_failed`; a supplied parameter failing §6.3 coercion → `pipeline.execution.invalid_parameter_type` naming every failure; a rendered `:name` the pipeline does not declare → `pipeline.node.sql_parameter_missing` (042). A DML/DDL node against a readonly datasource is refused with `pipeline.node.datasource_readonly`; a datasource invisible to the key's workspace resolves as `datasource.not_found`, and an unreachable datasource as `pipeline.execution.datasource_unreachable`; a refused statement is `pipeline.node.query_execution_failed`.

**Scope:** `author` — this runs real SQL and returns arbitrary customer row data; the same 037 F reasoning as `datasources_preview_rows`.

#### 6.2.21 `templates_used_by`

Which pipelines pin a given template version — the reverse arrow of a node's `{id, version}` template pin (040).

```json
{
  "name": "templates_used_by",
  "description": "Which pipelines pin a given template version in their working version (the draft when unreleased edits exist, else the latest released). Returns one reference per node — pipeline name and id, node id, and the pipeline version carrying the pin — plus the distinct pipeline count. Use it before editing or retiring a template version to see who you would affect. It does not answer 'is it safe to delete' (that scan includes historical pipeline versions and lives in the delete refusal), and it never changes anything.",
  "inputSchema": {
    "type": "object",
    "required": ["id", "version"],
    "properties": {
      "id": {"type": "string", "description": "Template id."},
      "version": {"type": "integer", "description": "The pinned version to look for."}
    },
    "additionalProperties": false
  }
}
```

Returns: `{"template": {"id", "version"}, "scan": "working_version", "pipeline_count", "references": [{"pipeline", "pipeline_id", "node_id", "pipeline_version", "pipeline_version_status"}]}` — one row per pinning NODE, so a pipeline with two nodes on the same version appears twice and `pipeline_count` stays the honest distinct count. The scan reads each pipeline's **working version** (draft-if-exists, versioning §7), so a draft that just adopted the pin is already counted — the "who do I notify" answer an author needs before editing a template ([Templates §5.4](templates.md#54-used-by-the-reverse-arrow-v19-040)). `version` is required and never clamped (the [D2 rule](templates.md#54-used-by-the-reverse-arrow-v19-040): the question is per version). The delete-safety question — who pins ANY version, in ANY pipeline version ever — is a different scan and surfaces as the `template.in_use` delete refusal on the REST surface, not here.

An unknown template id is the catalogued `template.not_found`; a known id with no such version is the same code with a `version` detail. **Scope:** `read` (040 D7) — reference structure a workspace reader may already see by reading the pipelines themselves; no customer row data.

#### 6.2.22 (removed) — no datasource writes on this surface

**No credential travels through an agent.** People add datasources in the UI; an operator uses `POST /api/v1/datasources` ([Datasources §3.1](datasources.md#31-json-structure-request--post-apiv1datasources)) or the bootstrap file ([Datasources §8A](datasources.md#8a-bootstrap-registration-config-declared-datasources)); agents use the datasources that already exist, **by name**. Creating, editing and deleting a datasource are UI/REST-only, without exception.

068 shipped a `datasources_create` tool here and wrote the hazard into its own description: a secret sent through a tool call transits the agent's context, its transcript and whatever logging the client does, so "prefer the UI for a real credential" was an instruction to the model. 094 (owner ruling 4) decided that hazard is not documentable away — the tool's own description is not a control — and removed it. The surface went 31 → 30 tools; this section number is kept so §6.2.23 onward do not shift.

What stays is every datasource tool that needs no credential: `datasources_list`, `datasources_get`, `datasources_test`, `datasources_get_schemas`, `datasources_get_tables`, `datasources_get_columns` and `datasources_preview_rows`. An agent can therefore still discover a connection, confirm it works, read its shape and preview its rows — everything authoring a pipeline needs — without one ever being handed a password. The same reasoning is why there is no `api_keys_create`: see the §6.2.23 preamble.

An agent that needs a datasource it cannot find should **ask the person** to add it in the UI, then read it back with `datasources_list`.

#### 6.2.23 `endpoints_create`

Publish a released pipeline as a `GET` endpoint under `/api/x` ([REST API §19](rest-api.md#19-published-endpoints)). Calls the same service `POST /api/v1/endpoints` calls — the same read-only rule, the same ambiguity refusal, the same audit (049's rule: two entry points, one validated path).

```json
{
  "name": "endpoints_create",
  "description": "Publish a released pipeline as a GET endpoint under /api/x. The pipeline must have a RELEASED version and must be side-effect-free: every node DQL into tempdb or the caller, transitively through PIPELINE nodes. A DML/DDL node, or a DQL node writing back to a datasource, is refused with endpoint.pipeline_not_readonly naming the node — that rule is what makes serving over GET safe, since GET is retried, preloaded and crawled. path is 1-10 segments, each a literal [a-z0-9][a-z0-9_.-]{0,63} or a {variable} naming a declared parameter; remaining parameters come from the query string. A path that could match the same URL as an existing one is refused (endpoint.path_conflict) rather than resolved by precedence. Calling the endpoint needs an API key bound to it — mint and bind one over REST or in the UI (auth.md §7.7); an unbound endpoint accepts user keys with the execute scope.",
  "inputSchema": {
    "type": "object",
    "required": [
      "path",
      "pipeline"
    ],
    "additionalProperties": false,
    "properties": {
      "path": {
        "type": "string",
        "description": "e.g. /nyc/revenue/{borough} — no /api/x prefix, no trailing slash."
      },
      "pipeline": {
        "type": "string",
        "description": "The pipeline NAME. It must have a released version."
      },
      "timeout_seconds": {
        "type": "integer",
        "description": "Clamped by datapipelines.endpoints.timeout-min-seconds/max-seconds. On timeout the endpoint answers 202 and the execution keeps running."
      },
      "description": {
        "type": "string"
      }
    }
  }
}
```

Returns the endpoint's wire shape: `path`, `pipeline` (by NAME), `timeout_seconds`, `description`, `enabled`, `path_variables` and the servable `url`.

#### 6.2.24 `endpoints_list`

The published endpoints of the key's pinned workspace.

```json
{
  "name": "endpoints_list",
  "description": "List the published endpoints of the key's workspace: path, pipeline name, timeout, whether it is enabled, and the path variables it binds. A disabled endpoint answers 404 exactly like an unpublished one, so this listing is the only way to see that it exists.",
  "inputSchema": {
    "type": "object",
    "additionalProperties": false,
    "properties": {}
  }
}
```

Returns `{endpoints: [...]}` in the shape §6.2.23 returns.

#### 6.2.25 `endpoints_get`

One published endpoint, by its path PATTERN.

```json
{
  "name": "endpoints_get",
  "description": "One published endpoint by its path (the pattern, not a request URL — '/nyc/revenue/{borough}').",
  "inputSchema": {
    "type": "object",
    "required": [
      "path"
    ],
    "additionalProperties": false,
    "properties": {
      "path": {
        "type": "string",
        "description": "The published path PATTERN, e.g. /nyc/revenue/{borough}."
      }
    }
  }
}
```

Returns the §6.2.23 shape, or `endpoint.not_found`.

#### 6.2.26 `endpoints_delete`

Unpublish an endpoint. The pipeline is untouched; key bindings on that node are not removed, since a node may still carry other endpoints beneath it.

```json
{
  "name": "endpoints_delete",
  "description": "Unpublish an endpoint by its path. The pipeline is untouched — only the URL stops answering. Key bindings on that path are NOT removed: they describe a node of the tree, which may still carry other endpoints beneath it.",
  "inputSchema": {
    "type": "object",
    "required": [
      "path"
    ],
    "additionalProperties": false,
    "properties": {
      "path": {
        "type": "string",
        "description": "The published path PATTERN, e.g. /nyc/revenue/{borough}."
      }
    }
  }
}
```

Returns `{path, deleted: true}`, or `endpoint.not_found`.

**Scope:** `author` — the same floor `datasources_test` sits on: registering a connection opens a real pool against a production database at save time. `global: true` additionally requires admin and is refused with `datasource.validation.workspace_forbidden`, exactly as REST refuses it; admin-ness is a D8 rule, not a scope ([Auth §7.6](auth.md#76-scope--operation-matrix-authoritative)).

**Mutating.** Declared `mutating` in the tool catalog, so every call writes `mcp.tool.called` **and** `mcp.tool.write` at the dispatcher's single audit choke point (§6.3). The audit row carries the datasource NAME and never the credential.

**The password caveat — a documented trade-off, not a bug.** A password sent to this tool transits the agent's context window, the client's transcript, and whatever logging that client does. No server-side change can undo that; refusing the tool would not undo it either, it would only push operators to paste credentials somewhere worse. So the tool states it, in the description an agent reads before calling. Register a real production credential in the UI or over REST; use this tool with a credential the user is willing to have in that transcript — a read-only role, or a short-lived password they will rotate afterwards.

**Suggested next call:** `datasources_test` on the new name. Creation validates and builds a test pool, but the tool does not probe on your behalf.

#### 6.2.23 `calculators_list`

The catalog of calculator kinds a `CALCULATOR` node can evaluate ([Calculators §2](calculators.md), [Pipeline Contract §4.10](pipeline-contract.md#410-json-structure-calculator-node)). The tool an agent calls **before** authoring a calculator node: a `kind` and its input names are the two things it cannot guess, and getting them from a 400 one at a time is a slow way to learn a fixed list.

```json
{
  "name": "calculators_list",
  "description": "The catalog of calculator kinds a CALCULATOR node can evaluate: every kind with its typed inputs (name, type, required, whether it takes a JSON array, and its default when optional), its output type, and one worked example. Call this before authoring a CALCULATOR node — the kind names and input names are not guessable. Also returns the Context keys every pipeline can reference without declaring anything: the deployment's org_* values and the platform keys current_date, current_timestamp and execution_id. Read-only.",
  "inputSchema": {
    "type": "object",
    "properties": {},
    "additionalProperties": false
  }
}
```

**Scope:** `read` — and read in the strongest sense the surface has: the answer is a property of the BUILD, identical for every caller, every key and every workspace. No workspace scoping applies because there is no workspace data in it.

**Response:** `kinds` (each with `kind`, `display_name`, `description`, `inputs`, `output`, `example`), `count`, `context_keys` (`org` names and `platform` name/type pairs), and `docs` pointing at the catalog page. An input carries `list: true` only when it takes a JSON array, and `default` only when it is optional — the absent keys carry the same information as `false`/`null` would, without spending an agent's context window on eighty of them.

#### 6.2.24 `calculators_get`

One kind's full definition — the same entry `calculators_list` returns, for a caller that already knows the name.

```json
{
  "name": "calculators_get",
  "description": "One calculator kind's full definition: display name, description, typed inputs, output type and a worked example. Use it when you know the kind and need its exact input names and types. An unknown kind is refused with the catalogued names in the error detail. Read-only.",
  "inputSchema": {
    "type": "object",
    "required": ["kind"],
    "properties": {
      "kind": {"type": "string", "description": "The kind name, e.g. fiscal_quarter."}
    },
    "additionalProperties": false
  }
}
```

**Scope:** `read`.

**Errors:** an unknown kind is `pipeline.validation.calculator_unknown` with `known_kinds` in the detail — deliberately the SAME code a rejected `pipelines_create` returns for a bad `kind`, so an agent sees one fact about the world rather than two unrelated failures.

#### 6.2.29 `lake_tables_register`

Register one table in a LAKE datasource's catalog — the dp-lake registry ([metadata-db §4.15](metadata-db.md#415-lake_tables), [Datasources §4.1](datasources.md)). A LAKE datasource's tables are exactly the registered rows: the engine cannot list a bucket, so this catalog is what introspection and query resolution read. Mirrors `POST /api/v1/datasources/{name}/tables` ([REST §9.8](rest-api.md#98-lake-tables-the-dp-lake-catalog)) — the SAME application service, so validation, the duplicate refusal and the pool invalidation are identical on both surfaces.

```json
{
  "name": "lake_tables_register",
  "description": "Register one table in a LAKE datasource's catalog (the dp-lake registry). Mirrors POST /api/v1/datasources/{name}/tables: namespace (array of segments or the dotted 'nyc.mobility' shorthand), table, format (parquet | iceberg) and location are required; partition_column is optional. The location is s3://bucket/prefix/ (parquet: a directory or glob; iceberg: the table's CURRENT metadata file, e.g. s3://bucket/table/metadata/00042-<uuid>.metadata.json — DuckDB 1.5.5 cannot scan a pyiceberg table by its root, so register the file, and re-register it when the table commits) or a file:// path — no other scheme, and no quotes, backslashes, whitespace or control characters (it is interpolated into the engine's CREATE VIEW, so the refusal is total). Segments follow the pipeline/template segment grammar without dots. Registering an already-registered (namespace, table) is the 409 datasource.lake_table_duplicate; a non-LAKE datasource is refused. Mutating.",
  "inputSchema": {
    "type": "object",
    "required": [
      "name",
      "namespace",
      "table",
      "format",
      "location"
    ],
    "additionalProperties": false,
    "properties": {
      "name": {
        "type": "string",
        "description": "Datasource name. A LAKE datasource visible in the key's pinned workspace."
      },
      "namespace": {
        "description": "Namespace — a segments array or the dotted shorthand. 1-9 segments, the segment grammar without dots.",
        "anyOf": [
          {
            "type": "array",
            "items": {
              "type": "string"
            }
          },
          {
            "type": "string"
          }
        ]
      },
      "table": {
        "type": "string",
        "description": "The table's name — one segment of the same grammar, e.g. hvfhv_zone_day."
      },
      "format": {
        "type": "string",
        "enum": [
          "parquet",
          "iceberg"
        ]
      },
      "location": {
        "type": "string",
        "description": "s3://bucket/prefix/ (parquet dir/glob; iceberg: the current metadata file, not the table root) or file:// path. Nothing else; no injection chars."
      },
      "partition_column": {
        "type": "string",
        "description": "Optional. The hive-style partition column, e.g. pickup_date."
      }
    }
  }
}
```

**Scope:** `author` — the datasource-mutation floor ([Auth §7.6](auth.md#76-scope--operation-matrix-authoritative)). Mutating a GLOBAL datasource's registry additionally requires admin, a workspaces D8 rule inside the shared service rather than a scope.

**Mutating.** Declared `mutating` in the tool catalog: every call writes `mcp.tool.called` **and** `mcp.tool.write` at the dispatcher's single audit choke point (§6.3).

**Errors:** `datasource.not_found` (unknown or invisible datasource), `datasource.validation.lake_dialect_required` (not a LAKE datasource), `lake_namespace_invalid` / `lake_name_invalid` / `lake_format_invalid` / `lake_location_invalid` (the grammar and the injection refusal), `datasource.lake_table_duplicate` (409 — the triple is taken).

**Response:** the stored row — `namespace`, `name`, `qualified_name`, `format`, `location`, `partition_column`, `registered_at`.

#### 6.2.30 `lake_tables_import`

Bulk-register from a `manifest.json` `tables[]` block (088's sample-data-lake shape), inline or by URL. Mirrors `POST /api/v1/datasources/{name}/tables/import`. A manifest URL is fetched **server-side, and only from the datasource's own bucket/endpoint** — derived from its declared `dialect.endpoint` / `catalog.ref`, or AWS S3 when neither is set; anything else is refused. There is no arbitrary URL fetch (SSRF).

```json
{
  "name": "lake_tables_import",
  "description": "Bulk-register lake tables from a manifest.json tables[] block (the sample-data-lake shape). Mirrors POST /api/v1/datasources/{name}/tables/import: pass EITHER tables (an array of {name, format, location|path, partition_column?, namespace?}, with publish_prefix for relative paths and an optional shared namespace) OR manifest_url. A manifest URL is fetched server-side ONLY from the datasource's own endpoint/bucket — derived from its declared dialect.endpoint / catalog.ref, or AWS S3 when neither is set; anything else is refused with datasource.validation.lake_manifest_url_forbidden (no arbitrary URL fetch — SSRF). Import is idempotent: already-registered tables are reported in already_registered, not errors. Mutating.",
  "inputSchema": {
    "type": "object",
    "required": [
      "name"
    ],
    "additionalProperties": false,
    "properties": {
      "name": {
        "type": "string",
        "description": "Datasource name. A LAKE datasource visible in the key's pinned workspace."
      },
      "tables": {
        "type": "array",
        "description": "Inline form: manifest entries {name, format, location|path, partition_column?, namespace?}.",
        "items": {
          "type": "object"
        }
      },
      "namespace": {
        "description": "Shared namespace applied to entries that carry none — an array of segments or the dotted shorthand.",
        "anyOf": [
          {
            "type": "array",
            "items": {
              "type": "string"
            }
          },
          {
            "type": "string"
          }
        ]
      },
      "publish_prefix": {
        "type": "string",
        "description": "Base URI resolving relative entry paths, e.g. s3://bucket/lake/v1."
      },
      "manifest_url": {
        "type": "string",
        "description": "URL of a manifest.json. Fetched ONLY from the datasource's own endpoint/bucket or AWS S3; else refused."
      }
    }
  }
}
```

**Scope:** `author`. **Mutating** — same audit pair as `lake_tables_register`.

**Errors:** the register set, plus `datasource.validation.lake_manifest_url_forbidden` (a URL outside the datasource's own roots) and `pipeline.execution.datasource_unreachable` (502 — the manifest could not be fetched from the datasource's own storage).

**Response:** `registered` (the stored rows), `registered_count`, `already_registered` (dotted qualified names skipped as already present — import is idempotent, so bootstrap can re-run it), `already_registered_count`.

#### 6.2.31 `lake_tables_unregister`

Unregister one table. The objects in the bucket are untouched — the table stops being served by the datasource. Mirrors `DELETE /api/v1/datasources/{name}/tables/{namespace}/{table}`.

```json
{
  "name": "lake_tables_unregister",
  "description": "Unregister one table from a LAKE datasource's catalog. Mirrors DELETE /api/v1/datasources/{name}/tables/{namespace}/{table}: the objects in the bucket are untouched — the table stops being served by the datasource. Unregistering a table that is not registered is the 404 datasource.lake_table_not_found, never a silent no-op. Mutating.",
  "inputSchema": {
    "type": "object",
    "required": [
      "name",
      "namespace",
      "table"
    ],
    "additionalProperties": false,
    "properties": {
      "name": {
        "type": "string",
        "description": "Datasource name. A LAKE datasource visible in the key's pinned workspace."
      },
      "namespace": {
        "description": "The table's namespace, outermost first — an array of segments or the dotted shorthand ('nyc.mobility').",
        "anyOf": [
          {
            "type": "array",
            "items": {
              "type": "string"
            }
          },
          {
            "type": "string"
          }
        ]
      },
      "table": {
        "type": "string",
        "description": "The table to unregister."
      }
    }
  }
}
```

**Scope:** `author`. **Mutating** — same audit pair as `lake_tables_register`.

**Errors:** `datasource.not_found`, `datasource.validation.lake_dialect_required`, the grammar codes for a malformed `namespace`/`table`, and `datasource.lake_table_not_found` (404 — an absent triple, never a silent no-op).

**Response:** `{datasource, table, deleted: true}` with `table` the dotted qualified name.

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

**Execution failures carry the full record (057).** When the error is an execution failure — a `pipelines_execute` whose pipeline failed — the `error` object additionally carries `node`, `sql` and `exception`: the §6.2.14 failure record, so the agent that just ran the pipeline sees the root cause without a second call. Every other error keeps exactly the shape above.

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
datapipelines://docs/skill                                     → the agent skill's operating core (Markdown)
datapipelines://docs/skill/{reference}                         → one reference file of the skill
```

The `docs` kind is not an entity: it is the manual the server ships (§7.2.4). Every other
form addresses stored content and is workspace-scoped; `docs/*` is the same bytes for every
caller on a given build.

### 7.2 Resource examples

#### 7.2.1 `datapipelines://pipelines/{id}`

Returns the pipeline JSON body, content-type `application/json`.

#### 7.2.2 `datapipelines://templates/{id}/versions/{version}`

Returns the template body (Freemarker SQL), content-type `text/x-freemarker-sql`.

**`{id}` contains slashes.** A template id is a folder path ([Template Hierarchy §4.1](template-hierarchy-design.md#41-grammar)) and, since 077, always at least two segments — so the id is **every segment after `templates`**, not one. `datapipelines://templates/nyc/mobility/daily_by_zone.sql` is the latest version of `nyc/mobility/daily_by_zone.sql`. The `versions/{version}` suffix is recognised by the LAST two segments, never by position, so a folder named `versions` stays a folder: `…/templates/acme/versions/report.sql` is the template `acme/versions/report.sql`, and `…/templates/acme/versions/report.sql/versions/2` is its version 2. (Corrected in 077: the parser had required exactly two segments since 043, so every hierarchical id read as not-found.)

#### 7.2.3 `datapipelines://datasources/{name}`

Returns datasource metadata as JSON, with the password field redacted. Workspace-scoped like every datasource read (§2 principle 6): a name bound to another workspace resolves as not-found; `datapipelines://datasources` lists exactly the pinned workspace's visible set (bound + global).

#### 7.2.4 `datapipelines://docs/skill`

Returns the agent skill's `SKILL.md`, content-type `text/markdown` — the same bytes the
deployment serves at `GET /skill.md` and the same file the repository holds at
`.agents/skills/datapipelines/SKILL.md` (§15). `datapipelines://docs/skill/{reference}`
returns one file of `references/` by name, with or without the `.md` suffix
(`…/skill/templates` and `…/skill/templates.md` are the same resource); an unknown name is
`RESOURCE_NOT_FOUND` like any other unknown URI. `{reference}` is a NAME, never a path — it
is looked up in a map of packaged files, so no caller string reaches a file system.

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
- Enumeration order is stable within a paging run (docs, then pipelines, then templates, then datasources, then executions; each by id).  The `docs` rows lead because they are the only constant-size kind — the skill plus one row per reference, identical on every server — so they cannot push an entity off a page, and an agent that lists resources at all meets the manual before it meets content. Entities created mid-run may be missed — `resources/list` is a discovery aid, not a consistent snapshot.

**Scope filtering:** the listing is filtered to what the calling key may read (`read` scope; ownership rules apply to executions) **and to the key's pinned workspace** (workspaces design §5.2/§5.3: its pipelines/templates/executions, its bound datasources plus global ones), so two agents see different resource sets on the same server.

**Execution resources are windowed:** only executions from the **last 24 hours** are enumerated. Older executions remain readable by direct URI (`datapipelines://executions/{id}`) as long as their metadata exists in the Metadata DB — they are simply not listed, because an unbounded execution history would make `resources/list` useless (and enormous) on any busy instance. Result rows are governed by the much shorter result TTL regardless (§6.2.15).

### 7.4 No subscriptions in v1

We do not support `resources/subscribe` in v1. Resources change rarely enough that re-fetch on demand is sufficient. Subscription support is a v2 candidate (would let agents react to new pipeline versions, etc.).

---

## 8. Prompt Surface

Predefined prompts the agent can invoke via `prompts/get`. Useful for steering agents toward common workflows.

**Admission rule:** a prompt ships only if every step it instructs the agent to take is achievable with the 30 tools in §6.1 and the resources in §7. A prompt that depends on a tool we have not built is a scripted failure — it reads as a supported capability and dead-ends the agent partway through. All three prompts meet the bar (§8.1, §8.2, §8.3); §8.2 returned in v1.1 together with the introspection tools it depends on.

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
- HTTP 429 (`rate_limit.unavailable`) → the limiter could not decide and refused the call (fail closed, [REST API §12.3](rest-api.md#123-when-the-limiter-itself-is-unavailable)). Not your budget: honor `Retry-After` and retry, and do not treat it as a signal to reduce your request rate permanently.
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
- **Datasource CREATE, UPDATE and DELETE tools**: deliberate omission, not an oversight, and since 094 a standing rule rather than a case-by-case judgement — **no credential travels through an agent** ([§6.2.22](#6222-removed--no-datasource-writes-on-this-surface)). Registration briefly existed as `datasources_create` (068) and was removed; editing and deleting an established datasource are operator actions with a blast radius across every pipeline that references it. All three stay UI/REST-only (§4.1).
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
- [ ] Every call to a catalog-declared **mutating** tool writes exactly one `mcp.tool.write` audit event — a node run that altered customer data must never be untraceable (§14).

---

## 14. Audit

> **Status:** normative (052, ruling R4 on T65). The same `audit_log` sink the [`auth.*` events](auth.md#101-events) use — no separate table, no execution rows, no UI history.

**Two events, both emitted at `McpToolDispatcher`** — the single dispatch choke point, so no tool can forget its own trace:

| Event | When | 
|---|---|
| `mcp.tool.called` | Every tool call, every outcome (success, domain error, invalid params, internal error, scope refusal) |
| `mcp.tool.write` | Exactly one per call to a tool the catalog declares **mutating**, emitted after the tool returns — on success and on failure alike. A §7.6 scope refusal never invoked the tool, so it writes no write event: the refusal is recorded by `mcp.tool.called` alone |

**Fields** (identical for both events): actor — `user_id` (the key's owner) and `key_id`; `tool`; `target` — the identifier-shaped argument only (execution/pipeline/template id or name); for version-aware tools the `version` the call named; for node runs the `node_id`; `outcome` (`success` \| `error` + `code` \| `invalid_params` \| `internal_error` \| `scope_refused`); `elapsed_ms`; `correlation_id`.

**Which tools are mutating is a declared property of the catalog entry** (`McpToolCatalog.Entry.mutating`), never a name pattern — `McpToolCatalogBindingTest` fails if a catalogued tool lacks the declaration or a known writer (`pipelines_create`, `pipelines_update`, `pipelines_execute`, `pipelines_execute_node`, `templates_create`) is flagged read. The failure direction is asymmetric: a read tool declared mutating is a harmless over-audit; a mutating tool declared read is the hole.

**Node runs are covered** (the point of 052): `pipelines_execute_node` runs real DML/DDL with no execution row, no SSE, no idempotency record — §6.2.20's ratification covers "no execution history", not "no trace". The `mcp.tool.write` row naming pipeline, node and version IS the trace that the write happened and by whom.

**Deliberately NOT recorded:** SQL text, row data, parameter values. Those are customer data; the event records THAT a write happened and by whom, never what it contained. Only identifier-shaped arguments are read from the request — the `parameters` map is never touched.

**Failure discipline:** emission happens after the tool returns and cannot change the tool's result or its error; an audit-sink failure is logged server-side (WARN + correlation id) and swallowed — the customer's call does not fail because bookkeeping did.

The event names are registered in [Enums §15](enums.md#15-authauditevent--auth-audit-log-events); the shared sink and its shape are [Auth §10](auth.md#10-audit-log).

---

## 15. The skill: how an agent learns this server

> **Status:** normative (095). One source, four deliveries.

An agent connecting from OUTSIDE a checkout used to get the tool descriptions, the prompts and
eight lines of workspace context — and not one word about how to author. The skill it needed
existed only as a file in the repository. It is now a shipped artifact of the server.

**The source.** `.agents/skills/datapipelines/` — `SKILL.md` (the operating core: core
concepts, the naming grammar and `confirm_new_root`, the golden path, execution semantics,
promotion, error handling, best practices, and a map of the references) plus `references/*.md`,
which an agent opens only when it needs them. `.claude/skills/datapipelines` is a symlink to
that directory. There is exactly one source; everything below is derived from it and
drift-tested against it.

**Delivery 1 — the handshake (push).** `initialize`'s `instructions` (§5.1) is the operating
core distilled: the introspection-first flow, the name grammar and `confirm_new_root`, "agents
describe datasources, humans register them", the three recoveries an agent gets wrong most
often, the draft rule, and a closing pointer to the resource below. It lives in
`modules/mcp-server/src/main/resources/mcp/server-instructions.txt` — a file, so the diff is
readable and the bytes are assertable — and is capped at **4096 bytes**, test-enforced: every
client injects it into every session, so a line that does not change what an agent DOES on its
first five calls belongs in the skill instead.

**Delivery 2 — the resource (pull, MCP).** `datapipelines://docs/skill` and
`datapipelines://docs/skill/{reference}` (§7.1, §7.2.4), `read` scope, listed by
`resources/list` ahead of the entity kinds.

**Delivery 3 — the URL (pull, HTTP).** `GET /skill.md` and `GET /skill/{reference}.md`,
`text/markdown`, **unauthenticated** — it is the manual, it holds no secret, and requiring a
key would mean an agent cannot learn to use its key correctly until after it has one. This is
the delivery for Cursor, Codex CLI, Copilot and anything else that speaks no MCP: one `curl`
into `.agents/skills/datapipelines/` and the agent has the manual for the version this
deployment actually runs. An unknown reference is a `404` in the §4.2 envelope with
`details.reason: "skill_reference_not_found"`.

*The rendered `/docs` viewer does NOT list the skill, deliberately.* That index is the
operator-facing spec set, grouped by `DocsCatalog` and link-rewritten to GitHub for anything it
does not package; the skill is agent-facing, carries YAML front matter that is meaningless as
HTML, and its reference map would render as dead links. The raw route is the one an agent
needs, and it is the one that exists.

**Delivery 4 — the Claude Code plugin.** `.claude-plugin/marketplace.json` at the repository
root and `plugins/datapipelines/`: `/plugin marketplace add msabiransari/datapipelines` then
`/plugin install datapipelines@datapipelines`. The plugin ships the skill and the MCP server
entry together; its `skills/datapipelines/` is a build-time COPY, not a symlink, because a
marketplace is fetched with git and Claude Code skips a symlink pointing out of the plugin
directory. The deployment URL and the API key are plugin `userConfig` values substituted into
the server entry — `${user_config.url}` / `${user_config.api_key}` — because a plugin
`.mcp.json` expands only those and the `${CLAUDE_PLUGIN_*}` path variables, never arbitrary
shell environment variables.

**Packaging (why it cannot break `main`).** `mcp-server`'s `processResources` packages the
skill directory into the jar under its own classpath root, `skill/` — never under `docs/`,
which `web`'s `DocsCatalog` scans and where an ungrouped file fails the application context at
init. `SkillDocs` is the single reader of those bytes, so the resource and the URL cannot
answer differently.

**The generated reference.** `references/tools.md` is rendered from `McpToolCatalog` plus each
tool's `definition` — name, description, arguments with their descriptions, §7.6 scope,
mutating flag — by `./gradlew :modules:mcp-server:skillArtifacts`, and a drift test fails when
the committed file is not what the catalog renders. The handwritten sections may NAME tools;
they may not list them. This is not a hypothetical: three hand-typed tool counts were stale
simultaneously when this was written.

**The guards**, each able to go red: `SKILL.md` ≤ 400 lines and its front matter unchanged;
`instructions` ≤ 4096 bytes and naming the resource URI; the packaged copy byte-identical to
the repo file for every file, both directions; the plugin copy likewise; `tools.md` equal to
the rendered catalog; the two resource URIs read, list and 404 correctly; `GET /skill.md`
200 `text/markdown` anonymous and `GET /skill/nope.md` 404 in the envelope.

---

## Appendix A: Change Log

| Date | Version | Author | Change |
|---|---|---|---|
| 2026-09-08 | v1.27 | T199 LAKE in the MCP enum | No new tools. §6.2.6 and §6.2.8 `dialect` enums gain **`LAKE`** — the server accepted it since 087/089, the ADVERTISED schema still listed seven values, so clients refused every LAKE template before the server saw it (an agent blamed its own typing and bypassed MCP over REST). The enum is now derived from `Dialect.entries`; `DialectEnumSchemaTest` and the §6.2 drift test pin it. |
| 2026-09-08 | v1.26 | 099 draft-first (D55/D56) | **Additive: response VALUES and descriptions, no new tool and no new argument.** §6.2.4 `pipelines_create` lands version 1 as a **DRAFT** — the response carries `status: "DRAFT"`, `current_version: null` and the `draft` pointer, and the description tells an agent to run it and then STOP for a human to release (D4 without exception). §6.2.8 `templates_create` mirrors it. §6.2.5 `pipelines_execute` documents its default in the `version` property: with none given it runs the **WORKING** version — the draft when one exists, else the latest release (D56) — never clamped for an explicit one. §6.2.1 `pipelines_list` rows now state the working `version` and a new `status` (`DRAFT`/`RELEASED`), because a listing that reported the released pointer alone would show nothing for every freshly authored pipeline. The `datapipelines://pipelines/{id}` resource (no `/versions/{n}`) serves the working version too, so reading a body and running it cannot disagree. Tool count unchanged at **30**. |
| 2026-09-04 | v1.19 | 072 calculators | Tool surface 22 → **24**: new §6.2.23 `calculators_list` and §6.2.24 `calculators_get` (both scope `read`, non-mutating), projecting the `CalculatorRegistry` catalog — typed inputs, output type and a worked example per kind, plus the org and platform Context keys a body may reference without declaring anything. `pipelines_create` / `pipelines_update` need no new arguments (a CALCULATOR node is part of the body) but their descriptions now name the type and point at `calculators_list`. `executions_get` needed no change and gained two things anyway: `parameters` is now the fully resolved Context after the run (org keys, platform keys, parameters, calculator outputs — [DAG Executor §7.3](dag-executor.md#73-the-context-snapshot--pipeline_executionsparameters_json)) and each CALCULATOR node's `node_stats` entry carries `context_key`/`context_value`. §5.1's static-surface count and §8's admission rule updated. |
| 2026-09-04 | v1.18 | 068 datasources_create | Tool surface 21 → **22**: new §6.2.22 `datasources_create` (scope `author`, **mutating**), which calls the same `DatasourceCreateService` `POST /api/v1/datasources` does — one payload binder, one set of workspaces D8 rules, one duplicate-name refusal. `global: true` still requires admin, refused with `datasource.validation.workspace_forbidden`. The result is the datasources §3.2 shape with `password_set: true` and no password at any depth. The tool's description carries the accepted trade-off: a password passed through an agent transits its context, transcript and client logging — prefer the UI or REST for a real credential. §4.1, §5.1, §6.1 and §8's admission-rule counts updated, and §14's "no datasource management tools" omission narrowed to update/delete. |
| 2026-09-02 | v1.17 | 040 template used-by | Tool surface 20 → **21**: new §6.2.21 `templates_used_by` (which pipelines pin a template version in their working version — one reference per node with the carrying pipeline version; scope `read`, 040 D7). §6.2.2 `pipelines_get` gains `upgrade_available` (omit-when-empty; node/template/pinned/latest-released rows; surfaced, never applied). §6.1, §5.1 and §8 admission-rule counts updated. |
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
| 2026-08-15 | v1.10 | hardening round 3 (005 review fix-cycle) | §6.2.16: `datasources_get_schemas` returns a page `{\"schemas\": [...], \"truncated\": bool}` capped at 2000 (was a bare array). §6.2.17/§6.2.18: without a `schema` argument, a datasource reporting no current schema (database-less MySQL URL) fails with the catalogued `pipeline.execution.parameter_required` (recovered via `datasources_get_schemas`) instead of a merged/spanning answer — descriptions and Returns updated; blank remarks are omitted, never `\"\"`. Input schemas unchanged (output-shape and error-behavior changes only). |
| 2026-08-16 | v1.11 | hardening round 4 (007 review fix-cycle) | §6.2.11: `datasources_get` (and `datasources_list`, which shares the projection) now returns `introspection_include_schemas` when the allowlist is non-empty — omitted when empty, the same envelope as REST §3.2 — so an agent debugging schema visibility can see an allowlist is active. Output-shape change only; inputSchema untouched. |
| 2026-08-16 | v1.12 | hardening round 4 (007 review fix-cycle) | §6.2.17: `datasources_get_tables` no longer fails on a datasource reporting no current schema — the parameter_required guard is scoped to `datasources_get_columns` (6.2.18), the only operation with a merge hazard; tool description updated (inputSchema unchanged). |
| 2026-08-17 | v1.13 | pipeline composition | §6.2.4/§6.2.5: the `nodes` inputSchema description now covers the PIPELINE node type (pipeline ref pinning, parameter literals and `${parent_param}` references, output legality). Runtime behavior is unchanged — composition executes through the internal execution service, not a new tool. |
| 2026-08-28 | v1.14 | workspaces surfaces slice | §2 principle 6 + §5.1 `instructions`: the workspace context statement (key-pinned scope; other workspaces absent, not hidden). §6.2.10/§6.2.11 descriptions + Returns gain `workspace`/`readonly`; datasource listings/by-name reads are workspace-scoped (bound + global — the REST §9.2/§9.3 predicate). §7.2.3/§7.3: the same scoping for datasource resources and `resources/list`. No new tools, no inputSchema changes. |
| 2026-09-01 | v1.15 | agent data visibility (037) | Tool surface 18 → **20**: new §6.2.19 `datasources_preview_rows` (≤50 wire-encoded rows of one table, `order_by` as `{column, direction}` objects, service-built + dialect-quoted statements, readonly datasources valid) and §6.2.20 `pipelines_execute_node` (ONE node's rendered SQL on its own datasource — a debug query, not an execution: no history/SSE/idempotency, DML/DDL for real, tempdb-source and PIPELINE nodes refused with `pipeline.node.standalone_execution_refused`, unknown node `pipeline.node.not_found`, E5 draft-if-exists version default with status always stated). §6.1, §5.1, §8 admission-rule counts updated. Both `author`: the first tools returning arbitrary customer row data (037 F). |
| 2026-09-02 | v1.16 | MCP audit (052) | New **§14 Audit** (normative, ruling R4): `mcp.tool.called` (every call, since the original build) registered + `mcp.tool.write` (NEW — exactly one per catalog-declared mutating call, node runs included, after the tool returns on success and failure; scope refusals excluded because the tool never ran). Emitted at the dispatcher, not per-tool. Mutating is a declared catalog-entry property guarded by `McpToolCatalogBindingTest`. Never SQL/row data/parameter values. §13 gains the mutating-call checklist line. Both events registered in Enums §15 the same commit (docs-audit check C). No tool surface change. |
| 2026-09-05 | v1.18 | pipeline folders (067) | Additive arguments only — **no new tool names**, the surface stays 21. `pipelines_list` and `templates_list` each gain **`prefix`**: absent = the flat listing (unchanged); present (`""` = the root) = ONE level of the folder tree, returning `{prefix, folders[{path, segment, *_count}], pipelines|templates[], total, has_more}`. `q`/`owner`/`datasource` are ignored while browsing; an illegal prefix answers an empty level, not an error. This closes a real gap for templates, which have had path ids since 043 and no way to browse a folder over MCP (verified 2026-09-04). `pipelines_create`/`pipelines_update` `name` patterns widen to the path grammar — rendered from `PipelineNameGrammar.pattern` itself, so the schema and the server rule cannot drift — with a description telling the agent to list the roots first and ask before minting one. §6.2.1 and §6.2.6 document browse-vs-search. |
| 2026-09-05 | v1.19 | published endpoints (074) | Tool surface 24 → **28**: `endpoints_create` / `endpoints_list` / `endpoints_get` / `endpoints_delete` (§6.2) — publish a released, side-effect-free pipeline as `GET /api/x/…` and bind endpoint-kind keys to it. `create`/`delete` are `author`, the reads `read` (auth.md §7.6). **An endpoint-kind key cannot reach `/mcp` at all** (refused at `McpAuthFilter`; `/mcp` is a servlet outside `ScopeInterceptor`'s reach — security pass). No `api_keys_create` tool: a credential must not transit an agent's transcript. |
| 2026-09-05 | v1.20 | mandatory folders (077) | No new tools; two `pattern`s and two descriptions. §6.2.4/§6.2.5 `pipelines_create`/`pipelines_update` `name` narrows to the 2–10-segment grammar ([Template Hierarchy §4.1](template-hierarchy-design.md#41-grammar)) — rendered from `PipelineNameGrammar.pattern` itself, so it moved with the rule. §6.2.8 `templates_create` `id` **gains a `pattern` for the first time** and it is `TemplateNameGrammar.pattern`: the schema had been advertising the pre-043 flat `[a-z0-9_.-]+` in prose, three grammar changes stale (audit T129, 2026-09-05). Both descriptions now state that a folder is required, that `details.reason='folder_required'` is how the refusal is recognised, and that experiments go under `test/`. An omitted `templates_create` id is generated under `test/`. |
| 2026-09-07 | v1.21 | 087 connector seams | No new tools. §6.2.22 `datasources_create` gains the `credential` object ([Datasources §3.4](datasources.md#34-credential-kinds)) and drops `username`/`password` from `required` — either shape is accepted, both together are refused; the dialect enum gains `LAKE`; the result carries `credential.kind` and a derived `password_set`. §6.2.16 `datasources_get_schemas` returns `entries: [{namespace, label}]` beside the legacy `schemas` array — two catalogs' same-named schemas are two entries, which a list of bare labels could not express. §6.2.17/§6.2.18 gain a `namespace` array argument beside `schema` (which now also accepts the dotted form), and every table row carries `namespace` beside `schema`. |
| 2026-09-07 | v1.20 | dp-lake registry (089 §A) | Tool surface 28 → **31**: `lake_tables_register` / `lake_tables_import` / `lake_tables_unregister` (§6.2.29–31) — the dp-lake catalog (metadata-db §4.15): register one table, bulk-import a manifest.json `tables[]` block (inline or fetched server-side from the datasource's OWN endpoint/bucket only — the SSRF boundary), unregister. All three are `author` (auth.md §7.6) and declared `mutating`. |
| 2026-09-08 | v1.22 | 089 Iceberg location correction | No surface change — two DESCRIPTION strings corrected to the measured rule (datasources.md §8C.7): §6.2.29's tool description and its `location` property now say an Iceberg table's location is the current metadata FILE (`…/metadata/00042-<uuid>.metadata.json`), not the table root — DuckDB 1.5.5 cannot scan a pyiceberg table by its root, so an agent following the old text registered a location whose view fails at connect. The code strings and the fence moved together (the §6.2 fences are drift-pinned to the shipped schemas). |
| 2026-09-08 | v1.23 | 094 the agent boundary | Tool surface 31 → **30**: `datasources_create` REMOVED. §6.2.22 becomes the policy it was an exception to — **no credential travels through an agent**; the section number is kept so §6.2.23 onward do not shift. 068 accepted the hazard with a warning in the tool's own description; a description is not a control. Datasource create, update and delete are UI/REST-only; every read and probe tool is unchanged. §4.1's scope-enforcement paragraph, §5.1's `listChanged` count, §6.1's list, §6.2.10's scope note, §8's admission-rule count and §14's omission entry all follow. |
| 2026-09-08 | v1.24 | 094 new-root confirmation | §6.2.4 `pipelines_create` and §6.2.8 `templates_create` gain **`confirm_new_root`** (boolean) and REFUSE a name whose root segment has nothing under it yet — `pipeline.validation.new_root_requires_confirmation` / `template.validation.new_root_requires_confirmation` ([Pipeline Contract §13](pipeline-contract.md#13-error-code-catalog)), with `details.root` and `details.existing_roots` from the same one-level query `pipelines_list`/`templates_list` `{prefix: ""}` serve. `test/` is exempt; an omitted `templates_create` id (generated under `test/`) is exempt. **Agent surface only**: REST, the UI and `pipelines_update` are unchanged, and no tool other than these two accepts the argument. 067/077 had already told an agent to list the roots and ask; this is the same sentence as a guarantee. No tool-count change. |
| 2026-09-08 | v1.25 | 095 skill distribution | **Additive.** New **§15 The skill: how an agent learns this server** — one source (`.agents/skills/datapipelines/`), four deliveries: the `initialize` handshake (§5.1, now the operating core distilled, capped at 4096 bytes and living in a resource file), the MCP resource, the unauthenticated `GET /skill.md` route, and the Claude Code plugin. §7.1 gains two URI forms — `datapipelines://docs/skill` and `…/skill/{reference}` (§7.2.4) — read-scope Markdown served from the packaged copy; §7.3's enumeration order gains `docs` at the FRONT (the only constant-size kind). No tool surface change: the count stays **30**, and `references/tools.md` is now RENDERED from `McpToolCatalog` rather than typed, drift-tested against the committed file. |
