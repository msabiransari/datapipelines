# REST API + SSE Specification

**Status:** v1 (frozen contract — additive-only changes after this point)
**Owner:** datapipelines.co core
**Depends on:** [Type System spec](type-system.md), [Pipeline Contract spec](pipeline-contract.md)
**Last updated:** 2026-08-05

---

## 1. Purpose

This spec defines the **HTTP surface** of datapipelines.co: every REST endpoint, request/response shape, error format, the SSE event stream for pipeline execution, and the claim-check pattern for large results.

It is the contract for:
- Browser-based UI (the pipeline editor, dashboard, execution views)
- Direct API clients (.NET services, Python scripts, etc.)
- The MCP server (which is a thin adapter over these endpoints — see [MCP spec](mcp-server.md))

---

## 2. Design Principles

1. **JSON-first.** Every request and response is JSON unless explicitly otherwise (binary upload, SSE stream, claim-check blob).
2. **Envelope consistency.** Every success response uses the same envelope shape. Every error response uses the same error envelope.
3. **SSE for execution, REST for everything else.** Pipeline execution is the only long-running, event-emitting operation. It uses Server-Sent Events. All other endpoints are synchronous request-response.
4. **Claim-check for large data.** Result sets above a configurable threshold (default 1 MB) are stored in Redis and referenced by URL, not inlined in the SSE stream.
5. **Idempotency where it matters.** Pipeline execution supports idempotency keys (retries don't re-execute). Write operations on pipelines and templates do not (each write creates a new version).
6. **Pagination everywhere.** List endpoints paginate. Result sets paginate (via claim-check).
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

Every endpoint (except `/auth/login` and `/auth/refresh`) requires authentication via one of:

- **Session cookie** — for browser-based UI flows. Set by `/auth/login`.
- **API key** — for programmatic clients. Sent in header: `X-API-Key: {key}`.

API keys are issued per-user-per-agent from the UI. See [Auth spec](auth.md).

### 3.3 Content negotiation

- Default: `application/json`.
- SSE endpoints: `text/event-stream`.
- Binary upload (templates): `multipart/form-data` or `application/octet-stream`.
- Claim-check download: `application/json` (default) or `application/vnd.apache.arrow.ipc` (via `Accept`).

### 3.4 Correlation

Every request may include `X-Correlation-Id` header. The server echoes it in the response and includes it in logs. If absent, the server generates one and returns it in the response header.

### 3.5 Idempotency

Write operations support idempotency via the `Idempotency-Key` header. The server caches the response for that key+request-hash for 24 hours. Repeated requests with the same key return the cached response.

Particularly important for `POST /pipelines/{id}/execute` — agent retry after network blip should not double-execute.

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

- `code` — error code from the [Pipeline Contract §11 catalog](pipeline-contract.md#11-error-code-catalog-initial-set). Always lowercase, dot-separated.
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
Authorization: ...
Idempotency-Key: ...   (optional but recommended)
Content-Type: application/json

{
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

Note: no `terminal_node_id` field. Terminal is auto-detected as the single DQL sink with `output.target: "caller"`. See [Pipeline Contract §9](pipeline-contract.md#9-terminal-node-auto-detection).

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
- SSE has automatic reconnection with `Last-Event-Id` (we honor this for resumable streams — see §6.7).
- WebSocket would require a custom protocol, custom proxy config, custom auth. Overkill.

### 6.3 SSE event format

Each event follows the SSE wire format:

```
event: {event_type}
id: {event_id}
data: {json_payload}

```

(Terminated by blank line.)

`event_id` is monotonic per execution. Used for resumable streams (§6.7).

### 6.4 Event types

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
      "jdbc_url": "jdbc:postgresql://...:5432/...",
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

Emitted after `pipeline_completed`. Carries the result data.

**Inline form** (small results — under `LARGE_RESULT_THRESHOLD`, default 1 MB):

```
event: data_ready
id: 7
data: {
  "execution_id": "exec-uuid",
  "delivery_mode": "inline",
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
  "truncated": false,
  "warnings": []
}
```

**Claim-check form** (large results — over threshold):

```
event: data_ready
id: 7
data: {
  "execution_id": "exec-uuid",
  "delivery_mode": "claim_check",
  "schema": [
    {"name": "customer_id", "type": "INTEGER"},
    ...
  ],
  "row_count": 12450000,
  "result_url": "https://{host}/api/v1/executions/exec-uuid/result",
  "expires_at": "2026-08-05T14:35:02Z",
  "ttl_seconds": 300,
  "warnings": []
}
```

Schema is always inline (small). Only `rows` get the claim-check treatment.

### 6.5 Event ordering guarantee

Within a single execution stream, events are ordered:
1. Exactly one `execution_started` (first).
2. For each node: zero or one `node_started` → zero or one of (`node_completed` | `node_failed`).
3. Exactly one of (`pipeline_completed` → `data_ready`) | `pipeline_failed`.
4. Stream closes after terminal event.

For parallel nodes, events are emitted in real-time as they occur (interleaved). Order between parallel nodes is non-deterministic.

### 6.6 Heartbeat (keepalive)

To prevent load balancer / proxy idle-timeout kills (AWS ALB default 60s, nginx default 65s), the server sends SSE comment lines every 15 seconds when no events have been emitted:

```
: heartbeat
```

These are SSE comments — ignored by the `EventSource` parser and by our `fetch`-based consumer. They exist solely to keep the TCP connection alive during periods of no event flow (e.g., a slow source query taking 30+ seconds).

The heartbeat interval is configurable via `datapipelines.sse.heartbeat-interval-seconds` (default: 15).

### 6.7 Event idempotency

`event_id` is monotonic per execution. Clients can use it to:
- Detect dropped events (gap in sequence).
- Resume a disconnected stream (§6.8).

### 6.8 Stream reconnection

If the SSE connection drops mid-stream, the client attempts reconnection. In a multi-instance deployment without sticky sessions, the reconnection may hit a different instance that is not running the execution.

**This is acceptable by design** — pipeline executions continue on the originating instance regardless of SSE client state (per [DAG Executor §10](dag-executor.md#10-sse-event-integration)). The client has two fallback paths:

1. **Poll execution status:** `GET /api/v1/executions/{execution_id}` returns the execution record. Once `status` transitions to `SUCCESS` or `FAILED`, fetch the result.
2. **Fetch result directly:** `GET /api/v1/executions/{execution_id}/result` (paginated, supports JSON / Arrow / CSV).

For short-running pipelines (seconds to a few minutes), the execution typically completes before the client even finishes reconnecting. The client polls once or twice, gets `SUCCESS`, and fetches the result.

Executions live in the event log for 1 hour after completion. The SSE stream is available for replay via `GET /executions/{execution_id}/events` (see §10.3).

---

## 7. Claim-Check Result Retrieval

### 7.1 Endpoint

```
GET /executions/{execution_id}/result?offset=0&limit=10000&format=json
Authorization: ...
```

Returns the result data stored in Redis after a large-result execution.

### 7.2 Response (JSON format, default)

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

Pagination: `offset` + `limit`. Max `limit` is 100,000 per page (configurable).

### 7.3 Response (Arrow IPC format)

```
GET /executions/{execution_id}/result?format=arrow
Accept: application/vnd.apache.arrow.ipc
```

Returns binary Arrow IPC stream with embedded schema. No pagination — full result in one stream (clients that need streaming should use the SSE `data_ready` inline form, or accept the claim-check + paginate).

### 7.4 Response (CSV format)

```
GET /executions/{execution_id}/result?format=csv
Accept: text/csv
```

Returns CSV with header row. Big integers and big decimals serialized as their string form (per Type System wire rules). No pagination — full result.

### 7.5 TTL and cleanup

- Claim-check data stored in Redis with TTL = `CLAIM_CHECK_TTL_SECONDS` (default 300 = 5 minutes).
- TTL refreshed on each page read (sliding expiration).
- On final page read (when `has_more: false`), server triggers immediate Redis key deletion.
- After TTL expiry, Redis auto-expires the key. Subsequent requests return `410 Gone` with `result.claim_check_expired`.

### 7.6 Endpoint errors

| Code | HTTP | Description |
|---|---|---|
| `result.execution_not_found` | 404 | Execution ID unknown |
| `result.execution_incomplete` | 409 | Execution has not reached `data_ready` yet |
| `result.execution_failed` | 410 | Execution ended in failure — no result to retrieve |
| `result.claim_check_expired` | 410 | TTL expired; result no longer available |
| `result.format_unsupported` | 400 | Unknown `format` parameter |

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
  "description": "Fetch orders in date range.",
  "params_schema": {
    "start_date": {"type": "DATE"},
    "end_date": {"type": "DATE"}
  },
  "body": "SELECT order_id, customer_id, total_amount, order_date\nFROM orders\nWHERE order_date BETWEEN '${start_date}' AND '${end_date}'"
}
```

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
  "dialect": "POSTGRES",            // must match existing (or use POST to create new id)
  "description": "...",
  "params_schema": {...},
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
    "maximum_pool_size": 10,
    "connection_timeout_seconds": 30
  }
}
```

Response: `201 Created` with the datasource entity (excluding password).

### 9.2 List datasources

```
GET /datasources?dialect={dialect}
```

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
    "result_delivery": "inline" | "claim_check",
    "result_url": "...",            // present if claim_check and not expired
    "result_expires_at": "...",
    "correlation_id": "...",
    "triggered_by": "user-uuid",
    "triggered_via": "UI" | "REST" | "MCP"
  }
  }
}
```

### 10.3 Replay SSE stream

```
GET /executions/{execution_id}/events
Accept: text/event-stream
```

Re-emits the SSE event stream from execution history. Useful for debugging pipelines after the fact. Events are replayed in their original order with original timestamps.

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

- Per API key: 100 requests/second, 1000 requests/minute (configurable).
- Pipeline execution: 10 concurrent executions per API key (configurable).
- SSE connections: 50 concurrent streams per API key.

### 12.2 Headers

Every response includes:

```
X-RateLimit-Limit: 100
X-RateLimit-Remaining: 87
X-RateLimit-Reset: 1691234567
```

On limit exceeded: `429 Too Many Requests` with `Retry-After` header and code `rate_limit.exceeded`.

---

## 13. CORS

### 13.1 Default policy

- `Access-Control-Allow-Origin`: configured per deployment (default: same-origin).
- `Access-Control-Allow-Methods`: `GET, POST, PUT, DELETE, OPTIONS`.
- `Access-Control-Allow-Headers`: `Authorization, X-API-Key, Content-Type, Idempotency-Key, X-Correlation-Id, Last-Event-Id`.
- `Access-Control-Allow-Credentials`: `true` (for cookie-based UI auth).

### 13.2 SSE-specific

SSE endpoints must include CORS headers on the stream response. Browsers won't consume SSE without them.

---

## 14. Open Questions / Future Additions

Out of scope for v1:

- **Streaming result delivery via SSE**: in addition to inline / claim-check, support a third mode where rows are streamed through the SSE channel itself in `data_chunk` events. Useful for very large results the client wants to process incrementally.
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

## Appendix A: Change Log

| Date | Version | Author | Change |
|---|---|---|---|
| 2026-08-05 | v1.0 | initial draft | Initial REST API + SSE specification: endpoints, envelopes, SSE event schemas, claim-check pattern, pagination, rate limits, CORS |
| 2026-08-05 | v1.1 | propagation | Updated create-pipeline example to v1.1 Pipeline Contract shape (no `terminal_node_id`, no `datasources_used`, node has `type`/`output`). |
| 2026-08-05 | v1.2 | SSE hardening | Added SSE heartbeat (§6.6) for LB idle-timeout prevention. Updated stream reconnection (§6.8) for multi-instance without sticky sessions: execution continues on originating instance; client polls/fetches result via REST if SSE reconnects to different instance. |
