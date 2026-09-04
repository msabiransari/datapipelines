---
name: datapipelines
description: "Author, maintain, and execute declarative SQL data pipelines on the datapipelines.co server. Use when the user asks to create, update, run, debug, or inspect pipelines, templates, datasources, or executions — or when MCP tools like pipelines_create, pipelines_execute, templates_render, templates_create, datasources_test, datasources_create, datasources_get_schemas, datasources_get_tables, datasources_get_columns, executions_get_result, or prompts like analyze_pipeline / create_pipeline_for_question / debug_failed_execution are available. Covers the pipeline JSON schema, Freemarker SQL templates, node types, execution semantics, error handling, and scopes."
---

# datapipelines

## What this product is

A self-hosted server that executes **declarative JSON pipelines** — DAGs of templated-SQL
nodes — against heterogeneous databases, staging intermediate results in a per-execution
in-memory H2, and returning results through a uniform Redis-backed cursor. It is
**MCP-native**: LLM agents author and execute pipelines as first-class clients alongside a
REST API and a browser UI. Metadata lives in Postgres (Flyway); results/events in Redis.

## Core concepts

**Pipeline** — a JSON document: `schema_version`, `name` (machine name, `[a-z0-9_]+`),
`display_name`, `description`, `parameters` (typed input map), and `nodes` (the DAG).
`id`, `version`, `owner`, timestamps are server-assigned on create.

**Node** — one SQL step, rendered from a template:
- `id` — unique within the pipeline, `[a-z0-9_]+`, stable
- `type` — `DQL` (SELECT → rows), `DML` (INSERT/UPDATE/DELETE/MERGE → row count),
  `DDL` (CREATE/ALTER/DROP → success/failure), or `PIPELINE` (run a pinned child
  pipeline `{"name": "...", "version": N}` as a sub-execution — declares `pipeline`
  plus optional parameter bindings instead of `source`/`template`)
- `source` — a registered datasource name, or the reserved literal `"tempdb"` for the
  per-execution in-memory H2
- `template` — `{"id": "...sql", "version": N}` (immutable pin)
- `output` — where a DQL node's rows go (see below); forbidden on DML/DDL
- `depends_on` — parent node ids; must exist, no cycles

**Output targets (DQL only):**
- omitted → `{"target": "caller"}` — the pipeline's result. **At most one caller node per
  pipeline; zero is legal** (pure write-back: stats only, no rows)
- `{"target": "tempdb", "table": "stg_x"}` — stage into H2 for downstream nodes
  (`source: "tempdb"`)
- `{"target": "datasource", "datasource": "...", "table": "...", "mode": "replace"|"append"}`
  — write-back to an external table (must exist, or be created by a preceding DDL node)

**Template** — Freemarker SQL: `id` (e.g. `fetch_orders.sql`, or a path like
`acme/finance/monthly_revenue` — 1–10 `/`-separated segments, each starting `[a-z0-9]`,
≤ 64 chars per segment, ≤ 200 total), `dialect` (one of
`POSTGRES`, `ORACLE`, `MSSQL`, `MYSQL`, `H2`, `DUCKDB`, `SQLITE`), `display_name`,
`description`, `imports` (`[{"id","version","alias"}]` for library macros), `body`,
`is_library`. **There is no params_schema field** — the variables a body may reference
are exactly the calling pipeline's `parameters` keys (defaults applied). A declared
parameter is referenced in the SQL as a **bind parameter**: `WHERE id = :customer_id`.
The body must **never** contain `<#import>` / `<#include>` — imports come from the `imports` array and
the body calls macros by alias (`<@dates.date_range …/>`). Library templates
(`is_library: true`) contain only `<#macro>`/`<#function>` definitions.

