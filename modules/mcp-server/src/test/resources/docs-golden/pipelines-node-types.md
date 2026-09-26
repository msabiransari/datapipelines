
# Nodes and output targets

**Node** — one SQL step, rendered from a template:
- `id` — unique within the pipeline, `[a-z0-9_]+`, stable
- `type` — `DQL` (SELECT → rows), `DML` (INSERT/UPDATE/DELETE/MERGE → row count),
  `DDL` (CREATE/ALTER/DROP → success/failure), `PIPELINE` (run a pinned child
  pipeline `{"name": "...", "version": N}` as a sub-execution — declares `pipeline`
  plus optional parameter bindings instead of `source`/`template`), `CALCULATOR`
  (compute one typed value — or, on a multi-output kind, a named set of them — into
  the Context — see `templates-calculators`), or `TRANSFORM` (evaluate a pinned
  `jsonata` template as a pure function over staged data — `inputs`, `output`,
  `strict`, never `source`; see `transforms-evaluation`)
- `source` — a registered datasource name, or the reserved literal `"tempdb"` for the
  per-execution in-memory H2
- `template` — `{"id": "...sql", "version": N}` (immutable pin)
- `output` — where a DQL node's rows go (see below); forbidden on DML/DDL
- `depends_on` — parent node ids; must exist, no cycles. Data flow, nothing else
  (`pipelines-dag`).

**Output targets (DQL only):**
- omitted → `{"target": "caller"}` — the pipeline's result. **At most one caller node per
  pipeline; zero is legal** (pure write-back: stats only, no rows)
- `{"target": "tempdb", "table": "stg_x"}` — stage into H2 for downstream nodes
  (`source: "tempdb"`); keep table names lower_snake_case (H2 lower-folds unquoted
  identifiers)
- `{"target": "datasource", "datasource": "...", "table": "...", "mode": "replace"|"append"}`
  — write-back to an external table (must exist, or be created by a preceding DDL node)
