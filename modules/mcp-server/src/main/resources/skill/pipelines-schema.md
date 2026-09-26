---
area: pipelines
layer: reference
purpose: Writing or reading a pipeline body: the parameter block's fields and types, and a minimal complete pipeline to copy.
---

# The pipeline JSON document

Open when you are writing or reading a pipeline body: the parameter block's fields and types, and a minimal complete pipeline to copy.

Part of the served manual — the operating core is the document `core`, and every document
answers to `docs_get` by its name here.

**Parameters** — typed with the canonical logical types: `BOOLEAN`, `INTEGER`,
`BIGINTEGER`, `DECIMAL`, `BIGDECIMAL`, `STRING`, `DATE`, `TIMESTAMP`, etc. (11 total —
see type-system.md §3). Each declares `type`, `required`, optional `default`,
`description`. **`DECIMAL` parameters must also declare `precision`** — omitting it
fails the save with `pipeline.validation.parameter_precision_missing`; `BIGDECIMAL`
precision is optional (omitted = unbounded).

Minimal single-node pipeline (Postgres source, the single DQL node IS the caller node):

```json
{
  "schema_version": 1,
  "name": "acme/reporting/active_users",
  "display_name": "Active Users",
  "description": "List all active users from local PG",
  "parameters": {},
  "nodes": [{
    "id": "fetch_active_users",
    "description": "Fetch active users",
    "type": "DQL",
    "source": "pg-local",
    "template": {"id": "acme/reporting/active_users.sql", "version": 1},
    "depends_on": []
  }]
}
```

**TRANSFORM nodes** — `type: "TRANSFORM"` pins a `jsonata` template and conforms to the pinned
contract: `inputs` maps each contract input to a staged table or a `"$context_key"`, `strict`
fails the node on any reject, and `output` is `{ "target": "tempdb", "table", "rejects"? }` or
`{ "target": "caller" }` in `row`/`table` mode, or absent in `value` mode (the node writes
`context_key` instead, bindable downstream as `:context_key`). See `transforms`:

```json
{
  "id": "shape_orders",
  "type": "TRANSFORM",
  "template": {"id": "acme/shape/order_lines.jsonata", "version": 3},
  "inputs": {"orders": "stg_orders", "tz": "$org_timezone"},
  "output": {"target": "tempdb", "table": "order_lines", "rejects": "order_lines_rejected"},
  "strict": false,
  "depends_on": ["stage_orders"]
}
```

**Timeouts** — a pipeline may declare `settings.query_timeout_seconds` (the default SQL
statement timeout for every DQL/DML/DDL node that sets none of its own), and any DQL/DML/DDL
node may declare its own `settings.query_timeout_seconds`, overriding the pipeline's. Precedence:
node > pipeline > the datasource's own `query_timeout_seconds` > the operator's per-dialect
default > the flat application default. Distinct from `settings.timeout_seconds` (a node's own
WALL-CLOCK deadline, render through materialize) — a node's `query_timeout_seconds` bounds one
SQL statement and must not exceed that same node's own `timeout_seconds`, or the save is refused
naming both numbers. Illegal on `PIPELINE`/`CALCULATOR` nodes, which run no statement:

```json
"settings": { "query_timeout_seconds": 300 },
"nodes": [{
  "id": "scan_trips",
  "type": "DQL",
  "settings": { "timeout_seconds": 600, "query_timeout_seconds": 300 }
}]
```

**Checks** — optional top-level `checks[]`: server-run cross-checks that gate a human's
release (pipeline-contract §3.3). You write the query and the expectation; only the server's
own run produces `observed`. One check:

```json
"checks": [{
  "id": "orders_total_reconciles",
  "name": "Orders total for the window, from the raw orders table",
  "datasource": "pg-local",
  "sql": "SELECT ... ",
  "expected": {"kind": "value", "value": 74.62, "tolerance": 0.01}
}]
```

`expected.kind` is `value` (numeric, ± `tolerance`, default 0), `range` (`min`..`max`
inclusive) or `rows` (exact row count). `:name` binds come from the pipeline's declared
parameters only; `${}` is refused (a check has no rendering); `tempdb` is not a check
datasource. Expectations are STATIC, and the release gate binds the declared defaults:
label a baseline-specific expectation in the check's `name`, and bind parameters only when
the expectation holds for every input — `pipelines-authoring` §5 lays out the two check
shapes and the output reconciliation that is not a check.

## The `current_version` pointer (read it honestly)

Responses carry `current_version` — the version every dependent (a published endpoint, promotion) runs. Five lines:

1. It is NULL until a human releases, and after the release it named was discarded with no survivor.
2. It moves only on: release, discard-of-the-named-version, restore-above-it, a human's manual switch.
3. It names the highest *eligible* live version when it moves — a draft only on a development server.
4. An import NEVER moves it; a human switches after reviewing.
5. Running with no version is NOT a pointer read — it runs your draft when one exists.