**Versioning** — create lands v1 RELEASED and immediately executable; every later save is
draft-first: the first save after a release opens a DRAFT (copy-on-write), later saves
overwrite that one draft in place. A save whose body is identical to the released one is a
no-op — nothing opens, no version number burns, and the response says `status: "RELEASED"`
with no draft pointer; that is success, not an error. Your updates are NOT published until
a human releases the draft from the UI — **leave the draft for a human to release** (by
design, D4; there is no release tool and that absence is deliberate). Pipeline nodes pin
template versions immutably; updating a template does not change existing pipelines until
you update the node reference. Drafts are executable — running your own draft is the
expected test loop.

**Parameters** — typed with the canonical logical types: `BOOLEAN`, `INTEGER`,
`BIGINTEGER`, `DECIMAL`, `BIGDECIMAL`, `STRING`, `DATE`, `TIMESTAMP`, etc. (11 total —
see type-system.md §3). Each declares `type`, `required`, optional `default`,
`description`. **`DECIMAL` parameters must also declare `precision`** — omitting it
fails the save with `pipeline.validation.parameter_precision_missing`; `BIGDECIMAL`
precision is optional (omitted = unbounded).

**In SQL, write `:name`, never `${name}`, for a declared parameter.** Bound values are
never parsed as SQL — that is the whole point: a `STRING` caller value cannot alter the
statement, while the interpolated form puts it inside the SQL string. Pipeline save
refuses the old form with `template.validation.parameter_interpolated`. `${}` stays for
**structure** — table names, dynamic `IN` lists, `ORDER BY` fragments — which you keep
safe yourself (never interpolate a caller-supplied value there). Bound values need no
quoting: `BETWEEN :start_date AND :end_date`, not `BETWEEN DATE ':start_date' AND …`.

**Dialects** — seven: POSTGRES, ORACLE, MSSQL, MYSQL, H2, DUCKDB, SQLITE. Templates are
dialect-specific; a node's template dialect must match what its `source` can execute.

## Connecting

- **MCP:** Streamable HTTP at `POST {host}/mcp` — stateless, protocol pinned to
  `2025-06-18`. Auth is API-key-only: `DP-API-Key: dpk_<id>.<secret>` or
  `Authorization: Bearer dpk_<id>.<secret>`. Browser session cookies are rejected on
  `/mcp`. REST lives at `/api/v1/**` with `DP-`-prefixed custom headers and a JSON
  envelope (`{"data": ...}` / `{"error": {code, user_message, details}}`).

- **22 MCP tools:** `pipelines_list`, `pipelines_get`, `pipelines_execute`,
  `pipelines_execute_node`, `pipelines_create`, `pipelines_update`, `templates_list`,
  `templates_get`, `templates_used_by`, `templates_create`, `templates_render`,
  `datasources_list`, `datasources_get`, `datasources_test`,
  `datasources_get_schemas`, `datasources_get_tables`, `datasources_get_columns`,
  `datasources_preview_rows`, `datasources_create`, `executions_list`,
  `executions_get`, `executions_get_result`.

- **3 prompts:** `analyze_pipeline` (read-only structural review of a pipeline),
  `create_pipeline_for_question` (ground a new pipeline's SQL in the introspection
  tools, then author it), `debug_failed_execution` (walk a failed execution to a
  diagnosis).

- **Scopes** (hierarchical: `admin ⊃ author ⊃ execute ⊃ read`): `read` = list/get;
  `execute` = run; `author` = create/update pipelines + templates (also template render,
  datasource test, schema introspection, datasource REGISTRATION, and workspace-bound
  datasource mutation). Update and delete of a datasource are REST/UI only; `datasources_create`
  is the one datasource WRITE on the MCP surface. Binding a datasource `global: true` needs
  `admin` either way. A tool or endpoint rejects with `auth.scope.insufficient` when the key's
  scope is too low.

- **Registering a datasource from an agent — read this before using `datasources_create`.**
  A password passed through an agent transits the agent's context, its transcript, and any
  logging the client does. That is a property of handing a secret to an agent; the server
  cannot undo it, and the tool does not refuse. **Prefer registering a datasource that has a
  real password in the UI or over REST.** Use `datasources_create` from an agent only with a
  credential the user is willing to have in that transcript — a read-only role, or a
  short-lived password they will rotate afterwards. Say so before you ask for one. The
  password never comes back: the result carries `password_set: true` and no password field.
  Follow a create with `datasources_test` on the new name.

