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

Response: `200 OK` with full pipeline JSON (including server-assigned fields). The default
body remains the **released** version; since the version lifecycle (versioning §7) the
response also carries the released version's `status` (`RELEASED`) and `body_hash` (the
precondition token for a first write), `current_version` (the latest RELEASED version), and
a `draft` pointer — `{version, body_hash, updated_by, updated_at}` — when a draft exists.

### 5.3 Get pipeline (specific version)

```
GET /pipelines/{id}/versions/{version}
```

### 5.4 List pipeline versions

```
GET /pipelines/{id}/versions
```

Returns metadata only (no body JSON) for each version: `version`, `status`
(`DRAFT`/`RELEASED`/`DISCARDED`), `body_hash`, `created_at`, `created_by`, `released_at`.

### 5.5 Update pipeline (writes the draft)

```
PUT /pipelines/{id}
If-Match: <body_hash of the version this edit is based on>
Content-Type: application/json

{full pipeline body, excluding server-assigned fields}
```

Since the version lifecycle (versioning §3.2/§5) a PUT **always writes the DRAFT branch**:
the first write after a release copies the released version to a draft (`version: N+1`,
`status: "DRAFT"`); later writes overwrite that same draft in place. A PUT never appends a
released version. The `If-Match` header carries the hash precondition (§4.2 of versioning):
the DRAFT's hash for an in-place write, the current RELEASED row's hash for a first write —
absent/blank is `400 pipeline.execution.invalid_parameter_type` with
`details.reason = "precondition_missing"`; stale is `409 pipeline.version.conflict` with the
current hash/author in `details`. A draft renaming onto a taken name fails HERE with
`pipeline.validation.duplicate_name` (versioning §3.5), not at release.

Response: `200 OK` with the draft version (`version`, `status: "DRAFT"`, `body_hash`,
`current_version` — the released pointer, unmoved).

### 5.10 Release pipeline

```
POST /pipelines/{id}/release
If-Match: <the draft's body_hash>
```

Locks the draft: the version flips to RELEASED, `pipelines.current_version` moves to it,
and the index row's name/display_name/description adopt the released body's values
(metadata rides the release — versioning §3.5). Preconditions evaluated server-side:
§12 re-validation on the draft body; every pinned template version RELEASED
(`409 pipeline.release.template_not_released` naming it); the hash guard
(`409 pipeline.version.conflict`); no draft at all (`409 pipeline.version.not_draft`).
UI-driven in practice — agents never release (versioning D4); no MCP tool exists.

Response: `200 OK` with the released version's full shape (`status: "RELEASED"`).

### 5.11 Discard pipeline draft

```
POST /pipelines/{id}/draft/discard
If-Match: <the draft's body_hash>
```

Discards the draft: a never-executed draft is hard-deleted and its version number returns
to the pool; an executed draft flips to `DISCARDED` (the executions FK blocks the delete)
and the number stays consumed. Both outcomes are transparent to the caller.

Response: `204 No Content`. Errors as §5.10 (`not_draft` / `version.conflict`).

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

