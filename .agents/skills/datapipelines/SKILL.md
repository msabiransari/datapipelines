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

**Pipeline** — a JSON document: `schema_version`, `name` (machine name, and a **folder
path** — see *Folders* below), `display_name`, `description`, `parameters` (typed input
map), and `nodes` (the DAG). `id`, `version`, `owner`, timestamps are server-assigned on
create.

**Versioning** — **everything you author is a DRAFT, always.** Create lands v1 as a DRAFT
(`status: "DRAFT"`, `current_version: null`) and it is immediately executable; every later save
is draft-first too: the first save after a release opens a DRAFT (copy-on-write), later saves
overwrite that one draft in place. DRAFT → RELEASED is a human step, with no exception —
including on creation. A save whose body is identical to the released one is a
no-op — nothing opens, no version number burns, and the response says `status: "RELEASED"`
with no draft pointer; that is success, not an error. Your updates are NOT published until
a human releases the draft from the UI — **leave the draft for a human to release** (by
design, D4; there is no release tool and that absence is deliberate). Version RETIREMENT is
human too (101): a human may **discard** a released version (reversible — restore brings it
back), **purge** a draft (irreversible — the row and its executions go), or **switch** the
pointer a pipeline's dependents run; there are no tools for those either, by the same rule.
Pipeline nodes pin template versions immutably; updating a template does not change existing
pipelines until you update the node reference. Drafts are executable — running your own draft
is the expected test loop.

**In SQL, write `:name`, never `${name}`, for a declared parameter.** Bound values are
never parsed as SQL — that is the whole point: a `STRING` caller value cannot alter the
statement, while the interpolated form puts it inside the SQL string. Pipeline save
refuses the old form with `template.validation.parameter_interpolated`. `${}` stays for
**structure** — table names, dynamic `IN` lists, `ORDER BY` fragments — which you keep
safe yourself (never interpolate a caller-supplied value there). Bound values need no
quoting: `BETWEEN :start_date AND :end_date`, not `BETWEEN DATE ':start_date' AND …`.

**Dialects** — eight: POSTGRES, ORACLE, MSSQL, MYSQL, H2, DUCKDB, SQLITE, LAKE. Templates are
dialect-specific; a node's template dialect must match what its `source` can execute. `LAKE`
is object storage read in place — see `references/dp-lake.md`.

## Folders — how you NAME a pipeline or a template

Pipelines and templates share one naming grammar and one organising convention. The name IS
the path; there is no folder object anywhere.

**Grammar (both kinds).** 2–10 `/`-separated segments, each starting `[a-z0-9]` and
continuing `[a-z0-9_.-]`, ≤ 64 chars per segment, ≤ 200 total. Lower-case only, no `@`, no
backslash, no `.`/`..` segments, no leading/trailing/double slash.

**A folder is REQUIRED.** `active_users` is refused; `test/active_users` is accepted. The
refusal is `pipeline.validation.name_invalid` / `template.validation.id_invalid` with
`details.reason: "folder_required"` — that is how you tell "you forgot the folder" from "you
used a bad character" (`reason: "grammar"`). The root holds folders only, and there is no
rename (§4.5), so a name minted at the root would be stuck there forever — which is the whole
reason the rule exists.

**`test/` is the scratch folder.** Experiments, spikes and throwaways go to `test/…` — never
to a new root and never (it is impossible now) to the root itself. No folder is reserved and
none is auto-created: a folder exists exactly when something is named under it.

**Workspace = who may see and run. Root segment = who owns.** The workspace is the isolation
boundary — membership decides who can read and execute. The root segment is an organising
claim, not a permission: putting a pipeline under `finance/` grants nobody anything.

**Your key is pinned to one workspace and can do at most what its issuer can do there;
nothing from another workspace exists for you.** A name or id you did not get from a listing
is not-found, never "forbidden" — so guessing tells you nothing, and a listing is the only
truth about what is there.

**Shape: `<owner>/<area>/<asset>`.** 2–4 levels is typical.

- **A pipeline and the templates it uses share a prefix.** That is the whole payoff — one
  prefix query shows an area's work whichever kind you browse.
- **Shared macros live under `<owner>/lib/`** (`nyc/lib/metrics.sql`), beside their owner,
  not at the root.
- **Scratch lives under `test/`** (`test/scratch`, `test/od_matrix_spike`).

**List the roots FIRST, and ask before minting a new one.** Before you create anything:

```
pipelines_list  {"prefix": ""}     → the roots, each with a count
templates_list  {"prefix": ""}     → the same, for templates
pipelines_list  {"prefix": "nyc"}  → one level down: sub-folders + the pipelines directly there
```

Reuse an existing root. If none fits, **ask the human** — a new root is a claim about how the
workspace is organised, and there is no rename to take it back. Never mint a root silently.

**`prefix` browses; `q` searches.** `prefix` returns ONE level (`{prefix, folders, pipelines
|templates, total, has_more}`) — direct sub-folders with counts, plus that level's own
leaves. `q` is a flat substring search across full paths. Use `prefix` to learn the shape,
`q` to find a thing you can already half-name.

## The golden path (authoring a new pipeline)

