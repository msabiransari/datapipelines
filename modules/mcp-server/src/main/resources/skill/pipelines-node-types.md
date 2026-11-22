---
area: pipelines
layer: reference
purpose: Wiring the DAG — what a node declares, the node types, and where a DQL or DML node's rows go.
---

# Nodes and output targets

**Node** — one SQL step, rendered from a template:
- `id` — unique within the pipeline, `[a-z0-9_]+`, stable
- `type` — `DQL` (SELECT → rows), `DML` (a declared `insert`/`update`/`delete` → result rows, below),
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
- `output` — where a DQL or DML node's rows go (see below); forbidden on DDL
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

**DML nodes** declare `operation` and a required `output` — `tempdb`, `caller` or `{"target": "discard"}`
(the write is the only effect); never `datasource`:
- `"operation": "insert"` also declares `"key": {"column": "order_id", "type": "BIGINTEGER"}` (the one
  key column the database returns; `INTEGER`, `BIGINTEGER` or `STRING`) and one `input`:
  `{"parameters": ["customer", "total"]}` (declared parameters, one row) or `{"table": "stg_lines"}`
  (a staged table from a node it depends on, one insert per row). The template binds each input field
  as `:field` and the key as `:id` — null asks the database to generate it. Each input row comes back
  with its `id` (named `id` whatever the key column is called; `BIGINTEGER` as a string). Stage it with
  `{"target": "tempdb", "table": "new_order"}`; a dependent DQL node selects `id AS order_id` into the
  table a line-item insert reads (an input field named `id` is always that insert's OWN key).
  Write exactly one row per execution: a key that is missing, duplicated or rewritten by a trigger fails
  the node (`pipeline.node.dml_key_correlation_failed`) and keeps no row. Inserts run on PostgreSQL,
  H2, DuckDB and tempdb; other dialects are refused (`pipeline.node.dml_insert_unqualified`).
- `"operation": "update"` / `"delete"` return one row, `affected_rows` (also for zero).
- `pipelines_execute_node` refuses an insert whose input is a staged table (`staged_input`); run the
  pipeline with `pipelines_execute`.
