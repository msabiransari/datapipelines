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
(`status: "DRAFT"`, `current_version: null`) and it is immediately executable; every later
save is draft-first too: the first save after a release opens a DRAFT (copy-on-write), later
saves overwrite that one draft in place. DRAFT → RELEASED is a human step, with no
exception — including on creation. A save whose body is identical to the released one is a
no-op — nothing opens, no version number burns, and the response says `status: "RELEASED"`
with no draft pointer; that is success, not an error. Your updates are NOT published until
a human releases the draft from the UI — **leave the draft for a human to release** (by
design, D4; there is no release tool and that absence is deliberate). Version RETIREMENT is
human too (101): a human may **discard** a released version (reversible — restore brings it
back), **purge** a draft (irreversible — the row and its executions go), or **switch** the
pointer a pipeline's dependents run; there are no tools for those either, by the same rule.
Pipeline nodes pin template versions immutably; updating a template does not change
existing pipelines until you update the node reference. Drafts are executable — running
your own draft is the expected test loop.

**In SQL, write `:name`, never `${name}`, for a declared parameter.** Bound values are
never parsed as SQL — that is the whole point: a `STRING` caller value cannot alter the
statement, while the interpolated form puts it inside the SQL string. Pipeline save refuses
the old form with `template.validation.parameter_interpolated`. `${}` stays for
**structure** — table names, dynamic `IN` lists, `ORDER BY` fragments — which you keep safe
yourself (never interpolate a caller-supplied value there). Bound values need no quoting:
`BETWEEN :start_date AND :end_date`, not `BETWEEN DATE ':start_date' AND …`.

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
- **Shared macros live under `<owner>/lib/`** (`acme/lib/metrics.sql`), beside their owner,
  not at the root.
- **Scratch lives under `test/`** (`test/scratch`, `test/od_matrix_spike`).

**List the roots FIRST, and ask before minting a new one.** Before you create anything:

```
pipelines_list  {"prefix": ""}     → the roots, each with a count
templates_list  {"prefix": ""}     → the same, for templates
pipelines_list  {"prefix": "acme"} → one level down: sub-folders + the pipelines directly there
```

Reuse an existing root. If none fits, **ask the human** — a new root is a claim about how the
workspace is organised, and there is no rename to take it back. Never mint a root silently.

**`prefix` browses; `q` searches.** `prefix` returns ONE level (`{prefix, folders, pipelines |templates, total,
has_more}`) — direct sub-folders with counts, plus that level's own leaves. `q` is a flat substring search across
full paths. Use `prefix` to learn the shape, `q` to find a thing you can already half-name.

## The golden path (authoring a new pipeline)

0. **Pick the folder.** `pipelines_list {"prefix": ""}` and `templates_list {"prefix": ""}`
   to see which roots this workspace already uses, then drill in with `{"prefix": "<root>"}`.
   Reuse a root. **A new root is refused until you confirm it: ask the person first, then pass
   `confirm_new_root: true`** — `pipelines_create` and `templates_create` answer
   `pipeline.validation.new_root_requires_confirmation` / `template.validation.new_root_requires_confirmation`
   with `details.existing_roots` listing what already exists. `test/` never needs it, and when the
   person has named the folder, pass `confirm_new_root: true` on the FIRST create — the
   confirmation is theirs, already given.
   Everything you create in the steps below goes under the prefix you settle on here — and it
   cannot be moved later.
1. **Learn before you assume.** You know nothing about a datasource until you have read it —
   not its time zone, not its units, not whether a table is a sample or a census, not what a coded
   value means. `datasources_list` is your first call and your first read: for every granted datasource
   it carries the description, the dialect and the `facts` earlier sessions recorded (window,
   sampling, units) — read them before you touch a table. Then, for EVERY datasource the pipeline
   will touch: `datasources_get_schemas` → `datasources_get_tables(namespace)` → `datasources_get_columns(table,
   namespace)` for every table the SQL will read — a LOOKUP is a table the SQL reads, and so is
   a table a template you PIN reads; `_get_columns` and `_get_table_stats` on each, before the
   first `templates_create` — each with its reported `namespace` array →
   `datasources_get_table_stats` → `sql_probe` a few rows and the distinct values of every column you
   will filter, group or join by. A lake table with `partition_column: null` in the listing has
   nothing to prune on — filter pushdown inside a file is not pruning. Descriptions and `remarks`
   are one input, written by a person; **the columns and the rows are the ground truth** — never
   write SQL against a column, a unit, a time zone or a sample rate you have not seen. Write what
   you learned into the pipeline's description. `references/authoring-playbook.md` §1 is the full procedure.

   **Read the `facts` that arrive with the listings, before you probe.** `datasources_list` carries
   the datasource-wide learned facts (its time window, whether it is a sample), `_get_tables` each
   table's (grain, caveats), `_get_columns` each column's (units, time zones, what a coded value
   means, joins) — what earlier sessions recorded, each with its `trust` and evidence. `observed` or
   `verified` with evidence saves you the probe; `stale` or `needs_review` is a warning, not a truth —
   re-verify it. `kind: definition` facts on the listing are rules earlier pipelines chose — read
   them before you choose yours. `datasources_get` is the same facts for one datasource — the
   refresh after you `semantics_record`.

   **Record what you learned, with the query that showed it.** After you have established a
   fact about the data that introspection could not tell you — a unit, a time zone, a sample
   rate, a grain, what a coded value means, a join that holds — call `semantics_record` with
   the probe you ran; when you re-verified a stale fact, record the superseding one. Never
   record what introspection already returns (types, keys, comments).

   Before your first `templates_create`, state — in your reasoning or your reply — which of
   these calls you made for EACH datasource and EACH table the SQL reads; a table you did
   not `_get_columns` and `_get_table_stats` is a table you may not read.
