# Transforms — JSONata templates with a contract, invariants and tests

Open when a pipeline's logic does not fit SQL: a nested shape, per-row business logic, or a
value SQL cannot express. A **transform template** is a pure function over staged data with a
declared contract and a test suite — write the empty and wrong-data cases before the
right-data one.

Part of the `datapipelines` skill — the operating core is `SKILL.md` beside this file.

## The two types

A template's `type` is `jsonata` or `javascript` (the transform types) beside `sql`/`html`.
A transform sets `engine: "none"` (evaluated, never rendered) and takes **no** `dialect`, no
`imports`, no `is_library`; a Freemarker construct (`${`, `<#`, `<@`) in its body is refused
with `template.validation.freemarker_forbidden`. `javascript` exists in the vocabulary but is
refused at save with `transform.js.unavailable` until round two — author `jsonata` today.

`templates_render` refuses a transform type with `template.render_not_applicable`; its probe
is **`templates_evaluate`** (`{ id, version?, input, now? }` → `{ output, rejects, invariants }`),
and a successful evaluate of a draft counts as its render for the render-before-you-run check.
A `javascript` or `sql`/`html` id passed to evaluate is `template.contract_invalid`
(`rule: type_not_transform`); an unknown one is `template.not_found`.

## The three blocks (required on every transform version)

`contract`, `invariants`, `tests` — bound strictly (a typo is `template.contract_invalid`,
`rule: unknown_field`, never a silent drop) and inside the version's `body_hash`.

```json
{
  "contract": {
    "mode": "row",
    "inputs": {
      "orders": { "kind": "table", "columns": [
        { "name": "order_id", "type": "INTEGER" },
        { "name": "amount_cents", "type": "INTEGER" },
        { "name": "customer_id", "type": "STRING", "nullable": true } ] },
      "tz": { "kind": "value", "type": "STRING" }
    },
    "output": { "kind": "table", "columns": [
      { "name": "order_id", "type": "INTEGER" },
      { "name": "amount", "type": "DECIMAL", "precision": 12, "scale": 2 },
      { "name": "customer_id", "type": "STRING" } ] },
    "rejects": true
  },
  "invariants": [
    { "name": "one_to_one",
      "expr": "$count(rows) + $count(rejects) = $count(inputs.orders)",
      "message": "every input row is accepted or rejected, never lost" }
  ],
  "tests": [
    { "name": "empty input",
      "input": { "rows": [], "inputs": { "tz": "UTC" } },
      "expect": { "output": { "rows": [], "rejects": [] } } },
    { "name": "wrong shape is refused",
      "input": { "rows": [ { "order_id": "x" } ], "inputs": { "tz": "UTC" } },
      "expect": { "refusal": "pipeline.transform.input_contract_violation" } }
  ]
}
```

- **`mode`** — `row` (the body reads `rows`, one batch at a time; exactly ONE table input),
  `table` or `value` (the body reads `inputs.<name>`; the output is one value or
  `{ "kind": "object" }`). Mismatches: `row_mode_inputs`, `mode_output_mismatch`,
  `rejects_without_table`.
- **Types are declared, never inferred.** Names are `LogicalType` wire names (`INTEGER`,
  `BIGINTEGER`, `DECIMAL`, `BIGDECIMAL`, `STRING`, `BOOLEAN`, `DATE`, `TIME`, `TIMESTAMP`;
  `BINARY`/`NULL` refused, `type_unsupported`). Wire forms: `BIGINTEGER` and `BIGDECIMAL` are
  JSON **strings**; `DECIMAL` is a JSON **number** and an output is ROUNDED half-even to its
  declared scale; precision/scale follow the type system's rules (`precision_scale_invalid`).
- **`tests`** — at least one case whose every table input and `rows` are empty
  (`empty_case_missing`), and in `row` mode a case lists NO table under `inputs`
  (`row_case_lists_table`). `expect` is exactly one of `output` (compared after
  canonicalisation — key order and decimal spelling never decide equality) or `refusal: "<code>"`
  (`expect_shape`). A case may pin the clock with `"now": "<ISO-8601>"`; absent, `$now()` and
  `$millis()` refuse (the clock is an input, not an ambient read).
- **`invariants`** — JSONata over `{ rows, rejects, inputs }`, must be `true` on every case
  (and every real execution). Compiled at save (`invariant_invalid`); in row mode the table
  input appears under its contract name.

## The authoring loop

1. `templates_create` with the type, the body, and the three blocks — **save runs the suite**:
   every case on the bounded evaluation pool, output gated, invariants checked. The first
   failure is `template.test_failed` naming the case (the diff is bounded; a wrong refusal
   code is a failure too).
2. Iterate with `templates_evaluate` per case — same engine, same pool, same input check —
   and fix what it surfaces. `templates_get` returns the blocks back.
3. A human releases from the UI; **release re-runs the suite** on the version being released.

Refusal codes you will meet: `template.validation.freemarker_forbidden` (detail `imports` /
`is_library` / `body`), `template.contract_invalid` (detail names the rule),
`template.invariant_invalid`, `template.test_failed`, `template.blocks_not_allowed` (the
blocks on `sql`/`html`), `template.render_not_applicable`, `transform.js.unavailable`, and
the engine's own (`pipeline.transform.evaluation_failed` / `timeout` / `resource_limit` /
`pool_exhausted`), the input check's `pipeline.transform.input_contract_violation`, and the
type gate's `pipeline.transform.row_shape_mismatch` / `value_type_mismatch` /
`precision_lost` / `value_too_large`. The bounds: one case ≤
`datapipelines.transform.evaluate-timeout-seconds`, the suite ≤ `suite-timeout-seconds`;
time catches recursion, depth catches nesting.

## The node

lane 7c fills this section.