## The golden path (authoring a new pipeline)

1. **Verify the source.** `datasources_test` (or `datasources_list`/`datasources_get`)
   to confirm name + dialect + connectivity, then introspect the schema:
   `datasources_get_schemas` → `datasources_get_tables(schema)` →
   `datasources_get_columns(table)` for every table the SQL will touch. Never write
   SQL against recalled column names.
2. **Write the template.** `templates_create` with `dialect` matching the source, a
   Freemarker body, and a `description` that names every parameter the body expects
   (the description is the only discoverability mechanism for parameters).
3. **Preview the SQL.** `templates_render` with a representative context — save-time
   validation is parse-only, so this is your check that the SQL is actually what you
   meant. This is mandatory before step 4 for anything non-trivial.
4. **Create the pipeline.** `pipelines_create` with `parameters` declared (types +
   required/defaults — remember `DECIMAL` needs `precision`), nodes referencing the
   template `{id, version}`, `depends_on` wiring, and `output` blocks for
   staging/write-back. Save-time validation dry-renders every template against the
   declared parameters and rejects anything that would not run. Create lands v1 RELEASED —
   the pipeline is executable immediately.
5. **Iterate on the DRAFT.** `pipelines_update` (requires the `expected_hash` you read —
   see Best practices) writes a DRAFT: first update opens it, later updates overwrite it,
   so iterating never piles up versions. Execute the draft to test (`pipelines_execute`
   runs the released version by default — but note the draft's version from your update's
   result if you need to pin it). **Stop here**: leave the draft for a human to release.
   Never claim your change is live — it is not until released.
6. **Read the result.** Inline first page + `total_rows` + `has_more` + `ttl_seconds`.
   Page the remainder with `executions_get_result` (`offset`/`limit`) **within the
   TTL** — afterwards the result is gone (`result.expired`).

Minimal single-node pipeline (Postgres source, the single DQL node IS the caller node):

```json
{
  "schema_version": 1,
  "name": "active_users",
  "display_name": "Active Users",
  "description": "List all active users from local PG",
  "parameters": {},
  "nodes": [{
    "id": "fetch_active_users",
    "description": "Fetch active users",
    "type": "DQL",
    "source": "pg-local",
    "template": {"id": "active_users.sql", "version": 1},
    "depends_on": []
  }]
}
```

## Execution semantics agents must know

- `pipelines_execute` is a **single blocking call** — it returns only when the execution
  reaches a terminal state (`SUCCESS` / `FAILED` / `ABORTED`) or the execution timeout
  (default 600 s) aborts it. There are no progress notifications in v1; the final result
  carries `node_stats` (per-node status, durations, row counts, errors) — the
  authoritative per-node record. A 3-minute pipeline is one 3-minute tool call.
- **Drafts are executable, and a draft run is not a release.** Executing your own draft is
  the expected test loop; history marks those runs (`draft_run`) and they never count as
  validation for release — the human decides that.
- **Cancellation:** no MCP cancel tool in v1. To stop an in-flight run, call
  `DELETE /api/v1/executions/{id}` out-of-band (REST); the blocked tool call then
  returns an `ABORTED` result.
- **Abandoned calls** run to completion — a dropped HTTP request has no disconnect
  callback on `/mcp`; use the REST cancel or let the timeout handle it.
- **Idempotency:** REST execute accepts `Idempotency-Key`; the MCP tool has no key
  carrier. If you are unsure whether a previous execute landed, check `executions_list`
  rather than firing a duplicate.
- **Zero-caller pipelines** return stats with no rows — that is a valid design, not a
  failure.
- `/mcp` and `/api/v1` share a per-user rate limiter — back off on `429`.

## Promotion is not yours to trigger

Moving released content from one deployment to another (dev → uat → prod) is **promotion**,
and it is a **human action from the UI, deliberately**. There is no MCP tool for it, there is
no schedule that runs it, and there is no REST endpoint you can call for it: the promotion
route accepts only a pre-shared key that one deployment holds for another, never an API key
and never a session. This is a design decision, not a gap — a release reaching production is
a decision a person makes.

