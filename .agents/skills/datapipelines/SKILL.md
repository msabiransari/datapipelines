---
name: datapipelines
description: "Author, maintain, and execute declarative SQL data pipelines on the datapipelines.co server. Use when the user asks to create, update, run, debug, or inspect pipelines, templates, datasources, or executions — or when MCP tools like pipelines_create, pipelines_execute, templates_render, templates_create, datasources_test, datasources_get_schemas, datasources_get_tables, datasources_get_columns, executions_get_result, or prompts like analyze_pipeline / create_pipeline_for_question / debug_failed_execution are available. Covers the pipeline JSON schema, Freemarker SQL templates, node types, execution semantics, error handling, and scopes."
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

**Template** — Freemarker SQL: `id` (e.g. `fetch_orders.sql`), `dialect` (one of
`POSTGRES`, `ORACLE`, `MSSQL`, `MYSQL`, `H2`, `DUCKDB`, `SQLITE`), `display_name`,
`description`, `imports` (`[{"id","version","alias"}]` for library macros), `body`,
`is_library`. **There is no params_schema field** — the variables a body may reference
are exactly the calling pipeline's `parameters` keys (defaults applied). The body must
**never** contain `<#import>` / `<#include>` — imports come from the `imports` array and
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

**Dialects** — seven: POSTGRES, ORACLE, MSSQL, MYSQL, H2, DUCKDB, SQLITE. Templates are
dialect-specific; a node's template dialect must match what its `source` can execute.

## Connecting

- **MCP:** Streamable HTTP at `POST {host}/mcp` — stateless, protocol pinned to
  `2025-06-18`. Auth is API-key-only: `DP-API-Key: dpk_<id>.<secret>` or
  `Authorization: Bearer dpk_<id>.<secret>`. Browser session cookies are rejected on
  `/mcp`. REST lives at `/api/v1/**` with `DP-`-prefixed custom headers and a JSON
  envelope (`{"data": ...}` / `{"error": {code, user_message, details}}`).

- **18 MCP tools:** `pipelines_list`, `pipelines_get`, `pipelines_execute`,
  `pipelines_create`, `pipelines_update`, `templates_list`, `templates_get`,
  `templates_create`, `templates_render`, `datasources_list`, `datasources_get`,
  `datasources_get_schemas`, `datasources_get_tables`, `datasources_get_columns`,
  `datasources_test`, `executions_list`, `executions_get`, `executions_get_result`.

- **3 prompts:** `analyze_pipeline` (read-only structural review of a pipeline),
  `create_pipeline_for_question` (ground a new pipeline's SQL in the introspection
  tools, then author it), `debug_failed_execution` (walk a failed execution to a
  diagnosis).

- **Scopes** (hierarchical: `admin ⊃ author ⊃ execute ⊃ read`): `read` = list/get;
  `execute` = run; `author` = create/update pipelines + templates (also template render,
  datasource test, schema introspection, and workspace-bound datasource mutation).
  Datasources are mutated via REST/UI only — workspace-bound CUD needs `author`,
  global CUD needs `admin`. A tool or endpoint rejects with
  `auth.scope.insufficient` when the key's scope is too low.

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
| `result.expired` | TTL elapsed on the cursor | Re-execute and page sooner |

Rule of thumb: validation errors are your bug — fix the document, don't retry.
Reachability and TTL errors are the world's state — probe, then retry once.

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
8. **Page results immediately.** Read all pages within `ttl_seconds`; long-running
   work between pages risks `result.expired`.
9. **Never put secrets in templates or descriptions.** Credentials live on the
   datasource entity (AES-GCM encrypted at rest). SQL bodies are visible to anyone
   with `read`.
10. **Respect the rate limiter** — batch listing calls, don't hammer `/mcp`.
11. **When debugging a failure**, follow the `debug_failed_execution` prompt flow:
    `executions_get` → failing node's `node_stats` + error → `pipelines_get` →
    `templates_get` → `templates_render` with the failed run's parameters → propose a fix.

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

The execution endpoint answers with an SSE stream of events
(`execution_started`, `node_started`, `node_completed`, `pipeline_completed`,
`data_ready`, …) — the agent-facing MCP tool turns that into one blocking call with
`node_stats` in the result. Everything the MCP tools do is a thin adapter over these
endpoints; error codes are identical.

## References (when working inside the repo)

- `docs/pipeline-contract.md` — pipeline/node JSON schema, validation rules, error catalog §13
- `docs/templates.md` — Freemarker rules, versioning, library templates
- `docs/datasources.md` — dialects, connection properties, credential storage
- `docs/enums.md` — every wire value (types, dialects, statuses, scopes)
- `docs/mcp-server.md` — the MCP surface (18 tools, 3 prompts, transport)
- `docs/rest-api.md` — REST endpoints, SSE, result cursor
- `docs/auth.md` — scopes, API keys, the scope↔operation matrix (§7.6)
- `docs/type-system.md` — canonical types and wire encodings
- `docs/versioning.md` — the draft/release lifecycle, the hash-precondition protocol, why agents never release
