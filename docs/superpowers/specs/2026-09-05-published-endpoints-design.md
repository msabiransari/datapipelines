# Design: Published Endpoints — a pipeline as a GET API, under `/api/x/**`

**Status:** RATIFIED 2026-09-05 (owner rulings R-EP1–R-EP4 below); implementation prompt 074.
**Not packaged into the product** (`docs/superpowers/` is excluded from the jar). The normative
text lands in `rest-api.md` (new §18), `auth.md` (key kinds), `pipeline-contract.md §13`
(codes), `metadata-db.md`, `mcp-server.md`, `ui-screens.md` — in the same commit as the code
constants (drift tests).

## 1. Why

Today a client that wants rows must call `POST /pipelines/{id}/execute`, read an SSE stream
(§6.4), wait for `data_ready`, and follow `result_url`. That is the right surface for agents and
the editor; it is the wrong surface for an application that just wants `GET /api/x/lending/home`
to return data. This design publishes a released pipeline as a **GET endpoint** whose response is
the `data_ready` payload — the first page plus the cursor — with no event handling on the client.
It is the "Answer over the API" station of the launch poster made literal, and the data source
the dashboards will consume next.

## 2. What already exists (verified in the tree 2026-09-05, with the test that pins it)

- Execute → SSE with node events → `data_ready` carrying `rows`, `has_more`, `result_url`,
  `expires_at`, `ttl_seconds` (rest-api §6.4.7; `TracerBulletE2eTest` asserts the sequence).
- Cursor `GET /executions/{id}/result?offset&limit&format`, `read` scope + ownership; "the URL is
  not a capability" (§7.2; E2E "§7.2 cursor returns exactly the seeded rows").
- `DP-Result-TTL-Seconds`, clamped to `ttl-min..ttl-max` (§7.4; E2E asserts `ttl_seconds`).
  **The first-page size is config only** (`datapipelines.result.page-size-rows`, default 1000;
  cursor `limit` ≤ `page-max-rows` 100,000) — there is no header for it today (R-EP4 adds one).
- Expiry is fixed at write time; reads do not extend it (`RedisResultStoreIntegrationTest`).
- In-process run-to-completion: `SubPipelineExecutionRunner.run` (suspend) already executes a
  pipeline and returns its result for composition — the endpoint reuses that path, never
  HTTP-to-self, never SSE parsing.
- Typed parameter validation: `ParameterCoercion` / `ParameterBinder` (pipeline-contract) — the
  §6.3 strict coercion the execute body uses; the endpoint's validator wraps it.
- API keys: `api_keys(id, user_id, workspace_id, name, key_hash, scopes, is_revoked, expires_at,
  …)`, `dpk_` prefix, Argon2id, scopes `read ⊂ execute ⊂ author ⊂ admin`, rate limits (§12), audit.
- Executions record `triggered_by` (user) and `triggered_via`.

## 3. Rulings (owner, 2026-09-05)

- **R-EP1 — root `/api/x/**`.** Engineers own everything beneath; `/api/v1/**` stays the
  product's (its routes and the security allowlist live there). One catch-all handler, never
  runtime route registration.
- **R-EP2 — auth is the key, applied hierarchically.** A key bound at a tree node authorises
  every endpoint beneath it; a binding at a deeper node overrides (replaces) the inherited one
  for that subtree. Keys gain a **kind**.
- **R-EP3 — timeout → `202` with the cursor, not `504`.** The execution keeps running; the
  client fetches when ready.
- **R-EP4 — `DP-Result-Page-Rows`** request header (clamped to `page-max-rows`) on BOTH the
  endpoint and `POST /pipelines/{id}/execute` — one contract.
- **Validation is explicit** — a dedicated request validator built from the pipeline's declared
  parameters; strict REST semantics and status codes (§6).

## 4. The registry (data, not routes)