0. **Pick the folder.** `pipelines_list {"prefix": ""}` and `templates_list {"prefix": ""}`
   to see which roots this workspace already uses, then drill in with `{"prefix": "<root>"}`.
   Reuse a root. **A new root is refused until you confirm it: ask the person first, then pass
   `confirm_new_root: true`** — `pipelines_create` and `templates_create` answer
   `pipeline.validation.new_root_requires_confirmation` /
   `template.validation.new_root_requires_confirmation` with `details.existing_roots` listing
   what already exists. `test/` never needs it. Everything you create in the steps below goes
   under the prefix you settle on here — and it cannot be moved later.
1. **Verify the source.** `datasources_test` (or `datasources_list`/`datasources_get`)
   to confirm name + dialect + connectivity, then introspect the schema:
   `datasources_get_schemas` → `datasources_get_tables(namespace)` →
   `datasources_get_columns(table, namespace)` for every table the SQL will touch, passing
   each table's reported `namespace` array through. Never write SQL against recalled column
   names.
1½. **Probe before you write.** Call `datasources_get_table_stats` on every table the SQL
   will touch (row estimate, indexes, per-column bounds — catalog reads, never a scan), then
   `sql_probe` the exact SELECT with representative parameters and read `plan.scan` and
   `wall_ms` — a `seq` plan on a large table is the timeout you would meet in step 5, found
   while it is still cheap. Only then write the template.
2. **Write the template.** `templates_create` with `dialect` matching the source, a
   Freemarker body, and a `description` that names every parameter the body expects
   (the description is the only discoverability mechanism for parameters). **To change a
   draft template, read its `body_hash` with `templates_get` and call `templates_update`** —
   it writes the DRAFT the same way `pipelines_update` writes a pipeline's. `templates_purge_draft`
   is for a template that should not exist, not for editing one, and it is refused once a
   pipeline pins the template.
3. **Preview the SQL.** `templates_render` with a representative context — save-time
   validation is parse-only, so this is your check that the SQL is actually what you
   meant. This is mandatory before step 4 for anything non-trivial.
4. **Create the pipeline.** `pipelines_create` with `parameters` declared (types +
   required/defaults — remember `DECIMAL` needs `precision`), nodes referencing the
   template `{id, version}`, `depends_on` wiring, and `output` blocks for
   staging/write-back. Save-time validation dry-renders every template against the
   declared parameters and rejects anything that would not run. **Create lands v1 as a
   DRAFT** — executable immediately, and not published: `current_version` comes back null and
   the response carries the `draft` pointer with the `body_hash` for your next write.
5. **Iterate on the DRAFT, and run it.** `pipelines_update` (requires the `expected_hash` you
   read — see Best practices) writes the DRAFT: the first update opens it, later updates
   overwrite it, so iterating never piles up versions. `pipelines_execute` with no `version`
   runs the **working version** — your draft when one exists, else the latest release — so
   testing your own work needs no version argument at all. **Then stop**: leave the draft for a
   human to release from the UI. Never claim your change is live — it is not until released,
   and no tool you have releases anything.
6. **Read the result.** Inline first page + `total_rows` + `has_more` + `ttl_seconds`.
   Page the remainder with `executions_get_result` (`offset`/`limit`) **within the
   TTL** — afterwards the result is gone (`result.expired`).

## Execution semantics agents must know

- `pipelines_execute` is a **single blocking call** — it returns only when the execution
  reaches a terminal state (`SUCCESS` / `FAILED` / `ABORTED`) or the execution timeout
  (default 600 s) aborts it. There are no progress notifications in v1; the final result
  carries `node_stats` (per-node status, durations, row counts, errors) — the
  authoritative per-node record. A 3-minute pipeline is one 3-minute tool call.
- **Drafts are executable, and a draft run is not a release.** Executing your own draft is
  the expected test loop; history marks those runs (`draft_run`) and they never count as
  validation for release — the human decides that.
- **Cancellation:** `executions_cancel` cancels a RUNNING execution your own MCP calls
  started — the same-credential rule: `triggered_via` MCP, your user, and an audit row
  pairing THIS key with the execution's correlation id (an execution started over REST,
  the UI, or another key of the same user is refused, and the refusal says which rule
  fired). Cancellation is requested, not awaited: poll `executions_get` for the terminal
  `ABORTED`. For anything the rule refuses, the out-of-band REST
  `DELETE /api/v1/executions/{id}` remains.
- **Abandoned calls** run to completion — a dropped HTTP request has no disconnect
  callback on `/mcp`; cancel with `executions_cancel` or let the timeout handle it.
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
route accepts only a `server`-kind key that one deployment holds for another, presented as
`DP-Promotion-Key` — never your API key and never a session (a server key presented as an
ordinary `DP-API-Key` is refused everywhere, this surface included). This is a design decision, not a gap — a release reaching production is
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
carry the same catalogued codes. The registry of record is pipeline-contract.md §13, and
`references/error-codes.md` lists the codes you will meet most often with the response
each one calls for.

Rule of thumb: validation errors are your bug — fix the document, don't retry.
Reachability and TTL errors are the world's state — probe, then retry once.