1½. **Probe before you write.** Call `datasources_get_table_stats` on every table the SQL
   will touch (row estimate, indexes, per-column bounds — catalog reads, never a scan), then
   `sql_probe` the exact SELECT with representative parameters and read `plan.scan` and
   `wall_ms` — a `seq` plan on a large table is the timeout you would meet in step 5, found
   while it is still cheap. A probe that settles a question about the DATA (not the plan) —
   "is `reading` already in the unit `unit` names?", "is `occurred_at` UTC or wall-clock?" —
   is a fact: record it (step 1) so the next session skips the probe. Only then write the
   template.
2. **Write the template.** `templates_create` with `dialect` matching the source, a
   Freemarker body, and a `description` that names every parameter the body expects
   (the description is the only discoverability mechanism for parameters). Before
   `templates_create` of a lookup or reference table — zones, calendars, stations, code lists —
   `templates_list {"q": "<table>"}` and PIN what exists; a narrowed variant (a region dropped,
   a year fixed) is a `WHERE` in the consumer or a parameter, not a new template. **To change a
   draft template, read its `body_hash` with `templates_get` and call `templates_update`** — it
   writes the DRAFT the same way `pipelines_update` writes a pipeline's. `templates_purge_draft`
   is for a template that should not exist, not for editing one, and it is refused once a
   pipeline pins the template.
3. **Preview the SQL.** `templates_render` with a representative context — save-time
   validation is parse-only, so this is your check that the SQL is actually what you
   meant. This is mandatory before step 4 for anything non-trivial — including one you create
   after the pipeline exists; the run is not its render. Every template the pipeline pins — the
   tempdb ones included: a `sql_probe {"name": "tempdb"}` checks syntax and names,
   `templates_render` checks what the Freemarker actually emits, and they are not the same check.
4. **Create the pipeline.** `pipelines_create` with `parameters` declared (types +
   required/defaults — remember `DECIMAL` needs `precision`; `required: true` and
   `default` are exclusive — a parameter with a default is optional by definition), nodes
   referencing the template `{id, version}`, `depends_on` wiring, and `output` blocks for
   staging/write-back. Save-time validation dry-renders every template against the
   declared parameters and rejects anything that would not run. **Create lands v1 as a
   DRAFT** — executable immediately, and not published: `current_version` comes back null and
   the response carries the `draft` pointer with the `body_hash` for your next write.
5. **Iterate on the DRAFT, and run it.** `pipelines_update` (requires the `expected_hash` you
   read — see Best practices) writes the DRAFT: the first update opens it, later updates
   overwrite it, so iterating never piles up versions. `pipelines_execute` with no `version`
   runs the **working version** — your draft when one exists, else the latest release — so
   testing your own work needs no version argument at all. **Then stop**: leave the draft for a
   human to release from the UI. Never claim your change is live — it is not until released, and
   no tool you have releases anything.
6. **Read the result.** Inline first page + `total_rows` + `has_more` + `ttl_seconds`.
   Page the remainder with `executions_get_result` (`offset`/`limit`) **within the
   TTL** — afterwards the result is gone (`result.expired`).

## Execution semantics agents must know

- `pipelines_execute` is a **single blocking call** — it returns only when the execution
  reaches a terminal state (`SUCCESS` / `FAILED` / `ABORTED`) or the execution timeout
  (default 600 s) aborts it. There are no progress notifications in v1; the final result
  carries `node_stats` (per-node status, durations, row counts, errors) — the authoritative
  per-node record. A 3-minute pipeline is one 3-minute tool call.
