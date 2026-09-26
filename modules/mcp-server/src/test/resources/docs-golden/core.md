
# datapipelines

A self-hosted server that executes **declarative JSON pipelines** — DAGs of templated-SQL
nodes — against heterogeneous databases, staging intermediate results in a per-execution
in-memory H2 and returning results through a Redis-backed cursor. It is **MCP-native**: agents
author and execute pipelines as first-class clients beside a REST API and a browser UI.

## How this manual works

`docs_list` lists this core and one entry per area (each entry nests its area's references);
`docs_get` returns a document, or one section by its id for anything large. The same documents
answer as `datapipelines://docs/<name>` resources and, unauthenticated, as plain HTTP —
`GET /skill.md`, then `GET /skill/<name>.md` — for a client that speaks no MCP.

## The rules

These hold everywhere. Each area guide cites them by name instead of restating them.

**Workspace boundaries.** Your key is pinned to ONE workspace; content in other workspaces is
absent, not hidden — it resolves as not-found. Datasources are registered once and granted to
workspaces; you see exactly your workspace's grants. A name you did not get from a listing is
not-found, never "forbidden" — a listing is the only truth about what exists, so never guess a
name: a guess cannot succeed and tells you nothing. Folder roots are organising claims, not
permissions.

**Your key and your role.** Your MCP key carries one role, fixed when the key was created: a
**viewer** reads everything, runs pipelines, introspects and tests connections; an **author**
additionally creates and changes pipelines and templates, renders, probes, records facts,
registers lake tables and publishes endpoints; a **promoter** reads and introspects but runs
nothing. The key does not follow its creator's role. `auth.role_required` means THIS key's role
is short — it is not retryable with this key or any other; ask whoever can create a key of the
needed role on the Keys page (no key is minted at sign-in). Each tool's permission is in its
area's tools reference.

**Humans register datasources.** No credential travels through an agent — a secret passed to
you transits your context and transcript, which no server can undo. Datasource create, update
and delete are UI/REST-only: ask a person to add the datasource, then read it back with
`datasources_list`.

**Draft and release.** Everything you author is a DRAFT — a create included — and lands
executable immediately; a human releases it from the UI. There is no release tool, and the
absence is deliberate: never say "released" or "live" about your own work — say it awaits
review. Retirement (discard, purge, switch) and promotion (moving releases between
deployments) are human actions too; no tool performs either. A receiver deployment refuses
authoring writes with `pipeline.authoring.disabled` — that refusal is the system working; the
fix belongs in the authoring environment. Because humans edit alongside you, every read
returns a `body_hash` and every write requires the `expected_hash` you read — read, edit,
write with the hash you read; a blind retry after a conflict overwrites a human's edit. A save
whose body equals the released one is a no-op success, not an error.

**Parameter safety.** A declared parameter appears in SQL as `:name` — bound, never parsed as
SQL. `${name}` is the interpolation syntax and is for STRUCTURE only (table names, dynamic
`IN` lists, `ORDER BY` fragments), never a caller-supplied value; a save refuses the
interpolated form around a declared parameter (`template.validation.parameter_interpolated`).
Never put secrets in templates or descriptions — credentials live encrypted on the datasource
entity, and SQL bodies are visible to everyone with read.

**Error recovery.** Every failure carries a catalogued code — `core-error-codes` says what each
calls for. A validation error is YOUR bug: fix the document, never resend it unchanged.
Reachability and TTL errors are the world's state: probe, then retry once. A failed execution's
root cause is the LAST entry of `error.exception.caused_by`, and you escalate quoting
`error.correlation_id`. Three identical failures mean stop and report — a fourth identical
call changes nothing.

**Naming.** A pipeline or template name is a folder path: 2–10 lower-case `/`-separated
segments, each starting `[a-z0-9]` and continuing `[a-z0-9_.-]`, ≤ 64 characters per segment,
≤ 200 total. A folder is REQUIRED — `test/active_users`, never `active_users`; the refusal's
`details.reason` says `folder_required` (you forgot the folder) or `grammar` (bad character).
There is no rename: choose the folder at creation. List the roots first — `pipelines_list` and
`templates_list` with `{"prefix": ""}` — reuse one, and ASK the person before minting a new
root: a new root is refused with `pipeline.validation.new_root_requires_confirmation` /
`template.validation.new_root_requires_confirmation` (`details.existing_roots`) until you pass
`confirm_new_root: true` on the FIRST create of
either kind (`test/` never needs it). Scratch lives under `test/`. `prefix` browses one level;
`q` searches flat across full paths. A pipeline and the templates it reads share a prefix;
shared macros live under `<owner>/lib/`.

## The areas

Open exactly the guide the task needs, then its references.

- **`pipelines`** — authoring or changing a pipeline: the workflow, the DAG, the checks.
- **`executions`** — runs: execute, read and page results, cancel, diagnose a failure.
- **`templates`** — the SQL a node runs: template anatomy, dialects, libraries, calculators.
- **`transforms`** — the logic does not fit SQL: JSONata transforms with contracts and tests.
- **`datasources`** — a first call, a new datasource, introspection, learned facts.
- **`lake`** — the data is Parquet or Iceberg on S3, not in a database.
- **`endpoints`** — a released read-only pipeline must answer a plain HTTP GET.

Each area's tools reference is named `<area>-tools` (`pipelines-tools`, `datasources-tools`, …)
— generated from the shipped tools' own descriptions, so it cannot drift. When a tool answered
`isError: true`, open **`core-error-codes`**: every catalogued code, its HTTP status and the
server's message.
