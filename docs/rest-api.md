# REST API + SSE Specification

**Status:** v1.4 (frozen contract — additive-only changes after this point)
**Owner:** datapipelines.co core
**Depends on:** [Type System spec](type-system.md), [Pipeline Contract spec](pipeline-contract.md), [Auth spec](auth.md)
**Last updated:** 2026-08-11

---

## 1. Purpose

This spec defines the **HTTP surface** of datapipelines.co: every REST endpoint, request/response shape, error format, the SSE event stream for pipeline execution, and the uniform Redis-backed result-delivery cursor.

It is the contract for:
- Browser-based UI (the pipeline editor, dashboard, execution views)
- Direct API clients (.NET services, Python scripts, etc.)
- The MCP server (which is a thin adapter over these endpoints — see [MCP spec](mcp-server.md))

---

## 2. Design Principles

1. **JSON-first.** Every request and response is JSON unless explicitly otherwise (binary upload, SSE stream, Arrow/CSV result pages).
2. **Envelope consistency.** Every success response uses the same envelope shape. Every error response uses the same error envelope.
3. **SSE for execution, REST for everything else.** Pipeline execution is the only long-running, event-emitting operation. It uses Server-Sent Events. All other endpoints are synchronous request-response.
4. **One result path.** Every completed execution's caller result is materialized in Redis and read through one cursor endpoint (§7). `data_ready` carries the first page inline, so small results still cost a single round-trip. There is no inline-vs-claim-check split.
5. **Idempotency where it matters.** Pipeline execution supports idempotency keys (retries don't re-execute) — see §3.5. Write operations on pipelines and templates do not (each write creates a new version).
6. **Pagination everywhere.** List endpoints paginate. Result data pages through the result cursor (§7).
7. **HTTP status codes used correctly.** 2xx success, 4xx client error, 5xx server error. No overloading.

---

## 3. Common Conventions

### 3.1 Base URL

```
https://{host}/api/v1
```

`{host}` is deployment-specific. Self-hosted deployments expose whatever they configure.

`/api/v1` is the API root. Version is in the path (not header) for simplicity and cacheability.

### 3.2 Authentication

Every `/api/v1/**` endpoint requires authentication via one of:

- **Session cookie** (`dp_session`) — for browser-based UI flows. Set by the OIDC login flow (`GET /oauth2/authorization/{provider}` → callback), not by any REST endpoint. There are no `/auth/login` or `/auth/refresh` endpoints — see [Auth §5](auth.md#5-oidc-login-flow).
- **API key** — for programmatic clients. Sent in header: `DP-API-Key: dpk_...`.

Required scopes per operation are defined once in the [Auth §7.6 scope matrix](auth.md#76-scope--operation-matrix-authoritative). API keys are issued per-user-per-agent from the UI (management endpoints in §16).

### 3.3 Content negotiation

- Default: `application/json`.
- SSE endpoints: `text/event-stream`.
- Binary upload (templates): `multipart/form-data` or `application/octet-stream`.
- Claim-check download: `application/json` (default) or `application/vnd.apache.arrow.ipc` (via `Accept`).

### 3.4 Correlation

Every request may include the `DP-Correlation-Id` header. The server echoes it in the response and includes it in logs. If absent, the server generates one and returns it in the response header. Adoption is conditional on shape: an inbound value that is not a well-formed UUID is replaced with a generated id, because the header is attacker-controlled text that is persisted (UUID column) and echoed on every response (added v1.4).

### 3.5 Idempotency

**Only `POST /pipelines/{id}/execute` supports idempotency** via the `Idempotency-Key` header (a de-facto standard header — deliberately not `DP-`-prefixed). The server caches the response reference for that key + request-hash for `datapipelines.idempotency.ttl-seconds` (default 24h); a retried request with the same key returns the original execution instead of re-executing. Same key + different body → `409 idempotency.key_reused_for_different_request`.

CRUD writes do NOT accept idempotency keys in v1 — each write deliberately creates a new version, and version history makes accidental duplicates visible and removable.

### 3.6 Custom header registry

All datapipelines custom headers use the `DP-` prefix:

| Header | Direction | Purpose |
|---|---|---|
| `DP-API-Key` | request | API-key authentication (§3.2) |
| `DP-Correlation-Id` | both | Log/trace correlation (§3.4) |
| `DP-CSRF-Token` | request | CSRF token for cookie-authenticated state-changing requests ([Auth §8.4](auth.md#84-api-endpoints-auth-via-api-key-or-jwt)) |
| `DP-Result-TTL-Seconds` | request | Client-requested result TTL, clamped by the server (§7.4) |

Standard headers used as-is: `Idempotency-Key`, `Retry-After`, `RateLimit-*` (§12), `Authorization` (Bearer on `/mcp` only — [Auth §8.5](auth.md#85-mcp-endpoint-mcp)).

---

## 4. Response Envelopes

### 4.1 Success envelope

Every success response (except SSE and raw binary) uses this shape:

```json
{
  "schema_version": 1,
  "correlation_id": "uuid",
  "data": { ... }
}
```

- `schema_version` — response envelope version. Currently `1`.
- `correlation_id` — for log/trace correlation.
- `data` — the operation-specific payload.

### 4.2 Error envelope

Every 4xx and 5xx response uses this shape:

```json
{
  "schema_version": 1,
  "correlation_id": "uuid",
  "error": {
    "code": "pipeline.validation.cycle_detected",
    "message": "Pipeline dependency graph contains a cycle: fetch_orders → revenue → fetch_orders.",
    "user_message": "Your pipeline has a circular dependency. Remove one of the arrows.",
    "details": {
      "cycle_path": ["fetch_orders", "revenue", "fetch_orders"]
    },
    "doc_url": "https://docs.datapipelines.co/errors/pipeline-validation-cycle-detected"
  }
}
```

- `code` — error code from the [Pipeline Contract §13 catalog](pipeline-contract.md#13-error-code-catalog). Always lowercase, dot-separated.
- `message` — technical message for developers. English. Includes specifics.
- `user_message` — non-technical message safe to display to end users. May be localized in future.
- `details` — structured, code-specific. Each error code documents its `details` shape.
- `doc_url` — link to the public docs page for this error code.

### 4.3 Pagination envelope

List endpoints return:

```json
{
  "schema_version": 1,
  "correlation_id": "uuid",
  "data": {
    "items": [...],
    "pagination": {
      "offset": 0,
      "limit": 50,
      "total": 237,
      "has_more": true
    }
  }
}
```

Query parameters: `?offset=0&limit=50`. Max `limit` is 200 (configurable).

---

## 5. Pipeline Endpoints

### 5.1 Create pipeline

```
POST /pipelines
Content-Type: application/json

{
  "schema_version": 1,
  "name": "monthly_revenue",
  "display_name": "Monthly Revenue",
  "description": "...",
  "parameters": {...},
  "settings": {
    "tempdb": {"engine": "H2"}
  },
  "nodes": [
    {
      "id": "fetch_orders",
      "type": "DQL",
      "source": "pg-prod",
      "template": {"id": "fetch_orders.sql", "version": 2},
      "output": {"target": "tempdb", "table": "stg_orders"},
      "depends_on": []
    },
    ...
    {
      "id": "final_report",
      "type": "DQL",
      "source": "tempdb",
      "template": {"id": "final_report.sql", "version": 1},
      "output": {"target": "caller"},
      "depends_on": ["revenue_by_customer"]
    }
  ]
}
```

Note: no `terminal_node_id` field. The result node is the (at most one) node resolving to `output.target: "caller"` — explicitly or by omitting `output`. See [Pipeline Contract §9](pipeline-contract.md#9-the-caller-node-result-node).

Response: `201 Created`

```json
{
  "schema_version": 1,
  "correlation_id": "uuid",
  "data": {
    "id": "pipeline-uuid",
    "version": 1,
    "name": "monthly_revenue",
    ...
  }
}
```

Server assigns: `id`, `version` (starts at `1`), `owner` (from auth), `created_at`, `updated_at`.

### 5.2 Get pipeline (latest version)

```
GET /pipelines/{id}
```

Response: `200 OK` with full pipeline JSON (including server-assigned fields).

### 5.3 Get pipeline (specific version)

```
GET /pipelines/{id}/versions/{version}
```

### 5.4 List pipeline versions

```
GET /pipelines/{id}/versions
```

Returns metadata only (no body JSON) for each version.

### 5.5 Update pipeline (creates new version)

```
PUT /pipelines/{id}
Content-Type: application/json

{full pipeline body, excluding server-assigned fields}
```

Response: `200 OK` with the new version (`version: N+1`, `updated_at` bumped).

### 5.6 Delete pipeline

```
DELETE /pipelines/{id}
```

Soft delete. Subsequent reads return `404`. Subsequent executions return `pipeline.execution.not_found`. The pipeline's historical executions remain queryable.

Response: `204 No Content`.

### 5.7 List pipelines

```
GET /pipelines?owner={user-id}&datasource={name}&q={search}&offset=0&limit=50
```

Filters:
- `owner` — limit to pipelines owned by user.
- `datasource` — limit to pipelines using this datasource name.
- `q` — full-text search on name, display_name, description.

### 5.8 Import pipeline

```
POST /pipelines/import
Content-Type: application/json

{full pipeline JSON, possibly including id}
```

Response: `201 Created` or `200 OK` (if updating existing pipeline).

Fails with `pipeline.import.missing_datasource` or `pipeline.import.missing_template` if dependencies unmet.

### 5.9 Export pipeline

```
GET /pipelines/{id}/export?include_templates=true
```

Returns a bundle:

```json
{
  "schema_version": 1,
  "correlation_id": "uuid",
  "data": {
    "pipeline": {full pipeline JSON},
    "templates": [
      {full template JSON for each referenced template version}
    ],
    "manifest": {
      "pipeline_id": "...",
      "pipeline_version": 3,
      "template_count": 4,
      "exported_at": "..."
    }
  }
}
```

`include_templates=true` is the default. Set `false` to export the pipeline only.

---

## 6. Pipeline Execution — SSE

### 6.1 Endpoint

```
POST /pipelines/{id}/execute
Authorization: ...
Content-Type: application/json
Accept: text/event-stream
Idempotency-Key: ...   (strongly recommended)

{
  "version": 3,                    // optional; defaults to latest
  "parameters": {                  // required values per pipeline.parameters schema
    "start_date": "2026-01-01",
    "end_date": "2026-03-31",
    "include_cancelled": false
  }
}
```

Response: `200 OK` with `Content-Type: text/event-stream`.

The response is a **stream of SSE events**, one per execution milestone. The stream closes when execution completes (success or failure).

### 6.2 Why SSE, not WebSocket

- SSE is unidirectional server → client, which is exactly what we need (the client sends one request, the server streams progress).
- SSE works over standard HTTP — proxies, load balancers, auth headers all work natively.
- WebSocket would require a custom protocol, custom proxy config, custom auth. Overkill.

Note: stream *resumption* (`Last-Event-Id`) is NOT supported — a dropped stream means the execution will be cancelled after the disconnect grace period (§6.8).

### 6.3 SSE event format

Each event follows the SSE wire format:

```
event: {event_type}
id: {event_id}
data: {json_payload}

```

(Terminated by blank line.)

`event_id` is monotonic per execution. Used for gap detection (§6.7).

### 6.4 Event types

Every event payload additionally carries `correlation_id` (normative — [Observability §3.3](observability.md#33-correlation-id-propagation)); the examples below omit it for brevity except where shown.

#### 6.4.1 `execution_started`

Emitted once at the start.

```json
{
  "execution_id": "exec-uuid",
  "pipeline_id": "pipeline-uuid",
  "pipeline_version": 3,
  "started_at": "2026-08-05T14:30:00.123Z",
  "correlation_id": "uuid",
  "parameters": {"start_date": "2026-01-01", ...}
}
```

#### 6.4.2 `node_started`

Emitted when a node begins executing (after its dependencies completed).

```json
{
  "execution_id": "exec-uuid",
  "node_id": "fetch_orders",
  "started_at": "2026-08-05T14:30:00.234Z",
  "attempt": 1
}
```

#### 6.4.3 `node_completed`

Emitted when a node finishes successfully.

```json
{
  "execution_id": "exec-uuid",
  "node_id": "fetch_orders",
  "started_at": "2026-08-05T14:30:00.234Z",
  "completed_at": "2026-08-05T14:30:01.500Z",
  "duration_ms": 1266,
  "rows_out": 12453,
  "bytes_out": 4567890
}
```

#### 6.4.4 `node_failed`

Emitted when a node fails. Execution then halts (fail-fast); a `pipeline_failed` event follows.

```json
{
  "execution_id": "exec-uuid",
  "node_id": "fetch_orders",
  "started_at": "2026-08-05T14:30:00.234Z",
  "failed_at": "2026-08-05T14:30:00.500Z",
  "duration_ms": 266,
  "error": {
    "code": "pipeline.node.datasource_connection_failed",
    "message": "Could not acquire connection to 'pg-prod': Connection refused.",
    "user_message": "We couldn't reach the 'pg-prod' database. Check that the database is online and reachable from this server.",
    "details": {
      "datasource_name": "pg-prod",
      "underlying_error": "java.net.ConnectException: Connection refused"
    },
    "doc_url": "https://docs.datapipelines.co/errors/pipeline-node-datasource-connection-failed"
  }
}
```

#### 6.4.5 `pipeline_completed`

Emitted when execution finishes successfully, immediately before `data_ready`.

```json
{
  "execution_id": "exec-uuid",
  "pipeline_id": "pipeline-uuid",
  "pipeline_version": 3,
  "started_at": "2026-08-05T14:30:00.123Z",
  "completed_at": "2026-08-05T14:30:02.500Z",
  "duration_ms": 2377,
  "status": "SUCCESS",
  "node_stats": [
    {"node_id": "fetch_orders", "duration_ms": 1266, "rows_out": 12453, "bytes_out": 4567890, "status": "SUCCESS"},
    {"node_id": "fetch_customers", "duration_ms": 850, "rows_out": 5400, "bytes_out": 1200000, "status": "SUCCESS"},
    {"node_id": "revenue_by_customer", "duration_ms": 200, "rows_out": 4500, "bytes_out": 800000, "status": "SUCCESS"},
    {"node_id": "final_report", "duration_ms": 60, "rows_out": 4480, "bytes_out": 780000, "status": "SUCCESS"}
  ]
}
```

#### 6.4.6 `pipeline_failed`

Emitted when execution halts due to any node failure.

```json
{
  "execution_id": "exec-uuid",
  "pipeline_id": "pipeline-uuid",
  "pipeline_version": 3,
  "started_at": "2026-08-05T14:30:00.123Z",
  "failed_at": "2026-08-05T14:30:00.500Z",
  "duration_ms": 377,
  "status": "FAILED",
  "failed_node_id": "fetch_orders",
  "error": {
    "code": "pipeline.node.datasource_connection_failed",
    "message": "...",
    ...
  },
  "node_stats": [
    {"node_id": "fetch_orders", "duration_ms": 266, "rows_out": 0, "status": "FAILED", "error_code": "..."},
    {"node_id": "fetch_customers", "duration_ms": 0, "status": "ABORTED"},
    {"node_id": "revenue_by_customer", "duration_ms": 0, "status": "ABORTED"},
    {"node_id": "final_report", "duration_ms": 0, "status": "ABORTED"}
  ]
}
```

#### 6.4.7 `data_ready`

Emitted after `pipeline_completed`, only when the pipeline has a caller node ([Pipeline Contract §9](pipeline-contract.md#9-the-caller-node-result-node)). By the time this event is emitted the full result is materialized in Redis; the event carries the schema, the **inline first page** (up to `datapipelines.result.page-size-rows`), and the cursor for the rest.

```
event: data_ready
id: 7
data: {
  "execution_id": "exec-uuid",
  "pipeline_id": "pipeline-uuid",
  "schema": [
    {"name": "customer_id", "type": "INTEGER"},
    {"name": "total_amount", "type": "BIGDECIMAL", "precision": 18, "scale": 2},
    {"name": "first_order_at", "type": "TIMESTAMP"}
  ],
  "rows": [
    [1, "12345.67", "2024-01-15T00:00:00Z"],
    [2, "67890.12", "2024-02-03T00:00:00Z"]
  ],
  "row_count": 2,
  "total_rows": 2,
  "has_more": false,
  "result_url": "https://{host}/api/v1/executions/exec-uuid/result",
  "expires_at": "2026-08-05T14:35:02Z",
  "ttl_seconds": 300,
  "warnings": []
}
```

- `rows` — the first page. For results ≤ one page, `rows` IS the whole result (`has_more: false`) and no cursor read is needed.
- `result_url` + `expires_at` — cursor for paging the full result within TTL (§7). Always present, small results included — a client may re-fetch or download in another format within the TTL.
- There is no `delivery_mode` field — the delivery model is uniform (v1.3; the former inline/claim-check split is gone).

#### 6.4.8 `execution_aborted`

Terminal event when an execution is cancelled: explicit `DELETE /executions/{id}` (§10.4), client disconnect beyond the grace period (§6.8), or server shutdown. Emitted to any still-connected stream (e.g., a UI watching an execution another session cancelled).

```json
{
  "execution_id": "exec-uuid",
  "pipeline_id": "pipeline-uuid",
  "aborted_at": "2026-08-05T14:30:01.000Z",
  "reason": "client_disconnect" | "cancelled" | "shutdown",
  "status": "ABORTED",
  "node_stats": [...]
}
```

### 6.5 Event ordering guarantee

Within a single execution stream, events are ordered:
1. Exactly one `execution_started` (first).
2. For each node: zero or one `node_started` → zero or one of (`node_completed` | `node_failed`).
3. Exactly one terminal sequence: (`pipeline_completed` [→ `data_ready` if a caller node exists]) | `pipeline_failed` | `execution_aborted`.
4. Stream closes after the terminal event.

For parallel nodes, events are emitted in real-time as they occur (interleaved). Order between parallel nodes is non-deterministic.

### 6.6 Heartbeat (keepalive)

To prevent load balancer / proxy idle-timeout kills (AWS ALB default 60s, nginx default 65s), the server sends SSE comment lines every 15 seconds when no events have been emitted:

```
: heartbeat
```

These are SSE comments — ignored by the `EventSource` parser and by our `fetch`-based consumer. They exist solely to keep the TCP connection alive during periods of no event flow (e.g., a slow source query taking 30+ seconds).

The heartbeat interval is configurable via `datapipelines.sse.heartbeat-interval-seconds` (default: 15).

### 6.7 Event idempotency

`event_id` is monotonic per execution. Clients can use it to detect dropped events (gap in sequence). Stream resumption is not supported (§6.8).

### 6.8 Client disconnect

**A disconnected client cancels its execution.** If the SSE connection drops mid-stream, the executing instance starts a grace timer (`datapipelines.sse.disconnect-grace-seconds`, default 30). If no terminal event has been reached when the grace period elapses, the execution is cancelled — in-flight statements are interrupted via `Statement.cancel()`, held datasource connections are released, and the execution finishes as `ABORTED` ([DAG Executor §8.3](dag-executor.md#83-cancellation)). Rationale: an execution nobody is waiting for must not keep occupying source-database connections and staging memory.

Consequences clients must design for:

- There is no reconnection or resumption path — `Last-Event-Id` is ignored. A client that loses its stream should assume the execution will be aborted and re-execute (with an `Idempotency-Key`, a retry within the idempotency TTL that arrives before the abort completes attaches to nothing — the original is gone; the retry starts a fresh execution).
- A disconnect **after** the terminal event costs nothing: the execution is complete and its result lives out its TTL in Redis — fetch it via §7.
- Detached (fire-and-forget) execution is intentionally not offered in v1; async execution with webhooks is a ROADMAP item.

Completed executions remain visible: metadata via `GET /executions/{execution_id}` (durable, [Metadata DB](metadata-db.md)), events replayable for 1 hour via §10.3 (Redis event log), results within their TTL via §7.

---

## 7. Result Delivery

### 7.1 Model

Every completed execution with a caller node has its full result **materialized in Redis before `data_ready` is emitted**. One storage model, one retrieval path:

- `data_ready` carries the schema + inline first page + `result_url` (§6.4.7). Small results need no further call.
- The cursor endpoint below pages the stored result — for ANY execution, any size, within the TTL, in JSON / Arrow / CSV.
- Because the result is fully materialized before the cursor exists, row order is stable across pages.

**Hard cap:** a caller result larger than `datapipelines.result.max-size-bytes` (default 100 MB) fails the execution with `result.too_large`. **Result delivery is not the bulk-data path** — pipelines producing large datasets should write them back with `output.target: "datasource"` and return a summary (or nothing) to the caller. Explicit NOT-goals: durable result storage beyond the TTL, and result delivery as an ETL mechanism.

**Redis unavailable at result-write time** fails the execution with `result.storage_unavailable` — there is no fallback to inline-only delivery (that would reintroduce a second path).

### 7.2 Cursor endpoint

```
GET /executions/{execution_id}/result?offset=0&limit=10000&format=json
```

Auth: `read` scope + ownership of the execution (`admin` may read any). The URL is not a capability — an unauthenticated request 401s ([Auth §7.6](auth.md#76-scope--operation-matrix-authoritative)).

### 7.3 Response (JSON format, default)

```json
{
  "schema_version": 1,
  "correlation_id": "uuid",
  "data": {
    "execution_id": "exec-uuid",
    "schema": [...],
    "rows": [
      [1, "12345.67", "2024-01-15T00:00:00Z"],
      ...
    ],
    "row_count": 10000,
    "offset": 0,
    "limit": 10000,
    "total_rows": 12450000,
    "has_more": true,
    "expires_at": "2026-08-05T14:35:02Z"
  }
}
```

Pagination: `offset` + `limit`. Default `limit` is `datapipelines.result.page-size-rows`; maximum is `datapipelines.result.page-max-rows`.

### 7.4 TTL — fixed, client-influenced, clamped

The client may request a TTL on the execute call:

```
POST /pipelines/{id}/execute
DP-Result-TTL-Seconds: 900
```

Effective TTL = `clamp(requested, datapipelines.result.ttl-min-seconds, datapipelines.result.ttl-max-seconds)`; if the header is absent, `datapipelines.result.ttl-default-seconds` (300). The clamp is non-negotiable — an unbounded client-controlled TTL would let one caller pin gigabytes in Redis.

The expiry is **fixed at result-write time** — page reads do NOT extend it (predictable memory; a result can never be kept alive indefinitely by polling). The effective `expires_at` is reported in `data_ready` and every cursor response. After expiry: `410 result.expired` — re-run the pipeline.

### 7.5 Other formats

```
GET /executions/{execution_id}/result?format=arrow    # application/vnd.apache.arrow.ipc
GET /executions/{execution_id}/result?format=csv      # text/csv, header row
```

Arrow: binary IPC stream with embedded schema, full result in one response (no pagination). CSV: header row; big integers/decimals as their wire-string form (Type System rules); full result, no pagination. Both respect the same TTL and auth.

**v1 delivery note (added v1.4):** `format=arrow` is recognized but not served in v1 — no Arrow IPC encoder ships in the v1 dependency set, and a hand-rolled one is unverifiable. The request is answered `400 result.format_unsupported` with `details.supported = ["json", "csv"]`. Arrow delivery remains tracked in §14.

### 7.6 Endpoint errors

Registry of record: [Pipeline Contract §13.10](pipeline-contract.md#1310-result-retrieval).

| Code | HTTP | Description |
|---|---|---|
| `result.execution_not_found` | 404 | Execution ID unknown |
| `result.execution_incomplete` | 409 | Execution has not completed yet |
| `result.execution_failed` | 410 | Execution ended in failure — no result to retrieve |
| `result.expired` | 410 | TTL elapsed; result no longer available |
| `result.format_unsupported` | 400 | Unknown `format` parameter |
| `result.too_large` | 500 | (During execution) result exceeded the size cap; execution failed |
| `result.storage_unavailable` | 500 | (During execution) Redis unavailable; execution failed |

---

## 8. Template Endpoints

Templates are first-class entities. See [Templates spec](templates.md) for the entity model.

### 8.1 Create template

```
POST /templates
Content-Type: application/json

{
  "id": "fetch_orders.sql",         // optional; auto-generated if omitted
  "dialect": "POSTGRES",
  "display_name": "Fetch Orders in Date Range",   // required (templates.md §3.2)
  "description": "Fetch orders in date range. Expects start_date and end_date in the render context.",
  "imports": [],
  "body": "SELECT order_id, customer_id, total_amount, order_date\nFROM orders\nWHERE order_date BETWEEN '${start_date}' AND '${end_date}'"
}
```

Templates declare no parameter schema — variables are declared by the pipelines that reference the template, and validated there by dry-render ([Pipeline Contract §7.4](pipeline-contract.md#74-template-variable-resolution)).

Response: `201 Created` with full template (including version `1`, `created_at`).

### 8.2 Get template (latest version)

```
GET /templates/{id}
```

### 8.3 Get template (specific version)

```
GET /templates/{id}/versions/{version}
```

### 8.4 Update template (creates new version)

```
PUT /templates/{id}

{
  "dialect": "POSTGRES",            // may differ from prior versions — a new version records its own dialect (existing pipelines pin a version, so they are unaffected)
  "display_name": "Fetch Orders in Date Range",   // required (templates.md §3.2)
  "description": "...",
  "imports": [{"id": "lib_date_filters.sql", "version": 2, "alias": "dates"}],
  "body": "..."
}
```

Response: `200 OK` with new version number.

### 8.5 List templates

```
GET /templates?dialect={dialect}&q={search}&offset=0&limit=50
```

### 8.6 Delete template

```
DELETE /templates/{id}
```

Soft delete. Existing pipelines referencing any version continue to work (we never hard-delete template versions). New pipelines cannot reference the deleted template.

### 8.7 Validate template (render against sample context)

```
POST /templates/{id}/versions/{version}/render

{
  "context": {
    "start_date": "2026-01-01",
    "end_date": "2026-01-31"
  }
}
```

Response: rendered SQL string. Useful for UI editor preview and for LLM-assisted authoring.

### 8.8 Import template library

```
POST /templates/import
Content-Type: application/json

{
  "templates": [
    {"id": "lib_aggregate.sql", "dialect": "POSTGRES", "body": "<#macro aggregate ...>...</#macro>"},
    ...
  ]
}
```

Library templates (Freemarker macros usable via `#import`) are stored like regular templates; they're just referenced by other templates rather than by pipelines directly. See [Templates spec](templates.md).

---

## 9. Datasource Endpoints

Datasources are environment-specific connections. See [Datasources spec](datasources.md) for the entity model.

### 9.1 Register datasource

```
POST /datasources
Content-Type: application/json

{
  "name": "pg-prod",
  "display_name": "Production Postgres",
  "dialect": "POSTGRES",
  "jdbc_url": "jdbc:postgresql://host:5432/db",
  "username": "readonly_user",
  "password": "...",                // write-only; never returned in GET
  "properties": {
    "hikari": {
      "maximumPoolSize": 10,
      "connectionTimeout": 30000
    },
    "jdbc": {
      "sslmode": "verify-full"
    }
  }
}
```

`properties` has exactly two reserved namespaces — `hikari` (HikariCP's own camelCase property names, durations in milliseconds) and `jdbc` (driver connection properties) — validated by a test pool build at save time. See [Datasources §5](datasources.md#5-connection-pool-configuration).

Response: `201 Created` with the datasource entity (excluding password).

### 9.2 List datasources

```
GET /datasources?dialect={dialect}&offset=0&limit=50
```

Returns the §4.3 pagination envelope (§2 principle 6 — list endpoints paginate).

### 9.3 Get datasource (sensitive fields redacted)

```
GET /datasources/{name}
```

Returns everything except `password`.

### 9.4 Update datasource

```
PUT /datasources/{name}
```

Updates connection details. Password is optional — omit to keep existing.

### 9.5 Delete datasource

```
DELETE /datasources/{name}
```

Fails with `datasource.in_use` if any non-deleted pipeline references it.

### 9.6 Test connection

```
POST /datasources/{name}/test
```

Returns `200 OK` with `{connected: true, server_version: "..."}` on success, or `200 OK` with `{connected: false, error: "..."}` on failure (note: not an HTTP error — connection test failure is a normal outcome, not a server error).

### 9.7 Schema introspection

```
GET /datasources/{name}/schema
GET /datasources/{name}/tables?schema={schema}
GET /datasources/{name}/tables/{table}/columns?schema={schema}
```

Read-only live schema metadata ([Datasources §7A](datasources.md#7a-schema-introspection)) over JDBC `DatabaseMetaData`, with column types mapped to the canonical Type System types. Scope: `author` ([Auth §7.6](auth.md#76-scope--operation-matrix-authoritative)) — same precedent as the connection test, since each call opens a live connection.

Responses (the §4.1 envelope around `data`):

```json
// GET /datasources/{name}/tables
{ "data": [ {"schema": "public", "name": "orders", "type": "TABLE"} ] }

// GET /datasources/{name}/tables/{table}/columns
{ "data": [
  {"name": "id", "type": "INTEGER", "nullable": false, "source_type": "int4"},
  {"name": "amount", "type": "DECIMAL", "precision": 10, "scale": 2, "source_type": "numeric"}
] }

// GET /datasources/{name}/schema
{ "data": {
    "datasource": "pg-prod", "dialect": "POSTGRES", "truncated": false,
    "tables": [ {"table": {"schema": "public", "name": "orders", "type": "TABLE"},
                 "columns": [ ...column descriptors as above... ]} ]
} }
```

Notes:

- `type` in a column descriptor is the canonical wire type; `source_type` is the driver's own type name. `precision`/`scale`/`nullable` are omitted when the metadata does not report them (the envelope convention — omitted is not null).
- `type` in a table descriptor is the driver's raw JDBC table type (`TABLE`, `VIEW`, `BASE TABLE`, ...).
- The snapshot is capped at 200 tables; `truncated: true` means tables were dropped — page the rest via `/tables` + `/columns`.
- Pass the table name exactly as `/tables` returned it — JDBC metadata name matching is case-sensitive. `table` and `schema` filters are exact-match identifiers, not LIKE patterns (`_`/`%` are escaped).
- An unknown `schema`/table filter matches nothing and returns an empty list. An unknown datasource name is `404 datasource.not_found`.
- No pagination: the snapshot is bounded by the cap, and per-table listings are naturally bounded.

---

## 10. Execution History

### 10.1 List executions

```
GET /executions?pipeline_id={id}&status={status}&offset=0&limit=50
```

Filters:
- `pipeline_id` — limit to one pipeline.
- `status` — `RUNNING | SUCCESS | FAILED | ABORTED`.
- `started_after` / `started_before` — ISO 8601 timestamp range.

### 10.2 Get execution metadata

```
GET /executions/{execution_id}
```

Returns the execution record (without rows — use §7 for result data):

```json
{
  "schema_version": 1,
  "correlation_id": "uuid",
  "data": {
    "execution_id": "exec-uuid",
    "pipeline_id": "pipeline-uuid",
    "pipeline_version": 3,
    "status": "SUCCESS",
    "parameters": {...},
    "started_at": "...",
    "completed_at": "...",
    "duration_ms": 2377,
    "node_stats": [...],
    "result_url": "...",            // present while the result is unexpired (absent for zero-caller pipelines)
    "result_expires_at": "...",
    "triggered_by": "user-uuid",
    "triggered_via": "UI" | "REST" | "MCP"
  }
}
```

### 10.3 Replay SSE stream

```
GET /executions/{execution_id}/events
Accept: text/event-stream
```

Re-emits the SSE event stream from the Redis event log, in original order with original timestamps. Useful for debugging pipelines after the fact.

Availability: the Redis event log lives **1 hour** past completion (not configurable); afterwards this endpoint returns `410 result.expired`. The durable per-event record survives 7 days in the `execution_events` table (`datapipelines.executions.event-retention-days`) and is queryable via ordinary execution metadata — only the *replayable stream* expires at 1 hour.

### 10.4 Cancel execution

```
DELETE /executions/{execution_id}
```

Cancels a RUNNING execution: in-flight statements are interrupted (`Statement.cancel()`), connections released, status set to `ABORTED`, and `execution_aborted` (§6.4.8) emitted to any connected stream. Scope: `execute` + ownership (`admin` may cancel any) — [Auth §7.6](auth.md#76-scope--operation-matrix-authoritative).

Works from **any** instance: the request writes a Redis cancellation flag that the executing instance honors within ~one heartbeat interval ([DAG Executor §8.3.1](dag-executor.md#831-the-registry)). The `204` acknowledges the cancellation *request*; the `execution_aborted` event marks its completion.

Response: `204 No Content`. Cancelling an already-terminal execution returns `409 Conflict` with `pipeline.execution.not_running`.

---

## 11. Health & Diagnostics

### 11.1 Health check

```
GET /health
```

Returns `200 OK` with service status. No auth required.

```json
{
  "status": "UP",
  "version": "1.2.3",
  "components": {
    "database": "UP",
    "redis": "UP",
    "h2_factory": "UP"
  }
}
```

### 11.2 Readiness check

```
GET /ready
```

Returns `200 OK` when the service is ready to accept traffic, `503` otherwise. Used by orchestrators (k8s).

---

## 12. Rate Limiting

### 12.1 Limits

All limits are **per user** — an API key draws from its owner's budget, so minting more keys does not raise any limit. Key names and defaults in [Configuration §3.7](configuration.md#37-rate-limiting) and §3.2 (executor concurrency):

- Requests: `rate-limit.requests-per-second` (100), `rate-limit.requests-per-minute` (1000).
- Pipeline execution: `executor.max-concurrent-executions-per-user` (10) → `pipeline.execution.concurrency_limit`.
- SSE connections: `sse.max-streams-per-user` (50) concurrent streams per user.

Counters are tracked in Redis, so limits hold across instances in a multi-instance deployment.

### 12.2 Headers

Every response includes the IETF draft rate-limit headers:

```
RateLimit-Limit: 100
RateLimit-Remaining: 87
RateLimit-Reset: 1691234567
```

On limit exceeded: `429 Too Many Requests` with `Retry-After` header and the single system-wide code `rate_limit.exceeded` ([Pipeline Contract §13.11](pipeline-contract.md#1311-rate-limiting--idempotency)) — the same code at every layer (REST, MCP, login).

---

## 13. CORS

### 13.1 Default policy

- `Access-Control-Allow-Origin`: configured per deployment (default: same-origin).
- `Access-Control-Allow-Methods`: `GET, POST, PUT, DELETE, OPTIONS`.
- `Access-Control-Allow-Headers`: `Authorization, DP-API-Key, DP-Correlation-Id, DP-CSRF-Token, DP-Result-TTL-Seconds, Content-Type, Idempotency-Key`.
- `Access-Control-Allow-Credentials`: `true` (for cookie-based UI auth).

### 13.2 SSE-specific

SSE endpoints must include CORS headers on the stream response. Browsers won't consume SSE without them.

---

## 14. Open Questions / Future Additions

Out of scope for v1:

- **Streaming result delivery via SSE**: stream rows through the SSE channel itself in `data_chunk` events, in addition to the stored-result cursor. Useful for very large results the client wants to process incrementally.
- **Async / detached execution with webhooks**: execute-and-return-immediately with delivery via webhook; would relax the §6.8 cancel-on-disconnect rule for explicitly detached runs.
- **GraphQL**: a GraphQL endpoint mirroring the REST surface, for clients that prefer it. Not in v1.
- **Webhook callbacks**: register a webhook URL and have us POST execution events there instead of (or in addition to) SSE. Useful for non-interactive integrations.
- **Result caching**: optional TTL-based caching of pipeline results keyed by `pipeline_id + version + parameters hash`. Useful for expensive pipelines that are queried with the same inputs repeatedly.
- **Arrow as default**: if Arrow IPC adoption grows, default result format could become Arrow IPC with JSON as fallback.
- **Multi-tenant deployment**: separate rate limits, datasources, and pipelines per tenant.

---

## 15. OpenAPI Specification

A complete OpenAPI 3.1 spec lives in `docs/api/openapi.yaml` and is published at `/openapi.json` on every running instance. This document is the normative reference; the OpenAPI is generated from it.

(The OpenAPI yaml is generated as a build artifact; not hand-edited.)

---

## 16. Auth & User Admin Endpoints

Flows and rules are specified in [Auth](auth.md) (§7.4 issuance, §7.6 scope matrix); this section defines the HTTP surface. All endpoints below live under `/api/v1`.

### 16.1 API keys (any authenticated principal — own keys only)

```
GET /auth/api-keys
```
Lists the caller's keys (id, name, scopes, created_at, expires_at, last_used_at, is_revoked). Never returns secrets.

```
POST /auth/api-keys
Content-Type: application/json

{"name": "claude-desktop", "scopes": ["read", "execute"], "expires_at": "2027-08-07T00:00:00Z"}
```
`scopes` must be ⊆ the caller's scopes (`403 auth.scope.insufficient` otherwise); `expires_at` optional. Response `201`:

```json
{
  "schema_version": 1,
  "correlation_id": "uuid",
  "data": {
    "id": "dpk_ab12cd34ef56",
    "name": "claude-desktop",
    "scopes": ["read", "execute"],
    "key": "dpk_ab12cd34ef56.9f8e7d6c...",
    "expires_at": "2027-08-07T00:00:00Z"
  }
}
```

`key` is the full plaintext, returned **exactly once** — it is never retrievable again.

```
DELETE /auth/api-keys/{key_id}
```
Revokes the key (effective ≤ cache TTL, ~60s). `204 No Content`.

### 16.2 Current principal

```
GET /auth/me
```
Returns the authenticated principal: `user_id`, `email`, `display_name`, `scopes`, `auth_method`, `key_id` (when key-authenticated). Lets agents and the UI discover their own scope set.

### 16.3 User administration (`admin` scope)

```
GET  /auth/users?q={search}&offset=0&limit=50     — list users
GET  /auth/users/{user_id}                         — user detail
POST /auth/users/{user_id}/deactivate              — is_active = false (effective ≤ ~60s, Auth §4.2)
POST /auth/users/{user_id}/activate                — is_active = true
POST /auth/users/{user_id}/grant-admin             — is_admin = true
POST /auth/users/{user_id}/revoke-admin            — is_admin = false
```

All return the standard envelopes; mutations return the updated user record and write the corresponding `auth.user.*` audit events ([Auth §10.1](auth.md#101-events)). There is no user-create endpoint — users are provisioned by OIDC first login only ([Auth §4.2](auth.md#42-user-provisioning)).

### 16.4 Logout (browser session)

```
POST /logout
```
Clears the `dp_session` cookie ([Auth §6.5](auth.md#65-logout)). Root-level (not under `/api/v1`), CSRF-protected, listed here for completeness.

---

## Appendix A: Change Log

| Date | Version | Author | Change |
|---|---|---|---|
| 2026-08-05 | v1.0 | initial draft | Initial REST API + SSE specification: endpoints, envelopes, SSE event schemas, claim-check pattern, pagination, rate limits, CORS |
| 2026-08-05 | v1.1 | propagation | Updated create-pipeline example to v1.1 Pipeline Contract shape (no `terminal_node_id`, no `datasources_used`, node has `type`/`output`). |
| 2026-08-05 | v1.2 | SSE hardening | Added SSE heartbeat (§6.6) for LB idle-timeout prevention. Updated stream reconnection (§6.8) for multi-instance without sticky sessions: execution continues on originating instance; client polls/fetches result via REST if SSE reconnects to different instance. |
| 2026-08-07 | v1.3 | consistency campaign | **D9:** §7 rewritten — every caller result materialized in Redis, `data_ready` = schema + inline first page + cursor, `DP-Result-TTL-Seconds` clamped TTL (fixed expiry), 100MB cap, `result.too_large`/`result.storage_unavailable`/`result.expired`; inline/claim-check split removed. **D7:** §6.8 inverted — client disconnect cancels the execution after grace; `Last-Event-Id` resumption language removed; new `DELETE /executions/{id}` (§10.4) + `execution_aborted` event (§6.4.8) + `pipeline.execution.not_running`. **D10:** `DP-` header sweep + header registry (§3.6); `RateLimit-*` headers. **D5/D15:** per-user rate limits, scope matrix references. New §16: API-key CRUD, `/auth/me`, user admin; deleted stale `/auth/login`//`/auth/refresh` references. §3.5 idempotency scoped to execute only. `jdbc_url` removed from `node_failed` details (redaction). See [SPEC-REVIEW-2026-08](SPEC-REVIEW-2026-08.md) |
| 2026-08-11 | v1.4 | P6a gate C doc-sync | Additive implementation-reality notes from the web module's Gate C: §3.4 correlation-id adoption is shape-conditional (non-UUID inbound values are replaced, not echoed); §7.5 `format=arrow` recognized but not served in v1 (`result.format_unsupported`, supported=[json,csv] — tracked in §14); §9.2 datasources list takes offset/limit and returns the §4.3 envelope (§2 principle 6); §10.3's post-expiry 410 named as `result.expired`. §13 gained `template.not_found` / `datasource.not_found` (404) — see pipeline-contract v1.3. |
| 2026-08-14 | v1.5 | v1.1 introspection build | New **§9.7 schema introspection**: `GET /datasources/{name}/schema`, `/tables?schema=`, `/tables/{table}/columns?schema=` — read-only JDBC metadata with canonical type mapping, `author` scope, 200-table snapshot cap, empty-list-for-unknown-filter. Sourced from datasources §7A; three MCP twins per mcp-server §6.2.16–18. |