- **Drafts are executable, and a draft run is not a release.** Executing your own draft is
  the expected test loop; history marks those runs (`draft_run`) and they never count as validation for release — the human decides that.
- **Cancellation:** `executions_cancel` cancels a RUNNING execution your own MCP calls
  started — the same-credential rule: `triggered_via` MCP, your user, and an audit row
  pairing THIS key with the execution's correlation id (an execution started over REST,
  the UI, or another key of the same user is refused, and the refusal says which rule
  fired). Cancellation is requested, not awaited: poll `executions_get` for the terminal
  `ABORTED`. For anything the rule refuses, the out-of-band REST `DELETE /api/v1/executions/{id}` remains.
- **Abandoned calls** run to completion — a dropped HTTP request has no disconnect
  callback on `/mcp`; cancel with `executions_cancel` or let the timeout handle it.
- **Idempotency:** REST execute accepts `Idempotency-Key`; the MCP tool has no key carrier.
  If you are unsure whether a previous execute landed, check `executions_list` rather than firing a duplicate.
- **Zero-caller pipelines** return stats with no rows — that is a valid design, not a
  failure.
- `/mcp` and `/api/v1` share a per-user rate limiter — back off on `429`.

## Promotion is not yours to trigger

Moving released content from one deployment to another (dev → uat → prod) is **promotion**,
and it is a **human action from the UI, deliberately**. There is no MCP tool for it, no
schedule that runs it, and no REST endpoint you can call: the promotion route accepts only a
`server`-kind key one deployment holds for another, presented as `DP-Promotion-Key` — never
your API key, never a session (a server key presented as an ordinary `DP-API-Key` is refused
everywhere, this surface included). This is a design decision, not a gap — a release reaching
production is a decision a person makes.

- **Never offer to promote, and never claim you did.** If asked, say what promotion is and
  point at the Promotion screen in the UI.
- **A "hotfix on prod" is not a thing here.** A receiver deployment refuses every authoring
  write with `pipeline.authoring.disabled` / `template.authoring.disabled` — that refusal is
  the system working; the fix is a new release in the authoring environment, promoted like
  any other change. If you meet that code, you are pointed at the wrong deployment.
- **What you CAN do is make a release promotable**: author, render, execute the draft, and
  tell the human it is ready to release. Release itself is also theirs.

## Error handling

Every failure is structured — REST envelopes and MCP tool results (`isError: true`)
carry the same catalogued codes. The registry of record is pipeline-contract.md §13, and
`references/error-codes.md` lists the codes you will meet most often with the response each
one calls for.

Rule of thumb: validation errors are your bug — fix the document, don't retry.
Reachability and TTL errors are the world's state — probe, then retry once.

## When an execution fails

