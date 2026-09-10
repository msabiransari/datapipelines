# REST API + SSE Specification

**Status:** v2.7 (frozen contract — additive-only changes after this point)
**Owner:** datapipelines.co core
**Depends on:** [Type System spec](type-system.md), [Pipeline Contract spec](pipeline-contract.md), [Auth spec](auth.md)
**Last updated:** 2026-09-05

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

Required scopes per operation are defined once in the [Auth §7.6 scope matrix](auth.md#76-operation-matrix--two-axes-authoritative). API keys are issued per-user-per-agent from the UI (management endpoints in §16).

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
| `DP-Result-Page-Rows` | request | Client-requested size of the INLINE first page, clamped to `datapipelines.result.page-max-rows`. **One contract on two surfaces** (ruling R-EP4): `POST /pipelines/{id}/execute`'s `data_ready` and a published endpoint's `200` body (§19) |
| `DP-Execution-Id` | response | The execution a published endpoint's answer belongs to (§19.4) — present on both the `200` and the `202` |
| `DP-Promotion-Key` | request | The promotion peer's pre-shared server key, on `/api/v1/promotion/**` and nowhere else (§18, [Versioning §10.6](versioning.md#106-the-promotion-peer-credential--a-shared-server-key-ratified-2026-09-01)) |

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
  "name": "acme/finance/monthly_revenue",
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
      "template": {"id": "acme/finance/fetch_orders.sql", "version": 2},
      "output": {"target": "tempdb", "table": "stg_orders"},
      "depends_on": []
    },
    ...
    {
      "id": "final_report",
      "type": "DQL",
      "source": "tempdb",
      "template": {"id": "acme/finance/final_report.sql", "version": 1},
      "output": {"target": "caller"},
      "depends_on": ["revenue_by_customer"]
    }
  ]
}
```

Note: no `terminal_node_id` field. The result node is the (at most one) node resolving to `output.target: "caller"` — explicitly or by omitting `output`. See [Pipeline Contract §9](pipeline-contract.md#9-the-caller-node-result-node).

**`name` is a folder path** (since 2026-09-05, 067; a folder is mandatory since 077): **2–10** `/`-separated segments, each `[a-z0-9][a-z0-9_.-]{0,63}`, ≤ 200 chars total — the same grammar template ids take ([Pipeline Contract §3.2](pipeline-contract.md#32-field-reference), [Template Hierarchy §14](template-hierarchy-design.md)). `finance/payments/daily_settlement` is valid; a bare `monthly_revenue` is not — the tree root holds folders only, and an experiment goes under `test/`. Folders are virtual — there is no folder resource, no folder id and nothing to create — and a name is immutable, so the folder is chosen once, at creation. A malformed name is `400 pipeline.validation.name_invalid`, whose `details.reason` is `folder_required` when a folder is the only thing missing and `grammar` otherwise.

**This changes no route.** A pipeline is addressed by UUID everywhere (`/pipelines/{id}`), so the encoded-slash problem that forced templates' §8 dual addressing ([Template Hierarchy §9.6](template-hierarchy-design.md)) cannot arise here: no pipeline route carries a name in a path segment.

Response: `201 Created`

```json
{
  "schema_version": 1,
  "correlation_id": "uuid",
  "data": {
    "id": "pipeline-uuid",
    "version": 1,
    "name": "acme/finance/monthly_revenue",
    ...
  }
}
```

Server assigns: `id`, `version` (starts at `1`), `owner` (from auth), `created_at`, `updated_at`.

**Version 1 lands as a DRAFT** (versioning §3.2, ruling D55, 2026-09-08). The response carries
`status: "DRAFT"`, `version: 1`, that version's `body_hash` (the precondition token for the next
`PUT`), `current_version: null` — nothing is released yet — and the `draft` pointer, exactly as a
`PUT` response does. Releasing it is `POST /pipelines/{id}/release` (§5.10) like any other draft,
and it is a human action (D4). The pipeline is executable immediately regardless: `execute` with no
`version` runs the working version (§6.1).

The two create paths that are NOT authoring keep landing RELEASED: `POST /pipelines/import` (§5.8,
promotion) and the seeders that ride it. `POST /templates` (§8.1) mirrors this section exactly.

### 5.2 Get pipeline (working version)

```
GET /pipelines/{id}
```

Response: `200 OK` with full pipeline JSON (including server-assigned fields). The default
body is the **working version** (versioning §7, since 039): the DRAFT when one exists, else
the current released version — authoring reads must show the draft, or an editor rebases on
released content and quietly discards it. The response states which `version` and `status`
it returned, carries that version's `body_hash` (the precondition token for the next
write), `current_version` (the latest RELEASED version — the execute-default pointer,
unmoved), and a `draft` pointer — `{version, body_hash, updated_by, updated_at}` — when a
draft exists. Since 078 the body's `parameters` also lists the pipeline's **derived execute
inputs** — one entry per CALCULATOR node's `context_key`,
`{"type": <kind output wire type, or "ANY">, "required": false, "derived": true}` — because a
calculator key is an implicit optional input of the execute endpoint (pipeline-contract
§4.10: supply it and the node is skipped). Declared parameters carry no `derived` flag;
derived on read, never stored.

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
`current_version` — the released pointer, unmoved). A PUT whose body is identical to the
released one is a **no-op** (versioning §5.1): no draft is created, no version number is
consumed, and the response reports the current state — `status: "RELEASED"`, the released
version's `body_hash`, no `draft` pointer. Not a 4xx: the write was well-formed and the
outcome is "already in that state".

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

### 5.11 Purge pipeline draft

```
POST /pipelines/{id}/draft/discard
If-Match: <the draft's body_hash>
```

**Purges** the draft (101: the route keeps its historical spelling for the editor's
button): the row is hard-deleted **together with its executions** — development runs of a
thing that never shipped are not history — and when it was the sole version the entity row
goes too. The number returns to the pool (nothing surviving outranks it). **Session-only**
(an API key is refused `403 auth.session.required`); audited as `pipeline.version.purged`.

Response: `204 No Content`. Errors as §5.10 (`not_draft` / `version.conflict`).

### 5.12 Discard pipeline version

```
POST /pipelines/{id}/versions/{v}/discard
```

Discards RELEASED version v (101): the row flips to `DISCARDED` — reversible via §5.13,
its executions and promotion history stay — and the sticky pointer recomputes only when v
WAS the pointer (D60: highest eligible live version, else NULL; a DRAFT is eligible only
under development posture). Refusals: `409 pipeline.version.not_released` (a draft is
purged, never discarded; an already-discarded version needs restore), `409
pipeline.version.pinned` (a live parent version exact-pins v), `404
pipeline.execution.not_found` (unknown version), `403 pipeline.authoring.disabled`
(hardened). **Session-only**; audited as `pipeline.version.discarded` (pointer
before/after in `details`).

Response: `200 OK` with the pipeline's full shape at the new pointer.

### 5.13 Restore pipeline version

```
POST /pipelines/{id}/versions/{v}/restore
```

Returns DISCARDED version v to RELEASED (101): the original `released_at`/`released_by`
return untouched, the discard stamps clear, and the pointer moves only when v > current or
current is NULL. `409 pipeline.version.not_discarded` otherwise. **Session-only**; audited
as `pipeline.version.restored`.

Response: `200 OK` with the pipeline's full shape.

### 5.14 Purge pipeline version

```
DELETE /pipelines/{id}/versions/{v}
```

**Purge, drafts only** (101): the version row and its executions are deleted,
irreversibly; the sole-draft case takes the entity with it. A RELEASED target is `409
pipeline.version.last_release` — a release is never purged; discard is per version and
reversible. **Session-only**; audited as `pipeline.version.purged`.

Response: `204 No Content`.

### 5.15 Switch pipeline current version

```
POST /pipelines/{id}/current
{"version": 3}
```

The **manual switch** (101, D60): `current = version`, which must be a live,
posture-eligible version (`409 pipeline.version.not_eligible` for a DISCARDED target or a
DRAFT under a hardened posture). NOT authoring-gated — this is the promotion receiver's
rollout/rollback lever — but still **session-only**; audited as
`pipeline.current_switched` (from/to in `details`).

Response: `200 OK` with the pipeline's full shape at the new pointer.

### 5.6 Delete pipeline (the entity purge)

```
DELETE /pipelines/{id}?include_exclusive_draft_templates=false
```

**The entity purge** (101 — replaces the V1 soft delete, retired in V19): allowed only
when the pipeline's ONLY version is a DRAFT (`409 pipeline.version.last_release`
otherwise); the entity row, the draft and its executions go, irreversibly.
`include_exclusive_draft_templates=true` also purges the draft-only templates this
pipeline exclusively pins; the response carries the offered set either way, so the UI can
show the cleanup. **Session-only**; audited as `pipeline.purged`.

Response: `200 OK` with `{"purged": true, "exclusive_draft_templates": [...],
"exclusive_draft_templates_purged": bool}`.

### 5.7 List pipelines

```
GET /pipelines?owner={user-id}&datasource={name}&q={search}&offset=0&limit=50
```

Filters:
- `owner` — limit to pipelines owned by user.
- `datasource` — limit to pipelines using this datasource name.
- `q` — full-text search on name, display_name, description. Since names are paths, `q` matches across the **full path**: `q=finance/pay` finds `finance/payments/daily_settlement`.
Each row carries `version` — the **working** version, the draft's number when the pipeline has a
draft and the latest released otherwise — and `status` (`DRAFT` or `RELEASED`), because since D55 a
freshly authored pipeline has no released version to name (both are `null` for a pipeline whose sole
draft was discarded).

- `prefix` — browse ONE level of the folder tree instead of listing flat (067; same contract as `pipelines_list {prefix}`, [MCP §6.2.1](mcp-server.md)). Present-but-empty (`?prefix=`) is the ROOT. The `data` payload becomes `{prefix, folders, pipelines, total, has_more}`: `folders` lists the prefix's direct sub-folders as `{path, segment, pipeline_count}` (subtree counts), `pipelines` its direct leaves as the same metadata rows as the flat list, `total`/`has_more` page the leaves via `offset`/`limit`. An unknown or illegal prefix answers an EMPTY level with `200` — never a `400`, never a query error. `owner`/`datasource`/`q` are ignored while `prefix` is present: browse and search are different presentations.

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

**Released only.** An export is what a promotion import consumes, and §9.2's import lands RELEASED
content, so a pipeline with no released version (D55: every freshly created one) is refused with
`409 pipeline.promotion.not_released` — "release it from the UI first" — rather than exporting a
draft or 404-ing on an absent body.

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
  "version": 3,                    // optional; defaults to the WORKING version (§6.1.1)
  "parameters": {                  // required values per pipeline.parameters schema
    "start_date": "2026-01-01",
    "end_date": "2026-03-31",
    "include_cancelled": false
  }
}
```