What that means in practice:

- **Never offer to promote, and never claim you did.** If asked, say what promotion is and
  point at the Promotion screen in the UI.
- **A "hotfix on prod" is not a thing here.** A receiver deployment refuses every authoring
  write with `pipeline.authoring.disabled` / `template.authoring.disabled` — that refusal is
  the system working. The fix is a new release in the authoring environment, promoted like
  any other change. If you meet that code, you are pointed at the wrong deployment.
- **What you CAN do is make a release promotable**: author, render, execute the draft, and
  tell the human it is ready to release. Release itself is also theirs.

## Error handling

Every failure is structured — REST envelopes and MCP tool results (`isError: true`)
carry the same catalogued codes. The registry of record is pipeline-contract.md §13.
Codes you will meet most often:

| Code | Meaning | Agent response |
|---|---|---|
| `auth.api_key.missing` / `auth.api_key.invalid` | Credential problem | Ask the user for a fresh key |
| `auth.scope.insufficient` | Key lacks the required scope | Ask for a broader key, or change what you asked |
| `pipeline.validation.*` (`cycle_detected`, `dangling_dependency`, `duplicate_node_id`, `parameter_precision_missing`, …) | Pipeline JSON is invalid | Fix the JSON — never retry as-is |
| `template.validation.*` (`syntax_error`, `dangerous_construct`, …) | Template body rejected | Fix the Freemarker and re-render |
| `template.not_found` / `datasource.not_found` | Reference points at nothing | Create the referenced entity or fix the id |
| `pipeline.version.conflict` | The pipeline changed after you loaded it (stale `expected_hash`) | Re-read with `pipelines_get`, rebase your edit onto the current body/hash, retry — NEVER retry blindly |
| `pipeline.version.not_draft` | Release/discard hit a pipeline with no draft | Nothing to act on for an agent — the draft was already released or discarded |
| `pipeline.authoring.disabled` / `template.authoring.disabled` | This server has authoring turned off — it is a promotion receiver | Do not retry; tell the user this server only receives promoted content. Reads, execution and import still work |
| `pipeline.validation.duplicate_name` (on update) | Your draft renames onto a taken name | Pick a different `name`; this fails at write time now, not at release |
| `pipeline.execution.datasource_unreachable` | Source DB down/bad credentials | `datasources_test` to confirm |
| `pipeline.node.query_execution_failed` | A node's SQL failed | Read `node_stats` + `executions_get` for the node error, re-render its template with the failed parameters |
| `pipeline.node.sql_parameter_missing` | The rendered SQL references a `:name` no pipeline parameter declares | Name a declared parameter — or interpolate structure instead |
| `template.validation.parameter_interpolated` | A declared parameter appears inside `${}` | Write `:name` for it — bound values are never parsed as SQL |
| `result.expired` | TTL elapsed on the cursor | Re-execute and page sooner |

Rule of thumb: validation errors are your bug — fix the document, don't retry.
Reachability and TTL errors are the world's state — probe, then retry once.

## When an execution fails