`executions_get` (and `pipelines_execute`'s own failure result) carries the FULL
failure record in `error` — the same object the UI shows and `error_json` stores. Read it in this order:

1. `error.code` — the catalogued code (`references/error-codes.md` says what to do with it).
2. `error.exception.caused_by` — the ROOT CAUSE IS THE **LAST** ENTRY of the chain
   (the wire is outermost-first). Quote `class` + `message` from that entry.
3. `error.sql` — the rendered SQL in `:name` form, exactly as it failed
   (bound values are never in it; they are in the execution's `parameters`).

`error.node` names the datasource, dialect and pinned template; `error.correlation_id`
is the one field that joins this failure to the server log — QUOTE IT whenever you
escalate to a human. On this server `error-detail=full` (the default), the exception
chain and stack frames travel with the error; a deployment may set `structured`, in which
case `error.exception` and `error.sql` are absent and you have the code, message, node
context and correlation id to work with.

An agent that reports "the pipeline failed" without the root cause is doing what the UI did
on 2026-09-02 (T85): the answer was in the event all along.

## Best practices (trouble-free authoring)

**Read `references/authoring-playbook.md` before building anything with more than two
nodes** — the judgment between the golden path's steps: the question's grain, the lookups and
display names, table roles from naming, aggregating at the source, indexing a staged table
after loading it, filtering a lake table on its partition column, the `CREATE INDEX`
suggestion in your handback, `depends_on` as data flow, a timeout as work in the wrong place,
casting across engines, sample vs census, one independent number, stopping at the draft. Its
Do/Don't table is one screen; each row is a mistake an agent made here.

1. **Render before you create.** `templates_render` with representative values catches
   wrong SQL, bad interpolation, and dialect drift before a pipeline exists.
2. **Test the datasource first.** `datasources_test` is cheap and answers connectivity
   + credential questions immediately.
2½. **Learned facts are shared memory — keep them honest.** A fact you record with
   `evidence_sql` is `observed`; without it, only `asserted` — except a `definition`, `exclusion`
   or `preference`, which is a choice and lands `asserted` until a person confirms it. Two facts
   of one kind on the same column are both served, flagged `conflict` — a reader decides, the
   store never picks. To correct one, record the replacement with `supersedes` (the old one
   retires as `superseded`); `semantics_retire` alone is for a fact that is simply wrong. **`refs`
   names every table the `fact` text names, spelled as the catalog spells it** — a catalog table
   the text names exactly and `refs` omit is ADDED for you (`refs_added` in the response), a
   near-miss is refused with the nearest name (`semantics.ref_mismatch`) — and a DATASOURCE-scope
   fact is table-wide: "in Q4 only A and B carry rows" is a pipeline description's job, not a
   fact's. A DATASOURCE fact is visible to every workspace the datasource is granted to; a
   `definition`, `exclusion` or `preference` (WORKSPACE scope) stays in yours. A rule the
   question leaves to you (what counts as rainy, active, late) is a `definition` — rule 13 says
   when to record one and when to reuse one an earlier pipeline recorded.
3. **Pin versions deliberately.** Nodes pin template versions; bump via `pipelines_update` only after re-rendering the new version.
4. **Carry the hash you read.** `pipelines_update` and `templates_update` require
   `expected_hash` — the `body_hash` from `pipelines_get`/`templates_get` or your previous
   update's result. `pipelines_get` (and `templates_get`) default to the **working
   version** — the draft when unreleased edits exist, else the latest released — and say
   which `version`/`status` they returned, so you always edit the newest content. The hash
   is the protocol that keeps two writers (you and a human, two sessions, two tabs) from
   silently overwriting each other; a blind retry after a 409 is how an agent destroys a
   human's edit. Read → edit → write with the hash you read.
5. **One caller node, or zero.** Two caller nodes fail validation; use a tempdb node + a projection node instead of two outputs.
6. **Stage with tempdb.** Multi-node pipelines chain through `output: tempdb` tables —
   downstream nodes read them with `source: "tempdb"`. Keep table names lower_snake_case
   (H2 lower-folds unquoted identifiers).
7. **Declare parameters honestly.** Required flags with no default make the pipeline
   refuse a bare execute; that is the contract working, not a bug — ask the user for values.
8. **Bind values, never interpolate them.** A declared parameter appears in SQL as
   `:name` (bound, never parsed as SQL); `${}` is for structural SQL only. A `:name`
   with no declared parameter fails at execution with
   `pipeline.node.sql_parameter_missing` — name a declared parameter or interpolate
   structure.
9. **Page results immediately.** Read all pages within `ttl_seconds`; long-running work between pages risks `result.expired`.
10. **Never put secrets in templates or descriptions.** Credentials live on the
   datasource entity (AES-GCM encrypted at rest). SQL bodies are visible to anyone with `read`.
11. **Respect the rate limiter** — batch listing calls, don't hammer `/mcp`.
12. **When debugging a failure**, follow the `debug_failed_execution` prompt flow:
    `executions_get` → failing node's `node_stats` + error → `pipelines_get` →
    `templates_get` → `templates_render` with the failed run's parameters → propose a fix.
13. **Parameters wear the question's vocabulary; technical inputs are derived.** A question
    that names a period — "2024", "Q3", "last month", "2023 to 2024" — gets that period's
    parameter (`year`, `quarter`, `base_year`/`comp_year`, an anchor date), and the calculator
    or in-dialect date math derives the bounds; two raw dates are an INPUT to a template,
    never the door of a pipeline. A relative phrase's door is an anchor date — `$current_date`
    for a live one; for a fixed dataset, the data's last date for a "this period" phrase but
    **the day AFTER the data's last date for a "last N periods" phrase** — those kinds resolve
    the complete periods before the one CONTAINING the anchor (data ends 2026-06-30: "last
    quarter" anchors `2026-07-01` → 2026-04-01..2026-06-30; `2026-06-30` → Q1). **Any relative
    time phrase in the question — "last", "this", "to date", "trailing", "N ago" — is resolved
    by reading `calculators_list`:** each kind lists the everyday phrases it answers; pick the
    kind whose phrases match the question's words, and when two kinds both fit, ask the person
    which one. A CALCULATOR node then writes the technical inputs into the execution Context,
    which downstream SQL binds as `:start_date` / `:end_date`
    ([calculators.md](../../../docs/calculators.md)); a kind may write several keys at once —
    the catalog's `outputs` says which. Write the interpretation you chose into
    the pipeline's `description` in the question's own words, and name the window the same way
    in every template's `description`. The calculator's `context_key` is already an optional
    execute input — never also declare it as a parameter
    (`pipeline.validation.calculator_output_collision`). When the question leaves a threshold
    or a rule to you — what counts as rainy, active, churned, late — write the rule you chose
    into the description AND record it as a `definition` fact on the workspace, with the probe
    that showed the distribution you chose over; before choosing, read the listing's facts — a
    `definition` an earlier pipeline recorded is the one to reuse, so two pipelines in one
    workspace never answer "rainy" two ways.
13½. **A number you did not measure is not a number.** Row counts, sample rates and windows come
    from `datasources_get_table_stats`, a probe, or a metadata table — never estimated. **A claim
    about the DATA is a probe you ran or a registry line you read:** partition layout from the
    stats read (no `partition` index = one unpartitioned file, whatever the plan's pushdown shows);
    a column's values over the WHOLE table from a whole-table probe, never a lookup or a
    description; "census, not sample" from a reconciled count or an `asserted` record. **And a
    cause is a claim too:** the REASON for a number you noticed — why a ratio collapsed, why a
    month is empty — is named only after the probe that shows it (`ORDER BY <measure> DESC
    LIMIT 5` finds an outlier in one call; a join to the flag you suspect shows whether it
    correlates), and never names a mechanism — "the generator", "the feed" — you have not seen.
    That includes the PLATFORM: when a tool behaves unexpectedly, report what you observed and
    what you did about it; never name the server's mechanism — you cannot see it, and a wrong
    mechanism in a handback becomes somebody's wrong fix.
    **And a description carries data facts and the interpretation you chose —
    never claims about your own process:** "validated", "reproduced", "checked" go in your reply —
    a description outlives the validation, and it is an exemplar the next agent copies. When the
    data cannot reveal a fact you depend on (a sample rate no table states, a time zone no type
    states): write the assumption into the pipeline's `description`, `semantics_record` it WITHOUT
    evidence so it lands as `asserted` for a human to verify — that record, not your reply, is what
    the next session finds — and say in the reply which facts you derived and which you assumed. An
    assumption that moves the answer by an order of magnitude: stop and ask first.
14. **Engine quirks** — a `:bind` inside an H2 GROUP BY, DECIMAL ÷ DECIMAL in H2, H2's
    `C1…` `VALUES` columns, `rows` on MySQL/DuckDB, the one-datasource wall for source
    nodes and probes: `references/authoring-playbook.md` §6, read it before your first
    tempdb node.

## References — open one when you need it

- **`references/pipeline-schema.md`** — writing or reading a pipeline body.
- **`references/node-types.md`** — wiring the DAG.
- **`references/authoring-playbook.md`** — building anything non-trivial.
- **`references/templates.md`** — writing SQL: templates, library imports, CALCULATOR nodes.
- **`references/naming.md`** — choosing where a new pipeline or template lives.
- **`references/connecting.md`** — a first call, a scope or credential refusal, no MCP transport.
- **`references/dp-lake.md`** — the data is Parquet or Iceberg on S3, not in a database.
- **`references/endpoints.md`** — a released read-only pipeline answering a plain HTTP GET.
- **`references/error-codes.md`** — a tool answered `isError: true`; the code's meaning and response.
- **`references/tools.md`** — every MCP tool, generated from the server's catalog at build time.

Each of these is served as the MCP resource `datapipelines://docs/skill/<name>` and by the
`docs_get` tool (`docs_list` names them), and a deployment serves them at
`GET /skill/<name>.md`; inside a checkout they are files in `references/` beside this one.

## References (when working inside the repo)

- `docs/pipeline-contract.md` — pipeline/node JSON schema, validation rules, error catalog §13
- `docs/templates.md` — Freemarker rules, versioning, library templates
- `docs/datasources.md` — dialects, connection properties, credential storage (§7), dp-lake (§8C)
- `docs/type-system.md` — canonical types and wire encodings
- `docs/mcp-server.md` — the MCP surface (tools, prompts, transport)
- `docs/rest-api.md` — REST endpoints, SSE, result cursor, and §19's published endpoints: the path grammar, the read-only rule, the status table
- `docs/auth.md` — scopes, API keys, the scope↔operation matrix (§7.6), key kinds and the hierarchical binding rule (§7.7)
- `docs/enums.md` — every wire value (types, dialects, statuses, scopes)
- `docs/key-providers.md` — implementing a KMS-backed credential key provider (the contract, the step list, the AWS recipe)
- `docs/versioning.md` — the draft/release lifecycle, the hash-precondition protocol, why agents never release