**Preserved-version import** (versioning §9.2, D5 — version numbers are global identities
and imports never renumber): a payload carrying `version` (export and promotion send it)
is honored at that EXACT version. `body_hash` must ride along and is recomputed from the
payload body — a mismatch (or a missing declaration) is
`409 pipeline.import.hash_mismatch`. Target-side rules: absent ⇒ insert as RELEASED at that
version (`released_at` from the payload's, if present); present+RELEASED+same hash ⇒
idempotent `200`; present+RELEASED+different hash / present as DRAFT / present as DISCARDED
⇒ `409 pipeline.import.version_conflict` with both hashes and the target status in
`details`. A version-less payload keeps the allocate-next behavior above.

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

The exported pipeline object carries its lifecycle fields (`version`, `status`,
`body_hash`, `released_at`) so a preserved-version import on the target can verify and
re-stamp them; bundled template versions carry their `version`, `status` and `body_hash`.

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

A `PIPELINE` node's `node_completed` additionally carries `"child_execution_id": "exec-uuid"` — the execution its child ran as — so a stream consumer can follow the link to the child's own stream and record (composition, [DAG Executor §6.6](dag-executor.md#66-pipeline-composition-direct-delivery-slots-and-cancellation)). The field is absent for every other node type. The same value appears in the node's `node_stats` entry on the terminal events.

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

Response carries the version's `status` and `body_hash` and, when a draft exists, a
`draft` pointer — the template mirror of §5.2 (versioning §6/§7).

### 8.3 Get template (specific version)

```
GET /templates/{id}/versions/{version}
```

### 8.4 Update template (writes the draft)

```
PUT /templates/{id}
If-Match: <body_hash of the version this edit is based on>

{
  "dialect": "POSTGRES",            // may differ from prior versions — a new version records its own dialect (existing pipelines pin a version, so they are unaffected)
  "display_name": "Fetch Orders in Date Range",   // required (templates.md §3.2)
  "description": "...",
  "imports": [{"id": "lib_date_filters.sql", "version": 2, "alias": "dates"}],
  "body": "..."
}
```

The template mirror of §5.5 (versioning §3.2/§6): the first write after a release copies
to a draft, later writes overwrite it in place; `If-Match` carries the hash precondition;
stale is `409 template.version.conflict`. The draft versions the CONTENT fields —
`display_name`/`description` move on the index row at save time (versioning §6's
documented asymmetry: they are not part of the versioned artifact).

Response: `200 OK` with the draft version's projection (`version`, `status: "DRAFT"`,
`body_hash`).

### 8.9 Release template

```
POST /templates/{id}/release
If-Match: <the draft's body_hash>
```

Locks the template draft (§5.10's mirror; templates lock BEFORE pipelines — versioning
§6's pin rule is enforced at pipeline release). Errors: `template.version.not_draft`,
`template.version.conflict`, or the template validator's §13.9 codes re-run on the draft
content. Response: `200 OK` with the released version.

### 8.10 Discard template draft

```
POST /templates/{id}/draft/discard
If-Match: <the draft's body_hash>
```

Always a hard delete (nothing references a template version by FK — versioning §6), so the
version number always returns to the pool. Response: `204 No Content`.

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
  "introspection_include_schemas": ["apex_reporting"],  // OPTIONAL — §9.7 escape hatch for the
                                                        // system-schema exclusion (exact names,
                                                        // no patterns; lowercased at bind)
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

`introspection_include_schemas` (optional, [Datasources §3.3](datasources.md#33-field-reference)) exempts exact schema names from the §9.7 system-schema exclusion; entries are legal schema identifiers — letters, digits, `_`, `$`, `#`, lowercase (entries outside the alphabet — wildcards, quoted identifiers, qualified `db.schema` names — are rejected with `400 datasource.validation.properties_invalid`), and the stored list is normalized on save — trim, lowercase, drop blank-after-trim entries, deduplicate (first-seen order) — so what a GET projects always survives an unmodified PUT round-trip. Omitted from the response when empty.

**Workspace binding (workspaces D8):** the optional `global` (boolean, admin-only — creating shared infrastructure is an admin act) and `workspace` (string, a workspace the caller can access) fields set the binding; with neither, the datasource binds to the caller's ACTIVE workspace. `readonly` (boolean, default `false`) forbids the three write-shaped pipeline uses ([Datasources §5.7](datasources.md#57-readonly-datasources-flag-semantics-and-enforcement-layers)). A non-admin sending `global: true`, binding to a workspace they are not in, or any member write while `member-datasources-enabled` is off, is `400 datasource.validation.workspace_forbidden`. Datasource NAMES stay a flat global namespace: a collision with another workspace's datasource is still `409 datasource.validation.duplicate_name` (by design — `name` is the PK and the pool-registry key).

Response: `201 Created` with the datasource entity (excluding password).

### 9.2 List datasources

```
GET /datasources?dialect={dialect}&offset=0&limit=50
```

Returns the §4.3 pagination envelope (§2 principle 6 — list endpoints paginate). **Workspace-scoped** (workspaces §5.3): the listing shows the active workspace's bound datasources plus all global ones; the predicate runs in the repository's SQL, so `total` counts exactly what the caller can see. A workspace-bound datasource of another workspace is absent — not filtered client-side, not paged-past.

### 9.3 Get datasource (sensitive fields redacted)

```
GET /datasources/{name}
```

Returns everything except `password`, plus the additive `workspace` (the bound workspace's name, `null` = global) and `readonly` fields (workspaces design §9). A name bound to another workspace behaves as not-found (`404 datasource.not_found`).

### 9.4 Update datasource

```
PUT /datasources/{name}
```

Updates connection details. Password is optional — omit to keep existing. The body is the §9.1 shape (name immutable); `introspection_include_schemas` is replaced wholesale when present and dropped to empty when absent. The `global`/`readonly` flags are optional — absent keeps the stored value, present attempts a gated write: `global` (either direction) and mutating a global datasource are admin-only, and `readonly` on a GLOBAL datasource is admin-only (workspaces design §6 last paragraph); a member may flip `readonly` on their workspace-bound datasource when the D8 gate is on. An accepted flag write crosses the same registry save path as every update — the connection pool is drained and rebuilds under the new settings at the next lease. Errors: `400 datasource.validation.workspace_forbidden` for the refusals.

### 9.5 Delete datasource

```
DELETE /datasources/{name}
```

Fails with `datasource.in_use` if any non-deleted pipeline references it. D8-gated like update: deleting a global datasource requires admin; a member needs the `member-datasources-enabled` gate for a bound one.

### 9.6 Test connection

```
POST /datasources/{name}/test
```

Returns `200 OK` with `{connected: true, server_version: "..."}` on success, or `200 OK` with `{connected: false, error: "..."}` on failure (note: not an HTTP error — connection test failure is a normal outcome, not a server error).

### 9.7 Schema introspection

```
GET /datasources/{name}/schemas
GET /datasources/{name}/tables?schema={schema}
GET /datasources/{name}/tables/{table}/columns?schema={schema}
```

Read-only live schema metadata ([Datasources §7A](datasources.md#7a-schema-introspection)) over JDBC `DatabaseMetaData`, with column types mapped to the canonical Type System types. Scope: `author` ([Auth §7.6](auth.md#76-scope--operation-matrix-authoritative)) — same precedent as the connection test, since each call opens a live connection.

Responses (the §4.1 envelope around `data`):

```json
// GET /datasources/{name}/schemas
{ "data": { "schemas": ["public", "sales"], "truncated": false } }

// GET /datasources/{name}/tables
{ "data": { "tables": [ {"schema": "public", "name": "orders", "type": "TABLE", "remarks": "customer orders"} ], "truncated": false } }

// GET /datasources/{name}/tables/{table}/columns
{ "data": [
  {"name": "id", "type": "INTEGER", "nullable": false, "source_type": "int4", "warnings": [], "remarks": "surrogate primary key"},
  {"name": "amount", "type": "DECIMAL", "precision": 10, "scale": 2, "source_type": "numeric", "warnings": []}
] }
```

Notes:

- `GET /schemas` returns the driver-reported schema names with the engine's system schemas excluded, as a page (`{"schemas": [...], "truncated": bool}`); on MySQL the databases arrive as JDBC catalogs, so the listing reads them from `getCatalogs()`. An empty list is a valid result on schemaless datasources (SQLite, single-db DuckDB). The listing is capped at 2000 schemas; `truncated: true` means the cap dropped some (on MySQL catalog routing the walk would otherwise span every database the server grants).
- `type` in a column descriptor is the canonical wire type; `source_type` is the driver's own type name. `precision`/`scale`/`nullable` are omitted when the metadata does not report them (the envelope convention — omitted is not null). `warnings` carries the ingress type mapper's warning messages, empty when the mapping was clean. `remarks` in a table or column descriptor is the engine-stored comment (JDBC REMARKS), omitted when the driver/database has none; the schemas listing carries no remarks (`getSchemas()` has none).
- `type` in a table descriptor is the driver's raw JDBC table type (`TABLE`, `VIEW`, `BASE TABLE`, ...).
- The tables listing is capped at 2000 tables; `truncated: true` means tables were dropped. Without a `schema` parameter the tables listing spans schemas — pass each table's reported `schema` to `/columns`.
- Pass the table name exactly as `/tables` returned it — JDBC metadata name matching is case-sensitive. `table` and `schema` filters are exact-match identifiers, not LIKE patterns (`_`/`%` are escaped); a present-but-empty `?schema=` binds to `""` and is treated as absent, so the default applies rather than a match-nothing empty filter. System schemas are excluded everywhere; `/columns` without a `schema` parameter defaults to the connection's current schema (routed per dialect, [Datasources §7A](datasources.md#7a-schema-introspection)) so same-named tables in different schemas cannot merge their columns — and when the datasource reports **no current schema** (e.g. a database-less MySQL URL), that default is impossible, so `/columns` fails with `400 pipeline.execution.parameter_required` instead of returning a merged answer; list `/schemas` and pass one explicitly. An unfiltered `/tables` carries each row's own schema and cannot merge — it keeps working on such a datasource, with no guard.
- An unknown `schema`/table filter matches nothing and returns an empty list. An unknown datasource name is `404 datasource.not_found`. A connection failure against the datasource is `502 pipeline.execution.datasource_unreachable` (the customer's database being down is not a server error).
- No pagination: the tables and schemas listings are bounded by their 2000-row cap (`truncated` flags the drop), and per-table listings are naturally bounded.

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
    "result_row_count": 1204,       // null for a zero-caller pipeline, and for a `direct`-delivered child
    "result_size_bytes": 48213,
    "triggered_by": "user-uuid",
    "triggered_via": "UI" | "REST" | "MCP" | "PIPELINE",

    "parent_execution_id": "exec-uuid",   // the execution whose PIPELINE node spawned this one; null for a root
    "parent_node_id": "run_leaf",         // that node's id; null for a root
    "root_execution_id": "exec-uuid"      // the family's top ancestor; equals execution_id for a root
  }
}
```

**Composition lineage.** The three lineage fields are the composition family's links ([Pipeline Contract §8.5](pipeline-contract.md#85-pipeline-nodes), [Metadata DB §4.6](metadata-db.md#46-pipeline_executions)) and are always present — `null` on a root rather than omitted, so "this is a root" and "this server does not report lineage" stay distinguishable. They answer the question a client is left with when a `node_completed` names a `child_execution_id` (§6.4.3): fetch that id here, and `parent_execution_id` / `parent_node_id` say where it came from. `root_execution_id` is never null (it is the execution's own id for a root), so grouping a family needs no special case. §10.1's listing carries the same fields — it is the same projection, minus `result_url` / `result_expires_at`.

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

## 17. Workspace Endpoints

Workspaces are the unit of team isolation ([workspaces design](superpowers/specs/2026-08-16-workspaces-design.md) §9; [Auth §5.6](auth.md#56-workspace-resolution--the-dp-workspace-header) resolves the ACTIVE workspace per request). Every endpoint below lives under `/api/v1`. Scope minimums are in [Auth §7.6](auth.md#76-scope--operation-matrix-authoritative); the role/mode gates (owner-or-admin, provisioning mode, `open-join`) are enforced in the service layer, default-deny.

**The no-oracle rule** ([pipeline-contract §13.12](pipeline-contract.md#1312-workspace-resolution)): for anyone but a global admin, an unknown workspace name and a workspace the caller is not a member of are the SAME `403 workspace.membership_required` — a name cannot be probed. A global admin (who could otherwise see any workspace) gets a real `404 workspace.not_found`. A member who is not the owner of a workspace they ARE in gets the same 403 for management operations — role probing is an oracle too.

### 17.1 List own workspaces

```
GET /workspaces
```
The caller's memberships (design §9 "list-own"): `{name, role, joined_at}` rows. A global admin gets exactly the same shape for their own memberships — no implicit merged view (the ratified 019 ruling; admins address other workspaces per-request via `DP-Workspace`).

### 17.2 Get workspace

```
GET /workspaces/{name}
```
`{name, display_name, is_personal, created_at}`. Members (or a global admin) only — everyone else gets the 403/404 split above.

### 17.3 Create workspace

```
POST /workspaces
{"name": "team-etl", "display_name": "Team ETL"}
```
`display_name` optional (defaults to `name`). Per provisioning mode (configuration §3.17): `auto-per-user`/`self-serve` allow any authenticated principal; `closed` refuses non-admins with `403 workspace.creation_forbidden`. The creator enters as `owner`. Errors: `400 workspace.validation.name_invalid` (`[a-z0-9_-]+`, 1–63), `409 workspace.validation.duplicate_name` (global namespace, soft-deleted included).

### 17.4 Update workspace

```
PUT /workspaces/{name}
{"display_name": "Team ETL (renamed)"}
```
Renames the display name; `name` is immutable v1. An absent `display_name` keeps the current one. Owner or global admin.

### 17.5 Delete workspace

```
DELETE /workspaces/{name}
```
Soft delete. `409 workspace.in_use` while the workspace still owns non-deleted pipelines, templates or (workspace-bound) datasources — `details.counts` names what blocks, by kind. Owner or global admin.

### 17.6 List members

```
GET /workspaces/{name}/members
```
`{user_id, email, display_name, role, joined_at}` rows, oldest membership first. Any member of the workspace, or a global admin.

### 17.7 Add member

```
POST /workspaces/{name}/members
{"email": "bob@example.com"}
```
Owner or global admin — except the `open-join` self-service path: when `datapipelines.workspaces.open-join` is `true` (self-serve mode) and the email is the caller's own, any authenticated principal joins. The user must already exist (OIDC-provisioned); an unknown email is the §16.3 unknown-user 404 stand-in (`pipeline.execution.not_found`, `details.reason = "user_not_found"`). A missing or non-textual `email` is the surface's generic bad-parameter 400 (`pipeline.execution.invalid_parameter_type`, `details.field = "email"`). Adding an existing member is idempotent.

### 17.8 Remove member

```
DELETE /workspaces/{name}/members/{user_id}
```
Owner or global admin. Removing a member with the `owner` role is refused with `409 workspace.in_use` (`details.blocked_by = "owner_membership"`) — ownership transfer is not a v1 operation, and a workspace must never be left without its owner.

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
| 2026-08-15 | v1.6 | surface restructure (part 1) | §9.7: `GET /datasources/{name}/schema` removed (bundled whole-schema snapshot deleted; table listings stay lightweight so more tables fit in one response). Snapshot example and cap notes dropped. |
| 2026-08-15 | v1.7 | surface restructure (part 2) | §9.7: new `GET /datasources/{name}/schemas` — the flow's entry point; system schemas excluded, `getCatalogs()` on MySQL, empty list valid on schemaless datasources. Tables note now states the unfiltered listing spans schemas and each table's schema belongs in `/columns`. |
| 2026-08-15 | v1.8 | semantics via remarks | §9.7: table and column descriptors gain `remarks` (JDBC REMARKS, omitted when none); schemas listing carries none by construction. |
| 2026-08-15 | v1.9 | hardening round 3 (005 review fix-cycle) | §9.7: a present-but-empty `?schema=` binds to \"\" and is treated as absent (the default applies, not a match-nothing empty filter); a datasource reporting **no current schema** (e.g. database-less MySQL URL) makes `/columns` and an unfiltered `/tables` fail with `400 pipeline.execution.parameter_required` instead of a merged answer (list `/schemas`, pass one); the `/schemas` response becomes a page `{\"schemas\": [...], \"truncated\": bool}` capped at 2000 (was a bare array); blank remarks are omitted, never `\"\"`. §9.1/§9.4: optional `introspection_include_schemas` array (exact lowercase names, no patterns — `400 properties_invalid` otherwise; projected when non-empty; PUT replaces it wholesale). |
| 2026-08-16 | v1.10 | hardening round 4 (007 review fix-cycle) | §9.1/§9.4: `introspection_include_schemas` entries carrying `*` or `%` are rejected as patterns at save (`400 properties_invalid`); `_` stays a legal name character. |
| 2026-08-16 | v1.11 | hardening round 4 (007 review fix-cycle) | §9.7: an unfiltered `/tables` no longer fails on a datasource reporting no current schema — the 400 parameter_required is scoped to `/columns` alone (each tables row carries its own schema; a listing cannot merge). |
| 2026-08-16 | v1.12 | hardening round 5 (008 review fix-cycle) | §9.1/§9.4: the include-schemas list is normalized on save — trim, lowercase, drop blank-after-trim entries, deduplicate (first-seen order) — so a row that landed dirty (restore, manual JSONB edit) reads back clean and an unmodified GET→PUT round-trip succeeds instead of 400ing on blank entries. |
| 2026-08-16 | v1.13 | hardening round 5 (008 review fix-cycle) | §9.1/§9.4: include-schemas entries are validated against the legal-identifier alphabet of the supported dialects (letters, digits, `_`, `$`, `#`, lowercase) instead of a per-character wildcard denylist — `?`, glob ranges, quoted identifiers, and qualified `db.schema` entries are now rejected (`400 properties_invalid`) rather than storing inert. |
| 2026-08-16 | v1.14 | pipeline composition | §10.2: `triggered_via` gains `"PIPELINE"` — a child execution spawned by a parent's PIPELINE node appears in execution history like any other row (enums §18, metadata-db §4.6 V3 lineage columns). |
| 2026-08-17 | v1.15 | pipeline composition | §6.4.3: a PIPELINE node's `node_completed` carries `child_execution_id` (absent for all other node types); the same value appears in the terminal events' `node_stats` entries. §10.1's history surfaces render the lineage: a child row shows its `parent_execution_id` link. |
| 2026-08-28 | v1.16 | workspaces surfaces | New **§17 workspace endpoints** (list-own/read/create per mode/update/delete with `workspace.in_use`/members sub-resource with `open-join`) — §13.12's CRUD codes go live. §9 re-grounded on the workspaces model: §9.1 binding fields (`global` admin-only, `workspace` accessible-to-caller, default = ACTIVE workspace) + `readonly`; §9.2 listing is workspace-scoped with exact totals; §9.3 gains additive `workspace`+`readonly` fields; §9.4/§9.5 D8 gates (member CUD behind `member-datasources-enabled`, global CUD admin-only) with pool-rebuilding flag writes; by-name access to another workspace's datasource is not-found. T23: template duplicate name is `409 template.validation.duplicate_name`. T31: unauthenticated HTML-accepting requests 302 to `/login`; `/api/**`+`/mcp` keep the exact 401 JSON envelope. |