```sql
-- V11__published_endpoints.sql (066 is shelved; this takes V11)
CREATE TABLE published_endpoints (
    id               UUID PRIMARY KEY,
    workspace_id     UUID NOT NULL REFERENCES workspaces(id),
    path_pattern     TEXT NOT NULL,            -- '/lending/{borough}/home', no root, §4.1 grammar
    pipeline_id      UUID NOT NULL REFERENCES pipelines(id),
    timeout_seconds  INTEGER NOT NULL,         -- clamped by config, §5.5
    description      TEXT NOT NULL DEFAULT '',
    is_enabled       BOOLEAN NOT NULL DEFAULT TRUE,
    created_by       UUID NOT NULL REFERENCES users(id),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (path_pattern)                      -- deployment-wide: a URL is global
);
CREATE TABLE endpoint_key_bindings (
    path_prefix      TEXT NOT NULL,            -- a tree NODE: '/lending' binds '/lending/**'
    api_key_id       TEXT NOT NULL REFERENCES api_keys(id) ON DELETE CASCADE,
    workspace_id     UUID NOT NULL REFERENCES workspaces(id),
    created_by       UUID NOT NULL REFERENCES users(id),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (path_prefix, api_key_id)
);
ALTER TABLE api_keys ADD COLUMN kind TEXT NOT NULL DEFAULT 'user';   -- 'user' | 'endpoint'
```

### 4.1 Path grammar and matching
- `path_pattern` = 1–10 segments, each `[a-z0-9][a-z0-9_.-]{0,63}` or a variable `{name}` where
  `name` obeys the parameter grammar (`[a-z_][a-z0-9_]*`); ≤ 200 chars; no trailing slash; no
  `**`. Same segment grammar as template names (hierarchy design §4) — one grammar to learn.
- Matching uses Spring's `PathPatternParser` / `PathPattern.matchAndExtract` as a library inside
  the catch-all; Spring's own request mapping never sees user paths.
- **Uniqueness beyond the constraint:** at publish time, refuse a pattern that could match the
  same URL as an existing one (`/a/{x}` vs `/a/b`) — `endpoint.path_conflict` (409) naming the
  other. Literal segments win over variables when both are present is NOT offered in v1;
  ambiguity is refused, not resolved.
- The registry is cached per instance; publish/unpublish invalidate through the 050 Redis
  pub/sub channel (the same mechanism as datasource pools).

### 4.2 Publish-time validation
- Pipeline must exist in the endpoint's workspace and have a **released** version.
- **GET is only for side-effect-free pipelines**: every node of the current released version
  is `DQL` (with `output.target` in {tempdb, caller}) or `CALCULATOR` (once 072 lands), and every
  `PIPELINE` node's child satisfies the same rule transitively. Any `DML`/`DDL` node, or a DQL
  node writing back to a datasource, refuses publication — `endpoint.pipeline_not_readonly`
  (409) with the offending node id. Re-checked on every serve (§5.1), because a later release
  can change the body.
- Every path variable must name a declared parameter of that version (`endpoint.path_variable_
  unknown`, 400); the pipeline may declare more (they come from the query string).
- `timeout_seconds` clamped to `datapipelines.endpoints.timeout-min/max-seconds`.

## 5. Serving `GET /api/x/{path}`

### 5.1 Resolution
Match → endpoint row (`is_enabled`) → **latest released version at request time** (never a
draft; a pipeline with no released version → `503 endpoint.pipeline_not_released`). Re-run the
§4.2 read-only check on that version; if it now fails, `503 endpoint.pipeline_not_readonly`
(the endpoint is live but its target changed underneath it — say so, do not run it).

### 5.2 Authentication and authorisation (R-EP2)
- Credential: an API key (`DP-API-Key` or `Authorization: Bearer dpk_…`), exactly as §8 today.
  Browser sessions are refused (`401`) — this is a machine surface. Missing/invalid → `401`.