`executions_get` (and `pipelines_execute`'s own failure result) carries the FULL
failure record in `error` — the same object the UI shows and `error_json` stores.
Read it in this order:

1. `error.code` — the catalogued code (the table above says what to do with it).
2. `error.exception.caused_by` — the ROOT CAUSE IS THE **LAST** ENTRY of the chain
   (the wire is outermost-first). Quote `class` + `message` from that entry.
3. `error.sql` — the rendered SQL in `:name` form, exactly as it failed
   (bound values are never in it; they are in the execution's `parameters`).

`error.node` names the datasource, dialect and pinned template; `error.correlation_id`
is the one field that joins this failure to the server log — QUOTE IT whenever you
escalate to a human. On this server `error-detail=full` (the default), the exception
chain and stack frames travel with the error; a deployment may set `structured`, in
which case `error.exception` and `error.sql` are absent and you have the code,
message, node context and correlation id to work with.

An agent that reports "the pipeline failed" without the root cause is doing what
the UI did on 2026-09-02 (T85): the answer was in the event all along.

## Best practices (trouble-free authoring)

1. **Render before you create.** `templates_render` with representative values catches
   wrong SQL, bad interpolation, and dialect drift before a pipeline exists.
2. **Test the datasource first.** `datasources_test` is cheap and answers connectivity
   + credential questions immediately.
3. **Pin versions deliberately.** Nodes pin template versions; bump via
   `pipelines_update` only after re-rendering the new version.
4. **Carry the hash you read.** `pipelines_update` requires `expected_hash` — the
   `body_hash` from `pipelines_get` or your previous update's result. `pipelines_get`
   (and `templates_get`) default to the **working version** — the draft when unreleased
   edits exist, else the latest released — and say which `version`/`status` they returned,
   so you always edit the newest content. The hash is the protocol that keeps two writers
   (you and a human, two sessions, two tabs) from silently overwriting each other; a blind
   retry after a 409 is how an agent destroys a human's edit. Read → edit → write with the
   hash you read.
5. **One caller node, or zero.** Two caller nodes fail validation; use a tempdb node +
   a projection node instead of two outputs.
6. **Stage with tempdb.** Multi-node pipelines chain through `output: tempdb` tables —
   downstream nodes read them with `source: "tempdb"`. Keep table names lower_snake_case
   (H2 lower-folds unquoted identifiers).
7. **Declare parameters honestly.** Required flags with no default make the pipeline
   refuse a bare execute; that is the contract working, not a bug — ask the user for
   values.
8. **Bind values, never interpolate them.** A declared parameter appears in SQL as
   `:name` (bound, never parsed as SQL); `${}` is for structural SQL only. A `:name`
   with no declared parameter fails at execution with
   `pipeline.node.sql_parameter_missing` — name a declared parameter or interpolate
   structure.
9. **Page results immediately.** Read all pages within `ttl_seconds`; long-running
   work between pages risks `result.expired`.
10. **Never put secrets in templates or descriptions.** Credentials live on the
   datasource entity (AES-GCM encrypted at rest). SQL bodies are visible to anyone
   with `read`.
11. **Respect the rate limiter** — batch listing calls, don't hammer `/mcp`.
12. **When debugging a failure**, follow the `debug_failed_execution` prompt flow:
    `executions_get` → failing node's `node_stats` + error → `pipelines_get` →
    `templates_get` → `templates_render` with the failed run's parameters → propose a fix.
13. **Never put a `:bind` parameter inside a GROUP BY expression in H2 (tempdb).**
    H2 fails to match the GROUP BY expression to the identical SELECT expression when it
    contains a parameter marker — `Column "w.prcp_mm" must be in the GROUP BY list`
    (SQLState 90016), a lie that sends you chasing the wrong fix. Compute the classified
    value in a derived table (`FROM (SELECT CASE ... :threshold ... END AS weather ...) x`)
    and `GROUP BY x.weather` — a plain column always matches. Measured on H2 2.3.232
    (2026-09-04, congestion/tip pipelines).
14. **Never divide DECIMAL by DECIMAL in H2 (tempdb) — cast to DOUBLE first.**
    H2's DECIMAL arithmetic collapses result scale (a `DECIMAL(·,2)/DECIMAL(·,0)` division
    can come back scale-0): `SUM(miles)/SUM(seconds)*3600` returned **0 mph** where the
    true answer was ~12, and `100.0 * tip / fare` rounded to one decimal. Cast every
    ratio operand: `CAST(SUM(x) AS DOUBLE) / NULLIF(CAST(SUM(y) AS DOUBLE), 0)`.
    Verified empirically against the pinned driver 2.3.232 — plain DECIMAL gave 2448.00
    where DOUBLE gave the correct 2456.81 (2026-09-04, congestion/tip/OD pipelines).

## Credential encryption and key providers

Three facts, and where to go for the rest:

- **Datasource passwords are write-only.** They are stored AES-256-GCM encrypted, bound to the
  datasource name, and no endpoint, tool or resource ever returns one — reads carry
  `password_set: true` instead. Never try to read a password back, and never echo one you were
  given into a pipeline body, a template, a commit message or a chat summary.
- **Every stored credential carries a key VERSION** (its first byte), so a deployment can rotate
  keys lazily: rows keep decrypting under the key they were written with, and move to the
  current key the next time their password is saved. The operator flow is
  `docs/datasources.md` §7.3 — there is deliberately no rotation endpoint or CLI to call.
- **Where the keys come from is a seam, not a constant.** `datapipelines.db.key-provider`
  selects a `KeyProvider`; `env` ships and is the default. Implementing an AWS/GCP/Azure/Vault
  provider is a written procedure with a shared contract suite every implementation must pass:
  **`docs/key-providers.md`**. If you are asked to "add KMS support", that document is the task
  — do not redesign the crypto.

## REST fallback (when the client has no MCP transport)

Same server, HTTP + JSON, authenticated with `-H "DP-API-Key: dpk_..."`:

```bash
curl -s http://localhost:8080/api/v1/pipelines                     # list
curl -s http://localhost:8080/api/v1/datasources -X POST           # register (admin)
  -H "Content-Type: application/json" -H "DP-API-Key: dpk_..." -d '{...}'
curl -s http://localhost:8080/api/v1/pipelines/{id}/execute -X POST # run (SSE stream)
  -H "Accept: text/event-stream" -H "DP-API-Key: dpk_..." -d '{"parameters": {}}'
curl -s http://localhost:8080/api/v1/executions/{id}/result?offset=0&limit=100
curl -s http://localhost:8080/api/v1/executions/{id} -X DELETE     # cancel
```

**Template addressing (rest-api v2.0):** a template name NEVER travels in a URL path
segment — a name may contain `/`, and an encoded `%2F` in the path is refused `400` by the
container before routing. Address templates by query parameter or body field instead:

```bash
curl -s "http://localhost:8080/api/v1/templates?name=acme/finance/report"        # one template
curl -s "http://localhost:8080/api/v1/templates/versions?name=acme/finance/report&version=1"
curl -s http://localhost:8080/api/v1/templates/render -X POST \
  -H "Content-Type: application/json" -H "DP-API-Key: dpk_..." \
  -d '{"name": "acme/finance/report", "version": 1, "context": {}}'
```

`GET /api/v1/templates` answers two shapes on one route: the single-resource envelope
(`404 template.not_found` on a miss) when `name` is present, the paged list when it is not.
`PUT /api/v1/templates` takes the `id` in the JSON body; release/draft-discard are
`POST /api/v1/templates/release` and `/draft/discard` with `{"name": ...}` in the body.

The execution endpoint answers with an SSE stream of events
(`execution_started`, `node_started`, `node_completed`, `pipeline_completed`,
`data_ready`, …) — the agent-facing MCP tool turns that into one blocking call with
`node_stats` in the result. Everything the MCP tools do is a thin adapter over these
endpoints; error codes are identical.

## References (when working inside the repo)

- `docs/pipeline-contract.md` — pipeline/node JSON schema, validation rules, error catalog §13
- `docs/templates.md` — Freemarker rules, versioning, library templates
- `docs/datasources.md` — dialects, connection properties, credential storage (§7)
- `docs/key-providers.md` — implementing a KMS-backed credential key provider (the contract, the step list, the AWS recipe)
- `docs/enums.md` — every wire value (types, dialects, statuses, scopes)
- `docs/mcp-server.md` — the MCP surface (22 tools, 3 prompts, transport)
- `docs/rest-api.md` — REST endpoints, SSE, result cursor
- `docs/auth.md` — scopes, API keys, the scope↔operation matrix (§7.6)
- `docs/type-system.md` — canonical types and wire encodings
- `docs/versioning.md` — the draft/release lifecycle, the hash-precondition protocol, why agents never release
