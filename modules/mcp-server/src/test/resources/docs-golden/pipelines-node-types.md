
# Nodes and output targets

**Node** — one SQL step, rendered from a template:
- `id` — unique within the pipeline, `[a-z0-9_]+`, stable
- `type` — `DQL` (SELECT → rows), `DML` (INSERT/UPDATE/DELETE/MERGE → row count),
  `DDL` (CREATE/ALTER/DROP → success/failure), `PIPELINE` (run a pinned child
  pipeline `{"name": "...", "version": N}` as a sub-execution — declares `pipeline`
  plus optional parameter bindings instead of `source`/`template`), `CALCULATOR`
  (compute one typed value — or, on a multi-output kind, a named set of them — into
  the Context — see `templates-calculators`), or `SCRIPT` (evaluate a pinned
  `jsonata` template as a pure function over staged data — `inputs`, `output`,
  `strict`, never `source`; see `scripts-evaluation`)
- `source` — a registered datasource name, or the reserved literal `"tempdb"` for the
  per-execution staging engine (H2 by default, DuckDB when selected)
- `template` — `{"id": "...sql", "version": N}` (immutable pin)
- `output` — where a DQL node's rows go (see below); forbidden on DML/DDL
- `delivery` — one DQL/tempdb node may name `{feed, id}` to read an immutable managed-lake delivery;
  requires DuckDB and reads `FROM delivery`. See the `lake` guide.
- `depends_on` — parent node ids; must exist, no cycles. Data flow and the managed-lake publication barrier
  (`pipelines-dag`).

**Output targets (DQL only):**
- omitted → `{"target": "caller"}` — the pipeline's result. **At most one caller node per
  pipeline; zero is legal** (pure write-back: stats only, no rows)
- `{"target": "tempdb", "table": "stg_x"}` — stage into the selected engine for downstream nodes
  (`source: "tempdb"`); keep table names lower_snake_case
- `{"target": "datasource", "datasource": "...", "table": "...", "mode": "replace"|"append"}`
  — write-back to an external table (must exist, or be created by a preceding DDL node).
  The configured managed lake supports only append to an ACTIVE `namespace.table`, under a delivery and its
  publication barrier; external LAKE datasources stay read-only. See the `lake` guide.
