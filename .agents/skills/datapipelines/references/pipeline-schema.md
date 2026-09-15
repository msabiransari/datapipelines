# The pipeline JSON document

Open when you are writing or reading a pipeline body: the parameter block's fields and types, and a minimal complete pipeline to copy.

Part of the `datapipelines` skill — the operating core is `SKILL.md` beside this file.

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
the expectation holds for every input — `authoring-playbook.md` §5 lays out the two check
shapes and the output reconciliation that is not a check.

## The `current_version` pointer (read it honestly)

Responses carry `current_version` — the version every dependent (a published endpoint, promotion) runs. Five lines:

1. It is NULL until a human releases, and after the release it named was discarded with no survivor.
2. It moves only on: release, discard-of-the-named-version, restore-above-it, a human's manual switch.
3. It names the highest *eligible* live version when it moves — a draft only on a development server.
4. An import NEVER moves it; a human switches after reviewing.
5. Running with no version is NOT a pointer read — it runs your draft when one exists.