- **Key kinds.** `kind = user` (today's keys: scopes, workspace) and `kind = endpoint`: no
  scopes; workspace-pinned; authorises exactly the endpoints it is bound to (§4) plus reading
  the cursor of executions it started. Minted through the existing key surface with
  `kind: "endpoint"` and `bindings: ["/lending", …]`; shown once; Argon2id; expiring; revocable;
  rate-limited per key like any other.
- **Hierarchical resolution, per request:** walk the request path's ancestors from the most
  specific (`/lending/{borough}/home` → `/lending/{borough}` → `/lending` → `/`); the FIRST node
  with any binding decides — the presenting key must be among that node's bound keys, else
  `403 endpoint.key_not_bound`. A deeper binding therefore overrides an inherited one (R-EP2:
  "replace", not "add" — bind both keys at the deeper node if both should work).
- **No binding on any ancestor** → the endpoint accepts `kind = user` keys of the endpoint's
  workspace with `execute` scope (operators and agents keep working); `kind = endpoint` keys
  are refused (`403`) — an unbound endpoint key authorises nothing.
- Every serve is audited with the key id, endpoint id, execution id, outcome.

### 5.3 Request validation (the Validator)
`EndpointRequestValidator(pipelineVersion)` builds the accepted parameter set from the
version's declared parameters (name, type, required, default):
- **path variables** → parameters by name (typed via `ParameterCoercion`);
- **query string** → the remaining parameters; repeated keys → `400 endpoint.request.parameter_
  repeated`; unknown names → `400 endpoint.request.parameter_unknown` (strict — a typo must
  not silently run the default); required-without-default missing → `400
  pipeline.execution.parameter_required` (existing code); wrong type → `400
  pipeline.execution.invalid_parameter_type` (existing); values > 4 KB → 400;
- headers: `DP-Result-TTL-Seconds` (§7.4 clamp), `DP-Result-Page-Rows` (R-EP4, clamp
  1..page-max-rows), `Accept` (v1: `application/json` or `*/*`, anything else `406`);
- **all failures are reported together** in one `400` body: `details.errors[]` of
  `{parameter, code, message}` — a client fixes a request once.

### 5.4 Execution and response
In-process, as the endpoint's workspace and the key's user (`triggered_via = "endpoint"`,
`triggered_by` = key owner; the audit row carries the key id). Wait for completion.
- `200` — body is the `data_ready` payload **verbatim** (§6.4.7): `execution_id`, `schema`,
  `rows`, `row_count`, `total_rows`, `has_more`, `result_url`, `expires_at`, `ttl_seconds`.
  Headers: `DP-Correlation-Id`, `DP-Execution-Id`, `Cache-Control: no-store`.
- Node failure → the 057 failure record as the error envelope; status from §13 (datasource
  connection failures `502`, everything else `500`). `pipeline.execution.concurrency_limit` → `429`
  with `Retry-After`.
- **Timeout (R-EP3)** → `202` with `{execution_id, result_url, status_url, expires_at}`; the
  execution continues; the cursor `404`s until the result exists (today's behaviour) and
  `GET /executions/{id}` reports status. Never `504` for a run that is still alive.

### 5.5 Configuration (validated at boot, configuration.md §7, `CHECK_COUNT` +1)
`datapipelines.endpoints.timeout-default-seconds` (30), `timeout-min-seconds` (1),
`timeout-max-seconds` (300), `page-rows-max` (= result.page-max-rows).

### 5.6 Status codes, complete
| Code | When |
|---|---|
| 200 | result inline (first page + cursor) |
| 202 | timeout; execution continues |
| 400 | request validation (all errors at once) |
| 401 | no/invalid key; browser session |
| 403 | key not bound / wrong workspace / wrong kind |
| 404 | no endpoint matches (same body whether the path is unknown or disabled — no enumeration) |
| 405 | any method but GET (`Allow: GET`) |
| 406 | unacceptable `Accept` |
| 429 | rate limit / concurrency, `Retry-After` |
| 500 / 502 | pipeline failure (§13 mapping) |
| 503 | pipeline not released / no longer read-only |

## 6. Management surfaces
- REST: `POST/GET/DELETE /api/v1/endpoints` (`author`; bindings `admin`-or-owner), single-form
  addressing (`?path=`), promotion carries endpoint rows and bindings-by-key-name in the batch
  (rows reference the pipeline by NAME, like everything promoted).
- MCP: `endpoints_create`, `endpoints_list`, `endpoints_get`, `endpoints_delete` (author,
  audited — R4); `api_keys_create` gains `kind`/`bindings`. `SKILL.md`: when to publish, the
  read-only rule, the key model, the `202` contract.
- **UI, read-only (R10):** an **Endpoints** screen — a tree by path segment (API Gateway's
  shape): folder rows show their key bindings (inherited ones greyed with "from /lending"),
  leaf rows show pipeline, released version, timeout, last serve, enabled. Publishing and
  binding happen through MCP/REST. The template-explorer tree component (047/058) is the
  exemplar.

## 7. Out of scope (named)
- POST endpoints (parameters in a body, side-effecting pipelines) — separate ruling; the
  read-only rule is what makes GET safe.
- Anonymous / embed tokens for public dashboards; per-endpoint rate limits; response caching
  across callers (results are per execution by design); CSV/Arrow via `Accept` (the cursor's
  `format` already serves them); custom domains.
