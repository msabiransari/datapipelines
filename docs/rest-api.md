# REST API + SSE Specification

**Status:** v2.37 (frozen contract — additive-only changes after this point; see the 2026-09-20, 2026-09-24 and 2026-09-26 (v2.36, v2.37) rows for the deliberate breaks)
**Owner:** datapipelines.co core
**Depends on:** [Type System spec](type-system.md), [Pipeline Contract spec](pipeline-contract.md), [Auth spec](auth.md)
**Last updated:** 2026-09-26

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
- **API key** — for programmatic clients, in the header `DP-API-Key: dpk_...`: an `endpoint` key or a `server` key, each confined to its own routes (below). The MCP key is refused here.

The permission every operation declares is defined once in the [Auth §7.6 permission catalog](auth.md#76-operation-matrix--the-permission-catalog-authoritative); what a principal holds is a ROLE (scopes were removed, #215). **This REST API is a SESSION's surface, plus two confined key kinds:** an `endpoint` key on the published tree and its own executions, a `server` key on the promotion routes (§18). The MCP key connects an MCP client to `/mcp` and nothing else — on every route here it is refused with `403 endpoint.key_kind_refused`, `details.reason = "user_key_off_surface"` (B2, [Auth §7.7](auth.md#77-key-kinds-and-published-endpoint-bindings)). Key management is §16.

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
| 2026-09-25 | v2.34 | 233 landing (#233) the limiter's principal | §12.1: limits are per PRINCIPAL — since keys v2 (A13) an mcp key is its own robot identity, so its request budget is its own, not its creator's (the pre-v2 sentence "an API key draws from its owner's budget, so minting more keys does not raise any limit" stopped being true with V37); `PerUserRateLimitE2eTest` (232) now proves the separation on the wire. No field or code changed. |
| 2026-09-25 | v2.33 | keys v2 (#233) | **§16.1 rewritten**: the ONE creation path for every kind — `kind` REQUIRED (`400 auth.key_kind_not_mintable` with no default kind, A15), `mcp` (renamed from `user`, A19) offered with a CHOSEN role under the subset rule (A14; `mcp_key.create` + `mcp_key.revoke_own`), a live name conflict answered `409 auth.key_name_taken` (A18), `GET /auth/api-keys/mine` gone with the login mint. **§19.3**: the result paging documented under the business path (`GET /api/<cat>/v<n>/<path>/executions/{id}[/result]`, keys-v2 A16) — a session refused there as on the serve route. |
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
    "doc_url": "https://datapipelines.co/docs/pipeline-contract#131-pipeline-validation-write-time"
  }
}
```

- `code` — error code from the [Pipeline Contract §13 catalog](pipeline-contract.md#13-error-code-catalog). Always lowercase, dot-separated.
- `message` — technical message for developers. English. Includes specifics.
- `user_message` — non-technical message safe to display to end users. May be localized in future.
- `details` — structured, code-specific. Each error code documents its `details` shape.
- `doc_url` — link to the public docs: the [Pipeline Contract §13 catalog](pipeline-contract.md#13-error-code-catalog) at the anchor of the section listing the code's family (there is no per-code page).

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

**The promoter lens (178).** Every read in this section — §5.2, §5.3, §5.4, §5.7 (both shapes),
§5.9 and the checks read — answers a **promoter** (session or key) through the lens ([Auth §11A.1](auth.md#11a1-the-404-rule)):
only pipelines that are RELEASED and newer than the promotion target's inventory entry
([Versioning §10.2](versioning.md#102-the-listing-rule-what-the-ui-shows)) exist for it. A hidden
pipeline is `404 pipeline.execution.not_found` on every by-id read, exactly as an id from another
workspace is — and so is a DRAFT version of a visible one, on §5.3's version read and on the
version's checks read (178b: a status is never probeable by number); the listings, the folder
level and its `total` and `pipeline_count` are the lensed set. While the promotion target cannot be read the lens fails closed — empty listings, 404s —
never a `502` on a read. Every other role reads as before.

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
inputs** — one entry per key a CALCULATOR node writes (121: every mapped `context_keys`
value of a multi-output kind too),
`{"type": <the key's output wire type, or "ANY">, "required": false, "derived": true}` — because a
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
[?override_checks_reason=<text — only when a check is failing>]
[?release_pinned_templates=true — consent to release the DRAFT template versions the body pins]
```

Locks the draft: the version flips to RELEASED, `pipelines.current_version` moves to it,
and the index row's name/display_name/description adopt the released body's values
(metadata rides the release — versioning §3.5). Preconditions evaluated server-side:
§12 re-validation on the draft body; every pinned template version RELEASED
(`409 pipeline.release.template_not_released` naming it); the hash guard
(`409 pipeline.version.conflict`); no draft at all (`409 pipeline.version.not_draft`);
and — when the body carries [`checks[]`](pipeline-contract.md#33-release-checks-checks) —
**the release-check gate** (versioning §5.3 precondition 4): a fresh check run whose every
verdict must be `pass`, else `409 pipeline.check.failed` with `details.checks` (expected,
observed and message per failing check). Releasing past a failing check needs
`override_checks_reason`, a non-blank reason of ≥ 10 characters, and is recorded:
`pipeline.version.released` gains `checks_overridden: [ids]` and `override_reason`. A
version with no checks releases exactly as before.
**The cascade (142, versioning §5.3):** without `release_pinned_templates` a DRAFT template pin
refuses `409 pipeline.release.template_not_released` exactly as before — `details.template_id`
/ `template_version` / `template_status` name the first offending pin, and
`details.pins_not_released` lists EVERY pin that is not RELEASED, so a client can decide whether
retrying with the flag would succeed (all DRAFT) or not (a DISCARDED or MISSING pin, which is
never releasable). With `release_pinned_templates=true` every DRAFT pin is released at its
pinned version in the SAME transaction as the pipeline flip, templates first; any refusal
(a template's own preconditions, a stale `If-Match`, a concurrent write) rolls the whole
transaction back. Each cascaded release is audited as `template.version.released` with
`cascade_from_pipeline_id` / `cascade_from_version`, and the pipeline's event carries
`templates_released: [{template_id, version}]` (empty when nothing cascaded). The flag rides the
query like `override_checks_reason`, for the same reason: the endpoint has no body. The same
role holds both release verbs (auth §7.6), so the flag grants no new capability.
UI-driven in practice — agents never release (versioning D4); no MCP tool exists. Over REST
the verb needs `pipeline.release` — an author's or workspace admin's session (release is the
author's since 2026-09-20, D8); no key reaches it (the MCP key is confined to `/mcp` since #215
B2). **Audited** as `pipeline.version.released` with the version and `via` (`session` /
`api_key`) — the record of who made the D4 decision and through what (T187).

Response: `200 OK` with the released version's full shape (`status: "RELEASED"`) **plus
`warnings`** (7e, [transform-nodes design](superpowers/specs/2026-09-09-transform-nodes-design.md) §8.2) —
ALWAYS present, `[]` on a clean release, one `{code, message, template, version}` per pinned
template version that reads `needs_review` (it cites a retired learned fact, [Templates §3.4](templates.md#34-implements-and-drift)):

```json
"warnings": [
  { "code": "pipeline.release.template_needs_review",
    "message": "Template 'nyc/weather/rainy_days.jsonata' v2 cites a retired fact: 1b2e… — superseded by 7c4a…. Released anyway — re-cite the successor (or drop the citation) with templates_update.",
    "template": "nyc/weather/rainy_days.jsonata", "version": 2 }
]
```

A warning never refuses and never changes the status: the release happened, the fact edit is
the releaser's to know about ([Pipeline Contract §13.13](pipeline-contract.md#1313-versioning--draft-release-lifecycle--promotion)
catalogues the code with HTTP `—`). The release dialog shows the same facts on its pin rows.

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

### 5.16 Release checks (140)

```
POST /pipelines/{id}/versions/{version}/checks/run
GET  /pipelines/{id}/versions/{version}/checks
```

A pipeline body may carry [`checks[]`](pipeline-contract.md#33-release-checks-checks) —
server-run cross-checks that gate §5.10's release. The agent writes the query and the
expectation; **only the server's own run produces `observed`** — there is no endpoint,
and no tool, that records an observed value from a caller.

**Run** (`pipeline.run_checks` — it writes `pipeline_check_runs` rows, like execute writes
executions): runs the version's checks NOW against their datasources, bound with the
pipeline's declared parameters (defaults filled the way §6's execute fills them — the
calculator context is NOT available to a check), through the same bounded probe path as
`sql_probe` (`via = rest`). An optional `parameters` JSON body overrides the defaults for
this run. Response `200 OK`:

```json
{ "version": 3,
  "runs": [ { "check_id": "manhattan_share_reconciles", "name": "Manhattan rideshare share, Q4 2024",
              "expected": { "kind": "value", "value": 74.62, "tolerance": 0.01 },
              "observed": "74.62", "verdict": "pass", "message": null, "ran_at": "…" } ] }
```

`verdict` is `pass` | `fail` | `error` (enums.md §20): `error` means no verdict could be
formed — the datasource was unreachable, the statement was refused, or it returned a shape
the expectation cannot compare (a `value`/`range` check requires exactly one row and one
column) — with the reason in `message`. A version with no checks answers `200` with an
empty `runs` list.

**Latest** (`pipeline.read`): the version's check definitions each with their LATEST run
(latest per `(version, check_id)` of the append-only `pipeline_check_runs`), or `null`
runs when none was ever recorded. This is what the UI's release dialog and version page
read.

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
    "doc_url": "https://datapipelines.co/docs/pipeline-contract#134-node-execution",
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

#### 6.4.9 `node_progress`

A **measured sample of a node's operation** (149): what the node is doing, where its output goes, the state it is in at the observation instant, its cumulative counts and the wall time spent in each state so far. Zero or more per node, strictly between that node's `node_started` and its `node_completed`/`node_failed` (or the execution's terminal event when the node ends by cancellation). Never terminal on the wire; `event_id` continues to count it like any other event. Additive: a client on the eight-event vocabulary sees exactly the events it always did, in the same order, with these interleaved.

```json
{
  "execution_id": "exec-uuid",
  "node_id": "fetch_orders",
  "attempt": 1,
  "sequence": 3,
  "operation": "stage",
  "destination": {"kind": "tempdb", "table": "orders"},
  "state": "writing",
  "started_at": "2026-08-05T14:30:00.234Z",
  "observed_at": "2026-08-05T14:30:05.535Z",
  "elapsed_ms": 5301,
  "timings_ms": {"connecting": 12, "executing": 410, "fetching": 3400, "waiting_output": 22, "writing": 1457},
  "rows_fetched": 12000,
  "rows_written": 11000,
  "batches_written": 11,
  "correlation_id": "corr-uuid"
}
```

| Field | Meaning |
|---|---|
| `sequence` | Ordinal of this sample within the operation — strictly increasing per (`execution_id`, `node_id`, `attempt`). Repeated batches and statements are distinguished by `sequence` and `batches_written`, never by a second destination. |
| `operation` | `stage` (source cursor → tempdb table), `ctas` (tempdb `CREATE TABLE … AS` — query and materialisation are ONE indivisible statement), `materialize` (caller result → the result store, or `direct` to an invoking PIPELINE node), `writeback` (cursor → external datasource table, one transaction), `statement` (DML/DDL), `child` (a PIPELINE node: the child execution, then the parent's own output write). CALCULATOR nodes have no operation and emit no samples. |
| `destination` | The REAL output destination: `{"kind":"tempdb","table":…}`, `{"kind":"datasource","datasource":…,"table":…}` (`table` absent on a DML/DDL statement — no SQL lineage is inferred), `{"kind":"caller"}`, `{"kind":"parent"}` (a child's caller rows streamed to the invoking node), `{"kind":"none"}` (DDL, or a node that writes nothing). Identifiers only — never a connection string ([Observability §9.3](observability.md)). |
| `state` | `connecting` (a SOURCE connection is being obtained), `executing` (the statement is submitted and has not returned — a returned cursor is not a fetched row), `fetching` (advancing the source cursor / decoding rows), `waiting_output` (an OUTPUT connection was requested and is not yet held), `writing` (inside a destination write, holding its connection), `finalizing` (commit, row count, budget check, result meta), then exactly one terminal `completed` \| `failed` \| `aborted`. |
| `started_at`, `observed_at`, `elapsed_ms` | Operation start; this sample's ORIGINAL observation instant (replay preserves it); the honest wall time between them. |
| `timings_ms` | Cumulative measured wall time per state actually observed, the open state counted up to `observed_at`. A state never entered is absent — not zero. Wall time at the executor's boundary, never engine CPU or exclusive network time. |
| `rows_fetched` | Rows read off the source cursor (or received from the child) so far. ABSENT when not observed (`ctas`, `statement`). |
| `rows_written` | Rows ACCEPTED by the destination so far — a tempdb batch inserted, a write-back batch executed, a result-store page pushed, a DML update count. Absent when not observed (DDL; a CTAS until its count). Accepted is not committed: see `committed`. |
| `batches_written` | Destination batches executed so far. |
| `committed` | **Terminal samples only**, and only for a destination that has something to commit. It is COMMIT EVIDENCE, independent of the node's outcome: `true` when the writer confirmed the write durable (write-back after `commit()` returned, tempdb after the last batch and the budget check, the result store after its meta key, DML after autocommit) — **also on a `failed`/`aborted` sample**, because a node can fail AFTER its write became durable (a connection close that throws once `commit()` returned; a cancellation landing between the commit and the node's completion) and a failed node is not evidence that its side effects were undone; `false` when the write was confirmed undone (`rolled_back` is then `true`: a write-back transaction rolled back after a failure that came BEFORE the commit attempt, a failed stage's partial table dropped). **Absent on a terminal sample when neither was observed** — a driver that never returned from `commit()` before the node's deadline, a cancellation before the commit whose implicit rollback nobody witnessed, and **any failure thrown by `commit()` itself**: the transaction may be durable with only its acknowledgement lost, a `rollback()` that succeeds afterwards undoes nothing, so the writer reports neither — the outcome is unknown, and clients must show it as such ("commit not observed"), never as "not committed". Absent while running — there is no "committed" before the commit — and absent for DDL or a child with no output. **Limit:** an abandoned driver body (§6.4.6 timeouts) may still commit AFTER the terminal sample; the sealed operation publishes nothing about it, so an unknown outcome on a timed-out write means exactly that — check the target. |
| `rolled_back` | Present (`true`) only when a failed write-back transaction was rolled back or a failed stage's partial table was dropped. |
| `child_execution_id` | `child` operations, once the child's id is minted. |

There is no percentage: no operation knows its total. A slow source query is `executing` (or `fetching`) time, a full tempdb pool is `waiting_output` time, a slow destination is `writing` time — the split is measured, not inferred. The measurement limits are stated in [DAG Executor §10](dag-executor.md#10-sse-event-integration): the source's server-side time is inside `executing` until the driver returns the first result, a driver that pre-buffers (`source-fetch-size: 0`, MySQL without streaming) moves source time into `executing`, and `fetching` for `materialize` includes JSON encoding.

**Cadence.** A sample is emitted at each FIRST entry into a state (taken at the entry instant, so a `writing` interval shorter than the executor's 250 ms pump tick is still seen), then at most once per `datapipelines.executor.progress-sample-interval-seconds` (default 1) while something changed, then exactly one terminal sample. Per operation that is at most `states + duration / interval + 1` events — never per row, never per batch. Every sample is persisted like every other event (§10.3).

### 6.5 Event ordering guarantee

Within a single execution stream, events are ordered:
1. Exactly one `execution_started` (first).
2. For each node: zero or one `node_started` → zero or more `node_progress` (§6.4.9) → zero or one of (`node_completed` | `node_failed`). A node's terminal `node_progress` sample (`completed`/`failed`/`aborted`) precedes its `node_completed`/`node_failed`; no `node_progress` for a node follows the node's terminal event, and none follows the execution's.
3. Exactly one terminal sequence: (`pipeline_completed` [→ `data_ready` if a caller node exists]) | `pipeline_failed` | `execution_aborted`.
4. Stream closes after the terminal event.

For parallel nodes, events are emitted in real-time as they occur (interleaved). Order between parallel nodes is non-deterministic — on the live stream and, independently, in the §10.3 replay, whose order between parallel nodes is the persistence order; a node's own events keep their order in both.

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

**A revoked subscriber's stream is cut; the execution is not** (#230, security-assurance P4). Before every write — event or heartbeat — an open stream re-judges its subscriber's authority to read the execution, with the same predicate a new request would run ([Auth §11.4](auth.md#114-api-key-validation-cache)); when the answer turns no, the stream ends at that write, carrying a final `revoked` SSE comment and nothing after, while the execution itself keeps running to its terminal state.

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

Auth: the `execution.result.read` row (D11, 177): the caller's OWN execution — `executed_by = self` (an `endpoint` key's identity owns the runs it started, #215 A.5; a person does not own a run a key they created started) — or any execution with `execution.read_all` (a workspace admin's); a promoter is refused by role, and another member's execution is `404 result.execution_not_found`, never 403. The URL is not a capability — an unauthenticated request 401s ([Auth §7.6](auth.md#76-operation-matrix--the-permission-catalog-authoritative)).

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

**The promoter lens (178)** applies to every read here as it does in §5: a **promoter** sees only
templates with a current RELEASED version newer than the promotion target's ([Auth §11A.1](auth.md#11a1-the-404-rule),
[Versioning §10.2](versioning.md#102-the-listing-rule-what-the-ui-shows) — the template arm); a hidden
template is `404 template.not_found` by name (§8.2, §8.3), and absent from §8.5's page, level,
`total` and `template_count`. Fail closed while the target is unreadable; every other role unchanged.

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

`type` is chosen here and never changes afterwards: `sql` (the default) requires `dialect` and is what pipeline nodes reference; `html` declares no `dialect`, renders through an auto-escaping engine configuration, and cannot be referenced by a pipeline node (templates.md §3.2, 046); `jsonata` and `javascript` are the transform types (7b) — `engine: "none"`, no `dialect`, no `imports`, `is_library: false`, and the three blocks `contract` / `invariants` / `tests` are required on create and update (templates.md §3.3; `javascript` is refused until round two).

A transform body may carry **`implements`** (7e, [Templates §3.4](templates.md#34-implements-and-drift)): an array of learned-fact ids — WORKSPACE `definition`/`exclusion`/`preference` facts of this workspace, at most 50 — the rules this transform implements. Not content: outside `body_hash`. An id the workspace cannot cite is `400 template.implements_unresolved` (`details.fact_id`, `details.reason`); on `sql`/`html` the field is `400 template.blocks_not_allowed`.

Response: `201 Created` with full template (including `type`, version `1`, `created_at`, and on a transform `implements` / `needs_review`).

### 8.2 Get template (working version)

```
GET /templates?name={path}
```

Response carries the **working version's** projection (versioning §7, since 039 — the
template mirror of §5.2): the DRAFT when one exists, else the latest released version. The
projection states its `version`, `status` and `body_hash`, and a `draft` pointer is present
when a draft exists. Every template projection here and in §8.3/§8.5 carries **`needs_review`**
(7e — `true` when a cited learned fact has been retired, computed on read, never stored) and,
on a transform, **`implements`** (its cited fact ids, sorted) and — when marked —
**`retired_facts`** (`[{fact_id, retired_reason, superseded_by}]`) ([Templates §3.4](templates.md#34-implements-and-drift)).
The pipeline editor's TRANSFORM card reads `needs_review` off §8.3's read.

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
  "dialect": "POSTGRES",            // OPTIONAL since 136 §D (T289a): omitted, the working version's is inherited; a DIFFERENT one is refused 400 template.validation.dialect_invalid, details naming dialect / established_dialect / template_id — a template's dialect is fixed by the version you edit (135 §C's MCP rule, on REST)
  "type": "sql",                    // OPTIONAL too: omitted, the working version's is inherited; a different one is template.validation.type_immutable
  "display_name": "Fetch Orders in Date Range",   // required (templates.md §3.2)
  "description": "...",
  "imports": [{"id": "acme/lib/date_filters.sql", "version": 2, "alias": "dates"}],
  "body": "..."
}
```

The template mirror of §5.5 (versioning §3.2/§6): the first write after a release copies
to a draft, later writes overwrite it in place; `If-Match` carries the hash precondition;
stale is `409 template.version.conflict`. `dialect` and `type` are inherited from the
working version when the body omits them (136 §D, T289a) — before 136 this endpoint
REQUIRED `dialect` and silently wrote a changed one; now a changed dialect is
`400 template.validation.dialect_invalid` naming both, before validation or any write. The draft versions the CONTENT fields —
`display_name`/`description` move on the index row at save time (versioning §6's
documented asymmetry: they are not part of the versioned artifact).

Response: `200 OK` with the draft version's projection (`version`, `status: "DRAFT"`,
`body_hash`). Content identical to the released version is a no-op (versioning §5.1): the
response carries `status: "RELEASED"` and no draft was created — though
`display_name`/`description` still moved at save time (the §6 asymmetry: they are not part
of the hashed artifact).

**`implements` on update (7e, [Templates §3.4](templates.md#34-implements-and-drift)).** Not content, so it never decides whether a
draft opens. Stated, it replaces the written version's citations (`[]` clears); **absent, it is
inherited** from the version the edit is based on (owner ruling 2026-09-25). A body equal to the
released content that states `implements` is the no-op above for content, and the citations land
on the RELEASED version — the one post-release write templates.md §5.1 allows; that is how a
`needs_review` version is cleared (cite the successor). Refusals as on create.

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
content. **Audited** as `template.version.released` (`template_id`, `version`, `via`). Response: `200 OK` with the released version.

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
`409 template.in_use` while any stored pipeline version — discarded included — pins it), restore brings it back
(`template.version.not_discarded`), purge is drafts-only
(`template.version.last_release`), and `current` is the manual switch
(`template.version.not_eligible`). The template's sticky pointer follows the same D60
rules. All **session-only**, audited as the `template.version.*` / `template.current_switched`
events. Responses mirror §5.12–§5.15 at template shape.

### 8.5 List templates

```
GET /templates?dialect={dialect}&type={sql|html|jsonata|javascript}&q={search}&offset=0&limit=50
```

The second shape on this route: answers only when `name` AND `prefix` are both ABSENT (§8's addressing note). The `type` filter (046) is optional; an unknown value is refused `400 pipeline.execution.invalid_parameter_type` naming the supported values. **`&implements={fact_id}`** (7e, [Templates §3.4](templates.md#34-implements-and-drift)) keeps the templates whose listed version cites that learned fact — a parameterised join through the template's own workspace, so another workspace's fact id matches nothing; a malformed id is `400 pipeline.execution.invalid_parameter_type`. It applies to this flat shape only (like `q`).

```
GET /templates?prefix={folder}&dialect={dialect}&type={sql|html|jsonata|javascript}&offset=0&limit=50
```

The third shape: answers when `name` is absent and `prefix` is PRESENT — browse ONE level of the template tree (067; same contract as `templates_list {prefix}`, [MCP §6.2.6](mcp-server.md)). Present-but-empty (`?prefix=`) is the ROOT. The `data` payload becomes `{prefix, folders, templates, total, has_more}`: `folders` lists the prefix's direct sub-folders as `{path, segment, template_count}` (subtree counts), `templates` its direct leaves as the same rows as the flat list, `total`/`has_more` page the leaves via `offset`/`limit`. `dialect`/`type` narrow both halves, so a folder whose whole subtree is filtered out is absent rather than empty; `q` is ignored while `prefix` is present (browse and search are different presentations). An unknown or illegal prefix answers an EMPTY level with `200` — never a `400`, never a query error.

### 8.6 Delete template (the entity purge, 101)

```
DELETE /templates?name={path}
```

**The entity purge** (replaces the V1 soft delete, retired in V19): allowed only when the
template's ONLY version is a DRAFT (`409 template.version.last_release` otherwise) and no
stored pipeline version — draft, released or discarded — pins any version of it (`409 template.in_use`, naming the pinners).
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

Response: rendered SQL string. Useful for UI editor preview and for LLM-assisted authoring. A **transform type** (`jsonata`/`javascript`) has nothing to render — refused `400 template.render_not_applicable` with `details.use: "templates_evaluate"` (7b).

### 8.7A Evaluate a transform template (7b)

```
POST /templates/evaluate

{
  "name": "acme/finance/apply_rules.jsonata",
  "version": 1,
  "input": {
    "rows": [ { "order_id": 1, "amount_cents": 1250, "customer_id": null } ],
    "inputs": { "tz": "UTC", "min_total": 0.00 }
  }
}
```

The evaluate route — the MCP `templates_evaluate` tool's REST twin, the same service, the same bounded evaluation pool, the same timeout (`datapipelines.transform.evaluate-timeout-seconds`). `version` omitted = the working version (the draft when one exists, else the latest released); `input` is the template contract's input object (in row mode `rows` is the batch and `inputs` holds the value inputs only; in table/value mode `inputs` holds every input, tables as arrays); an optional `now` (ISO-8601) pins the clock for `$now()`/`$millis()` (absent, those builtins refuse). Response:

```json
{
  "output": { "rows": [], "rejects": [ { "row": { "order_id": 1, "amount_cents": 1250, "customer_id": null }, "reason": "customer_id missing" } ] },
  "rejects": [ { "row": { "order_id": 1, "amount_cents": 1250, "customer_id": null }, "reason": "customer_id missing" } ],
  "invariants": [ { "name": "one_to_one", "passed": true, "message": "every input row is accepted or rejected, never lost" } ]
}
```

A refusal is the code with its detail — the contract's input check, the type gate, or the engine's own (the record's §7 mapping; §13.18 lands with 7c). Permission: `template.evaluate` (the render row's twin — auth.md §7.6).

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

A transform entry may carry `implements` (7e). The import keeps the ids that resolve to citable facts in THIS workspace and drops the rest without refusing (owner ruling 2026-09-25 — learned facts are environment-local and never promoted, so a promotion from another deployment lands with none); the kept ids land on the version the import wrote, and an idempotent re-import that wrote nothing writes no citations ([Templates §3.4](templates.md#34-implements-and-drift)).

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

**`facts` and `definitions` on every row (138 §B, additive).** Each item carries the two learned-fact blocks §9.7A defines — `facts`, the datasource-wide kinds (window, sampling), and `definitions`, the active workspace's rules (`definition`, `exclusion`, `preference`) that name this datasource — as the MCP `datasources_list` has since 136: a rule is read BEFORE the tables are chosen, so it rides the call every client makes first. Both are `[]` when nothing is recorded, never absent.

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

Read-only live schema metadata ([Datasources §7A](datasources.md#7a-schema-introspection)) over JDBC `DatabaseMetaData`, with column types mapped to the canonical Type System types. Scope: `author` ([Auth §7.6](auth.md#76-operation-matrix--the-permission-catalog-authoritative)) — same precedent as the connection test, since each call opens a live connection.

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
- An unknown `schema`/namespace filter on a listing matches nothing and returns an empty list. The columns read is table-ADDRESSED (123 §A): a table absent from the namespace's listing is `404 datasource.table_not_found`, with a "Did you mean …?" line naming the nearest listed table when one is close (complete-catalog dialects assert "does not exist"; privilege-filtered ones say the table does not exist OR the datasource's credentials cannot see it — [Datasources §7A](datasources.md#7a-schema-introspection)). An existing table with zero readable columns is still an empty list. An unknown datasource name is `404 datasource.not_found`. A connection failure against the datasource is `502 pipeline.execution.datasource_unreachable` (the customer's database being down is not a server error).
- No pagination: the tables and schemas listings are bounded by their 2000-row cap (`truncated` flags the drop), and per-table listings are naturally bounded.

### 9.7A Learned facts on the introspection endpoints (118)

`GET /datasources/{name}`, `GET /datasources/{name}/tables` and `GET /datasources/{name}/tables/{table}/columns` carry the **learned facts** agents recorded about the objects they return ([learned-semantic-layer design](superpowers/specs/2026-09-11-learned-semantic-layer-design.md) D-S7, §7.2) — the same block, from the same code, as the MCP twins ([MCP §6.2.18a](mcp-server.md#6218a-the-learned-fact-block-on-introspection-responses-118)). Additive: a client that never reads `facts` sees exactly what it saw before.

```json
// GET /datasources                   → data.items[i].facts and data.items[i].definitions (138 §B): the same two blocks as the detail; [] when none
// GET /datasources/{name}            → data.facts: the datasource-wide kinds (window, sampling); data.definitions: the workspace's rules naming this datasource (138 §B); [] when none — each rule carries implemented_by (7e)
// GET /datasources/{name}/tables     → data.tables[i].facts: TABLE-grain facts (a ref with no column); omitted when none
// GET /datasources/{name}/tables/{table}/columns → data[i].facts: the facts with a ref on that column; omitted when none
{
  "id": "…", "scope": "DATASOURCE", "kind": "unit",
  "fact": "value is already in the unit named by unit_col — never tenths",
  "trust": "observed", "drift": "column X no longer exists",
  "evidence_summary": "unit=°C, value=21.4 | unit=mm, value=0.8",
  "recorded_via": "mcp", "recorded_at": "2026-09-11T10:15:00Z",
  "from_this_workspace": true,
  "source_pipeline": {"id": "…", "name": "finance/revenue"},
  "conflict": true
}
```

Notes:

- `drift`, `evidence_summary`, `source_pipeline` and `conflict` are omitted-when-absent (the §3.2 envelope convention). `trust` is `asserted` / `observed` / `verified` / `needs_review` / `stale` (a `definition`, `exclusion` or `preference` lands `asserted` even with evidence — a choice is confirmed by a person, never observed; datasources.md §7E); retired facts are never served here.
- **The drift check runs at read** (design §6): `/columns` recomputes each fact's per-table fingerprint from the columns it just read, and a COMPLETE `/tables` listing (no `namespace`/`schema` filter, not truncated) checks each fact's tables; a demotion (`needs_review`, `stale`) is written back to the row before it is served — idempotent, one-way; nothing re-maps. `GET /datasources/{name}` opens no connection and serves facts as stored.
- **`implemented_by` (7e, [Templates §3.4](templates.md#34-implements-and-drift)):** every rule under `definitions` carries `[{template_id, version}]` — the live template versions citing it as the transform implementing it, under the caller's `template.read` lens (a promoter's: RELEASED versions of the templates it may read); `[]` when none. DATASOURCE facts never carry it — nothing can cite them.
- **Visibility is the store's one predicate:** every DATASOURCE fact on a datasource the workspace can see, plus the workspace's own WORKSPACE facts. **Provenance stops at the reader's visibility (D-S9):** `from_this_workspace` says whether the active workspace recorded it, and `source_pipeline` is present only when the caller's workspace can read that pipeline. **Conflicts coexist (D-S5):** two live facts of one kind on the same refs both carry `conflict: true`.
- Recording, listing and retiring facts are MCP verbs in round 1 (`semantics_record` / `semantics_list` / `semantics_retire`, [MCP §6.2.37–39](mcp-server.md#6237-semantics_record)); there is no REST write for facts yet. The refusal codes are [Pipeline Contract §13.15](pipeline-contract.md#1315-learned-semantics).

### 9.8 Lake tables (the dp-lake catalog)

```
POST   /datasources/{name}/tables
DELETE /datasources/{name}/tables/{namespace}/{table}
POST   /datasources/{name}/tables/import
GET    /datasources/{name}/lake-tables
```

The registry of tables a **LAKE**-dialect datasource serves ([metadata-db §4.15](metadata-db.md#415-lake_tables), the 2026-09-07 lake-datasource design record §2). A LAKE datasource reads object storage in place and the engine cannot LIST a bucket — these rows are its catalog. Scope: `author` for the three writes ([Auth §7.6](auth.md#76-operation-matrix--the-permission-catalog-authoritative)); mutating a GLOBAL datasource's registry additionally requires admin (workspaces D8, enforced in-handler). The listing is `read`. Every operation refuses a non-LAKE datasource with `400 datasource.validation.lake_dialect_required`.

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
| `executed_by` | uuid | The user the run belongs to (D11, 177): the session's user, or a key's OWNER. Was `triggered_by` until 2026-09-20 — renamed, not re-derived; every row kept its actor |
| `executed_by_key_kind` | string \| null | Which KIND of credential started it when a key did — `user` \| `endpoint` \| `server` ([enums §18A](enums.md#18a-executedbykeykind--which-kind-of-credential-started-an-execution)); `null` for a signed-in session. An `endpoint` run is nobody's OWN: it lists for workspace admins only, and for the endpoint key itself (§19) |
| `triggered_via` | string | `UI` \| `REST` \| `MCP` \| `PIPELINE` \| `ENDPOINT` \| `SCHEDULE` (#9 — a schedule fired it; `executed_by` is then the system identity, [Auth §4.5](auth.md#45-the-system-service-account-r7)) |
| `result_row_count` | int \| null | Rows in the caller result; null for a zero-caller pipeline and for a `direct`-delivered child |
| `result_size_bytes` | int \| null | Size of the materialized caller result |
| `parent_execution_id` | uuid \| null | The execution whose PIPELINE node spawned this one; null for a root ([§10.2](#102-get-execution-metadata)) |
| `parent_node_id` | string \| null | That node's id; null for a root |
| `root_execution_id` | uuid | The family's top ancestor; equals `execution_id` for a root |

Ownership (roles design D11, ratified 2026-09-20 — [Auth §7.6](auth.md#76-operation-matrix--the-permission-catalog-authoritative) `execution.read`): a **workspace admin** (or super admin — `execution.read_all`) reads every execution in the workspace, optionally pipeline-narrowed; a **viewer** or **author** reads only their OWN runs — `executed_by = self` and not started through an endpoint key; a **promoter** is refused the list, the read, the result and the replay by role (`403 auth.role_required`) before any row is consulted. The filter is SQL, so the page is cut after it and `has_more` is honest. Another member's execution is `404 result.execution_not_found` on the single reads, never 403 ([Auth §11A.1](auth.md#11a1-the-404-rule)).

**Scheduled runs (#9, ruling R3).** An execution a schedule fired (`triggered_via = SCHEDULE`) is nobody's own — it ran as the system identity — so it is visible to **every member whose role reaches `execution.read`**, on this list and on §10.2, §10.3, §10.3A and the result read. It lifts visibility, never ownership: cancelling one (§10.4) still needs `execution.cancel_all`. The UI's execution lists and MCP's `executions_list` still list own runs only (#250 decides them).

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
    "executed_by": "user-uuid",           // D11: the run's user — the session's, or the key's OWNER
    "executed_by_key_kind": null,         // "user" | "endpoint" | "server" when a key started it; null for a session
    "triggered_via": "UI" | "REST" | "MCP" | "PIPELINE" | "ENDPOINT" | "SCHEDULE",

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

Re-emits the SSE event stream from the Redis event log, in original order with original timestamps — `node_progress` samples included, each with the `observed_at` it was taken at (§6.4.9). Useful for debugging pipelines after the fact. The same revoked-subscriber cut as the live stream applies per chunk (§6.8, #230): a replay re-checks the caller's authority before each chunk it serves.

Availability: the Redis event log lives **1 hour** past completion (not configurable); afterwards this endpoint returns `410 result.expired`. The durable per-event record survives 7 days in the `execution_events` table (`datapipelines.executions.event-retention-days`) and is queryable via ordinary execution metadata — only the *replayable stream* expires at 1 hour. The replay is also the answer to "I was not attached (or left early) while it ran": the live stream's delivery guarantee runs only to a connected consumer (§10.4), and everything else is read back from here.

### 10.3A Durable event record (JSON)

```
GET /executions/{execution_id}/events?format=json&after=0&limit=200
```

The execution's **durable** event record — the `execution_events` rows ([Metadata DB §4.7](metadata-db.md#47-execution_events)) — as JSON, for as long as they are retained (`datapipelines.executions.event-retention-days`, 7 days past completion by default), within §10.3's hour or after it (#9; the scheduler design revision's §5.4). It is how a scheduled run's messages are read: nobody was attached to its stream. `format=json` selects it; without it the route is §10.3's replay, unchanged.

Paged by `event_id`, oldest first: `after` (default `0`) is the last `event_id` you hold, `limit` 1–500 (default 200). Response `data`:

| Field | Type | Description |
|---|---|---|
| `execution_id` | uuid | The execution |
| `events` | array | `{event_id, event, timestamp, data}` — `event` is the §6.4 event type, `data` its payload as it was persisted |
| `next_after` | int \| null | The `event_id` to pass as `after` for the next page; null on an empty page |
| `has_more` | bool | Another page exists |

Visibility and refusals are the metadata read's (§10.2, §10.1's ownership and R3 paragraph; session-only, like every execution read since keys v2). A completed execution whose rows the retention job has removed answers `410 result.expired` with `details.reason = event_record_expired`; its metadata stays readable at §10.2. A running execution with no rows yet answers an empty page.

### 10.4 Cancel execution

```
DELETE /executions/{execution_id}
```

Cancels a RUNNING execution: in-flight statements are interrupted (`Statement.cancel()`), connections released, status set to `ABORTED`, and `execution_aborted` (§6.4.8) emitted to any connected stream. Scope: `execute` + ownership (`admin` may cancel any) — [Auth §7.6](auth.md#76-operation-matrix--the-permission-catalog-authoritative).

Works from **any** instance: the request writes a Redis cancellation flag that the executing instance honors within ~one heartbeat interval ([DAG Executor §8.3.1](dag-executor.md#831-the-registry)). The `204` acknowledges the cancellation *request*; the `execution_aborted` event marks its completion.

**Live delivery guarantee.** A consumer that stays connected receives every event on its live stream, terminal event included, and the stream is closed by the server after the terminal sequence (§6.5). The terminal frame may legitimately lag the `204`: up to the executor's cancel re-issue horizon (~2 s) on the executing instance — the courtesy the abort unwind allows a driver that is slow to raise — and about one heartbeat interval cross-instance (§8.3.1). A consumer that abandons the stream early (its own fallback timer, a proxy idle timeout, a reload) has no live-delivery claim for anything written after it left; that gap is exactly what §10.3's replay — and, past the replay's hour, §10.2's durable record — exist to close.

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

### 11.3 The agent manual (public routes)

```
GET /skill.md
GET /skill/{name}.md
```

The manual an agent reads for THIS deployment, unauthenticated and `text/markdown` — it holds
no secret, and requiring a key would mean an agent cannot learn to use its key correctly until
after it has one (mcp-server.md §15). `/skill.md` is the operating core
(`core`); `/skill/{name}.md` serves any document of the rendered set by its flat name
(`pipelines`, `pipelines-dag`, `core-error-codes`, … — `docs_list` over MCP is the catalog).
The one-release aliases of the previous deliveries' names (`skill`, `authoring-playbook`,
`connecting`, …) answer with their successors' bytes; an unknown name is a `404` in the §4.2
envelope with `details.reason: "skill_reference_not_found"`. The bytes are the same rendered
`DocSet` the MCP tools and resources serve ([mcp-server.md §15](mcp-server.md#15-the-manual-how-an-agent-learns-this-server)).

---

## 12. Rate Limiting

### 12.1 Limits

All limits are **per principal** — a signed-in person, or a key acting as its own robot identity ([Auth §7.4](auth.md#74-issuance), keys v2 A13): a key's budget is the key's, never its creator's, so a person's session and their mcp key are two budgets, and every key a role may create is one more. (Before keys v2 a key drew from its owner's budget; since 2026-09-25 the creator's ROLE gates how many principals a workspace holds.) Key names and defaults in [Configuration §3.7](configuration.md#37-rate-limiting) and §3.2 (executor concurrency):

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

Flows and rules are specified in [Auth](auth.md) (§4.7 key identities, §7.4 issuance, §7.5 key roles, §7.6 the permission catalog); this section defines the HTTP surface. All endpoints below live under `/api/v1`.

### 16.1 API keys (creation is on the Keys page — keys v2)

```
GET /auth/api-keys
```
Lists the keys the caller CREATED — their own MCP key and any key they minted (id, name, `kind`, `role`, `identity`, `created_by`, created_at, expires_at, last_used_at, is_revoked). Never returns secrets. (`mcp_key.own` — every role.)

```
POST /auth/api-keys
Content-Type: application/json

{"name": "nightly-sync", "kind": "endpoint", "role": "api_caller", "bindings": ["/nyc"], "expires_at": "2027-08-07T00:00:00Z"}
```
The ONE creation path for every kind (keys v2 A15 — the login mint is retired; the same route serves the Keys page's dialog). **`kind` is REQUIRED** — naming no kind is `400 auth.key_kind_not_mintable` (there is no default kind to fall back to). The create PERMISSION follows the kind: `mcp_key.create` for `mcp` (author, promoter, workspace admin — the LOWEST of the three create permissions, so the route's floor), `api_key.create` for `endpoint` (workspace admin), `server_key.create` for `server` (super admin). On an `mcp` key the requested `role` must also pass **the subset rule** (A14): a creator may give a key only a role whose permission set is a subset of their own in that workspace — a role outside it is `403 auth.role_required` (`details.required`, `details.held`). The key is created with its own identity in one transaction ([Auth §4.7](auth.md#47-key-identities)). Response `201`:

```json
{
  "schema_version": 1,
  "correlation_id": "uuid",
  "data": {
    "id": "dpk_ab12cd34ef56",
    "name": "nightly-sync",
    "kind": "endpoint",
    "role": "api_caller",
    "identity": {"id": "uuid", "display_name": "nightly-sync"},
    "created_by": "uuid",
    "bindings": ["/nyc"],
    "key": "dpk_ab12cd34ef56.9f8e7d6c...",
    "created_at": "2026-09-08T09:00:00Z",
    "expires_at": "2027-08-07T00:00:00Z"
  }
}
```

`key` is the full plaintext, returned **exactly once** — it is never retrievable again.

**`kind`** ([Auth §7.7](auth.md#77-key-kinds-and-published-endpoint-bindings), [Enums §8A](enums.md#8a-apikeykind--what-an-api-key-is)) is `mcp`, `endpoint`, or `server`. It changes what the rest of the body means:

| `kind` | `role` | `bindings` | Who may create |
|---|---|---|---|
| `mcp` (V37, renamed from `user` — A19) | one of `author` \| `promoter` \| `workspace_admin`, chosen under the subset rule (A14) — never viewer (A15), never `super_admin` (B1) | Refused | Author (author only), promoter (promoter only), workspace admin (any of the three), super admin (any) — `mcp_key.create` + the subset rule |
| `endpoint` | `api_caller` (the only one offered, record A1); another role is `403 endpoint.key_kind_refused` | The endpoint-tree nodes it authorises, validated BEFORE the key is minted — and since 2026-09-21 (#191) each must be the root or lie at or above a path the CALLER'S workspace publishes (`400 endpoint.path_invalid`, naming only the caller's own tree) | Workspace admins and super admins (`api_key.create`) |
| `server` | `promotion_receiver`; another role is `403 endpoint.key_kind_refused` | Refused | Super admin only (`server_key.create`) |

**`scopes` is refused by name** (`400 pipeline.execution.invalid_parameter_type`, `details.field = "scopes"`): scopes were removed (#215, PK8), and a caller who still sends them believes the key will carry them. An unknown `role` token is the same 400 with `details.supported`. **A name already taken by a live key in the workspace is `409 auth.key_name_taken`** (keys v2 A18; revoking the old key frees the name). **An unbound published path is unservable until a key is bound to it** (#215 B3) — a session is refused on the published tree, and no other key kind reaches it.

An unknown `kind` is `403 endpoint.key_kind_refused`, naming the supported values. A `server` key is the credential a SENDING deployment presents as `DP-Promotion-Key` (§18); it authenticates nothing on this API — presented as `DP-API-Key` it is refused on every route with `403 endpoint.key_kind_refused` and `details.reason = "server_key_off_surface"`.

```
DELETE /auth/api-keys/{key_id}
```
Revokes a key the caller CREATED (creator-scoped; `mcp_key.revoke_own`, author and above — the service checks `created_by`). Revoking any key of the workspace is the workspace admin's `api_key.revoke` (a `server` key's is `server_key.revoke`). Revoking an `endpoint` or `server` key deactivates its identity in the same transaction; revoking a member's key ends with their removal (A17/B6). Effective ≤ cache TTL, ~60s. `204 No Content`.

### 16.2 Current principal

```
GET /auth/me
```
Returns the authenticated principal: `user_id`, `email`, `display_name`, `role` (the role it is judged as — the active membership's, `super_admin`, or a key role; informative), `auth_method`, `key_id` (when key-authenticated). A session's surface — the MCP key is refused here (B2).

### 16.3 User administration (super admin — `user.manage`)

```
GET  /auth/users?q={search}&offset=0&limit=50     — list users
GET  /auth/users/{user_id}                         — user detail
POST /auth/users/{user_id}/deactivate              — is_active = false (effective ≤ ~60s: every session and key of that user then answers 401 auth.principal_deactivated, Auth §11A.3)
POST /auth/users/{user_id}/activate                — is_active = true
POST /auth/users/{user_id}/grant-admin             — is_admin = true
POST /auth/users/{user_id}/revoke-admin            — is_admin = false
```

All return the standard envelopes; mutations return the updated user record and write the corresponding `auth.user.*` audit events ([Auth §10.1](auth.md#101-events)). Every route answers people only: a key's identity or the System row is the unknown-user `404` (`details.reason = "user_not_found"`), looked up BEFORE any mutation ([Auth §4.7](auth.md#47-key-identities)), and the list returns `human` rows only. There is no user-create endpoint — users are provisioned by OIDC first login only ([Auth §4.2](auth.md#42-user-provisioning)).

### 16.4 Logout (browser session)

```
POST /logout
```
Clears the `dp_session` cookie ([Auth §6.5](auth.md#65-logout)). Root-level (not under `/api/v1`), CSRF-protected, listed here for completeness.

---

## 17. Workspace Endpoints

Workspaces are the unit of team isolation ([workspaces design](superpowers/specs/2026-08-16-workspaces-design.md) §9; [Auth §5.6](auth.md#56-workspace-resolution--the-dp-workspace-header) resolves the ACTIVE workspace per request). Every endpoint below lives under `/api/v1`. Scope minimums are in [Auth §7.6](auth.md#76-operation-matrix--the-permission-catalog-authoritative); the role/mode gates (owner-or-admin, provisioning mode, `open-join`) are enforced in the service layer, default-deny.

**The no-oracle rule** ([pipeline-contract §13.12](pipeline-contract.md#1312-workspace-resolution)): for anyone but a global admin, an unknown workspace name and a workspace the caller is not a member of are the SAME `403 workspace.membership_required` — a name cannot be probed. A global admin (who could otherwise see any workspace) gets a real `404 workspace.not_found`. A member who is not the owner of a workspace they ARE in gets the same 403 for management operations — role probing is an oracle too.

### 17.1 List own workspaces

```
GET /workspaces
```
The caller's memberships (design §9 "list-own"): `{name, role, active, joined_at}` rows, `role` one of `viewer` \| `author` \| `promoter` \| `workspace_admin` ([enums §8C](enums.md#8c-workspacerole--the-one-role-a-membership-holds); D1, 2026-09-20 — the three booleans V23's flags had put on the wire are gone). **Every member's read** — it is the list the chrome's switcher draws from, so it sits on the switcher's row (`workspace.switch`), not on the page's (`workspace.read`, which D13 narrowed to admins: §17.2 and §17.6). A super admin gets every workspace on the instance, deactivated ones marked `active: false`.

### 17.2 Get workspace

```
GET /workspaces/{name}
```
`{name, display_name, is_personal, created_at, active, deactivated_at}`. A workspace admin of that workspace, or a super admin (D13) — everyone else gets the 403/404 split above.

### 17.3 Create workspace

```
POST /workspaces
{"name": "team-etl", "display_name": "Team ETL"}
```
`display_name` optional (defaults to `name`). **Super admins only** (D-R11): provisioning modes were retired with round 1, and with them `workspace.creation_forbidden` — a non-super-admin is refused by the matrix's own `auth.role_required`. The creator takes NO membership row: a super admin is an implicit member of every workspace (D-R8), and an explicit one would be indistinguishable from a granted membership in the audit. Errors: `400 workspace.validation.name_invalid` (`[a-z0-9_-]+`, 1–63), `409 workspace.validation.duplicate_name` (global namespace, soft-deleted included).

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

### 17.5a Deactivate / reactivate a workspace

```
POST /workspaces/{name}/deactivate
POST /workspaces/{name}/reactivate
```
Super admin (`workspace.lifecycle`). Deactivate, never delete (D-R10): nothing is purged; the workspace stops being selectable, its published endpoints become unknown paths to every caller, keys pinned to it answer `404 auth.key_workspace_inactive` and a server key pinned to it is refused by the promotion peer — all within the ~60 s liveness window ([Auth §11A.3](auth.md#11a3-deactivation)). Returns the §17.2 record (`active: false`, `deactivated_at` set). Reactivate restores every credential; reactivating an already-active workspace is `404 workspace.not_found` (the 404 rule: the surface owes no "it was not deactivated"). Both are audited (`workspace.deactivated` / `workspace.reactivated`).

### 17.6 List members

```
GET /workspaces/{name}/members
```
TWO arrays, never mixed ([Auth §4.6](auth.md#46-invitations)) — a client that counts members must not count ghosts:

- `members[]`: `{user_id, email, display_name, role, joined_at}` rows, oldest membership first — `role` is the member's ONE role (D1). A workspace admin of that workspace, or a super admin (D13).
- `invitations[]`: `{email, role, invited_by, invited_at}` rows — the pending, email-keyed invitations ([Auth §4.6](auth.md#46-invitations)) whose `users` row does not exist yet, each carrying the one role it will materialise with (D20). No `user_id`: there is no user yet; the email IS the identity.

### 17.7 Add member

```
POST /workspaces/{name}/members
{"email": "bob@example.com", "role": "author"}
```
Workspace admin or super admin (`workspace.members.manage`; D-R11 retired the `open-join` self-service path). `role` is optional, one of `viewer` \| `author` \| `promoter` \| `workspace_admin`; absent = `viewer` — a body that says nothing asks for the least a membership can be. A value outside the four is the surface's bad-parameter 400 (`pipeline.execution.invalid_parameter_type`, `details.field = "role"`, `details.allowed` the four) — never stored. The email is normalized lowercase before every lookup and store (the [Auth §4.2](auth.md#42-user-provisioning) rule). Adding an existing member is idempotent: the existing membership is returned unchanged — changing a role is §17.10.

TWO outcomes, distinguishable by status AND body, so a client never mistakes a ghost for a member:

- **`200`** with the membership row — the `users` row existed; the person is now (or already was) a member.
- **`202`** with `{"invited": true, "email", "role"}` — nobody by that email exists yet; an invitation ([Auth §4.6](auth.md#46-invitations)) was created, upserting over any earlier one (the latest admin decision wins, audited), and their first login materialises it.

A missing or non-textual `email` is the surface's generic bad-parameter 400 (`pipeline.execution.invalid_parameter_type`, `details.field = "email"`). An email the §4.3 domain allowlist would refuse at login is refused HERE with the SAME code the login would use — `auth.login.domain_not_allowed` at a 400 — because an invitation that could never be honoured is a trap. A deactivated workspace is `workspace.inactive` (404).

### 17.8 Remove member

```
DELETE /workspaces/{name}/members/{user_id}
```
Workspace admin or super admin. Removing YOURSELF is refused with `409 workspace.self_membership` (#208 — nobody administers their own membership; another admin does). Removing the LAST workspace admin is refused with `409 workspace.last_admin` — a workspace with no admin is unmanageable, and the caller's next step is "give someone else the workspace admin role first" (§13.12). A `user_id` that names no member of the workspace is the workspace's own 404 (`workspace.not_found`), so the member list cannot be probed one id at a time. The member's login-minted key pinned to this workspace is revoked in the same act (§17.11's effect, reason `member_removed`; [Auth §7.4](auth.md#74-issuance)) — a key is tied to user + workspace.

### 17.9 Revoke an invitation

```
DELETE /workspaces/{name}/invitations/{email}
```
Workspace admin or super admin (`workspace.members.manage`). Removes a pending invitation ([Auth §4.6](auth.md#46-invitations)) — `204` on success. An email with no invitation in this workspace is `404 workspace.invitation.not_found`: the workspace itself resolved, so the not-found thing is the invitation, and one answer for "revoked already", "never created" and "another workspace's" keeps an admin from probing which emails hold pending invitations. The email is normalized lowercase, so revoking `Bob@Company.com` finds the row the invite of `bob@company.com` created. A super admin may revoke inside a DEACTIVATED workspace (its pending invitations wait; cleanup before reactivation is the point).

### 17.10 Set a member's role

```
PUT /workspaces/{name}/members/{user_id}
{"role": "promoter"}
```
Workspace admin or super admin. REPLACES the membership's ONE role (D1, 2026-09-20; the three booleans of the flags era are gone from this body — see the change log). `role` is one of the four; absent = `viewer`, unknown = the §17.7 400. Changing your OWN role is `409 workspace.self_membership` (#208). Demoting the LAST workspace admin is `409 workspace.last_admin`. Returns the §17.6 member row. The audit event is `workspace.member_role_changed` (`from`, `to`).

### 17.11 Revoke a member's key

```
DELETE /workspaces/{name}/members/{user_id}/key
```
Workspace admin or super admin (`workspace.members.manage`, the §17.8 row). Revokes the member's login-minted `user` key pinned to this workspace WITHOUT removing them (#200; roles record §3.7, ruling 3; [Auth §7.4](auth.md#74-issuance)). `204` — also when the member holds no live key: the verb is IDEMPOTENT, "already revoked" is the success it reports, so an admin retrying after a timeout cannot turn cleanup into a failure. A `user_id` that names no member is the workspace's own 404 (`workspace.not_found`, the §17.8 rule).

The member's SESSION is untouched — revoking a key is not deactivation — and their next login or workspace entry mints a fresh key. There is no automatic rotation on password or identity events (§3.7 ruling 2: the product cannot tell a forgotten password from a compromise, so recovery is an admin act — this verb, or §17.8's removal). The audit event is `auth.api_key.revoked_by_admin` with `reason: admin_revoked`; the removal path's revoke carries `reason: member_removed`. No key id, prefix or plaintext appears in any response.

---

## 18. Promotion Endpoints (receiver)

The RECEIVER half of promotion ([Versioning §10](versioning.md#10-promotion-ui-driven-separate-use-case)). Two endpoints, and they are the only routes under `/api/v1/promotion/`.

**Authentication is different here, and deliberately narrower.** These routes are gated by the server key of §10.6, presented as `DP-Promotion-Key`. The header is read on this prefix and no other, so the credential authenticates nothing anywhere else and grants no read access outside this pair. The converse also holds: an ordinary API key or a session cookie does **not** open these routes — promotion is a deployment-to-deployment channel, not a privileged human one.

**How the receiver mints one (091; the page moved in 179).** On the receiver, a super admin opens the API keys page (`/api-keys`), creates a key of kind **`server`** (the `promotion_receiver` role, no bindings — the form hides bindings for this kind) and copies the plaintext, which is shown once. That value goes into the SENDER's `datapipelines.deployment.promotion.target.server-key`. Equivalently over REST, on the receiver:

```
POST /auth/api-keys   {"name": "uat receiver", "kind": "server"}
→ 201 {"data": {"id": "dpk_…", "kind": "server", "role": "promotion_receiver", "key": "dpk_….<secret>"}}
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

**The sender caches this answer (178).** The promoter lens evaluates §10.2 on every read a promoter makes, so the sender reads this endpoint through a per-workspace cache (`datapipelines.deployment.promotion.inventory-cache-ttl-seconds`, default 60 s — [Configuration §3.19](configuration.md#319-deployment)); an unreachable answer is remembered for the same window, a successful push invalidates the entry, and the push path itself always reads fresh (§10.3).

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

A released pipeline served as a **`GET` API** at `/api/<category>/<version>/<path…>`, whose
response is the `data_ready` payload — the first page plus the cursor — with no event handling on
the client (this section is the contract). Ruling R-EP5: the **category** is the engineer's
namespace — a business domain, a team, a product line — and the product's own API is simply its
reserved namespace: a category matching `v[0-9]+` (`/api/v1/…` today, any `v<n>` tomorrow) or the
literal `api` can never be published, so a published path cannot shadow a product route *by
construction*. There is **one** handler for the whole subtree — its mapping constrains the
category segment itself — and no runtime route registration: a published endpoint is a row, not
a mapping.

### 19.1 The path grammar

`path_pattern` is 3–10 segments — **category, version, path** — each a literal
`[a-z0-9][a-z0-9_.-]{0,63}` or a variable `{name}` matching the parameter grammar
`[a-z_][a-z0-9_]*`; at most 200 characters; a leading `/`, no trailing slash, no wildcards. The
category and the version are always **literal**: variables are allowed only after the version.
The version is one free-form segment — no pattern is enforced; `v1` is the convention. The stored
form is always the part after `/api`: a pattern handed in *with* one `/api` prefix is normalised,
not refused — which is why `/api/api/…` is refused (its category is `api`, a reserved segment).
It is the same segment grammar hierarchical template names
use ([Template hierarchy §4](template-hierarchy-design.md)) — one grammar to learn.

**Reserved categories.** A category matching `v[0-9]+` or equal to `api` is refused at publish
with `400 endpoint.path_reserved` naming the segment, and re-checked when the serve registry is
built — a row written around the publish path is never served. The reservation is the routing
contract: the catch-all handler's own pattern excludes those categories, so an unknown
`/api/v1/…` path answers the product's 404, not the endpoint one.

**Ambiguity is refused, not resolved.** `/nyc/v1/{x}` and `/nyc/v1/b` both match
`GET /api/nyc/v1/b`, so
publishing the second is `409 endpoint.path_conflict` naming the first. Literal-beats-variable
precedence is deliberately NOT offered in v1: because ambiguity cannot be published, at most one
pattern can match a request, and a reader of the registry can tell what a URL does by finding the
one row it matches. `UNIQUE (path_pattern)` is deployment-wide — a URL is global.

### 19.2 What may be published

The pipeline must exist in the endpoint's workspace and have a **released** version, and it must
be **side-effect-free**: every node of the current released version is `DQL` with `output.target`
in {`tempdb`, `caller`}, a `DML`/`DDL` node whose `source` is `tempdb`, or a `PIPELINE` node whose
pinned child satisfies the same rule transitively. `tempdb` is the execution's own in-memory H2,
created for the run and discarded with it — a statement against it cannot outlive the request, so
it has no effect a `GET` needs to be safe from (#171). A `DML`/`DDL` node whose `source` is a
registered datasource — or a DQL node writing back to a datasource, which is a write wearing a
read's type — refuses publication with `409 endpoint.pipeline_not_readonly` naming the node and
the datasource.

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
([Auth §7.7](auth.md#77-key-kinds-and-published-endpoint-bindings)): an `endpoint` key (role
`api_caller`) serves what is bound to it, the MCP key is refused here by kind, and so **a published
path with no binding on any ancestor serves no one until a key is bound to it** (#215 B3).

The accepted parameters are the released version's declared ones: **path variables** bind by
name, and the **query string** supplies the rest. Validation is strict and **reports every defect
at once** — one `400 endpoint.request.invalid` whose `details.errors[]` carries
`{parameter, code, message}` per problem, because a client fixes a request once:

- an unknown query parameter is `endpoint.request.parameter_unknown` — deliberately not ignored,
  since a typo that silently ran the default would return plausible, wrong rows;
- a repeated key is `endpoint.request.parameter_repeated`, as is a query key that also came from
  the path (the URL decides, not the query string);
- a required parameter with no value is `pipeline.execution.parameter_required`, and a value that
  is not its declared type is `pipeline.execution.invalid_parameter_type` — the existing codes.
  Nothing is trimmed: `?amount=%2012.50%20` is refused exactly as the execute body refuses
  `" 12.50 "` (the v2.36 break, #194). A value that breaks a declared rule of its parameter —
  a `constraints` bound, length or pattern, or the declared precision/scale — is
  `pipeline.execution.parameter_constraint_violation` (v2.37, pipeline-contract §6.2);
- a value over 4 KB is `endpoint.request.value_too_large`.

Headers: `DP-Result-TTL-Seconds` (§7.4's clamp) and `DP-Result-Page-Rows` (R-EP4, clamped to
`page-max-rows`) are honoured; an unparseable value reads as absent, because the server clamps
anyway. `Accept` must admit `application/json` (`*/*` and an absent header do), else `406`.

**The result paging rides the business path** (keys v2 A16 — the framework reads are session-only
now): a caller that started a run pages its result under the SAME path it called —
`GET /api/<category>/v<n>/<path>/executions/{execution_id}` and
`GET /api/<category>/v<n>/<path>/executions/{execution_id}/result` — with the same handlers'
semantics as the framework's `GET /executions/{id}`[`/result`] ([REST §10](#10-execution-history)), served
to the `api` key that STARTED the run and bound to this path. A run another key (or another
workspace's key) started is the 404 rule ([Auth §11A.1](auth.md#11a1-the-404-rule)); a BROWSER
SESSION is refused here exactly as it is on the serve route — this is a machine surface, and the
session gets the same `401 auth.session.required` shape. The routes are delegated from the serve
catch-all (the business path is variable-length, so no separate mapping can exist), and
`EndpointAuthorizer`'s bound-walk runs before any read.

### 19.4 The response

`200` — the body is the `data_ready` payload of [§6.4.7](#647-data_ready), verbatim:
`execution_id`, `schema`, `rows`, `row_count`, `total_rows`, `has_more`, `result_url`,
`expires_at`, `ttl_seconds`. Headers: `DP-Correlation-Id`, `DP-Execution-Id`, and
`Cache-Control: no-store` — a result is per-execution, so a shared cache holding one would hand a
second caller another caller's rows.

The execution runs **in-process** through the same recording path an MCP execution uses — never
HTTP-to-self, never SSE parsing — as the endpoint's workspace, with `triggered_via = ENDPOINT`,
`executed_by` = the key's owner and `executed_by_key_kind = endpoint` (D11: the run is the
ENDPOINT's, not the owner's — it lists for workspace admins only, and for this key through the
serve audit). Every serve is audited with the key id, the endpoint, the execution id and the outcome.

**`202` on timeout (ruling R-EP3).** When the endpoint's `timeout_seconds` elapses the execution
is **not** cancelled — it keeps running, and the response is `202` with
`{execution_id, result_url, status_url, expires_at}`. The cursor `404`s until the result exists
and `GET /executions/{id}` reports status. A `504` would be a lie in both directions: the upstream
is fine, and the answer is coming.

### 19.5 Managing endpoints

`POST` / `GET` / `DELETE /api/v1/endpoints` (`author`), addressed by `?path=` — never by a path
segment, because a `path_pattern` contains `/` and an encoded `%2F` is refused below routing on
the pinned Tomcat (the measured reason §8 moved templates to query addressing). The `GET` listing
answers a **promoter** through the lens (178, [Auth §11A.1](auth.md#11a1-the-404-rule)): an endpoint is
listed iff its pipeline is visible to that principal, so a hidden pipeline never leaks through
the endpoint that publishes it.

Bindings are `POST` / `DELETE /api/v1/endpoints/bindings`, and since 179 (D17) they are a
**workspace admin's verb** (`api_key.bind` — associating a credential with an endpoint tree is
no longer the publisher's). A prefix is refused with `endpoint.path_invalid` unless it is the
root or lies at or above a path the CALLER'S workspace publishes (2026-09-21, #191 — the refusal
names only the caller's own tree), and at serve time a binding decides only for an endpoint of
its own workspace ([Auth §7.7](auth.md#77-key-kinds-and-published-endpoint-bindings)). The key is
named by **`api_key_id`** (the `/api-keys` page's shape —
unambiguous, workspace-scoped, `endpoint` kind required) or, kept for REST compatibility, by
**`api_key_name`** — which resolves within the caller's OWN keys only, exactly as before:
`api_keys.name` carries no uniqueness constraint, so "the key named `ci`" is ambiguous
deployment-wide and a surface that resolved it across owners would pick one person's credential
to widen. Promotion carries endpoint rows and their bindings **of the source workspace, by key
name** — the bindings are read with the workspace predicate in the query (2026-09-21, #199), so a
neighbouring workspace's key bound at an equal node (legal: only the exact `path_pattern` is
unique deployment-wide, path trees may overlap) never rides into the batch; a target missing
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
dashboards; response caching across callers (results are per execution by design); CSV/Arrow by
`Accept` (the cursor's `format` already serves them); custom domains.

### 19.8 The demo API — the worked example (#224)

The demo workspace the product ships doubles as this section's worked example. When a deployment
seeds a demo family (`--demo nyc,trade,lake`), the bootstrap seeder **publishes every seeded demo
pipeline** as an endpoint under the `demo` category, deriving the path from the pipeline name by
one rule: the name's first segment is the version segment, and underscores fold to hyphens —

| Seeded pipeline | Published endpoint |
|---|---|
| `nyc/mobility/revenue_by_borough` | `GET /api/demo/nyc/mobility/revenue-by-borough` |
| `trade/balance_by_partner` | `GET /api/demo/trade/balance-by-partner` |
| `nyc/mobility/taxi_vs_rideshare` (the lake showcase) | `GET /api/demo/nyc/mobility/taxi-vs-rideshare` |

Re-seeding is idempotent: an endpoint already published at a derived path over the same pipeline
is left alone, a changed pipeline set adds and retires paths, and a family without seeded
pipelines publishes nothing.

**One key bound to all of them.** The seeder also mints exactly ONE `endpoint` key named
`demo-public-key` — an ordinary `api_caller` ([Auth §7.7](auth.md#77-key-kinds-and-published-endpoint-bindings)),
acting as its own identity, bound to every published demo path. Its plaintext comes from
configuration, `datapipelines.bootstrap.demo-api-key`
([Configuration §3.18](configuration.md#318-bootstrap)), and the site's
[demo-data page](https://datapipelines.co/demo-data) renders the same value, so an outsider can
copy the key and call any demo endpoint with `DP-API-Key` — no account:

```bash
curl -H "DP-API-Key: dpk_…" https://datapipelines.co/api/demo/nyc/mobility/revenue-by-borough?year=2024
```

```json
{"execution_id": "…", "schema": ["column", "…"], "rows": [["value", "…"]], "row_count": 1,
 "total_rows": 1, "has_more": false, "result_url": "…", "expires_at": "…"}
```

The key is **public by design** and its reach is exactly this section's surface: the bound demo
paths, plus the executions it started. On anything else it is refused like any other `api_caller`
— an unbound published path is `403 endpoint.key_not_bound`, `/mcp` and the product's own routes
are `403 endpoint.key_kind_refused`, and it can create nothing. Blank the setting and the public
API is retracted on the next boot (no key minted, the demo endpoints unpublished, the page's
section hidden); change the value and the next boot is the rotation — the old key revoked, the
new one minted and bound.

**The per-key request budget.** The serve path applies a budget to EVERY `api_caller` key —
`datapipelines.endpoints.key-request-budget` ([Configuration §3.22](configuration.md#322-published-endpoints)),
default 60 requests per 60-second window per key, per instance. The (N+1)th request inside the
window answers `429 rate_limit.exceeded` with `Retry-After` — the same code §12 meters with —
before the pipeline runs, and the key serves again when the window rolls over. A budget is a
product property of a public credential first; the demo key merely needs it first. It is also the
lake family's gate: the lake showcase's endpoint pays S3 egress on every call, so it is published
and bound only while a budget stands behind it (`max-requests` > 0).

---

## 20. Schedules

A **schedule** runs a registered executor's job — in v1 the `pipeline` executor, which runs a pipeline — at the occurrences of a five-field cron in an IANA timezone (#9; behaviour in [Scheduler](scheduler.md), the one owner of these semantics). Schedules are not versioned artifacts: no draft, no release, never promoted. Every route is under `/api/v1/schedules`, works in the caller's active workspace, and is **session-only in slice 1** — every key kind is refused here by §3.2's confinement (an application credential is a later slice). Permissions are [Auth §7.6](auth.md#76-operation-matrix--the-permission-catalog-authoritative)'s `schedule.*` rows: every member reads; authors, workspace admins and super admins create, edit, pause/resume/unblock, delete and Run now. A schedule fires as the **system identity**, never as its creator — a creator later demoted to viewer, or removed, does not stop it. Errors are [Pipeline Contract §13.19](pipeline-contract.md#1319-schedules)'s `schedule.*` codes; an id from another workspace (or one the promoter lens hides) is `404 schedule.not_found`.

**The schedule object** (every response that returns a schedule; the `ETag` header carries `"<revision>"`):

| Field | Type | Description |
|---|---|---|
| `id` | uuid | The schedule |
| `name` | string | A folder path, `finance/daily/revenue` — the pipeline/template name grammar, unique among the workspace's live schedules; rename/move keeps the id and the history |
| `revision` | int | Starts at 1; every edit, pause, resume, unblock and delete a person makes bumps it (a block the scheduler sets does not) |
| `executor` | string | The registered executor (`pipeline`) |
| `payload_schema_version` | int | The executor's payload schema version (1) |
| `payload` | object | The executor's payload. For `pipeline`: `{"pipeline": "<name>", "version": "current"}` — `current` follows the pipeline's current-version pointer at each run, drafts included; nothing else is accepted in v1 |
| `parameters` | object | Literal values for the pipeline's declared parameters, validated by the binder an interactive run uses |
| `target_ref` | string | The executor's reference to what it runs (`pipeline:<name>`) |
| `cron` | string | Five fields, Unix style (`minute hour day-of-month month day-of-week`) |
| `timezone` | string | IANA region id (`America/New_York`) |
| `missed_run_policy` | string | `skip` (default) \| `latest` |
| `enabled` | bool | `false` while paused |
| `condition` | string | `enabled` \| `paused` \| `blocked` — blocked wins |
| `blocked` | object \| null | `{reason, at, run_id}` while blocked (`run_unknown`, or an executor refusal such as `pointer_null`) |
| `next_due_at` | timestamp \| null | The next occurrence the dispatcher will record; ignored while paused or blocked (resume and unblock recompute it from now) |
| `created_by`, `updated_by` | uuid | People |
| `created_at`, `updated_at` | timestamp | |

**The run object** (§20.10–§20.12):

| Field | Type | Description |
|---|---|---|
| `id` | uuid | The run |
| `schedule_id` | uuid | Its schedule |
| `origin` | string | `cron` \| `catch_up` \| `manual` |
| `scheduled_at` | timestamp \| null | The occurrence (UTC); null for a manual run |
| `reference_at`, `reference_timezone` | timestamp, string | The run's frozen reference time — the occurrence for cron and catch-up runs, the accepted request time for a manual one |
| `admit_by` | timestamp | The end of its admission window (`datapipelines.scheduler.lateness-seconds`) |
| `schedule_revision` | int | The revision it was recorded under |
| `state`, `reason` | string, string \| null | [Scheduler §5](scheduler.md#5-runs-states-and-reasons) |
| `execution_id` | uuid \| null | The execution it launched (read it at §10.2, its messages at §10.3A); null when none was |
| `prepared` | object \| null | The executor's frozen snapshot — for `pipeline`, `{pipeline_id, version, body_sha256}` |
| `requested_by` | uuid \| null | The person who pressed Run now |
| `attempts` | int | Capacity retries |
| `created_at`, `claimed_at`, `started_at`, `finished_at` | timestamp | Lifecycle; the last three null until they happen |

### 20.1 List schedules

`GET /schedules?prefix=finance&offset=0&limit=50` — `schedule.read`. The workspace's live schedules by name, `prefix` narrowing to a folder's subtree — a folder path of 1–9 segments with no trailing `/` (`finance`, `finance/daily`; anything else is `400 schedule.validation.request_invalid`, `details.field = prefix`); the §4.3 page envelope. A promoter sees only schedules whose target the promoter lens admits.

### 20.2 Create a schedule

`POST /schedules` — `schedule.create`. Body: `name`, `payload`, `cron`, `timezone` (required); `executor` (default `pipeline`), `parameters` (default `{}`), `missed_run_policy` (default `skip`). Optional `Idempotency-Key`, held durably: a replay of the same request answers `200` with the original schedule; a replay with a different body is `409 idempotency.key_reused_for_different_request`. `201` with the schedule and its `ETag`. Refusals: the `schedule.validation.*` family, `409 schedule.name_taken`, `409 schedule.limit.per_workspace`, and a parameter refusal exactly as an interactive run's (§13.4).

### 20.3 Preview a pattern

`GET /schedules/preview?cron=30%202%20*%20*%20*&timezone=America/New_York&count=5` — `schedule.read`. The next `count` (1–20, default 5) occurrences of a pattern before any save, from the same function the dispatcher uses: `{cron, timezone, occurrences: [{at, local, offset}]}` — `at` the UTC instant, `local` the wall-clock time, `offset` the zone offset then in force. The DST rule shows here: a time inside a spring-forward gap runs at the transition, a time inside a fall-back overlap runs once, at its first pass.

### 20.4 Get a schedule

`GET /schedules/{id}` — `schedule.read`. The schedule, `ETag: "<revision>"`.

### 20.5 Edit a schedule

`PUT /schedules/{id}` with `If-Match: "<revision>"` — `schedule.update`. Body as §20.2 (the whole schedule). A stale revision is `409 schedule.revision_conflict` (`details.current_revision`); a missing `If-Match` is refused as on §5.5. Changing the cron or timezone recomputes `next_due_at` from now — occurrences of the old pattern are not "missed". Runs already recorded keep the revision they were recorded under.

### 20.6 Delete a schedule

`DELETE /schedules/{id}` with `If-Match` — `schedule.delete`. `204`. A soft delete: the schedule disappears from every read and records no more occurrences, a run still queued ends `skipped` / `schedule_deleted` when its turn comes, its history and any running execution stay, and its name is free again.

### 20.7 Pause and resume

`POST /schedules/{id}/pause`, `POST /schedules/{id}/resume` — `schedule.pause`; both idempotent, both return the schedule. Occurrences due while paused are not missed and are not caught up: resume recomputes from now. Resume never clears a block.

### 20.8 Unblock

`POST /schedules/{id}/unblock` — `schedule.pause`. A schedule blocks when a run's outcome is `unknown` (nobody can say whether its work happened) or an executor refusal asks for it (the pipeline has no current version, the parameters no longer bind, …). Unblock re-validates the saved payload and parameters first — a refusal there is the same `400` a save would get — then clears the block, recomputes `next_due_at` from now and writes an `unblocked` row on the blocking run's trail. `409 schedule.not_blocked` when it is not blocked.

### 20.9 Upcoming occurrences

`GET /schedules/{id}/upcoming?count=5` — `schedule.read`. As §20.3, for the saved schedule.

### 20.10 List runs

`GET /schedules/{id}/runs?offset=0&limit=50` — `schedule.read`. Its runs, newest first, the §4.3 page envelope. A deleted schedule's runs are not reachable here (its id is `404`); their executions stay readable at §10.

### 20.11 Get a run

`GET /schedules/{id}/runs/{run_id}` — `schedule.read`. The run plus its frozen `payload` and `parameters` and its `trail`: `[{seq, kind, reason, at, worker, details}]`, append-only, `seq` 1, 2, 3, … The execution's own events are not duplicated here — read them at §10.3A with the run's `execution_id`.

### 20.12 Run now

`POST /schedules/{id}/run` — `schedule.run`. Records a manual run (`origin = manual`, `requested_by` = you) and answers `202` with it; it then runs like any other — as the system identity, through the same capacity and admission. Allowed on a paused schedule; refused on a blocked one (`409 schedule.blocked`) and while one of its runs is queued, starting or running (`409 schedule.run.overlap`). Optional `Idempotency-Key`, durable: the same key answers `200` with the original run.

---

## Appendix A: Change Log

| Date | Version | Author | Change |
|---|---|---|---|
| 2026-09-26 | v2.37 | 194a (#194) parameter engine lane A — the shared validator | **Deliberate break — a pipeline parameter's declared precision and scale now bind the values it accepts.** Every surface that binds pipeline parameters (`POST /pipelines/{id}/execute`, `pipelines_execute`, release checks, schedules, published endpoints §19.3) judges a value with the shared `ParameterValueValidator`: a `DECIMAL`/`BIGDECIMAL` value with more decimal places than the declared `scale` (`12.345` into `DECIMAL(12,2)`), or more integer digits than `precision − scale`, is `400 pipeline.execution.parameter_constraint_violation` with `details.reason` `scale` / `precision` — never rounded. Until now a declared precision/scale was descriptive only and such values bound unchanged; a stored default or schedule input that breaks its declaration now fails when applied. **Additive:** parameters may declare `constraints` (`min`, `max`, `min_length`, `max_length`, `pattern` — pipeline-contract §6.1) and `cardinality` (`SINGLE`; `MULTI` refused until the dashboard round); a value that breaks one is the same new code with its own `details.reason` (`min`, `max`, `min_length`, `max_length`, `pattern`, `pattern_budget`). Save refuses what execute would: `default_invalid`, `constraint_not_applicable`, `constraint_invalid`, `pattern_invalid`, `cardinality_unsupported` (pipeline-contract §12.7). `null` and an absent key are unchanged — the default, then `parameter_required`. |
| 2026-09-26 | v2.36 | 194a (#194) parameter engine lane A | **Deliberate break — parameter values are no longer trimmed.** Until now a `BIGINTEGER`/`BIGDECIMAL` value with surrounding whitespace was trimmed before parsing (`" 12.50 "` bound as `12.50`) on every surface that binds pipeline parameters — `POST /pipelines/{id}/execute` (§6.1), `pipelines_execute`, release checks, schedules — and a published endpoint (§19.3) additionally trimmed its `INTEGER`, `DECIMAL` and `BOOLEAN` query values (`?limit=%2010`). Every one of those is now `400 pipeline.execution.invalid_parameter_type` with the coercion's existing wording (at an endpoint, inside `endpoint.request.invalid`). The parameter engine's rule (P19/P28): the server never trims, rounds or normalises what the caller sent, and every place a parameter value arrives shares one strict coercion, now in `typesystem`. A caller that sent padded values must send them unpadded. Headers (`DP-Result-TTL-Seconds`, `DP-Result-Page-Rows`, `Accept`) are unchanged. |
| 2026-09-26 | v2.35 | scheduler lane 1 (#9) — numbered after origin/main's v2.34 (197/232) | Additive. **New §20 Schedules** — list, create (durable `Idempotency-Key`), preview, get (`ETag` = revision), edit and delete (`If-Match`), pause/resume, unblock, upcoming, runs, one run with its trail, Run now (`202`); session-only in slice 1. **New §10.3A** — `GET /executions/{id}/events?format=json`, the durable event record paged by `event_id`, `410 result.expired` (`event_record_expired`) past retention. §10.1/§10.2: `triggered_via` gains `SCHEDULE`; a scheduled run is visible to every member with `execution.read` (R3), on the REST list and the single reads. |
| 2026-09-25 | v2.32 | 7e (#7) the semantic link | Additive. **§5.10: the release response carries `warnings`** — `[]` when clean, one `pipeline.release.template_needs_review` `{code, message, template, version}` per pinned version citing a retired learned fact; never a refusal. **§8.1/§8.4: a transform accepts `implements`** (outside `body_hash`; inherited when an update omits it; lands on a released version without a draft; `400 template.implements_unresolved` / `template.blocks_not_allowed`). **§8.2/§8.3/§8.5: every projection carries `needs_review`**, a transform's `implements` and, when marked, `retired_facts`; **§8.5 gains `implements={fact_id}`**. §8.8: import keeps only the ids that resolve in the importing workspace (owner ruling 2026-09-25). **§9.7A: rules under `definitions` carry `implemented_by`.** Status caught up (it read v2.30 after v2.31's row). |
| 2026-09-24 | v2.31 | 224 (#224) demo API | New **§19.8**: the demo family seeding publishes every seeded demo pipeline under `/demo/…` (the name-to-path mapping table), mints one configured `api_caller` key (`demo-public-key`) bound to all of them, and the demo-data page renders the same plaintext; the serve path's per-key request budget (`datapipelines.endpoints.key-request-budget`, 60/60, `429 rate_limit.exceeded` + `Retry-After`, per instance, every `api_caller` key; the lake endpoint's gate). §19.7 drops "per-endpoint rate limits" — a per-KEY budget now exists. |

| 2026-09-24 | v2.30 | 215b (#215) key identities, key roles | **Deliberate breaks, owner-ruled (2026-09-24):** (1) **the MCP key is refused on every REST route** (§3.2 — B2: "MCP key should be only MCP"; `403 endpoint.key_kind_refused`, `details.reason = user_key_off_surface`) — REST is a session's surface plus the two confined key kinds; (2) **an unbound published path is unservable** until a key is bound (B3). §16.1: create takes `role` (never `scopes` — refused by name) and returns `role`, `identity {id, display_name}` and `created_by`; the list is the keys the caller created; revoking an endpoint/server key deactivates its identity. §16.2 `/auth/me` returns `role`, not `scopes`. §16.3: every user-admin route answers the unknown-user 404 for a non-person row, before any mutation. §5.16/§7.2: permissions named, not scopes. |
| 2026-09-24 | v2.29 | 215a (#215) the permission catalog | No route, field or status changes. The inline authorization names are the §7.6 catalog's `<functionality>.<permission>` permissions instead of the retired operations (`RELEASE_VERSION` → `pipeline.release`, `READ_EXECUTIONS` → `execution.read`, `MANAGE_API_KEYS` → `api_key.create` / `api_key.bind`, …); §5's release sentence corrected — release is the author's since 2026-09-20 (D8), so a promoter's key cannot release (the text still described the pre-D8 rule). A `role_required` refusal's `details.held` is the role the caller was judged as (auth §9). Status caught up with the change log (it read v2.27). |
| 2026-09-23 | v2.28 | 213 (#213) show-once MCP key | §16.1: `/mine`'s `copyable` is false once the key has been copied — the first read of the copy endpoint destroys the copyable secret in the same statement (auth.md §7.4). No wire shape changed. |
| 2026-09-22 | v2.27 | #212 doc_url | §4.2: `doc_url` is `https://datapipelines.co/docs/pipeline-contract#<section anchor>` — the catalog page at the code's §13 section. The previous shape (`https://docs.datapipelines.co/errors/<code>`) named a host that does not exist. |
| 2026-09-22 | v2.26 | #208 self-membership | Additive. §17.8 / §17.10 / §17.11: a caller addressing their OWN membership — role, login-minted key, removal — is refused with the new `409 workspace.self_membership` (pipeline-contract §13.12); another workspace admin or a super admin does it. |
| 2026-09-21 | v2.25 | 200 (#200) membership-bound keys | Additive. NEW §17.11 `DELETE /workspaces/{name}/members/{user_id}/key` — a workspace admin or super admin revokes a member's login-minted key without removing them (idempotent `204`; the member's session keeps working, the next login mints fresh; [Auth §7.4](auth.md#74-issuance)). §17.8 states the removal path's new effect: the member's key is revoked in the same act (`auth.api_key.revoked_by_admin`, reason `member_removed` \| `admin_revoked`). |
| 2026-09-21 | v2.24 | 199 (#199) | Security, no wire change. §19.5: a promotion batch carries the bindings **of the source workspace** only — the sender read every workspace's `endpoint_key_bindings` rows and matched by node, so a neighbouring workspace's key bound at a node equal to a promoted pattern rode into the batch by NAME and, on the target, bound the target's key of that name or refused the whole batch for a name the source never chose. The read is now filtered by `workspace_id` in the query (the #191 rule for the serve path, applied to the sender). Numbered on base db12e043; the merger renumbers if another lane took v2.24. |
| 2026-09-21 | v2.23 | 180 (#180) roles R4 | Additive. §16.3: user deactivation names its effect — every session and key of the user answers `401 auth.principal_deactivated` within the liveness window (new §13.7 code; `auth.api_key.invalid` no longer means "owner deactivated"). New §17.5a documents the existing `POST /workspaces/{name}/deactivate` and `/reactivate` routes (super admin) and the 404 rule they keep. |
| 2026-09-21 | v2.22 | 178 (#178) roles R2 | **The promoter lens** ([Auth §11A.1](auth.md#11a1-the-404-rule)) on every pipeline, template and endpoint read (§5, §8, §19.5): a promoter's session or key sees released objects newer than the promotion target's and nothing else; hidden ones are the same `404` an id from another workspace gets; listings, levels and counts are the lensed set; fail closed (never a `502` on a read) while the target is unreadable. §18.1: the sender caches the inventory per workspace (`inventory-cache-ttl-seconds`). No wire shape changed. |
| 2026-09-20 | v2.21 | 177 (#177) roles R1 | **Two deliberate breaks under the ratified [roles design](superpowers/specs/2026-09-20-roles-permissions-design.md)**, both on surfaces whose only callers are the product's own UI and E2E suites: (1) §17 — the membership wire says **`role`** (`viewer` \| `author` \| `promoter` \| `workspace_admin`), replacing the `author`/`promoter`/`admin` booleans on §17.1, §17.6, §17.7 (request and both responses) and §17.10 (renamed *Set a member's role*; body `{"role"}`); an unknown role is a 400. §17.2/§17.6 are a workspace admin's reads (D13); §17.1 (list-own) stays every member's on the switcher's row. (2) §10 — `triggered_by` → **`executed_by`** on the list and read projections, plus **`executed_by_key_kind`** (`user` \| `endpoint` \| `server` \| null); `triggered_via` documents `ENDPOINT`. Ownership rewritten to D11: own unless workspace admin, promoter refused by role, endpoint-key runs admin-only. `/promotion` (UI) is readable by authors too (rule 13). |
| 2026-09-19 | v2.20 | 172 (#172) | **§19 re-rooted (BREAKING for a surface with zero callers)**: published endpoints serve at `/api/<category>/<version>/<path…>` — at least three segments; the category is the engineer's namespace with `v[0-9]+` and `api` reserved (`400 endpoint.path_reserved`, re-checked when the serve registry is built); the version is one free-form literal segment; variables live after it. One `/api` prefix on a submitted pattern is normalised away, never stored. The catch-all mapping constrains the category, so `/api/v1/…` stays the product's — its unknown paths answer the product's 404, not `endpoint.not_found`. Create/read responses gain the full served `url`. Prod held zero published endpoints at the ruling, so nothing migrates. |
| 2026-09-18 | v2.19 | 171 (#171) | §19.2: a published endpoint's `DML`/`DDL` node is allowed when its `source` is `tempdb` (the execution's own in-memory H2, discarded with the run) and refused, naming the datasource, for any registered datasource — previously every `DML`/`DDL` node was refused regardless of source. Re-checked at serve (§5.1/§19.4) through the same rule; no wire or status-code changes. |
| 2026-09-17 | v2.18 | #143/#130 live-stream delivery | §10.4 gains the **live delivery guarantee** paragraph (no new client behaviour): a connected consumer receives the terminal event and the server-closed stream; the frame may lag the `204` by up to the cancel re-issue horizon (~2 s) same-instance, ~one heartbeat interval cross-instance; a consumer that leaves early reads what it missed from §10.3's replay (then §10.2's record). §10.3 names itself that answer. No wire changes. |
| 2026-09-17 | v2.17 | 149 correction / #125 review R149-4 | §6.4.9: a failure thrown by `commit()` itself is ambiguous (the transaction may be durable, the acknowledgement lost) — `rolled_back` is evidence only when the failure came BEFORE the commit attempt; a lost acknowledgement leaves `committed`/`rolled_back` absent. |
| 2026-09-17 | v2.16 | 149 correction / #125 review R149-1 | §6.4.9 `committed` is commit EVIDENCE, independent of the node outcome: `true` survives a `failed`/`aborted` sample when the writer confirmed the commit before the node failed; `false` only with a confirmed rollback (`rolled_back`); **absent on a terminal sample when neither was observed** (unknown — never "not committed"); the abandoned-driver limit stated. |
| 2026-09-16 | v2.15 | 149 / #125 node_progress | New **§6.4.9 `node_progress`** (additive): a measured sample of a node's operation — `operation` (stage/ctas/materialize/writeback/statement/child), the real `destination`, one lifecycle `state`, `started_at`/`observed_at`/`elapsed_ms`, per-state `timings_ms`, `rows_fetched`/`rows_written`/`batches_written` (absent when unobserved), `committed`/`rolled_back` on the terminal sample only, `child_execution_id`; never a percentage. §6.5: zero or more per node between `node_started` and the node's terminal event, the terminal sample first; §10.3 replays them with their original `observed_at`. Cadence: first entry per state, then `datapipelines.executor.progress-sample-interval-seconds`. |
| 2026-09-15 | v2.14 | 142 release cascade | §5.10 gains `?release_pinned_templates=true`: consent to release the DRAFT template versions the body pins in the same transaction as the pipeline flip (templates first; any refusal rolls all back); the refusal's `details` gain `pins_not_released`; audit `template.version.released` per cascaded template (`cascade_from_pipeline_id`, `cascade_from_version`) and `templates_released` on `pipeline.version.released`. Additive; no route removed; no MCP tool releases anything. |
| 2026-09-14 | v2.13 | 140 release checks | New **§5.16** — `POST /pipelines/{id}/versions/{version}/checks/run` (`execute` scope; writes `pipeline_check_runs`; optional `parameters` body; `pass`/`fail`/`error` verdicts) and `GET …/checks` (`read`; definitions with the latest run per check). §5.10 gains the **release-check gate**: a body with `checks[]` releases only past an all-pass fresh run, else `409 pipeline.check.failed` with `details.checks`; `override_checks_reason` (≥ 10 chars, audited as `checks_overridden` + `override_reason` on `pipeline.version.released`) is the only way past. Only the server's run produces `observed` — no endpoint records one from a caller. All additive; no route removed. |
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
| 2026-09-05 | v2.3 | 074 published endpoints | New **§19**: a released, side-effect-free pipeline served as a `GET` endpoint, answering the `data_ready` payload verbatim. One catch-all handler over a registry — never runtime route registration (R-EP1). Ambiguous paths are refused at publish (`endpoint.path_conflict`) rather than resolved by precedence, so at request time at most one pattern matches. Validation reports every defect at once and is strict about unknown query parameters. `202` on timeout with the execution still running (R-EP3), never `504`. §3.6's registry gains `DP-Result-Page-Rows` (R-EP4 — one contract, honoured by §6's execute too) and the `DP-Execution-Id` response header. Management is `/api/v1/endpoints`, addressed by `?path=` for the same measured reason §8's templates are. |
| 2026-09-05 | v2.4 | 077 mandatory folders | §5.1: a pipeline `name` needs a **folder** — 2–10 segments, not 1–10 ([Pipeline Contract §3.2](pipeline-contract.md#32-field-reference)). `POST /api/v1/pipelines` and `POST /api/v1/templates` answer `400` with the existing codes (`pipeline.validation.name_invalid`, `template.validation.id_invalid`) for a flat name, and `details.reason` now separates `folder_required` from `grammar`. **A narrowing, not an addition** — the one kind of change §11 forbids after the freeze — taken pre-release on the owner ruling of 2026-09-05: with no rename (Template Hierarchy §4.5), a root-level name created after the tag is permanent, and the root would accrete scratch with no way to tidy it. No route changes. |
| 2026-09-07 | v2.5 | 087 connector seams | §9.1 gains the `credential` object ([Datasources §3.4](datasources.md#34-credential-kinds)) — `{kind, username?, secret?}`; the legacy top-level `username`/`password` pair still works and means `kind: password`, and a body carrying both is `400 datasource.validation.properties_invalid`. §9.3's response gains `credential: {kind, username?}` and derives `password_set` from the kind (`false` for `none`); top-level `username` is now nullable. §9.4: `credential.kind` is part of the body — moving to `none` clears the stored credential. §9.7's `/tables` and `/tables/{table}/columns` accept `?namespace=` (repeated or dotted) beside `?schema=`, and every table row gains a `namespace` array beside `schema`; `/schemas` gains `entries: [{namespace, label}]` beside the legacy `schemas`. All additive. |
| 2026-09-08 | v2.6 | 089 dp-lake registry (recorded with the §G corrections) | New **§9.8 Lake tables (the dp-lake catalog)** — `POST /datasources/{name}/tables`, `DELETE …/tables/{namespace}/{table}`, `POST …/tables/import` (inline `tables[]`, or a `manifest_url` fetched server-side and restricted to the datasource's own bucket/endpoint — the SSRF boundary), and `GET …/lake-tables` (`read`). The writes are `author`, with a GLOBAL datasource's registry admin-only as a workspaces D8 rule; every write evicts the pool and publishes the §5.7 invalidation. Two corrections landed with this row: the Iceberg `location` is the table's current metadata FILE, not its root (the measured rule, datasources.md §8C.7), and §9.7's introspection listing is registry-backed for LAKE as shipped (datasources.md §8C.3) — §9.8's "a later phase" sentence was written before 089 §C landed. All additive. |
| 2026-09-15 | v2.13 | 138 §B definitions on the REST datasource reads | §9.2 / §9.3 / §9.7A: `GET /datasources` rows and `GET /datasources/{name}` carry `definitions` beside `facts` — the two blocks the MCP `datasources_list` / `datasources_get` have served since 136, from the same one-store-read; the listing used to carry neither and the detail `facts` alone. Additive; `[]` when nothing is recorded. |
| 2026-09-14 | v2.12 | 136 §D PUT /templates inherits dialect and type | §8.4: `dialect` and `type` are optional on update — omitted, the working version's are inherited before the body is bound; a DIFFERENT dialect is refused `400 template.validation.dialect_invalid` (`details.dialect`, `established_dialect`, `template_id`) before validation or any write (the endpoint used to write a changed dialect silently — the defect 135 §C fixed on MCP only); a different `type` stays `type_immutable`. |
| 2026-09-13 | v2.11 | 123 §A table resolution | §9.7: the columns endpoint is table-ADDRESSED — a table absent from the namespace's listing is now **`404 datasource.table_not_found`** (was an empty list), with a "Did you mean …?" line naming the nearest listed table; the message asserts non-existence on complete-catalog dialects and "does not exist, or the credentials cannot see it" on privilege-filtered ones ([Datasources §7A](datasources.md#7a-schema-introspection)). The empty-list rule survives for unknown `schema`/namespace filters on the listing endpoints; an existing table with zero readable columns stays an empty list. |
| 2026-09-11 | v2.10 | 118 learned semantic layer | New **§9.7A**: the three introspection endpoints and `GET /datasources/{name}` carry the learned-fact block (`facts[]`, additive, omitted-when-none on the listings) — the same shape and code as the MCP twins; the drift check runs at read on `/columns` and on a complete `/tables` listing. No REST write for facts in round 1. |
| 2026-09-10 | v2.9 | T187 release audit | §5.10 and the template release: both verbs are now **audited** (`pipeline.version.released` / `template.version.released`, with `version` and `via` = `session` or `api_key`) and §5.10 states who may release over REST (a promoter's session, or a key whose issuer is a promoter — D4's human decision, expressed either way). |
| 2026-09-08 | v2.8 | 101 version lifecycle | §5.11 is the draft **purge** (row + executions deleted; sole-draft ⇒ the entity goes); new §5.12–§5.15 — `POST /pipelines/{id}/versions/{v}/discard|restore`, `DELETE /pipelines/{id}/versions/{v}` (drafts only), `POST /pipelines/{id}/current` (the manual switch) — and §5.6 becomes the **entity purge** (`include_exclusive_draft_templates`, response carrying the offered set). All session-only (`403 auth.session.required` for keys) and audited (enums.md §15's lifecycle table). §8.10/§8.11/§8.6 mirror for templates by name. The sticky `current_version` (D60) moves only on release / discard-of-current / restore-above-current / switch / purge-of-current-draft; imports never move an existing pointer ([Versioning §3.4](versioning.md#34-current_version-is-sticky-and-event-driven-d60)). |
| 2026-09-08 | v2.7 | 094 pool settings and retirement | §9.3's response gains **`pool`** (additive): every tunable HikariCP key's effective value, unit and source (`configured` / `dialect_default` / `application_default` / `hikari_default`) — what the pool RUNS with, beside the `properties.hikari` map of what the row STORES. §9.1/§9.4: out-of-range pool values are now REFUSED (`400 datasource.validation.properties_invalid`) instead of being silently rewritten by HikariCP ([Datasources §5](datasources.md#5-connection-pool-configuration)). §9.4/§9.5: an update or delete RETIRES the pool rather than closing it — new leases miss it at once, statements already running finish on the connection they hold, and a per-instance reaper closes it when drained or at the configured ceiling. All additive; no request shape changed. |