#### 6.1.1 The default version

With no `version` in the body, the execution runs the **working version** (versioning §7.2, ruling
D56): the DRAFT when one exists, else the latest RELEASED version — "always run the last version".
On a development server that may be a draft (drafts have been executable since 039); where
`authoring-enabled=false` no draft can exist at all, so it is always a release
([environments.md](environments.md)). An explicit `version` is exact and is never clamped: an
unknown number is `404 pipeline.execution.not_found` (`details.pipeline_version` names it), not a
silent run of the latest.

The execution record pins the version that actually ran, and every executions surface marks a draft
run — REST `draft_run` (§10.2), and a `DRAFT` label on the executions list and detail screens
([ui-screens §4.8](ui-screens.md)). The one pipeline that cannot be run at all is one with no
version, reachable only by discarding the sole draft of a never-released pipeline (versioning §3.4);
it answers that same `404 pipeline.execution.not_found`.

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

The `error` object is the **failure record** (057/T85): one object, completed once at the failure site, carried unchanged into this event, the terminal `pipeline_failed` event, and `pipeline_executions.error_json` — so the live stream, `GET /executions/{id}` and the execution detail page all show the same thing. `correlation_id` matches the top-level stamp. `node`, `sql` and `exception` are present under `datapipelines.executions.error-detail=full` (the default) and omitted under `structured`; the SQL is always the rendered statement in `:name` form — bound parameter **values never travel in the error object** (a driver message may echo a value; that is the driver's text and ships as-is, exactly as the log prints it). The `caused_by` chain is outermost-first, **root cause LAST**; frames are capped at 40 per level and the chain at 16 levels.

```json
{
  "execution_id": "exec-uuid",
  "node_id": "fetch_orders",
  "correlation_id": "corr-uuid",
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
    "doc_url": "https://docs.datapipelines.co/errors/pipeline-node-datasource-connection-failed",
    "correlation_id": "corr-uuid",
    "node": {
      "id": "fetch_orders",
      "type": "DQL",
      "datasource": "pg-prod",
      "dialect": "POSTGRES",
      "template": "acme/finance/fetch_orders.sql",
      "template_version": 4
    },
    "sql": "SELECT * FROM orders WHERE placed_on >= :start_date",
    "exception": {
      "class": "java.sql.SQLException",
      "message": "Connection refused",
      "frames": ["org.postgresql.Driver.connect(Driver.java:262)", "..."],
      "caused_by": [
        {"class": "java.net.ConnectException", "message": "Connection refused", "frames": ["..."]}
      ]
    }
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

Emitted when execution halts due to any node failure. Its `error` object is the SAME failure record `node_failed` carried for the failed node (§6.4.4), unchanged — and is also what `pipeline_executions.error_json` stores, so `GET /executions/{id}` and the execution detail page show it after the fact. A timeout or setup failure carries the same shape minus `node`/`sql` (neither exists for it).

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

Auth: `read` scope + ownership of the execution (`admin` may read any). The URL is not a capability — an unauthenticated request 401s ([Auth §7.6](auth.md#76-operation-matrix--two-axes-authoritative)).

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

**Addressing (v2.0 — template-hierarchy-design §9.6): the template name NEVER travels in a URL
path segment.** Since v2.0 a name may contain `/` (`acme/finance/monthly_revenue`), and an
encoded `%2F` in the path is refused `400` by the container below routing and below the
security chain (measured on the pinned Tomcat — no handler can reach past it). Every route in
this section therefore carries the name as a **query parameter or a body field**; the pre-v2.0
`/{id}` path forms are removed, not kept alongside. `GET /templates` answers **three shapes on
one route**: the single-resource envelope with `404 template.not_found` when `name` is present
(§8.2), one tree level (folders with counts + leaves) when `prefix` is present (§8.5), and the
paged list envelope when neither is (§8.5) — an exact-match filter returning an empty list
would make "no such template" indistinguishable from "empty result", so the miss keeps its code.

### 8.1 Create template

```
POST /templates
Content-Type: application/json

{
  "id": "nyc/mobility/fetch_orders.sql",   // optional; auto-generated under test/ if omitted
  "type": "sql",                    // optional (default); "html" takes NO dialect (046)
  "dialect": "POSTGRES",
  "display_name": "Fetch Orders in Date Range",   // required (templates.md §3.2)
  "description": "Fetch orders in date range. Expects start_date and end_date in the render context.",
  "imports": [],
  "body": "SELECT order_id, customer_id, total_amount, order_date\nFROM orders\nWHERE order_date BETWEEN '${start_date}' AND '${end_date}'"
}
```

Templates declare no parameter schema — variables are declared by the pipelines that reference the template, and validated there by dry-render ([Pipeline Contract §7.4](pipeline-contract.md#74-template-variable-resolution)).

`type` is chosen here and never changes afterwards: `sql` (the default) requires `dialect` and is what pipeline nodes reference; `html` declares no `dialect`, renders through an auto-escaping engine configuration, and cannot be referenced by a pipeline node (templates.md §3.2, 046).

Response: `201 Created` with full template (including `type`, version `1`, `created_at`).

### 8.2 Get template (working version)

```
GET /templates?name={path}
```

Response carries the **working version's** projection (versioning §7, since 039 — the
template mirror of §5.2): the DRAFT when one exists, else the latest released version. The
projection states its `version`, `status` and `body_hash`, and a `draft` pointer is present
when a draft exists.

### 8.3 Get template (specific version)

```
GET /templates/versions?name={path}&version={N}
```

### 8.4 Update template (writes the draft)

```
PUT /templates
If-Match: <body_hash of the version this edit is based on>

{
  "id": "acme/finance/fetch_orders.sql",   // REQUIRED here — the §9.6 addressing form (the name never travels in the path)
  "dialect": "POSTGRES",            // may differ from prior versions — a new version records its own dialect (existing pipelines pin a version, so they are unaffected)
  "display_name": "Fetch Orders in Date Range",   // required (templates.md §3.2)
  "description": "...",
  "imports": [{"id": "acme/lib/date_filters.sql", "version": 2, "alias": "dates"}],
  "body": "..."
}
```

The template mirror of §5.5 (versioning §3.2/§6): the first write after a release copies
to a draft, later writes overwrite it in place; `If-Match` carries the hash precondition;
stale is `409 template.version.conflict`. The draft versions the CONTENT fields —
`display_name`/`description` move on the index row at save time (versioning §6's
documented asymmetry: they are not part of the versioned artifact).

Response: `200 OK` with the draft version's projection (`version`, `status: "DRAFT"`,
`body_hash`). Content identical to the released version is a no-op (versioning §5.1): the
response carries `status: "RELEASED"` and no draft was created — though
`display_name`/`description` still moved at save time (the §6 asymmetry: they are not part
of the hashed artifact).

### 8.9 Release template

```
POST /templates/release
If-Match: <the draft's body_hash>

{
  "name": "acme/finance/fetch_orders.sql"
}
```

Locks the template draft (§5.10's mirror; templates lock BEFORE pipelines — versioning
§6's pin rule is enforced at pipeline release). Errors: `template.version.not_draft`,
`template.version.conflict`, or the template validator's §13.9 codes re-run on the draft
content. Response: `200 OK` with the released version.

### 8.10 Purge template draft

```
POST /templates/draft/discard
If-Match: <the draft's body_hash>

{
  "name": "acme/finance/fetch_orders.sql"
}
```

**Purges** the draft (101 — historical route spelling): always a hard delete (nothing
references a template version by FK — versioning §6), and the sole-draft case takes the
template entity with it. **Session-only**; audited as `template.version.purged`.
Response: `204 No Content`.

### 8.11 Discard / restore / purge a template version, and the manual switch (101)

```
POST /templates/version/discard          {"name": "...", "version": 2}
POST /templates/version/restore          {"name": "...", "version": 2}
DELETE /templates/version?name=...&version=2
POST /templates/current                  {"name": "...", "version": 2}
```

The pipeline twins by name (§5.12–§5.15; §9.6: the name never travels in a path segment):
discard flips a RELEASED version to DISCARDED (`409 template.version.not_released`;
`409 template.in_use` while a live pipeline version pins it), restore brings it back
(`template.version.not_discarded`), purge is drafts-only
(`template.version.last_release`), and `current` is the manual switch
(`template.version.not_eligible`). The template's sticky pointer follows the same D60
rules. All **session-only**, audited as the `template.version.*` / `template.current_switched`
events. Responses mirror §5.12–§5.15 at template shape.

### 8.5 List templates

```
GET /templates?dialect={dialect}&type={sql|html}&q={search}&offset=0&limit=50
```

The second shape on this route: answers only when `name` AND `prefix` are both ABSENT (§8's addressing note). The `type` filter (046) is optional; an unknown value is refused `400 pipeline.execution.invalid_parameter_type` naming the supported values.

```
GET /templates?prefix={folder}&dialect={dialect}&type={sql|html}&offset=0&limit=50
```

The third shape: answers when `name` is absent and `prefix` is PRESENT — browse ONE level of the template tree (067; same contract as `templates_list {prefix}`, [MCP §6.2.6](mcp-server.md)). Present-but-empty (`?prefix=`) is the ROOT. The `data` payload becomes `{prefix, folders, templates, total, has_more}`: `folders` lists the prefix's direct sub-folders as `{path, segment, template_count}` (subtree counts), `templates` its direct leaves as the same rows as the flat list, `total`/`has_more` page the leaves via `offset`/`limit`. `dialect`/`type` narrow both halves, so a folder whose whole subtree is filtered out is absent rather than empty; `q` is ignored while `prefix` is present (browse and search are different presentations). An unknown or illegal prefix answers an EMPTY level with `200` — never a `400`, never a query error.

### 8.6 Delete template (the entity purge, 101)

```
DELETE /templates?name={path}
```

**The entity purge** (replaces the V1 soft delete, retired in V19): allowed only when the
template's ONLY version is a DRAFT (`409 template.version.last_release` otherwise) and no
live pipeline version pins any version of it (`409 template.in_use`, naming the pinners).
Existing pipelines referencing any version continue to work (we never hard-delete template
versions); **session-only**, audited as `template.purged`.

### 8.7 Validate template (render against sample context)

```
POST /templates/render

{
  "name": "acme/finance/fetch_orders.sql",
  "version": 1,
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
    {"id": "acme/lib/aggregate.sql", "dialect": "POSTGRES", "body": "<#macro aggregate ...>...</#macro>"},
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
  "credential": {                   // §3.4 — WHAT the credential is
    "kind": "password",             // password | token | private_key | service_account_json | none
    "username": "readonly_user",
    "secret": "..."                 // write-only; never returned in GET
  },
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

**The credential ([Datasources §3.4](datasources.md#34-credential-kinds)).** Send EITHER the `credential` object or the legacy top-level `username`/`password` pair, which means `kind: "password"` and stays accepted under the frozen-shape rule. A body carrying BOTH is `400 datasource.validation.properties_invalid` — the two can disagree and there is no defensible winner. `kind: "none"` carries neither field and is the shape for a file database or an IAM role; `kind: "token"` carries a secret and may name a username. A kind the dialect's pinned driver cannot authenticate with is `400 datasource.validation.properties_invalid` naming what the dialect accepts. A missing secret on create is `400 datasource.validation.password_missing` for every kind but `none`.

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

Returns everything except the credential SECRET — neither `password` nor `credential.secret` — plus the additive `credential` (`{kind, username?}`, §3.4), `workspace` (the bound workspace's name, `null` = global), `readonly` (workspaces design §9) and `last_test` fields. `password_set` is derived from `credential.kind`: `false` exactly for `kind: "none"`, which genuinely has no stored credential. Top-level `username` is still returned and is `null` for the kinds that have none. `last_test` is the outcome of the last connection test — `{"tested_at": "...", "ok": true|false, "message": "..."}`, or `null` when the datasource has never been probed ([Datasources §8.1B](datasources.md#81b-the-last-tests-outcome-is-stored-and-listed)); it is also on every `GET /datasources` row, which is what lets a reader see a credential has stopped working without running an execution. A name bound to another workspace behaves as not-found (`404 datasource.not_found`).

**`pool` (094, additive).** Beside `properties`, the response carries the connection pool's EFFECTIVE settings — one entry per tunable HikariCP key ([Datasources §5](datasources.md#5-connection-pool-configuration)) as `{"value", "unit", "source"}`, where `source` is `configured` (this row set it), `dialect_default`, `application_default` or `hikari_default`. The keys keep HikariCP's camelCase spelling, deliberately: they are the exact identifiers a caller writes back under `properties.hikari.*`. This is NOT a duplicate of `properties.hikari` — that map is what the row STORES and is empty for almost every datasource, while `pool` is what the pool RUNS with, which is what a pool-acquisition timeout is explained by.

```json
"pool": {
  "maximumPoolSize": {"value": 10, "unit": "count", "source": "hikari_default"},
  "minimumIdle": {"value": 2, "unit": "count", "source": "application_default"},
  "connectionTimeout": {"value": 5000, "unit": "ms", "source": "configured"}
}
```

### 9.4 Update datasource

```
PUT /datasources/{name}
```

Updates connection details. The credential secret is optional — omit `credential.secret` (or the legacy `password`) to keep the stored one. `credential.kind` is part of the body like every other field: changing it to `none` clears the stored credential, and changing it to ANY other kind requires a secret in the same request — `400 datasource.validation.password_missing` otherwise, because keeping the stored secret would relabel it as something it is not ([Datasources §3.4](datasources.md#34-credential-kinds)). The body is the §9.1 shape (name immutable); `introspection_include_schemas` is replaced wholesale when present and dropped to empty when absent. The `global`/`readonly` flags are optional — absent keeps the stored value, present attempts a gated write: `global` (either direction) and mutating a global datasource are admin-only, and `readonly` on a GLOBAL datasource is admin-only (workspaces design §6 last paragraph); a member may flip `readonly` on their workspace-bound datasource when the D8 gate is on. An accepted flag write crosses the same registry save path as every update — the connection pool is RETIRED and rebuilds under the new settings at the next lease. Retired, not closed (094, [Datasources §5.2](datasources.md#52-pool-lifecycle)): the old pool stops taking new leases immediately and closes once the statements already running on it finish, so an update never cuts a query that is mid-flight. Errors: `400 datasource.validation.workspace_forbidden` for the refusals.

### 9.5 Delete datasource

```
DELETE /datasources/{name}
```

Fails with `datasource.in_use` if any non-deleted pipeline references it **in any version it has ever stored** — DRAFT, RELEASED and DISCARDED alike ([Datasources §6.2](datasources.md#62-in-use-check-on-delete)). Pipeline versions are immutable and executable by explicit version, so a reference from a released v1 that a later v2 dropped is a real reference; the guard that read each pipeline's current version only let that delete through and failed v1's next execution at connect.

`details` carries `datasource_name`, `referencing_pipelines` (the distinct pipeline names) and `references` — one entry per referencing node, `{"pipeline", "node_id", "pipeline_version", "version_status"}` — the same shape `template.in_use` uses (§8.6), because the version is what tells the operator which body to go and edit.

D8-gated like update: deleting a global datasource requires admin; a member needs the `member-datasources-enabled` gate for a bound one.

A successful delete **retires** the pool on the deleting instance and, over the §5.7 invalidation channel, on every other one (094, [Datasources §5.2](datasources.md#52-pool-lifecycle)): new leases miss it at once, and each instance closes its pool when the queries already running on it finish — or at `datapipelines.datasources.retire-ceiling-seconds` if one hangs. A delete therefore never cuts a running execution.

The UI's Delete action asks this same in-use question BEFORE it offers anything, and renders the `references` rows ([UI Screens §4.5](ui-screens.md#45-datasource-list)).

### 9.6 Test connection

```
POST /datasources/{name}/test
```

Returns `200 OK` with the full wire form of `TestResult` ([Datasources §8.1](datasources.md#81-post-apiv1datasourcesnametest)) — `connected`, `tested_at`, `latency_ms`, `server_version`, `error`, `error_class` — on success and on failure alike (note: not an HTTP error — connection test failure is a normal outcome, not a server error).

The probe also **records its outcome on the datasource row**, so the result shows up as `last_test` on the list and get responses and on the datasources screen ([Datasources §8.1B](datasources.md#81b-the-last-tests-outcome-is-stored-and-listed)). The write touches the three outcome columns only: it does not move `updated_at` and is not a datasource edit.

### 9.7 Schema introspection

```
GET /datasources/{name}/schemas
GET /datasources/{name}/tables?schema={schema}
GET /datasources/{name}/tables/{table}/columns?schema={schema}
```

Read-only live schema metadata ([Datasources §7A](datasources.md#7a-schema-introspection)) over JDBC `DatabaseMetaData`, with column types mapped to the canonical Type System types. Scope: `author` ([Auth §7.6](auth.md#76-operation-matrix--two-axes-authoritative)) — same precedent as the connection test, since each call opens a live connection.

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

### 9.8 Lake tables (the dp-lake catalog)

```
POST   /datasources/{name}/tables
DELETE /datasources/{name}/tables/{namespace}/{table}
POST   /datasources/{name}/tables/import
GET    /datasources/{name}/lake-tables
```

The registry of tables a **LAKE**-dialect datasource serves ([metadata-db §4.15](metadata-db.md#415-lake_tables), the 2026-09-07 lake-datasource design record §2). A LAKE datasource reads object storage in place and the engine cannot LIST a bucket — these rows are its catalog. Scope: `author` for the three writes ([Auth §7.6](auth.md#76-operation-matrix--two-axes-authoritative)); mutating a GLOBAL datasource's registry additionally requires admin (workspaces D8, enforced in-handler). The listing is `read`. Every operation refuses a non-LAKE datasource with `400 datasource.validation.lake_dialect_required`.

**Register one table** — `POST /datasources/{name}/tables`, 201:

```json
{
  "namespace": ["nyc", "mobility"],
  "name": "hvfhv_zone_day",
  "format": "parquet",
  "location": "s3://datapipelines-co/sample-data/lake/v1/hvfhv_zone_day/part-0.parquet",
  "partition_column": null
}
```

- `namespace` — required, 1–9 segments; an array of segments or the dotted shorthand `"nyc.mobility"`. Each segment follows the pipeline/template segment grammar (`[a-z0-9][a-z0-9_.-]{0,63}`) **without `.`** (the dotted form must round-trip). Violations are `400 datasource.validation.lake_namespace_invalid`.
- `name` — required, one segment of the same grammar; `400 datasource.validation.lake_name_invalid`.
- `format` — `parquet` | `iceberg`; `400 datasource.validation.lake_format_invalid`.
- `location` — `s3://bucket/prefix[/glob]` or a `file://` path. For `format: iceberg` the location is the table's **current metadata FILE** (`…/metadata/00042-<uuid>.metadata.json`), not the table root — the measured rule of [Datasources §8C.7](datasources.md#8c7-the-iceberg-location-rule--register-the-metadata-file). **No other schemes**, and no quotes, backslashes, whitespace or control characters anywhere — the value is later interpolated into the engine's `CREATE VIEW`, so the refusal is total (`400 datasource.validation.lake_location_invalid`).
- `partition_column` — optional; a plain column identifier.
- Re-registering a taken (namespace, name) triple is `409 datasource.lake_table_duplicate`.

**Unregister** — `DELETE /datasources/{name}/tables/{namespace}/{table}`, 204; `{namespace}` is the dot-joined form (`nyc.mobility`). An absent triple is `404 datasource.lake_table_not_found`, never a silent no-op. The bucket's objects are untouched.

**Bulk import** — `POST /datasources/{name}/tables/import`. The body is EITHER a 088-style manifest block inline:

```json
{
  "namespace": ["nyc", "mobility"],
  "publish_prefix": "s3://datapipelines-co/sample-data/lake/v1",
  "tables": [
    {"name": "hvfhv_zone_day", "format": "parquet", "path": "hvfhv_zone_day/part-0.parquet"},
    {"name": "hvfhv_trips", "format": "parquet", "location": "s3://datapipelines-co/sample-data/lake/v1/hvfhv_trips/pickup_date=*/part-*.parquet", "partition_column": "pickup_date"}
  ]
}
```

OR `{"manifest_url": "https://…", "namespace": "nyc.mobility"}` — a URL fetched **server-side, and only from the datasource's own bucket/endpoint** (derived from its declared `dialect.endpoint` / `catalog.ref`; an `s3://` URL is translated to the matching https form; with neither declared, only AWS S3 hosts over https). Anything else is `400 datasource.validation.lake_manifest_url_forbidden` — there is no arbitrary URL fetch (SSRF). A failed fetch is `502 pipeline.execution.datasource_unreachable`. Entry fields follow register's rules (`path` is resolved against `publish_prefix` / `access.https_base`; an entry's own `namespace` beats the shared one; an entry with neither is a 400). Import is **idempotent**: already-registered triples come back in `already_registered`, not as errors, so bootstrap can re-run it. The response is `{registered: [...], registered_count, already_registered: [...], already_registered_count}`.

**List the registry** — `GET /datasources/{name}/lake-tables` (`read`): the catalog's own rows, `{datasource, tables: [...], count}`. This is deliberately a separate route from §9.7's `GET /{name}/tables`, the introspection listing — which for a LAKE datasource is itself served from this registry (tables reported as `VIEW` with the format in remarks; [Datasources §8C.3](datasources.md#8c3-introspection-reads-the-registry-not-jdbc-metadata)), so the two routes agree by construction.

Every successful write evicts the datasource's connection pool and publishes the §5.7 invalidation, so per-connection state is rebuilt on the next lease ([Datasources §5.7](datasources.md)).

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

Each page item carries the §10.2 metadata projection minus `result_url` /
`result_expires_at` (present only on the single-execution read, and only while the
result is unexpired):

| Field | Type | Description |
|---|---|---|
| `execution_id` | uuid | The execution's id |
| `pipeline_id` | uuid | The pipeline that ran |
| `pipeline_version` | int | The version of the pipeline that ran |
| `status` | string | `RUNNING` \| `SUCCESS` \| `FAILED` \| `ABORTED` |
| `draft_run` | bool | The version that ran was a draft at the time (or still is / was discarded) — an informational history label, never execution behaviour or promotion eligibility ([Versioning §8](versioning.md#8-executing-drafts)) |
| `parameters` | object | The execution-time parameters the caller sent |
| `started_at` | timestamp | Start of execution |
| `completed_at` | timestamp \| null | Terminal timestamp; null while `RUNNING` |
| `duration_ms` | int \| null | Wall-clock duration; null while `RUNNING` |
| `node_stats` | array \| null | Per-node stats (rows read/written, timings) |
| `error` | object \| null | The mapped failure (`code`, `message`) on `FAILED` |
| `failed_node_id` | string \| null | The node the failure is attributed to |
| `correlation_id` | uuid \| null | The caller-supplied correlation id |
| `triggered_by` | uuid | The user (or owning execution's user context) that started it |
| `triggered_via` | string | `UI` \| `REST` \| `MCP` \| `PIPELINE` |
| `result_row_count` | int \| null | Rows in the caller result; null for a zero-caller pipeline and for a `direct`-delivered child |
| `result_size_bytes` | int \| null | Size of the materialized caller result |
| `parent_execution_id` | uuid \| null | The execution whose PIPELINE node spawned this one; null for a root ([§10.2](#102-get-execution-metadata)) |
| `parent_node_id` | string \| null | That node's id; null for a root |
| `root_execution_id` | uuid | The family's top ancestor; equals `execution_id` for a root |

Ownership: `admin` reads every execution in the workspace (optionally
pipeline-narrowed); every other principal reads only executions they triggered.

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

Cancels a RUNNING execution: in-flight statements are interrupted (`Statement.cancel()`), connections released, status set to `ABORTED`, and `execution_aborted` (§6.4.8) emitted to any connected stream. Scope: `execute` + ownership (`admin` may cancel any) — [Auth §7.6](auth.md#76-operation-matrix--two-axes-authoritative).

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

### 12.3 When the limiter itself is unavailable

The limiter **fails closed**. If its Redis cannot be reached, the request is refused — `429 Too Many Requests`, `Retry-After: 1`, and the **distinct** code `rate_limit.unavailable` (§13.11), in the standard §4.2 envelope. `/mcp` answers with the same code, because one filter meters both surfaces.

The two codes exist so a client can tell the cases apart: `rate_limit.exceeded` is the caller's own budget and is fixed by slowing down, `rate_limit.unavailable` is the service's dependency and is fixed by the operator. The response body for the unavailable case carries no `limit` — the caller spent nothing — and never names the failing dependency.

Failing open was rejected: it would make "make the limiter's Redis fail" the cheapest way past every limit in the system. There is **no configuration switch** to restore it. The outage is visible to operators on `/health` (the `redis` component the limiter shares — [§11.1](#111-health-check)) and as a single WARN per outage per instance ([Observability §3.2](observability.md#32-levels)).

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
Lists the caller's keys (id, name, `kind`, scopes, created_at, expires_at, last_used_at, is_revoked). Never returns secrets.

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
    "kind": "user",
    "scopes": ["read", "execute"],
    "bindings": [],
    "key": "dpk_ab12cd34ef56.9f8e7d6c...",
    "created_at": "2026-09-08T09:00:00Z",
    "expires_at": "2027-08-07T00:00:00Z"
  }
}
```

`key` is the full plaintext, returned **exactly once** — it is never retrievable again.

**`kind`** ([Auth §7.7](auth.md#77-key-kinds-and-published-endpoint-bindings), [Enums §8A](enums.md#8a-apikeykind--what-an-api-key-is)) is `user` (the default and every key minted before 074), `endpoint`, or `server`. It changes what the rest of the body means:

| `kind` | `scopes` | `bindings` | Who may mint |
|---|---|---|---|
| `user` | Required in effect — absent falls back to the configured default | Refused (`403 endpoint.key_kind_refused`) | Any authenticated principal, at or below its own scopes |
| `endpoint` | **Refused if present** — an endpoint key's authority is its bindings | The endpoint-tree nodes it authorises, validated BEFORE the key is minted | Any authenticated principal |
| `server` | **Refused if present** — a server key's authority is a route family | Refused | `admin` only (`403 auth.scope.insufficient` otherwise) |

An unknown `kind` is `403 endpoint.key_kind_refused`, naming the supported values. A `server` key is the credential a SENDING deployment presents as `DP-Promotion-Key` (§18); it authenticates nothing on this API — presented as `DP-API-Key` it is refused on every route with `403 endpoint.key_kind_refused` and `details.reason = "server_key_off_surface"`.

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

Workspaces are the unit of team isolation ([workspaces design](superpowers/specs/2026-08-16-workspaces-design.md) §9; [Auth §5.6](auth.md#56-workspace-resolution--the-dp-workspace-header) resolves the ACTIVE workspace per request). Every endpoint below lives under `/api/v1`. Scope minimums are in [Auth §7.6](auth.md#76-operation-matrix--two-axes-authoritative); the role/mode gates (owner-or-admin, provisioning mode, `open-join`) are enforced in the service layer, default-deny.

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

## 18. Promotion Endpoints (receiver)

The RECEIVER half of promotion ([Versioning §10](versioning.md#10-promotion-ui-driven-separate-use-case)). Two endpoints, and they are the only routes under `/api/v1/promotion/`.

**Authentication is different here, and deliberately narrower.** These routes are gated by the server key of §10.6, presented as `DP-Promotion-Key`. The header is read on this prefix and no other, so the credential authenticates nothing anywhere else and grants no read access outside this pair. The converse also holds: an ordinary API key or a session cookie does **not** open these routes — promotion is a deployment-to-deployment channel, not a privileged human one.

**How the receiver mints one (091).** On the receiver, an `admin` opens the API screen, creates a key of kind **`server`** (no scope, no bindings — the form hides both for this kind) and copies the plaintext, which is shown once. That value goes into the SENDER's `datapipelines.deployment.promotion.target.server-key`. Equivalently over REST, on the receiver:

```
POST /auth/api-keys   {"name": "uat receiver", "kind": "server"}
→ 201 {"data": {"id": "dpk_…", "kind": "server", "scopes": [], "key": "dpk_….<secret>"}}
```

Rotation is then a receiver-side act with no restart on either side: mint a second `server` key, set it on the sender, revoke the first. The older pre-shared config value (`datapipelines.deployment.promotion.server-key`) is still accepted for one release and WARNs at boot ([Configuration §3.19](configuration.md#319-deployment)).

**Fail closed.** A receiver with no configured value AND no live `server` key refuses every request here. Every refusal on this prefix — missing header, malformed header, wrong key, a key of the wrong KIND, a revoked or expired key, and "nothing configured on this deployment" — is the same `401 auth.promotion.key_invalid` with the same body, so a caller can tell none of them apart.

There is **no MCP twin** for either endpoint and no schedule that calls them ([Versioning §10.1](versioning.md#101-policy) D8: promotion is a human action).

### 18.1 Promotion inventory

```
GET /promotion/inventory?workspace={name}
```

What this deployment already holds in `{name}` — the sender's whole delta input (§10.2) and the datasource set §10.5 pre-validates against, in one round trip.

```json
{
  "schema_version": 1,
  "correlation_id": "...",
  "data": {
    "deployment": "uat",
    "authoring_enabled": false,
    "workspace": "acme",
    "pipelines": [
      { "name": "acme/finance/daily_revenue", "current_version": 3, "body_hash": "sha256-..." }
    ],
    "templates": [
      { "name": "finance/revenue.sql", "current_version": 2, "body_hash": "sha256-..." }
    ],
    "datasources": ["sales_db", "warehouse"]
  }
}
```

`current_version` needs no status filter: by the version lifecycle's invariant it IS the latest RELEASED version (a draft never moves it), so its hash is the hash of what this deployment serves. `authoring_enabled` lets a sender refuse a misconfigured target before building a batch instead of after pushing one.

Workspaces are addressed by **name**, because names are a global namespace and ids are not — a name is the one identifier that means the same thing on both deployments. An unknown name is `404 workspace.not_found`, not the no-oracle `403` an ordinary caller gets (§17): the peer is a trusted deployment, and "you do not have that workspace" is the answer its operator needs.

| Error | HTTP | When |
|---|---|---|
| `auth.promotion.key_invalid` | 401 | Any credential failure on this prefix, including no key configured here |
| `workspace.not_found` | 404 | This deployment has no workspace with that name |

### 18.2 Promotion push

```
POST /promotion/push
```

Apply one batch. **All of it, or none of it** ([§10.4](versioning.md#104-push-order-dependency-closure)): the receiver applies the batch in a single transaction, so a mid-batch failure cannot leave pipelines whose template pins or child pipelines do not resolve.

```json
{
  "source_env": "dev",
  "key_fingerprint": "sha256:1a2b3c4d5e6f",
  "workspace": "acme",
  "templates": [ { "id": "finance/revenue.sql", "version": 2, "body_hash": "...", "body": "...", "...": "..." } ],
  "pipelines": [ { "id": "…uuid…", "version": 4, "body_hash": "...", "name": "acme/finance/daily_revenue", "nodes": [] } ]
}
```

`templates` and `pipelines` arrive **in push order** — template versions in the transitive `imports_json` closure first, then child pipelines, then their parents (children before parents) — and are applied in the order given. The receiver does not re-derive the closure: the sender owns that rule (§10.4), and a second implementation of it here would be a second thing to keep correct. Entries already present at the same version and hash are omitted by the sender and are an idempotent no-op if sent anyway.

Each entry is the ordinary portable body plus the [§9.2](versioning.md#92-import-with-preserved-versions) lifecycle fields (`version`, `body_hash`, `released_at`), so the receiver's preserved-version import path applies unchanged: version numbers are honored, never renumbered; `body_hash` is recomputed and must match; a local DRAFT is never clobbered; a same-hash re-import is a no-op.

`source_env` is the sender's `deployment.name` and `key_fingerprint` a truncated SHA-256 of the key it presented — **never the key**. Both are recorded by the receiver against the import (`auth.promotion.accepted`), so a promoted row's provenance survives in the audit trail. Every promoted row is stamped with the receiver's own system service account ([Auth §4.5](auth.md#45-the-system-service-account-r7)) — the payload carries no user id that would mean anything locally.

```json
{
  "schema_version": 1,
  "correlation_id": "...",
  "data": { "workspace": "acme", "source_env": "dev", "templates": 1, "pipelines": 2 }
}
```

| Error | HTTP | When |
|---|---|---|
| `auth.promotion.key_invalid` | 401 | Any credential failure on this prefix |
| `workspace.not_found` | 404 | This deployment has no workspace with that name |
| `pipeline.promotion.target_is_authoring` | 409 | This deployment has `authoring-enabled: true` — dev is where drafts live (§10.1 D7) |
| `pipeline.import.missing_datasource` | 400 | A pushed pipeline references a datasource not registered here. The sender pre-validates (§10.5) and refuses with `pipeline.promotion.missing_datasources` before pushing; this is the receiver's own end of the same guard |
| `pipeline.import.missing_template` | 400 | A pushed pipeline pins a template version not present here and not in the batch |
| `pipeline.import.hash_mismatch` | 400 | A declared `body_hash` does not match its body — transfer corruption or canonicalization drift |
| `pipeline.import.version_conflict` | 409 | That version exists here with different content, as a local DRAFT, or was DISCARDED (§9.2's table) |

The whole batch rolls back on any of the above. The sender's own refusals — `pipeline.promotion.not_released`, `.not_newer`, `.missing_datasources`, `.target_is_authoring` — happen before the request is made and never reach this endpoint.

---

## 19. Published Endpoints

A released pipeline served as a **`GET` API** under `/api/x`, whose response is the `data_ready`
payload — the first page plus the cursor — with no event handling on the client
(this section is the contract). Ruling R-EP1: engineers own
everything beneath `/api/x`; the product's own routes stay under `/api/v1`, and a published path
can never shadow one. There is **one** handler for the whole subtree and no runtime route
registration: a published endpoint is a row, not a mapping.

### 19.1 The path grammar

`path_pattern` is 1–10 segments, each a literal `[a-z0-9][a-z0-9_.-]{0,63}` or a variable
`{name}` matching the parameter grammar `[a-z_][a-z0-9_]*`; at most 200 characters; a leading
`/`, no trailing slash, no wildcards. It is the same segment grammar hierarchical template names
use ([Template hierarchy §4](template-hierarchy-design.md)) — one grammar to learn.

**Ambiguity is refused, not resolved.** `/a/{x}` and `/a/b` both match `GET /api/x/a/b`, so
publishing the second is `409 endpoint.path_conflict` naming the first. Literal-beats-variable
precedence is deliberately NOT offered in v1: because ambiguity cannot be published, at most one
pattern can match a request, and a reader of the registry can tell what a URL does by finding the
one row it matches. `UNIQUE (path_pattern)` is deployment-wide — a URL is global.

### 19.2 What may be published

The pipeline must exist in the endpoint's workspace and have a **released** version, and it must
be **side-effect-free**: every node of the current released version is `DQL` with `output.target`
in {`tempdb`, `caller`}, or a `PIPELINE` node whose pinned child satisfies the same rule
transitively. A `DML`/`DDL` node — or a DQL node writing back to a datasource, which is a write
wearing a read's type — refuses publication with `409 endpoint.pipeline_not_readonly` naming the
node.

That rule is what makes `GET` safe here, and it is not a formality: `GET` is retried on timeout,
preloaded by browsers and followed by crawlers. It is **re-checked on every serve**, because an
endpoint pins a pipeline and serves its latest released version — a later release can put a write
under a live URL.

Every path variable must name a declared parameter of that version
(`400 endpoint.path_variable_unknown`); the pipeline may declare more, and they come from the
query string. `timeout_seconds` is clamped to `datapipelines.endpoints.timeout-min-seconds` /
`datapipelines.endpoints.timeout-max-seconds` ([Configuration §3.22](configuration.md#322-published-endpoints)).

### 19.3 The request

Authentication is an API key (`DP-API-Key`), never a browser session — this is a machine surface,
and a session is `401`. Authorisation is the key's hierarchical bindings
([Auth §7.7](auth.md#77-key-kinds-and-published-endpoint-bindings)).

The accepted parameters are the released version's declared ones: **path variables** bind by
name, and the **query string** supplies the rest. Validation is strict and **reports every defect
at once** — one `400 endpoint.request.invalid` whose `details.errors[]` carries
`{parameter, code, message}` per problem, because a client fixes a request once:

- an unknown query parameter is `endpoint.request.parameter_unknown` — deliberately not ignored,
  since a typo that silently ran the default would return plausible, wrong rows;
- a repeated key is `endpoint.request.parameter_repeated`, as is a query key that also came from
  the path (the URL decides, not the query string);
- a required parameter with no value is `pipeline.execution.parameter_required`, and a value that
  is not its declared type is `pipeline.execution.invalid_parameter_type` — the existing codes;
- a value over 4 KB is `endpoint.request.value_too_large`.

Headers: `DP-Result-TTL-Seconds` (§7.4's clamp) and `DP-Result-Page-Rows` (R-EP4, clamped to
`page-max-rows`) are honoured; an unparseable value reads as absent, because the server clamps
anyway. `Accept` must admit `application/json` (`*/*` and an absent header do), else `406`.

### 19.4 The response

`200` — the body is the `data_ready` payload of [§6.4.7](#647-data_ready), verbatim:
`execution_id`, `schema`, `rows`, `row_count`, `total_rows`, `has_more`, `result_url`,
`expires_at`, `ttl_seconds`. Headers: `DP-Correlation-Id`, `DP-Execution-Id`, and
`Cache-Control: no-store` — a result is per-execution, so a shared cache holding one would hand a
second caller another caller's rows.

The execution runs **in-process** through the same recording path an MCP execution uses — never
HTTP-to-self, never SSE parsing — as the endpoint's workspace, with `triggered_via = ENDPOINT`
and `triggered_by` = the key's owner. Every serve is audited with the key id, the endpoint, the
execution id and the outcome.

**`202` on timeout (ruling R-EP3).** When the endpoint's `timeout_seconds` elapses the execution
is **not** cancelled — it keeps running, and the response is `202` with
`{execution_id, result_url, status_url, expires_at}`. The cursor `404`s until the result exists
and `GET /executions/{id}` reports status. A `504` would be a lie in both directions: the upstream
is fine, and the answer is coming.

### 19.5 Managing endpoints

`POST` / `GET` / `DELETE /api/v1/endpoints` (`author`), addressed by `?path=` — never by a path
segment, because a `path_pattern` contains `/` and an encoded `%2F` is refused below routing on
the pinned Tomcat (the measured reason §8 moved templates to query addressing).

Bindings are `POST` / `DELETE /api/v1/endpoints/bindings`, naming the key by NAME and requiring
the key's **owner**. Binding another user's key is not offered in v1: `api_keys.name` carries no
uniqueness constraint, so "the key named `ci`" is ambiguous deployment-wide and a surface that
resolved it would pick one person's credential to widen. Promotion carries endpoint rows and their bindings **by key name**; a target missing
that key name refuses the batch with `endpoint.promotion.key_missing` before anything is pushed.
The same operations exist as MCP tools ([MCP Server §6.2](mcp-server.md#62-tool-definitions)).

### 19.6 Status codes, complete

| Code | When |
|---|---|
| 200 | Result inline — the first page plus the cursor |
| 202 | The endpoint's timeout elapsed; the execution continues (R-EP3) |
| 400 | Request validation — every defect at once in `details.errors[]` |
| 401 | No or invalid API key; a browser session |
| 403 | Key not bound / wrong workspace / wrong key kind |
| 404 | No endpoint matches. **The same body whether the path is unknown or the endpoint is disabled** — otherwise the registry is enumerable one URL at a time |
| 405 | Any method but `GET`, with `Allow: GET` |
| 406 | An `Accept` this surface cannot satisfy |
| 429 | Rate limit or concurrency limit, with `Retry-After` |
| 500 / 502 | Pipeline failure, mapped by [Pipeline Contract §13](pipeline-contract.md#13-error-code-catalog) (datasource connection failures are `502`) |
| 503 | The pipeline has no released version, or is no longer side-effect-free |

### 19.7 Not in v1

`POST` endpoints (parameters in a body, side-effecting pipelines — the read-only rule is what
makes `GET` safe, and a write surface needs its own ruling); anonymous or embed tokens for public
dashboards; per-endpoint rate limits; response caching across callers (results are per execution
by design); CSV/Arrow by `Accept` (the cursor's `format` already serves them); custom domains.

---

## Appendix A: Change Log

| Date | Version | Author | Change |
|---|---|---|---|
| 2026-09-08 | v2.8 | 099 draft-first (D55/D56) | **§5.1** — `POST /pipelines` lands v1 as a **DRAFT**: `status: "DRAFT"`, `current_version: null`, the `draft` pointer, and release is `POST …/release` like any other draft ([Versioning §3.2](versioning.md)). **New §6.1.1** — execute with no `version` runs the WORKING version (the draft when one exists, else the latest release); an explicit version stays exact and never clamped. **§5.7** — listing rows carry the working `version` plus a new `status` field. **§5.9** — export is released-only and refuses a never-released pipeline with `409 pipeline.promotion.not_released`. §8.1 (templates) mirrors §5.1. Response VALUES change; no request shape and no route does. |
| 2026-09-02 | v1.18 | 051 auth/config sweep | §10.1 gains its field table (T19): the listing's items were documented only by cross-reference to §10.2. The table is the shared metadata projection minus `result_url`/`result_expires_at`, including the fields §10.2's example omitted (`draft_run`, `error`, `failed_node_id`, `correlation_id`), plus the ownership sentence |
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
| 2026-09-02 | v2.2 | 055 promotion | New **§18 promotion endpoints** (receiver): `GET /promotion/inventory?workspace=` and `POST /promotion/push`, additive under v2.0. Authenticated ONLY by the §10.6 pre-shared server key on the `DP-Promotion-Key` header (§3.6's registry gains it) — read on that prefix and no other, and an API key or session cookie does not open the routes. The push is one transaction on the receiver; entries carry §9.2's preserved-version fields and arrive in §10.4 push order. No MCP twin and no schedule (§10.1 D8). |
| 2026-09-02 | v2.1 | 046 typed templates | Additive: §8.1's create gains optional `type` (`sql` \| `html`, default `sql`, fixed at creation — an `html` template takes no `dialect`); every template response echoes it; §8.5's list gains the `type` filter. No route changes. Per Templates §11.2's amended clause, the `dialect` conditional-requirement relaxation is claimed here explicitly: no existing payload becomes invalid (every stored template backfills to `sql` with its dialect intact). |
| 2026-09-02 | v2.0 | 043 template addressing | **BREAKING — one addressing form (template-hierarchy-design §9.6).** §8's eight `/{id}` path-addressed template routes are REMOVED and replaced by name-in-query/name-in-body forms (`GET /templates?name=`, `GET /templates/versions?name=&version=`, `PUT /templates` with `id` in the body, `POST /templates/release` + `POST /templates/draft/discard` with `name` in the body, `DELETE /templates?name=`, `POST /templates/render` with `name`+`version` in the body). Measured reason: on the pinned Tomcat an encoded `%2F` in the path is refused `400` below routing and below the security chain, so a hierarchical name (`acme/finance/report`, legal since this round's §4.1 grammar) cannot travel in a path segment at all. `GET /templates` now answers two shapes on one route (single-resource + `404 template.not_found` with `name`, paged list without). Sanctioned break of the v1.4 freeze: the owner confirmed zero callers outside this repo (2026-09-01), so the promise was protecting a population of zero. Datasources and pipelines keep path addressing — their names cannot contain `/`. |
| 2026-09-05 | v2.2 | 067 pipeline folders | Additive and route-free: §5.1 records that a pipeline `name` is now a **folder path** (the template grammar, [Pipeline Contract §3.2](pipeline-contract.md#32-field-reference)) — every pre-067 name is still valid as a one-segment path, so no request shape changes and no client breaks. §5.7 records that `q` matches across full paths, and that folder BROWSING deliberately stays off REST: it is served by `GET /partials/pipelines?prefix=…` and by MCP `pipelines_list {prefix}`. **No route changes**, because a pipeline is UUID-addressed — the `%2F` problem that forced v2.0 for templates cannot arise here. |
| 2026-09-05 | v2.3 | 074 published endpoints | New **§19**: a released, side-effect-free pipeline served as `GET /api/x/…`, answering the `data_ready` payload verbatim. One catch-all handler over a registry — never runtime route registration (R-EP1). Ambiguous paths are refused at publish (`endpoint.path_conflict`) rather than resolved by precedence, so at request time at most one pattern matches. Validation reports every defect at once and is strict about unknown query parameters. `202` on timeout with the execution still running (R-EP3), never `504`. §3.6's registry gains `DP-Result-Page-Rows` (R-EP4 — one contract, honoured by §6's execute too) and the `DP-Execution-Id` response header. Management is `/api/v1/endpoints`, addressed by `?path=` for the same measured reason §8's templates are. |
| 2026-09-05 | v2.4 | 077 mandatory folders | §5.1: a pipeline `name` needs a **folder** — 2–10 segments, not 1–10 ([Pipeline Contract §3.2](pipeline-contract.md#32-field-reference)). `POST /api/v1/pipelines` and `POST /api/v1/templates` answer `400` with the existing codes (`pipeline.validation.name_invalid`, `template.validation.id_invalid`) for a flat name, and `details.reason` now separates `folder_required` from `grammar`. **A narrowing, not an addition** — the one kind of change §11 forbids after the freeze — taken pre-release on the owner ruling of 2026-09-05: with no rename (Template Hierarchy §4.5), a root-level name created after the tag is permanent, and the root would accrete scratch with no way to tidy it. No route changes. |
| 2026-09-07 | v2.5 | 087 connector seams | §9.1 gains the `credential` object ([Datasources §3.4](datasources.md#34-credential-kinds)) — `{kind, username?, secret?}`; the legacy top-level `username`/`password` pair still works and means `kind: password`, and a body carrying both is `400 datasource.validation.properties_invalid`. §9.3's response gains `credential: {kind, username?}` and derives `password_set` from the kind (`false` for `none`); top-level `username` is now nullable. §9.4: `credential.kind` is part of the body — moving to `none` clears the stored credential. §9.7's `/tables` and `/tables/{table}/columns` accept `?namespace=` (repeated or dotted) beside `?schema=`, and every table row gains a `namespace` array beside `schema`; `/schemas` gains `entries: [{namespace, label}]` beside the legacy `schemas`. All additive. |
| 2026-09-08 | v2.6 | 089 dp-lake registry (recorded with the §G corrections) | New **§9.8 Lake tables (the dp-lake catalog)** — `POST /datasources/{name}/tables`, `DELETE …/tables/{namespace}/{table}`, `POST …/tables/import` (inline `tables[]`, or a `manifest_url` fetched server-side and restricted to the datasource's own bucket/endpoint — the SSRF boundary), and `GET …/lake-tables` (`read`). The writes are `author`, with a GLOBAL datasource's registry admin-only as a workspaces D8 rule; every write evicts the pool and publishes the §5.7 invalidation. Two corrections landed with this row: the Iceberg `location` is the table's current metadata FILE, not its root (the measured rule, datasources.md §8C.7), and §9.7's introspection listing is registry-backed for LAKE as shipped (datasources.md §8C.3) — §9.8's "a later phase" sentence was written before 089 §C landed. All additive. |
| 2026-09-08 | v2.8 | 101 version lifecycle | §5.11 is the draft **purge** (row + executions deleted; sole-draft ⇒ the entity goes); new §5.12–§5.15 — `POST /pipelines/{id}/versions/{v}/discard|restore`, `DELETE /pipelines/{id}/versions/{v}` (drafts only), `POST /pipelines/{id}/current` (the manual switch) — and §5.6 becomes the **entity purge** (`include_exclusive_draft_templates`, response carrying the offered set). All session-only (`403 auth.session.required` for keys) and audited (enums.md §15's lifecycle table). §8.10/§8.11/§8.6 mirror for templates by name. The sticky `current_version` (D60) moves only on release / discard-of-current / restore-above-current / switch / purge-of-current-draft; imports never move an existing pointer ([Versioning §3.4](versioning.md#34-current_version-is-sticky-and-event-driven-d60)). |
| 2026-09-08 | v2.7 | 094 pool settings and retirement | §9.3's response gains **`pool`** (additive): every tunable HikariCP key's effective value, unit and source (`configured` / `dialect_default` / `application_default` / `hikari_default`) — what the pool RUNS with, beside the `properties.hikari` map of what the row STORES. §9.1/§9.4: out-of-range pool values are now REFUSED (`400 datasource.validation.properties_invalid`) instead of being silently rewritten by HikariCP ([Datasources §5](datasources.md#5-connection-pool-configuration)). §9.4/§9.5: an update or delete RETIRES the pool rather than closing it — new leases miss it at once, statements already running finish on the connection they hold, and a per-instance reaper closes it when drained or at the configured ceiling. All additive; no request shape changed. |