## When an execution fails

`executions_get` (and `pipelines_execute`'s own failure result) carries the FULL
failure record in `error` — the same object the UI shows and `error_json` stores.
Read it in this order:

1. `error.code` — the catalogued code (`references/error-codes.md` says what to do with it).
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

**Read `references/authoring-playbook.md` before building anything with more than two
nodes** — the judgment between the golden path's steps: read the question's grain first,
join the lookups and answer with display names, infer table roles from naming when nothing
is described, aggregate at the source and ship the answer's grain, index a large staged
table only after loading it, filter a lake table on its PARTITION column (never a
sibling timestamp inside the files or a UNION of ranges), analyse every source node's predicate against the
table's indexes after it runs and put the `CREATE INDEX` suggestion in your handback, keep
`depends_on` to data flow, treat a timeout as work in the
wrong place, cast what you ship across engines, never compare a sample to a census, validate
one number independently, and stop at the draft. Each of those is a mistake an agent made
here.

1. **Render before you create.** `templates_render` with representative values catches
   wrong SQL, bad interpolation, and dialect drift before a pipeline exists.
2. **Test the datasource first.** `datasources_test` is cheap and answers connectivity
   + credential questions immediately.
3. **Pin versions deliberately.** Nodes pin template versions; bump via
   `pipelines_update` only after re-rendering the new version.
4. **Carry the hash you read.** `pipelines_update` and `templates_update` require
   `expected_hash` — the `body_hash` from `pipelines_get`/`templates_get` or your previous
   update's result. `pipelines_get`
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
13. **Parameters wear the question's vocabulary; technical inputs are derived.** Declare the
    parameter the PERSON asked in — a question asked in quarters takes `quarter` (`2024-Q4`),
    not a `start_date`/`end_date` pair the caller has to compute. Derive the technical inputs
    inside the pipeline with a CALCULATOR node (a `quarter_bounds`-style catalog function
    writing `start_date`/`end_date` into the execution Context, which downstream SQL binds as
    `:start_date` / `:end_date`) — see `calculators_list` and
    [calculators.md](../../../docs/calculators.md). A raw date range as the ONLY door makes
    every caller re-derive what the pipeline already knows, and every caller derive it slightly
    differently. Both doors may exist: a derived Context key is an optional execute input, so
    supplying `start_date` directly skips the calculator.
14. **Never put a `:bind` parameter inside a GROUP BY expression in H2 (tempdb).**
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

## References — open one when you need it

Each line says when to open the file; none of them is required reading first.

- **`references/pipeline-schema.md`** — writing or reading a pipeline body: the
  `parameters` block's fields and types, and a minimal complete pipeline to copy.
- **`references/node-types.md`** — wiring the DAG: what a node declares, the five node
  types, and where a DQL node's rows go.
- **`references/authoring-playbook.md`** — building anything non-trivial: how an expert
  reads the question and the schema, shapes the DAG (push down, ship little, then index),
  gets the numbers right, and finishes — with the Do/Don't table of real misses.
- **`references/templates.md`** — writing SQL: what a template is, how library imports
  work, and how a CALCULATOR node computes a value the SQL then binds.
- **`references/naming.md`** — choosing where a new pipeline or template lives, or
  explaining the folder rules to a human.
- **`references/connecting.md`** — your first call against a deployment, a refusal for
  scope or credential reasons, or a client with no MCP transport.
- **`references/dp-lake.md`** — the data is Parquet or Iceberg on S3, not in a database.
- **`references/endpoints.md`** — a released read-only pipeline has to answer a plain
  HTTP GET.
- **`references/error-codes.md`** — a tool answered `isError: true` and you need the
  code's meaning and the response it calls for.
- **`references/tools.md`** — every MCP tool with its arguments, scope and whether it
  writes. Generated from the server's own catalog at build time, so it is never stale.

The MCP resource `datapipelines://docs/skill/<name>` serves each of these, and a
deployment serves them at `GET /skill/<name>.md`; inside a checkout they are files in
`references/` beside this one.

## References (when working inside the repo)

- `docs/pipeline-contract.md` — pipeline/node JSON schema, validation rules, error catalog §13
- `docs/rest-api.md` §19 — published endpoints: the path grammar, the read-only rule, the status table
- `docs/auth.md` §7.7 — key kinds and the hierarchical binding rule
- `docs/templates.md` — Freemarker rules, versioning, library templates
- `docs/datasources.md` — dialects, connection properties, credential storage (§7), dp-lake (§8C)
- `docs/key-providers.md` — implementing a KMS-backed credential key provider (the contract, the step list, the AWS recipe)
- `docs/enums.md` — every wire value (types, dialects, statuses, scopes)
- `docs/mcp-server.md` — the MCP surface (35 tools, 3 prompts, transport)
- `docs/rest-api.md` — REST endpoints, SSE, result cursor
- `docs/auth.md` — scopes, API keys, the scope↔operation matrix (§7.6)
- `docs/type-system.md` — canonical types and wire encodings
- `docs/versioning.md` — the draft/release lifecycle, the hash-precondition protocol, why agents never release
